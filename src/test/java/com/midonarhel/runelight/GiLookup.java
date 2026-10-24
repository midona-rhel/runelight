package com.midonarhel.runelight;

import static org.junit.Assert.assertTrue;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * gi.glsl's lookup in Java, for the tests: the same patches found the same way from the same floor and wall textures
 * (GiPatches.floorTexels, wallTexels), with the constants read from the shader. Positions are exact here.
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

	private static boolean inside(int x, int z)
	{
		return x >= 0 && z >= 0 && x < GiPatches.SIZE && z < GiPatches.SIZE;
	}

	private static float floorAt(GiPatches p, int x, int z, int plane)
	{
		return p.floorTexels[(z * GiPatches.SIZE + x) * 4 + plane];
	}

	private static int plane(GiPatches p, int x, int z, float y, float slack)
	{
		return floorAt(p, x, z, 2) >= y - slack ? 2 : floorAt(p, x, z, 1) >= y - slack ? 1 : 0;
	}

	/** A wall face's texel: the first patch, bands and kind, base and band height. */
	private static float[] faceAt(GiPatches p, int x, int z, int face, int plane)
	{
		int at = ((plane * GiPatches.TILES + z * GiPatches.SIZE + x) * GiPatches.FACES + face) * 4;
		return new float[]{p.wallTexels[at], p.wallTexels[at + 1], p.wallTexels[at + 2], p.wallTexels[at + 3]};
	}

	/** A patch's light and whether it exists, as giPatch. */
	private static float[] patch(GiPatches p, float[] light, float id)
	{
		int i = (int) (id + 0.5f);
		return new float[]{light[i * 3], light[i * 3 + 1], light[i * 3 + 2], p.exists(i) ? 1 : 0};
	}

	/** giFloor. */
	static float[] floor(GiPatches p, float[] light, float[] world, float y)
	{
		int ox = (int) Math.floor(world[0] / GiPatches.TILE), oz = (int) Math.floor(world[2] / GiPatches.TILE);
		if (!inside(ox, oz))
		{
			return new float[4];
		}
		int plane = plane(p, ox, oz, y, define("GI_FLOOR_SLACK"));
		float ground = floorAt(p, ox, oz, plane);
		float fade = 1 - smoothstep(define("GI_OBJECT_FADE_START"), define("GI_OBJECT_FADE_END"), ground - y);
		if (fade <= 0)
		{
			return new float[4];
		}
		float ux = world[0] / GiPatches.TILE - 0.5f, uz = world[2] / GiPatches.TILE - 0.5f;
		int x0 = (int) Math.floor(ux), z0 = (int) Math.floor(uz);
		float fx = ux - x0, fz = uz - z0;
		float tolerance = define("GI_FLOOR_TOLERANCE");
		float[] sum = new float[3];
		float wsum = 0;
		for (int k = 0; k < 4; ++k)
		{
			int dx = k & 1, dz = k >> 1;
			int x = x0 + dx, z = z0 + dz;
			float w = (dx == 1 ? fx : 1 - fx) * (dz == 1 ? fz : 1 - fz);
			if (w <= 0 || !inside(x, z))
			{
				continue;
			}
			w *= 1 - smoothstep(tolerance, 2 * tolerance, Math.abs(floorAt(p, x, z, plane) - ground));
			if (w <= 0)
			{
				continue;
			}
			float[] patch = patch(p, light, plane * GiPatches.TILES + z * GiPatches.SIZE + x);
			if (patch[3] > 0.5f)
			{
				for (int c = 0; c < 3; ++c)
				{
					sum[c] += patch[c] * w;
				}
				wsum += w;
			}
		}
		if (wsum <= 0)
		{
			return new float[4];
		}
		return new float[]{sum[0] / wsum, sum[1] / wsum, sum[2] / wsum,
			Math.min(1, wsum / define("GI_FULL_WEIGHT")) * fade};
	}

	/** giBands. */
	private static float[] bands(GiPatches p, float[] light, float[] e, float y)
	{
		int bands = (int) (e[1] + 0.5f) & 7;
		if (bands == 0)
		{
			return new float[4];
		}
		float b = (e[2] - y) / e[3];
		if (b > bands + define("GI_WALL_TOP"))
		{
			return new float[4];
		}
		b = Math.max(0, Math.min(bands - 1, b - 0.5f));
		float k0 = (float) Math.floor(b), k1 = Math.min(k0 + 1, bands - 1);
		float[] p0 = patch(p, light, e[0] + k0), p1 = patch(p, light, e[0] + k1);
		float w0 = (1 - (b - k0)) * p0[3], w1 = (b - k0) * p1[3];
		float w = w0 + w1;
		if (w <= 0)
		{
			return new float[4];
		}
		return new float[]{(p0[0] * w0 + p1[0] * w1) / w, (p0[1] * w0 + p1[1] * w1) / w, (p0[2] * w0 + p1[2] * w1) / w, 1};
	}

	/** giWall. */
	static float[] wall(GiPatches p, float[] light, float[] world, float[] n)
	{
		float ax = Math.abs(n[0]), az = Math.abs(n[2]);
		boolean diagonal = Math.min(ax, az) >= define("GI_AXIS_RATIO") * Math.max(ax, az);
		float reach = define("GI_WALL_REACH");
		int ox, oz, face, alongX = 0, alongZ = 0;
		float along = 0;
		if (diagonal)
		{
			ox = (int) Math.floor(world[0] / GiPatches.TILE);
			oz = (int) Math.floor(world[2] / GiPatches.TILE);
			face = n[0] > 0 ? 5 : 4;
		}
		else if (ax >= az)
		{
			float edge = (float) Math.floor(world[0] / GiPatches.TILE + 0.5f);
			if (Math.abs(world[0] - edge * GiPatches.TILE) > reach)
			{
				return new float[4];
			}
			ox = (int) edge - (n[0] < 0 ? 1 : 0);
			oz = (int) Math.floor(world[2] / GiPatches.TILE);
			face = n[0] > 0 ? 3 : 1;
			alongZ = 1;
			along = world[2] / GiPatches.TILE - oz - 0.5f;
		}
		else
		{
			float edge = (float) Math.floor(world[2] / GiPatches.TILE + 0.5f);
			if (Math.abs(world[2] - edge * GiPatches.TILE) > reach)
			{
				return new float[4];
			}
			ox = (int) Math.floor(world[0] / GiPatches.TILE);
			oz = (int) edge - (n[2] < 0 ? 1 : 0);
			face = n[2] > 0 ? 2 : 0;
			alongX = 1;
			along = world[0] / GiPatches.TILE - ox - 0.5f;
		}
		if (!inside(ox, oz))
		{
			return new float[4];
		}
		int plane = plane(p, ox, oz, world[1], define("GI_WALL_SLACK"));
		float[] e0 = faceAt(p, ox, oz, face, plane);
		int kind = (int) (e0[1] + 0.5f) >> 3;
		if (e0[1] < 0.5f || diagonal != (kind != 0))
		{
			return new float[4];
		}
		if (diagonal)
		{
			float lx = world[0] - ox * GiPatches.TILE, lz = world[2] - oz * GiPatches.TILE;
			float perp;
			if (kind == 1)
			{
				perp = (lz - lx) * 0.70710678f;
				along = (lx + lz) / (2 * GiPatches.TILE) - 0.5f;
				alongX = 1;
				alongZ = 1;
			}
			else
			{
				perp = (lx + lz - GiPatches.TILE) * 0.70710678f;
				along = (lx - lz) / (2 * GiPatches.TILE);
				alongX = 1;
				alongZ = -1;
			}
			boolean north = (kind == 1) == (face == 4);
			if (Math.abs(perp) > reach || (n[2] > 0) != north)
			{
				return new float[4];
			}
		}
		int sign = along < 0 ? -1 : 1;
		int px = ox + sign * alongX, pz = oz + sign * alongZ;
		float[] e1 = inside(px, pz) ? faceAt(p, px, pz, face, plane) : new float[4];
		if (((int) (e1[1] + 0.5f) >> 3) != kind)
		{
			e1 = new float[4];
		}
		float[] w0 = bands(p, light, e0, world[1]), w1 = bands(p, light, e1, world[1]);
		float a0 = (1 - Math.abs(along)) * w0[3], a1 = Math.abs(along) * w1[3];
		float wsum = a0 + a1;
		if (wsum <= 0)
		{
			return new float[4];
		}
		return new float[]{(w0[0] * a0 + w1[0] * a1) / wsum, (w0[1] * a0 + w1[1] * a1) / wsum,
			(w0[2] * a0 + w1[2] * a1) / wsum, Math.min(1, wsum / define("GI_FULL_WEIGHT"))};
	}

	/**
	 * giIrradiance for the surface at world position pos facing unit n, far from the scene's edges: rgb and how much of
	 * it counts, from the patches' light (rgb per patch).
	 */
	static float[] irradiance(GiPatches p, float[] light, float[] pos, float[] n)
	{
		float[] gi = Math.abs(n[1]) < define("GI_WALL_NORMAL") ? wall(p, light, pos, n) : new float[4];
		if (gi[3] <= 0)
		{
			gi = floor(p, light, pos, pos[1] + Math.max(n[1], 0) * define("GI_DOWN_BIAS"));
		}
		return gi;
	}
}
