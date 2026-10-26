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

		float share(int plane, float tx, float ty)
		{
			return a[plane][(int) (ty * 4)][(int) (tx * 4) * 4 + 3];
		}

		float id(int plane, float tx, float ty)
		{
			return b[plane][(int) (ty * 4)][(int) (tx * 4) * 4];
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
		assertEquals("alone, it is the whole", 1, m.share(0, 24.6f, 24.6f), 0.01f);
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
	public void theDominantLightAndItsShare()
	{
		LightMap map = new LightMap(0);
		map.setBlockers(new BakeScene().blockers());
		LightMap.Source strong = source(0, 24.5f, 24.5f, 40, 8, 3), weak = source(0, 28.5f, 24.5f, 40, 8, 1);
		map.add(weak);
		map.add(strong);
		Mirror m = new Mirror();
		settle(map, m);
		// at the strong light's end the strong light has most of it; at the weak one's, still the stronger
		float shareNearStrong = m.share(0, 23.5f, 24.5f);
		assertTrue("share " + shareNearStrong, shareNearStrong > 0.5f && shareNearStrong < 1);
		assertEquals(strong.id, Math.round(m.id(0, 23.5f, 24.5f)));
		// the offset of the dominant light from the cell's middle and its height
		int gx = (int) (23.5f * 4), gz = (int) (24.5f * 4);
		float cx = (gx + 0.5f - BakeScene.OFFSET * 4) * 32;
		assertEquals(strong.x - cx, m.b[0][gz][gx * 4 + 1], 1);
		assertEquals(strong.y, m.b[0][gz][gx * 4 + 3], 1);
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
}
