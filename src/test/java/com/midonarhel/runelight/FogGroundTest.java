package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class FogGroundTest
{
	private static final int SIZE = 20, OFFSET = 4;

	// rooms 1.. on the given planes, each over extended tiles 8..11, on flat ground at y 0 with storeys 240 apart
	private static RoomMap rooms(int... planes)
	{
		BuildingDetector.Result r = new BuildingDetector.Result();
		for (int plane : planes)
		{
			BuildingDetector.Room room = new BuildingDetector.Room();
			room.plane = plane;
			room.tiles = new int[16];
			for (int k = 0; k < 16; ++k)
			{
				room.tiles[k] = (8 + k / 4) << 16 | 8 + k % 4;
			}
			r.rooms.add(room);
		}
		int[][][] heights = new int[4][SIZE + 1][SIZE + 1];
		for (int l = 0; l < 4; ++l)
		{
			for (int[] row : heights[l])
			{
				java.util.Arrays.fill(row, -240 * l);
			}
		}
		return RoomMap.build(r, new byte[4][SIZE][SIZE], heights, OFFSET);
	}

	// the texels of corner (x, y) on the page of plane 0
	private static float[] corner(float[] t, int x, int y)
	{
		int o = (y * FogGround.WIDTH + x) * FogGround.CHANNELS;
		return new float[]{t[o], t[o + 1], t[o + 2], t[o + 3]};
	}

	@Test
	public void roomsReachTheirOutlineAndTheFloorAbove()
	{
		float[] t = new float[FogGround.WIDTH * FogGround.CORNERS * FogGround.CHANNELS];
		new FogGround().texels(rooms(0), null, t);
		// every corner of the room's tiles is in it, up to where the empty storey above starts (rooms.glsl)
		float top = -240 - RoomMap.PLANE_MARGIN;
		for (int x = 8; x <= 12; ++x)
		{
			assertEquals(1, corner(t, x, 8)[1], 0);
			assertEquals(top, corner(t, x, 12)[3], 0);
		}
		// outside it nothing, with the top carried one corner out for the fade at its walls
		assertEquals(0, corner(t, 7, 10)[1], 0);
		assertEquals(top, corner(t, 7, 10)[3], 0);
		assertEquals(0, corner(t, 5, 5)[1], 0);
		assertEquals(0, corner(t, 10, 10)[2], 0);
	}

	@Test
	public void storeysAboveCarryTheRoomUpAndOpenRoomsShow()
	{
		float[] t = new float[FogGround.WIDTH * FogGround.CORNERS * FogGround.CHANNELS];
		OpenRooms open = new OpenRooms();
		open.bits[0] |= 1 << 1;
		new FogGround().texels(rooms(0, 1), open, t);
		// the room over it goes on to the empty storey over that
		assertEquals(-480 - RoomMap.PLANE_MARGIN, corner(t, 10, 10)[3], 0);
		assertEquals(1, corner(t, 10, 10)[2], 0);
		assertEquals(1, corner(t, 12, 12)[2], 0);
		assertEquals(0, corner(t, 13, 12)[2], 0);
	}
}
