package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.common.Clock;
import com.midonarhel.lumina.dev.GpuSections;
import com.midonarhel.lumina.dev.SceneDump;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Part 4: what a build costs. GPU ms per pass on an idle GPU each (GpuSections.serial), with the lamps and without,
 * in the dump's weather and in fog; one round per JVM, so tools/scorecard.sh can interleave builds and takes min and
 * median over the rounds. GPU memory: every texture, buffer and renderbuffer object alive, counted from GL itself.
 * CPU on load: a second load in the same JVM (the code warmed), the client thread's frames and the other threads' CPU.
 */
final class CardCost
{
	private CardCost()
	{
	}

	private static DumpRenderer.Options options(boolean fog)
	{
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.hour = 21;
		o.settings.put("antiAliasingMode", "DISABLED");
		o.settings.put("renderScale", "FULL");
		if (fog)
		{
			o.settings.put("weatherMode", "FOG");
			o.settings.put("weatherLightning", "false");
			o.restoreWeather = false;
		}
		return o;
	}

	static Map<String, Object> passes(SceneDump.Loaded dump, int frames) throws Exception
	{
		Map<String, Object> res = Card.map();
		for (boolean fog : new boolean[]{false, true})
		{
			Map<String, Object> config = Card.map();
			try (DumpRenderer r = new DumpRenderer(dump, options(fog)))
			{
				r.render();
				GpuSections.reset();
				GpuSections.serial = true;
				Map<String, Map<String, List<Double>>> samples = new LinkedHashMap<>();
				String[] names = new String[64];
				double[] ms = new double[64];
				// two turns of each: what drifts weighs on both alike
				for (int turn = 0; turn < 4; ++turn)
				{
					boolean lamps = (turn & 1) == 0;
					r.set("pointLights", String.valueOf(lamps));
					Map<String, List<Double>> s = samples.computeIfAbsent(lamps ? "lamps" : "noLamps", k -> new LinkedHashMap<>());
					GpuSections.on = true;
					for (int i = -5; i < frames; ++i)
					{
						glFinish();
						long t0 = System.nanoTime();
						r.frame();
						long t1 = System.nanoTime();
						glFinish();
						int n = GpuSections.read(names, ms);
						if (i < 0)
						{
							continue;
						}
						Map<String, Double> frame = new LinkedHashMap<>();
						double sum = 0;
						for (int k = 0; k < n; ++k)
						{
							frame.merge(names[k], ms[k], Double::sum);
							sum += ms[k];
						}
						frame.forEach((k, v) -> s.computeIfAbsent(k, x -> new ArrayList<>()).add(v));
						s.computeIfAbsent("GPU frame", x -> new ArrayList<>()).add(sum);
						s.computeIfAbsent("client thread", x -> new ArrayList<>()).add((t1 - t0) / 1e6);
					}
					GpuSections.on = false;
				}
				r.set("pointLights", "true");
				GpuSections.serial = false;
				for (Map.Entry<String, Map<String, List<Double>>> v : samples.entrySet())
				{
					Map<String, Object> passes = Card.map();
					v.getValue().forEach((k, l) ->
					{
						Map<String, Object> e = Card.map();
						e.put("min", Card.round(PassCost.min(l), 3));
						e.put("median", Card.round(PassCost.median(l), 3));
						passes.put(k, e);
					});
					config.put(v.getKey(), passes);
				}
			}
			res.put(fog ? "fog" : "dumpWeather", config);
		}
		return res;
	}

	// whether the lamps wait for something: bakes under way, or lamps on a light map not live yet
	private static boolean busy(LuminaPlugin plugin)
	{
		try
		{
			Object map = LuminaPlugin.class.getField("lightMap").get(plugin);
			int[] counts = (int[]) map.getClass().getMethod("counts").invoke(map);
			if (counts[1] < counts[0])
			{
				return true;
			}
		}
		catch (NoSuchFieldException | NoSuchMethodException e)
		{
			// not in this build
		}
		catch (ReflectiveOperationException e)
		{
			throw new IllegalStateException(e);
		}
		for (String[] f : new String[][]{{"lightMap", "busy"}, {"lightClusters", "reachBusy"}})
		{
			try
			{
				Object owner = LuminaPlugin.class.getField(f[0]).get(plugin);
				if ((Boolean) owner.getClass().getMethod(f[1]).invoke(owner))
				{
					return true;
				}
			}
			catch (NoSuchFieldException | NoSuchMethodException e)
			{
				// not in this build
			}
			catch (ReflectiveOperationException e)
			{
				throw new IllegalStateException(e);
			}
		}
		return false;
	}

	static Map<String, Object> memoryAndLoad(SceneDump.Loaded dump) throws Exception
	{
		Map<String, Object> res = Card.map();
		DumpRenderer.Options o = options(false);
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			r.render();
			res.put("memory", census());
		}
		// a load again, the JVM warm: the frames after it, nothing waited for
		ThreadMXBean bean = ManagementFactory.getThreadMXBean();
		Map<Long, Long> bg0 = Card.threadCpu(bean);
		DumpRenderer.Options o2 = options(false);
		try (DumpRenderer r = new DumpRenderer(dump, o2))
		{
			SceneDump.Manifest m = dump.manifest;
			int frames = 60;
			double[] cpu = new double[frames];
			long start = System.nanoTime();
			long settled = -1;
			for (int i = 0; i < frames; ++i)
			{
				long back = (frames - 1 - i) * 1_000_000_000L / 60;
				Clock.set(m.millis - back / 1_000_000, m.nanos - back);
				long t0 = System.nanoTime();
				r.frame();
				cpu[i] = (System.nanoTime() - t0) / 1e6;
				if (busy(r.plugin))
				{
					settled = System.nanoTime();
				}
			}
			long until = System.nanoTime() + 120_000_000_000L;
			while (busy(r.plugin) && System.nanoTime() < until)
			{
				Thread.sleep(2);
				settled = System.nanoTime();
			}
			double background = Card.threadCpuSince(bean, bg0);
			double[] steady = new double[30];
			for (int i = 0; i < steady.length; ++i)
			{
				long t0 = System.nanoTime();
				r.frame();
				steady[i] = (System.nanoTime() - t0) / 1e6;
			}
			Arrays.sort(steady);
			double base = steady[15], worst = 0, extra = 0;
			for (double v : cpu)
			{
				worst = Math.max(worst, v - base);
				extra += Math.max(0, v - base);
			}
			Map<String, Object> load = Card.map();
			load.put("steadyFrameCpuMs", Card.round(base, 2));
			load.put("clientThreadWorstFrameExtraMs", Card.round(worst, 2));
			load.put("clientThreadExtraMsTotal", Card.round(extra, 1));
			load.put("backgroundCpuMs", Card.round(background, 0));
			load.put("settledWallMs", Card.round(settled < 0 ? 0 : (settled - start) / 1e6, 0));
			res.put("load", load);
		}
		return res;
	}

	private static final int[] TARGETS = {GL_TEXTURE_2D, GL_TEXTURE_2D_ARRAY, GL_TEXTURE_3D, GL_TEXTURE_2D_MULTISAMPLE, GL_TEXTURE_CUBE_MAP, GL_TEXTURE_BUFFER};
	private static final String[] TARGET_NAMES = {"2D", "2D array", "3D", "2D MS", "cube", "buffer"};

	/** Every GL object alive now, sized from GL itself: no method can hide memory from it. */
	static Map<String, Object> census()
	{
		while (glGetError() != GL_NO_ERROR)
		{
		}
		int top = Math.max(glGenTextures(), 8192) + 64;
		List<long[]> items = new ArrayList<>();
		List<String> names = new ArrayList<>();
		long textures = 0, buffers = 0, renderbuffers = 0;
		glActiveTexture(GL_TEXTURE0);
		for (int id = 1; id < top; ++id)
		{
			if (!glIsTexture(id))
			{
				continue;
			}
			long bytes = 0;
			String what = "?";
			for (int t = 0; t < TARGETS.length; ++t)
			{
				glBindTexture(TARGETS[t], id);
				if (glGetError() != GL_NO_ERROR)
				{
					continue;
				}
				if (TARGETS[t] == GL_TEXTURE_BUFFER)
				{
					what = "buffer texture";
					glBindTexture(TARGETS[t], 0);
					break;
				}
				int faces = TARGETS[t] == GL_TEXTURE_CUBE_MAP ? 6 : 1;
				int query = TARGETS[t] == GL_TEXTURE_CUBE_MAP ? GL_TEXTURE_CUBE_MAP_POSITIVE_X : TARGETS[t];
				int w0 = 0, h0 = 0, d0 = 0;
				for (int level = 0; level < 16; ++level)
				{
					int w = glGetTexLevelParameteri(query, level, GL_TEXTURE_WIDTH), h = glGetTexLevelParameteri(query, level, GL_TEXTURE_HEIGHT);
					int d = glGetTexLevelParameteri(query, level, GL_TEXTURE_DEPTH);
					if (w == 0)
					{
						break;
					}
					if (level == 0)
					{
						w0 = w;
						h0 = h;
						d0 = d;
					}
					int bits = glGetTexLevelParameteri(query, level, GL_TEXTURE_RED_SIZE) + glGetTexLevelParameteri(query, level, GL_TEXTURE_GREEN_SIZE)
						+ glGetTexLevelParameteri(query, level, GL_TEXTURE_BLUE_SIZE) + glGetTexLevelParameteri(query, level, GL_TEXTURE_ALPHA_SIZE)
						+ glGetTexLevelParameteri(query, level, GL_TEXTURE_DEPTH_SIZE) + glGetTexLevelParameteri(query, level, GL_TEXTURE_STENCIL_SIZE);
					int samples = Math.max(1, glGetTexLevelParameteri(query, level, GL_TEXTURE_SAMPLES));
					bytes += (long) w * h * d * faces * bits * samples / 8;
				}
				what = TARGET_NAMES[t] + " " + w0 + "x" + h0 + (d0 > 1 ? "x" + d0 : "");
				glBindTexture(TARGETS[t], 0);
				break;
			}
			while (glGetError() != GL_NO_ERROR)
			{
			}
			textures += bytes;
			items.add(new long[]{bytes, names.size()});
			names.add("texture " + id + ": " + what);
		}
		for (int id = 1; id < top * 8; ++id)
		{
			if (glIsBuffer(id))
			{
				glBindBuffer(GL_COPY_READ_BUFFER, id);
				long size = glGetBufferParameteri64(GL_COPY_READ_BUFFER, GL_BUFFER_SIZE);
				buffers += size;
				items.add(new long[]{size, names.size()});
				names.add("buffer " + id);
			}
		}
		glBindBuffer(GL_COPY_READ_BUFFER, 0);
		for (int id = 1; id < top; ++id)
		{
			if (glIsRenderbuffer(id))
			{
				glBindRenderbuffer(GL_RENDERBUFFER, id);
				int w = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_WIDTH), h = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_HEIGHT);
				int bits = glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_RED_SIZE) + glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_GREEN_SIZE)
					+ glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_BLUE_SIZE) + glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_ALPHA_SIZE)
					+ glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_DEPTH_SIZE) + glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_STENCIL_SIZE);
				int samples = Math.max(1, glGetRenderbufferParameteri(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES));
				long size = (long) w * h * bits * samples / 8;
				renderbuffers += size;
				items.add(new long[]{size, names.size()});
				names.add("renderbuffer " + id + ": " + w + "x" + h);
			}
		}
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
		items.sort((a, b) -> Long.compare(b[0], a[0]));
		Map<String, Object> m = Card.map();
		m.put("totalMB", Card.round((textures + buffers + renderbuffers) / 1048576.0, 1));
		m.put("texturesMB", Card.round(textures / 1048576.0, 1));
		m.put("buffersMB", Card.round(buffers / 1048576.0, 1));
		m.put("renderbuffersMB", Card.round(renderbuffers / 1048576.0, 1));
		List<String> biggest = new ArrayList<>();
		for (int i = 0; i < Math.min(16, items.size()); ++i)
		{
			biggest.add(String.format("%.1f MB %s", items.get(i)[0] / 1048576.0, names.get((int) items.get(i)[1])));
		}
		m.put("biggest", biggest);
		return m;
	}
}
