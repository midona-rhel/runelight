package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.function.Function;
import org.junit.Test;

public class WeatherFieldTest
{
	private static final int N = 23;
	private static final float EPS = 1.5f / 255;
	// west half rains, east half is clear
	private static final WeatherType RAIN = WeatherTestData.type("RAIN");
	private static final WeatherType CLEAR = WeatherTestData.type("CLEAR");
	private static final Function<String, WeatherType> TYPES = b -> "WET".equals(b) ? RAIN : CLEAR;

	private static WeatherField field(int split, int shift, boolean gradual)
	{
		WeatherField f = new WeatherField();
		layout(f, split, shift);
		f.update(true, TYPES, 0.6f, gradual, false, 12, 0);
		return f;
	}

	/** Cells west of {@code split} (in world chunks) are rainy; the scene starts {@code shift} chunks east. */
	private static void layout(WeatherField f, int split, int shift)
	{
		layout(f, split, shift, "WET", null);
	}

	private static void layout(WeatherField f, int split, int shift, String west, String east)
	{
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int z = 0; z < N; ++z)
		{
			for (int x = 0; x < N; ++x)
			{
				int wx = x + shift;
				keys[z * N + x] = WeatherField.chunkKey(wx * 8, z * 8);
				biomes[z * N + x] = wx < split ? west : east;
			}
		}
		f.layout(N, N, (shift - 11) * WeatherField.CELL, -11 * WeatherField.CELL, keys, biomes);
	}

	private static float center(int x)
	{
		return (x + 0.5f) * WeatherField.CELL;
	}

	@Test
	public void insideARegionItsOwnWeather()
	{
		WeatherField f = field(12, 0, true);
		float[] t = new float[WeatherField.TARGETS];
		WeatherField.targets(RAIN, "WET", 0.6f, false, 12, t);
		float ox = f.getOriginX(), oz = f.getOriginZ();
		// at least the blur away from the border
		assertEquals(t[WeatherField.RAIN], f.sample(WeatherField.RAIN, ox + center(3), oz + center(10)), EPS);
		assertEquals(t[WeatherField.COVER], f.sample(WeatherField.COVER, ox + center(3), oz + center(10)), EPS);
		assertEquals(0, f.sample(WeatherField.RAIN, ox + center(20), oz + center(10)), EPS);
		// it has been raining in the rainy region
		assertEquals(1, f.sample(WeatherField.WET, ox + center(3), oz + center(10)), EPS);
		assertEquals(0, f.sample(WeatherField.WET, ox + center(20), oz + center(10)), EPS);
	}

	@Test
	public void bordersBlendSmoothly()
	{
		WeatherField f = field(12, 0, true);
		float ox = f.getOriginX(), oz = f.getOriginZ() + center(10);
		float full = f.sample(WeatherField.RAIN, ox + center(2), oz);
		float prev = full;
		float steepest = 0;
		// a tile at a time across the border: never rising, no jump bigger than a small share of the change
		for (float x = center(2); x <= center(21); x += 128)
		{
			float v = f.sample(WeatherField.RAIN, ox + x, oz);
			assertTrue("rain rises at " + x, v <= prev + 1e-6f);
			steepest = Math.max(steepest, prev - v);
			prev = v;
		}
		assertTrue("too sharp: " + steepest, steepest < full / 20);
		// the change is spread over several chunks either side of the border
		float atBorder = f.sample(WeatherField.RAIN, ox + 12 * WeatherField.CELL, oz);
		assertEquals(full * 0.5f, atBorder, full * 0.1f);
		assertTrue(f.sample(WeatherField.RAIN, ox + 12 * WeatherField.CELL - 2 * WeatherField.CELL, oz) < full - EPS);
		assertTrue(f.sample(WeatherField.RAIN, ox + 12 * WeatherField.CELL + 2 * WeatherField.CELL, oz) > EPS);
	}

	@Test
	public void deterministic()
	{
		WeatherField a = field(9, 0, true);
		WeatherField b = field(9, 0, true);
		for (int c = 0; c < WeatherField.CHANNELS; ++c)
		{
			for (float x = -12000; x < 12000; x += 777)
			{
				assertEquals(a.sample(c, x, x * 0.3f), b.sample(c, x, x * 0.3f), 0);
			}
		}
		// and the forecast for a biome only depends on the slot
		WeatherForecast fa = new WeatherForecast(), fb = new WeatherForecast();
		fa.update("SWAMP", 1_000_000_000, 12, 7);
		fb.update("DESERT", 1_000_000_000, 12, 7);
		for (String biome : new String[]{null, "SWAMP", "ARCTIC", "DESERT"})
		{
			assertEquals(fa.currentFor(biome), fb.currentFor(biome));
		}
		assertEquals(fa.getCurrent(), fa.currentFor("SWAMP"));
	}

	@Test
	public void easesOverTime()
	{
		// clear everywhere, then the west turns rainy: cover builds before the rain, the ground wets later
		WeatherField f = new WeatherField();
		layout(f, -1, 0);
		f.update(true, TYPES, 0.6f, true, false, 12, 0);
		layout(f, 12, 0);
		float x = f.getOriginX() + center(3), z = f.getOriginZ() + center(10);
		f.update(true, TYPES, 0.6f, true, false, 12, 0.25f);
		float cover = f.sample(WeatherField.COVER, x, z);
		assertTrue(cover > 0 && cover < 0.1f);
		assertEquals(0, f.sample(WeatherField.RAIN, x, z), 0);
		for (int i = 0; i < 4 * 60 * 4; ++i)
		{
			f.update(true, TYPES, 0.6f, true, false, 12, 0.25f);
		}
		assertTrue(f.sample(WeatherField.RAIN, x, z) > 0.5f);
		assertTrue(f.sample(WeatherField.WET, x, z) > 0.9f);
	}

	@Test
	public void reloadingTheSceneKeepsTheWeather()
	{
		WeatherField f = new WeatherField();
		layout(f, -1, 0);
		f.update(true, TYPES, 0.6f, true, false, 12, 0);
		layout(f, 12, 0);
		for (int i = 0; i < 40; ++i)
		{
			f.update(true, TYPES, 0.6f, true, false, 12, 0.25f);
		}
		float[] before = new float[N];
		for (int x = 0; x < N; ++x)
		{
			before[x] = f.cell(WeatherField.COVER, x, 10);
		}
		// the player walked east: the scene moves 3 chunks, the same chunks keep their (eased, unfinished) weather
		layout(f, 12, 3);
		for (int x = 0; x + 3 < N; ++x)
		{
			assertEquals(before[x + 3], f.cell(WeatherField.COVER, x, 10), 0);
		}
	}

	@Test
	public void focusSeesNoDifference()
	{
		WeatherField f = field(12, 0, true);
		float x = f.getOriginX() + 11.3f * WeatherField.CELL, z = f.getOriginZ() + 7.6f * WeatherField.CELL;
		f.focus(x, z);
		for (int c = 0; c < WeatherField.CHANNELS; ++c)
		{
			assertEquals(f.sample(c, x, z), f.getFocus(c), 0);
		}
		// clouds: rainier to the west, clearer to the east
		assertTrue(f.cloudDelta(2, 10) > 0.2f);
		assertTrue(f.cloudDelta(f.getCloudCellsX() - 2, 10) < -0.3f);
		// off: no differences at all
		f.update(false, TYPES, 0.6f, true, false, 12, 0);
		f.focus(x, z);
		for (int i = 0; i < f.getCloudCellsX(); ++i)
		{
			assertEquals(0, f.cloudDelta(i, 10), 0);
		}
	}

	@Test
	public void noWeatherWithoutSky()
	{
		WeatherField f = new WeatherField();
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		java.util.Arrays.fill(biomes, WeatherField.NO_SKY);
		f.layout(N, N, 0, 0, keys, biomes);
		f.update(true, b -> WeatherTestData.type("THUNDERSTORM"), 1, true, true, 6, 0);
		assertTrue(!f.hasAnything());
		assertEquals(0, f.max(WeatherField.RAIN), 0);
	}

	@Test
	public void buildupFollowsTheType()
	{
		float[][] s = new float[WeatherField.CHANNELS][1];
		float[] t = new float[WeatherField.TARGETS];
		// dust and ash fall in the snow pass but never whiten the ground
		for (String id : new String[]{"SANDSTORM", "ASH_FALL"})
		{
			s[WeatherField.SNOW_COVER][0] = 0;
			s[WeatherField.COVER][0] = 1;
			WeatherField.targets(WeatherTestData.type(id), null, 0.6f, false, 12, t);
			assertTrue(t[WeatherField.SNOW] > 0);
			for (int i = 0; i < 4 * 600; ++i)
			{
				WeatherField.step(s, 0, t, 0.25f);
			}
			assertTrue(s[WeatherField.SNOW][0] > 0.2f);
			assertEquals(id, 0, s[WeatherField.SNOW_COVER][0], 0);
			assertEquals(0, s[WeatherField.WET][0], 0);
		}
		// freezing fog leaves frost, and only that much
		WeatherType frost = WeatherTestData.type("FREEZING_FOG");
		WeatherField.targets(frost, null, 0.6f, false, 12, t);
		s[WeatherField.SNOW_COVER][0] = 0;
		for (int i = 0; i < 4 * 1200; ++i)
		{
			WeatherField.step(s, 0, t, 0.25f);
		}
		assertEquals(frost.frost, s[WeatherField.SNOW_COVER][0], 0.02f);
		// a monsoon soaks the ground faster than drizzle
		float[] wet = new float[2];
		String[] ids = {"DRIZZLE", "MONSOON"};
		for (int k = 0; k < 2; ++k)
		{
			java.util.Arrays.fill(s[WeatherField.WET], 0);
			s[WeatherField.COVER][0] = 1;
			s[WeatherField.RAIN][0] = 0.01f;
			WeatherField.targets(WeatherTestData.type(ids[k]), null, 0.6f, false, 12, t);
			for (int i = 0; i < 4 * 10; ++i)
			{
				WeatherField.step(s, 0, t, 0.25f);
			}
			wet[k] = s[WeatherField.WET][0];
		}
		assertTrue(wet[1] > 2 * wet[0]);
		// showers rain less on average than their peak
		WeatherType showers = WeatherTestData.type("SPRING_SHOWERS");
		WeatherField.targets(showers, null, 0.6f, false, 12, t);
		assertTrue(t[WeatherField.RAIN] < showers.rain * 0.8f);
	}

	// a swamp in a thunderstorm west of a desert in a sandstorm: nearly every quantity differs
	private static final Function<String, WeatherType> STORM_AND_DUST = b -> "SWAMP".equals(b)
		? WeatherTestData.type("THUNDERSTORM") : WeatherTestData.type("SANDSTORM");

	@Test
	public void everyChannelIsContinuousAcrossABorder()
	{
		WeatherField f = new WeatherField();
		layout(f, 12, 0, "SWAMP", "DESERT");
		f.update(true, STORM_AND_DUST, 0.6f, true, true, 6.5, 0);
		float x0 = f.getOriginX() + center(2), z = f.getOriginZ() + center(10);
		int tiles = (int) ((center(21) - center(2)) / 128);
		float[][] v = new float[WeatherField.CHANNELS][tiles + 1];
		for (int i = 0; i <= tiles; ++i)
		{
			f.focus(x0 + i * 128, z);
			for (int c = 0; c < WeatherField.CHANNELS; ++c)
			{
				v[c][i] = f.getFocus(c);
			}
		}
		int changing = 0;
		for (int c = 0; c < WeatherField.CHANNELS; ++c)
		{
			float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE, steepest = 0;
			for (int i = 0; i <= tiles; ++i)
			{
				lo = Math.min(lo, v[c][i]);
				hi = Math.max(hi, v[c][i]);
				if (i > 0)
				{
					steepest = Math.max(steepest, Math.abs(v[c][i] - v[c][i - 1]));
				}
			}
			// the textures' channels are 8 bits; the blur spreads a step over 40 tiles, 1/24 of it at the steepest
			float quantum = c >= WeatherField.GPU_CHANNELS ? 0 : (c == WeatherField.FOG ? WeatherField.FOG_RANGE : 1) / 255f;
			assertTrue("channel " + c + " jumps " + steepest + " of " + (hi - lo), steepest <= (hi - lo) / 20 + quantum);
			changing += hi - lo > 0.01f ? 1 : 0;
		}
		// and the two places really differ in most of them
		assertTrue("only " + changing + " channels differ", changing >= 20);
	}

	@Test
	public void theTypeShownIsTheDominantOne()
	{
		WeatherField f = new WeatherField();
		layout(f, 12, 0, "SWAMP", "DESERT");
		f.update(true, STORM_AND_DUST, 0.6f, true, true, 6.5, 0);
		float z = f.getOriginZ() + center(10);
		f.focus(f.getOriginX() + center(9), z);
		assertEquals(WeatherTestData.type("THUNDERSTORM"), f.getFocusType());
		f.focus(f.getOriginX() + center(14), z);
		assertEquals(WeatherTestData.type("SANDSTORM"), f.getFocusType());
	}

	@Test
	public void aRegionsWeatherChangeEasesEveryChannel()
	{
		WeatherField f = new WeatherField();
		layout(f, -1, 0, "SWAMP", "DESERT");
		f.update(true, b -> CLEAR, 0.6f, true, true, 13, 0);
		layout(f, 12, 0, "SWAMP", "DESERT");
		f.update(true, b -> CLEAR, 0.6f, true, true, 13, 0);
		float x = f.getOriginX() + center(3), z = f.getOriginZ() + center(10);
		f.focus(x, z);
		float[] before = new float[WeatherField.CHANNELS];
		for (int c = 0; c < WeatherField.CHANNELS; ++c)
		{
			before[c] = f.getFocus(c);
		}
		WeatherType storm = WeatherTestData.type("THUNDERSTORM");
		Function<String, WeatherType> types = b -> storm;
		float[] t = new float[WeatherField.TARGETS];
		WeatherField.targets(storm, "SWAMP", 0.6f, true, 13, t);
		f.update(true, types, 0.6f, true, true, 13, 0.25f);
		f.focus(x, z);
		float[] soon = new float[WeatherField.CHANNELS];
		for (int c = 0; c < WeatherField.CHANNELS; ++c)
		{
			soon[c] = f.getFocus(c);
		}
		for (int i = 0; i < 4 * 60 * 4; ++i)
		{
			f.update(true, types, 0.6f, true, true, 13, 0.25f);
		}
		f.focus(x, z);
		int eased = 0;
		for (int c = WeatherField.FOG; c < WeatherField.CHANNELS; ++c)
		{
			float delta = t[c] - before[c];
			if (c == WeatherField.WET || c == WeatherField.SNOW_COVER || c == WeatherField.SNOWINESS
				|| c == WeatherField.WIND || c == WeatherField.GUSTINESS || Math.abs(delta) < 0.05f)
			{
				continue;
			}
			// a little way after a quarter second, there after minutes
			float quantum = c >= WeatherField.GPU_CHANNELS ? 0 : (c == WeatherField.FOG ? WeatherField.FOG_RANGE : 1) / 255f;
			assertTrue("channel " + c + " jumped", Math.abs(soon[c] - before[c]) <= 0.08f * Math.abs(delta) + quantum);
			assertEquals("channel " + c, t[c], f.getFocus(c), 0.02f * Math.max(1, Math.abs(t[c])));
			++eased;
		}
		assertTrue("only " + eased + " channels eased", eased >= 8);
		// the wind is eased by Wind and gets the new value at once
		assertEquals(storm.wind, f.getFocus(WeatherField.WIND), 0.01f);
	}

	@Test
	public void groundComesFromTheClimate()
	{
		// a region that has had snow for days shows it from the first update, and holds it whatever the weather
		// above does: the climate decides when it thaws
		WeatherField f = new WeatherField();
		layout(f, 12, 0);
		java.util.function.Function<String, float[]> ground = b -> "WET".equals(b) ? new float[]{0.3f, 0.8f}
			: new float[]{0, 0};
		f.update(true, b -> CLEAR, ground, 0.6f, true, false, 12, 0);
		float x = f.getOriginX() + center(3), z = f.getOriginZ() + center(10);
		assertEquals(0.8f, f.sample(WeatherField.SNOW_COVER, x, z), EPS);
		assertEquals(0.3f, f.sample(WeatherField.WET, x, z), EPS);
		assertEquals(0, f.sample(WeatherField.SNOW_COVER, f.getOriginX() + center(20), z), EPS);
		for (int i = 0; i < 400; ++i)
		{
			f.update(true, b -> CLEAR, ground, 0.6f, true, false, 12, 0.25f);
		}
		assertEquals(0.8f, f.sample(WeatherField.SNOW_COVER, x, z), EPS);
		// and falling weather starts at full strength in a newly seen region
		WeatherField g = new WeatherField();
		layout(g, 12, 0);
		g.update(true, TYPES, ground, 0.6f, true, false, 12, 0);
		float[] t = new float[WeatherField.TARGETS];
		WeatherField.targets(RAIN, "WET", 0.6f, false, 12, t);
		assertEquals(t[WeatherField.RAIN], g.cell(WeatherField.RAIN, 3, 10), 1e-6f);
	}
}
