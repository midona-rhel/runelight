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

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import static com.midonarhel.lumina.LuminaPlugin.MAX_DISTANCE;
import static com.midonarhel.lumina.LuminaPlugin.MAX_FOG_DEPTH;
import com.midonarhel.lumina.config.AntiAliasingMode;
import com.midonarhel.lumina.config.ColorBlindMode;
import com.midonarhel.lumina.config.ColorFilter;
import com.midonarhel.lumina.config.ColorMode;
import com.midonarhel.lumina.config.DaylightCycle;
import com.midonarhel.lumina.config.DefaultSkyColor;
import com.midonarhel.lumina.config.FogDepthMode;
import com.midonarhel.lumina.config.InfernalCape;
import com.midonarhel.lumina.config.SeasonalHemisphere;
import com.midonarhel.lumina.config.ShadingMode;
import com.midonarhel.lumina.config.TextureResolution;
import com.midonarhel.lumina.config.VanillaShadowMode;
import com.midonarhel.lumina.scene.overrides.SeasonalTheme;
import com.midonarhel.lumina.scene.sky.MoonBehavior;
import com.midonarhel.lumina.scene.sky.MoonPhase;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Units;
import com.midonarhel.lumina.config.UIScalingMode;

@ConfigGroup(LuminaConfig.GROUP)
public interface LuminaConfig extends Config
{
	String GROUP = "lumina";

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
		keyName = "hideUnrelatedAreas",
		name = "Hide unrelated areas",
		description = "Hide unrelated areas which you shouldn't see from your current position.",
		position = 2
	)
	default boolean hideUnrelatedAreas()
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

	@ConfigItem(
		keyName = "fogDepthMode",
		name = "Fog depth mode",
		description =
			"Determines how the fog amount is controlled.<br>" +
			"'Dynamic' changes fog depth based on the area, while<br>" +
			"'Static' respects the manually defined fog depth.",
		position = 5
	)
	default FogDepthMode fogDepthMode()
	{
		return FogDepthMode.DYNAMIC;
	}

	@Range(
		max = MAX_FOG_DEPTH
	)
	@Units(" tiles")
	@ConfigItem(
		keyName = "fogDepth",
		name = "Static fog depth",
		description =
			"Specify how far from the edge fog should reach.<br>" +
			"This applies only when 'Fog depth mode' is set to 'Static'.",
		position = 5
	)
	default int fogDepth()
	{
		return 5;
	}

	@ConfigItem(
		keyName = "groundFog",
		name = "Ground fog",
		description = "Enables a height-based fog effect that covers the ground in certain areas.",
		position = 5
	)
	default boolean groundFog()
	{
		return true;
	}

	@Range(
		min = 0,
		max = 16
	)
	@ConfigItem(
		keyName = "anisotropicFilteringLevel",
		name = "Anisotropic filtering",
		description =
			"Configures whether mipmapping and anisotropic filtering should be used.<br>" +
			"At zero, mipmapping is disabled and textures look the most pixelated.<br>" +
			"At 1 through 16, mipmapping is enabled, and textures look more blurry and smoothed out.<br>" +
			"The higher you go beyond 1, the less blurry textures will look, up to a certain point.",
		position = 7
	)
	default int anisotropicFilteringLevel()
	{
		return 16;
	}

	@ConfigItem(
		keyName = "textureResolution",
		name = "Texture resolution",
		description = "Controls the resolution used for all in-game textures.",
		position = 7
	)
	default TextureResolution textureResolution()
	{
		return TextureResolution.RES_256;
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
		description =
			"Removes vertex snapping from most animations.<br>" +
			"Most animations are barely affected by this, and it only has an effect if the animation smoothing plugin is turned off.<br>" +
			"To see quite clearly what impact this option has, a good example is the godsword idle animation.",
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

	@ConfigItem(
		keyName = "shadingMode",
		name = "Shading mode",
		description = "117 HD's shading mode: whether vanilla shading is reversed, unlit colours are used and directional light shades surfaces.",
		position = 15
	)
	default ShadingMode shadingMode()
	{
		return ShadingMode.DEFAULT;
	}

	@ConfigItem(
		keyName = "colorMode",
		name = "Colours",
		description = "117 HD: 117 HD's colour changes (undo of vanilla shading, overrides). GPU: the game's own colours.",
		position = 16
	)
	default ColorMode colorMode()
	{
		return ColorMode.HD117;
	}

	// 117 HD's settings (rs117.hd.HdPluginConfig): the same keys, names, descriptions and defaults

	@ConfigSection(
		name = "117 HD: lighting",
		description = "117 HD's lighting, sky and colour settings.",
		position = 100
	)
	String hdLightingSection = "hdLighting";

	@ConfigItem(
		keyName = "daylightCycle",
		name = "Cycle mode",
		description =
			"Controls the day & night cycle behavior.<br>" +
			"'Off' disables the day & night cycle entirely.<br>" +
			"'Default' shows everyone the same sky, with one complete day passing per hour.<br>" +
			"'Dawn' shows the sky just before sunrise.<br>" +
			"'Sunrise' shows a constant sunrise.<br>" +
			"'Day' shows constant daytime.<br>" +
			"'Sunset' shows a constant sunset.<br>" +
			"'Dusk' shows the sky just after sunset.<br>" +
			"'Night' shows constant night-time.",
		position = 0,
		section = hdLightingSection
	)
	default DaylightCycle daylightCycle()
	{
		return DaylightCycle.DEFAULT;
	}

	@Range(
		min = 0,
		max = 1000
	)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "nightBrightness",
		name = "Night brightness",
		description =
			"Simulates your eyes adapting to darkness by brightening night-time lighting.<br>" +
			"'0%' disables night brightness adaptation entirely.<br>" +
			"'100%' yields good visibility at night.<br>" +
			"Values above 100% amplify the adjustment further, if needed.",
		position = 1,
		section = hdLightingSection
	)
	default int nightBrightness()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "experimentalNightAdaptationEverywhere",
		name = "Night adaptation everywhere",
		description = "Apply Day & night's 'Night brightness' adaptation to all dark scenes.",
		position = 2,
		section = hdLightingSection
	)
	default boolean nightAdaptationEverywhere()
	{
		return false;
	}

	@ConfigItem(
		keyName = "moonBehavior",
		name = "Moon behavior",
		description =
			"Controls how the moon moves across the sky.<br>" +
			"'Disabled' hides the moon, keeping half-moon illumination for scene lighting.<br>" +
			"'Realistic orbit' makes the moon orbit naturally, independent of the sun.<br>" +
			"'Mirror the sun' keeps the moon opposite the sun.<br>" +
			"'Static' keeps the moon at a fixed point in the sky.",
		position = 3,
		section = hdLightingSection
	)
	default MoonBehavior moonBehavior()
	{
		return MoonBehavior.REALISTIC;
	}

	@ConfigItem(
		keyName = "moonPhase",
		name = "Moon phase",
		description =
			"Controls the portion of the moon which is lit by the sun.<br>" +
			"'Dynamic' lights up the moon based on its position relative to the sun.<br>" +
			"All other options lock the moon in a particular lunar phase.",
		position = 4,
		section = hdLightingSection
	)
	default MoonPhase moonPhase()
	{
		return MoonPhase.DYNAMIC;
	}

	@ConfigItem(
		keyName = "defaultSkyColor",
		name = "Default sky",
		description =
			"Specify a sky color to use when the current area doesn't have a sky color defined.<br>" +
			"This only applies when the default summer seasonal theme is active.<br>" +
			"If set to 'RuneLite Skybox', the sky color from RuneLite's Skybox plugin will be used.<br>" +
			"If set to 'Old School Black', the sky will be black and water will remain blue, but for any<br>" +
			"other option, the water color will be influenced by the sky color.",
		position = 5,
		section = hdLightingSection
	)
	default DefaultSkyColor defaultSkyColor()
	{
		return DefaultSkyColor.DEFAULT;
	}

	@ConfigItem(
		keyName = "overrideSky",
		name = "Override sky color",
		description = "Forces the default sky color to be used in all environments.",
		position = 6,
		section = hdLightingSection
	)
	default boolean overrideSky()
	{
		return false;
	}

	@ConfigItem(
		keyName = "replaceVanillaSkyboxes",
		name = "Replace vanilla skyboxes",
		description = "Replace the game's built-in skybox models with 117 HD's own implementation.",
		position = 7,
		section = hdLightingSection
	)
	default boolean replaceVanillaSkyboxes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pohThemeEnvironments",
		name = "Player-owned house themes",
		description = "Change the environmental lighting based on the POH style.",
		position = 8,
		section = hdLightingSection
	)
	default boolean pohThemeEnvironments()
	{
		return true;
	}

	@ConfigItem(
		keyName = "legacyTobEnvironment",
		name = "Legacy Theatre of Blood",
		description =
			"Previously, Theatre of Blood used to look a whole lot more blue, which<br>" +
			"some people grew really used to. This option brings back that same old look.",
		position = 9,
		section = hdLightingSection
	)
	default boolean legacyTobEnvironment()
	{
		return false;
	}

	@ConfigItem(
		keyName = "legacyDisableEnvironmentalLighting",
		name = "Disable environmental lighting",
		description = "Disable lighting tuned per environment, falling back to bright sunlight everywhere.",
		position = 10,
		section = hdLightingSection
	)
	default boolean disableEnvironmentalLighting()
	{
		return false;
	}

	@ConfigItem(
		keyName = "useLegacyBrightness",
		name = "Use legacy brightness",
		description =
			"Whether the legacy brightness option below should be applied.<br>" +
			"We recommend leaving this disabled.",
		position = 11,
		section = hdLightingSection
	)
	default boolean useLegacyBrightness()
	{
		return false;
	}

	@Range(
		min = 1,
		max = 50
	)
	@ConfigItem(
		keyName = "brightness2",
		name = "Legacy brightness",
		description =
			"Controls the strength of the sun and ambient lighting.<br>" +
			"A brightness value of 20 is the default.",
		position = 12,
		section = hdLightingSection
	)
	default int legacyBrightness()
	{
		return 20;
	}

	@Range(
		min = 25,
		max = 400
	)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "screenBrightness",
		name = "Brightness",
		description =
			"Controls the brightness of the game, excluding the UI.<br>" +
			"Adjust until the circle on the left is barely visible.",
		position = 13,
		section = hdLightingSection
	)
	default int brightness()
	{
		return 100;
	}

	@Range(
		min = -500,
		max = 500
	)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "fSaturation",
		name = "Saturation",
		description =
			"Controls the saturation of the final rendered image.<br>" +
			"Intended to be kept between 0% and 120%.",
		position = 14,
		section = hdLightingSection
	)
	default int saturation()
	{
		return 100;
	}

	@Range(
		min = -500,
		max = 500
	)
	@Units(Units.PERCENT)
	@ConfigItem(
		keyName = "fContrast",
		name = "Contrast",
		description =
			"Controls the contrast of the final rendered image.<br>" +
			"Intended to be kept between 90% and 110%.",
		position = 15,
		section = hdLightingSection
	)
	default int contrast()
	{
		return 100;
	}

	@ConfigItem(
		keyName = "colorFilter",
		name = "Color filter",
		description = "Apply a color filter to the game as a post-processing effect.",
		position = 16,
		section = hdLightingSection
	)
	default ColorFilter colorFilter()
	{
		return ColorFilter.NONE;
	}

	@ConfigSection(
		name = "117 HD: scene",
		description = "117 HD's scene, model and terrain settings.",
		position = 101
	)
	String hdSceneSection = "hdScene";

	@ConfigItem(
		keyName = "seasonalTheme",
		name = "Seasonal theme",
		description = "Festive themes for Gielinor.",
		position = 0,
		section = hdSceneSection
	)
	default SeasonalTheme seasonalTheme()
	{
		return SeasonalTheme.AUTOMATIC;
	}

	@ConfigItem(
		keyName = "seasonalHemisphere",
		name = "Seasonal hemisphere",
		description = "Determines which hemisphere the 'Automatic' seasonal theme should consider.",
		position = 1,
		section = hdSceneSection
	)
	default SeasonalHemisphere seasonalHemisphere()
	{
		return SeasonalHemisphere.NORTHERN;
	}

	@ConfigItem(
		keyName = "seasonalFoliage",
		name = "Seasonal foliage",
		description = "Allow seasonal themes to change the color of tree leaves.",
		position = 2,
		section = hdSceneSection
	)
	default boolean seasonalFoliage()
	{
		return true;
	}

	@ConfigItem(
		keyName = "infernalCape",
		name = "Infernal cape",
		description =
			"Replace the infernal cape texture with a more detailed version.<br>" +
			"Note: with Anisotropic filtering above zero, the cape may look blurry when zoomed out.",
		position = 3,
		section = hdSceneSection
	)
	default InfernalCape infernalCape()
	{
		return InfernalCape.HD;
	}

	@ConfigItem(
		keyName = "hideVanillaWaterEffects",
		name = "Hide vanilla water ripples",
		description = "Hide vanilla ripples found around objects floating in the water.",
		position = 4,
		section = hdSceneSection
	)
	default boolean hideVanillaWaterEffects()
	{
		return true;
	}

	@ConfigItem(
		keyName = "recolorGroundObjects",
		name = "Color-matched ground objects",
		description = "Recolors grass and other small ground objects to match the tile's color.",
		position = 5,
		section = hdSceneSection
	)
	default boolean recolorGroundObjects()
	{
		return true;
	}

	@ConfigItem(
		keyName = "fillGapsInTerrain",
		name = "Fill gaps in terrain",
		description = "Attempt to patch all holes in the ground, such as around trapdoors and ladders.",
		position = 6,
		section = hdSceneSection
	)
	default boolean fillGapsInTerrain()
	{
		return true;
	}

	@ConfigItem(
		keyName = "vanillaShadowMode",
		name = "Vanilla shadows",
		description =
			"Choose whether shadows built into models by Jagex should be hidden. This does not affect clickboxes.<br>" +
			"'Show in PvM' will retain shadows for falling crystals during the Olm fight and other useful cases.<br>" +
			"'Prefer in PvM' will do the above and also disable 117 HD's dynamic shadows for those models.",
		position = 7,
		section = hdSceneSection
	)
	default VanillaShadowMode vanillaShadowMode()
	{
		return VanillaShadowMode.SHOW_IN_PVM;
	}

	@ConfigItem(
		keyName = "flatShading",
		name = "Flat shading",
		description = "Gives a more low-poly look to the game.",
		position = 8,
		section = hdSceneSection
	)
	default boolean flatShading()
	{
		return false;
	}

	@ConfigItem(
		keyName = "vanillaColorBanding",
		name = "Vanilla color banding",
		description =
			"Blend between colors similarly to how it works in vanilla, with clearly defined bands of color.<br>" +
			"This isn't really noticeable on textured surfaces, and is intended to be used without ground textures.",
		position = 9,
		section = hdSceneSection
	)
	default boolean vanillaColorBanding()
	{
		return false;
	}

	@ConfigItem(
		keyName = "reduceOverExposure",
		name = "Legacy gray colors",
		description =
			"Previously, HD attempted to reduce overexposure by capping the maximum color brightness,<br>" +
			"which changed white colors into dull shades of gray. This option brings back that old behavior.",
		position = 10,
		section = hdSceneSection
	)
	default boolean legacyGreyColors()
	{
		return false;
	}

	@ConfigItem(
		keyName = "experimentalPreserveVanillaNormals",
		name = "Preserve vanilla normals",
		description = "Originally, 117 HD would respect vanilla normals, but these are often less accurate.",
		position = 11,
		section = hdSceneSection
	)
	default boolean preserveVanillaNormals()
	{
		return false;
	}
}
