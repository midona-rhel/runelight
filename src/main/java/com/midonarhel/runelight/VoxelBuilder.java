package com.midonarhel.runelight;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Voxelizes the base scene for the skylight, which traces the voxels (SkyGrid). Everything runs on a worker thread:
 * after a region load the zones' triangles (captured as they upload, VoxelCapture) are voxelized, per zone so reused
 * zones keep theirs (Voxelizer), and assembled into the scene's grid (VoxelGrid) with the water's surface. The result
 * is handed to the skylight as SceneVoxels.
 */
class VoxelBuilder
{
	private static final Logger log = LoggerFactory.getLogger(VoxelBuilder.class);

	// changed zones (doors, trees cut down) are rebuilt once they have stopped changing for this long, and at most
	// this often
	static final long REBUILD_DELAY_MILLIS = 2000;
	static final long MIN_REBUILD_MILLIS = 10_000;

	private boolean buildRequested;
	private long buildAfterMillis;
	private long lastBuildMillis;
	private final AtomicInteger generation = new AtomicInteger();
	// the build started by a load for the scene it is about to swap in (buildNext), until the swap takes it (swapTo):
	// its result waits here, unseen by the drawn scene's consumers; guarded by this
	private int nextBuild;
	private SceneVoxels nextVoxels;
	private ModelBlockers nextBlockers;
	// the load's build the drawn scene was swapped to before it finished: published as soon as it does
	private int publishOnDone;

	private ExecutorService worker;
	private ForkJoinPool pool;
	private volatile SceneVoxels voxels;
	private volatile ModelBlockers modelBlockers;
	// a scene swap wants its models: -1 from the swap until its build is submitted, then that build's number; 0 once its
	// models are out (or none is wanted)
	private final AtomicInteger awaited = new AtomicInteger();

	/** The drawn scene's newest voxels, null before the first build; a new object after every build. */
	SceneVoxels getVoxels()
	{
		return voxels;
	}

	/**
	 * The newest voxels built, those of a scene being loaded among them: the skylight traces them before the swap and
	 * shows them once the scene matches (SkyGridMap).
	 */
	synchronized SceneVoxels getNewestVoxels()
	{
		return nextVoxels != null ? nextVoxels : voxels;
	}

	/** The models that stop light, null before the first build; a new object after every build. */
	ModelBlockers getModelBlockers()
	{
		return modelBlockers;
	}

	/**
	 * Whether the models of the scene just swapped in are not made yet: what getModelBlockers holds, if anything, is
	 * of a scene before.
	 */
	boolean modelsAwaited()
	{
		return awaited.get() != 0;
	}

	/** The worker. */
	void free()
	{
		if (worker != null)
		{
			worker.shutdownNow();
			pool.shutdownNow();
			worker = null;
			pool = null;
		}
		buildRequested = true;
	}

	/**
	 * Start the voxels of a scene being loaded, on the map loader's thread once its zones are uploaded (SceneLoader):
	 * they are built before the swap and handed over by swapTo.
	 *
	 * @return the build's number, for swapTo
	 */
	int buildNext(Scene scene, Zone[][] zones, WaterMap water)
	{
		int g = submitBuild(scene, zones, water, true);
		synchronized (this)
		{
			nextBuild = g;
			nextVoxels = null;
			nextBlockers = null;
		}
		return g;
	}

	/**
	 * The scene loaded with build g was swapped in, client thread: its voxels and models are the drawn scene's now if
	 * they are done, else as soon as they are; until then the old ones stand in for every consumer, and modelsAwaited
	 * says so.
	 */
	void swapTo(int g)
	{
		buildRequested = false;
		lastBuildMillis = System.currentTimeMillis();
		synchronized (this)
		{
			if (g != 0 && g == nextBuild && nextVoxels != null)
			{
				voxels = nextVoxels;
				modelBlockers = nextBlockers;
				awaited.set(0);
			}
			else if (g != 0 && g == nextBuild)
			{
				publishOnDone = g;
				awaited.set(g);
			}
			else
			{
				// no build was started for it: build now
				buildRequested = true;
				buildAfterMillis = 0;
				awaited.set(-1);
			}
			nextBuild = 0;
			nextVoxels = null;
			nextBlockers = null;
		}
	}

	/** A zone was rebuilt: rebuild the voxels once the changes settle. */
	void zonesChanged()
	{
		buildRequested = true;
		long now = System.currentTimeMillis();
		buildAfterMillis = Math.max(now + REBUILD_DELAY_MILLIS, lastBuildMillis + MIN_REBUILD_MILLIS);
	}

	/**
	 * Once a frame, on the client thread: start a build if one is due.
	 *
	 * @param wanted whether the voxels are wanted (the skylight is on)
	 * @param zones the top level scene's zones, [x][z] in extended zone coordinates
	 * @param water the scene's water map, null with none
	 */
	void prepare(boolean wanted, Scene scene, Zone[][] zones, WaterMap water)
	{
		long now = System.currentTimeMillis();
		boolean loading;
		synchronized (this)
		{
			// a load's build is in flight for the next scene: a build of the drawn one now would only delay it
			loading = nextBuild != 0;
		}
		if (wanted && buildRequested && !loading && now >= buildAfterMillis && scene != null && zones != null)
		{
			buildRequested = false;
			lastBuildMillis = now;
			submitBuild(scene, zones, water, false);
		}
	}

	/** Builds submitted so far; a change means a build started. */
	int builds()
	{
		return generation.get();
	}

	// ---------------------------------------------------------------------------------------------------------
	// worker

	private void submit(Runnable job)
	{
		if (worker == null)
		{
			worker = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "RuneLight voxels");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
			pool = new ForkJoinPool(Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 2)));
		}
		worker.execute(() ->
		{
			try
			{
				job.run();
			}
			catch (Throwable ex)
			{
				log.warn("Voxel build failed", ex);
			}
		});
	}

	/** Runs body for 0 to n - 1 on the pool and waits for all of them. */
	private void parallel(int n, IntConsumer body)
	{
		try
		{
			pool.submit(() -> IntStream.range(0, n).parallel().forEach(body)).get();
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException(ex);
		}
		catch (ExecutionException ex)
		{
			throw new RuntimeException(ex.getCause());
		}
	}

	/** Copy what the build needs from the scene, on the client thread, and queue it. */
	private int submitBuild(Scene scene, Zone[][] zones, WaterMap waterMap, boolean forNext)
	{
		int g = generation.incrementAndGet();
		if (!forNext)
		{
			awaited.compareAndSet(-1, g);
		}
		int baseZone = RuneLightPlugin.SCENE_OFFSET >> 3;
		int count = VoxelGrid.SCENE_TILES / 8 + 2;
		Zone[] list = new Zone[count * count];
		int[] zx = new int[list.length], zz = new int[list.length];
		int n = 0;
		for (int x = baseZone - 1; x < baseZone - 1 + count; ++x)
		{
			for (int z = baseZone - 1; z < baseZone - 1 + count; ++z)
			{
				if (x >= 0 && z >= 0 && x < zones.length && z < zones[x].length && zones[x][z] != null)
				{
					list[n] = zones[x][z];
					zx[n] = x;
					zz[n] = z;
					n++;
				}
			}
		}
		VoxelWater water = VoxelWater.of(waterMap, scene.getTileHeights(), RuneLightPlugin.SCENE_OFFSET);
		int baseX = scene.getBaseX(), baseY = scene.getBaseY();
		boolean instance = scene.isInstance();
		int[][][] heights = scene.getTileHeights();
		byte[][][] settings = scene.getExtendedTileSettings();
		SkyTerrain terrain = SkyTerrain.capture(scene, RuneLightPlugin.SCENE_OFFSET);
		int zones2 = n;
		submit(() -> build(g, forNext, list, zx, zz, zones2, baseZone, water, baseX, baseY, instance, heights, settings, terrain));
		return g;
	}

	/** A zone's tiles' floor heights per storey, ModelBlockers.rasterize's layout. */
	private static float[] zoneFloors(byte[][][] settings, int[][][] heights, int zoneX, int zoneZ)
	{
		float[] out = new float[ModelBlockers.PLANES * 64];
		for (int p = 0; p < ModelBlockers.PLANES; ++p)
		{
			for (int tx = 0; tx < 8; ++tx)
			{
				for (int tz = 0; tz < 8; ++tz)
				{
					out[p * 64 + tx * 8 + tz] = RoomMap.floorHeight(settings, heights, p, zoneX * 8 + tx, zoneZ * 8 + tz);
				}
			}
		}
		return out;
	}

	private void build(int g, boolean forNext, Zone[] list, int[] zx, int[] zz, int n, int baseZone, VoxelWater water,
		int baseX, int baseY, boolean instance, int[][][] heights, byte[][][] settings, SkyTerrain terrain)
	{
		if (g != generation.get())
		{
			// a newer build is already queued
			return;
		}
		long start = System.nanoTime();
		Voxelizer.Zone[] zoneVoxels = new Voxelizer.Zone[n];
		ModelBlockers.Zone[] blockerZones = new ModelBlockers.Zone[n];
		AtomicInteger voxelized = new AtomicInteger();
		// the models that stop light first, and out as soon as they are made: the light map waits for them, and the
		// voxelizing is the longer part
		parallel(n, i ->
		{
			Zone z = list[i];
			if (z.modelBlockers == null && z.voxelCapture != null)
			{
				z.modelBlockers = ModelBlockers.rasterize(z.voxelCapture, zoneFloors(settings, heights, zx[i], zz[i]));
			}
			blockerZones[i] = z.modelBlockers;
		});
		long blockerTime = System.nanoTime();
		ModelBlockers blockers = ModelBlockers.assemble(blockerZones, zx, zz, n, Constants.EXTENDED_SCENE_SIZE * LightBlockers.CELLS_PER_TILE);
		if (!forNext && g == generation.get())
		{
			// the drawn scene's: out at once, the light map waits for them and the voxelizing is the longer part
			this.modelBlockers = blockers;
			int wanted = awaited.get();
			if (wanted > 0 && g >= wanted)
			{
				awaited.compareAndSet(wanted, 0);
			}
		}
		parallel(n, i ->
		{
			// zones keep their voxels, so a zone reused by the next region load isn't voxelized again
			Zone z = list[i];
			if (z.voxels == null && z.voxelCapture != null)
			{
				z.voxels = Voxelizer.voxelize(z.voxelCapture);
				z.voxelCapture = null;
				voxelized.incrementAndGet();
			}
			zoneVoxels[i] = z.voxels;
		});
		long voxelTime = System.nanoTime();
		VoxelGrid grid = VoxelGrid.assemble(zoneVoxels, zx, zz, baseZone, water);
		SceneVoxels result = new SceneVoxels(grid, baseX, baseY, instance, heights);
		result.terrain = terrain;
		if (!forNext)
		{
			this.voxels = result;
		}
		else
		{
			synchronized (this)
			{
				if (g == publishOnDone)
				{
					// the scene was swapped in before its build finished: it is the drawn scene's now
					voxels = result;
					modelBlockers = blockers;
					publishOnDone = 0;
					awaited.compareAndSet(g, 0);
				}
				else if (g == nextBuild)
				{
					nextVoxels = result;
					nextBlockers = blockers;
				}
			}
		}
		long gridTime = System.nanoTime();
		log.info("Voxel build {}: {} new / {} zones, light blockers {} ms, voxelize {} ms, assemble {} ms, total {} ms; {}-unit cells, {}x{}x{}, {} MiB cells",
			g, voxelized.get(), n, (blockerTime - start) / 1_000_000.0, (voxelTime - blockerTime) / 1_000_000.0, (gridTime - voxelTime) / 1_000_000.0,
			(gridTime - start) / 1_000_000.0, VoxelGrid.VOXEL, grid.nx, grid.ny, grid.nz,
			grid.cells.length * 4L / (1024.0 * 1024.0));
	}
}
