package com.midonarhel.runelight;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LightMapTest
{
	/** The textures, on the CPU: visibility [plane][height layer][z][x][4 slots] (0 to 1), the index [plane][z][x][4 slots]. */
	private static final class Mirror implements LightMap.Sink
	{
		final int cells = BakeScene.SIZE * 4;
		final float[][][][] vis = new float[4][LightMapBake.HEIGHT_LAYERS][cells][cells * 4];
		final int[][][] index = new int[4][cells][cells * 4];
		final float[][][] floor = new float[4][cells][cells];
		int blocks, floors;

		@Override
		public void block(int plane, int bx, int bz, byte[] v, byte[] ix)
		{
			blocks++;
			int n = LightMap.BLOCK * LightMap.BLOCK;
			for (int j = 0; j < LightMap.BLOCK; ++j)
			{
				for (int i = 0; i < LightMap.BLOCK; ++i)
				{
					for (int c = 0; c < 4; ++c)
					{
						for (int layer = 0; layer < LightMapBake.HEIGHT_LAYERS; ++layer)
						{
							vis[plane][layer][bz * 32 + j][(bx * 32 + i) * 4 + c] = v == null ? 0 : (v[(layer * n + j * 32 + i) * 4 + c] & 255) / 255f;
						}
						// the slot's 16 bits: two to a texel, two texels
						int at = ((c >> 1) * n + j * 32 + i) * 4 + (c & 1) * 2;
						index[plane][bz * 32 + j][(bx * 32 + i) * 4 + c] = ix == null ? 0 : (ix[at] & 255) + 256 * (ix[at + 1] & 255);
					}
				}
			}
		}

		@Override
		public void floor(int plane, int bx, int bz, float[] light)
		{
			floors++;
			for (int j = 0; j < LightMap.BLOCK; ++j)
			{
				for (int i = 0; i < LightMap.BLOCK; ++i)
				{
					floor[plane][bz * 32 + j][bx * 32 + i] = light == null ? 0 : light[j * 32 + i];
				}
			}
		}

		/** The most visibility any light has at the lowest height layer. */
		float vis(int plane, float tx, float ty)
		{
			return vis(plane, 0, tx, ty);
		}

		float vis(int plane, int layer, float tx, float ty)
		{
			float most = 0;
			for (int c = 0; c < 4; ++c)
			{
				most = Math.max(most, vis[plane][layer][(int) (ty * 4)][(int) (tx * 4) * 4 + c]);
			}
			return most;
		}

		/** The visibility of the light (in its slot) at the lowest layer. */
		float vis(int plane, LightMap.Source s, float tx, float ty)
		{
			return vis[plane][0][(int) (ty * 4)][(int) (tx * 4) * 4 + s.slot];
		}

		int id(int plane, int slot, float tx, float ty)
		{
			return index[plane][(int) (ty * 4)][(int) (tx * 4) * 4 + slot];
		}

		float floor(int plane, float tx, float ty)
		{
			return floor[plane][(int) (ty * 4)][(int) (tx * 4)];
		}
	}

	private static LightMap.Source source(int plane, float tx, float ty, float height, float radiusTiles, float strength)
	{
		return new LightMap.Source(plane, BakeScene.world(tx), -plane * BakeScene.STOREY - height, BakeScene.world(ty),
			radiusTiles * 128, 1, 0.5f, 0.25f, strength, false, 1, 0);
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
		assertEquals("the whole of it at the light, nothing but visibility in the map", 1, m.vis(0, s, 24.6f, 24.6f), 0.02f);
		assertEquals(s.id, m.id(0, s.slot, 24.6f, 24.6f));
		assertEquals(0, m.vis(0, 40.5f, 24.5f), 0);
		assertEquals(0, m.id(0, s.slot, 40.5f, 24.5f));
		// the table has the light's place and radius, and its colour times its strength
		float[] t = map.table();
		int at = s.id * LightMap.TABLE_STRIDE * 4;
		assertEquals(s.x, t[at], 0);
		assertEquals(s.y, t[at + 1], 0);
		assertEquals(s.z, t[at + 2], 0);
		assertEquals(s.radius, t[at + 3], 0);
		assertEquals(2 * 1, t[at + 4], 1e-5f);
		assertEquals(2 * 0.5f, t[at + 5], 1e-5f);
		assertEquals(2 * 0.25f, t[at + 6], 1e-5f);
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
		assertTrue(m.vis(0, 30.5f, 30.5f) > 0 || m.vis(0, 30.5f, 29.5f) > 0);
		assertEquals(0, m.vis(0, 6.5f, 6.5f), 0);
	}

	@Test
	public void lightsThatOverlapAreInSlotsOfTheirOwn()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source a = source(0, 24.5f, 24.5f, 40, 6, 3), b = source(0, 28.5f, 24.5f, 100, 6, 1);
		LightMap.Source far = source(0, 6.5f, 6.5f, 40, 2, 1);
		map.add(a);
		map.add(b);
		map.add(far);
		Mirror m = new Mirror();
		settle(map, m);
		assertTrue("they overlap", a.slot != b.slot);
		assertEquals("a light far from the others takes the first slot there is", 0, far.slot);
		// between them each has its own channel and its own id: the table has what tells them apart
		assertEquals(a.id, m.id(0, a.slot, 26.5f, 24.5f));
		assertEquals(b.id, m.id(0, b.slot, 26.5f, 24.5f));
		assertTrue(m.vis(0, a, 26.5f, 24.5f) > 0.9f);
		assertTrue(m.vis(0, b, 26.5f, 24.5f) > 0.9f);
	}

	@Test
	public void noTwoLightsOfASlotAreNextToEachOther()
	{
		// a row of lights at all distances: no cell, nor a cell next to it, holds two lights of a slot, so a filtered read
		// of a channel is of one light
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		for (int i = 0; i < 9; ++i)
		{
			map.add(source(0, 12.5f + i * 2.2f, 24.5f, 40, 3, 1));
		}
		Mirror m = new Mirror();
		settle(map, m);
		int cells = m.cells;
		for (int z = 1; z < cells - 1; ++z)
		{
			for (int x = 1; x < cells - 1; ++x)
			{
				for (int slot = 0; slot < LightMap.SLOTS; ++slot)
				{
					int id = m.index[0][z][x * 4 + slot];
					for (int dz = -1; dz <= 1; ++dz)
					{
						for (int dx = -1; dx <= 1; ++dx)
						{
							float v = m.vis[0][1][z + dz][(x + dx) * 4 + slot];
							int other = m.index[0][z + dz][(x + dx) * 4 + slot];
							assertTrue("cell " + x + ", " + z + " slot " + slot + " holds " + id + ", and " + other + " beside it at " + dx + "," + dz + " vis " + v + " layer1 here " + m.vis[0][1][z][x * 4 + slot],
								v == 0 || other == id);
						}
					}
				}
			}
		}
	}

	@Test
	public void whereMoreLightsOverlapThanSlotsTheWeakestIsLeftOut()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source[] lights = new LightMap.Source[5];
		for (int i = 0; i < 4; ++i)
		{
			lights[i] = source(0, 24.5f + i * 0.25f, 24.5f, 40, 6, 4);
			map.add(lights[i]);
		}
		lights[4] = source(0, 24.5f, 24.75f, 40, 6, 1);
		map.add(lights[4]);
		Mirror m = new Mirror();
		settle(map, m);
		assertEquals("the four strongest are all there", 4, countIds(m, 25.5f, 24.5f));
		boolean weakestThere = false;
		for (int slot = 0; slot < 4; ++slot)
		{
			weakestThere |= m.id(0, slot, 25.5f, 24.5f) == lights[4].id;
		}
		assertFalse("and the weak one is not", weakestThere);
	}

	private static int countIds(Mirror m, float tx, float ty)
	{
		int n = 0;
		for (int slot = 0; slot < 4; ++slot)
		{
			n += m.id(0, slot, tx, ty) != 0 ? 1 : 0;
		}
		return n;
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
		map.setLevel(s, 3, 700);
		int at = s.id * LightMap.TABLE_STRIDE * 4;
		assertEquals("colour times the strength now", 3, map.table()[at + 4], 1e-5f);
		assertEquals(1.5f, map.table()[at + 5], 1e-5f);
		assertEquals("and the radius now", 700, map.table()[at + 3], 0);
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
		assertEquals(0, m.vis(0, 22.5f, 20.5f), 0);
		float elsewhereBefore = m.vis(0, 6.6f, 40.6f);
		int blocksBefore = m.blocks;
		map.setBlockers(open.blockers(2));
		settle(map, m);
		assertTrue("the light shines through the door now", m.vis(0, 22.5f, 20.5f) > 0.01f);
		assertEquals(elsewhereBefore, m.vis(0, 6.6f, 40.6f), 0);
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
		float lit = m.vis(0, 26.5f, 20.75f);
		assertTrue(lit > 0.01f);
		map.setBlockers(pillar.blockers());
		settle(map, m);
		assertTrue(m.vis(0, 26.5f, 20.75f) < lit * 0.5f);
	}

	@Test
	public void nightLightsComeWithTheNightInTheRoomsLightOnly()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source lamp = new LightMap.Source(0, BakeScene.world(24.5f), -40, BakeScene.world(24.5f), 6 * 128, 1, 1, 1, 2, true, 1, 0);
		LightMap.Source day = source(0, 30.5f, 24.5f, 40, 4, 1);
		map.setNight(0);
		map.add(lamp);
		map.add(day);
		Mirror m = new Mirror();
		settle(map, m);
		// the visibility is what it is by day and by night: the colour is the table's, and the table is set every frame
		assertTrue(m.vis(0, lamp, 24.6f, 24.6f) > 0.9f);
		assertEquals("a night light gives a floor no light by day", 0, m.floor(0, 24.6f, 24.6f), 0);
		assertTrue(m.floor(0, 30.6f, 24.6f) > 0);
		int blocks = m.blocks;
		map.setNight(1);
		settle(map, m);
		assertEquals("the textures are not sent again for a night", blocks, m.blocks);
		assertTrue("but the rooms' light is: " + m.floor(0, 24.6f, 24.6f), m.floor(0, 24.6f, 24.6f) > 1);
		assertTrue(LightMap.isLive(lamp));
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
		assertTrue(m.vis(0, 24.6f, 24.6f) > 0);
		map.remove(s, true);
		assertFalse(LightMap.isLive(s));
		LightMap.Source t = source(0, 40.5f, 40.5f, 40, 3, 1);
		map.add(t);
		assertTrue("the entry is not free before the blocks are sent", t.id != id);
		settle(map, m);
		assertEquals(0, m.vis(0, 24.6f, 24.6f), 0);
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
		assertEquals(0, m.vis(0, 24.6f, 24.6f), 0);
		assertTrue(map.add(s));
		settle(map, m);
		assertTrue(LightMap.isLive(s));
		assertTrue(m.vis(0, 24.6f, 24.6f) > 0);
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
		assertTrue(m.vis(0, 24.6f, 24.6f) > 0);
		map.clear();
		settle(map, m);
		assertEquals(0, m.vis(0, 24.6f, 24.6f), 0);
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
		assertTrue(m.vis(1, 24.6f, 24.6f) > 0);
		assertEquals(0, m.vis(0, 24.6f, 24.6f), 0);
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
		// a model at 225 units, over the top layer's middle, appears in the light's reach, which has a fire at 40 units: it
		// is baked again, and the result is what it was
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().modelBox(0, 4 * 30, 4 * 24, 0, 0, 32, 32, 0, 30).blockers(1));
		LightMap.Source s = source(0, 24.5f, 24.5f, 40, 6, 2);
		map.add(s);
		Mirror m = new Mirror();
		settle(map, m);
		int bakes = s.generation, blocks = m.blocks;
		map.setBlockers(new BakeScene().modelBox(0, 4 * 30, 4 * 24, 0, 0, 32, 32, 0, 30).modelBox(0, 4 * 25, 4 * 24, 0, 0, 32, 32, 225, 239)
			.blockers(2));
		settle(map, m);
		assertEquals(bakes + 1, s.generation);
		assertEquals(blocks, m.blocks);
		assertTrue(LightMap.isLive(s));
	}
}
