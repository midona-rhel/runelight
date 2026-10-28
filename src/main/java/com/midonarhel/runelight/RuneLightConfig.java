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
import net.runelite.client.config.Units;
import static com.midonarhel.runelight.RuneLightPlugin.MAX_DISTANCE;
import com.midonarhel.runelight.config.AntiAliasingMode;
import com.midonarhel.runelight.config.ColorBlindMode;
import com.midonarhel.runelight.config.DebugView;
import com.midonarhel.runelight.config.Quality;
import com.midonarhel.runelight.config.RenderScale;
import com.midonarhel.runelight.config.ShadingMode;
import com.midonarhel.runelight.config.ShadowResolution;
import com.midonarhel.runelight.config.SwayFps;
import com.midonarhel.runelight.config.UIScalingMode;
import com.midonarhel.runelight.config.WaterDebug;
import com.midonarhel.runelight.config.WeatherMode;

@ConfigGroup(RuneLightConfig.GROUP)
public interface RuneLightConfig extends Config
{
	String GROUP = "runelight";

	@ConfigSection(
		name = "Rendering",
		description = "Resolution, anti-aliasing, draw distance and frame rate",
		position = 10
	)
	String renderingSection = "rendering";

	@ConfigItem(
		keyName = "renderScale",
		name = "Render scale",
		description = "The 3D scene's resolution as a share of the window's pixels. It is scaled up to the window and "
			+ "the interface is drawn at full resolution on top. 1x (no HiDPI) renders at the window's size in "
			+ "points, which is half the pixels on a Retina display and the same as 100% elsewhere.",
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
		hidden = !Features.SUN,
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
		hidden = !Features.LIGHTING,
		name = "Interior brightness",
		description = "How much light dim rooms of buildings get, by day and at night, in percent. A room with lights "
			+ "or open to the day gets little or none; a closed-in one is lifted to a dim but readable level. 0 "
			+ "turns it off.",
		section = lightingSection,
		position = 32
	)
	default int interiorBrightness()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "pointLights",
		hidden = !Features.LIGHTING,
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
		hidden = !Features.LIGHTING,
		name = "Glowing materials",
		description = "Brightness of lava, fire, magic, spells and spirits like ghosts, which show their own color "
			+ "instead of being shaded, shadowed or darkened by ambient occlusion, in percent. 0 turns it off.",
		section = lightingSection,
		position = 34
	)
	default int emissiveStrength()
	{
		return 100;
	}

	@Range(max = 300)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "transmissionStrength",
		hidden = !Features.LIGHTING,
		name = "Light through leaves",
		description = "How much light passes through leaves, bushes and plants from behind, so tree canopies glow "
			+ "around the lights inside them, in percent. 0 turns it off.",
		section = lightingSection,
		position = 35
	)
	default int transmissionStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "shadows",
		hidden = !Features.LIGHTING,
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
		hidden = !Features.LIGHTING,
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
		hidden = !Features.LIGHTING,
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
		hidden = !Features.LIGHTING,
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
		hidden = !Features.LIGHTING,
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
		hidden = !Features.LIGHTING,
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
		hidden = !Features.LIGHTING,
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
		keyName = "shadingMode",
		hidden = !Features.SUN,
		name = "Shading mode",
		description = "117 HD's shading modes. 117 HD: the shading baked into the game's colors is removed and the sun "
			+ "shades. Vanilla: the baked shading stays and the sun lights every face the same. None: the game's unlit "
			+ "colors, lit the same everywhere. Experimental: the unlit colors, shaded by the sun. Reloads the scene.",
		section = lightingSection,
		position = 43
	)
	default ShadingMode shadingMode()
	{
		return ShadingMode.DEFAULT;
	}

	@ConfigItem(
		keyName = "preserveVanillaNormals",
		hidden = !Features.SUN,
		name = "Preserve vanilla normals",
		description = "Flat shaded faces take the game's own normals too, like 117 HD's option of the same name; there "
			+ "they are often less accurate. Reloads the scene.",
		section = lightingSection,
		position = 44
	)
	default boolean preserveVanillaNormals()
	{
		return false;
	}

	@ConfigItem(
		keyName = "groundBlending",
		hidden = !Features.SURFACES,
		name = "Ground blending",
		description = "Blend ground colors smoothly across tile borders and give paths soft edges, like 117 HD. Also "
			+ "removes the light the game bakes into the ground. Reloads the scene when changed.",
		section = lightingSection,
		position = 44
	)
	default boolean groundBlending()
	{
		return true;
	}

	// no header while none of its settings are shown (Features)
	String skySection = "sky";

	@ConfigItem(
		keyName = "proceduralSky",
		hidden = !Features.SKY,
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
		hidden = !Features.SUN,
		name = "Day length",
		description = "Real minutes per full day and night; the time follows the real clock, so it is the same whenever "
			+ "you play. 0 holds the time at the fixed hour (the weather goes on at 24 minutes a day).",
		section = lightingSection,
		position = 42
	)
	default int dayCycleMinutes()
	{
		return 24;
	}

	@Range(max = 23)
	@ConfigItem(
		keyName = "fixedHour",
		hidden = !Features.SUN,
		name = "Fixed hour",
		description = "Time of day to hold when day length is 0. 6 is sunrise, 12 midday.",
		section = lightingSection,
		position = 43
	)
	default int fixedHour()
	{
		return 12;
	}

	@ConfigItem(
		keyName = "clouds",
		hidden = !Features.SKY,
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
		hidden = !Features.SKY,
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
		hidden = !Features.SKY,
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
		hidden = !Features.SKY,
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
		hidden = !Features.SKY,
		name = "Nebula strength",
		description = "How bright the colored nebula clouds and the Milky Way are in the night sky, in percent. 0 hides "
			+ "them and the crowding of stars in the Milky Way.",
		section = skySection,
		position = 50
	)
	default int nebulaStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "aurora",
		hidden = !Features.SKY,
		name = "Aurora",
		description = "Curtains of light in the night sky of the far north: the Fremennik lands and the frozen north "
			+ "around Weiss. Needs area ambience.",
		section = skySection,
		position = 51
	)
	default boolean aurora()
	{
		return true;
	}

	@ConfigItem(
		keyName = "cloudShadows",
		hidden = !Features.SKY,
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
		hidden = !Features.SKY,
		name = "Horizon at world edge",
		description = "Put the sky's horizon where the drawn world ends, so the clouds come down to the land at any "
			+ "camera angle.",
		section = skySection,
		position = 48
	)
	default boolean skyHorizonAtEdge()
	{
		return true;
	}

	// no header while none of its settings are shown (Features)
	String weatherSection = "weather";

	@ConfigItem(
		keyName = "weatherMode",
		hidden = !Features.WEATHER,
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
		hidden = !Features.WEATHER,
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
		hidden = !Features.WEATHER,
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
		hidden = !Features.WEATHER,
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
		hidden = !Features.WEATHER,
		name = "Weather sounds",
		description = "Volume of the rain and the thunder, on top of the game's sound effects volume. 0 turns them off.",
		section = weatherSection,
		position = 55
	)
	default int weatherSounds()
	{
		return 50;
	}

	// no header while none of its settings are shown (Features)
	String waterSection = "water";

	@ConfigItem(
		keyName = "water",
		hidden = !Features.WATER,
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
		hidden = !Features.WATER,
		name = "World reflections",
		description = "Rough reflections of the scene (trees, walls, the shore) in the water, besides the sky. A few "
			+ "screen-space taps per water pixel.",
		section = waterSection,
		position = 62
	)
	default boolean waterReflections()
	{
		return true;
	}

	@ConfigItem(
		keyName = "waterCaustics",
		hidden = !Features.WATER,
		name = "Caustics",
		description = "Rippling sunlight on the bed of clear, shallow water (117 HD's caustics).",
		section = waterSection,
		position = 63
	)
	default boolean waterCaustics()
	{
		return true;
	}

	// no header while none of its settings are shown (Features)
	String fogSection = "fog";

	@ConfigItem(
		keyName = "volumetricFog",
		hidden = !Features.FOG,
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
		hidden = !Features.FOG,
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
		hidden = !Features.FOG,
		name = "Fog density",
		description = "How thick the fog is near the ground.",
		section = fogSection,
		position = 73
	)
	default int volumetricFogDensity()
	{
		return 15;
	}

	// no header while none of its settings are shown (Features)
	String environmentSection = "environment";

	@ConfigItem(
		keyName = "areaEnvironments",
		hidden = !Features.ENVIRONMENT,
		name = "Area ambience",
		description = "Recognise caves and dungeons by area and give areas their own light colors, fog and sky haze "
			+ "(deserts, swamps, snow, the Wilderness, caves). Off: only the game's sky color decides what is "
			+ "underground.",
		section = environmentSection,
		position = 81
	)
	default boolean areaEnvironments()
	{
		return true;
	}

	@ConfigItem(
		keyName = "windSway",
		hidden = !Features.ENVIRONMENT,
		name = "Sway in the wind",
		description = "Trees bend and plants flutter with the weather's wind: barely in calm air, hard in a storm.",
		section = environmentSection,
		position = 82
	)
	default boolean windSway()
	{
		return true;
	}

	@Range(max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "windSwayStrength",
		hidden = !Features.ENVIRONMENT,
		name = "Sway strength",
		description = "How far plants move, in percent.",
		section = environmentSection,
		position = 83
	)
	default int windSwayStrength()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "windSwayShadows",
		hidden = !Features.ENVIRONMENT,
		name = "Sway in shadows",
		description = "Shadows of swaying plants move with them. Costs redrawing those plants into the shadow map every "
			+ "frame.",
		section = environmentSection,
		position = 84
	)
	default boolean windSwayShadows()
	{
		return true;
	}

	@ConfigItem(
		keyName = "windSwayFps",
		hidden = !Features.ENVIRONMENT,
		name = "Sway frame rate",
		description = "Animate swaying plants at a low frame rate like the game's own animations, or smoothly every "
			+ "frame.",
		section = environmentSection,
		position = 85
	)
	default SwayFps windSwayFps()
	{
		return SwayFps.SMOOTH;
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
		description = "While you are in an area that stands apart from the map around it (an instance, a cave, a "
			+ "cutscene spot, an island, as 117 HD lists them), the rest of the loaded map is not drawn. Loads "
			+ "the map again.",
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
		position = 103
	)
	default boolean glErrorChecks()
	{
		return false;
	}

	@ConfigItem(
		keyName = "debugView",
		hidden = !Features.LIGHTING,
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
		hidden = !Features.WATER,
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
		hidden = !Features.LIGHTING,
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
		hidden = !Features.WEATHER,
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
		hidden = !Features.WEATHER,
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
		hidden = !Features.LIGHTING,
		name = "Show detected buildings",
		description = "Tint the rooms found on your floor, label each with its id (R) and level (L), mark where their "
			+ "lights are and outline the tiles where a hole in the floor links a room to the one above or "
			+ "below.",
		section = developmentSection,
		position = 109
	)
	default boolean showBuildings()
	{
		return false;
	}

	@ConfigItem(
		keyName = "dumpScene",
		hidden = !Features.LIGHTING,
		name = "Dump scene",
		description = "Write what the light map is made from around you (the models, walls, floors, lights, camera and "
			+ "time of day) to a file in .runelite/runelight-dumps, for offline renders. Unticks itself.",
		section = developmentSection,
		position = 110
	)
	default boolean dumpScene()
	{
		return false;
	}
}
