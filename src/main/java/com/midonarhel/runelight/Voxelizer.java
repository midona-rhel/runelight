package com.midonarhel.runelight;

import java.util.Arrays;

/**
 * The voxel proxy of the static scene, which the skylight traces (SkyGrid): zone triangles (VoxelCapture) rasterized
 * conservatively into half-tile cubes, each remembering how much surface and how much of it swaying foliage passes
 * through it, and how high the surface reaches in it (the roof's height is read from that). Zones are voxelized once and the result kept with the zone, so a reused zone after a region load isn't
 * redone; the scene's grid (VoxelGrid) is assembled from them.
 */
final class Voxelizer
{
	static final int VOXEL = VoxelGrid.VOXEL;
	// floats per voxel in Zone.data
	static final int STRIDE = 3;
	// zone-local voxel coordinates may reach this far past the zone (big models overhang it)
	private static final int MARGIN = 32;
	private static final int LAYER_BIAS = 2048;

	/** One zone's voxels: sums of the surface area and of the foliage's. */
	static final class Zone
	{
		// (lx + MARGIN) | (lz + MARGIN) << 7 | (layer + LAYER_BIAS) << 14
		int[] keys;
		// per voxel: the surface area (square world units) in it, the part of it that is foliage, and the world y of
		// the highest point of the surface found in it (y down, so the least), +infinity if none was found
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

	private Voxelizer()
	{
	}

	/**
	 * Voxelize a zone's triangles: every voxel a triangle touches gets the triangle's area.
	 */
	static Zone voxelize(VoxelCapture cap)
	{
		Accumulator acc = new Accumulator(Math.max(64, cap.count * 2));
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
			rasterize(v, len * 0.5f, cap.foliage[t], acc);
		}
		return acc.toZone();
	}

	private static void rasterize(float[] v, float area, boolean foliage, Accumulator acc)
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
					acc.add((x + MARGIN) | (z + MARGIN) << 7 | (y + LAYER_BIAS) << 14, area, foliage, highest(x, y, z, v));
				}
			}
		}
	}

	/**
	 * The world y of the highest point of the triangle that can be found in voxel (x, y, z): its corners inside the
	 * voxel's footprint and height, and the plane's height at the voxel's centre if the triangle covers it. Exact for
	 * a flat roof and a roof's slope, which is a plane; +infinity if none lies in the voxel.
	 */
	static float highest(int x, int y, int z, float[] v)
	{
		float lo = y * VOXEL, hi = lo + VOXEL;
		float x0 = x * VOXEL, x1 = x0 + VOXEL, z0 = z * VOXEL, z1 = z0 + VOXEL;
		float best = Float.POSITIVE_INFINITY;
		for (int i = 0; i < 9; i += 3)
		{
			if (v[i] >= x0 && v[i] <= x1 && v[i + 2] >= z0 && v[i + 2] <= z1 && v[i + 1] >= lo && v[i + 1] <= hi)
			{
				best = Math.min(best, v[i + 1]);
			}
		}
		// the plane through the triangle, y as a function of x and z, at the voxel's centre
		float cx = x0 + VOXEL * 0.5f, cz = z0 + VOXEL * 0.5f;
		float ax = v[3] - v[0], ay = v[4] - v[1], az = v[5] - v[2];
		float bx = v[6] - v[0], by = v[7] - v[1], bz = v[8] - v[2];
		// barycentric coordinates of the centre in x and z: (cx, cz) = v0 + s * a + t * b
		float det = ax * bz - az * bx;
		if (Math.abs(det) > 1e-3f)
		{
			float px = cx - v[0], pz = cz - v[2];
			float s = (px * bz - pz * bx) / det, t = (ax * pz - az * px) / det;
			float py = v[1] + s * ay + t * by;
			if (s >= 0 && t >= 0 && s + t <= 1 && py >= lo && py <= hi)
			{
				best = Math.min(best, py);
			}
		}
		return best;
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

		void add(int key, float w, boolean foliage, float highest)
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
				data[i * STRIDE + 2] = Float.POSITIVE_INFINITY;
				size++;
			}
			int o = i * STRIDE;
			data[o] += w;
			data[o + 2] = Math.min(data[o + 2], highest);
			if (foliage)
			{
				data[o + 1] += w;
			}
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
