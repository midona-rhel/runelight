package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FogDisturbanceTest
{
	private static FogDisturbance around(float x, float z)
	{
		FogDisturbance d = new FogDisturbance();
		d.recenter(x, z);
		return d;
	}

	@Test
	public void carvesASoftDiscStrongestAtTheCenter()
	{
		FogDisturbance d = around(5000, 5000);
		d.carve(5000, 5000, 160, 0.8f);
		float center = d.amountAt(5000, 5000);
		float mid = d.amountAt(5000 + 80, 5000);
		assertEquals(0.8f * 0.98f * 0.98f, center, 0.05f);
		assertTrue(mid > 0 && mid < center);
		assertEquals(0, d.amountAt(5000 + 200, 5000), 0);
		assertTrue(d.isDisturbed());
	}

	@Test
	public void carvingNeverPutsFogBack()
	{
		FogDisturbance d = around(5000, 5000);
		d.carve(5000, 5000, 160, 0.9f);
		float before = d.amountAt(5000, 5000);
		d.carve(5000, 5000, 160, 0.3f);
		assertEquals(before, d.amountAt(5000, 5000), 0);
	}

	@Test
	public void fogCreepsBackInAFewSeconds()
	{
		FogDisturbance d = around(5000, 5000);
		d.carve(5000, 5000, 160, 1);
		float start = d.amountAt(5000, 5000);
		for (int i = 0; i < 20; ++i)
		{
			d.relax(FogDisturbance.STEP_SECONDS);
		}
		// one second: down by e^(-1 / 2.5)
		assertEquals(start * Math.exp(-1 / FogDisturbance.RECOVERY_SECONDS), d.amountAt(5000, 5000), 0.01f);
		for (int i = 0; i < 400; ++i)
		{
			d.relax(FogDisturbance.STEP_SECONDS);
		}
		assertEquals(0, d.amountAt(5000, 5000), 0);
		assertFalse("quiet rows are skipped", d.isDisturbed());
	}

	@Test
	public void aWalkerLeavesATrailWithoutGaps()
	{
		FogDisturbance d = around(5000, 5000);
		// running: 2 tiles per 0.6 s, a step every STEP_SECONDS
		float x = 4800;
		for (int i = 0; i < 40; ++i)
		{
			d.relax(FogDisturbance.STEP_SECONDS);
			d.carve(x, 5000, 64 + FogDisturbance.MARGIN, 0.8f);
			x += 256 / 0.6f * FogDisturbance.STEP_SECONDS;
		}
		float newest = d.amountAt(x - 22, 5000);
		float oldest = d.amountAt(4800, 5000);
		assertTrue(newest > oldest);
		for (float tx = 4850; tx < x - 22; tx += 16)
		{
			assertTrue("gap at " + tx, d.amountAt(tx, 5000) > 0.1f);
		}
	}

	@Test
	public void movingTheWindowClearsOnlyWhatEntersIt()
	{
		FogDisturbance d = around(5000, 5000);
		d.carve(5000, 5000, 160, 1);
		d.recenter(5000 + 10 * FogDisturbance.CELL, 5000);
		// still inside the window: kept
		assertTrue(d.amountAt(5000, 5000) > 0.5f);
		// the cell that took the slot of the one at the far edge starts clean
		float edge = 5000 + 10 * FogDisturbance.CELL + FogDisturbance.EXTENT / 2f - 24;
		assertEquals(0, d.amountAt(edge, 5000), 0);
		// and a disturbance carved at the new far edge is not mirrored on the opposite side
		d.carve(edge, 5000, 100, 1);
		assertTrue(d.amountAt(edge, 5000) > 0.5f);
		assertEquals(0, d.amountAt(edge - FogDisturbance.EXTENT + 64, 5000), 0);
	}

	@Test
	public void jumpingAcrossTheMapStartsClean()
	{
		FogDisturbance d = around(5000, 5000);
		d.carve(5000, 5000, 160, 1);
		d.recenter(5000 + 3 * FogDisturbance.EXTENT, 5000 - 2 * FogDisturbance.EXTENT);
		assertEquals(0, d.amountAt(5000 + 3 * FogDisturbance.EXTENT, 5000 - 2 * FogDisturbance.EXTENT), 0);
		d.relax(1);
		assertFalse(d.isDisturbed() && d.amountAt(5000, 5000) > 0);
	}

	@Test
	public void carvingOutsideTheWindowIsIgnored()
	{
		FogDisturbance d = around(5000, 5000);
		d.carve(5000 + FogDisturbance.EXTENT, 5000, 160, 1);
		assertFalse(d.isDisturbed());
	}
}
