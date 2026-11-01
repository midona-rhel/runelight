package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneObjects;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.CollisionData;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Point;
import net.runelite.api.Renderable;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;

/**
 * The scene's tiles and collision maps faked from a dump's SceneObjects, so BuildingLights builds the buildings again
 * offline. As in the client, an animated renderable's getModel may only be called on the client thread (the thread that
 * made the fakes): elsewhere it throws "must be called on client thread", as the client did at 13:46:14 on 2026-11-01
 * when the buildings worker asked one (64770dd9).
 */
public final class SceneObjectFakes
{
	public final Tile[][][] tiles;
	public final CollisionData[] maps;
	private final Thread clientThread;
	private final int offset;

	public SceneObjectFakes(SceneObjects s, int offset)
	{
		this.clientThread = Thread.currentThread();
		this.offset = offset;
		int size = s.size;
		Map<Integer, List<SceneObjects.Obj>> byTile = new HashMap<>();
		for (SceneObjects.Obj o : s.objects)
		{
			byTile.computeIfAbsent((o.plane * size + o.x) * size + o.y, k -> new ArrayList<>()).add(o);
		}
		tiles = new Tile[4][size][size];
		for (int plane = 0; plane < 4; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int i = (plane * size + x) * size + y;
					if (s.tile != null && s.tile[i])
					{
						tiles[plane][x][y] = tile(s, plane, x, y, i, byTile.getOrDefault(i, List.of()));
					}
				}
			}
		}
		maps = new CollisionData[s.flags != null ? s.flags.length : 0];
		for (int i = 0; i < maps.length; ++i)
		{
			int[][] f = s.flags[i];
			maps[i] = f == null ? null : Fakes.of(CollisionData.class, Map.of("getFlags", f));
		}
	}

	private Tile tile(SceneObjects s, int plane, int x, int y, int i, List<SceneObjects.Obj> objects)
	{
		Map<String, Object> a = new HashMap<>();
		int paint = s.paint[i];
		a.put("getSceneTilePaint", paint == SceneObjects.NO_PAINT ? null : Fakes.of(SceneTilePaint.class, Map.of("getNeColor", paint)));
		a.put("getSceneTileModel", s.tileModel[i] ? Fakes.of(SceneTileModel.class, Map.of()) : null);
		a.put("getSceneLocation", new Point(x - offset, y - offset));
		a.put("getPlane", plane);
		a.put("getRenderLevel", plane);
		WallObject wall = null;
		List<GameObject> games = new ArrayList<>();
		for (SceneObjects.Obj o : objects)
		{
			if (o.kind == SceneObjects.KIND_WALL)
			{
				Map<String, Object> w = new HashMap<>();
				w.put("getId", o.id);
				w.put("getConfig", o.config);
				w.put("getZ", o.z);
				w.put("getOrientationA", o.orientation);
				w.put("getPlane", o.plane);
				w.put("getRenderable1", renderable(o.a));
				w.put("getRenderable2", renderable(o.b));
				wall = Fakes.of(WallObject.class, w);
			}
			else
			{
				Map<String, Object> g = new HashMap<>();
				g.put("getId", o.id);
				g.put("getConfig", o.config);
				g.put("getZ", o.z);
				g.put("getModelOrientation", o.orientation);
				g.put("getPlane", o.plane);
				g.put("getSceneMinLocation", new Point(o.minX, o.minY));
				g.put("getRenderable", renderable(o.a));
				games.add(Fakes.of(GameObject.class, g));
			}
		}
		a.put("getWallObject", wall);
		a.put("getGameObjects", games.toArray(new GameObject[0]));
		return Fakes.of(Tile.class, a);
	}

	private Renderable renderable(SceneObjects.Mesh m)
	{
		if (m == null)
		{
			return null;
		}
		Model model = m.none ? null : model(m);
		if (m.plain && model != null)
		{
			return model;
		}
		Map<String, Object> a = new HashMap<>();
		a.put("getModel", (Fakes.Answer) args ->
		{
			if (Thread.currentThread() != clientThread)
			{
				throw new IllegalStateException("must be called on client thread");
			}
			return model;
		});
		return Fakes.of(Renderable.class, a);
	}

	private static Model model(SceneObjects.Mesh m)
	{
		Map<String, Object> a = new HashMap<>();
		a.put("getVerticesCount", m.count);
		a.put("getVerticesX", m.x != null ? m.x : new float[m.count]);
		a.put("getVerticesY", m.y != null ? m.y : new float[m.count]);
		a.put("getVerticesZ", m.z != null ? m.z : new float[m.count]);
		Model[] self = new Model[1];
		a.put("getModel", (Fakes.Answer) args -> self[0]);
		self[0] = Fakes.of(Model.class, a);
		return self[0];
	}
}
