package com.midonarhel.lumina.dev;

import java.util.ArrayList;
import java.util.List;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import net.runelite.api.WorldView;

/**
 * What a scene's buildings are made from (visibility/BuildingLights: the walls, the diagonal walls, the roofs, the
 * floors), recorded at a scene dump on the client thread, so the offline renderer can fake the scene's tiles and build
 * the buildings again with the code under test (harness/SceneObjectFakes): the collision maps, every wall object, the
 * game objects of the diagonal-wall and roof types, and each tile's floor (its paint's colour, whether it has a model).
 * A model is recorded as getModel made it on the client thread; whether the renderable was a plain model or an
 * animated one (whose getModel the client allows only on its thread) is kept too.
 */
public final class SceneObjects
{
	public static final int KIND_WALL = 0, KIND_GAME = 1;
	// the game object types recorded: the diagonal wall (9) and the roof pieces (12 to 21)
	static final int TYPE_FIRST = 9, TYPE_LAST = 21;
	// a tile's paint colour where it has none
	public static final int NO_PAINT = Integer.MIN_VALUE;

	public int size;
	// [plane][x][y], local tiles
	public int[][][] flags;
	// per extended tile, (plane * size + x) * size + y: the paint's north-east colour (NO_PAINT: none), a tile model
	public int[] paint;
	public boolean[] tileModel;
	// whether a tile exists there at all
	public boolean[] tile;
	public List<Obj> objects = new ArrayList<>();

	public static final class Obj
	{
		public int kind, plane, x, y, id, config, z, orientation;
		// local scene tile of the object's south-west corner (game objects)
		public int minX, minY;
		public Mesh a, b;
	}

	public static final class Mesh
	{
		// the renderable was a Model itself (not an animated object's)
		public boolean plain;
		// getModel gave none
		public boolean none;
		public float[] x, y, z;
		public int count;
	}

	/** On the client thread. */
	public static SceneObjects record(WorldView wv)
	{
		Scene scene = wv.getScene();
		Tile[][][] tiles = scene.getExtendedTiles();
		SceneObjects s = new SceneObjects();
		s.size = tiles != null && tiles.length > 0 ? tiles[0].length : 0;
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps != null)
		{
			s.flags = new int[maps.length][][];
			for (int i = 0; i < maps.length; ++i)
			{
				if (maps[i] != null)
				{
					int[][] f = maps[i].getFlags();
					s.flags[i] = new int[f.length][];
					for (int x = 0; x < f.length; ++x)
					{
						s.flags[i][x] = f[x].clone();
					}
				}
			}
		}
		int n = 4 * s.size * s.size;
		s.paint = new int[n];
		s.tileModel = new boolean[n];
		s.tile = new boolean[n];
		for (int plane = 0; plane < 4 && tiles != null && plane < tiles.length; ++plane)
		{
			for (int x = 0; x < s.size && x < tiles[plane].length; ++x)
			{
				for (int y = 0; y < s.size && y < tiles[plane][x].length; ++y)
				{
					Tile t = tiles[plane][x][y];
					int i = (plane * s.size + x) * s.size + y;
					s.paint[i] = NO_PAINT;
					if (t == null)
					{
						continue;
					}
					s.tile[i] = true;
					SceneTilePaint p = t.getSceneTilePaint();
					s.paint[i] = p != null ? p.getNeColor() : NO_PAINT;
					s.tileModel[i] = t.getSceneTileModel() != null;
					WallObject w = t.getWallObject();
					if (w != null)
					{
						Obj o = new Obj();
						o.kind = KIND_WALL;
						o.plane = plane;
						o.x = x;
						o.y = y;
						o.id = w.getId();
						o.config = w.getConfig();
						o.z = w.getZ();
						o.orientation = w.getOrientationA();
						o.a = mesh(w.getRenderable1(), false);
						o.b = mesh(w.getRenderable2(), false);
						s.objects.add(o);
					}
					GameObject[] objects = t.getGameObjects();
					if (objects == null)
					{
						continue;
					}
					for (GameObject go : objects)
					{
						int type = go != null ? go.getConfig() & 0x1F : -1;
						if (type < TYPE_FIRST || type > TYPE_LAST || go.getSceneMinLocation() == null)
						{
							continue;
						}
						Obj o = new Obj();
						o.kind = KIND_GAME;
						o.plane = plane;
						o.x = x;
						o.y = y;
						o.id = go.getId();
						o.config = go.getConfig();
						o.z = go.getZ();
						o.orientation = go.getModelOrientation();
						o.minX = go.getSceneMinLocation().getX();
						o.minY = go.getSceneMinLocation().getY();
						o.a = mesh(go.getRenderable(), true);
						s.objects.add(o);
					}
				}
			}
		}
		return s;
	}

	private static Mesh mesh(Renderable r, boolean vertices)
	{
		if (r == null)
		{
			return null;
		}
		Mesh m = new Mesh();
		m.plain = r instanceof Model;
		Model model = m.plain ? (Model) r : r.getModel();
		m.none = model == null;
		if (model != null)
		{
			m.count = model.getVerticesCount();
			if (vertices)
			{
				m.x = java.util.Arrays.copyOf(model.getVerticesX(), m.count);
				m.y = java.util.Arrays.copyOf(model.getVerticesY(), m.count);
				m.z = java.util.Arrays.copyOf(model.getVerticesZ(), m.count);
			}
		}
		return m;
	}
}
