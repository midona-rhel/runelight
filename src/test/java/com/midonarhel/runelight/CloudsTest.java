package com.midonarhel.runelight;

import static org.junit.Assert.assertTrue;
import java.util.Random;
import org.junit.Test;

public class CloudsTest
{
	// clouds.glsl's CLOUD_HORIZON.x
	private static final double HORIZON = -0.02;

	@Test
	public void skyRowsHoldEveryTexelAboveTheCloudsHorizon()
	{
		Random rng = new Random(7);
		int[] rows = new int[2];
		int skipped = 0;
		for (int k = 0; k < 400; ++k)
		{
			// a target with the game viewport somewhere inside it, at half resolution
			int tw = 200 + rng.nextInt(200), th = 150 + rng.nextInt(150);
			int[] vp = {rng.nextInt(40), rng.nextInt(40), 0, 0};
			vp[2] = tw - vp[0] - rng.nextInt(40);
			vp[3] = th - vp[1] - rng.nextInt(40);
			int hw = (tw + 1) / 2, hh = (th + 1) / 2;

			float[] viewProj = Mat4.scale(400 + rng.nextInt(800), 400 + rng.nextInt(800), 1);
			Mat4.mul(viewProj, Mat4.projection(vp[2], vp[3], 50));
			// the game's pitch, and past it both ways; a little roll, which the bound doesn't rely on
			Mat4.mul(viewProj, rotateZ((rng.nextFloat() - 0.5f) * 0.4f));
			Mat4.mul(viewProj, Mat4.rotateX(-0.4f + rng.nextFloat() * 2.0f));
			Mat4.mul(viewProj, Mat4.rotateY(rng.nextFloat() * 6.3f));
			float[] inv = Mat4.inverse(viewProj);

			Clouds clouds = new Clouds();
			float radius = rng.nextInt(4) == 0 ? 0 : 3000 + rng.nextInt(9000);
			float camX = rng.nextFloat() * 3000 - 1500, camZ = rng.nextFloat() * 3000 - 1500;
			float camY = -rng.nextFloat() * 3000;
			boolean atEdge = rng.nextBoolean();
			clouds.setEye(camX, camY, camZ, 0, 0, 0, radius, atEdge);
			clouds.skyRows(rows, inv, vp, hw, hh, 2);
			skipped += hh - (rows[1] - rows[0]);

			// clouds.glsl's cloudEye
			float[] eye = {-camX, -camZ, radius, atEdge ? -camY : 0};
			for (int j = 0; j < hh; ++j)
			{
				for (int i = 0; i < hw; ++i)
				{
					double[] dir = ray(inv, vp, (i + 0.5) * 2, (j + 0.5) * 2);
					if (-skyDir(dir, eye)[1] > HORIZON)
					{
						assertTrue("view " + k + " texel " + i + ", " + j, j >= rows[0] && j < rows[1]);
					}
				}
			}
		}
		// and the scissor leaves out a good share of the rows
		assertTrue(skipped > 400 * 50);
	}

	private static float[] rotateZ(float rz)
	{
		float s = (float) Math.sin(rz), c = (float) Math.cos(rz);
		return new float[]{c, s, 0, 0, -s, c, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1};
	}

	// frame.glsl's normalize(reconstructPosition(px, 1.0))
	private static double[] ray(float[] m, int[] vp, double px, double py)
	{
		double x = (px - vp[0]) / vp[2] * 2 - 1, y = (py - vp[1]) / vp[3] * 2 - 1;
		double w = m[3] * x + m[7] * y + m[11] + m[15];
		double[] p = new double[3];
		for (int i = 0; i < 3; ++i)
		{
			p[i] = (m[i] * x + m[4 + i] * y + m[8 + i] + m[12 + i]) / w;
		}
		double l = Math.sqrt(p[0] * p[0] + p[1] * p[1] + p[2] * p[2]);
		return new double[]{p[0] / l, p[1] / l, p[2] / l};
	}

	// clouds.glsl's cloudSkyDir
	private static double[] skyDir(double[] dir, float[] eye)
	{
		double l = Math.hypot(dir[0], dir[2]);
		if (eye[3] <= 0 || eye[2] <= 0 || l < 1e-5)
		{
			return dir;
		}
		double hx = dir[0] / l, hz = dir[2] / l;
		double oc = hx * eye[0] + hz * eye[1];
		double far = Math.max(oc + Math.sqrt(Math.max(eye[2] * eye[2] - eye[0] * eye[0] - eye[1] * eye[1] + oc * oc, 0)),
			1);
		double below = eye[3] / Math.sqrt(eye[3] * eye[3] + far * far);
		double s = (-dir[1] + below) / (1 + below);
		double c = Math.sqrt(Math.max(1 - s * s, 0));
		return new double[]{hx * c, -s, hz * c};
	}
}
