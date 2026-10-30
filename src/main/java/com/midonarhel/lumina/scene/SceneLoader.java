/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.scene;

import com.google.common.base.Stopwatch;
import com.midonarhel.lumina.frame.FrameRenderer;
import com.midonarhel.lumina.frame.VBO;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.scene.overrides.ModelOverrideManager;
import com.midonarhel.lumina.scene.terrain.SkyCover;
import com.midonarhel.lumina.scene.terrain.UnlitTerrainColors;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.TextureProvider;
import net.runelite.api.WorldEntity;
import net.runelite.api.WorldView;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.callback.RenderCallbackManager;
import static com.midonarhel.lumina.scene.SceneContext.NUM_ZONES;
import static com.midonarhel.lumina.scene.SceneContext.SCENE_OFFSET;
import static org.lwjgl.opengl.GL33C.GL_STATIC_DRAW;

/**
 * The loaded scenes: the top-level scene's {@link SceneContext} and one per world entity. A scene load (map-load
 * thread) builds the scene's {@link SceneData}, reuses the zones that overlap the previous scene, sizes and uploads
 * the others into freshly mapped buffers, and the swap (client thread) makes it current. Invalidated zones are
 * rebuilt once per client tick.
 */
@Slf4j
public final class SceneLoader
{
	private static final int MAX_WORLDVIEWS = 4096;

	private final Client client;
	private final ClientThread clientThread;
	private final SceneDataLoader sceneDataLoader;

	// uploaders of the client thread (zone rebuilds) and of the map-load thread (scene loads)
	private final SceneUploader clientUploader, mapUploader;

	private final SceneContext root = new SceneContext(NUM_ZONES, NUM_ZONES);
	private final SceneContext[] subs = new SceneContext[MAX_WORLDVIEWS];
	private Zone[][] nextZones;
	private Map<Integer, Integer> nextRoofChanges;
	private SceneData nextSceneData;

	public SceneLoader(Client client, ClientThread clientThread, SceneDataLoader sceneDataLoader,
		RenderCallbackManager renderCallbackManager, ModelOverrideManager modelOverrideManager)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.sceneDataLoader = sceneDataLoader;
		clientUploader = new SceneUploader(renderCallbackManager, modelOverrideManager);
		mapUploader = new SceneUploader(renderCallbackManager, modelOverrideManager);
	}

	public SceneContext root()
	{
		return root;
	}

	public SceneContext context(Scene scene)
	{
		int wvid = scene.getWorldViewId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	public SceneContext context(WorldView wv)
	{
		int wvid = wv.getId();
		if (wvid == WorldView.TOPLEVEL)
		{
			return root;
		}
		return subs[wvid];
	}

	/**
	 * Loads and swaps in the scenes already in the client (the plugin started while logged in).
	 */
	public void loadCurrentWorld()
	{
		WorldView root = client.getTopLevelWorldView();
		Scene scene = root.getScene();
		loadScene(root, scene);
		swapScene(scene);

		for (WorldEntity subEntity : root.worldEntities())
		{
			WorldView sub = subEntity.getWorldView();
			log.debug("WorldView loading: {}", sub.getId());
			loadSubScene(sub, sub.getScene());
			swapSub(sub.getScene());
		}
	}

	/**
	 * Frees the top-level scene's zones and drops the scene data (plugin shutdown, GL context current).
	 */
	public void free()
	{
		root.free();
		root.data = null;
		nextSceneData = null;
	}

	/**
	 * 117 HD maps vanilla textures to materials once the client's textures are known; uploads need the mapping.
	 */
	private void initVanillaMaterials()
	{
		TextureProvider textureProvider = client.getTextureProvider();
		if (textureProvider != null && textureProvider.getTextures() != null)
		{
			MaterialManager.initVanillaTextures(textureProvider.getTextures());
		}
	}

	public void invalidateZone(Scene scene, int zx, int zz)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		Zone z = ctx.zones[zx][zz];
		if (!z.invalidate)
		{
			z.invalidate = true;
			log.debug("Zone invalidated: wx={} x={} z={}", scene.getWorldViewId(), zx, zz);
		}
	}

	/**
	 * Rebuilds the invalidated zones of every loaded scene. Client thread, once per client tick.
	 */
	public void rebuildInvalidated()
	{
		WorldView wv = client.getTopLevelWorldView();
		if (wv == null)
		{
			return;
		}

		rebuild(wv);
		for (WorldEntity we : wv.worldEntities())
		{
			wv = we.getWorldView();
			rebuild(wv);
		}
	}

	private void rebuild(WorldView wv)
	{
		SceneContext ctx = context(wv);
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
				zone.free();
				zone = ctx.zones[x][z] = new Zone();

				Scene scene = wv.getScene();
				initVanillaMaterials();
				if (ctx.data == null || ctx.data.scene != scene)
				{
					ctx.data = sceneDataLoader.createSceneData(wv, scene, client.getExpandedMapLoading());
					// client thread: the floor definitions load directly
					if (UnlitTerrainColors.loadDefinitions(client, scene))
					{
						ctx.data.unlitTerrain = UnlitTerrainColors.compute(scene);
						ctx.data.unlitTerrain.selfCheck(scene, ctx.data.sceneOffset);
					}
					if (ctx == root)
					{
						ctx.data.initAreaHiding();
						// client thread: the wall openings resolve directly
						SkyCover.bake(ctx.data, SkyCover.openings(client, SkyCover.wallObjectIds(scene))::get);
					}
				}
				clientUploader.zoneSize(scene, ctx.data, zone, x, z);

				VBO o = null, a = null;
				int sz = zone.sizeO * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					o = new VBO(sz);
					o.init(GL_STATIC_DRAW);
					o.map();
				}

				sz = zone.sizeA * Zone.VERT_SIZE * 3;
				if (sz > 0)
				{
					a = new VBO(sz);
					a.init(GL_STATIC_DRAW);
					a.map();
				}

				zone.init(o, a);

				clientUploader.uploadZone(scene, ctx.data, zone, x, z);

				zone.unmap();
				zone.initialized = true;
				zone.dirty = true;

				log.debug("Rebuilt zone wv={} x={} z={}", wv.getId(), x, z);
			}
		}
	}

	public void loadScene(WorldView worldView, Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			loadSubScene(worldView, scene);
			return;
		}

		if (nextZones != null)
		{
			log.debug("Double zone load!");
			// The previous scene load just gets dropped, this is uncommon and requires a back to back map build packet
			// while having the first load take more than a full server cycle to complete
			CountDownLatch latch = new CountDownLatch(1);
			clientThread.invoke(() ->
			{
				for (int x = 0; x < NUM_ZONES; ++x)
				{
					for (int z = 0; z < NUM_ZONES; ++z)
					{
						Zone zone = nextZones[x][z];
						assert !zone.cull;
						// anything initialized is a reused zone and so shouldn't be freed
						if (!zone.initialized)
						{
							zone.unmap();
							zone.initialized = true;
							zone.free();
						}
					}
				}
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
			nextZones = null;
			nextRoofChanges = null;
			nextSceneData = null;
		}

		SceneContext ctx = root;
		Scene prev = client.getTopLevelWorldView().getScene();

		// shading data of the new scene, before its zones are sized and uploaded. 117 HD hides unrelated areas at
		// upload and draw time instead of removing tiles from the scene, so all tiles take part in terrain normals
		Stopwatch sceneDataTime = Stopwatch.createStarted();
		initVanillaMaterials();
		SceneData sceneData = sceneDataLoader.createSceneData(worldView, scene, client.getExpandedMapLoading());
		sceneData.initAreaHiding();
		log.debug("Scene data time {}", sceneDataTime);

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
			// 117 HD reuses zones only within the same hidden area
			&& (root.data == null || root.data.currentArea == sceneData.currentArea))
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
					mapUploader.zoneSize(scene, sceneData, zone, x, z);
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

		// the sky cover's wall openings need object definitions, resolved on the client thread below; so do the
		// floor definitions of the Vanilla colour mode's unlit terrain colours
		int[] wallIds = SkyCover.wallObjectIds(scene);
		BitSet[] openings = new BitSet[1];
		boolean[] floorDefinitions = new boolean[1];

		// allocate buffers for zones which require upload
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			openings[0] = SkyCover.openings(client, wallIds);
			floorDefinitions[0] = UnlitTerrainColors.loadDefinitions(client, scene);

			for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
			{
				for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
				{
					Zone zone = newZones[x][z];

					if (zone.initialized)
					{
						continue;
					}

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

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

		// sky visibility before the uploads read it (map-load thread; the openings were resolved on the client thread)
		sw = Stopwatch.createStarted();
		SkyCover.bake(sceneData, openings[0]::get);
		log.debug("Sky cover time {}", sw);

		// Vanilla colour mode: the unlit tile colours (map-load thread; the definitions are cached)
		if (floorDefinitions[0])
		{
			sw = Stopwatch.createStarted();
			sceneData.unlitTerrain = UnlitTerrainColors.compute(scene);
			sceneData.unlitTerrain.selfCheck(scene, sceneData.sceneOffset);
			log.debug("Unlit terrain colours time {}", sw);
		}

		// upload zones
		sw = Stopwatch.createStarted();
		for (int x = 0; x < Constants.EXTENDED_SCENE_SIZE >> 3; ++x)
		{
			for (int z = 0; z < Constants.EXTENDED_SCENE_SIZE >> 3; ++z)
			{
				Zone zone = newZones[x][z];

				if (!zone.initialized)
				{
					mapUploader.uploadZone(scene, sceneData, zone, x, z);
				}
			}
		}
		log.debug("Scene upload time {}", sw);

		nextZones = newZones;
		nextRoofChanges = roofChanges;
		nextSceneData = sceneData;
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

	private void loadSubScene(WorldView worldView, Scene scene)
	{
		int worldViewId = scene.getWorldViewId();
		assert worldViewId != -1;

		log.debug("Loading world view {}", worldViewId);

		SceneContext ctx0 = subs[worldViewId];
		if (ctx0 != null)
		{
			log.info("Reload of an already loaded worldview?");
			return;
		}

		final SceneContext ctx = new SceneContext(worldView.getSizeX() >> 3, worldView.getSizeY() >> 3);
		initVanillaMaterials();
		ctx.data = sceneDataLoader.createSceneData(worldView, scene, 0);
		subs[worldViewId] = ctx;

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];
				mapUploader.zoneSize(scene, ctx.data, zone, x, z);
			}
		}

		// allocate buffers for zones which require upload (and load the Vanilla colour mode's floor definitions)
		boolean[] floorDefinitions = new boolean[1];
		CountDownLatch latch = new CountDownLatch(1);
		clientThread.invoke(() ->
		{
			floorDefinitions[0] = UnlitTerrainColors.loadDefinitions(client, scene);
			for (int x = 0; x < ctx.sizeX; ++x)
			{
				for (int z = 0; z < ctx.sizeZ; ++z)
				{
					Zone zone = ctx.zones[x][z];

					VBO o = null, a = null;
					int sz = zone.sizeO * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						o = new VBO(sz);
						o.init(GL_STATIC_DRAW);
						o.map();
					}

					sz = zone.sizeA * Zone.VERT_SIZE * 3;
					if (sz > 0)
					{
						a = new VBO(sz);
						a.init(GL_STATIC_DRAW);
						a.map();
					}

					zone.init(o, a);
				}
			}

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

		if (floorDefinitions[0])
		{
			ctx.data.unlitTerrain = UnlitTerrainColors.compute(scene);
			ctx.data.unlitTerrain.selfCheck(scene, ctx.data.sceneOffset);
		}

		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				mapUploader.uploadZone(scene, ctx.data, zone, x, z);
			}
		}
	}

	public void despawnWorldView(WorldView worldView)
	{
		int worldViewId = worldView.getId();
		if (worldViewId != WorldView.TOPLEVEL)
		{
			log.debug("WorldView despawn: {}", worldViewId);
			var sub = subs[worldViewId];
			if (sub == null)
			{
				return;
			}

			sub.free();
			subs[worldViewId] = null;
		}
	}

	public void swapScene(Scene scene)
	{
		if (scene.getWorldViewId() != WorldView.TOPLEVEL)
		{
			swapSub(scene);
			return;
		}

		SceneContext ctx = root;
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (zone.cull)
				{
					zone.free();
				}
				else
				{
					// reused zone
					zone.updateRoofs(nextRoofChanges);
				}
			}
		}
		nextRoofChanges = null;

		ctx.zones = nextZones;
		nextZones = null;
		ctx.data = nextSceneData;
		nextSceneData = null;

		// setup vaos
		for (int x = 0; x < ctx.zones.length; ++x) // NOPMD: ForLoopCanBeForeach
		{
			for (int z = 0; z < ctx.zones[0].length; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}

		FrameRenderer.checkGLErrors();
	}

	private void swapSub(Scene scene)
	{
		SceneContext ctx = context(scene);
		if (ctx == null)
		{
			return;
		}

		// setup vaos
		for (int x = 0; x < ctx.sizeX; ++x)
		{
			for (int z = 0; z < ctx.sizeZ; ++z)
			{
				Zone zone = ctx.zones[x][z];

				if (!zone.initialized)
				{
					zone.unmap();
					zone.initialized = true;
				}
			}
		}
		log.debug("WorldView ready: {}", scene.getWorldViewId());
	}
}
