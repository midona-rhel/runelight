package com.midonarhel.lumina.harness;

import com.google.gson.Gson;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The card of a build: its parts' JSON gathered into card.json and card.md, with the baseline's numbers beside it. The
 * cost's rounds are taken together here: of each pass, the least of the rounds' mins and the median of their medians.
 */
final class CardReport
{
	private CardReport()
	{
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> read(Path file) throws Exception
	{
		return Files.exists(file) ? new Gson().fromJson(Files.readString(file), Map.class) : null;
	}

	// a value at a path of keys, null where it isn't
	@SuppressWarnings("unchecked")
	private static Object at(Map<String, Object> m, String... keys)
	{
		Object o = m;
		for (String k : keys)
		{
			if (!(o instanceof Map))
			{
				return null;
			}
			o = ((Map<String, Object>) o).get(k);
		}
		return o;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> cost(Path dir, String dump) throws Exception
	{
		List<Map<String, Object>> rounds = new ArrayList<>();
		for (int r = 0; r < 64; ++r)
		{
			Map<String, Object> one = read(dir.resolve(dump + "-cost-" + r + ".json"));
			if (one != null)
			{
				rounds.add(one);
			}
		}
		if (rounds.isEmpty())
		{
			return null;
		}
		Map<String, Object> out = new LinkedHashMap<>();
		out.put("rounds", rounds.size());
		for (String config : new String[]{"dumpWeather", "fog"})
		{
			for (String variant : new String[]{"lamps", "noLamps"})
			{
				Map<String, Object> v = (Map<String, Object>) at(rounds.get(0), config, variant);
				if (v == null)
				{
					continue;
				}
				for (String pass : v.keySet())
				{
					double least = Double.MAX_VALUE;
					List<Double> medians = new ArrayList<>();
					for (Map<String, Object> r : rounds)
					{
						Object min = at(r, config, variant, pass, "min"), med = at(r, config, variant, pass, "median");
						if (min != null)
						{
							least = Math.min(least, ((Number) min).doubleValue());
							medians.add(((Number) med).doubleValue());
						}
					}
					Map<String, Object> e = new LinkedHashMap<>();
					e.put("min", Card.round(least, 3));
					e.put("median", Card.round(PassCost.median(medians), 3));
					((Map<String, Object>) ((Map<String, Object>) out.computeIfAbsent(config, k -> new LinkedHashMap<>())).computeIfAbsent(variant, k -> new LinkedHashMap<>())).put(pass, e);
				}
			}
		}
		return out;
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> gather(Path dir, List<String> dumps) throws Exception
	{
		Map<String, Object> all = new LinkedHashMap<>();
		for (String d : dumps)
		{
			Map<String, Object> m = new LinkedHashMap<>();
			for (String part : new String[]{"leak", "fog", "particles", "motion", "memory"})
			{
				Map<String, Object> p = read(dir.resolve(d + "-" + part + ".json"));
				if (p != null)
				{
					m.put(part, p);
				}
			}
			Map<String, Object> c = cost(dir, d);
			if (c != null)
			{
				m.put("cost", c);
			}
			all.put(d, m);
		}
		return all;
	}

	private static double num(Object o)
	{
		return o instanceof Number ? ((Number) o).doubleValue() : Double.NaN;
	}

	private static String show(double v)
	{
		if (Double.isNaN(v))
		{
			return "-";
		}
		return Math.abs(v - Math.rint(v)) < 1e-9 ? String.format("%,.0f", v) : Math.abs(v) >= 100 ? String.format("%,.1f", v) : String.format("%.3f", v).replaceAll("0+$", "").replaceAll("\\.$", "");
	}

	private interface Value
	{
		double of(Map<String, Object> dump);
	}

	private static final Map<String, Value> ROWS = new LinkedHashMap<>();

	private static double msDelta(Map<String, Object> m, String config, String pass)
	{
		return num(at(m, "cost", config, "lamps", pass, "median")) - num(at(m, "cost", config, "noLamps", pass, "median"));
	}

	private static void row(String name, String... path)
	{
		ROWS.put(name, m -> num(at(m, path)));
	}

	static
	{
		row("LEAK px (surfaces, rule: wall, roof, terrain, floor)", "leak", "leak", "px");
		row("LEAK px on the final frame", "leak", "leak", "finalFramePx");
		row("LEAK px, every lamp on", "leak", "leak", "allLampsOnPx");
		row("LEAK ppm of blocked samples", "leak", "leak", "ppmOfBlocked");
		row("LEAK share of the lamps' light, %", "leak", "leak", "energySharePct");
		row("through models only, px", "leak", "leak", "throughModelsOnlyPx");
		row("OVERBLOCK px (dark, must be lit)", "leak", "overblock", "px");
		row("OVERBLOCK ppm of open samples", "leak", "overblock", "ppmOfOpen");
		row("dimmed under 70 % px", "leak", "overblock", "dimmedPx");
		row("blotches", "leak", "edges", "blotches");
		row("biggest blotch px", "leak", "edges", "biggestBlotchPx");
		row("edge wobble p50 px", "leak", "edges", "p50px");
		row("edge wobble p90 px", "leak", "edges", "p90px");
		row("edge wobble p99 px", "leak", "edges", "p99px");
		row("stepped edge readings %", "leak", "edges", "steppedPct");
		row("ripple of open light", "leak", "edges", "openRipple");
		row("camera: unstable % (mean)", "motion", "camera", "unstablePctMean");
		row("camera: unstable % (worst step)", "motion", "camera", "unstablePctWorst");
		row("camera: biggest pop px", "motion", "camera", "biggestPopPx");
		row("door: changed px", "motion", "door", "changedPx");
		row("door: frames to settle", "motion", "door", "framesToSettle");
		row("door: biggest frame's share %", "motion", "door", "biggestFrameSharePct");
		row("door: worst step of 255", "motion", "door", "worstStepOf255");
		row("door: pop px (step over 8)", "motion", "door", "popPx");
		row("roof: changed px", "motion", "roof", "changedPx");
		row("roof: biggest frame's share %", "motion", "roof", "biggestFrameSharePct");
		row("roof: pop px (step over 8)", "motion", "roof", "popPx");
		row("fog: hole px", "fog", "holePx");
		row("fog: hole shapes", "fog", "holeShapes");
		row("fog: biggest hole px", "fog", "biggestHolePx");
		row("fog: glow through walls px", "fog", "glowThroughPx");
		row("fog: mean log error", "fog", "meanLogError");
		row("fog, every lamp on: hole px", "fog", "allLampsHolePx");
		row("fog, every lamp on: glow through px", "fog", "allLampsGlowThroughPx");
		row("rain: lit in closed air px", "particles", "rain", "litWhereAirClosedPx");
		row("rain: lit in closed air % of lit", "particles", "rain", "leakSharePct");
		row("rain: lit in open air over dark px", "particles", "rain", "litInOpenAirOverDarkSurfacesPx");
		row("snow: lit in closed air px", "particles", "snow", "litWhereAirClosedPx");
		row("snow: lit in closed air % of lit", "particles", "snow", "leakSharePct");
		row("snow: lit in open air over dark px", "particles", "snow", "litInOpenAirOverDarkSurfacesPx");
		row("GPU frame ms, min", "cost", "dumpWeather", "lamps", "GPU frame", "min");
		row("GPU frame ms, median", "cost", "dumpWeather", "lamps", "GPU frame", "median");
		row("composite ms, median", "cost", "dumpWeather", "lamps", "composite", "median");
		row("lamp cubes pass ms, median", "cost", "dumpWeather", "lamps", "lamp cubes", "median");
		ROWS.put("lamps in the composite ms (with less without)", m -> msDelta(m, "dumpWeather", "composite"));
		ROWS.put("lamps in the whole frame ms (with less without)", m -> msDelta(m, "dumpWeather", "GPU frame"));
		row("fog weather: GPU frame ms, median", "cost", "fog", "lamps", "GPU frame", "median");
		row("fog weather: fog pass ms, median", "cost", "fog", "lamps", "fog", "median");
		ROWS.put("fog weather: lamps in the fog pass ms", m -> msDelta(m, "fog", "fog"));
		row("client thread ms a frame, median", "cost", "dumpWeather", "lamps", "client thread", "median");
		row("GPU memory MB, all", "memory", "memory", "totalMB");
		row("load: client thread extra ms", "memory", "load", "clientThreadExtraMsTotal");
		row("load: worst frame extra ms", "memory", "load", "clientThreadWorstFrameExtraMs");
		row("load: background CPU ms", "memory", "load", "backgroundCpuMs");
		row("load: wall ms to settled", "memory", "load", "settledWallMs");
		row("door: client thread worst frame extra ms", "motion", "door", "clientThreadWorstFrameExtraMs");
		row("door: client thread extra ms", "motion", "door", "clientThreadExtraMsTotal");
		row("door: background CPU ms", "motion", "door", "backgroundCpuMs");
		row("door: GPU worst frame extra ms", "motion", "door", "gpuWorstFrameExtraMs");
		row("roof: client thread worst frame extra ms", "motion", "roof", "clientThreadWorstFrameExtraMs");
		row("roof: background CPU ms", "motion", "roof", "backgroundCpuMs");
		row("roof: GPU worst frame extra ms", "motion", "roof", "gpuWorstFrameExtraMs");
	}

	@SuppressWarnings("unchecked")
	static void write(Path dir, Path baseline, List<String> dumps) throws Exception
	{
		Map<String, Object> mine = gather(dir, dumps);
		Map<String, Object> base = baseline != null && !baseline.equals(dir) ? gather(baseline, dumps) : null;
		Files.writeString(dir.resolve("card.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(mine));
		StringBuilder b = new StringBuilder();
		b.append("# Occlusion scorecard: ").append(dir.getFileName()).append("\n\n");
		if (Files.exists(dir.resolve("build.txt")))
		{
			b.append(Files.readString(dir.resolve("build.txt"))).append("\n");
		}
		b.append(base != null ? "Each cell: this build, then the baseline in brackets.\n\n" : "");
		b.append("| |");
		for (String d : dumps)
		{
			b.append(' ').append(d).append(" |");
		}
		b.append("\n|---|");
		b.append("---:|".repeat(dumps.size())).append('\n');
		for (Map.Entry<String, Value> row : ROWS.entrySet())
		{
			b.append("| ").append(row.getKey()).append(" |");
			for (String d : dumps)
			{
				double v = row.getValue().of((Map<String, Object>) mine.get(d));
				b.append(' ').append(show(v));
				if (base != null)
				{
					b.append(" (").append(show(row.getValue().of((Map<String, Object>) base.get(d)))).append(')');
				}
				b.append(" |");
			}
			b.append('\n');
		}
		b.append("\n## Where the leaks are\n\n");
		for (String d : dumps)
		{
			Object worst = at((Map<String, Object>) mine.get(d), "leak", "leak", "worst");
			if (worst instanceof List && !((List<?>) worst).isEmpty())
			{
				b.append("**").append(d).append("**");
				b.append(String.format(": %s lamps, %s on storeys the client hides, %s %% of pixels not on the mesh (water, transparent, animated)%n%n",
					show(num(at((Map<String, Object>) mine.get(d), "leak", "lamps"))), show(num(at((Map<String, Object>) mine.get(d), "leak", "lampsOnHiddenStoreys"))),
					show(num(at((Map<String, Object>) mine.get(d), "leak", "pixelsNotOnMesh")))));
				b.append("| lamp's tile | storey | leak px | biggest group px | at pixel | crossed |\n|---|---:|---:|---:|---|---|\n");
				for (Map<String, Object> w : (List<Map<String, Object>>) worst)
				{
					List<Number> t = (List<Number>) w.get("lamp"), p = (List<Number>) w.get("atPixel");
					b.append(String.format("| %d, %d | %d | %s | %s | %d, %d | %s |%n", t.get(0).intValue(), t.get(1).intValue(), ((Number) w.get("plane")).intValue(),
						show(num(w.get("leakPx"))), show(num(w.get("biggestGroupPx"))), p.get(0).intValue(), p.get(1).intValue(), w.get("crossed")));
				}
				b.append('\n');
			}
		}
		b.append("## Checks on the bench itself\n\n| |");
		for (String d : dumps)
		{
			b.append(' ').append(d).append(" |");
		}
		b.append("\n|---|").append("---:|".repeat(dumps.size())).append('\n');
		String[][] checks = {{"frame repeated: pixels off", "leak", "repeatDiffPixels"}, {"frames to show a lamp", "leak", "framesToShowALamp"},
			{"lamps not in the oracle", "leak", "lampsNotInOracle"}, {"lit pixels cut by the 4 lamp budget, %", "leak", "litPixelsCutByBudgetPct"}};
		for (String[] c : checks)
		{
			b.append("| ").append(c[0]).append(" |");
			for (String d : dumps)
			{
				b.append(' ').append(show(num(at((Map<String, Object>) mine.get(d), c[1], c[2])))).append(" |");
			}
			b.append('\n');
		}
		String[][] held = {{"fog held", "fog", "weather", "held"}, {"rain held", "particles", "rain", "weather", "held"}, {"snow held", "particles", "snow", "weather", "held"}};
		for (String[] c : held)
		{
			b.append("| ").append(c[0]).append(" |");
			for (String d : dumps)
			{
				b.append(' ').append(at((Map<String, Object>) mine.get(d), Arrays.copyOfRange(c, 1, c.length))).append(" |");
			}
			b.append('\n');
		}
		Files.writeString(dir.resolve("card.md"), b.toString());
		System.out.println("wrote " + dir.resolve("card.md"));
	}
}
