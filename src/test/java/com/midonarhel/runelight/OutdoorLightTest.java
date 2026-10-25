package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class OutdoorLightTest
{
	@Test
	public void middayLightIsAsAuthored()
	{
		float[] out = new float[3];
		float scale = OutdoorLight.apply(60, 0.2f, 0.5f, 0.9f, out);
		assertEquals(1, scale, 1e-5f);
		assertArrayEquals(new float[]{0.2f, 0.5f, 0.9f}, out, 1e-5f);
	}

	@Test
	public void lightWeakensAsTheSunGoesDownAndKeepsALittleOfTheMoons()
	{
		float[] out = new float[3];
		assertEquals(1, OutdoorLight.apply(30, 1, 1, 1, out), 1e-5f);
		float horizon = OutdoorLight.apply(0, 1, 1, 1, out);
		assertTrue(horizon > 0.1f && horizon < 0.5f);
		assertTrue(OutdoorLight.apply(-8, 1, 1, 1, out) < 0.1f);
		// the moon's share, once it is high
		assertEquals(0.12f, OutdoorLight.apply(-40, 1, 1, 1, out), 1e-5f);
	}

	@Test
	public void lightIsWarmAtTheHorizonAndBlueAtNight()
	{
		float[] out = new float[3];
		OutdoorLight.apply(0, 1, 1, 1, out);
		assertTrue(out[0] > out[2]);
		OutdoorLight.apply(-30, 1, 1, 1, out);
		assertTrue(out[2] > out[0]);
	}
}
