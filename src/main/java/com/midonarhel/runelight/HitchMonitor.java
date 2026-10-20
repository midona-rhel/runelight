package com.midonarhel.runelight;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Finds the cause of occasional long frames from the log. A frame that took more than twice the running median
 * (and more than {@link #MIN_MS}) is logged with the plugin's CPU steps and GPU passes that were large in it, the
 * garbage collections that ran during it, and the periodic jobs that were flagged on it. The GPU times arrive 3
 * frames later, so the line is written then. Client thread only; costs a few array reads a frame.
 */
final class HitchMonitor
{
	private static final Logger log = LoggerFactory.getLogger(HitchMonitor.class);

	// jobs that run now and then, flagged by whoever starts them; the bit is the index in NAMES
	static final int STATIC_SHADOWS = 1;
	static final int ZONE_REBUILD = 1 << 1;
	static final int SCENE_SWAP = 1 << 2;
	static final int SHADER_RELOAD = 1 << 3;
	static final int AREA_HIDING = 1 << 4;
	static final int GI_BUILD = 1 << 5;
	static final int GI_UPLOAD = 1 << 6;
	static final int SKY_CAPTURE = 1 << 7;

	private static final String[] NAMES = {
		"static shadow rebuild", "zone rebuild", "scene swap", "shader reload", "area hiding", "GI build", "GI upload",
		"sky capture"
	};

	private static final double MIN_MS = 12;
	private static final double FACTOR = 2;
	private static final int HISTORY = 64;
	// frames before judging: the first ones load shaders and textures
	private static final int WARMUP = 240;
	// a pending line waits this long for the GPU's times
	private static final int GPU_WAIT = 6;
	// stages of at least this long are named
	private static final double STAGE_MS = 2;
	private static final int MAX_STAGES = 5;

	private final LogLimiter limiter = new LogLimiter(1000);
	private final List<GarbageCollectorMXBean> collectors = ManagementFactory.getGarbageCollectorMXBeans();
	private final double[] history = new double[HISTORY];
	private final double[] sorted = new double[HISTORY];
	private int count;
	private double median;

	private int jobs;
	private long gcCount;
	private long gcMillis;

	// the hitch waiting for its GPU times
	private long pendingFrame = -1;
	private String pendingText;

	/** Flag a job as running this frame. */
	void job(int flag)
	{
		jobs |= flag;
	}

	/**
	 * Call after the frame's swap and the GPU timer's endFrame.
	 *
	 * @param frameMs time since the last swap
	 */
	void endFrame(double frameMs, CpuTimer cpu, GpuTimer gpu)
	{
		long frame = gpu.getFrames();
		long collections = 0;
		long collectionMillis = 0;
		for (int i = 0; i < collectors.size(); ++i)
		{
			collections += Math.max(collectors.get(i).getCollectionCount(), 0);
			collectionMillis += Math.max(collectors.get(i).getCollectionTime(), 0);
		}

		if (pendingText != null)
		{
			if (gpu.getReadFrame() == pendingFrame)
			{
				log.debug("{}; GPU {}", pendingText, gpuText(gpu));
				pendingText = null;
			}
			else if (frame - pendingFrame > GPU_WAIT)
			{
				log.debug("{}; GPU times not read", pendingText);
				pendingText = null;
			}
		}

		if (count >= WARMUP && frameMs > MIN_MS && frameMs > median * FACTOR && pendingText == null)
		{
			int held = limiter.check("hitch");
			if (held >= 0)
			{
				pendingText = text(frame, frameMs, cpu, collections - gcCount, collectionMillis - gcMillis, held);
				pendingFrame = frame;
				if (!gpu.isEnabled())
				{
					log.debug("{}; GPU timings are off (show timings turns them on)", pendingText);
					pendingText = null;
				}
			}
		}

		gcCount = collections;
		gcMillis = collectionMillis;
		jobs = 0;
		history[count++ % HISTORY] = frameMs;
		// the median of the last frames; recomputed every few frames, it moves slowly
		if ((count & 7) == 0 || count == WARMUP)
		{
			int n = Math.min(count, HISTORY);
			System.arraycopy(history, 0, sorted, 0, n);
			Arrays.sort(sorted, 0, n);
			median = sorted[n / 2];
		}
	}

	private String text(long frame, double frameMs, CpuTimer cpu, long collections, long collectionMillis, int held)
	{
		StringBuilder b = new StringBuilder(256);
		b.append(String.format("Hitch: frame %d took %.1f ms (median %.1f)", frame, frameMs, median));
		if (held > 0)
		{
			b.append(", ").append(held).append(" more held back");
		}
		b.append("; CPU ");
		double sum = 0;
		boolean[] named = new boolean[CpuTimer.STAGES];
		int shown = 0;
		for (int s = 0; s < CpuTimer.STAGES; ++s)
		{
			sum += cpu.getLastMs(s);
		}
		for (; shown < MAX_STAGES; ++shown)
		{
			int best = -1;
			for (int s = 0; s < CpuTimer.STAGES; ++s)
			{
				if (!named[s] && cpu.getLastMs(s) >= STAGE_MS && (best < 0 || cpu.getLastMs(s) > cpu.getLastMs(best)))
				{
					best = s;
				}
			}
			if (best < 0)
			{
				break;
			}
			named[best] = true;
			b.append(shown > 0 ? ", " : "").append(CpuTimer.NAMES[best]).append(' ')
				.append(String.format("%.1f", cpu.getLastMs(best)));
		}
		if (shown == 0)
		{
			b.append("no step over ").append((int) STAGE_MS).append(" ms");
		}
		// the part of the frame no step covers: the plugin's untimed work, mostly the composite's submit
		double untimed = frameMs - sum;
		if (untimed >= STAGE_MS)
		{
			b.append(String.format(", untimed %.1f", untimed));
		}
		if (collections > 0)
		{
			b.append(String.format("; GC %d collections, %d ms", collections, collectionMillis));
		}
		b.append("; jobs ");
		boolean any = false;
		for (int i = 0; i < NAMES.length; ++i)
		{
			if ((jobs & 1 << i) != 0)
			{
				b.append(any ? ", " : "").append(NAMES[i]);
				any = true;
			}
		}
		if (!any)
		{
			b.append("none");
		}
		return b.toString();
	}

	private static String gpuText(GpuTimer gpu)
	{
		StringBuilder b = new StringBuilder(128);
		b.append(String.format("%.1f ms", gpu.getReadTotalMs()));
		boolean[] named = new boolean[GpuTimer.STAGES];
		for (int n = 0; n < MAX_STAGES; ++n)
		{
			int best = -1;
			for (int s = 0; s < GpuTimer.STAGES; ++s)
			{
				if (!named[s] && gpu.getReadMs(s) >= STAGE_MS && (best < 0 || gpu.getReadMs(s) > gpu.getReadMs(best)))
				{
					best = s;
				}
			}
			if (best < 0)
			{
				break;
			}
			named[best] = true;
			b.append(", ").append(GpuTimer.NAMES[best]).append(String.format(" %.1f", gpu.getReadMs(best)));
		}
		return b.toString();
	}
}
