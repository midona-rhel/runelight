package com.midonarhel.lumina.harness;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneObjects;
import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.WallMap;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.GameState;
import net.runelite.api.Scene;
import net.runelite.api.WorldView;
import org.junit.Test;

/**
 * BuildingLights builds the walls as the client does on a scene load, its analysis on its worker, against a scene
 * whose animated renderables answer getModel only on the client thread (SceneObjectFakes, as the client: 64770dd9 asked
 * one on the worker, it threw, and the scene was left with no walls). And a wall object with no model to draw (the
 * box round an Ardougne market stall) is no wall; a drawn one, and an animated one, are.
 */
public class BuildingsRebuildTest
{
	private static final int SIZE = Constants.EXTENDED_SCENE_SIZE, OFFSET = LuminaPlugin.SCENE_OFFSET;
	private static final int LOCAL = Constants.SCENE_SIZE;

	@Test
	public void wallsAreBuiltOffTheClientThreadAndOnlyDrawnOnesCount()
	{
		SceneObjects s = new SceneObjects();
		s.size = SIZE;
		s.flags = new int[4][LOCAL][LOCAL];
		int n = 4 * SIZE * SIZE;
		s.paint = new int[n];
		s.tileModel = new boolean[n];
		s.tile = new boolean[n];
		java.util.Arrays.fill(s.paint, SceneObjects.NO_PAINT);
		// three walls on the west edges of tiles 100, 110 and 120 (extended) on row 100: drawn, never drawn, animated
		wall(s, 100, 100, mesh(true, 4));
		wall(s, 110, 100, mesh(true, 0));
		wall(s, 120, 100, mesh(false, 8));

		SceneObjectFakes fakes = new SceneObjectFakes(s, OFFSET);
		Map<String, Object> sc = new HashMap<>();
		sc.put("getExtendedTiles", fakes.tiles);
		sc.put("getExtendedTileSettings", new byte[4][SIZE][SIZE]);
		sc.put("getRoofs", new int[4][SIZE][SIZE]);
		sc.put("getTileHeights", new int[4][SIZE + 1][SIZE + 1]);
		sc.put("getBaseX", 3200);
		sc.put("getBaseY", 3200);
		sc.put("getWorldViewId", WorldView.TOPLEVEL);
		Scene scene = Fakes.of(Scene.class, sc);
		WorldView wv = Fakes.of(WorldView.class, Map.of("getScene", scene, "getCollisionMaps", fakes.maps, "getId",
			WorldView.TOPLEVEL, "isTopLevel", true));
		Client client = Fakes.of(Client.class, Map.of("getGameState", GameState.LOGGED_IN, "getTopLevelWorldView", wv,
			"isClientThread", true));

		BuildingLights b = new BuildingLights(client);
		b.rebuild();
		long until = System.nanoTime() + 20_000_000_000L;
		while (b.getWalls() == null && System.nanoTime() < until)
		{
			java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);
			b.apply();
		}
		WallMap w = b.getWalls();
		assertNotNull("no walls: the buildings worker threw (a client call off the client thread?)", w);
		assertTrue("a drawn wall stops light", w.edge(0, 100, 100, WallMap.WEST));
		assertFalse("a wall with nothing to draw doesn't", w.edge(0, 110, 100, WallMap.WEST));
		assertFalse(w.edge(0, 109, 100, WallMap.EAST));
		assertTrue("an animated wall does", w.edge(0, 120, 100, WallMap.WEST));
		b.shutDown();
	}

	private static SceneObjects.Mesh mesh(boolean plain, int count)
	{
		SceneObjects.Mesh m = new SceneObjects.Mesh();
		m.plain = plain;
		m.count = count;
		return m;
	}

	private static void wall(SceneObjects s, int x, int y, SceneObjects.Mesh mesh)
	{
		// the collision map: sight blocked across the west edge, from both tiles
		s.flags[0][x - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
		s.flags[0][x - 1 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
		s.tile[(0 * SIZE + x) * SIZE + y] = true;
		SceneObjects.Obj o = new SceneObjects.Obj();
		o.kind = SceneObjects.KIND_WALL;
		o.x = x;
		o.y = y;
		o.id = 85;
		o.a = mesh;
		s.objects.add(o);
	}
}
