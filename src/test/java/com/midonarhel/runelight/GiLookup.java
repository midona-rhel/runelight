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

	/** A giFloors texel: the grounds of floors 0, 1 and 2, then the roof. */
	private static float[] tile(GiPatches p, int x, int z)
	{
		int at = (z * GiPatches.SIZE + x) * 4;
		return new float[]{p.floorTexels[at], p.floorTexels[at + 1], p.floorTexels[at + 2], p.floorTexels[at + 3]};
	}

	private static int plane(float[] floors, float y, float slack)
	{
		return floors[2] >= y - slack ? 2 : floors[1] >= y - slack ? 1 : 0;
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

	/** giOver. */
	private static float[] over(float[] acc, float[] layer)
	{
		float t = layer[3] * (1 - acc[3]);
		return new float[]{acc[0] + layer[0] * t, acc[1] + layer[1] * t, acc[2] + layer[2] * t, acc[3] + t};
	}

	/** giFloor. */
	private static float[] floor(GiPatches p, float[] light, int x0, int z0, float[] sw, float[][] tap, int plane, float y)
	{
		float[] fh = new float[4];
		float ssum = 0, gsum = 0;
		for (int k = 0; k < 4; ++k)
		{
			fh[k] = tap[k][plane];
			ssum += sw[k];
			gsum += sw[k] * fh[k];
		}
		float ground = gsum / ssum;
		float fade = 1 - smoothstep(define("GI_OBJECT_FADE_START"), define("GI_OBJECT_FADE_END"), ground - y);
		if (fade <= 0)
		{
			return new float[4];
		}
		float tolerance = define("GI_FLOOR_TOLERANCE");
		float[] sum = new float[3];
		float wsum = 0;
		for (int k = 0; k < 4; ++k)
		{
			float w = sw[k] * (1 - smoothstep(tolerance, 2 * tolerance, Math.abs(fh[k] - ground)));
			if (w <= 0)
			{
				continue;
			}
			int x = x0 + (k & 1), z = z0 + (k >> 1);
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
		return new float[]{sum[0] / wsum, sum[1] / wsum, sum[2] / wsum, Math.min(1, wsum / define("GI_FULL_WEIGHT")) * fade};
	}

	/** giGround. */
	static float[] ground(GiPatches p, float[] light, float[] world, float y, float facingUp)
	{
		int ox = (int) Math.floor(world[0] / GiPatches.TILE), oz = (int) Math.floor(world[2] / GiPatches.TILE);
		if (!inside(ox, oz))
		{
			return new float[4];
		}
		float ux = world[0] / GiPatches.TILE - 0.5f, uz = world[2] / GiPatches.TILE - 0.5f;
		int x0 = (int) Math.floor(ux), z0 = (int) Math.floor(uz);
		float fx = ux - x0, fz = uz - z0;
		float[] sw = {(1 - fx) * (1 - fz), fx * (1 - fz), (1 - fx) * fz, fx * fz};
		float[][] tap = new float[4][];
		for (int k = 0; k < 4; ++k)
		{
			int x = x0 + (k & 1), z = z0 + (k >> 1);
			if (inside(x, z))
			{
				tap[k] = tile(p, x, z);
			}
			else
			{
				tap[k] = new float[4];
				sw[k] = 0;
			}
		}
		float[] acc = new float[4];

		if (facingUp > 0)
		{
			float[] rw = sw.clone(), rh = new float[4];
			float rsum = 0, hsum = 0;
			for (int k = 0; k < 4; ++k)
			{
				rh[k] = tap[k][3];
				if (rh[k] >= define("GI_NO_ROOF"))
				{
					rw[k] = 0;
				}
				rsum += rw[k];
				hsum += rw[k] * rh[k];
			}
			if (rsum > 0)
			{
				float roof = hsum / rsum;
				float reach = 1 - smoothstep(define("GI_ROOF_REACH"), 2 * define("GI_ROOF_REACH"), Math.abs(world[1] - roof));
				if (reach > 0)
				{
					float[] sum = new float[3];
					float wsum = 0;
					for (int k = 0; k < 4; ++k)
					{
						float w = rw[k] * (1 - smoothstep(define("GI_ROOF_TOLERANCE"), 2 * define("GI_ROOF_TOLERANCE"),
							Math.abs(rh[k] - roof)));
						if (w <= 0)
						{
							continue;
						}
						int x = x0 + (k & 1), z = z0 + (k >> 1);
						float[] patch = patch(p, light, GiPatches.FLOOR_SLOTS + z * GiPatches.SIZE + x);
						if (patch[3] > 0.5f)
						{
							for (int c = 0; c < 3; ++c)
							{
								sum[c] += patch[c] * w;
							}
							wsum += w;
						}
					}
					if (wsum > 0)
					{
						float a = Math.min(1, wsum / define("GI_FULL_WEIGHT")) * reach * facingUp;
						acc = over(acc, new float[]{sum[0] / wsum, sum[1] / wsum, sum[2] / wsum, a});
					}
				}
			}
		}

		if (acc[3] < 1)
		{
			int ko = (fx >= 0.5f ? 1 : 0) + (fz >= 0.5f ? 2 : 0);
			int plane = plane(tap[ko], y, define("GI_FLOOR_SLACK"));
			float[] layer = floor(p, light, x0, z0, sw, tap, plane, y);
			if (plane > 0)
			{
				layer[3] *= smoothstep(0, define("GI_FLOOR_BLEND"), tap[ko][plane] + define("GI_FLOOR_SLACK") - y);
			}
			acc = over(acc, layer);
			if (plane > 0 && acc[3] < 1)
			{
				acc = over(acc, floor(p, light, x0, z0, sw, tap, plane - 1, y));
			}
		}
		return acc[3] > 0 ? new float[]{acc[0] / acc[3], acc[1] / acc[3], acc[2] / acc[3], acc[3]} : new float[4];
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
		float top = 1 - smoothstep(bands, bands + define("GI_WALL_TOP"), b);
		if (top <= 0)
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
		return new float[]{(p0[0] * w0 + p1[0] * w1) / w, (p0[1] * w0 + p1[1] * w1) / w, (p0[2] * w0 + p1[2] * w1) / w, top};
	}

	/** giWallFace. */
	private static float[] wallFace(GiPatches p, float[] light, float y, int ox, int oz, int face, int kind, int alongX, int alongZ,
		float along, int plane)
	{
		float[] e0 = faceAt(p, ox, oz, face, plane);
		if (e0[1] < 0.5f || ((int) (e0[1] + 0.5f) >> 3) != kind)
		{
			return new float[4];
		}
		int sign = along < 0 ? -1 : 1;
		int px = ox + sign * alongX, pz = oz + sign * alongZ;
		float[] e1 = inside(px, pz) ? faceAt(p, px, pz, face, plane) : new float[4];
		if (((int) (e1[1] + 0.5f) >> 3) != kind)
		{
			e1 = new float[4];
		}
		float[] w0 = bands(p, light, e0, y), w1 = bands(p, light, e1, y);
		float a0 = (1 - Math.abs(along)) * w0[3], a1 = Math.abs(along) * w1[3];
		float wsum = a0 + a1;
		if (wsum <= 0)
		{
			return new float[4];
		}
		return new float[]{(w0[0] * a0 + w1[0] * a1) / wsum, (w0[1] * a0 + w1[1] * a1) / wsum,
			(w0[2] * a0 + w1[2] * a1) / wsum, Math.min(1, wsum / define("GI_FULL_WEIGHT"))};
	}

	/** giWall. */
	static float[] wall(GiPatches p, float[] light, float[] world, float[] n)
	{
		float ax = Math.abs(n[0]), az = Math.abs(n[2]);
		boolean diagonal = Math.min(ax, az) >= define("GI_AXIS_RATIO") * Math.max(ax, az);
		float reachLimit = define("GI_WALL_REACH");
		int ox, oz, face, alongX = 0, alongZ = 0, kind = 0;
		float along, dist;
		if (diagonal)
		{
			ox = (int) Math.floor(world[0] / GiPatches.TILE);
			oz = (int) Math.floor(world[2] / GiPatches.TILE);
			face = n[0] > 0 ? 5 : 4;
			float lx = world[0] - ox * GiPatches.TILE, lz = world[2] - oz * GiPatches.TILE;
			float perp;
			kind = n[0] * n[2] < 0 ? 1 : 2;
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
			dist = Math.abs(perp);
		}
		else if (ax >= az)
		{
			float edge = (float) Math.floor(world[0] / GiPatches.TILE + 0.5f);
			dist = Math.abs(world[0] - edge * GiPatches.TILE);
			ox = (int) edge - (n[0] < 0 ? 1 : 0);
			oz = (int) Math.floor(world[2] / GiPatches.TILE);
			face = n[0] > 0 ? 3 : 1;
			alongZ = 1;
			along = world[2] / GiPatches.TILE - oz - 0.5f;
		}
		else
		{
			float edge = (float) Math.floor(world[2] / GiPatches.TILE + 0.5f);
			dist = Math.abs(world[2] - edge * GiPatches.TILE);
			ox = (int) Math.floor(world[0] / GiPatches.TILE);
			oz = (int) edge - (n[2] < 0 ? 1 : 0);
			face = n[2] > 0 ? 2 : 0;
			alongX = 1;
			along = world[0] / GiPatches.TILE - ox - 0.5f;
		}
		float reach = 1 - smoothstep(0.5f * reachLimit, reachLimit, dist);
		if (reach <= 0 || !inside(ox, oz))
		{
			return new float[4];
		}
		float[] floors = tile(p, ox, oz);
		int plane = plane(floors, world[1], define("GI_WALL_SLACK"));
		float[] layer = wallFace(p, light, world[1], ox, oz, face, kind, alongX, alongZ, along, plane);
		if (plane > 0)
		{
			layer[3] *= smoothstep(0, define("GI_WALL_BLEND"), floors[plane] + define("GI_WALL_SLACK") - world[1]);
		}
		if (plane > 0 && layer[3] < 1)
		{
			float[] acc = over(new float[]{layer[0] * layer[3], layer[1] * layer[3], layer[2] * layer[3], layer[3]},
				wallFace(p, light, world[1], ox, oz, face, kind, alongX, alongZ, along, plane - 1));
			float a = Math.max(acc[3], 1e-4f);
			layer = new float[]{acc[0] / a, acc[1] / a, acc[2] / a, acc[3]};
		}
		return new float[]{layer[0], layer[1], layer[2], layer[3] * reach};
	}

	/**
	 * giIrradiance for the surface at world position pos facing unit n, far from the scene's edges: rgb and how much of
	 * it counts, from the patches' light (rgb per patch).
	 */
	static float[] irradiance(GiPatches p, float[] light, float[] pos, float[] n)
	{
		float[] acc = new float[4];
		float wall = 1 - smoothstep(define("GI_WALL_NORMAL_MIN"), define("GI_WALL_NORMAL_MAX"), Math.abs(n[1]));
		if (wall > 0)
		{
			float[] w = wall(p, light, pos, n);
			acc = over(acc, new float[]{w[0], w[1], w[2], w[3] * wall});
		}
		if (acc[3] < 1)
		{
			float[] g = ground(p, light, pos, pos[1] + Math.max(n[1], 0) * define("GI_DOWN_BIAS"),
				smoothstep(0, define("GI_ROOF_NORMAL"), -n[1]));
			acc = over(acc, g);
		}
		return acc[3] > 0 ? new float[]{acc[0] / acc[3], acc[1] / acc[3], acc[2] / acc[3], acc[3]} : new float[4];
	}
}
