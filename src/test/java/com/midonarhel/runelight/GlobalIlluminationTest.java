package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GlobalIlluminationTest
{
	private static final int BASE_ZONE = 5;
	private static final int GRAY = 63;

	// ---- triangles and voxels

	/** Add a triangle so its front faces (nx, ny, nz), as the capture stores them. */
	private static void face(GiCapture cap, int[] a, int[] b, int[] c, float nx, float ny, float nz)
	{
		face(cap, GRAY, a, b, c, nx, ny, nz);
	}

	static void face(GiCapture cap, int color, int[] a, int[] b, int[] c, float nx, float ny, float nz)
	{
		long fx = (long) (b[1] - a[1]) * (c[2] - a[2]) - (long) (b[2] - a[2]) * (c[1] - a[1]);
		long fy = (long) (b[2] - a[2]) * (c[0] - a[0]) - (long) (b[0] - a[0]) * (c[2] - a[2]);
		long fz = (long) (b[0] - a[0]) * (c[1] - a[1]) - (long) (b[1] - a[1]) * (c[0] - a[0]);
		if (fx * nx + fy * ny + fz * nz < 0)
		{
			int[] t = b;
			b = c;
			c = t;
		}
		cap.add(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], color);
	}

	private static void quad(GiCapture cap, int[] a, int[] b, int[] c, int[] d, float nx, float ny, float nz)
	{
		quad(cap, GRAY, a, b, c, d, nx, ny, nz);
	}

	static void quad(GiCapture cap, int color, int[] a, int[] b, int[] c, int[] d, float nx, float ny, float nz)
	{
		face(cap, color, a, b, c, nx, ny, nz);
		face(cap, color, a, c, d, nx, ny, nz);
	}

	/** A box of outward faces from (x0, y0, z0) to (x1, y1, z1), zone-local units, y down. */
	private static void box(GiCapture cap, int x0, int y0, int z0, int x1, int y1, int z1)
	{
		box(cap, GRAY, x0, y0, z0, x1, y1, z1);
	}

	static void box(GiCapture cap, int color, int x0, int y0, int z0, int x1, int y1, int z1)
	{
		quad(cap, color, p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1), 0, -1, 0);
		quad(cap, color, p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1), 0, 1, 0);
		quad(cap, color, p(x0, y0, z0), p(x0, y1, z0), p(x0, y1, z1), p(x0, y0, z1), -1, 0, 0);
		quad(cap, color, p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1), 1, 0, 0);
		quad(cap, color, p(x0, y0, z0), p(x1, y0, z0), p(x1, y1, z0), p(x0, y1, z0), 0, 0, -1);
		quad(cap, color, p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1), 0, 0, 1);
	}

	private static int[] p(int x, int y, int z)
	{
		return new int[]{x, y, z};
	}

	private static void ground(GiCapture cap)
	{
		quad(cap, p(0, 0, 0), p(1024, 0, 0), p(1024, 0, 1024), p(0, 0, 1024), 0, -1, 0);
	}

	@Test
	public void triangleBoxOverlap()
	{
		float[] t = {0, 0, 0, 100, 0, 0, 0, 0, 100};
		assertTrue(GiVoxels.triBoxOverlap(32, 0, 32, 32, t));
		assertTrue(GiVoxels.triBoxOverlap(32, 31, 32, 32, t));
		assertFalse(GiVoxels.triBoxOverlap(32, 40, 32, 32, t));
		// beyond the hypotenuse
		assertFalse(GiVoxels.triBoxOverlap(96, 0, 96, 32, t));
		assertTrue(GiVoxels.triBoxOverlap(32, 0, 96, 32, t));
		// a slanted triangle passing the box's corner region only on one side
		float[] s = {0, 0, 0, 200, 200, 0, 0, 0, 200};
		assertTrue(GiVoxels.triBoxOverlap(100, 100, 10, 10, s));
		assertFalse(GiVoxels.triBoxOverlap(100, 0, 100, 10, s));
	}

	@Test
	public void groundVoxelizesAsOneSidedLayer()
	{
		GiCapture cap = new GiCapture();
		quad(cap, p(0, 0, 0), p(1000, 0, 0), p(1000, 0, 1000), p(0, 0, 1000), 0, -1, 0);
		// lift it inside a layer so only one layer is touched
		for (int i = 1; i < cap.count * 9; i += 3)
		{
			cap.pos[i] = 32;
		}
		GiVoxels.Zone z = GiVoxels.voxelize(cap);
		assertEquals(16 * 16, z.count);
		GiGrid g = GiGrid.assemble(new GiVoxels.Zone[]{z}, new int[]{BASE_ZONE}, new int[]{BASE_ZONE}, BASE_ZONE);
		assertEquals(1, g.ny);
		assertEquals(0, g.layer0);
		int cell = g.cells[g.index(3, 0, 7)];
		assertNotEquals(0, cell);
		assertFalse(GiGrid.twoSided(cell));
		float[] n = GiGrid.normal(cell);
		assertEquals(-1, n[1], 0.02);
		// gray 63 lightness is about 0.49 in sRGB
		assertEquals(GiVoxels.srgbToLinear(63 / 128f), GiGrid.albedo(cell, 0), 0.04);
		assertEquals(0, g.cells[g.index(16, 0, 7)]);
	}

	@Test
	public void thinWallIsTwoSided()
	{
		GiCapture cap = new GiCapture();
		// faces both ways 8 units apart in the same voxel
		quad(cap, p(100, 0, 0), p(100, -60, 0), p(100, -60, 60), p(100, 0, 60), -1, 0, 0);
		quad(cap, p(108, 0, 0), p(108, -60, 0), p(108, -60, 60), p(108, 0, 60), 1, 0, 0);
		GiVoxels.Zone z = GiVoxels.voxelize(cap);
		GiGrid g = GiGrid.assemble(new GiVoxels.Zone[]{z}, new int[]{BASE_ZONE}, new int[]{BASE_ZONE}, BASE_ZONE);
		int cell = g.cells[g.index(1, g.ny - 1, 0)];
		assertNotEquals(0, cell);
		assertTrue(GiGrid.twoSided(cell));
	}

	@Test
	public void captureOrientsFrontByNormals()
	{
		int up = VertexShading.encodeNormal(0, -1, 0);
		int[] x = {128, 0, 128}, y = {0, 0, 0}, z = {128, 128, 0};
		// NE, NW, SE: cross(b - a, c - a) points up already
		assertTrue(GiCapture.frontMatchesNormals(x, y, z, new int[]{up, up, up}));
		int down = VertexShading.encodeNormal(0, 1, 0);
		assertFalse(GiCapture.frontMatchesNormals(x, y, z, new int[]{down, down, down}));
		assertTrue(GiCapture.frontMatchesNormals(x, y, z, new int[]{0, 0, 0}));
	}

	// ---- rays

	@Test
	public void ddaFindsFirstCell()
	{
		GiGrid g = new GiGrid(16, 8, 16, 0);
		g.cells[g.index(10, 4, 5)] = 1;
		g.cells[g.index(12, 4, 5)] = 1;
		assertEquals(g.index(10, 4, 5), g.trace(2.5f, 4.5f, 5.5f, 1, 0, 0, 64, false, -1));
		assertEquals(-1, g.trace(2.5f, 4.5f, 5.5f, -1, 0, 0, 64, false, -1));
		// too short
		assertEquals(-1, g.trace(2.5f, 4.5f, 5.5f, 1, 0, 0, 5, false, -1));
		// starting inside a cell
		assertEquals(g.index(10, 4, 5), g.trace(10.5f, 4.5f, 5.5f, 1, 0, 0, 64, false, -1));
		assertEquals(g.index(12, 4, 5), g.trace(10.5f, 4.5f, 5.5f, 1, 0, 0, 64, true, -1));
		// diagonal
		g.cells[g.index(6, 6, 6)] = 1;
		float s = (float) (1 / Math.sqrt(3));
		assertEquals(g.index(6, 6, 6), g.trace(1.5f, 1.5f, 1.5f, s, s, s, 64, false, -1));
		// from outside the grid, entering it
		assertEquals(g.index(10, 4, 5), g.trace(10.5f, -20f, 5.5f, 0, 1, 0, 64, false, -1));
		assertEquals(-1, g.trace(-5f, 4.5f, 5.5f, -1, 0, 0, 64, false, -1));
	}

	@Test
	public void ddaVisitsCellsBrute()
	{
		// against a dense march with tiny steps
		java.util.Random r = new java.util.Random(7);
		GiGrid g = new GiGrid(12, 12, 12, 0);
		for (int i = 0; i < g.cells.length; ++i)
		{
			g.cells[i] = r.nextFloat() < 0.04f ? 1 : 0;
		}
		for (int k = 0; k < 500; ++k)
		{
			float ox = r.nextFloat() * 12, oy = r.nextFloat() * 12, oz = r.nextFloat() * 12;
			float dx = (float) r.nextGaussian(), dy = (float) r.nextGaussian(), dz = (float) r.nextGaussian();
			float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			dx /= l;
			dy /= l;
			dz /= l;
			int expect = -1;
			for (float t = 0; t < 30; t += 0.0005f)
			{
				int x = (int) Math.floor(ox + dx * t), y = (int) Math.floor(oy + dy * t), z = (int) Math.floor(oz + dz * t);
				if (x < 0 || y < 0 || z < 0 || x >= 12 || y >= 12 || z >= 12)
				{
					break;
				}
				if (g.cells[g.index(x, y, z)] != 0)
				{
					expect = g.index(x, y, z);
					break;
				}
			}
			assertEquals(expect, g.trace(ox, oy, oz, dx, dy, dz, 30, false, -1));
		}
	}

	@Test
	public void foliageLetsSomeRaysThrough()
	{
		GiGrid g = new GiGrid(16, 16, 16, 0);
		for (int x = 0; x < 16; ++x)
		{
			for (int z = 0; z < 16; ++z)
			{
				g.cells[g.index(x, 4, z)] = 1 | GiGrid.FOLIAGE;
			}
		}
		int through = 0;
		for (int seed = 0; seed < 1000; ++seed)
		{
			int hit = g.trace(8.5f, 10.5f, 8.5f, 0, -1, 0, 64, false, seed);
			if (hit < 0)
			{
				through++;
			}
			// the same ray always gets the same answer
			assertEquals(hit, g.trace(8.5f, 10.5f, 8.5f, 0, -1, 0, 64, false, seed));
		}
		assertEquals(GiGrid.FOLIAGE_PASS * 1000, through, 60);
		assertTrue(g.trace(8.5f, 10.5f, 8.5f, 0, -1, 0, 64, false, -1) >= 0);
	}

	// ---- rays

	@Test
	public void skyRaysCoverTheWholeSphereEqually()
	{
		// a bit each in a long
		assertTrue(GiPatches.SKY_SAMPLES <= 64);
		assertEquals(GiPatches.SKY_SAMPLES * 3, GiPatches.SKY_RAYS.length);
		float up = 0, down = 0;
		for (int r = 0; r < GiPatches.SKY_SAMPLES; ++r)
		{
			float[] d = GiPatches.SKY_RAYS;
			assertEquals(1, d[r * 3] * d[r * 3] + d[r * 3 + 1] * d[r * 3 + 1] + d[r * 3 + 2] * d[r * 3 + 2], 1e-5);
			// y is down
			if (d[r * 3 + 1] > 0)
			{
				down++;
			}
			else
			{
				up++;
			}
		}
		assertEquals(up, down, 2);
	}

	@Test
	public void halfFloatsRoundTrip()
	{
		for (float f : new float[]{0, 1, -2.5f, 0.1234f, 1e-5f, 65504, 3.0e-8f})
		{
			float back = GlobalIllumination.fromHalf(GlobalIllumination.toHalf(f));
			assertEquals(f, back, Math.max(Math.abs(f) * 1e-3f, 6e-8f));
		}
		assertEquals(0x3c00, GlobalIllumination.toHalf(1) & 0xffff);
		assertEquals(0x7c00, GlobalIllumination.toHalf(1e6f) & 0xffff);
	}

	@Test
	public void halfResPackingIsExact()
	{
		// halfres.glsl's packing in float arithmetic: 24 bit whole numbers survive
		java.util.Random r = new java.util.Random(3);
		for (int i = 0; i < 10000; ++i)
		{
			float hi = r.nextInt(4096), lo = r.nextInt(4096);
			float v = hi * 4096f + lo;
			float h = (float) Math.floor(v * (1f / 4096f));
			assertEquals(hi, h, 0);
			assertEquals(lo, v - h * 4096f, 0);
		}
	}

	// ---- patches

	/**
	 * Flat ground over the zones around base tiles 0-31, a 4x4 tile house at tiles 10-13 (2 tiles tall walls,
	 * a door on the south side at tile 12, a roof at 3 tiles), a low pillar at tile 4, 4 and a solid block at tiles
	 * 20-22 (walls()).
	 */
	private static GiPatches scene()
	{
		java.util.List<GiVoxels.Zone> zones = new java.util.ArrayList<>();
		java.util.List<Integer> zx = new java.util.ArrayList<>(), zz = new java.util.ArrayList<>();
		for (int x = 0; x < 4; ++x)
		{
			for (int z = 0; z < 4; ++z)
			{
				GiCapture cap = new GiCapture();
				ground(cap);
				if (x == 1 && z == 1)
				{
					// house, zone-local: tiles 10-13 are 2-5 here; walls up to the roof, the door in the south wall
					int lo = 256, hi = 768, roof = -384;
					wall(cap, lo, -1, 0, false);
					wall(cap, hi, 1, 0, false);
					wall(cap, lo, 0, -1, true);
					wall(cap, hi, 0, 1, false);
					quad(cap, p(lo, roof, lo), p(hi, roof, lo), p(hi, roof, hi), p(lo, roof, hi), 0, -1, 0);
					quad(cap, p(lo, roof + 6, lo), p(hi, roof + 6, lo), p(hi, roof + 6, hi), p(lo, roof + 6, hi), 0, 1, 0);
				}
				if (x == 0 && z == 0)
				{
					box(cap, 4 * 128, -120, 4 * 128, 5 * 128, 0, 5 * 128);
				}
				if (x == 2 && z == 2)
				{
					// tiles 20-22 are 4-6 here
					box(cap, 4 * 128, -512, 4 * 128, 7 * 128, 0, 7 * 128);
				}
				zones.add(GiVoxels.voxelize(cap));
				zx.add(BASE_ZONE + x);
				zz.add(BASE_ZONE + z);
			}
		}
		GiGrid g = GiGrid.assemble(zones.toArray(new GiVoxels.Zone[0]), zx.stream().mapToInt(i -> i).toArray(),
			zz.stream().mapToInt(i -> i).toArray(), BASE_ZONE);
		GiPatches.Input in = new GiPatches.Input();
		in.ground = new float[4][GiPatches.SIZE * GiPatches.SIZE];
		in.walls = walls();
		return GiPatches.build(g, in, GiPatches.ParallelFor.SEQUENTIAL);
	}

	/** The collision map's walls of scene(): the house's four sides but for the door, and the block's solid tiles. */
	private static WallMap walls()
	{
		int[][][] flags = new int[1][GiPatches.SIZE][GiPatches.SIZE];
		for (int i = 10; i < 14; ++i)
		{
			flags[0][10][i] |= WallMap.LOS_WEST;
			flags[0][13][i] |= WallMap.LOS_EAST;
			flags[0][i][13] |= WallMap.LOS_NORTH;
			if (i != 12)
			{
				flags[0][i][10] |= WallMap.LOS_SOUTH;
			}
		}
		for (int x = 20; x < 23; ++x)
		{
			for (int z = 20; z < 23; ++z)
			{
				flags[0][x][z] |= WallMap.LOS_FULL;
			}
		}
		return WallMap.build(flags, null, GiPatches.SIZE, 0, 0);
	}

	/**
	 * A house wall (zone-local 256-768) at x = at (nz 0) or z = at (nx 0), from the ground to the roof at -384, with
	 * outer face normal (nx, 0, nz) and an inner face 6 units inside; door leaves a gap 2 tiles tall at zone-local
	 * 512-640 (tile 12).
	 */
	private static void wall(GiCapture cap, int at, float nx, float nz, boolean door)
	{
		int lo = 256, hi = 768, top = -384, doorTop = -256;
		int in = (int) (-(nx + nz) * 6);
		for (int side = 0; side < 2; ++side)
		{
			int c = at + (side == 0 ? 0 : in);
			float sx = side == 0 ? nx : -nx, sz = side == 0 ? nz : -nz;
			if (nz == 0)
			{
				quad(cap, p(c, 0, lo), p(c, top, lo), p(c, top, hi), p(c, 0, hi), sx, 0, sz);
			}
			else if (door)
			{
				quad(cap, p(lo, 0, c), p(lo, top, c), p(512, top, c), p(512, 0, c), sx, 0, sz);
				quad(cap, p(640, 0, c), p(640, top, c), p(hi, top, c), p(hi, 0, c), sx, 0, sz);
				quad(cap, p(512, doorTop, c), p(512, top, c), p(640, top, c), p(640, doorTop, c), sx, 0, sz);
			}
			else
			{
				quad(cap, p(lo, 0, c), p(lo, top, c), p(hi, top, c), p(hi, 0, c), sx, 0, sz);
			}
		}
	}

	private static GiPatches patches;

	private static synchronized GiPatches patches()
	{
		if (patches == null)
		{
			patches = scene();
		}
		return patches;
	}

	/** The id of floor f's patch of tile (x, z), whether it has one or not. */
	static int floorId(int f, int x, int z)
	{
		return f * GiPatches.TILES + z * GiPatches.SIZE + x;
	}

	/** Wall face texel of a tile's face (see GiPatches.wallTexels): first patch, bands and kind, base, band height. */
	static float[] face(GiPatches p, int f, int x, int z, int face)
	{
		int at = ((f * GiPatches.TILES + z * GiPatches.SIZE + x) * GiPatches.FACES + face) * 4;
		return java.util.Arrays.copyOfRange(p.wallTexels, at, at + 4);
	}

	static int bands(float[] face)
	{
		return (int) face[1] & 7;
	}

	static boolean linked(GiPatches p, int i, int j)
	{
		for (int l = p.linkStart[i]; l < p.linkStart[i + 1]; ++l)
		{
			if (p.linkTarget[l] == j)
			{
				return true;
			}
		}
		return false;
	}

	static float linkWeight(GiPatches p, int i, int j)
	{
		for (int l = p.linkStart[i]; l < p.linkStart[i + 1]; ++l)
		{
			if (p.linkTarget[l] == j)
			{
				return p.linkWeight[l];
			}
		}
		return -1;
	}

	/** Each patch's own id as its light, so a lookup says which patches it read. */
	static float[] markers(GiPatches p)
	{
		float[] light = new float[p.count * 3];
		for (int i = 0; i < p.count; ++i)
		{
			light[i * 3] = i;
		}
		return light;
	}

	@Test
	public void floorPatchesStandOnTheGroundAndNothingInTheAir()
	{
		GiPatches p = patches();
		int open = floorId(0, 30, 30);
		assertTrue(p.exists(open));
		assertEquals(30.5f * GiPatches.TILE, p.position[open * 3], 0);
		assertEquals(0, p.position[open * 3 + 1], 0);
		assertEquals(30.5f * GiPatches.TILE, p.position[open * 3 + 2], 0);
		assertEquals(-1, p.normal[open * 3 + 1], 0);
		assertEquals(GiPatches.TILE * GiPatches.TILE, p.area[open], 0);
		// no floors above the open ground, nor patches where there is no ground at all
		assertFalse(p.exists(floorId(1, 30, 30)));
		assertFalse(p.exists(floorId(2, 30, 30)));
		assertFalse(p.exists(floorId(0, 60, 60)));
		// a solid object has none, and the floors' heights are told to the shader whether they have patches or not
		for (int x = 20; x < 23; ++x)
		{
			for (int z = 20; z < 23; ++z)
			{
				assertFalse(p.exists(floorId(0, x, z)));
			}
		}
		int t = (30 * GiPatches.SIZE + 30) * 4;
		assertEquals(0, p.floorTexels[t], 0);
		assertEquals(-GiPatches.FLOOR_GAP, p.floorTexels[t + 1], 0);
		assertEquals(-2 * GiPatches.FLOOR_GAP, p.floorTexels[t + 2], 0);
		// the house has a floor inside it
		assertTrue(p.exists(floorId(0, 11, 12)));
	}

	@Test
	public void aWallHasAFaceOnEachSideUpItsHeight()
	{
		GiPatches p = patches();
		// the house' west wall stands at x = 10 tiles: tile 9 is outside it, tile 10 inside, faces looking into them
		float[] outside = face(p, 0, 9, 12, 1), inside = face(p, 0, 10, 12, 3);
		assertTrue("outside " + bands(outside), bands(outside) >= 2);
		assertEquals(bands(outside), bands(inside));
		assertEquals(0, outside[1] - bands(outside), 0);
		int first = (int) outside[0], other = (int) inside[0];
		assertEquals(10 * GiPatches.TILE, p.position[first * 3], 0);
		assertEquals(10 * GiPatches.TILE, p.position[other * 3], 0);
		assertEquals(-1, p.normal[first * 3], 0);
		assertEquals(1, p.normal[other * 3], 0);
		assertEquals(12.5f * GiPatches.TILE, p.position[first * 3 + 2], 0);
		// the bands go up the wall, one over the next, as far as it is high: the 384 of it, but for what is the next
		// floor's, which begins one floor gap up
		float top = outside[2] - p.position[(first + bands(outside) - 1) * 3 + 1] + outside[3] / 2;
		assertEquals(GiPatches.FLOOR_GAP, top, 1e-2);
		for (int k = 1; k < bands(outside); ++k)
		{
			assertEquals(outside[3], p.position[(first + k - 1) * 3 + 1] - p.position[(first + k) * 3 + 1], 1e-3);
		}
		assertEquals(GiPatches.TILE * outside[3], p.area[first], 1e-2);
		// no wall under the door, and the wall of the tiles beside it has its faces
		assertEquals(0, face(p, 0, 12, 10, 2)[1], 0);
		assertTrue(face(p, 0, 11, 10, 2)[1] > 0);
		// a solid object has no faces: it is not a wall
		assertEquals(0, face(p, 0, 19, 21, 1)[1], 0);
		assertEquals(0, face(p, 0, 21, 23, 0)[1], 0);
		// the corner has the faces of both of its walls, each into its own tile
		assertTrue(face(p, 0, 10, 10, 3)[1] > 0 && face(p, 0, 10, 10, 2)[1] > 0);
		// walls of the floors above, which there are none of
		assertEquals(0, face(p, 1, 10, 12, 3)[1], 0);
	}

	@Test
	public void patchesLinkOnlyToTheOnesTheyFaceAndSee()
	{
		GiPatches p = patches();
		int links = 0;
		for (int i = 0; i < p.count; ++i)
		{
			for (int l = p.linkStart[i]; l < p.linkStart[i + 1]; ++l)
			{
				int j = p.linkTarget[l];
				links++;
				assertTrue(p.exists(i) && p.exists(j));
				float dx = p.position[j * 3] - p.position[i * 3], dy = p.position[j * 3 + 1] - p.position[i * 3 + 1];
				float dz = p.position[j * 3 + 2] - p.position[i * 3 + 2];
				float r2 = dx * dx + dy * dy + dz * dz;
				float r = (float) Math.sqrt(r2);
				float cosI = (p.normal[i * 3] * dx + p.normal[i * 3 + 1] * dy + p.normal[i * 3 + 2] * dz) / r;
				float cosJ = -(p.normal[j * 3] * dx + p.normal[j * 3 + 1] * dy + p.normal[j * 3 + 2] * dz) / r;
				assertTrue(cosI > 0 && cosJ > 0);
				assertTrue(r <= GiPatches.REACH);
				// the point-to-area form factor
				assertEquals(cosI * cosJ * p.area[j] / ((float) Math.PI * r2 + p.area[j]), p.linkWeight[l], 1e-6);
				// both see each other
				assertTrue(linked(p, j, i));
			}
		}
		assertTrue(links > 1000);
		// the floor outside the house's west wall is lit by that wall's outside, not its inside, which is behind it
		float[] outside = face(p, 0, 9, 12, 1), inside = face(p, 0, 10, 12, 3);
		int out = floorId(0, 9, 12), in = floorId(0, 11, 12);
		assertTrue(linked(p, out, (int) outside[0]));
		assertTrue(linked(p, in, (int) inside[0]));
		for (int k = 0; k < bands(inside); ++k)
		{
			assertFalse(linked(p, out, (int) inside[0] + k));
			assertFalse(linked(p, in, (int) outside[0] + k));
			assertFalse(linked(p, (int) outside[0] + k, (int) inside[0] + k));
		}
		// floors don't see each other
		assertFalse(linked(p, floorId(0, 30, 30), floorId(0, 31, 30)));
		// a patch in the open sees no wall, a wall's two bands in one plane don't see each other
		assertEquals(0, p.linkStart[floorId(0, 30, 30) + 1] - p.linkStart[floorId(0, 30, 30)]);
		assertFalse(linked(p, (int) outside[0], (int) outside[0] + 1));
	}

	@Test
	public void roomsSeeLittleSky()
	{
		GiPatches p = patches();
		int open = Long.bitCount(p.sky[floorId(0, 30, 30)]);
		// about the half of the directions above the horizon, but those too near it
		assertTrue("open " + open, open > 20 && open < 40);
		// a roof closes the room, but for what comes through the door: more of it near the door than at the back
		int near = Long.bitCount(p.sky[floorId(0, 12, 11)]), back = Long.bitCount(p.sky[floorId(0, 11, 12)]);
		assertTrue("door " + near, near > 0);
		assertTrue("near the door " + near + " at the back " + back, near > back);
		// a wall sees the sky on its open side only
		float[] outside = face(p, 0, 9, 12, 1);
		long wallOpen = p.sky[(int) outside[0] + bands(outside) - 1];
		assertTrue(Long.bitCount(wallOpen) > 10);
		for (int r = 0; r < GiPatches.SKY_SAMPLES; ++r)
		{
			if ((wallOpen >>> r & 1) != 0)
			{
				assertTrue(GiPatches.SKY_RAYS[r * 3] < 0.5);
			}
		}
	}

	@Test
	public void aFloorPixelReadsTheFloorPatchesAroundIt()
	{
		GiPatches p = patches();
		float[] marker = markers(p);
		float c = GiPatches.TILE;
		// at the center of a tile: that tile's patch
		float[] gi = GiLookup.irradiance(p, marker, new float[]{30.5f * c, -3, 30.5f * c}, new float[]{0, -1, 0});
		assertEquals(floorId(0, 30, 30), gi[0], 0);
		assertEquals(1, gi[3], 0);
		// halfway between two: the two, a quarter of the way: the nearer one most
		gi = GiLookup.irradiance(p, marker, new float[]{31 * c, 0, 30.5f * c}, new float[]{0, -1, 0});
		assertEquals((floorId(0, 30, 30) + floorId(0, 31, 30)) / 2f, gi[0], 1e-2);
		gi = GiLookup.irradiance(p, marker, new float[]{30.75f * c, 0, 30.5f * c}, new float[]{0, -1, 0});
		assertEquals(floorId(0, 30, 30) * 0.75f + floorId(0, 31, 30) * 0.25f, gi[0], 1e-2);
		// where a neighbor has no patch (the solid block's tiles) the others are renormalized, and the weight falls
		gi = GiLookup.irradiance(p, marker, new float[]{20 * c, 0, 19.5f * c}, new float[]{0, -1, 0});
		assertEquals(floorId(0, 19, 19) * 0.5f + floorId(0, 19, 20) * 0.0f + floorId(0, 20, 19) * 0.5f,
			gi[0] * 1f, 1e-1);
		assertTrue(gi[3] > 0 && gi[3] <= 1);
		// off the ground there is nothing
		gi = GiLookup.irradiance(p, marker, new float[]{60.5f * c, 0, 60.5f * c}, new float[]{0, -1, 0});
		assertEquals(0, gi[3], 0);
	}

	@Test
	public void aWallPixelReadsItsOwnFaceNotTheOneBehind()
	{
		GiPatches p = patches();
		float[] marker = markers(p);
		float[] outside = face(p, 0, 9, 12, 1), inside = face(p, 0, 10, 12, 3);
		int bands = bands(outside);
		float z = 12.5f * GiPatches.TILE;
		for (int k = 0; k < bands; ++k)
		{
			// at the height of a band's center, in front of the wall on either side, facing away from it
			float y = outside[2] - (k + 0.5f) * outside[3];
			float[] out = GiLookup.irradiance(p, marker, new float[]{10 * GiPatches.TILE - 8, y, z}, new float[]{-1, 0, 0});
			float[] in = GiLookup.irradiance(p, marker, new float[]{10 * GiPatches.TILE + 14, y, z}, new float[]{1, 0, 0});
			assertEquals(outside[0] + k, out[0], 1e-3);
			assertEquals(inside[0] + k, in[0], 1e-3);
			assertEquals(1, out[3], 0);
			assertEquals(1, in[3], 0);
		}
		// between two bands it blends the two
		float y = outside[2] - outside[3];
		float[] between = GiLookup.irradiance(p, marker, new float[]{10 * GiPatches.TILE - 8, y, z}, new float[]{-1, 0, 0});
		assertEquals(outside[0] + 0.5f, between[0], 1e-2);
		// along the wall, at the border of two tiles, the two tiles' faces
		float[] beside = face(p, 0, 9, 13, 1);
		float[] border = GiLookup.irradiance(p, marker, new float[]{10 * GiPatches.TILE - 8, outside[2] - 0.5f * outside[3],
			13 * GiPatches.TILE}, new float[]{-1, 0, 0});
		assertEquals((outside[0] + beside[0]) / 2, border[0], 1e-2);
		// above the wall it is not on it: the ground under it, faded out with the height
		float[] above = GiLookup.irradiance(p, marker, new float[]{10 * GiPatches.TILE - 8, -3 * 128f, z}, new float[]{-1, 0, 0});
		assertEquals(0, above[3], 0);
		// a pixel near no wall is read from the ground under it
		float[] tree = GiLookup.irradiance(p, marker, new float[]{30.5f * GiPatches.TILE, -120, 30.5f * GiPatches.TILE},
			new float[]{1, 0, 0});
		assertEquals(floorId(0, 30, 30), tree[0], 0);
		assertTrue(tree[3] > 0.5f && tree[3] < 1);
	}

	@Test
	public void roofsAndOtherFloorsAreNotReadThroughTheFloorsBetween()
	{
		GiPatches p = patches();
		float[] marker = markers(p);
		float c = GiPatches.TILE;
		// the roof of the house, facing up, is over the second floor, which is not there
		float[] roof = GiLookup.irradiance(p, marker, new float[]{12 * c, -390, 12 * c}, new float[]{0, -1, 0});
		assertEquals(0, roof[3], 0);
		// the underside of the roof reads the floor of the room, not the roof
		float[] ceiling = GiLookup.irradiance(p, marker, new float[]{12 * c, -378, 12 * c}, new float[]{0, 1, 0});
		assertEquals(0, ceiling[3], 0);
		// a pixel high above the ground is out of reach of its light
		float[] high = GiLookup.irradiance(p, marker, new float[]{30.5f * c, -300, 30.5f * c}, new float[]{1, 1, 0});
		assertEquals(0, high[3], 0);
	}

	@Test
	public void aDiagonalWallHasPatchesOnBothSides()
	{
		// a "/" wall across tile 15, 15 from corner to corner, 240 high
		GiCapture cap = new GiCapture();
		ground(cap);
		float d = 0.70710678f;
		int x0 = 128 * 7, z0 = 128 * 7;
		for (int side = 0; side < 2; ++side)
		{
			// zone-local: tile 15 is tile 7 of zone 1
			int shift = side == 0 ? 4 : -4;
			quad(cap, p(x0 - shift, 0, z0 + shift), p(x0 + 128 - shift, 0, z0 + 128 + shift),
				p(x0 + 128 - shift, -240, z0 + 128 + shift), p(x0 - shift, -240, z0 + shift),
				side == 0 ? -d : d, 0, side == 0 ? d : -d);
		}
		GiVoxels.Zone[] zones = new GiVoxels.Zone[16];
		int[] zx = new int[16], zz = new int[16];
		for (int i = 0; i < 16; ++i)
		{
			GiCapture ground = new GiCapture();
			ground(ground);
			zones[i] = i == 5 ? GiVoxels.voxelize(cap) : GiVoxels.voxelize(ground);
			zx[i] = BASE_ZONE + i / 4;
			zz[i] = BASE_ZONE + i % 4;
		}
		int[][][] flags = new int[1][GiPatches.SIZE][GiPatches.SIZE];
		flags[0][15][15] |= WallMap.LOS_FULL;
		WallMap walls = WallMap.build(flags, java.util.Collections.singletonList(
			new WallMap.Diagonal(0, 15, 15, WallMap.DIAG_SW_NE)), GiPatches.SIZE, 0, 0);
		GiPatches.Input in = new GiPatches.Input();
		in.ground = new float[1][GiPatches.SIZE * GiPatches.SIZE];
		in.walls = walls;
		GiPatches p = GiPatches.build(GiGrid.assemble(zones, zx, zz, BASE_ZONE), in, GiPatches.ParallelFor.SEQUENTIAL);

		// no floor in the tile, but the two sides of the wall, whose normals point northwest and southeast
		assertFalse(p.exists(floorId(0, 15, 15)));
		float[] west = face(p, 0, 15, 15, GiPatches.DIAGONAL_WEST), east = face(p, 0, 15, 15, GiPatches.DIAGONAL_EAST);
		assertEquals(GiPatches.KIND_SW_NE, (int) west[1] >> 3);
		assertEquals(GiPatches.KIND_SW_NE, (int) east[1] >> 3);
		int w = (int) west[0], e = (int) east[0];
		assertEquals(-d, p.normal[w * 3], 1e-6);
		assertEquals(d, p.normal[w * 3 + 2], 1e-6);
		assertEquals(d, p.normal[e * 3], 1e-6);
		assertEquals(-d, p.normal[e * 3 + 2], 1e-6);
		assertEquals(15.5f * GiPatches.TILE, p.position[w * 3], 0);
		assertEquals(GiPatches.TILE * 1.41421356f * west[3], p.area[w], 1e-2);

		// a pixel on the northwest side reads that side, one on the southeast side the other
		float[] marker = markers(p);
		float cx = 15.5f * GiPatches.TILE, cz = 15.5f * GiPatches.TILE, y = west[2] - 0.5f * west[3];
		float[] nw = GiLookup.irradiance(p, marker, new float[]{cx - 6, y, cz + 6}, new float[]{-d, 0, d});
		float[] se = GiLookup.irradiance(p, marker, new float[]{cx + 6, y, cz - 6}, new float[]{d, 0, -d});
		assertEquals(w, nw[0], 1e-3);
		assertEquals(e, se[0], 1e-3);
		assertEquals(1, nw[3], 0);
		// and a pixel far from the diagonal is not on it
		float[] far = GiLookup.irradiance(p, marker, new float[]{cx - 50, y, cz + 50}, new float[]{-d, 0, d});
		assertTrue(far[3] == 0 || far[0] < GiPatches.FLOOR_SLOTS);
	}

	private static float[] table(float sky, float caster)
	{
		float[] t = new float[GiRelight.TABLE_FLOATS];
		for (int y = 0; y < GiRelight.SKY_H; ++y)
		{
			float s = (y + 0.5f) / GiRelight.SKY_H * 2 - 1;
			for (int x = 0; x < GiRelight.SKY_W; ++x)
			{
				int i = (y * GiRelight.SKY_W + x) * 4;
				float v = s > 0 ? sky : sky * 0.3f;
				t[i] = v * 0.8f;
				t[i + 1] = v * 0.9f;
				t[i + 2] = v;
			}
		}
		int row = GiRelight.SKY_W * GiRelight.SKY_H * 4;
		t[row] = t[row + 1] = t[row + 2] = caster;
		t[row + 4] = 0.5f;
		t[row + 5] = 0.55f;
		t[row + 6] = 0.7f;
		t[row + 8] = 0;
		t[row + 12] = 1;
		return t;
	}

	private static float lum(float[] light, int patch)
	{
		return GiRelight.luminance(light[patch * 3], light[patch * 3 + 1], light[patch * 3 + 2]);
	}

	private static float[] sunDir()
	{
		float l = (float) Math.sqrt(0.25 + 0.49 + 0.25);
		return new float[]{0.5f / l, -0.7f / l, 0.5f / l};
	}

	@Test
	public void skyScaleMatchesTheOldAmbient()
	{
		// a sky of the same radiance everywhere lights a surface facing up with that radiance
		float[] t = new float[GiRelight.TABLE_FLOATS];
		for (int i = 0; i < GiRelight.SKY_W * GiRelight.SKY_H; ++i)
		{
			t[i * 4] = t[i * 4 + 1] = t[i * 4 + 2] = 0.25f;
		}
		int row = GiRelight.SKY_W * GiRelight.SKY_H * 4;
		t[row + 4] = t[row + 5] = t[row + 6] = 0.5f;
		GiRelight.Light l = GiRelight.Light.fromTable(t, sunDir(), false);
		assertEquals(0.5f / 0.25f, GiRelight.skyScale(l), 0.1);
	}

	@Test
	public void sunlitWallsLightTheGroundAndNothingLeaksIn()
	{
		GiPatches p = patches();
		GiRelight.Light withSun = GiRelight.Light.fromTable(table(0.3f, 2.6f), sunDir(), true);
		float[] light = GiRelight.relight(p, withSun, GiPatches.ParallelFor.SEQUENTIAL);
		for (float v : light)
		{
			assertTrue(Float.isFinite(v) && v >= 0);
		}
		// the sun is to the east and north: the house's east wall has the sun, the ground in front of it bounces it back
		int east = floorId(0, 14, 12), west = floorId(0, 9, 12);
		assertTrue(lum(light, east) > 0);
		// the room has a roof: what a floor in it gathers is a fraction of what the open ground beside a wall does
		int room = floorId(0, 11, 12);
		assertTrue("room " + lum(light, room) + " beside " + lum(light, east), lum(light, room) < lum(light, east) * 0.5f);
		// nothing is lit that does not exist
		assertEquals(0, lum(light, floorId(0, 60, 60)), 0);
		// without the sun, the sky alone lights what the walls bounce: less
		GiRelight.Light skyOnly = GiRelight.Light.fromTable(table(0.3f, 2.6f), sunDir(), false);
		float[] dim = GiRelight.relight(p, skyOnly, GiPatches.ParallelFor.SEQUENTIAL);
		assertTrue(lum(dim, east) < lum(light, east));
		assertTrue(lum(dim, east) > 0);
		assertTrue(lum(dim, west) > 0);
		// without light there is none
		GiRelight.Light dark = GiRelight.Light.fromTable(new float[GiRelight.TABLE_FLOATS], new float[]{0, -1, 0}, true);
		for (float v : GiRelight.relight(p, dark, GiPatches.ParallelFor.SEQUENTIAL))
		{
			assertEquals(0, v, 0);
		}
	}

	@Test
	public void relightTriggers()
	{
		assertEquals(0, GlobalIllumination.angle(new float[]{0, -1, 0}, new float[]{0, -2, 0}), 1e-3);
		float c = (float) Math.cos(Math.toRadians(3)), s = (float) Math.sin(Math.toRadians(3));
		assertEquals(3, GlobalIllumination.angle(new float[]{0, -1, 0}, new float[]{s, -c, 0}), 1e-2);
	}

	@Test
	public void lightTexelLayout()
	{
		GiPatches p = patches();
		float[] gathered = new float[p.count * 3];
		int wall = (int) face(p, 0, 9, 12, 1)[0];
		int floor = floorId(0, 30, 30);
		gathered[wall * 3] = 1;
		gathered[wall * 3 + 1] = 0.25f;
		gathered[wall * 3 + 2] = 3;
		short[] t = GlobalIllumination.texels(p, gathered);
		assertEquals(GlobalIllumination.LIGHT_W * GlobalIllumination.LIGHT_H * 4, t.length);
		// patch i is at texel (i % LIGHT_W, i / LIGHT_W)
		int at = ((wall / GlobalIllumination.LIGHT_W) * GlobalIllumination.LIGHT_W + wall % GlobalIllumination.LIGHT_W) * 4;
		assertEquals(1, GlobalIllumination.fromHalf(t[at]), 0);
		assertEquals(0.25f, GlobalIllumination.fromHalf(t[at + 1]), 0);
		assertEquals(3, GlobalIllumination.fromHalf(t[at + 2]), 0);
		assertEquals(1, GlobalIllumination.fromHalf(t[at + 3]), 0);
		// a patch with nothing gathered still is one, an empty slot is not
		assertEquals(1, GlobalIllumination.fromHalf(t[floor * 4 + 3]), 0);
		assertEquals(0, GlobalIllumination.fromHalf(t[floorId(0, 60, 60) * 4 + 3]), 0);
		// the textures hold every patch and the index textures' sizes
		assertTrue(GlobalIllumination.LIGHT_W * GlobalIllumination.LIGHT_H >= GiPatches.MAX_PATCHES);
		assertEquals(GlobalIllumination.FLOORS_W * GlobalIllumination.FLOORS_H * 4, p.floorTexels.length);
		assertEquals(GlobalIllumination.WALLS_W * GlobalIllumination.WALLS_H * 4, p.wallTexels.length);
	}

	@Test
	public void shaderMatchesTheLayout()
	{
		assertEquals(GiPatches.SIZE, GiLookup.define("GI_SIZE"), 0);
		assertEquals(GiPatches.TILES, GiLookup.define("GI_TILES"), 0);
		assertEquals(GiPatches.FACES, GiLookup.define("GI_FACES"), 0);
		assertEquals(GiPatches.TILE, GiLookup.define("GI_TILE"), 0);
		assertEquals(GlobalIllumination.LIGHT_W, 1 << (int) GiLookup.define("GI_PATCH_BITS"));
		assertEquals(GlobalIllumination.LIGHT_W - 1, GiLookup.define("GI_PATCH_MASK"), 0);
		// the shader tells a diagonal wall's kind by what the number of bands has added: 8 for "/", 16 for ""
		assertEquals(8, GiPatches.KIND * GiPatches.KIND_SW_NE);
		assertEquals(16, GiPatches.KIND * GiPatches.KIND_NW_SE);
		assertTrue(GiPatches.MAX_BANDS < GiPatches.KIND);
		// and which face is which
		assertEquals(4, GiPatches.DIAGONAL_WEST);
		assertEquals(5, GiPatches.DIAGONAL_EAST);
	}

	@Test
	public void skyTableSampling()
	{
		float[] t = table(0.3f, 1);
		GiRelight.Light l = GiRelight.Light.fromTable(t, new float[]{0, -1, 0}, true);
		float[] out = new float[3];
		GiRelight.sky(l.sky, 0, -1, 0, out);
		assertEquals(0.3f, out[2], 1e-4);
		GiRelight.sky(l.sky, 0.3f, 0.95f, 0.1f, out);
		assertEquals(0.09f, out[2], 1e-4);
	}
}
