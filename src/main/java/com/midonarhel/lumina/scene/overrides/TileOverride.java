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

import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.midonarhel.lumina.scene.areas.Area;
import com.midonarhel.lumina.scene.areas.AreaManager;
import com.midonarhel.lumina.util.ExpressionParser;
import com.midonarhel.lumina.util.ExpressionPredicate;
import com.midonarhel.lumina.util.VariableSupplier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import static com.midonarhel.lumina.util.ExpressionParser.asExpression;
import static com.midonarhel.lumina.util.ExpressionParser.parseExpression;

/**
 * One entry of tile_overrides.json: matches tiles by area and overlay/underlay ID and changes their colour and blending.
 * Only the colour and blending fields are ported; ground materials, UVs, height offsets and double sided faces are
 * ignored, and the water type is only kept as "is water".
 */
@Slf4j
@NoArgsConstructor
public class TileOverride
{
	public static final int OVERLAY_FLAG = 1 << 31;
	public static final TileOverride NONE = new TileOverride("NONE");

	@Nullable
	public String name;
	public String description;
	@JsonAdapter(AreaManager.Adapter.class)
	public Area area = Area.NONE;
	public int[] overlayIds;
	public int[] underlayIds;
	/**
	 * 117 HD's water type name. Lumina draws no water, but water tiles are kept out of ground colour blending as in 117 HD.
	 */
	@Nullable
	public String waterType;
	public boolean blended = true;
	public boolean blendedAsOpposite;
	public boolean forced;
	public boolean doubleSidedFaces;
	public int heightOffset;
	public int uvOrientation;
	public float uvScale = 1;
	private int setHue = -1;
	private int shiftHue;
	private int minHue;
	private int maxHue = 63;
	private int setSaturation = -1;
	private int shiftSaturation;
	private int minSaturation;
	private int maxSaturation = 7;
	private int setLightness = -1;
	private int shiftLightness;
	private int minLightness;
	private int maxLightness = 127;
	@SerializedName("replacements")
	public LinkedHashMap<String, JsonElement> rawReplacements;

	public transient int index;
	public transient int[] ids;
	public transient boolean queriedAsOverlay;
	public transient TileOverride replacement;
	@Nonnull
	private transient List<Map.Entry<ExpressionPredicate, TileOverride>> replacements = Collections.emptyList();

	private TileOverride(@Nullable String name)
	{
		this.name = name;
		this.index = Integer.MAX_VALUE; // Prioritize any-match overrides over this
	}

	@Override
	public String toString()
	{
		if (name != null)
		{
			return name;
		}
		if (description != null)
		{
			return description;
		}
		if (area != null)
		{
			return area.name;
		}
		return "Unnamed";
	}

	public boolean isWater()
	{
		return waterType != null && !waterType.equals("NONE");
	}

	public boolean isConstant()
	{
		return replacements.isEmpty();
	}

	public void normalize(TileOverride[] allOverrides, VariableSupplier constants)
	{
		int numOverlays = overlayIds == null ? 0 : overlayIds.length;
		int numUnderlays = underlayIds == null ? 0 : underlayIds.length;
		if (numOverlays + numUnderlays > 0)
		{
			ids = new int[numOverlays + numUnderlays];
			int i = 0;
			for (int j = 0; j < numOverlays; j++)
			{
				ids[i++] = OVERLAY_FLAG | overlayIds[j];
			}
			for (int j = 0; j < numUnderlays; j++)
			{
				ids[i++] = underlayIds[j];
			}
		}

		if (area == null)
		{
			area = Area.NONE;
		}

		if (forced)
		{
			// Replace hidden tiles with white by default
			minHue = maxHue = minSaturation = maxSaturation = 0;
			minLightness = maxLightness = 127;
		}

		if (setHue != -1)
		{
			minHue = maxHue = setHue;
		}
		if (setSaturation != -1)
		{
			minSaturation = maxSaturation = setSaturation;
		}
		if (setLightness != -1)
		{
			minLightness = maxLightness = setLightness;
		}

		// Enforce sensible limits
		minHue = clamp(minHue, 0, 0x3F);
		maxHue = clamp(maxHue, 0, 0x3F);
		minSaturation = clamp(minSaturation, 0, 0x7);
		maxSaturation = clamp(maxSaturation, 0, 0x7);
		minLightness = clamp(minLightness, 0, 0x7F);
		maxLightness = clamp(maxLightness, 0, 0x7F);

		if (rawReplacements != null)
		{
			replacements = new ArrayList<>();
			for (var entry : rawReplacements.entrySet())
			{
				var name = entry.getKey();
				TileOverride replacement = null;
				if (name == null || name.equals(NONE.name))
				{
					replacement = NONE;
				}
				else
				{
					for (var other : allOverrides)
					{
						if (name.equals(other.name))
						{
							replacement = other;
							break;
						}
					}
					if (replacement == null)
					{
						log.warn("Unknown tile override replacement '{}' in {}", name, this);
						replacement = NONE;
					}
				}

				if (entry.getValue() == null)
				{
					continue; // removed to break a replacement loop
				}

				var result = parseExpression(ExpressionParser.mergeJsonExpressions("||", entry.getValue()), constants);

				ExpressionPredicate predicate;
				boolean isConstant = result instanceof Boolean;
				if (isConstant)
				{
					if (!(boolean) result)
					{
						continue; // Skip replacement conditions that are always false
					}
					predicate = ExpressionPredicate.TRUE;
				}
				else
				{
					predicate = asExpression(result).toPredicate();
				}

				replacements.add(Map.entry(predicate, replacement));

				// Skip parsing the remaining unnecessary replacements
				if (isConstant)
				{
					break;
				}
			}
		}
	}

	public TileOverride resolveConstantReplacements()
	{
		// Check if the override always resolves to the same replacement override
		var override = this;
		for (int i = 0; i < override.replacements.size(); i++)
		{
			var entry = override.replacements.get(i);
			// Stop on the first non-constant predicate
			if (entry.getKey() != ExpressionPredicate.TRUE)
			{
				break;
			}

			override = entry.getValue();
		}

		return override;
	}

	public TileOverride resolveReplacements(VariableSupplier vars)
	{
		var replacement = resolveNextReplacement(vars);
		if (replacement == this)
		{
			return replacement;
		}
		return replacement.resolveReplacements(vars);
	}

	private TileOverride resolveNextReplacement(VariableSupplier vars)
	{
		for (var entry : replacements)
		{
			if (!entry.getKey().test(vars))
			{
				continue;
			}

			var replacement = entry.getValue();
			if (replacement == null)
			{
				replacement = NONE;
			}

			replacement.queriedAsOverlay = queriedAsOverlay;
			return replacement;
		}

		return this;
	}

	public int modifyColor(int jagexHsl)
	{
		int h = jagexHsl >> 10 & 0x3F;
		h = clamp(h + shiftHue, minHue, maxHue);

		int s = jagexHsl >> 7 & 7;
		s = clamp(s + shiftSaturation, minSaturation, maxSaturation);

		int l = jagexHsl & 0x7F;
		l = clamp(l + shiftLightness, minLightness, maxLightness);

		return h << 10 | s << 7 | l;
	}

	private static int clamp(int v, int min, int max)
	{
		// 117 HD's MathUtils.clamp: min(max(v, min), max), so max wins when min > max
		return Math.min(Math.max(v, min), max);
	}
}
