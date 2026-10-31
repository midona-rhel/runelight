package com.midonarhel.runelight.visibility;

import com.midonarhel.runelight.*;

import java.nio.FloatBuffer;

/**
 * Which building room every tile of the scene belongs to, for the volumetric fog (it stays out of interiors), the room
 * fill (RoomLight), puddles and the weather. Rooms come from BuildingDetector; 0 is outdoors. The floors' heights and
 * where there is a floor ride along.
 *
 * GL-free so it can be tested; RoomTexture uploads {@link #texels}, a layer each (rooms.glsl). Arrays are
 * [plane][x][y] in extended scene tiles; planes are logical planes as in BuildingDetector.
 */
public final class RoomMap
{
	public static final int PLANES = 4;
	// room ids are packed in 12 bits; ANY is one past the largest
	public static final int ANY = 4095;
	public static final int MAX_ROOM = ANY - 1;
	public static final float TILE = 128;
	// the texture's layers (rooms.glsl): rooms, floor heights, walls, puddles; an RGBA texel per tile, a plane each
	static final int LAYERS = 4;

	public final int size;
	// extended tiles before local tile 0
	public final int offset;
	public final short[] rooms;
	public final float[] floors;
	// under a roof by the map's flags, whether or not a room was found there
	public final boolean[] roofed;
	// tiles a diagonal wall crosses with another room, or the outdoors, on its far side: which half (WallMap.half)
	// the tile's room has, plus 1 (0: the whole tile), and the room of the other half (0 outdoors)
	final byte[] half;
	final short[] across;
	// a bit per plane: the tile has a floor there (setFloors); null until known
	private byte[] filled;
	private int version;
	// the walls the rooms were split along, null when unknown: the texels carry them
	private WallMap walls;

	// B3: the texels packed off the client thread (pools and all), for the version they were packed for;
	// RoomTexture.update sends them when ready and keeps the previous upload until then, so a region load
	// no longer packs the whole map on its swap frame (30+ ms of each load's hitch, 2026-10-30)
	private volatile java.nio.FloatBuffer packed;
	private volatile int packedVersion = -1;
	private volatile boolean packing;
	private static final java.util.concurrent.ExecutorService PACKER = java.util.concurrent.Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "RuneLight room texels");
		t.setDaemon(true);
		return t;
	});

	/** Whether {@link #texels} can be served from the packed copy for the current version. */
	boolean packedReady()
	{
		return packed != null && packedVersion == version;
	}

	/** Pack the texels for the current version on the worker, unless that is already under way. */
	void packAsync()
	{
		if (packing || packedReady())
		{
			return;
		}
		packing = true;
		final int forVersion = version;
		PACKER.execute(() ->
		{
			try
			{
				java.nio.FloatBuffer out = org.lwjgl.BufferUtils.createFloatBuffer(floatCount());
				texelsInto(out);
				out.flip();
				packed = out;
				packedVersion = forVersion;
			}
			finally
			{
				packing = false;
			}
		});
	}

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

	public int index(int plane, int x, int y)
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
	public static RoomMap build(BuildingDetector.Result r, byte[][][] settings, int[][][] heights, int offset)
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
	public void setWalls(WallMap walls)
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
	public int room(int plane, int x, int y)
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
	public static final float PLANE_MARGIN = 2;
	public static final float STOREY = 420;

	/**
	 * The room of a point at local world position, the CPU twin of rooms.glsl's roomAt: 0 outdoors, -1 outside the map.
	 */
	public int roomAt(float x, float y, float z)
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

	/** The index in {@link #texels} of a tile's value for plane 0 in a layer; plane p's is p after it. */
	static int texelIndex(int size, int layer, int x, int y)
	{
		return ((layer * size + y) * size + x) * PLANES;
	}

	/** Floats in {@link #texels}: an RGBA texel per tile and layer. */
	int floatCount()
	{
		return LAYERS * size * size * PLANES;
	}

	/**
	 * The texels, a layer after another, each in rows of tiles from the south (y), east along each (x), an RGBA texel
	 * of the four planes per tile: the rooms, the floor heights (world y), the walls: the tile's WallMap bits, its edges
	 * set when either tile beside them holds the wall, plus 64 times the half (WallMap.half) the tile's room has, plus
	 * 128 times the room of the other half of a tile a diagonal wall crosses (0 outdoors; the tile's own room when both
	 * halves are in it), bit 19 a floor tile; and the puddle places ({@link #pool}). From the packed copy when it is
	 * the current version's.
	 */
	void texels(FloatBuffer out)
	{
		java.nio.FloatBuffer ready = packed;
		if (ready != null && packedVersion == version)
		{
			out.put(ready.duplicate());
			return;
		}
		texelsInto(out);
	}

	private void texelsInto(FloatBuffer out)
	{
		float[] pools = pools();
		for (int layer = 0; layer < LAYERS; ++layer)
		{
			for (int y = 0; y < size; ++y)
			{
				for (int x = 0; x < size; ++x)
				{
					for (int p = 0; p < PLANES; ++p)
					{
						int i = index(p, x, y);
						switch (layer)
						{
							case 0:
								out.put(rooms[i]);
								break;
							case 1:
								out.put(floors[i]);
								break;
							case 2:
								out.put(wallTexel(p, x, y));
								break;
							default:
								out.put(pools[i]);
						}
					}
				}
			}
		}
	}

	private float wallTexel(int p, int x, int y)
	{
		int i = index(p, x, y);
		int b = walls != null ? walls.get(p, x, y) : 0;
		// rooms.glsl reads a tile's own texel for its edges
		if (walls != null)
		{
			for (int side = 1; side <= 8; side <<= 1)
			{
				if (walls.edge(p, x, y, side)) b |= side;
			}
		}
		// Bit 19 is floor presence; the lower 19 bits retain wall/room encoding.
		int floor = filled != null && (filled[x * size + y] & (1 << p)) != 0 ? 1 << 19 : 0;
		return floor + b + (half[i] == 0 ? 128 * rooms[i] : 64 * (half[i] - 1) + 128 * across[i]);
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
