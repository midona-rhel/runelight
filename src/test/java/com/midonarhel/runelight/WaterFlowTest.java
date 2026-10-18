package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.io.IOException;
import java.io.InputStream;
import org.junit.Test;

/**
 * The shipped world flow map: rivers whose direction is known run that way.
 */
public class WaterFlowTest
{
	private static WaterFlow flow() throws IOException
	{
		try (InputStream in = WaterFlow.class.getResourceAsStream(WaterFlow.RESOURCE))
		{
			assertTrue("resource present", in != null);
			return WaterFlow.read(in);
		}
	}

	private static float[] at(WaterFlow f, int x, int y)
	{
		float[] v = new float[2];
		assertTrue("flow known at " + x + ", " + y, f.flow(x, y, v));
		return v;
	}

	private static void runs(WaterFlow f, int x, int y, double towardX, double towardY)
	{
		float[] v = at(f, x, y);
		double len = Math.hypot(v[0], v[1]);
		assertTrue("moving at " + x + ", " + y, len > 0.2);
		double cos = (v[0] * towardX + v[1] * towardY) / (len * Math.hypot(towardX, towardY));
		assertTrue("direction at " + x + ", " + y + ": " + v[0] + ", " + v[1], cos > 0.5);
	}

	@Test
	public void loads() throws IOException
	{
		assertTrue(flow().regionCount() > 1000);
	}

	@Test
	public void riverLumRunsSouthPastLumbridge() throws IOException
	{
		WaterFlow f = flow();
		runs(f, 3236, 3247, 0, -1);
		runs(f, 3109, 3426, 0, -1);
	}

	@Test
	public void riverElidRunsSouthThroughTheDesert() throws IOException
	{
		runs(flow(), 3368, 3003, 0, -1);
	}

	@Test
	public void ardougneRiverRunsSouthWestToYanille() throws IOException
	{
		WaterFlow f = flow();
		runs(f, 2591, 3353, 0, -1);
		runs(f, 2390, 3100, -1, -2);
	}

	@Test
	public void riverSalveRunsSouth() throws IOException
	{
		runs(flow(), 3397, 3439, 0, -1);
	}

	@Test
	public void openSeaIsStill() throws IOException
	{
		float[] v = at(flow(), 3000, 3000);
		assertEquals(0, v[0], 1e-6);
		assertEquals(0, v[1], 1e-6);
	}

	@Test
	public void dryLandIsUnknown() throws IOException
	{
		// Lumbridge castle courtyard
		assertFalse(flow().flow(3222, 3218, new float[2]));
	}

	@Test
	public void instanceRotationTurnsWorldDirections()
	{
		// a chunk placed with rotation 1 maps scene +x to world -y (EnvironmentManager.toWorld)
		float[] v = {0, -1};
		WaterMap.rotateToScene(1, v);
		assertEquals(1, v[0], 1e-6);
		assertEquals(0, v[1], 1e-6);
		v = new float[]{0, 1};
		WaterMap.rotateToScene(3, v);
		assertEquals(1, v[0], 1e-6);
		assertEquals(0, v[1], 1e-6);
		v = new float[]{1, 2};
		WaterMap.rotateToScene(2, v);
		assertEquals(-1, v[0], 1e-6);
		assertEquals(-2, v[1], 1e-6);
	}
}
