package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.CpuTimer;
import com.midonarhel.lumina.dev.GpuSections;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.dev.ShaderWatcher;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.opengl.GL11C.glFinish;

/**
 * The frame's cost by pass on the bench: each dump drawn at each hour (after its warm-up), then frames at the dump's
 * time, each timed by GpuSections (GL_TIME_ELAPSED per pass; serial=true begins each pass on an idle GPU) and on the
 * CPU (the callbacks' submit, and to glFinish). Prints a table per dump, hour and variant (min, median, share of the
 * GPU frame) and appends it to out=FILE.
 *
 * Variants (variants=A:k=v,k=v;B:k=v) are compared in the same renderer: rounds of frames of each in turn, so the
 * GPU's clock and other work on the machine weigh on all alike. A variant's settings are set before its frames and
 * the base settings put back after; the key shaders=DIR draws it with the shader sources of DIR (as hot reload does).
 *
 * <pre>
 * ./gradlew passCost -Pargs="dumps=nightref,snow1 hours=12,21 frames=60 rounds=4 out=FILE [variants=...] [key=value settings]"
 * </pre>
 */
public final class PassCost
{
	// false: no GPU timers (they make the CPU wait at each section's end), for the CPU's own time and a profile
	static boolean sections = true;
	// a directory: each dump's frame at each hour, as drawn after the warm-up, into DIR/<dump>-<hour>.png
	static String render;

	public static void main(String[] args) throws Exception
	{
		List<String> dumps = new ArrayList<>();
		List<Integer> hours = new ArrayList<>();
		int frames = 60, rounds = 1;
		String out = null;
		Map<String, String> settings = new LinkedHashMap<>();
		Map<String, Map<String, String>> variants = new LinkedHashMap<>();
		for (String a : args)
		{
			int eq = a.indexOf('=');
			String k = a.substring(0, eq), v = a.substring(eq + 1);
			switch (k)
			{
				case "dumps":
					dumps.addAll(Arrays.asList(v.split(",")));
					break;
				case "hours":
					for (String h : v.split(","))
					{
						hours.add(h.equals("dump") ? null : Integer.parseInt(h));
					}
					break;
				case "frames":
					frames = Integer.parseInt(v);
					break;
				case "rounds":
					rounds = Integer.parseInt(v);
					break;
				case "render":
					render = v;
					break;
				case "sections":
					sections = Boolean.parseBoolean(v);
					break;
				case "serial":
					GpuSections.serial = Boolean.parseBoolean(v);
					break;
				case "out":
					out = v;
					break;
				case "variants":
					for (String var : v.split(";"))
					{
						int colon = var.indexOf(':');
						Map<String, String> s = new LinkedHashMap<>();
						if (colon >= 0)
						{
							for (String kv : var.substring(colon + 1).split(","))
							{
								if (kv.contains("="))
								{
									s.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
								}
							}
						}
						variants.put(colon >= 0 ? var.substring(0, colon) : var, s);
					}
					break;
				default:
					settings.put(k, v);
			}
		}
		if (hours.isEmpty())
		{
			hours.add(null);
		}
		if (variants.isEmpty())
		{
			variants.put("base", new LinkedHashMap<>());
		}
		for (String name : dumps)
		{
			SceneDump.Loaded dump = RenderDump.load(name);
			for (Integer hour : hours)
			{
				String table = run(name, dump, hour, frames, rounds, settings, variants);
				System.out.print(table);
				if (out != null)
				{
					Files.writeString(Path.of(out), table, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
			}
		}
		System.exit(0);
	}

	/** One variant's samples: per pass, per CPU step, the frame. */
	static final class Samples
	{
		final Map<String, List<Double>> gpu = new LinkedHashMap<>();
		final Map<String, List<Double>> cpu = new LinkedHashMap<>();
		final List<Double> gpuFrame = new ArrayList<>(), submit = new ArrayList<>(), wall = new ArrayList<>();
		// per round: each pass's median that round ("frame" the GPU frame's), for the paired deltas
		final List<Map<String, Double>> rounds = new ArrayList<>();
	}

	/** The median over rounds of (this variant's round median less the first variant's that round). */
	static double pairedDelta(Samples s, Samples first, String pass)
	{
		List<Double> d = new ArrayList<>();
		for (int r = 0; r < Math.min(s.rounds.size(), first.rounds.size()); ++r)
		{
			Double a = s.rounds.get(r).get(pass), b = first.rounds.get(r).get(pass);
			if (a != null && b != null)
			{
				d.add(a - b);
			}
		}
		return median(d);
	}

	static String run(String name, SceneDump.Loaded dump, Integer hour, int frames, int rounds, Map<String, String> settings,
		Map<String, Map<String, String>> variants) throws Exception
	{
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.hour = hour;
		o.settings.putAll(settings);
		Map<String, Samples> samples = new LinkedHashMap<>();
		String[] names = new String[64];
		double[] ms = new double[64];
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			java.awt.image.BufferedImage image = r.render();
			if (render != null)
			{
				Files.createDirectories(Path.of(render));
				javax.imageio.ImageIO.write(image, "png", Path.of(render, name + "-" + (hour == null ? "dump" : hour) + ".png").toFile());
				if (frames == 0)
				{
					return "";
				}
			}
			GpuSections.reset();
			for (int round = 0; round < rounds; ++round)
			{
				for (Map.Entry<String, Map<String, String>> v : variants.entrySet())
				{
					Samples s = samples.computeIfAbsent(v.getKey(), k -> new Samples());
					Map<String, String> was = apply(r, v.getValue());
					GpuSections.on = sections;
					Map<String, List<Double>> thisRound = new LinkedHashMap<>();
					for (int i = -5; i < frames; ++i)
					{
						glFinish();
						long t0 = System.nanoTime();
						r.frame();
						long t1 = System.nanoTime();
						glFinish();
						long t2 = System.nanoTime();
						int n = GpuSections.read(names, ms);
						if (i < 0)
						{
							continue;
						}
						Map<String, Double> frame = new LinkedHashMap<>();
						for (int k = 0; k < n; ++k)
						{
							frame.merge(names[k], ms[k], Double::sum);
						}
						double sum = 0;
						for (Map.Entry<String, Double> e : frame.entrySet())
						{
							s.gpu.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
							thisRound.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(e.getValue());
							sum += e.getValue();
						}
						s.gpuFrame.add(sum);
						thisRound.computeIfAbsent("frame", k -> new ArrayList<>()).add(sum);
						s.submit.add((t1 - t0) / 1e6);
						s.wall.add((t2 - t0) / 1e6);
						for (int k = 0; k < CPU_NAMES.length; ++k)
						{
							if (CPU_NAMES[k] != null)
							{
								s.cpu.computeIfAbsent(CPU_NAMES[k], x -> new ArrayList<>()).add(r.plugin.cpuTimer.getLastMs(k));
							}
						}
					}
					GpuSections.on = false;
					Map<String, Double> medians = new LinkedHashMap<>();
					thisRound.forEach((k, l) -> medians.put(k, median(l)));
					s.rounds.add(medians);
					unapply(r, v.getValue(), was);
				}
			}
		}
		StringBuilder b = new StringBuilder();
		Samples first = samples.values().iterator().next();
		for (Map.Entry<String, Samples> v : samples.entrySet())
		{
			Samples s = v.getValue();
			double gpuMedian = median(s.gpuFrame);
			b.append(String.format("%n### %s, hour %s, %d frames x %d rounds%s, variant %s %s%s%n%n", name, hour == null ? "dump" : hour,
				frames, rounds, GpuSections.serial ? ", serial" : "", v.getKey(), variants.get(v.getKey()),
				settings.isEmpty() ? "" : ", " + settings));
			boolean delta = s != first;
			b.append(delta ? "| GPU pass | min ms | median ms | share | median vs first | paired by round |\n|---|---:|---:|---:|---:|---:|\n"
				: "| GPU pass | min ms | median ms | share |\n|---|---:|---:|---:|\n");
			List<Map.Entry<String, List<Double>>> ranked = new ArrayList<>(s.gpu.entrySet());
			ranked.sort((x, y) -> Double.compare(median(y.getValue()), median(x.getValue())));
			for (Map.Entry<String, List<Double>> e : ranked)
			{
				double med = median(e.getValue());
				if (Math.abs(med) < 0.0005 && !delta)
				{
					continue;
				}
				b.append(String.format("| %s | %.3f | %.3f | %.1f %% |", e.getKey(), min(e.getValue()), med, 100 * med / gpuMedian));
				if (delta)
				{
					List<Double> f = first.gpu.get(e.getKey());
					b.append(f == null ? " | |" : String.format(" %+.3f | %+.3f |", med - median(f), pairedDelta(s, first, e.getKey())));
				}
				b.append('\n');
			}
			b.append(String.format("| **GPU frame (sum)** | %.3f | %.3f | |%s%n", min(s.gpuFrame), gpuMedian,
				delta ? String.format(" %+.3f | %+.3f |", gpuMedian - median(first.gpuFrame), pairedDelta(s, first, "frame")) : ""));
			b.append(String.format("| cpu: submit (callbacks) | %.3f | %.3f | |%s%n", min(s.submit), median(s.submit),
				delta ? String.format(" %+.3f |", median(s.submit) - median(first.submit)) : ""));
			b.append(String.format("| cpu: wall to glFinish | %.3f | %.3f | |%s%n", min(s.wall), median(s.wall),
				delta ? String.format(" %+.3f |", median(s.wall) - median(first.wall)) : ""));
			for (Map.Entry<String, List<Double>> e : s.cpu.entrySet())
			{
				if (median(e.getValue()) > 0.001)
				{
					b.append(String.format("| cpu: %s | %.3f | %.3f | |%s%n", e.getKey(), min(e.getValue()), median(e.getValue()),
						delta ? String.format(" %+.3f |", median(e.getValue()) - median(first.cpu.get(e.getKey()))) : ""));
				}
			}
		}
		return b.toString();
	}

	/** Sets the variant; gives the settings it changed as they were. */
	private static Map<String, String> apply(DumpRenderer r, Map<String, String> variant)
	{
		Map<String, String> was = new LinkedHashMap<>();
		for (Map.Entry<String, String> e : variant.entrySet())
		{
			if (e.getKey().equals("shaders"))
			{
				System.setProperty("runelight.shaderDir", e.getValue());
				r.plugin.shaderWatcher = ShaderWatcher.create();
				if (r.plugin.shaderWatcher == null)
				{
					throw new IllegalArgumentException("no shaders at " + e.getValue());
				}
				r.plugin.programs.reloadShaders();
			}
			else
			{
				was.put(e.getKey(), r.world.settings.get(e.getKey()));
				r.set(e.getKey(), e.getValue());
			}
		}
		return was;
	}

	private static void unapply(DumpRenderer r, Map<String, String> variant, Map<String, String> was)
	{
		for (Map.Entry<String, String> e : variant.entrySet())
		{
			if (e.getKey().equals("shaders"))
			{
				r.plugin.shaderWatcher = null;
				System.clearProperty("runelight.shaderDir");
				r.plugin.programs.reloadShaders();
			}
			else
			{
				String old = was.get(e.getKey());
				if (old != null)
				{
					r.set(e.getKey(), old);
				}
				else
				{
					r.world.settings.remove(e.getKey());
				}
			}
		}
	}

	// CpuTimer's steps that run in the bench's frame (not the client's own, nor the swap)
	private static final String[] CPU_NAMES = {
		null, "environment", "weather", "light collection", "light clusters", "shadow submit", "UI upload", null, null, null,
		"buffer map", "light upload", "scene voxels", "sky capture", "occlusion maps", "sky grid"
	};

	static double median(List<Double> a)
	{
		double[] c = a.stream().mapToDouble(Double::doubleValue).toArray();
		Arrays.sort(c);
		return c.length == 0 ? 0 : c[c.length / 2];
	}

	static double min(List<Double> a)
	{
		return a.stream().mapToDouble(Double::doubleValue).min().orElse(0);
	}

	private PassCost()
	{
	}

	static
	{
		// CpuTimer's steps are named in the order above
		assert CpuTimer.SKY_GRID == 15;
	}
}
