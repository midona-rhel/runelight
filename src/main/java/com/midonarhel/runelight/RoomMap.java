package com.midonarhel.runelight;

import java.nio.FloatBuffer;

/**
 * Which building room every tile of the scene belongs to, so point lights stay in the room they are in instead of
 * shining through walls (rooms.glsl), and the volumetric fog stays out of interiors. Rooms come from
 * BuildingDetector; 0 is outdoors. An outdoor tile directly across an opening (a doorway, an open door: no wall
 * between them: sight is not blocked) from a room is that room's porch, where its light spills out a little; the
 * same across an opening between two rooms or from outdoors into a room, for the lights the walls don't decide
 * (LightOcclusion: those cast against the walls pass open doors on their own). Where a floor has a hole (a
 * stairwell, a pit) the rooms above and below it are linked there: the room across the hole is shown in full.
 *
 * GL-free so it can be tested; LightClusters uploads {@link #texels} after the lights in the light buffer. Arrays
 * are [plane][x][y] in extended scene tiles; planes are logical planes as in BuildingDetector.
 */
final class RoomMap
{
	static final int PLANES = 4;
	// room ids are packed in 12 bits; ANY marks a light that reaches every room
	static final int ANY = 4095;
	static final int MAX_ROOM = ANY - 1;
	// a light this close to a tile edge (world units) whose far side is another room or outdoors, with no wall
	// between, lights both sides
	static final float EDGE_REACH = 40;
	static final float TILE = 128;
	// texels in front of the map: the header (rooms.glsl)
	static final int HEADER_TEXELS = 1;
	static final int TEXELS_PER_TILE = 3;

	// the porch id of the outdoors: what lies across an opening from a room tile that has no room beside it
	static final int OUTSIDE = ANY;
	// added to the porch id roomAt returns when it is an opening (light spills through it) rather than a hole in a floor
	static final int SOFT = 4096;
	// tiles a light spills in through an opening, in a straight line from it
	static final int PORCH_DEPTH = 2;

	final int size;
	// extended tiles before local tile 0
	final int offset;
	final short[] rooms;
	// the room (OUTSIDE for the outdoors) whose light spills onto the tile through an opening; set by setWalls
	final short[] porch;
	// room tiles: the room across a hole in the floor above or below; set by linkStoreys
	final short[] link;
	final float[] floors;
	// under a roof by the map's flags, whether or not a room was found there: lights there are covered (rooms.glsl)
	final boolean[] roofed;
	// tiles a diagonal wall crosses with another room, or the outdoors, on its far side: which half (WallMap.half)
	// the tile's room has, plus 1 (0: the whole tile), and the room of the other half (0 outdoors)
	final byte[] half;
	final short[] across;
	// a bit per plane: the tile has a floor there (linkStoreys); null until known
	private byte[] filled;
	private boolean[] linked = new boolean[0];
	private int version;
	// the pairs of neighboring tiles in different rooms (or a room and the outdoors), as tile index * 2 + (0 east of
	// it, 1 north of it): the only places an opening can be; the rooms never change after build
	private int[] seams;
	// the openings' porches as found, and the tiles they were written to, to tell what changed
	private final short[] found;
	private int[] touched = new int[16], previous = new int[16];
	private int touchedCount, previousCount;
	// the walls the rooms were split along, null when unknown: a light on a wall belongs to the side it hangs on,
	// and openings are the edges with none
	private WallMap walls;

	private RoomMap(int size, int offset)
	{
		this.size = size;
		this.offset = offset;
		rooms = new short[PLANES * size * size];
		porch = new short[PLANES * size * size];
		found = new short[PLANES * size * size];
		link = new short[PLANES * size * size];
		floors = new float[PLANES * size * size];
		roofed = new boolean[PLANES * size * size];
		half = new byte[PLANES * size * size];
		across = new short[PLANES * size * size];
	}

	int index(int plane, int x, int y)
	{
		return (plane * size + x) * size + y;
	}

	/** Changes whenever the texels do. */
	int getVersion()
	{
		return version;
	}

	/**
	 * @param settings the scene's extended tile settings, [level][x][y]
	 * @param heights the scene's extended tile heights, [level][x][y] corners
	 * @param offset extended tiles before local tile 0
	 */
	static RoomMap build(BuildingDetector.Result r, byte[][][] settings, int[][][] heights, int offset)
	{
		if (settings == null || settings.length == 0)
		{
			return null;
		}
		int size = settings[0].length;
		RoomMap m = new RoomMap(size, offset);
		for (int i = 0; i < r.rooms.size(); ++i)
		{
			BuildingDetector.Room room = r.rooms.get(i);
			if (room.plane >= PLANES)
			{
				continue;
			}
			short id = (short) (i + 1 > MAX_ROOM ? 0 : i + 1);
			for (int t : room.tiles)
			{
				int x = t >>> 16, y = t & 0xFFFF;
				if (x < size && y < size)
				{
					m.rooms[m.index(room.plane, x, y)] = id;
				}
			}
		}
		for (int i = 0; i < r.rooms.size(); ++i)
		{
			BuildingDetector.Room room = r.rooms.get(i);
			if (room.plane >= PLANES)
			{
				continue;
			}
			short id = (short) (i + 1 > MAX_ROOM ? 0 : i + 1);
			for (int c : room.cut)
			{
				int t = c >>> 1, x = t >>> 16, y = t & 0xFFFF;
				if (id == 0 || x >= size || y >= size)
				{
					continue;
				}
				int k = m.index(room.plane, x, y);
				if (m.rooms[k] == 0 || m.rooms[k] == id)
				{
					m.rooms[k] = id;
					m.half[k] = (byte) ((c & 1) + 1);
				}
				else
				{
					m.across[k] = id;
				}
			}
		}
		for (int plane = 0; plane < PLANES; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					m.floors[m.index(plane, x, y)] = floorHeight(settings, heights, plane, x, y);
					if (plane < settings.length && x < settings[0].length && y < settings[0][x].length)
					{
						int level = BuildingDetector.mapLevel(settings, plane, x, y);
						m.roofed[m.index(plane, x, y)] = level < settings.length
							&& (settings[level][x][y] & BuildingDetector.TILE_FLAG_UNDER_ROOF) != 0;
					}
				}
			}
		}
		m.findSeams();
		m.version = 1;
		return m;
	}

	/**
	 * Follow the scene's walls: doors open and close. Openings are the seams between rooms (or a room and the
	 * outdoors) with no wall on them; a light spills through them onto the tiles beyond.
	 *
	 * @return whether the porches changed
	 */
	boolean setWalls(WallMap walls)
	{
		if (walls != this.walls && (walls == null || this.walls == null || !walls.sameWalls(this.walls)))
		{
			// the wall texels follow the walls: doors open and close
			version++;
		}
		this.walls = walls;
		touchedCount = 0;
		for (int s : seams)
		{
			int i = s >> 1;
			int plane = i / (size * size), x = i / size % size, y = i % size;
			boolean east = (s & 1) == 0;
			if (walls == null || walls.edge(plane, x, y, east ? WallMap.EAST : WallMap.NORTH))
			{
				continue;
			}
			int j = east ? i + size : i + 1;
			spill(plane, i, j, east ? WallMap.EAST : WallMap.NORTH);
			spill(plane, j, i, east ? WallMap.WEST : WallMap.SOUTH);
		}
		boolean changed = false;
		for (int k = 0; k < previousCount; ++k)
		{
			if (found[previous[k]] == 0 && porch[previous[k]] != 0)
			{
				porch[previous[k]] = 0;
				changed = true;
			}
		}
		for (int k = 0; k < touchedCount; ++k)
		{
			int i = touched[k];
			if (porch[i] != found[i])
			{
				porch[i] = found[i];
				changed = true;
			}
			found[i] = 0;
		}
		int[] swap = previous;
		previous = touched;
		touched = swap;
		previousCount = touchedCount;
		if (changed)
		{
			version++;
		}
		return changed;
	}

	/** The light of the room (or the outdoors) at tile from spills onto tile to and on in line with it, through the opening. */
	private void spill(int plane, int from, int to, int side)
	{
		short id = rooms[from] != 0 ? rooms[from] : (short) OUTSIDE;
		int dx = side == WallMap.EAST ? 1 : side == WallMap.WEST ? -1 : 0;
		int dy = side == WallMap.NORTH ? 1 : side == WallMap.SOUTH ? -1 : 0;
		int x = to / size % size, y = to % size;
		for (int k = 0; k < PORCH_DEPTH; ++k, x += dx, y += dy)
		{
			// on through the same room, with no wall in the way
			if (k > 0 && (x < 0 || y < 0 || x >= size || y >= size || rooms[index(plane, x, y)] != rooms[index(plane, x - dx, y - dy)]
				|| walls.edge(plane, x - dx, y - dy, side)))
			{
				return;
			}
			int t = index(plane, x, y);
			// the first light to reach a tile keeps it, but another room's beats the outdoors
			if (found[t] == 0 || found[t] == OUTSIDE && id != OUTSIDE)
			{
				if (found[t] == 0)
				{
					if (touchedCount == touched.length)
					{
						touched = java.util.Arrays.copyOf(touched, touchedCount * 2);
					}
					touched[touchedCount++] = t;
				}
				found[t] = id;
			}
		}
	}

	/**
	 * Link the rooms above and below every hole in a floor, so lights shine through stairwells and pits and the
	 * storeys they connect, and remember which tiles have a floor (for lights seen through the openings). A run of
	 * floorless tiles between a room and the room over it is a hole only when it sits in a floor: at least half of
	 * the tiles around it have one. Floorless tiles under a roof that stands over open ground (a courtyard, the
	 * unfloored top of a storey) are not holes.
	 *
	 * @param filled per extended tile (x * size + y), a bit per plane that has a floor tile there
	 */
	void linkStoreys(byte[] filled)
	{
		this.filled = filled;
		java.util.Arrays.fill(link, (short) 0);
		boolean[] linkedRooms = new boolean[ANY];
		boolean any = false;
		if (filled != null && filled.length >= size * size)
		{
			int[] queue = new int[size * size];
			boolean[] seen = new boolean[size * size];
			for (int plane = 1; plane < PLANES; ++plane)
			{
				java.util.Arrays.fill(seen, false);
				for (int x = 0; x < size; ++x)
				{
					for (int y = 0; y < size; ++y)
					{
						if (seen[x * size + y] || !isHole(plane, x, y))
						{
							continue;
						}
						// the run of holes around this one, and how much of its surroundings is floor
						int head = 0, tail = 0, around = 0, floored = 0;
						queue[tail++] = x * size + y;
						seen[x * size + y] = true;
						while (head < tail)
						{
							int t = queue[head++];
							int tx = t / size, ty = t % size;
							for (int d = 0; d < 4; ++d)
							{
								int nx = tx + (d == 0 ? 1 : d == 1 ? -1 : 0);
								int ny = ty + (d == 2 ? 1 : d == 3 ? -1 : 0);
								if (nx < 0 || ny < 0 || nx >= size || ny >= size)
								{
									continue;
								}
								if (isHole(plane, nx, ny))
								{
									if (!seen[nx * size + ny])
									{
										seen[nx * size + ny] = true;
										queue[tail++] = nx * size + ny;
									}
								}
								else
								{
									around++;
									if ((filled[nx * size + ny] >> plane & 1) != 0)
									{
										floored++;
									}
								}
							}
						}
						if (floored * 2 < around)
						{
							continue;
						}
						for (int k = 0; k < tail; ++k)
						{
							int tx = queue[k] / size, ty = queue[k] % size;
							int low = index(plane - 1, tx, ty), high = index(plane, tx, ty);
							short below = rooms[low], above = rooms[high];
							link[low] = above;
							link[high] = below;
							linkedRooms[below] = true;
							linkedRooms[above] = true;
							any = true;
						}
					}
				}
			}
		}
		linked = any ? linkedRooms : new boolean[0];
		version++;
	}

	// a tile of a plane with no floor between its room and a different room right over it
	private boolean isHole(int plane, int x, int y)
	{
		if ((filled[x * size + y] >> plane & 1) != 0)
		{
			return false;
		}
		short below = rooms[index(plane - 1, x, y)], above = rooms[index(plane, x, y)];
		return below != 0 && above != 0 && below != above;
	}

	/** Whether a floor lies at a point of a plane. True when unknown. */
	boolean floorAt(int plane, float x, float z)
	{
		int tx = (int) Math.floor(x / TILE) + offset;
		int ty = (int) Math.floor(z / TILE) + offset;
		if (filled == null || plane < 0 || plane >= PLANES || tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return true;
		}
		return (filled[tx * size + ty] >> plane & 1) != 0;
	}

	/**
	 * Whether a light at a point of a plane can be seen from another plane through an opening: nothing floors it
	 * over (below the viewer), or nothing floors it in (above the viewer), or its room has a hole in its floor or
	 * ceiling.
	 */
	boolean seenFrom(int lightPlane, int viewPlane, float x, float z)
	{
		if (lightPlane == viewPlane || lightPlane < 0 || lightPlane >= PLANES)
		{
			return true;
		}
		if (lightPlane < viewPlane ? lightPlane + 1 >= PLANES || !floorAt(lightPlane + 1, x, z) : !floorAt(lightPlane, x, z))
		{
			return true;
		}
		int tx = (int) Math.floor(x / TILE) + offset;
		int ty = (int) Math.floor(z / TILE) + offset;
		if (tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return false;
		}
		return isLinked(rooms[index(lightPlane, tx, ty)]);
	}

	/** Whether a room has a hole in its floor or ceiling. */
	boolean isLinked(int room)
	{
		return room > 0 && room < linked.length && linked[room];
	}

	private static float floorHeight(byte[][][] settings, int[][][] heights, int plane, int x, int y)
	{
		if (heights == null || heights.length == 0)
		{
			return -240f * plane;
		}
		int level = plane < settings.length ? BuildingDetector.mapLevel(settings, plane, x, y) : plane;
		int[][] h = heights[Math.min(level, heights.length - 1)];
		if (x + 1 >= h.length || y + 1 >= h[x].length)
		{
			return h[Math.min(x, h.length - 1)][Math.min(y, h[0].length - 1)];
		}
		return (h[x][y] + h[x + 1][y] + h[x][y + 1] + h[x + 1][y + 1]) * 0.25f;
	}

	private void findSeams()
	{
		int[] out = new int[16];
		int n = 0;
		for (int plane = 0; plane < PLANES; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int i = index(plane, x, y);
					for (int d = 0; d < 2; ++d)
					{
						boolean east = d == 0;
						if ((east ? x + 1 >= size : y + 1 >= size) || rooms[i] == rooms[east ? i + size : i + 1])
						{
							continue;
						}
						if (n == out.length)
						{
							out = java.util.Arrays.copyOf(out, n * 2);
						}
						out[n++] = i * 2 + d;
					}
				}
			}
		}
		seams = java.util.Arrays.copyOf(out, n);
	}

	/** The room across a hole in the floor or ceiling of a room tile, 0 for none. */
	int link(int plane, int x, int y)
	{
		if (plane < 0 || plane >= PLANES || x < 0 || y < 0 || x >= size || y >= size)
		{
			return 0;
		}
		return link[index(plane, x, y)];
	}

	/** The room of a tile, 0 outdoors or outside the map. */
	int room(int plane, int x, int y)
	{
		if (plane < 0 || plane >= PLANES || x < 0 || y < 0 || x >= size || y >= size)
		{
			return 0;
		}
		return rooms[index(plane, x, y)];
	}

	/**
	 * The room code of a point light at local world position (x, y, z) on a logical plane, as LightClusters stores it
	 * in the light's fourth color channel: (1 + a + b * 4096), positive when both rooms are building rooms (an
	 * indoor light, left out of the fog and the weather), negative otherwise. a is the room it is in, b the room
	 * across a nearby tile edge with no wall on it (a wall light shines on its own side only), or a again.
	 *
	 * @param plane the light's logical plane, -1 when unknown: it then reaches everything
	 */
	float lightCode(float x, float y, float z, int plane)
	{
		int tx = (int) Math.floor(x / TILE) + offset;
		int ty = (int) Math.floor(z / TILE) + offset;
		if (plane < 0 || plane >= PLANES || tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return code(ANY, ANY);
		}
		int a = rooms[index(plane, tx, ty)];
		int b = a;
		float fx = x / TILE - (float) Math.floor(x / TILE);
		float fz = z / TILE - (float) Math.floor(z / TILE);
		float best = EDGE_REACH / TILE;
		for (int d = 0; d < 4; ++d)
		{
			float dist = d == 0 ? fx : d == 1 ? 1 - fx : d == 2 ? fz : 1 - fz;
			if (dist >= best)
			{
				continue;
			}
			int n = room(plane, tx + (d == 0 ? -1 : d == 1 ? 1 : 0), ty + (d == 2 ? -1 : d == 3 ? 1 : 0));
			if (n != a && (walls == null
				|| !walls.edge(plane, tx, ty, d == 0 ? WallMap.WEST : d == 1 ? WallMap.EAST : d == 2 ? WallMap.SOUTH : WallMap.NORTH)))
			{
				b = n;
				best = dist;
			}
		}
		return code(a, b);
	}

	/**
	 * Whether a light at local world position (x, z) on a logical plane is under a roof or inside a room: such a
	 * light doesn't light the tops of things open to the sky above it (light_occlusion.glsl).
	 */
	boolean covered(float x, float z, int plane)
	{
		int tx = (int) Math.floor(x / TILE) + offset;
		int ty = (int) Math.floor(z / TILE) + offset;
		if (plane < 0 || plane >= PLANES || tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return false;
		}
		int i = index(plane, tx, ty);
		return roofed[i] || rooms[i] != 0;
	}

	static float code(int a, int b)
	{
		float c = 1 + a + b * 4096f;
		return a != 0 && b != 0 && a != ANY ? c : -c;
	}

	/** The room a light with this code is in (0 outdoors, ANY for every room). */
	static int roomOf(float code)
	{
		return (int) ((Math.abs(code) - 1) % 4096);
	}

	/** Whether a light with this code is indoors: it lights only building rooms. */
	static boolean indoor(float code)
	{
		return code > 0.5f;
	}

	/** Light code for a light that ignores rooms. */
	static float anyCode()
	{
		return code(ANY, ANY);
	}

	/**
	 * How much of a light with this code reaches a point in room (with near, the porch room roomAt gives, plus SOFT
	 * when it is an opening's), as rooms.glsl decides. room -1: unknown, everything reaches it.
	 */
	static float share(float code, int room, int near, float porchShare)
	{
		float c = Math.abs(code) - 1;
		if (room < 0 || c < 0)
		{
			return 1;
		}
		int a = (int) (c % 4096), b = (int) Math.floor(c / 4096);
		if (a == ANY || a == room || b == room)
		{
			return 1;
		}
		int id = near & (SOFT - 1);
		if (id == 0 || a != id && b != id && (id != OUTSIDE || a != 0 && b != 0))
		{
			return 0;
		}
		// through an opening the light only spills; through a hole in a floor it shines in full
		return near >= SOFT ? porchShare : 1;
	}

	// as rooms.glsl: how far above a floor (world units) a point must be to be on that storey, and how far above
	// its storey's floor a point may be and still be inside the room
	static final float PLANE_MARGIN = 24;
	static final float STOREY = 420;

	/**
	 * The room (x) and porch room (y) of a point at local world position, the CPU twin of rooms.glsl's roomAt.
	 */
	int[] roomAt(float x, float y, float z)
	{
		int tx = (int) Math.floor(x / TILE) + offset;
		int ty = (int) Math.floor(z / TILE) + offset;
		if (tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return new int[]{-1, 0};
		}
		int plane = 0;
		for (int k = 1; k < PLANES; ++k)
		{
			if (y <= floors[index(k, tx, ty)] - PLANE_MARGIN)
			{
				plane = k;
			}
		}
		int i = index(plane, tx, ty);
		int room = rooms[i];
		int near = nearRoom(i);
		if (half[i] != 0 && walls != null && WallMap.halfAt(walls.get(plane, tx, ty), x / TILE + offset - tx,
			z / TILE + offset - ty) != half[i] - 1)
		{
			// the far side of a diagonal wall
			room = across[i];
			near = 0;
		}
		if (room != 0 && y < floors[i] - STOREY)
		{
			room = 0;
			near = 0;
		}
		return new int[]{room, near};
	}

	// the room across a hole in a tile's floor or ceiling, else the one whose light spills in through an opening
	// (plus SOFT), 0 for none
	private int nearRoom(int i)
	{
		return rooms[i] != 0 && link[i] != 0 ? link[i] : porch[i] != 0 ? porch[i] + SOFT : 0;
	}

	/** Texels after the header. */
	int texelCount()
	{
		return HEADER_TEXELS + size * size * TEXELS_PER_TILE;
	}

	/**
	 * Fill the buffer with the header texel (enabled, size, offset, lights use rooms) and three texels per tile: the four planes' rooms
	 * (room + porch * 4096, negative when the porch is an opening's), the four planes' floor heights (world y), and
	 * the four planes' walls: the tile's WallMap bits, plus 64 times the half (WallMap.half) the tile's room has,
	 * plus 128 times the room of the other half of a tile a diagonal wall crosses (0 outdoors; the tile's own room
	 * when both halves are in it).
	 */
	void texels(FloatBuffer out)
	{
		out.put(1).put(size).put(offset).put(1);
		for (int x = 0; x < size; ++x)
		{
			for (int y = 0; y < size; ++y)
			{
				for (int p = 0; p < PLANES; ++p)
				{
					int i = index(p, x, y);
					int near = nearRoom(i);
					float code = rooms[i] + (near & (SOFT - 1)) * 4096f;
					out.put(near >= SOFT ? -code : code);
				}
				for (int p = 0; p < PLANES; ++p)
				{
					out.put(floors[index(p, x, y)]);
				}
				for (int p = 0; p < PLANES; ++p)
				{
					int i = index(p, x, y);
					int b = walls != null ? walls.get(p, x, y) : 0;
					out.put(b + (half[i] == 0 ? 128 * rooms[i] : 64 * (half[i] - 1) + 128 * across[i]));
				}
			}
		}
	}
}
