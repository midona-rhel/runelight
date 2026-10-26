package com.midonarhel.runelight;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OpenRoomsTest
{
	private static BuildingDetector.Result rooms(int[][] planeAndRoof)
	{
		BuildingDetector.Result r = new BuildingDetector.Result();
		r.usedRoofIds = true;
		for (int[] pr : planeAndRoof)
		{
			BuildingDetector.Room room = new BuildingDetector.Room();
			room.plane = pr[0];
			room.roofId = pr[1];
			r.rooms.add(room);
		}
		return r;
	}

	@Test
	public void hiddenRoofsOpenTheirRoomsAboveThePlayersLevel()
	{
		// room ids 1..4: ground floor under roof 7, first floor under roof 8, another building, one with no roof id
		BuildingDetector.Result r = rooms(new int[][]{{0, 7}, {1, 8}, {0, 9}, {0, 0}});
		OpenRooms open = new OpenRooms();
		Set<Integer> hidden = new HashSet<>();
		hidden.add(7);
		hidden.add(8);
		open.update(r, hidden, 0);
		assertTrue(open.isOpen(1));
		assertTrue(open.isOpen(2));
		assertFalse(open.isOpen(3));
		assertFalse(open.isOpen(4));
		assertEquals(2, open.count());

		// upstairs the roof of the ground floor is the floor over it: shown
		open.update(r, hidden, 1);
		assertFalse(open.isOpen(1));
		assertTrue(open.isOpen(2));
		assertEquals(1, open.count());
	}

	@Test
	public void roofsShownOrUnknownOpenNothing()
	{
		BuildingDetector.Result r = rooms(new int[][]{{0, 7}});
		OpenRooms open = new OpenRooms();
		open.update(r, Collections.singleton(7), 0);
		assertEquals(1, open.count());
		open.update(r, Collections.emptySet(), 0);
		assertEquals(0, open.count());
		assertFalse(open.isOpen(1));
		open.update(r, Collections.singleton(7), 0);
		r.usedRoofIds = false;
		open.update(rooms(new int[][]{{0, 7}}), null, 0);
		assertEquals(0, open.count());
		assertFalse(open.isOpen(0));
		assertFalse(open.isOpen(RoomMap.ANY));
	}
}
