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
	static final int GI = 10;
	static final int WATER = 11;
	// parts of the shadow map: copying the cached static map in, the moving models
	static final int SHADOW_COPY = 12;
	static final int SHADOW_MODELS = 13;
	// uploading the skylight's traced grid
	static final int SKY_GRID = 14;
	static final int FILTER = 15;
	static final int UPSCALE = 16;
	static final int AMBIENT = 17;
	static final int SUN = 18;
	static final int POINT_LIGHTS = 19;
	static final int SURFACES = 20;
	static final int CLOUDS = 21;
	static final int STAGES = 22;

	static final String[] NAMES = {
		"scene", "MSAA resolve", "shadow map", "weather occlusion", "AO + skylight/GI", "sky cache", "fog", "sky/fog + final blend",
		"weather", "UI", "GI probes", "water", "shadow copy", "shadow models", "sky grid upload", "AO/fog filtering", "upscale", "ambient + sky/GI", "sun + cloud shadows", "point lights + blocking", "materials + water blend", "clouds"
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
	// Last valid execution of each stage, milliseconds; cached jobs retain their result.
	private final double[] ms = new double[STAGES];
	private double totalMs;
	// frames ended so far, and the frame whose times the last endFrame read (-1: none); for the hitch monitor
	private long frames;
	private long readFrame = -1;
	private double readTotalMs;
	private long lastReadNanos;
	private final long[] stageReadNanos = new long[STAGES];
	private final boolean[] present = new boolean[STAGES];

	void init()
	{
		for (int s = 0; s < SLOTS; ++s)
		{
			glGenQueries(queries[s]);
			used[s] = 0;
		}
		slot = 0;
		active = false;
		java.util.Arrays.fill(ms, Double.NaN);
		totalMs = Double.NaN;
		lastReadNanos = 0;
		java.util.Arrays.fill(stageReadNanos, 0);
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
		java.util.Arrays.fill(present, false);
		for (int i = 0; i < n; ++i)
		{
			int q = queries[slot][i];
			if (glGetQueryObjecti(q, GL_QUERY_RESULT_AVAILABLE) != GL_TRUE)
			{
				return;
			}
			long nanos = glGetQueryObjecti64(q, GL_QUERY_RESULT);
			// Invalid driver results must not leave plausible-looking stale averages.
			int stage = stageOf[slot][i];
			present[stage] = true;
			frame[stage] += nanos < 0 || nanos > 1_000_000_000L ? Double.NaN : nanos / 1e6;
		}
		double total = 0;
		long now = System.nanoTime();
		for (int s = 0; s < STAGES; ++s)
		{
			// Keep the last real execution for cached jobs and invalid driver samples.
			if (present[s] && Double.isFinite(frame[s]) && frame[s] > 0)
			{
				ms[s] = frame[s];
				stageReadNanos[s] = now;
			}
			total += frame[s];
		}
		// A bad query must not freeze the displayed total at an unrelated startup frame.
		// Show this frame's valid subtotal; the overlay explicitly reports missing queries.
		totalMs = 0;
		for (double value : frame) if (Double.isFinite(value)) totalMs += value;
		lastReadNanos = now;
		// this slot was filled SLOTS - 1 frames ago
		readFrame = frames - (SLOTS - 1);
		readTotalMs = total;
	}

	double getMs(int stage) { return ms[stage]; }
	double getTotalMs() { return totalMs; }
	double ageSeconds(int stage)
	{
		long time = stage < 0 ? lastReadNanos : stageReadNanos[stage];
		return time == 0 ? Double.POSITIVE_INFINITY : (System.nanoTime() - time) / 1e9;
	}

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
