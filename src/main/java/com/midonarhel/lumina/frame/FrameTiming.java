package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.dev.Snapshot;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;

/**
 * The frame's CPU and GPU timers and the frame boundaries they are driven from: the scene CPU time (between the
 * first pre-scene and the last post-scene callback, so boats drawn inside or after the top level are counted either
 * way; the record and replay steps inside it), the scene GPU time (the replay of the recorded draws, one contiguous
 * pass), the UI upload, blit and draw, the swap, and the time outside the plugin. A running {@link Snapshot}
 * (developer mode, ::lumina snapshot) records every frame's times until it is done.
 */
@Slf4j
public final class FrameTiming
{
	final GpuTimer gpuTimer = new GpuTimer();
	final CpuTimer cpuTimer = new CpuTimer();

	private final Client client;
	// the running snapshot (::lumina snapshot), or null
	private Snapshot snapshot;
	private long lastFrameEndNanos;
	// worldviews between their pre and post scene callbacks; the scene timers run while it is above 0
	private int sceneDepth;

	FrameTiming(Client client)
	{
		this.client = client;
	}

	void init()
	{
		gpuTimer.init();
	}

	void free()
	{
		gpuTimer.free();
		snapshot = null;
	}

	void beginScene()
	{
		if (sceneDepth++ == 0)
		{
			cpuTimer.end(CpuTimer.OUTSIDE);
			cpuTimer.end(CpuTimer.CLIENT_UI);
			cpuTimer.begin(CpuTimer.CLIENT_SCENE);
		}
	}

	void endScene()
	{
		if (sceneDepth > 0 && --sceneDepth == 0)
		{
			cpuTimer.end(CpuTimer.CLIENT_SCENE);
			cpuTimer.begin(CpuTimer.CLIENT_UI);
		}
	}

	/**
	 * The replay of the frame's recorded draws starts (inside the scene): its CPU time is the replay step, not the
	 * client's traversal. The caller brackets the GPU scene pass with {@link #gpuTimer} itself, after the hook that
	 * runs other passes first.
	 */
	void beginReplay()
	{
		cpuTimer.end(CpuTimer.CLIENT_SCENE);
		cpuTimer.begin(CpuTimer.REPLAY);
	}

	void endReplay()
	{
		gpuTimer.end();
		cpuTimer.end(CpuTimer.REPLAY);
		cpuTimer.begin(CpuTimer.CLIENT_SCENE);
	}

	void endFrame()
	{
		long now = System.nanoTime();
		double frameMs = lastFrameEndNanos == 0 ? 0 : (now - lastFrameEndNanos) / 1e6;
		lastFrameEndNanos = now;

		long frame = gpuTimer.getFrame();
		sceneDepth = 0;
		cpuTimer.endFrame();
		gpuTimer.endFrame();
		if (snapshot != null)
		{
			int players = 0;
			for (var ignored : client.getTopLevelWorldView().players())
			{
				players++;
			}
			// the first frame after start has no previous frame to measure from
			if (frameMs > 0)
			{
				snapshot.addFrame(frame, frameMs, cpuTimer, players);
			}
			if (gpuTimer.getReadFrame() >= 0)
			{
				snapshot.addGpu(gpuTimer.getReadFrame(), gpuTimer);
			}
			if (snapshot.isDone())
			{
				snapshot.write();
				snapshot = null;
				gpuTimer.setEnabled(false);
			}
		}
		cpuTimer.begin(CpuTimer.OUTSIDE);
	}

	public boolean isSnapshotRunning()
	{
		return snapshot != null;
	}

	/**
	 * Starts recording every frame for {@code seconds}; the GPU timers run while it records.
	 */
	public void startSnapshot(List<String> header, int seconds)
	{
		snapshot = new Snapshot(header, seconds);
		gpuTimer.setEnabled(true);
		log.info("Snapshot started: {} s", seconds);
	}
}
