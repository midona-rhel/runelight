package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.*;

import java.util.ArrayList;
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
public final class WeatherRegions
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

	/** A city: its land's climate, with haze on some of its clear days (Climate). */
	static final class City
	{
		String name;
		List<int[]> rects = java.util.Collections.emptyList();
	}

	static final class Data
	{
		String defaultBiome = "GRASSLAND";
		// biome -> the biome whose lighting it shares (environments.json has presets for the first ten only)
		Map<String, String> looks = new HashMap<>();
		List<Layer> layers = java.util.Collections.emptyList();
		List<City> cities = java.util.Collections.emptyList();
	}

	/** Prefix of a city's biome name: "CITY:GRASSLAND" is a city in grassland. */
	public static final String CITY = "CITY:";

	/**
	 * The surface as a raster of chunks (weather_biomes.bin.gz, from the game's terrain by WeatherRegionsRaster): sea
	 * only where the sea is, split by the climate of its nearest coast, every land chunk a biome, cities marked. Where
	 * it reaches it decides; the layers cover the rest (underground, instances).
	 */
	static final class Raster
	{
		final int x0, y0, w, h, chunk;
		final String[] names;
		final byte[] cells;

		Raster(java.io.InputStream gz) throws java.io.IOException
		{
			try (java.io.DataInputStream in = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(gz)))
			{
				if (in.readInt() != 1)
				{
					throw new java.io.IOException("unknown raster version");
				}
				x0 = in.readInt();
				y0 = in.readInt();
				w = in.readInt();
				h = in.readInt();
				chunk = in.readInt();
				names = new String[in.readInt()];
				for (int i = 0; i < names.length; ++i)
				{
					names[i] = in.readUTF();
				}
				cells = new byte[w * h];
				in.readFully(cells);
			}
		}

		/** The biome of a world tile, null outside the raster. */
		String at(int x, int y)
		{
			int cx = Math.floorDiv(x - x0, chunk), cy = Math.floorDiv(y - y0, chunk);
			return cx < 0 || cy < 0 || cx >= w || cy >= h ? null : names[cells[cy * w + cx] & 255];
		}
	}

	private static volatile WeatherRegions installed = new WeatherRegions(new Data(), null);

	private final Data data;
	private final Raster raster;

	private final String defaultBiome;
	private final Map<String, String> looks;
	private final String[] biomes;
	private final int[][][] rects;
	private final int[][] cityRects;
	// area name -> index of the highest layer that lists it
	private final Map<String, Integer> areaLayer = new HashMap<>();

	private WeatherRegions(Data d, Raster raster)
	{
		data = d;
		this.raster = raster;
		defaultBiome = d.defaultBiome;
		looks = d.looks != null ? d.looks : new HashMap<>();
		List<int[]> cr = new ArrayList<>();
		if (d.cities != null)
		{
			for (City c : d.cities)
			{
				cr.addAll(c.rects);
			}
		}
		cityRects = cr.toArray(new int[0][]);
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

	static void install(Data d, Raster raster)
	{
		installed = new WeatherRegions(d != null ? d : new Data(), raster);
	}

	/** The layers alone, without the raster: what WeatherRegionsRaster builds the raster from. */
	static void withoutRaster()
	{
		installed = new WeatherRegions(installed.data, null);
	}

	static Raster raster()
	{
		return installed.raster;
	}

	public static WeatherRegions get()
	{
		return installed;
	}

	/**
	 * The biome of a world tile. {@code areas} are the names of the 117 HD areas the tile lies in (surface areas
	 * only count on the surface). Null where there is no sky.
	 */
	public String biomeAt(int x, int y, Collection<String> areas)
	{
		if (raster != null && y < Constants.OVERWORLD_MAX_Y)
		{
			String r = raster.at(x, y);
			if (r != null)
			{
				return r;
			}
		}
		String b = landBiomeAt(x, y, areas);
		if (b != null)
		{
			for (int[] r : cityRects)
			{
				if (x >= r[0] && x <= r[2] && y >= r[1] && y <= r[3])
				{
					return CITY + b;
				}
			}
		}
		return b;
	}

	/** {@link #biomeAt} without the cities. */
	private String landBiomeAt(int x, int y, Collection<String> areas)
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
	public String look(String biome)
	{
		String c = climate(biome);
		return looks.getOrDefault(c, c);
	}

	/** The climate a biome name stands for: a city's is its land's. */
	public static String climate(String biome)
	{
		return biome != null && biome.startsWith(CITY) ? biome.substring(CITY.length()) : biome;
	}

	public static boolean isCity(String biome)
	{
		return biome != null && biome.startsWith(CITY);
	}

	public String getDefaultBiome()
	{
		return defaultBiome;
	}
}
