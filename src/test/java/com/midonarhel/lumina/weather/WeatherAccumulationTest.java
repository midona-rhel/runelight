package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.sky.GameClock;
import java.util.function.Function;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Snow on the ground grows only where snow is falling, and wet ground only where rain is: with nothing falling they
 * hold or shrink. Automatic weather (Climate's ground) over winter days in bands of regions with borders between,
 * stepped as the client steps it (a tenth of a second of real time per update, the default day); every cell, and the
 * player's blurred view walking along the field, checked every step. Prints how often and how much they grew dry.
 */
public class WeatherAccumulationTest
{
	private static final int N = 21;
	private static final float DT = 0.1f;
	// below this the cell's rain or snow counts as not falling
	private static final float FALLING = 0.003f;

	@Test
	public void nothingGrowsWithoutFalling()
	{
		WeatherTestData.load();
		String[] bands = {"MOUNTAIN", "FOREST", "GRASSLAND", "TUNDRA", "MOORLAND", "DESERT", "WILDERNESS"};
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int i = 0; i < N * N; ++i)
		{
			keys[i] = WeatherField.chunkKey(i % N * WeatherField.CELL_TILES, i / N * WeatherField.CELL_TILES);
			biomes[i] = bands[i % N * bands.length / N];
		}
		WeatherField f = new WeatherField();
		f.layout(N, N, 0, 0, keys, biomes);
		Climate climate = new Climate();
		long year = 4L * Climate.SEASON_DAYS * GameClock.DAY;
		// winter, some years after the epoch; game time runs 1440 / dayMinutes times real time (the user's 9 minutes)
		long start = 37 * year + 3L * Climate.SEASON_DAYS * GameClock.DAY;
		long realStep = (long) (DT * 1000);
		int dayMinutes = Integer.getInteger("lumina.accumulation.dayMinutes", 9);
		long speed = 1440 / dayMinutes;
		int steps = (int) (Long.getLong("lumina.accumulation.hours", 48L) * 3600 / speed / DT);
		float[] prevSnow = new float[N * N], prevWet = new float[N * N], prevCover = new float[N * N],
			prevWetness = new float[N * N];
		float focusPrevCover = 0, focusPrevWet = 0, focusPrevSnow = 0, focusPrevRain = 0;
		int dryGrowth = 0, wetGrowth = 0, focusDry = 0, focusWet = 0, snowing = 0;
		float worstSnow = 0, worstWet = 0, worstSpell = 0;
		float[] spell = new float[N * N];
		for (int s = 0; s < steps; ++s)
		{
			long game = start + s * realStep * speed;
			climate.update(game, Climate.SEASON_DAYS);
			f.update(climate::type, climate::ground, 0.6f, true, GameClock.hourOfDay(game), s == 0 ? 0 : DT);
			// the player walks east and back along the middle row, across every border
			float x = (float) ((Math.sin(s * DT / 300) * 0.45 + 0.5) * N * WeatherField.CELL);
			f.focus(x, N / 2f * WeatherField.CELL);
			for (int i = 0; i < N * N; ++i)
			{
				int cx = i % N, cz = i / N;
				float snow = f.cell(WeatherField.SNOW, cx, cz), rain = f.cell(WeatherField.RAIN, cx, cz);
				float cover = f.cell(WeatherField.SNOW_COVER, cx, cz), wet = f.cell(WeatherField.WET, cx, cz);
				snowing += snow > FALLING ? 1 : 0;
				if (s > 0 && snow <= FALLING && prevSnow[i] <= FALLING && cover > prevCover[i] + 1e-6f)
				{
					dryGrowth++;
					worstSnow = Math.max(worstSnow, cover - prevCover[i]);
					spell[i] += cover - prevCover[i];
					worstSpell = Math.max(worstSpell, spell[i]);
				}
				else if (snow > FALLING)
				{
					spell[i] = 0;
				}
				if (s > 0 && rain <= FALLING && prevWet[i] <= FALLING && wet > prevWetness[i] + 1e-6f)
				{
					wetGrowth++;
					worstWet = Math.max(worstWet, wet - prevWetness[i]);
				}
				prevSnow[i] = snow;
				prevWet[i] = rain;
				prevCover[i] = cover;
				prevWetness[i] = wet;
			}
			if (s % (s * DT * speed / 3600 >= 22 && s * DT * speed / 3600 <= 26 ? 150 * 60 / speed : 600 * 60 / speed) == 0 && Boolean.getBoolean("lumina.accumulation.timeline"))
			{
				// one game hour a line, Moorland in the middle row
				int i = N / 2 * N + 13;
				Climate.Region r = climate.region(biomes[i]);
				System.out.printf("timeline %5.1f h: %-8s %5.1f C  falling snow %.3f rain %.3f  ground snow %.3f wet %.3f"
						+ " (climate %.3f / %.3f)%n", s * DT * speed / 3600f, r.type.name, r.temperature,
					f.cell(WeatherField.SNOW, 13, N / 2), f.cell(WeatherField.RAIN, 13, N / 2), prevCover[i], prevWetness[i],
					r.snowCover(), r.wet);
			}
			float fc = f.getFocus(WeatherField.SNOW_COVER), fw = f.getFocus(WeatherField.WET);
			float fs = f.getFocus(WeatherField.SNOW), fr = f.getFocus(WeatherField.RAIN);
			// the player's view: 8-bit like the textures, so a step of one level is the least that shows
			if (s > 0 && fs <= FALLING && focusPrevSnow <= FALLING && fc > focusPrevCover + 1.5f / 255)
			{
				focusDry++;
			}
			if (s > 0 && fr <= FALLING && focusPrevRain <= FALLING && fw > focusPrevWet + 1.5f / 255)
			{
				focusWet++;
			}
			focusPrevCover = fc;
			focusPrevWet = fw;
			focusPrevSnow = fs;
			focusPrevRain = fr;
		}
		System.out.printf("%d minute day, over %d steps (%d cell-steps snowing): snow cover grew with no snow falling in %d"
				+ " cell-steps (most %.4f in a step, %.3f in one dry spell), wet ground grew with no rain in %d (most %.4f);"
				+ " at the player %d and %d%n", dayMinutes, steps, snowing, dryGrowth, worstSnow, worstSpell, wetGrowth,
			worstWet, focusDry, focusWet);
		assertEquals("snow cover grew with no snow falling", 0, dryGrowth);
		assertEquals("wet ground grew with no rain falling", 0, wetGrowth);
		assertEquals("the player saw snow grow with none falling", 0, focusDry);
		assertEquals("the player saw the ground wet with no rain", 0, focusWet);
	}

	/**
	 * Snow on the ground and wet ground ease: no cell's cover jumps from one update to the next, and its rate of
	 * change never jumps either, so growth and melt start and stop gently. The same automatic winter as above; a
	 * step's change and its change of rate in levels of the 8-bit texture a second. Prints a timeline of one cell for a
	 * chart with -Dlumina.accumulation.curve=true.
	 */
	@Test
	public void groundEasesSmoothly()
	{
		WeatherTestData.load();
		String[] bands = {"MOUNTAIN", "FOREST", "GRASSLAND", "TUNDRA", "MOORLAND", "DESERT", "WILDERNESS"};
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int i = 0; i < N * N; ++i)
		{
			keys[i] = WeatherField.chunkKey(i % N * WeatherField.CELL_TILES, i / N * WeatherField.CELL_TILES);
			biomes[i] = bands[i % N * bands.length / N];
		}
		WeatherField f = new WeatherField();
		f.layout(N, N, 0, 0, keys, biomes);
		Climate climate = new Climate();
		long year = 4L * Climate.SEASON_DAYS * GameClock.DAY;
		long start = 37 * year + 3L * Climate.SEASON_DAYS * GameClock.DAY;
		long realStep = (long) (DT * 1000);
		long speed = 1440 / 9;
		int steps = (int) (48L * 3600 / speed / DT);
		int[] channels = {WeatherField.SNOW_COVER, WeatherField.WET};
		float[][] prev = new float[2][N * N], rate = new float[2][N * N];
		float[] worstStep = new float[2], worstJerk = new float[2];
		boolean curve = Boolean.getBoolean("lumina.accumulation.curve");
		for (int s = 0; s < steps; ++s)
		{
			long game = start + s * realStep * speed;
			climate.update(game, Climate.SEASON_DAYS);
			f.update(climate::type, climate::ground, 0.6f, true, GameClock.hourOfDay(game), s == 0 ? 0 : DT);
			for (int k = 0; k < 2; ++k)
			{
				for (int i = 0; i < N * N; ++i)
				{
					float v = f.cell(channels[k], i % N, i / N);
					// levels a second
					float r = (v - prev[k][i]) * 255 / DT;
					if (s > 1)
					{
						worstStep[k] = Math.max(worstStep[k], Math.abs(r));
						worstJerk[k] = Math.max(worstJerk[k], Math.abs(r - rate[k][i]));
					}
					prev[k][i] = v;
					rate[k][i] = r;
				}
			}
			if (curve && s % 10 == 0)
			{
				// a cell of the middle row (by default tundra)
				int x = Integer.getInteger("lumina.accumulation.cell", 10), i = N / 2 * N + x;
				System.out.printf("curve %.1f %.4f %.4f %.4f%n", s * DT, f.cell(WeatherField.SNOW, x, N / 2),
					prev[0][i], climate.region(biomes[i]).snowCover());
			}
		}
		System.out.printf("over %d steps of %.1f s: snow cover's fastest change %.1f levels/s, largest jump of its rate"
				+ " %.1f levels/s; wet ground's %.1f and %.1f%n", steps, DT, worstStep[0], worstJerk[0], worstStep[1],
			worstJerk[1]);
		// the rate of change moving by at most half a level a second from one update to the next: before the ground
		// eased, growth set off at its full 4.3 levels a second in one update, and wet ground's by 5.1
		assertTrue("snow cover's rate jumped by " + worstJerk[0], worstJerk[0] <= 0.5f);
		assertTrue("wet ground's rate jumped by " + worstJerk[1], worstJerk[1] <= 0.5f);
	}

	@Test
	public void clearSkiesOnlyMeltAndDry()
	{
		// a held clear weather over snowy, wet ground: an hour of updates, never a rise
		WeatherTestData.load();
		WeatherType clear = WeatherTestData.type("CLEAR");
		WeatherField f = new WeatherField();
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int i = 0; i < N * N; ++i)
		{
			keys[i] = WeatherField.chunkKey(i % N * WeatherField.CELL_TILES, i / N * WeatherField.CELL_TILES);
		}
		f.layout(N, N, 0, 0, keys, biomes);
		f.update(b -> WeatherTestData.type("SNOW"), null, 0.6f, false, 12, 0);
		f.update(b -> WeatherTestData.type("RAIN"), null, 0.6f, true, 12, 60);
		float cover = f.cell(WeatherField.SNOW_COVER, 5, 5), wet = f.cell(WeatherField.WET, 5, 5);
		for (int s = 0; s < 36000; ++s)
		{
			f.update(b -> clear, null, 0.6f, true, 12, DT);
			float c = f.cell(WeatherField.SNOW_COVER, 5, 5), w = f.cell(WeatherField.WET, 5, 5);
			if (f.cell(WeatherField.SNOW, 5, 5) <= FALLING)
			{
				assertTrue("snow grew at " + s, c <= cover);
			}
			if (f.cell(WeatherField.RAIN, 5, 5) <= FALLING)
			{
				assertTrue("wet grew at " + s, w <= wet);
			}
			cover = c;
			wet = w;
		}
		assertTrue(cover < 0.99f && wet < 0.5f);
	}

	@Test
	public void snowStaysOnItsSideOfABorder()
	{
		// snowtree1: a mountain, snowing over 60 cm of snow, beside a forest where it rains. Through the login, ten
		// minutes of the mountain's snow, the window re-laid as the player walks and a switch to clear weather, the
		// forest's ground takes none of the mountain's snow and the mountain none of the forest's puddles, past half a
		// cell from the border
		WeatherTestData.load();
		WeatherType snow = WeatherTestData.type("SNOW"), rain = WeatherTestData.type("RAIN");
		WeatherType clear = WeatherTestData.type("CLEAR");
		Function<String, WeatherType> types = b -> "MOUNTAIN".equals(b) ? snow : rain;
		Function<String, float[]> ground = b -> "MOUNTAIN".equals(b) ? new float[]{0, 1} : new float[]{1, 0};
		WeatherField f = new WeatherField();
		int border = N / 2;
		lay(f, 0, border);
		f.update(types, ground, 0.6f, true, 12, 0);
		for (int s = 0; s <= 6000; ++s)
		{
			if (s == 3000)
			{
				// the window moves three cells east; the cells it still covers keep their ground
				lay(f, 3, border);
			}
			boolean automatic = s < 4500;
			f.update(automatic ? types : b -> clear, automatic ? ground : null, 0.6f, true, 12, DT);
			// the border in the window's tiles, half way between the last mountain cell's middle and the first forest's
			float edge = (border - (s >= 3000 ? 3 : 0)) * WeatherField.CELL_TILES;
			for (int t = 0; t < N * WeatherField.CELL_TILES; ++t)
			{
				float tile = t + 0.5f;
				f.focus(tile * 128, N / 2f * WeatherField.CELL);
				if (tile > edge + WeatherField.CELL_TILES / 2f)
				{
					assertEquals("snow in the forest at tile " + t + ", step " + s, 0,
						f.getFocus(WeatherField.SNOW_COVER), 0);
				}
				else if (tile < edge - WeatherField.CELL_TILES / 2f)
				{
					assertEquals("puddles on the mountain at tile " + t + ", step " + s, 0,
						f.getFocus(WeatherField.WET), 0);
				}
			}
		}
		f.focus(2.5f * WeatherField.CELL, N / 2f * WeatherField.CELL);
		assertTrue("the mountain keeps its snow", f.getFocus(WeatherField.SNOW_COVER) > 0.75f);
	}

	/** The field's cells from {@code first} cell east: mountain west of cell {@code border}, forest from it. */
	private static void lay(WeatherField f, int first, int border)
	{
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int i = 0; i < N * N; ++i)
		{
			int x = i % N + first;
			keys[i] = WeatherField.chunkKey(x * WeatherField.CELL_TILES, i / N * WeatherField.CELL_TILES);
			biomes[i] = x < border ? "MOUNTAIN" : "FOREST";
		}
		f.layout(N, N, 0, 0, keys, biomes);
	}
}
