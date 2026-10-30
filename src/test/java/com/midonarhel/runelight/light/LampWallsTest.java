package com.midonarhel.runelight.light;

import com.midonarhel.runelight.WallMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LampWallsTest
{
	private static final int SIZE = 24, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;

	/** A wall on the west edge of extended tile (x, y), flagged on one side only, as the shader must not need both. */
	private static WallMap westWall(int x, int y, boolean bothSides)
	{
		int[][][] flags = new int[4][LOCAL][LOCAL];
		flags[0][x - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
		if (bothSides)
		{
			flags[0][x - 1 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
		}
		return WallMap.build(flags, null, SIZE, OFFSET, 1);
	}

	@Test
	public void anEdgeIsInBothTilesTexels()
	{
		WallMap walls = westWall(10, 10, false);
		assertEquals(WallMap.WEST, LampWalls.bits(walls, 0, 10, 10));
		assertEquals(WallMap.EAST, LampWalls.bits(walls, 0, 9, 10));
		assertEquals(0, LampWalls.bits(walls, 1, 10, 10));
		float[] tiles = LampWalls.pack(walls);
		// rows from the south, east along each, six bits a storey, the first storey lowest
		assertEquals(WallMap.WEST, (int) tiles[10 * SIZE + 10]);
		assertEquals(WallMap.EAST, (int) tiles[10 * SIZE + 9]);
		// the four storeys' bits fit a float exactly
		assertEquals(1 << 23, (int) (float) (1 << 23));
	}

	@Test
	public void aLampKnowsWhetherAnyWallIsInItsReach()
	{
		WallMap walls = westWall(10, 10, true);
		int[][] counts = LampWalls.counts(walls, null);
		// local world position of extended tile 13's middle: three tiles east of the wall
		float x = (13 - OFFSET + 0.5f) * 128, z = (10 - OFFSET + 0.5f) * 128;
		assertTrue(LampWalls.anyIn(walls, counts, 0, x, z, 4 * 128));
		assertFalse(LampWalls.anyIn(walls, counts, 0, x, z, 128));
		assertFalse("another storey's walls", LampWalls.anyIn(walls, counts, 1, x, z, 4 * 128));
		// far off to the north
		assertFalse(LampWalls.anyIn(walls, counts, 0, x, z + 8 * 128, 3 * 128));
	}

	@Test
	public void theCpuLineTestStopsAtTheWallAsTheShaderDoes()
	{
		// WallMap.blocked is the CPU twin of lamp.glsl's lampWallBetweenIn (RoomLight's lamp scores use it)
		WallMap walls = westWall(10, 10, true);
		float y = (10 - OFFSET + 0.5f) * 128;
		assertTrue(walls.blocked(0, (9 - OFFSET + 0.5f) * 128, y, (11 - OFFSET + 0.5f) * 128, y));
		assertFalse(walls.blocked(0, (10 - OFFSET + 0.5f) * 128, y, (12 - OFFSET + 0.5f) * 128, y));
		assertFalse("past the wall's end", walls.blocked(0, (9 - OFFSET + 0.5f) * 128, y + 128, (11 - OFFSET + 0.5f) * 128, y + 128));
	}

	@Test
	public void aLadderHoleIsOpenAndAFloorOrATopStoreysRoofIsNot()
	{
		// storey 1 has a floor over tiles 8..14 but for the hole at (11, 11)
		byte[] floors = new byte[SIZE * SIZE];
		for (int x = 8; x <= 14; ++x)
		{
			for (int y = 8; y <= 14; ++y)
			{
				floors[x * SIZE + y] = (byte) (x == 11 && y == 11 ? 1 : 3);
			}
		}
		float[] ceilings = LampWalls.ceilings(SIZE, null, floors);
		assertEquals(1, (int) ceilings[10 * SIZE + 10] & 1);
		assertEquals("the hole", 0, (int) ceilings[11 * SIZE + 11] & 1);
		assertEquals("open ground", 0, (int) ceilings[3 * SIZE + 3] & 1);
		// a ceiling in reach marks the lamp as one to test, with no wall about
		WallMap none = WallMap.build(new int[4][LOCAL][LOCAL], null, SIZE, OFFSET, 1);
		int[][] counts = LampWalls.counts(none, ceilings);
		float x = (10 - OFFSET + 0.5f) * 128;
		assertTrue(LampWalls.anyIn(none, counts, 0, x, x, 128));
		assertTrue("and for the storey above, its floor", LampWalls.anyIn(none, counts, 1, x, x, 128));
		assertFalse(LampWalls.anyIn(none, counts, 0, (3 - OFFSET + 0.5f) * 128, (3 - OFFSET + 0.5f) * 128, 128));
	}

	@Test
	public void aLampsLinesStartOffTheWallsOfItsTileOnItsOwnSide()
	{
		float[] out = new float[2];
		// 2 units east of a west wall: moved out to ORIGIN_OFF_WALL, still east of it
		WallMap west = westWall(10, 10, true);
		float x0 = (10 - OFFSET) * 128, z = (10 - OFFSET + 0.5f) * 128;
		LampWalls.offWalls(west, 0, x0 + 2, z, out);
		assertEquals(x0 + LampWalls.ORIGIN_OFF_WALL, out[0], 1e-3);
		assertEquals(z, out[1], 1e-3);
		// a diagonal wall ("/") through the tile: a lamp 3 units north-west of it stays north-west, 24 off
		int[][][] flags = new int[4][LOCAL][LOCAL];
		flags[0][10 - OFFSET][10 - OFFSET] |= WallMap.LOS_FULL;
		WallMap diagonal = WallMap.build(flags, java.util.List.of(new WallMap.Diagonal(0, 10, 10, WallMap.DIAG_SW_NE)),
			SIZE, OFFSET, 1);
		float c = (10 - OFFSET + 0.5f) * 128;
		float step = 3 * (float) Math.sqrt(0.5);
		LampWalls.offWalls(diagonal, 0, c - step, c + step, out);
		float d = (out[1] - out[0]) * (float) Math.sqrt(0.5);
		assertEquals(LampWalls.ORIGIN_OFF_WALL, d, 1e-2);
		LampWalls.offWalls(diagonal, 0, c + step, c - step, out);
		assertEquals(-LampWalls.ORIGIN_OFF_WALL, (out[1] - out[0]) * (float) Math.sqrt(0.5), 1e-2);
	}
}
