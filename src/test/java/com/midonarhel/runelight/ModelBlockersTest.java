package com.midonarhel.runelight;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelBlockersTest
{
	private static float[] floors()
	{
		float[] f = new float[4 * 64];
		for (int p = 0; p < 4; ++p)
		{
			java.util.Arrays.fill(f, p * 64, p * 64 + 64, -240f * p);
		}
		return f;
	}

	/** A quad of two triangles between the corners (x0, y0, z0) and (x1, y1, z1), vertical when x0 == x1 or z0 == z1. */
	private static void quad(VoxelCapture c, int x0, int y0, int z0, int x1, int y1, int z1)
	{
		if (x0 == x1)
		{
			c.add(x0, y0, z0, x0, y1, z0, x0, y1, z1, false);
			c.add(x0, y0, z0, x0, y1, z1, x0, y0, z1, false);
		}
		else if (z0 == z1)
		{
			c.add(x0, y0, z0, x1, y0, z0, x1, y1, z0, false);
			c.add(x0, y0, z0, x1, y1, z0, x0, y1, z0, false);
		}
		else
		{
			c.add(x0, y0, z0, x1, y0, z0, x1, y0, z1, false);
			c.add(x0, y0, z0, x1, y0, z1, x0, y0, z1, false);
		}
	}

	private static ModelBlockers assemble(VoxelCapture c)
	{
		return ModelBlockers.assemble(new ModelBlockers.Zone[]{ModelBlockers.rasterize(c, floors())}, new int[]{1}, new int[]{1}, 1, 128);
	}

	/** The box and heights of the run of the cell at a light's height, null when nothing is. */
	private static float[] box(ModelBlockers m, int plane, int gx, int gz, float height)
	{
		float[] out = new float[ModelBlockers.MAX_RUNS * ModelBlockers.RUN];
		int runs = m.runs(plane, gx, gz, ModelBlockers.bin(height), 0, out);
		for (int r = 0; r < runs; ++r)
		{
			if (out[r * ModelBlockers.RUN + 6] != 0)
			{
				return Arrays.copyOfRange(out, r * ModelBlockers.RUN, r * ModelBlockers.RUN + 6);
			}
		}
		return null;
	}

	/** A box with four sides, 12 units (x) and 8 (z) in size, as the prop in the tests. */
	private static void prop(VoxelCapture c, int x, int z, int lowY, int highY)
	{
		quad(c, x, lowY, z, x, highY, z + 8);
		quad(c, x + 12, lowY, z, x + 12, highY, z + 8);
		quad(c, x, lowY, z, x + 12, highY, z);
		quad(c, x, lowY, z + 8, x + 12, highY, z + 8);
	}

	@Test
	public void aThinWallAtLightHeightStopsLightAsThinAsItIs()
	{
		VoxelCapture c = new VoxelCapture();
		// a one-unit-thin wall from the floor to 200 units, 64 long
		quad(c, 100, 0, 0, 100, -200, 64);
		ModelBlockers m = assemble(c);
		// zone (1, 1) starts at cell 32; x = 100 is cell 3 of it, 4 units in
		float[] b = box(m, 0, 32 + 3, 32, 100);
		assertEquals(4, b[0] * 32, 0.01f);
		assertEquals(4, b[2] * 32, 0.01f);
		assertEquals("along the whole cell", 0, b[1], 0);
		assertEquals(1, b[3], 0);
		assertTrue(box(m, 0, 32 + 3, 33, 100) != null);
		assertEquals(null, box(m, 0, 32 + 2, 32, 100));
		assertEquals(null, box(m, 0, 32 + 3, 34, 100));
		assertEquals("not on the storey above", null, box(m, 1, 32 + 3, 32, 100));
	}

	@Test
	public void aLightOnlyMeetsWhatStandsAtItsHeight()
	{
		VoxelCapture c = new VoxelCapture();
		// a barrel, 70 high, and a pillar to 200 (both 12 x 8 units)
		prop(c, 8, 8, 0, -70);
		prop(c, 136, 8, 0, -200);
		ModelBlockers m = assemble(c);
		float[] barrel = box(m, 0, 32, 32, 40);
		assertEquals("the box of the barrel, not the cell", 8, barrel[0] * 32, 0.01f);
		assertEquals(20, barrel[2] * 32, 0.01f);
		assertEquals(8, barrel[1] * 32, 0.01f);
		assertEquals(16, barrel[3] * 32, 0.01f);
		assertTrue("a fire at its height", box(m, 0, 32, 32, 60) != null);
		assertEquals("a torch above it", null, box(m, 0, 32, 32, 150));
		assertEquals("a torch above it", null, box(m, 0, 32, 32, 120));
		assertTrue("a pillar stops either", box(m, 0, 32 + 4, 32, 40) != null && box(m, 0, 32 + 4, 32, 150) != null);
		assertEquals("one bin either side", null, box(m, 0, 32 + 4, 32, 230 + 40));
	}

	@Test
	public void aCellHasARunForEachSetOfHeightsAModelReaches()
	{
		VoxelCapture c = new VoxelCapture();
		// a barrel, 70 high, with an awning over it from 150 to 225
		prop(c, 8, 8, 0, -70);
		prop(c, 4, 4, -150, -224);
		ModelBlockers m = assemble(c);
		float[] out = new float[ModelBlockers.MAX_RUNS * ModelBlockers.RUN];
		assertEquals(2, m.runs(0, 32, 32, ModelBlockers.bin(40), 0, out));
		assertEquals(0, out[4], 0);
		assertEquals(75, out[5], 0);
		assertEquals("at the light", 1, out[6], 0);
		assertEquals(150, out[ModelBlockers.RUN + 4], 0);
		assertEquals(225, out[ModelBlockers.RUN + 5], 0);
		assertEquals("far above it", 0, out[ModelBlockers.RUN + 6], 0);
		assertEquals(0, m.runs(0, 32 + 2, 32, ModelBlockers.bin(40), 0, out));
	}

	@Test
	public void aCellKeepsWhatEachObjectIsAndLeavesOutTheOneAskedFor()
	{
		VoxelCapture c = new VoxelCapture();
		// a bracket of one object (a, 12 x 8 units, 90 to 120 high) and a barrel of another, in the same cell
		c.current = 5;
		prop(c, 4, 4, -90, -120);
		c.current = 6;
		prop(c, 14, 14, 0, -70);
		ModelBlockers m = assemble(c);
		float[] out = new float[ModelBlockers.MAX_RUNS * ModelBlockers.RUN];
		assertEquals(2, m.runs(0, 32, 32, ModelBlockers.bin(100), 0, out));
		assertEquals("without the bracket: the barrel", 1, m.runs(0, 32, 32, ModelBlockers.bin(100), 5, out));
		assertEquals(0, out[4], 0);
		assertEquals(75, out[5], 0);
		assertEquals("without the barrel: the bracket", 1, m.runs(0, 32, 32, ModelBlockers.bin(100), 6, out));
		assertEquals(90, out[4], 0);
		assertEquals(0, ModelBlockers.assemble(new ModelBlockers.Zone[]{ModelBlockers.rasterize(new VoxelCapture(), floors())},
			new int[]{1}, new int[]{1}, 1, 128).runs(0, 32, 32, 0, 0, out));
	}

	@Test
	public void aModelCoversTheColumnsOfTheCellItsSilhouetteReaches()
	{
		VoxelCapture c = new VoxelCapture();
		// 12 units across x and 8 along z, at 8 to 20 and 8 to 16 of the cell: columns of 4 units, 3 and 2, and the face
		// on the far edge, a line, is in the column that starts there: 4 and 3
		prop(c, 8, 8, 0, -70);
		ModelBlockers m = assemble(c);
		float[] out = new float[ModelBlockers.MAX_RUNS * ModelBlockers.RUN];
		assertEquals(1, m.runs(0, 32, 32, 0, 0, out));
		assertEquals(4 / 8f, out[7], 1e-6f);
		assertEquals(3 / 8f, out[8], 1e-6f);
	}

	@Test
	public void flatThingsAreLeftOut()
	{
		VoxelCapture c = new VoxelCapture();
		// a tabletop at 60, a ceiling at 240 and a floor
		quad(c, 0, -60, 0, 64, -60, 64);
		quad(c, 128, -240, 0, 192, -240, 64);
		quad(c, 256, 0, 0, 320, 0, 64);
		ModelBlockers m = assemble(c);
		for (int x = 0; x < 12; ++x)
		{
			assertEquals(0, m.heights(0, 32 + x, 32));
			assertEquals(0, m.heights(1, 32 + x, 32));
		}
	}

	@Test
	public void aSlopeIsInEachBinItCrosses()
	{
		VoxelCapture c = new VoxelCapture();
		// a slope from 0 to 300 over 160 units in x, 32 wide in z
		c.add(256, 0, 0, 416, -300, 0, 256, 0, 32, false);
		ModelBlockers m = assemble(c);
		int low = m.heights(0, 32 + 8, 32), high = m.heights(0, 32 + 12, 32);
		assertTrue("low in the first cell", (low & 1) != 0 && (low & 1 << 8) == 0);
		assertTrue("higher further on", Integer.numberOfTrailingZeros(high) > Integer.numberOfTrailingZeros(low));
	}

	@Test
	public void eachStoreyHasItsOwnHeights()
	{
		VoxelCapture c = new VoxelCapture();
		// 60 to 140 over the first floor's floor (300 to 380 over the ground's)
		quad(c, 100, -300, 0, 100, -380, 32);
		ModelBlockers m = assemble(c);
		assertEquals(0, m.heights(0, 32 + 3, 32));
		assertTrue(m.heights(1, 32 + 3, 32) != 0);
		assertTrue(box(m, 1, 32 + 3, 32, 100) != null);
	}

	@Test
	public void changesAreFound()
	{
		VoxelCapture c = new VoxelCapture();
		ModelBlockers a = assemble(c);
		prop(c, 8, 8, 0, -70);
		ModelBlockers b = assemble(c);
		CellChanges same = new CellChanges(128);
		a.diff(a, same);
		assertTrue(same.isEmpty());
		CellChanges ch = new CellChanges(128);
		b.diff(a, ch);
		assertEquals(1, ch.count());
		assertTrue(ch.any(32, 32, 32, 32));
		assertFalse(ch.any(33, 32, 60, 60));
		// a model that is the same in another snapshot is no change
		CellChanges again = new CellChanges(128);
		assemble(c).diff(b, again);
		assertTrue(again.isEmpty());
	}
}
