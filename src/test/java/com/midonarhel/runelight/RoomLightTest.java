package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.Arrays;
import org.junit.Test;

public class RoomLightTest
{
	private static final int SIZE = 20, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;
	// the house of RoomMapTest: extended tiles 8..11, so cells 32..47 of a light map of 80 cells: block 1, 1
	private static final int CELLS = SIZE * 4;
	// the sun's height: well below the horizon, and high
	private static final float NIGHT = -20, DAY = 45;

	private static RoomLight light()
	{
		RoomLight l = new RoomLight();
		l.setCells(CELLS);
		l.setRooms(RoomMapTest.house(new int[4][LOCAL][LOCAL]));
		return l;
	}

	/** A block of the light map with this much light in every cell, null for none. */
	private static float[] glow(float lum)
	{
		float[] a = new float[LightMap.BLOCK * LightMap.BLOCK];
		Arrays.fill(a, lum);
		return a;
	}

	/** The light map's sink for the rooms' light alone. */
	private static LightMap.Sink floors(RoomLight l)
	{
		return new LightMap.Sink()
		{
			@Override
			public void block(int plane, int bx, int bz, byte[] vis, byte[] index)
			{
			}

			@Override
			public void floor(int plane, int bx, int bz, float[] floor)
			{
				l.floor(plane, bx, bz, floor);
			}
		};
	}

	private static float night(RoomLight l)
	{
		l.update(0, RoomLight.target(NIGHT, true, 1), NIGHT);
		return l.fillOf(1);
	}

	@Test
	public void aRoomWithNoLightAndNoSkyIsLiftedToTheTarget()
	{
		RoomLight l = light();
		assertEquals(RoomLight.TARGET_NIGHT, night(l), 1e-5);
		RoomLight dayRoom = light();
		float target = RoomLight.target(DAY, true, 1);
		dayRoom.update(0, target, DAY);
		assertEquals(target, dayRoom.fillOf(1), 1e-5);
		assertEquals(RoomLight.DAY_SHARE * RoomLight.outdoorLight(DAY), target, 1e-5);
		// the lift is a third of the light a sunlit floor outdoors gets, and without the day setting there is none
		assertEquals(0.42f * RoomLight.outdoorLight(DAY), target, 1e-5);
		assertTrue(target > 2 * RoomLight.TARGET_NIGHT);
		assertEquals(0, RoomLight.target(DAY, false, 1), 0);
		// at dusk it eases to the night's, never under it, and at dawn the same
		float last = target;
		for (float alt = DAY; alt >= NIGHT; alt -= 0.5f)
		{
			float t = RoomLight.target(alt, true, 1);
			assertTrue(t >= RoomLight.TARGET_NIGHT - 1e-6f);
			assertTrue("smooth at " + alt, Math.abs(t - last) < 0.05f);
			last = t;
		}
		assertEquals(RoomLight.TARGET_NIGHT, last, 1e-6);
	}

	@Test
	public void aRoomLitByTorchesGetsNoFill()
	{
		RoomLight l = light();
		l.floor(0, 1, 1, glow(3));
		assertEquals(1.5f, l.lightScore(1), 1e-3);
		assertEquals(0, night(l), 0);
		RoomLight day = light();
		day.floor(0, 1, 1, glow(3));
		day.update(0, RoomLight.target(DAY, true, 1), DAY);
		assertTrue("by day too " + day.fillOf(1), day.fillOf(1) < 0.02f);
	}

	@Test
	public void aHotSpotDoesNotHideTheDarkRestOfARoom()
	{
		RoomLight l = light();
		// one cell in sixteen, very bright: counts for no more than the cap
		float[] a = new float[LightMap.BLOCK * LightMap.BLOCK];
		for (int c = 0; c < LightMap.BLOCK * LightMap.BLOCK; c += 16)
		{
			a[c] = 1000;
		}
		l.floor(0, 1, 1, a);
		assertTrue(l.lightScore(1) <= RoomLight.CELL_CAP / 8);
		assertTrue(night(l) > 0.5f * RoomLight.TARGET_NIGHT);
	}

	@Test
	public void aWideOpenDoorwayLiftsARoomLessByDayThanAClosedOne()
	{
		float target = RoomLight.target(DAY, true, 1);
		RoomLight closed = light();
		closed.update(0, target, DAY);
		RoomLight open = light();
		open.setSky(new float[]{0, 1});
		open.update(0, target, DAY);
		RoomLight ajar = light();
		ajar.setSky(new float[]{0, 0.3f});
		ajar.update(0, target, DAY);
		assertTrue("wide open " + open.fillOf(1) + " of " + closed.fillOf(1), open.fillOf(1) < 0.5f * closed.fillOf(1));
		assertTrue(ajar.fillOf(1) < closed.fillOf(1) && ajar.fillOf(1) > open.fillOf(1));
		// the room's light is the fill and the daylight together: it reaches the target, and the open room gets the most
		// of it from the sky
		assertEquals(target, open.score(1, RoomLight.skyLuminance(DAY)) + open.fillOf(1), 0.35f * target);
		// at night the sky gives little: the same room is lifted to the night's target but for that little
		RoomLight n = light();
		n.setSky(new float[]{0, 1});
		float night = night(n);
		assertTrue(night > 0.6f * RoomLight.TARGET_NIGHT);
	}

	@Test
	public void byDayEveryRoomStaysClearlyDarkerThanTheOutdoors()
	{
		float outdoors = RoomLight.outdoorLight(DAY), target = RoomLight.target(DAY, true, 1);
		float skyLuminance = RoomLight.skyLuminance(DAY);
		RoomLight windowless = baked(walled(), 0, 4);
		windowless.update(0, target, DAY);
		RoomLight torch = baked(walled(), 9, 4);
		torch.update(0, target, DAY);
		// a doorway and windows: the sky comes in through the doorway; windows stop sight, so they bring none
		int[][][] doorway = RoomMapTest.walls();
		float sky = RoomLight.skyShares(RoomMapTest.house(doorway), WallMap.build(doorway, null, SIZE, OFFSET, 1), null, 4)[1];
		RoomLight doorAndWindows = light();
		doorAndWindows.setSky(new float[]{0, sky});
		doorAndWindows.update(0, target, DAY);
		float[] lit = {windowless.score(1, skyLuminance) + windowless.fillOf(1), doorAndWindows.score(1, skyLuminance) + doorAndWindows.fillOf(1),
			torch.score(1, skyLuminance) + torch.fillOf(1)};
		System.out.println("room light by day, outdoors " + outdoors + ": windowless score " + windowless.score(1, skyLuminance) + " fill "
			+ windowless.fillOf(1) + "; door and windows sky share " + sky + " score " + doorAndWindows.score(1, skyLuminance) + " fill "
			+ doorAndWindows.fillOf(1) + "; torch score " + torch.score(1, skyLuminance) + " fill " + torch.fillOf(1));
		for (float v : lit)
		{
			assertTrue("room " + v + " outdoors " + outdoors, v < 0.5f * outdoors && v > 0.3f * outdoors);
		}
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
		l.floor(0, 1, 1, glow(2));
		l.update(10, RoomLight.target(NIGHT, true, 1), NIGHT);
		assertEquals(0, l.fillOf(1), 1e-3);
		// a door opens, the lights are baked again with the room shut off from them: the blocks are cleared
		l.floor(0, 1, 1, null);
		l.update(10, RoomLight.target(NIGHT, true, 1), NIGHT);
		assertEquals(RoomLight.TARGET_NIGHT, l.fillOf(1), 1e-3);
		// brightness scales the target; without the day setting there is no target by day
		l.update(10, RoomLight.target(NIGHT, true, 0.5f), NIGHT);
		assertEquals(RoomLight.TARGET_NIGHT / 2, l.fillOf(1), 1e-3);
		assertEquals(0, RoomLight.target(DAY, false, 1), 0);
		assertEquals(RoomLight.TARGET_NIGHT, RoomLight.target(NIGHT, false, 1), 0);
	}

	@Test
	public void aRoomNewToTheSceneIsSummedFromTheLightAlreadySent()
	{
		RoomLight l = new RoomLight();
		l.setCells(CELLS);
		l.floor(0, 1, 1, glow(2));
		l.setRooms(RoomMapTest.house(new int[4][LOCAL][LOCAL]));
		assertEquals(1, l.lightScore(1), 1e-3);
	}

	@Test
	public void fillsEaseInAndTheSummaryCountsRooms()
	{
		RoomLight l = light();
		l.update(0, RoomLight.target(NIGHT, true, 1), NIGHT);
		l.floor(0, 1, 1, glow(2));
		float target = RoomLight.target(NIGHT, true, 1);
		l.update(0.1f, target, NIGHT);
		assertTrue("part of the way", l.fillOf(1) > 0 && l.fillOf(1) < target);
		int[] s = l.summary();
		assertEquals(1, s[0]);
		assertEquals(0, s[1] + s[2]);
		RoomLight dark = light();
		dark.update(0, target, NIGHT);
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
				strength, false, 1, 0));
		}
		map.runTasks();
		map.update(100000, floors(l));
		return l;
	}

	@Test
	public void aRoomWithATorchInItIsNotFilledAndAnEmptyOneIs()
	{
		float target = RoomLight.TARGET_NIGHT;
		RoomLight torch = baked(walled(), 9, 4);
		torch.update(0, target, NIGHT);
		assertTrue("score " + torch.lightScore(1), torch.lightScore(1) > target);
		assertEquals(0, torch.fillOf(1), 0.01f * target);
		RoomLight empty = baked(walled(), 0, 4);
		empty.update(0, target, NIGHT);
		assertEquals(target, empty.fillOf(1), 1e-5);
		// by day: the hearth's room needs about nothing of the day's target, the empty one all of it
		float day = RoomLight.target(DAY, true, 1);
		torch.update(10, day, DAY);
		empty.update(10, day, DAY);
		System.out.println("room light numbers: hearth room score " + torch.lightScore(1) + ", day target " + day + ", hearth fill "
			+ torch.fillOf(1) + ", windowless fill " + empty.fillOf(1));
		assertTrue("hearth by day " + torch.fillOf(1), torch.fillOf(1) < 0.6f * day);
		assertEquals(day, empty.fillOf(1), 1e-5);
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
		map.add(new LightMap.Source(0, BakeScene.world(30), -100, BakeScene.world(23), 4 * 128, 1, 0.58f, 0.01f, 9, false, 1, 0));
		map.runTasks();
		map.update(100000, floors(l));
		assertEquals(0, l.lightScore(1), 1e-3);
	}
}
