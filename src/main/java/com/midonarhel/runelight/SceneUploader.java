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
package com.midonarhel.runelight;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.HashSet;
import java.util.Set;
import net.runelite.api.Constants;
import net.runelite.api.DecorativeObject;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Perspective;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;
import net.runelite.client.callback.RenderCallbackManager;

class SceneUploader
{
	private static final Logger log = LoggerFactory.getLogger(SceneUploader.class);

	private final int[] modelLocalXI;
	private final int[] modelLocalYI;
	private final int[] modelLocalZI;
	// per vertex Wind code of the model being uploaded
	private final int[] swayCodes;
	// swaying objects of the current level and roof, uploaded after its other tiles so they form one range
	private final java.util.ArrayList<SwayObject> swayDeferred = new java.util.ArrayList<>();
	private boolean swayFlushing;

	private final float[] u, v;

	private final RenderCallbackManager renderCallbackManager;
	private int basex, basez, rid, level;
	// Static objects standing in the water: the game's end them at its water level, which the bed was sunk below, so
	// their faces with an edge within WATERLINE of their ground level (model y 0) are extruded down to the bed, along
	// their own texture, when the object reaches at least STANDING_HEIGHT above it (lily pads and the like lie on it).
	private static final int WATERLINE = 3;
	private static final int STANDING_HEIGHT = 24;
	// how far the object's ground may be from the water's level (the game places it by the tile's height at its middle)
	private static final int WATER_HEIGHT_SLACK = 16;
	private boolean sinkWater;
	private Scene scene;
	private final int[] objectWorld = new int[3];
	private final boolean[] atWaterLevel = new boolean[ModelUploader.MAX_VERTEX_COUNT];
	// faces of the last static model left out as the game's ripples (117 HD's, WaterOverrides); Zone.addAlphaModel has
	// to skip them as well
	private final boolean[] rippleFace = new boolean[ModelUploader.MAX_FACE_COUNT];
	private final GroundBlender groundBlender = new GroundBlender();
	private final float[] slope = new float[2];
	private final float[] tileSlopes = new float[8];
	// the corner slopes of the surface as drawn, the water bed sunk in (tileSlopes: as vanilla lit it)
	private final float[] surfaceSlopes = new float[8];
	// the scene's water, null when water is off; set before uploading (WaterMap)
	WaterMap water;
	private WaterMap sizeWater;
	private boolean[] landVertex = new boolean[64];
	// the color a shore tile model's land faces give each vertex, -1 where none does (see markLandVertices)
	private int[] landColor = new int[64];

	SceneUploader(RenderCallbackManager renderCallbackManager)
	{
		this.renderCallbackManager = renderCallbackManager;
		modelLocalXI = new int[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalYI = new int[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalZI = new int[ModelUploader.MAX_VERTEX_COUNT];
		swayCodes = new int[ModelUploader.MAX_VERTEX_COUNT];
		u = new float[3];
		v = new float[3];
	}

	void zoneSize(Scene scene, Zone zone, int mzx, int mzz)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		sizeWater = water != null && scene.getWorldViewId() == WorldView.TOPLEVEL ? water : null;

		for (int z = 3; z >= 0; --z)
		{
			for (int xoff = 0; xoff < 8; ++xoff)
			{
				for (int zoff = 0; zoff < 8; ++zoff)
				{
					Tile t = tiles[z][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (t != null)
					{
						zoneSize(zone, t);
					}
				}
			}
		}
	}

	void uploadZone(Scene scene, Zone zone, int mzx, int mzz)
	{
		int[][][] roofs = scene.getRoofs();
		Set<Integer> roofIds = new HashSet<>();

		// the top level scene's opaque triangles are also kept for the voxels the skylight traces
		VoxelCapture.Buffer voxelBuffer = zone.vboO != null && scene.getWorldViewId() == WorldView.TOPLEVEL
			? new VoxelCapture.Buffer(zone.vboO.vb) : null;
		var vb = voxelBuffer != null ? voxelBuffer : zone.vboO != null ? new GpuIntBuffer(zone.vboO.vb) : null;
		var ab = zone.vboA != null ? new GpuIntBuffer(zone.vboA.vb) : null;
		zone.swayRangeCount = 0;

		for (int level = 0; level <= 3; ++level)
		{
			for (int xoff = 0; xoff < 8; ++xoff)
			{
				for (int zoff = 0; zoff < 8; ++zoff)
				{
					int rid = roofs[level][(mzx << 3) + xoff][(mzz << 3) + zoff];
					if (rid > 0)
					{
						roofIds.add(rid);
					}
				}
			}
		}

		// built with blending off too: the water bed takes the bank's colors from it either way (shoreColor)
		groundBlender.build(scene, mzx, mzz);

		zone.rids = new int[4][roofIds.size()];
		zone.roofStart = new int[4][roofIds.size()];
		zone.roofEnd = new int[4][roofIds.size()];

		for (int level = 0; level <= 3; ++level)
		{
			this.level = level;

			if (level == 0)
			{
				uploadZoneLevel(scene, zone, mzx, mzz, level, false, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, level, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 1, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 2, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 3, true, roofIds, vb, ab);
			}
			else
			{
				uploadZoneLevel(scene, zone, mzx, mzz, level, false, roofIds, vb, ab);
			}

			if (zone.vboO != null)
			{
				int pos = zone.vboO.vb.position();
				zone.levelOffsets[level] = pos;
			}
		}

		if (voxelBuffer != null)
		{
			voxelBuffer.capture.trim();
			zone.voxelCapture = voxelBuffer.capture;
			zone.voxels = null;
			zone.modelBlockers = null;
		}
	}

	private void uploadZoneLevel(Scene scene, Zone zone, int mzx, int mzz, int level, boolean visbelow, Set<Integer> roofIds, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		int ridx = 0;

		// upload the roofs and save their positions
		for (int id : roofIds)
		{
			int pos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			uploadZoneLevelRoof(scene, zone, mzx, mzz, level, id, visbelow, vb, ab);

			int endpos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			if (endpos > pos)
			{
				zone.rids[level][ridx] = id;
				zone.roofStart[level][ridx] = pos;
				zone.roofEnd[level][ridx] = endpos;
				++ridx;
			}
		}

		// upload everything else
		uploadZoneLevelRoof(scene, zone, mzx, mzz, level, 0, visbelow, vb, ab);
	}

	private void uploadZoneLevelRoof(Scene scene, Zone zone, int mzx, int mzz, int level, int roofId, boolean visbelow, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		byte[][][] settings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();
		Tile[][][] tiles = scene.getExtendedTiles();

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? RuneLightPlugin.SCENE_OFFSET >> 3 : 0;
		sinkWater = water != null && scene.getWorldViewId() == WorldView.TOPLEVEL;
		this.scene = scene;
		this.basex = (mzx - offset) << 10;
		this.basez = (mzz - offset) << 10;

		for (int xoff = 0; xoff < 8; ++xoff)
		{
			for (int zoff = 0; zoff < 8; ++zoff)
			{
				int msx = (mzx << 3) + xoff;
				int msz = (mzz << 3) + zoff;

				boolean isbridge = (settings[1][msx][msz] & Constants.TILE_FLAG_BRIDGE) != 0;
				int maplevel = level;
				if (isbridge)
				{
					++maplevel;
				}

				boolean isvisbelow = maplevel <= 3 && (settings[maplevel][msx][msz] & Constants.TILE_FLAG_VIS_BELOW) != 0;
				if (isvisbelow != visbelow)
				{
					continue;
				}

				int rid;
				if (isvisbelow || maplevel == 0)
				{
					rid = 0;
				}
				else
				{
					rid = roofs[maplevel - 1][msx][msz];
				}

				if (rid == roofId)
				{
					Tile t = tiles[level][msx][msz];
					if (t != null)
					{
						this.rid = rid;
						uploadZoneTile(scene, zone, t, vb, ab);
					}
				}
			}
		}
		flushSway(zone, vb, ab);
	}

	/**
	 * Upload the swaying objects held back by {@link #uploadZoneRenderable} in one run, which the shadow pass
	 * redraws every frame while the rest of the zone stays in the static shadow cache.
	 */
	private void flushSway(Zone zone, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		if (swayDeferred.isEmpty())
		{
			return;
		}
		int savedRid = rid;
		int start = zone.vboO != null ? zone.vboO.vb.position() : 0;
		swayFlushing = true;
		try
		{
			for (SwayObject o : swayDeferred)
			{
				rid = o.rid;
				uploadZoneRenderable(o.r, zone, o.orient, o.x, o.y, o.z, o.lx, o.lz, o.ux, o.uz, o.id, vb, ab);
			}
		}
		finally
		{
			swayFlushing = false;
			swayDeferred.clear();
			rid = savedRid;
		}
		int end = zone.vboO != null ? zone.vboO.vb.position() : 0;
		zone.addSwayRange(start, end);
	}

	private void zoneSize(Zone z, Tile t)
	{
		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null)
		{
			z.sizeO += 2;
			if (sizeWater != null)
			{
				Point p = t.getSceneLocation();
				int ex = p.getX() + RuneLightPlugin.SCENE_OFFSET, ey = p.getY() + RuneLightPlugin.SCENE_OFFSET;
				if (sizeWater.isBed(ex, ey, t.getRenderLevel(), paint.getTexture()) && sizeWater.isShoreTile(ex, ey))
				{
					z.sizeO += 2;
				}
			}
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null)
		{
			z.sizeO += model.getFaceX().length;
		}

		WallObject wallObject = t.getWallObject();
		if (wallObject != null)
		{
			zoneRenderableSize(z, wallObject.getRenderable1(), wallObject.getId());
			zoneRenderableSize(z, wallObject.getRenderable2(), wallObject.getId());
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null)
		{
			zoneRenderableSize(z, decorativeObject.getRenderable(), decorativeObject.getId());
			zoneRenderableSize(z, decorativeObject.getRenderable2(), decorativeObject.getId());
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null)
		{
			zoneRenderableSize(z, groundObject.getRenderable(), groundObject.getId());
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects)
		{
			if (gameObject == null)
			{
				continue;
			}

			if (!gameObject.getSceneMinLocation().equals(t.getSceneLocation()))
			{
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			zoneRenderableSize(z, renderable, gameObject.getId());
		}

		Tile bridge = t.getBridge();
		if (bridge != null)
		{
			zoneSize(z, bridge);
		}
	}

	private int uploadZoneTile(Scene scene, Zone zone, Tile t, GpuIntBuffer vertexBuffer, GpuIntBuffer ab)
	{
		int len = 0;
		boolean drawTile = renderCallbackManager.drawTile(scene, t);

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null && drawTile)
		{
			Point tilePoint = t.getSceneLocation();
			len = upload(scene, paint,
				t.getRenderLevel(), tilePoint.getX(), tilePoint.getY(),
				vertexBuffer,
				tilePoint.getX() * 128 - basex, tilePoint.getY() * 128 - basez
			);
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null && drawTile)
		{
			Point tilePoint = t.getSceneLocation();
			len += upload(scene, model, t.getRenderLevel(), tilePoint.getX(), tilePoint.getY(), vertexBuffer);
		}

		WallObject wallObject = t.getWallObject();
		if (wallObject != null && renderCallbackManager.drawObject(scene, wallObject))
		{
			Renderable renderable1 = wallObject.getRenderable1();
			uploadZoneRenderable(renderable1, zone, 0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), -1, -1, -1, -1, wallObject.getId(), vertexBuffer, ab);

			Renderable renderable2 = wallObject.getRenderable2();
			uploadZoneRenderable(renderable2, zone, 0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), -1, -1, -1, -1, wallObject.getId(), vertexBuffer, ab);
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null && renderCallbackManager.drawObject(scene, decorativeObject))
		{
			// what hangs on a wall lies flat on it: drawn in front of it, as the game draws it after the wall
			objectBias = DECORATION_BIAS;
			Renderable renderable = decorativeObject.getRenderable();
			uploadZoneRenderable(renderable, zone, 0, decorativeObject.getX() + decorativeObject.getXOffset(), decorativeObject.getZ(), decorativeObject.getY() + decorativeObject.getYOffset(), -1, -1, -1, -1, decorativeObject.getId(), vertexBuffer, ab);

			Renderable renderable2 = decorativeObject.getRenderable2();
			uploadZoneRenderable(renderable2, zone, 0, decorativeObject.getX() + decorativeObject.getXOffset2(), decorativeObject.getZ(), decorativeObject.getY() + decorativeObject.getYOffset2(), -1, -1, -1, -1, decorativeObject.getId(), vertexBuffer, ab);
			objectBias = 0;
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null && renderCallbackManager.drawObject(scene, groundObject))
		{
			Renderable renderable = groundObject.getRenderable();
			uploadZoneRenderable(renderable, zone, 0, groundObject.getX(), groundObject.getZ(), groundObject.getY(),
				-1, -1, -1, -1,
				groundObject.getId(),
				vertexBuffer, ab);
		}

		GameObject[] gameObjects = t.getGameObjects();
		for (GameObject gameObject : gameObjects)
		{
			if (gameObject == null)
			{
				continue;
			}

			Point min = gameObject.getSceneMinLocation(), max = gameObject.getSceneMaxLocation();

			if (!min.equals(t.getSceneLocation()))
			{
				continue;
			}

			if (!renderCallbackManager.drawObject(scene, gameObject))
			{
				continue;
			}

			Renderable renderable = gameObject.getRenderable();
			uploadZoneRenderable(renderable, zone, gameObject.getModelOrientation(), gameObject.getX(), gameObject.getZ(), gameObject.getY(),
				min.getX(), min.getY(), max.getX(), max.getY(),
				gameObject.getId(),
				vertexBuffer, ab);
		}

		Tile bridge = t.getBridge();
		if (bridge != null)
		{
			len += uploadZoneTile(scene, zone, bridge, vertexBuffer, ab);
		}

		return len;
	}

	private void zoneRenderableSize(Zone z, Renderable r, int id)
	{
		// the client thread works out which objects have an action before the zone is uploaded (Interactables)
		Interactables.want(id);
		if (r instanceof DynamicObject)
		{
			var comp = ((DynamicObject) r).getRecordedObjectComposition();
			if (comp != null)
			{
				Interactables.want(comp.getId());
			}
		}
		Model m = null;
		if (r instanceof Model)
		{
			m = (Model) r;
		}
		else if (r instanceof DynamicObject)
		{
			m = ((DynamicObject) r).getModelZbuf();
		}
		if (m == null)
		{
			return;
		}

		byte[] transparencies = m.getFaceTransparencies();
		int faceCount = m.getFaceCount();
		z.sizeO += standingSize(m, transparencies);
		if (transparencies != null)
		{
			for (int face = 0; face < faceCount; ++face)
			{
				boolean alpha = transparencies[face] != 0;
				if (alpha)
				{
					z.sizeA++;
				}
				else
				{
					z.sizeO++;
				}
			}
			return;
		}
		z.sizeO += faceCount;
	}

	// at most the triangles uploadStaticModel extrudes to the bed for a static object standing in the water
	private static int standingSize(Model m, byte[] transparencies)
	{
		float[] vy = m.getVerticesY();
		boolean standing = false;
		for (int v = 0; v < m.getVerticesCount() && !standing; ++v)
		{
			standing = (int) vy[v] <= -STANDING_HEIGHT;
		}
		if (!standing)
		{
			return 0;
		}
		int[] i1 = m.getFaceIndices1(), i2 = m.getFaceIndices2(), i3 = m.getFaceIndices3();
		int n = 0;
		for (int f = 0; f < m.getFaceCount(); ++f)
		{
			if (transparencies != null && transparencies[f] != 0)
			{
				continue;
			}
			int at = (Math.abs((int) vy[i1[f]]) <= WATERLINE ? 1 : 0) + (Math.abs((int) vy[i2[f]]) <= WATERLINE ? 1 : 0)
				+ (Math.abs((int) vy[i3[f]]) <= WATERLINE ? 1 : 0);
			n += at == 2 ? 2 : 0;
		}
		return n;
	}

	private static final class SwayObject
	{
		final Renderable r;
		final int orient, x, y, z, lx, lz, ux, uz, id, rid;

		SwayObject(Renderable r, int orient, int x, int y, int z, int lx, int lz, int ux, int uz, int id, int rid)
		{
			this.r = r;
			this.orient = orient;
			this.x = x;
			this.y = y;
			this.z = z;
			this.lx = lx;
			this.lz = lz;
			this.ux = ux;
			this.uz = uz;
			this.id = id;
			this.rid = rid;
		}
	}

	private void uploadZoneRenderable(Renderable r, Zone zone, int orient, int x, int y, int z, int lx, int lz, int ux, int uz, int id, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		int material = SurfaceMaterials.forObject(id, r) | WindSway.forObject(id, r);
		if (!swayFlushing && WindSway.sways(material))
		{
			swayDeferred.add(new SwayObject(r, orient, x, y, z, lx, lz, ux, uz, id, rid));
			return;
		}
		int pos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		Model model = null;
		if (r instanceof Model)
		{
			model = (Model) r;
			uploadStaticModel(model, orient, x - basex, y, z - basez, material, id, vb, ab);
		}
		else if (r instanceof DynamicObject)
		{
			model = ((DynamicObject) r).getModelZbuf();
			if (model != null)
			{
				uploadStaticModel(model, orient, x - basex, y, z - basez, material, id, vb, ab);
			}
		}
		int endpos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		if (endpos > pos)
		{
			assert model != null;
			if (lx > -1)
			{
				lx -= basex >> 7;
				lz -= basez >> 7;
				ux -= basex >> 7;
				uz -= basez >> 7;
				assert lx >= 0 : lx;
				assert lz >= 0 : lz;
				assert ux < 25 : ux; // largest object?
				assert uz < 25 : uz;
			}
			zone.addAlphaModel(zone.glVaoA, model, pos, endpos,
				x - basex, y, z - basez,
				lx, lz, ux, uz,
				rid, level, id, rippleFace);
		}
	}

	private int upload(Scene scene, SceneTilePaint tile, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, int lx, int lz)
	{
		tileX += scene.getWorldViewId() == WorldView.TOPLEVEL ? RuneLightPlugin.SCENE_OFFSET : 0;
		tileY += scene.getWorldViewId() == WorldView.TOPLEVEL ? RuneLightPlugin.SCENE_OFFSET : 0;

		final int[][][] tileHeights = scene.getTileHeights();
		final int swHeight = tileHeights[tileZ][tileX][tileY];
		final int seHeight = tileHeights[tileZ][tileX + 1][tileY];
		final int neHeight = tileHeights[tileZ][tileX + 1][tileY + 1];
		final int nwHeight = tileHeights[tileZ][tileX][tileY + 1];

		int swColor = tile.getSwColor();
		int seColor = tile.getSeColor();
		int neColor = tile.getNeColor();
		int nwColor = tile.getNwColor();

		if (neColor == 12345678)
		{
			return 0;
		}

		if (water != null && scene.getWorldViewId() == WorldView.TOPLEVEL && water.isBed(tileX, tileY, tileZ, tile.getTexture()))
		{
			return uploadBed(tileZ, tileX, tileY, vertexBuffer, lx, lz, tileHeights[tileZ]);
		}

		int swFlag = 0, seFlag = 0, neFlag = 0, nwFlag = 0;
		if (GroundBlender.enabled && tile.getTexture() == -1)
		{
			boolean blend = !groundBlender.unblended(tileZ, tileX, tileY);
			int[][] h = tileHeights[tileZ];
			int sw = groundBlender.lookup(tileX << 7, swHeight, tileY << 7);
			int se = groundBlender.lookup(tileX + 1 << 7, seHeight, tileY << 7);
			int ne = groundBlender.lookup(tileX + 1 << 7, neHeight, tileY + 1 << 7);
			int nw = groundBlender.lookup(tileX << 7, nwHeight, tileY + 1 << 7);
			GroundBlender.cornerSlope(h, tileX, tileY, slope);
			swColor = groundColor(swColor, sw, blend);
			GroundBlender.cornerSlope(h, tileX + 1, tileY, slope);
			seColor = groundColor(seColor, se, blend);
			GroundBlender.cornerSlope(h, tileX + 1, tileY + 1, slope);
			neColor = groundColor(neColor, ne, blend);
			GroundBlender.cornerSlope(h, tileX, tileY + 1, slope);
			nwColor = groundColor(nwColor, nw, blend);
			swFlag = borderFlag(sw);
			seFlag = borderFlag(se);
			neFlag = borderFlag(ne);
			nwFlag = borderFlag(nw);
		}

		// 0,0
		final int lx0 = lx;
		final int ly0 = swHeight;
		final int lz0 = lz;
		final int hsl0 = swColor;

		// 1,0
		final int lx1 = lx + Perspective.LOCAL_TILE_SIZE;
		final int ly1 = seHeight;
		final int lz1 = lz;
		final int hsl1 = seColor;

		// 1,1
		final int lx2 = lx + Perspective.LOCAL_TILE_SIZE;
		final int ly2 = neHeight;
		final int lz2 = lz + Perspective.LOCAL_TILE_SIZE;
		final int hsl2 = neColor;

		// 0,1
		final int lx3 = lx;
		final int ly3 = nwHeight;
		final int lz3 = lz + Perspective.LOCAL_TILE_SIZE;
		final int hsl3 = nwColor;

		int tex = tile.getTexture() + 1;
		tex |= SurfaceMaterials.texture(tex - 1) | WaterMap.LAND_FLAG;

		final int[][] surface = surfaceHeights(scene, tileZ);
		final int n0 = terrainNormal(surface, tileX, tileY);
		final int n1 = terrainNormal(surface, tileX + 1, tileY);
		final int n2 = terrainNormal(surface, tileX + 1, tileY + 1);
		final int n3 = terrainNormal(surface, tileX, tileY + 1);

		vertexBuffer.put22224(lx2, ly2, lz2, neFlag, hsl2);
		vertexBuffer.put2222(tex, 256, 256, n2);

		vertexBuffer.put22224(lx3, ly3, lz3, nwFlag, hsl3);
		vertexBuffer.put2222(tex, 0, 256, n3);

		vertexBuffer.put22224(lx1, ly1, lz1, seFlag, hsl1);
		vertexBuffer.put2222(tex, 256, 0, n1);

		vertexBuffer.put22224(lx0, ly0, lz0, swFlag, hsl0);
		vertexBuffer.put2222(tex, 0, 0, n0);

		vertexBuffer.put22224(lx1, ly1, lz1, seFlag, hsl1);
		vertexBuffer.put2222(tex, 256, 0, n1);

		vertexBuffer.put22224(lx3, ly3, lz3, nwFlag, hsl3);
		vertexBuffer.put2222(tex, 0, 256, n3);

		return 6;
	}

	/**
	 * Smooth terrain normal at a tile corner from the neighboring corner heights. y points down, so a flat
	 * tile's normal is (0, -1, 0).
	 */
	/**
	 * The heights the ground of level z is drawn at, which its normals go by: with the water bed sunk in, so the
	 * bank's normals lean into the bed as the bed's own do and they shade alike where they meet.
	 */
	private int[][] surfaceHeights(Scene scene, int z)
	{
		int[][] bed = water != null && scene.getWorldViewId() == WorldView.TOPLEVEL ? water.bedHeights(z) : null;
		return bed != null ? bed : scene.getTileHeights()[z];
	}

	private static int terrainNormal(int[][] heights, int x, int y)
	{
		int maxX = heights.length - 1, maxY = heights[0].length - 1;
		int x0 = Math.max(x - 1, 0), x1 = Math.min(x + 1, maxX);
		int y0 = Math.max(y - 1, 0), y1 = Math.min(y + 1, maxY);
		x = Math.min(Math.max(x, 0), maxX);
		y = Math.min(Math.max(y, 0), maxY);
		float dhdx = (heights[x1][y] - heights[x0][y]) / (float) ((x1 - x0) * Perspective.LOCAL_TILE_SIZE);
		float dhdz = (heights[x][y1] - heights[x][y0]) / (float) ((y1 - y0) * Perspective.LOCAL_TILE_SIZE);
		return VertexShading.encodeNormal(dhdx, -1, dhdz);
	}

	// marks a vertex where overlay and underlay ground meet, in the static format's spare position short; the
	// fragment shader narrows color transitions toward these vertices, as 117 HD does with its overlay mix
	private static final int BORDER_FLAG = -1;

	private static int borderFlag(int lookup)
	{
		return lookup != -1 && (lookup & GroundBlender.BORDER) != 0 ? BORDER_FLAG : 0;
	}

	/**
	 * The color for a ground vertex: the blended color shared with the neighboring tiles, or the tile's own,
	 * with vanilla's baked terrain lighting undone either way (for the slope in {@link #slope}).
	 */
	private int groundColor(int own, int lookup, boolean blend)
	{
		if (blend && lookup != -1 && (lookup & GroundBlender.BLENDED) != 0)
		{
			return lookup & 0xffff;
		}
		if (own < 0 || own == 12345678 || !VertexShading.undoVanillaShading)
		{
			return own;
		}
		return GroundBlender.undoShading(own, slope[0], slope[1]);
	}

	private int upload(Scene scene, SceneTileModel sceneTileModel, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer)
	{
		final int lx = tileX << 7;
		final int lz = tileY << 7;
		final int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? RuneLightPlugin.SCENE_OFFSET : 0;
		final int ex = tileX + offset, ey = tileY + offset;

		final int[] faceX = sceneTileModel.getFaceX();
		final int[] faceY = sceneTileModel.getFaceY();
		final int[] faceZ = sceneTileModel.getFaceZ();

		final int[] vertexX = sceneTileModel.getVertexX();
		final int[] vertexY = sceneTileModel.getVertexY();
		final int[] vertexZ = sceneTileModel.getVertexZ();

		final int[] triangleColorA = sceneTileModel.getTriangleColorA();
		final int[] triangleColorB = sceneTileModel.getTriangleColorB();
		final int[] triangleColorC = sceneTileModel.getTriangleColorC();

		final int[] triangleTextures = sceneTileModel.getTriangleTextureId();

		final int faceCount = faceX.length;

		// Smooth normals like plain tiles have, so a shaped tile (path edge, shore) shades the same as its neighbors
		// instead of showing its triangles. Its heights are bilinear in the tile's corner heights.
		final boolean blending = GroundBlender.enabled;
		GroundBlender.tileSlopes(scene.getTileHeights()[tileZ], ex, ey, slope, tileSlopes);
		GroundBlender.tileSlopes(surfaceHeights(scene, tileZ), ex, ey, slope, surfaceSlopes);
		// 117 HD: on shaped tiles with any texture (shores) the overlay faces keep their own colors
		final boolean blendOverlay = triangleTextures == null && !groundBlender.unblended(tileZ, ex, ey);
		final int shape = sceneTileModel.getShape();
		// shore tiles: the water faces are lowered into the bed, the land faces and their vertices stay
		final boolean bedTile = triangleTextures != null && water != null && scene.getWorldViewId() == WorldView.TOPLEVEL
			&& water.hasBed(ex, ey, tileZ);
		if (bedTile)
		{
			markLandVertices(sceneTileModel);
			bedTileHeights = scene.getTileHeights()[tileZ];
		}

		int cnt = 0;
		// on shore tiles the land faces first, so the water faces' vertices on the bank take the very color the land
		// has there (landColor)
		for (int pass = 0; pass < (bedTile ? 2 : 1); ++pass)
		for (int i = 0; i < faceCount; ++i)
		{
			final boolean bedFace = bedTile && WaterMap.isWaterTexture(triangleTextures[i]);
			if (bedFace != (pass == 1))
			{
				continue;
			}
			final int vertex0 = faceX[i];
			final int vertex1 = faceY[i];
			final int vertex2 = faceZ[i];

			int hsl0 = triangleColorA[i];
			int hsl1 = triangleColorB[i];
			int hsl2 = triangleColorC[i];

			if (hsl0 == 12345678)
			{
				continue;
			}

			cnt += 3;

			if (bedFace)
			{
				putBedVertex(vertexBuffer, vertexX[vertex0], vertexY[vertex0], vertexZ[vertex0], vertex0, lx, lz, ex, ey);
				putBedVertex(vertexBuffer, vertexX[vertex1], vertexY[vertex1], vertexZ[vertex1], vertex1, lx, lz, ex, ey);
				putBedVertex(vertexBuffer, vertexX[vertex2], vertexY[vertex2], vertexZ[vertex2], vertex2, lx, lz, ex, ey);
				continue;
			}

			// vertexes are stored in scene local, convert to tile local
			int lx0 = vertexX[vertex0] - basex;
			int ly0 = vertexY[vertex0];
			int lz0 = vertexZ[vertex0] - basez;

			int lx1 = vertexX[vertex1] - basex;
			int ly1 = vertexY[vertex1];
			int lz1 = vertexZ[vertex1] - basez;

			int lx2 = vertexX[vertex2] - basex;
			int ly2 = vertexY[vertex2];
			int lz2 = vertexZ[vertex2] - basez;

			int tex = triangleTextures != null ? triangleTextures[i] + 1 : 0;
			final int texMaterial = SurfaceMaterials.texture(tex - 1) | WaterMap.LAND_FLAG;

			int n0 = modelNormal(vertexX[vertex0] - lx, vertexZ[vertex0] - lz);
			float s0x = slope[0], s0z = slope[1];
			int n1 = modelNormal(vertexX[vertex1] - lx, vertexZ[vertex1] - lz);
			float s1x = slope[0], s1z = slope[1];
			int n2 = modelNormal(vertexX[vertex2] - lx, vertexZ[vertex2] - lz);

			int f0 = 0, f1 = 0, f2 = 0;
			if (blending && tex == 0)
			{
				boolean blend = !GroundBlender.isOverlayFace(shape, i) || blendOverlay;
				int o = offset << 7;
				int b0 = groundBlender.lookup(vertexX[vertex0] + o, ly0, vertexZ[vertex0] + o);
				int b1 = groundBlender.lookup(vertexX[vertex1] + o, ly1, vertexZ[vertex1] + o);
				int b2 = groundBlender.lookup(vertexX[vertex2] + o, ly2, vertexZ[vertex2] + o);
				hsl2 = groundColor(hsl2, b2, blend);
				slope[0] = s1x;
				slope[1] = s1z;
				hsl1 = groundColor(hsl1, b1, blend);
				slope[0] = s0x;
				slope[1] = s0z;
				hsl0 = groundColor(hsl0, b0, blend);
				f0 = borderFlag(b0);
				f1 = borderFlag(b1);
				f2 = borderFlag(b2);
			}
			if (bedTile)
			{
				landColor[vertex0] = hsl0;
				landColor[vertex1] = hsl1;
				landColor[vertex2] = hsl2;
			}

			vertexBuffer.put22224(lx0, ly0, lz0, f0, hsl0);
			vertexBuffer.put2222(tex | texMaterial, (int) ((vertexX[vertex0] - lx) * 2f), (int) ((vertexZ[vertex0] - lz) * 2f), n0);

			vertexBuffer.put22224(lx1, ly1, lz1, f1, hsl1);
			vertexBuffer.put2222(tex | texMaterial, (int) ((vertexX[vertex1] - lx) * 2f), (int) ((vertexZ[vertex1] - lz) * 2f), n1);

			vertexBuffer.put22224(lx2, ly2, lz2, f2, hsl2);
			vertexBuffer.put2222(tex | texMaterial, (int) ((vertexX[vertex2] - lx) * 2f), (int) ((vertexZ[vertex2] - lz) * 2f), n2);
		}

		return cnt;
	}

	/**
	 * A water tile as the bed under the water: lowered, no texture, bed colored, flagged for the composite.
	 */
	private int uploadBed(int tileZ, int tileX, int tileY, GpuIntBuffer vb, int lx, int lz, int[][] heights)
	{
		final int[][] bed = water.bedHeights(tileZ);
		final int x0 = tileX << 7, z0 = tileY << 7;
		final int tex = WaterMap.BED_FLAG;
		// The bed's color at each corner: the bank's where the corner is on it, grading into the bed's own over the
		// next two corners out (bedCornerColor). Per corner, never a whole tile: tiles in the bank's color showed under
		// the water as dark squares, and sand right up to a dark bank as a bright band along the shore.
		final int c0 = bedCornerColor(tileX, tileY, heights);
		final int c1 = bedCornerColor(tileX + 1, tileY, heights);
		final int c2 = bedCornerColor(tileX + 1, tileY + 1, heights);
		final int c3 = bedCornerColor(tileX, tileY + 1, heights);
		final int n0 = terrainNormal(bed, tileX, tileY);
		final int n1 = terrainNormal(bed, tileX + 1, tileY);
		final int n2 = terrainNormal(bed, tileX + 1, tileY + 1);
		final int n3 = terrainNormal(bed, tileX, tileY + 1);
		final int h0 = bed[tileX][tileY], h1 = bed[tileX + 1][tileY], h2 = bed[tileX + 1][tileY + 1], h3 = bed[tileX][tileY + 1];
		final int s = Perspective.LOCAL_TILE_SIZE;

		if (water.isShoreTile(tileX, tileY))
		{
			// Around the shore the corners stay at the surface, which would leave a narrow stream no depth at all:
			// sink the middle of the tile too, as a fan of four triangles around it.
			final int hc = (h0 + h1 + h2 + h3 + 2 >> 2) - (water.bedOffset(tileX, tileY) + water.bedOffset(tileX + 1, tileY)
				+ water.bedOffset(tileX + 1, tileY + 1) + water.bedOffset(tileX, tileY + 1) + 2 >> 2) + water.centerOffset(tileX, tileY);
			final int cc = lerpHsl(lerpHsl(c0, c1, 0.5f), lerpHsl(c3, c2, 0.5f), 0.5f);
			GroundBlender.tileSlopes(bed, tileX, tileY, slope, surfaceSlopes);
			GroundBlender.interpolateSlope(surfaceSlopes, 0.5f, 0.5f, slope);
			final int nc = VertexShading.encodeNormal(slope[0], -1, slope[1]);
			final int h = s >> 1;
			// counterclockwise around the middle: sw, se, ne, nw
			bedFan(vb, lx + h, hc, lz + h, cc, nc, lx, h0, lz, c0, n0, lx + s, h1, lz, c1, n1, tex);
			bedFan(vb, lx + h, hc, lz + h, cc, nc, lx + s, h1, lz, c1, n1, lx + s, h2, lz + s, c2, n2, tex);
			bedFan(vb, lx + h, hc, lz + h, cc, nc, lx + s, h2, lz + s, c2, n2, lx, h3, lz + s, c3, n3, tex);
			bedFan(vb, lx + h, hc, lz + h, cc, nc, lx, h3, lz + s, c3, n3, lx, h0, lz, c0, n0, tex);
			return 12;
		}

		vb.put22224(lx + s, h2, lz + s, 0, c2);
		vb.put2222(tex, 256, 256, n2);
		vb.put22224(lx, h3, lz + s, 0, c3);
		vb.put2222(tex, 0, 256, n3);
		vb.put22224(lx + s, h1, lz, 0, c1);
		vb.put2222(tex, 256, 0, n1);

		vb.put22224(lx, h0, lz, 0, c0);
		vb.put2222(tex, 0, 0, n0);
		vb.put22224(lx + s, h1, lz, 0, c1);
		vb.put2222(tex, 256, 0, n1);
		vb.put22224(lx, h3, lz + s, 0, c3);
		vb.put2222(tex, 0, 256, n3);
		return 6;
	}

	// how much of the nearby bank's color a bed corner keeps, by its distance in corners from the nearest bank corner
	private static final float[] BANK_SHARE = {1f, 0.7f, 0.35f};
	private static final int BANK_REACH = BANK_SHARE.length - 1;

	/**
	 * The bed's color at corner (cx, cy), extended coordinates: the bank's where the
	 * corner is on it, else the bed's own blended toward the color of the bank corners within BANK_REACH corners
	 * (the nearer the more, darkened with depth as the bed's own is), the more the nearer the closest of them is.
	 */
	private int bedCornerColor(int cx, int cy, int[][] heights)
	{
		int own = water.bedColor(cx, cy, water.depthLevel(cx, cy));
		int hue = 0, sat = 0, light = 0, count = 0, nearest = BANK_REACH + 1;
		int maxX = heights.length - 1, maxY = heights[0].length - 1;
		for (int dy = -BANK_REACH; dy <= BANK_REACH; ++dy)
		{
			for (int dx = -BANK_REACH; dx <= BANK_REACH; ++dx)
			{
				int x = cx + dx, y = cy + dy;
				if (x < 0 || y < 0 || x > maxX || y > maxY)
				{
					continue;
				}
				int c = shoreColor(x << 7, heights[x][y], y << 7);
				if (c < 0)
				{
					continue;
				}
				int d = Math.max(Math.abs(dx), Math.abs(dy));
				if (d == 0)
				{
					return c;
				}
				nearest = Math.min(nearest, d);
				hue += c >> 10 & 63;
				sat += c >> 7 & 7;
				light += c & 127;
				++count;
			}
		}
		if (count == 0)
		{
			return own;
		}
		int bank = (hue + count / 2) / count << 10 | (sat + count / 2) / count << 7 | (light + count / 2) / count;
		return lerpHsl(own, bank, BANK_SHARE[nearest]);
	}

	/**
	 * Packed HSL a blended toward b by t (hue the short way round).
	 */
	static int lerpHsl(int a, int b, float t)
	{
		int ha = a >> 10 & 63, hb = b >> 10 & 63;
		int dh = hb - ha;
		if (dh > 32)
		{
			dh -= 64;
		}
		else if (dh < -32)
		{
			dh += 64;
		}
		int h = Math.round(ha + dh * t) & 63;
		int s = Math.round((a >> 7 & 7) + ((b >> 7 & 7) - (a >> 7 & 7)) * t);
		int l = Math.round((a & 127) + ((b & 127) - (a & 127)) * t);
		return h << 10 | s << 7 | l;
	}

	// the unlowered heights of the level of the shore tile model being uploaded
	private int[][] bedTileHeights;

	/**
	 * The land's (blended) color at the ground vertex at extended local position (x, height, z), or -1 where no
	 * land touches it. Whether or not the land itself is blended: without it the bed took its own sandy color right
	 * up to the waterline, a pale band all along the shore.
	 */
	private int shoreColor(int x, int height, int z)
	{
		int v = groundBlender.lookup(x, height, z);
		if (v == -1 || (v & GroundBlender.BLENDED) == 0)
		{
			return -1;
		}
		// Near-black ground colors (wall bases, black tiles, ground hidden under objects) carried onto the bed
		// showed under the water as dark squares, the refraction wobbling their edges: the bed keeps its own there.
		// Only those: dark mud banks are a real color, and leaving them out left the bed lighter than its bank.
		return (v & 127) < MIN_SHORE_LIGHTNESS ? -1 : v & 0xffff;
	}

	private static final int MIN_SHORE_LIGHTNESS = 3;

	/**
	 * The mean of the first n packed HSL colors that aren't -1, or -1 if all are.
	 */
	static int mixHsl(int[] c, int n)
	{
		int h = 0, sat = 0, l = 0, count = 0;
		for (int i = 0; i < n; ++i)
		{
			if (c[i] >= 0)
			{
				h += c[i] >> 10 & 63;
				sat += c[i] >> 7 & 7;
				l += c[i] & 127;
				++count;
			}
		}
		if (count == 0)
		{
			return -1;
		}
		return (h + count / 2) / count << 10 | (sat + count / 2) / count << 7 | (l + count / 2) / count;
	}

	private static void bedFan(GpuIntBuffer vb, int xc, int yc, int zc, int cc, int nc,
		int xa, int ya, int za, int ca, int na, int xb, int yb, int zb, int cb, int nb, int tex)
	{
		vb.put22224(xc, yc, zc, 0, cc);
		vb.put2222(tex, 128, 128, nc);
		vb.put22224(xa, ya, za, 0, ca);
		vb.put2222(tex, 0, 0, na);
		vb.put22224(xb, yb, zb, 0, cb);
		vb.put2222(tex, 0, 0, nb);
	}

	private void markLandVertices(SceneTileModel model)
	{
		int[] vx = model.getVertexX();
		if (landVertex.length < vx.length)
		{
			landVertex = new boolean[vx.length];
			landColor = new int[vx.length];
		}
		java.util.Arrays.fill(landVertex, 0, vx.length, false);
		java.util.Arrays.fill(landColor, 0, vx.length, -1);
		int[] tex = model.getTriangleTextureId();
		int[] fa = model.getFaceX(), fb = model.getFaceY(), fc = model.getFaceZ();
		for (int f = 0; f < fa.length; ++f)
		{
			if (!WaterMap.isWaterTexture(tex[f]))
			{
				landVertex[fa[f]] = landVertex[fb[f]] = landVertex[fc[f]] = true;
			}
		}
	}

	/**
	 * One vertex of a water face of a shore tile model, lowered into the bed: corners take the corner depth,
	 * other vertices interpolate it unless a land face shares them.
	 */
	private void putBedVertex(GpuIntBuffer vb, int x, int y, int z, int v, int lx, int lz, int ex, int ey)
	{
		int ox = x - lx, oz = z - lz;
		boolean corner = (ox == 0 || ox == 128) && (oz == 0 || oz == 128);
		int cx = ex + (ox >> 7), cy = ey + (oz >> 7);
		boolean edge = ox <= 0 || ox >= 128 || oz <= 0 || oz >= 128;
		int sink = corner ? water.bedOffset(cx, cy)
			: landVertex[v] ? 0
			// inside the tile a vertex can go deeper than its corners without opening a crack to the next tile
			: edge ? water.bedOffset(ex, ey, ox, oz) : Math.max(water.bedOffset(ex, ey, ox, oz), WaterMap.MIN_DEPTH);
		int depth = corner ? water.depthLevel(cx, cy) : water.depthLevel(ex + Math.round(ox / 128f), ey + Math.round(oz / 128f));
		GroundBlender.interpolateSlope(surfaceSlopes, ox / 128f, oz / 128f, slope);
		// the land's color where the vertex stays on the bank (land touches it, nothing sinks it), else the tile's
		// corner colors blended across it (see uploadBed)
		int off = RuneLightPlugin.SCENE_OFFSET << 7;
		int color = sink == 0 ? landColor[v] : -1;
		if (sink == 0 && color < 0)
		{
			color = shoreColor(x + off, y, z + off);
		}
		if (color < 0)
		{
			float fx = Math.min(Math.max(ox / 128f, 0), 1), fz = Math.min(Math.max(oz / 128f, 0), 1);
			int[][] h = bedTileHeights;
			color = lerpHsl(lerpHsl(bedCornerColor(ex, ey, h), bedCornerColor(ex + 1, ey, h), fx),
				lerpHsl(bedCornerColor(ex, ey + 1, h), bedCornerColor(ex + 1, ey + 1, h), fx), fz);
		}
		vb.put22224(x - basex, y + sink, z - basez, 0, color);
		vb.put2222(WaterMap.BED_FLAG, 0, 0, VertexShading.encodeNormal(slope[0], -1, slope[1]));
	}

	/**
	 * Smooth terrain normal at tile-local position (x, z) of the tile whose corner slopes are in
	 * {@link #surfaceSlopes}. Leaves vanilla's slope there (for undoing its lighting) in {@link #slope}.
	 */
	private int modelNormal(int x, int z)
	{
		GroundBlender.interpolateSlope(surfaceSlopes, x / 128f, z / 128f, slope);
		int n = VertexShading.encodeNormal(slope[0], -1, slope[1]);
		GroundBlender.interpolateSlope(tileSlopes, x / 128f, z / 128f, slope);
		return n;
	}

	// scene upload
	private final int[] flatNormals = new int[3];
	private final VertexShading.Normals computedNormals = new VertexShading.Normals();

	// Faces lying exactly on top of each other (a wall's overlay on the wall, a decoration on its wall) would fight over
	// the depth test pixel by pixel. The game draws them in order instead: a model's faces by render priority, and a
	// wall's decoration after the wall. Each priority, and a decoration, moves a face this much toward the camera in
	// the depth test (the game's own face bias units, vert.glsl), on top of its face bias.
	private static final int PRIORITY_BIAS = 1;
	private static final int DECORATION_BIAS = 4;
	private int objectBias;

	private int uploadStaticModel(Model model, int orient, int x, int y, int z, int material, int id, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		final int vertexCount = model.getVerticesCount();
		final int triangleCount = model.getFaceCount();

		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final int[] color1s = model.getFaceColors1();
		final int[] color2s = model.getFaceColors2();
		final int[] color3s = model.getFaceColors3();

		final short[] faceTextures = model.getFaceTextures();

		final byte[] transparencies = model.getFaceTransparencies();
		final byte[] bias = model.getFaceBias();
		final byte[] priorities = model.getFaceRenderPriorities();

		final VertexShading.Normals computed = this.computedNormals;
		computed.ensure(model.getVerticesCount());
		VertexShading.computeNormals(model.getVerticesCount(), vertexX, vertexY, vertexZ,
			model.getFaceCount(), indices1, indices2, indices3, color3s, computed.x, computed.y, computed.z);
		final float[] normalsX = computed.x;
		final float[] normalsY = computed.y;
		final float[] normalsZ = computed.z;
		final boolean hasNormals = true;
		final short[] unlitColors = model.getUnlitFaceColors();
		final int[] flatNormals = this.flatNormals;
		final float normalSin = orient != 0 ? Perspective.SINEF[orient] : 0;
		final float normalCos = orient != 0 ? Perspective.COSINEF[orient] : 1;

		int orientSin = 0;
		int orientCos = 0;
		if (orient != 0)
		{
			orientSin = Perspective.SINE[orient];
			orientCos = Perspective.COSINE[orient];
		}

		final boolean sway = WindSway.sways(material);
		if (sway)
		{
			float height = 0;
			for (int v = 0; v < vertexCount; ++v)
			{
				height = Math.max(height, -vertexY[v]);
			}
			int anchor = WindSway.anchor(x + basex, z + basez);
			for (int v = 0; v < vertexCount; ++v)
			{
				swayCodes[v] = WindSway.vertexCode(material, vertexX[v], vertexY[v], vertexZ[v], height, anchor);
			}
		}

		// the game's ripples on it, which the water draws itself (WaterMap.contact): 117 HD's list
		WaterOverrides overrides = WaterOverrides.get();
		boolean ripples = overrides.hasRipples(id) && EnvironmentManager.toWorld(scene, x + basex >> 7, z + basez >> 7,
			level, objectWorld);
		boolean standing = false;
		if (sinkWater && !sway)
		{
			for (int v = 0; v < vertexCount && !standing; ++v)
			{
				standing = (int) vertexY[v] <= -STANDING_HEIGHT;
			}
		}
		for (int v = 0; v < vertexCount; ++v)
		{
			int vx = (int) vertexX[v];
			int vy = (int) vertexY[v];
			int vz = (int) vertexZ[v];

			if (orient != 0)
			{
				int x0 = vx;
				vx = vz * orientSin + x0 * orientCos >> 16;
				vz = vz * orientCos - x0 * orientSin >> 16;
			}

			vx += x;
			vy += y;
			vz += z;

			modelLocalXI[v] = vx;
			atWaterLevel[v] = sinkWater && Math.abs((int) vertexY[v]) <= WATERLINE && water.atWaterLevel(vx + basex, vy, vz + basez, WATER_HEIGHT_SLACK);
			modelLocalYI[v] = vy;
			modelLocalZI[v] = vz;
		}

		int len = 0;
		for (int face = 0; face < triangleCount; ++face)
		{
			rippleFace[face] = false;
			int color1 = color1s[face];
			int color2 = color2s[face];
			int color3 = color3s[face];

			boolean alpha = (transparencies != null && transparencies[face] != 0);

			boolean flat = color3 == -1;
			if (color3 == -2)
			{
				continue;
			}

			int triangleA = indices1[face];
			int triangleB = indices2[face];
			int triangleC = indices3[face];
			if (ripples && overrides.hidesFace(id, objectWorld[0], objectWorld[1], objectWorld[2],
				faceTextures != null && faceTextures[face] != -1, transparencies != null ? transparencies[face] : 0, color1))
			{
				rippleFace[face] = true;
				continue;
			}

			if (faceTextures == null || faceTextures[face] == -1)
			{
				color1 = VertexShading.baseColor(color1, face, triangleA, unlitColors, normalsX, normalsY, normalsZ);
				if (!flat)
				{
					color2 = VertexShading.baseColor(color2, face, triangleB, unlitColors, normalsX, normalsY, normalsZ);
					color3 = VertexShading.baseColor(color3, face, triangleC, unlitColors, normalsX, normalsY, normalsZ);
				}
			}
			if (flat)
			{
				color2 = color3 = color1;
			}

			boolean smooth = !flat && hasNormals;
			int n1 = smooth ? VertexShading.vertexNormal(normalsX, normalsY, normalsZ, triangleA, normalSin, normalCos) : 0;
			int n2 = smooth ? VertexShading.vertexNormal(normalsX, normalsY, normalsZ, triangleB, normalSin, normalCos) : 0;
			int n3 = smooth ? VertexShading.vertexNormal(normalsX, normalsY, normalsZ, triangleC, normalSin, normalCos) : 0;
			if (flat && hasNormals && VertexShading.smoothFlatFaces
				&& VertexShading.flatFaceNormals(vertexX, vertexY, vertexZ, normalsX, normalsY, normalsZ, triangleA, triangleB, triangleC, normalSin, normalCos, flatNormals))
			{
				n1 = flatNormals[0];
				n2 = flatNormals[1];
				n3 = flatNormals[2];
				// one color over the rounded surface: the per-face lit colors would still show the facets
				if (unlitColors != null && (faceTextures == null || faceTextures[face] == -1))
				{
					color1 = color2 = color3 = unlitColors[face] & 0xffff;
				}
			}

			int vx1 = modelLocalXI[triangleA];
			int vy1 = modelLocalYI[triangleA];
			int vz1 = modelLocalZI[triangleA];

			int vx2 = modelLocalXI[triangleB];
			int vy2 = modelLocalYI[triangleB];
			int vz2 = modelLocalZI[triangleB];

			int vx3 = modelLocalXI[triangleC];
			int vy3 = modelLocalYI[triangleC];
			int vz3 = modelLocalZI[triangleC];

			ModelUploader.computeFaceUvs(model, face, 0f, 0f, 0f, false, u, v);

			int su0 = (int) (u[0] * 256f);
			int sv0 = (int) (v[0] * 256f);

			int su1 = (int) (u[1] * 256f);
			int sv1 = (int) (v[1] * 256f);

			int su2 = (int) (u[2] * 256f);
			int sv2 = (int) (v[2] * 256f);

			int alphaBias = 0;
			alphaBias |= transparencies != null ? (transparencies[face] & 0xff) << 24 : 0;
			int faceBias = (bias != null ? bias[face] & 0xff : 0) + objectBias
				+ (priorities != null ? (priorities[face] & 0xff) * PRIORITY_BIAS : 0);
			alphaBias |= Math.min(faceBias, 255) << 16;
			int texture = faceTextures != null ? faceTextures[face] + 1 : 0;
			texture |= SurfaceMaterials.face(material, texture - 1, color1);
			GpuIntBuffer buf = alpha ? ab : vb;

			buf.put22224(vx1, vy1, vz1, sway ? swayCodes[triangleA] : 0, alphaBias | color1);
			buf.put2222(texture, su0, sv0, n1);

			buf.put22224(vx2, vy2, vz2, sway ? swayCodes[triangleB] : 0, alphaBias | color2);
			buf.put2222(texture, su1, sv1, n2);

			buf.put22224(vx3, vy3, vz3, sway ? swayCodes[triangleC] : 0, alphaBias | color3);
			buf.put2222(texture, su2, sv2, n3);

			len += 3;

			if (standing && !alpha)
			{
				// the edge at the waterline, in the face's order
				int e = atWaterLevel[triangleA] ? (atWaterLevel[triangleB] ? 0 : atWaterLevel[triangleC] ? 2 : -1)
					: atWaterLevel[triangleB] && atWaterLevel[triangleC] ? 1 : -1;
				if (e >= 0 && !(atWaterLevel[triangleA] && atWaterLevel[triangleB] && atWaterLevel[triangleC]))
				{
					fx[0] = vx1; fy[0] = vy1; fz[0] = vz1; fc[0] = color1; fn[0] = n1; fu[0] = su0; fv[0] = sv0;
					fx[1] = vx2; fy[1] = vy2; fz[1] = vz2; fc[1] = color2; fn[1] = n2; fu[1] = su1; fv[1] = sv1;
					fx[2] = vx3; fy[2] = vy3; fz[2] = vz3; fc[2] = color3; fn[2] = n3; fu[2] = su2; fv[2] = sv2;
					len += extrude(e, (e + 1) % 3, texture, alphaBias, vb);
				}
			}
		}

		return len;
	}

	private final int[] fx = new int[3], fy = new int[3], fz = new int[3], fc = new int[3], fn = new int[3];
	private final int[] fu = new int[3], fv = new int[3];

	/**
	 * Extrudes the edge a -> b of the face in fx..fv down to the bed under it: two triangles facing as the face does,
	 * their texture carried on over the face's plane.
	 */
	private int extrude(int a, int b, int texture, int alphaBias, GpuIntBuffer buf)
	{
		int ba = water.bedHeight(fx[a] + basex, fz[a] + basez);
		int bb = water.bedHeight(fx[b] + basex, fz[b] + basez);
		if (ba <= fy[a] + 1 && bb <= fy[b] + 1)
		{
			return 0;
		}
		ba = Math.max(ba, fy[a]);
		bb = Math.max(bb, fy[b]);
		// the face's texture mapping, affine over its plane: the extruded corners' projected onto it
		float e1x = fx[1] - fx[0], e1y = fy[1] - fy[0], e1z = fz[1] - fz[0];
		float e2x = fx[2] - fx[0], e2y = fy[2] - fy[0], e2z = fz[2] - fz[0];
		float d00 = e1x * e1x + e1y * e1y + e1z * e1z, d01 = e1x * e2x + e1y * e2y + e1z * e2z;
		float d11 = e2x * e2x + e2y * e2y + e2z * e2z, den = d00 * d11 - d01 * d01;
		int[] uv = extrudedUv;
		for (int k = 0; k < 2; ++k)
		{
			int c = k == 0 ? a : b;
			float wx = fx[c] - fx[0], wy = (k == 0 ? ba : bb) - fy[0], wz = fz[c] - fz[0];
			float d20 = wx * e1x + wy * e1y + wz * e1z, d21 = wx * e2x + wy * e2y + wz * e2z;
			float s = den != 0 ? (d11 * d20 - d01 * d21) / den : 0, t = den != 0 ? (d00 * d21 - d01 * d20) / den : 0;
			uv[k * 2] = Math.round(fu[0] + s * (fu[1] - fu[0]) + t * (fu[2] - fu[0]));
			uv[k * 2 + 1] = Math.round(fv[0] + s * (fv[1] - fv[0]) + t * (fv[2] - fv[0]));
		}
		// (b, a, a') and (b, a', b'): below the edge, so the order is reversed to face the same way
		buf.put22224(fx[b], fy[b], fz[b], 0, alphaBias | fc[b]);
		buf.put2222(texture, fu[b], fv[b], fn[b]);
		buf.put22224(fx[a], fy[a], fz[a], 0, alphaBias | fc[a]);
		buf.put2222(texture, fu[a], fv[a], fn[a]);
		buf.put22224(fx[a], ba, fz[a], 0, alphaBias | fc[a]);
		buf.put2222(texture, uv[0], uv[1], fn[a]);

		buf.put22224(fx[b], fy[b], fz[b], 0, alphaBias | fc[b]);
		buf.put2222(texture, fu[b], fv[b], fn[b]);
		buf.put22224(fx[a], ba, fz[a], 0, alphaBias | fc[a]);
		buf.put2222(texture, uv[0], uv[1], fn[a]);
		buf.put22224(fx[b], bb, fz[b], 0, alphaBias | fc[b]);
		buf.put2222(texture, uv[2], uv[3], fn[b]);
		return 6;
	}

	private final int[] extrudedUv = new int[4];
}
