package com.midonarhel.runelight;

import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * The opaque triangles of one zone as SceneUploader writes them, kept for the global illumination's voxel proxy
 * (GiVoxels), which is built later on its worker thread. Positions are zone-local x and z and world y, as in the
 * static vertex format; each triangle keeps the color of its first vertex. Triangles are stored with their front
 * face (cross(b - a, c - a), which points up for ground with y down) turned the way the vertex normals point, so
 * the voxels know which side of a surface is its front.
 */
final class GiCapture
{
	short[] pos = new short[9 * 256];
	// per triangle: hsl (16 bits) | texture id + 1 (10 bits) << 16 | FOLIAGE
	int[] color = new int[256];
	// the triangle belongs to swaying foliage, which lets some light through
	static final int FOLIAGE = 1 << 26;
	int count;

	void add(int x0, int y0, int z0, int x1, int y1, int z1, int x2, int y2, int z2, int color)
	{
		if (count == this.color.length)
		{
			this.color = Arrays.copyOf(this.color, count * 2);
			pos = Arrays.copyOf(pos, count * 2 * 9);
		}
		int o = count * 9;
		pos[o] = (short) x0;
		pos[o + 1] = (short) y0;
		pos[o + 2] = (short) z0;
		pos[o + 3] = (short) x1;
		pos[o + 4] = (short) y1;
		pos[o + 5] = (short) z1;
		pos[o + 6] = (short) x2;
		pos[o + 7] = (short) y2;
		pos[o + 8] = (short) z2;
		this.color[count++] = color;
	}

	/** Drops the spare capacity once the zone is uploaded. */
	void trim()
	{
		color = Arrays.copyOf(color, count);
		pos = Arrays.copyOf(pos, count * 9);
	}

	/**
	 * A zone's opaque vertex buffer that also records its triangles. Every static vertex is one put22224 (position,
	 * spare short, abhsl) followed by one put2222 (texture, uv, packed normal).
	 */
	static final class Buffer extends GpuIntBuffer
	{
		// transparency above this (abhsl's top byte, 0 opaque) doesn't block light
		private static final int MAX_ALPHA = 127;

		final GiCapture capture = new GiCapture();
		private final int[] vx = new int[3], vy = new int[3], vz = new int[3], vw = new int[3], vc = new int[3],
			vt = new int[3], vn = new int[3];
		private int k;

		Buffer(IntBuffer ib)
		{
			super(ib);
		}

		@Override
		void put22224(int x, int y, int z, int w, int v)
		{
			super.put22224(x, y, z, w, v);
			vx[k] = x;
			vy[k] = y;
			vz[k] = z;
			vw[k] = w;
			vc[k] = v;
		}

		@Override
		void put2222(int x, int y, int z, int w)
		{
			super.put2222(x, y, z, w);
			vt[k] = x;
			vn[k] = w & 0xffff;
			if (++k == 3)
			{
				k = 0;
				triangle();
			}
		}

		private void triangle()
		{
			if ((vc[0] >>> 24) > MAX_ALPHA)
			{
				return;
			}
			// the water bed lies sunk under the surface, which the grid gets from the water map instead (GiWater)
			if ((vt[0] & WaterMap.BED_FLAG) != 0)
			{
				return;
			}
			int color = vc[0] & 0xffff | (vt[0] & SurfaceMaterials.TEXTURE_MASK) << 16;
			// wind codes (WindSway) are positive on swaying models, 0 where they stay put; -1 marks ground borders
			if (vw[0] > 0 && vw[1] > 0 && vw[2] > 0)
			{
				color |= FOLIAGE;
			}
			if (frontMatchesNormals(vx, vy, vz, vn))
			{
				capture.add(vx[0], vy[0], vz[0], vx[1], vy[1], vz[1], vx[2], vy[2], vz[2], color);
			}
			else
			{
				capture.add(vx[0], vy[0], vz[0], vx[2], vy[2], vz[2], vx[1], vy[1], vz[1], color);
			}
		}
	}

	/**
	 * Whether the winding's front face agrees with the vertex normals (VertexShading's packed octahedral normals,
	 * 0 for none); without normals the winding is trusted.
	 */
	static boolean frontMatchesNormals(int[] x, int[] y, int[] z, int[] packed)
	{
		float ax = x[1] - x[0], ay = y[1] - y[0], az = z[1] - z[0];
		float bx = x[2] - x[0], by = y[2] - y[0], bz = z[2] - z[0];
		float fx = ay * bz - az * by, fy = az * bx - ax * bz, fz = ax * by - ay * bx;
		float d = 0;
		for (int i = 0; i < 3; ++i)
		{
			int p = packed[i];
			if (p == 0)
			{
				continue;
			}
			float u = ((p & 0xff) - 1) / 254f * 2 - 1;
			float v = ((p >> 8 & 0xff) - 1) / 254f * 2 - 1;
			float w = 1 - Math.abs(u) - Math.abs(v);
			if (w < 0)
			{
				float ou = u;
				u = (1 - Math.abs(v)) * (ou >= 0 ? 1 : -1);
				v = (1 - Math.abs(ou)) * (v >= 0 ? 1 : -1);
			}
			d += fx * u + fy * v + fz * w;
		}
		return d >= 0;
	}
}
