package com.midonarhel.lumina.light;

import com.midonarhel.lumina.visibility.BuildingLights;
import com.midonarhel.lumina.visibility.RoomMap;

import java.util.Arrays;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The interior brightness: the soft fill every building room gets, one level for all of them, from the Interior
 * brightness setting and the time of day alone. What lights a room besides (its lamps, the sky through its doorway)
 * doesn't change it: a room with three torches gets the same fill as a room with none (the user: "interior brightness
 * should not be dependent on number of point lights and so on. We just have interior brightness").
 *
 * The level ({@link #target}): TARGET_NIGHT at night, by day DAY_SHARE of the light a sunlit floor outdoors gets
 * ({@link #outdoorLight}), times the setting. {@link #update} eases the rooms' fill to it in time, and {@link #table}
 * is what the shader reads by room id (rooms.glsl), x being the fill's luminance; the composite spreads it flat over
 * the room like ambient, fading toward its doorways and open sides (composite_frag.glsl).
 *
 * Calibration, in the composite's light on an upward surface (linear luminance; outdoors at 45 degrees of sun about
 * 2.7: the sun's 2.64 times its height, plus the ambient and sky light 0.8): at night 0.40, a third above what the
 * constant fill it replaced gave at its centre (BuildingLights.FILL_TINT of luminance 0.312); by day 0.42 of the
 * outdoor light, 1.13 at 45 degrees.
 */
public final class RoomLight
{
	private static final Logger log = LoggerFactory.getLogger(RoomLight.class);

	/** The rooms' light at night, in luminance: the old constant fill's, dim but readable. */
	static final float TARGET_NIGHT = 0.40f;
	/** By day the rooms' light is this share of the light a sunlit floor outdoors gets: dimmer than outside, legible. */
	static final float DAY_SHARE = 0.42f;
	/** What the sun gives a floor outdoors at its brightest (the sun's strength 2.9, in luminance), and the ambient and sky light. */
	static final float SUN_LUMINANCE = 2.64f;
	static final float DAY_AMBIENT_LUMINANCE = 0.8f;
	/** Seconds in which the fill follows its level. */
	static final float EASE_SECONDS = 0.4f;
	/** Seconds after the rooms are known that the level is told in the log. */
	static final float SUMMARY_DELAY = 8;

	private RoomMap rooms;
	// per room id: floor tiles, and its fill now
	private int[] tiles = new int[1];
	private float[] now = new float[1];
	// x per room id, the table the shader reads
	private float[] table = new float[4];
	private int used = 1;
	private int version;
	private boolean fresh;
	private float age = Float.NaN;
	private boolean summarized;
	private float level;

	/** The rooms of a new scene (or none, null): their tiles are counted. */
	public void setRooms(RoomMap map)
	{
		setRooms(map, false);
	}

	/**
	 * The rooms (or none, null): their tiles are counted.
	 *
	 * @param sameScene whether they are the scene's rooms built again for new walls (a door opened or shut): each room
	 * then keeps the fill of the room that held most of its tiles and eases from there to the level. Otherwise every
	 * fill starts at the level at once. Before, a door reset every room of the scene: each jumped to the full fill and
	 * faded back over half a second (the user, 2026-11-02: "it resets fading in again").
	 */
	public void setRooms(RoomMap map, boolean sameScene)
	{
		if (map == rooms)
		{
			return;
		}
		RoomMap before = rooms;
		float[] nowBefore = now;
		rooms = map;
		fresh = true;
		summarized = false;
		age = Float.NaN;
		int n = 1;
		if (map != null)
		{
			for (short id : map.rooms)
			{
				n = Math.max(n, id + 1);
			}
		}
		tiles = new int[n];
		now = new float[n];
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
		if (sameScene && before != null && before.rooms.length == map.rooms.length)
		{
			carry(before, nowBefore);
		}
	}

	/** Each room takes the fill of the room before that held most of its tiles (setRooms, sameScene). */
	private void carry(RoomMap before, float[] nowBefore)
	{
		// tiles per (room now, room before)
		java.util.Map<Integer, Integer> shared = new java.util.HashMap<>();
		short[] a = rooms.rooms, b = before.rooms;
		for (int i = 0; i < a.length; ++i)
		{
			if (a[i] > 0 && b[i] > 0 && b[i] < nowBefore.length)
			{
				shared.merge(a[i] << 16 | b[i], 1, Integer::sum);
			}
		}
		int[] best = new int[used], most = new int[used];
		for (java.util.Map.Entry<Integer, Integer> e : shared.entrySet())
		{
			int room = e.getKey() >>> 16;
			if (e.getValue() > most[room])
			{
				most[room] = e.getValue();
				best[room] = e.getKey() & 0xFFFF;
			}
		}
		for (int room = 1; room < used; ++room)
		{
			int was = best[room];
			if (was > 0)
			{
				now[room] = nowBefore[was];
				table[room * 4] = now[room];
			}
		}
		// a room no room was on before (none, unless the walls made one of open ground) eases in from no fill
		fresh = false;
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

	private static float smoothstep(float a, float b, float v)
	{
		float t = Math.max(0, Math.min(1, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}

	/**
	 * The rooms' light for the time of day: by day DAY_SHARE of the outdoor light (never less than the night's), easing
	 * to TARGET_NIGHT as the sun sets; times the Interior brightness setting.
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

	public void free()
	{
	}

	/**
	 * Move every room's fill toward the level.
	 *
	 * @param dt seconds since the last call
	 * @param target the rooms' light now ({@link #target}), 0 for no fill
	 * @return whether the table changed
	 */
	public boolean update(float dt, float target)
	{
		level = target;
		boolean changed = false;
		float ease = fresh ? 1 : 1 - (float) Math.exp(-Math.max(dt, 0) / EASE_SECONDS);
		for (int room = 1; room < used; ++room)
		{
			if (tiles[room] == 0)
			{
				continue;
			}
			float next = now[room] + (target - now[room]) * ease;
			if (Math.abs(target - next) < 1e-3f)
			{
				next = target;
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
				int n = 0;
				for (int room = 1; room < used; ++room)
				{
					n += tiles[room] > 0 ? 1 : 0;
				}
				log.info("Room light: {} rooms, each filled to {}", n, target);
			}
		}
		return changed;
	}

	/** The fill of a room now, in luminance (what the table holds). */
	float fillOf(int room)
	{
		return room > 0 && room < used ? now[room] : 0;
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
