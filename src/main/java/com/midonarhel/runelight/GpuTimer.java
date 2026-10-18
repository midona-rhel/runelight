package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Non-blocking GPU timing per frame stage. Each frame gets a slot of GL_TIME_ELAPSED queries, one per stage;
 * a slot is only read back when it comes around again several frames later, and only if the driver says the
 * result is ready, so measuring never stalls the pipeline.
 *
 * Uses elapsed-time queries rather than timestamps: Apple's OpenGL driver returns 0 for GL_TIMESTAMP.
 * Stages are sequential, never nested, as elapsed queries of one kind cannot overlap.
 */
class GpuTimer
{
	static final int SCENE = 0;
	static final int SHADOW = 1;
	static final int POST = 2;
	static final int PRESENT = 3;
	private static final int STAGES = 4;
	private static final int SLOTS = 4;

	private final int[][] queries = new int[SLOTS][STAGES];
	private final boolean[][] ended = new boolean[SLOTS][STAGES];
	private int slot;
	private int active = -1;
	private boolean initialized;

	// exponential moving averages, milliseconds
	private final double[] ms = new double[STAGES];

	void init()
	{
		for (int s = 0; s < SLOTS; ++s)
		{
			glGenQueries(queries[s]);
		}
		slot = 0;
		active = -1;
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
			java.util.Arrays.fill(ended[s], false);
		}
		initialized = false;
	}

	/**
	 * Start timing a stage, ending the previous one if it is still running.
	 */
	void begin(int stage)
	{
		if (!initialized)
		{
			return;
		}
		end();
		glBeginQuery(GL_TIME_ELAPSED, queries[slot][stage]);
		active = stage;
	}

	void end()
	{
		if (active < 0)
		{
			return;
		}
		glEndQuery(GL_TIME_ELAPSED);
		ended[slot][active] = true;
		active = -1;
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
		for (int stage = 0; stage < STAGES; ++stage)
		{
			if (!ended[slot][stage])
			{
				continue;
			}
			int q = queries[slot][stage];
			if (glGetQueryObjecti(q, GL_QUERY_RESULT_AVAILABLE) == GL_TRUE)
			{
				ms[stage] = smooth(ms[stage], glGetQueryObjecti64(q, GL_QUERY_RESULT) / 1e6);
			}
			ended[slot][stage] = false;
		}
	}

	private static double smooth(double avg, double v)
	{
		if (v < 0 || v > 1000)
		{
			return avg;
		}
		return avg == 0 ? v : avg * 0.9 + v * 0.1;
	}

	double getSceneMs()
	{
		return ms[SCENE];
	}

	double getShadowMs()
	{
		return ms[SHADOW];
	}

	double getPostMs()
	{
		return ms[POST];
	}

	double getPresentMs()
	{
		return ms[PRESENT];
	}
}
