package com.midonarhel.runelight;

import com.midonarhel.runelight.WeatherClimate.Season;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Automatic weather: every third of a game day (8 in-game hours) each biome gets a new weather, drawn from that
 * biome's odds for the season (WeatherClimate). The idea of per-biome forecasts comes from 3D Weather; the odds, the
 * seasons and the chain here are our own.
 *
 * Everything is a pure function of the slot number and the biome, so it needs no saved state, is the same every
 * time it is asked and can show the next slot ahead of time. Slots count from the game clock's epoch (GameClock), so
 * every client sees the same weather at the same time, whenever it started. Consecutive slots form a Markov chain (each weather
 * leans toward itself and toward weathers like it, so clear skies cloud over before it storms), restarted from the
 * biome's plain odds every EPOCH slots so finding a slot's weather is never more than EPOCH steps.
 *
 * Seasons follow each other every few game days (a setting); toward the end of a season its odds blend into the
 * next one's, so the weather shifts gradually.
 */
final class WeatherForecast
{
	static final int SLOTS_PER_DAY = 3;
	// slots per chain; at most this many transitions to find any slot's weather
	static final int EPOCH = 12;
	static final int DEFAULT_SEASON_DAYS = 7;
	// share of a season, at its end, over which its odds blend into the next season's
	static final float SEASON_BLEND = 0.25f;
	static final long SLOT_MILLIS = GameClock.DAY / SLOTS_PER_DAY;

	// transition weights between unlike weathers: alike ones up to NEAR, unrelated ones down to FAR, by the
	// distance between their looks (see features); SIMILARITY is the squared distance at which they are ~1
	private static final float NEAR = 1.8f;
	private static final float FAR = 0.15f;
	private static final float SIMILARITY = 1.0f;

	private static float[][] affinity = new float[0][0];
	private static int affinityVersion = -1;

	/**
	 * Weight of going from a weather (row) to another (column), on top of the biome's odds. The diagonal is each
	 * type's persistence; alike weathers are likelier than jumps.
	 */
	static synchronized float[][] affinity()
	{
		if (affinityVersion != WeatherType.version() || affinity.length != WeatherType.count())
		{
			WeatherType[] types = WeatherType.all();
			int n = types.length;
			float[][] f = new float[n][];
			for (int i = 0; i < n; ++i)
			{
				f[i] = features(types[i]);
			}
			float[][] a = new float[n][n];
			for (int i = 0; i < n; ++i)
			{
				for (int j = 0; j < n; ++j)
				{
					if (i == j)
					{
						a[i][j] = types[i].persistence;
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
			affinity = a;
			affinityVersion = WeatherType.version();
		}
		return affinity;
	}

	/** What a weather looks like, for how alike two are: clouds, rain, snow, dust or ash, storm, fog, wind, haze. */
	private static float[] features(WeatherType t)
	{
		float snow = Math.min(t.snow, 1.2f);
		return new float[]{
			t.cover,
			Math.min(t.rain, 1.2f),
			t.whitens() ? snow : 0,
			// dust and ash are nothing like snow
			t.whitens() ? 0 : 2 * snow,
			t.lightning,
			Math.min(t.fog / 2.2f, 1),
			0.7f * Math.min(t.wind, 1.2f),
			0.7f * t.haze,
		};
	}

	/** Season of a slot; seasons last {@code seasonDays} game days. */
	static Season season(long slot, int seasonDays)
	{
		long length = (long) SLOTS_PER_DAY * Math.max(seasonDays, 1);
		return WeatherClimate.SEASONS[(int) Math.floorMod(Math.floorDiv(slot, length), WeatherClimate.SEASONS.length)];
	}

	/** How far into its season a slot is, 0 at its start to 1 at its end. */
	static float seasonProgress(long slot, int seasonDays)
	{
		long length = (long) SLOTS_PER_DAY * Math.max(seasonDays, 1);
		return (Math.floorMod(slot, length) + 0.5f) / length;
	}

	/** The biome's odds in a slot: its season's, blending into the next season's toward the end. */
	static float[] odds(String biome, long slot, int seasonDays)
	{
		Season s = season(slot, seasonDays);
		float[] a = WeatherClimate.odds(biome, s);
		float t = smoothstep(1 - SEASON_BLEND, 1, seasonProgress(slot, seasonDays));
		if (t <= 0)
		{
			return a;
		}
		float[] b = WeatherClimate.odds(biome, WeatherClimate.SEASONS[(s.ordinal() + 1) % WeatherClimate.SEASONS.length]);
		float[] o = new float[a.length];
		// as shares, so a season with bigger weights does not dominate the blend
		float sa = sum(a), sb = sum(b);
		for (int i = 0; i < o.length; ++i)
		{
			o[i] = a[i] / sa * (1 - t) + b[i] / sb * t;
		}
		return o;
	}

	/** Whether a biome has a sky at all (caves and the like have no weather). */
	static boolean hasWeather(String biome)
	{
		return biome == null || WeatherClimate.knows(biome);
	}

	static WeatherType at(long slot, String biome)
	{
		return at(slot, biome, DEFAULT_SEASON_DAYS);
	}

	/**
	 * The weather of a biome in a slot. Slots count thirds of a game day; slot % 3 == 0 runs from midnight to
	 * 08:00.
	 */
	static WeatherType at(long slot, String biome, int seasonDays)
	{
		long seed = biomeSeed(biome);
		long start = Math.floorDiv(slot, EPOCH) * EPOCH;
		// the chain starts from a plain draw for the slot before the epoch, so every slot, the first included,
		// is a transition from something
		WeatherType w = draw(weighted(odds(biome, start - 1, seasonDays), null, start - 1), uniform(start - 1, seed));
		for (long s = start; s <= slot; ++s)
		{
			w = draw(weighted(odds(biome, s, seasonDays), w, s), uniform(s, seed));
		}
		return w;
	}

	/** Weights of the next weather after prev (null: plain odds), with the morning bonus for slot. */
	static float[] weighted(float[] odds, WeatherType prev, long slot)
	{
		WeatherType[] types = WeatherType.all();
		float[][] aff = affinity();
		int n = Math.min(types.length, odds.length);
		float[] w = new float[n];
		boolean morning = Math.floorMod(slot, SLOTS_PER_DAY) == 0;
		for (int j = 0; j < n; ++j)
		{
			float v = odds[j];
			if (prev != null && prev.index < aff.length)
			{
				v *= aff[prev.index][j];
			}
			if (morning)
			{
				v *= WeatherClimate.morning(types[j]);
			}
			w[j] = v;
		}
		return w;
	}

	static WeatherType draw(float[] weights, double u)
	{
		WeatherType[] types = WeatherType.all();
		double x = u * sum(weights);
		for (int j = 0; j < weights.length; ++j)
		{
			x -= weights[j];
			if (x < 0 && weights[j] > 0)
			{
				return types[j];
			}
		}
		// rounding at the top end: the last weather with any weight
		for (int j = weights.length - 1; j >= 0; --j)
		{
			if (weights[j] > 0)
			{
				return types[j];
			}
		}
		return WeatherType.clear();
	}

	private static float sum(float[] v)
	{
		float total = 0;
		for (float x : v)
		{
			total += x;
		}
		return total;
	}

	private static float smoothstep(float e0, float e1, float x)
	{
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	private static long biomeSeed(String biome)
	{
		String b = WeatherClimate.knows(biome) ? biome : WeatherClimate.defaultBiome();
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

	/** The slot a moment of the game clock falls in; slot % 3 == 0 runs from midnight to 08:00. */
	static long slot(long gameMillis)
	{
		return Math.floorDiv(gameMillis, SLOT_MILLIS);
	}

	// ---------------------------------------------------------------------------------------------------------
	// The forecast for this frame

	private String biome;
	private long slot = Long.MIN_VALUE;
	private int seasonDays = DEFAULT_SEASON_DAYS;
	private int catalogue = -1;
	private WeatherType current = WeatherType.clear();
	private WeatherType next = WeatherType.clear();
	private float minutesToNext;

	/**
	 * @param biome      the 3D Weather biome name, or null when unknown
	 * @param gameMillis the game clock (GameClock)
	 * @param dayMinutes real minutes per game day, for the time to the next slot
	 * @param seasonDays game days per season
	 */
	void update(String biome, long gameMillis, int dayMinutes, int seasonDays)
	{
		long s = slot(gameMillis);
		minutesToNext = (float) (((s + 1) * SLOT_MILLIS - gameMillis) * GameClock.realPerGame(dayMinutes) / 60_000.0);
		seasonDays = Math.max(seasonDays, 1);
		if (s != slot || !Objects.equals(biome, this.biome) || seasonDays != this.seasonDays
			|| catalogue != WeatherType.version())
		{
			slot = s;
			this.biome = biome;
			this.seasonDays = seasonDays;
			catalogue = WeatherType.version();
			current = at(s, biome, seasonDays);
			next = at(s + 1, biome, seasonDays);
			byBiome.clear();
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
		return byBiome.computeIfAbsent(biome != null ? biome : "", k -> at(slot, biome, seasonDays));
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

	long getSlot()
	{
		return slot;
	}

	Season getSeason()
	{
		return season(slot, seasonDays);
	}

	/** Game day of the season, from 1. */
	int getSeasonDay()
	{
		return (int) (Math.floorMod(slot, (long) SLOTS_PER_DAY * seasonDays) / SLOTS_PER_DAY) + 1;
	}

	int getSeasonDays()
	{
		return seasonDays;
	}

	String getBiome()
	{
		return biome != null ? biome : WeatherClimate.defaultBiome();
	}
}
