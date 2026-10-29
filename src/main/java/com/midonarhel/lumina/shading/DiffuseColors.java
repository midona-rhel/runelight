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

import com.midonarhel.lumina.shading.ColorMode;
import com.midonarhel.lumina.shading.ShadingMode;
import com.midonarhel.lumina.scene.overrides.TileOverride;

/**
 * The one switch for where diffuse colours come from: the 117 HD shading mode and the colour mode, set from the
 * config before a scene is uploaded. {@link FaceShading} (models) and the scene uploader's terrain path ask this class,
 * and nothing else decides it.
 * <p>
 * How the two settings combine:
 * <ul>
 * <li>Model base colours: the client's unlit face colours when the shading mode uses unlit colours or the colour mode
 * is GPU, otherwise the lit face colours (117 HD's default).</li>
 * <li>117 HD's colour changes (model colour overrides, inherited tile colours, textured face lightness 90, tile
 * override colour changes): only in the 117 HD colour mode, whatever the shading mode.</li>
 * <li>Undoing vanilla shading (with the brightness caps): only in the 117 HD colour mode and when the shading mode
 * undoes vanilla shading.</li>
 * <li>Material.NONE's brightness (0.8 with unlit colours), directional shading and the {@code UNLIT_FACE_COLORS}
 * request follow the shading mode, which the colour mode does not change (GPU mode adds the unlit request).</li>
 * </ul>
 */
public final class DiffuseColors
{
	public static volatile ShadingMode shadingMode = ShadingMode.DEFAULT;
	public static volatile ColorMode colorMode = ColorMode.HD117;

	private DiffuseColors()
	{
	}

	/**
	 * Whether the client must provide unlit model face colours ({@code DrawCallbacks.UNLIT_FACE_COLORS}).
	 */
	public static boolean requestUnlitFaceColors()
	{
		return shadingMode.unlitFaceColors || colorMode == ColorMode.GPU;
	}

	/**
	 * Whether models use the client's unlit face colours.
	 */
	public static boolean useUnlitModelColors()
	{
		return shadingMode.unlitFaceColors || colorMode == ColorMode.GPU;
	}

	/**
	 * Whether 117 HD's colour changes apply (overrides, inherited tile colours, textured lightness 90).
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
	 * @param override        the tile override of the face
	 * @param overrideApplies whether 117 HD applies the override's colour change here (it does for any override other
	 *                        than NONE on land; with ground blending off this is the whole of its terrain colour path)
	 */
	public static int terrainColor(int litHsl, TileOverride override, boolean overrideApplies)
	{
		if (colorMode == ColorMode.GPU)
		{
			// HOOK (GPU colour mode): the exact unlit ground colour recomputed from the floor definitions goes here.
			// Until then the game's lit tile colour is used as is.
			return litHsl;
		}
		return overrideApplies ? override.modifyColor(litHsl) : litHsl;
	}
}
