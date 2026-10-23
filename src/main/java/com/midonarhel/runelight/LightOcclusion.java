package com.midonarhel.runelight;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Which ground around each static light (scenery and world lights) the light can see past the
 * walls: a small top-down visibility grid per light, cast on the wall map (WallMap) on a worker thread when the
 * light first shows up and again when the walls near it change (a door opens). The composite multiplies each
 * light by its grid, looked up bilinearly at the pixel (light_occlusion.glsl), so a light stops at the walls of its
 * room, behind a diagonal wall too, and only reaches out through doorways and windows. Lights that move (NPCs,
 * projectiles, the test light) keep the room rules only.
 *
 * Grids are cells of a quarter tile (half a tile for big lights), 4 bits each (how much of the cell sees the light),
 * a byte to a texel of one 2D atlas texture (LightClusters), each grid inside a border of fully visible texels, so the
 * shader's lookup is a single hardware filtered fetch that reads 1 past the grid's edge.
 */
final class LightOcclusion
{
	private static final Logger log = LoggerFactory.getLogger(LightOcclusion.class);

	// cell size in world units, quarter tiles for lights up to FINE_MAX_RADIUS, half tiles past it
	static final float CELL_FINE = 32, CELL_COARSE = 64;
	static final float FINE_MAX_RADIUS = 1024;
	// grids reach this far at most: past it a light is unoccluded
	static final float MAX_RADIUS = 2048;
	// radii round up to this, so flickering and pulsing lights don't recast
	static final float RADIUS_STEP = 128;
	// a light closer than this (world units) to a wall of its own tile is cast from this far off it, on its own side:
	// a torch hung on a wall shines into its room and not through the wall
	static final float WALL_CLEARANCE = 8;
	// cells on a diagonal wall average two rays this far to either side of it
	static final float DIAGONAL_SPREAD = 8;
	// the atlas: this many texels across, grids packed in rows, at most this many down; a grid's position is
	// x + y * ATLAS_BASE_ROW, a whole number a float keeps exactly
	static final int ATLAS_WIDTH = 2048;
	static final int ATLAS_MAX_HEIGHT = 4096;
	static final int ATLAS_BASE_ROW = 4096;

	static final class Entry
	{
		final int id;
		final int generation;
		final long key;
		final int plane;
		final float x, z, radius;
		// the grid: its corner in local world units, cells per side, cell size
		final float originX, originZ, cell;
		final int n;
		// a nibble per cell, row (z) by row, null until cast; open when no wall is near enough to matter
		volatile byte[] cells;
		volatile boolean open;
		final AtomicBoolean queued = new AtomicBoolean();

		Entry(int id, int generation, long key, int plane, float x, float z, float radius)
		{
			this.id = id;
			this.generation = generation;
			this.key = key;
			this.plane = plane;
			this.x = x;
			this.z = z;
			float r = Math.min(MAX_RADIUS, (float) Math.ceil(radius / RADIUS_STEP) * RADIUS_STEP);
			this.radius = r;
			cell = r <= FINE_MAX_RADIUS ? CELL_FINE : CELL_COARSE;
			originX = (float) Math.floor((x - r) / cell) * cell;
			originZ = (float) Math.floor((z - r) / cell) * cell;
			n = (int) Math.ceil((x + r - originX) / cell) + 1;
		}

		/** Extended tile rectangle the grid covers, {x0, y0, x1, y1}. */
		int[] tiles(int offset)
		{
			int x0 = (int) Math.floor(originX / 128) + offset, y0 = (int) Math.floor(originZ / 128) + offset;
			int x1 = (int) Math.floor((originX + n * cell) / 128) + offset;
			int y1 = (int) Math.floor((originZ + n * cell) / 128) + offset;
			return new int[]{x0, y0, x1, y1};
		}
	}

	/**
	 * Every cast grid packed into the atlas texture: a byte per cell (visibility * 255), row by row, ATLAS_WIDTH
	 * across; bases are the texels of the grids' cells (0, 0) as x + y * ATLAS_BASE_ROW, by entry id.
	 */
	static final class Atlas
	{
		final byte[] texels;
		final int width, height;
		private final int[] base;

		Atlas(byte[] texels, int width, int height, int[] base)
		{
			this.texels = texels;
			this.width = width;
			this.height = height;
			this.base = base;
		}

		int base(Entry e)
		{
			return e != null && e.id < base.length ? base[e.id] : -1;
		}
	}

	private volatile WallMap walls;
	private volatile int generation;
	private volatile List<Entry> entries = new CopyOnWriteArrayList<>();
	private volatile Atlas atlas;
	// key -> entry, client thread only
	private long[] keys = new long[256];
	private Entry[] values = new Entry[256];
	private int count;
	private int nextId;

	private final ConcurrentLinkedQueue<Entry> pending = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean draining = new AtomicBoolean();
	private ExecutorService worker;

	/** The grids cast so far, null before any. */
	Atlas getAtlas()
	{
		return atlas;
	}

	int getEntryCount()
	{
		return entries.size();
	}

	void shutdown()
	{
		if (worker != null)
		{
			worker.shutdownNow();
			worker = null;
		}
		reset(null);
	}

	/**
	 * Follow the scene's walls (BuildingLights.getWalls), once a frame on the client thread: a new scene starts over,
	 * changed walls recast the lights whose grids they touch.
	 */
	void update(WallMap w)
	{
		WallMap old = walls;
		if (w == old)
		{
			return;
		}
		if (w == null || old == null || w.scene != old.scene || w.size != old.size || w.offset != old.offset)
		{
			reset(w);
			return;
		}
		int[] rect = w.diff(old);
		walls = w;
		if (rect == null)
		{
			return;
		}
		for (Entry e : entries)
		{
			int[] t = e.tiles(w.offset);
			if (t[0] <= rect[2] && t[2] >= rect[0] && t[1] <= rect[3] && t[3] >= rect[1])
			{
				enqueue(e);
			}
		}
	}

	private void reset(WallMap w)
	{
		walls = w;
		generation++;
		entries = new CopyOnWriteArrayList<>();
		pending.clear();
		atlas = null;
		keys = new long[256];
		values = new Entry[256];
		count = 0;
		nextId = 0;
	}

	/**
	 * Give this frame's fixed lights their grids (PointLight.occlusion), asking for the ones not cast yet; the
	 * others get none. Client thread.
	 */
	void assign(List<PointLight> lights, boolean enabled)
	{
		WallMap w = walls;
		for (int i = 0, n = lights.size(); i < n; ++i)
		{
			PointLight l = lights.get(i);
			l.occlusion = null;
			if (!enabled || w == null || !l.fixed || l.plane < 0 || l.plane >= WallMap.PLANES)
			{
				continue;
			}
			long key = key(l.plane, l.x, l.z);
			Entry e = get(key);
			if (e == null || e.radius < Math.min(MAX_RADIUS, l.radius) - 0.5f)
			{
				e = new Entry(nextId++, generation, key, l.plane, l.x, l.z, e != null ? Math.max(e.radius, l.radius) : l.radius);
				put(key, e);
				entries.add(e);
				enqueue(e);
			}
			l.occlusion = e;
		}
	}

	static long key(int plane, float x, float z)
	{
		long ix = Math.round(x) + (1 << 19), iz = Math.round(z) + (1 << 19);
		return (long) plane << 40 | (ix & 0xFFFFF) << 20 | (iz & 0xFFFFF);
	}

	private Entry get(long key)
	{
		int mask = keys.length - 1;
		for (int i = hash(key) & mask; ; i = (i + 1) & mask)
		{
			Entry e = values[i];
			if (e == null)
			{
				return null;
			}
			if (keys[i] == key)
			{
				return e;
			}
		}
	}

	private void put(long key, Entry e)
	{
		if ((count + 1) * 2 > keys.length)
		{
			long[] ok = keys;
			Entry[] ov = values;
			keys = new long[ok.length * 2];
			values = new Entry[ok.length * 2];
			count = 0;
			for (int i = 0; i < ok.length; ++i)
			{
				if (ov[i] != null)
				{
					put(ok[i], ov[i]);
				}
			}
		}
		int mask = keys.length - 1;
		for (int i = hash(key) & mask; ; i = (i + 1) & mask)
		{
			if (values[i] == null)
			{
				keys[i] = key;
				values[i] = e;
				count++;
				return;
			}
			if (keys[i] == key)
			{
				// a larger grid for the same light replaces the old one
				entries.remove(values[i]);
				values[i] = e;
				return;
			}
		}
	}

	private static int hash(long key)
	{
		long h = key * 0x9E3779B97F4A7C15L;
		return (int) (h ^ (h >>> 32));
	}

	private void enqueue(Entry e)
	{
		if (!e.queued.compareAndSet(false, true))
		{
			return;
		}
		pending.add(e);
		if (draining.compareAndSet(false, true))
		{
			if (worker == null)
			{
				worker = Executors.newSingleThreadExecutor(r ->
				{
					Thread t = new Thread(r, "RuneLight light occlusion");
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY);
					return t;
				});
			}
			worker.execute(this::drain);
		}
	}

	private void drain()
	{
		try
		{
			while (true)
			{
				long start = System.nanoTime();
				int cast = 0;
				Entry e;
				while ((e = pending.poll()) != null)
				{
					e.queued.set(false);
					WallMap w = walls;
					if (w == null || e.generation != generation)
					{
						continue;
					}
					castEntry(e, w);
					cast++;
				}
				if (cast > 0)
				{
					int gen = generation;
					Atlas a = pack(entries, gen);
					if (gen == generation)
					{
						atlas = a;
					}
					log.debug("Light occlusion: cast {} lights in {} ms, atlas {} rows", cast,
						(System.nanoTime() - start) / 1_000_000.0, a.height);
				}
				draining.set(false);
				// something queued after the poll found nothing, before the flag was cleared
				if (pending.isEmpty() || !draining.compareAndSet(false, true))
				{
					return;
				}
			}
		}
		catch (RuntimeException ex)
		{
			draining.set(false);
			log.warn("Light occlusion failed", ex);
		}
	}

	static void castEntry(Entry e, WallMap w)
	{
		int[] t = e.tiles(w.offset);
		if (!w.anyIn(e.plane, t[0], t[1], t[2], t[3]))
		{
			e.open = true;
			e.cells = null;
			return;
		}
		float[] from = castOrigin(w, e.plane, e.x, e.z);
		e.cells = cast(w, e.plane, from[0], from[1], e.originX, e.originZ, e.n, e.cell, e.radius);
		e.open = false;
	}

	/**
	 * Where a light is cast from: its own position, moved off the walls of its tile that it sits within
	 * WALL_CLEARANCE of, so that the wall stands between it and the other side whichever way its position rounds.
	 */
	static float[] castOrigin(WallMap w, int plane, float x, float z)
	{
		int tx = (int) Math.floor(x / 128) + w.offset, tz = (int) Math.floor(z / 128) + w.offset;
		float fx = x - (float) Math.floor(x / 128) * 128, fz = z - (float) Math.floor(z / 128) * 128;
		if (w.edge(plane, tx, tz, WallMap.WEST) && fx < WALL_CLEARANCE)
		{
			x += WALL_CLEARANCE - fx;
		}
		else if (w.edge(plane, tx, tz, WallMap.EAST) && 128 - fx < WALL_CLEARANCE)
		{
			x -= WALL_CLEARANCE - (128 - fx);
		}
		if (w.edge(plane, tx, tz, WallMap.SOUTH) && fz < WALL_CLEARANCE)
		{
			z += WALL_CLEARANCE - fz;
		}
		else if (w.edge(plane, tx, tz, WallMap.NORTH) && 128 - fz < WALL_CLEARANCE)
		{
			z -= WALL_CLEARANCE - (128 - fz);
		}
		return new float[]{x, z};
	}

	/**
	 * Cast a light's grid: per cell, 0-15 for how much of it sees the light at (lx, lz) past the walls. Cells past
	 * the radius are left lit; they get no light anyway.
	 */
	static byte[] cast(WallMap w, int plane, float lx, float lz, float originX, float originZ, int n, float cell,
		float radius)
	{
		byte[] out = new byte[n * n];
		float reach = radius + cell;
		float spread = DIAGONAL_SPREAD * 0.70710678f;
		for (int cz = 0; cz < n; ++cz)
		{
			float pz = originZ + (cz + 0.5f) * cell;
			for (int cx = 0; cx < n; ++cx)
			{
				float px = originX + (cx + 0.5f) * cell;
				float dx = px - lx, dz = pz - lz;
				int k = cz * n + cx;
				if (dx * dx + dz * dz > reach * reach)
				{
					out[k] = 15;
					continue;
				}
				int b = w.get(plane, (int) Math.floor(px / 128) + w.offset, (int) Math.floor(pz / 128) + w.offset);
				float vis;
				if (WallMap.split(b))
				{
					// across the diagonal: north-west/south-east of "/", north-east/south-west of "\"
					float ox = (b & WallMap.DIAG_SW_NE) != 0 ? -spread : spread, oz = spread;
					vis = ((w.blocked(plane, lx, lz, px + ox, pz + oz) ? 0 : 1)
						+ (w.blocked(plane, lx, lz, px - ox, pz - oz) ? 0 : 1)) * 0.5f;
				}
				else
				{
					vis = w.blocked(plane, lx, lz, px, pz) ? 0 : 1;
				}
				out[k] = (byte) Math.round(vis * 15);
			}
		}
		return out;
	}

	/** Pack the cast grids of a generation: 4 cells of 4 bits per float, 16 per texel. */
	static Atlas pack(List<Entry> entries, int generation)
	{
		int maxId = 0;
		List<Entry> packed = new ArrayList<>();
		List<int[]> places = new ArrayList<>();
		int x = 0, y = 0, rowHeight = 0;
		for (Entry e : entries)
		{
			maxId = Math.max(maxId, e.id + 1);
			byte[] c = e.cells;
			if (e.generation != generation || e.open || c == null)
			{
				continue;
			}
			// the grid and its border
			int side = e.n + 2;
			if (x + side > ATLAS_WIDTH)
			{
				x = 0;
				y += rowHeight;
				rowHeight = 0;
			}
			if (y + side > ATLAS_MAX_HEIGHT)
			{
				continue;
			}
			packed.add(e);
			places.add(new int[]{x, y});
			x += side;
			rowHeight = Math.max(rowHeight, side);
		}
		int height = Math.max(y + rowHeight, 1);
		int[] base = new int[maxId];
		java.util.Arrays.fill(base, -1);
		byte[] data = new byte[ATLAS_WIDTH * height];
		// the borders, and the gaps no lookup reaches: fully visible
		java.util.Arrays.fill(data, (byte) 255);
		for (int i = 0; i < packed.size(); ++i)
		{
			Entry e = packed.get(i);
			byte[] c = e.cells;
			int x0 = places.get(i)[0] + 1, y0 = places.get(i)[1] + 1;
			for (int cz = 0; cz < e.n; ++cz)
			{
				for (int cx = 0; cx < e.n; ++cx)
				{
					data[(y0 + cz) * ATLAS_WIDTH + x0 + cx] = (byte) ((c[cz * e.n + cx] & 15) * 17);
				}
			}
			base[e.id] = x0 + y0 * ATLAS_BASE_ROW;
		}
		return new Atlas(data, ATLAS_WIDTH, height, base);
	}

	/**
	 * A light's texel after the lights (light_occlusion.glsl): its grid's position in the atlas (Atlas, -1 for
	 * none), the grid's corner (local world xz), and a code: cells per side * 32 + the light's plane * 8 + 4 when the
	 * walls already decide where it reaches on its plane (its grid is cast, or no wall is near enough to matter: the
	 * rooms then only keep it off the other planes) + 2 when the light is covered (under a roof: it doesn't light
	 * what is open to the sky above it) + 1 for half tile cells.
	 *
	 * @param radius the light's radius: past the grids' reach the walls decide nothing
	 */
	static void texel(Entry e, Atlas a, boolean covered, int plane, float radius, FloatBuffer out)
	{
		int base = a != null && e != null && !e.open ? a.base(e) : -1;
		int flags = (covered ? 2 : 0) | (plane >= 0 && plane < WallMap.PLANES ? plane << 3 : 0);
		if (e != null && radius <= MAX_RADIUS && (e.open || base >= 0))
		{
			flags |= 4;
		}
		if (base < 0)
		{
			out.put(-1).put(0).put(0).put(flags);
			return;
		}
		out.put(base).put(e.originX).put(e.originZ).put(e.n * 32 + flags + (e.cell > CELL_FINE ? 1 : 0));
	}

	/** The visibility the shader reads for a cell of a packed grid, 0-1; 1 outside it. For tests. */
	static float lookup(Atlas a, Entry e, int cx, int cz)
	{
		int b = a.base(e);
		if (cx < 0 || cz < 0 || cx >= e.n || cz >= e.n || b < 0)
		{
			return 1;
		}
		int x = b % ATLAS_BASE_ROW + cx, y = b / ATLAS_BASE_ROW + cz;
		return (a.texels[y * a.width + x] & 255) / 255f;
	}
}
