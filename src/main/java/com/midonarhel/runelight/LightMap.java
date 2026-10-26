package com.midonarhel.runelight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The static point lights' visibility, baked into a light map: per storey, a grid of quarter-tile cells that holds, for
 * each baked light that reaches the cell, how much of the light is not stopped on its way there (LightMapBake works one
 * light out). Nothing else about a light is in the map: the composite, the fog, the weather and the water read which
 * lights reach a point and how visible each is, fetch the lights' places, radii and colours from the table (below) and
 * work the light out exactly as the clusters do (117 HD's falloff by the distance, N.L, flicker), times the visibility.
 * Shading by a grid of cells is what drew its staircases, bands and rectangles; visibility alone is smooth. What a cell
 * holds, per storey:
 *
 * - Visibility: HEIGHT_LAYERS layers over the storey's height, filtered across the layers too, RGBA8: a channel per slot.
 * - Index: the table ids of the lights in the slots, 16 bits each, over two texels read as they are (nearest).
 *
 * A light has one slot (0 to SLOTS - 1) for as far as it reaches, chosen so that the lights that share a slot never
 * reach cells next to each other: in the cells a light is in (and the one ring of cells round them, where a filtered
 * read takes from it) its slot's channel is only its own, whichever way a read filters. Where more lights than slots
 * overlap, a light is left out of the cells whose slots are taken (the first to hold a cell keeps it, and the nearest
 * lights are baked first), and the count of those is told in the log for each scene.
 *
 * The table, in lightData from LIGHT_MAP_TABLE_BASE, has two texels per id: the light's place and its radius now, and its
 * colour times its strength now (flicker, pulse, night and fades are all in it, per frame: setLevel). So a light that
 * flickers does so in every cell it reaches, and nothing in the map is a strength.
 *
 * The bakes run on background threads, nearest light first, and the cells are put together by 32 x 32 blocks from the
 * lights' results, again on the threads; the render thread only sends the finished blocks (the nearest first, a few a
 * frame, update) and learns from that when a light is live: when every block it is in has been sent. Until then the
 * light is the clusters', so nothing pops to black; and a light that goes is taken out of the blocks before its table
 * entry is reused.
 *
 * Changes: when the walls, the solid objects or the models change (doors, spawns), the lights whose reach holds a cell
 * that differs are baked again (setBlockers), and one that comes out as it was changes nothing downstream.
 *
 * Each stretch of work (a scene load, a teleport, a door) is told in the log once it is all sent: the lights baked, the
 * time from the start until every block was sent, the work and the threads.
 */
final class LightMap
{
	private static final Logger log = LoggerFactory.getLogger(LightMap.class);

	/** Cells per block side. */
	static final int BLOCK = 32;
	/** The steps the night is quantized to for the rooms' light. */
	static final int NIGHT_STEPS = 16;
	/** The lights a cell can hold: the channels of its visibility. */
	static final int SLOTS = 4;
	/** Slices of the index in a storey: SLOTS ids of 16 bits in texels of 4 bytes. */
	static final int INDEX_SLICES = SLOTS / 2;
	/** Table ids; 0 is no light. */
	static final int TABLE_IDS = 2048;
	/** Texels of the table to an id: place and radius, colour and strength. */
	static final int TABLE_STRIDE = 2;
	static final int TABLE_TEXELS = TABLE_IDS * TABLE_STRIDE;
	static final int STORIES = WallMap.PLANES;
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
		// the scene object the light is on (VoxelCapture.ownerOf), 0 for a world light
		final int owner;
		final float x, y, z, radius;
		final float red, green, blue, strength;
		// for the rooms' light only (RoomLight): what the light is at a night, which the map's visibility does not hold
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
		// per grid, the cells the light holds in its slot: those it is in and the ring round them (see the class's doc)
		boolean[][] holds;
		// the slot its cells are in
		int slot;
		int[] needKeys = new int[0], needSeqs = new int[0];
		// whether the map carries the light: its blocks have been sent
		volatile boolean live;

		Source(int plane, float x, float y, float z, float radius, float red, float green, float blue, float strength,
			boolean nightOnly, float nightMultiplier, int owner)
		{
			this.owner = owner;
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

		/** The strength the rooms' light is worked out with at a night (0 to 1). */
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
		// RGBA bytes: the visibility, HEIGHT_LAYERS layers of BLOCK * BLOCK cells, and the index, INDEX_SLICES of them; null
		// when the block holds no light
		final byte[] vis, index;
		// per cell, the light on a floor there (RoomLight), luminance; null as vis is
		final float[] floor;
		// only floor changed: the textures hold the block as it is
		final boolean floorOnly;

		Block(int key, int plane, int bx, int bz, int seq, byte[] vis, byte[] index, float[] floor, boolean floorOnly)
		{
			this.key = key;
			this.plane = plane;
			this.bx = bx;
			this.bz = bz;
			this.seq = seq;
			this.vis = vis;
			this.index = index;
			this.floor = floor;
			this.floorOnly = floorOnly;
		}
	}

	interface Sink
	{
		/** Send a block into the textures; a null vis (and index) clears it. */
		void block(int plane, int bx, int bz, byte[] vis, byte[] index);

		/** The light on a floor per cell of a block, as of the lights' strengths now (null: none), for the rooms. */
		void floor(int plane, int bx, int bz, float[] floor);
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
	private int night;
	// blocks that only the rooms' light changed in, since they were put together last: not sent to the textures
	private boolean[] floorOnly = new boolean[0];
	private final ArrayDeque<Integer> freeIds = new ArrayDeque<>();
	// the table entries of lights that were taken off, with the blocks that must be sent before they are free: id,
	// then the keys and the seqs
	private final List<int[]> releases = new ArrayList<>();
	private final Map<Integer, Block> ready = new HashMap<>();
	// render thread
	private int[] uploaded = new int[0];
	private boolean[] onGpu = new boolean[0];
	private float focusX, focusZ;

	// the table: per id, the light's place and radius now, then its colour times its strength now (and 0)
	private final float[] table = new float[TABLE_TEXELS * 4];
	private int tableUsed = 1;
	// per storey and slot, per cell: the id of the light that holds the cell in the slot, 0 for none; and the lights by id; guarded by lock
	private short[][] holder = new short[STORIES * SLOTS][0];
	private final Source[] byId = new Source[TABLE_IDS];

	/** The work since the map was idle: what was baked, why, and what it took, for the log. */
	private static final class Batch
	{
		final long start;
		boolean scene;
		int lights, fresh, changed, byWalls, byModels, unchanged, blocks, lostLights;
		long lostCells, litCells;
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
		for (int i = 1; i < TABLE_IDS; ++i)
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
				floorOnly = new boolean[seq.length];
				holder = new short[STORIES * SLOTS][next.cells * next.cells];
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
	 * The night (0 to 1) as of now. The rooms' light of lights that depend on it is worked out again when it has moved on
	 * by a step of NIGHT_STEPS; the textures hold none of it.
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
					// a block the textures hold as it is now: only its floor is put together again
					int[] keys = keysOf(s.grids);
					boolean[] clean = new boolean[keys.length];
					for (int i = 0; i < keys.length; ++i)
					{
						int k = keys[i];
						clean[i] = floorOnly[k] || !combinePending[k] && !ready.containsKey(k) && uploaded[k] >= seq[k];
					}
					dirty(s.grids, null);
					for (int i = 0; i < keys.length; ++i)
					{
						floorOnly[keys[i]] |= clean[i];
					}
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
			byId[s.id] = s;
			setLevel(s, s.strength, s.radius);
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
			unhold(s);
			byId[s.id] = null;
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
			s.holds = null;
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
			s.holds = null;
			s.id = 0;
		}
		sources.clear();
		for (short[] cells : holder)
		{
			Arrays.fill(cells, (short) 0);
		}
		Arrays.fill(byId, null);
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
	 * Say what the light is like now: its table entry holds its place, this radius, and its colour times this.
	 *
	 * @param intensity colour is multiplied by this (flicker, pulse, night, fades, all of it)
	 * @param radius the light's reach now (flicker, pulse), at most the reach it is baked for
	 */
	void setLevel(Source s, float intensity, float radius)
	{
		if (!s.registered || s.retired)
		{
			return;
		}
		int t = s.id * TABLE_STRIDE * 4;
		table[t] = s.x;
		table[t + 1] = s.y;
		table[t + 2] = s.z;
		table[t + 3] = radius;
		table[t + 4] = s.red * intensity;
		table[t + 5] = s.green * intensity;
		table[t + 6] = s.blue * intensity;
		table[t + 7] = 0;
	}

	/** Whether the map carries the light, so the clusters mustn't. */
	static boolean isLive(Source s)
	{
		return s.live;
	}

	/** The table, for the shaders: per id, TABLE_STRIDE texels (place and radius, colour and 0). */
	float[] table()
	{
		return table;
	}

	/** The table's ids in use: ids below this. */
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
			LightMapBake.Result r = LightMapBake.bake(new LightMapBake.Light(s.plane, s.x, s.y, s.z, s.radius, s.owner), b, scratch.get());
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
					unhold(s);
					hold(s, r.grids);
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

	/**
	 * Give the light its cells in a slot (the lock is held): the slot where the least light is lost, to the lights that hold
	 * cells in it already. The light it holds is those it is in and the ring round them. A cell that is taken goes to the
	 * brighter of the two lights there (what it gives the cell: its luminance times its visibility and its falloff there), and
	 * the other is left out of it: the weakest light is the one dropped.
	 */
	private void hold(Source s, LightMapBake.Grid[] grids)
	{
		int cells = blockers.cells;
		boolean[][] holds = new boolean[grids.length][];
		long litCells = 0;
		for (int i = 0; i < grids.length; ++i)
		{
			holds[i] = ring(grids[i]);
			for (int c = 0; c < grids[i].w * grids[i].h; ++c)
			{
				litCells += lit(grids[i], c) ? 1 : 0;
			}
		}
		int slot = 0;
		float fewest = Float.MAX_VALUE;
		for (int k = 0; k < SLOTS && fewest > 0; ++k)
		{
			float lost = 0;
			for (int i = 0; i < grids.length; ++i)
			{
				LightMapBake.Grid g = grids[i];
				short[] holder = this.holder[g.plane * SLOTS + k];
				for (int j = 0; j < g.h; ++j)
				{
					for (int x = 0; x < g.w; ++x)
					{
						int c = j * g.w + x, at = (g.z0 + j) * cells + g.x0 + x;
						if (holds[i][c] && holder[at] != 0)
						{
							Source other = byId[holder[at]];
							lost += Math.min(weight(s, g, x, j), other != null ? weightAt(other, g.plane, g.x0 + x, g.z0 + j) : 0);
						}
					}
				}
			}
			if (lost < fewest)
			{
				fewest = lost;
				slot = k;
			}
		}
		int lostLit = 0;
		Set<Integer> changed = new HashSet<>();
		for (int i = 0; i < grids.length; ++i)
		{
			LightMapBake.Grid g = grids[i];
			short[] holder = this.holder[g.plane * SLOTS + slot];
			for (int j = 0; j < g.h; ++j)
			{
				for (int x = 0; x < g.w; ++x)
				{
					int c = j * g.w + x, at = (g.z0 + j) * cells + g.x0 + x;
					if (!holds[i][c])
					{
						continue;
					}
					Source other = holder[at] != 0 ? byId[holder[at]] : null;
					if (other != null && weightAt(other, g.plane, g.x0 + x, g.z0 + j) >= weight(s, g, x, j))
					{
						holds[i][c] = false;
						lostLit += lit(g, c) ? 1 : 0;
						continue;
					}
					if (other != null)
					{
						lostLit += drop(other, g.plane, g.x0 + x, g.z0 + j, changed);
					}
					holder[at] = (short) s.id;
				}
			}
		}
		for (int k : changed)
		{
			// the lights that lost a cell are put into the blocks again
			seq[k]++;
			floorOnly[k] = false;
			schedule(k);
		}
		s.slot = slot;
		s.holds = holds;
		if (batch != null)
		{
			batch.litCells += litCells;
			if (lostLit > 0)
			{
				batch.lostLights++;
				batch.lostCells += lostLit;
			}
		}
	}

	/** The luminance of a light's colour at its strength. */
	private static float luminance(Source s)
	{
		return (0.2126f * s.red + 0.7152f * s.green + 0.0722f * s.blue) * s.strength;
	}

	/**
	 * What the light gives a cell of its grid (gx, gz in the grid), as the most of its layers: the light's luminance times
	 * its visibility there and its falloff by the distance.
	 */
	private float weight(Source s, LightMapBake.Grid g, int gx, int gz)
	{
		float most = 0;
		for (int layer = 0; layer < LightMapBake.HEIGHT_LAYERS; ++layer)
		{
			most = Math.max(most, g.c[(gz * g.w + gx) * LightMapBake.HEIGHT_LAYERS + layer]);
		}
		if (most <= 0)
		{
			return 0;
		}
		int off = blockers.offset * LightBlockers.CELLS_PER_TILE, x = g.x0 + gx, z = g.z0 + gz;
		float flat = (float) Math.hypot((x + 0.5f - off) * LightMapBake.CELL - s.x, (z + 0.5f - off) * LightMapBake.CELL - s.z);
		float dy = blockers.floor(g.plane, x / LightMapBake.CELLS_PER_TILE, z / LightMapBake.CELLS_PER_TILE)
			- LightMapBake.layerHeight(1) - s.y;
		return luminance(s) * most * LightMapBake.falloff((float) Math.hypot(flat, dy) / s.radius);
	}

	/** What a light that holds a cell (x, z) of a storey gives it. */
	private float weightAt(Source s, int plane, int x, int z)
	{
		for (LightMapBake.Grid g : s.grids)
		{
			if (g.plane == plane && x >= g.x0 && z >= g.z0 && x < g.x0 + g.w && z < g.z0 + g.h)
			{
				return weight(s, g, x - g.x0, z - g.z0);
			}
		}
		return 0;
	}

	/** A light that held a cell loses it (to a brighter one), and the block of it is to be put together again. Returns 1 if it was lit. */
	private int drop(Source s, int plane, int x, int z, Set<Integer> changed)
	{
		for (int i = 0; i < s.grids.length; ++i)
		{
			LightMapBake.Grid g = s.grids[i];
			if (g.plane == plane && x >= g.x0 && z >= g.z0 && x < g.x0 + g.w && z < g.z0 + g.h)
			{
				int c = (z - g.z0) * g.w + x - g.x0;
				s.holds[i][c] = false;
				changed.add(key(plane, x / BLOCK, z / BLOCK));
				return lit(g, c) ? 1 : 0;
			}
		}
		return 0;
	}

	/** The light lets go of its cells (the lock is held). */
	private void unhold(Source s)
	{
		if (s.holds == null || s.grids == null || blockers == null)
		{
			return;
		}
		int cells = blockers.cells;
		for (int i = 0; i < s.grids.length; ++i)
		{
			LightMapBake.Grid g = s.grids[i];
			short[] holder = this.holder[g.plane * SLOTS + s.slot];
			for (int j = 0; j < g.h; ++j)
			{
				for (int x = 0; x < g.w; ++x)
				{
					if (s.holds[i][j * g.w + x])
					{
						holder[(g.z0 + j) * cells + g.x0 + x] = 0;
					}
				}
			}
		}
		s.holds = null;
	}

	private static boolean lit(LightMapBake.Grid g, int cell)
	{
		for (int layer = 0; layer < LightMapBake.HEIGHT_LAYERS; ++layer)
		{
			if (g.c[cell * LightMapBake.HEIGHT_LAYERS + layer] > 0)
			{
				return true;
			}
		}
		return false;
	}

	/** The cells of a grid with any light, and the ring of cells round them. */
	static boolean[] ring(LightMapBake.Grid g)
	{
		boolean[] out = new boolean[g.w * g.h];
		for (int j = 0; j < g.h; ++j)
		{
			for (int x = 0; x < g.w; ++x)
			{
				if (lit(g, j * g.w + x))
				{
					for (int dj = Math.max(0, j - 1); dj <= Math.min(g.h - 1, j + 1); ++dj)
					{
						for (int dx = Math.max(0, x - 1); dx <= Math.min(g.w - 1, x + 1); ++dx)
						{
							out[dj * g.w + dx] = true;
						}
					}
				}
			}
		}
		return out;
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
		if (b.lostCells > 0)
		{
			String lost = "Light map: {} lights have no room for {} of their {} lit cells, where more than {} lights overlap";
			if (b.scene)
			{
				log.info(lost, b.lostLights, b.lostCells, b.litCells, SLOTS);
			}
			else if (log.isDebugEnabled())
			{
				log.debug(lost, b.lostLights, b.lostCells, b.litCells, SLOTS);
			}
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
			floorOnly[k] = false;
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
		final boolean[] holds;
		final int id, slot;
		// for the light on a floor: where the light is, how far it reaches and its luminance
		final float x, y, z, radius, luminance;

		Part(LightMapBake.Grid g, boolean[] holds, Source s, float strength)
		{
			this.g = g;
			this.holds = holds;
			id = s.id;
			slot = s.slot;
			x = s.x;
			y = s.y;
			z = s.z;
			radius = s.radius;
			luminance = (0.2126f * s.red + 0.7152f * s.green + 0.0722f * s.blue) * strength;
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
		int plane, bx, bz, seqNow, perSide;
		boolean floorOnlyNow;
		LightBlockers b;
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
			floorOnlyNow = floorOnly[k];
			b = blockers;
			float n = night / (float) NIGHT_STEPS;
			for (Source s : sources)
			{
				if (s.grids == null)
				{
					continue;
				}
				for (int gi = 0; gi < s.grids.length; ++gi)
				{
					LightMapBake.Grid g = s.grids[gi];
					if (g.plane == plane && g.x0 < (bx + 1) * BLOCK && g.x0 + g.w > bx * BLOCK
						&& g.z0 < (bz + 1) * BLOCK && g.z0 + g.h > bz * BLOCK)
					{
						parts.add(new Part(g, s.holds[gi], s, s.baked(n)));
					}
				}
			}
		}
		long start = System.nanoTime();
		Block block = build(k, plane, bx, bz, seqNow, floorOnlyNow, parts, b);
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

	/**
	 * A block's visibility and index: per cell, the lights that hold it each in its slot, the visibility of each layer in
	 * the slot's channel and the id in the slot's place in the index. And the light on the floor there, for the rooms: the
	 * lights' luminances at the strengths of the night as of now, each times its visibility and its falloff, at the height of
	 * the first layer.
	 */
	private static Block build(int key, int plane, int bx, int bz, int seq, boolean floorOnly, List<Part> parts,
		LightBlockers b)
	{
		int n = BLOCK * BLOCK, layers = LightMapBake.HEIGHT_LAYERS;
		byte[] vis = new byte[n * layers * 4], index = new byte[n * INDEX_SLICES * 4];
		float[] floor = new float[n];
		boolean any = false;
		float up = LightMapBake.layerHeight(0);
		for (Part p : parts)
		{
			LightMapBake.Grid g = p.g;
			int x0 = Math.max(g.x0, bx * BLOCK), x1 = Math.min(g.x0 + g.w, (bx + 1) * BLOCK);
			int z0 = Math.max(g.z0, bz * BLOCK), z1 = Math.min(g.z0 + g.h, (bz + 1) * BLOCK);
			float lx = p.x / LightMapBake.CELL + b.offset * LightBlockers.CELLS_PER_TILE;
			float lz = p.z / LightMapBake.CELL + b.offset * LightBlockers.CELLS_PER_TILE;
			for (int z = z0; z < z1; ++z)
			{
				for (int x = x0; x < x1; ++x)
				{
					int from = (z - g.z0) * g.w + x - g.x0;
					if (!p.holds[from])
					{
						continue;
					}
					int i = (z - bz * BLOCK) * BLOCK + x - bx * BLOCK;
					int at = ((p.slot >> 1) * n + i) * 4 + (p.slot & 1) * 2;
					index[at] = (byte) p.id;
					index[at + 1] = (byte) (p.id >> 8);
					for (int layer = 0; layer < layers; ++layer)
					{
						vis[(layer * n + i) * 4 + p.slot] = (byte) Math.round(g.c[from * layers + layer] * 255);
					}
					float v = g.c[from * layers];
					if (v > 0)
					{
						float flat = (float) Math.hypot(x + 0.5f - lx, z + 0.5f - lz) * LightMapBake.CELL;
						float dy = b.floor(g.plane, x / LightMapBake.CELLS_PER_TILE, z / LightMapBake.CELLS_PER_TILE) - up - p.y;
						floor[i] += p.luminance * v * LightMapBake.falloff((float) Math.hypot(flat, dy) / p.radius);
					}
					any = true;
				}
			}
		}
		return any ? new Block(key, plane, bx, bz, seq, vis, index, floor, floorOnly)
			: new Block(key, plane, bx, bz, seq, null, null, null, floorOnly);
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
				boolean empty = bl.vis == null;
				if (!bl.floorOnly)
				{
					if (!empty || onGpu[bl.key])
					{
						sink.block(bl.plane, bl.bx, bl.bz, bl.vis, bl.index);
					}
					onGpu[bl.key] = !empty;
				}
				sink.floor(bl.plane, bl.bx, bl.bz, bl.floor);
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
