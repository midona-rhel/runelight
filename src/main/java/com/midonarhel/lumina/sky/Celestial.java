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
 * Sun and moon positions over the game day, following {@link GameClock}'s hour (the old renderer's DayCycle with a
 * fixed day schedule).
 * <p>
 * Directions are unit vectors pointing from the world toward the light, in game coordinates: x east, z north and y
 * pointing DOWN, so "up" is -y.
 * <p>
 * The schedule, in game hours h: the sun is up from 04:00 to 20:00 and down from 20:00 to 04:00. Altitude by day
 * (4 &le; h &lt; 20) is {@code 70 sin(pi (h - 4) / 16)}, by night (20 &le; h &lt; 28, wrapping) {@code -35 sin(pi (h - 20) / 8)}:
 * both are 0 at 04:00 and 20:00 with the same slope there, the peak is 70 degrees at 12:00 and the low -35 degrees at
 * 00:00. Dusk is the hour before 20:00 (about 13.7 degrees at 19:00), dawn the hour before 04:00 (about -13.4 degrees at
 * 03:00). The azimuth is linear in the time of day: east at 06:00, south at 12:00, west at 18:00. The moon is opposite.
 * <p>
 * The light that casts shadows (the caster) uses the same azimuth but a clamped elevation, keeping shadows short; at
 * night the moon casts instead. The handoff has a dead band: the sun's light reaches exactly 0 at -2 degrees
 * ({@link #sunStrength}), the moon's is 0 above -4 degrees ({@link #moonStrength}), and the caster flips at
 * {@link #CASTER_SWITCH_ALTITUDE}, where both are zero, so the flip itself is invisible. {@link #casterEpoch} counts
 * the flips for the shadow module.
 */
public final class Celestial
{
	public enum Caster
	{
		SUN,
		MOON
	}

	private static final double PEAK_ALTITUDE = 70;
	private static final double NIGHT_DEPTH = 35;
	private static final double SUNRISE_HOUR = 4;
	private static final double SUNSET_HOUR = 20;

	// shadow caster elevation limits, degrees. 30 keeps shadows at most ~1.7x an object's height.
	public static final double MIN_CAST_ELEVATION = 30;
	public static final double MAX_SUN_CAST_ELEVATION = 68;
	public static final double MAX_MOON_CAST_ELEVATION = 58;

	// below this sun altitude the moon takes over as the shadow caster; both lights are zero there
	public static final double CASTER_SWITCH_ALTITUDE = -3;

	public final float[] sunDir = new float[3];
	public final float[] moonDir = new float[3];
	public final float[] casterDir = new float[3];
	/** The visual sun's altitude, degrees; below 0 is night. */
	public float sunAltitude;
	/** The sun's azimuth, radians: 0 east (+x), pi/2 south (-z). */
	public double sunAzimuth;
	public Caster casterKind = Caster.SUN;
	/** How strong the caster's light is, 0 to 1: {@link #sunStrength} or {@link #moonStrength} of the sun altitude. */
	public float casterStrength;
	/** Increments whenever {@link #casterKind} changes. */
	public int casterEpoch;

	private boolean started;

	/** The sun's altitude at this hour of the game day, degrees. */
	public static double altitudeAt(double hour)
	{
		double h = hour % 24;
		if (h < 0)
		{
			h += 24;
		}
		if (h >= SUNRISE_HOUR && h < SUNSET_HOUR)
		{
			return PEAK_ALTITUDE * Math.sin(Math.PI * (h - SUNRISE_HOUR) / (SUNSET_HOUR - SUNRISE_HOUR));
		}
		if (h < SUNRISE_HOUR)
		{
			h += 24;
		}
		return -NIGHT_DEPTH * Math.sin(Math.PI * (h - SUNSET_HOUR) / (24 - SUNSET_HOUR + SUNRISE_HOUR));
	}

	/** The sun's azimuth at this hour, radians: east at 06:00, south at 12:00, west at 18:00. */
	public static double azimuthAt(double hour)
	{
		return (hour - 6) / 24 * 2 * Math.PI;
	}

	/** The sun's light strength, 0 to 1, by its altitude: exactly 0 from -2 degrees down. */
	public static float sunStrength(float sunAltitude)
	{
		return smoothstep(-2, 10, sunAltitude);
	}

	/** The moon's light strength, 0 to 1, by the sun's altitude: exactly 0 from -4 degrees up. */
	public static float moonStrength(float sunAltitude)
	{
		return smoothstep(-4, -14, sunAltitude);
	}

	/**
	 * @param hour the game clock's hour of day, 0 to 24
	 */
	public void update(double hour)
	{
		double alt = altitudeAt(hour);
		sunAltitude = (float) alt;
		sunAzimuth = azimuthAt(hour);

		direction(sunDir, sunAzimuth, Math.toRadians(alt));
		direction(moonDir, sunAzimuth + Math.PI, Math.toRadians(-alt));

		Caster kind = alt > CASTER_SWITCH_ALTITUDE ? Caster.SUN : Caster.MOON;
		if (started && kind != casterKind)
		{
			casterEpoch++;
		}
		started = true;
		casterKind = kind;
		if (kind == Caster.SUN)
		{
			direction(casterDir, sunAzimuth, Math.toRadians(clamp(alt, MIN_CAST_ELEVATION, MAX_SUN_CAST_ELEVATION)));
			casterStrength = sunStrength(sunAltitude);
		}
		else
		{
			direction(casterDir, sunAzimuth + Math.PI, Math.toRadians(clamp(-alt, MIN_CAST_ELEVATION, MAX_MOON_CAST_ELEVATION)));
			casterStrength = moonStrength(sunAltitude);
		}
	}

	/** The caster's elevation above the horizon, degrees (within the clamp range). */
	public double casterElevation()
	{
		return Math.toDegrees(Math.asin(-casterDir[1]));
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

	static float smoothstep(float a, float b, float x)
	{
		float t = Math.max(0, Math.min(1, (x - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}
}
