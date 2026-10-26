package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How much light each building room gets, and the soft fill that makes up what it lacks, so that no real room is left
 * black, nor lit more than it needs.
 *
 * A room's score is what its floor receives, as luminance of the light on a surface, averaged over the floor:
 * - the light map's point lights (the blocks as they are sent to the GPU, {@link #block}): each floor cell's light
 *   times FLOOR_NDL (a floor is lit at a slant), at most CELL_CAP, so that a torch's hot spot cannot hide a room's dark
 *   rest. The light map is baked for the time of day, so a night-only light counts only at night;
 * - the sky coming in: how much of the room's floor the sky light reaches through its doors and openings (SkyField, as
 *   the skylight spreads it; found on a worker whenever the walls change), times what a floor the sky reaches in full
 *   gets of the daylight now ({@link #skyLuminance}).
 *
 * The fill is the shortfall against a target, with a soft knee: fill = target * knee((target - score) / target). The
 * target is the "minimum room light" ({@link #target}): TARGET_NIGHT at night, by day DAY_SHARE of the light a sunlit
 * floor outdoors gets ({@link #outdoorLight}), scaled by the Brightness setting. {@link #update} works the fills out
 * and eases them in time (a room whose lights have just been baked does not pop). {@link #table} is what the shader
 * reads by room id (rooms.glsl), x being the fill's luminance.
 *
 * Calibration, in the composite's light on an upward surface (linear luminance; outdoors at 45 degrees of sun about
 * 2.7: the sun's 2.64 times its height, plus the ambient and sky light 0.8):
 * - Night: a fully dark room gets TARGET_NIGHT, 0.30, what the constant fill it replaces gave at its centre
 *   (BuildingLights.FILL_TINT of luminance 0.312). A room with a hearth or torches scores past it (a torch of strength 9
 *   and radius 500 gives the floor within three tiles of it more) and gets none.
 * - Day: the target is DAY_SHARE, 0.32, of the outdoor light: 0.86 at 45 degrees. The fill's shaping (composite_frag.glsl)
 *   gives a room's middle all of it and its walls a third, so a room reads about 25 to 30 percent of the outdoors on
 *   average: a windowless room is lifted to that (score 0, fill 0.86); one with a door open to the day scores the
 *   SKY_SHARE (0.22) of the outdoors the doorway's share of its floor gets, 0.59 at most, and is lifted by the rest, a
 *   fifth of the outdoors with the door wide open; a room with a hearth (score past 1) gets nothing, and stays as lit
 *   as its fire makes it. Before this a pub by day read 11 percent of the grass outside; a fully lit room still does
 *   as it did.
 */
final class RoomLight implements LightMap.Sink
{
	private static final Logger log = LoggerFactory.getLogger(RoomLight.class);

	/** The least light a room gets at night, in luminance: the old constant fill's, dim but readable. */
	static final float TARGET_NIGHT = 0.30f;
	/** By day interiors are lifted to this share of the light a sunlit floor outdoors gets: dimmer than outside, legible. */
	static final float DAY_SHARE = 0.32f;
	/** Of the target (the shortfall) over which the fill eases in: no fill at a score of target * (1 + KNEE). */
	static final float KNEE = 0.25f;
	/** How much of the light map's cell light a floor takes: the cosine of the light's angle, averaged. */
	static final float FLOOR_NDL = 0.5f;
	/** The most a cell counts for, in luminance: about twice the day's target. */
	static final float CELL_CAP = 1.5f;
	/**
	 * The share of the outdoor light a floor the sky reaches in full gets: composite_frag.glsl's room daylight, 0.3 of the
	 * ambient, sky and 0.7 of the sun's light, over the outdoor light.
	 */
	static final float SKY_SHARE = 0.22f;
	/** What it gets at night, from the moon and the night sky, in luminance. */
	static final float SKY_NIGHT_LUMINANCE = 0.09f;
	/** What the sun gives a floor outdoors at its brightest (the sun's strength 2.9, in luminance), and the ambient and sky light. */
	static final float SUN_LUMINANCE = 2.64f;
	static final float DAY_AMBIENT_LUMINANCE = 0.8f;
	/** Seconds in which a fill follows its target. */
	static final float EASE_SECONDS = 0.4f;
	/** Seconds after the rooms are known that the summary is told in the log. */
	static final float SUMMARY_DELAY = 8;
	/** A fill under this share of the target is none, over 1 minus it the full target (the summary). */
	static final float SUMMARY_EDGE = 0.05f;

	private static final int CELLS = LightBlockers.CELLS_PER_TILE;

	// the light map's cells' scores per storey, as the GPU has them (the rooms may change under them)
	private final float[][] grid = new float[WallMap.PLANES][];
	private int cells;

	private RoomMap rooms;
	// per room id: floor tiles, the summed cell scores, the sky's share
	private int[] tiles = new int[1];
	private double[] sums = new double[1];
	private float[] sky = new float[1];
	private float[] now = new float[1], goal = new float[1];
	// x per room id, the table the shader reads
	private float[] table = new float[4];
	private int used = 1;
	private int version;
	private boolean fresh;
	private float age = Float.NaN;
	private boolean summarized;
	private float lastTarget;

	// the sky's share per room, found for the walls
	private ExecutorService worker;
	private Object skyFor, skyWalls;
	private volatile float[] skyDone;
	private volatile Object skyDoneFor;
	private volatile Object skyRequest;

	// -------------------------------------------------------------------------------------------------------
	// Score

	/**
	 * The light map's size in cells a side, 0 for none; the light stored is forgotten when it changes, and so it should
	 * when the map's texture is made again (empty).
	 */
	void setCells(int n)
	{
		if (n != cells)
		{
			clearLight();
			cells = n;
		}
	}

	/** The light map's texture came back empty: nothing is lit by it. */
	void clearLight()
	{
		Arrays.fill(grid, null);
		Arrays.fill(sums, 0);
	}

	/** The rooms of a new scene (or none, null): their tiles are counted and the stored light summed over them. */
	void setRooms(RoomMap map)
	{
		if (map == rooms)
		{
			return;
		}
		rooms = map;
		fresh = true;
		summarized = false;
		age = Float.NaN;
		skyFor = null;
		skyDone = null;
		skyDoneFor = null;
		int n = 1;
		if (map != null)
		{
			for (short id : map.rooms)
			{
				n = Math.max(n, id + 1);
			}
		}
		tiles = new int[n];
		sums = new double[n];
		sky = new float[n];
		now = new float[n];
		goal = new float[n];
		if (table.length < n * 4)
		{
			table = new float[n * 4];
		}
		Arrays.fill(table, 0);
		used = n;
		++version;
		if (map == null)
		{
			return;
		}
		int size = map.size;
		for (int plane = 0; plane < RoomMap.PLANES; ++plane)
		{
			float[] g = cells == size * CELLS ? grid[plane] : null;
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int room = map.room(plane, x, y);
					if (room <= 0)
					{
						continue;
					}
					++tiles[room];
					if (g != null)
					{
						for (int j = 0; j < CELLS; ++j)
						{
							for (int i = 0; i < CELLS; ++i)
							{
								sums[room] += g[(y * CELLS + j) * cells + x * CELLS + i];
							}
						}
					}
				}
			}
		}
	}

	/** A block of the light map is sent (LightMap.Sink): a null a is cleared. */
	@Override
	public void block(int plane, int bx, int bz, short[] a, short[] b)
	{
		RoomMap map = rooms;
		int side = LightMap.BLOCK;
		if (cells == 0 || plane < 0 || plane >= grid.length)
		{
			return;
		}
		if (grid[plane] == null)
		{
			if (a == null)
			{
				return;
			}
			grid[plane] = new float[cells * cells];
		}
		float[] g = grid[plane];
		for (int j = 0; j < side; ++j)
		{
			int z = bz * side + j;
			if (z >= cells)
			{
				break;
			}
			for (int i = 0; i < side; ++i)
			{
				int x = bx * side + i;
				if (x >= cells)
				{
					break;
				}
				float score = 0;
				if (a != null)
				{
					// the first height layer, the floor's
					int c = (j * side + i) * 4;
					float lum = 0.2126f * Half.fromHalf(a[c]) + 0.7152f * Half.fromHalf(a[c + 1])
						+ 0.0722f * Half.fromHalf(a[c + 2]);
					score = Math.min(lum * FLOOR_NDL, CELL_CAP);
				}
				int k = z * cells + x;
				float old = g[k];
				if (score == old)
				{
					continue;
				}
				g[k] = score;
				int room = map != null ? map.room(plane, x / CELLS, z / CELLS) : 0;
				if (room > 0)
				{
					sums[room] += score - old;
				}
			}
		}
	}

	/** The mean of the light map's cell scores over a room's floor. */
	float lightScore(int room)
	{
		return room > 0 && room < tiles.length && tiles[room] > 0 ? (float) (sums[room] / (tiles[room] * CELLS * CELLS)) : 0;
	}

	/** The sky's share of a room (0 to 1). */
	float skyShare(int room)
	{
		return room > 0 && room < sky.length ? sky[room] : 0;
	}

	/** The room's score now: its light plus the sky coming in, which gives a floor it reaches in full skyLuminance. */
	float score(int room, float skyLuminance)
	{
		return lightScore(room) + skyShare(room) * skyLuminance;
	}

	/**
	 * The light on an upward surface outdoors in the open, from the sun's height (degrees) as sky.glsl has the sun and
	 * ambient rise and fall; clear weather.
	 */
	static float outdoorLight(float sunAltitude)
	{
		float sun = SUN_LUMINANCE * smoothstep(-4, 10, sunAltitude) * Math.max((float) Math.sin(Math.toRadians(sunAltitude)), 0);
		return sun + DAY_AMBIENT_LUMINANCE * smoothstep(-10, 15, sunAltitude);
	}

	/** What a floor the sky reaches in full gets of the light now. */
	static float skyLuminance(float sunAltitude)
	{
		return Math.max(SKY_NIGHT_LUMINANCE, SKY_SHARE * outdoorLight(sunAltitude));
	}

	private static float smoothstep(float a, float b, float v)
	{
		float t = Math.max(0, Math.min(1, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	/**
	 * The minimum room light for the time of day, 0 for off: by day DAY_SHARE of the outdoor light (never less than the
	 * night's), easing to TARGET_NIGHT as the sun sets.
	 *
	 * @param sunAltitude degrees
	 * @param byDay whether there is a target by day too
	 * @param brightness the setting, 1 for 100%
	 */
	static float target(float sunAltitude, boolean byDay, float brightness)
	{
		float night = BuildingLights.nightFactor(sunAltitude);
		float day = Math.max(TARGET_NIGHT, DAY_SHARE * outdoorLight(sunAltitude));
		float t = byDay ? day + (TARGET_NIGHT - day) * night : TARGET_NIGHT * night;
		return t * Math.max(0, brightness);
	}

	/**
	 * The fill's share of the target for a shortfall of this share of it: 0 when the score is past the target by
	 * KNEE, easing in over twice that, then one to one. Continuous with a continuous slope.
	 */
	static float knee(float shortfall)
	{
		if (shortfall <= -KNEE)
		{
			return 0;
		}
		if (shortfall >= KNEE)
		{
			return shortfall;
		}
		float d = shortfall + KNEE;
		return d * d / (4 * KNEE);
	}

	/** The fill for a score against a target, in luminance. */
	static float fill(float score, float target)
	{
		return target > 0 ? target * knee((target - score) / target) : 0;
	}

	// -------------------------------------------------------------------------------------------------------
	// Sky

	/**
	 * The sky's share of every room, from the walls: the mean over its floor of the light that flows in from the open
	 * sky (SkyField), 0 for a room closed in, towards 1 for one open on a wide side.
	 *
	 * @param floors a bit per plane of the extended tiles that have a floor (BuildingLights.getFloors)
	 */
	static float[] skyShares(RoomMap map, WallMap walls, byte[] floors, float reachTiles)
	{
		float[] out = new float[map.rooms.length == 0 ? 1 : maxRoom(map) + 1];
		int[] count = new int[out.length];
		float falloff = SkyField.falloffOf(reachTiles);
		int size = map.size;
		for (int plane = 0; plane < RoomMap.PLANES; ++plane)
		{
			boolean any = false;
			for (int x = 0; x < size && !any; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					if (map.room(plane, x, y) > 0)
					{
						any = true;
						break;
					}
				}
			}
			if (!any)
			{
				continue;
			}
			boolean[] covered = SkyField.covered(map, floors, plane);
			float[] light = SkyField.propagate(walls, covered, plane, falloff);
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int room = map.room(plane, x, y);
					if (room > 0)
					{
						int t = x * size + y;
						out[room] += (light[t * 2] + light[t * 2 + 1]) * 0.5f;
						++count[room];
					}
				}
			}
		}
		for (int i = 1; i < out.length; ++i)
		{
			if (count[i] > 0)
			{
				out[i] /= count[i];
			}
		}
		return out;
	}

	private static int maxRoom(RoomMap map)
	{
		int n = 0;
		for (short id : map.rooms)
		{
			n = Math.max(n, id);
		}
		return n;
	}

	/** Follow the walls: the sky's share of the rooms is worked out again on a worker when they change. */
	void followWalls(WallMap walls, byte[] floors, float reachTiles)
	{
		RoomMap map = rooms;
		if (map == null || walls == null || floors == null || walls.size != map.size)
		{
			return;
		}
		if (skyFor != map || skyWalls != walls)
		{
			skyFor = map;
			skyWalls = walls;
			final Object request = new Object();
			skyRequest = request;
			if (worker == null)
			{
				worker = Executors.newSingleThreadExecutor(r ->
				{
					Thread t = new Thread(r, "RuneLight room sky");
					t.setDaemon(true);
					t.setPriority(Thread.MIN_PRIORITY);
					return t;
				});
			}
			worker.execute(() ->
			{
				float[] shares = skyShares(map, walls, floors, reachTiles);
				if (skyRequest == request)
				{
					skyDoneFor = map;
					skyDone = shares;
				}
			});
		}
		float[] done = skyDone;
		if (done != null && skyDoneFor == map && done.length == sky.length)
		{
			System.arraycopy(done, 0, sky, 0, done.length);
			skyDone = null;
		}
	}

	/** Put the sky's shares in directly (tests, and anything that has found them another way). */
	void setSky(float[] shares)
	{
		System.arraycopy(shares, 0, sky, 0, Math.min(shares.length, sky.length));
	}

	void free()
	{
		if (worker != null)
		{
			worker.shutdownNow();
			worker = null;
		}
	}

	// -------------------------------------------------------------------------------------------------------
	// Fill

	/**
	 * Work out every room's fill and move the table towards it.
	 *
	 * @param dt seconds since the last call
	 * @param target the minimum room light now ({@link #target}), 0 for no fill
	 * @param sunAltitude degrees, for the sky's light
	 * @return whether the table changed
	 */
	boolean update(float dt, float target, float sunAltitude)
	{
		float level = skyLuminance(sunAltitude);
		lastTarget = target;
		boolean changed = false;
		float ease = fresh ? 1 : 1 - (float) Math.exp(-Math.max(dt, 0) / EASE_SECONDS);
		for (int room = 1; room < used; ++room)
		{
			if (tiles[room] == 0)
			{
				continue;
			}
			float want = fill(score(room, level), target);
			goal[room] = want;
			float next = now[room] + (want - now[room]) * ease;
			if (Math.abs(want - next) < 1e-3f)
			{
				next = want;
			}
			if (next != now[room])
			{
				now[room] = next;
				table[room * 4] = next;
				changed = true;
			}
		}
		fresh = false;
		if (changed)
		{
			++version;
		}
		if (!summarized && rooms != null)
		{
			age = Float.isNaN(age) ? 0 : age + dt;
			if (age >= SUMMARY_DELAY)
			{
				summarized = true;
				int[] s = summary();
				log.info("Room light: {} rooms, target {} (sun {} degrees); fill none in {}, some in {}, the full target in {}; "
						+ "mean score {}", s[0] + s[1] + s[2], target, sunAltitude, s[0], s[1], s[2], meanScore(level));
			}
		}
		return changed;
	}

	/** Rooms with no fill, some, and the full target, by the fills they are heading for. */
	int[] summary()
	{
		int[] out = new int[3];
		for (int room = 1; room < used; ++room)
		{
			if (tiles[room] == 0)
			{
				continue;
			}
			float share = lastTarget > 0 ? goal[room] / lastTarget : 0;
			++out[share <= SUMMARY_EDGE ? 0 : share >= 1 - SUMMARY_EDGE ? 2 : 1];
		}
		return out;
	}

	private float meanScore(float level)
	{
		float sum = 0;
		int n = 0;
		for (int room = 1; room < used; ++room)
		{
			if (tiles[room] > 0)
			{
				sum += score(room, level);
				++n;
			}
		}
		return n > 0 ? sum / n : 0;
	}

	/** The fill of a room now, in luminance (what the table holds). */
	float fillOf(int room)
	{
		return room > 0 && room < used ? now[room] : 0;
	}

	/** The room's fill it is heading for. */
	float goalOf(int room)
	{
		return room > 0 && room < used ? goal[room] : 0;
	}

	/** The shader's table: RGBA texels from room id 0, x the fill. */
	float[] table()
	{
		return table;
	}

	/** How many texels of the table are in use (room ids below it). */
	int used()
	{
		return used;
	}

	/** Changes whenever the table does. */
	int version()
	{
		return version;
	}
}
