package com.midonarhel.runelight;

import java.util.Arrays;

/**
 * The global illumination's voxel proxy of the static scene: zone triangles (GiCapture) rasterized
 * conservatively into half-tile cubes, each with the area weighted average albedo (linear, unshaded) and normal of
 * what passes through it. Zones are voxelized once and the result kept with the zone, so a reused zone after a
 * region load isn't redone; the scene's grid (GiGrid) is assembled from them. A voxel also remembers how far the
 * surface lies from its center, which the volume of tint (GiEmitters) needs to tell a surface a bit above a voxel's
 * center from one a bit below it.
 */
final class GiVoxels
{
	static final int VOXEL = GiGrid.VOXEL;
	// floats per voxel in Zone.data
	static final int STRIDE = 9;
	// zone-local voxel coordinates may reach this far past the zone (big models overhang it)
	private static final int MARGIN = 32;
	private static final int LAYER_BIAS = 2048;
	// albedo of textured faces until the textures' average colors are known
	private static final float DEFAULT_ALBEDO = 0.2f;

	/**
	 * Average color of each game texture, linear rgb interleaved; set when the textures upload (TextureManager).
	 */
	static volatile float[] textureAlbedo;

	/** One zone's voxels: sums of weight, albedo * weight, normal * weight and plane distance * weight. */
	static final class Zone
	{
		// (lx + MARGIN) | (lz + MARGIN) << 7 | (layer + LAYER_BIAS) << 14
		int[] keys;
		// w, r, g, b, nx, ny, nz, foliage w, and the signed distance from the voxel's center to the surface, along its
		// normal and positive in front of it, times w, per voxel
		float[] data;
		int count;

		static int lx(int key)
		{
			return (key & 0x7f) - MARGIN;
		}

		static int lz(int key)
		{
			return (key >> 7 & 0x7f) - MARGIN;
		}

		static int layer(int key)
		{
			return (key >>> 14) - LAYER_BIAS;
		}
	}

	private GiVoxels()
	{
	}

	/** An array for {@link #textureAverage} with no texture known yet. */
	static float[] newTextureAlbedo(int textures)
	{
		float[] a = new float[textures * 3];
		Arrays.fill(a, -1);
		return a;
	}

	/** Store a texture's average linear color (ARGB pixels, 0 for holes) into out. */
	static void textureAverage(int textureId, int[] pixels, float[] out)
	{
		double r = 0, g = 0, b = 0;
		int n = 0;
		for (int p : pixels)
		{
			if (p == 0)
			{
				continue;
			}
			r += srgbToLinear((p >> 16 & 0xff) / 255f);
			g += srgbToLinear((p >> 8 & 0xff) / 255f);
			b += srgbToLinear((p & 0xff) / 255f);
			n++;
		}
		if (n > 0 && textureId * 3 + 2 < out.length)
		{
			out[textureId * 3] = (float) (r / n);
			out[textureId * 3 + 1] = (float) (g / n);
			out[textureId * 3 + 2] = (float) (b / n);
		}
	}

	static float srgbToLinear(float c)
	{
		return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055) / 1.055, 2.4);
	}

	/** Linear albedo of a captured triangle's color into out[0..2]. */
	static void albedo(int color, float[] out)
	{
		int tex = color >>> 16 & 0x3ff;
		if (tex > 0)
		{
			float[] a = textureAlbedo;
			int i = (tex - 1) * 3;
			if (a != null && i + 2 < a.length && a[i] >= 0)
			{
				out[0] = a[i];
				out[1] = a[i + 1];
				out[2] = a[i + 2];
			}
			else
			{
				out[0] = out[1] = out[2] = DEFAULT_ALBEDO;
			}
			return;
		}
		hslToRgb(color & 0xffff, out);
		for (int c = 0; c < 3; ++c)
		{
			out[c] = srgbToLinear(out[c]);
		}
	}

	/** The game's packed hsl to sRGB, as hsl_to_rgb.glsl. */
	static void hslToRgb(int hsl, float[] out)
	{
		float hue = (hsl >> 10 & 63) / 64f + 0.0078125f;
		float sat = (hsl >> 7 & 7) / 8f + 0.0625f;
		float lum = (hsl & 127) / 128f;
		float q = lum < 0.5f ? lum * (1 + sat) : lum + sat - lum * sat;
		float p = 2 * lum - q;
		out[0] = hue(p, q, hue + 1 / 3f);
		out[1] = hue(p, q, hue);
		out[2] = hue(p, q, hue - 1 / 3f);
	}

	private static float hue(float p, float q, float t)
	{
		if (t > 1)
		{
			t -= 1;
		}
		if (t < 0)
		{
			t += 1;
		}
		if (6 * t < 1)
		{
			return p + (q - p) * 6 * t;
		}
		if (2 * t < 1)
		{
			return q;
		}
		if (3 * t < 2)
		{
			return p + (q - p) * (2 / 3f - t) * 6;
		}
		return p;
	}

	/**
	 * Voxelize a zone's triangles: every voxel a triangle touches gets the triangle's area as weight.
	 */
	static Zone voxelize(GiCapture cap)
	{
		Accumulator acc = new Accumulator(Math.max(64, cap.count * 2));
		float[] rgb = new float[3];
		float[] v = new float[9];
		short[] pos = cap.pos;
		for (int t = 0; t < cap.count; ++t)
		{
			for (int i = 0; i < 9; ++i)
			{
				v[i] = pos[t * 9 + i];
			}
			float ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2];
			float bx = v[6] - v[0], by = v[7] - v[1], bz = v[8] - v[2];
			float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
			float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (len < 2)
			{
				// degenerate: a sliver can't block light
				continue;
			}
			float area = len * 0.5f;
			nx /= len;
			ny /= len;
			nz /= len;
			albedo(cap.color[t], rgb);
			rasterize(v, nx, ny, nz, area, rgb, (cap.color[t] & GiCapture.FOLIAGE) != 0, acc);
		}
		return acc.toZone();
	}

	private static void rasterize(float[] v, float nx, float ny, float nz, float area, float[] rgb, boolean foliage,
		Accumulator acc)
	{
		float minX = Math.min(v[0], Math.min(v[3], v[6])), maxX = Math.max(v[0], Math.max(v[3], v[6]));
		float minY = Math.min(v[1], Math.min(v[4], v[7])), maxY = Math.max(v[1], Math.max(v[4], v[7]));
		float minZ = Math.min(v[2], Math.min(v[5], v[8])), maxZ = Math.max(v[2], Math.max(v[5], v[8]));
		int x0 = Math.max(-MARGIN, Math.floorDiv((int) Math.floor(minX), VOXEL));
		int x1 = Math.min(127 - MARGIN, Math.floorDiv((int) Math.floor(maxX), VOXEL));
		int z0 = Math.max(-MARGIN, Math.floorDiv((int) Math.floor(minZ), VOXEL));
		int z1 = Math.min(127 - MARGIN, Math.floorDiv((int) Math.floor(maxZ), VOXEL));
		int y0 = Math.max(-LAYER_BIAS, Math.floorDiv((int) Math.floor(minY), VOXEL));
		int y1 = Math.min(LAYER_BIAS - 1, Math.floorDiv((int) Math.floor(maxY), VOXEL));
		boolean single = x0 == x1 && y0 == y1 && z0 == z1;
		// slightly larger than the voxel, so a triangle along a voxel face marks both sides
		float half = VOXEL * 0.5f + 0.5f;
		for (int y = y0; y <= y1; ++y)
		{
			for (int z = z0; z <= z1; ++z)
			{
				for (int x = x0; x <= x1; ++x)
				{
					if (!single && !triBoxOverlap((x + 0.5f) * VOXEL, (y + 0.5f) * VOXEL, (z + 0.5f) * VOXEL, half, v))
					{
						continue;
					}
					int key = (x + MARGIN) | (z + MARGIN) << 7 | (y + LAYER_BIAS) << 14;
					float plane = nx * ((x + 0.5f) * VOXEL - v[0]) + ny * ((y + 0.5f) * VOXEL - v[1])
						+ nz * ((z + 0.5f) * VOXEL - v[2]);
					acc.add(key, area, rgb, nx, ny, nz, foliage, plane);
				}
			}
		}
	}

	/**
	 * Triangle against an axis aligned cube, separating axis test (Akenine-Moller): the 3 box axes, the
	 * triangle's normal and the 9 cross products of edges and axes.
	 */
	static boolean triBoxOverlap(float cx, float cy, float cz, float h, float[] t)
	{
		float v0x = t[0] - cx, v0y = t[1] - cy, v0z = t[2] - cz;
		float v1x = t[3] - cx, v1y = t[4] - cy, v1z = t[5] - cz;
		float v2x = t[6] - cx, v2y = t[7] - cy, v2z = t[8] - cz;
		if (min3(v0x, v1x, v2x) > h || max3(v0x, v1x, v2x) < -h
			|| min3(v0y, v1y, v2y) > h || max3(v0y, v1y, v2y) < -h
			|| min3(v0z, v1z, v2z) > h || max3(v0z, v1z, v2z) < -h)
		{
			return false;
		}
		float e0x = v1x - v0x, e0y = v1y - v0y, e0z = v1z - v0z;
		float e1x = v2x - v1x, e1y = v2y - v1y, e1z = v2z - v1z;
		float e2x = v0x - v2x, e2y = v0y - v2y, e2z = v0z - v2z;

		// plane
		float nx = e0y * e1z - e0z * e1y, ny = e0z * e1x - e0x * e1z, nz = e0x * e1y - e0y * e1x;
		float d = nx * v0x + ny * v0y + nz * v0z;
		float r = h * (Math.abs(nx) + Math.abs(ny) + Math.abs(nz));
		if (d > r || d < -r)
		{
			return false;
		}

		return axis(e0x, e0y, e0z, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)
			&& axis(e1x, e1y, e1z, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h)
			&& axis(e2x, e2y, e2z, v0x, v0y, v0z, v1x, v1y, v1z, v2x, v2y, v2z, h);
	}

	// the three cross products of edge e with the x, y and z axes
	private static boolean axis(float ex, float ey, float ez, float ax, float ay, float az, float bx, float by,
		float bz, float cx, float cy, float cz, float h)
	{
		float fx = Math.abs(ex), fy = Math.abs(ey), fz = Math.abs(ez);
		// e x X = (0, ez, -ey)
		float p0 = ez * ay - ey * az, p1 = ez * by - ey * bz, p2 = ez * cy - ey * cz;
		float r = h * (fz + fy);
		if (min3(p0, p1, p2) > r || max3(p0, p1, p2) < -r)
		{
			return false;
		}
		// e x Y = (-ez, 0, ex)
		p0 = -ez * ax + ex * az;
		p1 = -ez * bx + ex * bz;
		p2 = -ez * cx + ex * cz;
		r = h * (fz + fx);
		if (min3(p0, p1, p2) > r || max3(p0, p1, p2) < -r)
		{
			return false;
		}
		// e x Z = (ey, -ex, 0)
		p0 = ey * ax - ex * ay;
		p1 = ey * bx - ex * by;
		p2 = ey * cx - ex * cy;
		r = h * (fy + fx);
		return !(min3(p0, p1, p2) > r || max3(p0, p1, p2) < -r);
	}

	private static float min3(float a, float b, float c)
	{
		return Math.min(a, Math.min(b, c));
	}

	private static float max3(float a, float b, float c)
	{
		return Math.max(a, Math.max(b, c));
	}

	/** Open addressing map from voxel key to its sums. */
	static final class Accumulator
	{
		private int[] keys;
		private float[] data;
		private int size;

		Accumulator(int expected)
		{
			int cap = Integer.highestOneBit(Math.max(16, expected * 2 - 1)) << 1;
			keys = new int[cap];
			Arrays.fill(keys, -1);
			data = new float[cap * STRIDE];
		}

		void add(int key, float w, float[] rgb, float nx, float ny, float nz, boolean foliage, float plane)
		{
			if (size * 2 >= keys.length)
			{
				grow();
			}
			int mask = keys.length - 1;
			int i = mix(key) & mask;
			while (keys[i] != -1 && keys[i] != key)
			{
				i = i + 1 & mask;
			}
			if (keys[i] == -1)
			{
				keys[i] = key;
				size++;
			}
			int o = i * STRIDE;
			data[o] += w;
			data[o + 1] += rgb[0] * w;
			data[o + 2] += rgb[1] * w;
			data[o + 3] += rgb[2] * w;
			data[o + 4] += nx * w;
			data[o + 5] += ny * w;
			data[o + 6] += nz * w;
			if (foliage)
			{
				data[o + 7] += w;
			}
			data[o + 8] += plane * w;
		}

		private static int mix(int k)
		{
			k *= 0x9E3779B1;
			return k ^ k >>> 16;
		}

		private void grow()
		{
			int[] ok = keys;
			float[] od = data;
			keys = new int[ok.length * 2];
			Arrays.fill(keys, -1);
			data = new float[keys.length * STRIDE];
			int mask = keys.length - 1;
			for (int j = 0; j < ok.length; ++j)
			{
				if (ok[j] == -1)
				{
					continue;
				}
				int i = mix(ok[j]) & mask;
				while (keys[i] != -1)
				{
					i = i + 1 & mask;
				}
				keys[i] = ok[j];
				System.arraycopy(od, j * STRIDE, data, i * STRIDE, STRIDE);
			}
		}

		Zone toZone()
		{
			Zone z = new Zone();
			z.keys = new int[size];
			z.data = new float[size * STRIDE];
			int n = 0;
			for (int j = 0; j < keys.length; ++j)
			{
				if (keys[j] != -1)
				{
					z.keys[n] = keys[j];
					System.arraycopy(data, j * STRIDE, z.data, n * STRIDE, STRIDE);
					n++;
				}
			}
			z.count = n;
			return z;
		}
	}
}
