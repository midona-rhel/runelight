package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.SkyField;
import com.midonarhel.lumina.visibility.WallMap;

import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.RoomMap;
import com.midonarhel.lumina.visibility.SkyField;
import com.midonarhel.lumina.visibility.WallMap;

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
 * - its lamps ({@link #lampScores}): each floor tile's light from the fixed lamps there now at their steady strength
 *   (SceneLights.steadyLamps), as the shader has it (light/lamp.glsl: the falloff, the walls, the cosine at the floor),
 *   at most TILE_CAP, so that a torch's hot spot cannot hide a room's dark rest. Worked out on a worker every
 *   LAMP_SECONDS, and when the rooms or walls change, so a night-only lamp counts only at night;
 * - the sky coming in: how much of the room's floor the sky light reaches through its doors and openings (SkyField, as
 *   the skylight spreads it; found on a worker whenever the walls change), times what a floor the sky reaches in full
 *   gets of the daylight now ({@link #skyLuminance}).
 *
 * The fill is the shortfall against a target, with a soft knee: fill = target * knee((target - score) / target). The
 * target is the "minimum room light" ({@link #target}): TARGET_NIGHT at night, by day DAY_SHARE of the light a sunlit
 * floor outdoors gets ({@link #outdoorLight}), scaled by the Brightness setting. {@link #update} works the fills out
 * and eases them in time (a room whose lamps have just come on does not pop). {@link #table} is what the shader
 * reads by room id (rooms.glsl), x being the fill's luminance.
 *
 * Calibration, in the composite's light on an upward surface (linear luminance; outdoors at 45 degrees of sun about
 * 2.7: the sun's 2.64 times its height, plus the ambient and sky light 0.8):
 * - Night: a fully dark room gets TARGET_NIGHT, 0.40, a third above what the constant fill it replaced gave at its
 *   centre (BuildingLights.FILL_TINT of luminance 0.312). A room with a hearth or torches scores past it (a torch of strength 9
 *   and radius 500 gives the floor within three tiles of it more) and gets none.
 * - Day: the target is DAY_SHARE, 0.42, of the outdoor light: 1.13 at 45 degrees. The fill's shaping (composite_frag.glsl)
 *   gives a room's middle all of it and its walls a third, so a room reads about a third to two fifths of the outdoors on
 *   average: a windowless room is lifted to that (score 0, fill 1.13); one with a door open to the day scores the
 *   SKY_SHARE (0.22) of the outdoors the doorway's share of its floor gets, 0.59 at most, and is lifted by the rest; a room with a hearth (score past 1) gets nothing, and stays as lit
 *   as its fire makes it. Before this a pub by day read 11 percent of the grass outside; a fully lit room still does
 *   as it did.
 */
public final class RoomLight
{
	private static final Logger log = LoggerFactory.getLogger(RoomLight.class);

	/** The least light a room gets at night, in luminance: the old constant fill's, dim but readable. */
	static final float TARGET_NIGHT = 0.40f;
	/** By day interiors are lifted to this share of the light a sunlit floor outdoors gets: dimmer than outside, legible. */
	static final float DAY_SHARE = 0.42f;
	/** Of the target (the shortfall) over which the fill eases in: no fill at a score of target * (1 + KNEE). */
	static final float KNEE = 0.25f;
	/** The most a floor tile counts for, in luminance: about twice the day's target. */
	static final float TILE_CAP = 1.5f;
	/** Seconds between the lamps' scores being worked out again. */
	static final float LAMP_SECONDS = 0.5f;
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

	private RoomMap rooms;
	// per room id: floor tiles, the lamps' mean score on them, the sky's share
	private int[] tiles = new int[1];
	private float[] lamp = new float[1];
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
	// the lamps' scores per room as the worker found them, and the rooms they are for; whether it is at it, how long
	// since they were asked for, and for which walls
	private volatile float[] lampDone;
	private volatile Object lampDoneFor;
	private volatile boolean lampBusy;
	private float lampAge = Float.POSITIVE_INFINITY;
	private Object lampWalls;

	// -------------------------------------------------------------------------------------------------------
	// Score

	/** The rooms of a new scene (or none, null): their tiles are counted; their lamps are worked out again. */
	public void setRooms(RoomMap map)
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
		lampDone = null;
		lampDoneFor = null;
		lampAge = Float.POSITIVE_INFINITY;
		int n = 1;
		if (map != null)
		{
			for (short id : map.rooms)
			{
				n = Math.max(n, id + 1);
			}
		}
		tiles = new int[n];
		lamp = new float[n];
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
		for (short room : map.rooms)
		{
			if (room > 0)
			{
				++tiles[room];
			}
		}
	}

	/** The lamps' mean score over a room's floor. */
	float lightScore(int room)
	{
		return room > 0 && room < lamp.length ? lamp[room] : 0;
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
	 * The minimum room light for the time of day: by day DAY_SHARE of the outdoor light (never less than the night's),
	 * easing to TARGET_NIGHT as the sun sets.
	 *
	 * @param sunAltitude degrees
	 * @param brightness the setting, 1 for 100%; 0 turns the fill off
	 */
	public static float target(float sunAltitude, float brightness)
	{
		float night = BuildingLights.nightFactor(sunAltitude);
		float day = Math.max(TARGET_NIGHT, DAY_SHARE * outdoorLight(sunAltitude));
		return (day + (TARGET_NIGHT - day) * night) * Math.max(0, brightness);
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
	public void followWalls(WallMap walls, byte[] floors, float reachTiles)
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
			worker().execute(() ->
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

	// -------------------------------------------------------------------------------------------------------
	// Lamps

	/**
	 * Every room's lamp score: the mean over its floor tiles of the light the lamps give the tile's middle, as
	 * light/lamp.glsl gives it (luminance times 117 HD's falloff, none past a wall of the lamp's storey, times the
	 * cosine at the upward floor), each tile at most TILE_CAP.
	 */
	static float[] lampScores(RoomMap map, WallMap walls, java.util.List<PointLight> lamps)
	{
		int n = map.rooms.length == 0 ? 1 : maxRoom(map) + 1;
		int size = map.size;
		float[] tile = new float[RoomMap.PLANES * size * size];
		for (PointLight l : lamps)
		{
			int plane = l.plane;
			if (plane < 0 || plane >= RoomMap.PLANES || l.radius <= 0)
			{
				continue;
			}
			float luminance = (0.2126f * l.r + 0.7152f * l.g + 0.0722f * l.b) * l.intensity;
			float r2 = l.radius * l.radius;
			int x0 = Math.max((int) Math.floor((l.x - l.radius) / RoomMap.TILE) + map.offset, 0);
			int y0 = Math.max((int) Math.floor((l.z - l.radius) / RoomMap.TILE) + map.offset, 0);
			int x1 = Math.min((int) Math.floor((l.x + l.radius) / RoomMap.TILE) + map.offset, size - 1);
			int y1 = Math.min((int) Math.floor((l.z + l.radius) / RoomMap.TILE) + map.offset, size - 1);
			for (int x = x0; x <= x1; ++x)
			{
				for (int y = y0; y <= y1; ++y)
				{
					int i = map.index(plane, x, y);
					if (map.rooms[i] <= 0)
					{
						continue;
					}
					float cx = (x - map.offset + 0.5f) * RoomMap.TILE, cz = (y - map.offset + 0.5f) * RoomMap.TILE;
					// y points down: the floor below the lamp is lit from above
					float dx = l.x - cx, dy = map.floors[i] - l.y, dz = l.z - cz;
					float d2 = dx * dx + dy * dy + dz * dz;
					if (d2 >= r2 || dy <= 0 || walls != null && walls.blocked(plane, cx, cz, l.ox, l.oz))
					{
						continue;
					}
					float d = (float) Math.sqrt(d2);
					float att = 1 - d / l.radius;
					tile[i] += luminance * att * att * (dy / Math.max(d, 1));
				}
			}
		}
		float[] sum = new float[n];
		int[] count = new int[n];
		for (int i = 0; i < tile.length; ++i)
		{
			int room = map.rooms[i];
			if (room > 0 && room < n)
			{
				sum[room] += Math.min(tile[i], TILE_CAP);
				++count[room];
			}
		}
		for (int room = 1; room < n; ++room)
		{
			sum[room] = count[room] > 0 ? sum[room] / count[room] : 0;
		}
		return sum;
	}

	/**
	 * Follow the lamps: their scores are worked out again on the worker every LAMP_SECONDS, and at once when the rooms
	 * or the walls change.
	 *
	 * @param lamps called for the lamps when they are wanted (SceneLights.steadyLamps)
	 * @param dt seconds since the last call
	 */
	public void followLamps(java.util.function.Consumer<java.util.List<PointLight>> lamps, WallMap walls, float dt)
	{
		RoomMap map = rooms;
		float[] done = lampDone;
		if (done != null && lampDoneFor == map && done.length == lamp.length)
		{
			System.arraycopy(done, 0, lamp, 0, done.length);
			lampDone = null;
		}
		if (map == null)
		{
			return;
		}
		lampAge += Math.max(dt, 0);
		if (lampBusy || lampAge < LAMP_SECONDS && walls == lampWalls)
		{
			return;
		}
		lampAge = 0;
		lampWalls = walls;
		java.util.List<PointLight> list = new java.util.ArrayList<>();
		lamps.accept(list);
		lampBusy = true;
		worker().execute(() ->
		{
			try
			{
				float[] scores = lampScores(map, walls, list);
				lampDoneFor = map;
				lampDone = scores;
			}
			finally
			{
				lampBusy = false;
			}
		});
	}

	private ExecutorService worker()
	{
		if (worker == null)
		{
			worker = Executors.newSingleThreadExecutor(r ->
			{
				Thread t = new Thread(r, "Lumina room light");
				t.setDaemon(true);
				t.setPriority(Thread.MIN_PRIORITY);
				return t;
			});
		}
		return worker;
	}

	/** Put the lamps' scores in directly (tests). */
	void setLamps(float[] scores)
	{
		System.arraycopy(scores, 0, lamp, 0, Math.min(scores.length, lamp.length));
	}

	/** Put the sky's shares in directly (tests, and anything that has found them another way). */
	void setSky(float[] shares)
	{
		System.arraycopy(shares, 0, sky, 0, Math.min(shares.length, sky.length));
	}

	public void free()
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
	public boolean update(float dt, float target, float sunAltitude)
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
