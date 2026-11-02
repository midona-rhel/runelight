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
 * Prints medians and appends them to -Dlumina.out.
 */
public class WeatherCostProbe
{
	@Test
	public void probe() throws Exception
	{
		String name = System.getProperty("lumina.dump");
		Assume.assumeTrue("no -Dlumina.dump", name != null && !name.isEmpty());
		Assume.assumeTrue("CGL: macOS only", Platform.get() == Platform.MACOSX);
		SceneDump.Loaded dump = RenderDump.load(name);
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
		int n = Integer.parseInt(System.getProperty("lumina.frames", "240"));
		try (DumpRenderer r = new DumpRenderer(dump, o))
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
