package com.midonarhel.lumina.dev;

import com.midonarhel.lumina.water.WaterMap;
import java.util.LinkedHashMap;
import java.util.Map;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;

/**
 * The scene's tiles by the water as the scene dump keeps them: what SceneUploader draws the water's bed and its banks from
 * (each tile's paint and shaped model, its render level and place, the tile under a bridge), so the bench can draw the
 * beds again with the code it has (the scene's heights, overlay and underlay ids and settings are the dump's "scene."
 * getters). The tiles of every zone (8 by 8) that holds water or is beside one that does, on every plane.
 */
public final class SceneTiles
{
	// zones round a zone with water whose tiles are kept too: the ground's colours blend across a zone's edge
	private static final int ZONE_REACH = 1;

	public int size, planes;
	// by key(plane, x, y), extended tiles
	public final Map<Integer, T> tiles = new LinkedHashMap<>();

	public static final class T
	{
		public int renderLevel, x, y;
		public P paint;
		public M model;
		public T bridge;
	}

	public static final class P
	{
		public int texture, sw, se, ne, nw;
	}

	public static final class M
	{
		public int[] vertexX, vertexY, vertexZ, faceX, faceY, faceZ, colorA, colorB, colorC, textures;
		public int shape;
	}

	public int key(int plane, int x, int y)
	{
		return (plane * size + x) * size + y;
	}

	/** On the client thread (or the loader's, with the scene loading); water: the scene's water map. */
	public static SceneTiles record(Scene scene, WaterMap water)
	{
		Tile[][][] tiles = scene.getExtendedTiles();
		SceneTiles s = new SceneTiles();
		s.planes = tiles.length;
		s.size = tiles.length > 0 ? tiles[0].length : 0;
		if (water == null || s.size == 0)
		{
			return s;
		}
		int zones = (s.size + 7) / 8;
		boolean[] wet = new boolean[zones * zones];
		for (int y = 0; y < s.size && y < WaterMap.SIZE; ++y)
		{
			for (int x = 0; x < s.size && x < WaterMap.SIZE; ++x)
			{
				if (water.level[y * WaterMap.SIZE + x] >= 0)
				{
					wet[(y >> 3) * zones + (x >> 3)] = true;
				}
			}
		}
		for (int z = 0; z < s.planes; ++z)
		{
			for (int x = 0; x < s.size; ++x)
			{
				for (int y = 0; y < s.size; ++y)
				{
					if (tiles[z][x][y] != null && near(wet, zones, x >> 3, y >> 3))
					{
						s.tiles.put(s.key(z, x, y), tile(tiles[z][x][y]));
					}
				}
			}
		}
		return s;
	}

	private static boolean near(boolean[] wet, int zones, int zx, int zy)
	{
		for (int dy = -ZONE_REACH; dy <= ZONE_REACH; ++dy)
		{
			for (int dx = -ZONE_REACH; dx <= ZONE_REACH; ++dx)
			{
				int x = zx + dx, y = zy + dy;
				if (x >= 0 && y >= 0 && x < zones && y < zones && wet[y * zones + x])
				{
					return true;
				}
			}
		}
		return false;
	}

	private static T tile(Tile t)
	{
		T r = new T();
		r.renderLevel = t.getRenderLevel();
		Point p = t.getSceneLocation();
		r.x = p.getX();
		r.y = p.getY();
		SceneTilePaint paint = t.getSceneTilePaint();
		if (paint != null)
		{
			r.paint = new P();
			r.paint.texture = paint.getTexture();
			r.paint.sw = paint.getSwColor();
			r.paint.se = paint.getSeColor();
			r.paint.ne = paint.getNeColor();
			r.paint.nw = paint.getNwColor();
		}
		SceneTileModel model = t.getSceneTileModel();
		if (model != null)
		{
			M m = new M();
			m.vertexX = copy(model.getVertexX());
			m.vertexY = copy(model.getVertexY());
			m.vertexZ = copy(model.getVertexZ());
			m.faceX = copy(model.getFaceX());
			m.faceY = copy(model.getFaceY());
			m.faceZ = copy(model.getFaceZ());
			m.colorA = copy(model.getTriangleColorA());
			m.colorB = copy(model.getTriangleColorB());
			m.colorC = copy(model.getTriangleColorC());
			m.textures = copy(model.getTriangleTextureId());
			m.shape = model.getShape();
			r.model = m;
		}
		Tile bridge = t.getBridge();
		r.bridge = bridge != null ? tile(bridge) : null;
		return r;
	}

	private static int[] copy(int[] a)
	{
		return a != null ? a.clone() : null;
	}
}
