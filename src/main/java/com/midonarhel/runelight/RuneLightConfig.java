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
}
