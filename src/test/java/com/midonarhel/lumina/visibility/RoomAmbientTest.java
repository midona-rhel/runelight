package com.midonarhel.lumina.visibility;

import java.nio.FloatBuffer;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** Geometry expectations for the restored, historical corner-distance room-fill field. */
public class RoomAmbientTest
{
	private static final int SIZE = 20, OFFSET = 4, X = 9, Y = 10;

	private int packed(RoomMap map)
	{
		FloatBuffer data = FloatBuffer.allocate(map.floatCount());
		map.texels(data);
		return (int) data.get(RoomMap.texelIndex(SIZE, 4, X, Y));
	}

	private void corners(int packed, int sw, int se, int nw, int ne)
	{
		assertEquals(sw, packed & 63);
		assertEquals(se, (packed >>> 6) & 63);
		assertEquals(nw, (packed >>> 12) & 63);
		assertEquals(ne, (packed >>> 18) & 63);
	}

	@Test
	public void straightWallReachesFullDistanceOneTileAway()
	{
		int[][][] flags = new int[4][12][12];
		flags[0][X - OFFSET][Y - OFFSET] = WallMap.LOS_WEST;
		RoomMap map = RoomMapTest.house(flags);
		corners(packed(map), 0, 63, 0, 63);
	}

	@Test
	public void openingAnIsolatedDoorUpdatesTheDistanceLayer()
	{
		int[][][] flags = new int[4][12][12];
		flags[0][X - OFFSET][Y - OFFSET] = WallMap.LOS_WEST;
		RoomMap map = RoomMapTest.house(flags);
		corners(packed(map), 0, 63, 0, 63);
		int version = map.getVersion();
		flags[0][X - OFFSET][Y - OFFSET] = 0;
		map.setWalls(WallMap.build(flags, null, SIZE, OFFSET, 1));
		assertTrue(map.getVersion() != version);
		corners(packed(map), 63, 63, 63, 63);
	}

	@Test
	public void diagonalStoresPerpendicularDistancesAndKeepsItsMask()
	{
		RoomMap map = RoomMapTest.house(null);
		map.setWalls(WallMap.build(null, Collections.singletonList(
			new WallMap.Diagonal(0, X, Y, WallMap.DIAG_SW_NE)), SIZE, OFFSET, 1));
		// The opposite corners are sqrt(0.5) tiles from the diagonal, quantized to six bits.
		corners(packed(map), 0, 45, 45, 0);
		FloatBuffer data = FloatBuffer.allocate(map.floatCount());
		map.texels(data);
		assertEquals(WallMap.DIAG_SW_NE,
			(int) data.get(RoomMap.texelIndex(SIZE, 2, X, Y)) & 63);
	}

	@Test
	public void narrowRoomRetainsHistoricalCornerFieldLimit()
	{
		int[][][] flags = new int[4][12][12];
		flags[0][X - OFFSET][Y - OFFSET] = WallMap.LOS_WEST | WallMap.LOS_EAST;
		// All four corners touch a wall: historical interpolation keeps this entire narrow tile
		// at the 35% wall level. This test records the approximation, not exact center distance.
		corners(packed(RoomMapTest.house(flags)), 0, 0, 0, 0);
	}
}
