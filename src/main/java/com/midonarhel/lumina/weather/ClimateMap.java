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

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import com.midonarhel.lumina.scene.areas.Area;
import com.midonarhel.lumina.scene.areas.AreaManager;
import com.midonarhel.lumina.scene.environment.Environment;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * World tile to climate id. Underground first: the tile's 117 HD environment (the first of environments.json whose
 * area contains it, conditions ignored, as the environment manager picks it) decides; where it is not overworld the
 * tile has no sky and no climate, {@link #NO_SKY}. Otherwise {@code weather/climate_regions.json}: a stack of
 * layers of 117 HD areas and tile rects, the last layer containing the tile wins, the file's default where none
 * does. {@link AreaManager} must be loaded first (the areas are resolved by name).
 */
public final class ClimateMap
{
	public static final String RESOURCE = "/com/midonarhel/lumina/weather/climate_regions.json";
	/** The "climate" of a tile without a sky. Not a climates.json id. */
	public static final String NO_SKY = "NO_SKY";

	private static final class File
	{
		@SerializedName("default")
		String defaultClimate;
		Layer[] layers;
	}

	private static final class Layer
	{
		String climate;
		String[] areas;
		int[][] rects;
		String note;
		transient Area[] resolved = {};

		boolean contains(int x, int y, int plane)
		{
			for (Area a : resolved)
			{
				if (a.containsPoint(x, y, plane))
				{
					return true;
				}
			}
			if (rects != null)
			{
				for (int[] r : rects)
				{
					if (r[0] <= x && x <= r[2] && r[1] <= y && y <= r[3])
					{
						return true;
					}
				}
			}
			return false;
		}
	}

	private final Environment[] environments;
	private final Layer[] layers;
	private final String defaultClimate;

	private ClimateMap(Environment[] environments, Layer[] layers, String defaultClimate)
	{
		this.environments = environments;
		this.layers = layers;
		this.defaultClimate = defaultClimate;
	}

	/** Loads environments.json (for underground) and climate_regions.json; every area name must exist. */
	public static ClimateMap load(Gson gson) throws IOException
	{
		Environment[] environments = GsonUtils.loadResource(gson, "environments.json", Environment[].class);
		for (Environment env : environments)
		{
			env.normalize();
		}
		File file = GsonUtils.loadClasspath(gson, RESOURCE, File.class);
		if (file.defaultClimate == null || file.layers == null)
		{
			throw new IOException("climate_regions.json: needs \"default\" and \"layers\"");
		}
		for (int i = 0; i < file.layers.length; i++)
		{
			Layer layer = file.layers[i];
			if (layer == null || layer.climate == null)
			{
				throw new IOException("climate_regions.json: layer " + i + " without a climate");
			}
			if (layer.areas != null)
			{
				layer.resolved = new Area[layer.areas.length];
				for (int j = 0; j < layer.areas.length; j++)
				{
					Area area = AreaManager.getArea(layer.areas[j]);
					if (area == Area.NONE)
					{
						throw new IOException("climate_regions.json: layer " + i + " (" + layer.climate + "): no area named " + layer.areas[j]);
					}
					layer.resolved[j] = area;
				}
			}
			if (layer.rects != null)
			{
				for (int[] r : layer.rects)
				{
					if (r == null || r.length != 4)
					{
						throw new IOException("climate_regions.json: layer " + i + " (" + layer.climate + "): a rect is [x1, y1, x2, y2]");
					}
					if (r[0] > r[2] || r[1] > r[3])
					{
						throw new IOException("climate_regions.json: layer " + i + " (" + layer.climate + "): rect corners out of order");
					}
				}
			}
			if (layer.resolved.length == 0 && (layer.rects == null || layer.rects.length == 0))
			{
				throw new IOException("climate_regions.json: layer " + i + " (" + layer.climate + ") has no areas and no rects");
			}
		}
		return new ClimateMap(environments, file.layers, file.defaultClimate);
	}

	/** The environment at a tile as the environment manager would pick it, conditions ignored; DEFAULT where none. */
	public Environment environmentAt(int x, int y, int plane)
	{
		for (Environment env : environments)
		{
			if (env.area.containsPoint(x, y, plane))
			{
				return env;
			}
		}
		return Environment.DEFAULT;
	}

	public boolean hasSky(int x, int y, int plane)
	{
		return environmentAt(x, y, plane).isOverworld;
	}

	/** The climate id at a tile, {@link #NO_SKY} underground. */
	public String climateAt(int x, int y, int plane)
	{
		if (!hasSky(x, y, plane))
		{
			return NO_SKY;
		}
		for (int i = layers.length - 1; i >= 0; i--)
		{
			if (layers[i].contains(x, y, plane))
			{
				return layers[i].climate;
			}
		}
		return defaultClimate;
	}

	/** Every climate id the file names, the default included, for checking against the climates table. */
	public Set<String> climateIds()
	{
		Set<String> ids = new LinkedHashSet<>();
		ids.add(defaultClimate);
		for (Layer layer : layers)
		{
			ids.add(layer.climate);
		}
		return ids;
	}
}
