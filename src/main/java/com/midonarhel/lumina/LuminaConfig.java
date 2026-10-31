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
		description = "Resolution of the 3D scene, as a share of the window. The interface stays sharp.",
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
		description = "Sharpens the scaled-up scene without ringing. Only used when the render scale is below 100%.",
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
		description = "Configures the anti-aliasing mode.",
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
		description = "Sharpens textures seen at a slant.",
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
		description = "How far the world is drawn, in tiles.",
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
		description = "Extra map area to load, in 8 tile chunks.",
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
		description = "Sampling function to use for the UI in stretched mode.",
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
		description = "Removes the 50 FPS cap for camera movement.",
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
		description = "Method to synchronize frame rate with refresh rate.",
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
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
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
		description = "Sun, shadows, ambient occlusion, point lights and interiors",
		position = 30
	)
	String lightingSection = "lighting";

	@ConfigItem(
		keyName = "sunLighting",
		name = "Sun and moon lighting",
		description = "Day and night cycle with directional light from the sun or moon. Off underground.",
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
		description = "How much light dim rooms get, in percent.",
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
		description = "Lights from torches, fires and other sources. They do not cast shadows.",
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
		description = "Brightness of lava, fire, magic and glowing things, in percent.",
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
		description = "Light from behind shows through leaves and plants.",
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
		description = "Soft shadows from the sun or moon.",
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
		description = "How far from your character shadows are drawn, in tiles.",
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
		description = "Shadow map size. Higher is sharper and costs more.",
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
		description = "Soft shadowing in corners, creases and under objects (SSAO).",
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
		description = "How dark occluded areas get, in percent.",
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
		description = "How far occlusion reaches, in percent of a tile.",
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
		description = "Samples per pixel of ambient occlusion: 12 on high, 8 on medium, 6 on low. Lower is noisier.",
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
		description = "Remove the shading the game bakes into model and ground colours.",
		section = lightingSection,
		position = 43
	)
	default boolean removeBakedShading()
	{
		return true;
	}

	@ConfigSection(
		name = "Sky & time of day",
		description = "The sky, the clock and the clouds",
		position = 40
	)
	String skySection = "sky";

	@ConfigItem(
		keyName = "proceduralSky",
		name = "Procedural sky",
		description = "Draw a sky that follows the time of day. Areas with their own skybox keep it.",
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
		description = "Real minutes per game day. 0 holds the time at the fixed hour.",
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
		description = "Time of day to hold when day length is 0. 6 is sunrise, 12 midday.",
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
		description = "Clouds drifting across the procedural sky, lit by the sun and moon. Rain and snow cover the sky.",
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
		description = "How much of the sky clouds cover in clear weather, in percent; cloudier weather adds to it.",
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
		description = "How fast the clouds drift, in percent.",
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
		description = "How bright the stars are, in percent.",
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
		description = "Brightness of the night sky's nebulae and Milky Way, in percent. 0 hides them.",
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
		description = "Northern lights in the night sky of the far north.",
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
		description = "Clouds shade the ground from the sun or moon as they pass over.",
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
		description = "Automatic weather by region and season, or a fixed weather.",
		section = skySection,
		position = 48
	)
	default boolean skyHorizonAtEdge()
	{
		return true;
	}

	@ConfigSection(
		name = "Weather",
		description = "Automatic regional weather, rain, snow and storms",
		position = 50
	)
	String weatherSection = "weather";

	@ConfigItem(
		keyName = "weatherMode",
		name = "Weather",
		description = "Automatic: each region's weather changes every 8 game hours by its biome and the season (deserts "
			+ "sunny with sandstorms, swamps foggy and wet, mountains snowy in winter). Off keeps it clear; any "
			+ "other choice holds that weather everywhere. Sheltered places, also under roofs the game hides, "
			+ "stay dry.",
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
		description = "How heavy the rain or snow is, in percent. In automatic weather, 60 is each weather's own "
			+ "amount.",
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
		description = "Weather changes blend over a minute and the ground gets wet or white over time.",
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
		description = "Occasional lightning in storms that briefly lights the sky and the scene.",
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
		description = "Water drawn as a surface over a bed, clear or murky by type.",
		section = weatherSection,
		position = 55
	)
	default int weatherSounds()
	{
		return 50;
	}

	@ConfigSection(
		name = "Water",
		description = "Water you can see into, with waves that follow the rivers, reflections and glints",
		position = 60
	)
	String waterSection = "water";

	@ConfigItem(
		keyName = "water",
		name = "Water",
		description = "Draw water as a surface over a lowered bed: clear water shows the bed and darkens with depth, "
			+ "swamps and caves are murky, rivers flow from their sources to the sea. Reloads the scene.",
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
		description = "Reflections of the scene in the water, besides the sky.",
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
		description = "Rippling sunlight on the bed of clear, shallow water (117 HD's caustics).",
		section = waterSection,
		position = 63
	)
	default boolean waterCaustics()
	{
		return true;
	}

	@ConfigSection(
		name = "Fog",
		description = "Volumetric fog",
		position = 70
	)
	String fogSection = "fog";

	@ConfigItem(
		keyName = "volumetricFog",
		name = "Volumetric fog",
		description = "Light fog around your character that thins out with distance, lit by the sun, moon and lights.",
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
		description = "Steps through the volumetric fog: 12 on high, 8 on medium, 6 on low. Lower is grainier in sun "
			+ "shafts.",
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
		description = "How thick the fog is near the ground.",
		section = fogSection,
		position = 73
	)
	default int volumetricFogDensity()
	{
		return 15;
	}

	@ConfigSection(
		name = "Environment",
		description = "Per-area ambience and wind",
		position = 80
	)
	String environmentSection = "environment";

	@ConfigItem(
		keyName = "areaEnvironments",
		name = "Area ambience",
		description = "Each area's own light colours, fog and sky.",
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
		description = "Trees bend and plants flutter with the weather's wind: barely in calm air, hard in a storm.",
		section = environmentSection,
		position = 82
	)
	default boolean windSway()
	{
		return true;
	}




	@ConfigSection(
		name = "Advanced",
		description = "Rarely needed switches",
		position = 90,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@Range(min = 0, max = 15)
	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Number of render threads to use.",
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
		description = "Smooths out the color banding that is present in the CPU renderer.",
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
		description = "Use old texture lighting method which results in brighter game textures.",
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
		description = "Removes vertex snapping from most animations.",
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
		description = "Adjusts colors to account for colorblindness.",
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
		description = "Strength of the colorblindness correction effect.",
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
		description = "Hide unrelated map areas you shouldn't see.",
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
		description = "Hide the map around an area that stands apart from it, like an instance or a cave.",
		section = advancedSection,
		position = 98
	)
	default boolean hideOtherAreas()
	{
		return true;
	}

	@ConfigSection(
		name = "Development",
		description = "Tools for working on the renderer",
		position = 100,
		closedByDefault = true
	)
	String developmentSection = "development";



	@ConfigItem(
		keyName = "glErrorChecks",
		name = "Check GL errors",
		description = "Call glGetError after each draw step and log failures. Slow; leave off when measuring.",
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
		description = "Show one of the scene buffers instead of the final image.",
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
		description = "Tint water by type and show which way it flows, or show its waves as a relief.",
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
		description = "A warm point light carried by your character, for checking point lighting.",
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
		description = "One lightning strike now, in any weather. Unticks itself.",
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
		description = "A small map of the weather around you in the timings overlay.",
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
