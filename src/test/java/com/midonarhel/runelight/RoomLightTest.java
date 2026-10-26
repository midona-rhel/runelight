package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class RoomLightTest
{
	private static final int SIZE = 20, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;
	// the house of RoomMapTest: extended tiles 8..11, so cells 32..47 of a light map of 80 cells: block 1, 1
	private static final int CELLS = SIZE * 4;

	private static RoomLight light()
	{
		RoomLight l = new RoomLight();
		l.setCells(CELLS);
		l.setRooms(RoomMapTest.house(new int[4][LOCAL][LOCAL]));
		return l;
	}

	/** A block of the light map with this much light in every cell, null for none. */
	private static short[] glow(float lum)
	{
		short[] a = new short[LightMap.BLOCK * LightMap.BLOCK * 4];
		for (int c = 0; c < LightMap.BLOCK * LightMap.BLOCK; ++c)
		{
			a[c * 4] = a[c * 4 + 1] = a[c * 4 + 2] = Half.toHalf(lum);
		}
		return a;
	}

	private static float night(RoomLight l)
	{
		l.update(0, RoomLight.target(1, true, 1), 1);
		return l.fillOf(1);
	}

	@Test
	public void aRoomWithNoLightAndNoSkyIsLiftedToTheTarget()
	{
		RoomLight l = light();
		assertEquals(RoomLight.TARGET_NIGHT, night(l), 1e-5);
		RoomLight dayRoom = light();
		dayRoom.update(0, RoomLight.target(0, true, 1), 0);
		assertEquals(RoomLight.TARGET_DAY, dayRoom.fillOf(1), 1e-5);
		assertTrue("a little more by day", RoomLight.TARGET_DAY > RoomLight.TARGET_NIGHT);
	}

	@Test
	public void aRoomLitByTorchesGetsNoFill()
	{
		RoomLight l = light();
		l.block(0, 1, 1, glow(2), null);
		assertEquals(RoomLight.CELL_CAP, l.lightScore(1), 1e-3);
		assertEquals(0, night(l), 0);
		RoomLight day = light();
		day.block(0, 1, 1, glow(2), null);
		day.update(0, RoomLight.target(0, true, 1), 0);
		assertEquals(0, day.fillOf(1), 0);
	}

	@Test
	public void aHotSpotDoesNotHideTheDarkRestOfARoom()
	{
		RoomLight l = light();
		// one cell in sixteen, very bright: counts for no more than the cap
		short[] a = new short[LightMap.BLOCK * LightMap.BLOCK * 4];
		for (int c = 0; c < LightMap.BLOCK * LightMap.BLOCK; c += 16)
		{
			a[c * 4] = a[c * 4 + 1] = a[c * 4 + 2] = Half.toHalf(1000);
		}
		l.block(0, 1, 1, a, null);
		assertTrue(l.lightScore(1) <= RoomLight.CELL_CAP / 8);
		assertTrue(night(l) > 0.5f * RoomLight.TARGET_NIGHT);
	}

	@Test
	public void aWideOpenDoorwayGivesLessFillByDayThanAtNight()
	{
		RoomLight l = light();
		l.setSky(new float[]{0, 0.7f});
		l.update(0, RoomLight.target(0, true, 1), 0);
		float day = l.fillOf(1);
		RoomLight n = light();
		n.setSky(new float[]{0, 0.7f});
		float night = night(n);
		assertTrue("day " + day + " night " + night, day < night / 10);
		assertTrue(night > 0.5f * RoomLight.TARGET_NIGHT);
	}

	@Test
	public void theSkyComesInThroughDoorwaysOnly()
	{
		int[][][] closed = RoomMapTest.walls();
		for (int y = 8; y < 12; ++y)
		{
			closed[0][8 - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
			closed[0][7 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
		}
		RoomMap shut = RoomMapTest.house(closed);
		float[] shutShares = RoomLight.skyShares(shut, WallMap.build(closed, null, SIZE, OFFSET, 1), null, 4);
		int[][][] doorway = RoomMapTest.walls();
		RoomMap open = RoomMapTest.house(doorway);
		float[] openShares = RoomLight.skyShares(open, WallMap.build(doorway, null, SIZE, OFFSET, 1), null, 4);
		assertEquals(0, shutShares[1], 1e-4);
		assertTrue("open " + openShares[1], openShares[1] > 0.02f);
	}

	@Test
	public void theKneeIsSmoothAndOnlyEasesInTheShortfall()
	{
		assertEquals(0, RoomLight.knee(-1), 0);
		assertEquals(0, RoomLight.knee(-RoomLight.KNEE), 0);
		assertEquals(1, RoomLight.knee(1), 0);
		assertEquals(RoomLight.KNEE, RoomLight.knee(RoomLight.KNEE), 1e-6);
		float step = 0.001f, previous = RoomLight.knee(-1);
		float slope = 0;
		for (float x = -1 + step; x < 1.5f; x += step)
		{
			float v = RoomLight.knee(x);
			float s = (v - previous) / step;
			assertTrue("never down", v >= previous);
			assertTrue("slope at most one", s <= 1.01f);
			assertTrue("the slope changes slowly at " + x, Math.abs(s - slope) < 0.01f + 1.5f * step / RoomLight.KNEE);
			slope = s;
			previous = v;
		}
		// the fill follows the shortfall one to one past the knee, and a score on the target gives only a little
		assertEquals(0.15f, RoomLight.fill(0.15f, 0.3f), 1e-6);
		assertTrue(RoomLight.fill(0.3f, 0.3f) < 0.3f * 0.07f);
		assertEquals(0, RoomLight.fill(0.5f, 0.3f), 0);
		assertEquals(0, RoomLight.fill(0, 0), 0);
	}

	@Test
	public void theFillFollowsABakeAndBrightnessAndTheDaySetting()
	{
		RoomLight l = light();
		assertEquals(RoomLight.TARGET_NIGHT, night(l), 1e-5);
		// the room's lights are baked: its blocks are sent
		l.block(0, 1, 1, glow(2), null);
		l.update(10, RoomLight.target(1, true, 1), 1);
		assertEquals(0, l.fillOf(1), 1e-3);
		// a door opens, the lights are baked again with the room shut off from them: the blocks are cleared
		l.block(0, 1, 1, null, null);
		l.update(10, RoomLight.target(1, true, 1), 1);
		assertEquals(RoomLight.TARGET_NIGHT, l.fillOf(1), 1e-3);
		// brightness scales the target; without the day setting there is no target by day
		l.update(10, RoomLight.target(1, true, 0.5f), 1);
		assertEquals(RoomLight.TARGET_NIGHT / 2, l.fillOf(1), 1e-3);
		assertEquals(0, RoomLight.target(0, false, 1), 0);
		assertEquals(RoomLight.TARGET_NIGHT, RoomLight.target(1, false, 1), 0);
	}

	@Test
	public void aRoomNewToTheSceneIsSummedFromTheLightAlreadySent()
	{
		RoomLight l = new RoomLight();
		l.setCells(CELLS);
		l.block(0, 1, 1, glow(2), null);
		l.setRooms(RoomMapTest.house(new int[4][LOCAL][LOCAL]));
		assertEquals(RoomLight.CELL_CAP, l.lightScore(1), 1e-3);
	}

	@Test
	public void fillsEaseInAndTheSummaryCountsRooms()
	{
		RoomLight l = light();
		l.update(0, RoomLight.target(1, true, 1), 1);
		l.block(0, 1, 1, glow(2), null);
		float target = RoomLight.target(1, true, 1);
		l.update(0.1f, target, 1);
		assertTrue("part of the way", l.fillOf(1) > 0 && l.fillOf(1) < target);
		int[] s = l.summary();
		assertEquals(1, s[0]);
		assertEquals(0, s[1] + s[2]);
		RoomLight dark = light();
		dark.update(0, target, 1);
		assertEquals(1, dark.summary()[2]);
	}

	// ---- a real bake: a room of 6 by 6 tiles in a scene of 48, lit by a light of the given strength in the middle

	private static RoomMap room(BakeScene scene)
	{
		int size = BakeScene.SIZE, offset = BakeScene.OFFSET;
		byte[][][] settings = new byte[4][size][size];
		for (int x = 20; x <= 25; ++x)
		{
			for (int y = 20; y <= 25; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		int[][][] heights = new int[4][size + 1][size + 1];
		RoomMap m = RoomMap.build(BuildingDetector.detect(settings, null), settings, heights, offset);
		m.setWalls(WallMap.build(scene.flags, null, size, offset, 1));
		return m;
	}

	private static BakeScene walled()
	{
		BakeScene scene = new BakeScene();
		scene.verticalWall(0, 20, 20, 25).verticalWall(0, 26, 20, 25);
		for (int x = 20; x <= 25; ++x)
		{
			scene.southWall(0, x, 20).southWall(0, x, 26);
		}
		return scene;
	}

	private static RoomLight baked(BakeScene scene, float strength, float radiusTiles)
	{
		RoomLight l = new RoomLight();
		l.setCells(BakeScene.SIZE * 4);
		l.setRooms(room(scene));
		LightMap map = new LightMap(0);
		map.setBlockers(scene.blockers());
		if (strength > 0)
		{
			map.add(new LightMap.Source(0, BakeScene.world(23), -100, BakeScene.world(23), radiusTiles * 128, 1, 0.58f, 0.01f,
				strength, false, 1));
		}
		map.runTasks();
		map.update(100000, l);
		return l;
	}

	@Test
	public void aRoomWithATorchInItIsNotFilledAndAnEmptyOneIs()
	{
		float target = RoomLight.TARGET_NIGHT;
		RoomLight torch = baked(walled(), 9, 4);
		torch.update(0, target, 1);
		assertTrue("score " + torch.lightScore(1), torch.lightScore(1) > target);
		assertEquals(0, torch.fillOf(1), 0.01f * target);
		RoomLight empty = baked(walled(), 0, 4);
		empty.update(0, target, 1);
		assertEquals(target, empty.fillOf(1), 1e-5);
	}

	@Test
	public void aLightBehindTheWallDoesNotCountForTheRoom()
	{
		BakeScene scene = walled();
		RoomLight l = new RoomLight();
		l.setCells(BakeScene.SIZE * 4);
		l.setRooms(room(scene));
		LightMap map = new LightMap(0);
		map.setBlockers(scene.blockers());
		map.add(new LightMap.Source(0, BakeScene.world(30), -100, BakeScene.world(23), 4 * 128, 1, 0.58f, 0.01f, 9, false, 1));
		map.runTasks();
		map.update(100000, l);
		assertEquals(0, l.lightScore(1), 1e-3);
	}
}
