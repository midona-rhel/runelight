package com.midonarhel.runelight;

/**
 * Sun and moon positions over a repeating day, following the game clock's time of day.
 *
 * Directions are unit vectors pointing from the world toward the light, in game coordinates:
 * x east, z north and y pointing DOWN, so "up" is -y.
 *
 * The visual sun follows a full arc that dips below the horizon so sunrise and sunset look right in the sky.
 * The shadow casting direction uses the same azimuth but a clamped elevation, keeping shadows short.
 * At night the moon, opposite the sun, casts instead.
 */
final class DayCycle
{
	// visual sun altitude = PEAK * sin(2 pi phase) + LIFT, in degrees. LIFT > 0 makes days a bit longer than nights.
	private static final double PEAK_ALTITUDE = 70;
	private static final double LIFT = 12;

	// shadow caster elevation limits, degrees. 30 keeps shadows at most ~1.7x an object's height.
	private static final double MIN_CAST_ELEVATION = 30;
	private static final double MAX_SUN_CAST_ELEVATION = 68;
	private static final double MAX_MOON_CAST_ELEVATION = 58;

	// below this sun altitude the moon takes over as the shadow caster; both lights are near zero there
	static final double CASTER_SWITCH_ALTITUDE = -3;

	// phase at which the visual sun crosses the horizon going up
	private static final double SUNRISE_PHASE = -Math.asin(LIFT / PEAK_ALTITUDE) / (2 * Math.PI);

	final float[] sunDir = new float[3];
	final float[] moonDir = new float[3];
	final float[] casterDir = new float[3];
	float sunAltitude;
	boolean sunIsCaster;
	// sky.glsl's strikeLight, from applyStrike
	final float[] strikeLight = new float[2];
	double phase;

	/**
	 * How bright the light that casts shadows is, linear luminance: sky.glsl's sunLightCover and moonLightCover, in
	 * round numbers, under this much overcast and snow. The bolt takes over once it is brighter (Lightning).
	 */
	float directStrength(float cover, float snow)
	{
		// sky.glsl's OVERCAST_DIRECT_RAIN and OVERCAST_DIRECT_SNOW
		float overcast = 1 + cover * (-1 + 0.30f + (0.42f - 0.30f) * snow);
		if (sunIsCaster)
		{
			// SUN_INTENSITY times the luminance of the sun's warm and noon colors
			float noon = smoothstep(2, 28, sunAltitude);
			return 2.9f * (0.52f + (0.92f - 0.52f) * noon) * smoothstep(-4, 10, sunAltitude) * overcast;
		}
		// MOON_INTENSITY times the luminance of the moon's color
		return 0.675f * 0.55f * smoothstep(-2, -14, sunAltitude) * overcast;
	}

	private static float smoothstep(float a, float b, float x)
	{
		float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	/**
	 * Let a lightning strike take over the light that casts shadows for this frame, after update().
	 */
	void applyStrike(Lightning lightning)
	{
		if (lightning.aim(casterDir))
		{
			sunIsCaster = true;
		}
		lightning.getStrike(strikeLight);
	}

	/**
	 * @param gameMillis the game clock (GameClock)
	 * @param cycleMinutes length of a full day in real minutes; 0 holds the time at fixedHour
	 * @param fixedHour 0-24, 6 is sunrise and 12 is roughly noon
	 */
	void update(long gameMillis, int cycleMinutes, double fixedHour)
	{
		phase = phaseForHour(cycleMinutes > 0 ? GameClock.hourOfDay(gameMillis) : fixedHour);
		phase -= Math.floor(phase);

		double alt = PEAK_ALTITUDE * Math.sin(2 * Math.PI * phase) + LIFT;
		sunAltitude = (float) alt;
		// south at the peak (phase 0.25), roughly east at sunrise and west at sunset
		double azimuth = Math.PI / 2 + 2 * Math.PI * (phase - 0.25);

		direction(sunDir, azimuth, Math.toRadians(alt));
		direction(moonDir, azimuth + Math.PI, Math.toRadians(-alt));

		sunIsCaster = alt > CASTER_SWITCH_ALTITUDE;
		if (sunIsCaster)
		{
			direction(casterDir, azimuth, Math.toRadians(clamp(alt, MIN_CAST_ELEVATION, MAX_SUN_CAST_ELEVATION)));
		}
		else
		{
			direction(casterDir, azimuth + Math.PI, Math.toRadians(clamp(-alt, MIN_CAST_ELEVATION, MAX_MOON_CAST_ELEVATION)));
		}
	}

	/**
	 * Clock time for display, 0-24.
	 */
	double hour()
	{
		double h = (phase - SUNRISE_PHASE) * 24 + 6;
		return h - Math.floor(h / 24) * 24;
	}

	private static double phaseForHour(double hour)
	{
		return (hour - 6) / 24 + SUNRISE_PHASE;
	}

	private static void direction(float[] out, double azimuth, double elevation)
	{
		double c = Math.cos(elevation);
		// azimuth 0 = east (+x), pi/2 = south (-z)
		out[0] = (float) (Math.cos(azimuth) * c);
		out[1] = (float) -Math.sin(elevation);
		out[2] = (float) (-Math.sin(azimuth) * c);
	}

	private static double clamp(double v, double lo, double hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}
}
