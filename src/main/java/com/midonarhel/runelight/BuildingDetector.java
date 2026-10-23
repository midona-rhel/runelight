package com.midonarhel.runelight;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds building interiors in a scene from the game's own tile data, without touching the client, so it can be
 * tested on synthetic grids.
 *
 * A tile is inside a building when the map marks it as under a roof (TILE_FLAG_UNDER_ROOF in the tile settings,
 * the flag the client uses to hide upper floors when you walk in). Connected under-roof tiles on one plane with
 * the same roof id form a room. The roof removal plugin ORs the flag into whole override areas, so the roof ids
 * (getRoofs()[level], the id of the roof over a tile on that level) are used to split neighbouring buildings and
 * to reject flagged tiles that have nothing overhead, as long as the scene's roof ids look consistent with the
 * flags; huge components are dropped either way.
 *
 * Arrays are indexed [level][x][y] in extended scene coordinates; levels are map levels as stored. Planes in
 * the results are logical planes (the plane a player standing there reports): on bridge tiles the data of map
 * level p + 1 belongs to plane p.
 */
final class BuildingDetector
{
	static final int TILE_FLAG_BRIDGE = 2;
	static final int TILE_FLAG_UNDER_ROOF = 4;

	// rooms smaller than this are stray flags or tiny canopies
	static final int MIN_TILES = 3;
	// larger components come from roof removal overrides flagging whole regions, not real buildings
	static final int MAX_TILES = 2000;
	// roof ids are trusted when at least this share of flagged tiles have one; low, because flags ORed in after
	// the roofs were built can cover more tiles than the real buildings
	static final float ROOF_ID_AGREEMENT = 0.1f;

	static final class Room
	{
		int plane;
		int roofId;
		int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
		/**
		 * Tiles packed as x << 16 | y.
		 */
		int[] tiles;
		/**
		 * Tiles a diagonal wall crosses of which the room has one half only, packed as (x << 16 | y) << 1 | half
		 * (WallMap.half): the room's tiles where the other half is another room or outdoors, and tiles of another
		 * room whose other half is this room's.
		 */
		int[] cut = new int[0];
	}

	static final class Result
	{
		final List<Room> rooms = new ArrayList<>();
		boolean usedRoofIds;
		int rejectedSmall, rejectedHuge;
	}

	private BuildingDetector()
	{
	}

	static Result detect(byte[][][] settings, int[][][] roofs)
	{
		Result result = new Result();
		if (settings == null || settings.length == 0)
		{
			return result;
		}
		int levels = settings.length;
		int sizeX = settings[0].length;
		int sizeY = settings[0][0].length;

		result.usedRoofIds = roofIdsAgree(settings, roofs, levels, sizeX, sizeY);
		int[][][] rids = result.usedRoofIds ? roofs : null;

		boolean[] seen = new boolean[sizeX * sizeY];
		int[] queue = new int[sizeX * sizeY];
		for (int plane = 0; plane < levels; ++plane)
		{
			java.util.Arrays.fill(seen, false);
			for (int x = 0; x < sizeX; ++x)
			{
				for (int y = 0; y < sizeY; ++y)
				{
					if (seen[x * sizeY + y] || roofAt(settings, rids, plane, x, y) < 0)
					{
						continue;
					}
					Room room = flood(settings, rids, plane, x, y, sizeX, sizeY, seen, queue);
					int n = room.tiles.length;
					if (n < MIN_TILES)
					{
						result.rejectedSmall++;
						continue;
					}
					if (n > MAX_TILES)
					{
						result.rejectedHuge++;
						continue;
					}
					result.rooms.add(room);
				}
			}
		}
		return result;
	}

	/**
	 * Split the rooms of a detection along their walls: each part of a room that can't be reached from the rest
	 * without crossing a wall (WallMap edges: what blocks sight, closed doors included, open doors not; low walls,
	 * fences and gates are no walls; diagonal walls included) becomes a room of its own. A
	 * tile a diagonal wall crosses is two halves while flooding and goes to the larger of their rooms. Rooms whose
	 * tiles all stay connected come out unchanged.
	 *
	 * The roof flags often run a tile or two past a building's outer wall (eaves, overhangs). Such a part, cut off by
	 * the wall and open to the ground on its other sides, is no room: of the parts a room splits into, those at most
	 * half the size of the largest are dropped when more of their edges open onto outdoor tiles than onto walls.
	 */
	static Result split(Result coarse, byte[][][] settings, WallMap walls)
	{
		if (walls == null || coarse.rooms.isEmpty())
		{
			return coarse;
		}
		Result result = new Result();
		result.usedRoofIds = coarse.usedRoofIds;
		result.rejectedSmall = coarse.rejectedSmall;
		result.rejectedHuge = coarse.rejectedHuge;
		int size = walls.size;
		// the coarse room (+1) of every tile on the plane being split
		int[] owner = new int[size * size];
		// component label per tile half, 0 unlabeled
		int[] label = new int[size * size * 2];
		int[] queue = new int[size * size * 2];
		for (int ri = 0; ri < coarse.rooms.size(); ++ri)
		{
			Room room = coarse.rooms.get(ri);
			int plane = room.plane;
			for (int t : room.tiles)
			{
				int x = t >>> 16, y = t & 0xFFFF;
				if (x < size && y < size)
				{
					owner[x * size + y] = ri + 1;
				}
			}
			int labels = 0;
			int[] counts = new int[8];
			for (int t : room.tiles)
			{
				int x = t >>> 16, y = t & 0xFFFF;
				if (x >= size || y >= size)
				{
					continue;
				}
				int halves = WallMap.split(walls.get(plane, x, y)) ? 2 : 1;
				for (int h = 0; h < halves; ++h)
				{
					int node = (x * size + y) * 2 + h;
					if (label[node] != 0)
					{
						continue;
					}
					if (++labels == counts.length)
					{
						counts = java.util.Arrays.copyOf(counts, labels * 2);
					}
					counts[labels] = flood(walls, plane, node, labels, ri + 1, owner, label, queue);
				}
			}
			if (labels <= 1)
			{
				result.rooms.add(room);
				clear(room, size, owner, label);
				continue;
			}
			// tiles per label: a split tile goes to the larger of its halves' rooms
			int[] tileCount = new int[labels + 1];
			int[] tileLabel = new int[room.tiles.length];
			for (int k = 0; k < room.tiles.length; ++k)
			{
				int t = room.tiles[k];
				int x = t >>> 16, y = t & 0xFFFF;
				if (x >= size || y >= size)
				{
					continue;
				}
				int l = partOf(walls, plane, x, y, label, counts);
				tileLabel[k] = l;
				tileCount[l]++;
			}
			boolean[] spill = spill(walls, plane, room, ri + 1, tileLabel, tileCount, owner, label, counts);
			// the halves of the tiles a diagonal wall crosses between two parts, for each part that is kept
			int[][] cuts = new int[labels + 1][];
			int[] cutCount = new int[labels + 1];
			for (int k = 0; k < room.tiles.length; ++k)
			{
				int t = room.tiles[k];
				int x = t >>> 16, y = t & 0xFFFF;
				int node = (x * size + y) * 2;
				if (x >= size || y >= size || !WallMap.split(walls.get(plane, x, y)) || label[node] == label[node + 1])
				{
					continue;
				}
				for (int h = 0; h < 2; ++h)
				{
					int l = label[node + h];
					if (tileCount[l] == 0 || spill[l])
					{
						continue;
					}
					if (cuts[l] == null)
					{
						cuts[l] = new int[8];
					}
					else if (cutCount[l] == cuts[l].length)
					{
						cuts[l] = java.util.Arrays.copyOf(cuts[l], cutCount[l] * 2);
					}
					cuts[l][cutCount[l]++] = t << 1 | h;
				}
			}
			for (int l = 1; l <= labels; ++l)
			{
				if (tileCount[l] == 0 || spill[l])
				{
					continue;
				}
				Room part = new Room();
				part.plane = plane;
				part.roofId = room.roofId;
				part.tiles = new int[tileCount[l]];
				if (cuts[l] != null)
				{
					part.cut = java.util.Arrays.copyOf(cuts[l], cutCount[l]);
				}
				int n = 0;
				for (int k = 0; k < room.tiles.length; ++k)
				{
					if (tileLabel[k] != l)
					{
						continue;
					}
					int t = room.tiles[k];
					int x = t >>> 16, y = t & 0xFFFF;
					part.tiles[n++] = t;
					part.minX = Math.min(part.minX, x);
					part.maxX = Math.max(part.maxX, x);
					part.minY = Math.min(part.minY, y);
					part.maxY = Math.max(part.maxY, y);
				}
				result.rooms.add(part);
			}
			clear(room, size, owner, label);
		}
		return result;
	}

	/** The part of a tile after the split: a tile a diagonal wall crosses goes to the larger of its halves' parts. */
	private static int partOf(WallMap walls, int plane, int x, int y, int[] label, int[] counts)
	{
		int node = (x * walls.size + y) * 2;
		int l = label[node];
		if (WallMap.split(walls.get(plane, x, y)) && counts[label[node + 1]] > counts[l])
		{
			l = label[node + 1];
		}
		return l;
	}

	/**
	 * The parts of a split room that are roof flags spilling past an outer wall: parts at most half the size of the
	 * largest whose edges open onto outdoor tiles (not the room's, no wall between) outnumber those on a wall.
	 */
	private static boolean[] spill(WallMap walls, int plane, Room room, int owned, int[] tileLabel, int[] tileCount,
		int[] owner, int[] label, int[] counts)
	{
		int size = walls.size;
		int parts = tileCount.length - 1;
		int largest = 1;
		for (int l = 2; l <= parts; ++l)
		{
			if (tileCount[l] > tileCount[largest])
			{
				largest = l;
			}
		}
		int[] open = new int[parts + 1], closed = new int[parts + 1];
		for (int k = 0; k < room.tiles.length; ++k)
		{
			int x = room.tiles[k] >>> 16, y = room.tiles[k] & 0xFFFF;
			if (x >= size || y >= size)
			{
				continue;
			}
			for (int d = 0; d < 4; ++d)
			{
				int side = d == 0 ? WallMap.EAST : d == 1 ? WallMap.WEST : d == 2 ? WallMap.NORTH : WallMap.SOUTH;
				int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
				int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
				boolean inside = nx >= 0 && ny >= 0 && nx < size && ny < size && owner[nx * size + ny] == owned;
				if (inside && partOf(walls, plane, nx, ny, label, counts) == tileLabel[k])
				{
					continue;
				}
				if (walls.edge(plane, x, y, side))
				{
					closed[tileLabel[k]]++;
				}
				else if (!inside)
				{
					open[tileLabel[k]]++;
				}
			}
		}
		boolean[] out = new boolean[parts + 1];
		for (int l = 1; l <= parts; ++l)
		{
			out[l] = tileCount[l] * 2 <= tileCount[largest] && open[l] > closed[l];
		}
		return out;
	}

	private static void clear(Room room, int size, int[] owner, int[] label)
	{
		for (int t : room.tiles)
		{
			int x = t >>> 16, y = t & 0xFFFF;
			if (x < size && y < size)
			{
				int i = x * size + y;
				owner[i] = 0;
				label[i * 2] = 0;
				label[i * 2 + 1] = 0;
			}
		}
	}

	/** Label every tile half reachable from start without crossing a wall or leaving the room; returns the count. */
	private static int flood(WallMap walls, int plane, int start, int id, int room, int[] owner, int[] label, int[] queue)
	{
		int size = walls.size;
		int head = 0, tail = 0;
		queue[tail++] = start;
		label[start] = id;
		while (head < tail)
		{
			int node = queue[head++];
			int tile = node >> 1, h = node & 1;
			int x = tile / size, y = tile % size;
			int b = walls.get(plane, x, y);
			for (int d = 0; d < 4; ++d)
			{
				int side = d == 0 ? WallMap.EAST : d == 1 ? WallMap.WEST : d == 2 ? WallMap.NORTH : WallMap.SOUTH;
				if (WallMap.half(b, side) != h || walls.edge(plane, x, y, side))
				{
					continue;
				}
				int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
				int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
				if (nx < 0 || ny < 0 || nx >= size || ny >= size || owner[nx * size + ny] != room)
				{
					continue;
				}
				int opposite = d == 0 ? WallMap.WEST : d == 1 ? WallMap.EAST : d == 2 ? WallMap.SOUTH : WallMap.NORTH;
				int next = (nx * size + ny) * 2 + WallMap.half(walls.get(plane, nx, ny), opposite);
				if (label[next] == 0)
				{
					label[next] = id;
					queue[tail++] = next;
				}
			}
		}
		return tail;
	}

	/**
	 * The map level holding the data of a logical plane at a tile.
	 */
	static int mapLevel(byte[][][] settings, int plane, int x, int y)
	{
		return settings.length > 1 && (settings[1][x][y] & TILE_FLAG_BRIDGE) != 0 ? plane + 1 : plane;
	}

	/**
	 * The roof id over a tile when it is inside (0 when roof ids are not used), or -1 when it is not inside.
	 */
	private static int roofAt(byte[][][] settings, int[][][] rids, int plane, int x, int y)
	{
		int level = mapLevel(settings, plane, x, y);
		if (level >= settings.length || (settings[level][x][y] & TILE_FLAG_UNDER_ROOF) == 0)
		{
			return -1;
		}
		if (rids == null || level >= rids.length - 1)
		{
			// nothing can be above the top level, so its flags are taken as they are
			return 0;
		}
		int rid = rids[level][x][y];
		return rid != 0 ? rid : -1;
	}

	/**
	 * Roof ids are only built when the client computes roofs, and whether they line up with the flags depends on
	 * the client, so check that they mostly agree before relying on them.
	 */
	private static boolean roofIdsAgree(byte[][][] settings, int[][][] roofs, int levels, int sizeX, int sizeY)
	{
		if (roofs == null || roofs.length < levels || roofs[0].length < sizeX || roofs[0][0].length < sizeY)
		{
			return false;
		}
		int flagged = 0, withId = 0;
		for (int level = 0; level < levels - 1; ++level)
		{
			for (int x = 0; x < sizeX; ++x)
			{
				for (int y = 0; y < sizeY; ++y)
				{
					if ((settings[level][x][y] & TILE_FLAG_UNDER_ROOF) != 0)
					{
						flagged++;
						if (roofs[level][x][y] != 0)
						{
							withId++;
						}
					}
				}
			}
		}
		return flagged > 0 && withId >= flagged * ROOF_ID_AGREEMENT;
	}

	private static Room flood(byte[][][] settings, int[][][] rids, int plane, int sx, int sy, int sizeX, int sizeY,
		boolean[] seen, int[] queue)
	{
		Room room = new Room();
		room.plane = plane;
		room.roofId = roofAt(settings, rids, plane, sx, sy);
		int head = 0, tail = 0;
		queue[tail++] = sx * sizeY + sy;
		seen[sx * sizeY + sy] = true;
		while (head < tail)
		{
			int i = queue[head++];
			int x = i / sizeY, y = i % sizeY;
			room.minX = Math.min(room.minX, x);
			room.maxX = Math.max(room.maxX, x);
			room.minY = Math.min(room.minY, y);
			room.maxY = Math.max(room.maxY, y);
			for (int d = 0; d < 4; ++d)
			{
				int nx = x + (d == 0 ? 1 : d == 1 ? -1 : 0);
				int ny = y + (d == 2 ? 1 : d == 3 ? -1 : 0);
				if (nx < 0 || ny < 0 || nx >= sizeX || ny >= sizeY)
				{
					continue;
				}
				int ni = nx * sizeY + ny;
				if (!seen[ni] && roofAt(settings, rids, plane, nx, ny) == room.roofId)
				{
					seen[ni] = true;
					queue[tail++] = ni;
				}
			}
		}
		room.tiles = new int[tail];
		for (int k = 0; k < tail; ++k)
		{
			room.tiles[k] = queue[k] / sizeY << 16 | queue[k] % sizeY;
		}
		return room;
	}

}
