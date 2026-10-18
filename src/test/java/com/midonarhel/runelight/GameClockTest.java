package com.midonarhel.runelight;

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
		// the day held still: the weather goes on at the default day
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
	public void dayCycleFollowsTheClock()
	{
		DayCycle d = new DayCycle();
		for (double hour : new double[]{0.5, 6, 9.25, 12, 18.75, 23.5})
		{
			long unix = GameClock.EPOCH_MILLIS + 1000L * 24 * MINUTE + (long) (hour * MINUTE);
			d.update(GameClock.gameMillis(unix, 24), 24, 0);
			assertEquals(hour, d.hour(), 1e-6);
		}
		// noon is day, midnight is night
		d.update(12 * GameClock.HOUR, 24, 0);
		assertTrue(d.sunAltitude > 40);
		d.update(0, 24, 0);
		assertTrue(d.sunAltitude < -40);
		// a fixed hour holds whatever the clock says
		d.update(5 * GameClock.HOUR, 0, 15);
		assertEquals(15, d.hour(), 1e-6);
	}
}
