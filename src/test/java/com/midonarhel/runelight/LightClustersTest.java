package com.midonarhel.runelight;

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

	private static List<Integer> fogColumnLights(LightClusters clusters)
	{
		List<Integer> all = new ArrayList<>();
		for (int c = 0; c < COLUMNS; ++c)
		{
			for (int light : entryLights(clusters, COLUMN_BASE + c))
			{
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
		// in a room: ground floor tiles x 4..7, y 27..30 (extended) are under a roof
		PointLight indoor = new PointLight().set(200, 800, 3200, 600, 1, 0.5f, 0.2f, 2).onPlane(0);
		lights.add(indoor);
		lights.add(new PointLight().set(0, 800, 20000, 600, 1, 0.5f, 0.2f, 2));
		byte[][][] settings = new byte[4][40][40];
		for (int x = 4; x < 8; ++x)
		{
			for (int y = 27; y < 31; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		clusters.setRooms(RoomMap.build(BuildingDetector.detect(settings, null), settings, new int[4][41][41], 4));
		clusters.setFog(true, 0, 3000, 4000, -1e9f, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		List<Integer> fog = fogColumnLights(clusters);
		assertEquals(3, clusters.getLightCount());
		assertEquals(1, fog.size());
		assertEquals(0, (int) fog.get(0));

		clusters.setFog(false, 0, 0, 0, 0, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertTrue(fogColumnLights(clusters).isEmpty());
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
		List<Integer> fog = fogColumnLights(clusters);
		assertEquals(3, clusters.getLightCount());
		assertEquals(1, fog.size());
		assertEquals(0, (int) fog.get(0));
		assertTrue(clusters.hasFogLights());

		clusters.setFog(true, 0, 3000, 4000, 2000, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		assertTrue(fogColumnLights(clusters).isEmpty());
		assertFalse(clusters.hasFogLights());
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
		clusters.setFog(true, 0, 3000, 4000, -1e9f, 0);
		clusters.cull(lights, viewProj(), 0, 0, 0);
		boolean any = false;
		for (int c = 0; c < COLUMNS; ++c)
		{
			List<Integer> column = entryLights(clusters, COLUMN_BASE + c);
			if (column.isEmpty())
			{
				continue;
			}
			any = true;
			assertEquals(LightClusters.MAX_LIGHTS_PER_CLUSTER, column.size());
			// the 9 dimmest are the ones dropped
			for (int i = 0; i < column.size(); ++i)
			{
				assertEquals(9 + i, (int) column.get(i));
			}
		}
		assertTrue(any);
		assertFalse(fogColumnLights(clusters).contains(0));
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
