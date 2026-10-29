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

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.midonarhel.lumina.shading.DiffuseColors;
import com.midonarhel.lumina.shading.ShadingVariables;
import com.midonarhel.lumina.util.ColorUtils;
import com.midonarhel.lumina.util.ExpressionParser;
import com.midonarhel.lumina.util.GsonUtils;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Texture;

/**
 * 117 HD's MaterialManager (rs117.hd.scene.MaterialManager), for the user's settings: loads materials.json
 * (parents inherited shallowly, root NONE), applies material replacements with the fixed variables of
 * {@link ShadingVariables} (modelTextures and groundTextures off, SUMMER, seasonal foliage on, the infernal cape
 * setting, vanilla by default), maps vanilla texture ids to materials (adding VANILLA_i fallbacks with transparency
 * for textures without a material), and keeps the scene shader's material table (one slot per material in use).
 * <p>
 * Lumina ships almost none of 117 HD's images (see scene/textures/): a material whose owner's image is missing
 * draws its vanilla texture, or white (the vertex colour) when it has no vanilla texture, and a flow map whose image
 * is missing does not flow. Brightness and texture scale are kept unless 117 HD ships that image
 * (textures/117hd_images.txt): then they were tuned for the image and are neutralised; owners 117 HD has no image
 * for either apply them to the vanilla texture in 117 HD too, so Lumina keeps doing that.
 */
@Slf4j
public final class MaterialManager
{
	/**
	 * Material name to the material used for it, after replacements (117 HD's MATERIAL_MAP).
	 */
	public static Map<String, Material> MATERIAL_MAP = Map.of();
	private static Material[] VANILLA_TEXTURE_MAPPING;

	public static final int MAX_SLOTS = 256;
	private static final List<Material> SLOTS = new ArrayList<>();
	private static volatile boolean slotsDirty;
	// texture array layer of each HD image Lumina ships (set by the texture manager), by image name
	private static Map<String, Integer> imageLayers = Map.of();
	// whether the texture manager has told us which images are shipped (before that, nothing is "missing")
	private static boolean imagesKnown;
	// materials already warned about for a missing image (guarded by SLOTS)
	private static final Set<String> warnedMissingImage = new HashSet<>();
	// the image names 117 HD ships (textures/117hd_images.txt)
	private static Set<String> hdImages = Set.of();

	private MaterialManager()
	{
	}

	public static void load(Gson gson) throws IOException
	{
		hdImages = loadHdImageNames();
		JsonArray rawMaterials = GsonUtils.loadResource(gson, "materials.json", JsonArray.class);

		var rawMaterialMap = new HashMap<String, JsonObject>();
		var validMaterials = new ArrayList<JsonObject>();
		for (JsonElement element : rawMaterials)
		{
			if (!element.isJsonObject())
			{
				continue;
			}
			var mat = element.getAsJsonObject();
			var name = mat.get("name");
			if (name == null || !name.isJsonPrimitive())
			{
				continue;
			}
			if (rawMaterialMap.putIfAbsent(name.getAsString(), mat) != null)
			{
				log.error("Duplicate material name: '{}'", name);
				continue;
			}
			validMaterials.add(mat);
		}

		var resolved = new HashMap<String, JsonObject>();
		for (String name : rawMaterialMap.keySet())
		{
			resolveParentDefinition(name, rawMaterialMap, resolved, new HashSet<>());
		}

		// REQUIRED_MATERIALS first, then the file's materials in order
		var materials = new ArrayList<Material>();
		materials.add(Material.NONE);
		materials.add(Material.UNLIT);
		var parents = new HashMap<Material, String>();
		var references = new HashMap<Material, String[]>();
		for (var raw : validMaterials)
		{
			JsonObject json = resolved.get(raw.get("name").getAsString());
			if (json == null)
			{
				continue;
			}
			Material m = parse(json);
			materials.add(m);
			if (json.has("parent"))
			{
				parents.put(m, json.get("parent").getAsString());
			}
			references.put(m, new String[]{
				string(json, "normalMap"), string(json, "displacementMap"), string(json, "roughnessMap"),
				string(json, "ambientOcclusionMap"), string(json, "flowMap"), string(json, "shadowAlphaMap")
			});
		}

		var byName = new HashMap<String, Material>();
		for (var m : materials)
		{
			byName.put(m.name, m);
		}
		for (var m : materials)
		{
			String parent = parents.get(m);
			if (parent != null)
			{
				m.parent = byName.get(parent);
				if (m.parent == m)
				{
					m.parent = null;
				}
			}
			String[] refs = references.get(m);
			if (refs != null)
			{
				m.normalMap = refs[0] == null ? null : byName.get(refs[0]);
				m.displacementMap = refs[1] == null ? null : byName.get(refs[1]);
				m.roughnessMap = refs[2] == null ? null : byName.get(refs[2]);
				m.ambientOcclusionMap = refs[3] == null ? null : byName.get(refs[3]);
				m.flowMap = refs[4] == null ? null : byName.get(refs[4]);
				m.shadowAlphaMap = refs[5] == null ? null : byName.get(refs[5]);
			}
		}
		for (var m : materials)
		{
			m.normalize();
		}

		// "Arbitrarily account for brightness increase from using unlit colors"
		Material.NONE.brightness = DiffuseColors.materialNoneBrightness();

		// Assemble the material map, accounting for replacements
		var materialMap = new LinkedHashMap<String, Material>();
		var vanillaReplacements = new LinkedHashMap<Integer, Material>();
		for (var original : materials)
		{
			Material replacement = original;
			if (isInactiveReplacement(original))
			{
				replacement = Material.NONE;
			}
			else
			{
				// Apply material replacements from top to bottom
				for (var other : materials)
				{
					if (replaces(other, replacement.name))
					{
						replacement = other;
					}
				}
			}
			materialMap.put(original.name, replacement);
			if (original.isVanillaReplacement())
			{
				vanillaReplacements.put(original.vanillaTextureIndex, replacement);
			}
		}
		MATERIAL_MAP = materialMap;
		VANILLA_REPLACEMENTS = vanillaReplacements;
		VANILLA_TEXTURE_MAPPING = null;
		synchronized (SLOTS)
		{
			SLOTS.clear();
			for (var m : materials)
			{
				m.slot = -1;
			}
		}
		slot(Material.NONE);
		log.debug("Loaded {} materials", materials.size());
	}

	private static Map<Integer, Material> VANILLA_REPLACEMENTS = Map.of();

	/**
	 * 117 HD sets Material.NONE's brightness from the shading mode (0.8 with unlit face colours).
	 */
	public static void updateNoneBrightness()
	{
		Material.NONE.brightness = DiffuseColors.materialNoneBrightness();
		slotsDirty = true;
	}

	/**
	 * 117 HD's VANILLA_TEXTURE_MAPPING, completed with VANILLA_i fallbacks (with transparency) for vanilla textures
	 * without a material. Built once the client's textures are known.
	 */
	public static synchronized void initVanillaTextures(Texture[] vanillaTextures)
	{
		if (VANILLA_TEXTURE_MAPPING != null && VANILLA_TEXTURE_MAPPING.length == vanillaTextures.length)
		{
			return;
		}
		var mapping = new Material[vanillaTextures.length];
		for (var entry : VANILLA_REPLACEMENTS.entrySet())
		{
			if (entry.getKey() < mapping.length)
			{
				mapping[entry.getKey()] = entry.getValue();
			}
		}
		var map = new LinkedHashMap<>(MATERIAL_MAP);
		for (int i = 0; i < mapping.length; i++)
		{
			if (vanillaTextures[i] == null || mapping[i] != null)
			{
				continue;
			}
			var m = new Material("VANILLA_" + i);
			m.vanillaTextureIndex = i;
			m.isFallbackVanillaMaterial = true;
			m.hasTransparency = true;
			m.normalize();
			map.put(m.name, m);
			mapping[i] = m;
		}
		MATERIAL_MAP = map;
		VANILLA_TEXTURE_MAPPING = mapping;
	}

	/**
	 * 117 HD's fromVanillaTexture.
	 */
	public static Material fromVanillaTexture(int vanillaTextureId)
	{
		Material[] mapping = VANILLA_TEXTURE_MAPPING;
		if (mapping == null || vanillaTextureId < 0 || vanillaTextureId >= mapping.length || mapping[vanillaTextureId] == null)
		{
			return Material.NONE;
		}
		return mapping[vanillaTextureId];
	}

	/**
	 * The material used for a material name (after replacements), or null if unknown.
	 */
	@Nullable
	public static Material getMaterial(String name)
	{
		return MATERIAL_MAP.get(name);
	}

	/**
	 * The material's slot in the scene shader's material table (0 is NONE; full tables fall back to NONE).
	 */
	public static int slot(Material material)
	{
		int slot = material.slot;
		if (slot >= 0)
		{
			return slot;
		}
		synchronized (SLOTS)
		{
			if (material.slot >= 0)
			{
				return material.slot;
			}
			if (SLOTS.size() >= MAX_SLOTS)
			{
				log.warn("Material table full, drawing {} as NONE", material);
				return 0;
			}
			material.slot = SLOTS.size();
			SLOTS.add(material);
			slotsDirty = true;
			return material.slot;
		}
	}

	/**
	 * Sets the texture array layers of the HD images Lumina ships, by image name.
	 */
	public static void setImageLayers(Map<String, Integer> layers)
	{
		imageLayers = layers;
		imagesKnown = true;
		synchronized (SLOTS)
		{
			warnedMissingImage.clear();
		}
		slotsDirty = true;
	}

	/**
	 * 117 HD's texture owner: the material's root, whose image (or vanilla texture) the material draws.
	 */
	private static Material owner(Material material)
	{
		var owner = material;
		while (owner.parent != null)
		{
			owner = owner.parent;
		}
		return owner;
	}

	/**
	 * The image name of the material's owner when 117 HD ships that image and Lumina does not, else null (no image
	 * in 117 HD either, or shipped).
	 */
	@Nullable
	private static String missingHdImage(Material material)
	{
		String image = owner(material).getTextureName();
		return image == null || imageLayers.containsKey(image) || !hdImages.contains(image) ? null : image;
	}

	/**
	 * The image name of the flow map's owner when Lumina does not ship it, else null.
	 */
	@Nullable
	private static String missingFlowImage(Material flowMap)
	{
		String image = owner(flowMap).getTextureName();
		return image == null || imageLayers.containsKey(image) ? null : image;
	}

	// textures/117hd_images.txt: one image name per line, # comments
	private static Set<String> loadHdImageNames() throws IOException
	{
		String path = GsonUtils.SCENE_DATA_PATH + "textures/117hd_images.txt";
		try (InputStream in = MaterialManager.class.getResourceAsStream(path))
		{
			if (in == null)
			{
				throw new IOException("Missing resource: " + path);
			}
			var names = new HashSet<String>();
			for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n"))
			{
				line = line.trim();
				if (!line.isEmpty() && !line.startsWith("#"))
				{
					names.add(line);
				}
			}
			return names;
		}
	}

	/**
	 * 117 HD's textureLayer, the layer of the material's texture owner (its root): the owner's image, or when the
	 * image is missing the material's vanilla texture (its own index, so FORTIS_ROADS_MIRRORED keeps vanilla 122
	 * under its owner FORTIS_ROADS, else the owner's), or -1 (white, the vertex colour) when there is neither.
	 */
	private static int textureLayer(@Nullable Material material)
	{
		if (material == null)
		{
			return -1;
		}
		var owner = owner(material);
		if (owner == Material.NONE)
		{
			return -1;
		}
		String image = owner.getTextureName();
		if (image != null)
		{
			Integer layer = imageLayers.get(image);
			if (layer != null)
			{
				return layer;
			}
		}
		if (material.vanillaTextureIndex != -1)
		{
			return material.vanillaTextureIndex;
		}
		return owner.vanillaTextureIndex;
	}

	/**
	 * The slot's layers, brightness and texture scale after the missing-image fallback (see the class comment),
	 * warning once per material when its own image or its flow map's image is not shipped.
	 */
	private static void fillTextureFields(float[] out, int o, Material m)
	{
		int colorMap = textureLayer(m);
		float brightness = m.brightness;
		float scaleX = m.textureScale[0];
		float scaleY = m.textureScale[1];
		float flowMapStrength = m.flowMapStrength;
		String image = imagesKnown ? missingHdImage(m) : null;
		if (image != null && colorMap != -1)
		{
			brightness = 1;
			scaleX = 1;
			scaleY = 1;
		}
		String flowImage = imagesKnown && m.flowMap != null ? missingFlowImage(m.flowMap) : null;
		if (flowImage != null)
		{
			flowMapStrength = 0;
		}
		if ((image != null || flowImage != null) && warnedMissingImage.add(m.name))
		{
			log.warn("material {}: {}{}{}", m.name,
				image == null ? "" : "image " + image + " not shipped, using " + (colorMap == -1 ? "vertex colour" :
					"vanilla texture " + colorMap + " with neutral brightness and texture scale"),
				image != null && flowImage != null ? "; " : "",
				flowImage == null ? "" : "flow map image " + flowImage + " not shipped, not flowing");
		}
		out[o] = colorMap;
		out[o + 1] = textureLayer(m.flowMap);
		out[o + 3] = brightness;
		out[o + 4] = scaleX == 0 ? 0 : 1 / scaleX;
		out[o + 5] = scaleY == 0 ? 0 : 1 / scaleY;
		out[o + 8] = flowMapStrength;
	}

	/**
	 * Whether the material table changed since the last {@link #fillSlots} call.
	 */
	public static boolean slotsDirty()
	{
		return slotsDirty;
	}

	/**
	 * Fills the shader material table (three vec4 per slot, see frag.glsl) as 117 HD's fillMaterialStruct does,
	 * including the vanilla texture scroll of materials with a vanilla texture index.
	 */
	public static int fillSlots(float[] out, Texture[] vanillaTextures)
	{
		slotsDirty = false;
		synchronized (SLOTS)
		{
			for (int i = 0; i < SLOTS.size(); i++)
			{
				Material m = SLOTS.get(i);
				float vanillaScrollX = 0;
				float vanillaScrollY = 0;
				// Replacement materials will only apply vanilla scrolling if they also specify a vanillaTextureIndex
				if (m.vanillaTextureIndex != -1 && vanillaTextures != null && m.vanillaTextureIndex < vanillaTextures.length)
				{
					var texture = vanillaTextures[m.vanillaTextureIndex];
					if (texture != null)
					{
						int direction = texture.getAnimationDirection();
						if (direction != 0)
						{
							// Convert vanilla texture animations to the same format as Material scroll parameters
							float speed = texture.getAnimationSpeed() * 50 / 128.f;
							float radians = direction * -(float) (Math.PI / 2);
							vanillaScrollX = (float) Math.cos(radians) * speed;
							vanillaScrollY = (float) Math.sin(radians) * speed;
						}
					}
				}
				int o = i * 12;
				fillTextureFields(out, o, m);
				out[o + 2] = (m.overrideBaseColor ? 4 : 0) | (m.unlit ? 2 : 0) | (m.hasTransparency ? 1 : 0);
				out[o + 6] = m.scrollSpeed[0] + vanillaScrollX;
				out[o + 7] = m.scrollSpeed[1] + vanillaScrollY;
				out[o + 9] = m.flowMapDuration[0];
				out[o + 10] = m.flowMapDuration[1];
				out[o + 11] = m.subsurface;
			}
			return SLOTS.size();
		}
	}

	/**
	 * The image names of the texture owners of all materials, for the texture manager to look up.
	 */
	public static Set<String> textureImageNames()
	{
		var names = new HashSet<String>();
		for (var m : MATERIAL_MAP.values())
		{
			var owner = m;
			while (owner.parent != null)
			{
				owner = owner.parent;
			}
			if (owner.getTextureName() != null)
			{
				names.add(owner.getTextureName());
			}
			if (m.flowMap != null && m.flowMap.getTextureName() != null)
			{
				names.add(m.flowMap.getTextureName());
			}
		}
		return names;
	}

	private static boolean isInactiveReplacement(Material m)
	{
		if (m.replacementCondition == null)
		{
			return false;
		}
		return !ExpressionParser.parsePredicate(m.replacementCondition).test(ShadingVariables.INSTANCE);
	}

	private static boolean replaces(Material m, String name)
	{
		if (m.replacementCondition == null || !m.materialsToReplace.contains(name))
		{
			return false;
		}
		return ExpressionParser.parsePredicate(m.replacementCondition).test(ShadingVariables.INSTANCE);
	}

	// 117 HD's GsonUtils.resolveParentDefinitions with shallowInheritFrom and the root parent NONE
	@Nullable
	private static JsonObject resolveParentDefinition(String name, Map<String, JsonObject> definitions,
		Map<String, JsonObject> resolved, Set<String> resolving)
	{
		if (resolved.containsKey(name))
		{
			return resolved.get(name);
		}
		JsonObject definition = definitions.get(name);
		if (definition == null)
		{
			return null;
		}
		if (!resolving.add(name))
		{
			log.error("material '{}' contains a parent loop", name);
			return null;
		}

		JsonObject result = definition;
		JsonElement parent = definition.get("parent");
		if (parent != null)
		{
			String parentName = parent.getAsString();
			if (!parentName.equals(Material.NONE.name))
			{
				JsonObject base = definitions.containsKey(parentName) ?
					resolveParentDefinition(parentName, definitions, resolved, resolving) : null;
				if (base != null)
				{
					for (var entry : base.entrySet())
					{
						if (!result.has(entry.getKey()))
						{
							result.add(entry.getKey(), entry.getValue());
						}
					}
				}
				else
				{
					log.error("material '{}' has an unknown parent '{}'", name, parentName);
					result = null;
				}
			}
		}
		resolving.remove(name);
		resolved.put(name, result);
		return result;
	}

	private static Material parse(JsonObject json)
	{
		Material m = new Material(json.get("name").getAsString());
		if (json.has("vanillaTextureIndex"))
		{
			m.vanillaTextureIndex = json.get("vanillaTextureIndex").getAsInt();
		}
		m.hasTransparency = bool(json, "hasTransparency");
		m.doubleSidedFaces = bool(json, "doubleSidedFaces");
		m.seasonalFoliage = bool(json, "seasonalFoliage");
		m.overrideBaseColor = bool(json, "overrideBaseColor");
		m.unlit = bool(json, "unlit");
		if (json.has("brightness"))
		{
			// ColorUtils.LinearAdapter: given in sRGB
			m.brightness = ColorUtils.srgbToLinear(json.get("brightness").getAsFloat());
		}
		if (json.has("subsurface"))
		{
			m.subsurface = json.get("subsurface").getAsFloat();
		}
		if (json.has("displacementScale"))
		{
			m.displacementScale = json.get("displacementScale").getAsFloat();
		}
		if (json.has("flowMapStrength"))
		{
			m.flowMapStrength = json.get("flowMapStrength").getAsFloat();
		}
		m.flowMapDuration = floats(json, "flowMapDuration", m.flowMapDuration);
		if (json.has("specularStrength"))
		{
			m.specularStrength = json.get("specularStrength").getAsFloat();
		}
		if (json.has("specularGloss"))
		{
			m.specularGloss = json.get("specularGloss").getAsFloat();
		}
		m.scrollSpeed = floats(json, "scrollSpeed", m.scrollSpeed);
		m.textureScale = floats(json, "textureScale", m.textureScale);
		if (json.has("materialsToReplace"))
		{
			var list = new ArrayList<String>();
			var element = json.get("materialsToReplace");
			if (element.isJsonArray())
			{
				for (var e : element.getAsJsonArray())
				{
					if (!e.isJsonNull())
					{
						list.add(e.getAsString());
					}
				}
			}
			else if (element.isJsonPrimitive())
			{
				list.add(element.getAsString());
			}
			m.materialsToReplace = list;
		}
		if (json.has("replacementCondition"))
		{
			m.replacementCondition = json.get("replacementCondition").getAsString();
		}
		return m;
	}

	private static boolean bool(JsonObject json, String key)
	{
		return json.has(key) && json.get(key).getAsBoolean();
	}

	@Nullable
	private static String string(JsonObject json, String key)
	{
		return json.has(key) && !json.get(key).isJsonNull() ? json.get(key).getAsString() : null;
	}

	private static float[] floats(JsonObject json, String key, float[] defaults)
	{
		if (!json.has(key) || !json.get(key).isJsonArray())
		{
			return defaults;
		}
		var array = json.get(key).getAsJsonArray();
		float[] result = new float[array.size()];
		for (int i = 0; i < result.length; i++)
		{
			result[i] = array.get(i).getAsFloat();
		}
		return result;
	}
}
