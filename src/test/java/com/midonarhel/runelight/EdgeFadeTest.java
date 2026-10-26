package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class EdgeFadeTest
{
	private static final int T = EdgeFade.TILE;

	@Test
	public void clientSquareCoversTheCircleWhereverTheCameraIs()
	{
		for (float offset : new float[]{0, 100, 1500, 4000})
		{
			int client = EdgeFade.clientDrawDistance(50, offset);
			float r = EdgeFade.radius(50, client, offset);
			assertEquals(50 * T, r, 1e-3);
			// every point of the circle is inside the square around the camera, a tile short to the north and east
			// and with the margin for partly covered zones, for any direction the camera is in
			for (int a = 0; a < 360; a += 15)
			{
				double ca = Math.cos(Math.toRadians(a)), sa = Math.sin(Math.toRadians(a));
				double camX = offset * ca, camZ = offset * sa;
				for (int b = 0; b < 360; b += 5)
				{
					double px = r * Math.cos(Math.toRadians(b)), pz = r * Math.sin(Math.toRadians(b));
					double half = (client - EdgeFade.CLIENT_MARGIN_TILES + 1) * T;
					assertTrue(Math.abs(px - camX) <= half && Math.abs(pz - camZ) <= half);
				}
			}
		}
		// a client that keeps its own limit shrinks the circle instead
		assertEquals((60 - EdgeFade.CLIENT_MARGIN_TILES) * T - 2000, EdgeFade.radius(184, 60, 2000), 1e-3);
		assertEquals(0, EdgeFade.radius(10, 0, 2000), 0);
	}

	@Test
	public void fadeWidensInFog()
	{
		assertEquals(0, EdgeFade.fogginess(0.12f, 0), 0);
		assertEquals(1, EdgeFade.fogginess(2.2f, 0), 0);
		assertEquals(1, EdgeFade.fogginess(0.12f, 60), 0);
		float some = EdgeFade.fogginess(1.0f, 10);
		assertTrue(some > 0 && some < 1);
		float r = 50 * T;
		assertEquals(12 * T, EdgeFade.targetWidth(r, 0), 1e-3);
		assertEquals(r * EdgeFade.FOGGY_SHARE, EdgeFade.targetWidth(r, 1), 1e-3);
		// never wider than the world
		assertEquals(5 * T, EdgeFade.targetWidth(5 * T, 0), 1e-3);
	}

	/** Same matrices as RuneLightPlugin: scale, projection, pitch, yaw; column-major, camera-relative. */
	private static float[] viewProj(float pitch, float yaw)
	{
		float[] m = Mat4.scale(512, 512, 1);
		Mat4.mul(m, Mat4.projection(800, 600, 50));
		Mat4.mul(m, Mat4.rotateX(pitch));
		Mat4.mul(m, Mat4.rotateY(yaw));
		return m;
	}

	private static float[] clip(float[] m, float x, float y, float z)
	{
		float[] c = new float[4];
		for (int r = 0; r < 4; ++r)
		{
			c[r] = m[r] * x + m[4 + r] * y + m[8 + r] * z + m[12 + r];
		}
		return c;
	}

	@Test
	public void frustumMatchesTheProjection()
	{
		EdgeFade e = new EdgeFade();
		java.util.Random random = new java.util.Random(1);
		for (int i = 0; i < 20; ++i)
		{
			float pitch = 0.2f + random.nextFloat() * 1.0f, yaw = random.nextFloat() * 6.28f;
			float[] m = viewProj(pitch, yaw);
			e.setFrustum(m, 1000, -2000, 3000);
			for (int j = 0; j < 500; ++j)
			{
				float x = (random.nextFloat() - 0.5f) * 12000, y = (random.nextFloat() - 0.5f) * 6000;
				float z = (random.nextFloat() - 0.5f) * 12000;
				float[] c = clip(m, x, y, z);
				boolean visible = c[3] > EdgeFade.NEAR && Math.abs(c[0]) <= c[3] && Math.abs(c[1]) <= c[3];
				// a point is a box with no size
				boolean tested = e.inFrustum(x + 1000, y - 2000, z + 3000, x + 1000, y - 2000, z + 3000);
				// planes and projection agree up to rounding at the very edge
				if (Math.abs(Math.abs(c[0]) - c[3]) > 1 && Math.abs(Math.abs(c[1]) - c[3]) > 1
					&& Math.abs(c[3] - EdgeFade.NEAR) > 1)
				{
					assertEquals(visible, tested);
				}
			}
		}
	}

	@Test
	public void tallObjectsAtTheScreenEdgeKeepTheirZone()
	{
		EdgeFade e = new EdgeFade();
		e.update(0, 0, 400 * T, 3, 0);
		// looking down from 20 tiles up; boxes a zone wide at every tile, so some sit just past the screen's edges
		int found = 0;
		for (float pitch = 0.3f; pitch < 1.4f; pitch += 0.2f)
		{
			e.setFrustum(viewProj(pitch, 0.7f), 0, -20 * T, 0);
			for (int z = -60 * T; z <= 60 * T; z += T)
			{
				for (int x = -60 * T; x <= 60 * T; x += T)
				{
					int p = EdgeFade.PAD_XZ;
					boolean ground = e.inFrustum(x - p, 0, z - p, x + 8 * T + p, 0, z + 8 * T + p);
					boolean canopy = e.inFrustum(x - p, -8 * T, z - p, x + 8 * T + p, -8 * T, z + 8 * T + p);
					if (!ground && canopy)
					{
						// trees reaching into view keep their zone, though the client gives the ground's height only
						assertTrue(e.zoneVisible(x, z, 0, 0));
						++found;
					}
				}
			}
		}
		assertTrue(found > 0);
	}

	@Test
	public void cullingDoesNotDependOnTheCameraYaw()
	{
		EdgeFade e = new EdgeFade();
		e.update(5000, 6000, 30 * T, 3, 0);
		// with a frustum that sees everything, only the circle decides
		float[] all = {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1e9f};
		e.setFrustum(all, 0, 0, 0);
		for (int zx = 0; zx < 20; ++zx)
		{
			for (int zz = 0; zz < 20; ++zz)
			{
				int x = zx * 8 * T, z = zz * 8 * T;
				float dx = Math.max(Math.max(x - EdgeFade.PAD_XZ - 5000, 5000 - (x + 8 * T + EdgeFade.PAD_XZ)), 0);
				float dz = Math.max(Math.max(z - EdgeFade.PAD_XZ - 6000, 6000 - (z + 8 * T + EdgeFade.PAD_XZ)), 0);
				assertEquals(dx * dx + dz * dz <= 30f * T * 30 * T, e.zoneVisible(x, z, -500, 0));
			}
		}
		assertTrue(e.modelVisible(5000 + 30 * T, 6000));
		assertFalse(e.modelVisible(5000 + 30 * T + EdgeFade.MODEL_PAD + 10, 6000));
	}
}
