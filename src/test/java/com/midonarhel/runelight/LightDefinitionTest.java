package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LightDefinitionTest
{
	private static LightDefinition parse(String json)
	{
		LightDefinition d = new Gson().fromJson(json, LightDefinition.class);
		d.prepare();
		return d;
	}

	@Test
	public void spawnsOnlyInsideItsAreasAndOutsideTheExcludedOnes()
	{
		LightDefinition d = parse("{\"areas\":[[3200,3200,0,3210,3210,1],[3300,3300,-1,3301,3301,9]],"
			+ "\"excludeAreas\":[[3205,3205,0,3206,3206,0]]}");
		assertTrue(d.allowedAt(3200, 3200, 0));
		assertTrue(d.allowedAt(3210, 3210, 1));
		assertTrue(d.allowedAt(3300, 3301, 3));
		assertFalse("past the box", d.allowedAt(3211, 3205, 0));
		assertFalse("on another plane", d.allowedAt(3205, 3204, 2));
		assertFalse("excluded", d.allowedAt(3205, 3205, 0));
		assertTrue("excluded on the ground floor only", d.allowedAt(3205, 3205, 1));
	}

	@Test
	public void noAreasMeansAnywhere()
	{
		LightDefinition d = parse("{}");
		assertTrue(d.allowedAt(1, 2, 3));
		LightDefinition onlyExcluding = parse("{\"excludeAreas\":[[0,0,0,10,10,3]]}");
		assertTrue(onlyExcluding.allowedAt(11, 5, 0));
		assertFalse(onlyExcluding.allowedAt(5, 5, 0));
	}

	@Test
	public void animationFilterFollowsTheParent()
	{
		LightDefinition d = parse("{\"animationIds\":[-1,10,11],\"excludeAnimationIds\":[11]}");
		assertTrue(d.animationSpecific());
		assertTrue(d.matchesAnimation(10, true));
		assertTrue("no animation is listed", d.matchesAnimation(-1, false));
		assertFalse("excluded", d.matchesAnimation(11, true));
		assertFalse("not listed", d.matchesAnimation(12, true));
	}

	@Test
	public void waitForAnimationNeedsOneToPlay()
	{
		LightDefinition d = parse("{\"waitForAnimation\":true}");
		assertTrue(d.animationSpecific());
		assertFalse(d.matchesAnimation(-1, false));
		assertTrue(d.matchesAnimation(5, true));
		LightDefinition plain = parse("{}");
		assertFalse(plain.animationSpecific());
		assertTrue(plain.matchesAnimation(-1, false));
	}

	@Test
	public void customOffsetsTurnWithTheParent()
	{
		LightDefinition d = parse("{\"height\":100,\"offset\":[10,20,30]}");
		float[] at = new float[3];
		d.locate(at, 1000, 500, 2000, 0, 1, 1);
		assertEquals(990, at[0], 1e-3f);
		assertEquals(500 - 100 - 1 - 20, at[1], 1e-3f);
		assertEquals(1970, at[2], 1e-3f);
		// a quarter turn
		d.locate(at, 1000, 500, 2000, 512, 1, 1);
		assertEquals(1000 - 30, at[0], 1e-2f);
		assertEquals(2000 + 10, at[2], 1e-2f);
	}

	@Test
	public void relativeAlignmentFollowsTheParentsFacing()
	{
		LightDefinition d = parse("{\"height\":50,\"alignment\":\"FRONT\"}");
		float[] at = new float[3];
		// a 2 by 2 footprint facing south: the front is a tile south of the middle (z points north)
		d.locate(at, 1000, 500, 2000, 0, 2, 2);
		assertEquals(1000, at[0], 1e-2f);
		assertEquals(500 - 50 - 1, at[1], 1e-3f);
		assertEquals(2000 - 128, at[2], 1e-2f);
		// the parent turned a quarter, to the west: so is its front
		d.locate(at, 1000, 500, 2000, 512, 2, 2);
		assertEquals(1000 - 128, at[0], 1e-2f);
		assertEquals(2000, at[2], 1e-1f);
	}

	@Test
	public void cornerAlignmentReachesTheCorner()
	{
		LightDefinition d = parse("{\"alignment\":\"BACKRIGHT_CORNER\"}");
		float[] at = new float[3];
		d.locate(at, 0, 0, 0, 0, 1, 1);
		// 1792 + 0 is northwest: 117 HD's corner distance
		float reach = (float) Math.sqrt(2 * 128 * 128) / 2;
		assertEquals(-reach * Math.sqrt(0.5), at[0], 1e-2f);
		assertEquals(reach * Math.sqrt(0.5), at[2], 1e-2f);
	}

	@Test
	public void absoluteAlignmentsFaceNorthLike117Hd()
	{
		float[] a = new float[3], b = new float[3];
		parse("{\"alignment\":\"SOUTH\"}").locate(a, 0, 0, 0, 700, 1, 1);
		parse("{\"alignment\":\"WEST\"}").locate(b, 0, 0, 0, 0, 1, 1);
		assertEquals(0, a[0], 1e-3f);
		assertEquals(64, a[2], 1e-3f);
		assertEquals(0, b[0], 1e-3f);
		assertEquals(64, b[2], 1e-3f);
	}

	@Test
	public void lightsJsonLoadsWithEverythingTheImporterKeeps()
	{
		LightDefinition[] defs = new Gson().fromJson(
			new InputStreamReader(LightDefinitionTest.class.getResourceAsStream("/com/midonarhel/runelight/lights.json"),
				StandardCharsets.UTF_8), LightDefinition[].class);
		int areas = 0, animated = 0, outdoor = 0, aligned = 0, delayed = 0;
		for (LightDefinition d : defs)
		{
			d.prepare();
			assertNotNull(d.alignment);
			areas += d.areas != null || d.excludeAreas != null ? 1 : 0;
			animated += d.animationSpecific() ? 1 : 0;
			outdoor += d.outdoorLighting ? 1 : 0;
			aligned += d.alignment != LightDefinition.Alignment.CUSTOM ? 1 : 0;
			delayed += d.spawnDelay > 0 || d.despawnDelay > 0 || d.fixedDespawnTime ? 1 : 0;
		}
		assertEquals(2892, defs.length);
		assertEquals(28, areas);
		assertEquals(93, animated);
		assertEquals(34, outdoor);
		assertEquals(17, aligned);
		assertTrue(delayed > 200);
	}
}
