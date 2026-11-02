package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.frame.Composite;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The weather's and the clouds' cost on the bench, for a change's before and after. Frames of the dump named by
 * -Dlumina.dump (camera -Dlumina.camera=yaw,pitch,scale, other settings -Dlumina.set=key=value,..., the dump's weather
 * left out with -Dlumina.weatherState=off), -Dlumina.frames of each (default 240):
 * <ul>
 * <li>GPU, GL_TIME_ELAPSED (Composite.passTimers): the half resolution cloud pass (which the water shares when it is
 * drawn: so frames alternate the clouds on and off, and the clouds' cost is the difference of neighbouring frames),
 * the full composite, and the rain and snow;</li>
 * <li>CPU: the weather's update for a frame (WeatherFrame.update: the climate, the field, the wind, the lightning) and
 * the clouds' (SkyFrame.updateClouds), each timed alone between frames.</li>
 * </ul>
 * Prints medians and appends them to -Dlumina.out. With -Dlumina.halfRate=true instead: the clouds at half rate
 * against every frame (Composite.cloudsEveryFrame), the clouds always on and the camera still, -Dlumina.frames of
 * two frames of each in turn, the saving per frame as each window of four's difference in the whole frame's GPU time.
 */
public class WeatherCostProbe
{
	@Test
	public void halfRate() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("-Dlumina.halfRate", Boolean.getBoolean("lumina.halfRate"));
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		int n = Integer.parseInt(System.getProperty("lumina.frames", "240"));
		try (DumpRenderer r = new DumpRenderer(RenderDump.load(name), options()))
		{
			r.render();
			// the whole frame's GPU time: the per pass queries read nothing on this GL when the pass holds the clouds alone
			int q = glGenQueries();
			// what a kept frame shows against a frame that draws its clouds, the same moment: written out with -Dlumina.out
			Composite.cloudsEveryFrame = true;
			r.frame();
			java.awt.image.BufferedImage drawn = r.readBack();
			Composite.cloudsEveryFrame = false;
			r.frame();
			r.frame();
			java.awt.image.BufferedImage kept = r.readBack();
			int off = 0, most = 0;
			for (int y = 0; y < drawn.getHeight(); ++y)
			{
				for (int x = 0; x < drawn.getWidth(); ++x)
				{
					int a = drawn.getRGB(x, y), b = kept.getRGB(x, y), m = 0;
					for (int sh = 0; sh < 24; sh += 8)
					{
						m = Math.max(m, Math.abs((a >> sh & 255) - (b >> sh & 255)));
					}
					most = Math.max(most, m);
					off += m > 8 ? 1 : 0;
				}
			}
			String image = String.format("kept frame against drawn: max %d, %.3f %% of pixels off by more than 8", most,
				100.0 * off / (drawn.getWidth() * drawn.getHeight()));
			System.out.println(image);
			// Two frames drawing the clouds every frame, then two at half rate (one drawing all the sky, one keeping it:
			// the steady state), in turn; the saving is each window of four's difference, so the GPU's clock, which swings
			// a frame's time by more than the clouds cost, cancels.
			double[] saved = new double[n];
			double[] pair = new double[2];
			for (int w = -5; w < n; ++w)
			{
				for (int mode = 0; mode < 2; ++mode)
				{
					Composite.cloudsEveryFrame = mode == 0;
					pair[mode] = 0;
					for (int f = 0; f < 2; ++f)
					{
						glBeginQuery(GL_TIME_ELAPSED, q);
						r.frame();
						glEndQuery(GL_TIME_ELAPSED);
						pair[mode] += glGetQueryObjecti64(q, GL_QUERY_RESULT) / 1e6;
					}
				}
				if (w >= 0)
				{
					saved[w] = (pair[0] - pair[1]) / 2;
				}
			}
			Composite.cloudsEveryFrame = false;
			glDeleteQueries(q);
			double[] sorted = saved.clone();
			Arrays.sort(sorted);
			String line = String.format("%s%s, GPU ms a frame saved at half rate, %d windows of four frames: median %.3f,"
					+ " quartiles %.3f to %.3f; %s", name,
				System.getProperty("lumina.camera") != null ? " camera " + System.getProperty("lumina.camera") : "", n,
				sorted[n / 2], sorted[n / 4], sorted[3 * n / 4], image);
			System.out.println(line);
			String out = System.getProperty("lumina.out");
			if (out != null)
			{
				Files.writeString(Path.of(out), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
	}

	private static DumpRenderer.Options options()
	{
		DumpRenderer.Options o = new DumpRenderer.Options();
		String camera = System.getProperty("lumina.camera");
		if (camera != null)
		{
			String[] c = camera.split(",");
			o.cameraYaw = Integer.parseInt(c[0]);
			o.cameraPitch = Integer.parseInt(c[1]);
			o.scale = Integer.parseInt(c[2]);
		}
		for (String kv : System.getProperty("lumina.set", "").split(","))
		{
			if (kv.contains("="))
			{
				o.settings.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
			}
		}
		o.restoreWeather = !"off".equals(System.getProperty("lumina.weatherState"));
		return o;
	}

	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeFalse("-Dlumina.halfRate", Boolean.getBoolean("lumina.halfRate"));
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		String camera = System.getProperty("lumina.camera");
		int n = Integer.parseInt(System.getProperty("lumina.frames", "240"));
		try (DumpRenderer r = new DumpRenderer(RenderDump.load(name), options()))
		{
			r.render();
			int[] q = {glGenQueries(), glGenQueries(), glGenQueries()};
			Composite.passTimers = q;
			double[] halfOn = new double[n], halfOff = new double[n], comp = new double[n], rain = new double[n];
			double[] pair = new double[n], cpuWeather = new double[n], cpuClouds = new double[n];
			int rainFrames = 0;
			for (int i = 0; i < 2 * n + 20; ++i)
			{
				boolean off = (i & 1) == 1;
				r.world.settings.put("clouds", String.valueOf(!off));
				Composite.passTimersUsed = 0;
				r.frame();
				// only the queries the frame began: no half pass without the clouds and the water, no rain without rain
				int used = Composite.passTimersUsed;
				boolean particles = (used & 4) != 0;
				double half = (used & 1) != 0 ? glGetQueryObjecti64(q[0], GL_QUERY_RESULT) / 1e6 : 0;
				double c = (used & 2) != 0 ? glGetQueryObjecti64(q[1], GL_QUERY_RESULT) / 1e6 : 0;
				double p = particles ? glGetQueryObjecti64(q[2], GL_QUERY_RESULT) / 1e6 : 0;
				// the CPU's, alone: this frame's camera, the weather's and the clouds' updates once more
				long t0 = System.nanoTime();
				r.plugin.weatherFrame.update(r.plugin.frameCameraX, r.plugin.frameCameraY, r.plugin.frameCameraZ);
				long t1 = System.nanoTime();
				r.plugin.skyFrame.updateClouds();
				long t2 = System.nanoTime();
				if (i >= 20)
				{
					int k = (i - 20) / 2;
					(off ? halfOff : halfOn)[k] = half;
					if (!off)
					{
						comp[k] = c;
						rain[k] = p;
						rainFrames += particles ? 1 : 0;
						cpuWeather[k] = (t1 - t0) / 1e3;
						cpuClouds[k] = (t2 - t1) / 1e3;
					}
					else
					{
						pair[k] = halfOn[k] - half;
					}
				}
			}
			Composite.passTimers = null;
			r.world.settings.remove("clouds");
			for (int id : q)
			{
				glDeleteQueries(id);
			}
			String line = String.format("%s%s: clouds (half pass, on less off, neighbouring frames) %.3f ms; half pass with"
					+ " clouds %.3f ms, without %.3f ms; composite %.3f ms; rain and snow %.3f ms (%d of %d frames);"
					+ " CPU weather update %.1f us, clouds' update %.1f us (medians of %d)",
				name, camera != null ? " camera " + camera : "", median(pair), median(halfOn), median(halfOff), median(comp),
				median(rain), rainFrames, n, median(cpuWeather), median(cpuClouds), n);
			System.out.println(line);
			String out = System.getProperty("lumina.out");
			if (out != null)
			{
				Files.writeString(Path.of(out), line + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
	}

	private static double median(double[] v)
	{
		double[] s = v.clone();
		Arrays.sort(s);
		return s[s.length / 2];
	}
}
