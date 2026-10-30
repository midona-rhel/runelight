package com.midonarhel.runelight;

import com.google.common.base.Stopwatch;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.client.callback.ClientThread;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static com.midonarhel.runelight.RuneLightPlugin.NUM_ZONES;
import static com.midonarhel.runelight.RuneLightPlugin.SCENE_OFFSET;

/**
 * The one scene load routine. A load (the map loader's thread) builds everything the new scene needs as one
 * {@link SceneDerived} record: its zones (reused or uploaded, their buffers from the pool and mapped and unmapped by the
 * scheduler alongside the upload, ZoneUploads), the water and its surface mesh, the fog's ground, and it starts the
 * scene's voxel build and the drawing of its static shadow map, both before the swap. The swap (client thread) takes
 * the record in whole: the zones, water and fog ground at once, the static shadow map and the voxels if they are done
 * (else the old ones stand in for every consumer alike until they are, and they are swapped in by the job that
 * finishes them). The swap makes no GL call of its own: the freed zones' buffers go back to the pool.
 *
 * Zone rebuilds (an object changed) and the sub world views (boats) use the same upload. RuneLightPlugin is the frame
 * driver: it calls the loader from its DrawCallbacks and runs the scheduler (SceneJobs) once a frame.
 */
final class SceneLoader
{
	private static final Logger log = LoggerFactory.getLogger(SceneLoader.class);

	/** What a load builds for its scene, taken in whole by the swap. */
	static final class SceneDerived
	{
		final Scene scene;
		final Zone[][] zones;
		final Map<Integer, Integer> roofChanges;
		final Water.Loaded water;
		final FogGround fogGround;
		// the voxel build started for this scene (VoxelBuilder generation), 0 for none
		int voxelBuild;
		// the static shadow map drawn for this scene before the swap, null for none
		RuneLightPlugin.StaticShadowBuild shadows;

		SceneDerived(Scene scene, Zone[][] zones, Map<Integer, Integer> roofChanges, Water.Loaded water, FogGround fogGround)
		{
			this.scene = scene;
			this.zones = zones;
			this.roofChanges = roofChanges;
			this.water = water;
			this.fogGround = fogGround;
		}
	}

	private final RuneLightPlugin plugin;
	private final Client client;
	private final ClientThread clientThread;
	private final RuneLightConfig config;
	final SceneJobs jobs = new SceneJobs();
	final ZonePool pool = new ZonePool();
	private final SceneLoadReport report;

	// built by the last load, not swapped in yet; map loader thread writes, client thread takes
	private volatile SceneDerived next;

	// the zone upload runs on the map loader's thread and these: one uploader each (their scratch is their own)
	private static final int UPLOAD_THREADS = Math.max(0, Math.min(3, Runtime.getRuntime().availableProcessors() / 2 - 1));
	private final SceneUploader[] uploaders = new SceneUploader[UPLOAD_THREADS];
	private final java.util.concurrent.ExecutorService uploadThreads = UPLOAD_THREADS == 0 ? null
		: java.util.concurrent.Executors.newFixedThreadPool(UPLOAD_THREADS, r ->
		{
			Thread t = new Thread(r, "RuneLight zone upload");
			t.setDaemon(true);
			return t;
		});

	SceneLoader(RuneLightPlugin plugin, Client client, ClientThread clientThread, RuneLightConfig config, SceneLoadReport report,
		net.runelite.client.callback.RenderCallbackManager renderCallbackManager)
	{
		this.plugin = plugin;
		this.client = client;
		this.clientThread = clientThread;
		this.config = config;
		this.report = report;
		for (int i = 0; i < uploaders.length; ++i)
		{
			uploaders[i] = new SceneUploader(renderCallbackManager);
		}
	}

	/** Whether a load is built and waits for its swap. */
	boolean loading()
	{
		return next != null;
	}

	/** The scene already loaded when the plugin starts: load and swap it at once, on the client thread. */
	void startup()
	{
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		load(root, scene);
		swap(scene);

		for (WorldEntity subEntity : root.worldEntities())
		{
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSub(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	/** DrawCallbacks.loadScene: the map loader's thread (or the client thread at start-up). */
	void load(WorldView worldView, Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			loadSub(worldView, scene);
			return;
		}

		SceneDerived dropped = next;
		if (dropped != null)
		{
			log.debug("Double zone load!");
			// The previous scene load just gets dropped, this is uncommon and requires a back to back map build packet
			// while having the first load take more than a full server cycle to complete
			next = null;
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() ->
			{
				for (int x = 0; x < NUM_ZONES; ++x)
				{
					for (int z = 0; z < NUM_ZONES; ++z)
					{
						Zone zone = dropped.zones[x][z];
						assert !zone.cull;
						// anything initialized is a reused zone and so shouldn't be freed; the new ones are unmapped already
						if (!zone.initialized)
						{
							zone.free(pool, jobs);
						}
					}
				}
				plugin.dropStaticShadows(dropped.shadows);
				latch.countDown();
			});
			try
			{
				latch.await();
			}
			catch (InterruptedException e)
			{
				throw new RuntimeException(e);
			}
		}

		report.loadStarted();
		long t = System.nanoTime();
		// the fog's ground needs only the scene: built beside the rest
		java.util.concurrent.Future<FogGround> fogGroundBuild = beside(() -> FogGround.build(scene));
		RuneLightPlugin.SceneContext ctx = plugin.root;
		Scene prev = client.getTopLevelWorldView().getScene();

		plugin.regionManager.prepare(scene);
		plugin.lookMonitor.scene("load", scene);

		// loaded again where the player stands, their tile is known; a new map is built around them instead
		Player player = client.getLocalPlayer();
		boolean inPlace = player != null && scene.getBaseX() == prev.getBaseX() && scene.getBaseY() == prev.getBaseY()
			&& scene.isInstance() == prev.isInstance();
		LocalPoint playerPoint = inPlace ? player.getLocalLocation() : null;
		plugin.areaHiding.prepare(scene, config.hideOtherAreas(), client.getTopLevelWorldView().getPlane(),
			playerPoint != null ? new int[]{playerPoint.getSceneX(), playerPoint.getSceneY()} : null);

		int dx = scene.getBaseX() - prev.getBaseX() >> 3;
		int dy = scene.getBaseY() - prev.getBaseY() >> 3;

		final int SCENE_ZONES = NUM_ZONES;

		// initially mark every zone as needing culled
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				ctx.zones[x][z].cull = true;
			}
		}

		Map<Integer, Integer> roofChanges = new HashMap<>();

		// find zones which overlap and copy them
		Zone[][] newZones = new Zone[SCENE_ZONES][SCENE_ZONES];
		final GameState gameState = client.getGameState();
		if (prev.isInstance() == scene.isInstance()
			&& gameState == GameState.LOGGED_IN
			// zones built with other tiles hidden can't be reused
			&& plugin.areaHiding.sameAsShown())
		{
			int[][][] prevTemplates = prev.getInstanceTemplateChunks();
			int[][][] curTemplates = scene.getInstanceTemplateChunks();

			int[][][] prids = prev.getRoofs();
			int[][][] nrids = scene.getRoofs();

			for (int x = 0; x < SCENE_ZONES; ++x)
			{
				next:
				for (int z = 0; z < SCENE_ZONES; ++z)
				{
					int ox = x + dx;
					int oz = z + dy;

					// Reused the old zone if it is also in the new scene, except for the edges, to work around
					// tile blending, (edge) shadows, sharelight, etc.
					if (canReuse(ctx.zones, ox, oz))
					{
						if (scene.isInstance())
						{
							// Convert from modified chunk coordinates to Jagex chunk coordinates
							int jx = x - (SCENE_OFFSET / 8);
							int jz = z - (SCENE_OFFSET / 8);
							int jox = ox - (SCENE_OFFSET / 8);
							int joz = oz - (SCENE_OFFSET / 8);
							// Check Jagex chunk coordinates are within the Jagex scene
							if (jx >= 0 && jx < Constants.SCENE_SIZE / 8 && jz >= 0 && jz < Constants.SCENE_SIZE / 8)
							{
								if (jox >= 0 && jox < Constants.SCENE_SIZE / 8 && joz >= 0 && joz < Constants.SCENE_SIZE / 8)
								{
									for (int level = 0; level < 4; ++level)
									{
										int prevTemplate = prevTemplates[level][jox][joz];
										int curTemplate = curTemplates[level][jx][jz];
										if (prevTemplate != curTemplate)
										{
											log.error("Instance template reuse mismatch! prev={} cur={}", prevTemplate, curTemplate);
											continue next;
										}
									}
								}
							}
						}

						Zone old = ctx.zones[ox][oz];
						assert old.initialized;

						if (old.dirty)
						{
							continue;
						}

						assert old.sizeO > 0 || old.sizeA > 0;

						// Roof ids aren't consistent between scenes, so build a mapping of old -> new roof ids
						// Sometimes groups split or merge, so we can't copy the zone in that case
						for (int level = 0; level < 4; level++)
						{
							for (int tx = 0; tx < 8; tx++)
							{
								for (int tz = 0; tz < 8; tz++)
								{
									int prid = prids[level][(ox << 3) + tx][(oz << 3) + tz];
									int nrid = nrids[level][(x << 3) + tx][(z << 3) + tz];

									if (prid != nrid && (prid == 0 || nrid == 0))
									{
										log.trace("Roof mismatch: {} -> {}", prid, nrid);
										continue next;
									}

									Integer orid = roofChanges.putIfAbsent(prid, nrid);
									if (orid == null)
									{
										log.trace("Roof change: {} -> {}", prid, nrid);
									}
									else if (orid != nrid)
									{
										log.trace("Roof mismatch: {} -> {} vs {}", prid, nrid, orid);
										continue next;
									}
								}
							}
						}

						assert old.cull;
						old.cull = false;

						newZones[x][z] = old;
					}
				}
			}
		}

		// Fill out any zones that weren't copied
		for (int x = 0; x < SCENE_ZONES; ++x)
		{
			for (int z = 0; z < SCENE_ZONES; ++z)
			{
				if (newZones[x][z] == null)
				{
					newZones[x][z] = new Zone();
				}
			}
		}

		t = stage("reuse", t);

		// the water's bed depths and flow, needed to size and upload the zones; its surface mesh beside the upload
		WaterMap waterMap = config.water()
			? WaterMap.build(scene, WaterOverrides.get()) : null;
		plugin.mapUploader.water = waterMap;
		java.util.concurrent.Future<Water.Loaded> waterBuild = beside(() -> Water.prepare(waterMap));
		t = stage("water map", t);

		// size the zones which require upload
		Stopwatch sw = Stopwatch.createStarted();
		int len = 0, lena = 0;
		int reused = 0, newzones = 0;
		for (int x = 0; x < NUM_ZONES; ++x)
		{
			for (int z = 0; z < NUM_ZONES; ++z)
			{
				Zone zone = newZones[x][z];
				if (!zone.initialized)
				{
					assert zone.glVao == 0;
					assert zone.glVaoA == 0;
					plugin.mapUploader.zoneSize(scene, zone, x, z);
					len += zone.sizeO;
					lena += zone.sizeA;
					newzones++;
				}
				else
				{
					reused++;
				}
			}
		}
		log.debug("Scene size time {} reused {} new {} len opaque {} size opaque {}kb len alpha {} size alpha {}kb",
			sw, reused, newzones,
			len, (len * Zone.VERT_SIZE * 3) / 1024,
			lena, (lena * Zone.VERT_SIZE * 3) / 1024);

		t = stage("sizes", t);

		// upload the zones which require it; their buffers are mapped and unmapped by the scheduler alongside
		sw = Stopwatch.createStarted();
		uploadZones(scene, newZones, NUM_ZONES, NUM_ZONES, "load");
		log.debug("Scene upload time {}", sw);
		t = stage("upload", t);

		SceneDerived d = new SceneDerived(scene, newZones, roofChanges, join(waterBuild), join(fogGroundBuild));
		t = stage("water mesh + fog ground wait", t);
		if (d.fogGround != null)
		{
			// the fog's ground drawn from the swap on (its rooms come after it, and ease in): uploaded before it
			FogGround g = d.fogGround;
			float[] texels = new float[FogGround.WIDTH * FogGround.CORNERS * FogGround.CHANNELS];
			g.texels(null, null, texels);
			jobs.add("fog ground", SceneJobs.LOAD, deadline ->
			{
				plugin.volumetricFog.prepareNext(g, texels);
				return true;
			});
			t = stage("fog ground texels", t);
		}
		// what is derived from the drawn zones starts now, before the swap: the voxels on their worker, the static
		// shadow map by the scheduler
		try
		{
			d.voxelBuild = plugin.voxelBuilder.buildNext(scene, newZones, waterMap);
			d.shadows = plugin.prebuildStaticShadows(scene, newZones);
		}
		catch (RuntimeException | AssertionError ex)
		{
			// the load must not fail for them: the swap then builds what is missing after it
			log.error("Scene load: starting the voxels or the static shadows failed", ex);
		}
		stage("derived starts", t);
		next = d;
		report.loadReturned();
		plugin.loadBuilt();
	}

	private long stage(String name, long since)
	{
		long now = System.nanoTime();
		report.stage(name, (now - since) / 1e6);
		return now;
	}

	/** Run a part of the load on an upload thread while the loader goes on (inline without one). */
	private <T> java.util.concurrent.Future<T> beside(java.util.concurrent.Callable<T> part)
	{
		if (uploadThreads != null)
		{
			return uploadThreads.submit(part);
		}
		java.util.concurrent.CompletableFuture<T> done = new java.util.concurrent.CompletableFuture<>();
		try
		{
			done.complete(part.call());
		}
		catch (Exception ex)
		{
			done.completeExceptionally(ex);
		}
		return done;
	}

	private static <T> T join(java.util.concurrent.Future<T> part)
	{
		try
		{
			return part.get();
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new RuntimeException(ex);
		}
		catch (java.util.concurrent.ExecutionException ex)
		{
			throw new RuntimeException(ex.getCause());
		}
	}

	private static boolean canReuse(Zone[][] zones, int zx, int zz)
	{
		// For tile blending, sharelight, and shadows to work correctly, the zones surrounding
		// the zone must be valid.
		for (int x = zx - 1; x <= zx + 1; ++x)
		{
			if (x < 0 || x >= NUM_ZONES)
			{
				return false;
			}
			for (int z = zz - 1; z <= zz + 1; ++z)
			{
				if (z < 0 || z >= NUM_ZONES)
				{
					return false;
				}
				Zone zone = zones[x][z];
				if (!zone.initialized)
				{
					return false;
				}
				if (zone.sizeO == 0 && zone.sizeA == 0)
				{
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * Upload the zones not initialized (not reused), on the loading thread: the scheduler maps their buffers ahead of
	 * the upload and unmaps them behind it (ZoneUploads), so they are ready to draw at the swap, which does no GL work.
	 */
	private void uploadZones(Scene scene, Zone[][] zones, int sizeX, int sizeZ, String what)
	{
		int n = 0;
		for (int x = 0; x < sizeX; ++x)
		{
			for (int z = 0; z < sizeZ; ++z)
			{
				if (!zones[x][z].initialized)
				{
					n++;
				}
			}
		}
		Zone[] list = new Zone[n];
		int[] xs = new int[n], zs = new int[n];
		n = 0;
		for (int x = 0; x < sizeX; ++x)
		{
			for (int z = 0; z < sizeZ; ++z)
			{
				if (!zones[x][z].initialized)
				{
					list[n] = zones[x][z];
					xs[n] = x;
					zs[n] = z;
					n++;
				}
			}
		}
		boolean onClientThread = client.isClientThread();
		ZoneUploads uploads = new ZoneUploads(list, pool, jobs, clientThread,
			() -> Interactables.resolve(plugin::hasAction),
			r ->
			{
				if (what.equals("load"))
				{
					report.preSwap(r[0], (int) r[1]);
				}
			});
		uploads.start(onClientThread);
		// zones are independent: the loader and the upload threads take them in turn (one SceneUploader each)
		java.util.concurrent.atomic.AtomicInteger nextZone = new java.util.concurrent.atomic.AtomicInteger();
		int helpers = onClientThread || n < 16 ? 0 : uploaders.length;
		java.util.concurrent.Future<?>[] running = new java.util.concurrent.Future<?>[helpers];
		for (int h = 0; h < helpers; ++h)
		{
			SceneUploader up = uploaders[h];
			up.water = plugin.mapUploader.water;
			running[h] = uploadThreads.submit(() -> uploadZones(scene, list, xs, zs, nextZone, up, uploads, false, what));
		}
		uploadZones(scene, list, xs, zs, nextZone, plugin.mapUploader, uploads, onClientThread, what);
		for (java.util.concurrent.Future<?> f : running)
		{
			try
			{
				f.get();
			}
			catch (InterruptedException ex)
			{
				Thread.currentThread().interrupt();
				throw new RuntimeException(ex);
			}
			catch (java.util.concurrent.ExecutionException ex)
			{
				log.error("Zone upload thread failed ({})", what, ex.getCause());
			}
		}
		uploads.finish(onClientThread);
	}

	private static void uploadZones(Scene scene, Zone[] list, int[] xs, int[] zs, java.util.concurrent.atomic.AtomicInteger next,
		SceneUploader uploader, ZoneUploads uploads, boolean onClientThread, String what)
	{
		for (int i; (i = next.getAndIncrement()) < list.length; )
		{
			uploads.mapped(i, onClientThread);
			try
			{
				uploader.uploadZone(scene, list[i], xs[i], zs[i]);
			}
			catch (RuntimeException ex)
			{
				// the load must not throw: the client then never finishes loading (2026-10-30, a zone's upload outgrew
				// its counted VBO at base (1632, 3080)); the zone loses its tail, the log names it
				log.error("Zone upload failed at zone {},{} ({}): {}", xs[i], zs[i], what, ex.toString());
			}
			uploads.uploaded(i);
		}
	}

	private void loadSub(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		RuneLightPlugin.SceneContext ctx0 = plugin.subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final RuneLightPlugin.SceneContext ctx = new RuneLightPlugin.SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		plugin.subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				plugin.mapUploader.zoneSize(scene, zone, x, z);
			}
		}

		uploadZones(scene, ctx.zones, ctx.sizeX, ctx.sizeZ, "sub view");
	}

	/** DrawCallbacks.despawnWorldView, client thread. */
	void despawn(WorldView worldView)
	{
		int worldViewId = worldView.getId();
		if (worldViewId != WorldView.TOPLEVEL)
		{
			log.debug("WorldView despawn: {}", worldViewId);
			var sub = plugin.subs[worldViewId];
			if (sub == null)
			{
				return;
			}

			sub.free(pool, jobs);
			plugin.subs[worldViewId] = null;
		}
	}

	/**
	 * DrawCallbacks.swapScene, client thread: the loaded record becomes the drawn scene, in whole. No GL call: the new
	 * zones were unmapped during the load, the freed ones' buffers go back to the pool.
	 */
	void swap(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			swapSub(scene);
			return;
		}

		plugin.sceneSwapStarted();
		long t0 = System.nanoTime();
		SceneDerived d = next;
		if (d == null)
		{
			// the load failed (an exception in loadScene): keep the previous zones rather than crash the client
			log.error("swapScene without a loaded scene: keeping the previous zones");
			return;
		}
		next = null;
		plugin.areaHiding.swapped();
		RuneLightPlugin.SceneContext ctx = plugin.root;
		int freed = 0, reused = 0;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
				{
					// its buffers go to the pool: no GL call (ZonePool)
					zone.free(pool, jobs);
					freed++;
				}
				else
				{
					// reused zone
					zone.updateRoofs(d.roofChanges);
					reused++;
				}
			}
		}
		long tFree = System.nanoTime();
		plugin.lookMonitor.scene("swap", scene);

		// the new zones were unmapped during the load (ZoneUploads): they are ready to draw
		for (Zone[] column : d.zones)
		{
			for (Zone zone : column)
			{
				zone.initialized = true;
			}
		}
		ctx.zones = d.zones;
		plugin.clientUploader.water = plugin.mapUploader.water;
		long tZones = System.nanoTime();
		plugin.sceneSwapped(d);

		long tEnd = System.nanoTime();
		double total = (tEnd - t0) / 1e6;
		report.swapped(total, freed, reused);
		if (total > 1)
		{
			log.info(String.format("swapScene %.2f ms: zones freed to the pool %.2f (%d freed, %d reused), zones in %.2f, derived data %.2f",
				total, (tFree - t0) / 1e6, freed, reused, (tZones - tFree) / 1e6, (tEnd - tZones) / 1e6));
		}
	}

	private void swapSub(Scene scene)
	{
		RuneLightPlugin.SceneContext ctx = plugin.context(scene);
		if (ctx == null)
		{
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				// unmapped during the load (ZoneUploads)
				ctx.zones[x][z].initialized = true;
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}

	private VBO mapZoneBuffer(int faces)
	{
		int bytes = faces * Zone.VERT_SIZE * 3;
		if (bytes <= 0)
		{
			return null;
		}
		VBO vbo = pool.acquire(bytes);
		vbo.map();
		return vbo;
	}

	/** The zones the client changed (invalidateZone) uploaded again, client thread, in the tick. */
	void rebuild(WorldView wv)
	{
		RuneLightPlugin.SceneContext ctx = plugin.context(wv);
		if (ctx == null)
		{
			return;
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				if (!zone.invalidate)
				{
					continue;
				}

				assert zone.initialized;
				zone.free(pool, jobs);
				zone = ctx.zones[x][z] = new Zone();

				Scene scene = wv.getScene();
				plugin.clientUploader.zoneSize(scene, zone, x, z);
				Interactables.resolve(plugin::hasAction);
				// buffers from the pool, the ones just freed among them (ZonePool)
				zone.init(mapZoneBuffer(zone.sizeO), mapZoneBuffer(zone.sizeA));

				try
				{
					plugin.clientUploader.uploadZone(scene, zone, x, z);
				}
				catch (RuntimeException ex)
				{
					// a zone whose upload outgrows its counted size (java.nio.BufferOverflowException, 2026-10-30, a teleport
					// destination) took the client down in swapScene, then left it loading forever: the rest of that zone is
					// lost, the scene loads, and the log names the zone
					log.error("Zone upload failed at zone {},{}: {}", x, z, ex.toString());
				}

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;
				if (ctx == plugin.root)
				{
					plugin.zoneRebuilt();
				}
			}
		}
	}

	/** Client thread, after the frame is presented: the jobs' share of this frame. */
	void runJobs()
	{
		if (!jobs.idle())
		{
			plugin.hitchMonitor.job(HitchMonitor.SCENE_JOBS);
			report.extra(jobs.run(client.getGameState() == GameState.LOADING));
		}
	}

	/** Shutdown, client thread: the zones' buffers and the pool's. */
	void free()
	{
		if (plugin.root != null)
		{
			plugin.root.free(pool, jobs);
		}
		if (plugin.subs != null)
		{
			for (RuneLightPlugin.SceneContext sub : plugin.subs)
			{
				if (sub != null)
				{
					sub.free(pool, jobs);
				}
			}
		}
		jobs.clear();
		pool.free();
		next = null;
		if (uploadThreads != null)
		{
			uploadThreads.shutdownNow();
		}
	}
}
