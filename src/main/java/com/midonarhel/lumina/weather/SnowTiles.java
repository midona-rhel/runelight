package com.midonarhel.lumina.weather;

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
 * The ground tiles snow never settles on, as 117 HD's winter theme decides them (snow_tiles.json, from
 * tools/import_117hd_snow_tiles.py): a tile matches one of its tile overrides as 117 HD matches them
 * (TileOverrideManager.getOverrideBeforeReplacements: by its overlay id, then its underlay id, the first in the file's
 * order whose area holds the tile, unless an override for any id comes before it), then the override's replacements
 * resolve under their conditions with 117 HD's default options. A tile whose resolution never takes a winter
 * replacement keeps its look in 117 HD's winter: a castle floor, a bridge, a path, a dock. Here it keeps clear of snow.
 */
public final class SnowTiles
{
	private static final Logger log = LoggerFactory.getLogger(SnowTiles.class);
	static final String FILE = "snow_tiles.json";
	// an overlay id among the ids (the importer's; 117 HD's own flag is the sign bit)
	static final int OVERLAY = 1 << 30;
	// how deep a replacement chain may go before it is taken as a loop
	private static final int MAX_CHAIN = 32;

	/** As in snow_tiles.json. */
	static final class Data
	{
		List<Row> overrides;
	}

	static final class Row
	{
		String name;
		int[] ids;
		int[][] boxes;
		List<List<Object>> replacements;
		Boolean snowy;
	}

	private static final class Override
	{
		String name;
		int index;
		boolean snowy;
		int[][] boxes;
		int[] targets;
		Condition[] conditions;

		boolean contains(int x, int y, int z)
		{
			for (int[] b : boxes)
			{
				if (x >= b[0] && y >= b[1] && z >= b[2] && x <= b[3] && y <= b[4] && z <= b[5])
				{
					return true;
				}
			}
			return false;
		}
	}

	private static volatile SnowTiles instance;

	private final Override[] all;
	private final Map<Integer, List<Override>> byId = new HashMap<>();
	private final List<Override> anyId = new ArrayList<>();

	private SnowTiles(Data d)
	{
		all = new Override[d.overrides.size()];
		for (int i = 0; i < all.length; ++i)
		{
			Row r = d.overrides.get(i);
			Override o = new Override();
			o.name = r.name;
			o.index = i;
			o.snowy = Boolean.TRUE.equals(r.snowy);
			o.boxes = r.boxes;
			int n = r.replacements == null ? 0 : r.replacements.size();
			o.targets = new int[n];
			o.conditions = new Condition[n];
			for (int k = 0; k < n; ++k)
			{
				List<Object> rep = r.replacements.get(k);
				o.targets[k] = ((Number) rep.get(0)).intValue();
				@SuppressWarnings("unchecked") List<String> conds = (List<String>) rep.get(1);
				o.conditions[k] = Condition.any(conds);
			}
			all[i] = o;
			if (o.boxes == null)
			{
				continue;
			}
			if (r.ids != null)
			{
				for (int id : r.ids)
				{
					byId.computeIfAbsent(id, k -> new ArrayList<>()).add(o);
				}
			}
			else
			{
				anyId.add(o);
			}
		}
	}

	/** The loaded tiles, or none (snow everywhere) when the file is missing or broken. */
	public static SnowTiles get(Gson gson)
	{
		SnowTiles s = instance;
		if (s == null)
		{
			synchronized (SnowTiles.class)
			{
				if (instance == null)
				{
					instance = load(gson);
				}
				s = instance;
			}
		}
		return s;
	}

	private static SnowTiles load(Gson gson)
	{
		try (InputStream in = SnowTiles.class.getResourceAsStream(FILE))
		{
			if (in != null)
			{
				Data d = gson.fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), Data.class);
				if (d != null && d.overrides != null)
				{
					SnowTiles s = new SnowTiles(d);
					log.debug("Loaded {} tile overrides for the snow, {} by id, {} for any", s.all.length, s.byId.size(),
						s.anyId.size());
					return s;
				}
			}
		}
		catch (Exception ex)
		{
			log.warn("Could not load {}: snow on every tile", FILE, ex);
		}
		return new SnowTiles(empty());
	}

	private static Data empty()
	{
		Data d = new Data();
		d.overrides = new ArrayList<>();
		return d;
	}

	/**
	 * Whether snow keeps off a ground tile.
	 *
	 * @param x, y, z  world tile and plane
	 * @param overlay  the tile's overlay id as the scene has it, underlay its underlay id
	 * @param swHsl    the tile's south western colour, the game's packed HSL (117 HD's h, s, l)
	 */
	public boolean clear(int x, int y, int z, int overlay, int underlay, int swHsl)
	{
		return clear(x, y, z, overlay, underlay, swHsl, null);
	}

	/** {@link #clear}, the overrides it went through written to trace (for tests and the log). */
	boolean clear(int x, int y, int z, int overlay, int underlay, int swHsl, StringBuilder trace)
	{
		if (all.length == 0)
		{
			return false;
		}
		Override match = null;
		int index = Integer.MAX_VALUE;
		int[] ids = {OVERLAY | overlay, underlay};
		outer:
		for (int id : ids)
		{
			List<Override> list = byId.get(id);
			if (list == null)
			{
				continue;
			}
			for (Override o : list)
			{
				if (o.contains(x, y, z))
				{
					match = o;
					index = o.index;
					break outer;
				}
			}
		}
		for (Override o : anyId)
		{
			if (o.index > index)
			{
				break;
			}
			if (o.contains(x, y, z))
			{
				match = o;
				break;
			}
		}
		if (match == null)
		{
			// no override: 117 HD keeps the game's look in winter too
			return true;
		}
		int[] vars = {swHsl >> 10 & 63, swHsl >> 7 & 7, swHsl & 127};
		Override o = match;
		for (int depth = 0; depth < MAX_CHAIN; ++depth)
		{
			if (trace != null)
			{
				trace.append(o.name).append(" > ");
			}
			if (o.snowy)
			{
				// snow underfoot in every season (a snowy mountain's ground)
				return false;
			}
			int next = -2;
			for (int k = 0; k < o.conditions.length; ++k)
			{
				Condition c = o.conditions[k];
				if (c.test(vars, true))
				{
					if (c.winter() && !c.test(vars, false))
					{
						// the winter replacement: the tile turns wintry
						return false;
					}
					next = o.targets[k];
					break;
				}
			}
			if (next == -2 || next < 0 || next >= all.length || all[next] == o)
			{
				return true;
			}
			o = all[next];
		}
		return true;
	}

	/**
	 * One replacement's condition, any of several expressions, as 117 HD writes them: h, s, l, the options (textures,
	 * blendingColors, blendingTextures, blendingTexturesOnly, at 117 HD's defaults: textures on, blending colours and
	 * textures), winter, ints, true and false, with ! && || == != < <= > >= and parentheses.
	 */
	static final class Condition
	{
		private final Node[] any;
		private final boolean winter;

		private Condition(Node[] any, boolean winter)
		{
			this.any = any;
			this.winter = winter;
		}

		static Condition any(List<String> exprs)
		{
			Node[] nodes = new Node[exprs.size()];
			boolean winter = false;
			for (int i = 0; i < nodes.length; ++i)
			{
				String e = exprs.get(i);
				winter |= e.contains("winter");
				nodes[i] = new Parser(e).parse();
			}
			return new Condition(nodes, winter);
		}

		/** Whether it holds for the tile's h, s, l, in winter or not. */
		boolean test(int[] hsl, boolean winterOn)
		{
			for (Node n : any)
			{
				if (n.eval(hsl, winterOn) != 0)
				{
					return true;
				}
			}
			return false;
		}

		/** Whether it mentions the winter at all. */
		boolean winter()
		{
			return winter;
		}
	}

	private interface Node
	{
		int eval(int[] hsl, boolean winter);
	}

	/** A recursive descent parser for the conditions. */
	private static final class Parser
	{
		private final String s;
		private int i;

		Parser(String s)
		{
			this.s = s;
		}

		Node parse()
		{
			Node n = or();
			skip();
			if (i != s.length())
			{
				throw new IllegalArgumentException("Unexpected '" + s.substring(i) + "' in " + s);
			}
			return n;
		}

		private Node or()
		{
			Node a = and();
			while (eat("||"))
			{
				Node l = a, r = and();
				a = (v, w) -> l.eval(v, w) != 0 || r.eval(v, w) != 0 ? 1 : 0;
			}
			return a;
		}

		private Node and()
		{
			Node a = compare();
			while (eat("&&"))
			{
				Node l = a, r = compare();
				a = (v, w) -> l.eval(v, w) != 0 && r.eval(v, w) != 0 ? 1 : 0;
			}
			return a;
		}

		private Node compare()
		{
			Node a = unary();
			for (String op : new String[]{"==", "!=", "<=", ">=", "<", ">"})
			{
				if (eat(op))
				{
					Node l = a, r = unary();
					switch (op)
					{
						case "==":
							return (v, w) -> l.eval(v, w) == r.eval(v, w) ? 1 : 0;
						case "!=":
							return (v, w) -> l.eval(v, w) != r.eval(v, w) ? 1 : 0;
						case "<=":
							return (v, w) -> l.eval(v, w) <= r.eval(v, w) ? 1 : 0;
						case ">=":
							return (v, w) -> l.eval(v, w) >= r.eval(v, w) ? 1 : 0;
						case "<":
							return (v, w) -> l.eval(v, w) < r.eval(v, w) ? 1 : 0;
						default:
							return (v, w) -> l.eval(v, w) > r.eval(v, w) ? 1 : 0;
					}
				}
			}
			return a;
		}

		private Node unary()
		{
			if (eat("!"))
			{
				Node a = unary();
				return (v, w) -> a.eval(v, w) == 0 ? 1 : 0;
			}
			if (eat("("))
			{
				Node a = or();
				if (!eat(")"))
				{
					throw new IllegalArgumentException("Missing ) in " + s);
				}
				return a;
			}
			skip();
			int start = i;
			if (i < s.length() && Character.isDigit(s.charAt(i)))
			{
				while (i < s.length() && Character.isDigit(s.charAt(i)))
				{
					++i;
				}
				int value = Integer.parseInt(s.substring(start, i));
				return (v, w) -> value;
			}
			while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '.'))
			{
				++i;
			}
			String name = s.substring(start, i);
			switch (name)
			{
				case "h":
					return (v, w) -> v[0];
				case "s":
					return (v, w) -> v[1];
				case "l":
					return (v, w) -> v[2];
				case "winter":
					return (v, w) -> w ? 1 : 0;
				case "true":
				case "textures":
				case "blendingColors":
				case "blendingTextures":
					return (v, w) -> 1;
				case "false":
				case "blendingTexturesOnly":
					return (v, w) -> 0;
				default:
					throw new IllegalArgumentException("Unknown '" + name + "' in " + s);
			}
		}

		private boolean eat(String token)
		{
			skip();
			// "!" is not the start of "!="
			if (s.startsWith(token, i) && !(token.equals("!") && s.startsWith("!=", i)))
			{
				i += token.length();
				return true;
			}
			return false;
		}

		private void skip()
		{
			while (i < s.length() && s.charAt(i) == ' ')
			{
				++i;
			}
		}
	}
}
