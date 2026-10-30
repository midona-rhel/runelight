/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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

import com.midonarhel.lumina.scene.materials.Material;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import com.midonarhel.lumina.scene.overrides.InheritTileColorType;
import com.midonarhel.lumina.scene.overrides.ModelOverride;
import com.midonarhel.lumina.scene.overrides.UvType;
import javax.annotation.Nullable;
import net.runelite.api.Model;
import net.runelite.api.Perspective;

/**
 * Per-face colours, alpha, depth bias and normals of a model, ported literally from 117 HD's zone SceneUploader:
 * {@code uploadStaticModel} for static (zone) models and {@code preprocessTempModel} + {@code uploadTempModel} for
 * temporary/dynamic models. The two paths differ in 117 HD and are kept apart here ({@link #setModel} {@code temp}).
 * Where colours come from, and which part of the model override applies, is decided by {@link DiffuseColors}.
 * <p>
 * Materials are chosen as in 117 HD (override base/texture materials that survive its model textures setting being
 * off, vanilla texture materials, material overrides of vanilla textures, colour override materials) and decide the
 * UV type, transparency (alpha buffer) and double-sided faces.
 * <p>
 * Normals are 117 HD's int normals (vertex normals as the client gives them, face normals scaled by 2048), in world
 * orientation, Y down.
 */
public final class FaceShading
{
	public static final int SKIP = -1;

	private static final int TEXTURED_FACE_COLOR = 90;

	// subtracts the X lowest lightness levels from the formula.
	// helps keep darker colors appropriately dark
	private static final int IGNORE_LOW_LIGHTNESS = 3;
	// multiplier applied to vertex' lightness value.
	// results in greater lightening of lighter colors
	private static final float LIGHTNESS_MULTIPLIER = 3;
	// the minimum amount by which each color will be lightened
	private static final int BASE_LIGHTEN = 10;

	/**
	 * Supplies the colour a face inherits from the tile below a static model (model override
	 * {@code inheritTileColorType}).
	 */
	@FunctionalInterface
	public interface TileColorSource
	{
		/**
		 * @return the inherited colour, or -1 if the tile has none
		 */
		int inherit(InheritTileColorType type);
	}

	// Outputs of the last classified / shaded face
	public int color1, color2, color3;
	public int transparency;
	public int depthBias;
	public boolean upwardsNormals;
	// whether 117 HD also writes the face reversed, with negated normals (static path: face override or material
	// doubleSidedFaces)
	public boolean doubleSided;
	// whether the face goes to the alpha buffer (material transparency or face transparency)
	public boolean alpha;
	public Material material = Material.NONE;
	public UvType uvType = UvType.GEOMETRY;
	public ModelOverride faceOverride;
	public final int[] normals = new int[9];

	private Model model;
	private ModelOverride override;
	private byte[] textureFaces;
	private TileColorSource tileColors;
	private boolean temp;
	private int orientSin, orientCos;
	private boolean rotate;

	private boolean colorChanges, inheritColors, undoShading;
	private short[] unlitColors;
	private int[] colors1, colors2, colors3;
	private byte[] transparencies;
	private byte modelTransparency;
	private short[] faceTextures;
	private byte[] bias;
	private int[] normalsX, normalsY, normalsZ;
	private boolean modelHasNormals;
	private float[] verticesY;
	private float[] posX, posY, posZ;
	private int[] indices1, indices2, indices3;

	/**
	 * Starts a model.
	 *
	 * @param orientation the model's orientation (0-2047), used to rotate normals
	 * @param tileColors  the tile colours a static model may inherit, or null
	 * @param temp        whether the model goes through 117 HD's temp (dynamic) model path
	 * @param posX        the model's vertex positions after orientation, as the uploader writes them (117 HD computes
	 *                    face normals from these)
	 */
	public void setModel(Model model, ModelOverride override, int orientation, @Nullable TileColorSource tileColors, boolean temp,
		float[] posX, float[] posY, float[] posZ)
	{
		this.model = model;
		// Vanilla colour mode keeps only the inherited tile colour of an override
		this.override = override = DiffuseColors.modelOverride(override);
		this.tileColors = tileColors;
		this.temp = temp;
		this.posX = posX;
		this.posY = posY;
		this.posZ = posZ;

		orientation &= 2047;
		rotate = orientation != 0;
		orientSin = Perspective.SINE[orientation];
		orientCos = Perspective.COSINE[orientation];

		colorChanges = DiffuseColors.apply117ColorChanges();
		inheritColors = DiffuseColors.inheritTileColors();
		undoShading = DiffuseColors.undoVanillaShading();
		unlitColors = DiffuseColors.useUnlitModelColors() ? model.getUnlitFaceColors() : null;
		colors1 = model.getFaceColors1();
		colors2 = model.getFaceColors2();
		colors3 = model.getFaceColors3();
		transparencies = model.getFaceTransparencies();
		modelTransparency = model.getTransparency();
		faceTextures = model.getFaceTextures();
		textureFaces = model.getTextureFaces();
		bias = model.getFaceBias();
		normalsX = model.getVertexNormalsX();
		normalsY = model.getVertexNormalsY();
		normalsZ = model.getVertexNormalsZ();
		modelHasNormals = normalsX != null && normalsY != null && normalsZ != null;
		verticesY = model.getVerticesY();
		indices1 = model.getFaceIndices1();
		indices2 = model.getFaceIndices2();
		indices3 = model.getFaceIndices3();
	}

	/**
	 * Decides whether a face is drawn: hidden faces, clickboxes, override hiding (also per colour), fake ground
	 * shadows and override alpha changes, in 117 HD's order for the model's path.
	 *
	 * @return the face's final transparency (0 opaque to 255), or {@link #SKIP}
	 */
	public int classify(int face)
	{
		if (colors3[face] == -2)
		{
			return SKIP;
		}

		int transparency = readFaceTransparency(modelTransparency, transparencies, face);
		if (transparency == 255)
		{
			return SKIP;
		}

		final int textureId = faceTextures != null ? faceTextures[face] : -1;
		final boolean textured = textureId != -1;
		UvType uvType = UvType.GEOMETRY;
		Material material = override.material;
		faceOverride = override;
		if (textured)
		{
			uvType = UvType.VANILLA;
			if (override.textureMaterial != Material.NONE)
			{
				material = override.textureMaterial;
			}
			else
			{
				material = MaterialManager.fromVanillaTexture(textureId);
				if (override.materialOverrides != null)
				{
					var materialOverride = override.materialOverrides.get(material);
					if (materialOverride != null)
					{
						faceOverride = materialOverride;
						material = faceOverride.textureMaterial;
					}
				}
			}
		}
		else if (override.colorOverrides != null)
		{
			// The static path tests colour 1 after the unlit colour replacement, the temp path the lit colour 1
			int color1 = !temp && unlitColors != null ? unlitColors[face] & 0xFFFF : colors1[face];
			final var colorOverride = override.testColorOverrides((0xFF - transparency) << 16 | color1);
			if (colorOverride != null)
			{
				faceOverride = colorOverride;
				material = faceOverride.material;
			}
		}

		if (faceOverride.hide)
		{
			return SKIP;
		}

		if (material != Material.NONE)
		{
			uvType = faceOverride.uvType;
			if (textured && faceOverride.retainVanillaUvs)
			{
				uvType = UvType.VANILLA;
			}
		}
		// Fall back to geometry UVs if vanilla UVs aren't valid
		if (uvType == UvType.VANILLA && (!textured || textureFaces == null || textureFaces[face] == -1))
		{
			uvType = UvType.GEOMETRY;
		}
		this.material = material;
		this.uvType = uvType;

		// Hide fake shadows or lighting that is often baked into models (isBakedGroundShading excludes textured faces)
		if (HdSettings.hideFakeShadows && faceOverride.hideVanillaShadows && isBakedGroundShading(face))
		{
			return SKIP;
		}

		if (faceOverride.modifiesAlpha)
		{
			transparency = 255 - faceOverride.modifyAlpha(255 - transparency);
			// Only 117 HD's temp path drops faces made fully transparent
			if (temp && transparency == 255)
			{
				return SKIP;
			}
		}

		this.transparency = transparency;
		alpha = material.hasTransparency || transparency != 0;
		// 117 HD's temp path writes no reversed faces
		doubleSided = !temp && (faceOverride.doubleSidedFaces || material.doubleSidedFaces);
		return transparency;
	}

	/**
	 * Classifies a face and, if it is drawn, computes its colours, depth bias and normals.
	 *
	 * @return false if the face is skipped
	 */
	public boolean shade(int face)
	{
		if (classify(face) == SKIP)
		{
			return false;
		}

		final boolean textured = faceTextures != null && faceTextures[face] != -1;

		int c1 = colors1[face];
		int c2 = colors2[face];
		int c3 = colors3[face];
		if (c3 == -1)
		{
			c2 = c3 = c1;
		}
		if (unlitColors != null)
		{
			c1 = c2 = c3 = unlitColors[face] & 0xFFFF;
		}

		// 117 HD's static path keeps the shading of textured faces; its temp path only that of vanilla texture materials
		boolean keepShading = !temp && textured;
		if (textured)
		{
			// 117 HD: "Without overriding the color for textured faces, vanilla shading remains pretty noticeable".
			// Vanilla mode: the exact unlit base of a textured face is white (the client lights them with the light
			// intensity alone)
			c1 = c2 = c3 = colorChanges ? TEXTURED_FACE_COLOR : DiffuseColors.VANILLA_TEXTURED_COLOR;
		}
		else if (!temp && inheritColors && faceOverride.inheritTileColorType != InheritTileColorType.NONE && tileColors != null)
		{
			// Ground-matched foliage, in both colour modes (the source gives lit or unlit tile colours accordingly)
			int inherited = tileColors.inherit(faceOverride.inheritTileColorType);
			if (inherited >= 0)
			{
				c1 = c2 = c3 = inherited;
				// Let the shader know vanilla shading reversal should be skipped for this face
				keepShading = true;
			}
		}

		if (colorChanges)
		{
			// Vanilla textures should always have a constant white background
			if (material.vanillaTextureIndex != -1)
			{
				c1 = c2 = c3 = TEXTURED_FACE_COLOR;
				keepShading = true;
			}

			if (faceOverride.modifiesColor)
			{
				c1 = faceOverride.modifyColor(c1);
				c2 = faceOverride.modifyColor(c2);
				c3 = faceOverride.modifyColor(c3);
			}
		}
		else if (material.vanillaTextureIndex != -1)
		{
			// the temp path skips the undo for vanilla texture materials
			keepShading = true;
		}

		final int[] n = normals;
		final int a = indices1[face];
		final int b = indices2[face];
		final int c = indices3[face];
		final boolean flatNormals = !modelHasNormals || faceOverride.flatNormals;
		final boolean shouldRotateNormals;
		boolean shouldCalculateFaceNormal;
		if (flatNormals || !HdSettings.preserveVanillaNormals && colors3[face] == -1)
		{
			shouldRotateNormals = false;
			shouldCalculateFaceNormal = true;
		}
		else
		{
			shouldRotateNormals = rotate;
			shouldCalculateFaceNormal = (n[0] = normalsX[a]) == 0;
			shouldCalculateFaceNormal &= (n[1] = normalsY[a]) == 0;
			shouldCalculateFaceNormal &= (n[2] = normalsZ[a]) == 0;
			shouldCalculateFaceNormal &= (n[3] = normalsX[b]) == 0;
			shouldCalculateFaceNormal &= (n[4] = normalsY[b]) == 0;
			shouldCalculateFaceNormal &= (n[5] = normalsZ[b]) == 0;
			shouldCalculateFaceNormal &= (n[6] = normalsX[c]) == 0;
			shouldCalculateFaceNormal &= (n[7] = normalsY[c]) == 0;
			shouldCalculateFaceNormal &= (n[8] = normalsZ[c]) == 0;
		}

		if (shouldCalculateFaceNormal)
		{
			calculateFaceNormal(n,
				posX[a], posY[a], posZ[a],
				posX[b], posY[b], posZ[b],
				posX[c], posY[c], posZ[c]);
		}

		// (the temp path skips vanilla textured faces through their material; keepShading covers them here)
		if (undoShading && faceOverride.undoVanillaShading && !keepShading)
		{
			c1 = undoVanillaShading(c1, n[0], n[1], n[2]);
			c2 = undoVanillaShading(c2, n[3], n[4], n[5]);
			c3 = undoVanillaShading(c3, n[6], n[7], n[8]);
		}

		// 117 HD's static path also rotates a computed face normal (from already oriented vertices); the temp path
		// does not
		if (shouldRotateNormals && (!temp || !shouldCalculateFaceNormal))
		{
			rotateNormals(n, orientSin, orientCos);
		}

		// HSL override is not applied to textured faces (temp path; static models have none)
		if (temp && !textured && material.vanillaTextureIndex == -1 && model.getOverrideAmount() > 0)
		{
			byte hue = model.getOverrideHue(), sat = model.getOverrideSaturation(), lum = model.getOverrideLuminance(), amount = model.getOverrideAmount();
			c1 = interpolateHSL(c1, hue, sat, lum, amount);
			c2 = interpolateHSL(c2, hue, sat, lum, amount);
			c3 = interpolateHSL(c3, hue, sat, lum, amount);
		}

		color1 = c1;
		color2 = c2;
		color3 = c3;
		upwardsNormals = faceOverride.upwardsNormals;
		depthBias = faceOverride.depthBias != -1 ? faceOverride.depthBias : bias == null ? 0 : bias[face] & 0xFF;
		return true;
	}

	private boolean isBakedGroundShading(int face)
	{
		if (transparencies == null || (transparencies[face] & 0xFF) <= 100)
		{
			return false;
		}

		if (faceTextures != null && faceTextures[face] != -1)
		{
			return false;
		}

		float heightA = verticesY[indices1[face]];
		if (heightA < -8)
		{
			return false;
		}

		float heightB = verticesY[indices2[face]];
		float heightC = verticesY[indices3[face]];
		return heightA == heightB && heightA == heightC;
	}

	public static int readFaceTransparency(byte modelTransparency, byte[] transparencies, int face)
	{
		if (modelTransparency == -1)
		{
			return 255;
		}

		int faceTransparency = transparencies != null ? transparencies[face] & 0xFF : 0;
		if (faceTransparency >= 253)
		{
			// 253 & 254 are special faces like clickboxes which we don't want to render
			return 255;
		}

		if (modelTransparency != 0)
		{
			int t = modelTransparency & 255;
			int a = (253 - faceTransparency) * t >> 8;
			return faceTransparency + a;
		}

		return faceTransparency;
	}

	public static void calculateFaceNormal(
		int[] out,
		float vx1, float vy1, float vz1,
		float vx2, float vy2, float vz2,
		float vx3, float vy3, float vz3
	)
	{
		float e0x = vx2 - vx1;
		float e0y = vy2 - vy1;
		float e0z = vz2 - vz1;

		float e1x = vx3 - vx1;
		float e1y = vy3 - vy1;
		float e1z = vz3 - vz1;

		float nx = e0y * e1z - e0z * e1y;
		float ny = e0z * e1x - e0x * e1z;
		float nz = e0x * e1y - e0y * e1x;

		float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
		nx /= length;
		ny /= length;
		nz /= length;

		out[0] = out[3] = out[6] = (int) (nx * 2048);
		out[1] = out[4] = out[7] = (int) (ny * 2048);
		out[2] = out[5] = out[8] = (int) (nz * 2048);
	}

	public static void rotateNormals(int[] normals, int orientSin, int orientCos)
	{
		for (int i = 0; i < 9; i += 3)
		{
			int nx = normals[i], nz = normals[i + 2];
			normals[i] = (nz * orientSin + nx * orientCos) >> 16;
			normals[i + 2] = (nz * orientCos - nx * orientSin) >> 16;
		}
	}

	/**
	 * 117 HD's approximate reversal of vanilla model shading (normals unrotated).
	 */
	private static int undoVanillaShading(int color, int nx, int ny, int nz)
	{
		float len = nx * nx + ny * ny + nz * nz;
		float norm = nx + ny + nz;

		int s = (color >> 7) & 0x7;
		int l = color & 0x7F;

		// Approximately invert vanilla shading by brightening vertices that were likely darkened by vanilla based on
		// vertex normals. This process is error-prone, as not all models are lit by vanilla with the same light
		// direction, and some models even have baked lighting built into the model itself. In some cases, increasing
		// brightness in this way leads to overly bright colors, so we are forced to cap brightness at a relatively
		// low value for it to look acceptable in most cases.
		final float colorAdjust = BASE_LIGHTEN - l + (l < IGNORE_LOW_LIGHTNESS ? 0f : (l - IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER);

		if (len > 0f)
		{
			final float invLen = 1 / (float) Math.sqrt(len);
			final float lightDotNormal = norm * 0.57735026f * invLen;
			if (lightDotNormal > 0f)
			{
				l += (int) (lightDotNormal * colorAdjust);
			}
		}

		// Clamp brightness as detailed above
		l = Math.min(l, HdSettings.legacyGreyColors ? 55 : getMaxBrightness(s));

		// Preserve H, replace S & L
		return (color & 0xFC00) | (s << 7) | l;
	}

	private static int getMaxBrightness(int s)
	{
		// MAX_BRIGHTNESS_LOOKUP_TABLE
		// [127, 61, 59, 57, 56, 56, 55, 55]
		switch (s)
		{
			case 0:
				return 127;
			case 1:
				return 61;
			case 2:
				return 59;
			case 3:
				return 57;
			case 4:
			case 5:
				return 56;
			default:
				return 55;
		}
	}

	public static int interpolateHSL(int hsl, byte hue2, byte sat2, byte lum2, byte lerp)
	{
		int hue = hsl >> 10 & 63;
		int sat = hsl >> 7 & 7;
		int lum = hsl & 127;
		int amount = lerp & 255;
		if (hue2 != -1)
		{
			hue += amount * (hue2 - hue) >> 7;
		}
		if (sat2 != -1)
		{
			sat += amount * (sat2 - sat) >> 7;
		}
		if (lum2 != -1)
		{
			lum += amount * (lum2 - lum) >> 7;
		}
		return (hue << 10 | sat << 7 | lum) & 65535;
	}
}
