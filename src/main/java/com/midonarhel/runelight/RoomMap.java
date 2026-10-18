package com.midonarhel.runelight;

import java.nio.FloatBuffer;

/**
 * Which building room every tile of the scene belongs to, so point lights stay in the room they are in instead of
 * shining through walls (rooms.glsl), and the volumetric fog stays out of interiors. Rooms come from
 * BuildingDetector; 0 is outdoors. An outdoor tile directly across an opening (a doorway, an open door: no wall
 * between them in the collision map) from a room is that room's porch, where its light spills out a little.
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
	// a light this close to a tile edge (world units) whose far side is another room or outdoors lights both sides:
	// wall torches and lamps hang on walls
	static final float EDGE_REACH = 40;
	static final float TILE = 128;
	// texels in front of the map: the header (rooms.glsl)
	static final int HEADER_TEXELS = 1;
	static final int TEXELS_PER_TILE = 2;

	// CollisionDataFlag
	static final int BLOCK_NORTH = 0x2, BLOCK_EAST = 0x8, BLOCK_SOUTH = 0x20, BLOCK_WEST = 0x80;
	static final int BLOCK_FULL = 0x100 | 0x40000 | 0x200000;

	final int size;
	// extended tiles before local tile 0
	final int offset;
	final short[] rooms;
	final short[] porch;
	final float[] floors;
	// under a roof by the map's flags, whether or not a room was found there: lights there are covered (rooms.glsl)
	final boolean[] roofed;
	private int version;

	private RoomMap(int size, int offset)
	{
		this.size = size;
		this.offset = offset;
		rooms = new short[PLANES * size * size];
		porch = new short[PLANES * size * size];
		floors = new float[PLANES * size * size];
		roofed = new boolean[PLANES * size * size];
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
		m.version = 1;
		return m;
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

	/**
	 * Find the openings out of every room from the collision maps: an outdoor tile next to a room tile with no wall
	 * between them becomes the room's porch. Doors open and close, so this runs every game tick; it is a pass over
	 * the scene's tiles.
	 *
	 * @param flags collision flags per plane, [plane][x][y] in local scene tiles; null entries are skipped
	 * @return whether the porches changed
	 */
	boolean updateOpenings(int[][][] flags)
	{
		boolean changed = false;
		for (int plane = 0; plane < PLANES; ++plane)
		{
			int[][] f = flags != null && plane < flags.length ? flags[plane] : null;
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int i = index(plane, x, y);
					short p = 0;
					if (f != null && rooms[i] == 0)
					{
						p = porchRoom(f, plane, x, y);
					}
					if (porch[i] != p)
					{
						porch[i] = p;
						changed = true;
					}
				}
			}
		}
		if (changed)
		{
			version++;
		}
		return changed;
	}

	private short porchRoom(int[][] f, int plane, int x, int y)
	{
		int lx = x - offset, ly = y - offset;
		if (lx < 0 || ly < 0 || lx >= f.length || ly >= f[lx].length || (f[lx][ly] & BLOCK_FULL) != 0)
		{
			return 0;
		}
		// toward each neighbor: the wall flag on this tile's side and on the neighbor's facing side
		for (int d = 0; d < 4; ++d)
		{
			int dx = d == 0 ? 1 : d == 1 ? -1 : 0;
			int dy = d == 2 ? 1 : d == 3 ? -1 : 0;
			int nx = x + dx, ny = y + dy;
			int nlx = lx + dx, nly = ly + dy;
			if (nx < 0 || ny < 0 || nx >= size || ny >= size || nlx < 0 || nly < 0 || nlx >= f.length || nly >= f[nlx].length)
			{
				continue;
			}
			short room = rooms[index(plane, nx, ny)];
			if (room == 0)
			{
				continue;
			}
			int mine = d == 0 ? BLOCK_EAST : d == 1 ? BLOCK_WEST : d == 2 ? BLOCK_NORTH : BLOCK_SOUTH;
			int theirs = d == 0 ? BLOCK_WEST : d == 1 ? BLOCK_EAST : d == 2 ? BLOCK_SOUTH : BLOCK_NORTH;
			if ((f[lx][ly] & mine) == 0 && (f[nlx][nly] & theirs) == 0)
			{
				return room;
			}
		}
		return 0;
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
	 * indoor light, left out of the fog and the weather), negative otherwise. a is the room it is in, b the room on
	 * the other side of a wall it hangs on, or a again.
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
			if (n != a)
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
	 * How much of a light with this code reaches a point in room (with porch room porch), as rooms.glsl decides.
	 * room -1: unknown, everything reaches it.
	 */
	static float share(float code, int room, int porch, float porchShare)
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
		if (room == 0 && porch != 0 && (a == porch || b == porch))
		{
			return porchShare;
		}
		return 0;
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
		if (room != 0 && y < floors[i] - STOREY)
		{
			room = 0;
		}
		return new int[]{room, porch[i]};
	}

	/** Texels after the header. */
	int texelCount()
	{
		return HEADER_TEXELS + size * size * TEXELS_PER_TILE;
	}

	/**
	 * Fill the buffer with the header texel (enabled, size, offset, lights use rooms) and two texels per tile: the four planes' rooms
	 * (room + porch * 4096) and the four planes' floor heights (world y).
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
					out.put(rooms[i] + porch[i] * 4096f);
				}
				for (int p = 0; p < PLANES; ++p)
				{
					out.put(floors[index(p, x, y)]);
				}
			}
		}
	}
}
