package com.midonarhel.runelight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The static point lights, baked into a light map: per storey, a grid of quarter-tile cells holding the light that
 * reaches the cell from all the baked lights together (LightMapBake works one light out; the composite, the fog and
 * the weather read the map instead of looping over the lights). What a cell holds, in two layers per storey:
 *
 * - A, filtered: the summed colour of the lights, each its colour times its strength times its share of the cell; and
 *   how far the brightest of them is ahead of the next one (their luminances' difference), in alpha.
 * - B, filtered but for its alpha: where the lights are, as their mean place weighted by their luminance, premultiplied
 *   by it so that it filters: the sum of luminance times the place from the cell's middle (x, z) and the height (world
 *   y), in sixteenths of a unit; and, to be read as it is, the table index of the brightest light.
 *   The shading takes its direction and height from the mean place, and the flicker from the table (light_map.glsl):
 *   the table holds, for each baked light, how strong it is now against how strong it was baked. So only the brightest
 *   light of a cell flickers, by as much as it is ahead of the others (so that where the brightest changes from one
 *   light to another, nothing steps), and a pulse changes a strength but not a radius. Nothing read as it is is a
 *   range or a distance: a light's reach is in the cells alone, which fade to exactly zero at its radius.
 *
 * The bakes run on background threads, nearest light first, and the cells are put together by 32 x 32 blocks from the
 * lights' results, again on the threads; the render thread only sends the finished blocks (the nearest first, a few a
 * frame, update) and learns from that when a light is live: when every block it is in has been sent. Until then the
 * light is the clusters', so nothing pops to black; and a light that goes is taken out of the blocks before its table
 * entry is reused.
 *
 * Changes: when the walls, the solid objects or the models change (doors, spawns), the lights whose reach holds a cell
 * that differs are baked again (setBlockers), and one that comes out as it was changes nothing downstream. A light that
 * is night-only, or has a night multiplier, is put into the blocks at its strength for the night as of sixteen steps of
 * it; between the steps, and for flicker, the table's strength against the baked one makes up the difference for the
 * brightest light of a cell only.
 *
 * Each stretch of work (a scene load, a teleport, a door) is told in the log once it is all sent: the lights baked, the
 * time from the start until every block was sent, the work and the threads.
 */
final class LightMap
{
	private static final Logger log = LoggerFactory.getLogger(LightMap.class);

	/** Cells per block side. */
	static final int BLOCK = 32;
	/** The places in layer B are stored in this many units to the world unit (they are sums). */
	static final float PLACE_SCALE = 1 / 16f;
	/** Table entries; 0 is no light. */
	static final int TABLE_TEXELS = 2048;
	static final int STORIES = WallMap.PLANES;
	/** The steps the night is quantized to for the baked strengths. */
	static final int NIGHT_STEPS = 16;
	/** Work on this many lights is told in the log at info level, less at debug. */
	static final int INFO_LIGHTS = 16;
	/** A light further than this (world units) outside the view's reach is let go of. */
	static final float EVICT_MARGIN = 2560;
	/**
	 * How long a scene's first bakes wait for its models (setBlockers): baking before them means baking again, and the
	 * lights are the clusters' meanwhile.
	 */
	static final long MODEL_WAIT_NANOS = 1_000_000_000L;
	/** More threads than the steady count work while at least this many tasks wait, and leave when idle this long. */
	static final int BURST_BACKLOG = 6;
	private static final long BURST_IDLE_MILLIS = 3000;
	/**
	 * A block is put together after the bakes of the lights that can reach it from the focus's side: those no further
	 * from the focus than it is, plus the lights' reach and half a block's diagonal. So it is put together about once,
	 * when those lights have landed, not after each of the many that reach it.
	 */
	static final float BLOCK_HALF_DIAGONAL = BLOCK * LightMapBake.CELL * 0.71f;

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
		// its bake waits for the models
		boolean held;
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
	private final int threadCount, burstCount;
	private final AtomicInteger burstRunning = new AtomicInteger();
	private final List<Thread> burstThreadList = new java.util.concurrent.CopyOnWriteArrayList<>();
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

	// the table: per id, (strength now over the baked, 0, 0, 0)
	private final float[] table = new float[TABLE_TEXELS * 4];
	private int tableUsed = 1;

	/** The work since the map was idle: what was baked, why, and what it took, for the log. */
	private static final class Batch
	{
		final long start;
		boolean scene;
		int lights, fresh, changed, byWalls, byModels, unchanged, blocks;
		long bakeNanos, blockNanos, visits;

		Batch(long start, boolean scene)
		{
			this.start = start;
			this.scene = scene;
		}
	}

	// guarded by lock
	private Batch batch;
	private boolean sceneLoad;
	private long sceneLoadNanos;
	private int bakesPending, combinesQueued;
	// whether the first bakes of a scene wait for its models, since when, and when the work they held began
	private float maxRadius;
	private boolean holding;
	private long waitStart, heldFrom;
	// totals for tests and measurements
	private long totalBakes, totalBakeNanos, totalCombines, totalCombineNanos;

	/** @param threads the baking threads; 0 for none: the work is then done by {@link #runTasks} */
	LightMap(int threads)
	{
		this(threads, threads);
	}

	/** @param burst the most threads while a lot of work waits (a scene load); the others leave when it is done */
	LightMap(int threads, int burst)
	{
		threadCount = threads;
		burstCount = Math.max(threads, burst);
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

	/** More threads for a burst of work: three quarters of the cores, six at the most. */
	static int burstThreads()
	{
		return Math.max(defaultThreads(), Math.min(6, Runtime.getRuntime().availableProcessors() * 3 / 4));
	}

	void free()
	{
		stopped = true;
		for (Thread t : threads)
		{
			t.interrupt();
		}
		threads.clear();
		for (Thread t : burstThreadList)
		{
			t.interrupt();
		}
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
		if (threadCount > 0 && burstCount > threadCount && tasks.size() >= BURST_BACKLOG
			&& burstRunning.get() < burstCount - threadCount)
		{
			burstRunning.incrementAndGet();
			Thread t = new Thread(this::burstWork, "RuneLight light map burst");
			t.setDaemon(true);
			t.setPriority(Thread.MIN_PRIORITY);
			burstThreadList.add(t);
			t.start();
		}
	}

	/** A thread for a burst: it works while there is work, and ends when there has been none for a while. */
	private void burstWork()
	{
		try
		{
			while (!stopped)
			{
				Task t = tasks.poll(BURST_IDLE_MILLIS, TimeUnit.MILLISECONDS);
				if (t == null)
				{
					return;
				}
				try
				{
					t.run.run();
				}
				catch (Throwable ex)
				{
					log.warn("Light map task failed", ex);
				}
			}
		}
		catch (InterruptedException ex)
		{
			// done
		}
		finally
		{
			burstThreadList.remove(Thread.currentThread());
			burstRunning.decrementAndGet();
		}
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
		setBlockers(next, false);
	}

	/**
	 * As setBlockers(next), told whether the scene's models are on the way (next has none yet). The lights are not
	 * baked for a moment then, MODEL_WAIT_NANOS at the most, so that they are baked once, with the models, and the
	 * clusters light them meanwhile. Call it every frame: the wait ends by itself.
	 *
	 * @param modelsAwaited the models of this scene are being made, next.models is null until they are
	 */
	void setBlockers(LightBlockers next, boolean modelsAwaited)
	{
		synchronized (lock)
		{
			long now = System.nanoTime();
			boolean wait = next != null && next.models == null && modelsAwaited;
			long since = waitStart;
			if (!wait)
			{
				waitStart = 0;
			}
			else if (waitStart == 0)
			{
				waitStart = since = now;
			}
			boolean was = holding;
			holding = wait && now - waitStart < MODEL_WAIT_NANOS;
			if (was && !holding)
			{
				heldFrom = since;
				log.info("Light map: waited {} ms for the scene's models{}", Math.round((now - since) / 1e6),
					wait ? ", not there yet: the lights are baked without them" : "");
			}
			applyBlockers(next);
			if (was && !holding)
			{
				for (Source s : new ArrayList<>(sources))
				{
					if (s.held && s.registered && !s.retired)
					{
						rebake(s, s.grids == null);
					}
				}
			}
		}
	}

	private void applyBlockers(LightBlockers next)
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
					rebake(s, false);
				}
				return;
			}
			CellChanges walls = new CellChanges(next.cells), models = new CellChanges(next.cells);
			if (old.walls != next.walls)
			{
				next.walls.diff(old.walls, walls);
			}
			if (old.models != next.models)
			{
				if (next.models != null && old.models == null)
				{
					// the models come to a scene without: only where they stand, and the solid objects that stop being
					// whole tiles at the light's height (LightBlockers.solid)
					next.models.markCells(models);
					markSolid(next.walls, models);
				}
				else if (next.models != null)
				{
					next.models.diff(old.models, models);
				}
				else
				{
					models.markAll();
				}
			}
			if (walls.isEmpty() && models.isEmpty())
			{
				return;
			}
			int off = next.offset * LightBlockers.CELLS_PER_TILE;
			int again = 0;
			for (Source s : sources)
			{
				if (!s.registered || s.retired)
				{
					continue;
				}
				// the reason is the walls', when both reach it
				boolean byWalls = touches(s, walls, off);
				if (byWalls || touches(s, models, off))
				{
					rebake(s, false);
					again++;
					if (byWalls)
					{
						batch.byWalls++;
					}
					else
					{
						batch.byModels++;
					}
				}
			}
			if (again > 0 && log.isDebugEnabled())
			{
				log.debug("Light map: {} lights baked again, {} cells of walls or solid objects and {} cells of models changed",
					again, walls.count(), models.count());
			}
		}
	}

	/** Mark the cells of the tiles that a solid object fills on any storey. */
	private static void markSolid(WallMap walls, CellChanges changes)
	{
		int n = LightBlockers.CELLS_PER_TILE;
		for (int p = 0; p < WallMap.PLANES; ++p)
		{
			for (int x = 0; x < walls.size; ++x)
			{
				for (int y = 0; y < walls.size; ++y)
				{
					if (walls.solid(p, x, y))
					{
						for (int j = 0; j < n; ++j)
						{
							for (int i = 0; i < n; ++i)
							{
								changes.mark(x * n + i, y * n + j, 0);
							}
						}
					}
				}
			}
		}
	}

	/** Whether a cell of the light's reach (its box, a storey apart or not) differs. */
	private static boolean touches(Source s, CellChanges changes, int off)
	{
		float r = s.radius / LightMapBake.CELL;
		float cx = s.x / LightMapBake.CELL + off, cz = s.z / LightMapBake.CELL + off;
		return changes.any((int) Math.floor(cx - r) - 1, (int) Math.floor(cz - r) - 1, (int) Math.floor(cx + r) + 1,
			(int) Math.floor(cz + r) + 1);
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
			maxRadius = Math.max(maxRadius, s.radius);
			s.id = freeIds.poll();
			tableUsed = Math.max(tableUsed, s.id + 1);
			table[s.id * 4] = 1;
			sources.add(s);
			if (blockers != null)
			{
				rebake(s, true);
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
		sceneLoad = true;
		sceneLoadNanos = System.nanoTime();
		if (batch != null)
		{
			batch.scene = true;
		}
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

	/** The table, for the shaders: per id (strength now over baked, 0, 0, 0). */
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

	/** Work done so far: bakes, combines, and the nanoseconds each took; for tests and measurements. */
	long[] stats()
	{
		synchronized (lock)
		{
			return new long[] {totalBakes, totalBakeNanos, totalCombines, totalCombineNanos};
		}
	}

	/** Whether nothing is baking, waiting to be put together or waiting to be sent, and no bake is held. */
	boolean idle()
	{
		synchronized (lock)
		{
			if (bakesPending > 0 || combinesQueued > 0 || !ready.isEmpty() || holding)
			{
				return false;
			}
			for (Source s : sources)
			{
				if (s.held)
				{
					return false;
				}
			}
			return true;
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

	/** Bake a light (the lock is held). */
	private void rebake(Source s, boolean fresh)
	{
		int gen = ++s.generation;
		if (holding)
		{
			// the models are coming: bake once, then
			s.held = true;
			return;
		}
		s.held = false;
		float dx = s.x - focusX, dz = s.z - focusZ;
		if (batch == null)
		{
			// a new scene counts from when it was loaded
			long from = heldFrom != 0 ? heldFrom : System.nanoTime();
			batch = new Batch(sceneLoad ? Math.min(sceneLoadNanos, from) : from, sceneLoad);
			heldFrom = 0;
			sceneLoad = false;
		}
		batch.lights++;
		if (fresh)
		{
			batch.fresh++;
		}
		else
		{
			batch.changed++;
		}
		bakesPending++;
		submit(dx * dx + dz * dz, () -> bake(s, gen));
	}

	private final ThreadLocal<LightMapBake.Scratch> scratch = ThreadLocal.withInitial(LightMapBake.Scratch::new);

	private void bake(Source s, int gen)
	{
		try
		{
			LightBlockers b;
			synchronized (lock)
			{
				if (s.retired || s.generation != gen || blockers == null)
				{
					return;
				}
				b = blockers;
			}
			long start = System.nanoTime();
			LightMapBake.Result r = LightMapBake.bake(new LightMapBake.Light(s.plane, s.x, s.y, s.z, s.radius), b, scratch.get());
			long took = System.nanoTime() - start;
			if (log.isDebugEnabled())
			{
				log.debug("Light map: light at {}, {} (storey {}, radius {}) baked in {} ms, {} cell visits, {} storeys",
					Math.round(s.x), Math.round(s.z), s.plane, Math.round(s.radius), took / 1e6, r.cellVisits, r.grids.length);
			}
			synchronized (lock)
			{
				totalBakes++;
				totalBakeNanos += took;
				if (batch != null)
				{
					batch.bakeNanos += took;
					batch.visits += r.cellVisits;
				}
				if (!s.retired && s.generation == gen)
				{
					if (s.grids != null && same(s.grids, r.grids))
					{
						// baked again to the same result: nothing changes for the blocks
						if (batch != null)
						{
							batch.unchanged++;
						}
						return;
					}
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
		}
		finally
		{
			synchronized (lock)
			{
				bakesPending--;
			}
		}
	}

	private static boolean same(LightMapBake.Grid[] a, LightMapBake.Grid[] b)
	{
		if (a.length != b.length)
		{
			return false;
		}
		for (int i = 0; i < a.length; ++i)
		{
			if (a[i].plane != b[i].plane || a[i].x0 != b[i].x0 || a[i].z0 != b[i].z0 || a[i].w != b[i].w || a[i].h != b[i].h
				|| !Arrays.equals(a[i].c, b[i].c))
			{
				return false;
			}
		}
		return true;
	}

	/** When all the work is done and sent: say what it was. The lock is held. */
	private void endBatch()
	{
		Batch b = batch;
		if (b == null || bakesPending > 0 || combinesQueued > 0 || !ready.isEmpty())
		{
			return;
		}
		batch = null;
		double wall = (System.nanoTime() - b.start) / 1e6;
		String what = "{} lights baked ({} new, {} again: {} for walls or solid objects, {} for models; {} of them came out the same), {} ms from {} until every block was sent, "
			+ "{} ms of work on {} threads ({} ms baking, {} ms putting blocks together), {} cell visits, {} blocks sent";
		Object[] args = {b.lights, b.fresh, b.changed, b.byWalls, b.byModels, b.unchanged, Math.round(wall), b.scene ? "the scene load" : "the first bake",
			Math.round((b.bakeNanos + b.blockNanos) / 1e6), threadCount, Math.round(b.bakeNanos / 1e6), Math.round(b.blockNanos / 1e6),
			b.visits, b.blocks};
		if (b.scene || b.lights >= INFO_LIGHTS)
		{
			log.info("Light map: " + what, args);
		}
		else if (log.isDebugEnabled())
		{
			log.debug("Light map: " + what, args);
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
			combinesQueued++;
			// among the bakes by distance, behind the lights that reach the block
			int perSide = blocksPerSide;
			float bxm = ((k % perSide) * BLOCK + BLOCK / 2f - blockers.offset * LightBlockers.CELLS_PER_TILE) * LightMapBake.CELL;
			float bzm = ((k / perSide % perSide) * BLOCK + BLOCK / 2f - blockers.offset * LightBlockers.CELLS_PER_TILE) * LightMapBake.CELL;
			float d = (float) Math.sqrt((bxm - focusX) * (bxm - focusX) + (bzm - focusZ) * (bzm - focusZ));
			float after = d + maxRadius + BLOCK_HALF_DIAGONAL;
			submit(after * after, () -> combine(k));
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
		try
		{
			put(k);
		}
		finally
		{
			synchronized (lock)
			{
				combinesQueued--;
			}
		}
	}

	private void put(int k)
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
		long start = System.nanoTime();
		Block block = build(k, plane, bx, bz, seqNow, off, parts);
		synchronized (lock)
		{
			totalCombines++;
			totalCombineNanos += System.nanoTime() - start;
			if (batch != null)
			{
				batch.blockNanos += System.nanoTime() - start;
			}
			if (k >= seq.length || seq[k] != block.seq)
			{
				// the block changed again while this was put together, and another thread is putting that one together:
				// this one must not be sent after it
				return;
			}
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
		// per cell: the colour sum, the sum of luminance times the place of the light (x, z, y), the luminance, the
		// brightest and the next brightest, and the brightest light
		float[] sum = new float[n * 3], at = new float[n * 3], total = new float[n], best = new float[n], second = new float[n];
		Part[] dominant = new Part[n];
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
					at[i * 3] += lum * p.x;
					at[i * 3 + 1] += lum * p.z;
					at[i * 3 + 2] += lum * p.y;
					if (lum > best[i])
					{
						second[i] = best[i];
						best[i] = lum;
						dominant[i] = p;
					}
					else if (lum > second[i])
					{
						second[i] = lum;
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
				Part d = dominant[c];
				if (d == null)
				{
					continue;
				}
				a[c * 4] = Half.toHalf(sum[c * 3]);
				a[c * 4 + 1] = Half.toHalf(sum[c * 3 + 1]);
				a[c * 4 + 2] = Half.toHalf(sum[c * 3 + 2]);
				a[c * 4 + 3] = Half.toHalf(best[c] - second[c]);
				// the mean place from the cell's middle, times the luminance, in sixteenths of a unit
				float cx = (bx * BLOCK + i + 0.5f - off) * LightMapBake.CELL, cz = (bz * BLOCK + j + 0.5f - off) * LightMapBake.CELL;
				b[c * 4] = Half.toHalf((at[c * 3] - total[c] * cx) * PLACE_SCALE);
				b[c * 4 + 1] = Half.toHalf((at[c * 3 + 1] - total[c] * cz) * PLACE_SCALE);
				b[c * 4 + 2] = Half.toHalf(at[c * 3 + 2] * PLACE_SCALE);
				b[c * 4 + 3] = Half.toHalf(d.id);
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
		return update(max, 0, max, sink);
	}

	/**
	 * As update(max, sink), but when more blocks are waiting, up to hardMax are sent for as long as the send has taken
	 * less than budgetNanos: the nearest first, so a scene load is not spread over many frames and a frame is not held
	 * up by the sending.
	 */
	int update(int max, long budgetNanos, int hardMax, Sink sink)
	{
		long begin = System.nanoTime();
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
			for (int i = 0; i < send.size() && sent < hardMax; ++i)
			{
				if (sent >= max && System.nanoTime() - begin >= budgetNanos)
				{
					break;
				}
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
			if (batch != null)
			{
				batch.blocks += sent;
			}
			for (Source s : sources)
			{
				if (!s.live && s.grids != null && sent(s.needKeys, s.needSeqs))
				{
					s.live = true;
				}
			}
			endBatch();
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
}
