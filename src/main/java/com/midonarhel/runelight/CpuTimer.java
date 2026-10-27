package com.midonarhel.runelight;

/**
 * CPU time of the plugin's own per-frame steps and of the parts of the frame it doesn't control, averaged.
 * Steps may be begun several times a frame (only on the client thread); their times add up. Not nested: begin
 * and end the same step, never two at once unless they are different steps.
 */
class CpuTimer
{
	// the client's scene traversal between our pre and post scene callbacks, including our draw callbacks
	static final int CLIENT_SCENE = 0;
	static final int ENVIRONMENT = 1;
	static final int WEATHER = 2;
	static final int LIGHTS = 3;
	static final int CLUSTERS = 4;
	static final int SHADOW_SUBMIT = 5;
	static final int UI_UPLOAD = 6;
	// waiting in swapBuffers: large when the GPU (or vsync) is the limit
	static final int SWAP = 7;
	// from our swap to the next frame's scene: the client's game loop, other plugins and overlays
	static final int OUTSIDE = 8;
	// between our scene and our draw: the client drawing its interface and RuneLite's overlays into the canvas
	static final int CLIENT_UI = 9;
	// mapping the dynamic model buffers at the start of the scene
	static final int BUFFER_MAP = 10;
	// sending the light and cluster tables to the GPU (part of light clusters before)
	static final int LIGHT_UPLOAD = 11;
	// the voxel build's submit; the sky capture; the occlusion map redraws
	static final int VOXELS = 12;
	static final int SKY_CACHE = 13;
	static final int OCCLUSION = 14;
	// handing the skylight's grid its next rays and uploading what it traced (the tracing is on its worker)
	static final int SKY_GRID = 15;
	// handing the light map its changes and sending the blocks baked since (the baking is on its threads)
	static final int LIGHT_MAP = 16;
	static final int STAGES = 17;

	static final String[] NAMES = {
		"client scene", "environment", "weather", "light collection", "light clusters", "shadow submit",
		"UI upload", "swap wait", "outside frame", "client UI + overlays",
		"buffer map", "light upload", "scene voxels", "sky capture", "occlusion maps", "sky grid", "light map"
	};

	// averages are over whole windows of this long, so a reading holds still while it is read
	static final long WINDOW_NANOS = 1_000_000_000L;

	private final long[] start = new long[STAGES];
	private final double[] frame = new double[STAGES];
	// each step's mean per frame over the last finished window, and the window being summed
	private final double[] ms = new double[STAGES];
	private final double[] windowSum = new double[STAGES];
	private int windowFrames;
	private long windowStart;
	// the frame that just ended, for the hitch monitor
	private final double[] last = new double[STAGES];

	void begin(int stage)
	{
		start[stage] = System.nanoTime();
	}

	void end(int stage)
	{
		if (start[stage] != 0)
		{
			frame[stage] += (System.nanoTime() - start[stage]) / 1e6;
			start[stage] = 0;
		}
	}

	/** Fold this frame's times into the averages. */
	void endFrame()
	{
		for (int s = 0; s < STAGES; ++s)
		{
			windowSum[s] += frame[s];
			last[s] = frame[s];
			frame[s] = 0;
		}
		++windowFrames;
		long now = System.nanoTime();
		if (now - windowStart >= WINDOW_NANOS)
		{
			for (int s = 0; s < STAGES; ++s)
			{
				ms[s] = windowSum[s] / windowFrames;
				windowSum[s] = 0;
			}
			windowFrames = 0;
			windowStart = now;
		}
	}

	/** A step's mean time per frame over the last whole window. */
	double getMs(int stage)
	{
		return ms[stage];
	}

	/** A step's time in the frame that ended last, not averaged. */
	double getLastMs(int stage)
	{
		return last[stage];
	}

	/** The frame's time not in any step but the one outside it: the plugin's own untimed work. */
	double untrackedMs(double cpuFrameMs)
	{
		double sum = 0;
		for (int s = 0; s < STAGES; ++s)
		{
			if (s != OUTSIDE)
			{
				sum += ms[s];
			}
		}
		return Math.max(cpuFrameMs - sum, 0);
	}
}
