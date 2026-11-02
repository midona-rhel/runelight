package com.midonarhel.lumina.weather;

import com.google.gson.Gson;
import java.util.List;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * 117 HD's tiles that stay clear in its winter keep clear of the snow (snow_tiles.json): Lumbridge's castle floor and
 * its bridge do; the grass and dirt around them, which 117 HD's winter turns white, don't.
 */
public class SnowTilesTest
{
	// 117 HD's colour for the grass and dirt rules: a plain green
	private static final int GREEN = 9 << 10 | 4 << 7 | 40;

	@Test
	public void floorsAndBridgesStayClear()
	{
		SnowTiles t = SnowTiles.get(new Gson());
		// LUMBRIDGE_CASTLE_FLOORS: overlay 10 in the castle, its upper floor too
		assertTrue(t.clear(3210, 3215, 0, 10, 0, GREEN));
		assertTrue(t.clear(3205, 3220, 1, 10, 0, GREEN));
		// LUM_BRIDGE_10: the bridge over the River Lum
		assertTrue(t.clear(3245, 3225, 0, 10, 0, GREEN));
	}

	@Test
	public void grassAndDirtTakeSnow()
	{
		SnowTiles t = SnowTiles.get(new Gson());
		// OVERWORLD - GRASS_UNDERLAY (underlay 7) and OVERWORLD - DIRT (underlay 19) around Lumbridge
		takes(t, 3230, 3240, 0, 0, 7);
		takes(t, 3180, 3250, 0, 0, 19);
		// the same grass with Lumbridge's path overlay on it is a path (LUMBRIDGE_PATHS): clear
		assertTrue(t.clear(3230, 3240, 0, 10, 7, GREEN));
	}

	private static void takes(SnowTiles t, int x, int y, int z, int overlay, int underlay)
	{
		StringBuilder trace = new StringBuilder();
		boolean clear = t.clear(x, y, z, overlay, underlay, GREEN, trace);
		assertFalse(x + "," + y + " " + overlay + "/" + underlay + ": " + trace, clear);
	}

	@Test
	public void conditions()
	{
		SnowTiles.Condition c = SnowTiles.Condition.any(List.of("h == 8 && s >= 3 && l <= 71", "s == 0"));
		assertTrue(c.test(new int[]{8, 4, 50}, false));
		assertFalse(c.test(new int[]{8, 2, 50}, false));
		assertTrue(c.test(new int[]{3, 0, 99}, false));
		SnowTiles.Condition b = SnowTiles.Condition.any(List.of("blendingColors && (s == 0 || h <= 10 && s < 2)"));
		assertTrue(b.test(new int[]{12, 0, 0}, false));
		assertFalse(b.test(new int[]{12, 1, 0}, false));
		SnowTiles.Condition w = SnowTiles.Condition.any(List.of("winter"));
		assertTrue(w.winter() && w.test(new int[3], true) && !w.test(new int[3], false));
		assertFalse(SnowTiles.Condition.any(List.of("!textures")).test(new int[3], true));
		assertTrue(SnowTiles.Condition.any(List.of("s != 2")).test(new int[]{0, 1, 0}, false));
	}
}
