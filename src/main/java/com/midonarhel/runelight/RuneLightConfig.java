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
		return 25;
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

	@ConfigSection(
		name = "Weather",
		description = "Rain and snow",
		position = 400
	)
	String weatherSection = "weather";

	@ConfigItem(
		keyName = "weatherMode",
		name = "Weather",
		description = "Rain or snow outdoors. Sheltered places, also under roofs the game hides, stay dry.",
		section = weatherSection,
		position = 401
	)
	default WeatherMode weatherMode()
	{
		return WeatherMode.OFF;
	}

	@Range(min = 1, max = 100)
	@ConfigItem(
		keyName = "weatherIntensity",
		name = "Intensity",
		description = "How heavy the rain or snow is, in percent.",
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
		description = "Weather fades in when turned on, and the ground gets wet or white over time.",
		section = weatherSection,
		position = 405
	)
	default boolean weatherBuildup()
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
}
