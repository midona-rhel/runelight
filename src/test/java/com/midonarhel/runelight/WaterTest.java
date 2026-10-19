package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WaterTest
{
	private static final int SIZE = 128;

	private static int at(byte[] t, int x, int y, int c)
	{
		return t[(Math.floorMod(y, SIZE) * SIZE + Math.floorMod(x, SIZE)) * 4 + c] & 0xFF;
	}

	@Test
	public void noiseTilesWithoutSeams()
	{
		byte[] t = Water.noise(SIZE, 1);
		// the step across the wrap is no bigger than steps inside the texture
		int inside = 0, wrap = 0;
		for (int c = 0; c < 4; ++c)
		{
			for (int i = 0; i < SIZE; ++i)
			{
				for (int x = 1; x < SIZE; ++x)
				{
					inside = Math.max(inside, Math.abs(at(t, x, i, c) - at(t, x - 1, i, c)));
				}
				wrap = Math.max(wrap, Math.abs(at(t, 0, i, c) - at(t, -1, i, c)));
				wrap = Math.max(wrap, Math.abs(at(t, i, 0, c) - at(t, i, -1, c)));
			}
		}
		assertTrue("wrap step " + wrap + " inside " + inside, wrap <= inside);
	}

	@Test
	public void slopesAreCenteredAndUseTheRange()
	{
		byte[] t = Water.noise(SIZE, 2);
		double sum = 0, sq = 0;
		int n = SIZE * SIZE;
		for (int i = 0; i < n; ++i)
		{
			double s = ((t[i * 4] & 0xFF) - 127.5) / 127.5;
			sum += s;
			sq += s * s;
		}
		double mean = sum / n, rms = Math.sqrt(sq / n);
		assertTrue("mean " + mean, Math.abs(mean) < 0.03);
		assertTrue("rms " + rms, rms > 0.25 && rms < 0.5);
	}

	@Test
	public void slopeMatchesHeight()
	{
		// the stored slope is the height's derivative: compare its sign with central differences of the height
		byte[] t = Water.noise(SIZE, 3);
		int agree = 0, total = 0;
		for (int y = 0; y < SIZE; ++y)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				int dh = at(t, x + 1, y, 2) - at(t, x - 1, y, 2);
				int s = at(t, x, y, 0) - 128;
				if (Math.abs(dh) > 3 && Math.abs(s) > 8)
				{
					++total;
					agree += Integer.signum(dh) == Integer.signum(s) ? 1 : 0;
				}
			}
		}
		assertTrue(agree + " of " + total, agree > total * 0.95);
	}

	@Test
	public void driftNeverJumps()
	{
		// the wind's strength flips each frame (a roof, gusts) and turns: the waves still only move a frame's worth
		Water w = new Water();
		double[] prev = w.drift();
		for (int f = 0; f < 20000; ++f)
		{
			double a = f * 0.001;
			float[] wind = {(float) Math.cos(a), (float) Math.sin(a), f % 2 == 0 ? 1 : 0};
			w.advance(wind, 1 / 60.0);
			double[] d = w.drift();
			for (int i = 0; i < 2; ++i)
			{
				assertTrue("frame " + f + " component " + i, Math.abs(d[i] - prev[i]) < 0.5);
			}
			prev = d;
		}
		// eased, not flipping
		assertEquals(0.5, prev[2], 0.05);
		// a stall moves it a quarter second's worth at most
		w.advance(new float[]{1, 0, 1}, 30);
		assertTrue(w.drift()[0] - prev[0] < 18 * 0.25 + 1e-6);
	}

	@Test
	public void causticsAreTheMapWithShiftedChannels()
	{
		// the map loads (256 x 256), alpha is the map, red is it shifted diagonally by the aberration
		byte[] t = Water.caustics(256);
		double mean = 0;
		int bright = 0;
		for (int i = 0; i < 256 * 256; ++i)
		{
			int a = t[i * 4 + 3] & 0xFF;
			mean += a;
			bright += a > 150 ? 1 : 0;
		}
		mean /= 256 * 256;
		// thin bright lines over dark cells, not the gray fallback
		assertTrue("mean " + mean, mean > 30 && mean < 80);
		assertTrue("bright " + bright, bright > 256 && bright < 256 * 256 / 10);

		float[] map = new float[SIZE * SIZE];
		map[5 * SIZE + 7] = 1;
		byte[] s = Water.aberrate(map, SIZE, 1);
		// a whole texel: red at (x + 1, y + 1) shows the spot one texel up and left of it
		assertEquals(255, at(s, 6, 4, 0));
		assertEquals(255, at(s, 6, 6, 1));
		assertEquals(255, at(s, 8, 6, 2));
		assertEquals(255, at(s, 7, 5, 3));
	}

	@Test
	public void cellsTileAndAreRoundBumps()
	{
		byte[] t = Water.cells(SIZE, 41);
		int inside = 0, wrap = 0;
		double sum = 0;
		for (int i = 0; i < SIZE; ++i)
		{
			for (int x = 1; x < SIZE; ++x)
			{
				inside = Math.max(inside, Math.abs(at(t, x, i, 2) - at(t, x - 1, i, 2)));
			}
			wrap = Math.max(wrap, Math.abs(at(t, 0, i, 2) - at(t, -1, i, 2)));
			wrap = Math.max(wrap, Math.abs(at(t, i, 0, 2) - at(t, i, -1, 2)));
		}
		for (int i = 0; i < SIZE * SIZE; ++i)
		{
			sum += (t[i * 4] & 0xFF) - 127.5;
		}
		assertTrue("wrap step " + wrap + " inside " + inside, wrap <= inside);
		// smooth: no texel jumps like a Voronoi crease would make
		assertTrue("inside " + inside, inside < 40);
		assertTrue("mean slope " + sum / (SIZE * SIZE), Math.abs(sum / (SIZE * SIZE)) < 4);
	}
}
