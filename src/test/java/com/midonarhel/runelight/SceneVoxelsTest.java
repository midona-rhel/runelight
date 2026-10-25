package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SceneVoxelsTest
{
	private static final int BASE_ZONE = 5;

	// ---- triangles and voxels

	private static void face(VoxelCapture cap, boolean foliage, int[] a, int[] b, int[] c)
	{
		cap.add(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], foliage);
	}

	private static void quad(VoxelCapture cap, boolean foliage, int[] a, int[] b, int[] c, int[] d)
	{
		face(cap, foliage, a, b, c);
		face(cap, foliage, a, c, d);
	}

	private static int[] p(int x, int y, int z)
	{
		return new int[]{x, y, z};
	}

	/** A horizontal square of the given side at height y, zone-local units. */
	private static void floor(VoxelCapture cap, boolean foliage, int x, int y, int z, int side)
	{
		quad(cap, foliage, p(x, y, z), p(x + side, y, z), p(x + side, y, z + side), p(x, y, z + side));
	}

	private static VoxelGrid assemble(Voxelizer.Zone... zones)
	{
		int[] zx = new int[zones.length], zz = new int[zones.length];
		java.util.Arrays.fill(zx, BASE_ZONE);
		java.util.Arrays.fill(zz, BASE_ZONE);
		return VoxelGrid.assemble(zones, zx, zz, BASE_ZONE, null);
	}

	@Test
	public void triangleBoxOverlap()
	{
		float[] t = {0, 0, 0, 100, 0, 0, 0, 0, 100};
		assertTrue(Voxelizer.triBoxOverlap(32, 0, 32, 32, t));
		assertTrue(Voxelizer.triBoxOverlap(32, 31, 32, 32, t));
		assertFalse(Voxelizer.triBoxOverlap(32, 40, 32, 32, t));
		// beyond the hypotenuse
		assertFalse(Voxelizer.triBoxOverlap(96, 0, 96, 32, t));
		assertTrue(Voxelizer.triBoxOverlap(32, 0, 96, 32, t));
		// a slanted triangle passing the box's corner region only on one side
		float[] s = {0, 0, 0, 200, 200, 0, 0, 0, 200};
		assertTrue(Voxelizer.triBoxOverlap(100, 100, 10, 10, s));
		assertFalse(Voxelizer.triBoxOverlap(100, 0, 100, 10, s));
	}

	@Test
	public void groundVoxelizesAsOneLayer()
	{
		VoxelCapture cap = new VoxelCapture();
		// inside a layer, so only one layer is touched
		floor(cap, false, 0, 32, 0, 1000);
		Voxelizer.Zone z = Voxelizer.voxelize(cap);
		assertEquals(16 * 16, z.count);
		VoxelGrid g = assemble(z);
		// the ground's layer and the empty ones over it
		assertEquals(1 + VoxelGrid.HEADROOM, g.ny);
		assertEquals(-VoxelGrid.HEADROOM, g.layer0);
		assertEquals(1, g.cells[g.index(3, VoxelGrid.HEADROOM, 7)]);
		assertEquals(0, g.cells[g.index(16, VoxelGrid.HEADROOM, 7)]);
		assertEquals(0, g.cells[g.index(3, 0, 7)]);
	}

	@Test
	public void aLittleSurfaceLeavesAVoxelEmpty()
	{
		VoxelCapture cap = new VoxelCapture();
		// a sign on a wall: a tenth of a voxel's face
		floor(cap, false, 0, 32, 0, 20);
		floor(cap, false, 128, 32, 128, 64);
		VoxelGrid g = assemble(Voxelizer.voxelize(cap));
		int y = g.ny - 1;
		assertEquals(0, g.cells[g.index(0, y, 0)]);
		assertEquals(1, g.cells[g.index(2, y, 2)]);
	}

	@Test
	public void foliageIsMarkedWhereItMakesUpMostOfAVoxel()
	{
		VoxelCapture cap = new VoxelCapture();
		floor(cap, true, 0, 32, 0, 64);
		floor(cap, false, 192, 32, 0, 64);
		// a quarter foliage, the rest not
		floor(cap, true, 384, 32, 0, 32);
		floor(cap, false, 416, 32, 0, 32);
		floor(cap, false, 384, 32, 32, 64);
		VoxelGrid g = assemble(Voxelizer.voxelize(cap));
		int y = g.ny - 1;
		assertEquals(1 | VoxelGrid.FOLIAGE, g.cells[g.index(0, y, 0)]);
		assertEquals(1, g.cells[g.index(3, y, 0)]);
		assertEquals(1, g.cells[g.index(6, y, 0)]);
		assertTrue(VoxelGrid.foliage(g.cells[g.index(0, y, 0)]));
		assertFalse(VoxelGrid.foliage(g.cells[g.index(3, y, 0)]));
	}

	@Test
	public void aVoxelTwoZonesFillIsFoliageOnlyIfBothSayIt()
	{
		VoxelCapture a = new VoxelCapture(), b = new VoxelCapture(), c = new VoxelCapture();
		floor(a, true, 0, 32, 0, 64);
		floor(b, true, 0, 32, 0, 64);
		floor(c, false, 0, 32, 0, 64);
		VoxelGrid both = assemble(Voxelizer.voxelize(a), Voxelizer.voxelize(b));
		int y = both.ny - 1;
		assertEquals(1 | VoxelGrid.FOLIAGE, both.cells[both.index(0, y, 0)]);
		VoxelGrid one = assemble(Voxelizer.voxelize(a), Voxelizer.voxelize(c));
		assertEquals(1, one.cells[one.index(0, y, 0)]);
	}

	@Test
	public void waterIsALayerOfVoxelsThatLeavesWhatIsThere()
	{
		VoxelCapture cap = new VoxelCapture();
		// a foliage post in the water's layer
		floor(cap, true, 0, 100, 0, 40);
		VoxelWater w = new VoxelWater();
		// base tile (0, 0): the surface in layer 1 (y 64 to 128)
		w.wet[0] = true;
		w.surface[0] = 100;
		VoxelGrid g = VoxelGrid.assemble(new Voxelizer.Zone[]{Voxelizer.voxelize(cap)}, new int[]{BASE_ZONE},
			new int[]{BASE_ZONE}, BASE_ZONE, w);
		int y = 1 - g.layer0;
		assertEquals(1 | VoxelGrid.FOLIAGE, g.cells[g.index(0, y, 0)]);
		assertEquals(1, g.cells[g.index(1, y, 0)]);
		assertEquals(1, g.cells[g.index(0, y, 1)]);
		assertEquals(1, g.cells[g.index(1, y, 1)]);
		assertEquals(0, g.cells[g.index(2, y, 0)]);
		assertEquals(0, g.cells[g.index(0, y - 1, 0)]);
	}

	@Test
	public void anEmptySceneIsOneEmptyLayer()
	{
		VoxelGrid g = VoxelGrid.assemble(new Voxelizer.Zone[0], new int[0], new int[0], BASE_ZONE, null);
		assertEquals(1, g.ny);
		assertEquals(0, g.cells[0]);
	}

	// ---- rays

	@Test
	public void ddaFindsFirstCell()
	{
		VoxelGrid g = new VoxelGrid(16, 8, 16, 0);
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
		VoxelGrid g = new VoxelGrid(12, 12, 12, 0);
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
		VoxelGrid g = new VoxelGrid(16, 16, 16, 0);
		for (int x = 0; x < 16; ++x)
		{
			for (int z = 0; z < 16; ++z)
			{
				g.cells[g.index(x, 4, z)] = 1 | VoxelGrid.FOLIAGE;
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
		assertEquals(VoxelGrid.FOLIAGE_PASS * 1000, through, 60);
		assertTrue(g.trace(8.5f, 10.5f, 8.5f, 0, -1, 0, 64, false, -1) >= 0);
	}
}
