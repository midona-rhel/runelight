package com.midonarhel.runelight;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;
import java.util.stream.IntStream;
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
	private volatile int generation;

	private ExecutorService worker;
	private ForkJoinPool pool;
	private volatile SceneVoxels voxels;

	/** The newest voxels, null before the first build; a new object after every build. */
	SceneVoxels getVoxels()
	{
		return voxels;
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

	/** A new top level scene was swapped in: build its voxels. */
	void sceneSwapped()
	{
		buildRequested = true;
		buildAfterMillis = 0;
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
		if (wanted && buildRequested && now >= buildAfterMillis && scene != null && zones != null)
		{
			buildRequested = false;
			lastBuildMillis = now;
			submitBuild(scene, zones, water);
		}
	}

	/** Builds submitted so far; a change means a build started. */
	int builds()
	{
		return generation;
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
	private void submitBuild(Scene scene, Zone[][] zones, WaterMap waterMap)
	{
		int g = ++generation;
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
		SkyTerrain terrain = SkyTerrain.capture(scene, RuneLightPlugin.SCENE_OFFSET);
		int zones2 = n;
		submit(() -> build(g, list, zx, zz, zones2, baseZone, water, baseX, baseY, instance, heights, terrain));
	}

	private void build(int g, Zone[] list, int[] zx, int[] zz, int n, int baseZone, VoxelWater water,
		int baseX, int baseY, boolean instance, int[][][] heights, SkyTerrain terrain)
	{
		if (g != generation)
		{
			// a newer build is already queued
			return;
		}
		long start = System.nanoTime();
		Voxelizer.Zone[] zoneVoxels = new Voxelizer.Zone[n];
		AtomicInteger voxelized = new AtomicInteger();
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
		this.voxels = result;
		long gridTime = System.nanoTime();
		log.info("Voxel build {}: {} new / {} zones, voxelize {} ms, assemble {} ms, total {} ms; {}-unit cells, {}x{}x{}, {} MiB cells",
			g, voxelized.get(), n, (voxelTime - start) / 1_000_000.0, (gridTime - voxelTime) / 1_000_000.0,
			(gridTime - start) / 1_000_000.0, VoxelGrid.VOXEL, grid.nx, grid.ny, grid.nz,
			grid.cells.length * 4L / (1024.0 * 1024.0));
	}
}
