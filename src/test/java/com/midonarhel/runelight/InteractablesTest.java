package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;

public class InteractablesTest
{
	@Before
	public void clear()
	{
		Interactables.clear();
	}

	@Test
	public void anyActionMakesItInteractable()
	{
		assertTrue(Interactables.hasAction(new String[]{null, "Chop down", null, null, null}));
		assertFalse(Interactables.hasAction(new String[]{null, null, null, null, null}));
		assertFalse(Interactables.hasAction(new String[]{"", null}));
		assertFalse(Interactables.hasAction(null));
	}

	@Test
	public void unresolvedObjectsAreScenery()
	{
		Interactables.want(7);
		assertFalse(Interactables.is(7));
		assertFalse(Interactables.is(8));
	}

	@Test
	public void resolveWorksOutTheWantedOnesOnce()
	{
		List<Integer> asked = new ArrayList<>();
		Interactables.want(1);
		Interactables.want(2);
		Interactables.want(2);
		Interactables.resolve(id ->
		{
			asked.add(id);
			return id == 2;
		});
		assertEquals(2, asked.size());
		assertFalse(Interactables.is(1));
		assertTrue(Interactables.is(2));

		// known ones are not asked again
		Interactables.want(2);
		Interactables.want(3);
		asked.clear();
		Interactables.resolve(id ->
		{
			asked.add(id);
			return true;
		});
		assertEquals(List.of(3), asked);
		assertTrue(Interactables.is(2));
		assertTrue(Interactables.is(3));
	}

	@Test
	public void snowClassRidesInTheFaceBits()
	{
		int hsl = 0;
		assertEquals(0, SurfaceMaterials.face(0, -1, hsl, 255) >> SurfaceMaterials.SNOW_SHIFT);
		assertEquals(1, SurfaceMaterials.face(SurfaceMaterials.SNOW_REDUCED, -1, hsl, 255) >> SurfaceMaterials.SNOW_SHIFT);
		assertEquals(2, SurfaceMaterials.face(SurfaceMaterials.SNOW_NONE | 3, -1, hsl, 255) >> SurfaceMaterials.SNOW_SHIFT);
		// the top of the texture id short, clear of the terrain flags and the texture id
		int face = SurfaceMaterials.face(SurfaceMaterials.SNOW_NONE, -1, hsl, 255);
		assertTrue(face < 1 << 16);
		assertEquals(0, face & (SurfaceMaterials.TEXTURE_MASK | WaterMap.BED_FLAG | WaterMap.LAND_FLAG));
		assertEquals(WaterMap.LAND_FLAG << 1, 1 << SurfaceMaterials.SNOW_SHIFT);
	}
}
