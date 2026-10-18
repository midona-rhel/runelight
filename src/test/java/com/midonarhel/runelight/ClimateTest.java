package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import com.midonarhel.runelight.WeatherClimate.Season;
import org.junit.Before;
import org.junit.Test;

public class ClimateTest
{
	private static final int SEASON_DAYS = 7;
	private static final long H = GameClock.HOUR;
	private static final long DAY = GameClock.DAY;
	private static final long YEAR = 4 * SEASON_DAYS * DAY;
	// some years after the epoch
	private static final long T0 = 37 * YEAR;

	@Before
	public void load()
	{
		WeatherTestData.load();
	}

	private static float[] range(String biome, Season season)
	{
		float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
		long start = T0 + season.ordinal() * SEASON_DAYS * DAY;
		for (long t = start; t < start + SEASON_DAYS * DAY; t += 37 * 60_000L)
		{
			float c = Climate.temperature(biome, t, SEASON_DAYS);
			lo = Math.min(lo, c);
			hi = Math.max(hi, c);
		}
		return new float[]{lo, hi};
	}

	@Test
	public void temperaturesByBiomeAndSeason()
	{
		for (Season s : WeatherClimate.SEASONS)
		{
			// cold regions are always below zero
			assertTrue(range("ARCTIC", s)[1] < 0);
			assertTrue(range("MOUNTAIN", s)[1] < 0);
			assertTrue(range("TROPICAL", s)[0] > 12);
		}
		assertTrue(range("DESERT", Season.SUMMER)[0] > 15);
		assertTrue(range("DESERT", Season.SUMMER)[1] > 35);
		assertTrue(range("GRASSLAND", Season.SUMMER)[0] > 0);
		// a temperate winter swings either side of zero
		float[] w = range("GRASSLAND", Season.WINTER);
		assertTrue(w[0] < -3 && w[1] > 3);
		// and summer is warmer than winter
		assertTrue(range("FOREST", Season.SUMMER)[0] > range("FOREST", Season.WINTER)[0]);
	}

	@Test
	public void afternoonsAreWarmerThanMornings()
	{
		double morning = 0, afternoon = 0;
		for (long d = 0; d < 4 * SEASON_DAYS; ++d)
		{
			long day = T0 + d * DAY;
			morning += Climate.temperature("GRASSLAND", day + 4 * H, SEASON_DAYS);
			afternoon += Climate.temperature("GRASSLAND", day + 15 * H, SEASON_DAYS);
		}
		assertTrue(afternoon > morning + 4 * 4 * SEASON_DAYS);
	}

	@Test
	public void smoothInTime()
	{
		// no jumps at slot or season boundaries: a game minute never moves it more than a fraction of a degree
		float prev = Climate.temperature("BARRENS", T0, SEASON_DAYS);
		for (long t = T0; t < T0 + YEAR; t += 60_000L)
		{
			float c = Climate.temperature("BARRENS", t, SEASON_DAYS);
			assertTrue("at " + t, Math.abs(c - prev) < 0.15f);
			prev = c;
		}
	}

	@Test
	public void precipitationFollowsTemperature()
	{
		WeatherType rain = WeatherTestData.type("RAIN");
		WeatherType snow = WeatherTestData.type("SNOW");
		WeatherType asSnow = Climate.withTemperature(rain, -5);
		assertEquals(0, asSnow.rain, 0);
		assertEquals(rain.rain, asSnow.snow, 1e-6f);
		assertTrue(asSnow.whitens());
		assertSame(asSnow, Climate.withTemperature(rain, -6));
		WeatherType asRain = Climate.withTemperature(snow, 8);
		assertEquals(0, asRain.snow, 0);
		assertEquals(snow.snow, asRain.rain, 1e-6f);
		WeatherType sleet = Climate.withTemperature(rain, 1);
		assertTrue(sleet.rain > 0 && sleet.snow > 0);
		// as it is where it fits, and dust never turns to rain
		assertSame(rain, Climate.withTemperature(rain, 10));
		assertSame(snow, Climate.withTemperature(snow, -10));
		WeatherType sand = WeatherTestData.type("SANDSTORM");
		assertSame(sand, Climate.withTemperature(sand, -20));
		assertSame(sand, Climate.withTemperature(sand, 30));
		// the original is untouched
		assertEquals(0, rain.snow, 0);
	}

	@Test
	public void frozenRegionsAlwaysHaveSnow()
	{
		Climate.Region r = new Climate.Region("ARCTIC");
		for (long t = T0; t < T0 + YEAR; t += 5 * H + 1234)
		{
			r.update(t, SEASON_DAYS);
			assertTrue(r.snowCm >= Climate.FROZEN_SNOW_CM);
			assertEquals(1, r.snowCover(), 0);
			assertTrue(r.temperature < 0);
			assertEquals(0, r.type.rain, 0);
		}
	}

	@Test
	public void snowStaysUntilItThaws()
	{
		Climate.Region r = new Climate.Region("GRASSLAND");
		r.update(T0, SEASON_DAYS);
		float prev = r.snowCm;
		boolean snowed = false, melted = false, kept = false;
		for (long hour = T0 / H; hour < (T0 + YEAR) / H; ++hour)
		{
			long t = (hour + 1) * H;
			r.update(t, SEASON_DAYS);
			long mid = hour * H + H / 2;
			float c = Climate.temperature("GRASSLAND", mid, SEASON_DAYS);
			WeatherType w = Climate.withTemperature(WeatherForecast.at(WeatherForecast.slot(mid), "GRASSLAND",
				SEASON_DAYS), c);
			boolean falling = w.whitens() && w.snow > 0;
			if (!falling && c <= 0)
			{
				// it stopped snowing but it's freezing: the snow stays (deep snow only settles)
				assertTrue(r.snowCm >= Math.min(prev, Climate.FULL_COVER_CM) - 1e-4f);
				kept |= prev > 1 && !falling;
			}
			if (!falling)
			{
				assertTrue(r.snowCm <= prev + 1e-4f);
			}
			if (c > 0 && prev > 0 && r.snowCm < prev - 1e-4f)
			{
				melted = true;
			}
			snowed |= r.snowCm >= Climate.FULL_COVER_CM;
			assertTrue(r.wet >= 0 && r.wet <= 1);
			prev = r.snowCm;
		}
		assertTrue(snowed);
		assertTrue(melted);
		assertTrue(kept);
		// a temperate summer has no snow
		r.update(T0 + YEAR + (SEASON_DAYS + 3) * DAY, SEASON_DAYS);
		assertEquals(0, r.snowCm, 0);
	}

	@Test
	public void sameGroundHoweverYouGotThere()
	{
		for (String biome : new String[]{"GRASSLAND", "FOREST", "BARRENS", "MOUNTAIN", "SWAMP"})
		{
			for (long t = T0 + 3 * SEASON_DAYS * DAY; t < T0 + 2 * YEAR; t += 9 * DAY + 7 * H + 12345)
			{
				// two clients arriving at the same moment
				Climate.Region a = new Climate.Region(biome), b = new Climate.Region(biome);
				a.update(t, SEASON_DAYS);
				b.update(t, SEASON_DAYS);
				assertEquals(a.snowCm, b.snowCm, 0);
				assertEquals(a.wet, b.wet, 0);
				assertEquals(a.temperature, b.temperature, 0);
				assertSame(a.type, b.type);
				// one that has been playing for days
				Climate.Region c = new Climate.Region(biome);
				c.update(t - 3 * DAY, SEASON_DAYS);
				for (long u = t - 3 * DAY; u < t; u += 41 * 60_000L)
				{
					c.update(u, SEASON_DAYS);
				}
				c.update(t, SEASON_DAYS);
				assertEquals(biome + " at " + t, a.snowCm, c.snowCm, 0.5f);
				assertEquals(a.wet, c.wet, 0.05f);
				assertEquals(a.temperature, c.temperature, 0);
			}
		}
	}

	@Test
	public void incrementalAndCheap()
	{
		Climate.Region r = new Climate.Region("GRASSLAND");
		r.update(T0, SEASON_DAYS);
		long first = r.steps;
		assertEquals(Climate.historyHours(SEASON_DAYS) + 1, first);
		// asking again within the hour costs nothing; an hour on costs one step
		r.update(T0 + H / 2, SEASON_DAYS);
		assertEquals(first, r.steps);
		r.update(T0 + H + 1, SEASON_DAYS);
		assertEquals(first + 1, r.steps);
		// going back in time (a changed day length) starts over, with the same result as a fresh region
		r.update(T0 - 5 * DAY, SEASON_DAYS);
		Climate.Region fresh = new Climate.Region("GRASSLAND");
		fresh.update(T0 - 5 * DAY, SEASON_DAYS);
		assertEquals(fresh.snowCm, r.snowCm, 0);
		assertEquals(fresh.wet, r.wet, 0);
	}

	@Test
	public void smoothGround()
	{
		// the ground moves in small steps from one game minute to the next: no snapping when weather changes
		Climate.Region r = new Climate.Region("FOREST");
		long start = T0 + 3 * SEASON_DAYS * DAY;
		r.update(start, SEASON_DAYS);
		float snow = r.snowCover(), wet = r.wet;
		for (long t = start; t < start + SEASON_DAYS * DAY; t += 60_000)
		{
			r.update(t, SEASON_DAYS);
			assertTrue(Math.abs(r.snowCm / Climate.FULL_COVER_CM - Math.min(snow, 1)) < 0.2f || r.snowCm
				>= Climate.FULL_COVER_CM);
			assertTrue(Math.abs(r.wet - wet) < 0.06f);
			snow = Math.min(1, r.snowCm / Climate.FULL_COVER_CM);
			wet = r.wet;
		}
	}

	@Test
	public void regionsByBiome()
	{
		Climate c = new Climate();
		c.update(T0 + 100 * H, SEASON_DAYS);
		Climate.Region r = c.region("SWAMP");
		assertNotNull(r);
		assertSame(r, c.region("SWAMP"));
		// unknown biomes are the default biome
		assertSame(c.region(null), c.region("NOWHERE"));
		assertSame(c.region(null), c.region(WeatherClimate.defaultBiome()));
		float[] g = c.ground("SWAMP");
		assertEquals(r.wet, g[0], 0);
		assertEquals(r.snowCover(), g[1], 0);
		assertSame(r.type, c.type("SWAMP"));
	}
}
