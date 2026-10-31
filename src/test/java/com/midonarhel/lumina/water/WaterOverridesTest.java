package com.midonarhel.lumina.water;

import com.midonarhel.lumina.*;

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
	public void groundGeometry()
	{
		WaterOverrides o = WaterOverrides.get();
		// CATHERBY_BEACH_LADDER_FIX: overlay 11 on that one tile is lowered 200 (heightOffset -200)
		assertEquals(-200, o.heightOffset(2842, 3424, 0, 11, -1));
		assertEquals(-200, o.heightOffset(2842, 3424, 0, 11, 0));
		// the underlay alone doesn't match an overlay rule, nor does the tile next to it
		assertEquals(0, o.heightOffset(2842, 3424, 0, -1, 0));
		assertEquals(0, o.heightOffset(2843, 3424, 0, 11, 0));
		// VARROCK_GRAND_EXCHANGE_ROOF: overlay 8 there is drawn double-sided
		assertTrue(o.doubleSided(3165, 3490, 1, 8, -1));
		assertFalse(o.doubleSided(3165, 3490, 1, -1, 8));
	}
}
