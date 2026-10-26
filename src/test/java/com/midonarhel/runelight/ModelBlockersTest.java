package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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

	@Test
	public void aWallAtLightHeightBlocksItsCellsThinAsItIs()
	{
		VoxelCapture c = new VoxelCapture();
		// a one-unit-thin wall from the floor to 200 units, 64 long
		c.add(100, 0, 0, 100, -200, 0, 100, -200, 64, false);
		c.add(100, 0, 0, 100, -200, 64, 100, 0, 64, false);
		ModelBlockers m = assemble(c);
		// zone (1, 1) starts at cell 32; x = 100 is cell 3 of it
		assertTrue(m.blocked(0, 32 + 3, 32));
		assertTrue(m.blocked(0, 32 + 3, 33));
		assertFalse(m.blocked(0, 32 + 2, 32));
		assertFalse(m.blocked(0, 32 + 3, 34));
		assertFalse("not on the storey above", m.blocked(1, 32 + 3, 32));
	}

	@Test
	public void lowAndHighThingsDoNot()
	{
		VoxelCapture c = new VoxelCapture();
		// a tabletop at 60 and a ceiling at 240
		quad(c, 0, -60, 0, 64, -60, 64);
		quad(c, 128, -240, 0, 192, -240, 64);
		// a barrel's side up to 70
		c.add(300, 0, 0, 300, -70, 0, 300, -70, 40, false);
		ModelBlockers m = assemble(c);
		assertFalse(m.blocked(0, 32, 32));
		assertFalse(m.blocked(0, 32 + 4, 32));
		assertFalse(m.blocked(0, 32 + 9, 32));
	}

	@Test
	public void aShelfBoardInTheBandBlocksAndATiltedRoofWhereItCrossesIt()
	{
		VoxelCapture c = new VoxelCapture();
		quad(c, 0, -100, 0, 64, -100, 64);
		// a slope from 0 to 300 over 160 units in x: it is in the band around x = 40 to 70 above its start
		c.add(256, 0, 0, 416, -300, 0, 256, 0, 32, false);
		ModelBlockers m = assemble(c);
		assertTrue(m.blocked(0, 32 + 1, 32 + 1));
		assertTrue("the slope is in the band at x about 300 to 330", m.blocked(0, 32 + 9, 32) || m.blocked(0, 32 + 10, 32));
		assertFalse("not before it reaches the band", m.blocked(0, 32 + 8, 32) && m.blocked(0, 32 + 8, 32 + 1));
	}

	@Test
	public void eachStoreyHasItsOwnBand()
	{
		VoxelCapture c = new VoxelCapture();
		// 100 units over the first floor's: 340 up, or 100 over the ground floor's floor
		quad(c, 0, -340, 0, 64, -340, 64);
		ModelBlockers m = assemble(c);
		assertTrue(m.blocked(1, 32, 32));
		assertFalse(m.blocked(0, 32, 32));
	}

	@Test
	public void changesAreFound()
	{
		VoxelCapture c = new VoxelCapture();
		ModelBlockers a = assemble(c);
		quad(c, 0, -100, 0, 64, -100, 64);
		ModelBlockers b = assemble(c);
		assertNull(a.diff(a));
		int[] d = b.diff(a);
		assertEquals(32, d[0]);
		assertEquals(32, d[1]);
		assertTrue(d[2] >= 33 && d[3] >= 33);
	}
}
