package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.*;

public class LightningTest
{
	private static final float DT = 1 / 120f;

	/** One strike, frame by frame. */
	private static final class Strike
	{
		float prepared = -1, firstTakeover = -1, lastTakeover = -1, endAt, flashAbove, maxFlash;
		int pulses, takeovers;
		boolean sunFaded;
		float continuityGap;
		final float[] dir = new float[3];
		final float[] startsOfTakeover = new float[2];
	}

	/** Follow the strike the plugin would: the bolt's map is ready this many frames after the strike is decided. */
	private static Strike run(Lightning l, float sunStrength, int drawFrames)
	{
		Strike s = new Strike();
		float[] strike = new float[2];
		float[] casterDir = new float[3];
		float t = 0;
		int frames = 0;
		boolean started = false, taken = false, flashing = false;
		l.setSunStrength(sunStrength);
		for (int guard = 0; guard < 120 * 3600; ++guard)
		{
			l.update(DT, 1, true, 0, 0);
			float[] bolt = l.getBoltDir();
			if (bolt != null)
			{
				if (!started)
				{
					started = true;
					t = 0;
					frames = 0;
				}
				++frames;
				l.setShadowMaps(frames > drawFrames, true);
			}
			else if (started)
			{
				break;
			}
			if (!started)
			{
				continue;
			}
			t += DT;
			l.getStrike(strike);
			float f = l.getFlash();
			s.maxFlash = Math.max(s.maxFlash, f);
			if (f > 0.5f)
			{
				s.flashAbove += DT;
			}
			if (f > 0 && !flashing)
			{
				++s.pulses;
				if (s.prepared < 0)
				{
					s.prepared = t;
				}
			}
			flashing = f > 0;
			boolean now = l.aim(casterDir);
			if (!now)
			{
				// the sun is never faded: only the bolt's flag and light are ever set
				assertEquals(0, strike[0], 0);
				assertEquals(0, strike[1], 0);
			}
			else
			{
				assertEquals(1, strike[0], 0);
				// the bolt takes over once it is the brighter light, and its light is then the flash times the knob
				assertEquals(f * Lightning.BRIGHTNESS, strike[1], 1e-4f);
				assertTrue(strike[1] > sunStrength);
				System.arraycopy(casterDir, 0, s.dir, 0, 3);
			}
			if (now && !taken)
			{
				++s.takeovers;
				if (s.firstTakeover < 0)
				{
					s.firstTakeover = t;
				}
			}
			if (now)
			{
				s.lastTakeover = t;
			}
			taken = now;
		}
		s.endAt = t;
		return s;
	}

	@Test
	public void boltTakesOverWhenBrighter()
	{
		boolean one = false, two = false;
		Lightning l = new Lightning();
		for (int i = 0; i < 30; ++i)
		{
			// a storm's dim sun: the bolt passes it early in the rise
			Strike s = run(l, 0.8f, 4);
			assertTrue(s.pulses == 1 || s.pulses == 2);
			one |= s.pulses == 1;
			two |= s.pulses == 2;
			assertEquals(s.pulses, s.takeovers);
			// no dark gap and no wait beyond the map's few frames
			assertTrue("prepared " + s.prepared, s.prepared > 0 && s.prepared < 0.1f);
			assertTrue("takeover " + (s.firstTakeover - s.prepared), s.firstTakeover - s.prepared < 0.05f);
			double elevation = Math.toDegrees(Math.asin(-s.dir[1]));
			assertTrue("elevation " + elevation, elevation >= 29.99 && elevation <= 40.01);
			// each pulse is on screen well over half a second at more than half strength
			assertTrue("bright " + s.flashAbove / s.pulses, s.flashAbove / s.pulses > 0.45f);
			assertTrue(s.maxFlash >= 0.8f && s.maxFlash <= 1);
			// at most one pulse a second
			assertTrue(s.endAt < 0.1f + 0.85f + (s.pulses - 1) * 1.1f + 0.05f);
		}
		assertTrue(one && two);
	}

	@Test
	public void brightSunIsOnlyBeatenAtTheTop()
	{
		// a clear noon: the bolt (4) passes the sun (2.6) at 65% of the flash, so less of each pulse
		Strike storm = run(new Lightning(), 0.8f, 4);
		Strike clear = run(new Lightning(), 2.6f, 4);
		float stormTaken = storm.lastTakeover - storm.firstTakeover;
		float clearTaken = clear.lastTakeover - clear.firstTakeover;
		assertTrue(clear.takeovers > 0);
		assertTrue(clearTaken > 0.1f);
		// and never at all against a sun brighter than the bolt can be
		Strike blinding = run(new Lightning(), 5, 4);
		assertEquals(0, blinding.takeovers);
		assertTrue(stormTaken > 0 || storm.takeovers > 0);
	}

	@Test
	public void flashWaitsForTheBoltsMapAndGivesUp()
	{
		Strike quick = run(new Lightning(), 0.8f, 3);
		Strike slow = run(new Lightning(), 0.8f, 40);
		assertTrue(slow.prepared > quick.prepared + 0.25f);
		// a map that never gets drawn drops the strike: no flash, no takeover
		Strike never = run(new Lightning(), 0.8f, Integer.MAX_VALUE / 2);
		assertEquals(0, never.takeovers);
		assertEquals(0, never.maxFlash, 0);
		assertEquals(1f, never.endAt, 0.05f);
	}

	@Test
	public void pulseRisesHoldsAndFalls()
	{
		assertEquals(0, Lightning.pulse(0), 0);
		assertEquals(1, Lightning.pulse(0.05f), 1e-6f);
		assertEquals(1, Lightning.pulse(0.3f), 0);
		assertEquals(1, Lightning.pulse(0.45f), 0);
		assertEquals(0, Lightning.pulse(0.85f), 1e-6f);
		float previous = 0;
		for (float t = 0; t <= 0.05f; t += 0.001f)
		{
			assertTrue(Lightning.pulse(t) >= previous);
			previous = Lightning.pulse(t);
		}
		for (float t = 0.45f; t <= 0.85f; t += 0.001f)
		{
			assertTrue(Lightning.pulse(t) <= previous + 1e-6f);
			previous = Lightning.pulse(t);
		}
	}

	@Test
	public void directStrengthOfTheSunAndMoon()
	{
		DayCycle day = new DayCycle();
		day.sunIsCaster = true;
		day.sunAltitude = 60;
		assertEquals(2.67f, day.directStrength(0, 0), 0.05f);
		// a full rain overcast keeps 30%, snow 42%
		assertEquals(0.3f * day.directStrength(0, 0), day.directStrength(1, 0), 1e-4f);
		assertEquals(0.42f * day.directStrength(0, 0), day.directStrength(1, 1), 1e-4f);
		day.sunAltitude = 0;
		assertTrue(day.directStrength(0, 0) < 1);
		day.sunIsCaster = false;
		day.sunAltitude = -30;
		assertEquals(0.37f, day.directStrength(0, 0), 0.01f);
	}

	@Test
	public void strikeOnRequestInAnyWeather()
	{
		Lightning l = new Lightning();
		l.update(DT, 0, false, 0, 0);
		assertNull(l.getBoltDir());
		l.strikeNow();
		l.update(DT, 0, false, 0, 0);
		assertNotNull(l.getBoltDir());
		// a request during a strike is dropped, not queued
		l.strikeNow();
		Strike s = run(l, 0.8f, 4);
		assertTrue(s.takeovers >= 1);
		for (int i = 0; i < 1200; ++i)
		{
			l.update(DT, 0, false, 0, 0);
			assertNull(l.getBoltDir());
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
			l.update(1 / 60f, 0.3f, true, 0, 0);
			l.update(1 / 60f, 1, false, 0, 0);
			l.getStrike(strike);
			assertEquals(0, strike[0], 0);
			assertEquals(0, strike[1], 0);
			assertFalse(l.aim(dir));
			assertNull(l.getBoltDir());
		}
	}
}
