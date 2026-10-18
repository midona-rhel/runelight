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
	// rooms up to this size (tiles, either side) get a single light; larger ones are split into cells this size
	static final int CELL = 6;
	// a cell needs this share of its area under the roof to get its own light
	static final float MIN_CELL_FILL = 0.2f;
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
	}

	/**
	 * A light anchor in tile space: (x, y) is a fractional tile position (tile centers at +0.5), on the room tile
	 * (tileX, tileY), lighting an area about extent tiles across.
	 */
	static final class Anchor
	{
		int plane;
		int mapLevel;
		float x, y;
		int tileX, tileY;
		float extent;
		int room;
	}

	static final class Result
	{
		final List<Room> rooms = new ArrayList<>();
		final List<Anchor> anchors = new ArrayList<>();
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
					place(settings, room, result.rooms.size(), result.anchors);
					result.rooms.add(room);
				}
			}
		}
		return result;
	}

	/**
	 * Split the rooms of a detection along their interior walls: each part of a room that can't be reached from the
	 * rest without crossing a wall (WallMap edges, diagonal walls included) becomes a room of its own, and gets its
	 * own lights. A tile a diagonal wall crosses is two halves while flooding and goes to the larger of their rooms.
	 * Rooms whose tiles all stay connected come out unchanged.
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
				int node = (x * size + y) * 2;
				int l = label[node];
				if (WallMap.split(walls.get(plane, x, y)) && counts[label[node + 1]] > counts[l])
				{
					l = label[node + 1];
				}
				tileLabel[k] = l;
				tileCount[l]++;
			}
			for (int l = 1; l <= labels; ++l)
			{
				if (tileCount[l] == 0)
				{
					continue;
				}
				Room part = new Room();
				part.plane = plane;
				part.roofId = room.roofId;
				part.tiles = new int[tileCount[l]];
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
		for (int i = 0; i < result.rooms.size(); ++i)
		{
			place(settings, result.rooms.get(i), i, result.anchors);
		}
		return result;
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

	/**
	 * One light for a small room; for a large one, split its bounds into roughly CELL-sized cells and light each
	 * cell that is mostly room. Each light sits on the room tile nearest the centroid of its tiles, so L-shaped
	 * rooms do not get lights inside their walls or outside.
	 */
	private static void place(byte[][][] settings, Room room, int index, List<Anchor> out)
	{
		int w = room.maxX - room.minX + 1;
		int h = room.maxY - room.minY + 1;
		int cellsX = w <= CELL + 2 ? 1 : (w + CELL - 1) / CELL;
		int cellsY = h <= CELL + 2 ? 1 : (h + CELL - 1) / CELL;
		float cw = (float) w / cellsX, ch = (float) h / cellsY;

		int cells = cellsX * cellsY;
		float[] sumX = new float[cells], sumY = new float[cells];
		int[] count = new int[cells];
		for (int t : room.tiles)
		{
			int x = t >>> 16, y = t & 0xFFFF;
			int c = cell(x - room.minX, cw, cellsX) * cellsY + cell(y - room.minY, ch, cellsY);
			sumX[c] += x + 0.5f;
			sumY[c] += y + 0.5f;
			count[c]++;
		}

		float minCount = cells == 1 ? 1 : Math.max(MIN_TILES, cw * ch * MIN_CELL_FILL);
		for (int c = 0; c < cells; ++c)
		{
			if (count[c] < minCount)
			{
				continue;
			}
			Anchor a = new Anchor();
			a.plane = room.plane;
			a.room = index;
			float mx = sumX[c] / count[c], my = sumY[c] / count[c];
			int best = nearestTile(room, mx, my);
			a.tileX = best >>> 16;
			a.tileY = best & 0xFFFF;
			a.mapLevel = mapLevel(settings, room.plane, a.tileX, a.tileY);
			// keep the centroid when it lies on a room tile (edges included), otherwise move onto the nearest one
			if (Math.abs(mx - a.tileX - 0.5f) <= 0.5f && Math.abs(my - a.tileY - 0.5f) <= 0.5f)
			{
				a.x = mx;
				a.y = my;
			}
			else
			{
				a.x = a.tileX + 0.5f;
				a.y = a.tileY + 0.5f;
			}
			a.extent = cells == 1 ? (float) Math.sqrt(w * w + h * h) : (float) Math.sqrt(cw * cw + ch * ch);
			out.add(a);
		}
	}

	private static int cell(int offset, float size, int cells)
	{
		return Math.min(cells - 1, (int) (offset / size));
	}

	private static int nearestTile(Room room, float x, float y)
	{
		int best = room.tiles[0];
		float bestD = Float.MAX_VALUE;
		for (int t : room.tiles)
		{
			float dx = (t >>> 16) + 0.5f - x, dy = (t & 0xFFFF) + 0.5f - y;
			float d = dx * dx + dy * dy;
			if (d < bestD)
			{
				bestD = d;
				best = t;
			}
		}
		return best;
	}
}
