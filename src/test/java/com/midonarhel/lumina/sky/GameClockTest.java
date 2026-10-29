package com.midonarhel.lumina.sky;

import com.midonarhel.lumina.config.DayLength;
import java.time.ZoneOffset;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GameClockTest
{
	private static final long MINUTE = 60_000;

	@Test
	public void anchoredToTheEpoch()
	{
		assertEquals(0, GameClock.gameMillis(GameClock.EPOCH_MILLIS, 24));
		// one game hour per real minute with the default day
		assertEquals(GameClock.HOUR, GameClock.gameMillis(GameClock.EPOCH_MILLIS + MINUTE, 24));
		assertEquals(GameClock.DAY, GameClock.gameMillis(GameClock.EPOCH_MILLIS + 24 * MINUTE, 24));
		// a 12 minute day runs twice as fast
		assertEquals(2 * GameClock.HOUR, GameClock.gameMillis(GameClock.EPOCH_MILLIS + MINUTE, 12));
		// the day held still runs the default day
		assertEquals(GameClock.gameMillis(GameClock.EPOCH_MILLIS + 12345678L, 24),
			GameClock.gameMillis(GameClock.EPOCH_MILLIS + 12345678L, 0));
		// before the epoch it counts backwards, with the time of day still right
		assertEquals(23.0, GameClock.hourOfDay(GameClock.gameMillis(GameClock.EPOCH_MILLIS - MINUTE, 24)), 1e-12);
		assertEquals(-1, GameClock.day(GameClock.gameMillis(GameClock.EPOCH_MILLIS - MINUTE, 24)));
	}

	@Test
	public void exactDecadesLater()
	{
		// 80 years of 24 minute days, then 13:30: whole milliseconds, nothing lost
		long days = 80L * 365 * 60;
		long unix = GameClock.EPOCH_MILLIS + days * 24 * MINUTE + 13 * MINUTE + 30_000;
		long game = GameClock.gameMillis(unix, 24);
		assertEquals(days, GameClock.day(game));
		assertEquals(13.5, GameClock.hourOfDay(game), 0);
		assertEquals(days * 24 + 13.5, GameClock.hours(game), 1e-6);
		// the time is a function of the real time only: two clocks asked at the same moment agree, a second later
		// they are one game minute on
		assertEquals(GameClock.gameMillis(unix + 1000, 24) - game, MINUTE);
		// odd day lengths stay exact in whole milliseconds
		long g7 = GameClock.gameMillis(unix, 7);
		assertTrue(g7 > 0);
		assertEquals((unix - GameClock.EPOCH_MILLIS) * 1440 / 7, g7);
	}

	@Test
	public void realDayIsTheLocalTimeOfDay()
	{
		GameClock utc = new GameClock(ZoneOffset.UTC);
		// 2024-01-01T13:30Z
		long unix = GameClock.EPOCH_MILLIS + 13 * 60 * MINUTE + 30 * MINUTE;
		assertEquals(13.5, utc.targetHour(unix, DayLength.GameClockMinutes.REAL_DAY, 0), 1e-9);
		GameClock plusTwo = new GameClock(ZoneOffset.ofHours(2));
		assertEquals(15.5, plusTwo.targetHour(unix, DayLength.GameClockMinutes.REAL_DAY, 0), 1e-9);
		// a fixed hour is just that
		assertEquals(5, utc.targetHour(unix, 0, 5), 0);
	}

	@Test
	public void dayLengthChangeEasesTheHour()
	{
		GameClock clock = new GameClock(ZoneOffset.UTC);
		long unix = GameClock.EPOCH_MILLIS + 1000L * 24 * MINUTE + 7 * MINUTE;
		double before = clock.hour(unix, 24, 12);
		assertEquals(7, before, 1e-9);
		// switching to the 2 hour day: the target hour jumps, the hour given out moves over 3 seconds
		double target = clock.targetHour(unix, 120, 12);
		assertTrue(Math.abs(GameClock.shortestWay(before, target)) > 1);
		double last = before;
		double maxStep = 0;
		for (long t = 0; t <= 3500; t += 50)
		{
			double h = clock.hour(unix + t, 120, 12);
			double step = Math.abs(GameClock.shortestWay(last, h));
			maxStep = Math.max(maxStep, step);
			last = h;
		}
		// 12 hours at most over 3 s with a smoothstep: under 0.3 h per 50 ms
		assertTrue("largest step " + maxStep, maxStep < 0.35);
		// settled on the clock
		assertEquals(clock.targetHour(unix + 3500, 120, 12), last, 1e-9);
	}

	@Test
	public void easesTheShortWayRoundTheClock()
	{
		assertEquals(2, GameClock.shortestWay(23, 1), 1e-12);
		assertEquals(-2, GameClock.shortestWay(1, 23), 1e-12);
		assertEquals(-12, GameClock.shortestWay(6, 18), 1e-12);
		GameClock clock = new GameClock(ZoneOffset.UTC);
		long unix = GameClock.EPOCH_MILLIS;
		assertEquals(23, clock.hour(unix, 0, 23), 0);
		// to 01:00: halfway through the ease it is at midnight, not at noon
		clock.hour(unix + 1, 0, 1);
		double mid = clock.hour(unix + 1500, 0, 1);
		assertTrue("hour " + mid, mid < 1 || mid > 23);
	}
}
