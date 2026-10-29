package com.midonarhel.lumina.weather;

import com.google.gson.Gson;
import com.midonarhel.lumina.scene.areas.AreaManager;
import java.io.IOException;
import org.junit.BeforeClass;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

/**
 * climate_regions.json on the real areas and environments: the file loads (every area name exists), names only
 * climates that exist, and a few landmarks land in the climate the map says; Lumbridge never snows.
 */
public class ClimateMapTest
{
	private static ClimateMap map;
	private static Climate.Table climates;
	private static Forecast forecast;

	@BeforeClass
	public static void load() throws IOException
	{
		Gson gson = new Gson();
		AreaManager.load(gson);
		map = ClimateMap.load(gson);
		climates = Climate.Table.load(gson);
		forecast = new Forecast(Pattern.Table.load(gson));
	}

	@Test
	public void everyLayerNamesAKnownClimate()
	{
		for (String id : map.climateIds())
		{
			assertNotNull("climate_regions.json names " + id + ", which climates.json lacks", climates.get(id));
		}
	}

	@Test
	public void landmarks()
	{
		assertEquals("TEMPERATE", map.climateAt(3222, 3218, 0)); // Lumbridge castle
		assertEquals("WETLANDS", map.climateAt(3200, 3170, 0)); // Lumbridge swamp
		assertEquals("TEMPERATE", map.climateAt(3212, 3428, 0)); // Varrock square
		assertEquals("TEMPERATE", map.climateAt(2662, 3305, 0)); // Ardougne market
		assertEquals("DESERT", map.climateAt(3300, 2900, 0)); // deep desert
		assertEquals("DESERT", map.climateAt(3293, 3180, 0)); // Al Kharid
		assertEquals("WILDERNESS", map.climateAt(3100, 3700, 0));
		assertEquals("ALPINE", map.climateAt(2330, 3800, 0)); // Neitiznot
		assertEquals("ALPINE", map.climateAt(2900, 3600, 0)); // Trollheim
		assertEquals("COOL_TEMPERATE", map.climateAt(2645, 3680, 0)); // Rellekka
		assertEquals("COOL_TEMPERATE", map.climateAt(2900, 3550, 0)); // Burthorpe
		assertEquals("MORYTANIA", map.climateAt(3490, 3490, 0)); // Canifis
		assertEquals("TROPICAL", map.climateAt(2760, 3180, 0)); // Brimhaven
		assertEquals("VOLCANIC", map.climateAt(2840, 3170, 0)); // Karamja volcano
		assertEquals("ARCEUUS", map.climateAt(1680, 3750, 0));
		assertEquals("KOUREND_TEMPERATE", map.climateAt(1500, 3620, 0)); // Shayzien
		assertEquals("TEMPERATE_RAINFOREST", map.climateAt(2460, 3440, 0)); // Gnome Stronghold
		assertEquals("MEDITERRANEAN", map.climateAt(1700, 3100, 0)); // Civitas illa Fortis
		assertEquals("AUTUMN_FOREST", map.climateAt(1400, 3340, 0)); // Auburnvale
		assertEquals(ClimateMap.NO_SKY, map.climateAt(3200, 9880, 0)); // Varrock sewers
		assertEquals(ClimateMap.NO_SKY, map.climateAt(2400, 4420, 0)); // Zanaris
	}

	@Test
	public void lumbridgeNeverSnows()
	{
		Climate lumbridge = climates.get(map.climateAt(3222, 3218, 0));
		for (long slot = 0; slot < 1000; slot++)
		{
			Forecast.Slot s = forecast.at(slot, lumbridge);
			assertNotEquals("slot " + slot + ": " + s, Pattern.Kind.SNOW, s.pattern.kind);
			assertNotEquals("slot " + slot + ": " + s, Pattern.Kind.SNOW, s.after.kind);
		}
	}
}
