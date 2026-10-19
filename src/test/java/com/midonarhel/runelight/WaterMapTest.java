package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;
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
				default:
					return null;
			}
		});
	}

	/**
	 * Flat land with a 20 x 20 lake in the middle, every tile on plane 0, given murk everywhere.
	 */
	private static WaterMap lake(int murk)
	{
		Tile[][][] tiles = new Tile[4][N][N];
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				boolean water = x >= 80 && x < 100 && y >= 80 && y < 100;
				tiles[0][x][y] = tile(x, y, water ? 1 : -1);
			}
		}
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
		return WaterMap.build(scene, (x, y, plane) -> murk, null);
	}

	@Test
	public void lakeBed()
	{
		WaterMap m = lake(WaterMap.CLEAR);
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
		WaterMap m = lake(WaterMap.CLEAR);
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
	public void murkyAreas()
	{
		assertEquals(WaterMap.CAVE, lake(WaterMap.MURKY_CAVE).type[90 * N + 90]);
		assertEquals(WaterMap.SWAMP, lake(WaterMap.MURKY_SWAMP).type[90 * N + 90]);
		// the bed is mud in a swamp, sand otherwise
		WaterMap clear = lake(WaterMap.CLEAR), swamp = lake(WaterMap.MURKY_SWAMP);
		int sand = clear.bedColor(85, 85, 0);
		int mud = swamp.bedColor(85, 85, 0);
		assertTrue((sand & 127) > (mud & 127));
	}
}
