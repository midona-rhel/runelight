package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.RoomMapTest;

import com.midonarhel.lumina.visibility.BuildingDetector;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.WallMap;

import com.midonarhel.lumina.visibility.BuildingDetector;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.WallMap;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import java.util.List;
import org.junit.Test;

public class RoomLightTest
{
	private static final int SIZE = 20, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;
	// the sun's height: well below the horizon, and high
	private static final float NIGHT = -20, DAY = 45;

	private static RoomLight light()
	{
		RoomLight l = new RoomLight();
		l.setRooms(RoomMapTest.house(new int[4][LOCAL][LOCAL]));
		return l;
	}

	/** The house's room lit to this score by its lamps. */
	private static RoomLight lit(float score)
	{
		RoomLight l = light();
		l.setLamps(new float[]{0, score});
		return l;
	}

	private static float night(RoomLight l)
	{
		l.update(0, RoomLight.target(NIGHT, 1), NIGHT);
		return l.fillOf(1);
	}

	@Test
	public void aRoomWithNoLightAndNoSkyIsLiftedToTheTarget()
	{
		RoomLight l = light();
		assertEquals(RoomLight.TARGET_NIGHT, night(l), 1e-5);
		RoomLight dayRoom = light();
		float target = RoomLight.target(DAY, 1);
		dayRoom.update(0, target, DAY);
		assertEquals(target, dayRoom.fillOf(1), 1e-5);
		assertEquals(RoomLight.DAY_SHARE * RoomLight.outdoorLight(DAY), target, 1e-5);
		// the lift is a third of the light a sunlit floor outdoors gets
		assertEquals(0.42f * RoomLight.outdoorLight(DAY), target, 1e-5);
		assertTrue(target > 2 * RoomLight.TARGET_NIGHT);
		// at dusk it eases to the night's, never under it, and at dawn the same
		float last = target;
		for (float alt = DAY; alt >= NIGHT; alt -= 0.5f)
		{
			float t = RoomLight.target(alt, 1);
			assertTrue(t >= RoomLight.TARGET_NIGHT - 1e-6f);
			assertTrue("smooth at " + alt, Math.abs(t - last) < 0.05f);
			last = t;
		}
		assertEquals(RoomLight.TARGET_NIGHT, last, 1e-6);
	}

	@Test
	public void aRoomLitByTorchesGetsNoFill()
	{
		RoomLight l = lit(1.5f);
		assertEquals(0, night(l), 0);
		RoomLight day = lit(1.5f);
		day.update(0, RoomLight.target(DAY, 1), DAY);
		assertTrue("by day too " + day.fillOf(1), day.fillOf(1) < 0.02f);
	}

	@Test
	public void aHotSpotDoesNotHideTheDarkRestOfARoom()
	{
		// a very bright lamp low over one corner of a 6 by 6 room: that tile counts for no more than the cap
		RoomMap room = room();
		float[] scores = RoomLight.lampScores(room, walled(), List.of(lamp(20.5f, 20.5f, 1000, 1.2f)));
		assertTrue("score " + scores[1], scores[1] <= RoomLight.TILE_CAP * 4 / 36 + 1e-4f);
		RoomLight l = new RoomLight();
		l.setRooms(room);
		l.setLamps(scores);
		assertTrue(night(l) > 0.5f * RoomLight.TARGET_NIGHT);
	}

	@Test
	public void aWideOpenDoorwayLiftsARoomLessByDayThanAClosedOne()
	{
		float target = RoomLight.target(DAY, 1);
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
		float outdoors = RoomLight.outdoorLight(DAY), target = RoomLight.target(DAY, 1);
		float skyLuminance = RoomLight.skyLuminance(DAY);
		RoomLight windowless = baked(0, 4);
		windowless.update(0, target, DAY);
		RoomLight torch = baked(9, 4);
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
	public void theFillFollowsTheLampsAndBrightness()
	{
		RoomLight l = light();
		assertEquals(RoomLight.TARGET_NIGHT, night(l), 1e-5);
		// the room's lamps come on
		l.setLamps(new float[]{0, 2});
		l.update(10, RoomLight.target(NIGHT, 1), NIGHT);
		assertEquals(0, l.fillOf(1), 1e-3);
		// a door shuts them out
		l.setLamps(new float[]{0, 0});
		l.update(10, RoomLight.target(NIGHT, 1), NIGHT);
		assertEquals(RoomLight.TARGET_NIGHT, l.fillOf(1), 1e-3);
		// brightness scales the target, by day and at night; 0 turns the fill off
		l.update(10, RoomLight.target(NIGHT, 0.5f), NIGHT);
		assertEquals(RoomLight.TARGET_NIGHT / 2, l.fillOf(1), 1e-3);
		assertEquals(2 * RoomLight.target(DAY, 1), RoomLight.target(DAY, 2), 1e-6);
		assertEquals(0, RoomLight.target(DAY, 0), 0);
		assertEquals(0, RoomLight.target(NIGHT, 0), 0);
	}

	@Test
	public void fillsEaseInAndTheSummaryCountsRooms()
	{
		RoomLight l = light();
		l.update(0, RoomLight.target(NIGHT, 1), NIGHT);
		l.setLamps(new float[]{0, 2});
		float target = RoomLight.target(NIGHT, 1);
		l.update(0.1f, target, NIGHT);
		assertTrue("part of the way", l.fillOf(1) > 0 && l.fillOf(1) < target);
		int[] s = l.summary();
		assertEquals(1, s[0]);
		assertEquals(0, s[1] + s[2]);
		RoomLight dark = light();
		dark.update(0, target, NIGHT);
		assertEquals(1, dark.summary()[2]);
	}

	// ---- the lamps' scores: a room of 6 by 6 tiles (extended 20..25) in a scene of 48, walled all round

	private static final int SCENE = 48, SCENE_OFFSET = 4;

	private static RoomMap room()
	{
		byte[][][] settings = new byte[4][SCENE][SCENE];
		for (int x = 20; x <= 25; ++x)
		{
			for (int y = 20; y <= 25; ++y)
			{
				settings[0][x][y] = BuildingDetector.TILE_FLAG_UNDER_ROOF;
			}
		}
		int[][][] heights = new int[4][SCENE + 1][SCENE + 1];
		RoomMap m = RoomMap.build(BuildingDetector.detect(settings, null), settings, heights, SCENE_OFFSET);
		m.setWalls(walled());
		return m;
	}

	/** The room's walls: west of columns 20 and 26, south of rows 20 and 26. */
	private static WallMap walled()
	{
		int local = SCENE - 2 * SCENE_OFFSET;
		int[][][] flags = new int[4][local][local];
		for (int i = 20; i <= 25; ++i)
		{
			for (int x : new int[]{20, 26})
			{
				flags[0][x - SCENE_OFFSET][i - SCENE_OFFSET] |= WallMap.LOS_WEST;
				flags[0][x - 1 - SCENE_OFFSET][i - SCENE_OFFSET] |= WallMap.LOS_EAST;
			}
			for (int y : new int[]{20, 26})
			{
				flags[0][i - SCENE_OFFSET][y - SCENE_OFFSET] |= WallMap.LOS_SOUTH;
				flags[0][i - SCENE_OFFSET][y - 1 - SCENE_OFFSET] |= WallMap.LOS_NORTH;
			}
		}
		return WallMap.build(flags, null, SCENE, SCENE_OFFSET, 1);
	}

	/** A warm lamp at an extended tile position, 100 units above the floor. */
	private static PointLight lamp(float tileX, float tileY, float strength, float radiusTiles)
	{
		return new PointLight().set((tileX - SCENE_OFFSET) * 128, -100, (tileY - SCENE_OFFSET) * 128, radiusTiles * 128,
			1, 0.58f, 0.01f, strength).onPlane(0);
	}

	/** The room with a lamp of this strength in its middle (none for 0). */
	private static RoomLight baked(float strength, float radiusTiles)
	{
		RoomMap room = room();
		RoomLight l = new RoomLight();
		l.setRooms(room);
		l.setLamps(RoomLight.lampScores(room, walled(), strength > 0 ? List.of(lamp(23, 23, strength, radiusTiles))
			: List.of()));
		return l;
	}

	@Test
	public void aRoomWithATorchInItIsNotFilledAndAnEmptyOneIs()
	{
		float target = RoomLight.TARGET_NIGHT;
		RoomLight torch = baked(9, 4);
		torch.update(0, target, NIGHT);
		assertTrue("score " + torch.lightScore(1), torch.lightScore(1) > target);
		// a twentieth at most: the floor takes the lamp at its real slant now (it was a flat half before), so the score
		// sits just past the target, in the knee
		assertEquals(0, torch.fillOf(1), 0.05f * target);
		RoomLight empty = baked(0, 4);
		empty.update(0, target, NIGHT);
		assertEquals(target, empty.fillOf(1), 1e-5);
		// by day: the hearth's room needs about nothing of the day's target, the empty one all of it
		float day = RoomLight.target(DAY, 1);
		torch.update(10, day, DAY);
		empty.update(10, day, DAY);
		System.out.println("room light numbers: hearth room score " + torch.lightScore(1) + ", day target " + day + ", hearth fill "
			+ torch.fillOf(1) + ", windowless fill " + empty.fillOf(1));
		// under two thirds of the target (0.6 with the light map's flat half for the floor's slant)
		assertTrue("hearth by day " + torch.fillOf(1), torch.fillOf(1) < 0.65f * day);
		assertEquals(day, empty.fillOf(1), 1e-5);
	}

	@Test
	public void aLampBehindTheWallDoesNotCountForTheRoomAndOneInADoorwayDoes()
	{
		// east of the room's east wall, three tiles in reach of its floor
		float[] behind = RoomLight.lampScores(room(), walled(), List.of(lamp(27, 23, 9, 4)));
		assertEquals(0, behind[1], 1e-6);
		// the same lamp inside, by the wall
		float[] inside = RoomLight.lampScores(room(), walled(), List.of(lamp(25.5f, 23, 9, 4)));
		assertTrue("inside " + inside[1], inside[1] > 0.1f);
	}
}
