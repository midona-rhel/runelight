package com.midonarhel.lumina.sky;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SkyModelTest
{
	@Test
	public void paletteHitsItsKeysAndStaysBetweenThem()
	{
		checkCurve(SkyModel.ZENITH_ALT, SkyModel.ZENITH_COLOR);
		checkCurve(SkyModel.HORIZON_ALT, SkyModel.HORIZON_COLOR);
	}

	private static void checkCurve(float[] keys, float[][] colours)
	{
		MonotoneCurve curve = new MonotoneCurve(keys, colours);
		float[] out = new float[3];
		for (int i = 0; i < keys.length; i++)
		{
			curve.evaluate(keys[i], out);
			for (int c = 0; c < 3; c++)
			{
				assertEquals("key " + keys[i], colours[i][c], out[c], 1e-6);
			}
		}
		// monotone between keys: never outside the two end colours of a segment, no overshoot
		for (int i = 0; i < keys.length - 1; i++)
		{
			for (int s = 1; s < 50; s++)
			{
				float at = keys[i] + (keys[i + 1] - keys[i]) * s / 50f;
				curve.evaluate(at, out);
				for (int c = 0; c < 3; c++)
				{
					float lo = Math.min(colours[i][c], colours[i + 1][c]), hi = Math.max(colours[i][c], colours[i + 1][c]);
					assertTrue("at " + at + " channel " + c + ": " + out[c], out[c] >= lo - 1e-6 && out[c] <= hi + 1e-6);
				}
			}
		}
		// clamped outside
		curve.evaluate(keys[0] - 100, out);
		assertEquals(colours[0][0], out[0], 1e-6);
		curve.evaluate(keys[keys.length - 1] + 100, out);
		assertEquals(colours[keys.length - 1][0], out[0], 1e-6);
	}

	@Test
	public void frameAtTheKeysIsThePaletteInLinear()
	{
		SkyModel model = new SkyModel();
		SkyFrame frame = new SkyFrame();
		for (int i = 0; i < SkyModel.ZENITH_ALT.length; i++)
		{
			float alt = SkyModel.ZENITH_ALT[i];
			model.evaluate(alt, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, frame);
			float gain = SkyModel.nightGain(alt, 1.5f);
			for (int c = 0; c < 3; c++)
			{
				assertEquals(SkyModel.srgbToLinear(SkyModel.ZENITH_COLOR[i][c] * gain), frame.zenith[c], 1e-5);
			}
		}
		// the sun's light obeys the dead band, the moon's too
		model.evaluate(-2, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, frame);
		assertEquals(0, frame.sunColour[0], 0);
		assertEquals(0, frame.moonColour[0], 0);
		model.evaluate(-5, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, frame);
		assertTrue(frame.moonColour[2] > 0);
		assertEquals(0, frame.sunColour[0], 0);
		// neutral modifiers are the identity: the same frame with a fresh neutral set
		SkyFrame again = new SkyFrame();
		model.evaluate(30, AreaLook.OVERWORLD, new SkyModifiers(), again);
		model.evaluate(30, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, frame);
		for (int c = 0; c < 3; c++)
		{
			assertEquals(frame.ambient[c], again.ambient[c], 0);
			assertEquals(frame.sunColour[c], again.sunColour[c], 0);
			assertEquals(frame.horizon[c], again.horizon[c], 0);
		}
	}
}
