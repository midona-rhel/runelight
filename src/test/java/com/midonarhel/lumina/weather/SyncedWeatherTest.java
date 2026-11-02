package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.config.DayLength;
import com.midonarhel.lumina.sky.GameClock;
import com.midonarhel.lumina.sky.Lightning;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * Two players on different day lengths see the same weather at the same moment. Each player runs the frame's weather
 * steps from their own clock as FrameState and WeatherFrame do (the time of day from their day length, the weather
 * from GameClock.weatherMillis): the climate, the weather field over bands of regions, the wind, the clouds' drift and
 * the lightning. Player A, on the 24 minute day, has been playing for 15 minutes; player B, on another day length,
 * joined 3 minutes ago. At moments over a week: every cell's weather, the wind, the clouds and the strikes compared.
 */
public class SyncedWeatherTest
{
	private static final int N = 21;
	private static final float DT = 0.1f;
	private static final String[] BANDS = {"MOUNTAIN", "FOREST", "GRASSLAND", "TUNDRA", "MOORLAND", "DESERT", "TROPICAL"};

	/** One player's weather, stepped by their own clock. */
	private static final class Player
	{
		final DayLength dayLength;
		final Climate climate = new Climate();
		final WeatherField field = new WeatherField();
		final Wind wind = new Wind();
		final Lightning lightning = new Lightning();
		final double[] drift = new double[2];
		final List<Double> strikes = new ArrayList<>();
		boolean lit;
		double hour;

		Player(DayLength dayLength)
		{
			this.dayLength = dayLength;
			long[] keys = new long[N * N];
			String[] biomes = new String[N * N];
			for (int i = 0; i < N * N; ++i)
			{
				keys[i] = WeatherField.chunkKey(i % N * WeatherField.CELL_TILES, i / N * WeatherField.CELL_TILES);
				biomes[i] = BANDS[i % N * BANDS.length / N];
			}
			field.layout(N, N, 0, 0, keys, biomes);
		}

		void frame(long unix, boolean first)
		{
			// FrameState: the time of day, and the weather's clock
			long game = dayLength.gameMillis(unix);
			long weather = GameClock.weatherMillis(unix);
			hour = GameClock.hourOfDay(game);
			// WeatherFrame and Weather.update
			climate.update(weather, Climate.SEASON_DAYS);
			field.update(climate::type, climate::ground, 0.6f, true, GameClock.hourOfDay(weather), first ? 0 : DT);
			field.focus(N / 2f * WeatherField.CELL, N / 2f * WeatherField.CELL);
			double t = GameClock.realSeconds(unix);
			wind.update(t, DT, field.getFocus(WeatherField.WIND), field.getFocus(WeatherField.GUSTINESS), true, true,
				0, 0);
			lightning.update(t, DT, field.getFocus(WeatherField.STORM), true, 0, 0);
			lightning.setShadowMaps(true, true);
			boolean now = lightning.getBoltDir() != null;
			if (now && !lit)
			{
				strikes.add(t);
			}
			lit = now;
			Wind.drift(t, drift);
		}

		void run(long from, long to)
		{
			for (long unix = from; unix <= to; unix += (long) (DT * 1000))
			{
				frame(unix, unix == from);
			}
		}
	}

	@Test
	public void sameWeatherOnEveryDayLength()
	{
		WeatherTestData.load();
		long year = 4L * Climate.SEASON_DAYS * GameClock.DAY;
		// real moments: a winter's week some years after the epoch, at odd times
		long start = GameClock.EPOCH_MILLIS + 3 * 365L * 24 * 3600 * 1000 + 3600_000L * 7 + 1234;
		DayLength[] others = {DayLength.REAL_WORLD, DayLength.HOUR, DayLength.FIXED};
		float worstSky = 0, worstGround = 0, worstWind = 0;
		double worstDrift = 0;
		int compared = 0, strikes = 0, skyDiffer = 0;
		for (int k = 0; k < 6; ++k)
		{
			long at = start + k * 31L * 3600_000L + k * 977_000L;
			Player a = new Player(DayLength.MINUTES_24), b = new Player(others[k % others.length]);
			a.run(at - 15 * 60_000L, at);
			b.run(at - 3 * 60_000L, at);
			// the time of day differs between them
			assertNotEquals(a.hour, b.dayLength == DayLength.FIXED ? 12 : b.hour, 1e-3);
			for (int c : new int[]{WeatherField.COVER, WeatherField.RAIN, WeatherField.SNOW, WeatherField.FOG,
				WeatherField.WIND, WeatherField.STORM})
			{
				for (int i = 0; i < N * N; ++i)
				{
					float d = Math.abs(a.field.cell(c, i % N, i / N) - b.field.cell(c, i % N, i / N));
					worstSky = Math.max(worstSky, d);
					skyDiffer += d > 1 / 255f ? 1 : 0;
				}
			}
			for (int c : new int[]{WeatherField.WET, WeatherField.SNOW_COVER})
			{
				for (int i = 0; i < N * N; ++i)
				{
					worstGround = Math.max(worstGround,
						Math.abs(a.field.cell(c, i % N, i / N) - b.field.cell(c, i % N, i / N)));
				}
			}
			for (String biome : BANDS)
			{
				assertEquals(a.climate.region(biome).type, b.climate.region(biome).type);
				assertEquals(a.climate.region(biome).temperature, b.climate.region(biome).temperature, 0);
			}
			worstWind = Math.max(worstWind, Math.max(Math.abs(a.wind.getDirX() - b.wind.getDirX()),
				Math.abs(a.wind.getDirZ() - b.wind.getDirZ())));
			assertEquals(a.wind.getFront(), b.wind.getFront(), 0);
			worstDrift = Math.max(worstDrift, Math.hypot(a.drift[0] - b.drift[0], a.drift[1] - b.drift[1]));
			// the strikes both saw: every one of B's is one of A's at the same moment
			for (double s : b.strikes)
			{
				assertTrue("strike at " + s + " only for B", a.strikes.contains(s));
				strikes++;
			}
			compared++;
		}
		System.out.printf("%d moments compared: sky, rain, snow, fog, wind and storm differ by at most %.5f (%d cell values"
				+ " over 1/255), wet ground and snow cover by %.5f, wind direction %.6f, clouds' drift %.6f units, %d strikes"
				+ " seen by both at the same moment%n", compared, worstSky, skyDiffer, worstGround, worstWind, worstDrift,
			strikes);
		assertTrue("sky " + worstSky, worstSky <= 1 / 255f);
		assertEquals(0, worstWind, 1e-6);
		assertEquals(0, worstDrift, 1e-6);
	}

	@Test
	public void aWeatherLastsEightRealMinutes()
	{
		// the forecast's slots are 8 real minutes from the epoch, whatever the day length: 3 a day on the 24 minute day
		for (long unix = GameClock.EPOCH_MILLIS; unix < GameClock.EPOCH_MILLIS + 3 * 24 * 3600_000L; unix += 37_000)
		{
			long real = (unix - GameClock.EPOCH_MILLIS) / (8 * 60_000L);
			assertEquals(real, WeatherForecast.slot(GameClock.weatherMillis(unix)));
		}
	}

	@Test
	public void sameStrikesForEveryone()
	{
		// a full storm: A watching for 15 minutes, B for the last 3, at frame rates that differ
		long at = GameClock.EPOCH_MILLIS + 2L * 365 * 24 * 3600 * 1000 + 5_555_555L;
		Lightning a = new Lightning(), b = new Lightning();
		List<Double> sa = strikes(a, at - 15 * 60_000L, at, 1 / 60f), sb = strikes(b, at - 3 * 60_000L, at, 1 / 37f);
		int both = 0;
		for (double s : sa)
		{
			if (s > GameClock.realSeconds(at - 3 * 60_000L) + 2)
			{
				// within a frame of each other
				double near = sb.stream().mapToDouble(x -> Math.abs(x - s)).min().orElse(1e9);
				assertTrue("A's strike at " + s + " not B's", near <= 1 / 37.0 + 1e-6);
				both++;
			}
		}
		System.out.printf("full storm, the last 3 minutes: %d strikes, the same for both%n", both);
		assertTrue(both >= 6);
	}

	private static List<Double> strikes(Lightning l, long from, long to, float dt)
	{
		List<Double> out = new ArrayList<>();
		boolean lit = false;
		for (double t = GameClock.realSeconds(from); t <= GameClock.realSeconds(to); t += dt)
		{
			l.update(t, dt, 1, true, 0, 0);
			l.setShadowMaps(true, true);
			boolean now = l.getBoltDir() != null;
			if (now && !lit)
			{
				out.add(t);
			}
			lit = now;
		}
		return out;
	}

	@Test
	public void driftIsSmoothAndFollowsTheWander()
	{
		// the drift's derivative is the wander's direction: checked across cells and periods (over a second either
		// way; the wander's noise is in floats, which leaves the drift a few millionths of a second's travel off)
		double[] p = new double[2], q = new double[2];
		double h = 1;
		for (double t : new double[]{1e3, 239.99, 240.0, 1024 * 240.0 - 0.005, 9.4e7, 9.4e7 + 123.4})
		{
			Wind.drift(t - h, p);
			Wind.drift(t + h, q);
			double a = Wind.wander(t);
			assertEquals(Math.cos(a), (q[0] - p[0]) / (2 * h), 1e-4);
			assertEquals(Math.sin(a), (q[1] - p[1]) / (2 * h), 1e-4);
		}
	}
}
