package com.midonarhel.runelight;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LightClustersTest
{
	private static final int COLUMNS = LightClusters.CLUSTERS_X * LightClusters.CLUSTERS_Y;
	private static final int STRIDE = LightClusters.MAX_LIGHTS_PER_CLUSTER + 1;
	private static final int COLUMN_BASE = COLUMNS * LightClusters.CLUSTERS_Z * STRIDE;

	// camera looking down north at a slant, like the client's projection
	private static float[] viewProj()
	{
		float[] m = Mat4.projection(1600, 900, 50);
		Mat4.mul(m, Mat4.rotateX((float) (300 * Math.PI / 1024)));
		return m;
	}

	private static List<Integer> fogColumnLights(short[] table)
	{
		List<Integer> all = new ArrayList<>();
		for (int c = 0; c < COLUMNS; ++c)
		{
			int base = COLUMN_BASE + c * STRIDE;
			for (int i = 0; i < table[base]; ++i)
			{
				int light = table[base + 1 + i];
				if (!all.contains(light))
				{
					all.add(light);
				}
			}
		}
		return all;
	}

	@Test
	public void fogColumnsSkipIndoorAndFarLights()
	{
		LightClusters clusters = new LightClusters();
		// in front of the camera (north is +z, y down)
		List<PointLight> lights = new ArrayList<>();
		lights.add(new PointLight().set(0, 800, 3000, 600, 1, 0.5f, 0.2f, 2));
		lights.add(new PointLight().set(200, 800, 3200, 600, 1, 0.5f, 0.2f, 2).indoor());
		lights.add(new PointLight().set(0, 800, 20000, 600, 1, 0.5f, 0.2f, 2));
		clusters.setFog(true, 0, 3000, 4000);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		List<Integer> fog = fogColumnLights(clusters.clusterTable());
		assertEquals(3, clusters.getLightCount());
		assertEquals(1, fog.size());
		assertEquals(0, (int) fog.get(0));

		clusters.setFog(false, 0, 0, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertTrue(fogColumnLights(clusters.clusterTable()).isEmpty());
	}

	@Test
	public void fullColumnsKeepTheBrightestLights()
	{
		LightClusters clusters = new LightClusters();
		List<PointLight> lights = new ArrayList<>();
		int n = LightClusters.MAX_LIGHTS_PER_CLUSTER + 9;
		for (int i = 0; i < n; ++i)
		{
			// dimmest first, all in the same place
			lights.add(new PointLight().set(0, 800, 3000, 300, 1, 1, 1, 1 + i));
		}
		clusters.setFog(true, 0, 3000, 4000);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		short[] table = clusters.clusterTable();
		boolean any = false;
		for (int c = 0; c < COLUMNS; ++c)
		{
			int base = COLUMN_BASE + c * STRIDE;
			if (table[base] == 0)
			{
				continue;
			}
			any = true;
			assertEquals(LightClusters.MAX_LIGHTS_PER_CLUSTER, table[base]);
			for (int i = 0; i < table[base]; ++i)
			{
				// the 9 dimmest are the ones dropped, brightest listed first
				assertEquals(n - 1 - i, table[base + 1 + i]);
			}
		}
		assertTrue(any);
		assertFalse(fogColumnLights(table).contains(0));
	}
}
