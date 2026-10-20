package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.midonarhel.runelight.WeatherClimate.Season;
import com.midonarhel.runelight.config.WeatherMode;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.BeforeClass;
import org.junit.Test;

public class WeatherForecastTest
{
	private static final int SLOTS = 60000;
	private static final int DAYS = WeatherForecast.DEFAULT_SEASON_DAYS;
	private static final String[] BIOMES = {"GRASSLAND", "FOREST", "DESERT", "BARRENS", "SWAMP", "ARCTIC", "MOUNTAIN",
		"WILDERNESS", "TROPICAL"};

	@BeforeClass
	public static void load()
	{
		WeatherTestData.load();
	}

	/** Share of each weather over many slots, all seasons. */
	private static float[] shares(String biome)
	{
		float[] n = new float[WeatherType.count()];
		for (long s = 0; s < SLOTS; ++s)
		{
			n[WeatherForecast.at(s, biome).index]++;
		}
		for (int i = 0; i < n.length; ++i)
		{
			n[i] /= SLOTS;
		}
		return n;
	}

	/** Share of each weather over the slots of one season only. */
	private static float[] shares(String biome, Season season)
	{
		float[] n = new float[WeatherType.count()];
		int count = 0;
		for (long s = 0; count < SLOTS / 2; ++s)
		{
			if (WeatherForecast.season(s, DAYS) == season)
			{
				n[WeatherForecast.at(s, biome).index]++;
				count++;
			}
		}
		for (int i = 0; i < n.length; ++i)
		{
			n[i] /= count;
		}
		return n;
	}

	private static float share(float[] n, String... ids)
	{
		float sum = 0;
		for (String id : ids)
		{
			sum += n[WeatherTestData.type(id).index];
		}
		return sum;
	}

	private static final String[] SNOWY = {"LIGHT_SNOW", "SNOW", "HEAVY_SNOW", "BLIZZARD", "SLEET"};
	private static final String[] RAINY = {"DRIZZLE", "RAIN", "SPRING_SHOWERS", "SUMMER_RAIN", "AUTUMN_RAIN",
		"THUNDERSTORM", "MONSOON", "SLEET"};

	@Test
	public void catalogue()
	{
		WeatherType[] all = WeatherType.all();
		assertTrue("types " + all.length, all.length >= 20);
		for (int i = 0; i < all.length; ++i)
		{
			assertEquals(i, all[i].index);
			assertTrue(all[i].cover >= 0 && all[i].cover <= 1);
			assertTrue(all[i].persistence > 0);
			assertEquals(3, all[i].fogTint.length);
			assertEquals(3, all[i].flakeColor.length);
		}
		assertEquals("CLEAR", WeatherType.clear().id);
		// every forced setting is a type, and every type can be forced
		for (WeatherMode m : WeatherMode.values())
		{
			if (m != WeatherMode.AUTOMATIC && m != WeatherMode.OFF)
			{
				assertNotNull(m.name(), WeatherType.get(m.name()));
				assertEquals(WeatherType.get(m.name()), Weather.resolve(m, null));
			}
		}
		assertEquals(all.length + 2, WeatherMode.values().length);
		assertEquals(WeatherType.clear(), Weather.resolve(WeatherMode.OFF, WeatherType.get("BLIZZARD")));
		assertEquals(WeatherType.get("BLIZZARD"), Weather.resolve(WeatherMode.AUTOMATIC, WeatherType.get("BLIZZARD")));
		// only storms strike
		for (WeatherType t : all)
		{
			boolean strikes = t.lightning >= 0.6f;
			assertEquals(t.id, t.id.equals("THUNDERSTORM") || t.id.equals("MONSOON"), strikes);
		}
	}

	@Test
	public void climateNamesOnlyKnownTypes() throws Exception
	{
		// the climate drops unknown ids silently; a typo there would quietly remove a weather
		JsonObject root;
		try (InputStreamReader r = new InputStreamReader(WeatherData.class.getResourceAsStream(WeatherData.CLIMATE),
			StandardCharsets.UTF_8))
		{
			root = new JsonParser().parse(r).getAsJsonObject();
		}
		for (Map.Entry<String, JsonElement> morning : root.getAsJsonObject("morning").entrySet())
		{
			assertNotNull(morning.getKey(), WeatherType.get(morning.getKey()));
		}
		JsonObject biomes = root.getAsJsonObject("biomes");
		for (String b : BIOMES)
		{
			JsonObject seasons = biomes.getAsJsonObject(b);
			assertNotNull(b, seasons);
			for (Season season : Season.values())
			{
				JsonObject row = seasons.getAsJsonObject(season.name().toLowerCase());
				assertNotNull(b + " " + season, row);
				for (Map.Entry<String, JsonElement> e : row.entrySet())
				{
					assertNotNull(b + " " + season + " " + e.getKey(), WeatherType.get(e.getKey()));
					assertTrue(e.getValue().getAsFloat() > 0);
				}
			}
		}
		// and nothing in the types file is misspelled as a field (unknown fields are ignored by Gson)
		try (InputStreamReader r = new InputStreamReader(WeatherData.class.getResourceAsStream(WeatherData.TYPES),
			StandardCharsets.UTF_8))
		{
			JsonObject types = new JsonParser().parse(r).getAsJsonObject();
			java.util.Set<String> fields = new java.util.HashSet<>();
			for (java.lang.reflect.Field f : WeatherType.class.getDeclaredFields())
			{
				fields.add(f.getName());
			}
			for (Map.Entry<String, JsonElement> t : types.entrySet())
			{
				for (String key : t.getValue().getAsJsonObject().keySet())
				{
					assertTrue(t.getKey() + "." + key, fields.contains(key));
				}
			}
		}
		new Gson();
	}

	@Test
	public void deterministic()
	{
		for (String b : BIOMES)
		{
			for (long s = -50; s < 500; ++s)
			{
				assertEquals(WeatherForecast.at(s, b), WeatherForecast.at(s, b));
			}
		}
		// the forecast of the next slot is what that slot then gets
		WeatherForecast f = new WeatherForecast();
		WeatherForecast g = new WeatherForecast();
		for (long ms = 0; ms < 10 * GameClock.DAY; ms += 37 * 60_000)
		{
			f.update("SWAMP", ms, 12, 3);
			g.update("SWAMP", ms, 12, 3);
			assertEquals(f.getCurrent(), g.getCurrent());
			WeatherType next = f.getNext();
			float minutes = f.getMinutesToNext();
			assertTrue(minutes > 0 && minutes <= 4.0001f);
			f.update("SWAMP", ms + (long) Math.ceil(minutes * 60_000 / GameClock.realPerGame(12)) + 100, 12, 3);
			assertEquals(next, f.getCurrent());
			f.update("SWAMP", ms, 12, 3);
		}
		// unknown or missing biomes behave like the default
		String def = WeatherClimate.defaultBiome();
		assertEquals(WeatherForecast.at(77, null), WeatherForecast.at(77, def));
		assertEquals(WeatherForecast.at(77, "NOWHERE"), WeatherForecast.at(77, def));
	}

	@Test
	public void slotsFollowTheDay()
	{
		// game time 0 is midnight, so the first change is at 08:00: 4 minutes of a 12 minute day, 8 of a 24 minute one
		WeatherForecast f = new WeatherForecast();
		f.update(null, 0, 12, 7);
		assertEquals(4f, f.getMinutesToNext(), 1e-4f);
		f.update(null, 0, 24, 7);
		assertEquals(8f, f.getMinutesToNext(), 1e-4f);
		f.update(null, 6 * GameClock.HOUR, 24, 7);
		assertEquals(2f, f.getMinutesToNext(), 1e-4f);
		// a third of a day per slot
		assertEquals(0, WeatherForecast.slot(0));
		assertEquals(0, WeatherForecast.slot(8 * GameClock.HOUR - 1));
		assertEquals(1, WeatherForecast.slot(8 * GameClock.HOUR));
		assertEquals(-1, WeatherForecast.slot(-1));
		assertEquals(3_000_000_000L, WeatherForecast.slot(1_000_000_000L * GameClock.DAY));
	}

	@Test
	public void seasonsCycle()
	{
		// N game days each, in order, and round again
		int days = 5;
		Season prev = WeatherForecast.season(0, days);
		int changes = 0;
		for (long s = 1; s < 3L * days * 4 * 3; ++s)
		{
			Season now = WeatherForecast.season(s, days);
			if (now != prev)
			{
				assertEquals(0, s % (3L * days));
				assertEquals(WeatherClimate.SEASONS[(prev.ordinal() + 1) % 4], now);
				changes++;
			}
			prev = now;
		}
		assertEquals(11, changes);
		assertEquals(Season.SPRING, WeatherForecast.season(0, days));
		assertEquals(Season.WINTER, WeatherForecast.season(-1, days));
		// the forecast reports the day of its season
		WeatherForecast f = new WeatherForecast();
		long day = GameClock.DAY;
		f.update(null, 0, 12, days);
		Season first = f.getSeason();
		int firstDay = f.getSeasonDay();
		assertTrue(firstDay >= 1 && firstDay <= days);
		f.update(null, day * (days - firstDay + 1), 12, days);
		assertEquals(1, f.getSeasonDay());
		assertEquals(WeatherClimate.SEASONS[(first.ordinal() + 1) % 4], f.getSeason());
		// odds blend into the next season's only at the end of a season, and always sum up to something
		for (long s = 0; s < 3L * days * 4; ++s)
		{
			float[] o = WeatherForecast.odds("GRASSLAND", s, days);
			float sum = 0;
			for (float v : o)
			{
				assertTrue(v >= 0);
				sum += v;
			}
			assertTrue(sum > 0);
			if (WeatherForecast.seasonProgress(s, days) < 1 - WeatherForecast.SEASON_BLEND)
			{
				assertTrue(o == WeatherClimate.odds("GRASSLAND", WeatherForecast.season(s, days)));
			}
		}
	}

	@Test
	public void noSnowInTheDesertNorRainInTheArctic()
	{
		for (Season season : Season.values())
		{
			float[] desert = shares("DESERT", season);
			assertEquals(season.toString(), 0, share(desert, SNOWY), 0);
			assertEquals(0, share(desert, "FREEZING_FOG", "ASH_FALL", "MONSOON"), 0);
			assertTrue(share(desert, "CLEAR", "SUNNY", "HARSH_SUN", "HAZY") > 0.6f);
			assertTrue(share(desert, "SANDSTORM") > 0.02f);
			float[] arctic = shares("ARCTIC", season);
			assertEquals(season.toString(), 0, share(arctic, RAINY), 0);
			assertEquals(0, share(arctic, "HARSH_SUN", "SANDSTORM"), 0);
			float[] tropical = shares("TROPICAL", season);
			assertEquals(0, share(tropical, SNOWY), 0);
			assertEquals(0, share(tropical, "FREEZING_FOG"), 0);
		}
		// across the whole year as well, with the season blends and the chain
		assertEquals(0, share(shares("DESERT"), SNOWY), 0);
		assertEquals(0, share(shares("ARCTIC"), RAINY), 0);
	}

	@Test
	public void seasonsShiftTheOdds()
	{
		// temperate regions: spring showers, summer heat and summer rain, autumn rain and wind, some snow in winter
		for (String b : new String[]{"GRASSLAND", "FOREST"})
		{
			float[] spring = shares(b, Season.SPRING);
			float[] summer = shares(b, Season.SUMMER);
			float[] autumn = shares(b, Season.AUTUMN);
			float[] winter = shares(b, Season.WINTER);
			assertTrue(b, share(spring, "SPRING_SHOWERS") > 0.08f);
			assertTrue(b, share(spring, "SPRING_SHOWERS") > 2 * share(summer, "SPRING_SHOWERS"));
			assertTrue(b, share(summer, "SUMMER_RAIN") > 0.04f);
			assertTrue(b, share(summer, "CLEAR", "SUNNY", "HARSH_SUN") > share(winter, "CLEAR", "SUNNY", "HARSH_SUN") + 0.15f);
			assertTrue(b, share(autumn, "AUTUMN_RAIN") > 0.08f);
			assertTrue(b, share(autumn, "AUTUMN_RAIN", "GALE", "OVERCAST") > share(summer, "AUTUMN_RAIN", "GALE", "OVERCAST") + 0.15f);
			float snow = share(winter, SNOWY);
			assertTrue(b + " winter snow " + snow, snow > 0.08f && snow < 0.4f);
			assertTrue(b, share(summer, SNOWY) < 0.03f);
			// a mix: nothing dominates any season
			for (float[] s : new float[][]{spring, summer, autumn, winter})
			{
				for (float v : s)
				{
					assertTrue(v < 0.35f);
				}
			}
		}
		// mountains snow in winter, much less in summer
		assertTrue(share(shares("MOUNTAIN", Season.WINTER), SNOWY) > 0.5f);
		assertTrue(share(shares("MOUNTAIN", Season.SUMMER), SNOWY) < 0.1f);
		// arctic winters are harsh
		assertTrue(share(shares("ARCTIC", Season.WINTER), "HEAVY_SNOW", "BLIZZARD") > 0.25f);
		// deserts burn in summer
		assertTrue(share(shares("DESERT", Season.SUMMER), "HARSH_SUN") > 0.2f);
		// the tropics have a wet season
		assertTrue(share(shares("TROPICAL", Season.SUMMER), "MONSOON", "THUNDERSTORM")
			> share(shares("TROPICAL", Season.WINTER), "MONSOON", "THUNDERSTORM") + 0.15f);
	}

	@Test
	public void biomeOdds()
	{
		float[] swamp = shares("SWAMP");
		assertTrue(share(swamp, "MIST", "FOG", "FREEZING_FOG") > 0.25f);
		assertTrue(share(swamp, "MIST", "FOG", "FREEZING_FOG", "DRIZZLE", "RAIN", "AUTUMN_RAIN", "SUMMER_RAIN",
			"THUNDERSTORM") > 0.55f);

		float[] desert = shares("DESERT");
		float desertStorm = share(desert, "THUNDERSTORM");
		assertTrue(desertStorm > 0 && desertStorm < 0.03f);

		float[] wilderness = shares("WILDERNESS");
		float[] grassland = shares("GRASSLAND");
		assertTrue(share(wilderness, "CLEAR", "SUNNY", "PARTLY_CLOUDY")
			< share(grassland, "CLEAR", "SUNNY", "PARTLY_CLOUDY") - 0.15f);
		assertTrue(share(wilderness, "ASH_FALL") > 0.02f);

		// every type turns up somewhere
		float[] seen = new float[WeatherType.count()];
		for (String b : BIOMES)
		{
			float[] n = shares(b);
			for (int i = 0; i < n.length; ++i)
			{
				seen[i] += n[i];
			}
		}
		for (WeatherType t : WeatherType.all())
		{
			assertTrue(t.id, seen[t.index] > 0);
		}

		// morning mist: mist and fog are likelier in the slot that ends at 08:00
		WeatherType mist = WeatherTestData.type("MIST"), fog = WeatherTestData.type("FOG");
		int morning = 0, other = 0;
		for (long s = 0; s < SLOTS; ++s)
		{
			WeatherType w = WeatherForecast.at(s, "FOREST");
			if (w == mist || w == fog)
			{
				if (Math.floorMod(s, 3) == 0)
				{
					morning++;
				}
				else
				{
					other++;
				}
			}
		}
		assertTrue(morning > other / 2 * 1.3f);
	}

	@Test
	public void transitionsAreConnected()
	{
		WeatherType clear = WeatherTestData.type("CLEAR"), sunny = WeatherTestData.type("SUNNY");
		WeatherType storm = WeatherTestData.type("THUNDERSTORM"), blizzard = WeatherTestData.type("BLIZZARD");
		WeatherType monsoon = WeatherTestData.type("MONSOON");
		for (String b : BIOMES)
		{
			float[] n = shares(b);
			float independentRepeat = 0;
			for (float v : n)
			{
				independentRepeat += v * v;
			}
			int repeats = 0, jumps = 0, fromClear = 0;
			WeatherType prev = WeatherForecast.at(-1, b);
			for (long s = 0; s < SLOTS; ++s)
			{
				WeatherType w = WeatherForecast.at(s, b);
				if (w == prev)
				{
					repeats++;
				}
				if (prev == clear || prev == sunny)
				{
					fromClear++;
					if (w == storm || w == blizzard || w == monsoon)
					{
						jumps++;
					}
				}
				prev = w;
			}
			// weather persists more than independent draws would, but still changes most of the time it can
			float repeat = repeats / (float) SLOTS;
			assertTrue(b + " repeat " + repeat, repeat > independentRepeat + 0.05f);
			assertTrue(b + " repeat " + repeat, repeat < 0.75f);
			// clear skies rarely turn straight into a storm
			assertTrue(b + " jumps " + jumps / (float) fromClear, fromClear == 0 || jumps / (float) fromClear < 0.03f);
		}
		// alike weathers are nearer than unlike ones
		float[][] a = WeatherForecast.affinity();
		int c = clear.index;
		assertTrue(a[c][sunny.index] > 1.5f);
		assertTrue(a[c][storm.index] < 0.3f);
		assertTrue(a[WeatherTestData.type("SNOW").index][WeatherTestData.type("HEAVY_SNOW").index] > 1.2f);
		assertTrue(a[WeatherTestData.type("SANDSTORM").index][WeatherTestData.type("LIGHT_SNOW").index] < 0.6f);
	}

	@Test
	public void lightningIsGentle()
	{
		Lightning l = new Lightning();
		float dt = 1 / 60f;
		int frames = 3600 * 60;
		int strikes = 0;
		float lastStrike = -100;
		float minGap = Float.MAX_VALUE;
		boolean wasLit = false;
		float prev = 0;
		boolean rising = false;
		int peaksInWindow = 0;
		float windowStart = 0;
		int maxPeaksPerSecond = 0;
		for (int i = 0; i < frames; ++i)
		{
			float t = i * dt;
			l.update(dt, 1, true, 0, 0);
			float f = l.getFlash();
			assertTrue(f >= 0 && f <= 1);
			boolean lit = f > 0;
			if (lit && !wasLit)
			{
				strikes++;
				minGap = Math.min(minGap, t - lastStrike);
				lastStrike = t;
			}
			wasLit = lit;
			// count flicker peaks per second
			if (f < prev && rising)
			{
				if (t - windowStart > 1)
				{
					windowStart = t;
					peaksInWindow = 0;
				}
				peaksInWindow++;
				maxPeaksPerSecond = Math.max(maxPeaksPerSecond, peaksInWindow);
			}
			rising = f > prev;
			prev = f;
		}
		// one strike every ~5-25 s in a full storm, and never two within 5 s
		assertTrue("strikes " + strikes, strikes > 3600 / 25 && strikes < 3600 / 5);
		assertTrue("gap " + minGap, minGap >= 5);
		assertTrue("peaks " + maxPeaksPerSecond, maxPeaksPerSecond <= 3);

		// no storm, no lightning
		Lightning calm = new Lightning();
		for (int i = 0; i < 600 * 60; ++i)
		{
			calm.update(dt, 0.3f, true, 0, 0);
			assertEquals(0, calm.getFlash(), 0);
			calm.update(dt, 1, false, 0, 0);
			assertEquals(0, calm.getFlash(), 0);
		}
	}

	@Test
	public void clearWeatherIsLight()
	{
		// a quarter of the old clear haze (0.6), and fog is where the thick fog is
		assertEquals(0.15f, WeatherType.clear().fog, 1e-6f);
		for (WeatherType t : WeatherType.all())
		{
			assertTrue(t.id, t.fog <= WeatherTestData.type("FOG").fog);
			assertTrue(t.cover >= 0 && t.cover <= 1);
		}
		assertEquals(1, Weather.morning(6.5), 1e-6f);
		assertEquals(0, Weather.morning(14), 0);
		assertEquals(0, Weather.morning(0), 0);
		// easing approaches the target without overshooting
		float v = 0;
		for (int i = 0; i < 60 * 60; ++i)
		{
			float next = Weather.ease(v, 1, 1 / 60f, 15);
			assertTrue(next >= v && next <= 1);
			v = next;
		}
		assertTrue(v > 0.95f);
	}

	@Test
	public void morytaniaNeverClears()
	{
		assertTrue(WeatherClimate.knows("MORYTANIA_SWAMP"));
		float[] n = shares("MORYTANIA_SWAMP");
		for (String clear : new String[]{"CLEAR", "SUNNY", "HARSH_SUN", "PARTLY_CLOUDY", "HIGH_CLOUDS"})
		{
			assertEquals(clear, 0, n[WeatherType.get(clear).index], 0);
		}
	}
}
