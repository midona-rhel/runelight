package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import org.junit.Test;

public class WaterSurfaceTest
{
	private static WaterMap map(int... waterTiles)
	{
		WaterMap m = new WaterMap();
		Arrays.fill(m.level, (byte) -1);
		for (int k = 0; k < waterTiles.length; k += 3)
		{
			int i = waterTiles[k + 1] * WaterMap.SIZE + waterTiles[k];
			m.level[i] = 0;
			m.type[i] = (byte) waterTiles[k + 2];
		}
		m.hasWater = waterTiles.length > 0;
		return m;
	}

	@Test
	public void waterTilesAndTheLandAroundThem()
	{
		WaterSurface.Mesh m = WaterSurface.build(map(20, 30, WaterMap.SWAMP, 21, 30, 0));
		// two water tiles and the ring of land around them: 4 x 3
		assertEquals(12, m.count);
		int swamp = 0, fresh = 0;
		for (int i = 0; i < m.count; ++i)
		{
			int x = m.tiles[i * 4] & 0xFF, y = m.tiles[i * 4 + 1] & 0xFF, t = m.tiles[i * 4 + 2];
			assertTrue(x >= 19 && x <= 22 && y >= 29 && y <= 31);
			if (x == 21 && y == 30)
			{
				// water without a type counts as fresh
				assertEquals(WaterMap.FRESH, t);
			}
			if (x == 19 && y == 30)
			{
				// land takes its water neighbor's type
				assertEquals(WaterMap.SWAMP, t);
			}
			swamp += t == WaterMap.SWAMP ? 1 : 0;
			fresh += t == WaterMap.FRESH ? 1 : 0;
		}
		assertEquals(12, swamp + fresh);
	}

	@Test
	public void instancesAreGroupedByChunk()
	{
		// water in two chunks: each chunk's instances contiguous, in chunk order
		WaterSurface.Mesh m = WaterSurface.build(map(3, 3, 1, 44, 3, 2, 3, 44, 1));
		assertEquals(3, m.chunks);
		int next = 0;
		for (int c = 0; c < m.chunks; ++c)
		{
			assertEquals(next, m.chunkStart[c]);
			for (int i = m.chunkStart[c]; i < m.chunkStart[c] + m.chunkCount[c]; ++i)
			{
				int x = m.tiles[i * 4] & 0xFF, y = m.tiles[i * 4 + 1] & 0xFF;
				assertEquals(m.chunkX[c], x / WaterSurface.CHUNK * WaterSurface.CHUNK);
				assertEquals(m.chunkY[c], y / WaterSurface.CHUNK * WaterSurface.CHUNK);
			}
			next += m.chunkCount[c];
		}
		assertEquals(m.count, next);
		assertNull(WaterSurface.build(map()));
	}

	@Test
	public void quadCoversTheTileWoundAlike()
	{
		short[] idx = WaterSurface.QUAD;
		assertEquals(6, idx.length);
		// twice the signed area of both triangles, corners 0 sw, 1 se, 2 nw, 3 ne: the whole tile, wound alike
		double area = 0;
		for (int k = 0; k < idx.length; k += 3)
		{
			int a = idx[k], b = idx[k + 1], c = idx[k + 2];
			int ax = a % 2, az = a / 2, bx = b % 2, bz = b / 2, cx = c % 2, cz = c / 2;
			double cross = (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
			assertTrue(cross < 0);
			area -= cross;
		}
		assertEquals(2.0, area, 1e-9);
	}

	@Test
	public void chunkDistanceIsToTheNearestPoint()
	{
		int cx = RuneLightPlugin.SCENE_OFFSET + 8, cy = RuneLightPlugin.SCENE_OFFSET + 16;
		// inside
		assertEquals(0, WaterSurface.chunkDistance(8 * 128 + 10, 16 * 128 + 500, cx, cy), 1e-3);
		// west of it
		assertEquals(300, WaterSurface.chunkDistance(8 * 128 - 300, 16 * 128 + 500, cx, cy), 1e-3);
		// beyond the north east corner
		assertEquals(500, WaterSurface.chunkDistance(16 * 128 + 300, 24 * 128 + 400, cx, cy), 1e-3);
	}

	@Test
	public void lookSlotsKeepTypeAndTexture()
	{
		int k = Water.slotKey(208, WaterMap.OCEAN_DARK);
		assertEquals(208, Water.slotTexture(k));
		assertEquals(WaterMap.OCEAN_DARK, Water.slotType(k));
		assertEquals(-1, Water.slotTexture(Water.slotKey(-1, WaterMap.FRESH)));
		// past the limit a look shares the first slot of its type
		WaterSurface.Mesh m = new WaterSurface.Mesh();
		for (int i = 0; i < Water.SLOTS; ++i)
		{
			assertEquals(i, m.slot(Water.slotKey(i, i % 2 == 0 ? WaterMap.FRESH : WaterMap.OCEAN)));
		}
		assertEquals(1, m.slot(Water.slotKey(500, WaterMap.OCEAN)));
		assertEquals(5, m.slot(Water.slotKey(5, WaterMap.OCEAN)));
	}
}
