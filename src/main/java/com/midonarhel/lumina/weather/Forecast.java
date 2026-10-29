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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THE
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.sky.GameClock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The weather of a climate in a slot: a pure function of the slot number and the climate, so it needs no saved
 * state, is the same on every client whenever it started, and can be asked ahead of time. Slots are thirds of a
 * game day ({@link #SLOT_HOURS} game hours) counted from the game clock's epoch; slot % 3 == 0 runs from midnight to
 * 08:00 (the old renderer's WeatherForecast, without its seasons).
 * <p>
 * Consecutive slots form a Markov chain: the next slot's weights are the climate's odds times the affinity of the
 * previous pattern for each candidate (a pattern leans toward itself by its persistence and toward patterns that
 * look like it, so clear skies cloud over before it rains). The chain restarts every {@link #EPOCH} slots from a
 * plain draw, so finding a slot's weather is never more than EPOCH steps; at a restart the first slot is not
 * conditioned on the real previous slot. Fog is twice as likely in the morning slot and half as likely otherwise.
 * The climate's shares are shares of time, and the affinity would bend them (a sticky clear sky starves the rare
 * patterns), so the odds the chain draws from are calibrated once per climate ({@link #calibrate}) until the
 * chain's long-run shares match the authored ones.
 * <p>
 * Hard rules: a kind with odds 0 never occurs, whatever the affinity (the snow line is applied in
 * {@link Climate#odds()}, specials only come from the climate's own shares); a storm never follows a storm
 * (persistence 0) and lasts {@code hours} of its slot, the rest being rain (or overcast where the climate has no
 * rain).
 */
public final class Forecast
{
	public static final int SLOTS_PER_DAY = 3;
	public static final int SLOT_HOURS = 24 / SLOTS_PER_DAY;
	public static final long SLOT_MILLIS = GameClock.DAY / SLOTS_PER_DAY;
	/** Slots per chain; at most this many transitions to find any slot's weather. */
	public static final int EPOCH = 12;

	// transition weights between unlike patterns: alike ones up to NEAR, unrelated ones down to FAR, by the
	// distance between their looks (see features); SIMILARITY is the squared distance at which they are ~1
	private static final float NEAR = 1.8f;
	private static final float FAR = 0.15f;
	private static final float SIMILARITY = 1.0f;

	private static final float MORNING_FOG = 2.0f;
	private static final float DAY_FOG = 0.5f;

	// splitmix streams per slot: the chain draw, the intensity, the wind, the storm length
	private static final long STREAM_INTENSITY = 0x51ED270B27C4A8E1L;
	private static final long STREAM_WIND = 0x7A3D9C1F5B6E8D2BL;
	private static final long STREAM_HOURS = 0x2C6F8A4E9D1B3F7DL;

	/** The weather of one slot. */
	public static final class Slot
	{
		public final long slot;
		public final Pattern pattern;
		/** Precipitation intensity 0 to 1; 0 where nothing falls. */
		public final float intensity;
		/** Wind 0 still to 1 gale. */
		public final float wind;
		/** Game hours from the slot's start that {@link #pattern} lasts: {@link #SLOT_HOURS}, or 4 to 8 for a storm. */
		public final int hours;
		/** What fills the slot after {@link #hours}; {@link #pattern} itself when it lasts the whole slot. */
		public final Pattern after;

		Slot(long slot, Pattern pattern, float intensity, float wind, int hours, Pattern after)
		{
			this.slot = slot;
			this.pattern = pattern;
			this.intensity = intensity;
			this.wind = wind;
			this.hours = hours;
			this.after = after;
		}

		/** The pattern at this many game hours into the slot. */
		public Pattern patternAt(double hourIntoSlot)
		{
			return hourIntoSlot < hours ? pattern : after;
		}

		@Override
		public String toString()
		{
			StringBuilder sb = new StringBuilder(pattern.kind.name());
			if (intensity > 0)
			{
				sb.append(String.format(" %.2f", intensity));
			}
			sb.append(String.format(" wind %.2f", wind));
			if (after != pattern)
			{
				sb.append(" for ").append(hours).append(" h, then ").append(after.kind);
			}
			return sb.toString();
		}
	}

	// calibration: damped fixed-point iterations and the days of chain the long-run shares are measured over
	private static final int CALIBRATE_ROUNDS = 40;
	private static final double CALIBRATE_DAMPING = 0.7;
	private static final int STATIONARY_DAYS = 120;

	private final Pattern[] patterns;
	private final float[][] affinity;
	// the calibrated odds per climate id
	private final Map<String, float[]> odds = new ConcurrentHashMap<>();

	public Forecast(Pattern.Table table)
	{
		patterns = table.all();
		affinity = affinity(patterns);
	}

	/** The odds the chain draws from for a climate: its {@link Climate#odds()} calibrated. */
	float[] odds(Climate climate)
	{
		return odds.computeIfAbsent(climate.id, id -> calibrate(climate.odds()));
	}

	/**
	 * Odds that make the chain's long-run share of each kind the target shares: start from the targets and scale
	 * each kind by (target / long-run share)^damping until they agree. Zeros stay zero.
	 */
	float[] calibrate(float[] target)
	{
		float total = 0;
		for (float t : target)
		{
			total += t;
		}
		float[] w = target.clone();
		for (int round = 0; round < CALIBRATE_ROUNDS; round++)
		{
			double[] share = stationary(w);
			float sum = 0;
			for (int j = 0; j < w.length; j++)
			{
				if (target[j] > 0)
				{
					w[j] *= (float) Math.pow(target[j] / total / Math.max(share[j], 1e-9), CALIBRATE_DAMPING);
				}
				sum += w[j];
			}
			for (int j = 0; j < w.length; j++)
			{
				w[j] *= total / sum;
			}
		}
		return w;
	}

	/** The long-run share of each pattern under the chain with these odds, over the day's three slots. */
	private double[] stationary(float[] w)
	{
		int n = patterns.length;
		double[] share = new double[n];
		float[] plain = weighted(w, null, 1);
		double sum = 0;
		for (float v : plain)
		{
			sum += v;
		}
		for (int j = 0; j < n; j++)
		{
			share[j] = plain[j] / sum;
		}
		double[] next = new double[n];
		// the share over a whole day is the mean over its three slots (the morning one has more fog), taken over
		// the last day once the chain has settled
		double[] mean = new double[n];
		for (int day = 0; day <= STATIONARY_DAYS; day++)
		{
			for (int phase = 0; phase < SLOTS_PER_DAY; phase++)
			{
				java.util.Arrays.fill(next, 0);
				for (int i = 0; i < n; i++)
				{
					if (share[i] <= 0)
					{
						continue;
					}
					float[] t = weighted(w, patterns[i], phase);
					double rowSum = 0;
					for (float v : t)
					{
						rowSum += v;
					}
					for (int j = 0; j < n; j++)
					{
						next[j] += share[i] * t[j] / rowSum;
					}
				}
				double[] swap = share;
				share = next;
				next = swap;
				if (day == STATIONARY_DAYS)
				{
					for (int j = 0; j < n; j++)
					{
						mean[j] += share[j] / SLOTS_PER_DAY;
					}
				}
			}
		}
		return mean;
	}

	/** The slot a moment of the game clock falls in. */
	public static long slot(long gameMillis)
	{
		return Math.floorDiv(gameMillis, SLOT_MILLIS);
	}

	/** Game hours into its slot, 0 to 8. */
	public static double hourIntoSlot(long gameMillis)
	{
		return Math.floorMod(gameMillis, SLOT_MILLIS) / (double) GameClock.HOUR;
	}

	/** Clock hour of a slot's start, 0, 8 or 16. */
	public static int startHour(long slot)
	{
		return (int) Math.floorMod(slot, SLOTS_PER_DAY) * SLOT_HOURS;
	}

	/** The weather of a climate in a slot. */
	public Slot at(long slot, Climate climate)
	{
		float[] odds = odds(climate);
		long seed = seed(climate.id);
		long start = Math.floorDiv(slot, EPOCH) * EPOCH;
		// the chain starts from a plain draw for the slot before the epoch, so every slot, the first included, is a
		// transition from something
		Pattern p = draw(weighted(odds, null, start - 1), uniform(start - 1, seed));
		for (long s = start; s <= slot; ++s)
		{
			p = draw(weighted(odds, p, s), uniform(s, seed));
		}
		return slotOf(slot, p, climate, odds, seed);
	}

	private Slot slotOf(long slot, Pattern p, Climate climate, float[] odds, long seed)
	{
		float intensity = 0;
		if (p.precipitation.falls())
		{
			float[] r = p.precipitation.intensity;
			intensity = r[0] + (r[1] - r[0]) * (float) uniform(slot, seed ^ STREAM_INTENSITY) + climate.intensityBias;
			intensity = Math.max(0, Math.min(1, intensity));
		}
		float wind = p.wind[0] + (p.wind[1] - p.wind[0]) * (float) uniform(slot, seed ^ STREAM_WIND);
		int hours = SLOT_HOURS;
		Pattern after = p;
		if (p.hours != null)
		{
			hours = p.hours[0] + (int) (uniform(slot, seed ^ STREAM_HOURS) * (p.hours[1] - p.hours[0] + 1));
			hours = Math.min(hours, SLOT_HOURS);
			if (hours < SLOT_HOURS)
			{
				after = tail(odds, p);
				if (after == p)
				{
					hours = SLOT_HOURS;
				}
			}
		}
		return new Slot(slot, p, intensity, wind, hours, after);
	}

	/** What a short pattern leaves behind: rain, else overcast, else itself, whichever the climate has. */
	private Pattern tail(float[] odds, Pattern p)
	{
		for (Pattern.Kind k : new Pattern.Kind[]{Pattern.Kind.RAIN, Pattern.Kind.OVERCAST})
		{
			if (odds[k.ordinal()] > 0)
			{
				return patterns[k.ordinal()];
			}
		}
		return p;
	}

	/**
	 * Weights of the next pattern after prev (null: the plain odds) in a slot: the odds times the affinity, the fog
	 * bonus by the time of day. Falls back to the plain odds if the affinity left nothing (a single-pattern
	 * climate whose pattern never follows itself).
	 */
	float[] weighted(float[] odds, Pattern prev, long slot)
	{
		float[] w = new float[patterns.length];
		boolean morning = Math.floorMod(slot, SLOTS_PER_DAY) == 0;
		float total = 0;
		for (int j = 0; j < w.length; ++j)
		{
			float v = odds[j];
			if (v <= 0)
			{
				continue;
			}
			if (prev != null)
			{
				v *= affinity[prev.kind.ordinal()][j];
			}
			if (patterns[j].kind == Pattern.Kind.FOG)
			{
				v *= morning ? MORNING_FOG : DAY_FOG;
			}
			w[j] = v;
			total += v;
		}
		return total > 0 || prev == null ? w : weighted(odds, null, slot);
	}

	Pattern draw(float[] weights, double u)
	{
		float total = 0;
		for (float v : weights)
		{
			total += v;
		}
		double x = u * total;
		for (int j = 0; j < weights.length; ++j)
		{
			if (weights[j] <= 0)
			{
				continue;
			}
			x -= weights[j];
			if (x < 0)
			{
				return patterns[j];
			}
		}
		// rounding at the top end: the last pattern with any weight
		for (int j = weights.length - 1; j >= 0; --j)
		{
			if (weights[j] > 0)
			{
				return patterns[j];
			}
		}
		throw new IllegalStateException("a climate without weights");
	}

	/**
	 * Weight of going from a pattern (row) to another (column), on top of the climate's odds. The diagonal is each
	 * pattern's persistence; alike patterns are likelier than jumps.
	 */
	private static float[][] affinity(Pattern[] patterns)
	{
		int n = patterns.length;
		float[][] f = new float[n][];
		for (int i = 0; i < n; ++i)
		{
			f[i] = features(patterns[i]);
		}
		float[][] a = new float[n][n];
		for (int i = 0; i < n; ++i)
		{
			for (int j = 0; j < n; ++j)
			{
				if (i == j)
				{
					a[i][j] = patterns[i].persistence;
					continue;
				}
				float d2 = 0;
				for (int k = 0; k < f[i].length; ++k)
				{
					float d = f[i][k] - f[j][k];
					d2 += d * d;
				}
				a[i][j] = FAR + (NEAR - FAR) * (float) Math.exp(-d2 / SIMILARITY);
			}
		}
		return a;
	}

	/** What a pattern looks like, for how alike two are: cover, haze, rain, snow, dust or ash, storm, wind. */
	private static float[] features(Pattern p)
	{
		Pattern.Precipitation.Kind falls = p.precipitation.kind;
		float amount = 0.5f * (p.precipitation.intensity[0] + p.precipitation.intensity[1]);
		return new float[]{
			p.sky.overcast,
			p.sky.haze,
			falls == Pattern.Precipitation.Kind.RAIN ? amount : 0,
			falls == Pattern.Precipitation.Kind.SNOW ? amount : 0,
			// dust and ash are nothing like snow
			falls == Pattern.Precipitation.Kind.SAND || falls == Pattern.Precipitation.Kind.ASH ? 1.5f : 0,
			p.kind == Pattern.Kind.STORM ? 1 : 0,
			0.7f * 0.5f * (p.wind[0] + p.wind[1]),
		};
	}

	private static long seed(String climateId)
	{
		long h = 1125899906842597L;
		for (int i = 0; i < climateId.length(); ++i)
		{
			h = 31 * h + climateId.charAt(i);
		}
		return h;
	}

	/** 0 to 1, from the slot and seed only. */
	static double uniform(long slot, long seed)
	{
		// splitmix64
		long z = slot * 0x9E3779B97F4A7C15L + seed;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return (z >>> 11) / (double) (1L << 53);
	}
}
