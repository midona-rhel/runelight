/*
 * Copyright (c) 2026, Midona
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
package com.midonarhel.lumina.shading;

import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.scene.overrides.InheritTileColorType;
import com.midonarhel.lumina.scene.overrides.ModelOverride;
import com.midonarhel.lumina.scene.overrides.TileOverride;
import javax.annotation.Nullable;

/**
 * The one switch for where diffuse colours and the overrides that shape them come from: the 117 HD shading mode and
 * the colour mode, set from the config before a scene is uploaded. {@link FaceShading} (models), the model override
 * manager, the material manager and the scene uploader's terrain path ask this class, and nothing else decides it.
 * <p>
 * How the two settings combine:
 * <ul>
 * <li>Model base colours: the client's unlit face colours when the shading mode uses unlit colours or the colour mode
 * is Vanilla, otherwise the lit face colours (117 HD's default).</li>
 * <li>117 HD's colour changes (model colour overrides, textured face lightness 90, tile override colour changes): only
 * in the 117 HD colour mode, whatever the shading mode.</li>
 * <li>Undoing vanilla shading (with the brightness caps): only in the 117 HD colour mode and when the shading mode
 * undoes vanilla shading.</li>
 * <li>Model overrides: whole in the 117 HD colour mode; in Vanilla mode reduced to the inherited tile colour
 * ({@link #modelOverride}), so no hiding, geometry, materials or colour changes apply.</li>
 * <li>Inherited tile colours ("Ground-matched foliage"): their own setting, in both colour modes; in Vanilla mode they
 * are the unlit tile colours.</li>
 * <li>Terrain: the client's lit tile colour with the tile override's change in 117 HD mode, the recomputed unlit tile
 * colour in Vanilla mode; tile overrides are not generated in Vanilla mode.</li>
 * <li>Vanilla textures: 117 HD's materials in 117 HD mode, plain per-texture materials (the texture, its own
 * transparency, nothing else) in Vanilla mode.</li>
 * <li>Material.NONE's brightness (0.8 with unlit colours), directional shading and the {@code UNLIT_FACE_COLORS}
 * request follow the shading mode, which the colour mode does not change (Vanilla mode adds the unlit request).</li>
 * </ul>
 */
public final class DiffuseColors
{
	public static volatile ShadingMode shadingMode = ShadingMode.DEFAULT;
	public static volatile ColorMode colorMode = ColorMode.HD117;

	/**
	 * The unlit colour of a textured face in Vanilla mode: white, the texture carries the colour. The client lights
	 * textured faces (models and terrain alike) with the light intensity alone, so this is the exact unlit base.
	 */
	public static final int VANILLA_TEXTURED_COLOR = 127;

	// the inherit-only overrides Vanilla mode reduces model overrides to
	private static final ModelOverride INHERIT_UNDERLAY = inheritOnly(InheritTileColorType.UNDERLAY);
	private static final ModelOverride INHERIT_OVERLAY = inheritOnly(InheritTileColorType.OVERLAY);

	private DiffuseColors()
	{
	}

	private static ModelOverride inheritOnly(InheritTileColorType type)
	{
		ModelOverride o = ModelOverride.NONE.copy();
		o.description = "Vanilla mode: inherit " + type;
		o.inheritTileColorType = type;
		return o;
	}

	/**
	 * Whether the client must provide unlit model face colours ({@code DrawCallbacks.UNLIT_FACE_COLORS}).
	 */
	public static boolean requestUnlitFaceColors()
	{
		return shadingMode.unlitFaceColors || colorMode == ColorMode.VANILLA;
	}

	/**
	 * Whether models use the client's unlit face colours.
	 */
	public static boolean useUnlitModelColors()
	{
		return shadingMode.unlitFaceColors || colorMode == ColorMode.VANILLA;
	}

	/**
	 * Whether 117 HD's colour changes apply (model colour overrides, textured lightness 90, tile override colours).
	 */
	public static boolean apply117ColorChanges()
	{
		return colorMode == ColorMode.HD117;
	}

	/**
	 * Whether 117 HD's undo of vanilla model shading applies (before the per-override switch).
	 */
	public static boolean undoVanillaShading()
	{
		return colorMode == ColorMode.HD117 && shadingMode.undoVanillaShading;
	}

	/**
	 * Whether terrain uses the unlit tile colours recomputed from the floor definitions, and tile overrides are off.
	 */
	public static boolean useUnlitTerrainColors()
	{
		return colorMode == ColorMode.VANILLA;
	}

	/**
	 * Whether vanilla textures use plain per-texture materials instead of 117 HD's.
	 */
	public static boolean useVanillaMaterials()
	{
		return colorMode == ColorMode.VANILLA;
	}

	/**
	 * The "Ground-matched foliage" setting: whether static models inherit the colour of the tile below them
	 * (117 HD's inheritTileColorType), in both colour modes.
	 */
	public static boolean inheritTileColors()
	{
		return HdSettings.recolorGroundObjects;
	}

	/**
	 * The model override that applies under the colour mode: the override itself in 117 HD mode; in Vanilla mode
	 * only its inherited tile colour survives (NONE when it has none).
	 */
	public static ModelOverride modelOverride(ModelOverride override)
	{
		if (colorMode != ColorMode.VANILLA || override == ModelOverride.NONE)
		{
			return override;
		}
		switch (override.inheritTileColorType)
		{
			case UNDERLAY:
				return INHERIT_UNDERLAY;
			case OVERLAY:
				return INHERIT_OVERLAY;
			default:
				return ModelOverride.NONE;
		}
	}

	/**
	 * 117 HD's Material.NONE brightness: "arbitrarily account for brightness increase from using unlit colors".
	 */
	public static float materialNoneBrightness()
	{
		return shadingMode.unlitFaceColors ? .8f : 1;
	}

	/**
	 * The colour of one terrain vertex.
	 *
	 * @param litHsl          the client's (lit) tile colour of the vertex
	 * @param unlitHsl        the recomputed unlit tile colour of the vertex's tile and layer, or -1 if there is none
	 * @param override        the tile override of the face
	 * @param overrideApplies whether 117 HD applies the override's colour change here (it does for any override other
	 *                        than NONE on land; with ground blending off this is the whole of its terrain colour path)
	 */
	public static int terrainColor(int litHsl, int unlitHsl, TileOverride override, boolean overrideApplies)
	{
		if (colorMode == ColorMode.VANILLA)
		{
			return unlitHsl != -1 ? unlitHsl : litHsl;
		}
		return overrideApplies ? override.modifyColor(litHsl) : litHsl;
	}

	/**
	 * The colour a model face inherits from a tile.
	 *
	 * @param litHsl   the client's (lit) tile colour 117 HD inherits
	 * @param unlitHsl the recomputed unlit tile colour, or -1 if there is none
	 * @param override the tile override whose colour change 117 HD applies to the inherited colour
	 */
	public static int inheritedTileColor(int litHsl, int unlitHsl, @Nullable TileOverride override)
	{
		if (colorMode == ColorMode.VANILLA)
		{
			return unlitHsl != -1 ? unlitHsl : litHsl;
		}
		return override == null ? litHsl : override.modifyColor(litHsl);
	}
}
