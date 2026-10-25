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
package com.midonarhel.runelight;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import static com.midonarhel.runelight.RuneLightPlugin.MAX_DISTANCE;
import static com.midonarhel.runelight.RuneLightPlugin.MAX_FOG_DEPTH;
import com.midonarhel.runelight.config.AntiAliasingMode;
import com.midonarhel.runelight.config.ColorBlindMode;
import com.midonarhel.runelight.config.DebugView;
import com.midonarhel.runelight.config.Quality;
import com.midonarhel.runelight.config.RenderScale;
import com.midonarhel.runelight.config.ShadowResolution;
import com.midonarhel.runelight.config.SwayFps;
import com.midonarhel.runelight.config.UIScalingMode;
import com.midonarhel.runelight.config.WaterDebug;
import com.midonarhel.runelight.config.WeatherMode;

@ConfigGroup(RuneLightConfig.GROUP)
public interface RuneLightConfig extends Config
{
	String GROUP = "runelight";

	@Range(
		max = MAX_DISTANCE
	)
	@ConfigItem(
		keyName = "drawDistance",
		name = "Draw distance",
		description = "Draw distance.",
		position = 1
	)
	default int drawDistance()
	{
		return 50;
	}

	@ConfigItem(
		keyName = "hideUnrelatedMaps",
		name = "Hide unrelated maps",
		description = "Hide unrelated map areas you shouldn't see.",
		position = 2
	)
	default boolean hideUnrelatedMaps()
	{
		return true;
	}

	@Range(
		max = 5
	)
	@ConfigItem(
		keyName = "expandedMapLoadingChunks",
		name = "Extended map loading",
		description = "Extra map area to load, in 8 tile chunks.",
		position = 1
	)
	default int expandedMapLoadingZones()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "smoothBanding",
		name = "Remove color banding",
		description = "Smooths out the color banding that is present in the CPU renderer.",
		position = 2
	)
	default boolean smoothBanding()
	{
		return true;
	}

	@ConfigItem(
		keyName = "antiAliasingMode",
		name = "Anti aliasing",
		description = "Configures the anti-aliasing mode.",
		position = 3
	)
	default AntiAliasingMode antiAliasingMode()
	{
		return AntiAliasingMode.MSAA_2;
	}

	@ConfigItem(
		keyName = "uiScalingMode",
		name = "UI scaling mode",
		description = "Sampling function to use for the UI in stretched mode.",
		position = 4
	)
	default UIScalingMode uiScalingMode()
	{
		return UIScalingMode.HYBRID;
	}

	@Range(
		min = 2,
		max = 40
	)
	@ConfigItem(
		keyName = "edgeFadeTiles",
		name = "Edge fade",
		description = "Tiles over which the world fades into the sky at the draw distance in clear weather. Fog makes it reach further.",
		position = 5
	)
	default int edgeFadeTiles()
	{
		return 12;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@ConfigItem(
		keyName = "fogDepth",
		name = "Fog depth",
		description = "Distance from the scene edge the fog starts.",
		position = 5
	)
	default int fogDepth()
	{
		return 0;
	}

	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description = "Configures the anisotropic filtering level.",
		position = 7
	)
	default int anisotropicFilteringLevel()
	{
		return 1;
	}

	@ConfigItem(
		keyName = "colorBlindMode",
		name = "Colorblindness correction",
		description = "Adjusts colors to account for colorblindness.",
		position = 8
	)
	default ColorBlindMode colorBlindMode()
	{
		return ColorBlindMode.NONE;
	}

	@Range(
		min = 0,
		max = 100
	)
	@ConfigItem(
		keyName = "colorBlindIntensity",
		name = "Colorblindness intensity",
		description = "Strength of the colorblindness correction effect.",
		position = 9
	)
	default int colorBlindIntensity()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "brightTextures",
		name = "Bright textures",
		description = "Use old texture lighting method which results in brighter game textures.",
		position = 10
	)
	default boolean brightTextures()
	{
		return false;
	}

	@ConfigItem(
		keyName = "unlockFps",
		name = "Unlock FPS",
		description = "Removes the 50 FPS cap for camera movement.",
		position = 11
	)
	default boolean unlockFps()
	{
		return true;
	}

	enum SyncMode
	{
		OFF,
		ON,
		ADAPTIVE
	}

	@ConfigItem(
		keyName = "vsyncMode",
		name = "Vsync mode",
		description = "Method to synchronize frame rate with refresh rate.",
		position = 12
	)
	default SyncMode syncMode()
	{
		return SyncMode.OFF;
	}

	@ConfigItem(
		keyName = "fpsTarget",
		name = "FPS target",
		description = "Target FPS when 'Unlock FPS' is enabled and 'Vsync mode' is off.",
		position = 13
	)
	@Range(
		min = 1,
		max = 999
	)
	default int fpsTarget()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "removeVertexSnapping",
		name = "Remove vertex snapping",
		description = "Removes vertex snapping from most animations.",
		position = 14
	)
	default boolean removeVertexSnapping()
	{
		return true;
	}

	@ConfigItem(
		keyName = "numThreads",
		name = "Threads",
		description = "Number of render threads to use.",
		position = 20
	)
	@Range(min = 0, max = 15)
	default int numThreads()
	{
		return 3;
	}

	@ConfigSection(
		name = "Lighting",
		description = "Lighting and shading effects",
		position = 50
	)
	String lightingSection = "lighting";

	@ConfigItem(
		keyName = "ambientOcclusion",
		name = "Ambient occlusion",
		description = "Soft shadowing in corners, creases and under objects (SSAO).",
		section = lightingSection,
		position = 51
	)
	default boolean ambientOcclusion()
	{
		return true;
	}

	@Range(max = 200)
	@ConfigItem(
		keyName = "aoStrength",
		name = "AO strength",
		description = "How dark occluded areas get, in percent.",
		section = lightingSection,
		position = 52
	)
	default int aoStrength()
	{
		return 100;
	}

	@Range(min = 10, max = 400)
	@ConfigItem(
		keyName = "aoRadius",
		name = "AO radius",
		description = "How far occlusion reaches, in percent of a tile.",
		section = lightingSection,
		position = 53
	)
	default int aoRadius()
	{
		return 75;
	}

	@ConfigItem(
		keyName = "sunLighting",
		name = "Sun and moon lighting",
		description = "Day and night cycle with directional light from the sun or moon. Off underground.",
		section = lightingSection,
		position = 54
	)
	default boolean sunLighting()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shadows",
		name = "Shadows",
		description = "Soft shadows from the sun or moon.",
		section = lightingSection,
		position = 55
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
		position = 56
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
		position = 57
	)
	default ShadowResolution shadowResolution()
	{
		return ShadowResolution.RES_2048;
	}

	@ConfigItem(
		keyName = "proceduralSky",
		name = "Sky",
		description = "Draw a sky that follows the time of day. Areas with their own skybox keep it.",
		section = lightingSection,
		position = 58
	)
	default boolean proceduralSky()
	{
		return true;
	}

	@Range(max = 120)
	@ConfigItem(
		keyName = "dayCycleMinutes",
		name = "Day length",
		description = "Real minutes per full day and night; the time follows the real clock, so it is the same whenever"
			+ " you play. 0 holds the time at the fixed hour (the weather goes on at 24 minutes a day).",
		section = lightingSection,
		position = 59
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
		section = lightingSection,
		position = 60
	)
	default int fixedHour()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "removeBakedShading",
		name = "Remove baked shading",
		description = "The game bakes light from one fixed direction into model colors. Remove it so the sun, moon "
			+ "and lights do the shading. Reloads the scene when changed.",
		section = lightingSection,
		position = 62
	)
	default boolean removeBakedShading()
	{
		return true;
	}

	@ConfigItem(
		keyName = "smoothShapes",
		name = "Smooth low-poly shapes",
		description = "Shade gently folded faces smoothly, so round shapes like tree trunks and rocks look round "
			+ "while sharp corners stay sharp. Reloads the scene when changed.",
		section = lightingSection,
		position = 62
	)
	default boolean smoothShapes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "unlitColors",
		name = "Unlit colors (experimental)",
		description = "Use the models' unlit colors instead of correcting the baked shading. Flattens color "
			+ "gradients. Reloads the scene when changed.",
		section = lightingSection,
		position = 62
	)
	default boolean unlitColors()
	{
		return false;
	}

	@ConfigItem(
		keyName = "roofShadows",
		name = "Hidden roofs cast shadows",
		description = "Roofs and upper floors keep casting shadows when the game hides them, so interiors stay shaded.",
		section = lightingSection,
		position = 63
	)
	default boolean roofShadows()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pointLights",
		name = "Point lights",
		description = "Lights from torches, fires and other sources. They do not cast shadows.",
		section = lightingSection,
		position = 61
	)
	default boolean pointLights()
	{
		return true;
	}

	@ConfigSection(
		name = "Buildings",
		description = "Soft overhead light inside buildings at night",
		position = 450
	)
	String buildingsSection = "buildings";

	@ConfigItem(
		keyName = "buildingLights",
		name = "Building lights",
		description = "Light the inside of buildings softly at night, as if their lamps were lit, found from the map's "
			+ "roof data.",
		section = buildingsSection,
		position = 451
	)
	default boolean buildingLights()
	{
		return true;
	}

	@ConfigSection(
		name = "Ground",
		description = "How ground tiles look",
		position = 300
	)
	String groundSection = "ground";

	@ConfigItem(
		keyName = "groundBlending",
		name = "Ground blending",
		description = "Blend ground colors smoothly across tile borders and give paths soft edges, like 117 HD. "
			+ "Also removes the light the game bakes into the ground. Reloads the scene when changed.",
		section = groundSection,
		position = 301
	)
	default boolean groundBlending()
	{
		return true;
	}

	@Range(max = 300)
	@ConfigItem(
		keyName = "buildingLightBrightness",
		name = "Brightness",
		description = "How brightly the inside of buildings is lit, in percent.",
		section = buildingsSection,
		position = 452
	)
	default int buildingLightBrightness()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "buildingLightsByDay",
		name = "Also by day",
		description = "Keep the inside of buildings dimly lit during the day too.",
		section = buildingsSection,
		position = 453
	)
	default boolean buildingLightsByDay()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBuildings",
		name = "Show detected buildings",
		description = "Tint the rooms found on your floor, label each with its id (R) and level (L), mark where their "
			+ "lights are and outline the tiles where a hole in the floor links a room to the one above or below.",
		section = buildingsSection,
		position = 454
	)
	default boolean showBuildings()
	{
		return false;
	}

	@ConfigSection(
		name = "Materials",
		description = "Glowing and see-through surfaces",
		position = 200
	)
	String materialsSection = "materials";

	@ConfigItem(
		keyName = "foliageTransmission",
		name = "Light through leaves",
		description = "Leaves, bushes and plants are lit through from behind by the sun, moon and lights, so tree "
			+ "canopies glow around the lights inside them.",
		section = materialsSection,
		position = 201
	)
	default boolean foliageTransmission()
	{
		return true;
	}

	@Range(max = 300)
	@ConfigItem(
		keyName = "transmissionStrength",
		name = "Leaf glow strength",
		description = "How much light passes through leaves, in percent.",
		section = materialsSection,
		position = 202
	)
	default int transmissionStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "emissiveMaterials",
		name = "Glowing materials",
		description = "Lava, fire, magic, spells and spirits like ghosts show their own color instead of being "
			+ "shaded, shadowed or darkened by ambient occlusion.",
		section = materialsSection,
		position = 203
	)
	default boolean emissiveMaterials()
	{
		return true;
	}

	@Range(min = 50, max = 300)
	@ConfigItem(
		keyName = "emissiveStrength",
		name = "Glow strength",
		description = "Brightness of glowing materials, in percent.",
		section = materialsSection,
		position = 204
	)
	default int emissiveStrength()
	{
		return 100;
	}

	@ConfigSection(
		name = "Fog",
		description = "Volumetric fog",
		position = 250
	)
	String fogSection = "fog";

	@ConfigItem(
		keyName = "volumetricFog",
		name = "Volumetric fog",
		description = "Light fog around your character that thins out with distance, lit by the sun, moon and lights.",
		section = fogSection,
		position = 251
	)
	default boolean volumetricFog()
	{
		return true;
	}

	@Range(max = 15)
	@ConfigItem(
		keyName = "volumetricFogDensity",
		name = "Fog density",
		description = "How thick the fog is near the ground, in percent.",
		section = fogSection,
		position = 252
	)
	default int volumetricFogDensity()
	{
		return 15;
	}

	@Range(min = 25, max = 2000)
	@ConfigItem(
		keyName = "volumetricFogHeight",
		name = "Fog height",
		description = "How high the fog reaches before it thins out, in percent of a tile.",
		section = fogSection,
		position = 253
	)
	default int volumetricFogHeight()
	{
		return 300;
	}

	@Range(min = 8, max = 184)
	@ConfigItem(
		keyName = "volumetricFogDistance",
		name = "Fog distance",
		description = "How far from your character the fog reaches before it has faded out, in tiles.",
		section = fogSection,
		position = 254
	)
	default int volumetricFogDistance()
	{
		return 40;
	}

	@Range(max = 300)
	@ConfigItem(
		keyName = "volumetricFogSunScattering",
		name = "Fog sun scattering",
		description = "How brightly the fog glows in sunlight and moonlight, including light shafts, in percent.",
		section = fogSection,
		position = 255
	)
	default int volumetricFogSunScattering()
	{
		return 100;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "volumetricFogNoise",
		name = "Fog patchiness",
		description = "How much the fog drifts in patches instead of being even, in percent.",
		section = fogSection,
		position = 256
	)
	default int volumetricFogNoise()
	{
		return 50;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "volumetricFogBanks",
		name = "Fog banks",
		description = "How much the fog gathers in large drifting banks, thicker over some stretches of ground than others, in percent.",
		section = fogSection,
		position = 257
	)
	default int volumetricFogBanks()
	{
		return 50;
	}

	@Range(min = 10, max = 100)
	@ConfigItem(
		keyName = "volumetricFogMaxCover",
		name = "Fog cover",
		description = "Most of the view around your character the fog and area fog may hide, in percent. Beyond about 25 tiles"
			+ " they fade out fully; lights and sun still glow in the fog. 100 puts no limit on it.",
		section = fogSection,
		position = 259
	)
	default int volumetricFogMaxCover()
	{
		return 30;
	}

	@ConfigSection(
		name = "Weather",
		description = "Automatic regional weather, rain, snow and storms",
		position = 400
	)
	String weatherSection = "weather";

	@ConfigItem(
		keyName = "weatherMode",
		name = "Force weather",
		description = "Automatic: each region's weather changes every 8 game hours by its biome and the season (deserts"
			+ " sunny with sandstorms, swamps foggy and wet, mountains snowy in winter). Off keeps it clear; any other"
			+ " choice holds that weather everywhere. Sheltered places, also under roofs the game hides, stay dry.",
		section = weatherSection,
		position = 401
	)
	default WeatherMode weatherMode()
	{
		return WeatherMode.AUTOMATIC;
	}

	@Range(min = 1, max = 100)
	@ConfigItem(
		keyName = "weatherIntensity",
		name = "Intensity",
		description = "How heavy the rain or snow is, in percent. In automatic weather, 60 is each weather's own"
			+ " amount.",
		section = weatherSection,
		position = 402
	)
	default int weatherIntensity()
	{
		return 60;
	}

	@ConfigItem(
		keyName = "weatherSurfaces",
		name = "Snow and puddles",
		description = "Snow settles on the ground and puddles gather on level terrain under the open sky.",
		section = weatherSection,
		position = 403
	)
	default boolean weatherSurfaces()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherSplashes",
		name = "Rain splashes",
		description = "Small sky-colored impacts where rain hits the ground, drawn like anime rain. Water has its own rings.",
		section = weatherSection,
		position = 404
	)
	default boolean weatherSplashes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherBuildup",
		name = "Gradual buildup",
		description = "Weather changes blend over a minute and the ground gets wet or white over time.",
		section = weatherSection,
		position = 405
	)
	default boolean weatherBuildup()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherVarying",
		name = "Varying intensity",
		description = "Rain and snow swell and ease off over time, with heavier bursts and gusts of wind.",
		section = weatherSection,
		position = 406
	)
	default boolean weatherVarying()
	{
		return true;
	}

	@Range(min = 0, max = 100)
	@ConfigItem(
		keyName = "weatherOvercast",
		name = "Overcast sky",
		description = "How much rain and snow gray the sky and soften the sun and moon, in percent.",
		section = weatherSection,
		position = 407
	)
	default int weatherOvercast()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "weatherPointLights",
		name = "Lit by point lights",
		description = "Rain, snow and snow cover catch the light of torches and lamps. Needs point lights.",
		section = weatherSection,
		position = 408
	)
	default boolean weatherPointLights()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherLightning",
		name = "Lightning",
		description = "Occasional lightning in storms that briefly lights the sky and the scene.",
		section = weatherSection,
		position = 409
	)
	default boolean weatherLightning()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherFieldMap",
		name = "Show weather map",
		description = "Development: a small map of the weather around you in the timings overlay.",
		section = weatherSection,
		position = 412
	)
	default boolean weatherFieldMap()
	{
		return false;
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "weatherSeasonDays",
		name = "Days per season",
		description = "Game days each season lasts (spring, summer, autumn, winter). Seasons change the odds of each"
			+ " weather: summer rain and heat, autumn rain and wind, snow in winter.",
		section = weatherSection,
		position = 413
	)
	default int weatherSeasonDays()
	{
		return 7;
	}

	@ConfigSection(
		name = "Development",
		description = "Tools for working on the renderer",
		position = 100,
		closedByDefault = true
	)
	String developmentSection = "development";

	@ConfigItem(
		keyName = "showTimings",
		name = "Show timings",
		description = "Overlay with the frame time and the CPU time per step.",
		section = developmentSection,
		position = 101
	)
	default boolean showTimings()
	{
		return false;
	}

	@ConfigItem(
		keyName = "gpuPassTimings",
		name = "Time GPU passes",
		description = "Adds the GPU time of every pass to the timings overlay. On macOS ending a timer query makes the "
			+ "CPU wait for the GPU, so the measured frames stutter: leave it off except while measuring.",
		section = developmentSection,
		position = 102
	)
	default boolean gpuPassTimings()
	{
		return false;
	}

	@ConfigItem(
		keyName = "glErrorChecks",
		name = "Check GL errors",
		description = "Call glGetError after each draw step and log failures. Slow; leave off when measuring.",
		section = developmentSection,
		position = 102
	)
	default boolean glErrorChecks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "testLight",
		name = "Test light on player",
		description = "A warm point light carried by your character, for checking point lighting.",
		section = developmentSection,
		position = 104
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
		position = 105
	)
	default boolean strikeLightning()
	{
		return false;
	}

	@ConfigItem(
		keyName = "debugView",
		name = "Debug view",
		description = "Show one of the scene buffers instead of the final image.",
		section = developmentSection,
		position = 103
	)
	default DebugView debugView()
	{
		return DebugView.FINAL;
	}

	@ConfigSection(
		name = "Environment",
		description = "Per-area ambience: inside or outside, caves, and each area's own light and fog",
		position = 350
	)
	String environmentSection = "environment";

	@ConfigItem(
		keyName = "areaEnvironments",
		name = "Area ambience",
		description = "Recognise caves and dungeons by area and give areas their own light colors, fog and sky haze"
			+ " (deserts, swamps, snow, the Wilderness, caves). Off: only the game's sky color decides what is underground.",
		section = environmentSection,
		position = 351
	)
	default boolean areaEnvironments()
	{
		return true;
	}

	@Range(max = 200)
	@ConfigItem(
		keyName = "areaLightStrength",
		name = "Area light tint",
		description = "How strongly areas tint and dim the ambient light and sun, in percent. 0 keeps the normal light everywhere.",
		section = environmentSection,
		position = 352
	)
	default int areaLightStrength()
	{
		return 100;
	}

	@Range(max = 200)
	@ConfigItem(
		keyName = "areaFogStrength",
		name = "Area fog",
		description = "Density of each area's distance fog and sky haze, in percent. 0 turns area fog off.",
		section = environmentSection,
		position = 353
	)
	default int areaFogStrength()
	{
		return 100;
	}

	@ConfigSection(
		name = "Clouds",
		description = "Volumetric clouds in the sky that close up in rain and snow",
		position = 500
	)
	String cloudsSection = "clouds";

	@ConfigItem(
		keyName = "clouds",
		name = "Clouds",
		description = "Clouds drifting across the procedural sky, lit by the sun and moon. Rain and snow cover the sky.",
		section = cloudsSection,
		position = 501
	)
	default boolean clouds()
	{
		return true;
	}

	@Range(max = 100)
	@ConfigItem(
		keyName = "clearWeatherClouds",
		name = "Clear weather clouds",
		description = "How much of the sky clouds cover in clear weather, in percent; cloudier weather adds to it.",
		section = cloudsSection,
		position = 502
	)
	default int cloudCoverage()
	{
		return 8;
	}

	@Range(max = 400)
	@ConfigItem(
		keyName = "cloudSpeed",
		name = "Wind speed",
		description = "How fast the clouds drift, in percent.",
		section = cloudsSection,
		position = 503
	)
	default int cloudSpeed()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "cloudShadows",
		name = "Cloud shadows",
		description = "Clouds shade the ground from the sun or moon as they pass over.",
		section = cloudsSection,
		position = 504
	)
	default boolean cloudShadows()
	{
		return true;
	}

	@ConfigSection(
		name = "Water",
		description = "Water you can see into, with waves that follow the rivers, reflections and glints",
		position = 550
	)
	String waterSection = "water";

	@ConfigItem(
		keyName = "water",
		name = "Water",
		description = "Draw water as a surface over a lowered bed: clear water shows the bed and darkens with depth,"
			+ " swamps and caves are murky, rivers flow from their sources to the sea. Reloads the scene.",
		section = waterSection,
		position = 551
	)
	default boolean water()
	{
		return true;
	}

	@ConfigItem(
		keyName = "waterReflections",
		name = "World reflections",
		description = "Rough reflections of the scene (trees, walls, the shore) in the water, besides the sky."
			+ " A few screen-space taps per water pixel.",
		section = waterSection,
		position = 552
	)
	default boolean waterReflections()
	{
		return true;
	}

	@Range(min = 25, max = 300)
	@ConfigItem(
		keyName = "waterClarity",
		name = "Clarity",
		description = "How far you can see into the water, in percent.",
		section = waterSection,
		position = 553
	)
	default int waterClarity()
	{
		return 100;
	}

	@Range(max = 200)
	@ConfigItem(
		keyName = "cloudDetail",
		name = "Cloud roughness",
		description = "How ragged and broken the clouds are instead of smooth and round, in percent.",
		section = cloudsSection,
		position = 505
	)
	default int cloudDetail()
	{
		return 100;
	}

	@Range(max = 200)
	@ConfigItem(
		keyName = "waterWaves",
		name = "Waves",
		description = "Height of the waves and the swell, in percent.",
		section = waterSection,
		position = 554
	)
	default int waterWaves()
	{
		return 100;
	}

	@Range(max = 300)
	@ConfigItem(
		keyName = "waterFlowSpeed",
		name = "Flow speed",
		description = "How fast rivers and the sea move, in percent.",
		section = waterSection,
		position = 555
	)
	default int waterFlowSpeed()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "waterCaustics",
		name = "Caustics",
		description = "Rippling sunlight on the bed of clear, shallow water (117 HD's caustics).",
		section = waterSection,
		position = 556
	)
	default boolean waterCaustics()
	{
		return true;
	}

	@ConfigItem(
		keyName = "waterDebugView",
		name = "Water debug",
		description = "Debug: tint water by type and show which way it flows, or show its waves as a relief.",
		section = waterSection,
		position = 557
	)
	default WaterDebug waterDebug()
	{
		return WaterDebug.OFF;
	}

	@ConfigItem(
		keyName = "skyCache",
		name = "Sky light from the sky",
		description = "Light from the sky follows the clouds and the colors of the sky, on walls too: warm facing a sunset, gray under cloud. The sky is captured every few in-game minutes and blended.",
		section = cloudsSection,
		position = 506
	)
	default boolean skyCache()
	{
		return true;
	}

	@Range(min = 1, max = 60)
	@ConfigItem(
		keyName = "skyCacheMinutes",
		name = "Sky capture interval",
		description = "In-game minutes between captures of the sky. Changes of weather and drifting clouds capture it sooner.",
		section = cloudsSection,
		position = 507
	)
	default int skyCacheMinutes()
	{
		return 5;
	}

	@Range(max = 200)
	@ConfigItem(
		keyName = "starBrightness",
		name = "Star brightness",
		description = "How bright the stars are, in percent.",
		section = cloudsSection,
		position = 509
	)
	default int starBrightness()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "skyHorizonAtEdge",
		name = "Horizon at world edge",
		description = "Put the sky's horizon where the drawn world ends, so the clouds come down to the land at any camera angle.",
		section = cloudsSection,
		position = 510
	)
	default boolean skyHorizonAtEdge()
	{
		return true;
	}

	@ConfigSection(
		name = "Performance",
		description = "Trade some quality for frame rate. High matches the full look.",
		position = 600
	)
	String performanceSection = "performance";

	@ConfigItem(
		keyName = "fogQuality",
		name = "Fog quality",
		description = "Steps through the volumetric fog: 12 on high, 8 on medium, 6 on low. Lower is grainier in sun shafts.",
		section = performanceSection,
		position = 601
	)
	default Quality fogQuality()
	{
		return Quality.HIGH;
	}

	@ConfigItem(
		keyName = "aoQuality",
		name = "Ambient occlusion quality",
		description = "Samples per pixel of ambient occlusion: 12 on high, 8 on medium, 6 on low. Lower is noisier.",
		section = performanceSection,
		position = 602
	)
	default Quality aoQuality()
	{
		return Quality.HIGH;
	}

	@ConfigSection(
		name = "Wind",
		description = "Trees, bushes, grass and flowers sway with the wind",
		position = 650
	)
	String windSection = "wind";

	@ConfigItem(
		keyName = "windSway",
		name = "Sway in the wind",
		description = "Trees bend and plants flutter with the weather's wind: barely in calm air, hard in a storm.",
		section = windSection,
		position = 651
	)
	default boolean windSway()
	{
		return true;
	}

	@Range(
		max = 200
	)
	@ConfigItem(
		keyName = "windSwayStrength",
		name = "Sway strength",
		description = "How far plants move, in percent.",
		section = windSection,
		position = 652
	)
	default int windSwayStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "windSwayShadows",
		name = "Sway in shadows",
		description = "Shadows of swaying plants move with them. Costs redrawing those plants into the shadow map every frame.",
		section = windSection,
		position = 653
	)
	default boolean windSwayShadows()
	{
		return true;
	}

	@ConfigItem(
		keyName = "windSwayFps",
		name = "Sway frame rate",
		description = "Animate swaying plants at a low frame rate like the game's own animations, or smoothly every frame.",
		section = windSection,
		position = 654
	)
	default SwayFps windSwayFps()
	{
		return SwayFps.SMOOTH;
	}

	@ConfigSection(
		name = "Rooms",
		description = "Keep light and fog where they belong: lamps inside buildings, fog outside them",
		position = 750
	)
	String roomsSection = "rooms";

	@ConfigItem(
		keyName = "roomLights",
		name = "Lights stay in rooms",
		description = "Lights inside a building only light its room, and lights outside don't shine in through the "
			+ "walls; light spills a little out of open doorways, and through holes in the floors (stairwells, pits) "
			+ "between the floors.",
		section = roomsSection,
		position = 751
	)
	default boolean roomLights()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fogIndoors",
		name = "No fog indoors",
		description = "The volumetric fog stays out of buildings.",
		section = roomsSection,
		position = 752
	)
	default boolean noFogIndoors()
	{
		return true;
	}

	@ConfigItem(
		keyName = "openRoofFog",
		name = "Hidden roofs open the fog",
		description = "A building whose roof is hidden lets its lamps shine up over the walls into the fog, and the "
			+ "fog comes back inside it, thinly.",
		section = roomsSection,
		position = 753
	)
	default boolean openRoofFog()
	{
		return true;
	}

	@ConfigSection(
		name = "Skylight and light occlusion",
		description = "Light from the sky and from lamps stops where the scenery and the walls block it",
		position = 800
	)
	String lightOcclusionSection = "lightOcclusion";

	@Range(max = 100)
	@ConfigItem(
		keyName = "skylightFloor",
		name = "Skylight floor",
		description = "Share of the ambient light kept where no sky is seen, such as deep inside buildings, in "
			+ "percent: higher keeps interiors easier to see.",
		section = lightOcclusionSection,
		position = 802
	)
	default int skylightFloor()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "lightWallOcclusion",
		name = "Walls block lights",
		description = "Lamps, fires and other lights that stay put don't shine through walls, diagonal ones included; "
			+ "they reach out through doorways and windows. Worked out in the background for each light.",
		section = lightOcclusionSection,
		position = 803
	)
	default boolean lightWallOcclusion()
	{
		return true;
	}

	@ConfigItem(
		keyName = "lightRoofOcclusion",
		name = "No light on roofs",
		description = "Lights under a roof don't light the roof's top, or anything else open to the sky above them.",
		section = lightOcclusionSection,
		position = 804
	)
	default boolean lightRoofOcclusion()
	{
		return true;
	}

	@ConfigItem(
		keyName = "roomsByWalls",
		name = "Split rooms at walls",
		description = "Buildings are split into rooms along their interior walls, so lights stay in their own "
			+ "room.",
		section = lightOcclusionSection,
		position = 805
	)
	default boolean roomsByWalls()
	{
		return true;
	}

	@ConfigItem(
		keyName = "skylightFlow",
		name = "Skylight through openings",
		description = "The sky's light flows in under cover through the openings of the walls: a gate arch fades in "
			+ "from its ends, a house with its door open has a pool of light inside, a closed door keeps it out. "
			+ "Without it, everything under a roof is as dark as the skylight floor.",
		section = lightOcclusionSection,
		position = 806
	)
	default boolean skylightFlow()
	{
		return true;
	}

	@Range(min = 1, max = 12)
	@ConfigItem(
		keyName = "skylightReach",
		name = "Skylight reach",
		description = "How far in, in tiles, the light flowing in under cover falls to a third of its brightness.",
		section = lightOcclusionSection,
		position = 807
	)
	default int skylightReach()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "skylightOutdoors",
		name = "Skylight outdoors",
		description = "Let the traced skylight shade outdoor surfaces near buildings and trees. Off, only covered spots "
			+ "get skylight shading.",
		section = lightOcclusionSection,
		position = 808
	)
	default boolean skylightOutdoors()
	{
		return true;
	}

	@ConfigSection(
		name = "Hide other areas",
		description = "What shows of the map around instances, caves and other areas of their own",
		position = 900
	)
	String areasSection = "areas";

	@ConfigItem(
		keyName = "hideOtherAreas",
		name = "Hide other areas",
		description = "While you are in an area that stands apart from the map around it (an instance, a cave, a "
			+ "cutscene spot, an island, as 117 HD lists them), the rest of the loaded map is not drawn. Loads the "
			+ "map again.",
		section = areasSection,
		position = 901
	)
	default boolean hideOtherAreas()
	{
		return true;
	}

	@ConfigSection(
		name = "Resolution",
		description = "Render the 3D scene below the screen's resolution and scale it up; the interface stays sharp",
		position = 910
	)
	String resolutionSection = "resolution";

	@ConfigItem(
		keyName = "renderScale",
		name = "Render scale",
		description = "The 3D scene's resolution as a share of the window's pixels; it is scaled up to the window and the "
			+ "interface is drawn at full resolution on top. 1x (no HiDPI) renders at the window's size in points, "
			+ "which is half the pixels on a Retina display and the same as 100% elsewhere.",
		section = resolutionSection,
		position = 911
	)
	default RenderScale renderScale()
	{
		return RenderScale.FULL;
	}

	@Range(
		max = 100
	)
	@ConfigItem(
		keyName = "upscaleSharpness",
		name = "Upscale sharpness",
		description = "Sharpens the scaled-up scene without ringing. Only used when the render scale is below 100%.",
		section = resolutionSection,
		position = 912
	)
	default int upscaleSharpness()
	{
		return 40;
	}
}
