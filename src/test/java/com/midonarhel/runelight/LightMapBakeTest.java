package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LightMapBakeTest
{
	private static LightMapBake.Result bake(BakeScene s, LightMapBake.Light l)
	{
		return LightMapBake.bake(l, s.blockers(), new LightMapBake.Scratch());
	}

	@Test
	public void openFloorFallsOffWithDistance()
	{
		BakeScene s = new BakeScene();
		LightMapBake.Result r = bake(s, BakeScene.light(0, 24.5f, 24.5f, 40, 8));
		float near = BakeScene.atTile(r, 0, 25.5f, 24.5f), far = BakeScene.atTile(r, 0, 29.5f, 24.5f);
		assertTrue(near > far && far > 0);
		assertEquals(0, BakeScene.atTile(r, 0, 34.5f, 24.5f), 0);
		assertTrue(r.cellVisits > 0);
	}

	@Test
	public void wallBlocksAndTheBlurStaysOnItsSide()
	{
		// a wall between x 19 and 20 from y 14 to 26: its ends are further from the light than the light reaches round it
		BakeScene s = new BakeScene().verticalWall(0, 20, 14, 26);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 17.5f, 20.5f, 40, 8));
		assertTrue(BakeScene.atTile(r, 0, 19.7f, 20.5f) > 0.05f);
		for (float x = 20.0f; x < 25; x += 0.25f)
		{
			assertEquals("x " + x, 0, BakeScene.atTile(r, 0, x + 0.1f, 20.5f), 0);
		}
	}

	@Test
	public void doorwayPassesASoftFan()
	{
		BakeScene s = new BakeScene().verticalWall(0, 20, 12, 28, 20);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 16.5f, 20.5f, 40, 12));
		float axis = BakeScene.atTile(r, 0, 24.5f, 20.5f);
		float aside = BakeScene.atTile(r, 0, 24.5f, 23.5f);
		float far = BakeScene.atTile(r, 0, 24.5f, 26.5f);
		assertTrue("axis " + axis, axis > 0.05f);
		assertTrue("axis " + axis + " aside " + aside, aside < axis * 0.7f && aside > 0);
		assertTrue("aside " + aside + " far " + far, far < aside);
		// behind the wall beside the door the light has only come through it, nothing like the light before the wall
		float wallAside = BakeScene.atTile(r, 0, 20.6f, 24.5f);
		assertTrue("wallAside " + wallAside, wallAside < BakeScene.atTile(r, 0, 20.6f, 20.5f) * 0.5f);
	}

	@Test
	public void furnaceWithDoorsMakesACone()
	{
		// a 3 x 2 tile solid, the light just in front of it, and a thin door leaf either side, 0.75 tile long
		BakeScene s = new BakeScene().solid(0, 20, 20, 22, 21);
		s.modelCells(0, 83, 77, 83, 79).modelCells(0, 88, 77, 88, 79);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 21.5f, 19.75f, 40, 9));
		float axis = BakeScene.atTile(r, 0, 21.5f, 14.5f);
		float side = BakeScene.atTile(r, 0, 26.5f, 14.5f);
		float wide = BakeScene.atTile(r, 0, 31.5f, 14.5f);
		assertTrue("axis " + axis, axis > 0.02f);
		assertTrue("axis " + axis + " side " + side, side < axis * 0.8f);
		assertTrue("side " + side + " wide " + wide, wide < side);
		// the door leaf shades what is behind it, softly
		float behindDoor = BakeScene.at(r, 0, 80, 78);
		float openAtSameRange = BakeScene.at(r, 0, 86, 75);
		assertTrue("behind " + behindDoor + " open " + openAtSameRange, behindDoor < openAtSameRange);
		// the furnace itself is dark, and behind it only the little that wraps round
		assertEquals(0, BakeScene.atTile(r, 0, 21.5f, 21.0f), 0);
		assertTrue(BakeScene.atTile(r, 0, 21.5f, 22.5f) < BakeScene.atTile(r, 0, 21.5f, 17.0f) * 0.5f);
	}

	@Test
	public void lightInsideItsOwnFootprintIsNotTrapped()
	{
		BakeScene s = new BakeScene().solid(0, 24, 24, 24, 24);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 24.5f, 24.5f, 60, 8));
		float east = BakeScene.atTile(r, 0, 27.5f, 24.5f), west = BakeScene.atTile(r, 0, 21.5f, 24.5f);
		float north = BakeScene.atTile(r, 0, 24.5f, 27.5f), south = BakeScene.atTile(r, 0, 24.5f, 21.5f);
		assertTrue("east " + east, east > 0.03f);
		assertTrue("west " + west, west > 0.03f);
		assertTrue("north " + north, north > 0.03f);
		assertTrue("south " + south, south > 0.03f);
		assertTrue(Math.abs(east - west) < 0.5f * Math.max(east, west));
	}

	@Test
	public void lightOnAWallBracketLightsItsRoom()
	{
		// a torch whose position is on the wall's own edge, in the cell of a solid wall tile
		BakeScene s = new BakeScene().solid(0, 20, 10, 20, 30);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 20.5f, 20.5f, 150, 8));
		assertTrue(BakeScene.atTile(r, 0, 18.5f, 20.5f) > 0.03f || BakeScene.atTile(r, 0, 22.5f, 20.5f) > 0.03f);
	}

	@Test
	public void roomWithADoorLightsOutsideOnlyThroughIt()
	{
		BakeScene s = new BakeScene();
		s.verticalWall(0, 20, 14, 26, 20);
		for (int x = 12; x <= 20; ++x)
		{
			s.southWall(0, x, 14);
			s.southWall(0, x, 27);
		}
		LightMapBake.Result r = bake(s, BakeScene.light(0, 16.5f, 20.5f, 40, 14));
		assertTrue(BakeScene.atTile(r, 0, 22.5f, 20.5f) > BakeScene.atTile(r, 0, 22.5f, 24.5f));
		assertTrue(BakeScene.atTile(r, 0, 16.5f, 12.5f) < 0.001f);
	}

	@Test
	public void lightFromAnUpstairsRoomLightsNothingBelow()
	{
		BakeScene s = new BakeScene().floor(1, 10, 10, 30, 30, true);
		LightMapBake.Result r = bake(s, BakeScene.light(1, 20.5f, 20.5f, 40, 10));
		assertTrue(BakeScene.reaches(r, 1));
		assertFalse(BakeScene.reaches(r, 0));
	}

	@Test
	public void balconyLampPoolsLightOnTheGroundBeyondTheEdge()
	{
		// the balcony's floor ends at tile 20; the lamp hangs at its edge
		BakeScene s = new BakeScene().floor(1, 10, 10, 20, 30, true);
		LightMapBake.Result r = bake(s, BakeScene.light(1, 20.9f, 20.5f, 40, 10));
		assertTrue(BakeScene.reaches(r, 0));
		assertTrue(BakeScene.atTile(r, 0, 25.5f, 20.5f) > 0.01f);
		assertEquals("under the balcony", 0, BakeScene.atTile(r, 0, 15.5f, 20.5f), 0);
	}

	@Test
	public void stairwellPassesLightUpAndNowhereElse()
	{
		BakeScene s = new BakeScene().floor(1, 10, 10, 40, 40, true).floor(1, 25, 20, 25, 20, false);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 25.5f, 20.5f, 40, 10));
		assertTrue(BakeScene.atTile(r, 0, 28.5f, 20.5f) > 0);
		assertTrue("above the hole", BakeScene.atTile(r, 1, 25.5f, 20.5f) > 0);
		assertEquals("upstairs, away from the hole", 0, BakeScene.atTile(r, 1, 29.5f, 20.5f), 0);
	}

	@Test
	public void roofStopsLightGoingUp()
	{
		BakeScene s = new BakeScene().floor(1, 10, 10, 40, 40, true);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 25.5f, 20.5f, 40, 10));
		assertTrue(BakeScene.reaches(r, 0));
		assertFalse(BakeScene.reaches(r, 1));
	}

	@Test
	public void wallsOfTheStoreyInTheWayStopLightToo()
	{
		// lamp on the balcony shines down past the edge; a wall on the ground between the lamp and a spot hides it
		BakeScene s = new BakeScene().floor(1, 10, 10, 20, 30, true).verticalWall(0, 23, 10, 30);
		LightMapBake.Result r = bake(s, BakeScene.light(1, 20.9f, 20.5f, 40, 10));
		assertTrue(BakeScene.atTile(r, 0, 22.5f, 20.5f) > 0);
		assertEquals(0, BakeScene.atTile(r, 0, 25.5f, 20.5f), 0);
	}
}
