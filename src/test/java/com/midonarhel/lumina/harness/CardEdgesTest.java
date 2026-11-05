package com.midonarhel.lumina.harness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The stepped edge reading on edges made in code: straight reads nothing, steps read what they should. */
public class CardEdgesTest
{
	private static final int W = 400, H = 300;

	private static float median(float[] v)
	{
		int[] plane = new int[W * H];
		Arrays.fill(plane, 1);
		List<Float> out = new ArrayList<>();
		CardEdges.residuals(v, plane, W, H, out);
		assertTrue(out.size() > 10);
		float[] a = new float[out.size()];
		for (int i = 0; i < a.length; ++i)
		{
			a[i] = out.get(i);
		}
		Arrays.sort(a);
		return a[a.length / 2];
	}

	@Test
	public void straightEdgeReadsNothing()
	{
		float[] v = new float[W * H];
		for (int y = 0; y < H; ++y)
		{
			for (int x = 0; x < W; ++x)
			{
				// soft over 6 cells, across a slope of 0.37
				v[y * W + x] = Math.max(0, Math.min(1, 0.5f + (x - 100 - 0.37f * y) / 6));
			}
		}
		assertEquals(0, median(v), 0.02);
	}

	@Test
	public void stepsReadAboutAThirdOfTheirSize()
	{
		float[] v = new float[W * H];
		int s = 8;
		for (int y = 0; y < H; ++y)
		{
			for (int x = 0; x < W; ++x)
			{
				// the same line drawn in texels of 8 cells, sharp
				int tx = x / s * s, ty = y / s * s;
				v[y * W + x] = tx - 100 - 0.37f * ty > 0 ? 1 : 0;
			}
		}
		float r = median(v);
		assertTrue("read " + r, r > 0.25 * s * 0.5 && r < 0.4 * s);
	}

	@Test
	public void otherPlanesDontJoin()
	{
		float[] v = new float[W * H];
		int[] plane = new int[W * H];
		for (int i = 0; i < v.length; ++i)
		{
			v[i] = i % W < W / 2 ? 0 : 1;
			plane[i] = i % W < W / 2 ? 1 : 2;
		}
		List<Float> out = new ArrayList<>();
		CardEdges.residuals(v, plane, W, H, out);
		assertTrue(out.isEmpty());
	}
}
