package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import org.junit.Test;

public class WaterMapTest
{
	private static final int N = WaterMap.SIZE;
	private static final int OFF = RuneLightPlugin.SCENE_OFFSET;

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> c, java.util.function.Function<String, Object> answer)
	{
		return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{c}, (p, m, a) -> answer.apply(m.getName()));
	}

	private static Tile tile(int x, int y, int texture)
	{
		return tile(x, y, texture, null, null);
	}

	private static Tile tile(int x, int y, int texture, Tile bridge, GameObject[] objects)
	{
		SceneTilePaint paint = proxy(SceneTilePaint.class, m ->
		{
			switch (m)
			{
				case "getTexture":
					return texture;
				case "getNeColor":
					return 0;
				default:
					return null;
			}
		});
		Point loc = new Point(x - OFF, y - OFF);
		return proxy(Tile.class, m ->
		{
			switch (m)
			{
				case "getSceneTilePaint":
					return paint;
				case "getRenderLevel":
					return 0;
				case "getSceneLocation":
					return loc;
				case "getBridge":
					return bridge;
				case "getGameObjects":
					return objects;
				default:
					return null;
			}
		});
	}

	/**
	 * Flat land with a 20 x 20 lake in the middle drawn with the given water texture.
	 */
	private static WaterMap lake(int texture)
	{
		return flat(texture, (x, y) -> x >= 80 && x < 100 && y >= 80 && y < 100);
	}

	/**
	 * Flat land with water of the given texture where {@code water} says, every tile on plane 0.
	 */
	private static WaterMap flat(int texture, java.util.function.BiPredicate<Integer, Integer> water)
	{
		Tile[][][] tiles = new Tile[4][N][N];
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				tiles[0][x][y] = tile(x, y, water.test(x, y) ? texture : -1);
			}
		}
		return build(tiles);
	}

	private static WaterMap build(Tile[][][] tiles)
	{
		int[][][] heights = new int[4][N + 1][N + 1];
		Scene scene = proxy(Scene.class, m ->
		{
			switch (m)
			{
				case "getExtendedTiles":
					return tiles;
				case "getTileHeights":
					return heights;
				case "getBaseX":
				case "getBaseY":
					return 3200;
				default:
					return null;
			}
		});
		return WaterMap.build(scene, null, null);
	}

	@Test
	public void lakeBed()
	{
		WaterMap m = lake(1);
		assertTrue(m.hasWater);
		assertTrue(m.isBed(90, 90, 0, 1));
		assertFalse(m.isBed(90, 90, 1, 1));
		assertFalse(m.isBed(10, 10, 0, -1));
		// the shore stays put, the middle sinks, deepest in the middle
		assertEquals(0, m.bedOffset(80, 90));
		assertTrue(m.bedOffset(81, 90) > 0);
		assertTrue(m.bedOffset(90, 90) > m.bedOffset(83, 90));
		assertEquals(WaterMap.depthForLevel(WaterMap.MAX_LEVEL), m.bedOffset(90, 90));
		assertEquals(m.bedOffset(90, 90), m.bedHeights(0)[90][90]);
		assertEquals(0, m.bedHeights(0)[10][10]);
		// tiles on the shore sink in the middle instead
		assertTrue(m.isShoreTile(80, 90));
		assertFalse(m.isShoreTile(85, 90));
		assertTrue(m.centerOffset(80, 90) >= WaterMap.MIN_DEPTH);
		assertEquals(WaterMap.FRESH, m.type[90 * N + 90]);
		assertEquals(WaterBodies.LAKE, m.bodies.bodyClass[m.bodies.body[90 * N + 90]]);
	}

	@Test
	public void textureLayout()
	{
		WaterMap m = lake(1);
		int w = WaterMap.CORNERS * 2;
		// page 0: the tile type sits at its sw corner; page 1: shore distance and the water share
		assertEquals(WaterMap.FRESH, m.texture[(90 * w + 90) * 4 + 3], 0);
		assertEquals(0, m.texture[(10 * w + 10) * 4 + 3], 0);
		int p1 = (90 * w + WaterMap.CORNERS + 90) * 4;
		assertTrue(m.texture[p1] >= 9);
		assertEquals(1, m.texture[p1 + 2], 0);
		// a corner on the shore touches two water tiles of four
		assertEquals(0.5f, m.texture[(90 * w + WaterMap.CORNERS + 80) * 4 + 2], 0);
	}

	@Test
	public void landCornersTakeTheWaterLevel()
	{
		int w = WaterMap.CORNERS * 2;
		float[] t = new float[w * WaterMap.CORNERS * 4];
		for (int cy = 0; cy < WaterMap.CORNERS; ++cy)
		{
			for (int cx = 0; cx < WaterMap.CORNERS; ++cx)
			{
				t[(cy * w + cx) * 4] = Float.NaN;
			}
		}
		// two pools at different levels
		t[(20 * w + 20) * 4] = -300;
		t[(20 * w + 120) * 4] = -800;
		WaterMap.spreadLevel(t, w);
		assertEquals(-300, t[(22 * w + 21) * 4], 0);
		assertEquals(-300, t[0], 0);
		assertEquals(-800, t[(25 * w + 118) * 4], 0);
		for (int cy = 0; cy < WaterMap.CORNERS; ++cy)
		{
			for (int cx = 0; cx < WaterMap.CORNERS; ++cx)
			{
				assertFalse(Float.isNaN(t[(cy * w + cx) * 4]));
			}
		}
		// page 1 untouched
		assertEquals(0, t[(20 * w + WaterMap.CORNERS + 20) * 4], 0);
	}

	@Test
	public void bedTakesTheBankColors()
	{
		int grass = 15 << 10 | 4 << 7 | 30, sand = 8 << 10 | 2 << 7 | 50;
		assertEquals(-1, SceneUploader.mixHsl(new int[]{-1, -1, -1, -1}, 4));
		assertEquals(grass, SceneUploader.mixHsl(new int[]{grass, -1, grass, -1}, 4));
		int m = SceneUploader.mixHsl(new int[]{grass, sand, -1, -1}, 4);
		assertEquals(12, m >> 10 & 63);
		assertEquals(3, m >> 7 & 7);
		assertEquals(40, m & 127);
	}

	@Test
	public void typeByTexture()
	{
		// 117 HD's rule, tile by tile: the swamp texture gives swamp water, the plain ones fresh
		assertEquals(WaterMap.FRESH, lake(1).type[90 * N + 90]);
		assertEquals(WaterMap.SWAMP, lake(25).type[90 * N + 90]);
		// the bed is mud in a swamp, sand otherwise
		int sand = lake(1).bedColor(85, 85, 0);
		int mud = lake(25).bedColor(85, 85, 0);
		assertTrue((sand & 127) > (mud & 127));
	}

	@Test
	public void seaByWidth()
	{
		// the game draws the sea with the rivers' texture: wide water running off the scene is the sea, to its shore
		WaterMap sea = flat(1, (x, y) -> x >= 60);
		assertEquals(WaterMap.OCEAN, sea.type[100 * N + 150]);
		assertEquals(WaterMap.OCEAN, sea.type[100 * N + 60]);
		assertEquals(WaterMap.NONE, sea.type[100 * N + 59]);
		// a narrow channel off the scene is a river, a swamp stays a swamp, an enclosed lake a lake
		assertEquals(WaterMap.FRESH, flat(1, (x, y) -> x >= 90 && x < 94).type[100 * N + 91]);
		assertEquals(WaterMap.SWAMP, flat(25, (x, y) -> x >= 60).type[100 * N + 150]);
		assertEquals(WaterMap.FRESH, lake(1).type[90 * N + 90]);
	}

	/**
	 * A one tile wide pier: a triangle from the surface up 100 units, standing in the middle of tile (90, 90) at
	 * the given height above it.
	 */
	private static GameObject pier(int hover)
	{
		Model model = proxy(Model.class, m ->
		{
			switch (m)
			{
				case "getVerticesX":
					return new float[]{0, 64, 0};
				case "getVerticesY":
					return new float[]{0, 0, -100};
				case "getVerticesZ":
					return new float[3];
				case "getFaceIndices1":
					return new int[]{0};
				case "getFaceIndices2":
					return new int[]{1};
				case "getFaceIndices3":
					return new int[]{2};
				case "getFaceColors3":
					return new int[]{0};
				case "getFaceCount":
					return 1;
				case "getVerticesCount":
					return 3;
				default:
					return null;
			}
		});
		Point at = new Point(90 - OFF, 90 - OFF);
		return proxy(GameObject.class, m ->
		{
			switch (m)
			{
				case "getSceneMinLocation":
					return at;
				case "getRenderable":
					return model;
				case "getX":
					return (90 - OFF) * 128 + 32;
				case "getY":
					return (90 - OFF) * 128 + 64;
				case "getZ":
					return -hover;
				default:
					return 0;
			}
		});
	}

	private static boolean touched(WaterMap m)
	{
		if (m.contact == null)
		{
			return false;
		}
		// the cells of tile (90, 90)
		for (int cy = 90 * WaterMap.CONTACT_RES; cy < 91 * WaterMap.CONTACT_RES; ++cy)
		{
			for (int cx = 90 * WaterMap.CONTACT_RES; cx < 91 * WaterMap.CONTACT_RES; ++cx)
			{
				if (m.contact[cy * WaterMap.CONTACT_SIZE + cx] != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	private static WaterMap withPier(int hover, boolean underBridge)
	{
		Tile[][][] tiles = new Tile[4][N][N];
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				tiles[0][x][y] = tile(x, y, x >= 80 && x < 100 && y >= 80 && y < 100 ? 1 : -1);
			}
		}
		GameObject[] pier = {pier(hover)};
		tiles[0][90][90] = underBridge ? tile(90, 90, -1, tile(90, 90, 1, null, pier), null) : tile(90, 90, 1, null, pier);
		return build(tiles);
	}

	@Test
	public void piersFoam()
	{
		assertTrue(touched(withPier(0, false)));
		// the bridge tile under a deck holds the piers of a bridge
		assertTrue(touched(withPier(0, true)));
		// a foot a little over the surface (bank tiles climb) stands in it, one high over it is clear
		assertTrue(touched(withPier(30, false)));
		assertFalse(touched(withPier(80, false)));
	}
}
