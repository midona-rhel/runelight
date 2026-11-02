package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.CpuTimer;
import com.midonarhel.lumina.dev.SceneDump;
import java.util.ArrayDeque;
import java.util.Arrays;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The client thread's time mapping the dynamic model buffers at the start of the scene (CpuTimer's buffer map) while
 * the GPU is busy drawing the dump's frames, as it is in the client: frames are drawn without waiting for them, at most
 * two in flight (as the swap paces them). Prints the map's median, p90 and max over the frames, and the dynamic
 * buffers' size. fps1 at 100%: 0.007 ms with two in flight, 4.2 (max 13) with no pacing, and the frame on the CPU the
 * same 7.2 ms either way and with the buffers in a ring mapped unsynchronized: the map is where the client thread
 * waits for a GPU that is behind, not a wait of its own. inFlight=N lets N frames be in flight (0: no pacing, the driver's own queue alone).
 * ./gradlew bufferMap -Pargs="fps1 300 [inFlight=2] [key=value settings]"
 */
public final class BufferMapProbe
{
	public static void main(String[] args) throws Exception
	{
		SceneDump.Loaded dump = RenderDump.load(args[0]);
		int frames = args.length > 1 ? Integer.parseInt(args[1]) : 300;
		DumpRenderer.Options o = new DumpRenderer.Options();
		int inFlight = 2;
		for (int i = 2; i < args.length; ++i)
		{
			int eq = args[i].indexOf('=');
			if (args[i].startsWith("inFlight="))
			{
				inFlight = Integer.parseInt(args[i].substring(eq + 1));
				continue;
			}
			o.settings.put(args[i].substring(0, eq), args[i].substring(eq + 1));
		}
		try (DumpRenderer r = new DumpRenderer(dump, o))
		{
			r.render();
			double[] ms = new double[frames], frameMs = new double[frames];
			ArrayDeque<Long> fences = new ArrayDeque<>();
			for (int i = -20; i < frames; ++i)
			{
				while (inFlight > 0 && fences.size() >= inFlight)
				{
					long f = fences.poll();
					glClientWaitSync(f, GL_SYNC_FLUSH_COMMANDS_BIT, 1_000_000_000L);
					glDeleteSync(f);
				}
				long t0 = System.nanoTime();
				r.frame();
				long t1 = System.nanoTime();
				if (inFlight > 0)
				{
					fences.add(glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0));
				}
				if (i >= 0)
				{
					ms[i] = r.plugin.cpuTimer.getLastMs(CpuTimer.BUFFER_MAP);
					frameMs[i] = (t1 - t0) / 1e6;
				}
			}
			int kb = 0;
			for (var rt : r.plugin.rts)
			{
				kb += rt.vaoO.size() + rt.vaoA.size();
			}
			Arrays.sort(ms);
			Arrays.sort(frameMs);
			System.out.printf("BUFFERMAP %s, %d in flight: dynamic buffers %d KiB; map median %.3f ms, p90 %.3f, max %.3f; frame on"
					+ " the CPU median %.2f ms%n", args[0], inFlight, kb, ms[frames / 2], ms[frames * 9 / 10], ms[frames - 1],
				frameMs[frames / 2]);
		}
		System.exit(0);
	}

	private BufferMapProbe()
	{
	}
}
