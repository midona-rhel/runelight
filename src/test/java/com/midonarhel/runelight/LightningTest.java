package com.midonarhel.runelight;

import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

public class LightningTest
{
	private static final float DT = 0.01f;

	/** What one strike looked like frame by frame. */
	private static final class Strike
	{
		float announcedFor;
		float keyFor;
		float peak;
		float flashBeforeKey;
		int pulses;
		float closestPulses = Float.MAX_VALUE;
	}

	private static Strike run(Lightning l, float focusX, float focusZ)
	{
		Strike s = new Strike();
		float t = 0;
		float lastPeakAt = -1;
		float previous = 0, before = 0;
		// wait for the strike, then follow it to its end
		for (int guard = 0; guard < 100000 && !l.isStriking(); ++guard)
		{
			l.update(DT, 1, true, focusX, focusZ);
		}
		assertTrue(l.isStriking());
		while (l.isStriking())
		{
			boolean key = l.isKey();
			if (!key && s.keyFor == 0)
			{
				s.announcedFor += DT;
				s.flashBeforeKey = Math.max(s.flashBeforeKey, l.getFlash());
			}
			if (key)
			{
				s.keyFor += DT;
			}
			float f = l.getFlash();
			s.peak = Math.max(s.peak, f);
			// a pulse is a local maximum of the flash
			if (previous > before && previous >= f && previous > 0.02f)
			{
				++s.pulses;
				if (lastPeakAt >= 0)
				{
					s.closestPulses = Math.min(s.closestPulses, t - lastPeakAt);
				}
				lastPeakAt = t;
			}
			before = previous;
			previous = f;
			t += DT;
			l.update(DT, 1, true, focusX, focusZ);
			assertTrue("a strike is over in a few seconds", t < 10);
		}
		assertEquals(0, l.getFlash(), 0);
		assertFalse(l.isKey());
		return s;
	}

	@Test
	public void aStrikeIsAnnouncedBeforeItsFirstPulseAndTheBoltThenCastsShadows()
	{
		Lightning l = new Lightning(new Random(1));
		Strike s = run(l, 0, 0);
		assertEquals(Lightning.LEAD, s.announcedFor, 2 * DT);
		assertEquals("no light before the first pulse", 0, s.flashBeforeKey, 0);
		assertTrue(s.keyFor > 0.3f);
		assertTrue(s.peak > 0.4f && s.peak <= 1);
	}

	@Test
	public void strikesVaryInPulsesAndStrengthAndKeepTheirDistance()
	{
		Lightning l = new Lightning(new Random(7));
		boolean[] pulses = new boolean[5];
		float weakest = 1, strongest = 0;
		for (int i = 0; i < 60; ++i)
		{
			Strike s = run(l, 0, 0);
			assertTrue("pulses " + s.pulses, s.pulses >= 1 && s.pulses <= 4);
			pulses[s.pulses] = true;
			weakest = Math.min(weakest, s.peak);
			strongest = Math.max(strongest, s.peak);
			// under three a second, as it always was
			assertTrue("pulses " + s.closestPulses + " s apart", s.closestPulses > 0.3f);
		}
		assertTrue("single flashes", pulses[1]);
		assertTrue("double flashes", pulses[2]);
		assertTrue("flickers", pulses[3] || pulses[4]);
		assertTrue(strongest - weakest > 0.25f);
	}

	@Test
	public void theBoltsLightComesFromTheStrike()
	{
		Lightning l = new Lightning(new Random(3));
		for (int i = 0; i < 20; ++i)
		{
			run(l, 5000, -2000);
			float[] d = l.getDirection();
			assertEquals(1, Math.sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]), 1e-4);
			assertTrue("from above", d[1] < -0.49f && d[1] > -0.93f);
		}
		// toward the strike, whichever way it lies, and steeper from a near one
		float[] near = new float[3], far = new float[3];
		DayCycle.boltDirection(near, 300, 400);
		DayCycle.boltDirection(far, 6000, 8000);
		assertEquals(0.6f, near[0] / (float) Math.hypot(near[0], near[2]), 1e-4);
		assertEquals(0.8f, near[2] / (float) Math.hypot(near[0], near[2]), 1e-4);
		assertEquals(0.6f, far[0] / (float) Math.hypot(far[0], far[2]), 1e-4);
		assertTrue(near[1] < far[1]);
		DayCycle.boltDirection(near, -1000, 0);
		assertTrue(near[0] < 0);
		assertEquals(0, near[2], 1e-4);
	}

	@Test
	public void noStrikeWithoutAStorm()
	{
		Lightning l = new Lightning(new Random(5));
		for (int i = 0; i < 20000; ++i)
		{
			l.update(DT, 0.3f, true, 0, 0);
			assertFalse(l.isStriking());
		}
		assertFalse(l.isStorming());
		l.update(DT, 1, true, 0, 0);
		assertTrue("storms keep the bolt's shadow map", l.isStorming());
		l.update(DT, 1, false, 0, 0);
		assertFalse(l.isStorming());
	}
}
