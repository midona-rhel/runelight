package com.midonarhel.runelight;

/**
 * The game's clock: a pure function of the real (Unix) time, counted from a fixed epoch, so the time of day, the
 * season and the weather are the same whenever and wherever the client runs. Restarting, hopping worlds or
 * reloading the plugin never resets them; they always follow the real time.
 *
 * Game time runs {@code 1440 / dayMinutes} times as fast as real time: with the default 24 minute day, one game
 * hour per real minute. Everything is whole milliseconds in longs, so it stays exact for centuries.
 */
final class GameClock
{
	// 2024-01-01T00:00:00Z: game time 0 is midnight of the first day of a season cycle
	static final long EPOCH_MILLIS = 1_704_067_200_000L;
	static final int DEFAULT_DAY_MINUTES = 24;
	static final long HOUR = 3_600_000L;
	static final long DAY = 24 * HOUR;
	private static final long MINUTES_PER_DAY = 24 * 60;

	private GameClock()
	{
	}

	/**
	 * Game milliseconds since the epoch.
	 *
	 * @param dayMinutes real minutes per game day; 0 or less (the day held at a fixed hour) runs the default day,
	 *                   so the weather and the seasons still go on
	 */
	static long gameMillis(long unixMillis, int dayMinutes)
	{
		long real = unixMillis - EPOCH_MILLIS;
		// real * 1440 stays far below 2^63 for any real time in the next few thousand years
		return Math.floorDiv(real * MINUTES_PER_DAY, dayMinutes(dayMinutes));
	}

	/** The day length the clock actually runs at. */
	static int dayMinutes(int dayMinutes)
	{
		return dayMinutes > 0 ? dayMinutes : DEFAULT_DAY_MINUTES;
	}

	/** Real milliseconds per game millisecond at this day length. */
	static double realPerGame(int dayMinutes)
	{
		return dayMinutes(dayMinutes) / (double) MINUTES_PER_DAY;
	}

	/** Clock time 0 to 24. */
	static double hourOfDay(long gameMillis)
	{
		return Math.floorMod(gameMillis, DAY) / (double) HOUR;
	}

	/** Whole game days since the epoch. */
	static long day(long gameMillis)
	{
		return Math.floorDiv(gameMillis, DAY);
	}

	/** Game days since the epoch with the fraction of the day, exact enough for anything smooth. */
	static double days(long gameMillis)
	{
		return day(gameMillis) + Math.floorMod(gameMillis, DAY) / (double) DAY;
	}

	/** Game hours since the epoch, with the fraction. */
	static double hours(long gameMillis)
	{
		return Math.floorDiv(gameMillis, HOUR) + Math.floorMod(gameMillis, HOUR) / (double) HOUR;
	}

	/** Real seconds since the epoch, for anything that moves in real time but must not depend on the session. */
	static double realSeconds(long unixMillis)
	{
		return (unixMillis - EPOCH_MILLIS) / 1000.0;
	}
}
