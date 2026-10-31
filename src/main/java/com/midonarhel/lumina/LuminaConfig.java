/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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
package com.midonarhel.lumina;

import com.midonarhel.lumina.water.Water;

import com.midonarhel.lumina.weather.Weather;

import com.midonarhel.lumina.sky.Clouds;
import com.midonarhel.lumina.sky.Lightning;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;
import static com.midonarhel.lumina.LuminaPlugin.MAX_DISTANCE;
import com.midonarhel.lumina.config.AntiAliasingMode;
import com.midonarhel.lumina.config.ColorBlindMode;
import com.midonarhel.lumina.config.DebugView;
import com.midonarhel.lumina.config.Quality;
import com.midonarhel.lumina.config.RenderScale;
import com.midonarhel.lumina.config.ShadowResolution;
import com.midonarhel.lumina.config.UIScalingMode;
import com.midonarhel.lumina.config.WaterDebug;
import com.midonarhel.lumina.config.WeatherMode;

@ConfigGroup(LuminaConfig.GROUP)
public interface LuminaConfig extends Config
{
	// the settings key keeps the old name on purpose: changing it would reset every saved setting
	String GROUP = "runelight";

	@ConfigSection(
		name = "Rendering",
		description = "Resolution, anti-aliasing, draw distance and frame rate",
		position = 10
	)
	String renderingSection = "rendering";

	@ConfigItem(
		keyName = "renderScale",
		name = "3D resolution",
		description = "Hide the map around an area that stands apart from it, like an instance or a cave.",
		section = renderingSection,
		position = 11
	)
	default RenderScale renderScale()
	{
		return RenderScale.FULL;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "upscaleSharpness",
		name = "Upscale sharpness",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 12
	)
	default int upscaleSharpness()
	{
		return 40;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 13
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@Range(min = 0, max = 16)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 14
	)
	default int anisotropicFilteringLevel()
	{
		return 8;
	}

	@Range(max = MAX_DISTANCE)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 15
	)
	default int drawDistance()
	{
		return 50;
	}

	@Range(max = 5)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 16
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 17
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 18
	)
	default boolean unlockFps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 19
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@Range(min = 1, max = 999)
	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Developer: tint and label the rooms found on your floor.",
		section = renderingSection,
		position = 20
	)
	default int fpsTarget()
	{
		return 60;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigSection(
		name = "Lighting",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 30
	)
	String lightingSection = "lighting";

	@ConfigItem(
		keyName = "sunLighting",
		name = "Sun and moon lighting",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 31
	)
	default boolean sunLighting()
	{
		return true;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "buildingLightBrightness",
		name = "Interior brightness",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 32
	)
	default int interiorBrightness()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "pointLights",
		name = "Point lights",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 33
	)
	default boolean pointLights()
	{
		return true;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "emissiveStrength",
		name = "Glowing materials",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 34
	)
	default int emissiveStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "leafTransmission",
		name = "Light through leaves",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 35
	)
	default boolean leafTransmission()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shadows",
		name = "Shadows",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 36
	)
	default boolean shadows()
	{
		return true;
	}

	@Range(min = 8, max = 96)
	@ConfigItem(
		keyName = "shadowDistance",
		name = "Shadow distance",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 37
	)
	default int shadowDistance()
	{
		return 32;
	}

	@ConfigItem(
		keyName = "shadowResolution",
		name = "Shadow resolution",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 38
	)
	default ShadowResolution shadowResolution()
	{
		return ShadowResolution.RES_2048;
	}

	@ConfigItem(
		keyName = "ambientOcclusion",
		name = "Ambient occlusion",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 39
	)
	default boolean ambientOcclusion()
	{
		return true;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "aoStrength",
		name = "AO strength",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 40
	)
	default int aoStrength()
	{
		return 100;
	}

	@Range(min = 10, max = 400)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "aoRadius",
		name = "AO radius",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 41
	)
	default int aoRadius()
	{
		return 75;
	}

	@ConfigItem(
		keyName = "aoQuality",
		name = "AO quality",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 42
	)
	default Quality aoQuality()
	{
		return Quality.HIGH;
	}

	@ConfigItem(
		keyName = "removeBakedShading",
		name = "Remove baked shading",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 43
	)
	default boolean removeBakedShading()
	{
		return true;
	}

	@ConfigItem(
		keyName = "groundBlending",
		name = "Ground blending",
		description = "Developer: tint and label the rooms found on your floor.",
		section = lightingSection,
		position = 44
	)
	default boolean groundBlending()
	{
		return true;
	}

	@ConfigSection(
		name = "Sky & time of day",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 40
	)
	String skySection = "sky";

	@ConfigItem(
		keyName = "proceduralSky",
		name = "Procedural sky",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 41
	)
	default boolean proceduralSky()
	{
		return true;
	}

	@Range(max = 120)
	@Units(Units.MINUTES)
	@ConfigItem(
		keyName = "dayCycleMinutes",
		name = "Day length",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 42
	)
	default int dayCycleMinutes()
	{
		return 24;
	}

	@Range(max = 23)
	@ConfigItem(
		keyName = "fixedHour",
		name = "Fixed hour",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 43
	)
	default int fixedHour()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "clouds",
		name = "Clouds",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 44
	)
	default boolean clouds()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "clearWeatherClouds",
		name = "Clear weather clouds",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 45
	)
	default int cloudCoverage()
	{
		return 8;
	}

	@Range(max = 400)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "cloudSpeed",
		name = "Cloud speed",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 46
	)
	default int cloudSpeed()
	{
		return 100;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "starBrightness",
		name = "Star brightness",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 49
	)
	default int starBrightness()
	{
		return 100;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "nebulaStrength",
		name = "Nebula strength",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 50
	)
	default int nebulaStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "aurora",
		name = "Aurora",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 51
	)
	default boolean aurora()
	{
		return true;
	}

	@ConfigItem(
		keyName = "cloudShadows",
		name = "Cloud shadows",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 47
	)
	default boolean cloudShadows()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skyHorizonAtEdge",
		name = "Horizon at world edge",
		description = "Developer: tint and label the rooms found on your floor.",
		section = skySection,
		position = 48
	)
	default boolean skyHorizonAtEdge()
	{
		return true;
	}

	@ConfigSection(
		name = "Weather",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 50
	)
	String weatherSection = "weather";

	@ConfigItem(
		keyName = "weatherMode",
		name = "Weather",
		description = "Developer: tint and label the rooms found on your floor.",
		section = weatherSection,
		position = 51
	)
	default WeatherMode weatherMode()
	{
		return WeatherMode.AUTOMATIC;
	}

	@Range(min = 1, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "weatherIntensity",
		name = "Intensity",
		description = "Developer: tint and label the rooms found on your floor.",
		section = weatherSection,
		position = 52
	)
	default int weatherIntensity()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "weatherBuildup",
		name = "Gradual buildup",
		description = "Developer: tint and label the rooms found on your floor.",
		section = weatherSection,
		position = 53
	)
	default boolean weatherBuildup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherLightning",
		name = "Lightning",
		description = "Developer: tint and label the rooms found on your floor.",
		section = weatherSection,
		position = 54
	)
	default boolean weatherLightning()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "weatherSounds",
		name = "Weather sounds",
		description = "Developer: tint and label the rooms found on your floor.",
		section = weatherSection,
		position = 55
	)
	default int weatherSounds()
	{
		return 50;
	}

	@ConfigSection(
		name = "Water",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 60
	)
	String waterSection = "water";

	@ConfigItem(
		keyName = "water",
		name = "Water",
		description = "Developer: tint and label the rooms found on your floor.",
		section = waterSection,
		position = 61
	)
	default boolean water()
	{
		return true;
	}

	@ConfigItem(
		keyName = "waterReflections",
		name = "World reflections",
		description = "Developer: tint and label the rooms found on your floor.",
		section = waterSection,
		position = 62
	)
	default boolean waterReflections()
	{
		return true;
	}

	@ConfigItem(
		keyName = "waterCaustics",
		name = "Caustics",
		description = "Developer: tint and label the rooms found on your floor.",
		section = waterSection,
		position = 63
	)
	default boolean waterCaustics()
	{
		return true;
	}

	@ConfigSection(
		name = "Fog",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 70
	)
	String fogSection = "fog";

	@ConfigItem(
		keyName = "volumetricFog",
		name = "Volumetric fog",
		description = "Developer: tint and label the rooms found on your floor.",
		section = fogSection,
		position = 71
	)
	default boolean volumetricFog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fogQuality",
		name = "Fog quality",
		description = "Developer: tint and label the rooms found on your floor.",
		section = fogSection,
		position = 72
	)
	default Quality fogQuality()
	{
		return Quality.HIGH;
	}

	@Range(max = 15)
	@ConfigItem(
		keyName = "volumetricFogDensity",
		name = "Fog density",
		description = "Developer: tint and label the rooms found on your floor.",
		section = fogSection,
		position = 73
	)
	default int volumetricFogDensity()
	{
		return 15;
	}

	@ConfigSection(
		name = "Environment",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 80
	)
	String environmentSection = "environment";

	@ConfigItem(
		keyName = "areaEnvironments",
		name = "Area ambience",
		description = "Developer: tint and label the rooms found on your floor.",
		section = environmentSection,
		position = 81
	)
	default boolean areaEnvironments()
	{
		return true;
	}

	@ConfigItem(
		keyName = "windSway",
		name = "Sway in the wind",
		description = "Developer: tint and label the rooms found on your floor.",
		section = environmentSection,
		position = 82
	)
	default boolean windSway()
	{
		return true;
	}




	@ConfigSection(
		name = "Advanced",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 90,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@Range(min = 0, max = 15)
	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 91
	)
	default int numThreads()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove color banding",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 92
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 93
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 94
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colorblindness correction",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 95
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(min = 0, max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colorblindness intensity",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 96
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 97
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideOtherAreas",
		name = "Hide other areas",
		description = "Developer: tint and label the rooms found on your floor.",
		section = advancedSection,
		position = 98
	)
	default boolean hideOtherAreas()
	{
		return true;
	}

	@ConfigSection(
		name = "Development",
		description = "Developer: tint and label the rooms found on your floor.",
		position = 100,
		closedByDefault = true
	)
	String developmentSection = "development";



	@ConfigItem(
		keyName = "glErrorChecks",
		name = "Check GL errors",
		description = "Developer: tint and label the rooms found on your floor.",
		section = developmentSection,
		position = 103
	)
	default boolean glErrorChecks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "debugView",
		name = "Debug view",
		description = "Developer: tint and label the rooms found on your floor.",
		section = developmentSection,
		position = 104
	)
	default DebugView debugView()
	{
		return DebugView.FINAL;
	}

	@ConfigItem(
		keyName = "waterDebugView",
		name = "Water debug",
		description = "Developer: tint and label the rooms found on your floor.",
		section = developmentSection,
		position = 105
	)
	default WaterDebug waterDebug()
	{
		return WaterDebug.OFF;
	}

	@ConfigItem(
		keyName = "testLight",
		name = "Test light on player",
		description = "Developer: tint and label the rooms found on your floor.",
		section = developmentSection,
		position = 106
	)
	default boolean testLight()
	{
		return false;
	}

	@ConfigItem(
		keyName = "strikeLightning",
		name = "Strike lightning",
		description = "Developer: tint and label the rooms found on your floor.",
		section = developmentSection,
		position = 107
	)
	default boolean strikeLightning()
	{
		return false;
	}

	@ConfigItem(
		keyName = "weatherFieldMap",
		name = "Show weather map",
		description = "Developer: tint and label the rooms found on your floor.",
		section = developmentSection,
		position = 108
	)
	default boolean weatherFieldMap()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBuildings",
		name = "Show detected buildings",
		description = "Developer: tint and label the rooms found on your floor.",
		section = developmentSection,
		position = 109
	)
	default boolean showBuildings()
	{
		return false;
	}
}
