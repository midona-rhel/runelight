package com.midonarhel.runelight;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/** How long 300 lights of about 7 tiles' radius take to bake in a full-size scene full of buildings. */
public class LightMapTimingTest
{
	private static final int SIZE = 184, OFFSET = 40, LOCAL = 104;

	// a barrel: a box 20 units wide in the cell, up to 90 high
	private static final byte[] BARREL = {6, 6, 26, 26, 6, 6, 26, 26, 6, 6, 26, 26, 6, 6, 26, 26};

	private static LightBlockers town(Random rnd)
	{
		int[][][] flags = new int[4][LOCAL][LOCAL];
		float[] floors = new float[4 * SIZE * SIZE];
		byte[] filled = new byte[SIZE * SIZE];
		for (int p = 0; p < 4; ++p)
		{
			java.util.Arrays.fill(floors, p * SIZE * SIZE, (p + 1) * SIZE * SIZE, -240f * p);
		}
		java.util.Arrays.fill(filled, (byte) 1);
		ModelBlockers models = new ModelBlockers(SIZE * 4);
		for (int b = 0; b < 40; ++b)
		{
			int w = 6 + rnd.nextInt(8), h = 6 + rnd.nextInt(6);
			int x0 = 4 + rnd.nextInt(LOCAL - w - 8), y0 = 4 + rnd.nextInt(LOCAL - h - 8);
			boolean upstairs = rnd.nextBoolean();
			for (int x = x0; x <= x0 + w; ++x)
			{
				for (int y = y0; y <= y0 + h; ++y)
				{
					boolean edge = x == x0 || x == x0 + w || y == y0 || y == y0 + h;
					if (upstairs)
					{
						filled[(x + OFFSET) * SIZE + y + OFFSET] |= 2;
					}
					if (edge)
					{
						int door = (x + y) % 7 == 0 ? 0 : 1;
						if (door == 1)
						{
							if (x == x0)
							{
								flags[0][x][y] |= WallMap.LOS_WEST;
								if (x > 0) flags[0][x - 1][y] |= WallMap.LOS_EAST;
							}
							else if (x == x0 + w)
							{
								flags[0][x][y] |= WallMap.LOS_EAST;
								if (x + 1 < LOCAL) flags[0][x + 1][y] |= WallMap.LOS_WEST;
							}
							if (y == y0)
							{
								flags[0][x][y] |= WallMap.LOS_SOUTH;
								if (y > 0) flags[0][x][y - 1] |= WallMap.LOS_NORTH;
							}
							else if (y == y0 + h)
							{
								flags[0][x][y] |= WallMap.LOS_NORTH;
								if (y + 1 < LOCAL) flags[0][x][y + 1] |= WallMap.LOS_SOUTH;
							}
						}
					}
				}
			}
			// a partition with a door, a solid footprint and a few thin models
			int px = x0 + w / 2;
			for (int y = y0; y <= y0 + h; ++y)
			{
				if (y != y0 + 2)
				{
					flags[0][px][y] |= WallMap.LOS_WEST;
					flags[0][px - 1][y] |= WallMap.LOS_EAST;
				}
			}
			flags[0][x0 + 2][y0 + h - 2] |= WallMap.LOS_FULL;
			flags[0][x0 + 3][y0 + h - 2] |= WallMap.LOS_FULL;
			for (int k = 0; k < 6; ++k)
			{
				int gx = (OFFSET + x0 + rnd.nextInt(w)) * 4 + rnd.nextInt(4), gz = (OFFSET + y0 + rnd.nextInt(h)) * 4 + rnd.nextInt(4);
				models.set(0, gx, gz, (short) 0x3f, BARREL, 0);
			}
		}
		WallMap walls = WallMap.build(flags, null, SIZE, OFFSET, 1);
		return new LightBlockers(walls, models, floors, filled);
	}

	@Test
	public void threeHundredLightsBake()
	{
		Random rnd = new Random(7);
		LightBlockers blockers = town(rnd);
		LightMapBake.Scratch scratch = new LightMapBake.Scratch();
		LightMapBake.Light[] lights = new LightMapBake.Light[300];
		for (int i = 0; i < lights.length; ++i)
		{
			int plane = i % 10 < 2 ? 1 : 0;
			float tx = OFFSET + 8 + rnd.nextFloat() * 88, ty = OFFSET + 8 + rnd.nextFloat() * 88;
			float radius = (3 + rnd.nextFloat() * 8) * 128;
			float height = rnd.nextFloat() < 0.3f ? 150 : 40;
			lights[i] = new LightMapBake.Light(plane, (tx - OFFSET) * 128, -plane * 240f - height, (ty - OFFSET) * 128, radius);
		}
		// warm up
		for (int i = 0; i < 20; ++i)
		{
			LightMapBake.bake(lights[i], blockers, scratch);
		}
		long visits = 0, cells = 0;
		long start = System.nanoTime();
		double worst = 0;
		for (LightMapBake.Light l : lights)
		{
			long t = System.nanoTime();
			LightMapBake.Result r = LightMapBake.bake(l, blockers, scratch);
			worst = Math.max(worst, (System.nanoTime() - t) / 1e6);
			visits += r.cellVisits;
			for (LightMapBake.Grid g : r.grids)
			{
				cells += g.c.length;
			}
		}
		double ms = (System.nanoTime() - start) / 1e6;
		System.out.printf("Light map: 300 lights, radius %.1f tiles on average, baked in %.0f ms on one thread (%.2f ms a light, worst %.1f ms), "
			+ "%d cell visits, %d cells kept (%.1f MB)%n", 7.0, ms, ms / 300, worst, visits, cells, cells * 4 / 1e6);
		assertTrue("took " + ms + " ms", ms < 30_000);
	}

	@Test
	public void blockersOfAZoneAreQuickToMake()
	{
		// a zone of about twenty thousand triangles, a third of them near the band
		Random rnd = new Random(3);
		VoxelCapture cap = new VoxelCapture();
		float[] floors = new float[4 * 64];
		for (int p = 0; p < 4; ++p)
		{
			java.util.Arrays.fill(floors, p * 64, p * 64 + 64, -240f * p);
		}
		int n = 20000;
		for (int t = 0; t < n; ++t)
		{
			int x = rnd.nextInt(1024), z = rnd.nextInt(1024), y = -rnd.nextInt(300);
			cap.add(x, y, z, x + 40, y, z, x, y - 30, z + 40, false);
		}
		ModelBlockers.rasterize(cap, floors);
		long start = System.nanoTime();
		ModelBlockers.Zone zone = null;
		for (int i = 0; i < 10; ++i)
		{
			zone = ModelBlockers.rasterize(cap, floors);
		}
		double ms = (System.nanoTime() - start) / 1e7;
		System.out.printf("Model blockers: %d triangles of a zone in %.1f ms, %d cells%n", n, ms, zone.keys.length);
		assertTrue(ms < 2000);
	}
}
