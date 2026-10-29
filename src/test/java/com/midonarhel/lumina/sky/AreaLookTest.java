package com.midonarhel.lumina.sky;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The fog rule: under the open sky the fog is the sky's horizon; an area with a sky override tints the sky and the
 * fog follows that horizon; underground the fog is the area's own colour and the sun is 0.
 */
public class AreaLookTest
{
	private static final float DAY = 40;

	@Test
	public void openSkyFogIsTheHorizon() throws Exception
	{
		SkyFrame frame = new SkyFrame();
		new SkyModel().evaluate(DAY, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, frame);
		for (int c = 0; c < 3; c++)
		{
			assertEquals(frame.horizon[c], frame.fog[c], 0);
		}
		assertTrue(frame.sunColour[0] > 0);
		assertTrue(frame.skyFill[0] > 0);
		assertEquals(SkyModel.CLEAR_DAY_FOG_DEPTH, frame.fogDepth, 0);
	}

	@Test
	public void morytaniaTintsTheSkyAndTheFogFollowsIt() throws Exception
	{
		AreaLook.Table looks = AreaLook.Table.load(new Gson());
		AreaLook morytania = looks.get("MORYTANIA");
		assertNotNull(morytania);
		assertEquals(0, morytania.underground, 0);
		assertTrue(morytania.haze > 0);
		assertEquals(1, morytania.fogWeight, 0);

		SkyModel model = new SkyModel();
		SkyFrame plain = new SkyFrame(), tinted = new SkyFrame();
		model.evaluate(DAY, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, plain);
		model.evaluate(DAY, morytania, SkyModifiers.NEUTRAL, tinted);

		// the fog is still the sky's horizon, the tinted one
		for (int c = 0; c < 3; c++)
		{
			assertEquals(tinted.horizon[c], tinted.fog[c], 0);
		}
		// the sky moved: greener than the plain blue sky (the tint), and the horizon moved toward the swamp fog colour
		assertTrue(tinted.horizon[1] / tinted.horizon[2] > plain.horizon[1] / plain.horizon[2]);
		assertTrue(tinted.zenith[1] / tinted.zenith[2] > plain.zenith[1] / plain.zenith[2]);
		float[] fog = new float[3];
		SkyModel.srgbToLinear(morytania.fogColor, fog);
		assertTrue(distance(tinted.horizon, fog) < distance(plain.horizon, fog));
		// the horizon takes more of it than the zenith
		assertTrue(distance(tinted.horizon, fog) / distance(plain.horizon, fog)
			< distance(tinted.zenith, fog) / distance(plain.zenith, fog));
		// the light is the area's multipliers, not the fog's: the sun is still up
		assertTrue(tinted.sunColour[0] > 0);
		assertEquals(plain.sunColour[0] * morytania.sun[0], tinted.sunColour[0], 1e-5);
	}

	@Test
	public void undergroundHasNoSkyAndItsOwnFog() throws Exception
	{
		AreaLook.Table looks = AreaLook.Table.load(new Gson());
		AreaLook cave = looks.get("LUMBRIDGE_SWAMP_CAVES");
		assertNotNull(cave);
		assertEquals(1, cave.underground, 0);

		SkyFrame frame = new SkyFrame();
		new SkyModel().evaluate(DAY, cave, SkyModifiers.NEUTRAL, frame);
		float[] fog = new float[3];
		SkyModel.srgbToLinear(cave.fogColor, fog);
		for (int c = 0; c < 3; c++)
		{
			assertEquals(fog[c], frame.fog[c], 1e-6);
			assertEquals(0, frame.sunColour[c], 0);
			assertEquals(0, frame.moonColour[c], 0);
			assertEquals(0, frame.skyFill[c], 0);
			assertEquals(SkyModel.CAVE_AMBIENT[c] * cave.ambient[c], frame.ambient[c], 1e-6);
		}
		// at night too: the cave does not follow the day
		SkyFrame night = new SkyFrame();
		new SkyModel().evaluate(-30, cave, SkyModifiers.NEUTRAL, night);
		for (int c = 0; c < 3; c++)
		{
			assertEquals(frame.ambient[c], night.ambient[c], 1e-6);
			assertEquals(frame.fog[c], night.fog[c], 1e-6);
		}
	}

	@Test
	public void blendingIntoACaveFadesTheSunAndNeverPassesThroughBlackFog()
	{
		AreaLook cave = new AreaLook();
		cave.underground = 1;
		cave.fogWeight = 1;
		cave.fogColor[0] = 0.1f;
		cave.fogColor[1] = 0.2f;
		cave.fogColor[2] = 0.3f;
		AreaLook half = new AreaLook();
		half.mix(AreaLook.OVERWORLD, cave, 0.5f);
		assertEquals(0.5f, half.underground, 0);
		assertEquals(0.5f, half.fogWeight, 0);
		// the overworld look has no fog colour of its own, so the blend takes the cave's instead of black
		assertEquals(0.2f, half.fogColor[1], 1e-6);

		SkyFrame full = new SkyFrame(), mid = new SkyFrame();
		new SkyModel().evaluate(DAY, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, full);
		new SkyModel().evaluate(DAY, half, SkyModifiers.NEUTRAL, mid);
		assertEquals(full.sunColour[0] * 0.5f, mid.sunColour[0], 1e-5);
		assertEquals(full.skyFill[0] * 0.5f, mid.skyFill[0], 1e-6);
	}

	private static float distance(float[] a, float[] b)
	{
		float d = 0;
		for (int c = 0; c < 3; c++)
		{
			d += (a[c] - b[c]) * (a[c] - b[c]);
		}
		return (float) Math.sqrt(d);
	}
}
