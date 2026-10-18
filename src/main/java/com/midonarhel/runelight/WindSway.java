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
 *   bits 0-2   the object's anchor tile x modulo 8, bits 3-5 z: the shader finds the anchor tile from it, and
 *              takes the object's phase, gust and rolling wave from there, so the whole object moves as one
 *   bit  6     jiggle: a small rustle up and down instead of a bend (ivy on walls)
 *   bits 7-14  bend weight: world units the vertex moves per unit of bend, square-root coded. It follows a bend
 *              curve of the vertex's height within its object: 0 at the base, growing faster toward the top
 * The code stays positive (the short is read as a float) and below 128 means still, so the 1 that dynamic models
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

	// vertex code; MAX_HEIGHT and ANCHOR_MOD must match sway.glsl
	static final int MAX_HEIGHT = 2048;
	static final int ANCHOR_MOD = 8;
	static final int BEND_SHIFT = 7;
	static final int BEND_MAX = 255;
	static final int JIGGLE_BIT = 1 << 6;

	// bend curves, weight = height share ^ power: plants bend along their length, trees stiffer low down
	private static final float PLANT_POWER = 1.5f, TREE_POWER = 2f;
	// trees: below this share of the height nothing moves; above it the trunk and canopy bend as one
	private static final float TREE_BASE = 0.25f;
	// small plants bend further for their size: below this height (world units) the bend grows with its square root
	private static final float SMALL = 256;

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
	 * The anchor code of an object at scene local (x, z): its tile modulo {@link #ANCHOR_MOD} on each axis.
	 */
	static int anchor(int x, int z)
	{
		return (x >> 7 & ANCHOR_MOD - 1) | (z >> 7 & ANCHOR_MOD - 1) << 3;
	}

	/**
	 * The vertex code for a vertex of a swaying model.
	 *
	 * @param material the material int with the object's wind code from {@link #forObject}
	 * @param x        model-space position, y up negative, the origin on the ground
	 * @param height   the model's height above its origin (positive)
	 * @param anchor   from {@link #anchor}
	 */
	static int vertexCode(int material, float x, float y, float z, float height, int anchor)
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
		float curve;
		int jiggle = 0;
		if (mode == TREE)
		{
			// the trunk stays at the base; no dependence on the distance from the trunk axis, so the canopy keeps
			// its shape and moves as a block
			curve = (float) Math.pow(clamp((frac - TREE_BASE) / (1 - TREE_BASE), 0, 1), TREE_POWER);
		}
		else
		{
			curve = (float) Math.pow(frac, PLANT_POWER);
			if (mode == JIGGLE)
			{
				jiggle = JIGGLE_BIT;
			}
		}
		float size = height < SMALL ? (float) Math.sqrt(SMALL * height) : height;
		float units = curve * size * MODIFIERS[model >> 3 & 7];
		int bend = Math.round((float) Math.sqrt(Math.min(units, MAX_HEIGHT) / MAX_HEIGHT) * BEND_MAX);
		if (bend == 0)
		{
			return 0;
		}
		return bend << BEND_SHIFT | jiggle | anchor & 63;
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
}
