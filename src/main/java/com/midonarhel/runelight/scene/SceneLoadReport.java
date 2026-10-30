package com.midonarhel.runelight.scene;

import com.midonarhel.runelight.*;

import com.midonarhel.runelight.dev.CpuTimer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One log line per scene load, the numbers the load pipeline is judged by: the swap frame's time and how much of it
 * was ours, our GL buffer and vertex array creations and deletions from the start of the load to two seconds after
 * the swap, and the frames over 25 ms in those two seconds with our client-thread time in each.
 *
 * The GL counts are kept here for every buffer and vertex array the renderer makes or deletes (client thread only).
 * "Ours" in a frame is the plugin's own CPU steps (CpuTimer, all but the client's scene, its interface, the swap
 * wait and the time outside the frame) plus the scene swap and the load's jobs that ran in it.
 */
public final class SceneLoadReport
{
	private static final Logger log = LoggerFactory.getLogger(SceneLoadReport.class);

	private static final long WINDOW_NANOS = 2_000_000_000L;
	private static final double SLOW_MS = 25;

	// GL objects made and deleted, client thread
	static int buffersCreated, buffersDeleted, arraysCreated, arraysDeleted;

	private static final int[] OUR_STAGES = {
		CpuTimer.ENVIRONMENT, CpuTimer.WEATHER, CpuTimer.LIGHTS, CpuTimer.CLUSTERS, CpuTimer.SHADOW_SUBMIT,
		CpuTimer.UI_UPLOAD, CpuTimer.BUFFER_MAP, CpuTimer.LIGHT_UPLOAD, CpuTimer.VOXELS, CpuTimer.SKY_CACHE,
		CpuTimer.OCCLUSION, CpuTimer.SKY_GRID
		// the light map is timed inside light collection (CpuTimer.nested)
	};

	private volatile long loadStartNanos;
	private volatile int countsAtLoad[];
	// the load's client-thread work before the swap (zone buffers made and mapped, unmapped), and its frames
	private volatile double preSwapMs;
	private volatile int preSwapCalls;
	// our loadScene, by stage (map loader thread), and when it returned
	private final StringBuilder stages = new StringBuilder();
	private volatile String stagesText = "";
	private volatile long loadEndNanos;
	private volatile String uploadThreadsText = "";
	private ZonePool pool;

	private long swapNanos;
	private double swapMs;
	private int swapFreed, swapReused;
	private double extraMs;
	private int frames, slow;
	private double swapFrameMs, swapFrameOurs, maxMs, maxOurs, oursAfter;
	private final StringBuilder slowText = new StringBuilder();

	/** The load began (the map loader's thread). */
	void loadStarted()
	{
		loadStartNanos = System.nanoTime();
		countsAtLoad = new int[]{buffersCreated, buffersDeleted, arraysCreated, arraysDeleted};
		preSwapMs = 0;
		preSwapCalls = 0;
		stages.setLength(0);
		loadEndNanos = 0;
	}

	/** A stage of our loadScene took this long (map loader thread). */
	void stage(String name, double ms)
	{
		stages.append(stages.length() > 0 ? ", " : "").append(name).append(String.format(" %.1f", ms));
	}

	void setPool(ZonePool pool)
	{
		this.pool = pool;
	}

	/** The upload's threads: their waits for mapped buffers and their writing, ms (map loader thread). */
	void uploadThreads(String text)
	{
		uploadThreadsText = text;
	}

	/** Our loadScene returned (map loader thread): what follows until the swap is the client's. */
	void loadReturned()
	{
		loadEndNanos = System.nanoTime();
		stagesText = stages.toString();
	}

	/** Client-thread work the load did before the swap, and in how many steps (frames). */
	void preSwap(double ms, int steps)
	{
		preSwapMs += ms;
		preSwapCalls += steps;
	}

	/** Our own work in this frame outside the CPU steps (the load's jobs), client thread. */
	void extra(double ms)
	{
		extraMs += ms;
	}

	/** The scene was swapped in (client thread); the two-second window starts. */
	void swapped(double ms, int freed, int reused)
	{
		swapNanos = System.nanoTime();
		swapMs = ms;
		swapFreed = freed;
		swapReused = reused;
		frames = 0;
		slow = 0;
		maxMs = maxOurs = oursAfter = 0;
		slowText.setLength(0);
	}

	/** After each frame's swap, with its interval. */
	public void frame(double frameMs, CpuTimer cpu)
	{
		double ours = extraMs;
		extraMs = 0;
		if (swapNanos == 0)
		{
			return;
		}
		for (int stage : OUR_STAGES)
		{
			ours += cpu.getLastMs(stage);
		}
		if (frames == 0)
		{
			// the frame the swap ran in (it runs outside our frame, before this frame's scene)
			ours += swapMs;
			swapFrameMs = frameMs;
			swapFrameOurs = ours;
		}
		else
		{
			maxMs = Math.max(maxMs, frameMs);
			maxOurs = Math.max(maxOurs, ours);
			oursAfter += ours;
		}
		if (frameMs > SLOW_MS)
		{
			slow++;
			if (slowText.length() < 400)
			{
				slowText.append(String.format(" +%d:%.0f/%.1f", frames, frameMs, ours));
			}
		}
		frames++;
		long now = System.nanoTime();
		if (now - swapNanos >= WINDOW_NANOS)
		{
			int[] at = countsAtLoad;
			if (at == null)
			{
				at = new int[4];
			}
			long loadEnd = loadEndNanos;
			log.info(String.format("Scene load report: load→swap %.0f ms = our loadScene %.0f (%s) + the client's until its swap %.0f; pre-swap client work %.1f ms in %d steps; swap frame %.1f ms, ours %.1f (swapScene %.1f, %d freed, %d reused); "
					+ "GL buffers +%d -%d, vertex arrays +%d -%d (%s); upload threads: %s; next 2 s: %d frames, %d over %.0f ms (frame:ms/ours:ms%s), max %.1f ms, our max %.1f, our mean %.2f",
				loadStartNanos == 0 ? 0 : (swapNanos - loadStartNanos) / 1e6,
				loadStartNanos == 0 || loadEnd == 0 ? 0 : (loadEnd - loadStartNanos) / 1e6, stagesText,
				loadEnd == 0 ? 0 : (swapNanos - loadEnd) / 1e6, preSwapMs, preSwapCalls,
				swapFrameMs, swapFrameOurs, swapMs, swapFreed, swapReused,
				buffersCreated - at[0], buffersDeleted - at[1], arraysCreated - at[2], arraysDeleted - at[3],
				pool != null ? pool.takeCounts() : "no pool", uploadThreadsText,
				frames - 1, slow, SLOW_MS, slowText, maxMs, maxOurs, frames > 1 ? oursAfter / (frames - 1) : 0));
			swapNanos = 0;
			loadStartNanos = 0;
			countsAtLoad = null;
		}
	}
}
