package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Non-blocking GPU timing per frame pass. Each frame gets a slot of GL_TIME_ELAPSED queries; a slot is only read
 * back when it comes around again several frames later, and only if the driver says the results are ready, so
 * measuring never stalls the pipeline.
 *
 * Uses elapsed-time queries rather than timestamps: Apple's OpenGL driver returns 0 for GL_TIMESTAMP. Elapsed
 * queries of one kind cannot overlap, so passes are timed one after another, never nested: {@link #begin} ends
 * whatever pass was running. A pass may be begun several times a frame (the UI is uploaded early and drawn late);
 * its pieces are added up.
 */
class GpuTimer
{
	static final int SCENE = 0;
	static final int RESOLVE = 1;
	static final int SHADOW = 2;
	static final int OCCLUSION = 3;
	static final int AO = 4;
	static final int SKY = 5;
	static final int FOG = 6;
	static final int COMPOSITE = 7;
	static final int WEATHER = 8;
	static final int UI = 9;
	static final int WATER = 10;
	// parts of the shadow map: copying the cached static map in, the moving models
	static final int SHADOW_COPY = 11;
	static final int SHADOW_MODELS = 12;
	// uploading the skylight's traced grid
	static final int SKY_GRID = 13;
	static final int FILTER = 14;
	static final int UPSCALE = 15;
	static final int AMBIENT = 16;
	static final int SUN = 17;
	static final int POINT_LIGHTS = 18;
	static final int SURFACES = 19;
	static final int CLOUDS = 20;
	// sending the light map's blocks
	static final int LIGHT_MAP = 21;
	static final int STAGES = 22;

	static final String[] NAMES = {
		"scene (with the client's traversal)", "MSAA resolve", "shadow map", "weather occlusion", "AO + skylight", "sky cache", "fog", "lighting + composite",
		"weather", "UI", "water", "shadow copy", "shadow models", "sky grid upload", "AO/fog filtering", "upscale", "ambient + sky", "sun + cloud shadows", "point lights + blocking", "materials + water blend", "clouds", "light map upload"
	};

	private static final int SLOTS = 4;
	// queries per frame; more begins than this in one frame are not timed
	private static final int ENTRIES = 40;

	private final int[][] queries = new int[SLOTS][ENTRIES];
	private final int[][] stageOf = new int[SLOTS][ENTRIES];
	private final int[] used = new int[SLOTS];
	private int slot;
	private boolean active;
	private boolean initialized;
	// off unless asked for (Time GPU passes): on Apple's driver ending a query makes the CPU wait for the GPU, which
	// stutters the frames being measured
	private boolean enabled = true;

	private final double[] frame = new double[STAGES];
	// each stage's mean per read frame over the last finished window (CpuTimer.WINDOW_NANOS), a stage that did not
	// run in a frame counting 0 for it, and the window being summed
	private final double[] ms = new double[STAGES];
	private double totalMs;
	private final double[] windowSum = new double[STAGES];
	private int windowFrames;
	private long windowStart;
	private final long[] lastRan = new long[STAGES];
	// frames ended so far, and the frame whose times the last endFrame read (-1: none); for the hitch monitor
	private long frames;
	private long readFrame = -1;
	private double readTotalMs;

	void init()
	{
		for (int s = 0; s < SLOTS; ++s)
		{
			glGenQueries(queries[s]);
			used[s] = 0;
		}
		slot = 0;
		active = false;
		java.util.Arrays.fill(ms, 0);
		java.util.Arrays.fill(windowSum, 0);
		totalMs = 0;
		windowFrames = 0;
		windowStart = System.nanoTime();
		initialized = true;
	}

	void free()
	{
		if (!initialized)
		{
			return;
		}
		end();
		for (int s = 0; s < SLOTS; ++s)
		{
			glDeleteQueries(queries[s]);
			used[s] = 0;
		}
		initialized = false;
	}

	/**
	 * Turn timing on or off; call between frames. Turning it off drops the frames still in flight.
	 */
	void setEnabled(boolean on)
	{
		if (on == enabled)
		{
			return;
		}
		end();
		java.util.Arrays.fill(used, 0);
		enabled = on;
	}

	/**
	 * Start timing a pass, ending the previous one if it is still running. Begin right before the pass's GL
	 * commands, after any CPU work that prepares them: the query counts the GPU waiting for those commands too.
	 */
	void begin(int stage)
	{
		if (!initialized || !enabled)
		{
			return;
		}
		end();
		int n = used[slot];
		if (n == ENTRIES)
		{
			return;
		}
		glBeginQuery(GL_TIME_ELAPSED, queries[slot][n]);
		stageOf[slot][n] = stage;
		used[slot] = n + 1;
		active = true;
	}

	void end()
	{
		if (!active)
		{
			return;
		}
		glEndQuery(GL_TIME_ELAPSED);
		// The GPU stops the clock when it reaches the end, which is when the driver next submits. CPU work before
		// that (the client's UI, a scene swap) would be counted as this pass's, so submit now.
		glFlush();
		active = false;
	}

	/**
	 * Call once per frame after swap. Moves to the next slot, reading back its old results first.
	 */
	void endFrame()
	{
		if (!initialized)
		{
			return;
		}
		++frames;
		readFrame = -1;
		end();
		slot = (slot + 1) % SLOTS;
		int n = used[slot];
		if (n == 0)
		{
			return;
		}
		used[slot] = 0;
		// the last query of a frame finishes last; if it is not ready, drop the frame rather than wait
		if (glGetQueryObjecti(queries[slot][n - 1], GL_QUERY_RESULT_AVAILABLE) != GL_TRUE)
		{
			return;
		}
		java.util.Arrays.fill(frame, 0);
		for (int i = 0; i < n; ++i)
		{
			int q = queries[slot][i];
			if (glGetQueryObjecti(q, GL_QUERY_RESULT_AVAILABLE) != GL_TRUE)
			{
				return;
			}
			long nanos = glGetQueryObjecti64(q, GL_QUERY_RESULT);
			// a result no pass can take (a driver's bad query) counts nothing
			if (nanos > 0 && nanos < 1_000_000_000L)
			{
				frame[stageOf[slot][i]] += nanos / 1e6;
			}
		}
		double total = 0;
		long now = System.nanoTime();
		for (int s = 0; s < STAGES; ++s)
		{
			if (frame[s] > 0)
			{
				lastRan[s] = now;
			}
			total += frame[s];
			windowSum[s] += frame[s];
		}
		++windowFrames;
		if (now - windowStart >= CpuTimer.WINDOW_NANOS)
		{
			totalMs = 0;
			for (int s = 0; s < STAGES; ++s)
			{
				ms[s] = windowSum[s] / windowFrames;
				totalMs += ms[s];
				windowSum[s] = 0;
			}
			windowFrames = 0;
			windowStart = now;
		}
		// this slot was filled SLOTS - 1 frames ago
		readFrame = frames - (SLOTS - 1);
		readTotalMs = total;
	}

	/** Whether a pass ran in the last CpuTimer.RECENT_NANOS. */
	boolean ranRecently(int stage)
	{
		return lastRan[stage] != 0 && System.nanoTime() - lastRan[stage] < CpuTimer.RECENT_NANOS;
	}

	/** A pass's mean time per frame over the last whole window, 0 where it did not run. */
	double getMs(int stage) { return ms[stage]; }
	double getTotalMs() { return totalMs; }

	boolean isEnabled()
	{
		return initialized && enabled;
	}

	/** Frames ended so far: the number of the frame that just ended. */
	long getFrames()
	{
		return frames;
	}

	/** The number of the frame whose results the last endFrame read, 3 frames old; -1 if it read none. */
	long getReadFrame()
	{
		return readFrame;
	}

	/** A stage's time in the frame getReadFrame names, not averaged. */
	double getReadMs(int stage)
	{
		return frame[stage];
	}

	double getReadTotalMs()
	{
		return readTotalMs;
	}
}
