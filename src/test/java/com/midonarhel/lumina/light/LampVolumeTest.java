package com.midonarhel.lumina.light;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;

/** The flood volume's claim (math.md 5): nothing a read takes comes from outside a closed mesh the lamp is in, at any angle. */
public class LampVolumeTest
{
	private static final int[][] FACES = {{0, 1, 3, 2}, {4, 6, 7, 5}, {0, 4, 5, 1}, {2, 3, 7, 6}, {0, 2, 6, 4}, {1, 5, 7, 3}};

	@Test
	public void noReadCrossesAClosedThinBoxAtAnyAngle()
	{
		Random random = new Random(7);
		for (int run = 0; run < 12; ++run)
		{
			double a = random.nextDouble() * Math.PI, b = random.nextDouble() * Math.PI, c = random.nextDouble() * Math.PI;
			double[][] m = rotation(a, b, c);
			float cx = 512 + random.nextInt(32), cy = -600 + random.nextInt(32), cz = 512 + random.nextInt(32);
			float half = 180;
			float[][] corners = new float[8][3];
			for (int i = 0; i < 8; ++i)
			{
				double x = (i & 1) != 0 ? half : -half, y = (i & 2) != 0 ? half : -half, z = (i & 4) != 0 ? half : -half;
				corners[i][0] = cx + (float) (m[0][0] * x + m[0][1] * y + m[0][2] * z);
				corners[i][1] = cy + (float) (m[1][0] * x + m[1][1] * y + m[1][2] * z);
				corners[i][2] = cz + (float) (m[2][0] * x + m[2][1] * y + m[2][2] * z);
			}
			LampVolume v = new LampVolume();
			List<ZoneSnapshot> zones = new ArrayList<>();
			zones.add(zone(corners));
			v.offline(zones, LuminaPlugin.SCENE_OFFSET >> 3, 0, 0, 3, Collections.emptySet(),
				new float[][]{{cx + 7, cy - 11, cz + 5, 700, cx + 7, cz + 5}}, cx, cz, cy + 1000);
			int inside = 0, outside = 0, lit = 0;
			for (float x = cx - 500; x <= cx + 500; x += 11)
			{
				for (float y = cy - 500; y <= cy + 500; y += 13)
				{
					for (float z = cz - 500; z <= cz + 500; z += 17)
					{
						// in the box's frame
						double px = x - cx, py = y - cy, pz = z - cz;
						double bx = m[0][0] * px + m[1][0] * py + m[2][0] * pz, by = m[0][1] * px + m[1][1] * py + m[2][1] * pz, bz = m[0][2] * px + m[1][2] * py + m[2][2] * pz;
						double e = Math.max(Math.abs(bx), Math.max(Math.abs(by), Math.abs(bz)));
						float read = v.offlineRead(0, x, y, z), fine = v.offlineReadFine(0, x, y, z);
						if (e > half + LampVolume.CELL)
						{
							++outside;
							assertTrue("run " + run + " light outside at " + x + " " + y + " " + z + ": " + read + " " + v.line(cx + 7, cy - 11, cz + 5, x, y, z), read <= 0);
							assertTrue("run " + run + " fine light outside at " + x + " " + y + " " + z + ": " + fine, fine <= 0);
						}
						else if (e < half - 64)
						{
							inside += read > -2 ? 1 : 0;
							lit += read > 0 ? 1 : 0;
						}
					}
				}
			}
			assertTrue(outside > 1000);
			assertTrue("run " + run + ": " + lit + " of " + inside + " lit inside", lit > inside * 0.95);
		}
	}

	private static double[][] rotation(double a, double b, double c)
	{
		double ca = Math.cos(a), sa = Math.sin(a), cb = Math.cos(b), sb = Math.sin(b), cc = Math.cos(c), sc = Math.sin(c);
		return new double[][]{
			{ca * cb, ca * sb * sc - sa * cc, ca * sb * cc + sa * sc},
			{sa * cb, sa * sb * sc + ca * cc, sa * sb * cc - ca * sc},
			{-sb, cb * sc, cb * cc}};
	}

	private static ZoneSnapshot zone(float[][] c)
	{
		int[] v = new int[6 * 2 * 18];
		int i = 0;
		for (int[] f : FACES)
		{
			for (int t : new int[]{f[0], f[1], f[2], f[0], f[2], f[3]})
			{
				int x = Math.round(c[t][0]), y = Math.round(c[t][1]), z = Math.round(c[t][2]);
				v[i] = x & 0xffff | y << 16;
				v[i + 1] = z & 0xffff;
				i += 6;
			}
		}
		ZoneSnapshot z = new ZoneSnapshot();
		z.x = LuminaPlugin.SCENE_OFFSET >> 3;
		z.z = z.x;
		z.vertsO = v;
		z.levelOffsets = new int[]{v.length, v.length, v.length, v.length};
		z.rids = new int[4][0];
		z.roofStart = new int[4][0];
		z.roofEnd = new int[4][0];
		return z;
	}

	@Test
	public void halfFloatsRoundTrip()
	{
		for (float f : new float[]{0, 1, 0.5f, 1234.5f, -77.25f, 1e-4f})
		{
			assertEquals(f, half(LampVolume.halfForTest(f)), Math.abs(f) * 1e-3f + 1e-7f);
		}
	}

	private static float half(short h)
	{
		int s = h >> 15 & 1, e = h >> 10 & 31, m = h & 1023;
		float v = e == 0 ? m * (float) Math.pow(2, -24) : (float) ((1 + m / 1024.0) * Math.pow(2, e - 15));
		return s != 0 ? -v : v;
	}
}
