package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class NightSkyTest
{
	private static float dot(float[] a, float[] b)
	{
		return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
	}

	@Test
	public void starFrameIsARotationThatTurnsOnceADay()
	{
		float[] m = new float[9];
		for (double phase : new double[]{0, 0.13, 0.5, 0.9})
		{
			NightSky.starFrame(phase, m);
			for (int a = 0; a < 3; ++a)
			{
				for (int b = 0; b < 3; ++b)
				{
					float d = m[a * 3] * m[b * 3] + m[a * 3 + 1] * m[b * 3 + 1] + m[a * 3 + 2] * m[b * 3 + 2];
					assertEquals(a == b ? 1 : 0, d, 1e-5);
				}
			}
			// determinant 1: not a mirror image
			float det = m[0] * (m[4] * m[8] - m[7] * m[5]) - m[3] * (m[1] * m[8] - m[7] * m[2])
				+ m[6] * (m[1] * m[5] - m[4] * m[2]);
			assertEquals(1, det, 1e-5);
		}
		float[] a = new float[9], b = new float[9];
		NightSky.starFrame(0, a);
		NightSky.starFrame(1, b);
		assertArrayEqualsClose(a, b);
		// the pole stays put
		NightSky.starFrame(0.37, m);
		double tilt = Math.toRadians(35);
		float[] pole = {0, (float) -Math.cos(tilt), (float) Math.sin(tilt)};
		for (int row = 0; row < 3; ++row)
		{
			assertEquals(pole[row], m[row] * pole[0] + m[3 + row] * pole[1] + m[6 + row] * pole[2], 1e-5);
		}
	}

	private static void assertArrayEqualsClose(float[] a, float[] b)
	{
		for (int i = 0; i < a.length; ++i)
		{
			assertEquals(a[i], b[i], 1e-5);
		}
	}

	@Test
	public void shootingStarsFlyBrieflyNearWhereTheCameraLooks()
	{
		float[] forward = {0.6f, -0.25f, 0.76f};
		float l = (float) Math.sqrt(forward[0] * forward[0] + forward[1] * forward[1] + forward[2] * forward[2]);
		for (int i = 0; i < 3; ++i)
		{
			forward[i] /= l;
		}
		NightSky sky = new NightSky();
		int slots = 400, seen = 0, frames = 0;
		float[] start = null;
		for (int slot = 0; slot < slots; ++slot)
		{
			boolean any = false;
			float last = 0;
			float[] lastHead = null;
			for (float t = 0; t < NightSky.SLOT_SECONDS; t += 1 / 30f)
			{
				sky.update(slot * NightSky.SLOT_SECONDS + t, 0.5, forward, 0.001f, null, 0, false);
				float[] h = sky.meteorHead;
				if (h[3] <= 0)
				{
					continue;
				}
				any = true;
				++frames;
				assertEquals(1, Math.sqrt(dot(h, h)), 1e-4);
				float[] tail = sky.meteorTail;
				assertEquals(1, Math.sqrt(dot(tail, tail)), 1e-4);
				// the sky above the horizon, in front of the camera
				assertTrue(h[1] < 0);
				assertTrue(dot(h, forward) > 0.2f);
				// the tail trails: it never leads
				float[] along = {h[0] - tail[0], h[1] - tail[1], h[2] - tail[2]};
				assertTrue(along[0] * along[0] + along[1] * along[1] + along[2] * along[2] < 0.2f * 0.2f);
				if (lastHead != null)
				{
					// it moves, and not by leaps
					float[] step = {h[0] - lastHead[0], h[1] - lastHead[1], h[2] - lastHead[2]};
					assertTrue(Math.sqrt(dot(step, step)) < 0.05);
				}
				lastHead = new float[]{h[0], h[1], h[2]};
			}
			if (any)
			{
				++seen;
			}
		}
		// about METEOR_CHANCE of the slots, and each lasts a second or so, not the whole slot
		assertEquals(NightSky.METEOR_CHANCE, seen / (float) slots, 0.08);
		assertTrue(frames / (float) seen < 1.3 * 30);
	}

	@Test
	public void shootingStarsAreTheSameForTheSameTime()
	{
		float[] forward = {0, 0, 1};
		NightSky a = new NightSky(), b = new NightSky();
		for (float t = 0; t < 120; t += 0.1f)
		{
			a.update(t, 0, forward, 0.001f, null, 0, false);
			b.update(t, 0, forward, 0.001f, null, 0, false);
			assertEquals(a.meteorHead[3], b.meteorHead[3], 0);
			assertEquals(a.meteorHead[0], b.meteorHead[0], 0);
		}
		// without a camera there is nothing to place one by
		NightSky c = new NightSky();
		for (float t = 0; t < 120; t += 0.1f)
		{
			c.update(t, 0, null, 0.001f, null, 0, false);
			assertEquals(0, c.meteorHead[3], 0);
		}
	}

	@Test
	public void auroraOnlyInTheNorthOnSomeNights()
	{
		assertTrue(NightSky.isAuroraBiome("TUNDRA"));
		assertTrue(NightSky.isAuroraBiome("ARCTIC"));
		assertFalse(NightSky.isAuroraBiome("GRASSLAND"));
		assertFalse(NightSky.isAuroraBiome(null));

		int without = 0, nights = 2000;
		for (int day = 0; day < nights; ++day)
		{
			long noon = (day * GameClock.DAY) + GameClock.DAY / 2;
			float s = NightSky.auroraNight(noon + GameClock.HOUR);
			assertTrue(s >= 0 && s <= 1);
			// one value all night: the day changes at noon, not at midnight
			assertEquals(s, NightSky.auroraNight(noon + GameClock.DAY - GameClock.HOUR), 0);
			assertEquals(s, NightSky.auroraNight(noon + GameClock.DAY / 2 + 1), 0);
			if (s == 0)
			{
				++without;
			}
			else
			{
				assertTrue(s >= 0.45f);
			}
		}
		assertEquals(NightSky.AURORA_NO_NIGHT, without / (float) nights, 0.04);
	}

	@Test
	public void auroraFadesInAndOutWithThePlace()
	{
		// find a night with one
		long t = 0;
		while (NightSky.auroraNight(t) == 0)
		{
			t += GameClock.DAY;
		}
		float expected = NightSky.auroraNight(t);
		NightSky sky = new NightSky();
		float seconds = 0;
		sky.update(seconds, 0, null, 0.001f, "GRASSLAND", t, true);
		for (int i = 0; i < 100; ++i)
		{
			sky.update(seconds += 0.1f, 0, null, 0.001f, "GRASSLAND", t, true);
		}
		assertEquals(0, sky.aurora, 0);
		// into the north: it comes up slowly, not at once
		sky.update(seconds += 0.1f, 0, null, 0.001f, "TUNDRA", t, true);
		assertTrue(sky.aurora > 0 && sky.aurora < expected);
		for (int i = 0; i < 200; ++i)
		{
			sky.update(seconds += 0.1f, 0, null, 0.001f, "TUNDRA", t, true);
		}
		assertEquals(expected, sky.aurora, 1e-6);
		// the setting off, or leaving, fades it away
		for (int i = 0; i < 200; ++i)
		{
			sky.update(seconds += 0.1f, 0, null, 0.001f, "TUNDRA", t, false);
		}
		assertEquals(0, sky.aurora, 0);
		// a long frame (loading) does not jump it
		NightSky jump = new NightSky();
		jump.update(0, 0, null, 0.001f, "ARCTIC", t, true);
		jump.update(60, 0, null, 0.001f, "ARCTIC", t, true);
		assertTrue(jump.aurora <= 0.1f / NightSky.AURORA_FADE_SECONDS + 1e-6);
	}
}
