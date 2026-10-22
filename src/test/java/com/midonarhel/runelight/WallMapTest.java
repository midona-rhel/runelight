package com.midonarhel.runelight;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class WallMapTest
{
	private static final int SIZE = 24, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;
	private static final float TILE = 128;

	// local world position of the center of extended tile t
	private static float world(int t)
	{
		return (t - OFFSET + 0.5f) * TILE;
	}

	private static int[][][] noFlags()
	{
		return new int[4][LOCAL][LOCAL];
	}

	// a line-of-sight wall on the west edge of extended tile (x, y), flagged on both sides as the game does
	private static void westWall(int[][][] f, int x, int y)
	{
		f[0][x - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
		f[0][x - 1 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
	}

	private static void southWall(int[][][] f, int x, int y)
	{
		f[0][x - OFFSET][y - OFFSET] |= WallMap.LOS_SOUTH;
		f[0][x - OFFSET][y - 1 - OFFSET] |= WallMap.LOS_NORTH;
	}

	@Test
	public void edgesFromLineOfSightFlags()
	{
		int[][][] f = noFlags();
		westWall(f, 10, 10);
		// a fence: blocks walking, not sight
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		assertTrue(w.edge(0, 10, 10, WallMap.WEST));
		assertTrue(w.edge(0, 9, 10, WallMap.EAST));
		assertFalse(w.edge(0, 12, 10, WallMap.WEST));
		assertFalse(w.edge(0, 10, 10, WallMap.NORTH));
	}

	@Test
	public void straightWallBlocks()
	{
		int[][][] f = noFlags();
		for (int y = 6; y < 14; ++y)
		{
			westWall(f, 10, y);
		}
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		assertTrue(w.blocked(0, world(8), world(10), world(12), world(9)));
		assertFalse(w.blocked(0, world(11), world(10), world(12), world(13)));
		// around the end of the wall
		assertFalse(w.blocked(0, world(8), world(15), world(12), world(15)));
		// other planes have no walls
		assertFalse(w.blocked(1, world(8), world(10), world(12), world(9)));
	}

	@Test
	public void cornerNeedsBothWays()
	{
		// an L of walls meeting at the south-west corner of tile (10, 10): west edge of (10, 10), south edge of (10, 10)
		int[][][] f = noFlags();
		westWall(f, 10, 10);
		southWall(f, 10, 10);
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		// exactly through the corner, from (9, 9) to (10, 10): both ways round it cross a wall
		assertTrue(w.blocked(0, world(9), world(9), world(10), world(10)));
		// a single wall end at a corner lets a ray graze past it
		int[][][] g = noFlags();
		westWall(g, 10, 10);
		WallMap w2 = WallMap.build(g, null, SIZE, OFFSET, 1);
		assertFalse(w2.blocked(0, world(9), world(9), world(10), world(8)));
	}

	@Test
	public void diagonalWalls()
	{
		List<WallMap.Diagonal> d = new ArrayList<>();
		d.add(new WallMap.Diagonal(0, 10, 10, WallMap.DIAG_SW_NE));
		int[][][] f = noFlags();
		f[0][10 - OFFSET][10 - OFFSET] |= WallMap.LOS_FULL;
		WallMap w = WallMap.build(f, d, SIZE, OFFSET, 1);
		// "/" splits the tile into north-west and south-east halves: from the west tile to the south tile crosses it
		float x0 = (10 - OFFSET) * TILE, y0 = (10 - OFFSET) * TILE;
		assertTrue(w.blocked(0, x0 + 32, y0 + 96, x0 + 96, y0 + 32));
		// along it, on one side
		assertFalse(w.blocked(0, x0 + 16, y0 + 48, x0 + 80, y0 + 112));
		// across it from far away
		assertTrue(w.blocked(0, world(7), world(13), world(13), world(7)));
		// "\" the other way
		List<WallMap.Diagonal> d2 = Collections.singletonList(new WallMap.Diagonal(0, 10, 10, WallMap.DIAG_NW_SE));
		WallMap w2 = WallMap.build(f, d2, SIZE, OFFSET, 1);
		assertFalse(w2.blocked(0, x0 + 32, y0 + 96, x0 + 96, y0 + 32));
		assertTrue(w2.blocked(0, x0 + 32, y0 + 32, x0 + 96, y0 + 96));
		// a diagonal fence (sight not blocked) is no wall
		WallMap w3 = WallMap.build(noFlags(), d, SIZE, OFFSET, 1);
		assertFalse(w3.blocked(0, x0 + 32, y0 + 96, x0 + 96, y0 + 32));
	}

	@Test
	public void diagonalFromModel()
	{
		// a strip along x = z
		float[] x = {-64, -60, 60, 64}, z = {-64, -56, 56, 64};
		assertEquals(WallMap.DIAG_SW_NE, WallMap.diagonalOf(x, z, 4, 0, 1));
		assertEquals(WallMap.DIAG_NW_SE, WallMap.diagonalOf(x, new float[]{64, 56, -56, -64}, 4, 0, 0));
		// a straight west wall turned 45 degrees by its model orientation
		float[] wx = {-64, -64, -64, -64}, wz = {-64, -20, 20, 64};
		int a = WallMap.diagonalOf(wx, wz, 4, 256, 0);
		int b = WallMap.diagonalOf(wx, wz, 4, 2048 - 256, 0);
		assertTrue(a != b);
		// nothing to go by: the rotation's parity
		assertEquals(WallMap.DIAG_NW_SE, WallMap.diagonalOf(null, null, 0, 0, 3));
	}

}
