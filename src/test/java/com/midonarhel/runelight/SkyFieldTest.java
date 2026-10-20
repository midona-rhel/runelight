package com.midonarhel.runelight;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SkyFieldTest
{
	private static final int SIZE = 40;
	private static final float FALL = 0.7f;

	private static int[][][] noFlags()
	{
		return new int[WallMap.PLANES][SIZE][SIZE];
	}

	private static boolean[] cover(int x0, int y0, int x1, int y1)
	{
		boolean[] c = new boolean[SIZE * SIZE];
		for (int x = x0; x <= x1; ++x)
		{
			for (int y = y0; y <= y1; ++y)
			{
				c[x * SIZE + y] = true;
			}
		}
		return c;
	}

	private static float at(float[] light, int x, int y)
	{
		// the lighter half
		int i = x * SIZE + y;
		return Math.max(light[2 * i], light[2 * i + 1]);
	}

	/** Walls along the edge of the rectangle of tiles. */
	private static void box(int[][][] f, int x0, int y0, int x1, int y1)
	{
		for (int x = x0; x <= x1; ++x)
		{
			f[0][x][y0] |= WallMap.LOS_SOUTH;
			f[0][x][y1] |= WallMap.LOS_NORTH;
		}
		for (int y = y0; y <= y1; ++y)
		{
			f[0][x0][y] |= WallMap.LOS_WEST;
			f[0][x1][y] |= WallMap.LOS_EAST;
		}
	}

	@Test
	public void openSkyIsFullLight()
	{
		WallMap w = WallMap.build(noFlags(), null, SIZE, 0, 1);
		float[] l = SkyField.propagate(w, new boolean[SIZE * SIZE], 0, FALL);
		for (float v : l)
		{
			assertEquals(1, v, 0);
		}
	}

	@Test
	public void gateTunnelFadesInFromBothEnds()
	{
		// a tunnel of tiles x 10..17 along y 10..12, walled on its long sides, open at both ends
		int[][][] f = noFlags();
		for (int x = 10; x <= 17; ++x)
		{
			f[0][x][10] |= WallMap.LOS_SOUTH;
			f[0][x][12] |= WallMap.LOS_NORTH;
		}
		WallMap w = WallMap.build(f, null, SIZE, 0, 1);
		float[] l = SkyField.propagate(w, cover(10, 10, 17, 12), 0, FALL);
		// one step down at the ends, then a step per tile in
		assertEquals(FALL, at(l, 10, 11), 1e-3);
		assertEquals(FALL, at(l, 17, 11), 1e-3);
		assertEquals(FALL * FALL, at(l, 11, 11), 1e-3);
		assertEquals(FALL * FALL, at(l, 16, 11), 1e-3);
		// symmetric, darkest in the middle, and nowhere black
		for (int x = 10; x <= 17; ++x)
		{
			assertEquals(at(l, x, 11), at(l, 27 - x, 11), 1e-4);
			assertTrue(at(l, x, 11) > 0.1f);
			if (x > 10 && x <= 13)
			{
				assertTrue(at(l, x, 11) < at(l, x - 1, 11));
			}
		}
		// the open sky around it is unchanged
		assertEquals(1, at(l, 9, 11), 0);
		assertEquals(1, at(l, 20, 11), 0);
	}

	@Test
	public void doorOpensAndClosesARoom()
	{
		// a 6 x 6 room (tiles 10..15) walled all round
		int[][][] closed = noFlags();
		box(closed, 10, 10, 15, 15);
		boolean[] roof = cover(10, 10, 15, 15);
		WallMap shut = WallMap.build(closed, null, SIZE, 0, 1);
		float[] dark = SkyField.propagate(shut, roof, 0, FALL);
		for (int x = 10; x <= 15; ++x)
		{
			for (int y = 10; y <= 15; ++y)
			{
				assertEquals(0, at(dark, x, y), 0);
			}
		}
		// its door on the south wall at x 12 opens
		int[][][] open = noFlags();
		box(open, 10, 10, 15, 15);
		open[0][12][10] &= ~WallMap.LOS_SOUTH;
		WallMap ajar = WallMap.build(open, null, SIZE, 0, 1);
		float[] lit = SkyField.propagate(ajar, roof, 0, FALL);
		assertEquals(FALL, at(lit, 12, 10), 1e-3);
		assertEquals(FALL * FALL, at(lit, 12, 11), 1e-3);
		// the pool spreads sideways, dimmer, and runs out toward the back
		assertTrue(at(lit, 14, 10) < at(lit, 13, 10));
		assertTrue(at(lit, 13, 10) < at(lit, 12, 10));
		assertTrue(at(lit, 12, 14) < at(lit, 12, 12));
		assertTrue(at(lit, 12, 15) > 0);
		// the wall keeps the room's light off the outside, which stays open sky
		assertEquals(1, at(lit, 12, 9), 0);
	}

	@Test
	public void solidObjectsDoNotPassLightOn()
	{
		// the doorway's own tile is blocked solid: light gets onto it but not past
		int[][][] f = noFlags();
		box(f, 10, 10, 15, 15);
		f[0][12][10] &= ~WallMap.LOS_SOUTH;
		f[0][12][10] |= WallMap.LOS_FULL;
		WallMap w = WallMap.build(f, null, SIZE, 0, 1);
		assertTrue(w.solid(0, 12, 10));
		float[] l = SkyField.propagate(w, cover(10, 10, 15, 15), 0, FALL);
		assertEquals(FALL, at(l, 12, 10), 1e-3);
		assertEquals(0, at(l, 12, 11), 0);
		assertEquals(0, at(l, 13, 10), 0);
	}

	@Test
	public void diagonalWallsSeparateTheHalves()
	{
		// the tile (12, 12) is cut from its south-west corner to its north-east one; light entering from the west
		// stays in the north-west half, so it leaves by the north edge and not the east one
		int[][][] f = noFlags();
		box(f, 10, 10, 14, 14);
		f[0][10][12] &= ~WallMap.LOS_WEST;
		f[0][12][12] |= WallMap.LOS_FULL;
		WallMap w = WallMap.build(f, Collections.singletonList(new WallMap.Diagonal(0, 12, 12, WallMap.DIAG_SW_NE)), SIZE,
			0, 1);
		assertTrue(!w.solid(0, 12, 12));
		float[] l = SkyField.propagate(w, cover(10, 10, 14, 14), 0, FALL);
		assertTrue(at(l, 11, 12) > 0);
		assertTrue(at(l, 12, 12) > 0);
		assertTrue(at(l, 12, 13) > 0);
		// without the diagonal wall the tile east of it is lit straight through
		f[0][12][12] &= ~WallMap.LOS_FULL;
		float[] plain = SkyField.propagate(WallMap.build(f, null, SIZE, 0, 1), cover(10, 10, 14, 14), 0, FALL);
		assertTrue(at(l, 13, 12) < at(plain, 13, 12) - 0.05f);
	}

	@Test
	public void packedTexelsEaseOverOpenEdgesOnly()
	{
		int[][][] f = noFlags();
		for (int x = 10; x <= 17; ++x)
		{
			f[0][x][10] |= WallMap.LOS_SOUTH;
			f[0][x][12] |= WallMap.LOS_NORTH;
		}
		WallMap w = WallMap.build(f, null, SIZE, 0, 1);
		boolean[] c = cover(10, 10, 17, 12);
		float[] l = SkyField.propagate(w, c, 0, FALL);
		byte[] t = SkyField.pack(w, 0, l, c);
		int width = SIZE * SkyField.TEXELS_PER_TILE;
		// the two texels of tile (10, 11) in its row: the west one leans to the open sky, the east one to tile 11
		int west = (10 * 2 + (11 * 2) * width) * 2, east = west + 2;
		assertTrue((t[west] & 0xFF) > (t[east] & 0xFF));
		// the cover eases toward the open tile beside it
		assertEquals(191, t[west + 1] & 0xFF, 1);
		// out in the open: light everywhere, and no cover
		int open = (30 * 2 + 30 * 2 * width) * 2;
		assertEquals(255, t[open] & 0xFF);
		assertEquals(0, t[open + 1] & 0xFF);
		// the wall along the tunnel keeps the open sky's light from easing across it: the north texel of tile
		// (12, 12), against the wall, only takes the tunnel's own light
		int inside = (12 * 2 + (12 * 2 + 1) * width) * 2;
		assertEquals(at(l, 12, 12), (t[inside] & 0xFF) / 255f, 0.1f);
	}

	@Test
	public void coveredFromRoofsAndFloorsAbove()
	{
		byte[][][] settings = new byte[4][SIZE][SIZE];
		int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
		BuildingDetector.Result r = new BuildingDetector.Result();
		settings[0][5][5] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
		RoomMap m = RoomMap.build(r, settings, heights, 0);
		byte[] floors = new byte[SIZE * SIZE];
		// a floor on plane 1 over tile (7, 7)
		floors[7 * SIZE + 7] = 2;
		boolean[] c = SkyField.covered(m, floors, 0);
		assertTrue(c[5 * SIZE + 5]);
		assertTrue(c[7 * SIZE + 7]);
		assertTrue(!c[6 * SIZE + 6]);
		// plane 1's own floor doesn't cover plane 1
		assertTrue(!SkyField.covered(m, floors, 1)[7 * SIZE + 7]);
	}

	@Test
	public void wholeSceneIsCheap()
	{
		// a 184-tile scene with a big roofed block of walled strips, timed after a warm-up
		int size = 184;
		int[][][] f = new int[WallMap.PLANES][size][size];
		boolean[] cov = new boolean[size * size];
		for (int x = 40; x < 140; ++x)
		{
			for (int y = 40; y < 140; ++y)
			{
				cov[x * size + y] = true;
				if (x % 8 == 0 && y % 20 != 0)
				{
					f[0][x][y] |= WallMap.LOS_WEST;
				}
			}
		}
		WallMap w = WallMap.build(f, null, size, 0, 1);
		for (int i = 0; i < 20; ++i)
		{
			SkyField.pack(w, 0, SkyField.propagate(w, cov, 0, SkyField.falloffOf(3)), cov);
		}
		long t = System.nanoTime();
		for (int i = 0; i < 10; ++i)
		{
			SkyField.pack(w, 0, SkyField.propagate(w, cov, 0, SkyField.falloffOf(3)), cov);
		}
		double ms = (System.nanoTime() - t) / 10e6;
		System.out.println("sky field of a 184 tile scene: " + ms + " ms");
		assertTrue(ms < 50);
	}
}
