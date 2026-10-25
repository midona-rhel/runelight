package com.midonarhel.runelight;

/**
 * The light that comes in from outside (117 HD's outdoorLighting: cave exits, skylights, windows): it follows the
 * sky. From the middle of the day it is as authored; as the sun goes down it takes the sky's colors, warm at
 * the horizon and the moon's blue after, and weakens to a little of the moon's.
 *
 * 117 HD samples its sky for the color and the brightness. RuneLight's sky is drawn on the GPU, so this follows the
 * same curves from the sun's altitude, with the colors of the sun at the horizon and of the moon (sky.glsl).
 */
final class OutdoorLight
{
	// linear, like sky.glsl's
	private static final float[] HORIZON = {1.00f, 0.42f, 0.16f};
	private static final float[] MOON = {0.42f, 0.55f, 1.00f};
	// 117 HD's: the share of the light the moon keeps at the most
	private static final float MOON_STRENGTH = 0.12f;

	private OutdoorLight()
	{
	}

	/**
	 * @param sunAltitude degrees
	 * @param r the light's own color, linear (with g and b)
	 * @param out the color it has now, linear
	 * @return what its strength is multiplied by
	 */
	static float apply(float sunAltitude, float r, float g, float b, float[] out)
	{
		// 117 HD's blend into the authored light as the sun climbs
		float midday = smoothstep(15, 30, sunAltitude);
		float horizon = smoothstep(-8, 0, sunAltitude);
		for (int i = 0; i < 3; ++i)
		{
			float sky = MOON[i] + (HORIZON[i] - MOON[i]) * horizon;
			float authored = i == 0 ? r : i == 1 ? g : b;
			out[i] = sky + (authored - sky) * midday;
		}
		float moon = MOON_STRENGTH * smoothstep(-5, 20, -sunAltitude);
		float time = Math.max(smoothstep(-8, 15, sunAltitude), moon);
		return time + (1 - time) * midday;
	}

	private static float smoothstep(float a, float b, float x)
	{
		float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}
}
