package com.midonarhel.lumina.weather;

import static org.junit.Assert.assertTrue;
import java.util.function.Function;
import org.junit.Test;

/**
 * A biome's weather changing where the player stands: the cover and the rain move toward the new weather without a
 * jump at the start, and get there. Prints the curve (share of the way per second) so the numbers can be rerun.
 */
public class WeatherTransitionTest
{
	private static final int N = 9;
	private static final float DT = 0.1f;

	private static float[] run(String channelName, int channel, float seconds)
	{
		WeatherType clear = WeatherTestData.type("CLEAR");
		WeatherType rain = WeatherTestData.type("RAIN");
		WeatherType[] now = {clear};
		Function<String, WeatherType> types = b -> now[0];
		WeatherField f = new WeatherField();
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int i = 0; i < N * N; ++i)
		{
			keys[i] = WeatherField.chunkKey(i % N * WeatherField.CELL_TILES, i / N * WeatherField.CELL_TILES);
			biomes[i] = "FOREST";
		}
		f.layout(N, N, 0, 0, keys, biomes);
		f.update(types, null, 0.6f, true, 12, 0);
		for (int i = 0; i < 600; ++i)
		{
			f.update(types, null, 0.6f, true, 12, DT);
		}
		float x = N / 2f * WeatherField.CELL, z = x;
		f.focus(x, z);
		float a = f.getFocus(channel);
		now[0] = rain;
		int steps = Math.round(seconds / DT);
		float[] v = new float[steps + 1];
		v[0] = a;
		for (int i = 1; i <= steps; ++i)
		{
			f.update(types, null, 0.6f, true, 12, DT);
			f.focus(x, z);
			v[i] = f.getFocus(channel);
		}
		float b = v[steps];
		StringBuilder sb = new StringBuilder(channelName + " clear -> rain, share of the way at 0,1,2,5,10,20,40,60,90,120 s:");
		for (int t : new int[]{0, 1, 2, 5, 10, 20, 40, 60, 90, 120})
		{
			int i = Math.min(steps, Math.round(t / DT));
			sb.append(String.format(" %.3f", (v[i] - a) / (b - a)));
		}
		float maxStep = 0;
		int at = 0;
		for (int i = 10; i <= steps; ++i)
		{
			float d = Math.abs(v[i] - v[i - 10]) / Math.abs(b - a);
			if (d > maxStep)
			{
				maxStep = d;
				at = i;
			}
		}
		sb.append(String.format("; most in one second %.3f of the way, at %.1f s", maxStep, at * DT));
		System.out.println(sb);
		for (int i = 0; i <= steps; ++i)
		{
			v[i] = (v[i] - a) / (b - a);
		}
		return v;
	}

	@Test
	public void changesStartAndEndGently()
	{
		WeatherTestData.load();
		float[] cover = run("cover", WeatherField.COVER, 180);
		float[] rain = run("rain", WeatherField.RAIN, 180);
		// no jump at the start: under 2 % of the way in the first two seconds
		assertTrue("cover set off at once: " + cover[20], cover[20] < 0.02f);
		// and no second moves it more than 5 % of the way
		for (int i = 10; i < cover.length; ++i)
		{
			assertTrue("cover moved " + (cover[i] - cover[i - 10]) + " in a second at " + i * DT,
				Math.abs(cover[i] - cover[i - 10]) < 0.05f);
		}
		// the rain comes in as the clouds finish building, without a lurch
		for (int i = 10; i < rain.length; ++i)
		{
			assertTrue("rain moved " + (rain[i] - rain[i - 10]) + " in a second at " + i * DT,
				Math.abs(rain[i] - rain[i - 10]) < 0.06f);
		}
		// there within two minutes
		assertTrue(cover[1200] > 0.95f);
		assertTrue(rain[1200] > 0.9f);
	}
}
