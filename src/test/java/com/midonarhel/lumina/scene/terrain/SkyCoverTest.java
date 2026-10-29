package com.midonarhel.lumina.scene.terrain;

import java.util.Arrays;
import net.runelite.api.Constants;
import static com.midonarhel.lumina.scene.SceneData.SKY_NE;
import static com.midonarhel.lumina.scene.SceneData.SKY_NW;
import static com.midonarhel.lumina.scene.SceneData.SKY_SE;
import static com.midonarhel.lumina.scene.SceneData.SKY_SW;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SkyCoverTest
{
	private static final int N = 8;

	private static int corner(byte[] q, int x, int z, int corner)
	{
		return q[(x * N + z) * 4 + corner] & 0xff;
	}

	/**
	 * A 3x3 room at x, z = 2..4 walled all round, with a door on the south edge of (3, 2).
	 */
	private static byte[] roomWithDoor(boolean[] covered)
	{
		byte[] walls = new byte[N * N];
		for (int x = 2; x <= 4; x++)
		{
			for (int z = 2; z <= 4; z++)
			{
				covered[x * N + z] = true;
				int edges = 0;
				if (x == 2)
				{
					edges |= SkyCover.EDGE_W;
				}
				if (x == 4)
				{
					edges |= SkyCover.EDGE_E;
				}
				if (z == 4)
				{
					edges |= SkyCover.EDGE_N;
				}
				if (z == 2 && x != 3)
				{
					edges |= SkyCover.EDGE_S;
				}
				SkyCover.closeEdges(walls, N, x, z, edges);
			}
		}
		return walls;
	}

	@Test
	public void coverFromRoofsFloorsAndFlags()
	{
		int[][][] roofs = new int[Constants.MAX_Z][N][N];
		byte[][][] settings = new byte[Constants.MAX_Z][N][N];
		int[][][] heights = new int[Constants.MAX_Z][N][N];
		boolean[][] floors = new boolean[Constants.MAX_Z][N * N];
		boolean[][] covered = new boolean[Constants.MAX_Z][N * N];
		for (int level = 0; level < Constants.MAX_Z; level++)
		{
			for (int[] column : heights[level])
			{
				Arrays.fill(column, -240 * level);
			}
		}
		roofs[0][1][1] = 7;
		settings[0][2][2] = Constants.TILE_FLAG_UNDER_ROOF; // ignored while the scene has roof ids
		floors[1][3 * N + 3] = true; // a floor 240 above (3, 3)
		floors[1][4 * N + 4] = true;
		heights[1][4][4] = -40; // too close to be cover

		SkyCover.cover(N, roofs, settings, heights, floors, covered);
		assertTrue(covered[0][1 * N + 1]);
		assertFalse(covered[0][2 * N + 2]);
		assertTrue(covered[0][3 * N + 3]);
		assertFalse(covered[1][3 * N + 3]);
		assertFalse(covered[0][4 * N + 4]);
		assertFalse(covered[0][5 * N + 5]);

		// without roof ids the under-roof flag decides
		SkyCover.cover(N, null, settings, heights, floors, covered);
		assertFalse(covered[0][1 * N + 1]);
		assertTrue(covered[0][2 * N + 2]);
	}

	@Test
	public void lightFallsOffFromTheDoorAndNeverThroughWalls()
	{
		boolean[] covered = new boolean[N * N];
		byte[] walls = roomWithDoor(covered);
		float[] values = new float[N * N];
		SkyCover.propagate(N, covered, walls, values);

		float f = (float) Math.exp(-1 / SkyCover.REACH_TILES);
		assertEquals(1f, values[3 * N + 1], 0f);
		assertEquals(f, values[3 * N + 2], 1e-5f); // one step in through the door
		assertEquals(f * f, values[3 * N + 3], 1e-5f);
		// (2, 3) is reached diagonally from (3, 2) since both ways round are open inside the room
		assertEquals((float) Math.pow(f, 2.4), values[2 * N + 3], 1e-5f);
		// (2, 2) sits beside the open (1, 2) but a wall is between them: two steps from the door
		assertEquals(f * f, values[2 * N + 2], 1e-5f);
		assertTrue(values[3 * N + 4] < values[3 * N + 3]);

		byte[] quadrants = new byte[N * N * 4];
		SkyCover.quadrants(N, walls, values, quadrants, 0);
		// the corner point (3, 2) south-west of the door tile: all four tiles round it join through the door and the
		// room's open interior edge, so they share one value
		int shared = corner(quadrants, 3, 2, SKY_SW);
		assertEquals(shared, corner(quadrants, 3, 1, SKY_NW));
		assertEquals(shared, corner(quadrants, 2, 1, SKY_NE));
		assertEquals(shared, corner(quadrants, 2, 2, SKY_SE));
		assertTrue(shared > 0 && shared < 255);
		// the room's south-west corner point (2, 2): the room tile is cut off by walls from the three open tiles
		assertEquals(255, corner(quadrants, 1, 1, SKY_NE));
		assertEquals(255, corner(quadrants, 2, 1, SKY_NW));
		assertNotEquals(255, corner(quadrants, 2, 2, SKY_SW));
		assertEquals(Math.round(values[2 * N + 2] * 255), corner(quadrants, 2, 2, SKY_SW));
	}

	@Test
	public void closedRoomIsDark()
	{
		boolean[] covered = new boolean[N * N];
		byte[] walls = roomWithDoor(covered);
		SkyCover.closeEdges(walls, N, 3, 2, SkyCover.EDGE_S);
		float[] values = new float[N * N];
		SkyCover.propagate(N, covered, walls, values);
		for (int x = 2; x <= 4; x++)
		{
			for (int z = 2; z <= 4; z++)
			{
				assertEquals(0f, values[x * N + z], 0f);
			}
		}
		assertEquals(1f, values[3 * N + 1], 0f);
	}
}
