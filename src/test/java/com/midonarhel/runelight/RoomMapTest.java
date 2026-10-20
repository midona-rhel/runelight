package com.midonarhel.runelight;

import java.nio.FloatBuffer;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoomMapTest
{
	private static final int SIZE = 20, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;
	private static final float TILE = 128;

	// a 4x4 house on plane 0 at extended tiles 8..11, flat ground at y 0, storeys 240 apart
	private static RoomMap house(int[][][] flags)
	{
		byte[][][] settings = new byte[4][SIZE][SIZE];
		for (int x = 8; x < 12; ++x)
		{
			for (int y = 8; y < 12; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
		for (int l = 0; l < 4; ++l)
		{
			for (int[] row : heights[l])
			{
				java.util.Arrays.fill(row, -240 * l);
			}
		}
		BuildingDetector.Result r = BuildingDetector.detect(settings, null);
		RoomMap m = RoomMap.build(r, settings, heights, OFFSET);
		m.setWalls(WallMap.build(flags, null, SIZE, OFFSET, 1));
		return m;
	}

	// local world position of the center of extended tile (x, y)
	private static float world(int t)
	{
		return (t - OFFSET + 0.5f) * TILE;
	}

	/** Walls all around the house except a doorway on its west side at y = 9. */
	private static int[][][] walls()
	{
		int[][][] f = new int[4][LOCAL][LOCAL];
		for (int x = 8; x < 12; ++x)
		{
			f[0][x - OFFSET][8 - OFFSET] |= WallMap.LOS_SOUTH;
			f[0][x - OFFSET][7 - OFFSET] |= WallMap.LOS_NORTH;
			f[0][x - OFFSET][11 - OFFSET] |= WallMap.LOS_NORTH;
			f[0][x - OFFSET][12 - OFFSET] |= WallMap.LOS_SOUTH;
		}
		for (int y = 8; y < 12; ++y)
		{
			f[0][11 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
			f[0][12 - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
			if (y != 9)
			{
				f[0][8 - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
				f[0][7 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
			}
		}
		return f;
	}

	@Test
	public void porchesAreFoundOnlyAcrossOpeningsWhateverTheWalls()
	{
		java.util.Random rnd = new java.util.Random(7);
		int[][][] f = new int[4][LOCAL][LOCAL];
		for (int pass = 0; pass < 20; ++pass)
		{
			for (int[][] plane : f)
			{
				for (int[] row : plane)
				{
					for (int y = 0; y < row.length; ++y)
					{
						row[y] = rnd.nextInt(4) == 0 ? WallMap.LOS_NORTH | WallMap.LOS_WEST : 0;
					}
				}
			}
			RoomMap m = house(f);
			WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
			for (int plane = 0; plane < RoomMap.PLANES; ++plane)
			{
				for (int x = 0; x < SIZE; ++x)
				{
					for (int y = 0; y < SIZE; ++y)
					{
						short p = m.porch[m.index(plane, x, y)];
						if (p != 0)
						{
							assertTrue(porchPossible(m, w, plane, x, y, p));
						}
						else
						{
							assertFalse(porchPossible(m, w, plane, x, y, (short) -1));
						}
					}
				}
			}
		}
	}

	// whether the tile can have the porch p (any when -1): a neighbor of another room with no wall between, or one more
	// tile on in line with it in the same room
	private static boolean porchPossible(RoomMap m, WallMap w, int plane, int x, int y, short p)
	{
		int[][] steps = {{1, 0, WallMap.EAST}, {-1, 0, WallMap.WEST}, {0, 1, WallMap.NORTH}, {0, -1, WallMap.SOUTH}};
		for (int[] s : steps)
		{
			for (int k = 1; k <= RoomMap.PORCH_DEPTH; ++k)
			{
				// k tiles back from here is the tile the light comes from
				int fx = x - s[0] * k, fy = y - s[1] * k;
				if (fx < 0 || fy < 0 || fx >= SIZE || fy >= SIZE)
				{
					break;
				}
				short from = m.rooms[m.index(plane, fx, fy)];
				short id = from != 0 ? from : (short) RoomMap.OUTSIDE;
				// the seam is between the source and the next tile; tiles after it stay in one room with no wall
				int sx = fx + s[0], sy = fy + s[1];
				boolean open = m.rooms[m.index(plane, sx, sy)] != from && !w.edge(plane, fx, fy, s[2]);
				for (int j = 1; open && j < k; ++j)
				{
					int ax = sx + s[0] * (j - 1), ay = sy + s[1] * (j - 1);
					open = m.rooms[m.index(plane, ax + s[0], ay + s[1])] == m.rooms[m.index(plane, sx, sy)]
						&& !w.edge(plane, ax, ay, s[2]);
				}
				if (open && (p == -1 || p == id))
				{
					return true;
				}
			}
		}
		return false;
	}

	@Test
	public void lightsInsideAreIndoorAndStayInside()
	{
		RoomMap m = house(walls());
		float inside = m.lightCode(world(9), -200, world(10), 0);
		float outside = m.lightCode(world(4), -200, world(4), 0);
		assertTrue(RoomMap.indoor(inside));
		assertFalse(RoomMap.indoor(outside));

		int[] floor = m.roomAt(world(10), -40, world(10));
		int[] yard = m.roomAt(world(14), -40, world(14));
		assertEquals(1, floor[0]);
		assertEquals(0, yard[0]);
		assertEquals(1, RoomMap.share(inside, floor[0], floor[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(inside, yard[0], yard[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(outside, floor[0], floor[1], 0.5f), 0);
		assertEquals(1, RoomMap.share(outside, yard[0], yard[1], 0.5f), 0);
	}

	@Test
	public void doorwaySpillsOntoItsPorchOnly()
	{
		RoomMap m = house(walls());
		float inside = m.lightCode(world(9), -200, world(9), 0);
		int[] porch = m.roomAt(world(7), -40, world(9));
		int[] beyond = m.roomAt(world(6), -40, world(9));
		int[] far = m.roomAt(world(5), -40, world(9));
		int[] besideWall = m.roomAt(world(7), -40, world(10));
		assertEquals(0, porch[0]);
		assertEquals(1 + RoomMap.SOFT, porch[1]);
		assertEquals(1 + RoomMap.SOFT, beyond[1]);
		assertEquals(0, far[1]);
		assertEquals(0, besideWall[1]);
		assertEquals(0.5f, RoomMap.share(inside, porch[0], porch[1], 0.5f), 0);
		assertEquals(0.5f, RoomMap.share(inside, beyond[0], beyond[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(inside, far[0], far[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(inside, besideWall[0], besideWall[1], 0.5f), 0);

		// the door closes
		int[][][] closed = walls();
		closed[0][8 - OFFSET][9 - OFFSET] |= WallMap.LOS_WEST;
		closed[0][7 - OFFSET][9 - OFFSET] |= WallMap.LOS_EAST;
		int before = m.getVersion();
		assertTrue(m.setWalls(WallMap.build(closed, null, SIZE, OFFSET, 1)));
		assertTrue(m.getVersion() != before);
		assertEquals(0, m.roomAt(world(7), -40, world(9))[1]);
		assertEquals(0, m.roomAt(world(6), -40, world(9))[1]);
		assertFalse(m.setWalls(WallMap.build(closed, null, SIZE, OFFSET, 1)));
		// and opens again
		assertTrue(m.setWalls(WallMap.build(walls(), null, SIZE, OFFSET, 1)));
		assertEquals(1 + RoomMap.SOFT, m.roomAt(world(7), -40, world(9))[1]);
	}

	@Test
	public void openingsBetweenRoomsSpillBothWays()
	{
		// two rooms side by side (two roofs: x 8..11 and 12..15, y 8..11), a doorway between them at y 9
		byte[][][] settings = new byte[4][SIZE][SIZE];
		for (int x = 8; x < 16; ++x)
		{
			for (int y = 8; y < 12; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		int[][][] rids = new int[4][SIZE][SIZE];
		for (int x = 8; x < 16; ++x)
		{
			for (int y = 8; y < 12; ++y)
			{
				rids[0][x][y] = x < 12 ? 1 : 2;
			}
		}
		int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
		for (int l = 0; l < 4; ++l)
		{
			for (int[] row : heights[l])
			{
				java.util.Arrays.fill(row, -240 * l);
			}
		}
		BuildingDetector.Result r = BuildingDetector.detect(settings, rids);
		assertEquals(2, r.rooms.size());
		RoomMap m = RoomMap.build(r, settings, heights, OFFSET);
		int west = m.room(0, 9, 9), east = m.room(0, 14, 9);
		assertTrue(west > 0 && east > 0 && west != east);
		int[][][] f = new int[4][LOCAL][LOCAL];
		for (int y = 8; y < 12; ++y)
		{
			if (y != 9)
			{
				f[0][11 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
				f[0][12 - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
			}
		}
		m.setWalls(WallMap.build(f, null, SIZE, OFFSET, 1));
		float inWest = m.lightCode(world(9), -200, world(9), 0), inEast = m.lightCode(world(14), -200, world(9), 0);
		// through the doorway, a tile or two into the next room, both ways; not past the wall
		int[] into = m.roomAt(world(12), -40, world(9));
		assertEquals(east, into[0]);
		assertEquals(0.5f, RoomMap.share(inWest, into[0], into[1], 0.5f), 0);
		assertEquals(0.5f, RoomMap.share(inWest, east, m.roomAt(world(13), -40, world(9))[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(inWest, east, m.roomAt(world(14), -40, world(9))[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(inWest, east, m.roomAt(world(12), -40, world(10))[1], 0.5f), 0);
		assertEquals(0.5f, RoomMap.share(inEast, west, m.roomAt(world(11), -40, world(9))[1], 0.5f), 0);
		assertEquals(1, RoomMap.share(inEast, east, m.roomAt(world(13), -40, world(9))[1], 0.5f), 0);
	}

	@Test
	public void outdoorLightsSpillIntoTheRoomThroughAnOpenDoor()
	{
		RoomMap m = house(walls());
		float lamp = m.lightCode(world(4), -200, world(9), 0);
		// the tile inside the open doorway and the next one, not the wall's other tiles
		int[] inside = m.roomAt(world(8), -40, world(9));
		assertEquals(1, inside[0]);
		assertEquals(0.5f, RoomMap.share(lamp, inside[0], inside[1], 0.5f), 0);
		assertEquals(0.5f, RoomMap.share(lamp, 1, m.roomAt(world(9), -40, world(9))[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(lamp, 1, m.roomAt(world(10), -40, world(9))[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(lamp, 1, m.roomAt(world(8), -40, world(10))[1], 0.5f), 0);
	}

	@Test
	public void wallLightsShineOnTheirOwnSideOnly()
	{
		// the house's walls all round except the west doorway at y 9
		RoomMap m = house(walls());
		// a torch on the inside of the east wall: on the room's edge tile, 10 units from the edge
		float x = (12 - OFFSET) * TILE - 10;
		float inside = m.lightCode(x, -200, world(10), 0);
		assertEquals(1, RoomMap.share(inside, 1, 0, 0.5f), 0);
		assertEquals(0, RoomMap.share(inside, 0, 0, 0.5f), 0);
		assertTrue(RoomMap.indoor(inside));
		// one on the outside of it, on the outdoor tile: it stays out
		float outside = m.lightCode(x + 20, -200, world(10), 0);
		assertEquals(0, RoomMap.share(outside, 1, 0, 0.5f), 0);
		assertEquals(1, RoomMap.share(outside, 0, 0, 0.5f), 0);
		// by the doorway, with no wall in it, a light still lights both sides
		float doorway = (8 - OFFSET) * TILE + 10;
		float lamp = m.lightCode(doorway, -200, world(9), 0);
		assertEquals(1, RoomMap.share(lamp, 1, 0, 0.5f), 0);
		assertEquals(1, RoomMap.share(lamp, 0, 0, 0.5f), 0);
	}

	@Test
	public void storeysAndRoofs()
	{
		RoomMap m = house(walls());
		// the top of an inner wall is still in the room, a roof above the next floor is not
		assertEquals(1, m.roomAt(world(10), -230, world(10))[0]);
		assertEquals(0, m.roomAt(world(10), -300, world(10))[0]);
		// outside the map: unknown
		assertEquals(-1, m.roomAt(-50 * TILE, 0, 0)[0]);
		assertEquals(1, RoomMap.share(RoomMap.code(1, 1), -1, 0, 0.5f), 0);
		// unknown plane: reaches everything
		assertEquals(1, RoomMap.share(m.lightCode(world(4), 0, world(4), -1), 1, 0, 0.5f), 0);
	}

	@Test
	public void codesSurviveFloats()
	{
		float c = RoomMap.code(RoomMap.MAX_ROOM, RoomMap.ANY - 1);
		assertEquals(1, RoomMap.share(c, RoomMap.MAX_ROOM, 0, 0.5f), 0);
		assertEquals(1, RoomMap.share(c, RoomMap.ANY - 1, 0, 0.5f), 0);
		assertEquals(0, RoomMap.share(c, 7, 0, 0.5f), 0);
		assertEquals(1, RoomMap.share(RoomMap.anyCode(), 7, 0, 0.5f), 0);
	}

	@Test
	public void texelLayout()
	{
		RoomMap m = house(walls());
		FloatBuffer b = FloatBuffer.allocate(m.texelCount() * 4);
		m.texels(b);
		assertEquals(0, b.remaining());
		assertEquals(1, b.get(0), 0);
		assertEquals(SIZE, b.get(1), 0);
		assertEquals(OFFSET, b.get(2), 0);
		int t = (RoomMap.HEADER_TEXELS + (9 * SIZE + 10) * RoomMap.TEXELS_PER_TILE) * 4;
		assertEquals(1, b.get(t), 0);
		assertEquals(-240, b.get(t + 5), 0);
		// the porch tile: room 0, porch 1, negative as an opening's
		int p = (RoomMap.HEADER_TEXELS + (7 * SIZE + 9) * RoomMap.TEXELS_PER_TILE) * 4;
		assertEquals(-4096, b.get(p), 0);
		assertTrue(LightClusters.ROOM_BASE + m.texelCount() < 65536);
	}

	// the 4x4 house with a second storey over it (rooms 1 below, 2 above); the upper floor has a hole at extended tile
	// (9, 9) when hole is set, and every other tile of both planes has a floor, except open ground at (14, 14) that
	// has none on the upper plane
	private static RoomMap twoStoreys(boolean hole)
	{
		return twoStoreys(hole, false);
	}

	// unfloored: the upper plane has no floor anywhere, the rooms above are flagged under a roof over open ground
	private static RoomMap twoStoreys(boolean hole, boolean unfloored)
	{
		byte[][][] settings = new byte[4][SIZE][SIZE];
		byte[] filled = new byte[SIZE * SIZE];
		for (int x = 0; x < SIZE; ++x)
		{
			for (int y = 0; y < SIZE; ++y)
			{
				filled[x * SIZE + y] = (byte) (unfloored ? 0b01 : 0b11);
			}
		}
		for (int x = 8; x < 12; ++x)
		{
			for (int y = 8; y < 12; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
				settings[1][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		filled[14 * SIZE + 14] = 0b01;
		if (hole)
		{
			filled[9 * SIZE + 9] = 0b01;
		}
		int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
		for (int l = 0; l < 4; ++l)
		{
			for (int[] row : heights[l])
			{
				java.util.Arrays.fill(row, -240 * l);
			}
		}
		RoomMap m = RoomMap.build(BuildingDetector.detect(settings, null), settings, heights, OFFSET);
		m.linkStoreys(filled);
		return m;
	}

	@Test
	public void holesInTheFloorLinkTheStoreys()
	{
		RoomMap m = twoStoreys(true);
		int below = m.room(0, 9, 9), above = m.room(1, 9, 9);
		assertTrue(below > 0 && above > 0 && below != above);
		// a light on the lower floor lights its own room, and the upper room only where the hole is
		float lamp = m.lightCode(world(10), -200, world(10), 0);
		assertEquals(1, RoomMap.share(lamp, m.roomAt(world(10), -40, world(10))[0], m.roomAt(world(10), -40, world(10))[1], 0.5f), 0);
		int[] aboveHole = m.roomAt(world(9), -300, world(9));
		assertEquals(above, aboveHole[0]);
		assertEquals(below, aboveHole[1]);
		assertEquals(1, RoomMap.share(lamp, aboveHole[0], aboveHole[1], 0.5f), 0);
		int[] aboveFloor = m.roomAt(world(10), -300, world(10));
		assertEquals(above, aboveFloor[0]);
		assertEquals(0, aboveFloor[1]);
		assertEquals(0, RoomMap.share(lamp, aboveFloor[0], aboveFloor[1], 0.5f), 0);
		// and the other way: the upper light reaches the floor under the hole
		float chandelier = m.lightCode(world(10), -440, world(10), 1);
		int[] underHole = m.roomAt(world(9), -40, world(9));
		assertEquals(1, RoomMap.share(chandelier, underHole[0], underHole[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(chandelier, m.roomAt(world(10), -40, world(10))[0], 0, 0.5f), 0);
		// the link is in the texels
		FloatBuffer b = FloatBuffer.allocate(m.texelCount() * 4);
		m.texels(b);
		int t = (RoomMap.HEADER_TEXELS + (9 * SIZE + 9) * RoomMap.TEXELS_PER_TILE) * 4;
		assertEquals(below + above * 4096f, b.get(t), 0);
		assertEquals(above + below * 4096f, b.get(t + 1), 0);
	}

	@Test
	public void solidFloorsLinkNothing()
	{
		RoomMap m = twoStoreys(false);
		assertEquals(0, m.roomAt(world(9), -300, world(9))[1]);
		assertEquals(0, m.roomAt(world(9), -40, world(9))[1]);
		assertFalse(m.seenFrom(0, 1, world(9), world(9)));
		assertTrue(m.seenFrom(1, 1, world(9), world(9)));
	}

	@Test
	public void roofedOpenGroundIsNoHole()
	{
		// rooms flagged on both planes but nothing floored above: not a stairwell, nothing links
		RoomMap m = twoStoreys(true, true);
		assertTrue(m.room(0, 9, 9) > 0 && m.room(1, 9, 9) > 0);
		assertEquals(0, m.link(0, 9, 9));
		assertEquals(0, m.link(1, 9, 9));
		assertEquals(0, m.link(0, 10, 10));
		assertFalse(m.isLinked(m.room(0, 9, 9)));
	}

	@Test
	public void lightsOfOtherPlanesAreSeenThroughOpenings()
	{
		RoomMap m = twoStoreys(true);
		// the room under the hole is linked: its lights show from above, and the upper room's from below
		assertTrue(m.seenFrom(0, 1, world(10), world(10)));
		assertTrue(m.seenFrom(1, 0, world(10), world(10)));
		// nothing floors a light over: the pit below a gallery
		assertTrue(m.seenFrom(0, 1, world(14), world(14)));
		// but a floor above (the second storey) over an unlinked room hides it
		RoomMap solid = twoStoreys(false);
		assertFalse(solid.seenFrom(0, 1, world(10), world(10)));
		assertFalse(solid.seenFrom(1, 0, world(10), world(10)));
		// a light on the upper floor, seen from below, over open air: nothing floors it in
		assertTrue(solid.seenFrom(1, 0, world(14), world(14)));
		// without floor data a light on another plane is hidden
		RoomMap unknown = house(walls());
		assertFalse(unknown.seenFrom(0, 1, world(10), world(10)));
		assertTrue(unknown.seenFrom(0, 0, world(10), world(10)));
	}
}
