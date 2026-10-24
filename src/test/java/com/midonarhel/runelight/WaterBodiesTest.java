package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WaterBodiesTest
{
	private static final int N = 48;

	private static WaterBodies grid()
	{
		return new WaterBodies(N);
	}

	private static void fill(WaterBodies w, int x0, int y0, int x1, int y1)
	{
		for (int y = y0; y <= y1; ++y)
		{
			for (int x = x0; x <= x1; ++x)
			{
				w.water[y * N + x] = true;
			}
		}
	}

	private static int at(int x, int y)
	{
		return y * N + x;
	}

	private static float speed(WaterBodies w, int i)
	{
		return (float) Math.hypot(w.flowX[i], w.flowZ[i]);
	}

	@Test
	public void smallPondIsStill()
	{
		WaterBodies w = grid();
		fill(w, 10, 10, 12, 12);
		w.solve();
		assertEquals(1, w.bodies);
		assertEquals(WaterBodies.POOL, w.bodyClass[0]);
		for (int i = 0; i < N * N; ++i)
		{
			assertEquals(0, speed(w, i), 0);
		}
		assertEquals(1, w.shore[at(11, 11)] - 1); // the middle is one tile further from land
	}

	@Test
	public void moatIsStill()
	{
		WaterBodies w = grid();
		// a two wide ring around a castle, nowhere near the scene edge
		fill(w, 10, 10, 30, 11);
		fill(w, 10, 28, 30, 29);
		fill(w, 10, 10, 11, 29);
		fill(w, 29, 10, 30, 29);
		w.solve();
		assertEquals(1, w.bodies);
		assertEquals(WaterBodies.POOL, w.bodyClass[0]);
		assertEquals(0, speed(w, at(20, 10)), 0);
	}

	@Test
	public void riverFeedsLake()
	{
		WaterBodies w = grid();
		fill(w, 20, 20, 33, 33); // lake
		fill(w, 25, 5, 27, 19); // river coming from the south, enclosed
		w.solve();
		assertEquals(1, w.bodies);
		assertEquals(WaterBodies.LAKE, w.bodyClass[0]);
		// the channel flows north into the lake
		for (int y = 6; y <= 16; ++y)
		{
			int i = at(26, y);
			assertTrue("flow at " + y + " " + w.flowZ[i], w.flowZ[i] > 0.2f);
			assertTrue(Math.abs(w.flowX[i]) < Math.abs(w.flowZ[i]) * 0.5f);
		}
		// the middle of the lake is calm
		assertTrue(speed(w, at(27, 28)) < 0.05f);
	}

	@Test
	public void levelRiverFlowsOneWay()
	{
		WaterBodies w = grid();
		fill(w, 0, 20, N - 1, 22);
		w.solve();
		assertEquals(WaterBodies.RIVER, w.bodyClass[0]);
		float sign = Math.signum(w.flowX[at(24, 21)]);
		assertTrue(sign != 0);
		for (int x = 3; x < N - 3; ++x)
		{
			int i = at(x, 21);
			assertEquals("direction at " + x, sign, Math.signum(w.flowX[i]), 0);
			assertTrue(Math.abs(w.flowX[i]) > 0.3f);
			assertTrue(Math.abs(w.flowZ[i]) < 0.1f);
		}
	}

	@Test
	public void riverFlowsDownhill()
	{
		for (int dir = -1; dir <= 1; dir += 2)
		{
			WaterBodies w = grid();
			fill(w, 0, 20, N - 1, 22);
			for (int y = 20; y <= 22; ++y)
			{
				for (int x = 0; x < N; ++x)
				{
					// y down: larger is lower; dir 1 runs downhill toward +x
					w.surface[at(x, y)] = dir * x * 4;
				}
			}
			w.solve();
			assertTrue("dir " + dir, Math.signum(w.flowX[at(24, 21)]) == dir);
		}
	}

	@Test
	public void worldFlowMapWins()
	{
		WaterBodies w = grid();
		// enclosed and narrow: on its own a still pool, but the world map knows it is a stretch of river
		fill(w, 10, 10, 30, 12);
		for (int x = 10; x <= 30; ++x)
		{
			for (int y = 10; y <= 12; ++y)
			{
				w.mapFlowX[at(x, y)] = -0.5f;
				w.mapFlowZ[at(x, y)] = 0;
			}
		}
		w.solve();
		assertEquals(WaterBodies.POOL, w.bodyClass[0]);
		assertTrue(w.flowX[at(20, 11)] < -0.3f);
		assertEquals(0, w.flowZ[at(20, 11)], 1e-6);
	}

	private static void mapFlow(WaterBodies w, int x0, int y0, int x1, int y1, float fx)
	{
		for (int y = y0; y <= y1; ++y)
		{
			for (int x = x0; x <= x1; ++x)
			{
				w.mapFlowX[at(x, y)] = fx;
				w.mapFlowZ[at(x, y)] = 0;
			}
		}
	}

	@Test
	public void channelRunsFastestInTheMiddle()
	{
		WaterBodies w = grid();
		fill(w, 0, 20, N - 1, 26);
		mapFlow(w, 0, 20, N - 1, 26, 0.5f);
		w.solve();
		float bank = speed(w, at(24, 20)), middle = speed(w, at(24, 23));
		assertEquals(4, w.ridge[at(24, 20)], 1e-6);
		assertTrue("bank " + bank + " middle " + middle, middle > bank * 1.8f);
		// symmetric
		assertEquals(bank, speed(w, at(24, 26)), 1e-6);
		assertTrue(speed(w, at(24, 22)) > bank && speed(w, at(24, 22)) < middle);
	}

	@Test
	public void narrowsRunFasterAndRougher()
	{
		WaterBodies w = grid();
		// a wide reach, then three tiles wide, then wide again
		fill(w, 0, 16, 15, 30);
		fill(w, 16, 22, 31, 24);
		fill(w, 32, 16, N - 1, 30);
		mapFlow(w, 0, 16, N - 1, 30, 0.5f);
		w.solve();
		float wide = speed(w, at(8, 23)), narrow = speed(w, at(24, 23));
		assertTrue("wide " + wide + " narrow " + narrow, narrow > wide * 2);
		assertTrue(w.turbulence[at(24, 23)] > w.turbulence[at(8, 23)]);
		assertEquals(0, w.turbulence[at(8, 23)], 1e-6);
	}

	@Test
	public void shapedMapFlowKeepsItsSpeeds()
	{
		WaterBodies w = grid();
		// a wide reach, then three tiles wide: the map's speeds already say how fast each part runs
		fill(w, 0, 16, 15, 30);
		fill(w, 16, 22, 31, 24);
		mapFlow(w, 0, 16, 15, 30, 0.2f);
		mapFlow(w, 16, 22, 31, 24, 1.1f);
		w.mapShaped = true;
		w.solve();
		assertEquals(0.2f, speed(w, at(8, 23)), 1e-6);
		assertEquals(1.1f, speed(w, at(24, 23)), 1e-6);
		assertEquals(1, w.gain[at(24, 23)], 1e-6);
		// the narrows still run rough, the wide reach doesn't
		assertTrue(w.turbulence[at(24, 23)] > 0.2f);
		assertEquals(0, w.turbulence[at(8, 23)], 1e-6);
	}

	@Test
	public void crossSectionGrowsWithWidth()
	{
		float prev = 0;
		for (float r = 1; r <= 12; r += 0.5f)
		{
			float a = WaterBodies.crossSection(r);
			assertTrue(a > prev);
			prev = a;
		}
	}

	@Test
	public void seaKeepsItsPushUnderTheWorldMap()
	{
		WaterBodies w = grid();
		fill(w, 0, 0, N - 1, 29);
		// the map says the sea is still
		for (int i = 0; i < N * 30; ++i)
		{
			w.mapFlowX[i] = 0;
			w.mapFlowZ[i] = 0;
		}
		w.solve();
		assertTrue(w.flowZ[at(24, 29)] > 0.05f);
		assertTrue(speed(w, at(24, 10)) < 0.01f);
	}

	@Test
	public void seaPushesTowardShore()
	{
		WaterBodies w = grid();
		// the whole south of the scene is sea, the shore runs along y = 30
		fill(w, 0, 0, N - 1, 29);
		w.solve();
		assertEquals(WaterBodies.OCEAN, w.bodyClass[0]);
		int i = at(24, 29);
		assertEquals(1, w.shore[i]);
		assertTrue("onshore " + w.flowZ[i], w.flowZ[i] > 0.05f);
		// open water: no push, waves come from the wind there
		assertTrue(speed(w, at(24, 10)) < 0.01f);
		assertTrue(w.energy[at(24, 10)] > w.energy[i]);
	}

	@Test
	public void riverRunsIntoSea()
	{
		WaterBodies w = grid();
		fill(w, 0, 0, N - 1, 19); // sea
		fill(w, 30, 20, 32, 40); // river from the north, enclosed at its source
		w.solve();
		assertEquals(1, w.bodies);
		assertEquals(WaterBodies.OCEAN, w.bodyClass[0]);
		for (int y = 26; y <= 38; ++y)
		{
			assertTrue("flow at " + y, w.flowZ[at(31, y)] < -0.3f);
		}
	}

	@Test
	public void unloadedTilesAreNotShore()
	{
		WaterBodies w = grid();
		fill(w, 10, 10, 20, 12);
		// the map isn't loaded past x = 20: the river runs on there
		for (int y = 0; y < N; ++y)
		{
			for (int x = 21; x < N; ++x)
			{
				w.unknown[at(x, y)] = true;
			}
		}
		w.solve();
		assertEquals(WaterBodies.RIVER, w.bodyClass[0]);
		assertTrue(w.flowX[at(15, 11)] > 0.3f);
		// the tile beside the unloaded area is no shore
		assertEquals(2, w.shore[at(20, 11)]);
	}

	@Test
	public void depthGrowsFromShore()
	{
		int n = 20;
		boolean[] land = new boolean[n * n];
		for (int y = 0; y < n; ++y)
		{
			for (int x = 0; x < n; ++x)
			{
				// land on the left, water on the right running off the edge
				land[y * n + x] = x <= 3;
			}
		}
		byte[] out = new byte[n * n];
		WaterMap.depthLevels(land, n, WaterMap.MAX_LEVEL, out);
		assertEquals(0, out[10 * n + 3]);
		assertEquals(1, out[10 * n + 4]);
		assertEquals(5, out[10 * n + 8]);
		assertEquals(WaterMap.MAX_LEVEL, out[10 * n + 19]);
		assertTrue(WaterMap.depthForLevel(1) < WaterMap.depthForLevel(5));
		assertEquals(0, WaterMap.depthForLevel(0));
	}

	@Test
	public void textureKinds()
	{
		assertEquals(WaterMap.FRESH, WaterMap.textureKind(1));
		assertEquals(WaterMap.SWAMP, WaterMap.textureKind(25));
		assertEquals(WaterMap.OCEAN, WaterMap.textureKind(133));
		assertEquals(WaterMap.OCEAN_DARK, WaterMap.textureKind(137));
		assertEquals(WaterMap.OCEAN_PALE, WaterMap.textureKind(161));
		assertEquals(WaterMap.SWAMP, WaterMap.textureKind(177));
		assertEquals(WaterMap.NONE, WaterMap.textureKind(-1));
		assertEquals(WaterMap.NONE, WaterMap.textureKind(31));
	}

	@Test
	public void wideWaterRunningOffTheSceneIsSea()
	{
		WaterBodies w = grid();
		fill(w, 0, 0, N - 1, 29);
		w.solve();
		for (int i = 0; i < N * N; ++i)
		{
			assertEquals("tile " + i, w.water[i], w.open[i]);
		}
	}

	@Test
	public void riversAndLakesAreNotSea()
	{
		WaterBodies w = grid();
		// a channel across the scene, the map's flow running in it, and an enclosed lake
		fill(w, 20, 0, 22, N - 1);
		for (int y = 0; y < N; ++y)
		{
			for (int x = 20; x <= 22; ++x)
			{
				w.mapFlowX[at(x, y)] = 0;
				w.mapFlowZ[at(x, y)] = 0.5f;
			}
		}
		fill(w, 30, 10, 44, 24);
		w.solve();
		for (int i = 0; i < N * N; ++i)
		{
			assertFalse("tile " + i, w.open[i]);
		}
		// wide but with the map's flow in it: a river
		w = grid();
		fill(w, 0, 0, N - 1, 29);
		for (int i = 0; i < N * N; ++i)
		{
			w.mapFlowX[i] = 0.3f;
			w.mapFlowZ[i] = 0;
		}
		w.solve();
		assertFalse(w.open[at(24, 10)]);
	}

	@Test
	public void riverMouthRunsIntoTheSea()
	{
		WaterBodies w = grid();
		fill(w, 0, 0, N - 1, 19); // sea
		fill(w, 30, 20, 32, 40); // river from the north, enclosed at its source
		fill(w, 33, 28, 37, 32); // a still pool beside it
		for (int y = 20; y <= 40; ++y)
		{
			for (int x = 30; x <= 32; ++x)
			{
				boolean mouth = y <= 21;
				w.mapFlowX[at(x, y)] = 0;
				w.mapFlowZ[at(x, y)] = mouth ? 0 : -0.5f;
			}
		}
		w.solve();
		assertTrue(w.open[at(5, 5)]);
		assertTrue(w.open[at(31, 19)]);
		// where the map's flow dies out the sea begins
		assertTrue(w.open[at(31, 20)]);
		assertFalse(w.open[at(31, 30)]);
		assertFalse(w.open[at(35, 30)]);
	}

	@Test
	public void knownLakesAreNotSea()
	{
		WaterBodies w = grid();
		fill(w, 0, 0, N - 1, 29);
		// a lake running off the edge that looks like the sea: its tiles are known, the sea beyond is not
		for (int y = 0; y <= 29; ++y)
		{
			for (int x = 0; x <= 15; ++x)
			{
				w.inland[at(x, y)] = true;
			}
		}
		w.solve();
		assertFalse(w.open[at(5, 10)]);
		assertFalse(w.open[at(15, 29)]);
		assertTrue(w.open[at(30, 10)]);
		assertTrue(w.open[at(30, 29)]);
	}
}
