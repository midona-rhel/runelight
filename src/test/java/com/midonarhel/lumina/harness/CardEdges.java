package com.midonarhel.lumina.harness;

import java.util.Arrays;
import java.util.List;

/**
 * Stepped edges: how far the line where a lamp's light passes through half lies from a straight line, in pixels. On a
 * flat face a shadow's edge is a straight line in the world and so on screen; a shadow map or a grid of cells draws it
 * as a staircase of its texels. The share of light V (measured over expected, NaN where unknown) is read on a grid of
 * samples; where it crosses 0.5 between two neighbours on the same plane there is a point of the edge, placed by
 * interpolation. Points on neighbouring rows (for the steep stretches of an edge) or columns (for the flat ones) chain
 * up; every WINDOW points of a chain (overlapping by half) take a straight line by least squares, and the root mean square of what is left is
 * one reading. A straight edge reads about 0, however soft; steps of s pixels read about a third of s or more.
 * (CardEdgesTest draws both.)
 */
final class CardEdges
{
	static final int WINDOW = 16;
	private static final float LINK = 8;

	private CardEdges()
	{
	}

	/** Adds to out each window's residual, in grid cells, along rows and along columns. */
	static void residuals(float[] v, int[] plane, int w, int h, List<Float> out)
	{
		// along rows: each row's crossings of 0.5
		scan(v, plane, 1, w, w, h, out);
		// along columns
		scan(v, plane, w, 1, h, w, out);
	}

	// lines run along step (1: rows, w: columns), next line next away; n cells a line, lines of them
	private static void scan(float[] v, int[] plane, int step, int next, int n, int lines, List<Float> out)
	{
		// the chains still growing: last position, its index in chains
		float[] lastPos = new float[n * 4], newPos = new float[n * 4];
		int[] lastChain = new int[n * 4], newChain = new int[n * 4];
		int lastCount = 0;
		float[][] buf = new float[64][];
		int[] len = new int[64];
		int used = 0;
		for (int line = 0; line < lines; ++line)
		{
			int newCount = 0;
			for (int a = 0; a + 1 < n; ++a)
			{
				int i = line * next + a * step, j = i + step;
				float va = v[i], vb = v[j];
				if (Float.isNaN(va) || Float.isNaN(vb) || plane[i] == 0 || plane[i] != plane[j] || (va < 0.5f) == (vb < 0.5f))
				{
					continue;
				}
				float pos = a + (0.5f - va) / (vb - va);
				// the nearest of the line before: a step of a staircase is as far as LINK
				int chain = -1, from = -1;
				float nearest = LINK;
				for (int k = 0; k < lastCount; ++k)
				{
					float gap = Math.abs(lastPos[k] - pos);
					if (lastChain[k] >= 0 && gap <= nearest)
					{
						nearest = gap;
						from = k;
					}
				}
				if (from >= 0)
				{
					chain = lastChain[from];
					lastChain[from] = -1;
				}
				if (chain < 0)
				{
					if (used == buf.length)
					{
						buf = Arrays.copyOf(buf, used * 2);
						len = Arrays.copyOf(len, used * 2);
					}
					chain = used++;
					buf[chain] = new float[32];
				}
				if (len[chain] == buf[chain].length)
				{
					buf[chain] = Arrays.copyOf(buf[chain], len[chain] * 2);
				}
				buf[chain][len[chain]++] = pos;
				if (newCount < newPos.length)
				{
					newPos[newCount] = pos;
					newChain[newCount++] = chain;
				}
			}
			float[] t = lastPos;
			lastPos = newPos;
			newPos = t;
			int[] c = lastChain;
			lastChain = newChain;
			newChain = c;
			lastCount = newCount;
		}
		for (int c = 0; c < used; ++c)
		{
			float[] p = buf[c];
			for (int s = 0; s + WINDOW <= len[c]; s += WINDOW / 2)
			{
				float r = fit(p, s);
				if (r >= 0)
				{
					out.add(r);
				}
			}
		}
	}

	// the root mean square of a window's distances from its line; -1 for a stretch steeper than 45 degrees to the lines
	// (the other direction's scan reads those)
	private static float fit(float[] p, int s)
	{
		double sx = 0, sy = 0, sxx = 0, sxy = 0;
		for (int i = 0; i < WINDOW; ++i)
		{
			sx += i;
			sy += p[s + i];
			sxx += i * i;
			sxy += i * p[s + i];
		}
		double b = (WINDOW * sxy - sx * sy) / (WINDOW * sxx - sx * sx), a = (sy - b * sx) / WINDOW;
		if (Math.abs(b) > 1.05)
		{
			return -1;
		}
		double e = 0;
		for (int i = 0; i < WINDOW; ++i)
		{
			double d = p[s + i] - a - b * i;
			e += d * d;
		}
		// perpendicular to the line
		return (float) Math.sqrt(e / WINDOW / (1 + b * b));
	}
}
