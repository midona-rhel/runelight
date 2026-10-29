/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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

import com.midonarhel.lumina.frame.GpuIntBuffer;
import com.midonarhel.lumina.shading.DiffuseColors;
import com.midonarhel.lumina.shading.FaceShading;
import com.midonarhel.lumina.scene.areas.Area;
import com.midonarhel.lumina.scene.overrides.InheritTileColorType;
import com.midonarhel.lumina.scene.overrides.ModelOverride;
import com.midonarhel.lumina.scene.overrides.ModelOverrideManager;
import com.midonarhel.lumina.scene.overrides.TileOverride;
import com.midonarhel.lumina.scene.overrides.UvType;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.scene.materials.Material;
import com.midonarhel.lumina.scene.terrain.TerrainVertices;
import com.midonarhel.lumina.util.ObjectOrientation;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
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
import static com.midonarhel.lumina.scene.SceneContext.SCENE_OFFSET;
import static com.midonarhel.lumina.scene.SceneData.HIDDEN_HSL;
import static com.midonarhel.lumina.scene.SceneData.TILE_OVERRIDE_MAIN;
import static com.midonarhel.lumina.scene.SceneData.TILE_OVERRIDE_OVERLAY;
import static com.midonarhel.lumina.scene.SceneData.TILE_OVERRIDE_UNDERLAY;
import static com.midonarhel.lumina.util.MathUtils.DEG_TO_JAU;
import static com.midonarhel.lumina.util.MathUtils.JAU_TO_RAD;
import static com.midonarhel.lumina.util.MathUtils.fract;
import static com.midonarhel.lumina.util.MathUtils.mix;

@Slf4j
class SceneUploader
{

	private final int[] modelLocalXI;
	private final int[] modelLocalYI;
	private final int[] modelLocalZI;
	// the same positions as floats, for FaceShading's face normals (117 HD passes these ints as floats)
	private final float[] modelLocalXF;
	private final float[] modelLocalYF;
	private final float[] modelLocalZF;

	private final float[] uvs = new float[12];
	private final float[] uvWork = new float[9];

	private final RenderCallbackManager renderCallbackManager;
	private final ModelOverrideManager modelOverrideManager;
	private final FaceShading faceShading = new FaceShading();
	private final FaceShading.TileColorSource tileColors = this::inheritTileColor;

	private int basex, basez, rid, level;

	// The scene data of the scene being uploaded, and the tile being uploaded
	private SceneData data;
	private Tile tile;
	private int tileExX, tileExY, tileZ;
	private final int[] worldPos = new int[3];

	private final int[][] vertices = new int[4][3];
	private final int[] vertexKeys = new int[4];
	// 117 HD's UP_NORMAL and its per-vertex tile normal buffers
	private static final short[] UP_NORMAL = {0, -1, 0};
	private final short[][] tileNormals = new short[4][3];

	// The faces of the last static model written to the alpha buffer, in buffer order (for Zone.addAlphaModel)
	private int[] alphaFaces = new int[ModelUploader.MAX_FACE_COUNT];
	private int alphaFaceCount;

	SceneUploader(RenderCallbackManager renderCallbackManager, ModelOverrideManager modelOverrideManager)
	{
		this.renderCallbackManager = renderCallbackManager;
		this.modelOverrideManager = modelOverrideManager;
		modelLocalXI = new int[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalYI = new int[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalZI = new int[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalXF = new float[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalYF = new float[ModelUploader.MAX_VERTEX_COUNT];
		modelLocalZF = new float[ModelUploader.MAX_VERTEX_COUNT];
	}

	void zoneSize(Scene scene, SceneData data, Zone zone, int mzx, int mzz)
	{
		this.data = data;
		Tile[][][] tiles = scene.getExtendedTiles();

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

		// 117 HD's estimateZoneGapFillers
		if (shouldFillGaps(zone))
		{
			int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET >> 3 : 0;
			int gapBasex = (mzx - offset) << 10;
			int gapBasez = (mzz - offset) << 10;
			for (int xoff = 0; xoff < 8; ++xoff)
			{
				for (int zoff = 0; zoff < 8; ++zoff)
				{
					zone.sizeO += processGapTile(scene, (mzx << 3) + xoff, (mzz << 3) + zoff, gapBasex, gapBasez, null);
				}
			}
		}

		clearTile();
		this.data = null;
	}

	/**
	 * 117 HD's shouldFillGaps: only zones with geometry, in a contiguous scene, inside a current area with fillGaps.
	 */
	private boolean shouldFillGaps(Zone zone)
	{
		Area currentArea = data.currentArea;
		return data.fillGaps &&
			zone.sizeO > 0 &&
			data.sceneBase != null &&
			currentArea != null && currentArea.fillGaps;
	}

	private void uploadZoneGapFillers(Scene scene, int mzx, int mzz, GpuIntBuffer vb)
	{
		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET >> 3 : 0;
		int gapBasex = (mzx - offset) << 10;
		int gapBasez = (mzz - offset) << 10;
		for (int xoff = 0; xoff < 8; ++xoff)
		{
			for (int zoff = 0; zoff < 8; ++zoff)
			{
				processGapTile(scene, (mzx << 3) + xoff, (mzz << 3) + zoff, gapBasex, gapBasez, vb);
			}
		}
	}

	/**
	 * 117 HD's processGapTile: level 0 tiles without visible tile paint are filled with black faces facing up.
	 *
	 * @param vb where to write the faces, or null to only count them
	 * @return the face count if the tile is gap-filled, otherwise 0
	 */
	private int processGapTile(Scene scene, int tileExX, int tileExY, int basex, int basez, GpuIntBuffer vb)
	{
		// Skip tiles along the scene boundary, which are always empty
		if (tileExX == 0 || tileExX == Constants.EXTENDED_SCENE_SIZE - 1 ||
			tileExY == 0 || tileExY == Constants.EXTENDED_SCENE_SIZE - 1)
		{
			return 0;
		}

		Area currentArea = data.currentArea;
		if (currentArea != null)
		{
			data.extendedSceneToWorld(tileExX, tileExY, 0, worldPos);
			if (!currentArea.containsPoint(worldPos))
			{
				return 0;
			}
		}

		Tile tile = scene.getExtendedTiles()[0][tileExX][tileExY];

		SceneTilePaint paint;
		SceneTileModel model = null;
		int renderLevel = 0;
		if (tile != null)
		{
			renderLevel = tile.getRenderLevel();
			paint = tile.getSceneTilePaint();
			model = tile.getSceneTileModel();

			if (model == null)
			{
				boolean hasTilePaint = paint != null && paint.getNeColor() != HIDDEN_HSL;
				if (!hasTilePaint)
				{
					tile = tile.getBridge();
					if (tile != null)
					{
						renderLevel = tile.getRenderLevel();
						paint = tile.getSceneTilePaint();
						model = tile.getSceneTileModel();
						hasTilePaint = paint != null && paint.getNeColor() != HIDDEN_HSL;
					}
				}

				if (hasTilePaint)
				{
					return 0;
				}
			}
		}

		if (vb != null)
		{
			if (model != null)
			{
				final int[] faceX = model.getFaceX();
				final int[] faceY = model.getFaceY();
				final int[] faceZ = model.getFaceZ();
				final int[] vertexX = model.getVertexX();
				final int[] vertexY = model.getVertexY();
				final int[] vertexZ = model.getVertexZ();
				final int[] triangleColorA = model.getTriangleColorA();
				for (int face = 0; face < faceX.length; ++face)
				{
					if (triangleColorA[face] != HIDDEN_HSL)
					{
						continue;
					}
					int v0 = faceX[face];
					int v1 = faceY[face];
					int v2 = faceZ[face];
					putGapVertex(vb, vertexX[v0] - basex, vertexY[v0], vertexZ[v0] - basez, 0, 0);
					putGapVertex(vb, vertexX[v1] - basex, vertexY[v1], vertexZ[v1] - basez, 0, 0);
					putGapVertex(vb, vertexX[v2] - basex, vertexY[v2], vertexZ[v2] - basez, 0, 0);
				}
			}
			else
			{
				final int[][][] tileHeights = scene.getTileHeights();
				int swHeight = tileHeights[renderLevel][tileExX][tileExY];
				int seHeight = tileHeights[renderLevel][tileExX + 1][tileExY];
				int neHeight = tileHeights[renderLevel][tileExX + 1][tileExY + 1];
				int nwHeight = tileHeights[renderLevel][tileExX][tileExY + 1];
				int x = (tileExX - data.sceneOffset) * Perspective.LOCAL_TILE_SIZE - basex;
				int z = (tileExY - data.sceneOffset) * Perspective.LOCAL_TILE_SIZE - basez;
				putGapVertex(vb, x + Perspective.LOCAL_TILE_SIZE, neHeight, z + Perspective.LOCAL_TILE_SIZE, 0, 0);
				putGapVertex(vb, x, nwHeight, z + Perspective.LOCAL_TILE_SIZE, 1, 0);
				putGapVertex(vb, x + Perspective.LOCAL_TILE_SIZE, seHeight, z, 0, 1);
				putGapVertex(vb, x, swHeight, z, 1, 1);
				putGapVertex(vb, x + Perspective.LOCAL_TILE_SIZE, seHeight, z, 0, 1);
				putGapVertex(vb, x, nwHeight, z + Perspective.LOCAL_TILE_SIZE, 1, 0);
			}
		}

		return model == null ? 2 : model.getFaceX().length;
	}

	/**
	 * A gap filler vertex: black (117 HD's BLACK material, brightness 0), normal straight up.
	 */
	private static int clamp(int v, int min, int max)
	{
		return Math.min(Math.max(v, min), max);
	}


	private static void putGapVertex(GpuIntBuffer vb, int x, int y, int z, int u, int v)
	{
		Material black = MaterialManager.getMaterial("BLACK");
		vb.putZoneVertex(x, y, z, 0, 0, 0, 0, u, v, 255 << 8, 0, -1, 0, MaterialManager.slot(black == null ? Material.NONE : black));
	}

	void uploadZone(Scene scene, SceneData data, Zone zone, int mzx, int mzz)
	{
		this.data = data;
		int[][][] roofs = scene.getRoofs();
		Set<Integer> roofIds = new HashSet<>();

		var vb = zone.vboO != null ? new GpuIntBuffer(zone.vboO.vb) : null;
		var ab = zone.vboA != null ? new GpuIntBuffer(zone.vboA.vb) : null;

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

		zone.rids = new int[4][roofIds.size()];
		zone.roofStart = new int[4][roofIds.size()];
		zone.roofEnd = new int[4][roofIds.size()];

		// 117 HD's layout: level 0 holds only renderables (its tiles go to the terrain section), levels 1-3 hold their
		// tiles and renderables, then the terrain section (level 0 tiles and vis-below tiles), then gap fillers
		for (int level = 0; level <= 3; ++level)
		{
			this.level = level;

			if (level == 0)
			{
				uploadZoneLevel(scene, zone, mzx, mzz, 0, false, false, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 0, true, false, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 1, true, false, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 2, true, false, true, roofIds, vb, ab);
				uploadZoneLevel(scene, zone, mzx, mzz, 3, true, false, true, roofIds, vb, ab);
			}
			else
			{
				uploadZoneLevel(scene, zone, mzx, mzz, level, false, false, false, roofIds, vb, ab);
			}

			if (zone.vboO != null)
			{
				int pos = zone.vboO.vb.position();
				zone.levelOffsets[level] = pos;
			}
		}

		if (zone.vboO != null)
		{
			uploadZoneLevel(scene, zone, mzx, mzz, 0, false, true, false, roofIds, vb, ab);
			uploadZoneLevel(scene, zone, mzx, mzz, 0, true, true, false, roofIds, vb, ab);
			uploadZoneLevel(scene, zone, mzx, mzz, 1, true, true, false, roofIds, vb, ab);
			uploadZoneLevel(scene, zone, mzx, mzz, 2, true, true, false, roofIds, vb, ab);
			uploadZoneLevel(scene, zone, mzx, mzz, 3, true, true, false, roofIds, vb, ab);
			zone.levelOffsets[Zone.LEVEL_TERRAIN] = zone.vboO.vb.position();

			if (shouldFillGaps(zone))
			{
				uploadZoneGapFillers(scene, mzx, mzz, vb);
			}
			zone.levelOffsets[Zone.LEVEL_GAP_FILLER] = zone.vboO.vb.position();
		}

		clearTile();
		this.data = null;
	}

	private void uploadZoneLevel(Scene scene, Zone zone, int mzx, int mzz, int level, boolean visbelow, boolean onlyTiles, boolean onlyRenderables,
		Set<Integer> roofIds, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		int ridx = 0;

		// upload the roofs and save their positions
		for (int id : roofIds)
		{
			int pos = zone.vboO != null ? zone.vboO.vb.position() : 0;

			uploadZoneLevelRoof(scene, zone, mzx, mzz, level, id, visbelow, onlyTiles, onlyRenderables, vb, ab);

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
		uploadZoneLevelRoof(scene, zone, mzx, mzz, level, 0, visbelow, onlyTiles, onlyRenderables, vb, ab);
	}

	private void uploadZoneLevelRoof(Scene scene, Zone zone, int mzx, int mzz, int level, int roofId, boolean visbelow, boolean onlyTiles,
		boolean onlyRenderables, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		byte[][][] settings = scene.getExtendedTileSettings();
		int[][][] roofs = scene.getRoofs();
		Tile[][][] tiles = scene.getExtendedTiles();

		int offset = scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET >> 3 : 0;
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
						uploadZoneTile(scene, zone, t, false, onlyTiles, onlyRenderables, vb, ab);
					}
				}
			}
		}
	}

	/**
	 * Makes {@code t} the current tile: its extended scene position, render level and world position.
	 */
	private void setTile(Tile t)
	{
		tile = t;
		Point p = t.getSceneLocation();
		tileExX = p.getX() + data.sceneOffset;
		tileExY = p.getY() + data.sceneOffset;
		tileZ = t.getRenderLevel();
		data.sceneToWorld(p.getX(), p.getY(), t.getPlane(), worldPos);
	}

	private void clearTile()
	{
		tile = null; // avoid keeping the scene alive
	}

	private void zoneSize(Zone z, Tile t)
	{
		setTile(t);

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null)
		{
			z.sizeO += 2;
			if (isTileDoubleSided(t.getRenderLevel(), data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_MAIN)))
			{
				z.sizeO += 2;
			}
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null)
		{
			int len = model.getFaceX().length;
			z.sizeO += len;
			if (isTileDoubleSided(t.getRenderLevel(), data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_OVERLAY))
				|| isTileDoubleSided(t.getRenderLevel(), data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_UNDERLAY)))
			{
				z.sizeO += len;
			}
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

	private int uploadZoneTile(Scene scene, Zone zone, Tile t, boolean isBridge, boolean onlyTiles, boolean onlyRenderables,
		GpuIntBuffer vertexBuffer, GpuIntBuffer ab)
	{
		setTile(t);

		// 117 HD's area hiding: nothing outside the current area is uploaded (bridges follow their tile)
		Area currentArea = data.currentArea;
		if (currentArea != null && !isBridge && !currentArea.containsPoint(worldPos))
		{
			return 0;
		}

		int len = 0;
		boolean drawTile = renderCallbackManager.drawTile(scene, t);

		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null && drawTile && !onlyRenderables)
		{
			Point tilePoint = t.getSceneLocation();
			len = upload(scene, paint,
				t.getRenderLevel(), tilePoint.getX(), tilePoint.getY(),
				vertexBuffer,
				tilePoint.getX() * 128 - basex, tilePoint.getY() * 128 - basez
			);
		}

		SceneTileModel model = t.getSceneTileModel();
		if (model != null && drawTile && !onlyRenderables)
		{
			Point tilePoint = t.getSceneLocation();
			len += upload(model, tilePoint.getX() << 7, tilePoint.getY() << 7, vertexBuffer);
		}

		if (!onlyTiles)
		{
			uploadZoneTileRenderables(scene, zone, t, vertexBuffer, ab);
		}

		Tile bridge = t.getBridge();
		if (bridge != null)
		{
			len += uploadZoneTile(scene, zone, bridge, true, onlyTiles, onlyRenderables, vertexBuffer, ab);
			setTile(t);
		}

		return len;
	}

	private void uploadZoneTileRenderables(Scene scene, Zone zone, Tile t, GpuIntBuffer vertexBuffer, GpuIntBuffer ab)
	{
		WallObject wallObject = t.getWallObject();
		if (wallObject != null && renderCallbackManager.drawObject(scene, wallObject))
		{
			Renderable renderable1 = wallObject.getRenderable1();
			uploadZoneRenderable(renderable1, zone, ObjectOrientation.convertWallObjectOrientation(wallObject.getOrientationA()), 0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), -1, -1, -1, -1, wallObject.getId(), vertexBuffer, ab);

			Renderable renderable2 = wallObject.getRenderable2();
			uploadZoneRenderable(renderable2, zone, ObjectOrientation.convertWallObjectOrientation(wallObject.getOrientationB()), 0, wallObject.getX(), wallObject.getZ(), wallObject.getY(), -1, -1, -1, -1, wallObject.getId(), vertexBuffer, ab);
		}

		DecorativeObject decorativeObject = t.getDecorativeObject();
		if (decorativeObject != null && renderCallbackManager.drawObject(scene, decorativeObject))
		{
			Renderable renderable = decorativeObject.getRenderable();
			uploadZoneRenderable(renderable, zone, ObjectOrientation.getModelPreOrientation(decorativeObject.getConfig()), 0, decorativeObject.getX() + decorativeObject.getXOffset(), decorativeObject.getZ(), decorativeObject.getY() + decorativeObject.getYOffset(), -1, -1, -1, -1, decorativeObject.getId(), vertexBuffer, ab);

			Renderable renderable2 = decorativeObject.getRenderable2();
			// 117 HD places the second decoration renderable at the object position, without the second offset
			uploadZoneRenderable(renderable2, zone, ObjectOrientation.getModelPreOrientation(decorativeObject.getConfig()), 0, decorativeObject.getX(), decorativeObject.getZ(), decorativeObject.getY(), -1, -1, -1, -1, decorativeObject.getId(), vertexBuffer, ab);
		}

		GroundObject groundObject = t.getGroundObject();
		if (groundObject != null && renderCallbackManager.drawObject(scene, groundObject))
		{
			Renderable renderable = groundObject.getRenderable();
			uploadZoneRenderable(renderable, zone, ObjectOrientation.getModelPreOrientation(groundObject.getConfig()), 0, groundObject.getX(), groundObject.getZ(), groundObject.getY(),
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
			uploadZoneRenderable(renderable, zone, ObjectOrientation.getModelPreOrientation(gameObject.getConfig()), gameObject.getModelOrientation(), gameObject.getX(), gameObject.getZ(), gameObject.getY(),
				min.getX(), min.getY(), max.getX(), max.getY(),
				gameObject.getId(),
				vertexBuffer, ab);
		}
	}

	private static Model renderableModel(Renderable r)
	{
		if (r instanceof Model)
		{
			return (Model) r;
		}
		if (r instanceof DynamicObject)
		{
			return ((DynamicObject) r).getModelZbuf();
		}
		return null;
	}

	/**
	 * The model override of a static renderable on the current tile. Objects transformed by varbits use the
	 * override of the object they currently show.
	 */
	private ModelOverride modelOverride(Renderable r, int id)
	{
		if (r instanceof DynamicObject)
		{
			var composition = ((DynamicObject) r).getRecordedObjectComposition();
			if (composition != null)
			{
				id = composition.getId();
			}
		}
		return modelOverrideManager.getOverride(ModelOverrideManager.TYPE_OBJECT, id, worldPos);
	}

	private void zoneRenderableSize(Zone z, Renderable r, int id)
	{
		Model m = renderableModel(r);
		if (m == null)
		{
			return;
		}

		ModelOverride override = modelOverride(r, id);
		if (override.hide)
		{
			return;
		}

		// Count exactly the faces uploadStaticModel writes, using the same face classification
		faceShading.setModel(m, override, 0, null, false, modelLocalXF, modelLocalYF, modelLocalZF);
		int faceCount = m.getFaceCount();
		for (int face = 0; face < faceCount; ++face)
		{
			int transparency = faceShading.classify(face);
			if (transparency == FaceShading.SKIP)
			{
				continue;
			}
			int n = faceShading.doubleSided ? 2 : 1;
			if (faceShading.alpha)
			{
				z.sizeA += n;
			}
			else
			{
				z.sizeO += n;
			}
		}
	}

	private void uploadZoneRenderable(Renderable r, Zone zone, int preOrientation, int orient, int x, int y, int z, int lx, int lz, int ux, int uz, int id, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		Model model = renderableModel(r);
		if (model == null)
		{
			return;
		}

		ModelOverride override = modelOverride(r, id);
		if (override.hide)
		{
			return;
		}

		int pos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		uploadStaticModel(model, override, preOrientation, orient, x - basex, y, z - basez, vb, ab);
		int endpos = zone.vboA != null ? zone.vboA.vb.position() : 0;
		if (endpos > pos)
		{
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
			zone.addAlphaModel(zone.glVaoA, model, alphaFaces, alphaFaceCount, pos, endpos,
				x - basex, y + override.heightOffset, z - basez,
				lx, lz, ux, uz,
				rid, level, id);
		}
	}

	/**
	 * The colour a model face inherits from the current tile (117 HD's inheritTileColorType), from the lit tile
	 * colours as in 117 HD, or -1.
	 */
	private int inheritTileColor(InheritTileColorType type)
	{
		SceneTileModel tileModel = tile.getSceneTileModel();
		SceneTilePaint tilePaint = tile.getSceneTilePaint();

		// No point in inheriting tilepaint color if the ground tile does not have a color, for example above a cave wall
		if (tilePaint != null && tilePaint.getTexture() == -1 && tilePaint.getRBG() != 0 && tilePaint.getNeColor() != HIDDEN_HSL)
		{
			// Since tile colors are guaranteed to have the same hue and saturation per face,
			// we can blend without converting from HSL to RGB
			int averageColor = (tilePaint.getSwColor() + tilePaint.getNwColor() + tilePaint.getNeColor() + tilePaint.getSeColor()) / 4;
			return data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_MAIN).modifyColor(averageColor);
		}

		if (tileModel != null && tileModel.getTriangleTextureId() == null)
		{
			int faceColorIndex = -1;
			for (int i = 0; i < tileModel.getTriangleColorA().length; i++)
			{
				boolean isOverlay = TerrainVertices.isOverlayFace(tile, i);
				// Use underlay if the tile does not have an overlay, useful for rocks in cave corners.
				if (type == InheritTileColorType.UNDERLAY || tileModel.getModelOverlay() == 0)
				{
					// pulling the color from UNDERLAY is more desirable for green grass tiles
					// OVERLAY pulls in path color which is not desirable for grass next to paths
					if (!isOverlay)
					{
						faceColorIndex = i;
						break;
					}
				}
				else if (type == InheritTileColorType.OVERLAY && isOverlay)
				{
					// OVERLAY used in dirt/path/house tile color blend better with rubbles/rocks
					faceColorIndex = i;
					break;
				}
			}

			if (faceColorIndex != -1)
			{
				int color = tileModel.getTriangleColorA()[faceColorIndex];
				if (color != HIDDEN_HSL)
				{
					var override = data.getTileOverride(tileZ, tileExX, tileExY,
						type == InheritTileColorType.OVERLAY ? TILE_OVERRIDE_OVERLAY : TILE_OVERRIDE_UNDERLAY);
					return override.modifyColor(color);
				}
			}
		}

		return -1;
	}

	/**
	 * 117 HD's seasonalWaterType(...) != NONE: the override's water type, or a vanilla water texture as a fallback.
	 */
	private static boolean isWater(TileOverride override, int textureId)
	{
		if (override.isWater())
		{
			return true;
		}
		return 130 <= textureId && textureId <= 189 || textureId == 208 || textureId == 1 || textureId == 24 || textureId == 25;
	}

	/**
	 * 117 HD's isDoubleSided for tile paint and tile models: tiles above the ground level that are not visible from
	 * below, or overrides with doubleSidedFaces, are also drawn reversed.
	 */
	private boolean isTileDoubleSided(int renderLevel, TileOverride override)
	{
		byte[][][] settings = data.scene.getExtendedTileSettings();
		boolean isTileVisBelow = (settings[renderLevel][tileExX][tileExY] & Constants.TILE_FLAG_VIS_BELOW) != 0;
		return !isTileVisBelow && renderLevel != 0 || override.doubleSidedFaces;
	}

	/**
	 * 117 HD's MATERIAL_FLAG_IS_OVERLAY of a terrain vertex: the vertex is marked both overlay and underlay.
	 */
	private int overlayFlag(int vertexKey, int bit)
	{
		return data.isVertexOverlay(vertexKey) && data.isVertexUnderlay(vertexKey) ? 1 << bit : 0;
	}

	private short[] terrainNormal(int vertexKey, short[] out)
	{
		if (data.getVertexNormal(vertexKey, out) == null)
		{
			out[0] = UP_NORMAL[0];
			out[1] = UP_NORMAL[1];
			out[2] = UP_NORMAL[2];
		}
		return out;
	}

	/**
	 * One terrain face: written as 117 HD writes it (vertex A, B, C with the face's colours and overlay flags), and
	 * if double-sided once more reversed (C, B, A) with negated normals.
	 */
	private static void putTerrainFace(GpuIntBuffer vb, boolean doubleSided, int tex, int flags, int material,
		int xa, int ya, int za, int ca, float ua, float va, short[] na, int sa,
		int xb, int yb, int zb, int cb, float ub, float vb2, short[] nb, int sb,
		int xc, int yc, int zc, int cc, float uc, float vc, short[] nc, int sc)
	{
		// the vertex's sky visibility rides in flags bits 8-15
		vb.putZoneVertex(xa, ya, za, ca, cb, cc, tex, ua, va, flags | sa << 8, na[0], na[1], na[2], material);
		vb.putZoneVertex(xb, yb, zb, ca, cb, cc, tex, ub, vb2, flags | sb << 8, nb[0], nb[1], nb[2], material);
		vb.putZoneVertex(xc, yc, zc, ca, cb, cc, tex, uc, vc, flags | sc << 8, nc[0], nc[1], nc[2], material);
		if (doubleSided)
		{
			// reversed winding: the face's colours and overlay flags are reversed with it (117 HD's windingReversed)
			int rflags = (flags & 1) << 2 | (flags & 2) | (flags >> 2 & 1) | (flags & ~7);
			vb.putZoneVertex(xc, yc, zc, cc, cb, ca, tex, uc, vc, rflags | sc << 8, -nc[0], -nc[1], -nc[2], material);
			vb.putZoneVertex(xb, yb, zb, cc, cb, ca, tex, ub, vb2, rflags | sb << 8, -nb[0], -nb[1], -nb[2], material);
			vb.putZoneVertex(xa, ya, za, cc, cb, ca, tex, ua, va, rflags | sa << 8, -na[0], -na[1], -na[2], material);
		}
	}

	/**
	 * The sky visibility of a static model vertex: sampled a little along its normal (an inner wall face lands in
	 * the room, the outer one outside) on the level the biased height belongs to, at least the tile's.
	 */
	private int vertexSkyVisibility(int vx, int vy, int vz, int nx, int ny, int nz)
	{
		if (data.skyVisibility == null)
		{
			return 255;
		}
		float len = (float) Math.sqrt((float) nx * nx + (float) ny * ny + (float) nz * nz);
		if (len > 0)
		{
			float s = SKY_NORMAL_BIAS / len;
			vx += Math.round(nx * s);
			vy += Math.round(ny * s);
			vz += Math.round(nz * s);
		}
		return data.skyVisibility(vx + basex, vy, vz + basez, tileZ, -2);
	}

	// how far along its normal a static model vertex samples the sky visibility (units)
	private static final int SKY_NORMAL_BIAS = 32;


	// 117 HD's uploadTilePaint with ground blending off, without water, ground materials or HD textures
	private int upload(Scene scene, SceneTilePaint tile, int tileZ, int tileX, int tileY, GpuIntBuffer vertexBuffer, int lx, int lz)
	{
		tileX += scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;
		tileY += scene.getWorldViewId() == WorldView.TOPLEVEL ? SCENE_OFFSET : 0;

		final int[][][] tileHeights = scene.getTileHeights();
		int swHeight = tileHeights[tileZ][tileX][tileY];
		int seHeight = tileHeights[tileZ][tileX + 1][tileY];
		int neHeight = tileHeights[tileZ][tileX + 1][tileY + 1];
		int nwHeight = tileHeights[tileZ][tileX][tileY + 1];

		int swColor = tile.getSwColor();
		int seColor = tile.getSeColor();
		int neColor = tile.getNeColor();
		int nwColor = tile.getNwColor();

		if (neColor == HIDDEN_HSL)
		{
			return 0;
		}

		final int textureId = tile.getTexture();
		TileOverride override = data.getTileOverride(tileZ, tileX, tileY, TILE_OVERRIDE_MAIN);
		final boolean water = isWater(override, textureId);
		Material material = Material.NONE;
		int uvOrientation = 0;
		float uvScale = 1;

		// Vertex keys in the order SW, SE, NW, NE
		TerrainVertices.tileVertexKeys(data, this.tile, vertices, vertexKeys);
		final int swKey = vertexKeys[0];
		final int seKey = vertexKeys[1];
		final int nwKey = vertexKeys[2];
		final int neKey = vertexKeys[3];
		final short[] swNormal = terrainNormal(swKey, tileNormals[0]);
		final short[] seNormal = terrainNormal(seKey, tileNormals[1]);
		final short[] nwNormal = terrainNormal(nwKey, tileNormals[2]);
		final short[] neNormal = terrainNormal(neKey, tileNormals[3]);

		if (!water)
		{
			if (textureId != -1)
			{
				material = MaterialManager.fromVanillaTexture(textureId);
				// Disable tile overrides for newly introduced vanilla textures
				if (material.isFallbackVanillaMaterial)
				{
					override = TileOverride.NONE;
				}
			}
			if (override != TileOverride.NONE)
			{
				uvOrientation = override.uvOrientation;
				uvScale = override.uvScale;
			}

			// With ground blending off, 117 HD's tile colour is the client's tile colour with the override's change
			final boolean overrideApplies = override != TileOverride.NONE;
			swColor = DiffuseColors.terrainColor(swColor, override, overrideApplies);
			seColor = DiffuseColors.terrainColor(seColor, override, overrideApplies);
			nwColor = DiffuseColors.terrainColor(nwColor, override, overrideApplies);
			neColor = DiffuseColors.terrainColor(neColor, override, overrideApplies);
		}

		swHeight -= override.heightOffset;
		seHeight -= override.heightOffset;
		neHeight -= override.heightOffset;
		nwHeight -= override.heightOffset;

		final int lx0 = lx, lz0 = lz; // SW
		final int lx1 = lx + Perspective.LOCAL_TILE_SIZE, lz1 = lz; // SE
		final int lx2 = lx + Perspective.LOCAL_TILE_SIZE, lz2 = lz + Perspective.LOCAL_TILE_SIZE; // NE
		final int lx3 = lx, lz3 = lz + Perspective.LOCAL_TILE_SIZE; // NW

		final boolean doubleSided = isTileDoubleSided(tileZ, override);
		final int tex = textureId + 1;
		final int slot = MaterialManager.slot(material);

		// 117 HD's world position UVs, with the override's UV orientation and scale
		float uvcos = -uvScale, uvsin = 0;
		if (uvOrientation % 2048 != 0)
		{
			float rad = -uvOrientation * JAU_TO_RAD;
			uvcos = (float) Math.cos(rad) * -uvScale;
			uvsin = (float) Math.sin(rad) * -uvScale;
		}
		float uvx = worldPos[0];
		float uvy = worldPos[1];
		float tmp = uvx;
		uvx = fract(uvx * uvcos - uvy * uvsin);
		uvy = fract(tmp * uvsin + uvy * uvcos);

		// the tile's sky visibility quadrants at its corners, on its render level
		final int swSky = data.skyVisibilityCorner(tileZ, tileX, tileY, SceneData.SKY_SW);
		final int seSky = data.skyVisibilityCorner(tileZ, tileX, tileY, SceneData.SKY_SE);
		final int nwSky = data.skyVisibilityCorner(tileZ, tileX, tileY, SceneData.SKY_NW);
		final int neSky = data.skyVisibilityCorner(tileZ, tileX, tileY, SceneData.SKY_NE);

		// face 1: NE, NW, SE (overlay flags are only set on land in 117 HD)
		int flags = water ? 0 : overlayFlag(neKey, 0) | overlayFlag(nwKey, 1) | overlayFlag(seKey, 2);
		putTerrainFace(vertexBuffer, doubleSided, tex, flags, slot,
			lx2, neHeight, lz2, neColor, uvx, uvy, neNormal, neSky,
			lx3, nwHeight, lz3, nwColor, uvx - uvcos, uvy - uvsin, nwNormal, nwSky,
			lx1, seHeight, lz1, seColor, uvx + uvsin, uvy - uvcos, seNormal, seSky);

		// face 2: SW, SE, NW
		flags = water ? 0 : overlayFlag(swKey, 0) | overlayFlag(seKey, 1) | overlayFlag(nwKey, 2);
		putTerrainFace(vertexBuffer, doubleSided, tex, flags, slot,
			lx0, swHeight, lz0, swColor, uvx - uvcos + uvsin, uvy - uvsin - uvcos, swNormal, swSky,
			lx1, seHeight, lz1, seColor, uvx + uvsin, uvy - uvcos, seNormal, seSky,
			lx3, nwHeight, lz3, nwColor, uvx - uvcos, uvy - uvsin, nwNormal, nwSky);

		return doubleSided ? 12 : 6;
	}

	// 117 HD's uploadTileModel with ground blending off, without water, ground materials or HD textures
	private int upload(SceneTileModel sceneTileModel, int lx, int lz, GpuIntBuffer vertexBuffer)
	{
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

		final TileOverride overlayOverride = data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_OVERLAY);
		final TileOverride underlayOverride = data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_UNDERLAY);

		final int faceCount = faceX.length;

		int cnt = 0;
		for (int i = 0; i < faceCount; ++i)
		{
			int colorA = triangleColorA[i];
			int colorB = triangleColorB[i];
			int colorC = triangleColorC[i];

			if (colorA == HIDDEN_HSL)
			{
				continue;
			}

			final int textureId = triangleTextures != null ? triangleTextures[i] : -1;
			final boolean isOverlay = TerrainVertices.isOverlayFace(tile, i);
			TileOverride override = isOverlay ? overlayOverride : underlayOverride;
			final boolean water = isWater(override, textureId);
			Material material = Material.NONE;
			int uvOrientation = 0;
			float uvScale = 1;

			final int vertex0 = faceX[i];
			final int vertex1 = faceY[i];
			final int vertex2 = faceZ[i];

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

			TerrainVertices.faceVertexKeys(tile, i, vertices, vertexKeys);
			final short[] normalA = terrainNormal(vertexKeys[0], tileNormals[0]);
			final short[] normalB = terrainNormal(vertexKeys[1], tileNormals[1]);
			final short[] normalC = terrainNormal(vertexKeys[2], tileNormals[2]);

			if (!water)
			{
				if (textureId != -1)
				{
					material = MaterialManager.fromVanillaTexture(textureId);
					// Disable tile overrides for newly introduced vanilla textures
					if (material.isFallbackVanillaMaterial)
					{
						override = TileOverride.NONE;
					}
				}
				if (override != TileOverride.NONE)
				{
					uvOrientation = override.uvOrientation;
					uvScale = override.uvScale;
				}

				final boolean overrideApplies = override != TileOverride.NONE;
				colorA = DiffuseColors.terrainColor(colorA, override, overrideApplies);
				colorB = DiffuseColors.terrainColor(colorB, override, overrideApplies);
				colorC = DiffuseColors.terrainColor(colorC, override, overrideApplies);
			}

			// 117 HD sets the overlay flags of tile model faces whether or not they are water
			final int flags = overlayFlag(vertexKeys[0], 0) | overlayFlag(vertexKeys[1], 1) | overlayFlag(vertexKeys[2], 2);

			ly0 -= override.heightOffset;
			ly1 -= override.heightOffset;
			ly2 -= override.heightOffset;

			final boolean doubleSided = isTileDoubleSided(tileZ, override);
			final int tex = textureId + 1;

			// 117 HD's world position UVs, with the override's UV orientation and scale
			float uvcos = -uvScale, uvsin = 0;
			if (uvOrientation % 2048 != 0)
			{
				float rad = -uvOrientation * JAU_TO_RAD;
				uvcos = (float) Math.cos(rad) * -uvScale;
				uvsin = (float) Math.sin(rad) * -uvScale;
			}
			float uvx = fract(worldPos[0] * uvcos - worldPos[1] * uvsin);
			float uvy = fract(worldPos[0] * uvsin + worldPos[1] * uvcos);
			// (117 HD measures the vertex offsets from zone-local positions against the scene tile coordinates)
			int tileX = tile.getSceneLocation().getX();
			int tileY = tile.getSceneLocation().getY();
			float dx, dz;
			dx = (float) lx0 / Perspective.LOCAL_TILE_SIZE - tileX;
			dz = (float) lz0 / Perspective.LOCAL_TILE_SIZE - tileY;
			float uvAx = uvx + dx * uvcos - dz * uvsin;
			float uvAy = uvy + dx * uvsin + dz * uvcos;
			dx = (float) lx1 / Perspective.LOCAL_TILE_SIZE - tileX;
			dz = (float) lz1 / Perspective.LOCAL_TILE_SIZE - tileY;
			float uvBx = uvx + dx * uvcos - dz * uvsin;
			float uvBy = uvy + dx * uvsin + dz * uvcos;
			dx = (float) lx2 / Perspective.LOCAL_TILE_SIZE - tileX;
			dz = (float) lz2 / Perspective.LOCAL_TILE_SIZE - tileY;
			float uvCx = uvx + dx * uvcos - dz * uvsin;
			float uvCy = uvy + dx * uvsin + dz * uvcos;

			// the tile's sky visibility quadrants, bilinear at each vertex's position within the tile
			final int tileLx = tileX << 7, tileLz = tileY << 7;
			final int skyA = data.skyVisibility(tileZ, tileExX, tileExY, (lx0 + basex - tileLx) / 128f, (lz0 + basez - tileLz) / 128f);
			final int skyB = data.skyVisibility(tileZ, tileExX, tileExY, (lx1 + basex - tileLx) / 128f, (lz1 + basez - tileLz) / 128f);
			final int skyC = data.skyVisibility(tileZ, tileExX, tileExY, (lx2 + basex - tileLx) / 128f, (lz2 + basez - tileLz) / 128f);

			putTerrainFace(vertexBuffer, doubleSided, tex, flags, MaterialManager.slot(material),
				lx0, ly0, lz0, colorA, uvAx, uvAy, normalA, skyA,
				lx1, ly1, lz1, colorB, uvBx, uvBy, normalB, skyB,
				lx2, ly2, lz2, colorC, uvCx, uvCy, normalC, skyC);

			cnt += doubleSided ? 6 : 3;
		}

		return cnt;
	}

	// scene upload: 117 HD's uploadStaticModel (colours, alpha, bias and normals in FaceShading)
	private int uploadStaticModel(Model model, ModelOverride override, int preOrientation, int orient, int x, int y, int z, GpuIntBuffer vb, GpuIntBuffer ab)
	{
		final int vertexCount = model.getVerticesCount();
		final int triangleCount = model.getFaceCount();

		final float[] vertexX = model.getVerticesX();
		final float[] vertexY = model.getVerticesY();
		final float[] vertexZ = model.getVerticesZ();

		final int[] indices1 = model.getFaceIndices1();
		final int[] indices2 = model.getFaceIndices2();
		final int[] indices3 = model.getFaceIndices3();

		final short[] faceTextures = model.getFaceTextures();
		final float modelHeight = model.getModelHeight();

		if (override.rotate != 0)
		{
			orient = (int) (override.rotate * DEG_TO_JAU);
		}

		int orientSin = 0;
		int orientCos = 0;
		if (orient != 0)
		{
			orient = Math.floorMod(orient, 2048);
			orientSin = Perspective.SINE[orient];
			orientCos = Perspective.COSINE[orient];
		}

		y += override.heightOffset;

		final int[][][] tileHeights = data.scene.getTileHeights();
		for (int v = 0; v < vertexCount; ++v)
		{
			int vx = (int) vertexX[v];
			int vy = (int) vertexY[v];
			int vz = (int) vertexZ[v];
			float heightFrac = override.terrainVertexSnap ? Math.abs(vy / modelHeight) : 0.0f;

			if (orient != 0)
			{
				int x0 = vx;
				vx = vz * orientSin + x0 * orientCos >> 16;
				vz = vz * orientCos - x0 * orientSin >> 16;
			}

			vx += x;
			vy += y;
			vz += z;

			// 117 HD's terrainVertexSnap: blend low vertices towards the terrain height below them
			if (override.terrainVertexSnap && heightFrac <= override.terrainVertexSnapThreshold)
			{
				int vertexTileExX = clamp(data.sceneOffset + ((vx + basex) / 128), 0, Constants.EXTENDED_SCENE_SIZE - 1);
				int vertexTileExY = clamp(data.sceneOffset + ((vz + basez) / 128), 0, Constants.EXTENDED_SCENE_SIZE - 1);

				float h00 = tileHeights[tileZ][vertexTileExX][vertexTileExY];
				float h10 = tileHeights[tileZ][vertexTileExX + 1][vertexTileExY];
				float h01 = tileHeights[tileZ][vertexTileExX][vertexTileExY + 1];
				float h11 = tileHeights[tileZ][vertexTileExX + 1][vertexTileExY + 1];

				float hx0 = mix(h00, h10, (vx % 128.0f) / 128.0f);
				float hx1 = mix(h01, h11, (vx % 128.0f) / 128.0f);
				float h = mix(hx0, hx1, (vz % 128.0f) / 128.0f);

				float blend = override.terrainVertexSnapThreshold == 0 ? 0 : heightFrac / override.terrainVertexSnapThreshold;
				vy = (int) mix(h, vy, blend);
			}

			modelLocalXI[v] = vx;
			modelLocalYI[v] = vy;
			modelLocalZI[v] = vz;
			modelLocalXF[v] = vx;
			modelLocalYF[v] = vy;
			modelLocalZF[v] = vz;
		}

		if (alphaFaces.length < triangleCount * 2)
		{
			alphaFaces = new int[triangleCount * 2];
		}
		alphaFaceCount = 0;

		faceShading.setModel(model, override, orient, tileColors, false, modelLocalXF, modelLocalYF, modelLocalZF);

		final int[] n = faceShading.normals;
		int len = 0;
		for (int face = 0; face < triangleCount; ++face)
		{
			if (!faceShading.shade(face))
			{
				continue;
			}

			final int transparency = faceShading.transparency;
			final boolean alpha = faceShading.alpha;

			int triangleA = indices1[face];
			int triangleB = indices2[face];
			int triangleC = indices3[face];

			int vx1 = modelLocalXI[triangleA];
			int vy1 = modelLocalYI[triangleA];
			int vz1 = modelLocalZI[triangleA];

			int vx2 = modelLocalXI[triangleB];
			int vy2 = modelLocalYI[triangleB];
			int vz2 = modelLocalZI[triangleB];

			int vx3 = modelLocalXI[triangleC];
			int vy3 = modelLocalYI[triangleC];
			int vz3 = modelLocalZI[triangleC];

			// 117 HD's face UVs: vanilla (texture triangle), computed by the override's UV type, or geometry
			ModelUploader.faceUvs(faceShading, model, face, preOrientation, false, 0, 0, 0,
				modelLocalXF, modelLocalYF, modelLocalZF, basex, basez, uvs, uvWork);

			int alphaBias = transparency << 24 | faceShading.depthBias << 16;
			int c1 = alphaBias | faceShading.color1;
			int c2 = alphaBias | faceShading.color2;
			int c3 = alphaBias | faceShading.color3;
			int texture = faceTextures != null ? faceTextures[face] + 1 : 0;
			int flags = (faceShading.upwardsNormals ? 1 << 3 : 0) | (faceShading.uvType == UvType.VANILLA ? 1 << 4 : 0);
			int slot = MaterialManager.slot(faceShading.material);
			GpuIntBuffer buf = alpha ? ab : vb;

			// each vertex's sky visibility in flags bits 8-15 (the reversed copy keeps the front's values)
			int flags1 = flags | vertexSkyVisibility(vx1, vy1, vz1, n[0], n[1], n[2]) << 8;
			int flags2 = flags | vertexSkyVisibility(vx2, vy2, vz2, n[3], n[4], n[5]) << 8;
			int flags3 = flags | vertexSkyVisibility(vx3, vy3, vz3, n[6], n[7], n[8]) << 8;

			buf.putZoneVertex(vx1, vy1, vz1, c1, c2, c3, texture, uvs[0], uvs[1], flags1, n[0], n[1], n[2], slot);
			buf.putZoneVertex(vx2, vy2, vz2, c1, c2, c3, texture, uvs[4], uvs[5], flags2, n[3], n[4], n[5], slot);
			buf.putZoneVertex(vx3, vy3, vz3, c1, c2, c3, texture, uvs[8], uvs[9], flags3, n[6], n[7], n[8], slot);
			len += 3;
			if (alpha)
			{
				alphaFaces[alphaFaceCount++] = face;
			}

			if (faceShading.doubleSided)
			{
				// 117 HD's reversed copy: vertices C, B, A with negated normals (colours reversed with the winding)
				buf.putZoneVertex(vx3, vy3, vz3, c3, c2, c1, texture, uvs[8], uvs[9], flags3, -n[6], -n[7], -n[8], slot);
				buf.putZoneVertex(vx2, vy2, vz2, c3, c2, c1, texture, uvs[4], uvs[5], flags2, -n[3], -n[4], -n[5], slot);
				buf.putZoneVertex(vx1, vy1, vz1, c3, c2, c1, texture, uvs[0], uvs[1], flags1, -n[0], -n[1], -n[2], slot);
				len += 3;
				if (alpha)
				{
					alphaFaces[alphaFaceCount++] = face;
				}
			}
		}

		return len;
	}
}
