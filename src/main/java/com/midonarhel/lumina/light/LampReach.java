package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.WallMap;
import java.nio.FloatBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Per lamp, the walls its light meets, baked on the CPU so a pixel reads them instead of walking the lamp's line tile by
 * tile (lamp.glsl lampReachShare; the walk was up to 1.6 ms of a night frame, docs/perf-design-review.md). The walls
 * don't move: they change with a door or a scene, and a lamp that stands still keeps its map.
 *
 * Around the lamp's origin (where its lines start, LampWalls.origin), BINS rays, each walked once over the shown walls
 * of the lamp's storey as the shader walked a line (LampWalls' tiles: WallMap's edges, either tile's, and the diagonals),
 * out to the lamp's reach: the first two walls each ray meets, as the line each lies on (an edge's x or y, a diagonal's
 * x - y or x + y, in extended tiles). A point reads the rays either side of its direction from the origin and takes
 * their walls as lines: along any ray between them a straight wall, or the corner where two meet, is where it is, so a
 * wall stops the light exactly as the walk had it; only where a wall ends between the two rays does its line go on past
 * the end, for less than a ray's width (a sliver of shadow more, never light past a wall). The first walls decide a
 * surface; the fog's glow takes both of each ray, each up to the walls' top.
 *
 * And per lamp the lowest drawn roof in its reach: a line both of whose ends are below it can't pass through a roof, so
 * the shader looks for roofs along the line only where it could.
 *
 * In the light buffer from LightClusters.LAMP_REACH_BASE, PLACES places of TEXELS texels: per texel two rays, per ray
 * its first and second wall's line (0: none). A lamp's fourth texel says its place's first texel (0: none, the shader
 * walks) and its lowest roof. Until a lamp's map is baked, and while walls ease in (a door: the new walls' maps are
 * baked meanwhile), the shader walks.
 */
public final class LampReach
{
	/** Rays around a lamp: at a reach of 10 tiles one is 0.06 tiles wide. */
	public static final int BINS = 1024;
	/** Texels of a lamp's map: two rays a texel, two lines a ray. */
	public static final int TEXELS = BINS / 2;
	/** Lamps the light buffer holds maps for at once. */
	public static final int PLACES = 256;
	public static final int REGION_TEXELS = PLACES * TEXELS;
	/** A line's id: 1 + its kind (0: x = c, 1: y = c, 2: x - y = c, 3: x + y = c) + 4 * (c + C_OFF). */
	public static final int C_OFF = 1024;
	// rays walk this far past the reach asked for (tiles), so a flicker's larger reach is still covered
	private static final float REACH_MARGIN = 1.5f;
	// at most this many maps baking at once; the others wait for a later frame
	private static final int MAX_PENDING = 64;
	private static final int SETS = 3;
	// maps no lamp has asked for in this many frames are let go (a power of two)
	private static final int PRUNE_FRAMES = 512;

	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r ->
	{
		Thread t = new Thread(r, "Lumina lamp reach");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	/** A lamp's map: for which walls, storey and origin, how far it reaches, and its lines once baked. */
	private static final class Entry
	{
		final float[] tiles;
		final int size, plane;
		final float ox, oz, reach;
		volatile float[] lines;
		int place = -1;
		int used;
		// the lowest drawn roof in its reach (LampWalls' roofs layer: world y plus RoofMap.BIAS, 0 none), as of roofs
		float roofLow;
		int roofsVersion = -1;

		Entry(float[] tiles, int size, int plane, float ox, float oz, float reach)
		{
			this.tiles = tiles;
			this.size = size;
			this.plane = plane;
			this.ox = ox;
			this.oz = oz;
			this.reach = reach;
		}
	}

	private static final class Key
	{
		final float[] tiles;
		final int plane;
		final float ox, oz;

		Key(float[] tiles, int plane, float ox, float oz)
		{
			this.tiles = tiles;
			this.plane = plane;
			this.ox = ox;
			this.oz = oz;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Key))
			{
				return false;
			}
			Key k = (Key) o;
			return k.tiles == tiles && k.plane == plane && Float.floatToIntBits(k.ox) == Float.floatToIntBits(ox)
				&& Float.floatToIntBits(k.oz) == Float.floatToIntBits(oz);
		}

		@Override
		public int hashCode()
		{
			return ((System.identityHashCode(tiles) * 31 + plane) * 31 + Float.floatToIntBits(ox)) * 31
				+ Float.floatToIntBits(oz);
		}
	}

	private final Map<Key, Entry> entries = new HashMap<>();
	private final AtomicInteger pending = new AtomicInteger();
	private final Entry[] placed = new Entry[PLACES];
	private final ArrayDeque<Integer> freePlaces = new ArrayDeque<>();
	// what each place holds, and what each copy of the light buffer has of it
	private final float[] texels = new float[REGION_TEXELS * 4];
	private final int[] placeVersion = new int[PLACES];
	private final int[][] sentPlace = new int[SETS][PLACES];
	private final FloatBuffer staging = BufferUtils.createFloatBuffer(TEXELS * 4);
	private float[] walls;
	private int frame;

	public LampReach()
	{
		for (int i = 0; i < PLACES; ++i)
		{
			freePlaces.add(i);
		}
		invalidateAll();
	}

	/**
	 * A new frame: the walls shown now (LampWalls); maps baked for other walls are let go. Called before the frame's
	 * lookups.
	 */
	void frame(LampWalls lampWalls)
	{
		++frame;
		float[] now = lampWalls != null ? lampWalls.shownTiles() : null;
		boolean prune = (frame & (PRUNE_FRAMES - 1)) == 0;
		if (now != walls || prune)
		{
			walls = now;
			for (Iterator<Entry> it = entries.values().iterator(); it.hasNext(); )
			{
				Entry e = it.next();
				// baked for other walls, or for a lamp gone a while (one that moved)
				if (e.tiles != now || frame - e.used > PRUNE_FRAMES && e.lines != null)
				{
					release(e);
					it.remove();
				}
			}
		}
	}

	/**
	 * The lamp's map, for the light buffer (into out: its place's first texel, 0 none: the shader walks; and its lowest
	 * roof): the shown walls', for a lamp at (ox, oz) (local world units, its lines' origin) on a storey, reaching
	 * radius. A map not baked yet is asked for, and the lamp walks meanwhile. Render thread.
	 */
	void lookup(LampWalls lampWalls, int plane, float ox, float oz, float radius, int base, float[] out)
	{
		out[0] = 0;
		out[1] = 0;
		float[] tiles = walls;
		if (tiles == null || plane < 0 || plane >= WallMap.PLANES)
		{
			return;
		}
		int size = lampWalls.shownSize();
		float offset = lampWalls.shownOffset();
		float tx = ox / 128 + offset, tz = oz / 128 + offset;
		float reach = radius / 128;
		Key key = new Key(tiles, plane, tx, tz);
		Entry e = entries.get(key);
		if (e == null || e.reach < reach)
		{
			if (pending.get() >= MAX_PENDING)
			{
				return;
			}
			if (e != null)
			{
				release(e);
			}
			Entry fresh = new Entry(tiles, size, plane, tx, tz, reach * REACH_MARGIN + 1);
			fresh.used = frame;
			entries.put(key, fresh);
			pending.incrementAndGet();
			WORKER.execute(() ->
			{
				try
				{
					float[] lines = new float[BINS * 2];
					bake(fresh.tiles, fresh.size, fresh.plane, fresh.ox, fresh.oz, fresh.reach, lines);
					fresh.lines = lines;
				}
				finally
				{
					pending.decrementAndGet();
				}
			});
			return;
		}
		e.used = frame;
		// while the walls before ease out the shader walks both; the map is baked meanwhile
		if (e.lines == null || lampWalls.easing() || !place(e))
		{
			return;
		}
		int version = lampWalls.roofsVersion();
		if (e.roofsVersion != version)
		{
			e.roofsVersion = version;
			e.roofLow = lowestRoof(lampWalls.roofBottoms(), size, e.ox, e.oz, reach * REACH_MARGIN + 1);
		}
		out[0] = base + e.place * TEXELS;
		out[1] = e.roofLow;
	}

	/** Whether maps are being baked (the offline renderer waits for them). */
	public boolean busy()
	{
		return pending.get() > 0;
	}

	/** A place in the light buffer for the map: a free one, or the one least recently used by a lamp not in this frame. */
	private boolean place(Entry e)
	{
		if (e.place >= 0)
		{
			return true;
		}
		Integer p = freePlaces.poll();
		if (p == null)
		{
			Entry oldest = null;
			for (Entry o : placed)
			{
				if (o != null && o.used != frame && (oldest == null || o.used < oldest.used))
				{
					oldest = o;
				}
			}
			if (oldest == null)
			{
				return false;
			}
			p = oldest.place;
			oldest.place = -1;
		}
		e.place = p;
		placed[p] = e;
		System.arraycopy(e.lines, 0, texels, p * TEXELS * 4, TEXELS * 4);
		++placeVersion[p];
		return true;
	}

	private void release(Entry e)
	{
		if (e.place >= 0)
		{
			placed[e.place] = null;
			freePlaces.add(e.place);
			e.place = -1;
		}
	}

	/** Bring a copy of the light buffer (bound to GL_TEXTURE_BUFFER) up to date: the places changed since it last was. */
	void upload(int set, int base)
	{
		int[] sent = sentPlace[set];
		for (int p = 0; p < PLACES; ++p)
		{
			if (sent[p] != placeVersion[p])
			{
				staging.clear();
				staging.put(texels, p * TEXELS * 4, TEXELS * 4).flip();
				glBufferSubData(GL_TEXTURE_BUFFER, (long) (base + p * TEXELS) * 4 * Float.BYTES, staging);
				sent[p] = placeVersion[p];
			}
		}
	}

	/** A copy of the light buffer was made anew: it has none of the places. */
	void invalidate(int set)
	{
		java.util.Arrays.fill(sentPlace[set], -1);
	}

	private void invalidateAll()
	{
		for (int s = 0; s < SETS; ++s)
		{
			invalidate(s);
		}
	}

	public void free()
	{
		entries.clear();
		java.util.Arrays.fill(placed, null);
		freePlaces.clear();
		for (int i = 0; i < PLACES; ++i)
		{
			freePlaces.add(i);
		}
		walls = null;
		invalidateAll();
	}

	/** A line's id, as the shader reads it. */
	static float lineId(int kind, int c)
	{
		return 1 + kind + 4 * (c + C_OFF);
	}

	/**
	 * Walk the rays (out: per ray its first and second wall's line, 0 none) from (ox, oz), extended tiles, over tiles
	 * (LampWalls.pack's: six bits a storey) of a scene size tiles a side, on a storey, out to reach tiles.
	 */
	static void bake(float[] tiles, int size, int plane, float ox, float oz, float reach, float[] out)
	{
		for (int b = 0; b < BINS; ++b)
		{
			double th = (b + 0.5) * 2 * Math.PI / BINS;
			float dx = (float) Math.cos(th), dy = (float) Math.sin(th);
			int tx = (int) Math.floor(ox), ty = (int) Math.floor(oz);
			int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0, sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
			float deltaX = sx != 0 ? 1 / Math.abs(dx) : Float.POSITIVE_INFINITY;
			float deltaY = sy != 0 ? 1 / Math.abs(dy) : Float.POSITIVE_INFINITY;
			float maxX = sx > 0 ? (tx + 1 - ox) * deltaX : sx < 0 ? (ox - tx) * deltaX : Float.POSITIVE_INFINITY;
			float maxY = sy > 0 ? (ty + 1 - oz) * deltaY : sy < 0 ? (oz - ty) * deltaY : Float.POSITIVE_INFINITY;
			float enter = 0;
			int hits = 0;
			out[b * 2] = 0;
			out[b * 2 + 1] = 0;
			while (hits < 2 && enter < reach && tx >= 0 && ty >= 0 && tx < size && ty < size)
			{
				int bits = (int) tiles[ty * size + tx] >> (6 * plane) & 63;
				float exit = Math.min(maxX, maxY);
				// a diagonal wall of the tile, crossed inside it
				if ((bits & WallMap.DIAG_SW_NE) != 0 && crosses(ox - tx, oz - ty, dx, dy, enter, exit, true))
				{
					out[b * 2 + hits++] = lineId(2, tx - ty);
				}
				if (hits < 2 && (bits & WallMap.DIAG_NW_SE) != 0 && crosses(ox - tx, oz - ty, dx, dy, enter, exit, false))
				{
					out[b * 2 + hits++] = lineId(3, tx + ty + 1);
				}
				boolean alongX = maxX < maxY;
				if (hits < 2)
				{
					// the edge stepped over
					if (alongX ? (bits & (sx > 0 ? WallMap.EAST : WallMap.WEST)) != 0
						: (bits & (sy > 0 ? WallMap.NORTH : WallMap.SOUTH)) != 0)
					{
						out[b * 2 + hits++] = alongX ? lineId(0, sx > 0 ? tx + 1 : tx) : lineId(1, sy > 0 ? ty + 1 : ty);
					}
				}
				if (alongX)
				{
					tx += sx;
					enter = maxX;
					maxX += deltaX;
				}
				else
				{
					ty += sy;
					enter = maxY;
					maxY += deltaY;
				}
			}
		}
	}

	/**
	 * Whether the ray (from (ax, ay), relative to a tile's south-west corner, along (dx, dy)) crosses a diagonal of the
	 * tile between distances t0 and t1: the south-west to north-east one (y = x), or the other (x + y = 1).
	 */
	private static boolean crosses(float ax, float ay, float dx, float dy, float t0, float t1, boolean swNe)
	{
		if (t1 <= t0)
		{
			return false;
		}
		float x0 = ax + dx * t0, y0 = ay + dy * t0, x1 = ax + dx * t1, y1 = ay + dy * t1;
		return swNe ? (y0 - x0) * (y1 - x1) < 0 : (x0 + y0 - 1) * (x1 + y1 - 1) < 0;
	}

	/** The lowest drawn roof (the largest world y plus BIAS; 0 none) on the tiles within reach of (ox, oz). */
	static float lowestRoof(float[] bottoms, int size, float ox, float oz, float reach)
	{
		if (bottoms == null)
		{
			return 0;
		}
		int x0 = Math.max((int) Math.floor(ox - reach) - 1, 0), x1 = Math.min((int) Math.floor(ox + reach) + 1, size - 1);
		int y0 = Math.max((int) Math.floor(oz - reach) - 1, 0), y1 = Math.min((int) Math.floor(oz + reach) + 1, size - 1);
		float low = 0;
		for (int y = y0; y <= y1; ++y)
		{
			for (int x = x0; x <= x1; ++x)
			{
				int i = y * size + x;
				if (i < bottoms.length && bottoms[i] > low)
				{
					low = bottoms[i];
				}
			}
		}
		return low;
	}

	/** The lamp lines' shader defines. */
	public static String defines()
	{
		return "#define LAMP_REACH_BINS " + BINS + "\n" + "#define LAMP_REACH_C_OFF " + C_OFF + "\n";
	}
}
