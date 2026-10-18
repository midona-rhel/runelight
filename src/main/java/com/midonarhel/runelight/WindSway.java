package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.runelite.api.DynamicObject;
import net.runelite.api.Renderable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glUniform1f;
import static org.lwjgl.opengl.GL33C.glUniform2f;

/**
 * Plants that sway in the wind, after 117 HD's wind displacement (wind.json, from tools/import_117hd_wind.py).
 *
 * Each vertex of a swaying object carries a code in the fourth position short of the static vertex format, which
 * otherwise only ground vertices use (GroundBlender's -1). sway.glsl turns it into a displacement:
 *   bits 0-3   the object's phase, so neighbours don't move in lockstep
 *   bits 4-5   flutter 0-3: how much the vertex also shakes on its own
 *   bit  6     jiggle: no bend, the flutter runs along the surface (ivy on walls)
 *   bits 9-14  bend weight: world units of height above the part that stays put, square-root coded
 * The code stays positive (the short is read as a float) and below 512 means still, so the 1 that dynamic models
 * read as w (their position has 3 floats) is still too. Animated objects, drawn in that dynamic format, don't
 * sway: they already move on their own.
 *
 * The codes are always baked; the config only changes uniforms, so no scene reload is needed.
 */
final class WindSway
{
	private static final Logger log = LoggerFactory.getLogger(WindSway.class);

	// the per-object code (wind.json) rides in the material int the uploaders get, above SurfaceMaterials' bits:
	// mode in bits 0-2, modifier + 3 in bits 3-5, invert in bit 6
	static final int MODEL_SHIFT = 16;
	static final int OBJECT = 1, OBJECT_NO_GROUND = 2, VERTEX = 3, TREE = 4, JIGGLE = 5;
	private static final int MODE_MASK = 7;
	private static final int INVERT = 1 << 6;
	// 117 HD's windDisplacementModifier -3 to 3
	private static final float[] MODIFIERS = {0.25f, 0.5f, 0.75f, 1, 1.25f, 1.5f, 2, 2};

	// vertex code; MAX_HEIGHT must match WIND_MAX_HEIGHT in sway.glsl
	static final int MAX_HEIGHT = 4096;
	static final int BEND_SHIFT = 9;
	static final int BEND_MAX = 63;
	static final int FLUTTER_SHIFT = 4;
	static final int JIGGLE_BIT = 1 << 6;

	// trees (117 HD's hemisphere blend): the canopy moves, the trunk stays. Below this share of the height
	// nothing moves; near the trunk axis (in world units) only the top does.
	private static final float TREE_BASE = 0.3f;
	private static final float TREE_TRUNK = 50, TREE_TRUNK_BLEND = 10;

	// how fast windTime runs: calm air rocks slowly, a storm fast (the shader multiplies it into its frequencies)
	private static final float TIME_CALM = 0.6f, TIME_WIND = 1.2f, TIME_GUST = 0.8f;

	private static byte[] objects = new byte[0];

	private WindSway()
	{
	}

	private static class Data
	{
		int[][] objects;
	}

	static void load(Gson gson)
	{
		Data d;
		try (InputStream is = WindSway.class.getResourceAsStream("wind.json"))
		{
			if (is == null)
			{
				log.warn("wind.json missing");
				return;
			}
			d = gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Data.class);
		}
		catch (Exception ex)
		{
			log.error("Unable to load wind.json", ex);
			return;
		}
		objects = table(d.objects);
	}

	static byte[] table(int[][] entries)
	{
		int max = -1;
		for (int[] e : entries)
		{
			max = Math.max(max, e[0]);
		}
		byte[] t = new byte[max + 1];
		for (int[] e : entries)
		{
			if (e[0] >= 0)
			{
				t[e[0]] = (byte) e[1];
			}
		}
		return t;
	}

	static void setTable(byte[] table)
	{
		objects = table;
	}

	private static int lookup(int id)
	{
		return id >= 0 && id < objects.length ? objects[id] & 0x7f : 0;
	}

	/**
	 * The object's wind code, shifted to {@link #MODEL_SHIFT} to be ORed into the material int; 0 if it doesn't
	 * sway. Farming patches and other impostors sway as the object they currently look like.
	 */
	static int forObject(int id, Renderable r)
	{
		if (r instanceof DynamicObject)
		{
			var comp = ((DynamicObject) r).getRecordedObjectComposition();
			if (comp != null)
			{
				int c = lookup(comp.getId());
				if (c != 0)
				{
					return c << MODEL_SHIFT;
				}
			}
		}
		return lookup(id) << MODEL_SHIFT;
	}

	static boolean sways(int material)
	{
		return (material >> MODEL_SHIFT & MODE_MASK) != 0;
	}

	/**
	 * Phase 0-15 of an object at (x, z), scattered so that neighbours differ.
	 */
	static int phase(int x, int z)
	{
		int h = (x >> 6) * 73856093 ^ (z >> 6) * 19349663;
		h ^= h >>> 13;
		h *= 0x5bd1e995;
		return (h ^ h >>> 15) & 15;
	}

	/**
	 * The vertex code for a vertex of a swaying model.
	 *
	 * @param material the material int with the object's wind code from {@link #forObject}
	 * @param x        model-space position, y up negative, the origin on the ground
	 * @param height   the model's height above its origin (positive)
	 * @param phase    from {@link #phase}
	 */
	static int vertexCode(int material, float x, float y, float z, float height, int phase)
	{
		int model = material >> MODEL_SHIFT;
		int mode = model & MODE_MASK;
		if (mode == 0 || height <= 0)
		{
			return 0;
		}
		float frac = clamp(-y / height, 0, 1);
		if ((model & INVERT) != 0)
		{
			// hanging things (banners, vines) are fixed at the top
			frac = 1 - frac;
		}
		float weight = frac;
		int flutter;
		int jiggle = 0;
		switch (mode)
		{
			case TREE:
			{
				float trunk = clamp((Math.abs(x) + Math.abs(z) - TREE_TRUNK) / TREE_TRUNK_BLEND, 0, 1);
				float top = clamp((frac - 0.5f) / 0.2f, 0, 1);
				weight *= smoothstep(TREE_BASE - 0.05f, TREE_BASE + 0.05f, frac) * (trunk + (1 - trunk) * top);
				flutter = 1;
				break;
			}
			case VERTEX:
				flutter = 3;
				break;
			case JIGGLE:
				flutter = 3;
				jiggle = JIGGLE_BIT;
				break;
			default:
				flutter = 2;
				break;
		}
		float units = weight * height * MODIFIERS[model >> 3 & 7];
		int bend = Math.round((float) Math.sqrt(Math.min(units, MAX_HEIGHT) / MAX_HEIGHT) * BEND_MAX);
		if (bend == 0)
		{
			return 0;
		}
		return bend << BEND_SHIFT | jiggle | flutter << FLUTTER_SHIFT | phase & 15;
	}

	/**
	 * World units of bend weight a code stands for, as sway.glsl decodes it.
	 */
	static float bendUnits(int code)
	{
		float b = (code >> BEND_SHIFT & BEND_MAX) / (float) BEND_MAX;
		return b * b * MAX_HEIGHT;
	}

	/**
	 * The sway amount uniform of one program (sway.glsl). The wind itself comes from Wind.java (wind.glsl).
	 */
	static final class Uniforms
	{
		private int sway = -1;

		void locate(int program)
		{
			sway = glGetUniformLocation(program, "windSway");
		}

		/**
		 * Call with the program in use.
		 *
		 * @param amount amplitude multiplier, 0 holds everything still
		 */
		void set(float amount)
		{
			glUniform1f(sway, amount);
		}
	}

	private static float clamp(float v, float lo, float hi)
	{
		return v < lo ? lo : v > hi ? hi : v;
	}

	private static float smoothstep(float a, float b, float x)
	{
		float t = clamp((x - a) / (b - a), 0, 1);
		return t * t * (3 - 2 * t);
	}
}
