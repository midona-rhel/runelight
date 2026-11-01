package com.midonarhel.lumina.weather;

import java.util.function.Function;
import org.junit.Test;

/**
 * Probe, not a check: CPU time of one weather field update (easing, transitions, blurs, quantizing) on the client's
 * 33 x 33 cells in four biomes, printed. Run with
 * {@code ./gradlew test --tests '*WeatherFieldCostProbe*' -i -Dlumina.probe.cost=true}.
 */
public class WeatherFieldCostProbe
{
	@Test
	public void cost()
	{
		if (!Boolean.getBoolean("lumina.probe.cost"))
		{
			return;
		}
		WeatherTestData.load();
		int n = 33;
		WeatherType[] types = {WeatherTestData.type("CLEAR"), WeatherTestData.type("RAIN"),
			WeatherTestData.type("THUNDERSTORM"), WeatherTestData.type("CLEAR")};
		String[] names = {"FOREST", "GRASSLAND", "MOUNTAIN", "WETLAND"};
		long[] keys = new long[n * n];
		String[] biomes = new String[n * n];
		for (int i = 0; i < n * n; ++i)
		{
			keys[i] = WeatherField.chunkKey(i % n * WeatherField.CELL_TILES, i / n * WeatherField.CELL_TILES);
			biomes[i] = names[(i % n / 9 + i / n / 9) % 4];
		}
		int[] flip = {0};
		Function<String, WeatherType> typeFor = b -> types[(java.util.Arrays.asList(names).indexOf(b) + flip[0]) % 4];
		WeatherField f = new WeatherField();
		f.layout(n, n, 0, 0, keys, biomes);
		f.update(typeFor, null, 0.6f, true, 12, 0);
		int runs = 20000;
		long best = Long.MAX_VALUE;
		for (int round = 0; round < 5; ++round)
		{
			long t0 = System.nanoTime();
			for (int i = 0; i < runs; ++i)
			{
				// the weather changes every 200 updates (20 s), so transitions are running much of the time
				flip[0] = i / 200;
				f.update(typeFor, null, 0.6f, true, 12, 0.1f);
			}
			best = Math.min(best, System.nanoTime() - t0);
		}
		System.out.printf("weather field update, %d x %d cells: %.1f us (best of 5 rounds of %d); it runs every %.1f s%n",
			n, n, best / 1e3 / runs, runs, 0.1);
	}
}
