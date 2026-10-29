/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.scene.areas;

import com.google.gson.Gson;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.util.Arrays;
import javax.annotation.Nonnull;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads areas.json. The areas are kept in a static array, since the other data files refer to areas by name while
 * they are parsed (see {@link Adapter} and {@link AABB.ArrayAdapter}), so areas must be loaded first.
 */
@Slf4j
public final class AreaManager
{
	public static Area[] AREAS = new Area[0];
	// 117 HD's areasWithAreaHiding: the areas with hideOtherAreas, in file order
	public static Area[] AREAS_WITH_AREA_HIDING = new Area[0];

	private AreaManager()
	{
	}

	public static void load(Gson gson) throws IOException
	{
		Area[] areas = GsonUtils.loadResource(gson, "areas.json", Area[].class);
		Area[] all = Arrays.copyOf(areas, areas.length + 2);
		all[all.length - 2] = Area.ALL;
		all[all.length - 1] = Area.NONE;
		AREAS = all;

		var withAreaHiding = new java.util.ArrayList<Area>();
		for (Area area : areas)
		{
			area.normalize();
			if (area.hideOtherAreas)
			{
				withAreaHiding.add(area);
			}
		}
		AREAS_WITH_AREA_HIDING = withAreaHiding.toArray(Area[]::new);

		Area.OVERWORLD = getArea("OVERWORLD");
		log.debug("Loaded {} areas", areas.length);
	}

	@Nonnull
	public static Area getArea(String name)
	{
		for (Area area : AREAS)
		{
			if (name.equals(area.name))
			{
				return area;
			}
		}
		return Area.NONE;
	}

	/**
	 * Maps an area name to an already loaded area.
	 */
	public static class Adapter extends TypeAdapter<Area>
	{
		@Override
		public Area read(JsonReader in) throws IOException
		{
			var token = in.peek();
			if (token == JsonToken.NULL)
			{
				in.nextNull();
				return null;
			}

			if (token != JsonToken.STRING)
			{
				log.warn("Expected an area name instead of {} at {}", token, GsonUtils.location(in));
				in.skipValue();
				return Area.NONE;
			}

			var str = in.nextString();
			for (Area area : AREAS)
			{
				if (str.equals(area.name))
				{
					return area;
				}
			}

			log.warn("No area exists with the name '{}' at {}", str, GsonUtils.location(in));
			return Area.NONE;
		}

		@Override
		public void write(JsonWriter out, Area area)
		{
			throw new UnsupportedOperationException();
		}
	}
}
