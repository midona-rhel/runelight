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
		// the step across the wrap is no bigger than steps inside the texture (the height, b)
		int inside = 0, wrap = 0;
		for (int i = 0; i < SIZE; ++i)
		{
			for (int x = 1; x < SIZE; ++x)
			{
				inside = Math.max(inside, Math.abs(at(t, x, i, 2) - at(t, x - 1, i, 2)));
			}
			wrap = Math.max(wrap, Math.abs(at(t, 0, i, 2) - at(t, -1, i, 2)));
			wrap = Math.max(wrap, Math.abs(at(t, i, 0, 2) - at(t, i, -1, 2)));
		}
		assertTrue("wrap step " + wrap + " inside " + inside, inside > 0 && wrap <= inside);
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
}
