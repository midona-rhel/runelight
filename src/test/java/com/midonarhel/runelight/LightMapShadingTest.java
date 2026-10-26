package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The shader's reading of the light map (light_map.glsl), on the CPU: what a floor point is lit with, point to point.
 * Lights of very different range and height side by side, one of them well above its baked strength: each is worked out
 * for the point itself (its falloff by the distance, from the table), times the map's visibility, so nothing is cut at a
 * range and nothing steps from cell to cell.
 */
public class LightMapShadingTest
{
	private static final int CELLS = BakeScene.SIZE * 4;
	private static final int OFFSET_CELLS = BakeScene.OFFSET * 4;

	private static final class Mirror implements LightMap.Sink
	{
		// the ground floor: visibility per height layer (a channel per slot), and the index
		final float[][][] vis = new float[LightMapBake.HEIGHT_LAYERS][CELLS][CELLS * 4];
		final int[][] index = new int[CELLS][CELLS * 4];

		@Override
		public void block(int plane, int bx, int bz, byte[] v, byte[] ix)
		{
			if (plane != 0)
			{
				return;
			}
			int n = LightMap.BLOCK * LightMap.BLOCK;
			for (int j = 0; j < LightMap.BLOCK; ++j)
			{
				for (int i = 0; i < LightMap.BLOCK; ++i)
				{
					for (int c = 0; c < 4; ++c)
					{
						for (int layer = 0; layer < LightMapBake.HEIGHT_LAYERS; ++layer)
						{
							vis[layer][bz * 32 + j][(bx * 32 + i) * 4 + c] = v == null ? 0 : (v[(layer * n + j * 32 + i) * 4 + c] & 255) / 255f;
						}
						int at = ((c >> 1) * n + j * 32 + i) * 4 + (c & 1) * 2;
						index[bz * 32 + j][(bx * 32 + i) * 4 + c] = ix == null ? 0 : (ix[at] & 255) + 256 * (ix[at + 1] & 255);
					}
				}
			}
		}

		@Override
		public void floor(int plane, int bx, int bz, float[] floor)
		{
		}

		/** Bilinear read of the slot's visibility at the layer, at cell coordinates (texel centres at +0.5). */
		float linear(int layer, float x, float z, int slot)
		{
			float fx = x - 0.5f, fz = z - 0.5f;
			int ix = (int) Math.floor(fx), iz = (int) Math.floor(fz);
			float tx = fx - ix, tz = fz - iz;
			float v = 0;
			for (int dz = 0; dz < 2; ++dz)
			{
				for (int dx = 0; dx < 2; ++dx)
				{
					int px = ix + dx, pz = iz + dz;
					float w = (dx == 0 ? 1 - tx : tx) * (dz == 0 ? 1 - tz : tz);
					if (px >= 0 && pz >= 0 && px < CELLS && pz < CELLS)
					{
						v += w * vis[layer][pz][px * 4 + slot];
					}
				}
			}
			return v;
		}

		/**
		 * The light a point (world units) gets, read at the cell coordinates (x, z), as composite_frag.glsl works it out:
		 * each light of the cell, its colour from the table times its falloff by the distance and its visibility. The
		 * normal is the given direction (0 for none: the light as it is).
		 */
		float shade(LightMap map, int layer, float x, float z, float wx, float wy, float wz, float nx, float ny, float nz)
		{
			float sum = 0;
			int cx = (int) Math.floor(x), cz = (int) Math.floor(z);
			float[] t = map.table();
			for (int slot = 0; slot < LightMap.SLOTS; ++slot)
			{
				int id = index[cz][cx * 4 + slot];
				if (id == 0)
				{
					continue;
				}
				int at = id * LightMap.TABLE_STRIDE * 4;
				float lx = t[at] - wx, ly = t[at + 1] - wy, lz = t[at + 2] - wz;
				float d2 = lx * lx + ly * ly + lz * lz, r2 = t[at + 3] * t[at + 3];
				if (d2 >= r2)
				{
					continue;
				}
				float d = (float) Math.sqrt(Math.max(d2, 1));
				float ndl = nx == 0 && ny == 0 && nz == 0 ? 1 : Math.max((lx * nx + ly * ny + lz * nz) / d, 0);
				float att = 1 - (float) Math.sqrt(d2 / r2);
				float luminance = 0.2126f * t[at + 4] + 0.7152f * t[at + 5] + 0.0722f * t[at + 6];
				sum += luminance * linear(layer, x, z, slot) * att * att * ndl;
			}
			return sum;
		}
	}

	private static LightMap.Source source(float tx, float ty, float height, float radiusTiles, float strength, float r, float g, float b)
	{
		return new LightMap.Source(0, BakeScene.world(tx), -height, BakeScene.world(ty), radiusTiles * 128, r, g, b, strength, false, 1, 0);
	}

	private static float biggestStep(float[] v)
	{
		float max = 0, step = 0;
		for (int i = 0; i < v.length; ++i)
		{
			max = Math.max(max, v[i]);
			if (i > 0)
			{
				step = Math.max(step, Math.abs(v[i] - v[i - 1]));
			}
		}
		return step / max;
	}

	@Test
	public void lightsOfDifferentRangeFadeToNothingWithoutAnEdge()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		// a wide dim light high up, a small strong one low in it, a third at the side with a flicker's table level
		LightMap.Source wide = source(24.5f, 24.5f, 150, 9, 1.5f, 1, 0.6f, 0.4f);
		LightMap.Source small = source(26.5f, 24.5f, 40, 2.5f, 6, 1, 0.3f, 0.2f);
		LightMap.Source side = source(21.5f, 27.5f, 100, 5, 3, 0.4f, 0.6f, 1);
		map.add(wide);
		map.add(small);
		map.add(side);
		Mirror m = new Mirror();
		map.runTasks();
		map.update(100000, m);
		map.setLevel(side, 4.2f, side.radius);
		// along rows and a diagonal through the middle, a point every quarter of a cell
		for (int line = 0; line < 3; ++line)
		{
			float[] v = new float[160];
			for (int i = 0; i < v.length; ++i)
			{
				float x = 4 * 14 + i * 0.5f, z = 4 * 24.5f + (line == 0 ? 0 : line == 1 ? 6 : i * 0.5f - 40);
				v[i] = m.shade(map, 0, x, z, (x - OFFSET_CELLS) * 32, 0, (z - OFFSET_CELLS) * 32, 0, 0, 0);
			}
			assertTrue("line " + line + " step " + biggestStep(v), biggestStep(v) < 0.1f);
		}
	}

	// ---- a wall next to a light (composite_frag.glsl's pointLights takes its face normal)

	/** The map's cells with a wall along a tile row and a light in front of it, baked, on the CPU. */
	private static Mirror wallAndLight(LightMap map, float lightTileY)
	{
		BakeScene scene = new BakeScene();
		for (int x = 14; x <= 36; ++x)
		{
			scene.southWall(0, x, 24);
		}
		map.setBlockers(scene.blockers());
		map.add(new LightMap.Source(0, BakeScene.world(24.5f), -60, BakeScene.world(lightTileY), 4 * 128, 1, 0.5f, 0.2f, 8, false, 1, 0));
		Mirror m = new Mirror();
		map.runTasks();
		map.update(100000, m);
		return m;
	}

	/**
	 * What a pixel of the wall (facing north, +z) at this distance along it and this height takes of the light, as the
	 * shader works it out: the visibility read in front of the wall, each light worked out for the pixel, and N.L with the
	 * wall's face normal.
	 */
	private static float wallPixel(LightMap map, Mirror m, float along, float height)
	{
		float zw = BakeScene.world(24), lx = BakeScene.world(24.5f);
		float wx = lx + along, wy = -height, wz = zw + 0.01f;
		float ax = wx, az = wz + 20;
		float cx = ax / 32 + OFFSET_CELLS, cz = az / 32 + OFFSET_CELLS;
		return m.shade(map, (int) (height / LightMapBake.LAYER_HEIGHT), cx, cz, wx, wy, wz, 0, 0, 1);
	}

	/** The biggest jump between neighbouring samples along the wall, over their mean. */
	private static float biggestJump(LightMap map, Mirror m, float from, float to, float height)
	{
		float worst = 0, previous = wallPixel(map, m, from, height);
		for (float along = from + 4; along <= to; along += 4)
		{
			float v = wallPixel(map, m, along, height);
			worst = Math.max(worst, Math.abs(v - previous) / Math.max((v + previous) / 2, 1e-4f));
			previous = v;
		}
		return worst;
	}

	@Test
	public void aWallBesideALightGlowsInAGradientNotInBands()
	{
		LightMap map = new LightMap(0);
		Mirror m = wallAndLight(map, 24.3f);
		for (float height : new float[]{30, 70})
		{
			float jump = biggestJump(map, m, 64, 320, height);
			assertTrue("jump " + jump + " at " + height, jump < 0.25f);
		}
	}
}
