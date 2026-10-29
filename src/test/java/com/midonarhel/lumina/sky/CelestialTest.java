package com.midonarhel.lumina.sky;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class CelestialTest
{
	@Test
	public void daySchedule()
	{
		assertEquals(0, Celestial.altitudeAt(4), 1e-9);
		assertEquals(70, Celestial.altitudeAt(12), 1e-9);
		assertEquals(0, Celestial.altitudeAt(20), 1e-9);
		assertEquals(-35, Celestial.altitudeAt(0), 1e-9);
		assertEquals(-35, Celestial.altitudeAt(24), 1e-9);
		// dusk and dawn tones begin an hour before sunset and sunrise
		assertEquals(13.7, Celestial.altitudeAt(19), 0.05);
		assertEquals(-13.4, Celestial.altitudeAt(3), 0.05);
		// continuous with a matching slope at sunrise and sunset
		double eps = 1e-4;
		for (double h : new double[]{4, 20})
		{
			double before = (Celestial.altitudeAt(h) - Celestial.altitudeAt(h - eps)) / eps;
			double after = (Celestial.altitudeAt(h + eps) - Celestial.altitudeAt(h)) / eps;
			assertEquals(Celestial.altitudeAt(h - eps), Celestial.altitudeAt(h + eps), 1e-2);
			assertEquals("slope at " + h, before, after, 1e-2);
		}
		// east at 06:00 (26.8 degrees up, so x = cos(26.8) = 0.89), south at noon, west at 18:00
		Celestial c = new Celestial();
		c.update(6);
		assertTrue(c.sunDir[0] > 0.85 && Math.abs(c.sunDir[2]) < 1e-6);
		c.update(12);
		assertTrue(Math.abs(c.sunDir[0]) < 1e-6 && c.sunDir[2] < 0);
		c.update(18);
		assertTrue(c.sunDir[0] < -0.85 && Math.abs(c.sunDir[2]) < 1e-6);
	}

	@Test
	public void handoffDeadBand()
	{
		// the sun's light ends at -2, the moon's begins at -4: both zero at -3, where the caster flips
		assertEquals(0, Celestial.sunStrength(-2), 0);
		assertEquals(0, Celestial.sunStrength(-3), 0);
		assertEquals(0, Celestial.moonStrength(-3), 0);
		assertEquals(0, Celestial.moonStrength(-4), 0);
		assertTrue(Celestial.sunStrength(-1.9f) > 0);
		assertTrue(Celestial.moonStrength(-4.1f) > 0);

		// the hour the sun is at -3 (the night arc after sunset)
		double switchHour = 20 + 8 / Math.PI * Math.asin(3.0 / 35);
		assertEquals(-3, Celestial.altitudeAt(switchHour), 1e-9);
		Celestial c = new Celestial();
		c.update(switchHour - 0.01);
		assertEquals(Celestial.Caster.SUN, c.casterKind);
		assertEquals(0, c.casterStrength, 1e-6);
		assertEquals(0, c.casterEpoch);
		c.update(switchHour + 0.01);
		assertEquals(Celestial.Caster.MOON, c.casterKind);
		assertEquals(0, c.casterStrength, 1e-6);
		assertEquals(1, c.casterEpoch);
		// the night caster is the moon, opposite the sun
		assertTrue(c.casterDir[0] * c.sunDir[0] <= 0 && c.casterDir[2] * c.sunDir[2] <= 0);
	}

	@Test
	public void casterStaysWithinTheClamp()
	{
		Celestial c = new Celestial();
		for (double h = 0; h < 24; h += 0.05)
		{
			c.update(h);
			double e = c.casterElevation();
			double max = c.casterKind == Celestial.Caster.SUN ? Celestial.MAX_SUN_CAST_ELEVATION : Celestial.MAX_MOON_CAST_ELEVATION;
			assertTrue("hour " + h + " elevation " + e, e >= Celestial.MIN_CAST_ELEVATION - 1e-4 && e <= max + 1e-4);
			// unit vectors, y down
			for (float[] d : new float[][]{c.sunDir, c.moonDir, c.casterDir})
			{
				assertEquals(1, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-5);
			}
			assertTrue(c.casterDir[1] < 0);
		}
		// at noon the sun is at 70 and the caster at the 68 clamp
		c.update(12);
		assertEquals(70, c.sunAltitude, 1e-4);
		assertEquals(68, c.casterElevation(), 1e-3);
	}
}
