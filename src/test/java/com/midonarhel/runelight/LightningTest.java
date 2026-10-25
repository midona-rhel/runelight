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
		int peaks, takeovers;
		// the flash at each top, and the lowest it fell to between the first two
		final float[] top = new float[2];
		float dip = 1;
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
		boolean started = false, taken = false;
		float previous = 0, before = 0, sinceFirstTop = -1;
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
			if (f > 0 && s.prepared < 0)
			{
				s.prepared = t;
			}
			// a top is a local maximum of the flash
			if (previous > before && previous >= f && previous > 0.3f && s.peaks < 2)
			{
				s.top[s.peaks++] = previous;
				sinceFirstTop = s.peaks == 1 ? 0 : sinceFirstTop;
			}
			if (s.peaks == 1 && sinceFirstTop >= 0)
			{
				s.dip = Math.min(s.dip, f);
			}
			before = previous;
			previous = f;
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
			assertTrue(s.peaks == 1 || s.peaks == 2);
			one |= s.peaks == 1;
			two |= s.peaks == 2;
			if (s.peaks == 2)
			{
				// the second flare is quick, tops a little under the first, and starts from 40 to 60% of it (the dip a little lower, as the flare takes 0.04 s to overtake)
				assertTrue("second " + s.top[1] / s.top[0], s.top[1] / s.top[0] >= 0.69f && s.top[1] / s.top[0] <= 0.91f);
				assertTrue("dip " + s.dip / s.top[0], s.dip / s.top[0] >= 0.33f && s.dip / s.top[0] <= 0.62f);
			}
			// one flash, one takeover: the bolt stays the light across the dip, so the maps are swapped once each way
			assertEquals(1, s.takeovers);
			// no dark gap and no wait beyond the map's few frames
			assertTrue("prepared " + s.prepared, s.prepared > 0 && s.prepared < 0.1f);
			assertTrue("takeover " + (s.firstTakeover - s.prepared), s.firstTakeover - s.prepared < 0.05f);
			double elevation = Math.toDegrees(Math.asin(-s.dir[1]));
			assertTrue("elevation " + elevation, elevation >= 29.99 && elevation <= 40.01);
			// each pulse is on screen well over half a second at more than half strength
			assertTrue("bright " + s.flashAbove, s.flashAbove > 0.45f);
			assertTrue(s.maxFlash >= 0.8f && s.maxFlash <= 1);
			// the whole flash is over in 0.85 s, or 1.15 s with a flicker
			assertTrue(s.endAt < 0.1f + 0.85f + (s.peaks - 1) * 0.3f + 0.05f);
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
	public void flareIsQuick()
	{
		assertEquals(0, Lightning.flare(0), 0);
		assertEquals(1, Lightning.flare(0.04f), 1e-6f);
		assertEquals(1, Lightning.flare(0.12f), 0);
		assertEquals(0, Lightning.flare(0.47f), 1e-6f);
		// a pulse that has fallen to half is half way through its fall, and the share maps back to its time
		assertEquals(0.5f, Lightning.fallTime(0.5f), 1e-5f);
		for (float share = 0.4f; share <= 0.6f; share += 0.05f)
		{
			float x = Lightning.fallTime(share);
			assertEquals(share, 1 - x * x * (3 - 2 * x), 1e-4f);
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
	public void everyStrikeHasOneClapAtItsFlash()
	{
		Lightning l = new Lightning();
		float[] thunder = new float[2];
		for (int i = 0; i < 20; ++i)
		{
			l.strikeNow();
			int claps = 0;
			float flashAtClap = -1;
			boolean started = false;
			for (int f = 0; f < 120 * 10; ++f)
			{
				l.update(DT, 0, false, 0, 0);
				if (l.getBoltDir() != null)
				{
					started = true;
					l.setShadowMaps(f > 3, true);
				}
				else if (started)
				{
					break;
				}
				if (l.takeThunder(thunder))
				{
					++claps;
					flashAtClap = l.getFlash();
					// from the player to a bolt that comes down 3000 to 9000 units away, with the flash's top as strength
					assertTrue(thunder[0] >= 3000 && thunder[0] <= 9000);
					assertTrue(thunder[1] >= 0.8f && thunder[1] <= 1);
				}
			}
			assertEquals(1, claps);
			// as the flash begins: it is still at its very start
			assertTrue(flashAtClap < 0.1f);
		}
	}

	/** A strike's rumble of a constant envelope, for this many seconds. */
	private static Rumble steady(float seconds, float strength)
	{
		float[] envelope = new float[(int) (seconds * Rumble.RATE)];
		java.util.Arrays.fill(envelope, 1);
		return new Rumble(envelope, strength, 1, 0);
	}

	@Test
	public void rumbleGlowsAfterTheFlashAndIsCapped()
	{
		Lightning l = new Lightning();
		float[] thunder = new float[2];
		l.strikeNow();
		boolean rumbling = false, flashed = false;
		float after = 0, most = 0;
		for (int f = 0; f < 120 * 12; ++f)
		{
			l.update(DT, 0, false, 0, 0);
			if (l.getBoltDir() != null)
			{
				l.setShadowMaps(f > 3, true);
			}
			if (!rumbling && l.takeThunder(thunder))
			{
				l.rumble(steady(5, 1));
				rumbling = true;
			}
			most = Math.max(most, l.getFlash());
			assertTrue(l.getFlash() <= 1);
			if (rumbling && l.getBoltDir() == null && f > 120 * 2)
			{
				flashed = true;
				after = l.getFlash();
				break;
			}
		}
		// the flash is over, and the light still glows with the thunder, no more than the cap
		assertTrue(flashed);
		assertEquals(Lightning.RUMBLE_FLICKER, after, 1e-4f);
		assertTrue(most <= 1);
		// and when the thunder has gone the light is out
		for (int f = 0; f < 120 * 6; ++f)
		{
			l.update(DT, 0, false, 0, 0);
		}
		assertEquals(0, l.getFlash(), 0);
	}

	@Test
	public void rumbleFollowsTheClipsEnvelopeAndStrength()
	{
		// a clip that is silent, then loud, then silent; a strike heard at half strength
		float[] envelope = new float[Rumble.RATE * 3];
		java.util.Arrays.fill(envelope, Rumble.RATE, 2 * Rumble.RATE, 1);
		Rumble r = new Rumble(envelope, 0.5f, 1, 0.1f);
		assertEquals(0, r.level(0.5f), 0);
		assertEquals(0.5f, r.level(1.6f), 1e-6f);
		assertEquals(0, r.level(2.9f), 0);
		// the device's delay shifts it; a clip played faster is over sooner
		assertEquals(0, r.level(1.05f), 0);
		assertEquals(3.1f, r.duration(), 1e-5f);
		assertEquals(1.5f + 0.1f, new Rumble(envelope, 1, 2, 0.1f).duration(), 1e-5f);
	}

	@Test
	public void rumbleNeverStartsATakeover()
	{
		// no strike: the rumble alone, at full strength, never takes the caster, and the bolt's light stays off
		Lightning l = new Lightning();
		l.setSunStrength(0);
		l.rumble(steady(5, 1));
		float[] strike = new float[2], dir = new float[3];
		for (int f = 0; f < 120 * 5; ++f)
		{
			l.update(DT, 0, true, 0, 0);
			l.setShadowMaps(true, true);
			assertFalse(l.aim(dir));
			l.getStrike(strike);
			assertEquals(0, strike[0], 0);
			assertEquals(0, strike[1], 0);
		}
		// a strike too weak to pass a bright sun: the glow brightens the sky, the caster stays the sun's
		l = new Lightning();
		l.setSunStrength(100);
		l.strikeNow();
		float[] thunder = new float[2];
		boolean glowed = false;
		for (int f = 0; f < 120 * 8; ++f)
		{
			l.update(DT, 0, false, 0, 0);
			if (l.getBoltDir() != null)
			{
				l.setShadowMaps(f > 3, true);
			}
			if (l.takeThunder(thunder))
			{
				l.rumble(steady(5, 1));
			}
			assertFalse(l.aim(dir));
			l.getStrike(strike);
			assertEquals(0, strike[0], 0);
			glowed |= l.getFlash() > 0;
		}
		assertTrue(glowed);
	}

	@Test
	public void boltLightGlowsOnlyWhilePastTheSun()
	{
		// the sun at 2: the bolt (4 at its top) holds the caster while its curve is above 2
		Lightning l = new Lightning();
		l.setSunStrength(2);
		l.strikeNow();
		float[] thunder = new float[2], strike = new float[2];
		boolean taken = false;
		for (int f = 0; f < 120 * 4; ++f)
		{
			l.update(DT, 0, false, 0, 0);
			if (l.getBoltDir() != null)
			{
				l.setShadowMaps(f > 3, true);
			}
			if (l.takeThunder(thunder))
			{
				l.rumble(steady(5, 1));
			}
			l.getStrike(strike);
			if (strike[0] == 1)
			{
				taken = true;
				// never less than the sun's, which it passed to take the caster, and never more than the cap allows
				assertTrue(strike[1] >= 2 - 1e-3f);
				assertTrue(strike[1] <= (1 + Lightning.RUMBLE_FLICKER) * Lightning.BRIGHTNESS + 1e-3f);
			}
		}
		assertTrue(taken);
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
