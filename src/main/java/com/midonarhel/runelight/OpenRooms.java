package com.midonarhel.runelight;

import java.util.Set;

/**
 * The building rooms whose roof the client has hidden this frame (the player is in or under the building): open to
 * the sky, so their lamps shine up over the walls into the fog (fog_frag.glsl). A bit per room id (RoomMap), kept
 * between frames and only rebuilt when the hidden roofs, the player's level or the rooms change.
 */
final class OpenRooms
{
	// room ids are 12 bits (RoomMap.ANY)
	static final int WORDS = (RoomMap.ANY + 31) / 32;

	final int[] bits = new int[WORDS];
	private int count;
	private BuildingDetector.Result builtFor;
	private int builtLevel = -1, builtHash;

	/** Rooms that are open; 0 means none, so nothing needs testing. */
	int count()
	{
		return count;
	}

	/** Whether the room (id as in RoomMap) is open. */
	boolean isOpen(int room)
	{
		return room > 0 && room < RoomMap.ANY && (bits[room >> 5] >>> (room & 31) & 1) != 0;
	}

	/**
	 * @param rooms the rooms as RoomMap was built from them, room id i + 1 for rooms.get(i); null: none open
	 * @param hidden the roof ids the client hides
	 * @param level the player's level: only roofs on levels above it are hidden (Zone.renderOpaque)
	 */
	void update(BuildingDetector.Result rooms, Set<Integer> hidden, int level)
	{
		int hash = hidden == null ? 0 : hidden.hashCode();
		if (rooms == builtFor && level == builtLevel && hash == builtHash)
		{
			return;
		}
		builtFor = rooms;
		builtLevel = level;
		builtHash = hash;
		java.util.Arrays.fill(bits, 0);
		count = 0;
		if (rooms == null || !rooms.usedRoofIds || hidden == null || hidden.isEmpty())
		{
			return;
		}
		for (int i = 0; i < rooms.rooms.size() && i < RoomMap.MAX_ROOM; ++i)
		{
			BuildingDetector.Room room = rooms.rooms.get(i);
			// the roof of a room is drawn on the level above it
			if (room.roofId > 0 && room.plane >= level && hidden.contains(room.roofId))
			{
				int id = i + 1;
				bits[id >> 5] |= 1 << (id & 31);
				count++;
			}
		}
	}
}
