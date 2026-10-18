package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WeatherVariationTest
{
	// two hours at 60 fps
	private static final double DT = 1 / 60.0;
	private static final int FRAMES = 2 * 3600 * 60;

	@Test
	public void boundedAndVaried()
	{
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		int bursts = 0;
		boolean inBurst = false;
		for (int i = 0; i < FRAMES; ++i)
		{
			double t = i * DT;
			float v = WeatherVariation.intensity(t);
			assertTrue(v >= WeatherVariation.MIN - 1e-6f && v <= WeatherVariation.MAX + 1e-6f);
			float g = WeatherVariation.gust(t);
			assertTrue(g >= 0 && g <= 1);
			float w = WeatherVariation.windScale(t);
			assertTrue(w >= 0.6f - 1e-6f && w <= 1.4f + 1e-6f);
			assertTrue(Math.abs(WeatherVariation.windTurn(t)) <= WeatherVariation.WIND_TURN + 1e-6f);
			lo = Math.min(lo, v);
			hi = Math.max(hi, v);
			boolean b = WeatherVariation.burst(t) > 0.5f;
			if (b && !inBurst)
			{
				++bursts;
			}
			inBurst = b;
		}
		// lulls and swells both happen, and bursts are occasional: some, but not constantly
		assertTrue("lowest " + lo, lo < 0.75f);
		assertTrue("highest " + hi, hi > 1.4f);
		assertTrue("bursts " + bursts, bursts >= 5 && bursts <= 200);
	}

	@Test
	public void smooth()
	{
		float maxStep = 0, maxGustStep = 0;
		for (int i = 0; i < FRAMES; ++i)
		{
			double t = i * DT;
			maxStep = Math.max(maxStep, Math.abs(WeatherVariation.intensity(t + DT) - WeatherVariation.intensity(t)));
			maxGustStep = Math.max(maxGustStep, Math.abs(WeatherVariation.gust(t + DT) - WeatherVariation.gust(t)));
		}
		// a full swing takes seconds at least, never a jump between frames
		assertTrue("intensity step " + maxStep, maxStep < 0.01f);
		assertTrue("gust step " + maxGustStep, maxGustStep < 0.02f);
	}

	@Test
	public void deterministic()
	{
		for (double t = 0; t < 10000; t += 7.31)
		{
			assertEquals(WeatherVariation.intensity(t), WeatherVariation.intensity(t), 0);
			assertEquals(WeatherVariation.noise(t, 3), WeatherVariation.noise(t, 3), 0);
		}
		// continuous across lattice points, and the same far into a long session
		assertEquals(WeatherVariation.noise(5 - 1e-9, 1), WeatherVariation.noise(5, 1), 1e-6);
		double late = 40 * 24 * 3600.0;
		assertEquals(WeatherVariation.intensity(late), WeatherVariation.intensity(late), 0);
	}
}
