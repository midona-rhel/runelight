package com.midonarhel.runelight;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Constants;

/**
 * The climate map of the world: which biome (weather_climate.json) every tile of the surface has, from
 * weather_regions.json. The file is a stack of layers, lowest first; a tile takes the biome of the highest layer
 * that holds it, either through a rectangle of tiles or through one of 117 HD's areas (environments.json) that the
 * tile lies in, and the default biome where no layer does. Underground (y at the mainland's top and above) only
 * rectangles count: areas there are caves, and a tile no layer holds has no biome (no sky, no weather).
 *
 * This is the one place that decides where it snows, rains or sands: the weather field, the forecast, the ground
 * and the lighting of the open air all ask it.
 */
final class WeatherRegions
{
	/** As in weather_regions.json. */
	static final class Layer
	{
		String biome;
		// 117 HD area names, as in environments.json
		List<String> areas = java.util.Collections.emptyList();
		// inclusive tile rectangles: minX, minY, maxX, maxY
		List<int[]> rects = java.util.Collections.emptyList();
	}

	static final class Data
	{
		String defaultBiome = "GRASSLAND";
		// biome -> the biome whose lighting it shares (environments.json has presets for the first ten only)
		Map<String, String> looks = new HashMap<>();
		List<Layer> layers = java.util.Collections.emptyList();
	}

	private static volatile WeatherRegions installed = new WeatherRegions(new Data());

	private final String defaultBiome;
	private final Map<String, String> looks;
	private final String[] biomes;
	private final int[][][] rects;
	// area name -> index of the highest layer that lists it
	private final Map<String, Integer> areaLayer = new HashMap<>();

	private WeatherRegions(Data d)
	{
		defaultBiome = d.defaultBiome;
		looks = d.looks != null ? d.looks : new HashMap<>();
		int n = d.layers != null ? d.layers.size() : 0;
		biomes = new String[n];
		rects = new int[n][][];
		for (int i = 0; i < n; ++i)
		{
			Layer l = d.layers.get(i);
			biomes[i] = l.biome;
			rects[i] = l.rects.toArray(new int[0][]);
			for (String a : l.areas)
			{
				areaLayer.put(a, i);
			}
		}
	}

	static void install(Data d)
	{
		installed = new WeatherRegions(d != null ? d : new Data());
	}

	static WeatherRegions get()
	{
		return installed;
	}

	/**
	 * The biome of a world tile. {@code areas} are the names of the 117 HD areas the tile lies in (surface areas
	 * only count on the surface). Null where there is no sky.
	 */
	String biomeAt(int x, int y, Collection<String> areas)
	{
		boolean surface = y < Constants.OVERWORLD_MAX_Y;
		int best = -1;
		if (surface)
		{
			for (String a : areas)
			{
				Integer i = areaLayer.get(a);
				if (i != null && i > best)
				{
					best = i;
				}
			}
		}
		for (int i = biomes.length - 1; i > best; --i)
		{
			for (int[] r : rects[i])
			{
				if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3])
				{
					return biomes[i];
				}
			}
		}
		return best >= 0 ? biomes[best] : surface ? defaultBiome : null;
	}

	/** The biome whose lighting a biome shares: itself unless the file says otherwise. */
	String look(String biome)
	{
		return looks.getOrDefault(biome, biome);
	}

	String getDefaultBiome()
	{
		return defaultBiome;
	}
}
