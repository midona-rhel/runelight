package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 117 HD's water definitions (water_overrides.json, converted by tools/import_117hd_water.py), matched as 117 HD
 * matches them: which tiles' water its tile overrides make another type, and which objects' faces are the game's
 * ripples it hides.
 */
final class WaterOverrides
{
	private static final Logger log = LoggerFactory.getLogger(WaterOverrides.class);

	private static final class Data
	{
		List<Tile> tiles;
		List<Effect> effects;
	}

	private static final class Tile
	{
		int[] overlays, underlays;
		String type;
		int[][] area;
		transient int index, waterType;
	}

	private static final class Effect
	{
		int[] objects;
		int[][] area;
		boolean hide;
		List<Rule> rules;
	}

	private static final class Rule
	{
		// any of these, each all of its clauses: [variable, operator, value]
		Object[][][] when;
		boolean hide;
	}

	private static WaterOverrides loaded = new WaterOverrides(new Data());

	// tile overrides by overlay id and by underlay id, each in the file's order, and those matching any id
	private final Map<Integer, List<Tile>> overlays = new HashMap<>(), underlays = new HashMap<>();
	private final List<Tile> anyMatch = new ArrayList<>();
	private final Map<Integer, Effect> effects = new HashMap<>();

	private WaterOverrides(Data d)
	{
		if (d.tiles != null)
		{
			for (int i = 0; i < d.tiles.size(); ++i)
			{
				Tile t = d.tiles.get(i);
				t.index = i;
				t.waterType = t.type == null ? WaterMap.NONE : typeOf(t.type);
				if (t.overlays == null && t.underlays == null)
				{
					anyMatch.add(t);
				}
				add(overlays, t.overlays, t);
				add(underlays, t.underlays, t);
			}
		}
		if (d.effects != null)
		{
			for (Effect e : d.effects)
			{
				for (int id : e.objects)
				{
					effects.putIfAbsent(id, e);
				}
			}
		}
	}

	private static void add(Map<Integer, List<Tile>> map, int[] ids, Tile t)
	{
		if (ids != null)
		{
			for (int id : ids)
			{
				map.computeIfAbsent(id, k -> new ArrayList<>()).add(t);
			}
		}
	}

	private static int typeOf(String name)
	{
		switch (name)
		{
			case "OCEAN":
				return WaterMap.OCEAN;
			case "OCEAN_DARK":
				return WaterMap.OCEAN_DARK;
			case "OCEAN_PALE":
				return WaterMap.OCEAN_PALE;
			case "SWAMP":
				return WaterMap.SWAMP;
			case "CAVE":
				return WaterMap.CAVE;
			default:
				return WaterMap.FRESH;
		}
	}

	static void load(Gson gson)
	{
		try (InputStream is = WaterOverrides.class.getResourceAsStream("water_overrides.json"))
		{
			if (is == null)
			{
				log.warn("water_overrides.json missing");
				return;
			}
			loaded = new WaterOverrides(gson.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), Data.class));
		}
		catch (Exception ex)
		{
			log.error("Unable to load water_overrides.json", ex);
		}
	}

	static WaterOverrides get()
	{
		return loaded;
	}

	/**
	 * The water type 117 HD's tile overrides give a tile at world (x, y, plane) with these overlay and underlay ids,
	 * or NONE where the one matching gives none (the tile keeps its texture's type).
	 */
	int tileType(int x, int y, int plane, int overlayId, int underlayId)
	{
		Tile match = first(overlays.get(overlayId), x, y, plane);
		if (match == null)
		{
			match = first(underlays.get(underlayId), x, y, plane);
		}
		int index = match == null ? Integer.MAX_VALUE : match.index;
		for (Tile t : anyMatch)
		{
			if (t.index > index)
			{
				break;
			}
			if (contains(t.area, x, y, plane))
			{
				match = t;
				break;
			}
		}
		return match == null ? WaterMap.NONE : match.waterType;
	}

	private static Tile first(List<Tile> list, int x, int y, int plane)
	{
		if (list != null)
		{
			for (Tile t : list)
			{
				if (contains(t.area, x, y, plane))
				{
					return t;
				}
			}
		}
		return null;
	}

	private static boolean contains(int[][] area, int x, int y, int plane)
	{
		for (int[] b : area)
		{
			if (b[0] <= x && x <= b[3] && b[1] <= y && y <= b[4] && b[2] <= plane && plane <= b[5])
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Whether the object has ripples 117 HD hides: then {@link #hidesFace} says which faces.
	 */
	boolean hasRipples(int objectId)
	{
		return effects.containsKey(objectId);
	}

	/**
	 * Whether a face of the object, at world (x, y, plane), is one of the game's ripples (117 HD's hideAsWaterEffect):
	 * the whole model, or for an untextured face the first of its color rules matching the face's color (alpha, and
	 * the packed HSL of its first color).
	 */
	boolean hidesFace(int objectId, int x, int y, int plane, boolean textured, int transparency, int hsl)
	{
		Effect e = effects.get(objectId);
		if (e == null || e.area != null && !contains(e.area, x, y, plane))
		{
			return false;
		}
		if (e.hide)
		{
			return true;
		}
		if (textured || e.rules == null)
		{
			return false;
		}
		int ahsl = (0xFF - (transparency & 0xFF)) << 16 | hsl & 0xFFFF;
		for (Rule r : e.rules)
		{
			if (matches(r.when, ahsl))
			{
				return r.hide;
			}
		}
		return false;
	}

	private static boolean matches(Object[][][] when, int ahsl)
	{
		for (Object[][] all : when)
		{
			boolean ok = true;
			for (Object[] c : all)
			{
				int v = value((String) c[0], ahsl), n = ((Number) c[2]).intValue();
				switch ((String) c[1])
				{
					case "==":
						ok = v == n;
						break;
					case "!=":
						ok = v != n;
						break;
					case "<":
						ok = v < n;
						break;
					case "<=":
						ok = v <= n;
						break;
					case ">":
						ok = v > n;
						break;
					default:
						ok = v >= n;
						break;
				}
				if (!ok)
				{
					break;
				}
			}
			if (ok)
			{
				return true;
			}
		}
		return false;
	}

	private static int value(String variable, int ahsl)
	{
		switch (variable)
		{
			case "a":
				return ahsl >>> 16 & 0xFF;
			case "h":
				return ahsl >>> 10 & 0x3F;
			case "s":
				return ahsl >>> 7 & 0x7;
			case "l":
				return ahsl & 0x7F;
			case "hsl":
				return ahsl & 0xFFFF;
			default:
				return ahsl;
		}
	}
}
