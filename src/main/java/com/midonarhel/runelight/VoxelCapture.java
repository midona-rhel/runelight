package com.midonarhel.runelight;

import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * The opaque triangles of one zone as SceneUploader writes them, kept for the voxel proxy (Voxelizer), which is built
 * later on its worker thread. Positions are zone-local x and z and world y, as in the static vertex format.
 */
final class VoxelCapture
{
	short[] pos = new short[9 * 256];
	// per triangle: it belongs to swaying foliage, which lets some light through
	boolean[] foliage = new boolean[256];
	int count;

	void add(int x0, int y0, int z0, int x1, int y1, int z1, int x2, int y2, int z2, boolean foliage)
	{
		if (count == this.foliage.length)
		{
			this.foliage = Arrays.copyOf(this.foliage, count * 2);
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
		this.foliage[count++] = foliage;
	}

	/** Drops the spare capacity once the zone is uploaded. */
	void trim()
	{
		foliage = Arrays.copyOf(foliage, count);
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

		final VoxelCapture capture = new VoxelCapture();
		private final int[] vx = new int[3], vy = new int[3], vz = new int[3], vw = new int[3], vc = new int[3],
			vt = new int[3];
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
			// the water bed lies sunk under the surface, which the grid gets from the water map instead (VoxelWater)
			if ((vt[0] & WaterMap.BED_FLAG) != 0)
			{
				return;
			}
			// wind codes (WindSway) are positive on swaying models, 0 where they stay put; -1 marks ground borders
			capture.add(vx[0], vy[0], vz[0], vx[1], vy[1], vz[1], vx[2], vy[2], vz[2],
				vw[0] > 0 && vw[1] > 0 && vw[2] > 0);
		}
	}
}
