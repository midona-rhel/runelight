package com.midonarhel.runelight;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RoomSplitTest
{
	private static final int SIZE = 24, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;

	// an 8x4 house on plane 0 at extended tiles x 8..15, y 8..11
	private static byte[][][] house()
	{
		byte[][][] settings = new byte[4][SIZE][SIZE];
		for (int x = 8; x < 16; ++x)
		{
			for (int y = 8; y < 12; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		return settings;
	}

	private static void westWall(int[][][] f, int x, int y)
	{
		f[0][x - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
		f[0][x - 1 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
	}

	private static int roomOf(BuildingDetector.Result r, int x, int y)
	{
		for (int i = 0; i < r.rooms.size(); ++i)
		{
			for (int t : r.rooms.get(i).tiles)
			{
				if (t == (x << 16 | y))
				{
					return i;
				}
			}
		}
		return -1;
	}

	@Test
	public void interiorWallSplitsRoom()
	{
		byte[][][] settings = house();
		BuildingDetector.Result coarse = BuildingDetector.detect(settings, null);
		assertEquals(1, coarse.rooms.size());
		int[][][] f = new int[4][LOCAL][LOCAL];
		// an interior wall between x 11 and 12, with a closed door: it blocks sight all the way
		for (int y = 8; y < 12; ++y)
		{
			westWall(f, 12, y);
		}
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		BuildingDetector.Result r = BuildingDetector.split(coarse, settings, w);
		assertEquals(2, r.rooms.size());
		assertNotEquals(roomOf(r, 9, 9), roomOf(r, 14, 9));
		assertEquals(roomOf(r, 8, 8), roomOf(r, 11, 11));
		// each part has its own light
		assertEquals(2, r.anchors.size());
		assertTrue(r.anchors.get(0).room != r.anchors.get(1).room);

		// the door open: one room again
		int[][][] g = new int[4][LOCAL][LOCAL];
		for (int y = 9; y < 12; ++y)
		{
			westWall(g, 12, y);
		}
		BuildingDetector.Result open = BuildingDetector.split(coarse, settings, WallMap.build(g, null, SIZE, OFFSET, 1));
		assertEquals(1, open.rooms.size());
	}

	@Test
	public void unchangedWithoutWalls()
	{
		byte[][][] settings = house();
		BuildingDetector.Result coarse = BuildingDetector.detect(settings, null);
		BuildingDetector.Result r = BuildingDetector.split(coarse, settings,
			WallMap.build(new int[4][LOCAL][LOCAL], null, SIZE, OFFSET, 1));
		assertEquals(1, r.rooms.size());
		assertSame(coarse.rooms.get(0), r.rooms.get(0));
	}

	@Test
	public void diagonalWallSplitsRoom()
	{
		// a 4x4 house cut corner to corner by a "/" diagonal wall through its diagonal tiles
		byte[][][] settings = new byte[4][SIZE][SIZE];
		int[][][] f = new int[4][LOCAL][LOCAL];
		java.util.List<WallMap.Diagonal> d = new java.util.ArrayList<>();
		for (int x = 8; x < 12; ++x)
		{
			for (int y = 8; y < 12; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
			d.add(new WallMap.Diagonal(0, x, x, WallMap.DIAG_SW_NE));
			f[0][x - OFFSET][x - OFFSET] |= WallMap.LOS_FULL;
		}
		BuildingDetector.Result coarse = BuildingDetector.detect(settings, null);
		WallMap w = WallMap.build(f, d, SIZE, OFFSET, 1);
		BuildingDetector.Result r = BuildingDetector.split(coarse, settings, w);
		assertEquals(2, r.rooms.size());
		// north-west of the diagonal against south-east of it
		assertNotEquals(roomOf(r, 8, 11), roomOf(r, 11, 8));
		assertEquals(roomOf(r, 8, 10), roomOf(r, 9, 11));
		assertEquals(roomOf(r, 10, 8), roomOf(r, 11, 9));
		int tiles = 0;
		for (BuildingDetector.Room room : r.rooms)
		{
			tiles += room.tiles.length;
		}
		assertEquals(16, tiles);
	}

	@Test
	public void roomMapFollowsSplitRooms()
	{
		byte[][][] settings = house();
		int[][][] f = new int[4][LOCAL][LOCAL];
		for (int y = 8; y < 12; ++y)
		{
			westWall(f, 12, y);
		}
		BuildingDetector.Result r = BuildingDetector.split(BuildingDetector.detect(settings, null), settings,
			WallMap.build(f, Collections.emptyList(), SIZE, OFFSET, 1));
		int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
		RoomMap m = RoomMap.build(r, settings, heights, OFFSET);
		assertNotEquals(m.room(0, 9, 9), m.room(0, 14, 9));
		assertTrue(m.room(0, 9, 9) > 0 && m.room(0, 14, 9) > 0);
		// a light in the west room doesn't reach the east one
		float code = m.lightCode((9 - OFFSET + 0.5f) * 128, -200, (9 - OFFSET + 0.5f) * 128, 0);
		assertEquals(0, RoomMap.share(code, m.room(0, 14, 9), 0, 0.5f), 0);
		assertEquals(1, RoomMap.share(code, m.room(0, 10, 10), 0, 0.5f), 0);
		assertTrue(m.covered((9 - OFFSET + 0.5f) * 128, (9 - OFFSET + 0.5f) * 128, 0));
		assertTrue(!m.covered((5 - OFFSET + 0.5f) * 128, (5 - OFFSET + 0.5f) * 128, 0));
	}
}
