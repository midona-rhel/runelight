package com.midonarhel.runelight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The static point lights, baked into a light map: per storey, a grid of quarter-tile cells holding the light that
 * reaches the cell from all the baked lights together (LightMapBake works one light out; the composite, the fog and
 * the weather read the map instead of looping over the lights). What a cell holds, in two layers per storey:
 *
 * - A, filtered: the summed colour of the lights, each its colour times its strength times its share of the cell; and
 *   the dominant light's share of it (the weight of the brightest of them), in alpha.
 * - B, read as it is: the dominant light's table index, its place relative to the cell's middle (x, z) and its height
 *   (world y). The shading takes its direction and its vertical falloff from it, and its flicker from the table
 *   (light_map.glsl): the table holds, for each baked light, how strong it is now against how strong it was baked, and
 *   its radius. So only the dominant light of a cell flickers, and a pulse changes its strength but not its radius.
 *
 * The bakes run on background threads, nearest light first, and the cells are put together by 32 x 32 blocks from the
 * lights' results, again on the threads; the render thread only sends the finished blocks (the nearest first, a few a
 * frame, update) and learns from that when a light is live: when every block it is in has been sent. Until then the
 * light is the clusters', so nothing pops to black; and a light that goes is taken out of the blocks before its table
 * entry is reused.
 *
 * Changes: when the walls, the solid objects or the models near a light change (doors, spawns), the lights whose box
 * holds the change are baked again (setBlockers). A light that is night-only, or has a night multiplier, is put into the
 * blocks at its strength for the night as of sixteen steps of it; between the steps, and for flicker, the table's
 * strength against the baked one makes up the difference for the dominant light only.
 */
final class LightMap
{
	private static final Logger log = LoggerFactory.getLogger(LightMap.class);

	/** Cells per block side. */
	static final int BLOCK = 32;
	/** Table entries; 0 is no light. */
	static final int TABLE_TEXELS = 2048;
	static final int STORIES = WallMap.PLANES;
	/** The steps the night is quantized to for the baked strengths. */
	static final int NIGHT_STEPS = 16;
	/** A light further than this (world units) outside the view's reach is let go of. */
	static final float EVICT_MARGIN = 2560;

	/** A static light: where it is, what it is like, and what the map knows of it. */
	static final class Source
	{
		final int plane;
		final float x, y, z, radius;
		final float red, green, blue, strength;
		final boolean nightOnly;
		final float nightMultiplier;

		// guarded by the map's lock, except live
		int id;
		boolean registered;
		boolean retired;
		int generation;
		LightMapBake.Grid[] grids;
		int[] needKeys = new int[0], needSeqs = new int[0];
		// whether the map carries the light: its blocks have been sent
		volatile boolean live;

		Source(int plane, float x, float y, float z, float radius, float red, float green, float blue, float strength,
			boolean nightOnly, float nightMultiplier)
		{
			this.plane = plane;
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
			this.red = red;
			this.green = green;
			this.blue = blue;
			this.strength = strength;
			this.nightOnly = nightOnly;
			this.nightMultiplier = nightMultiplier;
		}

		boolean nightDependent()
		{
			return nightOnly || nightMultiplier != 1;
		}

		/** The strength the light is baked with at a night (0 to 1). */
		float baked(float night)
		{
			float s = strength * (1 + (nightMultiplier - 1) * night);
			return nightOnly ? s * night : s;
		}
	}

	/** A block ready to send. */
	static final class Block
	{
		final int key, plane, bx, bz, seq;
		// RGBA half floats of the two layers, BLOCK * BLOCK * 4 each; null when the block holds no light
		final short[] a, b;

		Block(int key, int plane, int bx, int bz, int seq, short[] a, short[] b)
		{
			this.key = key;
			this.plane = plane;
			this.bx = bx;
			this.bz = bz;
			this.seq = seq;
			this.a = a;
			this.b = b;
		}
	}

	interface Sink
	{
		/** Send a block into the textures; a null a (and b) clears it. */
		void block(int plane, int bx, int bz, short[] a, short[] b);
	}

	/** Work for a thread: lowest priority first. */
	private static final class Task implements Comparable<Task>
	{
		final float priority;
		final Runnable run;

		Task(float priority, Runnable run)
		{
			this.priority = priority;
			this.run = run;
		}

		@Override
		public int compareTo(Task o)
		{
			return Float.compare(priority, o.priority);
		}
	}

	private final Object lock = new Object();
	private final PriorityBlockingQueue<Task> tasks = new PriorityBlockingQueue<>();
	private final List<Thread> threads = new ArrayList<>();
	private final int threadCount;
	private volatile boolean stopped;

	// guarded by lock
	private LightBlockers blockers;
	private int blocksPerSide;
	private int[] seq = new int[0];
	private boolean[] combinePending = new boolean[0];
	private final List<Source> sources = new ArrayList<>();
	private final ArrayDeque<Integer> freeIds = new ArrayDeque<>();
	// the table entries of lights that were taken off, with the blocks that must be sent before they are free: id,
	// then the keys and the seqs
	private final List<int[]> releases = new ArrayList<>();
	private int night;
	private final Map<Integer, Block> ready = new HashMap<>();
	// render thread
	private int[] uploaded = new int[0];
	private boolean[] onGpu = new boolean[0];
	private float focusX, focusZ;

	// the table: per id, (strength now over the baked, radius, 0, 0)
	private final float[] table = new float[TABLE_TEXELS * 4];
	private int tableUsed = 1;

	private final AtomicInteger queued = new AtomicInteger(), finished = new AtomicInteger();
	private final AtomicLong bakeNanos = new AtomicLong(), bakeVisits = new AtomicLong(), combined = new AtomicLong();
	private volatile long batchStart;

	/** @param threads the baking threads; 0 for none: the work is then done by {@link #runTasks} */
	LightMap(int threads)
	{
		threadCount = threads;
		resetIds();
	}

	private void resetIds()
	{
		freeIds.clear();
		for (int i = 1; i < TABLE_TEXELS; ++i)
		{
			freeIds.add(i);
		}
	}

	static int defaultThreads()
	{
		return Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
	}

	void free()
	{
		stopped = true;
		for (Thread t : threads)
		{
			t.interrupt();
		}
		threads.clear();
		tasks.clear();
	}

	private void submit(float priority, Runnable run)
	{
		if (threadCount > 0 && threads.isEmpty())
		{
			stopped = false;
			for (int i = 0; i < threadCount; ++i)
			{
				Thread t = new Thread(this::work, "RuneLight light map");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				t.start();
				threads.add(t);
			}
		}
		tasks.add(new Task(priority, run));
	}

	private void work()
	{
		while (!stopped)
		{
			try
			{
				tasks.take().run.run();
			}
			catch (InterruptedException ex)
			{
				return;
			}
			catch (Throwable ex)
			{
				log.warn("Light map task failed", ex);
			}
		}
	}

	/** Do all the queued work here, for tests (a map made without threads). */
	void runTasks()
	{
		Task t;
		while ((t = tasks.poll()) != null)
		{
			t.run.run();
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Client thread

	/**
	 * What stops light now. The lights near what changed since the last are baked again; a scene of another size starts
	 * the map over.
	 */
	void setBlockers(LightBlockers next)
	{
		synchronized (lock)
		{
			LightBlockers old = blockers;
			if (next == old)
			{
				return;
			}
			blockers = next;
			if (next == null)
			{
				return;
			}
			int perSide = next.cells / BLOCK;
			if (perSide != blocksPerSide)
			{
				// another size of scene: nothing of the old is on the map
				dropAll();
				blocksPerSide = perSide;
				seq = new int[STORIES * perSide * perSide];
				combinePending = new boolean[seq.length];
				uploaded = new int[seq.length];
				onGpu = new boolean[seq.length];
			}
			if (old == null || old.size != next.size || old.offset != next.offset)
			{
				for (Source s : sources)
				{
					rebake(s);
				}
				return;
			}
			int[] rect = changedCells(old, next);
			if (rect == null)
			{
				return;
			}
			int off = next.offset * LightBlockers.CELLS_PER_TILE;
			for (Source s : sources)
			{
				if (s.registered && !s.retired && touches(s, rect, off))
				{
					rebake(s);
				}
			}
		}
	}

	/** The rectangle of cells (x0, z0, x1, z1) that differ between two snapshots, null when none. */
	private static int[] changedCells(LightBlockers a, LightBlockers b)
	{
		int[] walls = a.walls == b.walls ? null : b.walls.diff(a.walls);
		int[] models = a.models == b.models ? null : b.models != null ? b.models.diff(a.models) : a.models.diff(null);
		int[] rect = null;
		int n = LightBlockers.CELLS_PER_TILE;
		if (walls != null)
		{
			// a wall is on the edge of its tile: the cells of the tiles next to it too
			rect = new int[]{(walls[0] - 1) * n, (walls[1] - 1) * n, (walls[2] + 2) * n - 1, (walls[3] + 2) * n - 1};
		}
		if (models != null)
		{
			rect = rect == null ? models : new int[]{Math.min(rect[0], models[0]), Math.min(rect[1], models[1]),
				Math.max(rect[2], models[2]), Math.max(rect[3], models[3])};
		}
		return rect;
	}

	/** Whether a light's reach (its box) holds any of the cells of the rectangle, a storey apart or not. */
	private static boolean touches(Source s, int[] rect, int off)
	{
		float r = Math.min(s.radius, LightMapBake.MAX_RADIUS) / LightMapBake.CELL;
		float cx = s.x / LightMapBake.CELL + off, cz = s.z / LightMapBake.CELL + off;
		return rect[2] >= cx - r - 1 && rect[0] <= cx + r + 1 && rect[3] >= cz - r - 1 && rect[1] <= cz + r + 1;
	}

	/**
	 * The night (0 to 1) as of now. Lights that depend on it are put into the blocks again when it has moved on by a
	 * step of NIGHT_STEPS.
	 */
	void setNight(float value)
	{
		int step = Math.round(Math.max(0, Math.min(1, value)) * NIGHT_STEPS);
		synchronized (lock)
		{
			if (step == night)
			{
				return;
			}
			night = step;
			for (Source s : sources)
			{
				if (s.nightDependent() && s.grids != null && !s.retired)
				{
					dirty(s.grids, null);
				}
			}
		}
	}

	/** Where the view is, for working nearest first. */
	void setFocus(float x, float z)
	{
		focusX = x;
		focusZ = z;
	}

	/**
	 * Put a light on the map: it is baked in the background and becomes live when its blocks are sent. Safe to repeat.
	 *
	 * @return false when there is no room for it: it stays with the clusters
	 */
	boolean add(Source s)
	{
		synchronized (lock)
		{
			if (s.registered)
			{
				return !s.retired;
			}
			if (s.retired || freeIds.isEmpty())
			{
				return false;
			}
			s.registered = true;
			s.id = freeIds.poll();
			tableUsed = Math.max(tableUsed, s.id + 1);
			table[s.id * 4] = 1;
			table[s.id * 4 + 1] = s.radius;
			sources.add(s);
			if (blockers != null)
			{
				rebake(s);
			}
			return true;
		}
	}

	/**
	 * Take a light off the map: its share leaves the blocks, and its table entry is free once they are sent.
	 *
	 * @param permanent the light is gone for good; else it can be added again
	 */
	void remove(Source s, boolean permanent)
	{
		synchronized (lock)
		{
			if (permanent)
			{
				s.retired = true;
			}
			if (!s.registered)
			{
				return;
			}
			s.registered = false;
			s.live = false;
			s.generation++;
			sources.remove(s);
			int[] keys = dirty(s.grids, null);
			int[] seqs = new int[keys.length];
			for (int i = 0; i < keys.length; ++i)
			{
				seqs[i] = seq[keys[i]];
			}
			int[] release = new int[1 + keys.length * 2];
			release[0] = s.id;
			System.arraycopy(keys, 0, release, 1, keys.length);
			System.arraycopy(seqs, 0, release, 1 + keys.length, keys.length);
			releases.add(release);
			s.grids = null;
			s.id = 0;
		}
	}

	/** Forget every light, for a new scene: the blocks are cleared. */
	void clear()
	{
		synchronized (lock)
		{
			dropAll();
		}
	}

	private void dropAll()
	{
		for (Source s : sources)
		{
			// they can come back
			s.registered = false;
			s.live = false;
			s.generation++;
			s.grids = null;
			s.id = 0;
		}
		sources.clear();
		resetIds();
		tableUsed = 1;
		releases.clear();
		ready.clear();
		// whatever the textures hold is cleared
		for (int k = 0; k < onGpu.length; ++k)
		{
			if (onGpu[k])
			{
				seq[k]++;
				schedule(k);
			}
		}
	}

	/**
	 * Say how strong the light is now: its table entry holds this against the strength it is baked with.
	 *
	 * @param intensity colour is multiplied by this (flicker, pulse, night, fades, all of it)
	 */
	void setLevel(Source s, float intensity)
	{
		if (!s.registered || s.retired)
		{
			return;
		}
		float base = s.baked(night / (float) NIGHT_STEPS);
		table[s.id * 4] = base > 1e-6f ? intensity / base : 0;
	}

	/** Whether the map carries the light, so the clusters mustn't. */
	static boolean isLive(Source s)
	{
		return s.live;
	}

	/** The table, for the shaders: per id (strength now over baked, radius, 0, 0). */
	float[] table()
	{
		return table;
	}

	/** The table's entries in use: ids below this. */
	int tableUsed()
	{
		return tableUsed;
	}

	/** Whether any light is live. */
	boolean hasLive()
	{
		synchronized (lock)
		{
			for (Source s : sources)
			{
				if (s.live)
				{
					return true;
				}
			}
			return false;
		}
	}

	/** Cells per side of the map's scene, 0 without one. */
	int cells()
	{
		LightBlockers b = blockers;
		return b != null ? b.cells : 0;
	}

	int offsetCells()
	{
		LightBlockers b = blockers;
		return b != null ? b.offset * LightBlockers.CELLS_PER_TILE : 0;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Bakes

	private void rebake(Source s)
	{
		int gen = ++s.generation;
		float dx = s.x - focusX, dz = s.z - focusZ;
		if (queued.getAndIncrement() == finished.get())
		{
			batchStart = System.nanoTime();
		}
		submit(dx * dx + dz * dz, () -> bake(s, gen));
	}

	private final ThreadLocal<LightMapBake.Scratch> scratch = ThreadLocal.withInitial(LightMapBake.Scratch::new);

	private void bake(Source s, int gen)
	{
		LightBlockers b;
		synchronized (lock)
		{
			if (s.retired || s.generation != gen || blockers == null)
			{
				done();
				return;
			}
			b = blockers;
		}
		long start = System.nanoTime();
		LightMapBake.Result r = LightMapBake.bake(new LightMapBake.Light(s.plane, s.x, s.y, s.z, s.radius), b, scratch.get());
		long took = System.nanoTime() - start;
		bakeNanos.addAndGet(took);
		bakeVisits.addAndGet(r.cellVisits);
		if (log.isDebugEnabled())
		{
			log.debug("Light map: light at {}, {} (storey {}, radius {}) baked in {} ms, {} cell visits, {} storeys",
				Math.round(s.x), Math.round(s.z), s.plane, Math.round(s.radius), took / 1e6, r.cellVisits, r.grids.length);
		}
		synchronized (lock)
		{
			if (!s.retired && s.generation == gen)
			{
				dirty(s.grids, r.grids);
				s.grids = r.grids;
				s.needKeys = keysOf(r.grids);
				s.needSeqs = new int[s.needKeys.length];
				for (int i = 0; i < s.needKeys.length; ++i)
				{
					s.needSeqs[i] = seq[s.needKeys[i]];
				}
			}
		}
		done();
	}

	private void done()
	{
		if (finished.incrementAndGet() == queued.get() && log.isDebugEnabled())
		{
			log.debug("Light map: {} bakes done in {} ms of waiting, {} ms of work, {} cell visits; {} blocks put together",
				finished.get(), (System.nanoTime() - batchStart) / 1e6, bakeNanos.get() / 1e6, bakeVisits.get(), combined.get());
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Blocks

	private int key(int plane, int bx, int bz)
	{
		return (plane * blocksPerSide + bz) * blocksPerSide + bx;
	}

	private int[] keysOf(LightMapBake.Grid[] grids)
	{
		if (grids == null)
		{
			return new int[0];
		}
		int[] out = new int[16];
		int n = 0;
		for (LightMapBake.Grid g : grids)
		{
			for (int bz = g.z0 / BLOCK; bz <= (g.z0 + g.h - 1) / BLOCK; ++bz)
			{
				for (int bx = g.x0 / BLOCK; bx <= (g.x0 + g.w - 1) / BLOCK; ++bx)
				{
					if (bx < blocksPerSide && bz < blocksPerSide)
					{
						if (n == out.length)
						{
							out = Arrays.copyOf(out, n * 2);
						}
						out[n++] = key(g.plane, bx, bz);
					}
				}
			}
		}
		return Arrays.copyOf(out, n);
	}

	/** Every block of the grids has changed: bump it and have it put together. Returns the keys. */
	private int[] dirty(LightMapBake.Grid[] a, LightMapBake.Grid[] b)
	{
		int[] ka = keysOf(a), kb = keysOf(b);
		int[] all = Arrays.copyOf(ka, ka.length + kb.length);
		System.arraycopy(kb, 0, all, ka.length, kb.length);
		Arrays.sort(all);
		int n = 0;
		for (int i = 0; i < all.length; ++i)
		{
			if (i == 0 || all[i] != all[i - 1])
			{
				all[n++] = all[i];
			}
		}
		all = Arrays.copyOf(all, n);
		for (int k : all)
		{
			seq[k]++;
			schedule(k);
		}
		return all;
	}

	private void schedule(int k)
	{
		if (!combinePending[k])
		{
			combinePending[k] = true;
			// the blocks first: they are quick and the bakes wait for them
			submit(-1, () -> combine(k));
		}
	}

	private static final class Part
	{
		final LightMapBake.Grid g;
		final float r, gr, b;
		final int id;
		final float x, y, z;

		Part(LightMapBake.Grid g, Source s, float strength)
		{
			this.g = g;
			r = s.red * strength;
			gr = s.green * strength;
			b = s.blue * strength;
			id = s.id;
			x = s.x;
			y = s.y;
			z = s.z;
		}
	}

	private void combine(int k)
	{
		List<Part> parts = new ArrayList<>();
		int plane, bx, bz, seqNow, perSide, off;
		synchronized (lock)
		{
			combinePending[k] = false;
			if (blockers == null)
			{
				return;
			}
			perSide = blocksPerSide;
			plane = k / (perSide * perSide);
			bz = k / perSide % perSide;
			bx = k % perSide;
			seqNow = seq[k];
			off = blockers.offset * LightBlockers.CELLS_PER_TILE;
			float n = night / (float) NIGHT_STEPS;
			for (Source s : sources)
			{
				if (s.grids == null)
				{
					continue;
				}
				for (LightMapBake.Grid g : s.grids)
				{
					if (g.plane == plane && g.x0 < (bx + 1) * BLOCK && g.x0 + g.w > bx * BLOCK
						&& g.z0 < (bz + 1) * BLOCK && g.z0 + g.h > bz * BLOCK)
					{
						parts.add(new Part(g, s, s.baked(n)));
					}
				}
			}
		}
		Block block = build(k, plane, bx, bz, seqNow, off, parts);
		combined.incrementAndGet();
		synchronized (lock)
		{
			Block old = ready.get(k);
			if (old == null || old.seq <= block.seq)
			{
				ready.put(k, block);
			}
		}
	}

	private static Block build(int key, int plane, int bx, int bz, int seq, int off, List<Part> parts)
	{
		int n = BLOCK * BLOCK;
		float[] sum = new float[n * 3], best = new float[n];
		Part[] dominant = new Part[n];
		float[] total = new float[n];
		boolean any = false;
		for (Part p : parts)
		{
			LightMapBake.Grid g = p.g;
			int x0 = Math.max(g.x0, bx * BLOCK), x1 = Math.min(g.x0 + g.w, (bx + 1) * BLOCK);
			int z0 = Math.max(g.z0, bz * BLOCK), z1 = Math.min(g.z0 + g.h, (bz + 1) * BLOCK);
			for (int z = z0; z < z1; ++z)
			{
				for (int x = x0; x < x1; ++x)
				{
					float c = g.c[(z - g.z0) * g.w + x - g.x0];
					if (c <= 0)
					{
						continue;
					}
					int i = (z - bz * BLOCK) * BLOCK + x - bx * BLOCK;
					float lr = p.r * c, lg = p.gr * c, lb = p.b * c;
					sum[i * 3] += lr;
					sum[i * 3 + 1] += lg;
					sum[i * 3 + 2] += lb;
					float lum = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
					total[i] += lum;
					if (lum > best[i])
					{
						best[i] = lum;
						dominant[i] = p;
					}
					any = true;
				}
			}
		}
		if (!any)
		{
			return new Block(key, plane, bx, bz, seq, null, null);
		}
		short[] a = new short[n * 4], b = new short[n * 4];
		for (int j = 0; j < BLOCK; ++j)
		{
			for (int i = 0; i < BLOCK; ++i)
			{
				int c = j * BLOCK + i;
				a[c * 4] = Half.toHalf(sum[c * 3]);
				a[c * 4 + 1] = Half.toHalf(sum[c * 3 + 1]);
				a[c * 4 + 2] = Half.toHalf(sum[c * 3 + 2]);
				Part d = dominant[c];
				if (d != null)
				{
					a[c * 4 + 3] = Half.toHalf(total[c] > 0 ? best[c] / total[c] : 0);
					// the light's place from the cell's middle, in world units
					float cx = (bx * BLOCK + i + 0.5f - off) * LightMapBake.CELL, cz = (bz * BLOCK + j + 0.5f - off) * LightMapBake.CELL;
					b[c * 4] = Half.toHalf(d.id);
					b[c * 4 + 1] = Half.toHalf(d.x - cx);
					b[c * 4 + 2] = Half.toHalf(d.z - cz);
					b[c * 4 + 3] = Half.toHalf(d.y);
				}
			}
		}
		return new Block(key, plane, bx, bz, seq, a, b);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Render thread

	/**
	 * Send up to max finished blocks, the ones nearest the focus first, and bring the lights live whose blocks are all
	 * sent; table entries of lights that are gone and cleared are free again.
	 *
	 * @return the blocks sent
	 */
	int update(int max, Sink sink)
	{
		List<Block> send = null;
		synchronized (lock)
		{
			if (!ready.isEmpty())
			{
				send = new ArrayList<>(ready.values());
			}
		}
		int sent = 0;
		if (send != null)
		{
			int off = offsetCells();
			float fx = focusX, fz = focusZ;
			send.sort((p, q) -> Float.compare(distance(p, off, fx, fz), distance(q, off, fx, fz)));
			for (int i = 0; i < send.size() && sent < max; ++i)
			{
				Block bl = send.get(i);
				synchronized (lock)
				{
					if (ready.get(bl.key) != bl)
					{
						continue;
					}
					ready.remove(bl.key);
					if (bl.key >= uploaded.length)
					{
						continue;
					}
				}
				boolean empty = bl.a == null;
				if (!empty || onGpu[bl.key])
				{
					sink.block(bl.plane, bl.bx, bl.bz, bl.a, bl.b);
				}
				onGpu[bl.key] = !empty;
				uploaded[bl.key] = Math.max(uploaded[bl.key], bl.seq);
				sent++;
			}
		}
		synchronized (lock)
		{
			for (Source s : sources)
			{
				if (!s.live && s.grids != null && sent(s.needKeys, s.needSeqs))
				{
					s.live = true;
				}
			}
		}
		synchronized (lock)
		{
			for (int i = releases.size() - 1; i >= 0; --i)
			{
				int[] r = releases.get(i);
				int n = (r.length - 1) / 2;
				if (sent(Arrays.copyOfRange(r, 1, 1 + n), Arrays.copyOfRange(r, 1 + n, r.length)))
				{
					releases.remove(i);
					freeIds.add(r[0]);
				}
			}
		}
		return sent;
	}

	private boolean sent(int[] keys, int[] seqs)
	{
		for (int i = 0; i < keys.length; ++i)
		{
			if (keys[i] >= uploaded.length || uploaded[keys[i]] < seqs[i])
			{
				return false;
			}
		}
		return true;
	}

	private float distance(Block b, int off, float fx, float fz)
	{
		float x = (b.bx * BLOCK + BLOCK / 2f - off) * LightMapBake.CELL - fx, z = (b.bz * BLOCK + BLOCK / 2f - off) * LightMapBake.CELL - fz;
		return x * x + z * z;
	}

	/** Blocks put together and not sent yet, and bakes not done: zero when the map is up to date. */
	int pending()
	{
		synchronized (lock)
		{
			return ready.size() + queued.get() - finished.get() + tasks.size();
		}
	}
}
