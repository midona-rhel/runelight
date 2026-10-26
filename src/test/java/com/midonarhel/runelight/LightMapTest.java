package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LightMapTest
{
	/** The textures, on the CPU: [plane][layer][z][x][4] floats. */
	private static final class Mirror implements LightMap.Sink
	{
		final int cells = BakeScene.SIZE * 4;
		final float[][][] a = new float[4][cells][cells * 4], b = new float[4][cells][cells * 4];
		int blocks;

		@Override
		public void block(int plane, int bx, int bz, short[] sa, short[] sb)
		{
			blocks++;
			for (int j = 0; j < LightMap.BLOCK; ++j)
			{
				for (int i = 0; i < LightMap.BLOCK; ++i)
				{
					for (int c = 0; c < 4; ++c)
					{
						a[plane][bz * 32 + j][(bx * 32 + i) * 4 + c] = sa == null ? 0 : Half.fromHalf(sa[(j * 32 + i) * 4 + c]);
						b[plane][bz * 32 + j][(bx * 32 + i) * 4 + c] = sb == null ? 0 : Half.fromHalf(sb[(j * 32 + i) * 4 + c]);
					}
				}
			}
		}

		float red(int plane, float tx, float ty)
		{
			return a[plane][(int) (ty * 4)][(int) (tx * 4) * 4];
		}

		/** How far the brightest light is ahead of the rest, as a share of all the light. */
		float lead(int plane, float tx, float ty)
		{
			float[] c = a[plane][(int) (ty * 4)];
			int i = (int) (tx * 4) * 4;
			return c[i + 3] / (0.2126f * c[i] + 0.7152f * c[i + 1] + 0.0722f * c[i + 2]);
		}

		/** The lights' mean place (x, y, z; world units) as the shader works it out from the cell. */
		float[] place(int plane, float tx, float ty)
		{
			int gx = (int) (tx * 4), gz = (int) (ty * 4);
			float[] c = a[plane][gz], d = b[plane][gz];
			float total = 0.2126f * c[gx * 4] + 0.7152f * c[gx * 4 + 1] + 0.0722f * c[gx * 4 + 2];
			float midX = (gx + 0.5f - BakeScene.OFFSET * 4) * 32, midZ = (gz + 0.5f - BakeScene.OFFSET * 4) * 32;
			return new float[]{midX + d[gx * 4] * 16 / total, d[gx * 4 + 2] * 16 / total, midZ + d[gx * 4 + 1] * 16 / total};
		}

		float id(int plane, float tx, float ty)
		{
			return b[plane][(int) (ty * 4)][(int) (tx * 4) * 4 + 3];
		}
	}

	private static LightMap.Source source(int plane, float tx, float ty, float height, float radiusTiles, float strength)
	{
		return new LightMap.Source(plane, BakeScene.world(tx), -plane * BakeScene.STOREY - height, BakeScene.world(ty),
			radiusTiles * 128, 1, 0.5f, 0.25f, strength, false, 1);
	}

	private static void settle(LightMap map, Mirror m)
	{
		map.runTasks();
		map.update(100000, m);
	}

	@Test
	public void aLightIsLiveOnlyOnceItsBlocksAreSent()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source s = source(0, 24.5f, 24.5f, 40, 6, 2);
		assertTrue(map.add(s));
		assertFalse(LightMap.isLive(s));
		map.runTasks();
		Mirror m = new Mirror();
		map.update(0, m);
		assertFalse("baked and put together, but nothing sent", LightMap.isLive(s));
		map.update(100000, m);
		assertTrue(LightMap.isLive(s));
		assertEquals("colour times strength at the light", 2 * 1, m.red(0, 24.6f, 24.6f), 0.5f);
		assertEquals(0.5f * m.red(0, 24.6f, 24.6f), m.a[0][(int) (24.6f * 4)][(int) (24.6f * 4) * 4 + 1], 0.01f);
		assertEquals("alone, it is the whole", 1, m.lead(0, 24.6f, 24.6f), 0.01f);
		float[] at = m.place(0, 24.6f, 24.6f);
		assertEquals("the light's place", s.x, at[0], 2);
		assertEquals(s.y, at[1], 1);
		assertEquals(s.z, at[2], 2);
		assertEquals(0, m.red(0, 40.5f, 24.5f), 0);
	}

	@Test
	public void theBlocksNearestTheViewGoFirst()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		map.setFocus(BakeScene.world(30), BakeScene.world(30));
		LightMap.Source near = source(0, 30.5f, 30.5f, 40, 3, 1), far = source(0, 6.5f, 6.5f, 40, 3, 1);
		map.add(far);
		map.add(near);
		map.runTasks();
		Mirror m = new Mirror();
		map.update(1, m);
		assertEquals(1, m.blocks);
		assertTrue(m.red(0, 30.5f, 30.5f) > 0 || m.red(0, 30.5f, 29.5f) > 0);
		assertEquals(0, m.red(0, 6.5f, 6.5f), 0);
	}

	@Test
	public void theLightsMeanPlaceAndTheLeadOfTheBrightest()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source strong = source(0, 24.5f, 24.5f, 40, 8, 3), weak = source(0, 28.5f, 24.5f, 100, 8, 1);
		map.add(weak);
		map.add(strong);
		Mirror m = new Mirror();
		settle(map, m);
		// near the strong light it is ahead, but not all the light; the mean place is between them, nearer the strong one
		float lead = m.lead(0, 23.5f, 24.5f);
		assertTrue("lead " + lead, lead > 0.2f && lead < 1);
		assertEquals(strong.id, Math.round(m.id(0, 23.5f, 24.5f)));
		float[] at = m.place(0, 23.5f, 24.5f);
		assertTrue("x " + at[0], at[0] > strong.x && at[0] < weak.x);
		assertTrue("y " + at[1], at[1] < strong.y && at[1] > weak.y);
		// halfway between, where they are as bright: no lead, whichever is read
		float mid = 0;
		for (float x = 24.5f; x < 28.5f; x += 0.25f)
		{
			mid = Math.min(mid == 0 ? 1 : mid, m.lead(0, x, 24.5f));
		}
		assertTrue("the lead goes through zero or near it where the brightest changes: " + mid, mid < 0.15f);
	}

	@Test
	public void theMeanPlaceChangesSmoothlyFromCellToCell()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source a = source(0, 20.5f, 24.5f, 100, 8, 2), b = source(0, 30.5f, 24.5f, 40, 8, 2);
		map.add(a);
		map.add(b);
		Mirror m = new Mirror();
		settle(map, m);
		float last = Float.NaN;
		for (float x = 18.5f; x < 32; x += 0.25f)
		{
			float[] at = m.place(0, x, 24.5f);
			if (!Float.isNaN(last))
			{
				assertTrue("x " + x + " place " + at[0] + " after " + last, Math.abs(at[0] - last) < 180);
			}
			last = at[0];
		}
	}

	@Test
	public void flickerChangesTheTableNotTheBlocks()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source s = source(0, 24.5f, 24.5f, 40, 6, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		int before = m.blocks;
		map.setLevel(s, 3);
		assertEquals("1.5 times the baked strength", 1.5f, map.table()[s.id * 4], 1e-4f);
		assertEquals("its radius", s.radius, map.table()[s.id * 4 + 1], 0);
		settle(map, m);
		assertEquals(before, m.blocks);
	}

	@Test
	public void aDoorOpeningBakesTheLightNearItAgain()
	{
		BakeScene closed = new BakeScene().verticalWall(0, 20, 14, 26);
		BakeScene open = new BakeScene().verticalWall(0, 20, 14, 26, 20);
		LightMap map = new LightMap(0);
		map.setBlockers(closed.blockers(1));
		LightMap.Source s = source(0, 17.5f, 20.5f, 40, 8, 2);
		LightMap.Source elsewhere = source(0, 6.5f, 40.5f, 40, 3, 2);
		map.add(s);
		map.add(elsewhere);
		Mirror m = new Mirror();
		settle(map, m);
		assertEquals(0, m.red(0, 22.5f, 20.5f), 0);
		float elsewhereBefore = m.red(0, 6.6f, 40.6f);
		int blocksBefore = m.blocks;
		map.setBlockers(open.blockers(2));
		settle(map, m);
		assertTrue("the light shines through the door now", m.red(0, 22.5f, 20.5f) > 0.01f);
		assertEquals(elsewhereBefore, m.red(0, 6.6f, 40.6f), 0);
		assertTrue(LightMap.isLive(s));
		assertTrue("only the blocks round the door were sent again", m.blocks - blocksBefore < blocksBefore);
	}

	@Test
	public void aModelAppearingBlocksLightToo()
	{
		BakeScene bare = new BakeScene();
		BakeScene pillar = new BakeScene().modelCells(0, 96, 82, 99, 85);
		LightMap map = new LightMap(0);
		map.setBlockers(bare.blockers());
		LightMap.Source s = source(0, 20.5f, 20.75f, 40, 8, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		float lit = m.red(0, 26.5f, 20.75f);
		assertTrue(lit > 0.01f);
		map.setBlockers(pillar.blockers());
		settle(map, m);
		assertTrue(m.red(0, 26.5f, 20.75f) < lit * 0.5f);
	}

	@Test
	public void nightLightsComeWithTheNight()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source lamp = new LightMap.Source(0, BakeScene.world(24.5f), -40, BakeScene.world(24.5f), 6 * 128, 1, 1, 1, 2, true, 1);
		LightMap.Source day = source(0, 30.5f, 24.5f, 40, 4, 1);
		map.setNight(0);
		map.add(lamp);
		map.add(day);
		Mirror m = new Mirror();
		settle(map, m);
		assertEquals("a night light is not there by day", 0, m.red(0, 24.6f, 24.6f), 0);
		assertTrue(m.red(0, 30.6f, 24.6f) > 0);
		map.setNight(1);
		settle(map, m);
		assertTrue(m.red(0, 24.6f, 24.6f) > 1);
		// between the steps the table makes up for it: baked at 1/16 of the night, now a little more
		map.setNight(0.5f);
		float now = 2 * 0.52f;
		map.setLevel(lamp, now);
		assertEquals(now / (2 * 0.5f), map.table()[lamp.id * 4], 1e-3f);
	}

	@Test
	public void aLightThatGoesLeavesTheMapBeforeItsEntryIsReused()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source s = source(0, 24.5f, 24.5f, 40, 6, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		int id = s.id;
		assertTrue(m.red(0, 24.6f, 24.6f) > 0);
		map.remove(s, true);
		assertFalse(LightMap.isLive(s));
		LightMap.Source t = source(0, 40.5f, 40.5f, 40, 3, 1);
		map.add(t);
		assertTrue("the entry is not free before the blocks are sent", t.id != id);
		settle(map, m);
		assertEquals(0, m.red(0, 24.6f, 24.6f), 0);
		LightMap.Source u = source(0, 10.5f, 10.5f, 40, 3, 1);
		map.add(u);
		assertTrue(u.id > 0);
	}

	@Test
	public void aLightLetGoOfCanComeBack()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source s = source(0, 24.5f, 24.5f, 40, 6, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		map.remove(s, false);
		settle(map, m);
		assertEquals(0, m.red(0, 24.6f, 24.6f), 0);
		assertTrue(map.add(s));
		settle(map, m);
		assertTrue(LightMap.isLive(s));
		assertTrue(m.red(0, 24.6f, 24.6f) > 0);
		map.remove(s, true);
		assertFalse(map.add(s));
	}

	@Test
	public void aSceneChangeClearsTheTextures()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source s = source(0, 24.5f, 24.5f, 40, 6, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		assertTrue(m.red(0, 24.6f, 24.6f) > 0);
		map.clear();
		settle(map, m);
		assertEquals(0, m.red(0, 24.6f, 24.6f), 0);
		assertFalse(LightMap.isLive(s));
	}

	@Test
	public void lightsBelowAnUpstairsRoomAreOnTheirOwnStoreyOnly()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().floor(1, 10, 10, 40, 40, true).blockers());
		LightMap.Source s = source(1, 24.5f, 24.5f, 40, 6, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		assertTrue(m.red(1, 24.6f, 24.6f) > 0);
		assertEquals(0, m.red(0, 24.6f, 24.6f), 0);
	}

	@Test
	public void onlyAChangeInTheReachOfALightBakesItAgain()
	{
		// two doors, far apart, and a light between them out of reach of both: the box round the two would hold it
		BakeScene before = new BakeScene().verticalWall(0, 8, 6, 10).verticalWall(0, 40, 38, 42);
		BakeScene after = new BakeScene().verticalWall(0, 8, 6, 10, 8).verticalWall(0, 40, 38, 42, 40);
		LightMap map = new LightMap(0);
		map.setBlockers(before.blockers(1));
		LightMap.Source middle = source(0, 24.5f, 24.5f, 40, 6, 2), byDoor = source(0, 5.5f, 8.5f, 40, 6, 2);
		map.add(middle);
		map.add(byDoor);
		Mirror m = new Mirror();
		settle(map, m);
		int middleBakes = middle.generation, doorBakes = byDoor.generation;
		map.setBlockers(after.blockers(2));
		settle(map, m);
		assertEquals("the middle light is out of reach of both", middleBakes, middle.generation);
		assertEquals("the one by the door is baked again", doorBakes + 1, byDoor.generation);
	}

	@Test
	public void aSnapshotThatIsTheSameBakesNothing()
	{
		BakeScene scene = new BakeScene().verticalWall(0, 20, 14, 26);
		LightMap map = new LightMap(0);
		map.setBlockers(scene.blockers(1));
		LightMap.Source s = source(0, 17.5f, 20.5f, 40, 8, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		int bakes = s.generation;
		// the walls and the models are rebuilt every tick and every voxel build: equal ones are no change
		map.setBlockers(new BakeScene().verticalWall(0, 20, 14, 26).blockers(2));
		settle(map, m);
		assertEquals(bakes, s.generation);
	}

	@Test
	public void aLightBakedAgainToTheSameResultSendsNothing()
	{
		// a model at 200 units appears in the light's reach, which has a fire at 40 units: it is baked again, and the
		// result is what it was
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().modelBox(0, 4 * 30, 4 * 24, 0, 0, 32, 32, 0, 30).blockers(1));
		LightMap.Source s = source(0, 24.5f, 24.5f, 40, 6, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		int bakes = s.generation, blocks = m.blocks;
		map.setBlockers(new BakeScene().modelBox(0, 4 * 30, 4 * 24, 0, 0, 32, 32, 0, 30).modelBox(0, 4 * 25, 4 * 24, 0, 0, 32, 32, 200, 230)
			.blockers(2));
		settle(map, m);
		assertEquals(bakes + 1, s.generation);
		assertEquals(blocks, m.blocks);
		assertTrue(LightMap.isLive(s));
	}
}
