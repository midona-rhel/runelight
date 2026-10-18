package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class FogClimateTest
{
	private static final String[] BIOMES = {"GRASSLAND", "FOREST", "SWAMP", "TROPICAL", "DESERT", "BARRENS", "ARCTIC",
		"MOUNTAIN", "WILDERNESS"};

	/** The fog multiplier Weather eases toward in calm weather of this type. */
	private static float fog(String biome, double hour, WeatherType type)
	{
		FogClimate.Profile p = FogClimate.profile(biome);
		return FogClimate.total(type.fog, FogClimate.density(p, hour, FogClimate.calm(type), type.cover));
	}

	@Test
	public void desertMorningHasNoMist()
	{
		float f = fog("DESERT", 6.5, WeatherType.CLEAR);
		assertTrue("desert dawn " + f, f < 0.08f);
		// thinner than a clear midday elsewhere
		assertTrue(f < fog("GRASSLAND", 12, WeatherType.CLEAR) * 0.5f);
	}

	@Test
	public void swampMorningIsThick()
	{
		float f = fog("SWAMP", 6.5, WeatherType.CLEAR);
		assertTrue("swamp dawn " + f, f > 1.0f);
		// and lies low
		float h = FogClimate.height(FogClimate.profile("SWAMP"), 6.5, 1, 0);
		assertTrue("swamp dawn height " + h, h < 0.7f);
		// burns off by late morning
		assertTrue(fog("SWAMP", 11, WeatherType.CLEAR) < f * 0.3f);
	}

	@Test
	public void clearMiddayIsLight()
	{
		for (String b : BIOMES)
		{
			float f = fog(b, 12.5, WeatherType.CLEAR);
			assertTrue(b + " midday " + f, f <= 0.25f);
		}
	}

	@Test
	public void humidRegionsMistMoreAtDawnThanDryOnes()
	{
		float swamp = fog("SWAMP", 6.5, WeatherType.CLEAR);
		float forest = fog("FOREST", 6.5, WeatherType.CLEAR);
		float grass = fog("GRASSLAND", 6.5, WeatherType.CLEAR);
		float barrens = fog("BARRENS", 6.5, WeatherType.CLEAR);
		assertTrue(swamp > forest && forest > grass && grass > barrens);
		// dusk and night mist in the swamp, little on the grassland
		assertTrue(fog("SWAMP", 20, WeatherType.CLEAR) > 2 * fog("GRASSLAND", 20, WeatherType.CLEAR));
		assertTrue(fog("SWAMP", 2, WeatherType.CLEAR) > fog("SWAMP", 13, WeatherType.CLEAR) * 2);
	}

	@Test
	public void windAndRainDisperseMist()
	{
		assertEquals(0, FogClimate.calm(WeatherType.STORM), 1e-6);
		assertEquals(1, FogClimate.calm(WeatherType.CLEAR), 1e-6);
		float stormDawn = FogClimate.density(FogClimate.profile("SWAMP"), 6.5, FogClimate.calm(WeatherType.STORM), 1);
		float stormNoon = FogClimate.density(FogClimate.profile("SWAMP"), 12.5, FogClimate.calm(WeatherType.STORM), 1);
		assertEquals(stormNoon, stormDawn, 1e-6);
	}

	@Test
	public void desertHazeAtMiddayOnlyInSun()
	{
		FogClimate.Profile d = FogClimate.profile("DESERT");
		assertTrue(FogClimate.density(d, 12.5, 1, 0) > FogClimate.density(d, 6.5, 1, 0) * 1.5f);
		assertEquals(d.humidity, FogClimate.density(d, 12.5, 1, 1), 1e-6);
		// haze rises instead of lying low
		assertTrue(FogClimate.height(d, 12.5, 1, 0) > 1.2f);
	}

	@Test
	public void foggyWeatherIsCappedSoftly()
	{
		float f = fog("SWAMP", 6.5, WeatherType.FOG);
		assertTrue(f < FogClimate.FOG_CAP && f > FogClimate.FOG_KNEE);
		// below the knee nothing changes
		assertEquals(1.5f, FogClimate.total(1.5f, 1), 1e-6);
		// monotonic
		float last = 0;
		for (float x = 0; x < 20; x += 0.1f)
		{
			float t = FogClimate.total(x, 1);
			assertTrue(t >= last);
			last = t;
		}
	}

	@Test
	public void smoothOverTheDay()
	{
		// no jumps anywhere around the clock, midnight included, and unknown biomes behave like grassland
		for (String b : new String[]{"SWAMP", "DESERT", "ARCTIC", null, "NOT_A_BIOME"})
		{
			FogClimate.Profile p = FogClimate.profile(b);
			float last = FogClimate.density(p, -0.01, 1, 0);
			float lastH = FogClimate.height(p, -0.01, 1, 0);
			for (double h = 0; h <= 24.01; h += 0.01)
			{
				float d = FogClimate.density(p, h, 1, 0);
				float hh = FogClimate.height(p, h, 1, 0);
				assertTrue(b + " at " + h, Math.abs(d - last) < 0.05f);
				assertTrue(b + " height at " + h, Math.abs(hh - lastH) < 0.02f);
				last = d;
				lastH = hh;
			}
		}
		assertEquals(FogClimate.profile("GRASSLAND"), FogClimate.profile(null));
	}
}
