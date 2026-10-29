/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.sky;

/**
 * The sky's colours and the light they stand for, all driven by the visual sun altitude in degrees (below 0 is
 * night): the old renderer's sky.glsl palette and light functions on the CPU, evaluated once per frame.
 * <p>
 * The palette (zenith and horizon keyframes by altitude) is authored in sRGB, as before, and interpolated with a
 * {@link MonotoneCurve} instead of per-segment smoothsteps; the {@link SkyFrame} it fills is linear. The light
 * colours (sun, moon, ambient) are linear diffuse-light units as before, scaled by {@link #BRIGHTNESS} so that the
 * midday overworld comes out as bright as 117 HD's OVERWORLD environment (see BrightnessMatchTest), and multiplied by
 * the area's {@link AreaLook}. {@link SkyModifiers} is the weather's seam: the old overcast, haze, warmth, saturation
 * and sun strength functions, every one the identity when neutral. Not ported: the overcast sky-light boost (the
 * energy balance under cloud), lightning, the cloud light.
 */
public final class SkyModel
{
	// Stylized palette: sunrise and sunset go red-orange at the horizon with purple above it, nights are cool blue.
	static final float[] ZENITH_ALT = {-30, -12, -6, -2, 0, 6, 20, 90};
	static final float[][] ZENITH_COLOR = {
		{0.020f, 0.024f, 0.102f},
		{0.071f, 0.102f, 0.247f},
		{0.169f, 0.137f, 0.314f},
		{0.290f, 0.180f, 0.361f},
		{0.369f, 0.290f, 0.478f},
		{0.410f, 0.550f, 0.790f},
		{0.300f, 0.550f, 0.870f},
		{0.220f, 0.490f, 0.860f}};
	private static final MonotoneCurve ZENITH = new MonotoneCurve(ZENITH_ALT, ZENITH_COLOR);

	static final float[] HORIZON_ALT = {-30, -12, -6, -2, 0, 4, 12, 25, 90};
	static final float[][] HORIZON_COLOR = {
		{0.043f, 0.039f, 0.102f},
		{0.102f, 0.082f, 0.188f},
		{0.247f, 0.129f, 0.251f},
		{0.627f, 0.267f, 0.180f},
		{0.890f, 0.443f, 0.227f},
		{0.949f, 0.631f, 0.369f},
		{0.851f, 0.722f, 0.604f},
		{0.663f, 0.737f, 0.827f},
		{0.576f, 0.682f, 0.796f}};
	private static final MonotoneCurve HORIZON = new MonotoneCurve(HORIZON_ALT, HORIZON_COLOR);

	// Nights are this much brighter than the palette (sRGB, the sky itself), fading in as the sun sinks from 2 to 14
	// degrees under the horizon like the moon does, so dusk and dawn stay smooth. The lights get theirs in linear.
	private static final float NIGHT_SKY_BRIGHTNESS = 1.5f;
	private static final float NIGHT_LIGHT_BRIGHTNESS = 1.5f;

	// Light colours are LINEAR and are multiplied with the surface colour, so 1.0 means "full surface colour". The
	// sun is much stronger than the ambient on purpose: that contrast between sunlit and shaded faces is what makes
	// sunlight read as sunlight.
	private static final float SUN_INTENSITY = 2.9f;
	// Under a clear sky with the sun well up the ambient light drops to this much, a little further in harsh sun.
	private static final float CLEAR_AMBIENT = 0.80f;
	private static final float HARSH_AMBIENT = 0.08f;
	private static final float MOON_INTENSITY = 0.675f;
	private static final float SKY_LIGHT = 0.45f;
	/**
	 * The scale on every light so the midday overworld is as bright as 117 HD's: flat ground at Lumina's noon (caster
	 * at 68 degrees) equals flat ground under 117 HD's DAY preset (sun at 52 degrees) in its OVERWORLD environment
	 * (BrightnessMatchTest, which pins it).
	 */
	public static final float BRIGHTNESS = 1.264f;

	// haze: share of the sky turned milky at full haze, its colour, the sun and moon light it takes away, how much it
	// lifts the ambient light (scattered sunlight), and how much of the sun's disc it hides
	private static final float HAZE_SKY = 0.6f;
	private static final float[] HAZE_COLOR = {0.88f, 0.87f, 0.84f};
	private static final float HAZE_BRIGHTNESS = 1.12f;
	private static final float HAZE_DIRECT = 0.35f;
	private static final float HAZE_AMBIENT = 0.25f;
	private static final float HAZE_DISC = 0.6f;
	// warmth tints at +1 and -1, on the direct light fully and on the ambient and sky by WARMTH_FILL
	private static final float[] WARM_TINT = {1.12f, 0.96f, 0.76f};
	private static final float[] COOL_TINT = {0.88f, 0.96f, 1.12f};
	private static final float WARMTH_FILL = 0.5f;

	// the overcast sky's tint and brightness relative to the clear sky's luminance: dark slate in rain, soft white in
	// snow; the share of the clear sky's own colour left at full cover; how far zenith and horizon move toward one
	// brightness
	private static final float[] OVERCAST_RAIN_TINT = {0.86f, 0.93f, 1.05f};
	private static final float[] OVERCAST_SNOW_TINT = {0.98f, 0.99f, 1.02f};
	private static final float OVERCAST_RAIN_SKY = 0.62f;
	private static final float OVERCAST_SNOW_SKY = 1.18f;
	private static final float OVERCAST_SKY_KEEP = 0.06f;
	private static final float OVERCAST_SKY_FLATTEN = 0.7f;
	// sun or moon left at full cover, how much of its colour it keeps, and how soon the colour goes
	private static final float OVERCAST_DIRECT_RAIN = 0.30f;
	private static final float OVERCAST_DIRECT_SNOW = 0.42f;
	private static final float OVERCAST_DIRECT_SATURATION = 0.05f;
	private static final float OVERCAST_DIRECT_GRAY = 0.5f;
	// ambient sky light at full cover: grayer, a little lower in rain, brighter in snow
	private static final float OVERCAST_AMBIENT_RAIN = 0.95f;
	private static final float OVERCAST_AMBIENT_SNOW = 1.30f;
	private static final float OVERCAST_AMBIENT_GRAY = 0.85f;

	private static final float[] SUN_WARM = {1.00f, 0.42f, 0.16f};
	private static final float[] SUN_NOON = {1.00f, 0.90f, 0.76f};
	private static final float[] MOON_COLOR = {0.42f, 0.55f, 1.00f};
	private static final float[] AMBIENT_DAY = {0.30f, 0.38f, 0.58f};
	private static final float[] AMBIENT_NIGHT = {0.10f, 0.14f, 0.30f};
	private static final float[] AMBIENT_DUSK_TINT = {1.10f, 0.80f, 0.80f};
	private static final float[] GLOW_LOW = {1.0f, 0.55f, 0.30f};
	private static final float[] GLOW_HIGH = {1.0f, 0.85f, 0.65f};

	private final float[] zenithSrgb = new float[3];
	private final float[] horizonSrgb = new float[3];
	private final float[] tmp = new float[3];

	/**
	 * The sky at this sun altitude.
	 *
	 * @param altitude the visual sun's altitude, degrees
	 * @param look     the area's multipliers
	 * @param mod      the weather's modifiers, {@link SkyModifiers#NEUTRAL} for a clear sky
	 */
	public void evaluate(float altitude, AreaLook look, SkyModifiers mod, SkyFrame out)
	{
		float alt = altitude;
		float o = mod.overcast;

		// the sky (sRGB palette, then linear)
		ZENITH.evaluate(alt, zenithSrgb);
		HORIZON.evaluate(alt, horizonSrgb);
		float skyGain = nightGain(alt, NIGHT_SKY_BRIGHTNESS);
		scale(zenithSrgb, skyGain);
		scale(horizonSrgb, skyGain);
		float clearMean = 0.5f * (luma(zenithSrgb) + luma(horizonSrgb));
		overcastSky(zenithSrgb, clearMean, mod);
		overcastSky(horizonSrgb, clearMean, mod);
		srgbToLinear(zenithSrgb, out.zenith);
		srgbToLinear(horizonSrgb, out.horizon);

		// the sun's glow and disc, the moon and the stars
		float clear = overcastClear(mod);
		out.sunVisibility = smoothstep(-6, 2, alt) * clear;
		out.nightVisibility = smoothstep(-4, -14, alt) * clear;
		mix(tmp, GLOW_LOW, GLOW_HIGH, smoothstep(0, 25, alt));
		srgbToLinear(tmp, out.glow);
		scale(out.glow, out.sunVisibility);

		// the sun (linear): warm at the horizon, pale at noon; its strength window is the handoff dead band
		mix(out.sunColour, SUN_WARM, SUN_NOON, smoothstep(2, 28, alt));
		scale(out.sunColour, SUN_INTENSITY * Celestial.sunStrength(alt));
		overcastDirect(out.sunColour, o, mod);
		scale(out.sunColour, (1 + mod.sunStrength) * BRIGHTNESS);
		multiply(out.sunColour, look.sun);

		// the moon
		System.arraycopy(MOON_COLOR, 0, out.moonColour, 0, 3);
		scale(out.moonColour, MOON_INTENSITY * Celestial.moonStrength(alt));
		overcastDirect(out.moonColour, o, mod);
		scale(out.moonColour, BRIGHTNESS);
		multiply(out.moonColour, look.sun);

		// light from the whole sky, cool blue by day; fills shadows
		mix(out.ambient, AMBIENT_NIGHT, AMBIENT_DAY, smoothstep(-10, 15, alt));
		scale(out.ambient, nightGain(alt, NIGHT_LIGHT_BRIGHTNESS));
		// warmer, a little darker around sunrise and sunset
		float dusk = smoothstep(-8, 2, alt) * (1 - smoothstep(2, 20, alt));
		float clearSun = smoothstep(8, 35, alt) * (1 - o) * (1 - mod.haze);
		scale(out.ambient, mix(1, CLEAR_AMBIENT - HARSH_AMBIENT * clamp(mod.sunStrength, 0, 1), clearSun));
		mix(tmp, ONES, AMBIENT_DUSK_TINT, dusk);
		multiply(out.ambient, tmp);
		overcastAmbient(out.ambient, o, mod);
		scale(out.ambient, BRIGHTNESS);
		multiply(out.ambient, look.ambient);

		// the sky's light on surfaces: SkyLight integrates the sky's radiance (zenith, horizon, glow) times this. For
		// a dome of one radiance the up-facing irradiance is pi times it, so this gives the old skyLightAt scale.
		for (int i = 0; i < 3; i++)
		{
			out.skyFill[i] = SKY_LIGHT / (float) Math.PI * BRIGHTNESS * look.skyTint[i];
		}
	}

	private static final float[] ONES = {1, 1, 1};

	static float nightGain(float alt, float gain)
	{
		return mix(1, gain, smoothstep(-2, -14, alt));
	}

	// The weather's sky before cloud cover: more or less saturated, milky in haze, tinted by warmth
	private static void weatherSky(float[] c, SkyModifiers mod)
	{
		if (mod.isNeutral())
		{
			return;
		}
		float l = luma(c);
		for (int i = 0; i < 3; i++)
		{
			c[i] = Math.max(0, mix(l, c[i], 1 + mod.saturation));
			float haze = l * HAZE_BRIGHTNESS * HAZE_COLOR[i];
			c[i] = mix(c[i], haze, mod.haze * HAZE_SKY) * weatherTint(i, WARMTH_FILL, mod);
		}
	}

	private static float weatherTint(int channel, float amount, SkyModifiers mod)
	{
		float w = mod.warmth * amount;
		return w >= 0 ? mix(1, WARM_TINT[channel], w) : mix(1, COOL_TINT[channel], -w);
	}

	// the sky c at cover o; even: the clear sky's mean luminance
	private static void overcastSky(float[] c, float even, SkyModifiers mod)
	{
		weatherSky(c, mod);
		float o = mod.overcast;
		if (o <= 0)
		{
			return;
		}
		float l = mix(luma(c), even, OVERCAST_SKY_FLATTEN);
		float s = mod.snow;
		for (int i = 0; i < 3; i++)
		{
			float gray = l * mix(OVERCAST_RAIN_SKY, OVERCAST_SNOW_SKY, s) * mix(OVERCAST_RAIN_TINT[i], OVERCAST_SNOW_TINT[i], s);
			c[i] = mix(c[i], mix(gray, c[i], OVERCAST_SKY_KEEP), o);
		}
	}

	// the sun or moon colour c at cover o
	private static void overcastDirect(float[] c, float o, SkyModifiers mod)
	{
		if (mod.isNeutral())
		{
			return;
		}
		for (int i = 0; i < 3; i++)
		{
			c[i] *= (1 - HAZE_DIRECT * mod.haze) * weatherTint(i, 1, mod);
		}
		float s = mod.snow;
		float l = luma(c);
		float gray = smoothstep(0, OVERCAST_DIRECT_GRAY, o);
		float left = mix(1, mix(OVERCAST_DIRECT_RAIN, OVERCAST_DIRECT_SNOW, s), o);
		for (int i = 0; i < 3; i++)
		{
			float muted = mix(l * mix(OVERCAST_RAIN_TINT[i], OVERCAST_SNOW_TINT[i], s), c[i], OVERCAST_DIRECT_SATURATION);
			c[i] = mix(c[i], muted, gray) * left;
		}
	}

	private static void overcastAmbient(float[] c, float o, SkyModifiers mod)
	{
		if (mod.isNeutral())
		{
			return;
		}
		float s = mod.snow;
		float l = luma(c);
		for (int i = 0; i < 3; i++)
		{
			float gray = l * mix(OVERCAST_RAIN_TINT[i], OVERCAST_SNOW_TINT[i], s);
			float a = mix(c[i], gray, OVERCAST_AMBIENT_GRAY) * mix(OVERCAST_AMBIENT_RAIN, OVERCAST_AMBIENT_SNOW, s);
			c[i] = mix(c[i], a, o) * (1 + HAZE_AMBIENT * mod.haze) * weatherTint(i, WARMTH_FILL, mod);
		}
	}

	// how much of the sun and moon discs, their glow and the stars still show
	private static float overcastClear(SkyModifiers mod)
	{
		return (1 - smoothstep(0.15f, 0.85f, mod.overcast)) * (1 - HAZE_DISC * mod.haze);
	}

	static float luma(float[] c)
	{
		return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
	}

	static float smoothstep(float a, float b, float x)
	{
		float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	static float mix(float a, float b, float t)
	{
		return a + (b - a) * t;
	}

	static void mix(float[] out, float[] a, float[] b, float t)
	{
		for (int i = 0; i < 3; i++)
		{
			out[i] = a[i] + (b[i] - a[i]) * t;
		}
	}

	static float clamp(float v, float lo, float hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	static void scale(float[] c, float s)
	{
		c[0] *= s;
		c[1] *= s;
		c[2] *= s;
	}

	static void multiply(float[] c, float[] by)
	{
		c[0] *= by[0];
		c[1] *= by[1];
		c[2] *= by[2];
	}

	static float srgbToLinear(float c)
	{
		return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4);
	}

	static void srgbToLinear(float[] srgb, float[] out)
	{
		for (int i = 0; i < 3; i++)
		{
			out[i] = srgbToLinear(Math.max(0, srgb[i]));
		}
	}
}
