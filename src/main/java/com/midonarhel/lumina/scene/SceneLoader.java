package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.shadow.ShadowPass;

import com.midonarhel.lumina.fog.FogGround;

import com.midonarhel.lumina.water.Water;
import com.midonarhel.lumina.water.WaterMap;
import com.midonarhel.lumina.water.WaterOverrides;

import com.midonarhel.lumina.*;

import com.midonarhel.lumina.dev.HitchMonitor;

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
import static com.midonarhel.lumina.LuminaPlugin.NUM_ZONES;
import static com.midonarhel.lumina.LuminaPlugin.SCENE_OFFSET;

/**
 * The one scene load routine. A load (the map loader's thread) builds everything the new scene needs as one
 * {@link SceneDerived} record: its zones (reused or uploaded, their buffers from the pool and mapped and unmapped by the
 * scheduler alongside the upload, ZoneUploads), the water and its surface mesh, the fog's ground, and it starts the
 * scene's voxel build and the drawing of its static shadow map, both before the swap. The swap (client thread) takes
 * the record in whole: the zones, water and fog ground at once, the static shadow map and the voxels if they are done
 * (else the old ones stand in for every consumer alike until they are, and they are swapped in by the job that
 * finishes them). The swap makes no GL call of its own: the freed zones' buffers go back to the pool.
 *
 * Zone rebuilds (an object changed) and the sub world views (boats) use the same upload. LuminaPlugin is the frame
 * driver: it calls the loader from its DrawCallbacks and runs the scheduler (SceneJobs) once a frame.
 */
public final class SceneLoader
{
	private static final Logger log = LoggerFactory.getLogger(SceneLoader.class);

	/** What a load builds for its scene, taken in whole by the swap. */
	public static final class SceneDerived
	{
		public final Scene scene;
		public final Zone[][] zones;
		public final Map<Integer, Integer> roofChanges;
		public final Water.Loaded water;
		public final FogGround fogGround;
		// the voxel build started for this scene (VoxelBuilder generation), 0 for none
		public int voxelBuild;
		// the static shadow map drawn for this scene before the swap, null for none
		public ShadowPass.StaticShadowBuild shadows;

		public SceneDerived(Scene scene, Zone[][] zones, Map<Integer, Integer> roofChanges, Water.Loaded water, FogGround fogGround)
		{
			this.scene = scene;
			this.zones = zones;
			this.roofChanges = roofChanges;
			this.water = water;
			this.fogGround = fogGround;
		}
	}

	private final LuminaPlugin plugin;
	private final Client client;
	private final ClientThread clientThread;
	private final LuminaConfig config;
	public final SceneJobs jobs = new SceneJobs();
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
			Thread t = new Thread(r, "Lumina zone upload");
			t.setDaemon(true);
			return t;
		});

	public SceneLoader(LuminaPlugin plugin, Client client, ClientThread clientThread, LuminaConfig config, SceneLoadReport report,
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
		report.setPool(pool);
	}

	/** Whether a load is built and waits for its swap. */
	public boolean loading()
	{
		return next != null;
	}

	/** The scene already loaded when the plugin starts: load and swap it at once, on the client thread. */
	public void startup()
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
	public void load(WorldView worldView, Scene scene)
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
		LuminaPlugin.SceneContext ctx = plugin.root;
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

		// the water's bed depths and flow, needed to size and upload the zones; its surface mesh beside the upload. The
		// map shares its work with the upload threads, which wait for the sizes (one may still be building the fog ground:
		// the others and this thread take its share)
		WaterMap waterMap = config.water()
			? WaterMap.buildShared(scene, WaterOverrides.get(), uploadThreads) : null;
		if (waterMap != null && log.isDebugEnabled())
		{
			StringBuilder parts = new StringBuilder();
			for (int k = 0; k < WaterMap.PARTS.length; ++k)
			{
				parts.append(String.format(" %s %.1f", WaterMap.PARTS[k], waterMap.partMs[k]));
			}
			log.debug("Water map parts (ms):{}", parts);
		}
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
		// the water's surface tiles, data and contact into the textures not drawn, before the swap, which flips them
		queueDerived(d);
		if (d.fogGround != null)
		{
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

	/** The water's and the fog ground's uploads of a loaded scene, as jobs before its swap. */
	private void queueDerived(SceneDerived d)
	{
		Water.Loaded water = d.water;
		jobs.add("water", SceneJobs.LOAD, deadline ->
		{
			plugin.water.uploadNext(water);
			return true;
		});
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
		}
	}

	/**
	 * A top level scene from a dump (dev/SceneDump, the offline renderer), client thread: its zones restored
	 * (ZoneSnapshot), its water map and fog ground as the load built them. Derived and queued as a load's are, and
	 * swapped in by {@link #swap}; the voxels are the dump's (VoxelBuilder.restore), not built again.
	 */
	public void restore(Scene scene, Zone[][] zones, WaterMap waterMap, FogGround fogGround)
	{
		for (Zone[] column : plugin.root.zones)
		{
			for (Zone zone : column)
			{
				zone.cull = true;
			}
		}
		plugin.mapUploader.water = waterMap;
		SceneDerived d = new SceneDerived(scene, zones, new HashMap<>(), Water.prepare(waterMap), fogGround);
		queueDerived(d);
		d.shadows = plugin.prebuildStaticShadows(scene, zones);
		next = d;
	}

	/** A sub world view's zones from a dump, drawable at once (as swapSub leaves them). */
	public void restoreSub(int worldViewId, Zone[][] zones)
	{
		LuminaPlugin.SceneContext ctx = new LuminaPlugin.SceneContext(zones.length, zones[0].length);
		ctx.zones = zones;
		for (Zone[] column : zones)
		{
			for (Zone zone : column)
			{
				zone.initialized = true;
			}
		}
		plugin.subs[worldViewId] = ctx;
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
		java.util.List<Thread> writers = new java.util.concurrent.CopyOnWriteArrayList<>();
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
		@SuppressWarnings("unchecked")
		java.util.concurrent.Future<long[]>[] running = new java.util.concurrent.Future[helpers];
		for (int h = 0; h < helpers; ++h)
		{
			SceneUploader up = uploaders[h];
			up.water = plugin.mapUploader.water;
			running[h] = uploadThreads.submit(() ->
			{
				writers.add(Thread.currentThread());
				return uploadZones(scene, list, xs, zs, nextZone, up, uploads, false, what);
			});
		}
		writers.add(Thread.currentThread());
		UploadSampler sampler = helpers > 0 ? new UploadSampler(writers) : null;
		StringBuilder threads = new StringBuilder();
		long[] own = uploadZones(scene, list, xs, zs, nextZone, plugin.mapUploader, uploads, onClientThread, what);
		threads.append(String.format("loader %d zones: wait %.0f write %.0f", own[2], own[0] / 1e6, own[1] / 1e6));
		for (java.util.concurrent.Future<long[]> f : running)
		{
			try
			{
				long[] h = f.get();
				threads.append(String.format(", helper %d: wait %.0f write %.0f", h[2], h[0] / 1e6, h[1] / 1e6));
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
		writers.clear();
		if (sampler != null)
		{
			sampler.stop();
		}
		long f0 = System.nanoTime();
		uploads.finish(onClientThread);
		if (what.equals("load"))
		{
			threads.append(String.format("; last unmaps %.0f", (System.nanoTime() - f0) / 1e6));
			if (sampler != null)
			{
				threads.append("; where the writers were: ").append(sampler.text());
			}
			report.uploadThreads(threads.toString());
		}
	}

	/** @return {nanos waiting for buffers to be mapped, nanos writing, zones written} of this thread */
	private static long[] uploadZones(Scene scene, Zone[] list, int[] xs, int[] zs, java.util.concurrent.atomic.AtomicInteger next,
		SceneUploader uploader, ZoneUploads uploads, boolean onClientThread, String what)
	{
		long[] t = new long[3];
		for (int i; (i = next.getAndIncrement()) < list.length; )
		{
			long t0 = System.nanoTime();
			uploads.mapped(i, onClientThread);
			long t1 = System.nanoTime();
			t[0] += t1 - t0;
			t[2]++;
			try
			{
				uploader.uploadZone(scene, list[i], xs[i], zs[i]);
				t[1] += System.nanoTime() - t1;
			}
			catch (RuntimeException ex)
			{
				// the load must not throw: the client then never finishes loading (2026-10-30, a zone's upload outgrew
				// its counted VBO at base (1632, 3080)); the zone loses its tail, the log names it
				log.error("Zone upload failed at zone {},{} ({}): {}", xs[i], zs[i], what, ex.toString());
			}
			uploads.uploaded(i);
		}
		return t;
	}

	/**
	 * Where the upload's threads spend their time: their stacks every 2 ms while they write, counted by their three
	 * innermost frames (the load report names the most frequent). The writers took as long together as one alone
	 * (a115b4c4: 4 threads × 633 ms for the login scene): this names what they share.
	 */
	private static final class UploadSampler implements Runnable
	{
		private final java.util.List<Thread> writers;
		private final java.util.Map<String, Integer> counts = new java.util.HashMap<>();
		private volatile boolean running = true;
		private final Thread thread;
		private int samples;

		UploadSampler(java.util.List<Thread> writers)
		{
			this.writers = writers;
			thread = new Thread(this, "Lumina upload sampler");
			thread.setDaemon(true);
			thread.start();
		}

		@Override
		public void run()
		{
			while (running)
			{
				for (Thread t : writers)
				{
					StackTraceElement[] st = t.getStackTrace();
					StringBuilder k = new StringBuilder();
					for (int i = 0; i < Math.min(3, st.length); ++i)
					{
						k.append(i > 0 ? " < " : "").append(st[i].getClassName().replaceAll(".*\\.", "")).append('.')
							.append(st[i].getMethodName()).append(':').append(st[i].getLineNumber());
					}
					synchronized (counts)
					{
						counts.merge(k.toString(), 1, Integer::sum);
						samples++;
					}
				}
				try
				{
					Thread.sleep(2);
				}
				catch (InterruptedException ex)
				{
					return;
				}
			}
		}

		void stop()
		{
			running = false;
			thread.interrupt();
		}

		String text()
		{
			synchronized (counts)
			{
				StringBuilder b = new StringBuilder();
				counts.entrySet().stream().sorted((a, c) -> c.getValue() - a.getValue()).limit(6).forEach(e ->
					b.append(b.length() > 0 ? " | " : "").append(Math.round(100.0 * e.getValue() / Math.max(1, samples)))
						.append("% ").append(e.getKey()));
				return b.toString();
			}
		}
	}

	private void loadSub(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		LuminaPlugin.SceneContext ctx0 = plugin.subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final LuminaPlugin.SceneContext ctx = new LuminaPlugin.SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
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
	public void despawn(WorldView worldView)
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
	public void swap(Scene scene)
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
		LuminaPlugin.SceneContext ctx = plugin.root;
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
		ModelUploader.water = plugin.clientUploader.water;
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
		LuminaPlugin.SceneContext ctx = plugin.context(scene);
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
	public void rebuild(WorldView wv)
	{
		LuminaPlugin.SceneContext ctx = plugin.context(wv);
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

	/** Whether scene jobs wait to run (the offline renderer runs frames until none do). */
	public boolean jobsPending()
	{
		return !jobs.idle();
	}

	/** Client thread, after the frame is presented: the jobs' share of this frame. */
	public void runJobs()
	{
		if (!jobs.idle())
		{
			plugin.hitchMonitor.job(HitchMonitor.SCENE_JOBS);
			report.extra(jobs.run(client.getGameState() == GameState.LOADING));
		}
	}

	/** Shutdown, client thread: the zones' buffers and the pool's. */
	public void free()
	{
		if (plugin.root != null)
		{
			plugin.root.free(pool, jobs);
		}
		if (plugin.subs != null)
		{
			for (LuminaPlugin.SceneContext sub : plugin.subs)
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
