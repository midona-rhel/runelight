package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
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

	// ---- a wall next to a light (composite_frag.glsl's pointLightNdl)

	/** pointLightNdl of the composite, as it is there. */
	static float taken(float ndl, float dist)
	{
		float near = 1 - smoothstep(48, 640, dist);
		float facing = smoothstep(-0.35f, 0.05f, ndl);
		float floor = near * 0.5f, knee = Math.max(near * 0.2f, 1e-3f), s = Math.max(ndl, 0) - floor;
		float lifted = s >= knee ? s : s <= -knee ? 0 : (s + knee) * (s + knee) / (4 * knee);
		return facing * (floor + lifted);
	}

	private static float smoothstep(float a, float b, float v)
	{
		float t = Math.max(0, Math.min(1, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	/** The map's cells with a wall along a tile row and a light in front of it, baked, on the CPU. */
	private static Mirror wallAndLight(LightMap map, float lightTileY)
	{
		BakeScene scene = new BakeScene();
		for (int x = 14; x <= 36; ++x)
		{
			scene.southWall(0, x, 24);
		}
		map.setBlockers(scene.blockers());
		map.add(new LightMap.Source(0, BakeScene.world(24.5f), -60, BakeScene.world(lightTileY), 4 * 128, 1, 0.5f, 0.2f, 8, false, 1));
		Mirror m = new Mirror();
		map.runTasks();
		map.update(100000, m);
		return m;
	}

	/**
	 * What a pixel of the wall (facing north, +z) at this distance along it and this height takes of the light, as the
	 * shader works it out: the light read in front of the wall, along the direction to its mean place, the wall's normal
	 * tilted as vertex normals tilt it, and N.L as it is, or as pointLightNdl has it.
	 */
	private static float wallPixel(Mirror m, float along, float height, boolean fixed)
	{
		float zw = BakeScene.world(24), lx = BakeScene.world(24.5f);
		float wx = lx + along, wy = -height, wz = zw + 0.01f;
		float ax = wx, az = wz + 20;
		float cx = ax / 32 + 16, cz = az / 32 + 16;
		float total = 0.2126f * m.linear(m.a, cx, cz, 0) + 0.7152f * m.linear(m.a, cx, cz, 1) + 0.0722f * m.linear(m.a, cx, cz, 2);
		if (total <= 0)
		{
			return 0;
		}
		float mx = m.linear(m.b, cx, cz, 0) * 16 / total, my = m.linear(m.b, cx, cz, 2) * 16 / total,
			mz = m.linear(m.b, cx, cz, 1) * 16 / total;
		float lxv = ax + mx - wx, lyv = my - wy, lzv = az + mz - wz;
		float d = (float) Math.sqrt(lxv * lxv + lyv * lyv + lzv * lzv);
		// each tile's face tilts one way at its west edge and the other at its east, a few degrees (the corner vertices
		// share the end and top faces' normals), and a little up
		float f = ((wx / 128) % 1 + 1) % 1;
		float tx = 0.12f * (1 - 2 * f), ty = -0.07f;
		float len = (float) Math.sqrt(tx * tx + ty * ty + 1);
		float ndl = (tx * lxv + ty * lyv + lzv) / (len * d);
		return total * (fixed ? taken(ndl, d) : Math.max(ndl, 0));
	}

	/** The biggest jump between neighbouring samples along the wall, over their mean. */
	private static float biggestJump(Mirror m, float from, float to, float height, boolean fixed)
	{
		float worst = 0, previous = wallPixel(m, from, height, fixed);
		for (float along = from + 4; along <= to; along += 4)
		{
			float v = wallPixel(m, along, height, fixed);
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
			float before = biggestJump(m, 64, 320, height, false), after = biggestJump(m, 64, 320, height, true);
			assertTrue("before " + before, before > 1);
			assertTrue("after " + after, after < 0.25f);
		}
	}

	@Test
	public void theNdlIsAsItWasForSurfacesFacingTheLightOrFarFromIt()
	{
		for (float dist : new float[]{20, 100, 300, 600})
		{
			// facing the light, past the floor and its knee
			for (float ndl : new float[]{0.7f, 0.8f, 1})
			{
				assertEquals(ndl, taken(ndl, dist), 1e-6);
			}
		}
		for (float ndl : new float[]{0, 0.1f, 0.3f, 0.6f, 1})
		{
			assertEquals("far from the light", ndl, taken(ndl, 640), 1e-3);
		}
		// turned away from the light: dark, however near
		assertEquals(0, taken(-0.5f, 10), 0);
		assertEquals(0, taken(-1, 10), 0);
		assertEquals(0, taken(-0.36f, 10), 0.01f);
		// a surface next to a light takes at least half of it however it is turned (but away), and the part of the light
		// it takes only rises as the light comes nearer
		assertEquals(0.5f, taken(0.1f, 10), 0.1f);
		float previous = 0;
		for (float dist = 640; dist >= 10; dist -= 5)
		{
			float v = taken(0.1f, dist);
			assertTrue(v >= previous - 1e-6);
			previous = v;
		}
		// and goes with the angle and the distance smoothly
		for (float dist : new float[]{10, 100, 300, 500})
		{
			float last = taken(-0.5f, dist);
			for (float ndl = -0.5f; ndl <= 1; ndl += 0.01f)
			{
				float v = taken(ndl, dist);
				assertTrue("smooth at " + ndl + " / " + dist, Math.abs(v - last) < 0.06f);
				last = v;
			}
		}
	}
}
