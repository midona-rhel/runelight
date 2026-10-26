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
		// a 3 x 2 tile solid with its body (a model all the way up), the light just in front of it, and a thin door leaf
		// either side, 0.75 tile long
		BakeScene s = new BakeScene().solid(0, 20, 20, 22, 21);
		s.modelCells(0, 80, 80, 91, 87);
		for (int z = 77; z <= 79; ++z)
		{
			s.modelBox(0, 83, z, 12, 0, 16, 32, 0, 200).modelBox(0, 88, z, 12, 0, 16, 32, 0, 200);
		}
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
		// the furnace itself is dark inside, and behind it only the little that wraps round
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
		// lamp on the balcony shines down past the edge; a wall on the ground between the lamp and a spot hides it (a lamp
		// higher up, or a spot higher, would see over the wall's top: the ray is above the storey's height there)
		BakeScene s = new BakeScene().floor(1, 10, 10, 20, 30, true).verticalWall(0, 23, 10, 30);
		LightMapBake.Result r = bake(s, BakeScene.light(1, 20.9f, 20.5f, 10, 10));
		assertTrue(BakeScene.atTile(r, 0, 22.5f, 20.5f) > 0);
		assertEquals(0, BakeScene.atTile(r, 0, 25.5f, 20.5f), 0);
	}

	/** The share along a row of cells (z fixed), from gx0 to gx1. */
	private static float[] row(LightMapBake.Result r, int plane, int gx0, int gx1, int gz)
	{
		float[] out = new float[gx1 - gx0 + 1];
		for (int x = gx0; x <= gx1; ++x)
		{
			out[x - gx0] = BakeScene.at(r, plane, x, gz);
		}
		return out;
	}

	/** The biggest step between neighbours of a row, as a share of the biggest value in it. */
	private static float biggestStep(float[] row)
	{
		float max = 0, step = 0;
		for (int i = 0; i < row.length; ++i)
		{
			max = Math.max(max, row[i]);
			if (i > 0)
			{
				step = Math.max(step, Math.abs(row[i] - row[i - 1]));
			}
		}
		return step / max;
	}

	@Test
	public void aStraightEdgeOfShadowIsAGradientNotSteps()
	{
		// a wall on the line x = 24 from y 24 to 34, a light 7.5 tiles west of it, 1.5 south of its end: the edge of the
		// shadow runs slantwise over the floor east of the wall, where cells could show as steps
		BakeScene s = new BakeScene().verticalWall(0, 24, 24, 34);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 16.5f, 22.5f, 40, 20));
		// a column across the edge, 6 tiles east of the wall
		float[] col = new float[37];
		for (int i = 0; i < col.length; ++i)
		{
			col[i] = BakeScene.at(r, 0, 30 * 4, 22 * 4 + i);
		}
		float top = col[0], bottom = col[col.length - 1];
		assertTrue("lit " + top + " shadow " + bottom, top > bottom * 10);
		assertTrue("no step of more than a sixth of it: " + biggestStep(col), biggestStep(col) < 1 / 6f);
		int between = 0;
		for (float v : col)
		{
			between += v > 0.1f * top && v < 0.7f * top ? 1 : 0;
		}
		assertTrue("cells in the edge: " + between, between >= 6);
		// along a row, from the lit side past the edge
		float[] row = row(r, 0, 25 * 4, 31 * 4, 25 * 4 + 1);
		assertTrue("row step " + biggestStep(row), biggestStep(row) < 1 / 6f);
	}

	@Test
	public void aBarrelBesideATorchShadesNothingAboveItButAFireBehindIt()
	{
		// a barrel (a box 22 units across, up to 70 high) two tiles east of the light
		BakeScene s = new BakeScene();
		s.modelBox(0, 4 * 24 + 2, 4 * 24, 5, 5, 27, 27, 0, 70);
		LightMapBake.Result torch = bake(s, BakeScene.light(0, 22.5f, 24.5f, 150, 10));
		LightMapBake.Result bare = bake(new BakeScene(), BakeScene.light(0, 22.5f, 24.5f, 150, 10));
		for (float x = 25.5f; x < 30; x += 0.5f)
		{
			assertEquals("behind the barrel, x " + x, BakeScene.atTile(bare, 0, x, 24.5f), BakeScene.atTile(torch, 0, x, 24.5f), 1e-6f);
		}
		LightMapBake.Result fire = bake(s, BakeScene.light(0, 22.5f, 24.5f, 40, 10));
		LightMapBake.Result fireBare = bake(new BakeScene(), BakeScene.light(0, 22.5f, 24.5f, 40, 10));
		// right behind it: the light is wide, and only a barrel wide of it is gone
		float shaded = BakeScene.at(fire, 0, 0, 101, 98), open = BakeScene.at(fireBare, 0, 0, 101, 98);
		assertTrue("shaded " + shaded + " open " + open, shaded < open * 0.95f && shaded > open * 0.5f);
		// and above the barrel's top the same fire is not shaded at all
		assertEquals(BakeScene.at(fireBare, 0, 3, 101, 98), BakeScene.at(fire, 0, 3, 101, 98), 1e-3f);
	}

	@Test
	public void aPillarShadeIsSoftAndItsSizeIsItsOwn()
	{
		// a pillar 12 units across stands in the middle of a cell; a cell-sized block would be 32
		BakeScene s = new BakeScene();
		s.modelBox(0, 4 * 24 + 2, 4 * 24, 10, 10, 22, 22, 0, 200);
		LightMapBake.Result r = bake(s, BakeScene.light(0, 22.5f, 24.5f, 100, 10));
		float[] row = row(r, 0, 4 * 24 + 3, 4 * 31, 4 * 24);
		float[] column = new float[16];
		for (int i = 0; i < column.length; ++i)
		{
			column[i] = BakeScene.at(r, 0, 4 * 27, 4 * 24 - 8 + i);
		}
		// the shadow is shallow and wide on the other side of it, and no cell is much darker than its neighbours
		float low = Float.MAX_VALUE, high = 0;
		for (float v : column)
		{
			low = Math.min(low, v);
			high = Math.max(high, v);
		}
		assertTrue("dip " + low + " of " + high, low < high * 0.95f && low > high * 0.4f);
		assertTrue("step " + biggestStep(column), biggestStep(column) < 0.15f);
		assertTrue(row.length > 0);
	}

	@Test
	public void aLightFadesToExactlyNothingAtItsRadius()
	{
		// high up, on open floor: along a row and along a diagonal out past the radius, no step and nothing left over
		LightMapBake.Result r = bake(new BakeScene(), BakeScene.light(0, 24.5f, 24.5f, 150, 6));
		for (int line = 0; line < 2; ++line)
		{
			float[] v = new float[40];
			for (int i = 0; i < v.length; ++i)
			{
				v[i] = line == 0 ? BakeScene.at(r, 0, 98 + i, 98) : BakeScene.at(r, 0, 98 + i * 3 / 4, 98 + i * 3 / 4);
			}
			assertTrue("line " + line + " step " + biggestStep(v), biggestStep(v) < 0.15f);
			// the radius is 24 cells from the light: past it, nothing
			assertEquals(0, v[v.length - 1], 0);
			assertTrue("just inside the radius it is nearly gone: " + v[line == 0 ? 22 : 21], v[line == 0 ? 22 : 21] < 0.02f);
		}
	}

	@Test
	public void eachLayerIsLitFromItsOwnHeight()
	{
		// a lamp 30 up: close beside it the layer at its height is the brightest, the top layer, 180 higher, the least
		LightMapBake.Result r = bake(new BakeScene(), BakeScene.light(0, 24.5f, 24.5f, 30, 8));
		float low = BakeScene.at(r, 0, 0, 4 * 25, 4 * 24 + 2), top = BakeScene.at(r, 0, 3, 4 * 25, 4 * 24 + 2);
		assertTrue("low " + low + " top " + top, low > top * 1.2f && top > 0);
		// far out the same, in less
		float far = BakeScene.at(r, 0, 0, 4 * 29, 4 * 24 + 2);
		assertTrue(far > 0 && far < low);
	}

	@Test
	public void anEaveShadesTheHighLayersBehindItAndNotTheLowOnes()
	{
		// a roof overhang, 150 to 239 high, two tiles east of the lamp, a tile deep and three wide: the ground under it is lit by the layers
		// the overhang does not cover the light's way to (the low ones, seen under it), and the layer at the overhang's height
		// is shaded behind it
		BakeScene s = new BakeScene();
		for (int gz = 4 * 23; gz < 4 * 26; ++gz)
		{
			for (int gx = 4 * 26; gx < 4 * 27; ++gx)
			{
				s.modelBox(0, gx, gz, 0, 0, 32, 32, 150, 239);
			}
		}
		LightMapBake.Result roofed = bake(s, BakeScene.light(0, 24.5f, 24.5f, 30, 10));
		LightMapBake.Result bare = bake(new BakeScene(), BakeScene.light(0, 24.5f, 24.5f, 30, 10));
		int gx = 4 * 28 + 2, gz = 4 * 24 + 2;
		assertEquals("low layer sees under the eave", BakeScene.at(bare, 0, 0, gx, gz), BakeScene.at(roofed, 0, 0, gx, gz), 1e-3f);
		assertTrue("top layer is behind it", BakeScene.at(roofed, 0, 3, gx, gz) < BakeScene.at(bare, 0, 3, gx, gz) * 0.5f);
	}

	/** The share of the layer at the light's height (the second) at a tile position. */
	private static float at(LightMapBake.Result r, float tileX, float tileY)
	{
		return BakeScene.at(r, 0, 1, (int) Math.floor(tileX * 4), (int) Math.floor(tileY * 4));
	}

	/** A wall sconce: a bracket of the same object reaching 64 units, past the small clearance a world light has, on the light's line east. */
	private static BakeScene sconceScene(int owner)
	{
		BakeScene s = new BakeScene();
		for (int gz = 4 * 24 + 1; gz <= 4 * 24 + 3; ++gz)
		{
			for (int gx = 4 * 24 + 3; gx <= 4 * 24 + 4; ++gx)
			{
				s.modelBox(0, gx, gz, 0, 0, 32, 32, 80, 110, owner);
			}
		}
		return s;
	}

	@Test
	public void aLightIsNotShadedByTheObjectItIsOn()
	{
		// 95 up, in the cell at (24.6, 24.6) tiles, with its bracket reaching 60 units east of it
		float x = 24.6f, y = 24.6f;
		LightMapBake.Result bare = bake(new BakeScene(), BakeScene.light(0, x, y, 95, 8));
		LightMapBake.Result own = bake(sconceScene(77), BakeScene.light(0, x, y, 95, 8, 77));
		LightMapBake.Result other = bake(sconceScene(78), BakeScene.light(0, x, y, 95, 8, 77));
		LightMapBake.Result world = bake(sconceScene(0), BakeScene.light(0, x, y, 95, 8));
		for (float tx = 27; tx <= 29; tx += 1)
		{
			float open = at(bare, tx, y);
			assertEquals("no streak east, tile " + tx, open, at(own, tx, y), 1e-6f);
			assertTrue("another object's bracket does shade it, tile " + tx, at(other, tx, y) < open * 0.5f);
		}
		// a world light has no object, and keeps only the small clearance round it: 60 units of bracket shade it
		assertTrue(at(world, 28, y) < at(bare, 28, y) * 0.5f);
	}

	/** The share behind a prop at a cell, over what it is in the open. */
	private static float behind(BakeScene prop, int gx, int gz, int cx, int cz, float height)
	{
		LightMapBake.Result shaded = bake(prop, BakeScene.light(0, 22.5f, 24.5f, height, 8));
		LightMapBake.Result open = bake(new BakeScene(), BakeScene.light(0, 22.5f, 24.5f, height, 8));
		return BakeScene.at(shaded, 0, 1, cx, cz) / BakeScene.at(open, 0, 1, cx, cz);
	}

	@Test
	public void aThinPropShadesALittleAndAMassThatFillsItsCellsShadesAll()
	{
		// the light at 22.5 tiles, the prop in the cell 3 tiles east of it, the cell behind it 2 tiles on
		int gx = 4 * 25 + 2, gz = 4 * 24 + 2, behindX = 4 * 27, behindZ = 4 * 24 + 2;
		// an armour stand: 12 units across, to 150 high
		float stand = behind(new BakeScene().modelBox(0, gx, gz, 10, 10, 22, 22, 0, 149), gx, gz, behindX, behindZ, 60);
		assertTrue("an armour stand takes a few tens of percent at most: " + stand, stand > 0.6f && stand < 0.98f);
		// the same, 20 across: a statue's base
		float wide = behind(new BakeScene().modelBox(0, gx, gz, 6, 6, 26, 26, 0, 149), gx, gz, behindX, behindZ, 60);
		assertTrue("wider takes more: " + wide, wide < stand);
		assertTrue("a few tens of percent at most: " + wide, wide > 0.6f);
		// an oven body: a tile of cells filled
		BakeScene oven = new BakeScene();
		for (int z = 4 * 24; z < 4 * 25; ++z)
		{
			for (int x = 4 * 25; x < 4 * 26; ++x)
			{
				oven.modelBox(0, x, z, 0, 0, 32, 32, 0, 149);
			}
		}
		float body = behind(oven, gx, gz, behindX, behindZ, 60);
		assertTrue("an oven body shades what is behind it, as a wall of models always did, to what the wrap of light leaves: " + body, body < 0.5f);
	}

	@Test
	public void aDoorLeafShadesAcrossItsLengthAndNotAlongIt()
	{
		// a leaf 28 long, 4 thick, on the line from the light: broadside (the leaf across the line east) and edge on
		BakeScene across = new BakeScene().modelBox(0, 4 * 25 + 2, 4 * 24 + 2, 14, 2, 18, 30, 0, 100);
		BakeScene along = new BakeScene().modelBox(0, 4 * 25 + 2, 4 * 24 + 2, 2, 14, 30, 18, 0, 100);
		float broad = behind(across, 0, 0, 4 * 27, 4 * 24 + 2, 60), edge = behind(along, 0, 0, 4 * 27, 4 * 24 + 2, 60);
		assertTrue("broadside " + broad + " edge on " + edge, broad < 0.85f && edge > broad + 0.1f);
	}
}
