package com.midonarhel.runelight.water;

import com.midonarhel.runelight.*;

import com.midonarhel.runelight.scene.SceneUploader;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.lang.reflect.Proxy;
import net.runelite.api.GroundObject;
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
		return tile(x, y, texture, null);
	}

	private static Tile tile(int x, int y, int texture, GroundObject ground)
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
				case "getGroundObject":
					return ground;
				default:
					return null;
			}
		});
	}

	/**
	 * Flat land with a 20 x 20 lake in the middle drawn with the given water texture, every tile on plane 0.
	 */
	private static WaterMap lake(int texture)
	{
		return flat((x, y) -> x >= 80 && x < 100 && y >= 80 && y < 100 ? texture : -1);
	}

	/**
	 * Flat land with water where texture gives a water texture (-1 for land), every tile on plane 0.
	 */
	private static WaterMap flat(java.util.function.IntBinaryOperator texture)
	{
		Tile[][][] tiles = new Tile[4][N][N];
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				tiles[0][x][y] = tile(x, y, texture.applyAsInt(x, y));
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
		int w = WaterMap.CORNERS * WaterMap.PAGES;
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
	public void distanceToLandIsStraightLine()
	{
		int n = 16;
		boolean[] land = new boolean[n * n];
		land[3 * n + 4] = true;
		land[12 * n + 13] = true;
		float[] d = new float[n * n];
		WaterMap.distanceToLand(land, n, 100, d);
		assertEquals(0, d[3 * n + 4], 0);
		assertEquals(5, d[7 * n + 7], 1e-5f);                // 3 across, 4 down: the 3-4-5 triangle, not a walk of 7
		assertEquals(Math.hypot(1, 1), d[4 * n + 5], 1e-5f);  // the diagonal
		assertEquals(Math.hypot(2, 1), d[11 * n + 11], 1e-5f); // nearer to the second
		// reaches no further than max
		WaterMap.distanceToLand(land, n, 3, d);
		assertEquals(3, d[10 * n + 0], 0);
	}

	@Test
	public void distanceToLandWithoutLandIsMax()
	{
		int n = 8;
		float[] d = new float[n * n];
		WaterMap.distanceToLand(new boolean[n * n], n, 32, d);
		for (float v : d)
		{
			assertEquals(32, v, 0);
		}
	}

	@Test
	public void shorePageHoldsTheDistanceToLand()
	{
		WaterMap m = lake(1);
		int w = WaterMap.CORNERS * WaterMap.PAGES;
		int page2 = WaterMap.CORNERS * 2;
		// the middle of the lake is further from land than its edge
		float edge = m.texture[(90 * w + page2 + 80) * 4];
		float middle = m.texture[(90 * w + page2 + 90) * 4];
		assertTrue(edge < middle);
		assertTrue(middle <= WaterMap.SHORE_FIELD_REACH);
	}

	@Test
	public void landCornersTakeTheWaterLevel()
	{
		int w = WaterMap.CORNERS * WaterMap.PAGES;
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
	public void waterPullsTowardAFallsLip()
	{
		int n = WaterMap.CORNERS;
		float[] px = new float[n * n], pz = new float[n * n], level = new float[n * n], strength = new float[n * n];
		// a lip along z = 10 tiles from x = 5 to 7, 100 units up
		java.util.List<float[]> lips = java.util.Collections.singletonList(new float[]{5 * 128f, 10 * 128f, 7 * 128f, 10 * 128f, 100f});
		WaterMap.pullToLines(lips, 2.5f, px, pz, level, strength);
		int near = 8 * n + 6, far = 4 * n + 6, beside = 10 * n + 12;
		assertEquals(0f, px[near], 1e-4f);
		assertEquals(1f - 2f / 2.5f, pz[near], 1e-4f);
		assertEquals(100f, level[near], 0f);
		assertEquals(1f - 2f / 2.5f, strength[near], 1e-4f);
		// on the lip: no direction, full strength
		assertEquals(1f, strength[10 * n + 6], 1e-4f);
		assertEquals(0f, pz[10 * n + 6], 0f);
		assertEquals(0f, pz[far], 0f);
		assertEquals(0f, px[beside], 0f);
		// the pull points back at the lip from the other side too
		assertTrue(pz[12 * n + 6] < 0f);
	}

	@Test
	public void aFallsContactMarksOnlyTheWaterAtItsLevel()
	{
		// a lip at height 100 along z = 5 tiles, the pool above it (z < 5) at 100 and the one below it at 400
		float[] surface = new float[N * N];
		for (int y = 0; y < N; ++y)
		{
			for (int x = 0; x < N; ++x)
			{
				surface[y * N + x] = y < 5 ? 100f : y < 10 ? 400f : Float.NaN;
			}
		}
		byte[] c = new byte[WaterMap.CONTACT_SIZE * WaterMap.CONTACT_SIZE];
		WaterMap.spreadLine(new float[]{4 * 128f, 5 * 128f, 6 * 128f, 5 * 128f, 100f}, 8, surface, c);
		int cells = WaterMap.CONTACT_RES;
		// the cell just above the lip is marked, the one just below it, over the lower pool, is not
		assertTrue((c[(5 * cells - 1) * WaterMap.CONTACT_SIZE + 5 * cells] & 255) > 0);
		assertEquals(0, c[(5 * cells + 1) * WaterMap.CONTACT_SIZE + 5 * cells] & 255);
		// a foot at 400 marks the lower pool and leaves the upper one
		java.util.Arrays.fill(c, (byte) 0);
		WaterMap.spreadLine(new float[]{4 * 128f, 5 * 128f, 6 * 128f, 5 * 128f, 400f}, 8, surface, c);
		assertEquals(0, c[(5 * cells - 1) * WaterMap.CONTACT_SIZE + 5 * cells] & 255);
		assertTrue((c[(5 * cells + 1) * WaterMap.CONTACT_SIZE + 5 * cells] & 255) > 0);
	}

	/**
	 * A strip of water 3 tiles wide running in direction k (0 +x, 1 -x, 2 +z, 3 -z, WaterMap's FALL_DX): level to 83 along it, a
	 * fall's tile at 83 dropping `drop` over its run, level again from 84 to 87; land beside it, at the strip's heights. With
	 * `land`, the tile below the fall is land. Coordinates along the run are mirrored for the negative directions.
	 */
	static WaterMap fallStrip(int drop, boolean land, int k)
	{
		boolean alongX = k < 2;
		Tile[][][] tiles = new Tile[4][N][N];
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				int along = k == 0 ? x : k == 1 ? 167 - x : k == 2 ? y : 167 - y;
				int across = alongX ? y : x;
				boolean water = across >= 80 && across < 83 && along >= 80 && along < 88 && !(land && along >= 84);
				tiles[0][x][y] = tile(x, y, water ? 1 : -1);
			}
		}
		int[][][] heights = new int[4][N + 1][N + 1];
		for (int x = 0; x <= N; ++x)
		{
			for (int y = 0; y <= N; ++y)
			{
				int along = k == 0 ? x : k == 1 ? 168 - x : k == 2 ? y : 168 - y;
				heights[0][x][y] = along <= 83 ? 0 : drop;
			}
		}
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
	public void aSteepTileBetweenTwoPoolsIsAFall()
	{
		WaterMap m = fallStrip(256, false, 2);
		for (int y = 80; y < 88; ++y)
		{
			assertEquals("tile " + y, y == 83, m.fall[y * N + 81]);
		}
		// its surface is the slope: the terrain's heights at its corners, the pools' above and below
		int w = WaterMap.CORNERS * WaterMap.PAGES;
		assertEquals(0f, m.texture[(83 * w + 81) * 4], 0f);
		assertEquals(256f, m.texture[(84 * w + 81) * 4], 0f);
		// the frame points down the fall (+y), 1 above it and 1 plus the slope's extra length from the foot on: sqrt(1 + 2 ^ 2)
		int p2 = 2 * WaterMap.CORNERS;
		assertEquals(1f, m.texture[(83 * w + p2 + 81) * 4 + 3], 1e-5f);
		assertEquals(0f, m.texture[(83 * w + p2 + 81) * 4 + 2], 0f);
		assertEquals((float) Math.sqrt(5), m.texture[(84 * w + p2 + 81) * 4 + 3], 1e-5f);
		assertEquals((float) Math.sqrt(5), m.texture[(86 * w + p2 + 81) * 4 + 3], 1e-5f);
		// it runs down the fall, fast
		assertTrue(m.texture[(83 * w + 81) * 4 + 2] > 2f);
		// its water is covered in foam, thickest at the lip and the foot
		int cells = WaterMap.CONTACT_RES;
		int first = m.contact[(83 * cells) * WaterMap.CONTACT_SIZE + 81 * cells + 1] & 255;
		int middle = m.contact[(83 * cells + 1) * WaterMap.CONTACT_SIZE + 81 * cells + 1] & 255;
		int last = m.contact[(83 * cells + cells - 1) * WaterMap.CONTACT_SIZE + 81 * cells + 1] & 255;
		assertTrue(middle > 128);
		assertTrue(first > middle);
		assertTrue(last > middle);
	}

	@Test
	public void aFallRunsDownhillWhicheverWayItFaces()
	{
		int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
		int w = WaterMap.CORNERS * WaterMap.PAGES;
		int p2 = 2 * WaterMap.CORNERS;
		for (int k = 0; k < 4; ++k)
		{
			WaterMap m = fallStrip(256, false, k);
			// the fall's tile and the corner on its foot's edge, in the middle of its width
			int tx = k < 2 ? (k == 0 ? 83 : 84) : 81, ty = k < 2 ? 81 : (k == 2 ? 83 : 84);
			assertTrue("tile " + k, m.fall[ty * N + tx]);
			int cx = k < 2 ? 84 : 81, cy = k < 2 ? 81 : 84;
			assertEquals("x " + k, dx[k] * (float) Math.sqrt(5), m.texture[(cy * w + p2 + cx) * 4 + 2], 1e-5f);
			assertEquals("z " + k, dz[k] * (float) Math.sqrt(5), m.texture[(cy * w + p2 + cx) * 4 + 3], 1e-5f);
			// the water on it runs the same way
			assertTrue("flow " + k, m.texture[(cy * w + cx) * 4 + 1] * dx[k] + m.texture[(cy * w + cx) * 4 + 2] * dz[k] > 2f);
		}
	}

	@Test
	public void aClimbingBankIsNoFall()
	{
		// the same slope with land below it
		WaterMap m = fallStrip(256, true, 2);
		for (int y = 80; y < 88; ++y)
		{
			assertFalse("tile " + y, m.fall[y * N + 81]);
		}
		// and a gentle slope between two pools
		m = fallStrip(64, false, 2);
		assertFalse(m.fall[83 * N + 81]);
	}

	/**
	 * A walled pool, 2 tiles by 4 (extended x 80-81, y 80-83) on flat ground, its rim one model standing in the water along
	 * all four of its edges: vertical faces on the edges' lines from below the surface to above it.
	 */
	private static WaterMap walledPool()
	{
		int x0 = 80 * 128, x1 = 82 * 128, z0 = 80 * 128, z1 = 84 * 128;
		// the model's origin: the middle of the pool's sw tile, scene local
		int ox = x0 + 64, oz = z0 + 64;
		float[][] edges = {{x0, z0, x0, z1}, {x1, z0, x1, z1}, {x0, z0, x1, z0}, {x0, z1, x1, z1}};
		float[] vx = new float[16], vy = new float[16], vz = new float[16];
		int[] f1 = new int[8], f2 = new int[8], f3 = new int[8];
		for (int e = 0; e < 4; ++e)
		{
			float[] l = edges[e];
			for (int k = 0; k < 4; ++k)
			{
				vx[e * 4 + k] = (k < 2 ? l[0] : l[2]) - ox;
				vz[e * 4 + k] = (k < 2 ? l[1] : l[3]) - oz;
				vy[e * 4 + k] = (k & 1) == 0 ? -50 : 50;
			}
			f1[e * 2] = e * 4;
			f2[e * 2] = e * 4 + 1;
			f3[e * 2] = e * 4 + 2;
			f1[e * 2 + 1] = e * 4 + 1;
			f2[e * 2 + 1] = e * 4 + 3;
			f3[e * 2 + 1] = e * 4 + 2;
		}
		int[] colors = new int[8];
		Model rim = proxy(Model.class, m ->
		{
			switch (m)
			{
				case "getVerticesX":
					return vx;
				case "getVerticesY":
					return vy;
				case "getVerticesZ":
					return vz;
				case "getFaceIndices1":
					return f1;
				case "getFaceIndices2":
					return f2;
				case "getFaceIndices3":
					return f3;
				case "getFaceColors1":
				case "getFaceColors3":
					return colors;
				case "getVerticesCount":
					return 16;
				case "getFaceCount":
					return 8;
				default:
					return null;
			}
		});
		GroundObject ground = proxy(GroundObject.class, m ->
		{
			switch (m)
			{
				case "getRenderable":
					return rim;
				case "getX":
					return ox - (OFF << 7);
				case "getY":
					return oz - (OFF << 7);
				case "getId":
				case "getZ":
					return 0;
				default:
					return null;
			}
		});
		Tile[][][] tiles = new Tile[4][N][N];
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				boolean water = x >= 80 && x < 82 && y >= 80 && y < 84;
				tiles[0][x][y] = tile(x, y, water ? 1 : -1, x == 80 && y == 80 ? ground : null);
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
		return WaterMap.build(scene, null, null);
	}

	@Test
	public void aRimFoamsAlongAllFourEdgesOfAPool()
	{
		WaterMap m = walledPool();
		int cells = WaterMap.CONTACT_RES, size = WaterMap.CONTACT_SIZE;
		// the cells just inside each edge, halfway along it
		int west = m.contact[82 * cells * size + 80 * cells] & 255;
		int east = m.contact[82 * cells * size + 82 * cells - 1] & 255;
		int south = m.contact[80 * cells * size + 81 * cells] & 255;
		int north = m.contact[(84 * cells - 1) * size + 81 * cells] & 255;
		assertTrue("west " + west, west > 128);
		assertEquals("east as west", west, east);
		assertEquals("north as south", south, north);
		assertEquals("south as west", west, south);
	}

	@Test
	public void aRunOfWaterOneTileWideIsOneTrough()
	{
		// three water tiles in a row along x, land all round: every corner is on the bank
		WaterMap m = flat((x, y) -> y == 90 && x >= 80 && x < 83 ? 1 : -1);
		for (int x = 80; x < 83; ++x)
		{
			assertTrue(m.isShoreTile(x, 90));
		}
		// the sides between two of them sink as deep as the tiles' middles, the same from either side; the sides on the
		// bank run straight
		assertEquals(WaterMap.MIN_DEPTH, m.sideOffset(80, 90, 0, 1));
		assertEquals(WaterMap.MIN_DEPTH, m.sideOffset(81, 90, 0, 3));
		assertEquals(m.centerOffset(81, 90), m.sideOffset(81, 90, 0, 1));
		assertEquals(-1, m.sideOffset(80, 90, 0, 3));
		assertEquals(-1, m.sideOffset(81, 90, 0, 0));
		assertEquals(-1, m.sideOffset(81, 90, 0, 2));
		assertEquals(5, m.shoreTriangles(80, 90, 0));
		assertEquals(6, m.shoreTriangles(81, 90, 0));
		// a side between a shore tile and deeper water runs straight: the deeper tile's bed is one quad
		WaterMap lake = lake(1);
		assertEquals(-1, lake.sideOffset(81, 85, 0, 1));
	}

	@Test
	public void aFallPullsOnlyTheWaterAcrossFromIt()
	{
		int n = WaterMap.CORNERS;
		float[] px = new float[n * n], pz = new float[n * n], level = new float[n * n], strength = new float[n * n];
		// a lip one tile wide, along z = 10 tiles from x = 5 to 6
		java.util.List<float[]> lips = java.util.Collections.singletonList(new float[]{5 * 128f, 10 * 128f, 6 * 128f, 10 * 128f, 0f});
		WaterMap.pullToLines(lips, 2.5f, px, pz, level, strength);
		// its own corners, on it and upstream of it, are pulled; the corners a tile beside it are not
		assertTrue(strength[10 * n + 5] > 0 && strength[10 * n + 6] > 0);
		assertTrue(pz[8 * n + 5] > 0 && pz[8 * n + 6] > 0);
		assertEquals(0f, strength[10 * n + 4], 0f);
		assertEquals(0f, strength[10 * n + 7], 0f);
		assertEquals(0f, strength[9 * n + 7], 0f);
	}

	@Test
	public void aFallsFrameIsAsWideAsTheFall()
	{
		// the strip is three falls side by side, x 80 to 82: corners 80 to 83 framed, none beside them
		WaterMap m = fallStrip(256, false, 2);
		int w = WaterMap.CORNERS * WaterMap.PAGES, p2 = 2 * WaterMap.CORNERS;
		for (int cx = 76; cx <= 87; ++cx)
		{
			float z = m.texture[(84 * w + p2 + cx) * 4 + 3];
			assertEquals("corner " + cx, cx >= 80 && cx <= 83, z != 0f);
		}
	}
}
