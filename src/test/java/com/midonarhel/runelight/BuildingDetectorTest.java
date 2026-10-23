package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class BuildingDetectorTest
{
	private static final int SIZE = 40;

	private final byte[][][] settings = new byte[4][SIZE][SIZE];
	private final int[][][] roofs = new int[4][SIZE][SIZE];

	private void room(int level, int x0, int y0, int x1, int y1, int roofId)
	{
		for (int x = x0; x <= x1; ++x)
		{
			for (int y = y0; y <= y1; ++y)
			{
				settings[level][x][y] |= BuildingDetector.TILE_FLAG_UNDER_ROOF;
				roofs[level][x][y] = roofId;
			}
		}
	}

	@Test
	public void separateHousesAreSeparateRooms()
	{
		room(0, 2, 2, 5, 5, 1);
		room(0, 10, 2, 14, 6, 2);
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertEquals(2, r.rooms.size());
		assertTrue(r.usedRoofIds);
		assertEquals(16, r.rooms.get(0).tiles.length);
		assertEquals(25, r.rooms.get(1).tiles.length);
	}

	@Test
	public void adjacentBuildingsSplitByRoofId()
	{
		room(0, 2, 2, 5, 5, 1);
		room(0, 6, 2, 9, 5, 2);
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertEquals(2, r.rooms.size());
	}

	@Test
	public void flagsWithoutRoofIdsStillWork()
	{
		room(0, 2, 2, 5, 5, 1);
		room(0, 6, 2, 9, 5, 2);
		BuildingDetector.Result r = BuildingDetector.detect(settings, null);
		assertFalse(r.usedRoofIds);
		// without ids the two touching houses merge, which is fine for lighting
		assertEquals(1, r.rooms.size());
	}

	@Test
	public void roofRemovalOverridesAreIgnored()
	{
		room(0, 2, 2, 5, 5, 1);
		// a roof removal override flags an area with no roof over it; ids show it is not a building
		for (int x = 20; x < 30; ++x)
		{
			for (int y = 20; y < 30; ++y)
			{
				settings[0][x][y] |= BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertTrue(r.usedRoofIds);
		assertEquals(1, r.rooms.size());
	}

	@Test
	public void wholeSceneFlaggedIsRejected()
	{
		byte[][][] big = new byte[4][60][60];
		for (int x = 0; x < 60; ++x)
		{
			for (int y = 0; y < 60; ++y)
			{
				big[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		BuildingDetector.Result r = BuildingDetector.detect(big, null);
		assertEquals(0, r.rooms.size());
		assertEquals(1, r.rejectedHuge);
	}

	@Test
	public void tinyComponentsAreDropped()
	{
		room(0, 2, 2, 2, 2, 1);
		room(0, 8, 8, 9, 8, 2);
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertEquals(0, r.rooms.size());
		assertEquals(2, r.rejectedSmall);
	}

	@Test
	public void diagonalTilesAreNotConnected()
	{
		room(0, 2, 2, 4, 4, 1);
		room(0, 5, 5, 7, 7, 1);
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertEquals(2, r.rooms.size());
	}

	@Test
	public void upperFloorsAreTheirOwnPlanes()
	{
		room(0, 2, 2, 7, 7, 1);
		room(1, 2, 2, 7, 7, 2);
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertEquals(2, r.rooms.size());
		assertEquals(0, r.rooms.get(0).plane);
		assertEquals(1, r.rooms.get(1).plane);
	}

	@Test
	public void topLevelFlagsNeedNoRoofId()
	{
		room(0, 2, 2, 7, 7, 1);
		// nothing can be above level 3, so it has no roof ids
		for (int x = 10; x < 14; ++x)
		{
			for (int y = 10; y < 14; ++y)
			{
				settings[3][x][y] |= BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertEquals(2, r.rooms.size());
		assertEquals(3, r.rooms.get(1).plane);
	}

	@Test
	public void bridgeTilesUseTheLevelAbove()
	{
		// a house on a bridge: its data is stored on level 1 but a player in it is on plane 0
		for (int x = 2; x <= 6; ++x)
		{
			for (int y = 2; y <= 6; ++y)
			{
				settings[1][x][y] |= BuildingDetector.TILE_FLAG_BRIDGE;
			}
		}
		room(1, 2, 2, 6, 6, 5);
		BuildingDetector.Result r = BuildingDetector.detect(settings, roofs);
		assertEquals(1, r.rooms.size());
		assertEquals(0, r.rooms.get(0).plane);
	}

	@Test
	public void nightFactorFadesAtDusk()
	{
		assertEquals(0, BuildingLights.nightFactor(40), 0);
		assertEquals(0, BuildingLights.nightFactor(BuildingLights.FADE_START_ALTITUDE), 0);
		assertEquals(0.5f, BuildingLights.nightFactor((BuildingLights.FADE_START_ALTITUDE + BuildingLights.FADE_END_ALTITUDE) / 2), 1e-4);
		assertEquals(1, BuildingLights.nightFactor(-30), 0);
	}
}
