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
		long fx = (long) (b[1] - a[1]) * (c[2] - a[2]) - (long) (b[2] - a[2]) * (c[1] - a[1]);
		long fy = (long) (b[2] - a[2]) * (c[0] - a[0]) - (long) (b[0] - a[0]) * (c[2] - a[2]);
		long fz = (long) (b[0] - a[0]) * (c[1] - a[1]) - (long) (b[1] - a[1]) * (c[0] - a[0]);
		if (fx * nx + fy * ny + fz * nz < 0)
		{
			int[] t = b;
			b = c;
			c = t;
		}
		cap.add(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], GRAY);
	}

	private static void quad(GiCapture cap, int[] a, int[] b, int[] c, int[] d, float nx, float ny, float nz)
	{
		face(cap, a, b, c, nx, ny, nz);
		face(cap, a, c, d, nx, ny, nz);
	}

	/** A box of outward faces from (x0, y0, z0) to (x1, y1, z1), zone-local units, y down. */
	private static void box(GiCapture cap, int x0, int y0, int z0, int x1, int y1, int z1)
	{
		quad(cap, p(x0, y0, z0), p(x1, y0, z0), p(x1, y0, z1), p(x0, y0, z1), 0, -1, 0);
		quad(cap, p(x0, y1, z0), p(x1, y1, z0), p(x1, y1, z1), p(x0, y1, z1), 0, 1, 0);
		quad(cap, p(x0, y0, z0), p(x0, y1, z0), p(x0, y1, z1), p(x0, y0, z1), -1, 0, 0);
		quad(cap, p(x1, y0, z0), p(x1, y1, z0), p(x1, y1, z1), p(x1, y0, z1), 1, 0, 0);
		quad(cap, p(x0, y0, z0), p(x1, y0, z0), p(x1, y1, z0), p(x0, y1, z0), 0, 0, -1);
		quad(cap, p(x0, y0, z1), p(x1, y0, z1), p(x1, y1, z1), p(x0, y1, z1), 0, 0, 1);
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

	// ---- spherical harmonics

	private static float[] project(java.util.function.Function<float[], float[]> radiance)
	{
		float[] d = GiSh.fibonacci(GiSh.RAYS);
		GiSh.Accumulator acc = new GiSh.Accumulator();
		for (int i = 0; i < GiSh.RAYS; ++i)
		{
			float[] dir = {d[i * 3], d[i * 3 + 1], d[i * 3 + 2]};
			float[] l = radiance.apply(dir);
			acc.add(dir[0], dir[1], dir[2], l[0], l[1], l[2]);
		}
		float[] sh = new float[12];
		acc.finish(sh, 0);
		return sh;
	}

	@Test
	public void uniformRadianceGivesItsValueEverywhere()
	{
		float[] sh = project(d -> new float[]{0.5f, 0.25f, 1});
		for (float[] n : new float[][]{{0, -1, 0}, {0, 1, 0}, {1, 0, 0}, {0.6f, 0, 0.8f}})
		{
			// the Fibonacci set's mean direction is only nearly zero
			assertEquals(0.5f, GiSh.eval(sh, 0, 0, n[0], n[1], n[2]), 0.005);
			assertEquals(0.25f, GiSh.eval(sh, 0, 1, n[0], n[1], n[2]), 0.005);
			assertEquals(1f, GiSh.eval(sh, 0, 2, n[0], n[1], n[2]), 0.01);
		}
	}

	@Test
	public void skyHemisphereLightsUpFacingFully()
	{
		// radiance 1 from above the horizon (y down)
		float[] sh = project(d -> d[1] < 0 ? new float[]{1, 1, 1} : new float[]{0, 0, 0});
		assertEquals(1, GiSh.eval(sh, 0, 0, 0, -1, 0), 0.05);
		assertEquals(0, GiSh.eval(sh, 0, 0, 0, 1, 0), 0.05);
		assertEquals(0.5, GiSh.eval(sh, 0, 0, 1, 0, 0), 0.05);
	}

	@Test
	public void halfFloatsRoundTrip()
	{
		for (float f : new float[]{0, 1, -2.5f, 0.1234f, 1e-5f, 65504, 3.0e-8f})
		{
			float back = GiSh.fromHalf(GiSh.toHalf(f));
			assertEquals(f, back, Math.max(Math.abs(f) * 1e-3f, 6e-8f));
		}
		assertEquals(0x3c00, GiSh.toHalf(1) & 0xffff);
		assertEquals(0x7c00, GiSh.toHalf(1e6f) & 0xffff);
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

	// ---- probes

	/**
	 * Flat ground over the zones around base tiles 0-31, a 4x4 tile house at tiles 10-13 (2 tiles tall walls,
	 * a door on the south side at tile 11, a roof at 3 tiles), a low pillar at tile 3, 3 and a closed block at tiles
	 * 20-22.
	 */
	private static GiProbes scene()
	{
		java.util.List<GiVoxels.Zone> zones = new java.util.ArrayList<>();
		java.util.List<Integer> zx = new java.util.ArrayList<>(), zz = new java.util.ArrayList<>();
		for (int x = 0; x < 4; ++x)
		{
			for (int z = 0; z < 4; ++z)
			{
				GiCapture cap = new GiCapture();
				ground(cap);
				int ox = x * 1024, oz = z * 1024;
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
					box(cap, 3 * 128, -120, 3 * 128, 4 * 128, 0, 4 * 128);
				}
				if (x == 2 && z == 2)
				{
					// tiles 20-22 are 4-6 here
					box(cap, 4 * 128, -512, 4 * 128, 7 * 128, 0, 7 * 128);
				}
				zones.add(GiVoxels.voxelize(cap));
				zx.add(BASE_ZONE + x);
				zz.add(BASE_ZONE + z);
				assertTrue(ox >= 0 && oz >= 0);
			}
		}
		GiGrid g = GiGrid.assemble(zones.toArray(new GiVoxels.Zone[0]), zx.stream().mapToInt(i -> i).toArray(),
			zz.stream().mapToInt(i -> i).toArray(), BASE_ZONE);
		GiProbes.Input in = new GiProbes.Input();
		in.ground = new float[4][GiProbes.SIZE * GiProbes.SIZE];
		in.indoor = new boolean[GiProbes.SIZE * GiProbes.SIZE];
		for (int x = 10; x < 14; ++x)
		{
			for (int z = 10; z < 14; ++z)
			{
				in.indoor[z * GiProbes.SIZE + x] = true;
			}
		}
		return GiProbes.build(g, in, GiProbes.ParallelFor.SEQUENTIAL);
	}

	/**
	 * A house wall (zone-local 256-768) at x = at (nz 0) or z = at (nx 0), from the ground to the roof at -384, with
	 * outer face normal (nx, 0, nz) and an inner face 6 units inside; door leaves a gap 2 tiles tall at zone-local
	 * 384-512 (tile 11).
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
				quad(cap, p(lo, 0, c), p(lo, top, c), p(384, top, c), p(384, 0, c), sx, 0, sz);
				quad(cap, p(512, 0, c), p(512, top, c), p(hi, top, c), p(hi, 0, c), sx, 0, sz);
				quad(cap, p(384, doorTop, c), p(384, top, c), p(512, top, c), p(512, doorTop, c), sx, 0, sz);
			}
			else
			{
				quad(cap, p(lo, 0, c), p(lo, top, c), p(hi, top, c), p(hi, 0, c), sx, 0, sz);
			}
		}
	}

	private static GiProbes probes;

	private static synchronized GiProbes probes()
	{
		if (probes == null)
		{
			probes = scene();
		}
		return probes;
	}

	@Test
	public void layoutAndClassification()
	{
		GiProbes p = probes();
		int house = GiProbes.probe(11, 12, 0);
		int outside = GiProbes.probe(30, 30, 0);
		assertTrue(p.valid[house]);
		assertTrue(p.valid[outside]);
		assertTrue(p.indoor[house]);
		assertFalse(p.indoor[outside]);
		// the high layer is above the roof
		assertFalse(p.indoor[GiProbes.probe(11, 12, 2)]);
		float roof = (float) Math.floor(p.meta[(12 * GiProbes.SIZE + 11) * 4 + 3]);
		// the roof line: the bottom of the voxel holding the roof at -384
		assertTrue("roof " + roof, roof <= -320 && roof >= -384);
		assertEquals(GiProbes.NO_ROOF, (float) Math.floor(p.meta[(30 * GiProbes.SIZE + 30) * 4 + 3]), 0);
		// validity bits in the meta's fraction
		float m = p.meta[(30 * GiProbes.SIZE + 30) * 4 + 3];
		assertEquals(7, Math.round((m - Math.floor(m)) * 16));
		assertEquals(-96, p.meta[(30 * GiProbes.SIZE + 30) * 4], 0);
	}

	@Test
	public void probesInGeometryMoveOrStop()
	{
		GiProbes p = probes();
		// the probe starts inside the pillar, whose top is at -120: it moves up out of it
		int pillar = GiProbes.probe(3, 3, 0);
		assertTrue(p.valid[pillar]);
		assertTrue(p.position[pillar * 3 + 1] < -96);
		// inside the closed block every spot sees only the block's back faces
		int block = GiProbes.probe(21, 21, 0);
		assertFalse(p.valid[block]);
		float m = p.meta[(21 * GiProbes.SIZE + 21) * 4 + 3];
		assertEquals(0, Math.round((m - Math.floor(m)) * 16) & 1);
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

	private static float lum(float[] sh, int probe, float nx, float ny, float nz)
	{
		return GiRelight.luminance(GiSh.eval(sh, probe * 12, 0, nx, ny, nz), GiSh.eval(sh, probe * 12, 1, nx, ny, nz),
			GiSh.eval(sh, probe * 12, 2, nx, ny, nz));
	}

	@Test
	public void relightOpenSkyMatchesOldAmbientAndInteriorsAreDarker()
	{
		GiProbes p = probes();
		float[] sun = {0.5f, -0.7f, 0.5f};
		float l = (float) Math.sqrt(0.25 + 0.49 + 0.25);
		sun[0] /= l;
		sun[1] /= l;
		sun[2] /= l;
		GiRelight.Light skyOnly = GiRelight.Light.fromTable(table(0.3f, 2.6f), sun, false);
		float[] sh = GiRelight.relight(p, skyOnly, null, 1, GiProbes.ParallelFor.SEQUENTIAL);
		// high above open ground: up-facing light is the old ambient + sky light (luminance 0.54)
		int high = GiProbes.probe(60, 60, 2);
		float target = GiRelight.luminance(0.5f, 0.55f, 0.7f);
		assertEquals(target, lum(sh, high, 0, -1, 0), target * 0.1);
		int house = GiProbes.probe(11, 12, 0);
		int outside = GiProbes.probe(30, 30, 0);
		float in = lum(sh, house, 0, -1, 0), out = lum(sh, outside, 0, -1, 0);
		assertTrue("inside " + in + " outside " + out, in < out * 0.35f);

		// sunlight bounced off the ground brightens the shade facing down, and more bounces add light
		GiRelight.Light withSun = GiRelight.Light.fromTable(table(0.3f, 2.6f), sun, true);
		float[] bounced = GiRelight.relight(p, withSun, null, 3, GiProbes.ParallelFor.SEQUENTIAL);
		assertTrue(lum(bounced, outside, 0, 1, 0) > lum(sh, outside, 0, 1, 0) * 1.5f);
		assertTrue(lum(bounced, house, 0, -1, 0) > in);
		float[] once = GiRelight.relight(p, withSun, null, 1, GiProbes.ParallelFor.SEQUENTIAL);
		assertTrue(lum(bounced, house, 0, 1, 0) >= lum(once, house, 0, 1, 0));
		for (float v : bounced)
		{
			assertTrue(Float.isFinite(v));
		}
	}

	@Test
	public void doorLetsSomeLightIn()
	{
		GiProbes p = probes();
		GiRelight.Light light = GiRelight.Light.fromTable(table(0.3f, 2.6f), new float[]{0, -1, 0}, false);
		float[] sh = GiRelight.relight(p, light, null, 1, GiProbes.ParallelFor.SEQUENTIAL);
		// the probe by the door sees more sky than the one at the back wall
		int byDoor = GiProbes.probe(11, 10, 0);
		int back = GiProbes.probe(12, 13, 0);
		assertTrue(p.valid[byDoor] && p.valid[back]);
		assertTrue(lum(sh, byDoor, 0, 0, -1) > lum(sh, back, 0, 0, -1));
		assertTrue(lum(sh, byDoor, 0, 0, -1) > 0);
	}

	@Test
	public void relightTriggers()
	{
		assertEquals(0, GlobalIllumination.angle(new float[]{0, -1, 0}, new float[]{0, -2, 0}), 1e-3);
		float c = (float) Math.cos(Math.toRadians(3)), s = (float) Math.sin(Math.toRadians(3));
		assertEquals(3, GlobalIllumination.angle(new float[]{0, -1, 0}, new float[]{s, -c, 0}), 1e-2);
	}

	@Test
	public void texelLayout()
	{
		float[] sh = new float[GiProbes.PROBES * 12];
		int probe = GiProbes.probe(5, 7, 2);
		for (int k = 0; k < 12; ++k)
		{
			sh[probe * 12 + k] = k + 1;
		}
		short[] t = GlobalIllumination.texels(sh);
		// green (channel 1) of that probe at (5 * 3 + 1, 2 * 104 + 7)
		int i = ((2 * 104 + 7) * GlobalIllumination.TEX_W + 5 * 3 + 1) * 4;
		float[] got = new float[4];
		for (int k = 0; k < 4; ++k)
		{
			got[k] = GiSh.fromHalf(t[i + k]);
		}
		assertArrayEquals(new float[]{5, 6, 7, 8}, got, 0);
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
