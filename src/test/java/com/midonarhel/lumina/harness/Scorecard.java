package com.midonarhel.lumina.harness;

import com.google.gson.GsonBuilder;
import com.midonarhel.lumina.dev.SceneDump;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The occlusion scorecard (occlusion/SCORECARD.md): one dump, one phase, one JVM; tools/scorecard.sh runs them.
 * <pre>
 * args: phase=probe|oracle|render|cost|why|report dump=NAME cache=DIR out=DIR [parts=leak,fog,particles,motion,memory] [round=N frames=N]
 * </pre>
 * probe (GPU) and oracle (CPU only) make what every method is measured against, into cache/NAME/; render (GPU) scores
 * the build in this tree against them, into out/NAME-*.json and images; cost (GPU) times its passes, a round at a time so
 * that builds can be interleaved. Nothing here changes what the plugin draws.
 */
public final class Scorecard
{
	private Scorecard()
	{
	}

	public static void main(String[] args) throws Exception
	{
		String phase = "render", dump = null;
		Path cache = null, out = null, baseline = null;
		List<String> dumps = new ArrayList<>();
		List<String> parts = new ArrayList<>(Arrays.asList("leak", "fog", "particles", "motion", "memory"));
		int round = 0, frames = 40;
		int[] why = new int[4];
		for (String a : args)
		{
			int eq = a.indexOf('=');
			String k = a.substring(0, eq), v = a.substring(eq + 1);
			switch (k)
			{
				case "phase":
					phase = v;
					break;
				case "dump":
					dump = v;
					break;
				case "cache":
					cache = Path.of(v);
					break;
				case "baseline":
					baseline = Path.of(v);
					break;
				case "dumps":
					dumps = Arrays.asList(v.split(","));
					break;
				case "out":
					out = Path.of(v);
					break;
				case "parts":
					parts = Arrays.asList(v.split(","));
					break;
				case "round":
					round = Integer.parseInt(v);
					break;
				case "frames":
					frames = Integer.parseInt(v);
					break;
				case "why":
					// the pixel x, y and the lamp's world tile x, y
					why = Arrays.stream(v.split(",")).mapToInt(Integer::parseInt).toArray();
					break;
				default:
					throw new IllegalArgumentException(a);
			}
		}
		if (phase.equals("report"))
		{
			CardReport.write(out, baseline, dumps);
			System.exit(0);
		}
		SceneDump.Loaded loaded = Card.load(dump);
		Path probeFile = cache != null ? cache.resolve(dump).resolve("probe.bin") : null;
		Path oracleFile = cache != null ? cache.resolve(dump).resolve("oracle.bin") : null;
		switch (phase)
		{
			case "probe":
				try (DumpRenderer r = new DumpRenderer(loaded, Card.options("CLEAR", false)))
				{
					r.render();
					CardProbe.take(r).save(probeFile);
				}
				break;
			case "oracle":
			{
				CardOracle o = CardOracle.compute(loaded, CardProbe.load(probeFile));
				o.save(oracleFile);
				System.out.printf("oracle %s: %d lamps, %.1f s, %.1f MB%n", dump, o.px.length, o.millis / 1e3, Files.size(oracleFile) / 1e6);
				break;
			}
			case "why":
				System.out.print(CardOracle.explain(loaded, CardProbe.load(probeFile), why[0], why[1], why[2], why[3]));
				break;
			case "render":
				Files.createDirectories(out.resolve("frames"));
				render(loaded, dump, CardProbe.load(probeFile), CardOracle.load(oracleFile), out, parts);
				break;
			case "cost":
				Files.createDirectories(out);
				write(out.resolve(dump + "-cost-" + round + ".json"), CardCost.passes(loaded, frames));
				break;
			default:
				throw new IllegalArgumentException(phase);
		}
		System.exit(0);
	}

	private static void render(SceneDump.Loaded loaded, String dump, CardProbe p, CardOracle o, Path out, List<String> parts) throws Exception
	{
		if (parts.contains("leak"))
		{
			try (DumpRenderer r = new DumpRenderer(loaded, Card.options("CLEAR", false)))
			{
				r.render();
				write(out.resolve(dump + "-leak.json"), CardLeak.run(r, p, o, out, dump));
			}
		}
		if (parts.contains("fog"))
		{
			try (DumpRenderer r = new DumpRenderer(loaded, Card.options("FOG", true)))
			{
				r.render();
				write(out.resolve(dump + "-fog.json"), CardAir.fog(r, p, o, out, dump));
			}
		}
		if (parts.contains("particles"))
		{
			Map<String, Object> m = Card.map();
			for (String weather : new String[]{"RAIN", "SNOW"})
			{
				try (DumpRenderer r = new DumpRenderer(loaded, Card.options(weather, false)))
				{
					r.render();
					m.put(weather.toLowerCase(), CardAir.particles(r, p, o, out, dump, weather));
				}
			}
			write(out.resolve(dump + "-particles.json"), m);
		}
		if (parts.contains("motion"))
		{
			write(out.resolve(dump + "-motion.json"), CardMotion.run(loaded, out, dump));
		}
		if (parts.contains("memory"))
		{
			write(out.resolve(dump + "-memory.json"), CardCost.memoryAndLoad(loaded));
		}
	}

	static void write(Path file, Map<String, Object> m) throws Exception
	{
		Files.writeString(file, new GsonBuilder().setPrettyPrinting().serializeSpecialFloatingPointValues().create().toJson(m));
		System.out.println("wrote " + file);
	}
}
