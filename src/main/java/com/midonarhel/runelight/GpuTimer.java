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
	// parts of the shadow map: copying the cached static map in, the moving models, the sky occlusion map
	static final int SHADOW_COPY = 12;
	static final int SHADOW_MODELS = 13;
	static final int SKY_OCCLUSION = 14;
	static final int STAGES = 15;

	static final String[] NAMES = {
		"scene", "MSAA resolve", "shadow map", "weather occlusion", "AO", "sky cache", "fog", "composite",
		"weather", "UI", "GI probes", "water", "shadow copy", "shadow models", "sky occlusion"
	};

	private static final int SLOTS = 4;
	// queries per frame; more begins than this in one frame are not timed
	private static final int ENTRIES = 24;

	private final int[][] queries = new int[SLOTS][ENTRIES];
	private final int[][] stageOf = new int[SLOTS][ENTRIES];
	private final int[] used = new int[SLOTS];
	private int slot;
	private boolean active;
	private boolean initialized;
	// off unless the timings are shown: the queries and their readback cost the CPU a little every frame
	private boolean enabled = true;

	private final double[] frame = new double[STAGES];
	// exponential moving averages, milliseconds
	private final double[] ms = new double[STAGES];
	private double totalMs;

	void init()
	{
		for (int s = 0; s < SLOTS; ++s)
		{
			glGenQueries(queries[s]);
			used[s] = 0;
		}
		slot = 0;
		active = false;
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
			frame[stageOf[slot][i]] += glGetQueryObjecti64(q, GL_QUERY_RESULT) / 1e6;
		}
		double total = 0;
		for (int s = 0; s < STAGES; ++s)
		{
			// passes that did not run this frame decay toward 0, so turning something off shows
			ms[s] = smooth(ms[s], frame[s]);
			total += frame[s];
		}
		totalMs = smooth(totalMs, total);
	}

	private static double smooth(double avg, double v)
	{
		if (v < 0 || v > 1000)
		{
			return avg;
		}
		return avg == 0 ? v : avg * 0.95 + v * 0.05;
	}

	double getMs(int stage)
	{
		return ms[stage];
	}

	double getTotalMs()
	{
		return totalMs;
	}
}
