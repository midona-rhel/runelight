package com.midonarhel.runelight;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
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
 * Every face carries 5 material bits in the spare high bits of its texture id short (texture ids need 9 bits),
 * which is free in both the static zone and the dynamic vertex formats:
 *   bits 10-11  emissive level 0-3: lit as if by at least a fixed light, so it shows its own color and glows
 *   bit  12     transmissive: thin foliage that is also lit through from behind
 * The flags are always baked; the config toggles only change shader uniforms, so no scene reload is needed.
 */
final class SurfaceMaterials
{
	private static final Logger log = LoggerFactory.getLogger(SurfaceMaterials.class);

	static final int SHIFT = 10;
	static final int TEXTURE_MASK = (1 << SHIFT) - 1;

	// model-level flags: emissive level in bits 0-1, then the bits below
	static final int EMISSIVE_MASK = 3;
	static final int TRANSMISSIVE = 1 << 2;
	// emissive applies only to hot-colored faces (flames, not the logs under them)
	static final int FIRE = 1 << 3;
	static final int EMISSIVE_FULL = 3;

	// Foliage objects are whole trees: these HSL limits keep brown and grey faces (bark, trunks, rocks) opaque.
	// OSRS hue runs 0-63 (0 red, ~8 brown/orange, ~11 yellow, ~21 green, ~42 blue), saturation 0-7.
	private static final int BARK_MAX_HUE = 9;
	private static final int BARK_MAX_SATURATION = 4;
	private static final int GREY_MAX_SATURATION = 1;

	// "hot" colors in fire objects: red to yellow, saturated and bright
	private static final int FIRE_MAX_HUE = 12;
	private static final int FIRE_MIN_SATURATION = 4;
	private static final int FIRE_MIN_LIGHTNESS = 45;

	private static byte[] objects = new byte[0];
	private static byte[] npcs = new byte[0];
	private static byte[] spotanims = new byte[0];
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
		int[][] fireObjects;
		int[][] emissiveNpcs;
		@SerializedName("emissiveSpotanims")
		int[][] emissiveSpotanims;
	}

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

		byte[] obj = new byte[maxId(d.transmissiveObjects, d.emissiveObjects, d.fireObjects) + 1];
		set(obj, d.transmissiveObjects, TRANSMISSIVE);
		setLevels(obj, d.emissiveObjects, 0);
		setLevels(obj, d.fireObjects, FIRE);

		byte[] npc = new byte[maxId(null, d.emissiveNpcs, null) + 1];
		setLevels(npc, d.emissiveNpcs, 0);

		byte[] spot = new byte[maxId(null, d.emissiveSpotanims, null) + 1];
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
		textures = tex;
	}

	private static int maxId(int[] plain, int[][] a, int[][] b)
	{
		int max = -1;
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

	private static void set(byte[] table, int[] ids, int flag)
	{
		if (ids != null)
		{
			for (int id : ids)
			{
				table[id] |= flag;
			}
		}
	}

	private static void setLevels(byte[] table, int[][] entries, int flag)
	{
		if (entries != null)
		{
			for (int[] e : entries)
			{
				table[e[0]] = (byte) ((table[e[0]] & ~EMISSIVE_MASK) | (e[1] & EMISSIVE_MASK) | flag);
			}
		}
	}

	private static int lookup(byte[] table, int id)
	{
		return id >= 0 && id < table.length ? table[id] : 0;
	}

	static int forObject(int id)
	{
		return lookup(objects, id);
	}

	/**
	 * Model flags for a static or animated scene object, preferring the composition the dynamic object recorded
	 * (farming patches and other impostors draw as the object they currently look like).
	 */
	static int forObject(int id, Renderable r)
	{
		if (r instanceof DynamicObject)
		{
			var comp = ((DynamicObject) r).getRecordedObjectComposition();
			if (comp != null)
			{
				int f = lookup(objects, comp.getId());
				if (f != 0)
				{
					return f;
				}
			}
		}
		return lookup(objects, id);
	}

	// type field of a scene object hash (TileObject.getHash), as 117 HD's ModelHash reads it
	private static final int HASH_TYPE_OBJECT = 2;

	/**
	 * Model flags for an entity drawn as a temporary model: NPCs, projectiles, spot animations, spawned objects.
	 * Players and ground items get none.
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
			return f;
		}
		if (r instanceof Projectile)
		{
			return lookup(spotanims, ((Projectile) r).getId());
		}
		if (r instanceof GraphicsObject)
		{
			return lookup(spotanims, ((GraphicsObject) r).getId());
		}
		if ((hash >> 16 & 7) == HASH_TYPE_OBJECT)
		{
			return forObject(id, r);
		}
		return 0;
	}

	/**
	 * Material bits for one face, shifted into place to be ORed into the texture id short.
	 *
	 * @param model   model flags from {@link #forObject} or {@link #forRenderable}
	 * @param texture the face's texture id, -1 for none
	 * @param hsl     the face's packed HSL color (ignored for textured faces)
	 */
	static int face(int model, int texture, int hsl)
	{
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
		return bits;
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
