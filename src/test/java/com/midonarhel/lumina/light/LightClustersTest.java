package com.midonarhel.lumina.light;

import com.midonarhel.lumina.common.Mat4;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LightClustersTest
{
	private static final int COLUMNS = LightClusters.CLUSTERS_X * LightClusters.CLUSTERS_Y;
	private static final int COLUMN_BASE = COLUMNS * LightClusters.CLUSTERS_Z;

	// camera looking down north at a slant, like the client's projection
	private static float[] viewProj()
	{
		float[] m = Mat4.projection(1600, 900, 50);
		Mat4.mul(m, Mat4.rotateX((float) (300 * Math.PI / 1024)));
		return m;
	}

	// the lights in an entry's mask, in index order, as the shaders walk them
	private static List<Integer> entryLights(LightClusters clusters, int entry)
	{
		int words = clusters.clusterTexels() * 4;
		int[] table = clusters.clusterTable();
		List<Integer> lights = new ArrayList<>();
		for (int i = 0; i < words * 32; ++i)
		{
			if ((table[entry * words + (i >> 5)] >>> (i & 31) & 1) != 0)
			{
				lights.add(i);
			}
		}
		return lights;
	}

	// how many fog columns hold a stretch of distance that lamps glow in (LightClusters: the first two words of a column's entry)
	private static int fogSpans(LightClusters clusters)
	{
		int[] table = clusters.clusterTable();
		int words = clusters.clusterTexels() * 4, spans = 0;
		for (int c = 0; c < COLUMNS; ++c)
		{
			if (Float.intBitsToFloat(table[(COLUMN_BASE + c) * words + 1]) > 0)
			{
				++spans;
			}
		}
		return spans;
	}

	@Test
	public void fogColumnsTakeIndoorLightsAndSkipFarOnes()
	{
		LightClusters clusters = new LightClusters();
		// in front of the camera (north is +z, y down)
		List<PointLight> lights = new ArrayList<>();
		lights.add(new PointLight().set(0, 800, 3000, 600, 1, 0.5f, 0.2f, 2));
		// a lamp in a room glows in the fog too: walls alone stop its light, in the shader (light/lamp.glsl)
		lights.add(new PointLight().set(200, 800, 3200, 600, 1, 0.5f, 0.2f, 2).onPlane(0));
		lights.add(new PointLight().set(0, 800, 20000, 600, 1, 0.5f, 0.2f, 2));
		clusters.setFog(true, 0, 3000, 4000, -1e9f, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertEquals(3, clusters.getLightCount());
		assertTrue(fogSpans(clusters) > 0);
	}

	@Test
	public void fogColumnsSkipLightsAboveTheLayerAndTooDim()
	{
		LightClusters clusters = new LightClusters();
		List<PointLight> lights = new ArrayList<>();
		lights.add(new PointLight().set(0, 800, 3000, 600, 1, 0.5f, 0.2f, 2));
		// its sphere ends above the layer's top at y 0 (y down)
		lights.add(new PointLight().set(200, -700, 3200, 600, 1, 0.5f, 0.2f, 2));
		// brightest channel times radius 0.1 * 2 * 600 = 120, under the 500 asked for
		lights.add(new PointLight().set(-200, 800, 3200, 600, 0.1f, 0.1f, 0.1f, 2));
		clusters.setFog(true, 0, 3000, 4000, 0, 500);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertEquals(3, clusters.getLightCount());
		assertTrue(fogSpans(clusters) > 0);
		assertTrue(clusters.hasFogLights());

		clusters.setFog(true, 0, 3000, 4000, 2000, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertEquals(0, fogSpans(clusters));
		assertFalse(clusters.hasFogLights());
	}

	@Test
	public void lightsPastTheFirstTexelGetMoreTexels()
	{
		LightClusters clusters = new LightClusters();
		List<PointLight> lights = new ArrayList<>();
		for (int i = 0; i < LightClusters.LIGHTS_PER_TEXEL + 2; ++i)
		{
			// a grid of small lights over the ground in view
			lights.add(new PointLight().set(-2600 + (i % 13) * 400, 800, 3000 + (i / 13) * 400, 50, 1, 1, 1, 1));
		}
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertEquals(lights.size(), clusters.getLightCount());
		assertEquals(2, clusters.clusterTexels());
		boolean[] found = new boolean[lights.size()];
		for (int e = 0; e < COLUMN_BASE; ++e)
		{
			for (int light : entryLights(clusters, e))
			{
				found[light] = true;
			}
		}
		for (boolean f : found)
		{
			assertTrue(f);
		}
	}

	@Test
	public void unchangedFramesKeepTheTableAndMovesRebuildIt()
	{
		LightClusters clusters = new LightClusters();
		List<PointLight> lights = new ArrayList<>();
		PointLight moving = new PointLight().set(0, 800, 3000, 300, 1, 1, 1, 1);
		lights.add(moving);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		int[] first = clusters.clusterTable().clone();
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertArrayEquals(first, clusters.clusterTable());

		// what a fresh cull of the moved light gives
		moving.set(-2000, 800, 6000, 300, 1, 1, 1, 1);
		LightClusters fresh = new LightClusters();
		fresh.cull(lights, viewProj(), 0, 0, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertArrayEquals(fresh.clusterTable(), clusters.clusterTable());
		assertFalse(java.util.Arrays.equals(first, clusters.clusterTable()));

		// and with the light gone
		lights.clear();
		clusters.cull(lights, viewProj(), 0, 0, 0);
		for (int word : clusters.clusterTable())
		{
			assertEquals(0, word);
		}
	}
}
