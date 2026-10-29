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
import com.midonarhel.lumina.sky.SkyModifiers;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;

/**
 * A weather pattern from {@code weather/patterns.json}: what the sky, the fog, the air and the ground do while it
 * lasts. One per {@link Kind}; the forecast picks a kind per slot, the climate says how often. The fields are the
 * file's; {@link Table#load} checks them.
 */
public final class Pattern
{
	public enum Kind
	{
		CLEAR, PARTLY_CLOUDY, OVERCAST, RAIN, STORM, FOG, SNOW, SANDSTORM, ASH_FALL
	}

	/** Where the distance fog takes its colour from. The sky owns the colour; these are the only exceptions. */
	public enum FogColour
	{
		/** The sky's horizon, as a clear sky. */
		SKY,
		/** The horizon desaturated by {@link Fog#desaturate} and darkened by {@link Fog#darken} (rain). */
		GREY,
		/** White-grey (fog, snow). */
		WHITE,
		/** {@link Fog#tint} times the horizon's luma: orange sand. */
		SAND,
		/** {@link Fog#tint} times the horizon's luma: grey-brown ash. */
		ASH
	}

	public enum Ground
	{
		NONE, WET, SNOW_COVER, DUST, ASH
	}

	public static final class Fog
	{
		public FogColour colour = FogColour.SKY;
		public float desaturate;
		public float darken;
		public float[] tint;
		/** Multiplier on the climate's baseline fog density. */
		public float density = 1;
	}

	public static final class Precipitation
	{
		public enum Kind
		{
			NONE, RAIN, SNOW, SAND, ASH
		}

		public Kind kind = Kind.NONE;
		/** [min, max] of the intensity drawn per slot, 0 to 1. */
		public float[] intensity = {0, 0};

		public boolean falls()
		{
			return kind != Kind.NONE;
		}
	}

	public Kind kind;
	/** The sky modifiers at the pattern's full strength. */
	public SkyModifiers sky;
	public Fog fog;
	public Precipitation precipitation;
	/** [min, max] of the wind drawn per slot, 0 still to 1 gale. */
	public float[] wind;
	public Ground ground;
	/** The pattern's weight to follow itself in the next slot; 0 never follows itself. */
	public float persistence;
	/** [min, max] game hours the pattern lasts from the slot's start; null is the whole slot. */
	public int[] hours;

	void validate() throws IOException
	{
		if (kind == null)
		{
			throw new IOException("patterns.json: entry without a kind");
		}
		if (sky == null || fog == null || precipitation == null || ground == null)
		{
			throw new IOException("patterns.json: " + kind + " lacks sky, fog, precipitation or ground");
		}
		range(wind, "wind");
		range(precipitation.intensity, "precipitation.intensity");
		if ((fog.colour == FogColour.SAND || fog.colour == FogColour.ASH) && (fog.tint == null || fog.tint.length != 3))
		{
			throw new IOException("patterns.json: " + kind + " fog colour " + fog.colour + " needs a 3-number tint");
		}
		if (fog.density <= 0)
		{
			throw new IOException("patterns.json: " + kind + " fog density must be above 0");
		}
		if (persistence < 0)
		{
			throw new IOException("patterns.json: " + kind + " persistence must not be negative");
		}
		if (hours != null && (hours.length != 2 || hours[0] < 1 || hours[1] < hours[0] || hours[1] > Forecast.SLOT_HOURS))
		{
			throw new IOException("patterns.json: " + kind + " hours must be [min, max] within 1 to " + Forecast.SLOT_HOURS);
		}
	}

	private void range(float[] r, String name) throws IOException
	{
		if (r == null || r.length != 2 || r[0] < 0 || r[1] < r[0] || r[1] > 1)
		{
			throw new IOException("patterns.json: " + kind + " " + name + " must be [min, max] within 0 to 1");
		}
	}

	@Override
	public String toString()
	{
		return kind.name();
	}

	/** The patterns of patterns.json, one per kind. */
	public static final class Table
	{
		public static final String RESOURCE = "/com/midonarhel/lumina/weather/patterns.json";

		private final Pattern[] byKind = new Pattern[Kind.values().length];

		public static Table load(Gson gson) throws IOException
		{
			Pattern[] entries = GsonUtils.loadClasspath(gson, RESOURCE, Pattern[].class);
			Table table = new Table();
			for (Pattern p : entries)
			{
				if (p == null)
				{
					throw new IOException("patterns.json: null entry");
				}
				p.validate();
				if (table.byKind[p.kind.ordinal()] != null)
				{
					throw new IOException("patterns.json: duplicate " + p.kind);
				}
				table.byKind[p.kind.ordinal()] = p;
			}
			for (Kind k : Kind.values())
			{
				if (table.byKind[k.ordinal()] == null)
				{
					throw new IOException("patterns.json: no entry for " + k);
				}
			}
			return table;
		}

		public Pattern get(Kind kind)
		{
			return byKind[kind.ordinal()];
		}

		public Pattern[] all()
		{
			return byKind.clone();
		}
	}
}
