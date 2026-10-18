package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WeatherForecastTest
{
	private static final int SLOTS = 60000;
	private static final String[] BIOMES = {"GRASSLAND", "FOREST", "DESERT", "BARRENS", "SWAMP", "ARCTIC", "MOUNTAIN",
		"WILDERNESS", "TROPICAL"};

	private static float[] shares(String biome)
	{
		float[] n = new float[WeatherType.values().length];
		for (long s = 0; s < SLOTS; ++s)
		{
			n[WeatherForecast.at(s, biome).ordinal()]++;
		}
		for (int i = 0; i < n.length; ++i)
		{
			n[i] /= SLOTS;
		}
		return n;
	}

	private static float share(float[] n, WeatherType... types)
	{
		float sum = 0;
		for (WeatherType t : types)
		{
			sum += n[t.ordinal()];
		}
		return sum;
	}

	@Test
	public void deterministic()
	{
		for (String b : BIOMES)
		{
			for (long s = -50; s < 500; ++s)
			{
				assertEquals(WeatherForecast.at(s, b), WeatherForecast.at(s, b));
			}
		}
		// the forecast of the next slot is what that slot then gets
		WeatherForecast f = new WeatherForecast(1234);
		WeatherForecast g = new WeatherForecast(1234);
		for (long ms = 0; ms < 10 * 3_600_000L; ms += 37_000)
		{
			f.update("SWAMP", ms, 12);
			g.update("SWAMP", ms, 12);
			assertEquals(f.getCurrent(), g.getCurrent());
			WeatherType next = f.getNext();
			float minutes = f.getMinutesToNext();
			assertTrue(minutes > 0 && minutes <= 4.0001f);
			f.update("SWAMP", ms + (long) Math.ceil(minutes * 60_000) + 1, 12);
			assertEquals(next, f.getCurrent());
			f.update("SWAMP", ms, 12);
		}
		// unknown or missing biomes behave like the default
		assertEquals(WeatherForecast.at(77, null), WeatherForecast.at(77, WeatherForecast.DEFAULT_BIOME));
		assertEquals(WeatherForecast.at(77, "NOWHERE"), WeatherForecast.at(77, WeatherForecast.DEFAULT_BIOME));
	}

	@Test
	public void slotsFollowTheDay()
	{
		// the cycle starts at 09:00, so the first change is at 16:00: 7 game hours, 3.5 minutes of a 12 minute day
		WeatherForecast f = new WeatherForecast(0);
		f.update(null, 0, 12);
		assertEquals(3.5f, f.getMinutesToNext(), 1e-4f);
		f.update(null, 0, 24);
		assertEquals(7f, f.getMinutesToNext(), 1e-4f);
		// a third of a day per slot
		assertEquals(0, (long) Math.floor(WeatherForecast.gameDays(0, 12) * 3) - 1);
		assertEquals(2, (long) Math.floor(WeatherForecast.gameDays(4 * 60_000 + 3 * 30_000, 12) * 3));
	}

	@Test
	public void biomeOdds()
	{
		float[] desert = shares("DESERT");
		assertEquals(0, share(desert, WeatherType.SNOW, WeatherType.BLIZZARD), 0);
		assertTrue(share(desert, WeatherType.CLEAR, WeatherType.PARTLY_CLOUDY) > 0.75f);
		float desertStorm = share(desert, WeatherType.STORM);
		assertTrue(desertStorm > 0 && desertStorm < 0.03f);

		float[] arctic = shares("ARCTIC");
		assertEquals(0, share(arctic, WeatherType.RAIN, WeatherType.STORM), 0);
		assertTrue(share(arctic, WeatherType.SNOW, WeatherType.BLIZZARD) > 0.4f);
		assertTrue(share(arctic, WeatherType.BLIZZARD) > 0.05f);

		float[] mountain = shares("MOUNTAIN");
		assertTrue(share(mountain, WeatherType.SNOW, WeatherType.BLIZZARD) > 0.2f);

		float[] swamp = shares("SWAMP");
		assertTrue(share(swamp, WeatherType.FOG) > 0.25f);
		assertTrue(share(swamp, WeatherType.FOG, WeatherType.RAIN, WeatherType.STORM) > 0.55f);

		float[] tropical = shares("TROPICAL");
		assertTrue(share(tropical, WeatherType.RAIN, WeatherType.STORM) > 0.3f);
		assertEquals(0, share(tropical, WeatherType.SNOW, WeatherType.BLIZZARD), 0);

		float[] wilderness = shares("WILDERNESS");
		float[] grassland = shares("GRASSLAND");
		assertTrue(share(wilderness, WeatherType.CLEAR, WeatherType.PARTLY_CLOUDY)
			< share(grassland, WeatherType.CLEAR, WeatherType.PARTLY_CLOUDY) - 0.15f);

		// temperate biomes are a mix: nothing dominates, and there is both clear weather and rain
		for (float[] mix : new float[][]{grassland, shares("FOREST")})
		{
			for (float v : mix)
			{
				assertTrue(v < 0.4f);
			}
			assertTrue(share(mix, WeatherType.CLEAR) > 0.15f);
			assertTrue(share(mix, WeatherType.RAIN, WeatherType.STORM) > 0.15f);
		}

		// morning mist: fog is likelier in the slot that ends at 08:00
		int morning = 0, other = 0;
		for (long s = 0; s < SLOTS; ++s)
		{
			if (WeatherForecast.at(s, "FOREST") == WeatherType.FOG)
			{
				if (Math.floorMod(s, 3) == 0)
				{
					morning++;
				}
				else
				{
					other++;
				}
			}
		}
		assertTrue(morning > other / 2 * 1.3f);
	}

	@Test
	public void transitionsAreConnected()
	{
		for (String b : BIOMES)
		{
			float[] n = shares(b);
			float independentRepeat = 0;
			for (float v : n)
			{
				independentRepeat += v * v;
			}
			int repeats = 0, jumps = 0, fromClear = 0;
			WeatherType prev = WeatherForecast.at(-1, b);
			for (long s = 0; s < SLOTS; ++s)
			{
				WeatherType w = WeatherForecast.at(s, b);
				if (w == prev)
				{
					repeats++;
				}
				if (prev == WeatherType.CLEAR)
				{
					fromClear++;
					if (w == WeatherType.STORM || w == WeatherType.BLIZZARD)
					{
						jumps++;
					}
				}
				prev = w;
			}
			// weather persists more than independent draws would, but still changes most of the time it can
			float repeat = repeats / (float) SLOTS;
			assertTrue(b + " repeat " + repeat, repeat > independentRepeat + 0.05f);
			assertTrue(b + " repeat " + repeat, repeat < 0.75f);
			// clear skies rarely turn straight into a storm
			assertTrue(b, fromClear == 0 || jumps / (float) fromClear < 0.03f);
		}
	}

	@Test
	public void lightningIsGentle()
	{
		Lightning l = new Lightning();
		float dt = 1 / 60f;
		int frames = 3600 * 60;
		int strikes = 0;
		float lastStrike = -100;
		float minGap = Float.MAX_VALUE;
		boolean wasLit = false;
		float prev = 0;
		boolean rising = false;
		int peaksInWindow = 0;
		float windowStart = 0;
		int maxPeaksPerSecond = 0;
		for (int i = 0; i < frames; ++i)
		{
			float t = i * dt;
			l.update(dt, 1, true, 0, 0);
			float f = l.getFlash();
			assertTrue(f >= 0 && f <= 1);
			boolean lit = f > 0;
			if (lit && !wasLit)
			{
				strikes++;
				minGap = Math.min(minGap, t - lastStrike);
				lastStrike = t;
			}
			wasLit = lit;
			// count flicker peaks per second
			if (f < prev && rising)
			{
				if (t - windowStart > 1)
				{
					windowStart = t;
					peaksInWindow = 0;
				}
				peaksInWindow++;
				maxPeaksPerSecond = Math.max(maxPeaksPerSecond, peaksInWindow);
			}
			rising = f > prev;
			prev = f;
		}
		// one strike every ~5-25 s in a full storm, and never two within 5 s
		assertTrue("strikes " + strikes, strikes > 3600 / 25 && strikes < 3600 / 5);
		assertTrue("gap " + minGap, minGap >= 5);
		assertTrue("peaks " + maxPeaksPerSecond, maxPeaksPerSecond <= 3);

		// no storm, no lightning
		Lightning calm = new Lightning();
		for (int i = 0; i < 600 * 60; ++i)
		{
			calm.update(dt, 0.3f, true, 0, 0);
			assertEquals(0, calm.getFlash(), 0);
			calm.update(dt, 1, false, 0, 0);
			assertEquals(0, calm.getFlash(), 0);
		}
	}

	@Test
	public void clearWeatherIsLight()
	{
		// a quarter of the old clear haze (0.6), and fog is where the thick fog is
		assertEquals(0.15f, WeatherType.CLEAR.fog, 1e-6f);
		for (WeatherType t : WeatherType.values())
		{
			assertTrue(t.fog <= WeatherType.FOG.fog);
			assertTrue(t.cover >= 0 && t.cover <= 1);
		}
		assertEquals(1, Weather.morning(6.5), 1e-6f);
		assertEquals(0, Weather.morning(14), 0);
		assertEquals(0, Weather.morning(0), 0);
		// easing approaches the target without overshooting
		float v = 0;
		for (int i = 0; i < 60 * 60; ++i)
		{
			float next = Weather.ease(v, 1, 1 / 60f, 15);
			assertTrue(next >= v && next <= 1);
			v = next;
		}
		assertTrue(v > 0.95f);
	}
}
