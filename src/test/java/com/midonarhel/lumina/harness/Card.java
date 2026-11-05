package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.light.CardLamps;
import com.midonarhel.lumina.light.PointLight;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/** What the scorecard's parts share: the settings a dump is drawn with, lamps isolated, pixels, the rise of a frame. */
final class Card
{
	static final int LIT = 2;           // of 255 in any channel: a pixel a lamp rose by more than this is lit by it
	static final double LIGHTING_SCALE = 0.35; // the LIGHTING view's: linearToSrgb(light * 0.35)
	private static final float[] SRGB = new float[256];

	static
	{
		for (int i = 0; i < 256; ++i)
		{
			double c = i / 255.0;
			SRGB[i] = (float) (c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4));
		}
	}

	private Card()
	{
	}

	/** Each live thread's CPU time but this one's, ns. */
	static Map<Long, Long> threadCpu(java.lang.management.ThreadMXBean bean)
	{
		Map<Long, Long> m = new java.util.HashMap<>();
		long me = Thread.currentThread().getId();
		for (long id : bean.getAllThreadIds())
		{
			long t = id == me ? -1 : bean.getThreadCpuTime(id);
			if (t > 0)
			{
				m.put(id, t);
			}
		}
		return m;
	}

	/** The other threads' CPU ms since threadCpu gave before: those that ended since are not counted. */
	static double threadCpuSince(java.lang.management.ThreadMXBean bean, Map<Long, Long> before)
	{
		long sum = 0;
		for (Map.Entry<Long, Long> e : threadCpu(bean).entrySet())
		{
			sum += e.getValue() - before.getOrDefault(e.getKey(), 0L);
		}
		return sum / 1e6;
	}

	static float linear(int v)
	{
		return SRGB[v];
	}

	/** What each part of the card draws with: night, one sample a pixel at full scale, no fog or AO unless it asks for them. */
	static DumpRenderer.Options options(String weather, boolean fog)
	{
		DumpRenderer.Options o = new DumpRenderer.Options();
		o.hour = 21;
		o.settings.put("antiAliasingMode", "DISABLED");
		o.settings.put("renderScale", "FULL");
		o.settings.put("ambientOcclusion", "false");
		o.settings.put("volumetricFog", String.valueOf(fog));
		o.settings.put("weatherMode", weather);
		o.settings.put("weatherLightning", "false");
		o.restoreWeather = false;
		return o;
	}

	static int[] pixels(BufferedImage image)
	{
		return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
	}

	static void png(int[] rgb, int w, int h, Path file) throws Exception
	{
		BufferedImage i = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		System.arraycopy(rgb, 0, pixels(i), 0, rgb.length);
		ImageIO.write(i, "png", file.toFile());
	}

	/** The frame's lamps: isolating one sets the others' intensity to 0, so each stays in every list and keeps its slots. */
	static final class Lamps
	{
		final List<PointLight> list;
		final float[] saved;

		Lamps(LuminaPlugin plugin)
		{
			list = new ArrayList<>(plugin.frameLights);
			saved = new float[list.size()];
			for (int i = 0; i < saved.length; ++i)
			{
				saved[i] = CardLamps.intensity(list.get(i));
			}
		}

		void all()
		{
			for (int i = 0; i < saved.length; ++i)
			{
				CardLamps.intensity(list.get(i), saved[i]);
			}
		}

		void none()
		{
			only(-1);
		}

		void only(int k)
		{
			for (int i = 0; i < saved.length; ++i)
			{
				CardLamps.intensity(list.get(i), i == k ? saved[i] : 0);
			}
		}
	}

	/** Lamp k of the frame's list in the probe's list, by place: a method keeps the dump's lamps, whatever their order. */
	static int[] matchLamps(LuminaPlugin plugin, List<PointLight> lamps, float[][] probed)
	{
		int[] out = new int[lamps.size()];
		for (int i = 0; i < out.length; ++i)
		{
			float[] d = CardLamps.describe(plugin, lamps.get(i));
			out[i] = -1;
			for (int j = 0; j < probed.length; ++j)
			{
				if (Math.abs(probed[j][CardLamps.X] - d[CardLamps.X]) < 1 && Math.abs(probed[j][CardLamps.Y] - d[CardLamps.Y]) < 1
					&& Math.abs(probed[j][CardLamps.Z] - d[CardLamps.Z]) < 1 && Math.abs(probed[j][CardLamps.RADIUS] - d[CardLamps.RADIUS]) < 1)
				{
					out[i] = j;
					break;
				}
			}
		}
		return out;
	}

	static int rise(int on, int off)
	{
		int r = (on >> 16 & 255) - (off >> 16 & 255), g = (on >> 8 & 255) - (off >> 8 & 255), b = (on & 255) - (off & 255);
		return Math.max(r, Math.max(g, b));
	}

	static int maxDiff(int a, int b)
	{
		int r = Math.abs((a >> 16 & 255) - (b >> 16 & 255)), g = Math.abs((a >> 8 & 255) - (b >> 8 & 255)), c = Math.abs((a & 255) - (b & 255));
		return Math.max(r, Math.max(g, c));
	}

	static Map<String, Object> map()
	{
		return new LinkedHashMap<>();
	}

	static double round(double v, int digits)
	{
		double s = Math.pow(10, digits);
		return Math.round(v * s) / s;
	}

	/**
	 * Connected groups of set cells of a grid (4 neighbours): for each, {cells, x of a cell, y of a cell}, largest first.
	 * The grid is left clear.
	 */
	static List<int[]> groups(boolean[] set, int w, int h)
	{
		List<int[]> out = new ArrayList<>();
		int[] stack = new int[w * h];
		for (int i = 0; i < w * h; ++i)
		{
			if (!set[i])
			{
				continue;
			}
			int sp = 0, n = 0;
			long sx = 0, sy = 0;
			stack[sp++] = i;
			set[i] = false;
			while (sp > 0)
			{
				int c = stack[--sp];
				int x = c % w, y = c / w;
				++n;
				sx += x;
				sy += y;
				if (x > 0 && set[c - 1])
				{
					set[c - 1] = false;
					stack[sp++] = c - 1;
				}
				if (x + 1 < w && set[c + 1])
				{
					set[c + 1] = false;
					stack[sp++] = c + 1;
				}
				if (y > 0 && set[c - w])
				{
					set[c - w] = false;
					stack[sp++] = c - w;
				}
				if (y + 1 < h && set[c + w])
				{
					set[c + w] = false;
					stack[sp++] = c + w;
				}
			}
			out.add(new int[]{n, (int) (sx / n), (int) (sy / n)});
		}
		out.sort((a, b) -> b[0] - a[0]);
		return out;
	}

	static SceneDump.Loaded load(String name) throws java.io.IOException
	{
		return RenderDump.load(name);
	}
}
