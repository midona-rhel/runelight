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
		// the ground's layer and the empty ones over it
		assertEquals(1 + GiGrid.HEADROOM, g.ny);
		assertEquals(-GiGrid.HEADROOM, g.layer0);
		int cell = g.cells[g.index(3, GiGrid.HEADROOM, 7)];
		assertNotEquals(0, cell);
		assertFalse(GiGrid.twoSided(cell));
		float[] n = GiGrid.normal(cell);
		assertEquals(-1, n[1], 0.02);
		// gray 63 lightness is about 0.49 in sRGB
		assertEquals(GiVoxels.srgbToLinear(63 / 128f), GiGrid.albedo(cell, 0), 0.04);
		assertEquals(0, g.cells[g.index(16, GiGrid.HEADROOM, 7)]);
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
		assertEquals(GiEmitters.SKY_SAMPLES * 3, GiEmitters.SKY_RAYS.length);
		float up = 0, down = 0;
		for (int r = 0; r < GiEmitters.SKY_SAMPLES; ++r)
		{
			float[] d = GiEmitters.SKY_RAYS;
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
}
