package com.midonarhel.runelight;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
	private static final int LOW = 0, HIGH = 1;
	private static final int[][][] HEIGHTS = new int[1][SkyGrid.SIZE + 1][SkyGrid.SIZE + 1];

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
		s.setGrid(g, HEIGHTS, 0, 0, 0, false, null);
		while (s.dirtyCount() > 0)
		{
			s.trace(1 << 20, 50, 50);
		}
		return s;
	}

	private static int texel(int x, int z, int slice)
	{
		return ((slice * SkyGrid.TEX_H + z) * SkyGrid.TEX_W + x) * 4;
	}

	/** The harmonics (a, bx, by, bz) of the probe of a slot of tile (tx, tz), in a half of it. */
	private static float[] probe(SkyGrid s, int tx, int tz, int slot, int half)
	{
		int t = texel(2 * tx + half, tz, SkyGrid.PROBE_SLICE + slot);
		float[] sh = new float[4];
		for (int c = 0; c < 4; ++c)
		{
			sh[c] = GlobalIllumination.fromHalf(s.texels.get(t + c));
		}
		return sh;
	}

	/** What a surface facing n sees from a probe, as skyGridShade in skylight_ao.glsl. */
	private static float shade(float[] sh, float nx, float ny, float nz)
	{
		float my = Math.min(ny, 0);
		return Math.max(0, Math.min(1, (sh[0] + 2 * (nx * sh[1] + my * sh[2] + nz * sh[3])) / (1 - my)));
	}

	private static float up(SkyGrid s, int tx, int tz, int slot)
	{
		return shade(probe(s, tx, tz, slot, 0), 0, -1, 0);
	}

	/** What the quadrant (east, north) of a tile may blend with on the ground floor: the link bits of SkyGrid.link. */
	private static int link(SkyGrid s, int tx, int tz, boolean east, boolean north)
	{
		int q = (east ? 1 : 0) + (north ? 2 : 0);
		return (int) GlobalIllumination.fromHalf(s.texels.get(texel(2 * tx, tz, SkyGrid.LINK_SLICE + q)));
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
		assertTrue(up(s, 52, 52, LOW) < 0.05f);
		// open sky has no mark: far from any geometry the high probe is not traced and holds the open sky
		assertFalse(s.active[(HIGH * SkyGrid.SIZE + 20) * SkyGrid.SIZE + 20]);
		assertEquals(1, up(s, 20, 20, HIGH), 0);
		assertEquals(1, shade(probe(s, 20, 20, HIGH, 0), 1, 0, 0), 0);
		assertEquals(1, shade(probe(s, 20, 20, HIGH, 0), 0, 1, 0), 0);
		// the west wall: outside (tile 49) its face sees the sky to the west, inside (tile 50) nothing
		assertTrue(shade(probe(s, 49, 52, LOW, 0), -1, 0, 0) > 0.6f);
		assertTrue(shade(probe(s, 50, 52, LOW, 0), 1, 0, 0) < 0.12f);
		// the doorway lets a little in sideways, low down by the door
		assertTrue(shade(probe(s, 51, 50, LOW, 0), 0, 0, -1) > 0);
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
		// at the wall under the eaves: the sky shows under their edge
		float[] sh = probe(s, 59, 90, LOW, 0);
		float side = shade(sh, -1, 0, 0), up = shade(sh, 0, -1, 0);
		assertTrue("side " + side, side > 0.4f);
		assertTrue("up " + up, up < 0.4f && up < side - 0.2f);
	}

	@Test
	public void wallsSeparateProbes()
	{
		SkyGrid s = traced(house(), houseWalls());
		// the west wall: the tiles either side of it differ, and neither blends with the other
		assertNotEquals(up(s, 49, 52, LOW), up(s, 50, 52, LOW), 0.5f);
		assertEquals(0, link(s, 49, 52, true, true) & 1);
		assertEquals(0, link(s, 50, 52, false, true) & 1);
		// along the wall they do, and so the diagonal ones of the tile inside the house
		assertEquals(2, link(s, 49, 52, true, true) & 2);
		assertEquals(3, link(s, 49, 52, false, false) & 3);
		assertEquals(2, link(s, 49, 52, true, true) & 7);
		// the house's south-west tile (50, 50) is shut in on its south and west sides, and has no diagonal way out either
		assertEquals(0, link(s, 50, 50, false, false) & 7);
		// out in the open all around a tile are one, and their values equal
		assertEquals(7, link(s, 20, 20, true, true) & 7);
		for (int[] d : new int[][]{{1, 0}, {0, 1}, {1, 1}, {-1, -1}})
		{
			float[] a = probe(s, 20, 20, LOW, 0), b = probe(s, 20 + d[0], 20 + d[1], LOW, 0);
			for (int c = 0; c < 4; ++c)
			{
				assertEquals(a[c], b[c], 0);
			}
		}
		// the doorway joins the inside and the outside: the tile before the door and the one inside it
		assertEquals(2, link(s, 51, 49, true, true) & 2);
		assertEquals(0, link(s, 52, 49, true, true) & 2);
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
		// the north-west half is under the roof, the south-east one in the open
		float north = shade(probe(s, 30, 30, LOW, 0), 0, -1, 0), south = shade(probe(s, 30, 30, LOW, 1), 0, -1, 0);
		assertTrue("north " + north + " south " + south, north < 0.25f && south > 0.35f);
		// the tile's diagonal on the ground floor is in its link texels, and a half reaches only its own side's neighbours:
		// the north-west half has the west and north ones, and the south-east half the east and south ones
		assertEquals(1, GlobalIllumination.fromHalf(s.texels.get(texel(2 * 30, 30, SkyGrid.LINK_SLICE) + 3)), 0);
		int northWest = (int) GlobalIllumination.fromHalf(s.texels.get(texel(2 * 30, 30, SkyGrid.LINK_SLICE + 2)));
		int northEast = (int) GlobalIllumination.fromHalf(s.texels.get(texel(2 * 30, 30, SkyGrid.LINK_SLICE + 3)));
		int southEast = (int) GlobalIllumination.fromHalf(s.texels.get(texel(2 * 30 + 1, 30, SkyGrid.LINK_SLICE + 3)));
		// quadrant 2 is west and north: along x to the west tile, along z to the north one
		assertEquals(3, northWest & 3);
		// the north-east quadrant of the north-west half: only the north tile, no east one
		assertEquals(2, northEast & 3);
		// the south-east half in the same quadrant: only the east tile
		assertEquals(1, southEast & 3);
	}

	@Test
	public void floorsSeparateStoreys()
	{
		// a two storey house on tiles 50..53 with walls round it up to 400 and a floor over the ground storey at 240
		// (plane 1), open to the sky above
		GiGrid g = flat();
		box(g, 100, 100, 107, 107, 224, 256);
		box(g, 100, 100, 100, 107, 0, 400);
		box(g, 107, 100, 107, 107, 0, 400);
		box(g, 100, 100, 107, 100, 0, 400);
		box(g, 100, 107, 107, 107, 0, 400);
		int[][][] heights = new int[2][SkyGrid.SIZE + 1][SkyGrid.SIZE + 1];
		for (int[] column : heights[1])
		{
			java.util.Arrays.fill(column, -240);
		}
		byte[] floors = new byte[SkyGrid.SIZE * SkyGrid.SIZE];
		for (int z = 50; z <= 53; ++z)
		{
			for (int x = 50; x <= 53; ++x)
			{
				floors[x * SkyGrid.SIZE + z] = 2;
			}
		}
		SkyGrid s = new SkyGrid();
		s.setWalls(WallMap.empty(SkyGrid.SIZE, 0, 1));
		s.setGrid(g, heights, 0, 0, 0, false, floors);
		while (s.dirtyCount() > 0)
		{
			s.trace(1 << 20, 50, 50);
		}
		// the ground storey is shut in by the floor above it, the upper one is open to the sky: the floor keeps the two
		// apart
		int upper = SkyGrid.HEIGHTS.length;
		assertTrue(up(s, 51, 51, LOW) < 0.05f && up(s, 51, 51, HIGH) < 0.05f);
		assertTrue(up(s, 51, 51, upper) > 0.5f);
		// the floor's height is stored for the tile and the storey that isn't there has none
		int heightsAt = texel(2 * 51, 51, SkyGrid.HEIGHT_SLICE);
		assertEquals(-240, GlobalIllumination.fromHalf(s.texels.get(heightsAt + 1)), 1);
		assertEquals(SkyGrid.NO_FLOOR, GlobalIllumination.fromHalf(s.texels.get(heightsAt + 2)), 100);
		// outside the house no tile has a floor on the upper plane: no probes there
		assertFalse(s.active[(upper * SkyGrid.SIZE + 40) * SkyGrid.SIZE + 40]);
		assertTrue(s.active[(upper * SkyGrid.SIZE + 51) * SkyGrid.SIZE + 51]);
	}

	@Test
	public void movingKeepsTheValuesInPlace()
	{
		SkyGrid s = traced(house(), houseWalls());
		float inside = up(s, 52, 52, LOW);
		// the next region: the same house 8 tiles west and south in the new base's coordinates
		GiGrid g = flat();
		s.setGrid(g, HEIGHTS, 0, 8, 8, false, null);
		assertEquals(inside, up(s, 44, 44, LOW), 0);
		// what came in at the far edge is open sky until traced
		assertEquals(1, up(s, SkyGrid.SIZE - 1, 30, LOW), 0);
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
		s.setGrid(g, HEIGHTS, 0, 0, 0, false, null);
		int dirty = s.dirtyCount();
		assertTrue(dirty > 0 && dirty < SkyGrid.SLOTS * SkyGrid.SIZE * SkyGrid.SIZE / 4);
		s.trace(1 << 20, 51, 49);
		assertTrue(up(s, 51, 49, LOW) < 0.5f);
	}

	@Test
	public void shaderMatchesTheLayout() throws Exception
	{
		String glsl = new String(Files.readAllBytes(Paths.get("src/main/resources/com/midonarhel/runelight/skylight_ao.glsl")));
		assertEquals(SkyGrid.SIZE, define(glsl, "SKY_GRID_TILES"), 0);
		assertEquals(SkyGrid.TILE, define(glsl, "SKY_GRID_TILE"), 0);
		assertEquals(SkyGrid.LINK_SLICE, define(glsl, "SKY_GRID_LINKS"), 0);
		assertEquals(SkyGrid.PROBE_SLICE, define(glsl, "SKY_GRID_PROBES"), 0);
		assertEquals(SkyGrid.SURFACE, define(glsl, "SKY_GRID_SURFACE"), 0);
		assertEquals(SkyGrid.HEIGHTS[0], define(glsl, "SKY_GRID_LOW"), 0);
		assertEquals(SkyGrid.HEIGHTS[1], define(glsl, "SKY_GRID_HIGH"), 0);
		assertEquals(2, SkyGrid.HEIGHTS.length);
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
		warm.setGrid(g, HEIGHTS, 0, 0, 0, false, null);
		for (int i = 0; i < 100; ++i)
		{
			warm.trace(SkyGridMap.RAY_BUDGET, 20, 20);
		}
		SkyGrid s = new SkyGrid();
		s.setGrid(g, HEIGHTS, 0, 0, 0, false, null);
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
				bytes += 2L * (box[2] - box[0] + 1) * (box[3] - box[1] + 1) * (box[5] - box[4] + 1) * 8;
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
