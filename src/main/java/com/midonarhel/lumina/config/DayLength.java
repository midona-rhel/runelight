package com.midonarhel.lumina.config;

import com.midonarhel.lumina.sky.GameClock;
import java.time.ZoneId;

/**
 * How the time of day runs. Only the time of day: the weather, the seasons and the snow follow one clock for every
 * player whatever this is (GameClock.weatherMillis), so everyone sees the same weather at the same moment.
 */
public enum DayLength
{
	// the time held at the fixed hour
	FIXED("Fixed", 0),
	MINUTES_24("24 minutes", 24),
	HOUR("1 hour", 60),
	// the local time of day
	REAL_WORLD("Real world", 24 * 60);

	private final String name;
	private final int minutes;

	DayLength(String name, int minutes)
	{
		this.name = name;
		this.minutes = minutes;
	}

	/** Game milliseconds for the time of day at this real time (Unix milliseconds); FIXED runs the default day. */
	public long gameMillis(long unixMillis)
	{
		if (this == REAL_WORLD)
		{
			// a day per day from the epoch, which is a midnight, shifted to the local time
			return unixMillis - GameClock.EPOCH_MILLIS + ZoneId.systemDefault().getRules().getOffset(
				java.time.Instant.ofEpochMilli(unixMillis)).getTotalSeconds() * 1000L;
		}
		return GameClock.gameMillis(unixMillis, minutes);
	}

	public boolean isFixed()
	{
		return this == FIXED;
	}

	/**
	 * The option nearest the old free day length (real minutes per day, 0 or less held at the fixed hour), to carry
	 * a profile's setting over.
	 */
	public static DayLength nearest(int dayCycleMinutes)
	{
		if (dayCycleMinutes <= 0)
		{
			return FIXED;
		}
		DayLength best = MINUTES_24;
		for (DayLength d : values())
		{
			if (d != FIXED && Math.abs(d.minutes - dayCycleMinutes) < Math.abs(best.minutes - dayCycleMinutes))
			{
				best = d;
			}
		}
		return best;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
