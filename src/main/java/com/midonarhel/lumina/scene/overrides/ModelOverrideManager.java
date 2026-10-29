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
package com.midonarhel.lumina.scene.overrides;

import com.midonarhel.lumina.config.HdSettings;
import com.google.gson.Gson;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads model_overrides.json and looks overrides up by kind and ID, resolving area-restricted overrides by world
 * position. 117 HD's fishing spot replacer and sailing/detail culling overrides are not ported.
 */
@Slf4j
@Singleton
public class ModelOverrideManager
{
	public static final int TYPE_NPC = 0;
	public static final int TYPE_OBJECT = 1;
	public static final int TYPE_PROJECTILE = 2;
	public static final int TYPE_GRAPHICS_OBJECT = 3;
	private static final int TYPE_COUNT = 4;

	@SuppressWarnings("unchecked")
	private volatile Map<Integer, ModelOverride>[] overrides = new Map[]{Map.of(), Map.of(), Map.of(), Map.of()};

	@SuppressWarnings("unchecked")
	public void load(Gson gson) throws IOException
	{
		ModelOverride[] parsed = GsonUtils.loadResource(gson, "model_overrides.json", ModelOverride[].class);

		Map<Integer, ModelOverride>[] maps = new Map[TYPE_COUNT];
		for (int i = 0; i < TYPE_COUNT; i++)
		{
			maps[i] = new HashMap<>();
		}

		int count = 0;
		for (ModelOverride override : parsed)
		{
			// Ignore trailing commas in the JSON
			if (override == null)
			{
				continue;
			}
			try
			{
				override.normalize();
			}
			catch (Exception ex)
			{
				log.error("Invalid model override '{}':", override.description, ex);
				continue;
			}

			addOverride(maps, override);
			count++;

			if (override.hideInAreas.length > 0)
			{
				var hider = override.copy();
				hider.hide = true;
				hider.areas = override.hideInAreas;
				addOverride(maps, hider);
			}
		}

		overrides = maps;
		log.debug("Loaded {} model overrides", count);
	}

	private static void addOverride(Map<Integer, ModelOverride>[] maps, ModelOverride override)
	{
		if (override.seasonalTheme != null && override.seasonalTheme != HdSettings.seasonalTheme)
		{
			return;
		}

		for (int id : override.npcIds)
		{
			addEntry(maps[TYPE_NPC], id, override);
		}
		for (int id : override.objectIds)
		{
			addEntry(maps[TYPE_OBJECT], id, override);
		}
		for (int id : override.projectileIds)
		{
			addEntry(maps[TYPE_PROJECTILE], id, override);
		}
		for (int id : override.graphicsObjectIds)
		{
			addEntry(maps[TYPE_GRAPHICS_OBJECT], id, override);
		}
	}

	private static void addEntry(Map<Integer, ModelOverride> map, int id, ModelOverride entry)
	{
		ModelOverride current = map.get(id);

		if (current != null && !Objects.equals(current.seasonalTheme, entry.seasonalTheme))
		{
			// Seasonal theme overrides should take precedence
			if (current.seasonalTheme != null)
			{
				return;
			}
			current = null;
		}

		if (entry.areas.length == 0)
		{
			// Non-area-restricted override, of which there can only be one per ID; a later one replaces an earlier one
			if (current != null && current.areaOverrides != null)
			{
				var replacement = entry.copy();
				replacement.areaOverrideBounds = current.areaOverrideBounds;
				replacement.areaOverrides = current.areaOverrides;
				current = replacement;
			}
			else
			{
				current = entry;
			}

			map.put(id, current);
		}
		else
		{
			if (current == null)
			{
				current = ModelOverride.NONE;
			}

			if (current.areaOverrides == null)
			{
				// Create a copy to avoid aliasing area overrides with other IDs
				current = current.copy();
				map.put(id, current);
			}

			int size = entry.areas.length;
			if (current.areaOverrides == null)
			{
				current.areaOverrides = new ModelOverride[size];
				Arrays.fill(current.areaOverrides, entry);
				current.areaOverrideBounds = entry.areas;
			}
			else
			{
				int offset = current.areaOverrides.length;
				current.areaOverrides = Arrays.copyOf(current.areaOverrides, offset + size);
				Arrays.fill(current.areaOverrides, offset, offset + size, entry);
				current.areaOverrideBounds = Arrays.copyOf(current.areaOverrideBounds, offset + size);
				System.arraycopy(entry.areas, 0, current.areaOverrideBounds, offset, size);
			}
		}
	}

	/**
	 * The override for an ID, before resolving area-restricted overrides; check {@link ModelOverride#areaOverrides}
	 * and call {@link #resolveArea} when it is set.
	 */
	@Nonnull
	public ModelOverride getOverride(int type, int id)
	{
		var override = overrides[type].get(id);
		return override == null ? ModelOverride.NONE : override;
	}

	@Nonnull
	public static ModelOverride resolveArea(@Nonnull ModelOverride override, @Nullable int[] worldPos)
	{
		if (override.areaOverrides != null && worldPos != null)
		{
			for (int i = 0; i < override.areaOverrides.length; i++)
			{
				if (override.areaOverrideBounds[i].contains(worldPos))
				{
					return override.areaOverrides[i];
				}
			}
		}
		return override;
	}

	@Nonnull
	public ModelOverride getOverride(int type, int id, @Nullable int[] worldPos)
	{
		return resolveArea(getOverride(type, id), worldPos);
	}
}
