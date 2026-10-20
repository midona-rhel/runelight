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

	// ---- rays

	@Test
	public void raysCoverTheSphereAboveTheFloor()
	{
		assertTrue(GiProbes.RAYS > 16 && GiProbes.RAYS < GiProbes.RAY_SAMPLES);
		float sum = 0;
		for (int r = 0; r < GiProbes.RAYS; ++r)
		{
			float w = GiProbes.WEIGHT[r];
			assertTrue(w > 0 && w <= 1);
			sum += w;
			for (float[] d : GiProbes.DIRECTIONS)
			{
				assertEquals(1, d[r * 3] * d[r * 3] + d[r * 3 + 1] * d[r * 3 + 1] + d[r * 3 + 2] * d[r * 3 + 2], 1e-5);
				// the same elevation in every rotation, none steeply down (y is down)
				assertEquals(GiProbes.DIRECTIONS[0][r * 3 + 1], d[r * 3 + 1], 1e-6);
				assertTrue(d[r * 3 + 1] < GiProbes.BELOW_HORIZON);
			}
		}
		assertEquals(sum, GiProbes.WEIGHT_SUM, 1e-4);
		// rays up and level count in full
		for (int r = 0; r < GiProbes.RAYS; ++r)
		{
			if (GiProbes.DIRECTIONS[0][r * 3 + 1] <= 0)
			{
				assertEquals(1, GiProbes.WEIGHT[r], 0);
			}
		}
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

	// ---- probes

	/**
	 * Flat ground over the zones around base tiles 0-31, a 4x4 tile house at tiles 10-13 (2 tiles tall walls,
	 * a door on the south side at tile 12, a roof at 3 tiles), a low pillar at tile 4, 4 and a closed block at tiles
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
		// columns stand at every second tile: column 6 is tile 12, column 15 tile 30
		int house = GiProbes.probe(6, 6, 0);
		int outside = GiProbes.probe(15, 15, 0);
		assertTrue(p.valid[house]);
		assertTrue(p.valid[outside]);
		assertTrue(p.indoor[house]);
		assertFalse(p.indoor[outside]);
		// the floor's high probe is above its low one, and the second floor's above the roof
		assertTrue(p.indoor[GiProbes.probe(6, 6, 1)]);
		assertFalse(p.indoor[GiProbes.probe(6, 6, 3)]);
		int tile = 12 * GiProbes.SIZE + 12;
		float roof = (float) Math.floor(p.meta[tile * 4 + 3]);
		// the roof line: the bottom of the voxel holding the roof at -384
		assertTrue("roof " + roof, roof <= -320 && roof >= -384);
		int open = 30 * GiProbes.SIZE + 30;
		assertEquals(GiProbes.NO_ROOF, p.meta[open * 4 + 3], 0);
		// the floors' grounds, FLOOR_GAP apart at least
		assertEquals(0, p.meta[open * 4], 0);
		assertEquals(-GiProbes.FLOOR_GAP, p.meta[open * 4 + 1], 0);
		assertEquals(-2 * GiProbes.FLOOR_GAP, p.meta[open * 4 + 2], 0);
		// an open column has probes in the air, floors with no floor have none
		assertTrue(p.valid[GiProbes.probe(15, 15, 1)]);
		assertFalse(p.valid[GiProbes.probe(15, 15, 2)]);
		assertFalse(p.valid[GiProbes.probe(15, 15, 5)]);
	}

	@Test
	public void probesInGeometryMoveOrStop()
	{
		GiProbes p = probes();
		// the low probe starts inside the pillar, whose top is at -120: it moves up out of it
		int pillar = GiProbes.probe(2, 2, 0);
		assertTrue(p.valid[pillar]);
		assertTrue(p.position[pillar * 3 + 1] < -GiProbes.LOW);
		// inside the closed block every spot sees only the block's back faces
		assertFalse(p.valid[GiProbes.probe(10, 10, 0)]);
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

	private static float lum(float[] light, int probe)
	{
		return GiRelight.luminance(light[probe * 3], light[probe * 3 + 1], light[probe * 3 + 2]);
	}

	private static float sum(float[] light)
	{
		float s = 0;
		for (float v : light)
		{
			s += v;
		}
		return s;
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
		assertEquals(0.5f / 0.25f, GiRelight.skyScale(l), 0.02);
	}

	@Test
	public void openProbesAreOneAndInteriorsAreDarker()
	{
		GiProbes p = probes();
		GiRelight.Light skyOnly = GiRelight.Light.fromTable(table(0.3f, 2.6f), sunDir(), false);
		GiRelight.Result r = GiRelight.relight(p, skyOnly, null, 1, GiProbes.ParallelFor.SEQUENTIAL, false);
		// in the open, nothing in the way: the sky's own light, 1 relative to itself (what the ground below gives is
		// dark with no sun to light it)
		int open = GiProbes.probe(15, 15, 1);
		for (int c = 0; c < 3; ++c)
		{
			assertEquals(1, r.shown[open * 3 + c], 0.08);
		}
		int house = GiProbes.probe(6, 6, 0);
		assertTrue(p.valid[house]);
		assertTrue("inside " + lum(r.shown, house), lum(r.shown, house) < 0.35f);

		// sunlight bounced off the ground and walls adds light, more bounces add more
		GiRelight.Light withSun = GiRelight.Light.fromTable(table(0.3f, 2.6f), sunDir(), true);
		GiRelight.Result bounced = GiRelight.relight(p, withSun, null, 3, GiProbes.ParallelFor.SEQUENTIAL, false);
		GiRelight.Result once = GiRelight.relight(p, withSun, null, 1, GiProbes.ParallelFor.SEQUENTIAL, false);
		int ground = GiProbes.probe(15, 15, 0);
		assertTrue(lum(bounced.light, ground) > lum(r.light, ground) * 1.1f);
		assertTrue(lum(bounced.light, house) > lum(r.light, house));
		assertTrue(sum(bounced.light) > sum(once.light));
		for (float v : bounced.shown)
		{
			assertTrue(Float.isFinite(v) && v >= 0 && v <= GiRelight.MAX_RELATIVE);
		}
	}

	@Test
	public void repeatedBouncesSettle()
	{
		// a bright pen with its roof off: sunlight falls in and bounces from wall to wall, every relight feeding the
		// next; the light it holds must level off, not grow
		GiCapture cap = new GiCapture();
		int lo = 0, hi = 1024, top = -512;
		quad(cap, p(lo, 0, lo), p(hi, 0, lo), p(hi, 0, hi), p(lo, 0, hi), 0, -1, 0);
		quad(cap, p(lo, 0, lo), p(lo, top, lo), p(lo, top, hi), p(lo, 0, hi), 1, 0, 0);
		quad(cap, p(hi, 0, lo), p(hi, top, lo), p(hi, top, hi), p(hi, 0, hi), -1, 0, 0);
		quad(cap, p(lo, 0, lo), p(hi, 0, lo), p(hi, top, lo), p(lo, top, lo), 0, 0, 1);
		quad(cap, p(lo, 0, hi), p(hi, 0, hi), p(hi, top, hi), p(lo, top, hi), 0, 0, -1);
		GiGrid g = GiGrid.assemble(new GiVoxels.Zone[]{GiVoxels.voxelize(cap)}, new int[]{BASE_ZONE}, new int[]{BASE_ZONE},
			BASE_ZONE);
		GiProbes.Input in = new GiProbes.Input();
		in.ground = new float[1][GiProbes.SIZE * GiProbes.SIZE];
		GiProbes p = GiProbes.build(g, in, GiProbes.ParallelFor.SEQUENTIAL);
		GiRelight.Light light = GiRelight.Light.fromTable(table(0.3f, 2.6f), sunDir(), true);
		float[] last = null;
		float[] peaks = new float[2];
		for (int i = 1; i <= 40; ++i)
		{
			last = GiRelight.relight(p, light, last, 1, GiProbes.ParallelFor.SEQUENTIAL, false).light;
			if (i == 20 || i == 40)
			{
				for (float v : last)
				{
					assertTrue(Float.isFinite(v));
					peaks[i / 20 - 1] = Math.max(peaks[i / 20 - 1], v);
				}
			}
		}
		assertTrue("peaks " + peaks[0] + " " + peaks[1], peaks[1] < peaks[0] * 1.02f + 1e-4f && peaks[1] < 4);
	}

	@Test
	public void doorLetsSomeLightIn()
	{
		GiProbes p = probes();
		GiRelight.Light light = GiRelight.Light.fromTable(table(0.3f, 2.6f), new float[]{0, -1, 0}, false);
		float[] shown = GiRelight.relight(p, light, null, 1, GiProbes.ParallelFor.SEQUENTIAL, false).shown;
		// the probe by the door sees more sky than the one at the back wall
		int byDoor = GiProbes.probe(6, 5, 0);
		int back = GiProbes.probe(6, 6, 0);
		assertTrue(p.valid[byDoor] && p.valid[back]);
		assertTrue(lum(shown, byDoor) > lum(shown, back));
		assertTrue(lum(shown, byDoor) > 0);
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
		float[] light = new float[GiProbes.PROBES * 3];
		boolean[] valid = new boolean[GiProbes.PROBES];
		int probe = GiProbes.probe(5, 7, 2);
		for (int k = 0; k < 3; ++k)
		{
			light[probe * 3 + k] = k + 1;
		}
		valid[probe] = true;
		short[] t = GlobalIllumination.texels(light, valid);
		// column x 5 across, level 2 and column z 7 down
		int i = ((2 * GiProbes.COLUMNS + 7) * GlobalIllumination.TEX_W + 5) * 4;
		float[] got = new float[4];
		for (int k = 0; k < 4; ++k)
		{
			got[k] = GlobalIllumination.fromHalf(t[i + k]);
		}
		assertArrayEquals(new float[]{1, 2, 3, 1}, got, 0);
		assertEquals(0, t[i + 4 + 3]);
		assertEquals(GlobalIllumination.TEX_W * GlobalIllumination.TEX_H, GiProbes.PROBES);
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
