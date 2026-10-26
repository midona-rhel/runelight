package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * The shader's reading of the light map (light_map.glsl), on the CPU: what a floor point is lit with, point to point.
 * Lights of very different range and height side by side, one of them well above its baked strength: nothing read from
 * the nearest cell (the brightest light's index) may cut the light at a range, as the vertical falloff by its radius did.
 */
public class LightMapShadingTest
{
	private static final int CELLS = BakeScene.SIZE * 4;

	private static final class Mirror implements LightMap.Sink
	{
		final float[][][] a = new float[4][CELLS][CELLS * 4], b = new float[4][CELLS][CELLS * 4];

		@Override
		public void block(int plane, int bx, int bz, short[] sa, short[] sb)
		{
			for (int j = 0; j < LightMap.BLOCK; ++j)
			{
				for (int i = 0; i < LightMap.BLOCK; ++i)
				{
					for (int c = 0; c < 4; ++c)
					{
						a[plane][bz * 32 + j][(bx * 32 + i) * 4 + c] = sa == null ? 0 : Half.fromHalf(sa[(j * 32 + i) * 4 + c]);
						b[plane][bz * 32 + j][(bx * 32 + i) * 4 + c] = sb == null ? 0 : Half.fromHalf(sb[(j * 32 + i) * 4 + c]);
					}
				}
			}
		}

		/** Bilinear read of channel c of layer t at cell coordinates (texel centres at +0.5). */
		float linear(float[][][] t, float x, float z, int c)
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
						v += w * t[0][pz][px * 4 + c];
					}
				}
			}
			return v;
		}

		/** The luminance of the light a floor point at the cell coordinates gets, as light_map.glsl works it out. */
		float shade(LightMap map, float x, float z)
		{
			float total = 0.2126f * linear(a, x, z, 0) + 0.7152f * linear(a, x, z, 1) + 0.0722f * linear(a, x, z, 2);
			if (total <= 0)
			{
				return 0;
			}
			int id = Math.round(b[0][(int) Math.floor(z)][(int) Math.floor(x) * 4 + 3]);
			float swing = 1;
			if (id > 0)
			{
				float lead = Math.max(0, Math.min(1, linear(a, x, z, 3) / total));
				swing = 1 - lead + lead * map.table()[id * 4];
			}
			return total * swing;
		}
	}

	private static LightMap.Source source(float tx, float ty, float height, float radiusTiles, float strength, float r, float g, float b)
	{
		return new LightMap.Source(0, BakeScene.world(tx), -height, BakeScene.world(ty), radiusTiles * 128, r, g, b, strength, false, 1);
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
		map.setLevel(side, 4.2f);
		// along rows and a diagonal through the middle, a point every quarter of a cell
		for (int line = 0; line < 3; ++line)
		{
			float[] v = new float[160];
			for (int i = 0; i < v.length; ++i)
			{
				float x = 4 * 14 + i * 0.5f, z = 4 * 24.5f + (line == 0 ? 0 : line == 1 ? 6 : i * 0.5f - 40);
				v[i] = m.shade(map, x, z);
			}
			assertTrue("line " + line + " step " + biggestStep(v), biggestStep(v) < 0.1f);
		}
	}
}
