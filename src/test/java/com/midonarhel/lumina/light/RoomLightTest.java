package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.RoomMapTest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The interior brightness (RoomLight): one level for every room, from the setting and the time of day; nothing that
 * lights a room (its lamps, the sky through its doorway) changes it.
 */
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

	@Test
	public void theLevelIsTheSettingForTheTimeOfDay()
	{
		assertEquals(RoomLight.TARGET_NIGHT, RoomLight.target(NIGHT, 1), 1e-6);
		float day = RoomLight.target(DAY, 1);
		assertEquals(RoomLight.DAY_SHARE * RoomLight.outdoorLight(DAY), day, 1e-5);
		assertTrue(day > 2 * RoomLight.TARGET_NIGHT);
		// at dusk it eases to the night's, never under it
		float last = day;
		for (float alt = DAY; alt >= NIGHT; alt -= 0.5f)
		{
			float t = RoomLight.target(alt, 1);
			assertTrue(t >= RoomLight.TARGET_NIGHT - 1e-6f);
			assertTrue("smooth at " + alt, Math.abs(t - last) < 0.05f);
			last = t;
		}
		// the setting scales it, and 0 is none
		assertEquals(2 * day, RoomLight.target(DAY, 2), 1e-5);
		assertEquals(0.5f * RoomLight.TARGET_NIGHT, RoomLight.target(NIGHT, 0.5f), 1e-6);
		assertEquals(0, RoomLight.target(NIGHT, 0), 0);
	}

	@Test
	public void everyRoomIsFilledToTheLevel()
	{
		for (float level : new float[]{RoomLight.target(NIGHT, 1), RoomLight.target(DAY, 1), RoomLight.target(DAY, 3), 0})
		{
			RoomLight l = light();
			l.update(0, level);
			assertEquals(level, l.fillOf(1), 0);
			assertEquals(level, l.table()[4], 0);
		}
	}

	@Test
	public void theFillEasesToANewLevel()
	{
		RoomLight l = light();
		l.update(0, RoomLight.TARGET_NIGHT);
		float day = RoomLight.target(DAY, 1);
		int version = l.version();
		l.update(0.1f, day);
		float f = l.fillOf(1);
		assertTrue("partway " + f, f > RoomLight.TARGET_NIGHT && f < day);
		assertTrue(l.version() != version);
		for (int i = 0; i < 100; ++i)
		{
			l.update(0.1f, day);
		}
		assertEquals(day, l.fillOf(1), 0);
		version = l.version();
		l.update(0.1f, day);
		assertEquals("no change, no new version", version, l.version());
	}
	@Test
	public void aDoorKeepsTheRoomsFillsInsteadOfStartingThemOver()
	{
		// the level is moving (dusk): the rooms built again for a door keep the fill they had and ease on from it, where
		// a new scene's rooms start at the level at once
		float night = RoomLight.target(NIGHT, 1), day = RoomLight.target(DAY, 1);
		RoomLight door = light();
		door.update(0, day);
		door.update(0.1f, night);
		float before = door.fillOf(1);
		assertTrue("easing " + before, before > night && before < day);
		door.setRooms(RoomMapTest.house(new int[4][LOCAL][LOCAL]), true);
		assertEquals(before, door.fillOf(1), 1e-6);
		door.update(1 / 60f, night);
		assertTrue("eases on, no restart: " + door.fillOf(1), door.fillOf(1) <= before && door.fillOf(1) > night);
		RoomLight scene = light();
		scene.update(0, day);
		scene.update(0.1f, night);
		scene.setRooms(RoomMapTest.house(new int[4][LOCAL][LOCAL]), false);
		scene.update(1 / 60f, night);
		assertEquals(night, scene.fillOf(1), 1e-6);
	}
}
