/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.sky;

import com.midonarhel.lumina.config.DayLength;
import java.time.Instant;
import java.time.ZoneId;

/**
 * The game's clock: a pure function of the real (Unix) time, counted from a fixed epoch, so the time of day is the
 * same whenever and wherever the client runs. Restarting, hopping worlds or reloading the plugin never resets it; it
 * always follows the real time. (The old renderer's GameClock; the static part is verbatim.)
 * <p>
 * Game time runs {@code 1440 / dayMinutes} times as fast as real time: with the default 24 minute day, one game hour
 * per real minute. Everything is whole milliseconds in longs, so it stays exact for centuries. With
 * {@link DayLength.GameClockMinutes#REAL_DAY} the game's time of day is the machine's local time of day.
 * <p>
 * The instance part, {@link #hour}, is the hour the sky follows: when the day length or the fixed hour changes it
 * eases from the hour it was showing to the new one over {@link #EASE_MILLIS} along the shorter way round the clock
 * (the old code jumped).
 */
public final class GameClock
{
	// 2024-01-01T00:00:00Z: game time 0 is midnight of the first day of a season cycle
	public static final long EPOCH_MILLIS = 1_704_067_200_000L;
	public static final int DEFAULT_DAY_MINUTES = 24;
	public static final long HOUR = 3_600_000L;
	public static final long DAY = 24 * HOUR;
	private static final long MINUTES_PER_DAY = 24 * 60;

	/** How long a change of the day length or the fixed hour takes to settle, real milliseconds. */
	public static final double EASE_MILLIS = 3000;

	private final ZoneId zone;

	private boolean started;
	private int dayMinutes;
	private double fixedHour;
	// the hour given out last
	private double hour;
	private boolean easing;
	private long easeStartMillis;
	private double easeFromHour;

	public GameClock()
	{
		this(ZoneId.systemDefault());
	}

	/**
	 * @param zone the time zone of the real-time day (24 hours per game day)
	 */
	public GameClock(ZoneId zone)
	{
		this.zone = zone;
	}

	/**
	 * Game milliseconds since the epoch.
	 *
	 * @param dayMinutes real minutes per game day; 0 or less (the day held at a fixed hour) runs the default day
	 */
	public static long gameMillis(long unixMillis, int dayMinutes)
	{
		long real = unixMillis - EPOCH_MILLIS;
		// real * 1440 stays far below 2^63 for any real time in the next few thousand years
		return Math.floorDiv(real * MINUTES_PER_DAY, dayMinutes(dayMinutes));
	}

	/** The day length the clock actually runs at. */
	public static int dayMinutes(int dayMinutes)
	{
		return dayMinutes > 0 ? dayMinutes : DEFAULT_DAY_MINUTES;
	}

	/** Real milliseconds per game millisecond at this day length. */
	public static double realPerGame(int dayMinutes)
	{
		return dayMinutes(dayMinutes) / (double) MINUTES_PER_DAY;
	}

	/** Clock time 0 to 24. */
	public static double hourOfDay(long gameMillis)
	{
		return Math.floorMod(gameMillis, DAY) / (double) HOUR;
	}

	/** Whole game days since the epoch. */
	public static long day(long gameMillis)
	{
		return Math.floorDiv(gameMillis, DAY);
	}

	/** Game days since the epoch with the fraction of the day, exact enough for anything smooth. */
	public static double days(long gameMillis)
	{
		return day(gameMillis) + Math.floorMod(gameMillis, DAY) / (double) DAY;
	}

	/** Game hours since the epoch, with the fraction. */
	public static double hours(long gameMillis)
	{
		return Math.floorDiv(gameMillis, HOUR) + Math.floorMod(gameMillis, HOUR) / (double) HOUR;
	}

	/** Real seconds since the epoch, for anything that moves in real time but must not depend on the session. */
	public static double realSeconds(long unixMillis)
	{
		return (unixMillis - EPOCH_MILLIS) / 1000.0;
	}

	/**
	 * The hour of day the settings ask for at this moment, 0 to 24, before any easing.
	 *
	 * @param dayMinutes real minutes per game day; {@link DayLength.GameClockMinutes#REAL_DAY} is the local clock; 0 or
	 *                   less holds the day at fixedHour
	 */
	public double targetHour(long unixMillis, int dayMinutes, double fixedHour)
	{
		if (dayMinutes <= 0)
		{
			return wrap(fixedHour);
		}
		if (dayMinutes == DayLength.GameClockMinutes.REAL_DAY)
		{
			return Instant.ofEpochMilli(unixMillis).atZone(zone).toLocalTime().toNanoOfDay() / (double) (HOUR * 1_000_000L);
		}
		return hourOfDay(gameMillis(unixMillis, dayMinutes));
	}

	/**
	 * The hour of day the sky follows, 0 to 24: {@link #targetHour}, eased over {@link #EASE_MILLIS} from the hour
	 * last given out when the day length or the fixed hour changed. Call once per frame.
	 */
	public double hour(long unixMillis, int dayMinutes, double fixedHour)
	{
		double target = targetHour(unixMillis, dayMinutes, fixedHour);
		if (!started)
		{
			started = true;
			this.dayMinutes = dayMinutes;
			this.fixedHour = fixedHour;
			hour = target;
			return hour;
		}
		if (dayMinutes != this.dayMinutes || fixedHour != this.fixedHour)
		{
			this.dayMinutes = dayMinutes;
			this.fixedHour = fixedHour;
			easing = true;
			easeStartMillis = unixMillis;
			easeFromHour = hour;
		}
		if (easing)
		{
			double t = (unixMillis - easeStartMillis) / EASE_MILLIS;
			if (t >= 1)
			{
				easing = false;
				hour = target;
			}
			else
			{
				double s = t * t * (3 - 2 * t);
				hour = wrap(easeFromHour + shortestWay(easeFromHour, target) * s);
			}
		}
		else
		{
			hour = target;
		}
		return hour;
	}

	/** Signed hours from one clock time to another the shorter way round, in [-12, 12). */
	static double shortestWay(double from, double to)
	{
		double d = wrap(to - from);
		return d >= 12 ? d - 24 : d;
	}

	private static double wrap(double hour)
	{
		double h = hour % 24;
		return h < 0 ? h + 24 : h;
	}
}
