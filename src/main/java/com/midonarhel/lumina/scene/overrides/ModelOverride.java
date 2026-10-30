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
package com.midonarhel.lumina.scene.overrides;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.annotations.SerializedName;
import com.midonarhel.lumina.config.HdSettings;
import com.midonarhel.lumina.config.SeasonalTheme;
import com.midonarhel.lumina.scene.materials.Material;
import com.midonarhel.lumina.scene.materials.MaterialManager;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Model;
import com.midonarhel.lumina.scene.areas.AABB;
import com.midonarhel.lumina.util.Gamevals;
import java.util.Collections;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import static com.midonarhel.lumina.util.ExpressionParser.asExpression;
import static com.midonarhel.lumina.util.ExpressionParser.parseExpression;

/**
 * One entry of model_overrides.json (117 HD's ModelOverride), without wind, shadows and custom UVs/face materials
 * (unused by the data): which faces are hidden, normals, colour and alpha changes (also per colour through
 * {@link #colorOverrides}), tile colour inheritance, depth bias, whether vanilla shading is undone, materials (those
 * that survive 117 HD's model textures setting being off), UV types, rotation, height offsets and vertex snapping.
 */
@Slf4j
@NoArgsConstructor
public class ModelOverride
{
	public static final ModelOverride NONE = new ModelOverride(true);

	private static final Set<Integer> EMPTY = Collections.emptySet();

	public String description = "UNKNOWN";

	// When, where or what the override should apply to
	public SeasonalTheme seasonalTheme;
	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] areas = {};
	@JsonAdapter(Gamevals.NpcAdapter.class)
	public Set<Integer> npcIds = EMPTY;
	@JsonAdapter(Gamevals.ObjectAdapter.class)
	public Set<Integer> objectIds = EMPTY;
	@JsonAdapter(Gamevals.SpotanimAdapter.class)
	public Set<Integer> projectileIds = EMPTY;
	@JsonAdapter(Gamevals.SpotanimAdapter.class)
	public Set<Integer> graphicsObjectIds = EMPTY;

	public boolean hide = false;
	public boolean flatNormals = false;
	public boolean upwardsNormals = false;
	public boolean hideVanillaShadows = false;
	public boolean retainVanillaShadowsInPvm = false;
	public boolean undoVanillaShading = true;
	public boolean doubleSidedFaces = false;
	@SerializedName(value = "material", alternate = "baseMaterial")
	private String materialName;
	@SerializedName("textureMaterial")
	private String textureMaterialName;
	public transient Material material = Material.NONE;
	public transient Material textureMaterial = Material.NONE;
	public UvType uvType = UvType.VANILLA;
	public float uvScale = 1;
	public int uvOrientation = 0;
	public int uvOrientationX = 0;
	public int uvOrientationY = 0;
	public int uvOrientationZ = 0;
	public boolean retainVanillaUvs = true;
	public boolean forceMaterialChanges = false;
	@SerializedName("materialOverrides")
	private Map<String, ModelOverride> rawMaterialOverrides;
	public transient Map<Material, ModelOverride> materialOverrides;
	public transient boolean modifiesVanillaTexture;
	public transient boolean mightBeDoubleSided;
	// model orientation override in degrees (0: keep), height offset, and snapping of low vertices to the terrain
	public int rotate = 0;
	public int heightOffset = 0;
	public boolean terrainVertexSnap = false;
	public float terrainVertexSnapThreshold = 0.125f;
	private boolean hideAsWaterEffect = false;
	public InheritTileColorType inheritTileColorType = InheritTileColorType.NONE;
	public int depthBias = -1;

	private int setHue = -1;
	private int shiftHue;
	private int minHue;
	private int maxHue = 63;
	private int setSaturation = -1;
	private int shiftSaturation;
	private int minSaturation;
	private int maxSaturation = 7;
	private int setLightness = -1;
	private int shiftLightness;
	private int minLightness;
	private int maxLightness = 127;
	private int setAlpha = -1;
	private int shiftAlpha;
	private int minAlpha;
	private int maxAlpha = 255;

	@JsonAdapter(AABB.ArrayAdapter.class)
	public AABB[] hideInAreas = {};

	public ModelOverride[] colorOverrides;

	private JsonElement colors;

	public transient boolean isDummy;
	public transient ModelOverride[] areaOverrides;
	public transient AABB[] areaOverrideBounds;
	public transient AhslPredicate ahslCondition;
	public transient boolean mightHaveTransparency;
	public transient boolean modifiesColor;
	public transient boolean modifiesAlpha;

	// Not volatile: a stale read only falls back to the full lookup
	private transient long cachedColorOverrideAhsl = -1;

	@FunctionalInterface
	public interface AhslPredicate
	{
		boolean test(int ahsl);
	}

	private ModelOverride(boolean isDummy)
	{
		this.isDummy = isDummy;
	}

	private static Material resolveMaterial(String name, String description)
	{
		if (name == null)
		{
			return Material.NONE;
		}
		Material m = MaterialManager.getMaterial(name);
		if (m == null)
		{
			log.error("Unknown material '{}' in model override '{}'", name, description);
			return Material.NONE;
		}
		return m;
	}

	public void normalize()
	{
		// (the "Ground-matched foliage" setting is applied at upload time, DiffuseColors.inheritTileColors)
		if (inheritTileColorType == null)
		{
			inheritTileColorType = InheritTileColorType.NONE;
		}

		material = resolveMaterial(materialName, description);
		textureMaterial = resolveMaterial(textureMaterialName, description);
		if (uvType == null)
		{
			uvType = UvType.VANILLA;
		}

		modifiesVanillaTexture = textureMaterial.modifiesVanillaTexture;

		// 117 HD with model textures off: materials that change vanilla textures are dropped
		boolean disableTextures = !forceMaterialChanges;
		if (disableTextures)
		{
			if (material.modifiesVanillaTexture)
			{
				material = Material.NONE;
			}
			if (textureMaterial.modifiesVanillaTexture)
			{
				textureMaterial = Material.NONE;
			}
		}

		if (setHue != -1)
		{
			minHue = maxHue = setHue;
		}
		if (setSaturation != -1)
		{
			minSaturation = maxSaturation = setSaturation;
		}
		if (setLightness != -1)
		{
			minLightness = maxLightness = setLightness;
		}
		if (setAlpha != -1)
		{
			minAlpha = maxAlpha = setAlpha;
		}

		// Enforce sensible limits
		minHue = clamp(minHue, 0, 0x3F);
		maxHue = clamp(maxHue, 0, 0x3F);
		minSaturation = clamp(minSaturation, 0, 0x7);
		maxSaturation = clamp(maxSaturation, 0, 0x7);
		minLightness = clamp(minLightness, 0, 0x7F);
		maxLightness = clamp(maxLightness, 0, 0x7F);
		minAlpha = clamp(minAlpha, 0, 0xFF);
		maxAlpha = clamp(maxAlpha, 0, 0xFF);

		modifiesColor =
			shiftHue != 0 || minHue != 0 || maxHue != 0x3F ||
			shiftSaturation != 0 || minSaturation != 0 || maxSaturation != 0x7 ||
			shiftLightness != 0 || minLightness != 0 || maxLightness != 0x7F ||
			shiftAlpha != 0 || minAlpha != 0 || maxAlpha != 0xFF;
		modifiesAlpha = shiftAlpha != 0 || minAlpha != 0 || maxAlpha != 0xFF;

		if (areas == null)
		{
			areas = new AABB[0];
		}
		if (hideInAreas == null)
		{
			hideInAreas = new AABB[0];
		}
		if (npcIds == null)
		{
			npcIds = EMPTY;
		}
		if (objectIds == null)
		{
			objectIds = EMPTY;
		}
		if (projectileIds == null)
		{
			projectileIds = EMPTY;
		}
		if (graphicsObjectIds == null)
		{
			graphicsObjectIds = EMPTY;
		}

		mightHaveTransparency =
			material.hasTransparency ||
				textureMaterial.hasTransparency ||
				modifiesAlpha && minAlpha < 255;

		mightBeDoubleSided =
			doubleSidedFaces ||
				material.doubleSidedFaces ||
				textureMaterial.doubleSidedFaces;

		if (rawMaterialOverrides != null)
		{
			var normalized = new HashMap<Material, ModelOverride>();
			for (var entry : rawMaterialOverrides.entrySet())
			{
				var override = entry.getValue();
				if (override == null)
				{
					continue;
				}
				override.normalize();
				if (disableTextures && override.modifiesVanillaTexture)
				{
					continue;
				}
				Material key = MaterialManager.getMaterial(entry.getKey());
				if (key == null)
				{
					log.error("Unknown material '{}' in model override '{}'", entry.getKey(), description);
					continue;
				}
				mightHaveTransparency |= override.mightHaveTransparency;
				mightBeDoubleSided |= override.mightBeDoubleSided;
				normalized.put(key, override);
			}
			materialOverrides = normalized.isEmpty() ? null : normalized;
		}

		if (uvOrientationX == 0)
		{
			uvOrientationX = uvOrientation;
		}
		if (uvOrientationY == 0)
		{
			uvOrientationY = uvOrientation;
		}
		if (uvOrientationZ == 0)
		{
			uvOrientationZ = uvOrientation;
		}

		hide |= hideAsWaterEffect && HdSettings.hideVanillaWaterEffects;

		if (retainVanillaShadowsInPvm && HdSettings.vanillaShadowMode.retainInPvm)
		{
			hideVanillaShadows = false;
		}

		if (colorOverrides != null)
		{
			for (var override : colorOverrides)
			{
				override.normalize();
				mightHaveTransparency |= override.mightHaveTransparency;
				override.ahslCondition = parseAhslConditions(override.colors);
			}
		}
	}

	public ModelOverride copy()
	{
		var copy = new ModelOverride();
		copy.description = description;
		copy.seasonalTheme = seasonalTheme;
		copy.areas = areas;
		copy.npcIds = npcIds;
		copy.objectIds = objectIds;
		copy.projectileIds = projectileIds;
		copy.graphicsObjectIds = graphicsObjectIds;
		copy.hide = hide;
		copy.flatNormals = flatNormals;
		copy.upwardsNormals = upwardsNormals;
		copy.hideVanillaShadows = hideVanillaShadows;
		copy.retainVanillaShadowsInPvm = retainVanillaShadowsInPvm;
		copy.undoVanillaShading = undoVanillaShading;
		copy.doubleSidedFaces = doubleSidedFaces;
		copy.materialName = materialName;
		copy.textureMaterialName = textureMaterialName;
		copy.material = material;
		copy.textureMaterial = textureMaterial;
		copy.uvType = uvType;
		copy.uvScale = uvScale;
		copy.uvOrientation = uvOrientation;
		copy.uvOrientationX = uvOrientationX;
		copy.uvOrientationY = uvOrientationY;
		copy.uvOrientationZ = uvOrientationZ;
		copy.retainVanillaUvs = retainVanillaUvs;
		copy.forceMaterialChanges = forceMaterialChanges;
		copy.rawMaterialOverrides = rawMaterialOverrides;
		copy.materialOverrides = materialOverrides;
		copy.modifiesVanillaTexture = modifiesVanillaTexture;
		copy.mightBeDoubleSided = mightBeDoubleSided;
		copy.rotate = rotate;
		copy.heightOffset = heightOffset;
		copy.terrainVertexSnap = terrainVertexSnap;
		copy.terrainVertexSnapThreshold = terrainVertexSnapThreshold;
		copy.hideAsWaterEffect = hideAsWaterEffect;
		copy.inheritTileColorType = inheritTileColorType;
		copy.depthBias = depthBias;
		copy.setHue = setHue;
		copy.shiftHue = shiftHue;
		copy.minHue = minHue;
		copy.maxHue = maxHue;
		copy.setSaturation = setSaturation;
		copy.shiftSaturation = shiftSaturation;
		copy.minSaturation = minSaturation;
		copy.maxSaturation = maxSaturation;
		copy.setLightness = setLightness;
		copy.shiftLightness = shiftLightness;
		copy.minLightness = minLightness;
		copy.maxLightness = maxLightness;
		copy.setAlpha = setAlpha;
		copy.shiftAlpha = shiftAlpha;
		copy.minAlpha = minAlpha;
		copy.maxAlpha = maxAlpha;
		copy.hideInAreas = hideInAreas;
		copy.colorOverrides = colorOverrides;
		copy.colors = colors;
		copy.isDummy = isDummy;
		copy.areaOverrides = areaOverrides;
		copy.areaOverrideBounds = areaOverrideBounds;
		copy.ahslCondition = ahslCondition;
		copy.mightHaveTransparency = mightHaveTransparency;
		copy.modifiesColor = modifiesColor;
		copy.modifiesAlpha = modifiesAlpha;
		return copy;
	}

	private AhslPredicate parseAhslConditions(JsonElement element)
	{
		if (element == null)
		{
			return ahsl -> false;
		}

		JsonArray arr;
		if (element.isJsonArray())
		{
			arr = element.getAsJsonArray();
		}
		else
		{
			arr = new JsonArray();
			arr.add(element);
		}

		AhslPredicate combinedPredicate = null;

		for (var el : arr)
		{
			if (el.isJsonNull())
			{
				continue;
			}
			if (!el.isJsonPrimitive())
			{
				log.warn("Skipping unexpected HSL condition '{}' in override '{}'", el, description);
				continue;
			}

			AhslPredicate condition;
			var prim = el.getAsJsonPrimitive();
			if (prim.isBoolean())
			{
				boolean bool = prim.getAsBoolean();
				condition = ahsl -> bool;
			}
			else if (prim.isNumber())
			{
				int targetHsl;
				try
				{
					targetHsl = prim.getAsInt();
				}
				catch (Exception ex)
				{
					log.warn("Expected integer, but got {} in override '{}'", el, description);
					continue;
				}
				condition = ahsl -> (ahsl & 0xFFFF) == targetHsl;
			}
			else if (prim.isString())
			{
				var predicate = asExpression(parseExpression(prim.getAsString())).toPredicate();
				condition = ahsl -> predicate.test(key ->
				{
					switch (key)
					{
						case "a":
							return ahsl >>> 16 & 0xFF;
						case "h":
							return ahsl >>> 10 & 0x3F;
						case "s":
							return ahsl >>> 7 & 0x7;
						case "l":
							return ahsl & 0x7F;
						case "ahsl":
							return ahsl;
						case "hsl":
							return ahsl & 0xFFFF;
						default:
							return 0;
					}
				});
			}
			else
			{
				log.warn("Skipping unexpected HSL condition primitive '{}' in override '{}'", el, description);
				continue;
			}

			if (combinedPredicate == null)
			{
				combinedPredicate = condition;
			}
			else
			{
				var prev = combinedPredicate;
				combinedPredicate = ahsl -> prev.test(ahsl) || condition.test(ahsl);
			}
		}

		return combinedPredicate == null ? ahsl -> false : combinedPredicate;
	}

	private static final float JAU_TO_RAD = (float) (Math.PI * 2 / 2048);
	private static final int LOCAL_TILE_SIZE = 128;

	/**
	 * 117 HD's fillUvsForFace for the computed UV types (the zone path computes VANILLA UVs itself): writes uvw for
	 * the face's three vertices at offsets 0, 4 and 8.
	 *
	 * @param orientation the model's pre-orientation (from the object's config)
	 */
	public void fillUvsForFace(float[] out, Model model, int orientation, UvType uvType, int face, float[] workingSpace)
	{
		switch (uvType)
		{
			case WORLD_XY:
			case WORLD_XZ:
			case WORLD_YZ:
				uvType.computeWorldUvw(out, 0, uvScale);
				uvType.computeWorldUvw(out, 4, uvScale);
				uvType.computeWorldUvw(out, 8, uvScale);
				break;
			case MODEL_XY:
			case MODEL_XY_MIRROR_A:
			case MODEL_XY_MIRROR_B:
			case MODEL_XZ:
			case MODEL_XZ_MIRROR_A:
			case MODEL_XZ_MIRROR_B:
			case MODEL_YZ:
			case MODEL_YZ_MIRROR_A:
			case MODEL_YZ_MIRROR_B:
			{
				final float[] vertexX = model.getVerticesX();
				final float[] vertexY = model.getVerticesY();
				final float[] vertexZ = model.getVerticesZ();
				final int triA = model.getFaceIndices1()[face];
				final int triB = model.getFaceIndices2()[face];
				final int triC = model.getFaceIndices3()[face];

				computeModelUvw(out, 0, vertexX[triA], vertexY[triA], vertexZ[triA], orientation, uvType);
				computeModelUvw(out, 4, vertexX[triB], vertexY[triB], vertexZ[triB], orientation, uvType);
				computeModelUvw(out, 8, vertexX[triC], vertexY[triC], vertexZ[triC], orientation, uvType);
				break;
			}
			case BOX:
				computeBoxUvw(out, model, orientation, face, workingSpace);
				break;
			case GEOMETRY:
			default:
				out[0] = 0;
				out[1] = 0;
				out[2] = 0;
				out[4] = 1;
				out[5] = 0;
				out[6] = 0;
				out[8] = 0;
				out[9] = 1;
				out[10] = 0;
				break;
		}
	}

	private void computeModelUvw(float[] out, int i, float x, float y, float z, int orientation, UvType uvType)
	{
		float rad, cos, sin;
		float temp;
		if (orientation % 2048 != 0)
		{
			// Reverse baked vertex rotation
			rad = orientation * JAU_TO_RAD;
			cos = (float) Math.cos(rad);
			sin = (float) Math.sin(rad);
			temp = x * sin + z * cos;
			x = x * cos - z * sin;
			z = temp;
		}

		x = (x / LOCAL_TILE_SIZE + .5f) / uvScale;
		y = (y / LOCAL_TILE_SIZE + .5f) / uvScale;
		z = (z / LOCAL_TILE_SIZE + .5f) / uvScale;

		uvType.computeModelUvw(out, i, x, y, z);

		if (uvOrientation % 2048 != 0)
		{
			rad = uvOrientation * JAU_TO_RAD;
			cos = (float) Math.cos(rad);
			sin = (float) Math.sin(rad);
			x = out[i] - .5f;
			z = out[i + 1] - .5f;
			temp = x * sin + z * cos;
			x = x * cos - z * sin;
			z = temp;
			out[i] = x + .5f;
			out[i + 1] = z + .5f;
		}
	}

	private static void rotateUvs(float[] out, float[] v, int uvOrientation)
	{
		if (uvOrientation % 2048 == 0)
		{
			return;
		}
		float rad = uvOrientation * JAU_TO_RAD;
		float cos = (float) Math.cos(rad);
		float sin = (float) Math.sin(rad);
		for (int i = 0; i < 3; i++)
		{
			int j = i * 4;
			v[i * 3] = out[j] - .5f;
			v[i * 3 + 2] = out[j + 1] - .5f;
			float temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
			v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
			v[i * 3 + 2] = temp;
			out[j] = v[i * 3] + .5f;
			out[j + 1] = v[i * 3 + 2] + .5f;
		}
	}

	private void computeBoxUvw(float[] out, Model model, int modelOrientation, int face, float[] v)
	{
		final float[] verticesX = model.getVerticesX();
		final float[] verticesY = model.getVerticesY();
		final float[] verticesZ = model.getVerticesZ();

		int vidx;
		vidx = model.getFaceIndices1()[face];
		v[0] = verticesX[vidx];
		v[1] = verticesY[vidx];
		v[2] = verticesZ[vidx];
		vidx = model.getFaceIndices2()[face];
		v[3] = verticesX[vidx];
		v[4] = verticesY[vidx];
		v[5] = verticesZ[vidx];
		vidx = model.getFaceIndices3()[face];
		v[6] = verticesX[vidx];
		v[7] = verticesY[vidx];
		v[8] = verticesZ[vidx];

		if (modelOrientation % 2048 != 0)
		{
			// Reverse baked vertex rotation
			float rad = modelOrientation * JAU_TO_RAD;
			float cos = (float) Math.cos(rad);
			float sin = (float) Math.sin(rad);

			for (int i = 0; i < 3; i++)
			{
				float temp = v[i * 3] * sin + v[i * 3 + 2] * cos;
				v[i * 3] = v[i * 3] * cos - v[i * 3 + 2] * sin;
				v[i * 3 + 2] = temp;
			}
		}

		for (int i = 0; i < 3; i++)
		{
			v[i * 3] = (v[i * 3] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i * 3 + 1] = (v[i * 3 + 1] / LOCAL_TILE_SIZE + .5f) / uvScale;
			v[i * 3 + 2] = (v[i * 3 + 2] / LOCAL_TILE_SIZE + .5f) / uvScale;
		}

		// Compute face normal as cross(v[1] - v[0], v[2] - v[0])
		float nx = (v[3 + 1] - v[1]) * (v[6 + 2] - v[2]) - (v[3 + 2] - v[2]) * (v[6 + 1] - v[1]);
		float ny = (v[3 + 2] - v[2]) * (v[6] - v[0]) - (v[3] - v[0]) * (v[6 + 2] - v[2]);
		float nz = (v[3] - v[0]) * (v[6 + 1] - v[1]) - (v[3 + 1] - v[1]) * (v[6] - v[0]);
		float absNx = Math.abs(nx);
		float absNy = Math.abs(ny);
		float absNz = Math.abs(nz);

		out[2] = out[6] = out[10] = 0;
		if (absNx > absNy && absNx > absNz)
		{
			// YZ plane
			float flip = Math.signum(nx);
			for (int tri = 0; tri < 3; tri++)
			{
				out[tri * 4] = flip * -v[tri * 3 + 2];
				out[tri * 4 + 1] = v[tri * 3 + 1];
			}
			rotateUvs(out, v, uvOrientationX);
		}
		else if (absNy > absNx && absNy > absNz)
		{
			// XZ
			float flip = Math.signum(ny);
			for (int tri = 0; tri < 3; tri++)
			{
				out[tri * 4] = flip * -v[tri * 3];
				out[tri * 4 + 1] = v[tri * 3 + 2];
			}
			rotateUvs(out, v, uvOrientationY);
		}
		else
		{
			// XY
			float flip = Math.signum(nz);
			for (int tri = 0; tri < 3; tri++)
			{
				out[tri * 4] = flip * v[tri * 3];
				out[tri * 4 + 1] = v[tri * 3 + 1];
			}
			rotateUvs(out, v, uvOrientationZ);
		}
	}

	public int modifyAlpha(int alpha)
	{
		return clamp(alpha + shiftAlpha, minAlpha, maxAlpha);
	}

	public int modifyColor(int jagexHsl)
	{
		int h = jagexHsl >> 10 & 0x3F;
		h = clamp(h + shiftHue, minHue, maxHue);

		int s = jagexHsl >> 7 & 7;
		s = clamp(s + shiftSaturation, minSaturation, maxSaturation);

		int l = jagexHsl & 0x7F;
		l = clamp(l + shiftLightness, minLightness, maxLightness);

		return h << 10 | s << 7 | l;
	}

	/**
	 * The first colour override whose condition matches, or null.
	 *
	 * @param ahsl (255 - transparency) << 16 | lit face colour; the conditions are written against vanilla's lit colours
	 */
	@Nullable
	public final ModelOverride testColorOverrides(int ahsl)
	{
		ModelOverride override = null;
		final long packedAhsl = cachedColorOverrideAhsl;
		if (packedAhsl != -1 && ahsl == (int) packedAhsl)
		{
			override = colorOverrides[(int) (packedAhsl >> 32)];
		}

		if (override == null)
		{
			final int len = colorOverrides.length;
			for (int i = 0; i < len; ++i)
			{
				final var inner = colorOverrides[i];
				if (inner.ahslCondition.test(ahsl))
				{
					cachedColorOverrideAhsl = ahsl | (long) i << 32;
					override = inner;
					break;
				}
			}
		}

		return override;
	}

	private static int clamp(int v, int min, int max)
	{
		// 117 HD's MathUtils.clamp: min(max(v, min), max), so max wins when min > max
		return Math.min(Math.max(v, min), max);
	}

	@Override
	public String toString()
	{
		return description;
	}
}
