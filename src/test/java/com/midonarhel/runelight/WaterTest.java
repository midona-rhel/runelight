package com.midonarhel.runelight;

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
}
