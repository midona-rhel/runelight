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
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A climate from {@code weather/climates.json}: a real-world analogue with a mean temperature and the share of
 * slots each {@link Pattern.Kind} gets. {@link #odds()} is what the forecast draws from: the shares after the snow
 * line ({@link #SNOW_LINE_C}): at or below it RAIN falls as SNOW, above it SNOW falls as RAIN, so a kind the climate
 * cannot have gets weight 0 and never occurs. STORM is left alone (no climate at or below the line has one).
 */
public final class Climate
{
	/** Mean temperature at or below which precipitation is snow. */
	public static final float SNOW_LINE_C = 2;

	public String id;
	public String analogue;
	/** Mean temperature, C. */
	public float temperature;
	/** Annual precipitation, mm; informational. */
	public float precipitation;
	/** Share of slots per kind as authored; a missing kind is 0. */
	public Map<Pattern.Kind, Float> weights;
	/** The ground is always snow. */
	public boolean permanentSnowCover;
	/** A permanent dimming on top of every pattern (the Wilderness). */
	public boolean alwaysDim;
	/** Added to the drawn precipitation intensity. */
	public float intensityBias;
	public String note;

	private transient float[] odds;

	public boolean snows()
	{
		return temperature <= SNOW_LINE_C;
	}

	/** The authored share of a kind. */
	public float weight(Pattern.Kind kind)
	{
		Float w = weights == null ? null : weights.get(kind);
		return w == null ? 0 : w;
	}

	/** The weights the forecast draws from, indexed by {@link Pattern.Kind#ordinal()}: the shares after the snow line. */
	public float[] odds()
	{
		return odds;
	}

	void normalize() throws IOException
	{
		if (id == null || id.isEmpty())
		{
			throw new IOException("climates.json: entry without an id");
		}
		odds = new float[Pattern.Kind.values().length];
		float total = 0;
		for (Pattern.Kind k : Pattern.Kind.values())
		{
			float w = weight(k);
			if (w < 0)
			{
				throw new IOException("climates.json: " + id + " has a negative weight for " + k);
			}
			odds[k.ordinal()] = w;
			total += w;
		}
		if (total <= 0)
		{
			throw new IOException("climates.json: " + id + " has no weights");
		}
		int rain = Pattern.Kind.RAIN.ordinal(), snow = Pattern.Kind.SNOW.ordinal();
		if (snows())
		{
			odds[snow] += odds[rain];
			odds[rain] = 0;
		}
		else
		{
			odds[rain] += odds[snow];
			odds[snow] = 0;
		}
	}

	@Override
	public String toString()
	{
		return id;
	}

	/** The climates of climates.json by id. */
	public static final class Table
	{
		public static final String RESOURCE = "/com/midonarhel/lumina/weather/climates.json";

		private final Map<String, Climate> byId = new LinkedHashMap<>();

		public static Table load(Gson gson) throws IOException
		{
			Climate[] entries = GsonUtils.loadClasspath(gson, RESOURCE, Climate[].class);
			Table table = new Table();
			for (Climate c : entries)
			{
				if (c == null)
				{
					throw new IOException("climates.json: null entry");
				}
				c.normalize();
				if (ClimateMap.NO_SKY.equals(c.id) || table.byId.put(c.id, c) != null)
				{
					throw new IOException("climates.json: duplicate or reserved id " + c.id);
				}
			}
			return table;
		}

		/** The climate with this id, or null ({@link ClimateMap#NO_SKY} included). */
		public Climate get(String id)
		{
			return byId.get(id);
		}

		public Collection<Climate> all()
		{
			return Collections.unmodifiableCollection(byId.values());
		}
	}
}
