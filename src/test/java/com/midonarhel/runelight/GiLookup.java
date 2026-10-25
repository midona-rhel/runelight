package com.midonarhel.runelight;

import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gi.glsl's lookup in Java, for the tests: the same filtered reads of the same texels (GiRelight.relight) as the GPU
 * makes, with the constants read from the shader. Positions are in the grid's world coordinates (giCamera is zero).
 */
final class GiLookup
{
	private static String glsl;

	private GiLookup()
	{
	}

	/** The value of a #define of gi.glsl. */
	static synchronized float define(String name)
	{
		try
		{
			if (glsl == null)
			{
				glsl = new String(Files.readAllBytes(
					Paths.get("src/main/resources/com/midonarhel/runelight/gi.glsl")));
			}
		}
		catch (java.io.IOException ex)
		{
			throw new RuntimeException(ex);
		}
		Matcher m = Pattern.compile("#define " + name + " ([0-9.]+)u?").matcher(glsl);
		assertTrue(name, m.find());
		return Float.parseFloat(m.group(1));
	}

	private static float smoothstep(float edge0, float edge1, float x)
	{
		float t = Math.max(0, Math.min(1, (x - edge0) / (edge1 - edge0)));
		return t * t * (3 - 2 * t);
	}

	/** texture(): the trilinear read at (x, y, z) texels (a texel's center at .5), clamped to the edge. */
	private static float[] texture(GiGrid g, byte[] texels, float x, float y, float z)
	{
		int depth = GiRelight.FIELDS * g.ny;
		float fx = x - 0.5f, fy = y - 0.5f, fz = z - 0.5f;
		int x0 = (int) Math.floor(fx), y0 = (int) Math.floor(fy), z0 = (int) Math.floor(fz);
		float tx = fx - x0, ty = fy - y0, tz = fz - z0;
		float[] out = new float[4];
		for (int k = 0; k < 8; ++k)
		{
			int cx = clamp(x0 + (k & 1), g.nx), cz = clamp(z0 + (k >> 1 & 1), g.nz), cy = clamp(y0 + (k >> 2 & 1), depth);
			float w = ((k & 1) == 0 ? 1 - tx : tx) * ((k >> 1 & 1) == 0 ? 1 - tz : tz) * ((k >> 2 & 1) == 0 ? 1 - ty : ty);
			int at = ((cy * g.nz + cz) * g.nx + cx) * 4;
			for (int c = 0; c < 4; ++c)
			{
				out[c] += w * (texels[at + c] & 0xff) / 255f;
			}
		}
		return out;
	}

	private static int clamp(int v, int n)
	{
		return Math.max(0, Math.min(n - 1, v));
	}

	/** giFetch: the sum of colour in field 0 to 2 (upright, slanted, level) at cell (voxels from the grid's corner) times how far it is given, and that. */
	private static float[] fetch(GiGrid g, byte[] texels, float[] cell, int field)
	{
		float layer = field * g.ny + Math.max(0.5f, Math.min(g.ny - 0.5f, cell[1]));
		float[] t = texture(g, texels, cell[0], layer, cell[2]);
		float min = define("GI_GIVEN_MIN");
		if (t[3] < min)
		{
			return new float[4];
		}
		float range = define("GI_RANGE");
		float[] out = new float[4];
		for (int c = 0; c < 3; ++c)
		{
			float u = t[c] / t[3];
			out[c] = range * u * u;
		}
		float given = smoothstep(min, define("GI_GIVEN_FULL"), t[3]);
		for (int c = 0; c < 3; ++c)
		{
			out[c] *= given;
		}
		out[3] = given;
		return out;
	}

	/** giIrradiance of the volume texels at world position p facing n (unit): rgb the sum of colour, a how much. */
	static float[] irradiance(GiGrid g, byte[] texels, float[] p, float[] n)
	{
		return irradiance(g, texels, texels, 1, p, n);
	}

	/** ... with the volume to blending in over from by blend, 0 to 1. */
	static float[] irradiance(GiGrid g, byte[] from, byte[] to, float blend, float[] p, float[] n)
	{
		float tile = define("GI_TILE");
		float tx = p[0] / tile, tz = p[2] / tile;
		float size = define("GI_SIZE");
		float edge = Math.min(Math.min(tx, size - tx), Math.min(tz, size - tz));
		float fade = Math.max(0, Math.min(1, edge / define("GI_EDGE_FADE")));
		if (fade <= 0)
		{
			return new float[4];
		}
		float voxel = define("GI_VOXEL"), lookup = define("GI_LOOKUP");
		float[] cell = {(p[0] + n[0] * lookup) / voxel, (p[1] + n[1] * lookup) / voxel - g.layer0, (p[2] + n[2] * lookup) / voxel};
		float slack = define("GI_SLOPE_SLACK");
		float y = Math.abs(n[1]);
		float[] across = {y * y, 0.5f - y * (float) Math.sqrt(Math.max(1 - y * y, 0)), 1 - y * y};
		float[] acc = new float[4];
		for (int field = 0; field < GiRelight.FIELDS; ++field)
		{
			float weight = Math.max(across[field] - slack, 0) / (1 - slack);
			if (weight <= 0)
			{
				continue;
			}
			float[] a = fetch(g, from, cell, field), b = fetch(g, to, cell, field);
			for (int c = 0; c < 3; ++c)
			{
				acc[c] += weight * (a[c] + (b[c] - a[c]) * blend);
			}
			acc[3] = Math.max(acc[3], a[3] + (b[3] - a[3]) * blend);
		}
		acc[3] *= fade;
		return acc;
	}
}
