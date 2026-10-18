package com.midonarhel.runelight;

import com.midonarhel.runelight.WeatherClimate.Season;
import com.midonarhel.runelight.WeatherClimate.Temperature;
import java.util.HashMap;
import java.util.Map;

/**
 * Temperature, snow on the ground and wet ground per region (biome), as pure functions of the game clock.
 *
 * The temperature is the biome's mean for the time of year (seasons blend into each other around their middles),
 * plus the swing between the cold early morning and the warm afternoon, which clouds damp, plus what the weather
 * does (whatever falls cools the air; harsh sun, blizzards and the like add their own), plus a slow wander over a
 * couple of days. Frozen biomes never thaw.
 *
 * Rain and snow follow the temperature: the forecast picks a weather, and its rain and snow are split between the
 * two by how cold it is, so a winter rain falls as snow in a frost and a snowfall as sleet or rain in a thaw.
 *
 * Snow depth and wetness depend on what happened before, so each region runs a small simulation in steps of one
 * game hour: snow piles up while it snows, settles slowly, and melts only above zero (faster the warmer, faster
 * still in rain or sun); wet ground dries with warmth and sun. The simulation starts a couple of seasons' worth of
 * days back from the clean ground (frozen biomes from their permanent snow), long enough that what it started from
 * no longer shows, so every client arrives at the same ground for the same moment however it got there.
 */
final class Climate
{
	// degrees per unit of rain or snow falling (forecast amounts, which are about 0.25 to 1.3)
	static final float PRECIP_COOL = 3;
	// share of the day's temperature swing full cloud cover takes away
	static final float CLOUD_DAMP = 0.65f;
	// warmest hour of the day; the coldest is twelve hours off
	static final double PEAK_HOUR = 15;
	// game hours per step of the slow wander
	static final double WANDER_HOURS = 30;
	// a new slot's weather eases into the temperature over this many game hours
	static final double EFFECT_HOURS = 2;
	// frozen biomes stay at or below this
	static final float FROZEN_MAX = -1;
	// all snow below, all rain above, sleet between (degrees)
	static final float SNOW_BELOW = -0.5f;
	static final float RAIN_ABOVE = 2.5f;

	// snow depth per game hour per unit of snowfall, cm
	static final float SNOW_CM_PER_HOUR = 5;
	// melt per game hour: per degree above zero, per unit of rain (and more the warmer the rain), in full sun, cm
	static final float MELT_CM_PER_DEGREE = 0.5f;
	static final float RAIN_MELT_CM = 3;
	static final float SUN_MELT_CM = 0.6f;
	// snow deeper than full cover settles by this share of the excess per game hour
	static final float SETTLE = 0.01f;
	static final float MAX_SNOW_CM = 60;
	// depth at which the ground is fully white; below it, patches (the shaders grow them with the depth)
	static final float FULL_COVER_CM = 10;
	// frozen biomes always have at least this much
	static final float FROZEN_SNOW_CM = 30;
	// wetness per game hour per unit of rain, and per cm of snow melting
	static final float WET_PER_HOUR = 3;
	static final float MELT_WET = 0.08f;
	// drying per game hour at 10 degrees under an overcast sky; full sun dries 2.5 times as fast
	static final float DRY_PER_HOUR = 0.2f;
	// below zero wet ground freezes rather than dries
	static final float FROZEN_DRY = 0.3f;
	// how far back the simulation starts, game days: two seasons, within these bounds
	static final int HISTORY_DAYS_MIN = 4;
	static final int HISTORY_DAYS_MAX = 30;

	// ---------------------------------------------------------------------------------------------------------
	// Temperature

	/** The temperature of a biome at a moment, degrees C. */
	static float temperature(String biome, long gameMillis, int seasonDays)
	{
		long slot = WeatherForecast.slot(gameMillis);
		return temperature(biome, gameMillis, seasonDays, WeatherForecast.at(slot - 1, biome, seasonDays),
			WeatherForecast.at(slot, biome, seasonDays));
	}

	/**
	 * The temperature with the forecast's weather of the moment's slot and of the slot before it given.
	 */
	static float temperature(String biome, long gameMillis, int seasonDays, WeatherType previous, WeatherType current)
	{
		Temperature t = WeatherClimate.temperature(biome);
		seasonDays = Math.max(seasonDays, 1);
		// season k is centered on the middle of its days; between two middles the values blend smoothly
		double y = GameClock.days(gameMillis) / seasonDays - 0.5;
		long k = (long) Math.floor(y);
		float f = smooth((float) (y - k));
		float[] a = t.season(WeatherClimate.SEASONS[(int) Math.floorMod(k, 4L)]);
		float[] b = t.season(WeatherClimate.SEASONS[(int) Math.floorMod(k + 1, 4L)]);
		float mean = a[0] + (b[0] - a[0]) * f;
		float swing = a[1] + (b[1] - a[1]) * f;

		double hour = GameClock.hourOfDay(gameMillis);
		float diurnal = (float) Math.cos(2 * Math.PI * (hour - PEAK_HOUR) / 24);
		double into = Math.floorMod(gameMillis, WeatherForecast.SLOT_MILLIS) / (double) GameClock.HOUR;
		float w = smooth((float) Math.min(1, into / EFFECT_HOURS));
		float damp = damp(previous) + (damp(current) - damp(previous)) * w;
		float offset = offset(previous) + (offset(current) - offset(previous)) * w;

		float temp = mean + swing * damp * diurnal + offset + t.noise * wander(biome, GameClock.hours(gameMillis));
		return t.frozen ? Math.min(temp, FROZEN_MAX) : temp;
	}

	/** Slow wander, -1 to 1, smooth in time. */
	static float wander(String biome, double hours)
	{
		int seed = seed(biome);
		float n = 0.65f * WeatherVariation.noise(hours / WANDER_HOURS, seed)
			+ 0.35f * WeatherVariation.noise(hours / (WANDER_HOURS * 0.37), seed + 1);
		// a sum of value noise sits near 0.5; spread it so both cold and warm spells reach the full range
		return Math.max(-1, Math.min(1, (n - 0.5f) * 2.6f));
	}

	private static float damp(WeatherType type)
	{
		return 1 - CLOUD_DAMP * Math.min(type.cover, 1);
	}

	private static float offset(WeatherType type)
	{
		float falling = type.rain + (type.whitens() ? type.snow : 0);
		return type.temperature - PRECIP_COOL * Math.min(falling * showerShare(type), 1.2f);
	}

	/** Share of the time showers actually fall, as the weather field averages them. */
	private static float showerShare(WeatherType type)
	{
		return 1 - type.showers * (1 - Weather.SHOWER_SHARE);
	}

	private static int seed(String biome)
	{
		String b = WeatherClimate.knows(biome) ? biome : WeatherClimate.defaultBiome();
		return 7001 + b.hashCode();
	}

	// ---------------------------------------------------------------------------------------------------------
	// Rain or snow

	/** Share of what falls that is snow at this temperature: 1 in a frost, 0 in a thaw. */
	static float snowShare(float celsius)
	{
		return 1 - smoothstep(SNOW_BELOW, RAIN_ABOVE, celsius);
	}

	private static final int SHARE_STEPS = 10;
	private static final Map<WeatherType, WeatherType[]> variants = new HashMap<>();
	private static int variantsVersion = -1;

	/**
	 * The weather with its rain and snow split by the temperature. Weathers that don't rain or snow (and dust and
	 * ash) are returned as they are, and so is a weather whose own split is already about right.
	 */
	static synchronized WeatherType withTemperature(WeatherType type, float celsius)
	{
		if (!type.precipitates())
		{
			return type;
		}
		float total = type.rain + type.snow;
		int q = Math.round(snowShare(celsius) * SHARE_STEPS);
		float share = q / (float) SHARE_STEPS;
		if (Math.abs(share - type.snow / total) < 0.5f / SHARE_STEPS + 1e-4f)
		{
			return type;
		}
		if (variantsVersion != WeatherType.version())
		{
			variants.clear();
			variantsVersion = WeatherType.version();
		}
		WeatherType[] v = variants.computeIfAbsent(type, k -> new WeatherType[SHARE_STEPS + 1]);
		if (v[q] == null)
		{
			WeatherType c = type.copy();
			c.rain = total * (1 - share);
			c.snow = total * share;
			if (share >= 0.9f)
			{
				// it all comes down as snow, so it all piles up
				c.snowRate = Math.max(c.snowRate, 1);
			}
			if (c.skyWhite >= 0 && type.frost <= 0)
			{
				// a fixed whiteness belonged to the original mix
				c.skyWhite = -1;
			}
			c.name = type.name + (share >= 0.9f ? " as snow" : share <= 0.1f ? " as rain" : " as sleet");
			v[q] = c;
		}
		return v[q];
	}

	// ---------------------------------------------------------------------------------------------------------
	// Ground

	/** History length in game hours for a season length. */
	static long historyHours(int seasonDays)
	{
		return 24L * Math.max(HISTORY_DAYS_MIN, Math.min(HISTORY_DAYS_MAX, 2 * Math.max(seasonDays, 1)));
	}

	/**
	 * One region's weather, temperature and ground at the clock's current moment, and the hourly simulation behind
	 * the ground.
	 */
	static final class Region
	{
		final String biome;
		private final boolean frozen;
		// the simulation: (snowA, wetA) at the start of game hour `hour`, (snowB, wetB) at its end
		private long hour = Long.MIN_VALUE;
		private float snowA, wetA, snowB, wetB;
		private int seasonDays = -1;
		private int catalogue = -1;
		// forecast weather of slot `slot` and the one before it, for the steps
		private long slot = Long.MIN_VALUE;
		private WeatherType slotPrevious, slotCurrent;
		// steps simulated so far, for the tests
		long steps;

		// at the current moment
		private long now = Long.MIN_VALUE;
		float temperature;
		// the forecast's weather, and as it falls at this temperature
		WeatherType forecast = WeatherType.clear();
		WeatherType type = WeatherType.clear();
		float snowCm;
		float wet;

		Region(String biome)
		{
			this.biome = biome;
			this.frozen = WeatherClimate.temperature(biome).frozen;
		}

		/** Bring the region to a moment of the game clock. */
		void update(long gameMillis, int seasonDays)
		{
			seasonDays = Math.max(seasonDays, 1);
			if (gameMillis == now && seasonDays == this.seasonDays && catalogue == WeatherType.version())
			{
				return;
			}
			long h = Math.floorDiv(gameMillis, GameClock.HOUR);
			long history = historyHours(seasonDays);
			if (hour == Long.MIN_VALUE || seasonDays != this.seasonDays || catalogue != WeatherType.version()
				|| h < hour || h - hour > history)
			{
				// start over from clean ground far enough back that it no longer matters
				this.seasonDays = seasonDays;
				catalogue = WeatherType.version();
				slot = Long.MIN_VALUE;
				hour = h - history;
				snowA = frozen ? FROZEN_SNOW_CM : 0;
				wetA = 0;
				step();
			}
			while (hour < h)
			{
				snowA = snowB;
				wetA = wetB;
				++hour;
				step();
			}
			now = gameMillis;
			float f = Math.floorMod(gameMillis, GameClock.HOUR) / (float) GameClock.HOUR;
			snowCm = snowA + (snowB - snowA) * f;
			wet = wetA + (wetB - wetA) * f;
			slotTypes(WeatherForecast.slot(gameMillis));
			forecast = slotCurrent;
			temperature = Climate.temperature(biome, gameMillis, seasonDays, slotPrevious, slotCurrent);
			type = withTemperature(slotCurrent, temperature);
		}

		/** (snowB, wetB) from (snowA, wetA) over game hour `hour`, in the weather of its middle. */
		private void step()
		{
			steps++;
			long mid = hour * GameClock.HOUR + GameClock.HOUR / 2;
			slotTypes(WeatherForecast.slot(mid));
			float t = Climate.temperature(biome, mid, seasonDays, slotPrevious, slotCurrent);
			WeatherType w = withTemperature(slotCurrent, t);
			float shower = showerShare(w);
			float rain = w.rain * shower;
			float snowfall = w.whitens() ? w.snow * shower * w.snowRate : 0;
			double h = GameClock.hourOfDay(mid);
			float sun = (float) Math.max(0, Math.sin(Math.PI * (h - 6) / 12)) * (1 - Math.min(w.cover, 1));

			float snow = snowA + SNOW_CM_PER_HOUR * snowfall;
			float melt = 0;
			if (t > 0)
			{
				melt = MELT_CM_PER_DEGREE * t + RAIN_MELT_CM * rain * (1 + t / 4) + SUN_MELT_CM * sun;
			}
			melt = Math.min(melt, snow);
			snow -= melt;
			snow -= Math.max(0, snow - FULL_COVER_CM) * SETTLE;
			snow = Math.min(snow, MAX_SNOW_CM);
			if (frozen)
			{
				snow = Math.max(snow, FROZEN_SNOW_CM);
			}
			snowB = Math.max(0, snow);

			float gain = WET_PER_HOUR * rain * w.wetRate + MELT_WET * melt;
			float dry = rain > 0.02f ? 0
				: DRY_PER_HOUR * (0.25f + Math.max(0, Math.min(t, 30)) / 10) * (1 + 1.5f * sun) * (t < 0 ? FROZEN_DRY : 1);
			wetB = Math.max(0, Math.min(1, wetA + gain - dry));
		}

		private void slotTypes(long s)
		{
			if (s == slot)
			{
				return;
			}
			slotPrevious = s == slot + 1 ? slotCurrent : WeatherForecast.at(s - 1, biome, seasonDays);
			slotCurrent = WeatherForecast.at(s, biome, seasonDays);
			slot = s;
		}

		/** Snow cover for the shaders, 0 bare to 1 fully white: the depth, or frost in a freezing fog. */
		float snowCover()
		{
			float cover = Math.min(1, snowCm / FULL_COVER_CM);
			if (type.frost > 0 && temperature < 0.5f)
			{
				cover = Math.max(cover, type.frost);
			}
			return cover;
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Every region seen

	private final Map<String, Region> regions = new HashMap<>();
	private long gameMillis;
	private int seasonDays = WeatherForecast.DEFAULT_SEASON_DAYS;

	Climate()
	{
	}

	/** Move the clock; regions follow when next asked for. */
	void update(long gameMillis, int seasonDays)
	{
		this.gameMillis = gameMillis;
		this.seasonDays = Math.max(seasonDays, 1);
	}

	/** A region (null: the default biome) at the current moment. */
	Region region(String biome)
	{
		String key = WeatherClimate.knows(biome) ? biome : WeatherClimate.defaultBiome();
		Region r = regions.get(key);
		if (r == null || r.frozen != WeatherClimate.temperature(key).frozen)
		{
			r = new Region(key);
			regions.put(key, r);
		}
		r.update(gameMillis, seasonDays);
		return r;
	}

	/** The weather of a region as it falls now (WeatherField). */
	WeatherType type(String biome)
	{
		return region(biome).type;
	}

	/** wet and snow cover of a region now, 0 to 1 (WeatherField). */
	float[] ground(String biome)
	{
		Region r = region(biome);
		return new float[]{r.wet, r.snowCover()};
	}

	private static float smooth(float x)
	{
		return x * x * (3 - 2 * x);
	}

	private static float smoothstep(float e0, float e1, float x)
	{
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}
}
