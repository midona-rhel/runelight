package com.midonarhel.lumina.sky;

import java.util.Random;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SkyLightTest
{
	private static SkyLight bake(double hour, Celestial c, SkyFrame frame)
	{
		c.update(hour);
		new SkyModel().evaluate(c.sunAltitude, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, frame);
		SkyLight light = new SkyLight();
		float[] caster = c.casterKind == Celestial.Caster.SUN ? frame.sunColour : frame.moonColour;
		assertTrue(light.update(frame, c.sunDir, c.sunAltitude, c.sunAzimuth, frame.ambient, caster, c.casterDir));
		return light;
	}

	@Test
	public void neverNegativeAndBrighterByDay()
	{
		Celestial c = new Celestial();
		SkyFrame frame = new SkyFrame();
		SkyLight noon = bake(12, c, frame);
		float[] up = {0, -1, 0}, down = {0, 1, 0}, out = new float[3];
		float[] noonUp = noon.evaluate(up, new float[3]);
		assertTrue(noonUp[0] > 0 && noonUp[1] > 0 && noonUp[2] > 0);
		// the sky is blue by day
		assertTrue(noonUp[2] > noonUp[0]);
		// a downward face gets the ground's bounce only: some, but never more than the ambient light it also gets
		float[] noonDown = noon.evaluate(down, new float[3]);
		assertTrue(noonDown[1] > 0 && noonDown[1] < frame.ambient[1]);

		Random random = new Random(7);
		for (int i = 0; i < 1000; i++)
		{
			float[] n = {(float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian()};
			float len = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			n[0] /= len;
			n[1] /= len;
			n[2] /= len;
			noon.evaluate(n, out);
			for (float v : out)
			{
				assertTrue(v >= 0);
			}
		}

		SkyLight night = bake(0, c, frame);
		float[] nightUp = night.evaluate(up, new float[3]);
		assertTrue(nightUp[1] >= 0 && nightUp[1] < noonUp[1] * 0.2f);
	}

	@Test
	public void rebakesOnlyWhenTheSunMovedOrTheSkyChanged()
	{
		Celestial c = new Celestial();
		SkyFrame frame = new SkyFrame();
		SkyLight light = bake(10, c, frame);
		float[] caster = frame.sunColour;
		// nothing moved: no rebake, however many frames
		for (int i = 0; i < 10; i++)
		{
			assertFalse(light.update(frame, c.sunDir, c.sunAltitude, c.sunAzimuth, frame.ambient, caster, c.casterDir));
		}
		// a small sun step does not, a step over the threshold does
		assertFalse(light.update(frame, c.sunDir, c.sunAltitude + 0.2f, c.sunAzimuth, frame.ambient, caster, c.casterDir));
		assertTrue(light.update(frame, c.sunDir, c.sunAltitude + 0.3f, c.sunAzimuth, frame.ambient, caster, c.casterDir));
		// the sky changing by 1% per frame (an area fade): at most every 4th frame
		int rebakes = 0;
		for (int i = 0; i < 40; i++)
		{
			for (int k = 0; k < 3; k++)
			{
				frame.skyFill[k] *= 0.99f;
			}
			if (light.update(frame, c.sunDir, c.sunAltitude + 0.3f, c.sunAzimuth, frame.ambient, caster, c.casterDir))
			{
				rebakes++;
			}
		}
		assertTrue("rebakes " + rebakes, rebakes >= 8 && rebakes <= 10);
		// a 1% step alone, after the frame gate, does not
		for (int i = 0; i < 4; i++)
		{
			light.update(frame, c.sunDir, c.sunAltitude + 0.3f, c.sunAzimuth, frame.ambient, caster, c.casterDir);
		}
		for (int k = 0; k < 3; k++)
		{
			frame.skyFill[k] *= 1.01f;
		}
		assertFalse(light.update(frame, c.sunDir, c.sunAltitude + 0.3f, c.sunAzimuth, frame.ambient, caster, c.casterDir));

		// the cost of one integration
		long best = Long.MAX_VALUE, total = 0;
		int runs = 300;
		for (int i = 0; i < runs; i++)
		{
			// a fresh instance integrates on its first update (the only path that does the work)
			SkyLight fresh = new SkyLight();
			long t0 = System.nanoTime();
			assertTrue(fresh.update(frame, c.sunDir, c.sunAltitude, c.sunAzimuth, frame.ambient, caster, c.casterDir));
			long dt = System.nanoTime() - t0;
			best = Math.min(best, dt);
			if (i >= runs / 2)
			{
				total += dt;
			}
		}
		System.out.printf("SkyLight integration (%d directions): best %.1f us, mean of the warm half %.1f us%n",
			SkyLight.SAMPLES, best / 1000.0, total / (runs / 2) / 1000.0);

		// the irradiance of a uniform dome of radiance 1 is pi for the up normal, 0 for the down one (without bounce)
		SkyFrame flat = new SkyFrame();
		java.util.Arrays.fill(flat.zenith, 1);
		java.util.Arrays.fill(flat.horizon, 1);
		java.util.Arrays.fill(flat.skyFill, 1);
		float[] noSun = {0, 1, 0};
		SkyLight uniform = new SkyLight();
		uniform.update(flat, noSun, 0, 0, new float[3], new float[3], noSun);
		assertEquals(Math.PI, uniform.c0[0] - uniform.c1[3], 0.05);
		assertEquals(0, uniform.c0[0] + uniform.c1[3], 0.05);
	}
}
