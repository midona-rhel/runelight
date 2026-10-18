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
	private static final Function<String, WeatherType> TYPES = b -> "WET".equals(b) ? WeatherType.RAIN : WeatherType.CLEAR;

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
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int z = 0; z < N; ++z)
		{
			for (int x = 0; x < N; ++x)
			{
				int wx = x + shift;
				keys[z * N + x] = WeatherField.chunkKey(wx * 8, z * 8);
				biomes[z * N + x] = wx < split ? "WET" : null;
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
		float[] t = new float[WeatherField.CHANNELS];
		WeatherField.targets(WeatherType.RAIN, "WET", 0.6f, false, 12, t);
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
		WeatherForecast fa = new WeatherForecast(42), fb = new WeatherForecast(42);
		fa.update("SWAMP", 1_000_000, 12);
		fb.update("DESERT", 1_000_000, 12);
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
		f.update(true, b -> WeatherType.STORM, 1, true, true, 6, 0);
		assertTrue(!f.hasAnything());
		assertEquals(0, f.max(WeatherField.RAIN), 0);
	}
}
