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
		m.updateOpenings(flags);
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
			f[0][x - OFFSET][8 - OFFSET] |= RoomMap.BLOCK_SOUTH;
			f[0][x - OFFSET][7 - OFFSET] |= RoomMap.BLOCK_NORTH;
			f[0][x - OFFSET][11 - OFFSET] |= RoomMap.BLOCK_NORTH;
			f[0][x - OFFSET][12 - OFFSET] |= RoomMap.BLOCK_SOUTH;
		}
		for (int y = 8; y < 12; ++y)
		{
			f[0][11 - OFFSET][y - OFFSET] |= RoomMap.BLOCK_EAST;
			f[0][12 - OFFSET][y - OFFSET] |= RoomMap.BLOCK_WEST;
			if (y != 9)
			{
				f[0][8 - OFFSET][y - OFFSET] |= RoomMap.BLOCK_WEST;
				f[0][7 - OFFSET][y - OFFSET] |= RoomMap.BLOCK_EAST;
			}
		}
		return f;
	}

	@Test
	public void porchesAreFoundOnlyBesideRoomsWhateverTheFlags()
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
						row[y] = rnd.nextInt(4) == 0 ? RoomMap.BLOCK_NORTH | RoomMap.BLOCK_WEST : rnd.nextInt(8) == 0 ? RoomMap.BLOCK_FULL : 0;
					}
				}
			}
			RoomMap m = house(f);
			for (int plane = 0; plane < RoomMap.PLANES; ++plane)
			{
				for (int x = 0; x < SIZE; ++x)
				{
					for (int y = 0; y < SIZE; ++y)
					{
						assertEquals(porchByRule(m, f, plane, x, y), m.porch[m.index(plane, x, y)]);
					}
				}
			}
		}
	}

	// the rule written out for every tile: no room here, a free tile, and a room next to it with no wall between
	private static short porchByRule(RoomMap m, int[][][] flags, int plane, int x, int y)
	{
		int lx = x - OFFSET, ly = y - OFFSET;
		if (m.rooms[m.index(plane, x, y)] != 0 || lx < 0 || ly < 0 || lx >= LOCAL || ly >= LOCAL
			|| (flags[plane][lx][ly] & RoomMap.BLOCK_FULL) != 0)
		{
			return 0;
		}
		int[][] steps = {{1, 0, RoomMap.BLOCK_EAST, RoomMap.BLOCK_WEST}, {-1, 0, RoomMap.BLOCK_WEST, RoomMap.BLOCK_EAST},
			{0, 1, RoomMap.BLOCK_NORTH, RoomMap.BLOCK_SOUTH}, {0, -1, RoomMap.BLOCK_SOUTH, RoomMap.BLOCK_NORTH}};
		for (int[] s : steps)
		{
			int nx = x + s[0], ny = y + s[1];
			if (nx < 0 || ny < 0 || nx >= SIZE || ny >= SIZE || nx - OFFSET < 0 || ny - OFFSET < 0
				|| nx - OFFSET >= LOCAL || ny - OFFSET >= LOCAL)
			{
				continue;
			}
			short room = m.rooms[m.index(plane, nx, ny)];
			if (room != 0 && (flags[plane][lx][ly] & s[2]) == 0 && (flags[plane][nx - OFFSET][ny - OFFSET] & s[3]) == 0)
			{
				return room;
			}
		}
		return 0;
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
		int[] besideWall = m.roomAt(world(7), -40, world(10));
		assertEquals(0, porch[0]);
		assertEquals(1, porch[1]);
		assertEquals(0, besideWall[1]);
		assertEquals(0.5f, RoomMap.share(inside, porch[0], porch[1], 0.5f), 0);
		assertEquals(0, RoomMap.share(inside, besideWall[0], besideWall[1], 0.5f), 0);

		// the door closes
		int[][][] closed = walls();
		closed[0][8 - OFFSET][9 - OFFSET] |= RoomMap.BLOCK_WEST;
		int before = m.getVersion();
		assertTrue(m.updateOpenings(closed));
		assertTrue(m.getVersion() != before);
		assertEquals(0, m.roomAt(world(7), -40, world(9))[1]);
		assertFalse(m.updateOpenings(closed));
	}

	@Test
	public void wallLightsLightBothSides()
	{
		RoomMap m = house(walls());
		// a torch on the outside of the east wall: on the room's edge tile, 10 units from the edge
		float x = (12 - OFFSET) * TILE - 10;
		float torch = m.lightCode(x, -200, world(10), 0);
		assertFalse(RoomMap.indoor(torch));
		assertEquals(1, RoomMap.share(torch, 1, 0, 0.5f), 0);
		assertEquals(1, RoomMap.share(torch, 0, 0, 0.5f), 0);
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
		int t = (RoomMap.HEADER_TEXELS + (9 * SIZE + 10) * 2) * 4;
		assertEquals(1, b.get(t), 0);
		assertEquals(-240, b.get(t + 5), 0);
		// the porch tile: room 0, porch 1
		int p = (RoomMap.HEADER_TEXELS + (7 * SIZE + 9) * 2) * 4;
		assertEquals(4096, b.get(p), 0);
		assertTrue(LightClusters.ROOM_BASE + m.texelCount() < 65536);
	}
}
