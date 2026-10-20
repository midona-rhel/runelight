package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

	private static GiProbes probes(GiGrid grid)
	{
		GiProbes.Input in = new GiProbes.Input();
		in.ground = new float[1][GiProbes.SIZE * GiProbes.SIZE];
		in.indoor = new boolean[GiProbes.SIZE * GiProbes.SIZE];
		return GiProbes.build(grid, in, GiProbes.ParallelFor.SEQUENTIAL);
	}

	private static GiRelight.Light sunlit()
	{
		float[] t = new float[GiRelight.TABLE_FLOATS];
		for (int i = 0; i < GiRelight.SKY_W * GiRelight.SKY_H; ++i)
		{
			t[i * 4] = 0.3f;
			t[i * 4 + 1] = 0.4f;
			t[i * 4 + 2] = 0.6f;
		}
		int row = GiRelight.SKY_W * GiRelight.SKY_H * 4;
		t[row] = 2.9f;
		t[row + 1] = 2.6f;
		t[row + 2] = 2.2f;
		t[row + 4] = 0.5f;
		t[row + 5] = 0.55f;
		t[row + 6] = 0.7f;
		t[row + 12] = 1;
		float l = (float) Math.sqrt(0.25 + 0.49 + 0.25);
		return GiRelight.Light.fromTable(t, new float[]{0.5f / l, -0.7f / l, 0.5f / l}, true);
	}

	@Test
	public void bounceCarriesTheGroundsColorAndNoSky()
	{
		GiProbes p = probes(greenGrid(null, false));
		GiRelight.Result r = GiRelight.relight(p, sunlit(), null, 3, GiProbes.ParallelFor.SEQUENTIAL);
		// tile 10 is column 5
		int probe = GiProbes.probe(5, 5, 0);
		assertTrue(p.valid[probe]);
		float red = r.shown[probe * 3], green = r.shown[probe * 3 + 1], blue = r.shown[probe * 3 + 2];
		assertTrue("green " + red + " " + green + " " + blue, green > red * 1.4f && green > blue * 2f);
		// the sky is not part of the bounce but is part of the whole light
		assertTrue(r.light[probe * 3 + 2] > blue * 1.5f);
		// without sun and with nothing lit before, the surfaces give nothing
		float[] t = new float[GiRelight.TABLE_FLOATS];
		for (int i = 0; i < GiRelight.SKY_W * GiRelight.SKY_H; ++i)
		{
			t[i * 4] = t[i * 4 + 1] = t[i * 4 + 2] = 0.5f;
		}
		t[GiRelight.SKY_W * GiRelight.SKY_H * 4 + 4] = 0.5f;
		GiRelight.Light dark = GiRelight.Light.fromTable(t, new float[]{0, -1, 0}, true);
		for (float v : GiRelight.relight(p, dark, null, 1, GiProbes.ParallelFor.SEQUENTIAL).shown)
		{
			assertEquals(0, v, 0);
		}
	}

	@Test
	public void saturationKeepsLuminance()
	{
		float[] out = new float[3];
		GiProbes.saturate(0.15f, 0.25f, 0.12f, out, 0);
		assertEquals(GiRelight.luminance(0.15f, 0.25f, 0.12f), GiRelight.luminance(out[0], out[1], out[2]), 1e-3);
		assertTrue(out[1] - out[0] > 0.1f * 1.5f - 1e-3f);
		GiProbes.saturate(0.2f, 0.2f, 0.2f, out, 0);
		assertEquals(0.2f, out[0], 1e-6);
		assertEquals(0.2f, out[2], 1e-6);
		// a color that would go below zero stops there
		GiProbes.saturate(0, 0.3f, 0, out, 0);
		assertTrue(out[0] >= 0 && out[2] >= 0);
	}

	@Test
	public void waterSurfaceIsALayerOfBlueVoxels()
	{
		GiWater water = new GiWater();
		// two tiles of water at world y 40: the voxel layer 0
		for (int x = 10; x < 12; ++x)
		{
			water.type[10 * GiProbes.SIZE + x] = WaterMap.FRESH;
			water.surface[10 * GiProbes.SIZE + x] = 40;
		}
		// one deeper down (y down: larger)
		water.type[20 * GiProbes.SIZE + 20] = WaterMap.OCEAN;
		water.surface[20 * GiProbes.SIZE + 20] = 3 * 64 + 10;
		// and one over the ground, which keeps its voxels
		water.type[3 * GiProbes.SIZE + 3] = WaterMap.FRESH;
		water.surface[3 * GiProbes.SIZE + 3] = 0;
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
	public void waterBounceTintsTheShoreBlue()
	{
		GiWater water = new GiWater();
		for (int x = 8; x < 24; ++x)
		{
			for (int z = 8; z < 24; ++z)
			{
				water.type[z * GiProbes.SIZE + x] = WaterMap.FRESH;
				water.surface[z * GiProbes.SIZE + x] = 40;
			}
		}
		GiProbes p = probes(greenGrid(water, true));
		float[] bounce = GiRelight.relight(p, sunlit(), null, 3, GiProbes.ParallelFor.SEQUENTIAL).shown;
		// over the water the light thrown up is blue, over the grass it is green
		int overWater = GiProbes.probe(7, 7, 0), overGrass = GiProbes.probe(1, 1, 0);
		assertTrue(p.valid[overWater] && p.valid[overGrass]);
		float wr = bounce[overWater * 3 + 0];
		float wg = bounce[overWater * 3 + 1];
		float wb = bounce[overWater * 3 + 2];
		assertTrue("water " + wr + " " + wg + " " + wb, wb > wr * 2f && wb > wg * 0.9f);
		float gr = bounce[overGrass * 3 + 0];
		float gg = bounce[overGrass * 3 + 1];
		float gb = bounce[overGrass * 3 + 2];
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
