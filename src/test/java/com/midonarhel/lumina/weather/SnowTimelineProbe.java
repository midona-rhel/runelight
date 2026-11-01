package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.sky.GameClock;
import org.junit.Test;

/**
 * Probe, not a check: the snow at a place over real time as the client sees it, printed. Run with
 * {@code ./gradlew test --tests '*SnowTimelineProbe*' -i -Dlumina.probe.biome=FOREST -Dlumina.probe.unix=...
 * -Dlumina.probe.dayMinutes=9 -Dlumina.probe.realMinutes=120}.
 */
public class SnowTimelineProbe
{
	/**
	 * Over a year of game time in each temperate biome: how long snow lies, and how long it outlasts each snowfall
	 * (game hours from the last hour of snow falling until the cover is under 0.1, for spells that end with no snow
	 * falling). Run with
	 * {@code ./gradlew test --tests '*SnowTimelineProbe*' -i -Dlumina.probe.summary=true}.
	 */
	@Test
	public void summary()
	{
		if (!Boolean.getBoolean("lumina.probe.summary"))
		{
			return;
		}
		WeatherTestData.load();
		long year = 4L * Climate.SEASON_DAYS * GameClock.DAY;
		long t0 = 37 * year;
		for (String biome : new String[]{"FOREST", "GRASSLAND", "MOORLAND", "WETLAND", "MEDITERRANEAN"})
		{
			Climate climate = new Climate();
			int hours = 0, covered = 0, falling = 0, fallingCovered = 0, spells = 0;
			double outlast = 0, longest = 0;
			long lastFall = Long.MIN_VALUE;
			boolean wasCovered = false;
			for (long t = t0; t < t0 + year; t += GameClock.HOUR)
			{
				climate.update(t, Climate.SEASON_DAYS);
				Climate.Region r = climate.region(biome);
				boolean snowing = r.type.snow > 0.02f;
				boolean cov = r.snowCover() >= 0.1f;
				hours++;
				covered += cov ? 1 : 0;
				falling += snowing ? 1 : 0;
				fallingCovered += snowing && cov ? 1 : 0;
				if (snowing)
				{
					lastFall = t;
				}
				if (wasCovered && !cov && !snowing && lastFall != Long.MIN_VALUE)
				{
					double h = (t - lastFall) / (double) GameClock.HOUR;
					outlast += h;
					longest = Math.max(longest, h);
					spells++;
				}
				wasCovered = cov;
			}
			System.out.printf("%-14s snowing %4d h, covered %4d h (%4.1f %% of the year), covered while not snowing %4d h;"
					+ " %d spells, snow outlasts the snowfall by %.1f game hours on average (longest %.0f)%n",
				biome, falling, covered, 100.0 * covered / hours, covered - fallingCovered, spells,
				spells > 0 ? outlast / spells : 0, longest);
		}
	}

	@Test
	public void timeline()
	{
		String biome = System.getProperty("lumina.probe.biome");
		if (biome == null)
		{
			return;
		}
		WeatherTestData.load();
		long unix = Long.getLong("lumina.probe.unix", 1793540018367L);
		int dayMinutes = Integer.getInteger("lumina.probe.dayMinutes", 9);
		int realMinutes = Integer.getInteger("lumina.probe.realMinutes", 120);
		int stepSeconds = Integer.getInteger("lumina.probe.stepSeconds", 30);
		Climate climate = new Climate();
		for (long s = -realMinutes * 60L; s <= realMinutes * 60L; s += stepSeconds)
		{
			long g = GameClock.gameMillis(unix + s * 1000, dayMinutes);
			climate.update(g, Climate.SEASON_DAYS);
			Climate.Region r = climate.region(biome);
			System.out.printf("real %+6.1f min  hour %5.2f  %-28s snow %.2f rain %.2f  %5.1f C  depth %5.2f cm  cover %.3f%n",
				s / 60.0, GameClock.hourOfDay(g), r.type.name, r.type.snow, r.type.rain, r.temperature, r.snowCm,
				r.snowCover());
		}
	}
}
