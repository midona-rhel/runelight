package com.midonarhel.lumina.visibility;

import java.lang.reflect.Field;

/** For the bench's door (harness/DoorFadeProbe): a wall map with one edge toggled, and the rooms built for it. */
public final class DoorAccess
{
	private static final int[] EDGE = {WallMap.NORTH, WallMap.EAST, WallMap.SOUTH, WallMap.WEST};
	private static final int[] DX = {0, 1, 0, -1}, DY = {1, 0, -1, 0};

	private DoorAccess()
	{
	}

	/** A copy of the map with the edge (0 north, 1 east, 2 south, 3 west) of an extended tile toggled, both sides. */
	public static WallMap toggle(WallMap o, int plane, int x, int y, int edge) throws ReflectiveOperationException
	{
		WallMap w = WallMap.empty(o.size, o.offset, o.scene);
		for (String f : new String[]{"bits", "see"})
		{
			byte[] a = (byte[]) field(f).get(o);
			System.arraycopy(a, 0, (byte[]) field(f).get(w), 0, a.length);
		}
		boolean[] full = (boolean[]) field("full").get(o);
		System.arraycopy(full, 0, (boolean[]) field("full").get(w), 0, full.length);
		byte[] bits = (byte[]) field("bits").get(w), see = (byte[]) field("see").get(w);
		int back = EDGE[(edge + 2) & 3], i = w.index(plane, x, y), j = w.index(plane, x + DX[edge], y + DY[edge]);
		bits[i] ^= EDGE[edge];
		see[i] ^= EDGE[edge];
		bits[j] ^= back;
		see[j] ^= back;
		return w;
	}

	public static boolean isWall(WallMap w, int plane, int x, int y, int edge)
	{
		return (w.get(plane, x, y) & EDGE[edge]) != 0;
	}

	/** The rooms as BuildingLights builds them again for new walls (its door path), null where it would keep the old. */
	public static RoomMap rooms(BuildingDetector.Result coarse, byte[][][] settings, int[][][] heights, int offset, WallMap w, byte[] floors)
	{
		if (coarse == null)
		{
			return null;
		}
		RoomMap n = RoomMap.build(BuildingDetector.split(coarse, settings, w), settings, heights, offset);
		if (n != null)
		{
			n.setWalls(w);
			n.setFloors(floors);
		}
		return n;
	}

	private static Field field(String name) throws NoSuchFieldException
	{
		Field f = WallMap.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}
}
