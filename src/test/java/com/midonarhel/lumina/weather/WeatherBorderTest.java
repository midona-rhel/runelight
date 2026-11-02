package com.midonarhel.lumina.weather;

import static org.junit.Assert.assertTrue;
import java.util.function.Function;
import org.junit.Test;

/**
 * Walking across a region border: rain to the west, clear to the east, settled. The player's cover and rain blend
 * over a wide band, with no step from one tile to the next. Prints the band's width and the biggest one-tile change.
 */
public class WeatherBorderTest
{
	private static final int N = 33;

	/** Per tile across the field's middle row: the focus value of a channel, as a share of the way west to east. */
	static float[] walk(int channel)
	{
		WeatherTestData.load();
		WeatherType rain = WeatherTestData.type("RAIN"), clear = WeatherTestData.type("CLEAR");
		Function<String, WeatherType> types = b -> "WEST".equals(b) ? rain : clear;
		WeatherField f = new WeatherField();
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int i = 0; i < N * N; ++i)
		{
			int x = i % N, z = i / N;
			keys[i] = WeatherField.chunkKey(x * WeatherField.CELL_TILES, z * WeatherField.CELL_TILES);
			biomes[i] = x < N / 2 ? "WEST" : "EAST";
		}
		f.layout(N, N, 0, 0, keys, biomes);
		f.update(types, null, 0.6f, false, 12, 0);
		int tiles = N * WeatherField.CELL_TILES;
		float[] v = new float[tiles];
		for (int t = 0; t < tiles; ++t)
		{
			f.focus((t + 0.5f) * 128, N / 2f * WeatherField.CELL);
			v[t] = f.getFocus(channel);
		}
		float a = v[0], b = v[tiles - 1];
		for (int t = 0; t < tiles; ++t)
		{
			v[t] = (v[t] - a) / (b - a);
		}
		return v;
	}

	static String describe(String name, float[] v)
	{
		int first = -1, last = -1;
		float step = 0;
		for (int t = 0; t < v.length; ++t)
		{
			if (first < 0 && v[t] > 0.1f)
			{
				first = t;
			}
			if (last < 0 && v[t] > 0.9f)
			{
				last = t;
			}
			if (t > 0)
			{
				step = Math.max(step, Math.abs(v[t] - v[t - 1]));
			}
		}
		return String.format("%s across a rain|clear border: 10-90 %% over %d tiles, most in one tile %.3f of the way", name,
			last - first, step);
	}

	@Test
	public void bordersBlendWide()
	{
		float[] cover = walk(WeatherField.COVER), rain = walk(WeatherField.RAIN);
		System.out.println(describe("cover", cover));
		System.out.println(describe("rain", rain));
		for (float[] v : new float[][]{cover, rain})
		{
			for (int t = 1; t < v.length; ++t)
			{
				assertTrue("one tile moved " + (v[t] - v[t - 1]), Math.abs(v[t] - v[t - 1]) < 0.015f);
			}
		}
	}
}
