package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.PlainData;
import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.weather.WeatherField;
import java.util.Arrays;

/**
 * The client thread's time in the weather field's upload (WeatherField.ensureUploaded, through bindSky as the sky
 * capture reaches it) while the GPU is busy drawing the dump's frames, as it is in the client: frames are drawn without
 * waiting for them, and each frame the field is marked stale and bound again. Prints the field's size and the upload's
 * median, p90 and max over the frames. ./gradlew weatherFieldUpload -Pargs="tree1 200"
 */
public final class WeatherFieldUploadProbe
{
	public static void main(String[] args) throws Exception
	{
		SceneDump.Loaded dump = RenderDump.load(args[0]);
		int frames = args.length > 1 ? Integer.parseInt(args[1]) : 200;
		try (DumpRenderer r = new DumpRenderer(dump, new DumpRenderer.Options()))
		{
			r.render();
			WeatherField field = r.plugin.weather.getField();
			double[] ms = new double[frames], idle = new double[frames];
			// as the client is paced (vsync, the swap): at most two frames in flight, the older waited for before the next
			java.util.ArrayDeque<Long> fences = new java.util.ArrayDeque<>();
			for (int i = -10; i < frames; ++i)
			{
				for (int k = 0; k < 4; ++k)
				{
					// the frames between two of the field's uploads, each after the one two before it is done
					while (fences.size() >= 2)
					{
						long f = fences.poll();
						org.lwjgl.opengl.GL33C.glClientWaitSync(f, org.lwjgl.opengl.GL33C.GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
						org.lwjgl.opengl.GL33C.glDeleteSync(f);
					}
					r.frame();
					fences.add(org.lwjgl.opengl.GL33C.glFenceSync(org.lwjgl.opengl.GL33C.GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
				}
				// the same bind with nothing to upload: what any GL call costs right after the frame
				long b0 = System.nanoTime();
				field.bindSky(com.midonarhel.lumina.LuminaPlugin.WEATHER_SKY_UNIT);
				long b1 = System.nanoTime();
				if (i >= 0)
				{
					idle[i] = (b1 - b0) / 1e6;
				}
				PlainData.set(field, "uploaded", false);
				long t0 = System.nanoTime();
				field.bindSky(com.midonarhel.lumina.LuminaPlugin.WEATHER_SKY_UNIT);
				long t1 = System.nanoTime();
				if (i >= 0)
				{
					ms[i] = (t1 - t0) / 1e6;
				}
			}
			Arrays.sort(ms);
			Arrays.sort(idle);
			System.out.printf("FIELD %s: %sx%s; upload median %.2f ms, p90 %.2f, max %.2f; the bind alone median %.2f, max %.2f%n", args[0],
				PlainData.get(field, "sizeX"), PlainData.get(field, "sizeZ"), ms[frames / 2], ms[frames * 9 / 10], ms[frames - 1],
				idle[frames / 2], idle[frames - 1]);
		}
		System.exit(0);
	}

	private WeatherFieldUploadProbe()
	{
	}
}
