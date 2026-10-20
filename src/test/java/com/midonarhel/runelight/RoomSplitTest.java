package com.midonarhel.runelight;

import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class RoomSplitTest
{
	// CollisionDataFlag: blocks walking toward a side
	private static final int BLOCK_EAST = 0x8, BLOCK_WEST = 0x80;
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

	// local world position of the center of extended tile t
	private static float world(int t)
	{
		return (t - OFFSET + 0.5f) * 128;
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
	public void lowWallsFencesAndGatesDoNotSplit()
	{
		byte[][][] settings = house();
		BuildingDetector.Result coarse = BuildingDetector.detect(settings, null);
		int[][][] f = new int[4][LOCAL][LOCAL];
		// a fence, closed gate or low wall: blocks walking, not sight
		for (int y = 8; y < 12; ++y)
		{
			f[0][12 - OFFSET][y - OFFSET] |= BLOCK_WEST;
			f[0][11 - OFFSET][y - OFFSET] |= BLOCK_EAST;
		}
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		assertFalse(w.edge(0, 12, 9, WallMap.WEST));
		assertFalse(w.edge(0, 11, 9, WallMap.EAST));
		BuildingDetector.Result r = BuildingDetector.split(coarse, settings, w);
		assertEquals(1, r.rooms.size());
		assertEquals(coarse.rooms.get(0).tiles.length, r.rooms.get(0).tiles.length);
		// light passes it too
		assertFalse(w.blocked(0, world(10), world(9), world(14), world(9)));
		// opening and closing it changes nothing about the walls
		f[0][12 - OFFSET][9 - OFFSET] = 0;
		f[0][11 - OFFSET][9 - OFFSET] = 0;
		assertTrue(w.sameWalls(WallMap.build(f, null, SIZE, OFFSET, 1)));
		// a wall of the same line does split
		for (int y = 8; y < 12; ++y)
		{
			westWall(f, 12, y);
		}
		assertEquals(2, BuildingDetector.split(coarse, settings, WallMap.build(f, null, SIZE, OFFSET, 1)).rooms.size());
	}

	// the 8x4 house walled all round (outer walls: sight and walking), its roof flags running one row past the north
	// wall at y 12
	private static int[][][] walledHouse()
	{
		int[][][] f = new int[4][LOCAL][LOCAL];
		for (int x = 8; x < 16; ++x)
		{
			f[0][x - OFFSET][8 - OFFSET] |= WallMap.LOS_SOUTH;
			f[0][x - OFFSET][7 - OFFSET] |= WallMap.LOS_NORTH;
			f[0][x - OFFSET][11 - OFFSET] |= WallMap.LOS_NORTH;
			f[0][x - OFFSET][12 - OFFSET] |= WallMap.LOS_SOUTH;
		}
		for (int y = 8; y < 12; ++y)
		{
			f[0][15 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
			f[0][16 - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
			f[0][8 - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
			f[0][7 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
		}
		return f;
	}

	@Test
	public void roofFlagsPastTheOuterWallAreNoRoom()
	{
		byte[][][] settings = house();
		for (int x = 8; x < 16; ++x)
		{
			settings[0][x][12] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
		}
		BuildingDetector.Result coarse = BuildingDetector.detect(settings, null);
		assertEquals(1, coarse.rooms.size());
		assertEquals(40, coarse.rooms.get(0).tiles.length);
		BuildingDetector.Result r = BuildingDetector.split(coarse, settings,
			WallMap.build(walledHouse(), null, SIZE, OFFSET, 1));
		assertEquals(1, r.rooms.size());
		assertEquals(32, r.rooms.get(0).tiles.length);
		assertEquals(0, roomOf(r, 9, 9));
		assertEquals(-1, roomOf(r, 9, 12));
		// every light is inside the walls
		for (BuildingDetector.Anchor a : r.anchors)
		{
			assertTrue(a.tileY < 12);
		}
		// and the same with an interior wall: two halves, no strip
		int[][][] f = walledHouse();
		for (int y = 8; y < 12; ++y)
		{
			westWall(f, 12, y);
		}
		BuildingDetector.Result two = BuildingDetector.split(coarse, settings, WallMap.build(f, null, SIZE, OFFSET, 1));
		assertEquals(2, two.rooms.size());
		assertEquals(-1, roomOf(two, 14, 12));
		assertEquals(16, two.rooms.get(0).tiles.length);
		assertEquals(16, two.rooms.get(1).tiles.length);
	}

	@Test
	public void wallTorchLightsItsOwnRoomOnly()
	{
		// a house with an interior wall between x 11 and 12; a torch hangs on it on the west side, one on the east side
		byte[][][] settings = house();
		int[][][] f = walledHouse();
		for (int y = 8; y < 12; ++y)
		{
			westWall(f, 12, y);
		}
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		BuildingDetector.Result r = BuildingDetector.split(BuildingDetector.detect(settings, null), settings, w);
		int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
		for (int l = 0; l < 4; ++l)
		{
			for (int[] row : heights[l])
			{
				java.util.Arrays.fill(row, -240 * l);
			}
		}
		RoomMap m = RoomMap.build(r, settings, heights, OFFSET);
		m.setWalls(w);
		int west = m.room(0, 10, 9), east = m.room(0, 13, 9);
		assertTrue(west > 0 && east > 0 && west != east);
		float edge = (12 - OFFSET) * 128f, z = (9 - OFFSET + 0.5f) * 128;
		for (boolean onWest : new boolean[]{true, false})
		{
			float x = onWest ? edge - 6 : edge + 6;
			// the room test: only its own room
			float code = m.lightCode(x, -200, z, 0);
			assertTrue(RoomMap.indoor(code));
			int[] own = m.roomAt(onWest ? edge - 64 : edge + 64, -40, z), other = m.roomAt(onWest ? edge + 64 : edge - 64, -40, z);
			assertEquals(1, RoomMap.share(code, own[0], own[1], 0.55f), 0);
			assertEquals(0, RoomMap.share(code, other[0], other[1], 0.55f), 0);
			// the cast: the other side of the wall is dark, the near side is lit right up to the wall
			LightOcclusion.Entry e = new LightOcclusion.Entry(0, 0, 0, 0, x, z, 600);
			LightOcclusion.castEntry(e, w);
			LightOcclusion.Atlas a = LightOcclusion.pack(java.util.Collections.singletonList(e), 0);
			assertEquals(1, LightOcclusion.lookup(a, e, cellOf(e, onWest ? edge - 16 : edge + 16), cellOfZ(e, z)), 1e-6);
			assertEquals(0, LightOcclusion.lookup(a, e, cellOf(e, onWest ? edge + 16 : edge - 16), cellOfZ(e, z)), 1e-6);
		}
	}

	private static int cellOf(LightOcclusion.Entry e, float x)
	{
		return (int) Math.floor((x - e.originX) / e.cell);
	}

	private static int cellOfZ(LightOcclusion.Entry e, float z)
	{
		return (int) Math.floor((z - e.originZ) / e.cell);
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
	public void diagonalTilesKeepBothHalvesRooms()
	{
		// a 4x4 house with its south-east corner cut off by a "/" diagonal wall on tile (11, 8): the half of that tile
		// past the wall is outdoors; and a "/" diagonal wall through the rest, as in diagonalWallSplitsRoom
		byte[][][] settings = new byte[4][SIZE][SIZE];
		int[][][] f = new int[4][LOCAL][LOCAL];
		java.util.List<WallMap.Diagonal> d = new java.util.ArrayList<>();
		for (int x = 8; x < 12; ++x)
		{
			for (int y = 8; y < 12; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		for (int x = 8; x < 11; ++x)
		{
			d.add(new WallMap.Diagonal(0, x, x + 1, WallMap.DIAG_SW_NE));
			f[0][x - OFFSET][x + 1 - OFFSET] |= WallMap.LOS_FULL;
		}
		d.add(new WallMap.Diagonal(0, 11, 8, WallMap.DIAG_SW_NE));
		f[0][11 - OFFSET][8 - OFFSET] |= WallMap.LOS_FULL;
		// outer walls, so the small room is no roof flags spilling past them
		for (int k = 8; k < 12; ++k)
		{
			f[0][8 - OFFSET][k - OFFSET] |= WallMap.LOS_WEST;
			f[0][k - OFFSET][11 - OFFSET] |= WallMap.LOS_NORTH;
		}
		WallMap w = WallMap.build(f, d, SIZE, OFFSET, 1);
		BuildingDetector.Result r = BuildingDetector.split(BuildingDetector.detect(settings, null), settings, w);
		RoomMap m = RoomMap.build(r, settings, new int[4][SIZE + 1][SIZE + 1], OFFSET);
		m.setWalls(w);
		// tile (9, 10) on the "/" wall: north-west of it the small room, south-east the big one
		// (world y -10: just above the floor, on plane 0; the test's floors are all at 0)
		int nw = m.roomAt((9 - OFFSET + 0.25f) * 128, -10, (10 - OFFSET + 0.75f) * 128)[0];
		int se = m.roomAt((9 - OFFSET + 0.75f) * 128, -10, (10 - OFFSET + 0.25f) * 128)[0];
		assertTrue(nw > 0 && se > 0);
		assertNotEquals(nw, se);
		assertEquals(nw, m.roomAt((8 - OFFSET + 0.5f) * 128, -10, (11 - OFFSET + 0.5f) * 128)[0]);
		assertEquals(se, m.roomAt((10 - OFFSET + 0.5f) * 128, -10, (9 - OFFSET + 0.5f) * 128)[0]);
		// the cut corner: inside the wall the big room, past it outdoors
		assertEquals(se, m.roomAt((11 - OFFSET + 0.2f) * 128, -10, (8 - OFFSET + 0.8f) * 128)[0]);
		assertEquals(0, m.roomAt((11 - OFFSET + 0.8f) * 128, -10, (8 - OFFSET + 0.2f) * 128)[0]);
		// and the texels say so: "/" with the room on the north-west half (0), outdoors across
		java.nio.FloatBuffer b = java.nio.FloatBuffer.allocate(m.texelCount() * 4);
		m.texels(b);
		int t = (RoomMap.HEADER_TEXELS + (11 * SIZE + 8) * RoomMap.TEXELS_PER_TILE + 2) * 4;
		assertEquals(1, b.get(t), 0);
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
