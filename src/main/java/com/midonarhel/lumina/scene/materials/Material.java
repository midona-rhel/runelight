/*
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
package com.midonarhel.lumina.scene.materials;

import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;

/**
 * A material from materials.json: 117 HD's Material (rs117.hd.scene.materials.Material) with the properties its
 * scene shader reads when textures are off (colour map, flow map, scrolling, texture scale, brightness, unlit,
 * overrideBaseColor, transparency, double-sided faces) plus what decides whether a model override may keep it
 * ({@link #modifiesVanillaTexture}). Normal, displacement, roughness, ambient occlusion and shadow maps, specular and
 * subsurface are kept only for that decision.
 */
public class Material
{
	public static final Material NONE = new Material("NONE");
	public static final Material UNLIT = new Material("UNLIT");

	static
	{
		UNLIT.parent = NONE;
		UNLIT.unlit = true;
	}

	public String name;
	@Nullable
	public Material parent;
	public int vanillaTextureIndex = -1;

	@Nullable
	public Material normalMap, displacementMap, roughnessMap, ambientOcclusionMap, flowMap, shadowAlphaMap;
	public boolean hasTransparency;
	public boolean doubleSidedFaces;
	public boolean seasonalFoliage;
	public boolean overrideBaseColor;
	public boolean unlit;
	// linear (materials.json gives it in sRGB)
	public float brightness = 1;
	public float subsurface;
	public float displacementScale = .1f;
	public float flowMapStrength;
	public float[] flowMapDuration = {0, 0};
	public float specularStrength;
	public float specularGloss;
	public float[] scrollSpeed = {0, 0};
	public float[] textureScale = {1, 1, 1};
	public List<String> materialsToReplace = Collections.emptyList();
	@Nullable
	public String replacementCondition;

	public transient boolean modifiesVanillaTexture;
	public transient boolean isFallbackVanillaMaterial;
	/**
	 * The material's slot in the scene shader's material table, or -1 before it is first used.
	 */
	transient int slot = -1;

	public Material(String name)
	{
		this.name = name;
	}

	/**
	 * 117 HD's Material.normalize (after references are resolved): drops a displacement map with no scale and
	 * decides whether the material contains a non-vanilla texture change (seasonal foliage ignores the setting).
	 */
	void normalize()
	{
		if (displacementScale == 0)
		{
			displacementMap = null;
		}
		flowMapDuration = ensureDefaults(flowMapDuration, NONE.flowMapDuration);
		scrollSpeed = ensureDefaults(scrollSpeed, NONE.scrollSpeed);
		textureScale = ensureDefaults(textureScale, NONE.textureScale);

		var base = this;
		while (base.parent != null)
		{
			base = base.parent;
		}
		modifiesVanillaTexture = !seasonalFoliage && (
			(base != NONE && base.vanillaTextureIndex == -1) ||
				normalMap != null ||
				displacementMap != null ||
				roughnessMap != null ||
				ambientOcclusionMap != null ||
				flowMap != null ||
				shadowAlphaMap != null
		);
	}

	/**
	 * 117 HD's isVanillaReplacement: the material defines a vanilla texture index of its own.
	 */
	boolean isVanillaReplacement()
	{
		return vanillaTextureIndex != -1 && (parent == null || parent.vanillaTextureIndex != vanillaTextureIndex);
	}

	/**
	 * 117 HD's getTextureName: the image file name, for materials that are not NONE or a vanilla fallback.
	 */
	@Nullable
	public String getTextureName()
	{
		if (this == NONE || isFallbackVanillaMaterial)
		{
			return null;
		}
		return name.toLowerCase();
	}

	private static float[] ensureDefaults(float[] array, float[] defaults)
	{
		if (array == null)
		{
			return defaults.clone();
		}
		if (array.length >= defaults.length)
		{
			return array;
		}
		float[] result = defaults.clone();
		System.arraycopy(array, 0, result, 0, array.length);
		return result;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
