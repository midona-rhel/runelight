package com.midonarhel.runelight;

import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SceneLightsBudgetTest
{
	private static PointLight at(float x, float z)
	{
		return new PointLight().set(x, 0, z, 300, 1, 1, 1, 1);
	}

	private static List<PointLight> row(int n, float from)
	{
		List<PointLight> lights = new ArrayList<>();
		for (int i = 0; i < n; ++i)
		{
			lights.add(at(from + i, 0));
		}
		return lights;
	}

	@Test
	public void automaticLightsComeAfterTheOthersAndAllFitWhenThereIsRoom()
	{
		List<PointLight> out = row(10, 0);
		List<PointLight> auto = row(5, 100);
		SceneLights.appendNearest(out, auto, LightClusters.MAX_LIGHTS - out.size(), 0, 0);
		assertEquals(15, out.size());
		for (int i = 0; i < 10; ++i)
		{
			assertEquals(i, out.get(i).x, 0);
		}
		assertSame(auto.get(0), out.get(10));
	}

	@Test
	public void aFullBudgetKeepsEveryLightFromLightsJson()
	{
		List<PointLight> out = row(LightClusters.MAX_LIGHTS, 0);
		SceneLights.appendNearest(out, row(20, 5000), LightClusters.MAX_LIGHTS - out.size(), 0, 0);
		assertEquals(LightClusters.MAX_LIGHTS, out.size());
		out = row(LightClusters.MAX_LIGHTS + 7, 0);
		SceneLights.appendNearest(out, row(20, 5000), LightClusters.MAX_LIGHTS - out.size(), 0, 0);
		assertEquals(LightClusters.MAX_LIGHTS + 7, out.size());
	}

	@Test
	public void theNearestAutomaticLightsTakeWhatIsLeft()
	{
		List<PointLight> out = row(LightClusters.MAX_LIGHTS - 3, 0);
		List<PointLight> auto = new ArrayList<>();
		for (float x : new float[]{900, -50, 4000, 30, -700})
		{
			auto.add(at(x, 0));
		}
		PointLight farthest = auto.get(2);
		SceneLights.appendNearest(out, auto, LightClusters.MAX_LIGHTS - out.size(), 0, 0);
		assertEquals(LightClusters.MAX_LIGHTS, out.size());
		List<PointLight> added = out.subList(LightClusters.MAX_LIGHTS - 3, LightClusters.MAX_LIGHTS);
		assertEquals(30, added.get(0).x, 0);
		assertEquals(-50, added.get(1).x, 0);
		assertEquals(-700, added.get(2).x, 0);
		assertTrue(out.indexOf(farthest) < 0);
	}
}
