/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.shadow;

import com.midonarhel.lumina.scene.SceneContext;

/**
 * The local-space bounding boxes of a scene's zones, from the scene's tile heights: a zone covers its 8x8 extended
 * tiles horizontally and, vertically, from {@link ShadowFit#OBJECT_HEIGHT} above its highest terrain corner on any
 * level down to its lowest. Projected through a {@link ShadowFit} they say which zones can cast into a map (any box
 * whose light-space rectangle meets the map's: a tall zone just outside the square still casts into it at a low
 * caster) and which texels a zone's redraw touches. Also the scene's own height bounds. Pure math, no GL.
 */
public final class ZoneBounds
{
	private static final int TILE = 128;

	private int sizeX, sizeZ;
	// per zone: local x, z range and y range (y down: minY is the highest point)
	private float[] minX, maxX, minY, maxY, minZ, maxZ;
	/** The scene's height bounds over every level, world units (y down). */
	public float sceneMinY, sceneMaxY;

	private final float[] p = new float[3];

	/** Allocates for a scene's zone grid. */
	public void resize(int sizeX, int sizeZ)
	{
		if (sizeX != this.sizeX || sizeZ != this.sizeZ)
		{
			this.sizeX = sizeX;
			this.sizeZ = sizeZ;
			int n = sizeX * sizeZ;
			minX = new float[n];
			maxX = new float[n];
			minY = new float[n];
			maxY = new float[n];
			minZ = new float[n];
			maxZ = new float[n];
		}
	}

	/**
	 * Computes every zone's box and the scene's height bounds.
	 *
	 * @param tileHeights the scene's tile heights, [level][extended x][extended z] (corners)
	 * @param sceneOffset the extended tile the local origin is at (SceneContext.SCENE_OFFSET for the top level)
	 */
	public void compute(int[][][] tileHeights, int sizeX, int sizeZ, int sceneOffset)
	{
		resize(sizeX, sizeZ);
		sceneMinY = Float.MAX_VALUE;
		sceneMaxY = -Float.MAX_VALUE;
		for (int zx = 0; zx < sizeX; ++zx)
		{
			for (int zz = 0; zz < sizeZ; ++zz)
			{
				computeZone(tileHeights, zx, zz, sceneOffset);
				int i = zx * sizeZ + zz;
				sceneMinY = Math.min(sceneMinY, minY[i] + ShadowFit.OBJECT_HEIGHT);
				sceneMaxY = Math.max(sceneMaxY, maxY[i]);
			}
		}
		if (sceneMinY > sceneMaxY)
		{
			sceneMinY = sceneMaxY = 0;
		}
	}

	/** Recomputes one zone's box (a rebuilt zone). */
	public void computeZone(int[][][] tileHeights, int zx, int zz, int sceneOffset)
	{
		int i = zx * sizeZ + zz;
		minX[i] = (zx * 8 - sceneOffset) * TILE;
		maxX[i] = minX[i] + 8 * TILE;
		minZ[i] = (zz * 8 - sceneOffset) * TILE;
		maxZ[i] = minZ[i] + 8 * TILE;
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int level = 0; level < tileHeights.length; ++level)
		{
			int[][] h = tileHeights[level];
			for (int x = zx * 8; x <= zx * 8 + 8 && x < h.length; ++x)
			{
				int[] col = h[x];
				for (int z = zz * 8; z <= zz * 8 + 8 && z < col.length; ++z)
				{
					int v = col[z];
					lo = Math.min(lo, v);
					hi = Math.max(hi, v);
				}
			}
		}
		if (lo > hi)
		{
			lo = hi = 0;
		}
		minY[i] = lo - ShadowFit.OBJECT_HEIGHT;
		maxY[i] = hi;
	}

	/** Sets one zone's box directly (tests). */
	public void set(int zx, int zz, float x0, float y0, float z0, float x1, float y1, float z1)
	{
		int i = zx * sizeZ + zz;
		minX[i] = x0;
		maxX[i] = x1;
		minY[i] = y0;
		maxY[i] = y1;
		minZ[i] = z0;
		maxZ[i] = z1;
	}

	/**
	 * The zone's light-space rectangle in {@code fit}'s receiver space, into {@code rect} as (u0, v0, u1, v1).
	 */
	public void rect(ShadowFit fit, int zx, int zz, float[] rect)
	{
		int i = zx * sizeZ + zz;
		rect[0] = rect[1] = Float.MAX_VALUE;
		rect[2] = rect[3] = -Float.MAX_VALUE;
		for (int c = 0; c < 8; ++c)
		{
			float x = (c & 1) == 0 ? minX[i] : maxX[i];
			float y = (c & 2) == 0 ? minY[i] : maxY[i];
			float z = (c & 4) == 0 ? minZ[i] : maxZ[i];
			fit.project(x, y, z, p);
			rect[0] = Math.min(rect[0], p[0]);
			rect[1] = Math.min(rect[1], p[1]);
			rect[2] = Math.max(rect[2], p[0]);
			rect[3] = Math.max(rect[3], p[1]);
		}
	}

	/** Whether the zone's light-space rectangle meets the map ([0,1]²). */
	public boolean overlapsMap(ShadowFit fit, int zx, int zz, float[] scratch)
	{
		rect(fit, zx, zz, scratch);
		return scratch[2] >= 0 && scratch[0] <= 1 && scratch[3] >= 0 && scratch[1] <= 1;
	}

	/** Whether the zone's light-space rectangle meets another rectangle (u0, v0, u1, v1). */
	public boolean overlapsRect(ShadowFit fit, int zx, int zz, float[] other, float[] scratch)
	{
		rect(fit, zx, zz, scratch);
		return scratch[2] >= other[0] && scratch[0] <= other[2] && scratch[3] >= other[1] && scratch[1] <= other[3];
	}

	/**
	 * A receiver-space rectangle as texels of a map of {@code size}, clamped to the map and padded by a texel each
	 * way for the filter: into {@code out} as (x, y, width, height); false when it misses the map.
	 */
	public static boolean texelRect(float[] rect, int size, int[] out)
	{
		int x0 = Math.max(0, (int) Math.floor(rect[0] * size) - 1);
		int y0 = Math.max(0, (int) Math.floor(rect[1] * size) - 1);
		int x1 = Math.min(size, (int) Math.ceil(rect[2] * size) + 1);
		int y1 = Math.min(size, (int) Math.ceil(rect[3] * size) + 1);
		if (x1 <= x0 || y1 <= y0)
		{
			return false;
		}
		out[0] = x0;
		out[1] = y0;
		out[2] = x1 - x0;
		out[3] = y1 - y0;
		return true;
	}

	/** The extended tile the top-level scene's local origin is at. */
	public static int topLevelOffset()
	{
		return SceneContext.SCENE_OFFSET;
	}
}
