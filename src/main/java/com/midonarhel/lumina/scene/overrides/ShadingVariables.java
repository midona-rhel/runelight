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
import com.midonarhel.lumina.config.InfernalCape;
import com.midonarhel.lumina.util.VariableSupplier;

/**
 * The constants 117 HD's data file expressions may refer to (117 HD's HDVariables): no 117 HD textures (the
 * user's objectTextures and groundTextures off), ground colour blending off (never blended in Lumina), and the
 * settings of {@link HdSettings}.
 */
public final class ShadingVariables implements VariableSupplier
{
	public static final ShadingVariables INSTANCE = new ShadingVariables();

	private ShadingVariables()
	{
	}

	@Override
	public Object get(String name)
	{
		switch (name)
		{
			case "season":
				return HdSettings.seasonalTheme.ordinal();
			case "seasonalFoliage":
				return HdSettings.seasonalFoliage;
			case "hdInfernalCape":
				return HdSettings.infernalCape == InfernalCape.HD;
			case "legacyInfernalCape":
				return HdSettings.infernalCape == InfernalCape.LEGACY;
			case "modelTextures":
			case "groundTextures":
			case "blendingColorsOnly":
			case "blendingTexturesOnly":
				return false;
			case "blendingColors":
			case "blendingTextures":
			case "blending":
			case "blendingBoth":
				// Lumina never blends ground colours or textures (117 HD with ground blending off)
				return false;
		}

		String prefix = SeasonalTheme.class.getSimpleName() + ".";
		if (name.startsWith(prefix))
		{
			String key = name.substring(prefix.length());
			for (SeasonalTheme theme : SeasonalTheme.values())
			{
				if (theme.name().equals(key))
				{
					return theme.ordinal();
				}
			}
		}

		return null;
	}
}
