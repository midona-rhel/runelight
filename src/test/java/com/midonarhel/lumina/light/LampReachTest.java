package com.midonarhel.lumina.light;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.midonarhel.lumina.visibility.WallMap;
import java.util.Random;
import org.junit.Test;

/**
 * The reach map (LampReach) against the walk it replaces (lamp.glsl lampWallsShareIn on a surface): over random walls,
 * edges and diagonals, from random lamps, at random points in reach. Read as lamp.glsl's lampReachShare reads it, a
 * point the walk finds behind a wall is never lit (light never gets past a wall); and a point the walk lights is dark
 * only in the sliver past a wall's end, under one ray of the map wide.
 */
public class LampReachTest
{
	private static final int SIZE = 48;

	@Test
	public void noPointBehindAWallIsLitAndFewInTheOpenAreDark()
	{
		Random rnd = new Random(7);
		long points = 0, leaks = 0, dark = 0, bakes = 0, bakeNanos = 0;
		for (int scene = 0; scene < 40; ++scene)
		{
			float[] tiles = walls(rnd, 0.12f + rnd.nextFloat() * 0.2f, 0.03f);
			for (int lamp = 0; lamp < 10; ++lamp)
			{
				float ox = 12 + rnd.nextFloat() * (SIZE - 24), oz = 12 + rnd.nextFloat() * (SIZE - 24);
				if (onAWall(tiles, ox, oz))
				{
					continue;
				}
				float reach = 10;
				float[] lines = new float[LampReach.BINS * 2];
				long start = System.nanoTime();
				LampReach.bake(tiles, SIZE, 0, ox, oz, reach, lines);
				bakeNanos += System.nanoTime() - start;
				bakes++;
				for (int k = 0; k < 2000; ++k)
				{
					double a = rnd.nextDouble() * 2 * Math.PI, r = Math.sqrt(rnd.nextDouble()) * reach;
					float px = ox + (float) (Math.cos(a) * r), pz = oz + (float) (Math.sin(a) * r);
					if (nearALine(px, pz))
					{
						// on a wall's line itself (a surface is read off it, LAMP_WALL_BIAS): either side is right
						continue;
					}
					boolean walked = walkBlocked(tiles, px, pz, ox, oz);
					boolean read = readBlocked(lines, ox, oz, px, pz);
					points++;
					leaks += walked && !read ? 1 : 0;
					dark += !walked && read ? 1 : 0;
				}
			}
		}
		System.out.printf("LAMP REACH %d points: %d lit behind a wall, %d dark in the open (%.3f %%); %d bakes, %.3f ms each%n",
			points, leaks, dark, 100.0 * dark / points, bakes, bakeNanos / 1e6 / bakes);
		assertEquals("points the walk finds behind a wall that the map lights", 0, leaks);
		assertTrue("points in the open the map darkens: " + dark + " of " + points, dark < points / 200);
	}

	/** Random walls: tile edges (in both tiles' bits, as LampWalls packs them) and diagonals, on storey 0. */
	private static float[] walls(Random rnd, float edges, float diagonals)
	{
		int[] bits = new int[SIZE * SIZE];
		for (int y = 1; y < SIZE - 1; ++y)
		{
			for (int x = 1; x < SIZE - 1; ++x)
			{
				// runs of wall along x and along y, as buildings have them
				if (rnd.nextFloat() < edges)
				{
					int run = 1 + rnd.nextInt(5);
					for (int i = 0; i < run && x + i < SIZE - 1; ++i)
					{
						bits[y * SIZE + x + i] |= WallMap.NORTH;
						bits[(y + 1) * SIZE + x + i] |= WallMap.SOUTH;
					}
				}
				if (rnd.nextFloat() < edges)
				{
					int run = 1 + rnd.nextInt(5);
					for (int i = 0; i < run && y + i < SIZE - 1; ++i)
					{
						bits[(y + i) * SIZE + x] |= WallMap.EAST;
						bits[(y + i) * SIZE + x + 1] |= WallMap.WEST;
					}
				}
				if (rnd.nextFloat() < diagonals)
				{
					bits[y * SIZE + x] |= rnd.nextBoolean() ? WallMap.DIAG_SW_NE : WallMap.DIAG_NW_SE;
				}
			}
		}
		float[] tiles = new float[SIZE * SIZE];
		for (int i = 0; i < tiles.length; ++i)
		{
			tiles[i] = bits[i];
		}
		return tiles;
	}

	private static boolean nearALine(float x, float z)
	{
		float fx = x - (float) Math.floor(x), fz = z - (float) Math.floor(z);
		float e = 1e-3f;
		return fx < e || fx > 1 - e || fz < e || fz > 1 - e || Math.abs(fx - fz) < e || Math.abs(fx + fz - 1) < e;
	}

	private static boolean onAWall(float[] tiles, float x, float z)
	{
		return ((int) tiles[(int) z * SIZE + (int) x] & (WallMap.DIAG_SW_NE | WallMap.DIAG_NW_SE)) != 0;
	}

	/** lamp.glsl lampWallsShareIn on a surface: the line from the point to the lamp's origin, tile by tile. */
	private static boolean walkBlocked(float[] tiles, float ax, float az, float bx, float bz)
	{
		float dx = bx - ax, dz = bz - az;
		int tx = (int) Math.floor(ax), tz = (int) Math.floor(az);
		int ex = (int) Math.floor(bx), ez = (int) Math.floor(bz);
		int sx = (int) Math.signum(dx), sz = (int) Math.signum(dz);
		float deltaX = sx != 0 ? 1 / Math.abs(dx) : 1e30f, deltaZ = sz != 0 ? 1 / Math.abs(dz) : 1e30f;
		float maxX = sx > 0 ? (tx + 1 - ax) * deltaX : sx < 0 ? (ax - tx) * deltaX : 1e30f;
		float maxZ = sz > 0 ? (tz + 1 - az) * deltaZ : sz < 0 ? (az - tz) * deltaZ : 1e30f;
		int steps = Math.abs(ex - tx) + Math.abs(ez - tz);
		float enter = 0;
		for (int i = 0; i <= steps; ++i)
		{
			int bits = tx >= 0 && tz >= 0 && tx < SIZE && tz < SIZE ? (int) tiles[tz * SIZE + tx] & 63 : 0;
			float exit = Math.min(Math.min(maxX, maxZ), 1);
			if (diagonal(bits, tx, tz, ax, az, dx, dz, enter, exit))
			{
				return true;
			}
			boolean alongX = maxX < maxZ;
			if (i < steps && (bits & (alongX ? (sx > 0 ? WallMap.EAST : WallMap.WEST) : (sz > 0 ? WallMap.NORTH : WallMap.SOUTH))) != 0)
			{
				return true;
			}
			if (alongX)
			{
				tx += sx;
				enter = maxX;
				maxX += deltaX;
			}
			else
			{
				tz += sz;
				enter = maxZ;
				maxZ += deltaZ;
			}
		}
		return false;
	}

	private static boolean diagonal(int bits, int tx, int tz, float ax, float az, float dx, float dz, float t0, float t1)
	{
		if ((bits & (WallMap.DIAG_SW_NE | WallMap.DIAG_NW_SE)) == 0 || t1 <= t0)
		{
			return false;
		}
		float x0 = ax + dx * t0 - tx, z0 = az + dz * t0 - tz, x1 = ax + dx * t1 - tx, z1 = az + dz * t1 - tz;
		return (bits & WallMap.DIAG_SW_NE) != 0 && (z0 - x0) * (z1 - x1) < 0
			|| (bits & WallMap.DIAG_NW_SE) != 0 && (x0 + z0 - 1) * (x1 + z1 - 1) < 0;
	}

	/** lamp.glsl lampReachShare on a surface: the first walls of the rays either side, as lines. */
	private static boolean readBlocked(float[] lines, float ox, float oz, float px, float pz)
	{
		float vx = px - ox, vz = pz - oz;
		double f = Math.atan2(vz, vx) * (LampReach.BINS / (2 * Math.PI)) - 0.5;
		int r0 = (int) Math.floor(f);
		r0 = r0 < 0 ? r0 + LampReach.BINS : r0;
		int r1 = r0 + 1 < LampReach.BINS ? r0 + 1 : 0;
		return cross(lines[r0 * 2], ox, oz, vx, vz) < 1 || cross(lines[r1 * 2], ox, oz, vx, vz) < 1;
	}

	private static float cross(float id, float ox, float oz, float vx, float vz)
	{
		if (id < 0.5f)
		{
			return 2;
		}
		int k = (int) (id + 0.5f) - 1;
		int kind = k & 3;
		float c = (k >> 2) - LampReach.C_OFF;
		float num = kind == 0 ? c - ox : kind == 1 ? c - oz : kind == 2 ? c - (ox - oz) : c - (ox + oz);
		float den = kind == 0 ? vx : kind == 1 ? vz : kind == 2 ? vx - vz : vx + vz;
		if (Math.abs(den) < 1e-6f)
		{
			return 2;
		}
		float t = num / den;
		return t > 0 ? t : 2;
	}
}
