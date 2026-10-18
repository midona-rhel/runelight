package com.midonarhel.runelight;

/**
 * How rain and snow change over time: slow swells and lulls over a minute or two, and now and then a short heavier
 * burst. Everything is smooth deterministic noise of the time in seconds, so it needs no state and never jumps. The
 * wind is Wind.java's, which uses the same noise.
 */
final class WeatherVariation
{
	// intensity multiplier at the deepest lull and the strongest swell
	static final float LULL = 0.55f;
	static final float SWELL = 1.3f;
	// extra multiplier at the peak of a burst
	static final float BURST = 0.45f;
	static final float MIN = LULL;
	static final float MAX = SWELL + BURST;

	// seconds per noise lattice step: two octaves of swells, then bursts
	private static final double SWELL_SLOW = 80;
	private static final double SWELL_FAST = 31;
	private static final double BURST_PERIOD = 9;

	private WeatherVariation()
	{
	}

	/**
	 * Intensity multiplier, between MIN and MAX.
	 */
	static float intensity(double t)
	{
		return swell(t) + BURST * burst(t);
	}

	/**
	 * The slow part of intensity without bursts, between LULL and SWELL.
	 */
	static float swell(double t)
	{
		float n = 0.6f * noise(t / SWELL_SLOW, 1) + 0.4f * noise(t / SWELL_FAST, 2);
		// a sum of value noise sits near 0.5; spread it so both lulls and swells actually happen
		return LULL + (SWELL - LULL) * smoothstep(0.25f, 0.75f, n);
	}

	/**
	 * 0 most of the time, rising to 1 for a heavier spell of several seconds.
	 */
	static float burst(double t)
	{
		return smoothstep(0.72f, 0.92f, noise(t / BURST_PERIOD, 3));
	}

	/**
	 * 1D value noise in 0..1 with a quintic fade, so it is smooth in value and slope.
	 */
	static float noise(double x, int seed)
	{
		double fl = Math.floor(x);
		long i = (long) fl;
		float f = (float) (x - fl);
		float u = f * f * f * (f * (f * 6 - 15) + 10);
		float a = hash(i, seed);
		float b = hash(i + 1, seed);
		return a + (b - a) * u;
	}

	private static float hash(long i, int seed)
	{
		// splitmix64 finalizer
		long z = i * 0x9E3779B97F4A7C15L + seed * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return (z >>> 40) / (float) (1 << 24);
	}

	private static float smoothstep(float e0, float e1, float x)
	{
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
