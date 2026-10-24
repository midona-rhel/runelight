package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.nio.IntBuffer;
import org.junit.Test;

public class GiBounceTest
{
	private static final int BASE_ZONE = 5;
	// the game's packed hsl of a mid green
	private static final int GREEN = 21 << 10 | 5 << 7 | 60;

	private static GiVoxels.Zone greenGround()
	{
		GiCapture cap = new GiCapture();
		// front faces up (y down), as the capture stores them
		cap.add(0, 0, 0, 1024, 0, 0, 0, 0, 1024, GREEN);
		cap.add(1024, 0, 0, 1024, 0, 1024, 0, 0, 1024, GREEN);
		return GiVoxels.voxelize(cap);
	}

	// 4 x 4 zones of green ground; with a pond the middle four are missing (the water's bed is left out of the capture)
	private static GiGrid greenGrid(GiWater water, boolean pond)
	{
		GiVoxels.Zone[] zones = new GiVoxels.Zone[16];
		int[] zx = new int[16], zz = new int[16];
		GiVoxels.Zone ground = greenGround();
		for (int i = 0; i < 16; ++i)
		{
			int x = i / 4, z = i % 4;
			zones[i] = pond && x >= 1 && x <= 2 && z >= 1 && z <= 2 ? null : ground;
			zx[i] = BASE_ZONE + x;
			zz[i] = BASE_ZONE + z;
		}
		return GiGrid.assemble(zones, zx, zz, BASE_ZONE, water);
	}

	private static GiPatches patches(GiGrid grid)
	{
		GiPatches.Input in = new GiPatches.Input();
		in.ground = new float[1][GiPatches.SIZE * GiPatches.SIZE];
		return GiPatches.build(grid, in, GiPatches.ParallelFor.SEQUENTIAL);
	}

	private static GiRelight.Light sunlit()
	{
		float[] t = new float[GiRelight.TABLE_FLOATS];
		int row = GiRelight.SKY_W * GiRelight.SKY_H * 4;
		t[row] = 2.9f;
		t[row + 1] = 2.6f;
		t[row + 2] = 2.2f;
		t[row + 12] = 1;
		float l = (float) Math.sqrt(0.25 + 0.49 + 0.25);
		return GiRelight.Light.fromTable(t, new float[]{0.5f / l, -0.7f / l, 0.5f / l}, true);
	}

	@Test
	public void openGroundBouncesNothing()
	{
		// floors don't see each other, so on flat ground nothing is left to bounce but what the composite lights itself
		GiPatches p = patches(greenGrid(null, false));
		for (float v : GiRelight.relight(p, sunlit(), GiPatches.ParallelFor.SEQUENTIAL))
		{
			assertEquals(0, v, 0);
		}
	}

	/**
	 * Green ground over 4 x 4 zones, and a gray wall 8 units thick on the west edge of tile 10, from tile 10 to 13 and
	 * 240 high, as the collision map has it.
	 */
	private static GiPatches greenWall()
	{
		GiVoxels.Zone[] zones = new GiVoxels.Zone[16];
		int[] zx = new int[16], zz = new int[16];
		GiVoxels.Zone ground = greenGround();
		for (int i = 0; i < 16; ++i)
		{
			zones[i] = ground;
			zx[i] = BASE_ZONE + i / 4;
			zz[i] = BASE_ZONE + i % 4;
		}
		GiCapture cap = new GiCapture();
		cap.add(0, 0, 0, 1024, 0, 0, 0, 0, 1024, GREEN);
		cap.add(1024, 0, 0, 1024, 0, 1024, 0, 0, 1024, GREEN);
		GlobalIlluminationTest.box(cap, 63, 252, -240, 256, 260, 0, 768);
		zones[5] = GiVoxels.voxelize(cap);
		GiPatches.Input in = new GiPatches.Input();
		in.ground = new float[1][GiPatches.SIZE * GiPatches.SIZE];
		int[][][] flags = new int[1][GiPatches.SIZE][GiPatches.SIZE];
		for (int z = 10; z < 14; ++z)
		{
			flags[0][10][z] |= WallMap.LOS_WEST;
		}
		in.walls = WallMap.build(flags, null, GiPatches.SIZE, 0, 0);
		return GiPatches.build(GiGrid.assemble(zones, zx, zz, BASE_ZONE), in, GiPatches.ParallelFor.SEQUENTIAL);
	}

	/** What the composite does with a lookup: the tint at luminance 1 and how much of it applies (light_occlusion.glsl giTint). */
	static float[] tint(float[] gi)
	{
		float l = GiRelight.luminance(gi[0], gi[1], gi[2]);
		if (l < 1e-4f)
		{
			return new float[]{1, 1, 1, 0};
		}
		return new float[]{gi[0] / l, gi[1] / l, gi[2] / l, Math.min(1, l) * gi[3]};
	}

	@Test
	public void theGrassGreensTheFootOfAWallAndFadesUp()
	{
		GiPatches p = greenWall();
		float[] light = GiRelight.relight(p, sunlit(), GiPatches.ParallelFor.SEQUENTIAL);
		// the wall's east face looks at the sunlit grass, at x = 1284; the sun is to the east, so its west face is in the
		// wall's own shadow
		float[][] at = new float[3][];
		int k = 0;
		for (float y : new float[]{-10, -100, -200})
		{
			float[] gi = GiLookup.irradiance(p, light, new float[]{1286, y, 12.5f * GiPatches.TILE}, new float[]{1, 0, 0});
			at[k] = tint(gi);
			System.err.println("wall at " + -y + " up: tint " + java.util.Arrays.toString(at[k]) + " lookup " + java.util.Arrays.toString(gi));
			k++;
		}
		// green at the foot, and a good deal of it
		assertTrue("foot " + java.util.Arrays.toString(at[0]), at[0][1] > at[0][0] * 1.5f && at[0][1] > at[0][2] * 1.5f);
		assertTrue("amount at the foot " + at[0][3], at[0][3] > 0.5f);
		// and less of it higher up, still green
		assertTrue("100 up " + at[1][3] + " foot " + at[0][3], at[1][3] < at[0][3] * 0.6f && at[1][3] > 0);
		assertTrue("200 up " + at[2][3] + " 100 up " + at[1][3], at[2][3] < at[1][3] * 0.6f);
		assertTrue(at[1][1] > at[1][0] && at[1][1] > at[1][2]);
		// both faces have patches
		assertTrue(GlobalIlluminationTest.face(p, 0, 9, 12, 1)[1] >= 1);
		assertTrue(GlobalIlluminationTest.face(p, 0, 10, 12, 3)[1] >= 1);
		// the grass beside the wall is lit by the wall alone, which is gray: white, no tint of green
		int grass = GlobalIlluminationTest.floorId(0, 10, 12);
		assertTrue(p.exists(grass));
		float[] t = tint(new float[]{light[grass * 3], light[grass * 3 + 1], light[grass * 3 + 2], 1});
		assertTrue("grass " + java.util.Arrays.toString(t), t[0] > 0.7f && t[2] > 0.7f && t[1] < 1.3f);
	}

	@Test
	public void saturationKeepsLuminance()
	{
		float[] out = new float[3];
		GiPatches.saturate(0.15f, 0.25f, 0.12f, out, 0);
		assertEquals(GiRelight.luminance(0.15f, 0.25f, 0.12f), GiRelight.luminance(out[0], out[1], out[2]), 1e-3);
		assertTrue(out[1] - out[0] > 0.1f * 1.5f - 1e-3f);
		GiPatches.saturate(0.2f, 0.2f, 0.2f, out, 0);
		assertEquals(0.2f, out[0], 1e-6);
		assertEquals(0.2f, out[2], 1e-6);
		// a color that would go below zero stops there
		GiPatches.saturate(0, 0.3f, 0, out, 0);
		assertTrue(out[0] >= 0 && out[2] >= 0);
	}

	@Test
	public void waterSurfaceIsALayerOfBlueVoxels()
	{
		GiWater water = new GiWater();
		// two tiles of water at world y 40: the voxel layer 0
		for (int x = 10; x < 12; ++x)
		{
			water.type[10 * GiPatches.SIZE + x] = WaterMap.FRESH;
			water.surface[10 * GiPatches.SIZE + x] = 40;
		}
		// one deeper down (y down: larger)
		water.type[20 * GiPatches.SIZE + 20] = WaterMap.OCEAN;
		water.surface[20 * GiPatches.SIZE + 20] = 3 * 64 + 10;
		// and one over the ground, which keeps its voxels
		water.type[3 * GiPatches.SIZE + 3] = WaterMap.FRESH;
		water.surface[3 * GiPatches.SIZE + 3] = 0;
		GiGrid g = greenGrid(water, true);
		int cell = g.cells[g.index(20, -g.layer0, 20)];
		assertNotEquals(0, cell);
		assertTrue(GiGrid.albedo(cell, 2) > GiGrid.albedo(cell, 0) * 1.5f);
		assertEquals(-1, GiGrid.normal(cell)[1], 0.02);
		assertNotEquals(0, g.cells[g.index(23, -g.layer0, 21)]);
		assertEquals(0, g.cells[g.index(24, -g.layer0, 20)]);
		assertNotEquals(0, g.cells[g.index(41, 3 - g.layer0, 41)]);
		assertEquals(0, g.cells[g.index(41, 2 - g.layer0, 41)]);
		GiGrid dry = greenGrid(null, true);
		assertEquals(dry.cells[dry.index(6, -dry.layer0, 6)], g.cells[g.index(6, -g.layer0, 6)]);
	}

	@Test
	public void waterPatchesAreBlueAndGrassPatchesGreen()
	{
		GiWater water = new GiWater();
		for (int x = 8; x < 24; ++x)
		{
			for (int z = 8; z < 24; ++z)
			{
				water.type[z * GiPatches.SIZE + x] = WaterMap.FRESH;
				water.surface[z * GiPatches.SIZE + x] = 40;
			}
		}
		GiPatches p = patches(greenGrid(water, true));
		// what a wall at the shore would bounce is the patch's albedo times its light
		int overWater = GlobalIlluminationTest.floorId(0, 14, 14), overGrass = GlobalIlluminationTest.floorId(0, 2, 2);
		assertTrue(p.exists(overWater) && p.exists(overGrass));
		float wr = p.albedo[overWater * 3], wg = p.albedo[overWater * 3 + 1], wb = p.albedo[overWater * 3 + 2];
		assertTrue("water " + wr + " " + wg + " " + wb, wb > wr * 2f && wb > wg * 0.9f);
		float gr = p.albedo[overGrass * 3], gg = p.albedo[overGrass * 3 + 1], gb = p.albedo[overGrass * 3 + 2];
		assertTrue("grass " + gr + " " + gg + " " + gb, gg > gb * 2f);
	}

	@Test
	public void noWaterNoSurface()
	{
		assertNull(GiWater.of(null, null, 0));
	}

	@Test
	public void captureLeavesOutTheSunkBed()
	{
		GiCapture.Buffer buffer = new GiCapture.Buffer(IntBuffer.allocate(64));
		for (int k = 0; k < 3; ++k)
		{
			buffer.put22224(k * 100, 0, k % 2 * 100, 0, GREEN);
			buffer.put2222(WaterMap.BED_FLAG, 0, 0, 0);
		}
		assertEquals(0, buffer.capture.count);
		for (int k = 0; k < 3; ++k)
		{
			buffer.put22224(k * 100, 0, k % 2 * 100, 0, GREEN);
			buffer.put2222(0, 0, 0, 0);
		}
		assertEquals(1, buffer.capture.count);
	}
}
