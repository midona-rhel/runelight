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
package com.midonarhel.lumina.config;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;

/**
 * The cached 117 HD settings (HdPlugin.updateCachedConfigs): the one place the ported code reads a setting from.
 * The fields are 117 HD's config* fields of the same meaning, refreshed by {@link #update} when the config changes.
 */
public final class HdSettings
{
	public static volatile boolean overrideSky;
	public static volatile DefaultSkyColor defaultSkyColor = DefaultSkyColor.DEFAULT;
	public static volatile boolean legacyGreyColors;
	public static volatile boolean recolorGroundObjects = true;
	public static volatile boolean hideVanillaWaterEffects = true;
	public static volatile boolean flatShading;
	public static volatile boolean vanillaColorBanding;
	public static volatile boolean preserveVanillaNormals;
	public static volatile boolean fillGapsInTerrain = true;
	public static volatile boolean hideUnrelatedAreas = true;
	public static volatile boolean replaceVanillaSkyboxes = true;
	public static volatile boolean pohThemeEnvironments = true;
	public static volatile boolean legacyTobEnvironment;
	public static volatile boolean disableEnvironmentalLighting;
	/**
	 * The resolved seasonal theme (AUTOMATIC replaced by the current season).
	 */
	public static volatile SeasonalTheme seasonalTheme = SeasonalTheme.SUMMER;
	public static volatile boolean seasonalFoliage = true;
	// Lumina ships no HD cape images (vanilla keeps the vanilla texture; HD and Legacy draw the cape untextured)
	public static volatile InfernalCape infernalCape = InfernalCape.VANILLA;
	public static volatile boolean useLegacyBrightness;
	public static volatile int legacyBrightness = 20;
	public static volatile int brightness = 100;
	public static volatile float saturation = 1;
	public static volatile float contrast = 1;
	public static volatile float nightBrightness = 1;
	public static volatile boolean nightAdaptationEverywhere;
	public static volatile VanillaShadowMode vanillaShadowMode = VanillaShadowMode.SHOW_IN_PVM;
	public static volatile boolean hideFakeShadows = true;
	public static volatile ColorFilter colorFilter = ColorFilter.NONE;
	public static volatile ColorFilter colorFilterPrevious = ColorFilter.NONE;
	public static volatile long colorFilterChangedAt;
	public static volatile DaylightCycle daylightCycle = DaylightCycle.DEFAULT;
	public static volatile MoonBehavior moonBehavior = MoonBehavior.REALISTIC;
	public static volatile MoonPhase moonPhase = MoonPhase.DYNAMIC;
	public static volatile TextureResolution textureResolution = TextureResolution.RES_256;
	public static volatile FogDepthMode fogDepthMode = FogDepthMode.DYNAMIC;
	public static volatile int fogDepth = 5;
	public static volatile boolean groundFog = true;

	private HdSettings()
	{
	}

	public static void update(LuminaConfig config)
	{
		overrideSky = config.overrideSky();
		defaultSkyColor = config.defaultSkyColor();
		legacyGreyColors = config.legacyGreyColors();
		recolorGroundObjects = config.recolorGroundObjects();
		hideVanillaWaterEffects = config.hideVanillaWaterEffects();
		flatShading = config.flatShading();
		vanillaColorBanding = config.vanillaColorBanding();
		preserveVanillaNormals = config.preserveVanillaNormals();
		fillGapsInTerrain = config.fillGapsInTerrain();
		hideUnrelatedAreas = config.hideUnrelatedAreas();
		replaceVanillaSkyboxes = config.replaceVanillaSkyboxes();
		pohThemeEnvironments = config.pohThemeEnvironments();
		legacyTobEnvironment = config.legacyTobEnvironment();
		disableEnvironmentalLighting = config.disableEnvironmentalLighting();
		seasonalTheme = resolveSeasonalTheme(config.seasonalTheme(), config.seasonalHemisphere());
		seasonalFoliage = config.seasonalFoliage();
		infernalCape = config.infernalCape();
		useLegacyBrightness = config.useLegacyBrightness();
		legacyBrightness = config.legacyBrightness();
		brightness = config.brightness();
		saturation = config.saturation() / 100f;
		contrast = config.contrast() / 100f;
		nightBrightness = config.nightBrightness() / 100f;
		nightAdaptationEverywhere = config.nightAdaptationEverywhere();
		vanillaShadowMode = config.vanillaShadowMode();
		hideFakeShadows = vanillaShadowMode != VanillaShadowMode.SHOW;
		var newColorFilter = config.colorFilter();
		if (newColorFilter != colorFilter)
		{
			colorFilterPrevious = colorFilter;
			colorFilter = newColorFilter;
			colorFilterChangedAt = System.currentTimeMillis();
		}
		daylightCycle = config.daylightCycle();
		moonBehavior = config.moonBehavior();
		moonPhase = moonBehavior == MoonBehavior.DISABLED ? MoonPhase.FIRST_QUARTER : config.moonPhase();
		textureResolution = config.textureResolution();
		fogDepthMode = config.fogDepthMode();
		fogDepth = config.fogDepth();
		groundFog = config.groundFog();
	}

	/**
	 * 117 HD's getGammaCorrection.
	 */
	public static float getGammaCorrection()
	{
		if (useLegacyBrightness)
		{
			return 1;
		}
		return 100f / brightness;
	}

	public static boolean isCycleDisabled()
	{
		return daylightCycle == DaylightCycle.OFF;
	}

	private static SeasonalTheme resolveSeasonalTheme(SeasonalTheme theme, SeasonalHemisphere hemisphere)
	{
		if (theme != SeasonalTheme.AUTOMATIC)
		{
			return theme;
		}
		var time = ZonedDateTime.now(ZoneOffset.UTC);
		if (hemisphere == SeasonalHemisphere.NORTHERN)
		{
			switch (time.getMonth())
			{
				case SEPTEMBER:
				case OCTOBER:
				case NOVEMBER:
					return SeasonalTheme.AUTUMN;
				case DECEMBER:
				case JANUARY:
				case FEBRUARY:
					return SeasonalTheme.WINTER;
				default:
					return SeasonalTheme.SUMMER;
			}
		}
		switch (time.getMonth())
		{
			case MARCH:
			case APRIL:
			case MAY:
				return SeasonalTheme.AUTUMN;
			case JUNE:
			case JULY:
			case AUGUST:
				return SeasonalTheme.WINTER;
			default:
				return SeasonalTheme.SUMMER;
		}
	}
}
