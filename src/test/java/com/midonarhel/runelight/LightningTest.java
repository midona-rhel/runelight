package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.*;

public class LightningTest
{
	private static final float DT = 1 / 60f;

	/** One strike, frame by frame, with the shadow map drawn this many frames after the light turns. */
	private static final class Strike
	{
		float darkAt = -1, fullDarkAt = -1, aimAt = -1, firstFlashAt = -1, lastFlashAt = -1, returnAt = -1, endAt = -1;
		int pulses;
		float maxFlash, elevation, flashWhileLit;
		final float[] dir = new float[3];
	}

	private static Strike run(Lightning l, int drawFrames)
	{
		Strike s = new Strike();
		float[] strike = new float[2];
		float[] casterDir = new float[3];
		float t = 0;
		boolean aiming = false, wasFlashing = false;
		int sinceTurn = 0;
		for (int guard = 0; guard < 60 * 3600; ++guard)
		{
			l.update(DT, 1, true, 0, 0);
			l.getStrike(strike);
			t += DT;
			if (strike[0] > 0)
			{
				break;
			}
		}
		assertTrue(strike[0] > 0);
		s.darkAt = 0;
		t = 0;
		while (strike[0] > 0)
		{
			boolean now = l.aim(casterDir);
			if (now != aiming)
			{
				aiming = now;
				sinceTurn = 0;
				if (now)
				{
					s.aimAt = t;
					System.arraycopy(casterDir, 0, s.dir, 0, 3);
				}
				else
				{
					s.returnAt = t;
				}
			}
			++sinceTurn;
			l.setShadowsReady(sinceTurn > drawFrames, casterDir);
			l.update(DT, 1, true, 0, 0);
			l.getStrike(strike);
			t += DT;
			if (strike[0] >= 1 && s.fullDarkAt < 0)
			{
				s.fullDarkAt = t;
			}
			boolean flashing = strike[1] > 0;
			if (flashing)
			{
				s.maxFlash = Math.max(s.maxFlash, strike[1]);
				s.flashWhileLit = Math.max(s.flashWhileLit, 1 - strike[0]);
				if (s.firstFlashAt < 0)
				{
					s.firstFlashAt = t;
				}
				s.lastFlashAt = t;
				if (!wasFlashing)
				{
					++s.pulses;
				}
			}
			wasFlashing = flashing;
		}
		s.endAt = t;
		return s;
	}

	@Test
	public void strikeTakesOverTheLight()
	{
		boolean one = false, two = false;
		Lightning l = new Lightning();
		for (int i = 0; i < 30; ++i)
		{
			Strike s = run(l, 4);
			// the sun is out within 0.15 s and the light turns to the bolt then, low over the horizon
			assertEquals(0.15f, s.fullDarkAt, 2 * DT);
			assertEquals(0.15f, s.aimAt, 2 * DT);
			float len = (float) Math.sqrt(s.dir[0] * s.dir[0] + s.dir[1] * s.dir[1] + s.dir[2] * s.dir[2]);
			assertEquals(1, len, 1e-4f);
			double elevation = Math.toDegrees(Math.asin(-s.dir[1]));
			assertTrue("elevation " + elevation, elevation >= 29.99 && elevation <= 40.01);
			// the flash is at full strength, only with the sun out, after the map is drawn for the new light
			assertEquals(0, s.flashWhileLit, 1e-3f);
			assertTrue(s.maxFlash >= 0.8f && s.maxFlash <= 1);
			assertTrue(s.firstFlashAt - s.aimAt >= 0.1f && s.firstFlashAt - s.aimAt < 0.2f);
			assertTrue(s.pulses == 1 || s.pulses == 2);
			one |= s.pulses == 1;
			two |= s.pulses == 2;
			// the light is back on the sun after the last pulse, and the sun is back within 0.3 s of the map
			assertTrue(s.returnAt >= s.lastFlashAt - DT);
			assertTrue(s.endAt - s.returnAt < 0.5f);
			assertTrue("strike " + s.endAt, s.endAt < 2.5f);
		}
		assertTrue(one && two);
	}

	@Test
	public void strikeWaitsForTheShadowsButNotForever()
	{
		Strike quick = run(new Lightning(), 3);
		Strike slow = run(new Lightning(), 20);
		assertTrue(slow.firstFlashAt - slow.aimAt > quick.firstFlashAt - quick.aimAt + 0.2f);
		// the map never being ready (shadows off or indoors) holds the dark for the longest wait only
		Strike never = run(new Lightning(), Integer.MAX_VALUE / 2);
		assertEquals(1f, never.firstFlashAt - never.aimAt, 3 * DT);
	}

	@Test
	public void pulseRisesAndFallsSmoothly()
	{
		assertEquals(0, Lightning.pulse(0), 0);
		assertEquals(1, Lightning.pulse(0.05f), 1e-6f);
		assertEquals(0, Lightning.pulse(0.2f), 1e-6f);
		float previous = 0;
		for (float t = 0; t <= 0.05f; t += 0.001f)
		{
			assertTrue(Lightning.pulse(t) >= previous);
			previous = Lightning.pulse(t);
		}
		for (float t = 0.05f; t <= 0.2f; t += 0.001f)
		{
			assertTrue(Lightning.pulse(t) <= previous + 1e-6f);
			previous = Lightning.pulse(t);
		}
	}

	@Test
	public void strikeOnRequestInAnyWeather()
	{
		Lightning l = new Lightning();
		float[] strike = new float[2];
		l.update(DT, 0, false, 0, 0);
		l.getStrike(strike);
		assertEquals(0, strike[0], 0);
		l.strikeNow();
		l.update(DT, 0, false, 0, 0);
		l.getStrike(strike);
		assertTrue(strike[0] > 0);
		// a request during a strike is dropped, not queued
		l.strikeNow();
		Strike s = run(l, 4);
		assertTrue(s.pulses >= 1);
		for (int i = 0; i < 600; ++i)
		{
			l.update(DT, 0, false, 0, 0);
			l.getStrike(strike);
			assertEquals(0, strike[0], 0);
		}
	}

	@Test
	public void noStormNoStrike()
	{
		Lightning l = new Lightning();
		float[] strike = new float[2];
		float[] dir = new float[3];
		for (int i = 0; i < 600 * 60; ++i)
		{
			l.update(DT, 0.3f, true, 0, 0);
			l.update(DT, 1, false, 0, 0);
			l.getStrike(strike);
			assertEquals(0, strike[0], 0);
			assertEquals(0, strike[1], 0);
			assertFalse(l.aim(dir));
		}
	}
}
