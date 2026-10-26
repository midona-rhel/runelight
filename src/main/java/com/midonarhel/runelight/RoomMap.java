package com.midonarhel.runelight;

import java.nio.FloatBuffer;

/**
 * Which building room every tile of the scene belongs to, for the volumetric fog (it stays out of interiors), the room
 * fill (RoomLight), puddles and the weather. Rooms come from BuildingDetector; 0 is outdoors. The floors' heights and
 * where there is a floor ride along, as do the walls' distances.
 *
 * GL-free so it can be tested; LightClusters uploads {@link #texels} after the lights in the light buffer. Arrays
 * are [plane][x][y] in extended scene tiles; planes are logical planes as in BuildingDetector.
 */
final class RoomMap
{
	static final int PLANES = 4;
	// room ids are packed in 12 bits; ANY is one past the largest
	static final int ANY = 4095;
	static final int MAX_ROOM = ANY - 1;
	static final float TILE = 128;
	// texels in front of the map: the header (rooms.glsl)
	static final int HEADER_TEXELS = 1;
	static final int TEXELS_PER_TILE = 5;

	final int size;
	// extended tiles before local tile 0
	final int offset;
	final short[] rooms;
	final float[] floors;
	// under a roof by the map's flags, whether or not a room was found there
	final boolean[] roofed;
	// tiles a diagonal wall crosses with another room, or the outdoors, on its far side: which half (WallMap.half)
	// the tile's room has, plus 1 (0: the whole tile), and the room of the other half (0 outdoors)
	final byte[] half;
	final short[] across;
	// a bit per plane: the tile has a floor there (setFloors); null until known
	private byte[] filled;
	private int version;
	// the walls the rooms were split along, null when unknown: the texels carry them
	private WallMap walls;

	private RoomMap(int size, int offset)
	{
		this.size = size;
		this.offset = offset;
		rooms = new short[PLANES * size * size];
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
		m.version = 1;
		return m;
	}

	/** Follow the scene's walls: doors open and close, and the walls' texels with them. */
	void setWalls(WallMap walls)
	{
		if (walls != this.walls && (walls == null || this.walls == null || !walls.sameWalls(this.walls)))
		{
			version++;
		}
		this.walls = walls;
	}

	/**
	 * Remember which tiles have a floor, for the texels' floor bit.
	 *
	 * @param filled per extended tile (x * size + y), a bit per plane that has a floor tile there
	 */
	void setFloors(byte[] filled)
	{
		this.filled = filled;
		version++;
	}

	static float floorHeight(byte[][][] settings, int[][][] heights, int plane, int x, int y)
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
	 * The room a point light at local world position is in on a logical plane, 0 outdoors or when unknown (plane -1).
	 */
	int roomOfLight(float x, float z, int plane)
	{
		int tx = (int) Math.floor(x / TILE) + offset;
		int ty = (int) Math.floor(z / TILE) + offset;
		if (plane < 0 || plane >= PLANES || tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return 0;
		}
		return rooms[index(plane, tx, ty)];
	}

	// as rooms.glsl: how far above a floor (world units) a point must be to be on that storey, and how far above
	// its storey's floor a point may be and still be inside the room
	static final float PLANE_MARGIN = 2;
	static final float STOREY = 420;

	/**
	 * The room of a point at local world position, the CPU twin of rooms.glsl's roomAt: 0 outdoors, -1 outside the map.
	 */
	int roomAt(float x, float y, float z)
	{
		int tx = (int) Math.floor(x / TILE) + offset;
		int ty = (int) Math.floor(z / TILE) + offset;
		if (tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return -1;
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
		if (half[i] != 0 && walls != null && WallMap.halfAt(walls.get(plane, tx, ty), x / TILE + offset - tx,
			z / TILE + offset - ty) != half[i] - 1)
		{
			// the far side of a diagonal wall
			room = across[i];
		}
		if (room != 0 && y < floors[i] - STOREY)
		{
			room = 0;
		}
		return room;
	}

	/** Texels after the header. */
	int texelCount()
	{
		return HEADER_TEXELS + size * size * TEXELS_PER_TILE;
	}

	/**
	 * Fill the buffer with the header texel (enabled, size, offset) and five texels per tile: the four planes' rooms,
	 * the four planes' floor heights (world y), and
	 * the four planes' walls: the tile's WallMap bits, plus 64 times the half (WallMap.half) the tile's room has,
	 * plus 128 times the room of the other half of a tile a diagonal wall crosses (0 outdoors; the tile's own room
	 * when both halves are in it), the four planes' wall distances at the tile's corners (RoomAmbient), and the four
	 * planes' puddle places ({@link #pool}).
	 */
	void texels(FloatBuffer out)
	{
		out.put(2).put(size).put(offset).put(0); // the texel is four floats
		float[] pools = pools();
		for (int x = 0; x < size; ++x)
		{
			for (int y = 0; y < size; ++y)
			{
				for (int p = 0; p < PLANES; ++p)
				{
					out.put(rooms[index(p, x, y)]);
				}
				for (int p = 0; p < PLANES; ++p)
				{
					out.put(floors[index(p, x, y)]);
				}
				for (int p = 0; p < PLANES; ++p)
				{
					int i = index(p, x, y);
					int b = walls != null ? walls.get(p, x, y) : 0;
					// Shader filtering needs both sides of cardinal boundaries in the current tile.
					if (walls != null)
					{
						for (int side = 1; side <= 8; side <<= 1)
						{
							if (walls.edge(p, x, y, side)) b |= side;
						}
					}
					// Bit 19 is floor presence; the lower 19 bits retain wall/room encoding.
					int floor = filled != null && (filled[x * size + y] & (1 << p)) != 0 ? 1 << 19 : 0;
					out.put(floor + b + (half[i] == 0 ? 128 * rooms[i] : 64 * (half[i] - 1) + 128 * across[i]));
				}
				for (int p = 0; p < PLANES; ++p)
				{
					int i = index(p, x, y);
					out.put(walls != null ? RoomAmbient.pack(walls, p, x, y) : 0);
				}
				for (int p = 0; p < PLANES; ++p)
				{
					out.put(pools[index(p, x, y)]);
				}
			}
		}
	}

	// how much lower than its neighbours (world units) a tile must lie to collect a full puddle, and the slope
	// (world units of height across a tile) from which water runs off instead
	static final float POOL_DEPTH = 24;
	static final float POOL_FLAT = 4;
	static final float POOL_STEEP = 24;

	// the most puddles a chunk (8 by 8 tiles, aligned with the world's) gets on each storey, and the least pool a tile
	// needs to get one
	static final int POOLS_PER_CHUNK = 3;
	static final float POOL_MIN = 0.2f;

	/**
	 * Every tile's {@link #pool}, by {@link #index}, but only the POOLS_PER_CHUNK likeliest tiles of each chunk and
	 * storey keep theirs: the puddles form there, always the same tiles. Ties (a run of wall) go by a hash of the
	 * chunk's heights and the tile's place in it, which stay the same whenever the area is loaded.
	 */
	float[] pools()
	{
		float[] out = new float[PLANES * size * size];
		float[] score = new float[64], value = new float[64];
		int[] tile = new int[64];
		for (int p = 0; p < PLANES; ++p)
		{
			// local tile 0 is a chunk corner `offset` extended tiles in
			for (int cx = Math.floorMod(offset, 8) - 8; cx < size; cx += 8)
			{
				for (int cy = Math.floorMod(offset, 8) - 8; cy < size; cy += 8)
				{
					int n = 0, seed = 0;
					for (int t = 0; t < 64; ++t)
					{
						int x = cx + (t >> 3), y = cy + (t & 7);
						if (x >= 0 && y >= 0 && x < size && y < size)
						{
							seed = seed * 31 + Math.round(floors[index(p, x, y)]);
						}
					}
					for (int t = 0; t < 64; ++t)
					{
						int x = cx + (t >> 3), y = cy + (t & 7);
						if (x < 0 || y < 0 || x >= size || y >= size)
						{
							continue;
						}
						float v = pool(p, x, y);
						if (v >= POOL_MIN)
						{
							int h = (seed ^ t * 0x9E3779B9) * 0x85EBCA6B;
							score[n] = v + ((h >>> 8) & 0xFFFF) * (0.05f / 0xFFFF);
							value[n] = v;
							tile[n++] = index(p, x, y);
						}
					}
					for (int k = 0; k < POOLS_PER_CHUNK && k < n; ++k)
					{
						int best = 0;
						for (int j = 1; j < n; ++j)
						{
							if (score[j] > score[best]) best = j;
						}
						out[tile[best]] = value[best];
						score[best] = -1;
					}
				}
			}
		}
		return out;
	}

	/**
	 * Where rain water collects on a tile of a storey, 0 to 1: in hollows lower than the tiles around them, and along
	 * walls, on level ground only (weather_ground.glsl grows its puddles from the highest of these).
	 */
	float pool(int p, int x, int y)
	{
		float h = floors[index(p, x, y)];
		float sum = 0;
		int n = 0;
		for (int dx = -1; dx <= 1; ++dx)
		{
			for (int dy = -1; dy <= 1; ++dy)
			{
				int nx = x + dx, ny = y + dy;
				if ((dx != 0 || dy != 0) && nx >= 0 && ny >= 0 && nx < size && ny < size)
				{
					sum += floors[index(p, nx, ny)];
					n++;
				}
			}
		}
		// y points down: a hollow's floor is larger than its neighbours'
		float hollow = n == 0 ? 0 : clamp01((h - sum / n) / POOL_DEPTH);
		float slope = Math.max(Math.abs(floorAt(p, x + 1, y, h) - floorAt(p, x - 1, y, h)),
			Math.abs(floorAt(p, x, y + 1, h) - floorAt(p, x, y - 1, h))) * 0.5f;
		float level = 1 - clamp01((slope - POOL_FLAT) / (POOL_STEEP - POOL_FLAT));
		float byWall = 0;
		if (walls != null)
		{
			int packed = RoomAmbient.pack(walls, p, x, y);
			int nearest = 63;
			for (int c = 0; c < 4; ++c)
			{
				nearest = Math.min(nearest, packed >> (c * 6) & 63);
			}
			byWall = 1 - nearest / 63f;
		}
		return level * Math.max(hollow, 0.75f * byWall);
	}

	private float floorAt(int p, int x, int y, float fallback)
	{
		return x < 0 || y < 0 || x >= size || y >= size ? fallback : floors[index(p, x, y)];
	}

	private static float clamp01(float v)
	{
		return Math.max(0, Math.min(1, v));
	}
}
