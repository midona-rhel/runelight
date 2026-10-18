package com.midonarhel.runelight;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Which weather each biome gets in each season: weather_climate.json's odds, as weight arrays in the catalogue's
 * order (WeatherType.index). A zero stays zero whatever the forecast's chain does: deserts never see snow.
 */
final class WeatherClimate
{
	enum Season
	{
		SPRING("Spring"),
		SUMMER("Summer"),
		AUTUMN("Autumn"),
		WINTER("Winter");

		private final String name;

		Season(String name)
		{
			this.name = name;
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	static final Season[] SEASONS = Season.values();

	/** As in weather_climate.json. */
	static final class Data
	{
		String defaultBiome = "GRASSLAND";
		// type id -> multiplier on its odds in the slot ending in the morning (00:00 to 08:00): morning mists
		Map<String, Float> morning;
		// biome -> season name (lower case) -> type id -> weight
		Map<String, Map<String, Map<String, Float>>> biomes;
		// biome -> its temperatures (Climate)
		Map<String, Temperature> temperature;
	}

	/** A biome's temperatures: per season the mean and the half swing between night and afternoon, degrees C. */
	static final class Temperature
	{
		float[] spring, summer, autumn, winter;
		// half range of the slow wander over a couple of days
		float noise = 2.5f;
		// never thaws: always below zero, always snow on the ground
		boolean frozen;

		float[] season(Season s)
		{
			switch (s)
			{
				case SPRING:
					return spring;
				case SUMMER:
					return summer;
				case AUTUMN:
					return autumn;
				default:
					return winter;
			}
		}
	}

	// a biome without temperatures: mild, a little frost in winter
	private static final Temperature MILD = new Temperature();

	static
	{
		MILD.spring = new float[]{9, 5};
		MILD.summer = new float[]{17, 6};
		MILD.autumn = new float[]{9, 4};
		MILD.winter = new float[]{1, 4};
	}

	private static final class Tables
	{
		final String defaultBiome;
		// biome -> [season][type]
		final Map<String, float[][]> odds = new HashMap<>();
		final float[] morning;
		final int catalogueVersion;
		final Map<String, Temperature> temperature = new HashMap<>();

		Tables(Data d)
		{
			catalogueVersion = WeatherType.version();
			int n = WeatherType.count();
			morning = new float[n];
			java.util.Arrays.fill(morning, 1);
			if (d.morning != null)
			{
				for (Map.Entry<String, Float> e : d.morning.entrySet())
				{
					WeatherType t = WeatherType.get(e.getKey());
					if (t != null && e.getValue() != null)
					{
						morning[t.index] = e.getValue();
					}
				}
			}
			if (d.biomes != null)
			{
				for (Map.Entry<String, Map<String, Map<String, Float>>> b : d.biomes.entrySet())
				{
					float[][] bySeason = new float[SEASONS.length][];
					for (Season s : SEASONS)
					{
						Map<String, Float> row = b.getValue().get(s.name().toLowerCase());
						bySeason[s.ordinal()] = weights(row, n);
					}
					// a biome with only some seasons given uses its first given one for the rest
					float[] fallback = null;
					for (float[] w : bySeason)
					{
						if (w != null)
						{
							fallback = w;
							break;
						}
					}
					if (fallback == null)
					{
						continue;
					}
					for (int s = 0; s < bySeason.length; ++s)
					{
						if (bySeason[s] == null)
						{
							bySeason[s] = fallback;
						}
					}
					odds.put(b.getKey(), bySeason);
				}
			}
			if (d.temperature != null)
			{
				for (Map.Entry<String, Temperature> e : d.temperature.entrySet())
				{
					Temperature t = e.getValue();
					if (t != null)
					{
						for (Season s : SEASONS)
						{
							float[] v = t.season(s);
							if (v == null || v.length < 2)
							{
								// a season left out takes the mild default's
								v = MILD.season(s);
							}
							switch (s)
							{
								case SPRING:
									t.spring = v;
									break;
								case SUMMER:
									t.summer = v;
									break;
								case AUTUMN:
									t.autumn = v;
									break;
								default:
									t.winter = v;
							}
						}
						temperature.put(e.getKey(), t);
					}
				}
			}
			String def = d.defaultBiome != null ? d.defaultBiome : "GRASSLAND";
			if (!odds.containsKey(def))
			{
				// nothing loaded: clear weather everywhere
				float[] clear = new float[n];
				clear[WeatherType.clear().index] = 1;
				odds.put(def, new float[][]{clear, clear, clear, clear});
			}
			defaultBiome = def;
		}

		private static float[] weights(Map<String, Float> row, int n)
		{
			if (row == null)
			{
				return null;
			}
			float[] w = new float[n];
			float total = 0;
			for (Map.Entry<String, Float> e : row.entrySet())
			{
				WeatherType t = WeatherType.get(e.getKey());
				if (t != null && e.getValue() != null && e.getValue() > 0)
				{
					w[t.index] += e.getValue();
					total += e.getValue();
				}
			}
			if (total <= 0)
			{
				w[WeatherType.clear().index] = 1;
			}
			return w;
		}
	}

	private static Data data = new Data();
	private static volatile Tables tables;

	private WeatherClimate()
	{
	}

	static synchronized void install(Data d)
	{
		data = d != null ? d : new Data();
		tables = new Tables(data);
	}

	/** The tables for the current catalogue, rebuilt when the catalogue changed since. */
	private static Tables tables()
	{
		Tables t = tables;
		if (t == null || t.catalogueVersion != WeatherType.version())
		{
			synchronized (WeatherClimate.class)
			{
				t = tables;
				if (t == null || t.catalogueVersion != WeatherType.version())
				{
					t = tables = new Tables(data);
				}
			}
		}
		return t;
	}

	static String defaultBiome()
	{
		return tables().defaultBiome;
	}

	/** Whether the climate knows this biome; unknown ones get the default biome's odds. */
	static boolean knows(String biome)
	{
		return biome != null && tables().odds.containsKey(biome);
	}

	/** The biome's odds in a season, in catalogue order; unknown and missing biomes get the default's. */
	static float[] odds(String biome, Season season)
	{
		Tables t = tables();
		float[][] o = biome != null ? t.odds.get(biome) : null;
		if (o == null)
		{
			o = t.odds.get(t.defaultBiome);
		}
		return o[season.ordinal()];
	}

	/** The biome's temperatures; unknown biomes get the default biome's, and without any, mild ones. */
	static Temperature temperature(String biome)
	{
		Tables t = tables();
		Temperature r = biome != null ? t.temperature.get(biome) : null;
		if (r == null)
		{
			r = t.temperature.get(t.defaultBiome);
		}
		return r != null ? r : MILD;
	}

	/** Multiplier on a type's odds in the morning slot. */
	static float morning(WeatherType type)
	{
		float[] m = tables().morning;
		return type.index < m.length ? m[type.index] : 1;
	}

	static Map<String, float[][]> allOdds()
	{
		return Collections.unmodifiableMap(tables().odds);
	}
}
