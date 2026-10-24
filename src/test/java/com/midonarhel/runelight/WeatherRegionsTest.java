package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import com.google.gson.Gson;
import com.midonarhel.runelight.WeatherClimate.Season;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import org.junit.BeforeClass;
import org.junit.Test;

/** The climate map: where each biome lies, that no snow can fall where it never should, and that it is seamless. */
public class WeatherRegionsTest
{
	private static final int SEASON_DAYS = 7;
	private static final long DAY = GameClock.DAY;
	private static final long YEAR = 4 * SEASON_DAYS * DAY;
	// biomes that are never cold enough for snow
	private static final String[] HOT = {"DESERT", "TROPICAL", "MEDITERRANEAN", "VOLCANIC", "WETLAND"};
	private static EnvironmentManager env;

	@BeforeClass
	public static void load()
	{
		WeatherTestData.load();
		env = new EnvironmentManager();
		env.load(new Gson());
	}

	private static String biome(int x, int y)
	{
		return env.weatherBiomeAt(x, y, 0);
	}

	private static void at(String expected, String place, int x, int y)
	{
		assertEquals(place + " (" + x + ", " + y + ")", expected, biome(x, y));
	}

	@Test
	public void theMainland()
	{
		at("GRASSLAND", "Lumbridge", 3222, 3218);
		at("GRASSLAND", "Draynor", 3093, 3244);
		at("GRASSLAND", "Varrock", 3213, 3428);
		at("GRASSLAND", "Falador", 2965, 3380);
		at("GRASSLAND", "Ardougne", 2662, 3305);
		at("SWAMP", "Lumbridge Swamp", 3170, 3170);
		at("FOREST", "Tree Gnome Stronghold", 2460, 3440);
		at("FOREST", "Tirannwn", 2340, 3170);
		at("SWAMP", "Poison Waste", 2250, 3090);
	}

	@Test
	public void theDesertIsAllDesert()
	{
		at("DESERT", "Al Kharid", 3293, 3174);
		at("DESERT", "Duel Arena", 3315, 3235);
		at("DESERT", "Shantay Pass", 3304, 3125);
		at("DESERT", "Pollnivneach", 3359, 2979);
		at("DESERT", "Nardah", 3420, 2890);
		at("DESERT", "Sophanem", 3300, 2780);
		at("DESERT", "Menaphos", 3230, 2760);
		at("DESERT", "Uzer", 3490, 3090);
	}

	@Test
	public void theTropics()
	{
		at("TROPICAL", "Brimhaven", 2780, 3180);
		at("TROPICAL", "Musa Point", 2920, 3150);
		at("TROPICAL", "Shilo Village", 2850, 2960);
		at("TROPICAL", "Tai Bwo Wannai", 2800, 3080);
		at("TROPICAL", "Ape Atoll", 2760, 2750);
		at("TROPICAL", "Feldip Hills", 2540, 2970);
		at("TROPICAL", "Mos Le'Harmless", 3680, 2970);
		at("TROPICAL", "Fossil Island", 3745, 3800);
		at("TROPICAL", "Tlati Rainforest", 1250, 3150);
	}

	@Test
	public void theColdNorth()
	{
		at("TUNDRA", "Rellekka", 2660, 3660);
		at("TUNDRA", "Miscellania", 2580, 3850);
		at("MOUNTAIN", "Trollheim", 2890, 3680);
		at("MOUNTAIN", "Burthorpe", 2930, 3555);
		at("MOUNTAIN", "Ice Mountain", 3007, 3475);
		at("MOUNTAIN", "White Wolf Mountain slopes", 2850, 3525);
		at("ARCTIC", "Weiss", 2850, 3940);
		at("ARCTIC", "God Wars Dungeon entrance", 2880, 3750);
		at("ARCTIC", "Frozen Waste Plateau", 2975, 3880);
	}

	@Test
	public void morytaniaAndTheWilderness()
	{
		at("MORYTANIA_SWAMP", "Canifis", 3495, 3485);
		at("MORYTANIA_SWAMP", "Mort Myre", 3450, 3400);
		at("MORYTANIA_SWAMP", "Port Phasmatys", 3680, 3490);
		at("MORYTANIA_SWAMP", "Burgh de Rott", 3500, 3210);
		at("WILDERNESS", "Wilderness", 3100, 3700);
		at("WILDERNESS", "Bandit Camp", 3040, 3700);
		at("GRASSLAND", "Edgeville", 3093, 3493);
	}

	@Test
	public void theHousesOfKourendAndVarlamore()
	{
		at("GRASSLAND", "Kourend Castle", 1640, 3670);
		at("MEDITERRANEAN", "Hosidius", 1745, 3510);
		at("VOLCANIC", "Lovakengj", 1500, 3800);
		at("MOORLAND", "Arceuus", 1632, 3808);
		at("MOORLAND", "Shayzien", 1500, 3590);
		at("WETLAND", "Piscarilius", 1810, 3730);
		at("WETLAND", "Kebos Lowlands", 1250, 3600);
		at("ARCTIC", "Wintertodt", 1630, 3995);
		at("MEDITERRANEAN", "Civitas illa Fortis", 1700, 3100);
		at("MEDITERRANEAN", "Aldarin", 1400, 2930);
	}

	@Test
	public void farOffAndUnderground()
	{
		at("FOREST", "Prifddinas", 3260, 6100);
		// dungeons and caves have no sky
		at(WeatherField.NO_SKY, "Taverley Dungeon", 2880, 9800);
		at(WeatherField.NO_SKY, "Lumbridge Swamp Caves", 3160, 9570);
	}

	@Test
	public void everyBiomeOnTheMapHasAClimate()
	{
		WeatherRegions r = WeatherRegions.get();
		assertTrue(WeatherClimate.knows(r.getDefaultBiome()));
		Set<String> used = new HashSet<>();
		for (int x = 1100; x < 3900; x += 40)
		{
			for (int y = 2200; y < 4160; y += 40)
			{
				used.add(biome(x, y));
			}
		}
		for (String b : used)
		{
			assertTrue(b + " has weather", WeatherClimate.knows(b));
			assertNotNull(b + " has temperatures", WeatherClimate.temperature(b));
			assertTrue(b + " has a fog profile", WeatherClimate.fog(b) != FogClimate.PLAIN);
			assertTrue(b + " has a look", r.look(b) != null);
		}
		// the whole surface is covered, and every kind of place is on it
		for (String b : new String[]{"GRASSLAND", "FOREST", "DESERT", "SWAMP", "MORYTANIA_SWAMP", "ARCTIC", "MOUNTAIN",
			"WILDERNESS", "TROPICAL", "MEDITERRANEAN", "VOLCANIC", "WETLAND", "MOORLAND", "TUNDRA"})
		{
			assertTrue(b + " is somewhere", used.contains(b));
		}
		assertFalse(used.contains(WeatherField.NO_SKY));
	}

	@Test
	public void hotPlacesNeverSee()
	{
		for (String b : HOT)
		{
			// the odds have no snow in any season
			for (Season s : WeatherClimate.SEASONS)
			{
				float[] odds = WeatherClimate.odds(b, s);
				for (WeatherType t : WeatherType.all())
				{
					if (odds[t.index] > 0)
					{
						assertFalse(b + " " + s + " " + t.id, t.whitens());
					}
				}
			}
			// and never cold enough to turn rain to sleet or snow, nor to leave any on the ground, over many years
			Climate climate = new Climate();
			long start = 12 * YEAR;
			for (long t = start; t < start + 6 * YEAR; t += 5 * GameClock.HOUR)
			{
				climate.update(t, SEASON_DAYS);
				Climate.Region r = climate.region(b);
				assertFalse(b + " at " + t + ": " + r.type, r.type.whitens());
				assertEquals(b + " snow on the ground", 0, r.snowCm, 0);
				assertEquals(b + " snow cover", 0, r.snowCover(), 0);
				assertTrue(b + " " + r.temperature, r.temperature > Climate.SNOW_BELOW);
			}
		}
	}

	@Test
	public void coldPlacesStayCold()
	{
		Climate climate = new Climate();
		long start = 12 * YEAR;
		for (String b : new String[]{"ARCTIC", "MOUNTAIN"})
		{
			for (long t = start; t < start + YEAR; t += GameClock.HOUR)
			{
				climate.update(t, SEASON_DAYS);
				assertTrue(b, climate.region(b).snowCm > 0);
			}
		}
	}

	// --- the field over the real map -----------------------------------------------------------------------------

	private static final int SPAN = 41;

	/** The weather field over a window of the map whose south-west cell holds tile (x, y), in winter. */
	private static WeatherField field(int x, int y)
	{
		Climate climate = new Climate();
		climate.update(12 * YEAR + 3 * SEASON_DAYS * DAY + 3 * DAY + 3 * GameClock.HOUR, SEASON_DAYS);
		int cx0 = Math.floorDiv(x, WeatherField.CELL_TILES), cz0 = Math.floorDiv(y, WeatherField.CELL_TILES);
		long[] keys = new long[SPAN * SPAN];
		String[] biomes = new String[SPAN * SPAN];
		for (int z = 0; z < SPAN; ++z)
		{
			for (int i = 0; i < SPAN; ++i)
			{
				int wx = (cx0 + i) * WeatherField.CELL_TILES + 8, wy = (cz0 + z) * WeatherField.CELL_TILES + 8;
				keys[z * SPAN + i] = WeatherField.chunkKey(wx, wy);
				biomes[z * SPAN + i] = env.weatherBiomeAt(wx, wy, 0);
			}
		}
		WeatherField f = new WeatherField();
		f.layout(SPAN, SPAN, cx0 * WeatherField.CELL, cz0 * WeatherField.CELL, keys, biomes);
		f.update(climate::type, climate::ground, 0.6f, false, 3, 0);
		return f;
	}

	/** Every channel, a tile at a time from one place to another, never steps by more than a small share. */
	private static void assertSeamless(String route, int x0, int y0, int x1, int y1)
	{
		WeatherField f = field(Math.min(x0, x1) - 8 * WeatherField.CELL_TILES, Math.min(y0, y1) - 8 * WeatherField.CELL_TILES);
		int tiles = Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0));
		float ox = f.getOriginX(), oz = f.getOriginZ();
		float[] prev = new float[WeatherField.CHANNELS];
		int changing = 0;
		float[] lo = new float[WeatherField.CHANNELS], hi = new float[WeatherField.CHANNELS];
		for (int c = 0; c < WeatherField.CHANNELS; ++c)
		{
			lo[c] = Float.MAX_VALUE;
			hi[c] = -Float.MAX_VALUE;
			for (int z = 0; z < SPAN; ++z)
			{
				for (int i = 0; i < SPAN; ++i)
				{
					lo[c] = Math.min(lo[c], f.cell(c, i, z));
					hi[c] = Math.max(hi[c], f.cell(c, i, z));
				}
			}
		}
		float[] steepest = new float[WeatherField.CHANNELS];
		float[] first = new float[WeatherField.CHANNELS], last = new float[WeatherField.CHANNELS];
		for (int t = 0; t <= tiles; ++t)
		{
			float x = (x0 + (x1 - x0) * (float) t / tiles) * 128, z = (y0 + (y1 - y0) * (float) t / tiles) * 128;
			// to local units of the window
			x -= (Math.min(x0, x1) - 8 * WeatherField.CELL_TILES) / WeatherField.CELL_TILES * WeatherField.CELL_TILES * 128f
				- ox;
			z -= (Math.min(y0, y1) - 8 * WeatherField.CELL_TILES) / WeatherField.CELL_TILES * WeatherField.CELL_TILES * 128f
				- oz;
			for (int c = 0; c < WeatherField.CHANNELS; ++c)
			{
				float v = f.sample(c, x, z);
				if (t == 0)
				{
					first[c] = v;
				}
				else
				{
					steepest[c] = Math.max(steepest[c], Math.abs(v - prev[c]));
				}
				last[c] = prev[c] = v;
			}
		}
		for (int c = 0; c < WeatherField.CHANNELS; ++c)
		{
			float quantum = c >= WeatherField.GPU_CHANNELS ? 0 : (c == WeatherField.FOG ? WeatherField.FOG_RANGE : 1) / 255f;
			assertTrue(route + ": channel " + c + " steps " + steepest[c] + " of " + (hi[c] - lo[c]),
				steepest[c] <= (hi[c] - lo[c]) / 40 + quantum);
			changing += Math.abs(last[c] - first[c]) > 0.01f ? 1 : 0;
		}
		assertTrue(route + ": only " + changing + " channels differ between its ends", changing >= 6);
	}

	@Test
	public void bordersAreSeamless()
	{
		// Lumbridge into the desert, the desert into the tropics, Morytania out of Varrock's grassland, Rellekka up
		// to Weiss, and the Wilderness' edge
		assertSeamless("Lumbridge to Nardah", 3222, 3218, 3420, 2890);
		assertSeamless("Lumbridge to Al Kharid", 3200, 3190, 3340, 3190);
		assertSeamless("Karamja to the desert", 2900, 3000, 3300, 3000);
		assertSeamless("Varrock to Canifis", 3213, 3428, 3495, 3485);
		assertSeamless("Rellekka to Weiss", 2660, 3560, 2850, 3940);
		assertSeamless("Edgeville into the Wilderness", 3093, 3400, 3093, 3700);
		assertSeamless("Hosidius to Lovakengj", 1745, 3510, 1500, 3800);
	}

	@Test
	public void noSnowInTheDesertOrTheTropics()
	{
		// in the middle of winter, at night, over the whole of the south and east
		WeatherField f = field(2400, 2500);
		boolean hot = false;
		Set<String> hotNames = new HashSet<>(Arrays.asList(HOT));
		for (int z = 0; z < SPAN; ++z)
		{
			for (int x = 0; x < SPAN; ++x)
			{
				String b = f.biome(x, z);
				if (hotNames.contains(b))
				{
					hot = true;
					assertEquals(b + " snow cover", 0, f.cell(WeatherField.SNOW_COVER, x, z), 0);
					assertFalse(b + " " + f.type(x, z), f.type(x, z).whitens());
				}
			}
		}
		assertTrue(hot);
	}
}
