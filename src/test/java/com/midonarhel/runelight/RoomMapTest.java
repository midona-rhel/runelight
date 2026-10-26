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
	static RoomMap house(int[][][] flags)
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
	static float world(int t)
	{
		return (t - OFFSET + 0.5f) * TILE;
	}

	/** Walls all around the house except a doorway on its west side at y = 9. */
	static int[][][] walls()
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
	public void lightsInsideAreInTheirRoom()
	{
		RoomMap m = house(walls());
		assertEquals(1, m.roomOfLight(world(9), world(10), 0));
		assertEquals(0, m.roomOfLight(world(4), world(4), 0));
		// an unknown storey, or a place off the map: no room
		assertEquals(0, m.roomOfLight(world(9), world(10), -1));
		assertEquals(0, m.roomOfLight(-50 * TILE, 0, 0));
		assertEquals(1, m.roomAt(world(10), -40, world(10)));
		assertEquals(0, m.roomAt(world(14), -40, world(14)));
	}

	@Test
	public void wallTexelsFollowTheWalls()
	{
		RoomMap m = house(walls());
		int t = (RoomMap.HEADER_TEXELS + (9 * SIZE + 10) * RoomMap.TEXELS_PER_TILE + 2) * 4;
		FloatBuffer b = FloatBuffer.allocate(m.texelCount() * 4);
		m.texels(b);
		assertEquals(0, (int) b.get(t) & 63);
		// a wall inside the room: the texels must upload again
		int[][][] f = walls();
		f[0][9 - OFFSET][10 - OFFSET] |= WallMap.LOS_NORTH | WallMap.LOS_EAST;
		int before = m.getVersion();
		m.setWalls(WallMap.build(f, null, SIZE, OFFSET, 1));
		assertTrue(m.getVersion() != before);
		b.clear();
		m.texels(b);
		assertEquals(WallMap.NORTH | WallMap.EAST, (int) b.get(t) & 63);
		// the room's own number as the other half's: no diagonal, nothing changes room on the tile
		assertEquals(m.room(0, 9, 10), (int) b.get(t) >> 7);
		before = m.getVersion();
		m.setWalls(WallMap.build(f, null, SIZE, OFFSET, 1));
		assertEquals(before, m.getVersion());
	}

	@Test
	public void storeysAndRoofs()
	{
		RoomMap m = house(walls());
		// the top of an inner wall is still in the room, a roof above the next floor is not
		assertEquals(1, m.roomAt(world(10), -230, world(10)));
		assertEquals(0, m.roomAt(world(10), -300, world(10)));
		// outside the map: unknown
		assertEquals(-1, m.roomAt(-50 * TILE, 0, 0));
	}

	@Test
	public void texelLayout()
	{
		RoomMap m = house(walls());
		FloatBuffer b = FloatBuffer.allocate(m.texelCount() * 4);
		m.texels(b);
		assertEquals(0, b.remaining());
		assertEquals(2, b.get(0), 0);
		assertEquals(SIZE, b.get(1), 0);
		assertEquals(OFFSET, b.get(2), 0);
		int t = (RoomMap.HEADER_TEXELS + (9 * SIZE + 10) * RoomMap.TEXELS_PER_TILE) * 4;
		assertEquals(1, b.get(t), 0);
		assertEquals(-240, b.get(t + 5), 0);
		// the tile outside the doorway is outdoors
		int p = (RoomMap.HEADER_TEXELS + (7 * SIZE + 9) * RoomMap.TEXELS_PER_TILE) * 4;
		assertEquals(0, b.get(p), 0);
		// Buffer addresses are packed as floats; expanded diagnostic light storage must stay exact.
		assertTrue(LightClusters.ROOM_BASE + m.texelCount() < (1 << 24));
	}
}
