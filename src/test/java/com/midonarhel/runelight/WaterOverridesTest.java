package com.midonarhel.runelight;

import com.google.gson.Gson;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.Test;

public class WaterOverridesTest
{
	@BeforeClass
	public static void load()
	{
		WaterOverrides.load(new Gson());
	}

	@Test
	public void tileTypes()
	{
		WaterOverrides o = WaterOverrides.get();
		// 117 HD's catch-alls: these overlays are plain water anywhere, those swamp water
		assertEquals(WaterMap.FRESH, o.tileType(3240, 3230, 0, 6, 0));
		assertEquals(WaterMap.SWAMP, o.tileType(3240, 3230, 0, 7, 0));
		// no override: the tile keeps its texture's type
		assertEquals(WaterMap.NONE, o.tileType(3240, 3230, 0, 30000, 30000));
	}

	@Test
	public void ripples()
	{
		WaterOverrides o = WaterOverrides.get();
		// Floating Rocks - Remove Ripples: hue 42 faces, untextured
		assertTrue(o.hasRipples(6707));
		assertTrue(o.hidesFace(6707, 3240, 3230, 0, false, 0, 42 << 10 | 2 << 7 | 60));
		assertFalse(o.hidesFace(6707, 3240, 3230, 0, true, 0, 42 << 10 | 2 << 7 | 60));
		assertFalse(o.hidesFace(6707, 3240, 3230, 0, false, 0, 10 << 10 | 2 << 7 | 60));
		assertFalse(o.hasRipples(1));
	}

	@Test
	public void lakes()
	{
		WaterOverrides o = WaterOverrides.get();
		assertTrue(o.lake(2780, 3550));
		assertFalse(o.lake(2780, 3600));
		assertFalse(o.lake(3240, 3230));
	}
}
