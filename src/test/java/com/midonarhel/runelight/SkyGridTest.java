package com.midonarhel.runelight;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SkyGridTest
{
	private static final int N = SkyGrid.SIZE * 2;
	// world y -640 up to 128 down: ground at 0 fills layer 10
	private static final int LAYER0 = -10, LAYERS = 12;
	private static final int GROUND = 10;
	private static final int CELL = GiGrid.pack(1, 0.3f, 0.3f, 0.3f, 0, -1, 0);
	private static final int UP = 0, PLUS_X = 1, MINUS_X = 2, PLUS_Z = 3, MINUS_Z = 4;

	private static GiGrid flat()
	{
		GiGrid g = new GiGrid(N, LAYERS, N, LAYER0);
		for (int z = 0; z < N; ++z)
		{
			for (int x = 0; x < N; ++x)
			{
				g.cells[g.index(x, GROUND, z)] = CELL;
			}
		}
		return g;
	}

	/** Fill voxels x0..x1, z0..z1 (inclusive) between world heights top and bottom above the ground (y up here). */
	private static void box(GiGrid g, int x0, int z0, int x1, int z1, int bottom, int top)
	{
		for (int y = -top / GiGrid.VOXEL; y < -bottom / GiGrid.VOXEL; ++y)
		{
			for (int z = z0; z <= z1; ++z)
			{
				for (int x = x0; x <= x1; ++x)
				{
					g.cells[g.index(x, y - LAYER0, z)] = CELL;
				}
			}
		}
	}

	private static SkyGrid traced(GiGrid g, WallMap walls)
	{
		SkyGrid s = new SkyGrid();
		s.setWalls(walls);
		s.setGrid(g, new int[1][SkyGrid.CORNERS][SkyGrid.CORNERS], 0, 0, 0, false);
		while (s.dirtyCount() > 0)
		{
			s.trace(1 << 20, 50, 50);
		}
		return s;
	}

	/** Lobe of the value tile (tx, tz) keeps at its corner (i, j) (1 east, north), at a height of the ground floor. */
	private static float value(SkyGrid s, int tx, int tz, int i, int j, int height, int lobe)
	{
		int x = (lobe == MINUS_Z ? 2 * SkyGrid.SIZE : 0) + 2 * tx + i, y = 2 * tz + j;
		int t = (((1 + height) * SkyGrid.TEX_H + y) * SkyGrid.TEX_W + x) * 4 + (lobe == MINUS_Z ? 0 : lobe);
		return GlobalIllumination.fromHalf(s.texels.get(t));
	}

	// a closed 4x4 house on tiles 50..53 with walls on its edges (their voxels just inside), a door in the middle of
	// the south wall at tile 51 and a flat roof at 256
	private static GiGrid house()
	{
		GiGrid g = flat();
		box(g, 100, 100, 100, 107, 0, 256);
		box(g, 107, 100, 107, 107, 0, 256);
		box(g, 100, 107, 107, 107, 0, 256);
		box(g, 100, 100, 101, 100, 0, 256);
		box(g, 104, 100, 107, 100, 0, 256);
		// over the door
		box(g, 102, 100, 103, 100, 192, 256);
		box(g, 100, 100, 107, 107, 256, 320);
		return g;
	}

	private static WallMap houseWalls()
	{
		int[][][] f = new int[1][SkyGrid.SIZE][SkyGrid.SIZE];
		for (int k = 50; k <= 53; ++k)
		{
			f[0][50][k] |= WallMap.LOS_WEST;
			f[0][53][k] |= WallMap.LOS_EAST;
			f[0][k][53] |= WallMap.LOS_NORTH;
			if (k != 51)
			{
				f[0][k][50] |= WallMap.LOS_SOUTH;
			}
		}
		return WallMap.build(f, null, SkyGrid.SIZE, 0, 1);
	}

	@Test
	public void closedRoomWithADoor()
	{
		SkyGrid s = traced(house(), houseWalls());
		// inside, the floor sees no sky overhead
		assertTrue(value(s, 52, 52, 0, 0, 0, UP) < 0.05f);
		// out in the open everything sees all of it
		for (int lobe = 0; lobe < SkyGrid.LOBES; ++lobe)
		{
			assertEquals(1, value(s, 20, 20, 0, 0, 1, lobe), 0.02f);
		}
		// the west wall: outside (tile 49's east corners) it sees the sky to the west, inside (tile 50's west ones)
		// nothing
		assertTrue(value(s, 49, 52, 1, 0, 1, MINUS_X) > 0.6f);
		assertTrue(value(s, 50, 52, 0, 0, 1, PLUS_X) < 0.05f);
		// the doorway lets a little in sideways, low down by the door
		assertTrue(value(s, 51, 50, 0, 0, 0, MINUS_Z) > 0);
	}

	@Test
	public void overhangLetsTheSkyInSideways()
	{
		// a wall on the edge x = 60 tiles (its voxels on the east side) from z 80 to 100, 320 high, with eaves one tile
		// deep over the west side at 192..256
		GiGrid g = flat();
		box(g, 120, 160, 120, 201, 0, 320);
		box(g, 118, 160, 119, 201, 192, 256);
		int[][][] f = new int[1][SkyGrid.SIZE][SkyGrid.SIZE];
		for (int k = 80; k <= 100; ++k)
		{
			f[0][59][k] |= WallMap.LOS_EAST;
		}
		SkyGrid s = traced(g, WallMap.build(f, null, SkyGrid.SIZE, 0, 1));
		// at the wall under the eaves, halfway up: the sky shows under their edge
		float side = value(s, 59, 90, 1, 0, 1, MINUS_X);
		float up = value(s, 59, 90, 1, 0, 1, UP);
		assertTrue("side " + side, side > 0.4f);
		assertTrue("up " + up, up < 0.2f);
	}

	@Test
	public void wallsSeparateQuadrants()
	{
		SkyGrid s = traced(house(), houseWalls());
		// the corner (50, 52) on the west wall: the quadrants either side of it differ
		for (int lobe = 0; lobe < SkyGrid.LOBES; ++lobe)
		{
			if (lobe != PLUS_X)
			{
				assertNotEquals(value(s, 49, 52, 1, 0, 1, lobe), value(s, 50, 52, 0, 0, 1, lobe), 0.05f);
			}
			// along it, on the same side, they are one
			assertEquals(value(s, 49, 52, 1, 0, 1, lobe), value(s, 49, 51, 1, 1, 1, lobe), 0);
			assertEquals(value(s, 50, 52, 0, 0, 1, lobe), value(s, 50, 51, 0, 1, 1, lobe), 0);
			// out in the open all four around a corner are one
			assertEquals(value(s, 20, 20, 0, 0, 1, lobe), value(s, 19, 19, 1, 1, 1, lobe), 0);
			assertEquals(value(s, 20, 20, 0, 0, 1, lobe), value(s, 19, 20, 1, 0, 1, lobe), 0);
		}
		// the doorway joins the inside and the outside: corner (52, 50) east of the door
		assertEquals(value(s, 51, 49, 1, 1, 0, UP), value(s, 51, 50, 1, 0, 0, UP), 0);
	}

	@Test
	public void diagonalWallsSplitTheirTile()
	{
		// a "/" wall across tile (30, 30), its voxels on the diagonal, a roof over the north-west half
		GiGrid g = flat();
		box(g, 60, 60, 60, 60, 0, 256);
		box(g, 61, 61, 61, 61, 0, 256);
		box(g, 56, 61, 60, 66, 192, 256);
		java.util.List<WallMap.Diagonal> d = new java.util.ArrayList<>();
		d.add(new WallMap.Diagonal(0, 30, 30, WallMap.DIAG_SW_NE));
		int[][][] f = new int[1][SkyGrid.SIZE][SkyGrid.SIZE];
		f[0][30][30] = WallMap.LOS_FULL;
		SkyGrid s = traced(g, WallMap.build(f, d, SkyGrid.SIZE, 0, 1));
		// the north corners are the north-west half's, under the roof; the south ones the open half's
		float north = value(s, 30, 30, 0, 1, 0, UP), south = value(s, 30, 30, 1, 0, 0, UP);
		assertTrue("north " + north + " south " + south, north < 0.4f && south > 0.6f);
		// the tile's diagonal on the ground floor is in its corner's meta texels
		int t = ((2 * 30) * SkyGrid.TEX_W + 2 * 30) * 4 + 3;
		assertEquals(1, GlobalIllumination.fromHalf(s.texels.get(t)), 0);
	}

	@Test
	public void movingKeepsTheValuesInPlace()
	{
		SkyGrid s = traced(house(), houseWalls());
		float inside = value(s, 52, 52, 0, 0, 0, UP);
		// the next region: the same house 8 tiles west and south in the new base's coordinates
		GiGrid g = flat();
		s.setGrid(g, new int[1][SkyGrid.CORNERS][SkyGrid.CORNERS], 0, 8, 8, false);
		assertEquals(inside, value(s, 44, 44, 0, 0, 0, UP), 0);
		// what came in at the far edge is open sky until traced
		assertEquals(1, value(s, SkyGrid.SIZE - 1, 30, 0, 0, 0, UP), 0);
		assertTrue(s.dirtyCount() > 0);
		int[] all = s.takeChanges();
		assertEquals(1, all[6]);
	}

	@Test
	public void changedVoxelsTraceAgainAroundThem()
	{
		SkyGrid s = traced(house(), houseWalls());
		GiGrid g = house();
		// the door gets a roof over the porch
		box(g, 96, 92, 109, 99, 256, 320);
		s.setGrid(g, new int[1][SkyGrid.CORNERS][SkyGrid.CORNERS], 0, 0, 0, false);
		int dirty = s.dirtyCount();
		assertTrue(dirty > 0 && dirty < SkyGrid.LEVELS * SkyGrid.CORNERS * SkyGrid.CORNERS / 4);
		s.trace(1 << 20, 51, 49);
		assertTrue(value(s, 51, 49, 0, 0, 0, UP) < 0.5f);
	}

	@Test
	public void shaderMatchesTheLayout() throws Exception
	{
		String glsl = new String(Files.readAllBytes(Paths.get("src/main/resources/com/midonarhel/runelight/skylight_ao.glsl")));
		assertEquals(SkyGrid.SIZE, define(glsl, "SKY_GRID_TILES"), 0);
		assertEquals(SkyGrid.TEX_D, define(glsl, "SKY_GRID_D"), 0);
		assertEquals(SkyGrid.HEIGHTS[0], define(glsl, "SKY_GRID_LOW"), 0);
		assertEquals(SkyGrid.HEIGHTS[1] - SkyGrid.HEIGHTS[0], define(glsl, "SKY_GRID_STEP"), 0);
		assertEquals(SkyGrid.HEIGHTS[2] - SkyGrid.HEIGHTS[1], define(glsl, "SKY_GRID_STEP"), 0);
	}

	private static float define(String glsl, String name)
	{
		Matcher m = Pattern.compile("#define " + name + " ([0-9.]+)").matcher(glsl);
		assertTrue(name, m.find());
		return Float.parseFloat(m.group(1));
	}

	@Test
	public void workerCost()
	{
		// a busy scene: houses of random size every few tiles, trees, hills
		Random r = new Random(7);
		GiGrid g = new GiGrid(N, 24, N, -20);
		for (int z = 0; z < N; ++z)
		{
			for (int x = 0; x < N; ++x)
			{
				g.cells[g.index(x, 20 - (int) (2 + Math.sin(x / 17.0) + Math.cos(z / 23.0)), z)] = CELL;
			}
		}
		for (int k = 0; k < 400; ++k)
		{
			int x = r.nextInt(N - 12), z = r.nextInt(N - 12), w = 4 + r.nextInt(8), h = 4 + r.nextInt(8);
			int top = 3 + r.nextInt(6);
			for (int y = 20 - top; y < 20; ++y)
			{
				for (int i = 0; i <= w; ++i)
				{
					for (int j = 0; j <= h; ++j)
					{
						if (i == 0 || j == 0 || i == w || j == h || y == 20 - top)
						{
							g.cells[g.index(x + i, y, z + j)] = CELL;
						}
					}
				}
			}
		}
		// warm up the JIT on one, time another
		SkyGrid warm = new SkyGrid();
		warm.setGrid(g, new int[1][SkyGrid.CORNERS][SkyGrid.CORNERS], 0, 0, 0, false);
		for (int i = 0; i < 100; ++i)
		{
			warm.trace(SkyGridMap.RAY_BUDGET, 20, 20);
		}
		SkyGrid s = new SkyGrid();
		s.setGrid(g, new int[1][SkyGrid.CORNERS][SkyGrid.CORNERS], 0, 0, 0, false);
		s.takeChanges();
		int jobs = 0, near = 0, uploads = 0;
		long rays = 0, bytes = 0, nanos = 0;
		int[] box = null;
		while (s.dirtyCount() > 0)
		{
			long start = System.nanoTime();
			rays += s.trace(SkyGridMap.RAY_BUDGET, 52, 52);
			nanos += System.nanoTime() - start;
			jobs++;
			if (near == 0 && s.clearRings() >= SkyGridMap.READY_RINGS)
			{
				near = jobs;
			}
			// the map's uploads: the box of what changed over UPLOAD_FRAMES jobs
			int[] c = s.takeChanges();
			if (c != null)
			{
				box = box == null ? c : new int[]{Math.min(box[0], c[0]), Math.min(box[1], c[1]), Math.max(box[2], c[2]),
					Math.max(box[3], c[3]), Math.min(box[4], c[4]), Math.max(box[5], c[5])};
			}
			if (box != null && (jobs % SkyGridMap.UPLOAD_FRAMES == 0 || s.dirtyCount() == 0))
			{
				bytes += 2L * (box[2] - box[0] + 1) * 2 * (box[3] - box[1] + 1) * 2 * (box[5] - box[4] + 1) * 8;
				uploads++;
				box = null;
			}
		}
		double ms = nanos / 1e6;
		System.out.printf("sky grid: %.3f ms a job of %d rays (%.0f ns a ray); %d jobs for the %d rings around the player,"
				+ " %d for everything; uploads of %d KB on average%n", ms / jobs, SkyGridMap.RAY_BUDGET, ms * 1e6 / rays,
			near, SkyGridMap.READY_RINGS, jobs, bytes / Math.max(uploads, 1) / 1024);
		assertTrue(rays > 0);
	}
}
