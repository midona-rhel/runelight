package com.midonarhel.runelight;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.runelite.api.DynamicObject;
import net.runelite.api.GraphicsObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Projectile;
import net.runelite.api.Renderable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glUniform1f;

/**
 * Per-surface material behaviour, from materials.json (derived from 117 HD by tools/import_117hd_materials.py).
 *
 * Every face carries 7 material bits in the spare high bits of its texture id short (texture ids need 9 bits),
 * which is free in both the static zone and the dynamic vertex formats:
 *   bits 9-10   emissive level 0-3: lit as if by at least a fixed light, so it shows its own color and glows
 *   bit  11     transmissive: thin foliage that is also lit through from behind
 *   bits 12-13  the water bed and land terrain flags (WaterMap)
 *   bits 14-15  how much snow settles on it: all, reduced (things to interact with) or none (items, actors)
 * Models whose 117 HD override lights only some faces name an emissive group (bits 6-13 of the model flags), which
 * decides each face by its color, alpha and whether it is textured.
 * Fake water, faces that pretend to be water (fountains, wells, troughs, waterfalls), has both water flags at once
 * (bed and land terrain, which no model face has otherwise): the composite shades them as real water (water.glsl
 * waterFake). They are the faces with the game's water textures, and the faces of a fake water group (bits 23-30 of
 * the model flags) that its rules pick out. The uploaders make them opaque, drop their texture and put their height
 * above the foot of the model's water in the u coordinate (see {@link #fakeFoot}).
 * The flags are always baked; the config toggles only change shader uniforms, so no scene reload is needed.
 */
final class SurfaceMaterials
{
	private static final Logger log = LoggerFactory.getLogger(SurfaceMaterials.class);

	static final int SHIFT = 9;
	static final int TEXTURE_MASK = (1 << SHIFT) - 1;
	static final int SNOW_SHIFT = SHIFT + 5;

	// model-level flags: emissive level in bits 0-1, then the bits below (wind sway has the bits from 16 up)
	static final int EMISSIVE_MASK = 3;
	static final int TRANSMISSIVE = 1 << 2;
	// emissive applies only to hot-colored faces (flames, not the logs under them)
	static final int FIRE = 1 << 3;
	static final int EMISSIVE_FULL = 3;
	// model-level snow class in bits 4-5 (the face's bits 14-15): scenery with an action, and things that move or
	// lie around
	static final int SNOW_MODEL_SHIFT = 4;
	static final int SNOW_REDUCED = 1 << SNOW_MODEL_SHIFT;
	static final int SNOW_NONE = 2 << SNOW_MODEL_SHIFT;
	private static final int SNOW_MASK = 3 << SNOW_MODEL_SHIFT;
	// model-level emissive group in bits 6-13: 1 + its index in groups, 0 for none
	private static final int GROUP_SHIFT = 6;
	private static final int GROUP_MASK = 0xff;
	// model-level fake water group in bits 23-30 (above the wind's, WindSway.MODEL_SHIFT): 1 + its index in fakeGroups
	private static final int FAKE_SHIFT = 23;
	// face bits of fake water: both water flags
	static final int FAKE_WATER = WaterMap.BED_FLAG | WaterMap.LAND_FLAG;
	// the game's water textures: a face with one is fake water on any model (the terrain's are the water map's)
	private static final int[] WATER_TEXTURES = {1, 24, 25};

	// Foliage objects are whole trees: these HSL limits keep brown and grey faces (bark, trunks, rocks) opaque.
	// OSRS hue runs 0-63 (0 red, ~8 brown/orange, ~11 yellow, ~21 green, ~42 blue), saturation 0-7.
	private static final int BARK_MAX_HUE = 9;
	private static final int BARK_MAX_SATURATION = 4;
	private static final int GREY_MAX_SATURATION = 1;

	// "hot" colors in fire objects: red to yellow, saturated and bright
	private static final int FIRE_MAX_HUE = 12;
	private static final int FIRE_MIN_SATURATION = 4;
	private static final int FIRE_MIN_LIGHTNESS = 45;

	private static int[] objects = new int[0];
	private static int[] npcs = new int[0];
	private static int[] spotanims = new int[0];
	private static Group[] groups = new Group[0];
	private static Group[] fakeGroups = new Group[0];
	// per texture id: the face bits (already shifted)
	private static int[] textures = new int[0];

	private SurfaceMaterials()
	{
	}

	/**
	 * The materials.glsl strength uniforms of one program.
	 */
	static final class Uniforms
	{
		private int emissive = -1, transmission = -1;

		void locate(int program)
		{
			emissive = glGetUniformLocation(program, "emissiveStrength");
			transmission = glGetUniformLocation(program, "transmissionStrength");
		}

		/**
		 * Call with the program in use.
		 */
		void set(RuneLightConfig config)
		{
			glUniform1f(emissive, config.emissiveMaterials() ? config.emissiveStrength() / 100f : 0f);
			glUniform1f(transmission, config.foliageTransmission() ? config.transmissionStrength() / 100f : 0f);
		}
	}

	private static class Data
	{
		int[] transmissiveTextures;
		int[] transmissiveObjects;
		int[][] emissiveTextures;
		int[][] emissiveObjects;
		Group[] emissiveGroups;
		int[][] fireObjects;
		int[][] emissiveNpcs;
		@SerializedName("emissiveSpotanims")
		int[][] emissiveSpotanims;
		// as emissiveGroups, a level above 0 meaning fake water
		Group[] fakeWater;
	}

	/**
	 * Models with only some faces emissive, from the colorOverrides of a 117 HD model override: textured faces take
	 * their own level, the others the level of the first rule whose condition their color meets, else the base level.
	 */
	static final class Group
	{
		int[] objects;
		int[] npcs;
		int untextured;
		int textured;
		Rule[] rules;

		private int level(int texture, int hsl, int opacity)
		{
			if (texture >= 0)
			{
				return textured;
			}
			for (Rule r : rules)
			{
				if (r.matches(hsl, opacity))
				{
					return r.level;
				}
			}
			return untextured;
		}
	}

	static final class Rule
	{
		int level;
		// clauses of terms like "h==5" or "a<255", as 117 HD's expressions over the face's hue (0-63), saturation
		// (0-7), lightness (0-127), alpha (255 minus its transparency) or packed hsl; a face meets the rule when
		// it meets every term of any one clause
		String[][] when;
		private transient int[][][] clauses;

		private void compile()
		{
			clauses = new int[when.length][][];
			for (int i = 0; i < when.length; i++)
			{
				clauses[i] = new int[when[i].length][];
				for (int t = 0; t < when[i].length; t++)
				{
					Matcher m = TERM.matcher(when[i][t]);
					if (!m.matches())
					{
						throw new IllegalArgumentException("materials.json condition " + when[i][t]);
					}
					clauses[i][t] = new int[]{VARIABLES.indexOf(m.group(1)), OPERATORS.indexOf(m.group(2)),
						Integer.parseInt(m.group(3))};
				}
			}
		}

		private boolean matches(int hsl, int opacity)
		{
			int[] v = {hsl >> 10 & 63, hsl >> 7 & 7, hsl & 127, opacity, hsl & 0xffff};
			for (int[][] clause : clauses)
			{
				boolean all = true;
				for (int i = 0; all && i < clause.length; i++)
				{
					int x = v[clause[i][0]], c = clause[i][2];
					switch (clause[i][1])
					{
						case 0:
							all = x == c;
							break;
						case 1:
							all = x != c;
							break;
						case 2:
							all = x <= c;
							break;
						case 3:
							all = x >= c;
							break;
						case 4:
							all = x < c;
							break;
						default:
							all = x > c;
					}
				}
				if (all)
				{
					return true;
				}
			}
			return false;
		}
	}

	private static final List<String> VARIABLES = Arrays.asList("h", "s", "l", "a", "hsl");
	private static final List<String> OPERATORS = Arrays.asList("==", "!=", "<=", ">=", "<", ">");
	private static final Pattern TERM = Pattern.compile("(hsl|h|s|l|a)(==|!=|<=|>=|<|>)(\\d+)");

	static void load(Gson gson)
	{
		Data d;
		try (InputStream is = SurfaceMaterials.class.getResourceAsStream("materials.json"))
		{
			if (is == null)
			{
				log.warn("materials.json missing");
				return;
			}
			d = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Data.class);
		}
		catch (Exception ex)
		{
			log.error("Unable to load materials.json", ex);
			return;
		}

		Group[] g = d.emissiveGroups != null ? d.emissiveGroups : new Group[0];
		if (g.length > GROUP_MASK)
		{
			log.error("materials.json has {} emissive groups, more than the {} a model can name", g.length, GROUP_MASK);
			g = Arrays.copyOf(g, GROUP_MASK);
		}
		int[] obj = new int[maxId(d.transmissiveObjects, d.emissiveObjects, d.fireObjects, g, true) + 1];
		set(obj, d.transmissiveObjects, TRANSMISSIVE);
		setLevels(obj, d.emissiveObjects, 0);
		setLevels(obj, d.fireObjects, FIRE);

		int[] npc = new int[maxId(null, d.emissiveNpcs, null, g, false) + 1];
		setLevels(npc, d.emissiveNpcs, 0);

		for (int i = 0; i < g.length; i++)
		{
			for (Rule r : g[i].rules)
			{
				r.compile();
			}
			for (int id : g[i].objects)
			{
				obj[id] |= (i + 1) << GROUP_SHIFT;
			}
			for (int id : g[i].npcs)
			{
				npc[id] |= (i + 1) << GROUP_SHIFT;
			}
		}

		Group[] fake = d.fakeWater != null ? d.fakeWater : new Group[0];
		if (fake.length > GROUP_MASK)
		{
			log.error("materials.json has {} fake water groups, more than the {} a model can name", fake.length, GROUP_MASK);
			fake = Arrays.copyOf(fake, GROUP_MASK);
		}
		for (int i = 0; i < fake.length; i++)
		{
			for (Rule r : fake[i].rules)
			{
				r.compile();
			}
			for (int id : fake[i].objects)
			{
				if (id >= obj.length)
				{
					obj = Arrays.copyOf(obj, id + 1);
				}
				obj[id] |= (i + 1) << FAKE_SHIFT;
			}
		}

		int[] spot = new int[maxId(null, d.emissiveSpotanims, null, g, false) + 1];
		setLevels(spot, d.emissiveSpotanims, 0);

		int[] tex = new int[TextureManager.TEXTURE_COUNT];
		if (d.transmissiveTextures != null)
		{
			for (int t : d.transmissiveTextures)
			{
				if (t >= 0 && t < tex.length)
				{
					tex[t] |= TRANSMISSIVE << SHIFT;
				}
			}
		}
		if (d.emissiveTextures != null)
		{
			for (int[] e : d.emissiveTextures)
			{
				if (e[0] >= 0 && e[0] < tex.length)
				{
					tex[e[0]] |= (e[1] & EMISSIVE_MASK) << SHIFT;
				}
			}
		}

		objects = obj;
		npcs = npc;
		spotanims = spot;
		groups = g;
		fakeGroups = fake;
		textures = tex;
	}

	private static int maxId(int[] plain, int[][] a, int[][] b, Group[] g, boolean objectGroups)
	{
		int max = -1;
		for (Group e : g)
		{
			for (int id : objectGroups ? e.objects : e.npcs)
			{
				max = Math.max(max, id);
			}
		}
		if (plain != null)
		{
			for (int id : plain)
			{
				max = Math.max(max, id);
			}
		}
		for (int[][] list : new int[][][]{a, b})
		{
			if (list != null)
			{
				for (int[] e : list)
				{
					max = Math.max(max, e[0]);
				}
			}
		}
		return max;
	}

	private static void set(int[] table, int[] ids, int flag)
	{
		if (ids != null)
		{
			for (int id : ids)
			{
				table[id] |= flag;
			}
		}
	}

	private static void setLevels(int[] table, int[][] entries, int flag)
	{
		if (entries != null)
		{
			for (int[] e : entries)
			{
				table[e[0]] = (table[e[0]] & ~EMISSIVE_MASK) | (e[1] & EMISSIVE_MASK) | flag;
			}
		}
	}

	private static int lookup(int[] table, int id)
	{
		return id >= 0 && id < table.length ? table[id] : 0;
	}

	static int forObject(int id)
	{
		return lookup(objects, id);
	}

	/**
	 * Model flags for a static or animated scene object, preferring the composition the dynamic object recorded
	 * (farming patches and other impostors draw as the object they currently look like). Objects with an action
	 * (Interactables) take less snow.
	 */
	static int forObject(int id, Renderable r)
	{
		int flags = lookup(objects, id);
		int shown = id;
		if (r instanceof DynamicObject)
		{
			var comp = ((DynamicObject) r).getRecordedObjectComposition();
			if (comp != null)
			{
				shown = comp.getId();
				int f = lookup(objects, shown);
				if (f != 0)
				{
					flags = f;
				}
			}
		}
		return Interactables.is(shown) ? flags | SNOW_REDUCED : flags;
	}

	// type field of a scene object hash (TileObject.getHash), as 117 HD's ModelHash reads it
	private static final int HASH_TYPE_OBJECT = 2;

	/**
	 * Model flags for an entity drawn as a temporary model: NPCs, projectiles, spot animations, spawned objects,
	 * players and ground items. Everything but objects takes no snow.
	 */
	static int forRenderable(Renderable r, long hash, int id)
	{
		if (r instanceof NPC)
		{
			NPC npc = (NPC) r;
			int f = lookup(npcs, npc.getId());
			NPCComposition base = npc.getComposition();
			if (f == 0 && base != null && base.getConfigs() != null)
			{
				// only NPCs that change form by varbit need their current form resolved
				NPCComposition comp = npc.getTransformedComposition();
				f = comp != null ? lookup(npcs, comp.getId()) : 0;
			}
			return f | SNOW_NONE;
		}
		if (r instanceof Projectile)
		{
			return lookup(spotanims, ((Projectile) r).getId()) | SNOW_NONE;
		}
		if (r instanceof GraphicsObject)
		{
			return lookup(spotanims, ((GraphicsObject) r).getId()) | SNOW_NONE;
		}
		if ((hash >> 16 & 7) == HASH_TYPE_OBJECT)
		{
			return forObject(id, r);
		}
		return SNOW_NONE;
	}

	/**
	 * Material bits for one face, shifted into place to be ORed into the texture id short.
	 *
	 * @param model   model flags from {@link #forObject} or {@link #forRenderable}
	 * @param texture the face's texture id, -1 for none
	 * @param hsl     the face's packed HSL color (ignored for textured faces)
	 * @param opacity the face's alpha, 255 minus its transparency (see {@link #opacity})
	 */
	static int face(int model, int texture, int hsl, int opacity)
	{
		if (isFakeWater(model, texture, hsl, opacity))
		{
			// water takes no snow
			return FAKE_WATER | SNOW_NONE >> SNOW_MODEL_SHIFT << SNOW_SHIFT;
		}
		int bits = 0;
		if (texture >= 0)
		{
			// a textured face's color is only a lightness, so its texture alone says whether it's a leaf
			bits = texture < textures.length ? textures[texture] : 0;
		}
		else if ((model & TRANSMISSIVE) != 0 && !isBark(hsl))
		{
			bits = TRANSMISSIVE << SHIFT;
		}

		int level = model & EMISSIVE_MASK;
		if (level != 0 && ((model & FIRE) == 0 || texture < 0 && isHot(hsl)))
		{
			bits = (bits & ~(EMISSIVE_MASK << SHIFT)) | Math.max(level, bits >> SHIFT & EMISSIVE_MASK) << SHIFT;
		}
		int group = model >> GROUP_SHIFT & GROUP_MASK;
		if (group != 0)
		{
			level = groups[group - 1].level(texture, hsl, opacity);
			bits = (bits & ~(EMISSIVE_MASK << SHIFT)) | Math.max(level, bits >> SHIFT & EMISSIVE_MASK) << SHIFT;
		}
		return bits | (model & SNOW_MASK) >> SNOW_MODEL_SHIFT << SNOW_SHIFT;
	}

	private static boolean isFakeWater(int model, int texture, int hsl, int opacity)
	{
		int group = model >> FAKE_SHIFT & GROUP_MASK;
		return isWaterTexture(texture) || group != 0 && fakeGroups[group - 1].level(texture, hsl, opacity) != 0;
	}

	/**
	 * Whether the face bits {@link #face} gave are fake water.
	 */
	static boolean isFake(int faceBits)
	{
		return (faceBits & FAKE_WATER) == FAKE_WATER;
	}

	/**
	 * Where the water of a model with fake water stands: the largest y (lowest point, y down) of the vertices of its
	 * fake faces, or NaN when it has none. {@code material} the model flags, the arrays the model's (faces whose third
	 * color is -2 are not drawn). The uploaders put each vertex's height above it, in world units, into the u
	 * coordinate of its fake faces, which the shader foams the foot of falls by.
	 */
	static float fakeFoot(int material, short[] textures, int faceCount, int[] colors, byte[] transparencies,
		int[] indices1, int[] indices2, int[] indices3, float[] vertexY)
	{
		boolean any = (material >> FAKE_SHIFT & GROUP_MASK) != 0;
		for (int f = 0; f < faceCount && !any && textures != null; ++f)
		{
			any = isWaterTexture(textures[f]);
		}
		if (!any)
		{
			return Float.NaN;
		}
		float foot = Float.NaN;
		for (int f = 0; f < faceCount; ++f)
		{
			int texture = textures != null ? textures[f] : -1;
			if (isFake(face(material, texture, colors[f] & 0xffff, opacity(transparencies, f))))
			{
				foot = Math.max(Float.isNaN(foot) ? -Float.MAX_VALUE : foot,
					Math.max(vertexY[indices1[f]], Math.max(vertexY[indices2[f]], vertexY[indices3[f]])));
			}
		}
		return foot;
	}

	private static boolean isWaterTexture(int texture)
	{
		for (int t : WATER_TEXTURES)
		{
			if (t == texture)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A fake water vertex's height above the foot (see {@link #fakeFoot}), for the u coordinate: whole world units.
	 */
	static int fakeHeight(float foot, float vertexY)
	{
		return Math.max(0, Math.min(Math.round(foot - vertexY), 0x7fff));
	}

	/**
	 * The opacity face() wants, from a model's face transparencies (null for none).
	 */
	static int opacity(byte[] transparencies, int face)
	{
		return 255 - (transparencies != null ? transparencies[face] & 0xff : 0);
	}

	/**
	 * Texture-only material bits, for terrain.
	 */
	static int texture(int texture)
	{
		return texture >= 0 && texture < textures.length ? textures[texture] : 0;
	}

	private static boolean isBark(int hsl)
	{
		int h = hsl >> 10 & 63, s = hsl >> 7 & 7;
		return s <= GREY_MAX_SATURATION || h <= BARK_MAX_HUE && s <= BARK_MAX_SATURATION;
	}

	private static boolean isHot(int hsl)
	{
		int h = hsl >> 10 & 63, s = hsl >> 7 & 7, l = hsl & 127;
		return h <= FIRE_MAX_HUE && s >= FIRE_MIN_SATURATION && l >= FIRE_MIN_LIGHTNESS;
	}
}
