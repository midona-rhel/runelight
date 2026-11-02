package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.Commands;
import com.midonarhel.lumina.dev.SceneDump;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import org.junit.Assume;
import org.junit.Test;
import org.lwjgl.system.Platform;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The skylight's GPU cost on the bench: frames of the dump named by -Dlumina.dump (camera -Dlumina.camera=yaw,pitch,scale,
 * render scale -Dlumina.renderScale, other settings -Dlumina.set=key=value,...), the AO pass and the full composite timed
 * with GL_TIME_ELAPSED queries (Composite.passTimers), alternately with the skylight on and switched off
 * (Commands.skylightOff: the AO pass's open-sky fallback), -Dlumina.frames of each (default 240). Prints and appends to
 * -Dlumina.out the medians and the skylight's share of the AO pass (on less off).
 *
 * -Dlumina.ab=aurora toggles the aurora instead (NightSky.auroraHold 1 and 0, the skylight left on): its cost is the
 * composite's, on less off ("skylight" in the line then reads "aurora", and the AO pass is unchanged).
 * -Dlumina.ab=nebula toggles the nebula the same way (the nebulaStrength setting -Dlumina.nebula, default 100, and 0).
 */
public class GpuCostProbe
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
		String scale = System.getProperty("lumina.renderScale");
		if (scale != null)
		{
			o.settings.put("renderScale", scale);
		}
		// other settings, key=value,key=value (passes the skylight doesn't touch off, to quiet the frame)
		String set = System.getProperty("lumina.set", "");
		for (String kv : set.split(","))
		{
			if (kv.contains("="))
			{
				o.settings.put(kv.substring(0, kv.indexOf('=')), kv.substring(kv.indexOf('=') + 1));
			}
		}
		int n = Integer.parseInt(System.getProperty("lumina.frames", "240"));
		String ab = System.getProperty("lumina.ab", "");
		boolean nebula = ab.equals("nebula"), aurora = ab.equals("aurora") || nebula;
		String nebulaOn = System.getProperty("lumina.nebula", "100");
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			r.render();
			int[] q = {glGenQueries(), glGenQueries()};
			com.midonarhel.lumina.frame.Composite.passTimers = q;
			// per frame: the AO pass (where the skylight is worked out) and the full composite, with the skylight on and off
			double[] aoOn = new double[n], aoOff = new double[n], compOn = new double[n], compOff = new double[n];
			// the skylight per pair of neighbouring frames (on, then off), so the GPU's clock drifting cancels
			double[] pair = new double[n];
			for (int i = 0; i < 2 * n + 20; ++i)
			{
				boolean skyOff = (i & 1) == 1;
				if (aurora)
				{
					com.midonarhel.lumina.sky.NightSky.auroraHold = skyOff ? 0 : 1;
				}
				else
				{
					Commands.skylightOff = skyOff;
				}
				r.frame();
				double ao = glGetQueryObjecti64(q[0], GL_QUERY_RESULT) / 1e6, comp = glGetQueryObjecti64(q[1], GL_QUERY_RESULT) / 1e6;
				if (i >= 20)
				{
					int k = (i - 20) / 2;
					(skyOff ? aoOff : aoOn)[k] = ao;
					(skyOff ? compOff : compOn)[k] = comp;
					if (skyOff) pair[k] = aurora ? compOn[k] - comp : aoOn[k] - ao;
				}
			}
			com.midonarhel.lumina.frame.Composite.passTimers = null;
			Commands.skylightOff = false;
			com.midonarhel.lumina.sky.NightSky.auroraHold = Float.NaN;
			glDeleteQueries(q[0]);
			glDeleteQueries(q[1]);
			for (double[] a : new double[][]{aoOn, aoOff, compOn, compOff, pair}) Arrays.sort(a);
			if (aurora)
			{
				String line = String.format("%s camera=%s renderScale=%s frames=%d: composite %.3f ms (p10 %.3f p90 %.3f) with the %s, %.3f (p10 %.3f p90 %.3f) without: %s %.3f ms (by pairs %.3f, p25 %.3f p75 %.3f)%n",
					name, camera == null ? "dump" : camera, scale == null ? "dump" : scale, n,
					compOn[n / 2], compOn[n / 10], compOn[n * 9 / 10], ab, compOff[n / 2], compOff[n / 10], compOff[n * 9 / 10], ab, compOn[n / 2] - compOff[n / 2], pair[n / 2], pair[n / 4], pair[n * 3 / 4]);
				System.out.print(line);
				String out = System.getProperty("lumina.out");
				if (out != null)
				{
					Files.writeString(Path.of(out), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
				}
				return;
			}
			String line = String.format("%s camera=%s renderScale=%s frames=%d: AO pass %.3f ms (p10 %.3f p90 %.3f), with the skylight off %.3f (p10 %.3f p90 %.3f), skylight %.3f ms (by pairs %.3f, p25 %.3f p75 %.3f); composite %.3f ms (off %.3f)%n",
				name, camera == null ? "dump" : camera, scale == null ? "dump" : scale, n,
				aoOn[n / 2], aoOn[n / 10], aoOn[n * 9 / 10], aoOff[n / 2], aoOff[n / 10], aoOff[n * 9 / 10], aoOn[n / 2] - aoOff[n / 2], pair[n / 2], pair[n / 4], pair[n * 3 / 4],
				compOn[n / 2], compOff[n / 2]);
			System.out.print(line);
			String out = System.getProperty("lumina.out");
			if (out != null)
			{
				Files.writeString(Path.of(out), line, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
		}
	}
}
