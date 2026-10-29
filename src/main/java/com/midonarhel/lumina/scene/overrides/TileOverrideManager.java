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

import com.google.gson.Gson;
import com.midonarhel.lumina.scene.areas.Area;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nonnull;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Tile;
import static com.midonarhel.lumina.scene.overrides.TileOverride.OVERLAY_FLAG;

/**
 * Loads tile_overrides.json and finds the override for a tile. The first matching override in file order wins.
 */
@Slf4j
@Singleton
public class TileOverrideManager
{
	private static final ThreadLocal<TileOverrideVariables> TILE_OVERRIDE_VARIABLES = ThreadLocal.withInitial(TileOverrideVariables::new);

	/**
	 * All overrides in file order; scene data stores indices into this array.
	 */
	private volatile TileOverride[] overrides = new TileOverride[0];
	private List<TileOverride> anyMatchOverrides = new ArrayList<>();
	private Map<Integer, List<TileOverride>> idMatchOverrides = new HashMap<>();

	public void load(Gson gson) throws IOException
	{
		TileOverride[] allOverrides = GsonUtils.loadResource(gson, "tile_overrides.json", TileOverride[].class);

		HashSet<String> names = new HashSet<>();
		for (var override : allOverrides)
		{
			if (override.name != null && !names.add(override.name))
			{
				log.warn("Removing duplicate tile override name: {}", override.name);
				override.name = null;
			}
		}

		checkForReplacementLoops(allOverrides);

		List<TileOverride> anyMatch = new ArrayList<>();
		Map<Integer, List<TileOverride>> idMatch = new HashMap<>();

		var vars = ShadingVariables.INSTANCE.aliases(Map.of("textures", "groundTextures"));

		for (int i = 0; i < allOverrides.length; i++)
		{
			var override = allOverrides[i];
			try
			{
				override.index = i;
				override.normalize(allOverrides, vars);
			}
			catch (Exception ex)
			{
				log.warn("Skipping invalid tile override '{}':", override.name, ex);
				continue;
			}

			if (override.area == Area.NONE)
			{
				continue;
			}

			override.replacement = override.resolveConstantReplacements();
			if (override.ids != null)
			{
				for (int id : override.ids)
				{
					idMatch.computeIfAbsent(id, k -> new ArrayList<>()).add(override);
				}
			}
			else
			{
				anyMatch.add(override);
			}
		}

		anyMatchOverrides = anyMatch;
		idMatchOverrides = idMatch;
		overrides = allOverrides;

		log.debug("Loaded {} tile overrides", allOverrides.length);
	}

	public TileOverride[] getOverrides()
	{
		return overrides;
	}

	private void checkForReplacementLoops(TileOverride[] allOverrides)
	{
		Map<String, TileOverride> relevantOverrides = new HashMap<>();
		for (var override : allOverrides)
		{
			if (override.name != null && override.rawReplacements != null)
			{
				relevantOverrides.put(override.name, override);
			}
		}

		Set<String> alreadyChecked = new HashSet<>();
		for (var override : relevantOverrides.values())
		{
			checkForReplacementLoops(relevantOverrides, alreadyChecked, override);
		}
	}

	private static void checkForReplacementLoops(Map<String, TileOverride> map, Set<String> alreadyChecked, TileOverride topLevelOverride)
	{
		String name = topLevelOverride.name;
		// Only check each top-level override once
		if (alreadyChecked.add(name))
		{
			checkForReplacementLoops(map, alreadyChecked, new ArrayDeque<>(), name, topLevelOverride);
		}
	}

	private static void checkForReplacementLoops(
		Map<String, TileOverride> map,
		Set<String> alreadyChecked,
		ArrayDeque<String> loop,
		String topLevelOverrideName,
		TileOverride overrideToCheck
	)
	{
		loop.addLast(overrideToCheck.name);

		for (String replacementName : overrideToCheck.rawReplacements.keySet())
		{
			// Check if the replacement introduces a loop
			if (topLevelOverrideName.equals(replacementName))
			{
				log.warn("Tile override contains replacement loop: {} -> {}", String.join(" -> ", loop), replacementName);
				// Remove the loop
				overrideToCheck.rawReplacements.put(replacementName, null);
				continue;
			}

			var replacement = map.get(replacementName);
			if (replacement == null)
			{
				continue;
			}

			// Rule out loops within the replacement itself first, so we don't get stuck there
			checkForReplacementLoops(map, alreadyChecked, replacement);

			// The replacement might've already been removed to prevent a loop in the step above
			if (overrideToCheck.rawReplacements.get(replacementName) == null)
			{
				continue;
			}

			// Check if any further replacements result in a loop
			checkForReplacementLoops(map, alreadyChecked, loop, topLevelOverrideName, replacement);
		}

		loop.removeLast();
	}

	/**
	 * The override for a tile at a world position, matched against the given overlay/underlay IDs (overlay IDs carry
	 * {@link TileOverride#OVERLAY_FLAG}), after resolving replacements.
	 */
	@Nonnull
	public TileOverride getOverride(@Nonnull Tile tile, @Nonnull int[] worldPos, int... ids)
	{
		final TileOverride override = getOverrideBeforeReplacements(worldPos, ids);
		if (override.isConstant())
		{
			return override;
		}

		final var vars = TILE_OVERRIDE_VARIABLES.get();
		vars.setTile(tile);
		TileOverride replacement = override.resolveReplacements(vars);
		vars.setTile(null); // Avoid accidentally keeping the old scene in memory
		return replacement;
	}

	@Nonnull
	private TileOverride getOverrideBeforeReplacements(@Nonnull int[] worldPos, int... ids)
	{
		var match = TileOverride.NONE;
		int index = match.index;

		outer:
		for (final int id : ids)
		{
			final var entries = idMatchOverrides.get(id);
			if (entries == null)
			{
				continue;
			}
			for (final var entry : entries)
			{
				if (entry.area.containsPoint(worldPos))
				{
					index = entry.index;
					match = entry.replacement;
					match.queriedAsOverlay = (id & OVERLAY_FLAG) != 0;
					break outer;
				}
			}
		}

		for (final var entry : anyMatchOverrides)
		{
			if (entry.index > index)
			{
				break;
			}
			if (entry.area.containsPoint(worldPos))
			{
				match = entry.replacement;
				break;
			}
		}

		return match;
	}
}
