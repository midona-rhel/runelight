package com.midonarhel.lumina.sky;

import com.midonarhel.lumina.lighting.SkyLighting;
import com.midonarhel.lumina.util.ColorUtils;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The brightness calibration: Lumina's noon (sun 70 degrees, caster at its 68 degree clamp) on flat ground in the
 * overworld equals 117 HD's DAY preset (its sun at 52 degrees) in the OVERWORLD environment, which is what the user
 * runs. {@link SkyModel#BRIGHTNESS} is set from this; the test pins it to 2%. The wall facing the light differs by
 * the two angles' cosines and is reported, not pinned.
 * <p>
 * 117 HD's side: OVERWORLD (environments.json: ambient #97baff x 1, directional white x 4) through
 * SkyLighting.applyAtmosphere at 52 degrees (its evaluateLighting), summed as its scene shader does: ambient + sun * N.L
 * + half the fog colour (the sky gradient's horizon, GIELINOR preset at 52 degrees) on upward faces. The night terms
 * are zero by day except the airglow (#94B8FF x 0.005), left out.
 */
public class BrightnessMatchTest
{
	private static final float HD_SUN_ALTITUDE = 52;

	@Test
	public void noonGroundMatches117HdDay()
	{
		// 117 HD, DAY preset
		float[] hdAmbient = ColorUtils.rgb("#97baff");
		float[] hdSun = {4, 4, 4};
		SkyLighting.applyAtmosphere(hdSun, hdAmbient, HD_SUN_ALTITUDE);
		float[] hdHorizon = mix(ColorUtils.rgb("#8CA0BE"), ColorUtils.rgb("#879BB9"), (HD_SUN_ALTITUDE - 50) / 40);
		float hdSin = (float) Math.sin(Math.toRadians(HD_SUN_ALTITUDE)), hdCos = (float) Math.cos(Math.toRadians(HD_SUN_ALTITUDE));
		float[] hdGround = new float[3], hdWall = new float[3];
		for (int i = 0; i < 3; i++)
		{
			hdGround[i] = hdAmbient[i] + hdSun[i] * hdSin + 0.5f * hdHorizon[i];
			hdWall[i] = hdAmbient[i] + hdSun[i] * hdCos;
		}

		// Lumina at noon
		Celestial c = new Celestial();
		c.update(12);
		assertEquals(68, c.casterElevation(), 1e-3);
		SkyFrame frame = new SkyFrame();
		new SkyModel().evaluate(c.sunAltitude, AreaLook.OVERWORLD, SkyModifiers.NEUTRAL, frame);
		SkyLight sky = new SkyLight();
		sky.update(frame, c.sunDir, c.sunAltitude, c.sunAzimuth, frame.ambient, frame.sunColour, c.casterDir);
		float[] up = {0, -1, 0};
		float[] wallNormal = {c.casterDir[0], 0, c.casterDir[2]};
		float len = (float) Math.sqrt(wallNormal[0] * wallNormal[0] + wallNormal[2] * wallNormal[2]);
		wallNormal[0] /= len;
		wallNormal[2] /= len;
		float[] skyUp = sky.evaluate(up, new float[3]), skyWall = sky.evaluate(wallNormal, new float[3]);
		float ndlGround = -c.casterDir[1];
		float ndlWall = wallNormal[0] * c.casterDir[0] + wallNormal[2] * c.casterDir[2];
		float[] ground = new float[3], wall = new float[3];
		for (int i = 0; i < 3; i++)
		{
			ground[i] = frame.ambient[i] + skyUp[i] + frame.sunColour[i] * ndlGround;
			wall[i] = frame.ambient[i] + skyWall[i] + frame.sunColour[i] * ndlWall;
		}

		float hdGroundY = luma(hdGround), hdWallY = luma(hdWall), groundY = luma(ground), wallY = luma(wall);
		System.out.printf("117 HD DAY (52 deg)  ground %s Y=%.3f  wall %s Y=%.3f  (ambient %s sun %s horizon %s)%n",
			str(hdGround), hdGroundY, str(hdWall), hdWallY, str(hdAmbient), str(hdSun), str(hdHorizon));
		System.out.printf("Lumina noon (68 deg) ground %s Y=%.3f  wall %s Y=%.3f  (ambient %s sky(up) %s sun %s)%n",
			str(ground), groundY, str(wall), wallY, str(frame.ambient), str(skyUp), str(frame.sunColour));
		System.out.printf("ratio   ground %.3f  wall %.3f  (BRIGHTNESS %.3f; for an exact ground match: %.4f)%n",
			groundY / hdGroundY, wallY / hdWallY, SkyModel.BRIGHTNESS, SkyModel.BRIGHTNESS * hdGroundY / groundY);
		assertTrue("ground " + groundY + " vs " + hdGroundY, Math.abs(groundY / hdGroundY - 1) <= 0.02);
	}

	private static float luma(float[] c)
	{
		return ColorUtils.linearSrgbLuminance(c);
	}

	private static float[] mix(float[] a, float[] b, float t)
	{
		return new float[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
	}

	private static String str(float[] c)
	{
		return String.format("[%.3f %.3f %.3f]", c[0], c[1], c[2]);
	}
}
