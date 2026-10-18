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
import com.midonarhel.runelight.config.ShadowResolution;
import com.midonarhel.runelight.config.UIScalingMode;
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
		description = "Real minutes per full day and night. 0 holds the time at the fixed hour.",
		section = lightingSection,
		position = 59
	)
	default int dayCycleMinutes()
	{
		return 12;
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
		description = "Light the inside of buildings from overhead at night, found from the map's roof data.",
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
		description = "Strength of building lights, in percent.",
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
		description = "Keep a dimmer version of the building lights on during the day.",
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
		description = "Outline the rooms found on your floor and mark where their lights are.",
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

	@Range(max = 200)
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

	@ConfigItem(
		keyName = "fogClimate",
		name = "Fog by region and time",
		description = "Mist at dawn and dusk in damp regions such as swamps and forests, barely any in deserts, little at midday.",
		section = fogSection,
		position = 258
	)
	default boolean fogClimate()
	{
		return true;
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
		name = "Wet ground and snow cover",
		description = "Surfaces under the open sky get darker and glossy in rain, or white in snow.",
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
		description = "Small splashes where rain hits the ground.",
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
		keyName = "weatherField",
		name = "Weather by region",
		description = "In automatic weather, every region of the loaded map has its own weather: rain clouds, fog and"
			+ " wet ground over a rainy region in the distance while it is clear where you are.",
		section = weatherSection,
		position = 410
	)
	default boolean weatherField()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherFarPrecipitation",
		name = "Distant rain and snow",
		description = "Rain and snow beyond the falling drops and flakes, as streaked curtains out to the draw distance"
			+ " and under the clouds on the horizon.",
		section = weatherSection,
		position = 411
	)
	default boolean weatherFarPrecipitation()
	{
		return true;
	}

	@ConfigItem(
		keyName = "weatherFieldMap",
		name = "Show weather map",
		description = "Development: a small map of the weather over the loaded scene in the timings overlay.",
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
		name = "Show GPU timings",
		description = "Overlay with GPU time spent on the scene and on presenting the frame.",
		section = developmentSection,
		position = 101
	)
	default boolean showTimings()
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
		keyName = "cloudCoverage",
		name = "Clear weather clouds",
		description = "How much of the sky clouds cover without rain or snow, in percent.",
		section = cloudsSection,
		position = 502
	)
	default int cloudCoverage()
	{
		return 30;
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
		description = "Dappled sunlight on the bed of clear, shallow water.",
		section = waterSection,
		position = 556
	)
	default boolean waterCaustics()
	{
		return true;
	}

	@ConfigItem(
		keyName = "waterDebug",
		name = "Show water map",
		description = "Debug: tint water by type and show which way it flows.",
		section = waterSection,
		position = 557
	)
	default boolean waterDebug()
	{
		return false;
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

	@ConfigItem(
		keyName = "skyCacheView",
		name = "Draw sky from capture",
		description = "Draw the sky and clouds from the captured sky instead of every frame: faster, but softer clouds that move in steps.",
		section = cloudsSection,
		position = 508
	)
	default boolean skyCacheView()
	{
		return false;
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

	@ConfigItem(
		keyName = "shadowQuality",
		name = "Soft shadow quality",
		description = "Samples per pixel of the soft shadows. Lower is grainier at the edges of soft shadows.",
		section = performanceSection,
		position = 603
	)
	default Quality shadowQuality()
	{
		return Quality.HIGH;
	}

	@ConfigItem(
		keyName = "shadowCache",
		name = "Cache static shadows",
		description = "Draw the scenery into the shadow map only when the sun has moved a little or you walked a few tiles, not every frame. Characters and animated objects still cast every frame.",
		section = performanceSection,
		position = 604
	)
	default boolean shadowCache()
	{
		return true;
	}
}
