package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SkyCacheTest
{
	private static boolean due(SkyCache.Schedule s, double minutes, long millis, float cover)
	{
		return s.due(minutes, millis, 5, cover, 0, 0, 40, 1000, 0, 0, 7);
	}

	private static void captured(SkyCache.Schedule s, double minutes, long millis, float cover)
	{
		s.captured(minutes, millis, cover, 0, 0, 40, 1000, 0, 0, 7);
	}

	@Test
	public void capturesByGameTime()
	{
		SkyCache.Schedule s = new SkyCache.Schedule();
		assertTrue(due(s, 100, 0, 0));
		captured(s, 100, 0, 0);
		assertFalse(due(s, 104.9, 1000, 0));
		assertTrue(due(s, 105, 1000, 0));
		// the clock going back (day length changed) captures at once
		assertTrue(due(s, 50, 1000, 0));
		// the clock stopping captures at once, and then now and then for the drifting clouds
		assertTrue(due(s, Double.NaN, 1000, 0));
		captured(s, Double.NaN, 1000, 0);
		assertFalse(due(s, Double.NaN, 20_000, 0));
		assertTrue(due(s, Double.NaN, 1000 + (long) (SkyCache.Schedule.MAX_SECONDS * 1000), 0));
	}

	@Test
	public void capturesOnChanges()
	{
		SkyCache.Schedule s = new SkyCache.Schedule();
		captured(s, 0, 0, 0.3f);
		assertFalse(due(s, 1, 10, 0.32f));
		assertTrue(due(s, 1, 10, 0.35f));
		assertTrue(s.due(1, 10, 5, 0.3f, 0, 0, 40, 1000, 3000, 0, 7));
		assertTrue(s.due(1, 10, 5, 0.3f, 0, 0, 43, 1000, 0, 0, 7));
		assertTrue(s.due(1, 10, 5, 0.3f, 0, 0, 40, 1000, 0, 0, 8));
		assertFalse(s.due(1, 10, 5, 0.3f, 0, 0, 40, 1000, 1000, 1000, 7));
	}

	@Test
	public void blendFollowsTheClockWithoutPopping()
	{
		SkyCache.Schedule s = new SkyCache.Schedule();
		assertEquals(1, s.blend(0, 0, 5), 0);
		captured(s, 100, 0, 0);
		assertEquals(0, s.blend(100, 0, 5), 1e-6);
		// halfway through the interval by the game clock, before the real-time minimum
		float half = s.blend(102.5, 500, 5);
		assertEquals(0.5f, half, 1e-6);
		assertEquals(1, s.blend(105, 600, 5), 1e-6);
		// the clock standing still: real time finishes it
		captured(s, Double.NaN, 0, 0);
		assertEquals(1, s.blend(Double.NaN, (long) (SkyCache.Schedule.BLEND_SECONDS * 1000), 5), 1e-6);
		// monotonic and continuous over a whole interval at 60 fps on a 12 minute day (2 game minutes a second)
		captured(s, 0, 0, 0);
		float last = 0;
		for (int frame = 1; frame <= 200; ++frame)
		{
			long ms = frame * 1000L / 60;
			float b = s.blend(ms / 1000.0 * 2, ms, 5);
			assertTrue(b >= last && b - last < 0.03f);
			last = b;
		}
		assertEquals(1, last, 1e-6);
	}

	@Test
	public void blendLastsAboutAsLongAsCapturesAreApart()
	{
		SkyCache.Schedule s = new SkyCache.Schedule();
		captured(s, Double.NaN, 0, 0);
		captured(s, Double.NaN, 5000, 0);
		// the clock standing still: the blend reaches the capture about when the next one comes
		assertEquals(0.5f, s.blend(Double.NaN, 7500, 5), 1e-6);
		assertEquals(1, s.blend(Double.NaN, 10_000, 5), 1e-6);
		// never slower than MAX_BLEND_SECONDS, never faster than BLEND_SECONDS
		captured(s, Double.NaN, 100_000, 0);
		assertEquals(1, s.blend(Double.NaN, 100_000 + (long) (SkyCache.Schedule.MAX_BLEND_SECONDS * 1000), 5), 1e-6);
		captured(s, Double.NaN, 100_100, 0);
		assertEquals(0.5f, s.blend(Double.NaN, 100_100 + (long) (SkyCache.Schedule.BLEND_SECONDS * 500), 5), 1e-6);
	}

	@Test
	public void cacheWeightEasesBothWays()
	{
		float w = 0;
		for (int i = 0; i < 10; ++i)
		{
			float next = SkyCache.ease(w, 1, 0.016f);
			assertTrue(next > w && next - w < 0.02f);
			w = next;
		}
		assertEquals(1, SkyCache.ease(0.99f, 1, 1), 0);
		assertEquals(0, SkyCache.ease(0.01f, 0, 1), 0);
		assertTrue(SkyCache.ease(1, 0, 0.016f) < 1);
	}

	@Test
	public void capturesMoreOftenAtTwilight()
	{
		assertEquals(5, SkyCache.Schedule.interval(5, 50), 1e-9);
		assertEquals(5 * SkyCache.Schedule.TWILIGHT_INTERVAL, SkyCache.Schedule.interval(5, 0), 1e-9);
		assertEquals(5, SkyCache.Schedule.interval(5, -40), 1e-9);
	}

	@Test
	public void texturesRotate()
	{
		assertArrayEquals(new int[]{0, 0, 0}, SkyCache.roles(-1));
		for (int onScreen = 0; onScreen < 3; ++onScreen)
		{
			int[] r = SkyCache.roles(onScreen);
			assertEquals(onScreen, r[1]);
			// capture, source and blend target are three different textures
			assertNotEquals(r[0], r[1]);
			assertNotEquals(r[0], r[2]);
			assertNotEquals(r[1], r[2]);
		}
	}
}
