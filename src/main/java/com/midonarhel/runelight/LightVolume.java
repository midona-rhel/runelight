package com.midonarhel.runelight;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The static point lights, baked (LightBake) into a window of cells around the player that the shaders read in place
 * of looping over them (light_volume.glsl): which lights are baked, which blocks of the window are baked and what to
 * bake next. GL free; LightVolumeMap puts the baked blocks on the GPU.
 *
 * The window is BLOCKS_X x BLOCKS_Y x BLOCKS_Z blocks of BLOCK^3 cells of CELL units: 4096 x 2048 x 4096 units, 32
 * tiles square. It follows the player a block at a time; horizontally it wraps (the blocks that leave on one side
 * are the texels the ones that come in on the other side take), so moving rebakes only what comes in. Vertically it
 * doesn't, and moving it up or down a block rebakes everything.
 *
 * The lights are the registry: a light is added when it first comes in range, rebaked when its strength, colour or
 * place change by more than TOLERANCE, and taken out when it goes. Every change is an epoch, and bakes the blocks
 * its sphere reaches (and a cell more) are marked dirty for; a block's baked epoch is the registry's at the time its
 * bake began, so a block holds a light when its epoch is the light's epoch or more. That is how the old path
 * and the baked one never both light a pixel and neither misses: the shader leaves a light out where the pixel's
 * block holds it (light_volume.glsl), and the lights whose blocks all hold them leave the clusters altogether
 * ({@link #resident}). A block is baked in the background, nearest the focal point first, and handed on to be
 * uploaded a few at a time ({@link #pollFinished}).
 *
 * Client thread only, except the bakes and the voxel comparison on its own threads.
 */
final class LightVolume
{
	private static final Logger log = LoggerFactory.getLogger(LightVolume.class);

	static final int CELL = LightBake.CELL;
	static final int BLOCK = 16;
	static final int BLOCKS_X = 8, BLOCKS_Y = 4, BLOCKS_Z = 8;
	static final int BLOCK_COUNT = BLOCKS_X * BLOCKS_Y * BLOCKS_Z;
	static final int DIM_X = BLOCKS_X * BLOCK, DIM_Y = BLOCKS_Y * BLOCK, DIM_Z = BLOCKS_Z * BLOCK;
	// a block's bake reaches a cell past it on every side, so the filtering at its edge reads baked cells
	static final int APRON = 1;
	static final int REGION = BLOCK + 2 * APRON;
	// a light is baked again when a channel of its colour times strength, or its place, moves by this much of it
	static final float TOLERANCE = 0.04f;
	// bakes handed to the workers at a time, per worker
	private static final int QUEUE_PER_WORKER = 2;

	/** The constants light_volume.glsl is compiled with. */
	static String shaderConfig()
	{
		return "#define LIGHT_CELL " + CELL + ".0\n"
			+ "#define LIGHT_BLOCK " + BLOCK + ".0\n"
			+ "#define LIGHT_VOLUME_DIM vec3(" + DIM_X + ".0, " + DIM_Y + ".0, " + DIM_Z + ".0)\n"
			+ "#define LIGHT_VOLUME_BLOCKS ivec3(" + BLOCKS_X + ", " + BLOCKS_Y + ", " + BLOCKS_Z + ")\n"
			+ "#define LIGHT_VOLUME_EPOCH_VECS " + BLOCK_COUNT / 4 + "\n"
			+ "#define LIGHT_VOLUME_FLICKER_VECS " + LightBake.SLOTS / 4 + "\n";
	}

	/** A baked block: its cells and the epoch it holds. */
	static final class Result
	{
		final int generation, slot, bx, by, bz, epoch;
		// REGION^3 cells from (bx * BLOCK - APRON, ...), see LightBake; null: no light reaches it, all zero
		final short[] a, b;
		final long nanos;
		final int lights;

		Result(int generation, int slot, int bx, int by, int bz, int epoch, short[] a, short[] b, long nanos, int lights)
		{
			this.generation = generation;
			this.slot = slot;
			this.bx = bx;
			this.by = by;
			this.bz = bz;
			this.epoch = epoch;
			this.a = a;
			this.b = b;
			this.nanos = nanos;
			this.lights = lights;
		}
	}

	private static final class Entry
	{
		final Object key;
		int epoch;
		int slot;
		boolean seen;
		float x, y, z, radius, r, g, b;
		LightBake.Light light;

		Entry(Object key)
		{
			this.key = key;
		}
	}

	/** A flicker slot waiting for the blocks that held its light to be baked without it. */
	private static final class Freeing
	{
		final int slot, epoch;
		final int[] blocks;

		Freeing(int slot, int epoch, int[] blocks)
		{
			this.slot = slot;
			this.epoch = epoch;
			this.blocks = blocks;
		}
	}

	private final Map<Object, Entry> entries = new HashMap<>();
	private final ArrayDeque<Integer> freeSlots = new ArrayDeque<>();
	private final List<Freeing> freeing = new ArrayList<>();
	private int registryEpoch;

	private boolean sceneSet;
	private int sceneBaseX, sceneBaseY;
	private boolean sceneInstance;
	private VoxelGrid grid;
	private int generation;

	// the window's first block
	private boolean placed;
	private int originBx, originBy, originBz;
	// per block of the window, by slot: baked epoch (0: not baked), a bake wanted, a bake under way, and which blocks
	// (global, x and z; y is the slot's) the slot holds now
	private final int[] committed = new int[BLOCK_COUNT];
	private final boolean[] dirty = new boolean[BLOCK_COUNT];
	private final boolean[] inFlight = new boolean[BLOCK_COUNT];
	private final int[] slotBx = new int[BLOCK_COUNT], slotBz = new int[BLOCK_COUNT];

	private final float[] mods = new float[LightBake.SLOTS];

	private ExecutorService workers;
	private int workerCount;
	private int queued;
	private final ConcurrentLinkedQueue<Result> finished = new ConcurrentLinkedQueue<>();
	private final ConcurrentLinkedQueue<int[]> changes = new ConcurrentLinkedQueue<>();

	// for the log: a run of bakes from the first submitted to the last finished
	private long runStart, runCpuNanos;
	private int runBlocks;

	LightVolume()
	{
		for (int s = 1; s < LightBake.SLOTS; ++s)
		{
			freeSlots.add(s);
		}
		Arrays.fill(mods, 1f);
	}

	// ---------------------------------------------------------------------------------------------------------
	// The window

	/** The window's first cell on each axis. */
	int originCellX()
	{
		return originBx * BLOCK;
	}

	int originCellY()
	{
		return originBy * BLOCK;
	}

	int originCellZ()
	{
		return originBz * BLOCK;
	}

	boolean isPlaced()
	{
		return placed;
	}

	/** The slot of a block of the window; -1 if the block is not in it. */
	int slotOf(int bx, int by, int bz)
	{
		int yi = by - originBy;
		if (!placed || bx < originBx || bx >= originBx + BLOCKS_X || bz < originBz || bz >= originBz + BLOCKS_Z
			|| yi < 0 || yi >= BLOCKS_Y)
		{
			return -1;
		}
		return (yi * BLOCKS_Z + Math.floorMod(bz, BLOCKS_Z)) * BLOCKS_X + Math.floorMod(bx, BLOCKS_X);
	}

	/** The epochs the blocks are baked at, by slot (BLOCK_COUNT of them): 0 for none. */
	int[] blockEpochs()
	{
		return committed;
	}

	/** The strength of each flicker slot now over its baked strength, 1 for the others. */
	float[] modulation()
	{
		return mods;
	}

	private void recenter(float focalX, float focalY, float focalZ)
	{
		int fbx = Math.floorDiv(LightBake.cell(focalX), BLOCK), fby = Math.floorDiv(LightBake.cell(focalY), BLOCK),
			fbz = Math.floorDiv(LightBake.cell(focalZ), BLOCK);
		if (!placed)
		{
			placed = true;
			originBx = fbx - BLOCKS_X / 2;
			originBy = fby - 2;
			originBz = fbz - BLOCKS_Z / 2;
			invalidateAll();
			return;
		}
		// the focal point stays in the middle four blocks across and the two in the middle up and down
		boolean moveY = fby < originBy + 1 || fby > originBy + 2;
		boolean moveX = fbx < originBx + BLOCKS_X / 2 - 2 || fbx >= originBx + BLOCKS_X / 2 + 2;
		boolean moveZ = fbz < originBz + BLOCKS_Z / 2 - 2 || fbz >= originBz + BLOCKS_Z / 2 + 2;
		if (!moveX && !moveY && !moveZ)
		{
			return;
		}
		if (moveX)
		{
			originBx = fbx - BLOCKS_X / 2;
		}
		if (moveZ)
		{
			originBz = fbz - BLOCKS_Z / 2;
		}
		if (moveY)
		{
			originBy = fby - 2;
			invalidateAll();
			return;
		}
		// the slots that hold other blocks now start over
		for (int yi = 0; yi < BLOCKS_Y; ++yi)
		{
			for (int zi = 0; zi < BLOCKS_Z; ++zi)
			{
				for (int xi = 0; xi < BLOCKS_X; ++xi)
				{
					int slot = (yi * BLOCKS_Z + zi) * BLOCKS_X + xi;
					int gx = originBx + Math.floorMod(xi - originBx, BLOCKS_X);
					int gz = originBz + Math.floorMod(zi - originBz, BLOCKS_Z);
					if (slotBx[slot] != gx || slotBz[slot] != gz)
					{
						slotBx[slot] = gx;
						slotBz[slot] = gz;
						committed[slot] = 0;
						dirty[slot] = true;
					}
				}
			}
		}
	}

	private void invalidateAll()
	{
		for (int yi = 0; yi < BLOCKS_Y; ++yi)
		{
			for (int zi = 0; zi < BLOCKS_Z; ++zi)
			{
				for (int xi = 0; xi < BLOCKS_X; ++xi)
				{
					int slot = (yi * BLOCKS_Z + zi) * BLOCKS_X + xi;
					slotBx[slot] = originBx + Math.floorMod(xi - originBx, BLOCKS_X);
					slotBz[slot] = originBz + Math.floorMod(zi - originBz, BLOCKS_Z);
					committed[slot] = 0;
					dirty[slot] = true;
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Per frame

	/**
	 * Once a frame: take in this frame's static lights (those with a bakeKey), move the window to the focal point and hand
	 * the workers the next blocks. Without voxels of this scene nothing is baked and nothing is resident.
	 *
	 * @param voxels the scene's voxels, null if there are none yet; they are of the scene at baseX, baseY only if
	 * they say so
	 */
	void update(List<PointLight> lights, SceneVoxels voxels, int baseX, int baseY, boolean instance, float focalX,
		float focalY, float focalZ)
	{
		if (voxels == null || voxels.baseX != baseX || voxels.baseY != baseY || voxels.instance != instance)
		{
			if (sceneSet)
			{
				reset();
			}
			return;
		}
		if (!sceneSet || baseX != sceneBaseX || baseY != sceneBaseY || instance != sceneInstance)
		{
			reset();
			sceneSet = true;
			sceneBaseX = baseX;
			sceneBaseY = baseY;
			sceneInstance = instance;
		}
		if (voxels.grid != grid)
		{
			setGrid(voxels.grid);
		}
		recenter(focalX, focalY, focalZ);
		applyChanges();
		sync(lights);
		releaseSlots();
		schedule(focalX, focalY, focalZ);
		report();
	}

	/** Forget everything baked: nothing is resident until the next bakes are in. */
	void reset()
	{
		++generation;
		entries.clear();
		freeing.clear();
		freeSlots.clear();
		for (int s = 1; s < LightBake.SLOTS; ++s)
		{
			freeSlots.add(s);
		}
		Arrays.fill(mods, 1f);
		Arrays.fill(committed, 0);
		Arrays.fill(dirty, false);
		changes.clear();
		grid = null;
		sceneSet = false;
		placed = false;
		runBlocks = 0;
		runCpuNanos = 0;
		runStart = 0;
	}

	private void setGrid(VoxelGrid next)
	{
		VoxelGrid old = grid;
		grid = next;
		if (old == null)
		{
			return;
		}
		if (old.nx != next.nx || old.ny != next.ny || old.nz != next.nz || old.layer0 != next.layer0)
		{
			// the voxels moved: everything is rebaked
			Arrays.fill(dirty, true);
			return;
		}
		compareInBackground(old, next);
	}

	/** The box of voxels that differ between two grids of the same size, if any, handed back through changes. */
	private void compareInBackground(VoxelGrid old, VoxelGrid next)
	{
		executor().execute(() ->
		{
			int[] c = null;
			for (int y = 0; y < next.ny; ++y)
			{
				for (int z = 0; z < next.nz; ++z)
				{
					int row = (y * next.nz + z) * next.nx;
					for (int x = 0; x < next.nx; ++x)
					{
						if (old.cells[row + x] != next.cells[row + x])
						{
							if (c == null)
							{
								c = new int[]{x, y, z, x, y, z};
							}
							c[0] = Math.min(c[0], x);
							c[1] = Math.min(c[1], y);
							c[2] = Math.min(c[2], z);
							c[3] = Math.max(c[3], x);
							c[4] = Math.max(c[4], y);
							c[5] = Math.max(c[5], z);
						}
					}
				}
			}
			if (c != null)
			{
				// to world units, a voxel more each way: the shadow of a changed voxel falls on cells past it
				int v = VoxelGrid.VOXEL;
				changes.add(new int[]{c[0] * v - v, (c[1] + next.layer0) * v - v, c[2] * v - v, (c[3] + 1) * v + v,
					(c[4] + 1 + next.layer0) * v + v, (c[5] + 1) * v + v});
			}
		});
	}

	/** Rebake the blocks of the lights whose reach includes a voxel that changed. */
	private void applyChanges()
	{
		int[] c;
		while ((c = changes.poll()) != null)
		{
			for (Entry e : entries.values())
			{
				if (e.x + e.radius >= c[0] && e.x - e.radius <= c[3] && e.y + e.radius >= c[1] && e.y - e.radius <= c[4]
					&& e.z + e.radius >= c[2] && e.z - e.radius <= c[5])
				{
					markDirty(e.x, e.y, e.z, e.radius);
				}
			}
		}
	}

	private void sync(List<PointLight> lights)
	{
		Arrays.fill(mods, 1f);
		for (Entry e : entries.values())
		{
			e.seen = false;
		}
		for (int i = 0; i < lights.size(); ++i)
		{
			PointLight l = lights.get(i);
			if (l.bakeKey == null || l.bakeRadius <= 0 || !reaches(l.x, l.y, l.z, l.bakeRadius))
			{
				continue;
			}
			float r = l.r * l.bakeStrength, g = l.g * l.bakeStrength, b = l.b * l.bakeStrength;
			Entry e = entries.get(l.bakeKey);
			if (e == null)
			{
				e = new Entry(l.bakeKey);
				entries.put(l.bakeKey, e);
				e.epoch = ++registryEpoch;
				if (l.bakeModulated)
				{
					e.slot = freeSlots.isEmpty() ? 0 : freeSlots.poll();
				}
				set(e, l, r, g, b);
				markDirty(e.x, e.y, e.z, e.radius);
			}
			else if (changed(e, l, r, g, b))
			{
				++registryEpoch;
				markDirty(e.x, e.y, e.z, e.radius);
				set(e, l, r, g, b);
				markDirty(e.x, e.y, e.z, e.radius);
			}
			e.seen = true;
			if (e.slot > 0)
			{
				mods[e.slot] = l.bakeMod;
			}
		}
		for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); )
		{
			Entry e = it.next();
			if (e.seen)
			{
				continue;
			}
			it.remove();
			++registryEpoch;
			markDirty(e.x, e.y, e.z, e.radius);
			if (e.slot > 0)
			{
				freeing.add(new Freeing(e.slot, registryEpoch, blocksOf(e.x, e.y, e.z, e.radius)));
			}
		}
	}

	private void set(Entry e, PointLight l, float r, float g, float b)
	{
		e.x = l.x;
		e.y = l.y;
		e.z = l.z;
		e.radius = l.bakeRadius;
		e.r = r;
		e.g = g;
		e.b = b;
		e.light = new LightBake.Light(e.x, e.y, e.z, e.radius, r, g, b, e.slot);
	}

	private static boolean changed(Entry e, PointLight l, float r, float g, float b)
	{
		if (Math.abs(e.x - l.x) > 0.5f || Math.abs(e.y - l.y) > 0.5f || Math.abs(e.z - l.z) > 0.5f
			|| Math.abs(e.radius - l.bakeRadius) > 0.5f)
		{
			return true;
		}
		float tolerance = TOLERANCE * Math.max(Math.max(e.r, Math.max(e.g, e.b)), 0.05f);
		return Math.abs(e.r - r) > tolerance || Math.abs(e.g - g) > tolerance || Math.abs(e.b - b) > tolerance;
	}

	/** Whether a sphere reaches the window. */
	private boolean reaches(float x, float y, float z, float radius)
	{
		return placed && LightBake.cell(x + radius) >= originCellX() - APRON
			&& LightBake.cell(x - radius) < originCellX() + DIM_X + APRON
			&& LightBake.cell(y + radius) >= originCellY() - APRON && LightBake.cell(y - radius) < originCellY() + DIM_Y + APRON
			&& LightBake.cell(z + radius) >= originCellZ() - APRON && LightBake.cell(z - radius) < originCellZ() + DIM_Z + APRON;
	}

	/** Mark the blocks a light's bake reaches (with its apron) as needing a bake. */
	private void markDirty(float x, float y, float z, float radius)
	{
		for (int slot : blocksOf(x, y, z, radius))
		{
			dirty[slot] = true;
		}
	}

	/** The slots of the window's blocks a sphere's bake reaches. */
	private int[] blocksOf(float x, float y, float z, float radius)
	{
		return blocksOf(x, y, z, radius, APRON);
	}

	private int[] blocksOf(float x, float y, float z, float radius, int apron)
	{
		if (!placed)
		{
			return new int[0];
		}
		int x0 = Math.max(Math.floorDiv(LightBake.cell(x - radius) - apron, BLOCK), originBx);
		int x1 = Math.min(Math.floorDiv(LightBake.cell(x + radius) + apron, BLOCK), originBx + BLOCKS_X - 1);
		int y0 = Math.max(Math.floorDiv(LightBake.cell(y - radius) - apron, BLOCK), originBy);
		int y1 = Math.min(Math.floorDiv(LightBake.cell(y + radius) + apron, BLOCK), originBy + BLOCKS_Y - 1);
		int z0 = Math.max(Math.floorDiv(LightBake.cell(z - radius) - apron, BLOCK), originBz);
		int z1 = Math.min(Math.floorDiv(LightBake.cell(z + radius) + apron, BLOCK), originBz + BLOCKS_Z - 1);
		if (x0 > x1 || y0 > y1 || z0 > z1)
		{
			return new int[0];
		}
		int[] out = new int[(x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1)];
		int n = 0;
		for (int by = y0; by <= y1; ++by)
		{
			for (int bz = z0; bz <= z1; ++bz)
			{
				for (int bx = x0; bx <= x1; ++bx)
				{
					out[n++] = slotOf(bx, by, bz);
				}
			}
		}
		return out;
	}

	/** Give back the flicker slots whose lights are out of every block that held them. */
	private void releaseSlots()
	{
		for (Iterator<Freeing> it = freeing.iterator(); it.hasNext(); )
		{
			Freeing f = it.next();
			boolean done = true;
			for (int slot : f.blocks)
			{
				if (committed[slot] < f.epoch && (dirty[slot] || inFlight[slot]))
				{
					done = false;
					break;
				}
			}
			if (done)
			{
				freeSlots.add(f.slot);
				it.remove();
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// What is resident

	/** The epoch a light entered the registry at, 0 if it is not in it (the light is not baked, or not yet). */
	int epochOf(PointLight l)
	{
		Entry e = l.bakeKey != null ? entries.get(l.bakeKey) : null;
		return e != null ? e.epoch : 0;
	}

	/**
	 * Whether a light is entirely in the volume: every block its sphere touches is in the window and baked with it.
	 * Such a light needs no pass of its own.
	 */
	boolean resident(PointLight l)
	{
		Entry e = l.bakeKey != null ? entries.get(l.bakeKey) : null;
		if (e == null)
		{
			return false;
		}
		int bx0 = Math.floorDiv(LightBake.cell(e.x - e.radius), BLOCK), bx1 = Math.floorDiv(LightBake.cell(e.x + e.radius), BLOCK);
		int by0 = Math.floorDiv(LightBake.cell(e.y - e.radius), BLOCK), by1 = Math.floorDiv(LightBake.cell(e.y + e.radius), BLOCK);
		int bz0 = Math.floorDiv(LightBake.cell(e.z - e.radius), BLOCK), bz1 = Math.floorDiv(LightBake.cell(e.z + e.radius), BLOCK);
		if (bx0 < originBx || bx1 >= originBx + BLOCKS_X || by0 < originBy || by1 >= originBy + BLOCKS_Y
			|| bz0 < originBz || bz1 >= originBz + BLOCKS_Z)
		{
			return false;
		}
		for (int by = by0; by <= by1; ++by)
		{
			for (int bz = bz0; bz <= bz1; ++bz)
			{
				for (int bx = bx0; bx <= bx1; ++bx)
				{
					if (committed[slotOf(bx, by, bz)] < e.epoch)
					{
						return false;
					}
				}
			}
		}
		return true;
	}

	/** Lights in the registry. */
	int lightCount()
	{
		return entries.size();
	}

	/** Blocks waiting for a bake or being baked. */
	int pendingBlocks()
	{
		int n = 0;
		for (int i = 0; i < BLOCK_COUNT; ++i)
		{
			if (dirty[i] || inFlight[i])
			{
				++n;
			}
		}
		return n + finished.size();
	}

	/** Blocks baked, counted in the window. */
	int bakedBlocks()
	{
		int n = 0;
		for (int c : committed)
		{
			if (c > 0)
			{
				++n;
			}
		}
		return n;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Baking

	private ExecutorService executor()
	{
		if (workers == null)
		{
			workerCount = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2));
			workers = Executors.newFixedThreadPool(workerCount, r ->
			{
				Thread t = new Thread(r, "RuneLight light bake");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
		}
		return workers;
	}

	/** Stop the workers. */
	void free()
	{
		if (workers != null)
		{
			workers.shutdownNow();
			workers = null;
		}
		reset();
		queued = 0;
		Arrays.fill(inFlight, false);
		finished.clear();
	}

	private void schedule(float focalX, float focalY, float focalZ)
	{
		if (grid == null || !placed)
		{
			return;
		}
		executor();
		float fx = focalX / CELL, fy = focalY / CELL, fz = focalZ / CELL;
		while (queued < workerCount * QUEUE_PER_WORKER)
		{
			// the nearest block that wants a bake
			int best = -1;
			float bestDistance = Float.MAX_VALUE;
			for (int slot = 0; slot < BLOCK_COUNT; ++slot)
			{
				if (!dirty[slot] || inFlight[slot])
				{
					continue;
				}
				int yi = slot / (BLOCKS_Z * BLOCKS_X);
				float dx = (slotBx[slot] * BLOCK + BLOCK * 0.5f) - fx;
				float dy = ((originBy + yi) * BLOCK + BLOCK * 0.5f) - fy;
				float dz = (slotBz[slot] * BLOCK + BLOCK * 0.5f) - fz;
				float distance = dx * dx + dy * dy * 4 + dz * dz;
				if (distance < bestDistance)
				{
					bestDistance = distance;
					best = slot;
				}
			}
			if (best < 0)
			{
				return;
			}
			submit(best);
		}
	}

	private void submit(int slot)
	{
		int yi = slot / (BLOCKS_Z * BLOCKS_X);
		int bx = slotBx[slot], by = originBy + yi, bz = slotBz[slot];
		int x0 = bx * BLOCK - APRON, y0 = by * BLOCK - APRON, z0 = bz * BLOCK - APRON;
		List<LightBake.Light> reaching = new ArrayList<>();
		for (Entry e : entries.values())
		{
			LightBake.Light l = e.light;
			if (l.maxX >= x0 && l.minX < x0 + REGION && l.maxY >= y0 && l.minY < y0 + REGION && l.maxZ >= z0
				&& l.minZ < z0 + REGION)
			{
				reaching.add(l);
			}
		}
		LightBake.Light[] lights = reaching.toArray(new LightBake.Light[0]);
		VoxelGrid g = grid;
		int gen = generation, epoch = registryEpoch;
		dirty[slot] = false;
		inFlight[slot] = true;
		++queued;
		if (runBlocks == 0 && runStart == 0)
		{
			runStart = System.nanoTime();
		}
		executor().execute(() ->
		{
			Result r;
			long start = System.nanoTime();
			try
			{
				if (lights.length == 0)
				{
					r = new Result(gen, slot, bx, by, bz, epoch, null, null, 0, 0);
				}
				else
				{
					int n = REGION * REGION * REGION * 4;
					short[] a = new short[n], b = new short[n];
					LightBake.bake(g, lights, lights.length, x0, y0, z0, REGION, REGION, REGION, a, b);
					r = new Result(gen, slot, bx, by, bz, epoch, a, b, System.nanoTime() - start, lights.length);
				}
			}
			catch (RuntimeException ex)
			{
				log.warn("Light bake failed", ex);
				// an empty block: better dark than the old path doubled, and it is baked again when anything changes
				r = new Result(gen, slot, bx, by, bz, epoch, null, null, 0, 0);
			}
			finished.add(r);
		});
	}

	/**
	 * Take up to max finished bakes that are still wanted, to upload. Call {@link #committed} for each once it is
	 * uploaded.
	 */
	List<Result> pollFinished(int max)
	{
		List<Result> out = new ArrayList<>();
		Result r;
		while (out.size() < max && (r = finished.poll()) != null)
		{
			--queued;
			// the bake is no longer under way, though what it made may not be wanted any more
			if (r.generation == generation)
			{
				inFlight[r.slot] = false;
				runBlocks++;
				runCpuNanos += r.nanos;
				if (slotOf(r.bx, r.by, r.bz) == r.slot && slotBx[r.slot] == r.bx && slotBz[r.slot] == r.bz)
				{
					out.add(r);
				}
			}
		}
		if (queued < 0)
		{
			queued = 0;
		}
		return out;
	}

	/** A bake from {@link #pollFinished} is in the texture: its block now holds the epoch. */
	void committed(Result r)
	{
		int slot = slotOf(r.bx, r.by, r.bz);
		if (r.generation == generation && slot == r.slot)
		{
			committed[slot] = r.epoch;
		}
	}

	/** Log a run of bakes when it is over. */
	private void report()
	{
		if (runBlocks == 0 || !log.isDebugEnabled())
		{
			return;
		}
		for (int i = 0; i < BLOCK_COUNT; ++i)
		{
			if (dirty[i] || inFlight[i])
			{
				return;
			}
		}
		if (!finished.isEmpty())
		{
			return;
		}
		double wall = (System.nanoTime() - runStart) / 1e6;
		long worst = 0;
		long total = 0;
		long cells = 0;
		for (Entry e : entries.values())
		{
			total += e.light.nanos();
			cells += e.light.cells();
			worst = Math.max(worst, e.light.nanos());
		}
		log.debug("Light bake: {} blocks, {} lights, {} cells, {} ms of CPU in {} ms; per light {} ms on average, {} ms at most",
			runBlocks, entries.size(), cells, String.format("%.1f", runCpuNanos / 1e6), String.format("%.1f", wall),
			String.format("%.2f", entries.isEmpty() ? 0 : total / 1e6 / entries.size()), String.format("%.2f", worst / 1e6));
		runBlocks = 0;
		runCpuNanos = 0;
		runStart = 0;
	}
}
