package com.midonarhel.runelight;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Automatic weather: every third of a game day (8 in-game hours) each biome gets a new weather, drawn from that
 * biome's odds. The idea of per-biome forecasts comes from 3D Weather; the odds and the chain here are our own.
 *
 * Everything is a pure function of the slot number and the biome, so it needs no saved state, is the same every
 * time it is asked and can show the next slot ahead of time. Consecutive slots form a Markov chain (each weather
 * leans toward itself and its neighbours: clear to partly cloudy, rain to storm), restarted from the biome's plain
 * odds every EPOCH slots so finding a slot's weather is never more than EPOCH steps.
 */
final class WeatherForecast
{
	static final int SLOTS_PER_DAY = 3;
	// slots per chain; at most this many transitions to find any slot's weather
	static final int EPOCH = 12;
	// the day cycle starts at 09:00 (DayCycle)
	private static final double START_HOUR = 9;
	// with the day held at a fixed hour, weather still turns over as if a day took this long
	private static final int FALLBACK_CYCLE_MINUTES = 12;
	// fog is this much likelier in the slot that ends in the morning (00:00 to 08:00): morning mists
	private static final float MORNING_FOG = 2.0f;

	private static final WeatherType[] TYPES = WeatherType.values();
	private static final int N = TYPES.length;

	// weight of going from a weather (row) to another (column), on top of the biome's odds. The diagonal is
	// persistence; neighbours are likelier than jumps, so clear skies cloud over before it storms. Storms and
	// blizzards persist less: they blow over.
	private static final float[][] AFFINITY = new float[N][N];

	static
	{
		for (int i = 0; i < N; ++i)
		{
			for (int j = 0; j < N; ++j)
			{
				AFFINITY[i][j] = i == j ? 3f : 1f;
			}
		}
		near(WeatherType.CLEAR, WeatherType.PARTLY_CLOUDY);
		near(WeatherType.PARTLY_CLOUDY, WeatherType.CLOUDY);
		near(WeatherType.CLOUDY, WeatherType.RAIN);
		near(WeatherType.RAIN, WeatherType.STORM);
		near(WeatherType.CLOUDY, WeatherType.SNOW);
		near(WeatherType.SNOW, WeatherType.BLIZZARD);
		near(WeatherType.FOG, WeatherType.CLEAR);
		near(WeatherType.FOG, WeatherType.PARTLY_CLOUDY);
		near(WeatherType.FOG, WeatherType.CLOUDY);
		far(WeatherType.CLEAR, WeatherType.STORM);
		far(WeatherType.CLEAR, WeatherType.BLIZZARD);
		far(WeatherType.FOG, WeatherType.STORM);
		far(WeatherType.FOG, WeatherType.BLIZZARD);
		AFFINITY[WeatherType.STORM.ordinal()][WeatherType.STORM.ordinal()] = 1.2f;
		AFFINITY[WeatherType.BLIZZARD.ordinal()][WeatherType.BLIZZARD.ordinal()] = 1.6f;
	}

	private static void near(WeatherType a, WeatherType b)
	{
		AFFINITY[a.ordinal()][b.ordinal()] = AFFINITY[b.ordinal()][a.ordinal()] = 1.8f;
	}

	private static void far(WeatherType a, WeatherType b)
	{
		AFFINITY[a.ordinal()][b.ordinal()] = AFFINITY[b.ordinal()][a.ordinal()] = 0.15f;
	}

	// odds per biome, in WeatherType order: clear, partly cloudy, cloudy, fog, rain, storm, snow, blizzard.
	// A zero stays zero whatever the chain does: deserts never see snow.
	private static final Map<String, float[]> ODDS = new HashMap<>();
	static final String DEFAULT_BIOME = "GRASSLAND";

	static
	{
		ODDS.put("GRASSLAND", new float[]{30, 22, 15, 8, 15, 5, 2, 0});
		ODDS.put("FOREST", new float[]{22, 20, 16, 12, 18, 6, 2, 0});
		ODDS.put("DESERT", new float[]{64, 22, 9, 0, 2, 5, 0, 0});
		ODDS.put("BARRENS", new float[]{40, 25, 15, 4, 8, 6, 0, 0});
		ODDS.put("SWAMP", new float[]{8, 10, 18, 32, 24, 6, 2, 0});
		ODDS.put("ARCTIC", new float[]{10, 12, 16, 10, 0, 0, 36, 16});
		ODDS.put("MOUNTAIN", new float[]{18, 16, 18, 12, 6, 4, 18, 8});
		ODDS.put("WILDERNESS", new float[]{8, 12, 28, 14, 18, 14, 4, 2});
		ODDS.put("TROPICAL", new float[]{24, 18, 12, 4, 26, 16, 0, 0});
	}

	/** The biome's odds, or the default biome's for unknown or missing ones. */
	static float[] odds(String biome)
	{
		float[] o = biome != null ? ODDS.get(biome) : null;
		return o != null ? o : ODDS.get(DEFAULT_BIOME);
	}

	/** Whether a biome has a sky at all (caves and the like have no weather). */
	static boolean hasWeather(String biome)
	{
		return biome == null || ODDS.containsKey(biome);
	}

	/**
	 * The weather of a biome in a slot. Slots count thirds of a game day; slot % 3 == 0 runs from midnight to
	 * 08:00.
	 */
	static WeatherType at(long slot, String biome)
	{
		float[] odds = odds(biome);
		long seed = biomeSeed(biome);
		long start = Math.floorDiv(slot, EPOCH) * EPOCH;
		// the chain starts from a plain draw for the slot before the epoch, so every slot, the first included,
		// is a transition from something
		WeatherType w = draw(weighted(odds, null, start - 1), uniform(start - 1, seed));
		for (long s = start; s <= slot; ++s)
		{
			w = draw(weighted(odds, w, s), uniform(s, seed));
		}
		return w;
	}

	/** Weights of the next weather after prev (null: plain odds), with the morning fog bonus for slot. */
	static float[] weighted(float[] odds, WeatherType prev, long slot)
	{
		float[] w = new float[N];
		boolean morning = Math.floorMod(slot, SLOTS_PER_DAY) == 0;
		for (int j = 0; j < N; ++j)
		{
			float v = odds[j];
			if (prev != null)
			{
				v *= AFFINITY[prev.ordinal()][j];
			}
			if (morning && TYPES[j] == WeatherType.FOG)
			{
				v *= MORNING_FOG;
			}
			w[j] = v;
		}
		return w;
	}

	static WeatherType draw(float[] weights, double u)
	{
		float total = 0;
		for (float v : weights)
		{
			total += v;
		}
		double x = u * total;
		for (int j = 0; j < N; ++j)
		{
			x -= weights[j];
			if (x < 0 && weights[j] > 0)
			{
				return TYPES[j];
			}
		}
		// rounding at the top end: the last weather with any weight
		for (int j = N - 1; j >= 0; --j)
		{
			if (weights[j] > 0)
			{
				return TYPES[j];
			}
		}
		return WeatherType.CLEAR;
	}

	private static long biomeSeed(String biome)
	{
		String b = biome != null && ODDS.containsKey(biome) ? biome : DEFAULT_BIOME;
		long h = 1125899906842597L;
		for (int i = 0; i < b.length(); ++i)
		{
			h = 31 * h + b.charAt(i);
		}
		return h;
	}

	/** 0 to 1, from the slot and biome only. */
	static double uniform(long slot, long seed)
	{
		// splitmix64
		long z = slot * 0x9E3779B97F4A7C15L + seed;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z ^= z >>> 31;
		return (z >>> 11) / (double) (1L << 53);
	}

	/**
	 * Game days since the day cycle started, counting the 09:00 start, so a slot boundary falls on 00:00, 08:00
	 * and 16:00 of the clock.
	 */
	static double gameDays(long elapsedMillis, int cycleMinutes)
	{
		int minutes = cycleMinutes > 0 ? cycleMinutes : FALLBACK_CYCLE_MINUTES;
		return elapsedMillis / (minutes * 60_000.0) + START_HOUR / 24;
	}

	// ---------------------------------------------------------------------------------------------------------
	// The forecast for this frame

	// added to every slot so each session has its own run of weather; a multiple of SLOTS_PER_DAY keeps the
	// morning slots in the morning
	private final long sessionBase;
	private String biome;
	private long slot = Long.MIN_VALUE;
	private WeatherType current = WeatherType.CLEAR;
	private WeatherType next = WeatherType.CLEAR;
	private float minutesToNext;

	WeatherForecast(long sessionSeed)
	{
		this.sessionBase = Math.floorMod(sessionSeed, 1L << 40) * SLOTS_PER_DAY;
	}

	/**
	 * @param biome the 3D Weather biome name, or null when unknown
	 * @param elapsedMillis the day cycle's clock
	 */
	void update(String biome, long elapsedMillis, int cycleMinutes)
	{
		double slots = gameDays(elapsedMillis, cycleMinutes) * SLOTS_PER_DAY;
		long s = sessionBase + (long) Math.floor(slots);
		int minutes = cycleMinutes > 0 ? cycleMinutes : FALLBACK_CYCLE_MINUTES;
		minutesToNext = (float) ((Math.floor(slots) + 1 - slots) * minutes / SLOTS_PER_DAY);
		if (s != slot || !Objects.equals(biome, this.biome))
		{
			slot = s;
			this.biome = biome;
			current = at(s, biome);
			next = at(s + 1, biome);
		}
	}

	// the current weather of every biome asked for this slot (WeatherField)
	private final Map<String, WeatherType> byBiome = new HashMap<>();
	private long byBiomeSlot = Long.MIN_VALUE;

	/** The current slot's weather for any biome (null: the default biome). {@link #update} first. */
	WeatherType currentFor(String biome)
	{
		if (byBiomeSlot != slot)
		{
			byBiome.clear();
			byBiomeSlot = slot;
		}
		return byBiome.computeIfAbsent(biome != null ? biome : "", k -> at(slot, biome));
	}

	WeatherType getCurrent()
	{
		return current;
	}

	WeatherType getNext()
	{
		return next;
	}

	float getMinutesToNext()
	{
		return minutesToNext;
	}

	String getBiome()
	{
		return biome != null ? biome : DEFAULT_BIOME;
	}
}
