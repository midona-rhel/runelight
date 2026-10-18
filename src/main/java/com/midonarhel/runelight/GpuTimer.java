package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Non-blocking GPU timestamps. Each frame gets a slot of timestamp queries; a slot is only read back
 * when it comes around again several frames later, and only if the driver says the result is ready,
 * so measuring never stalls the pipeline.
 */
class GpuTimer
{
	static final int FRAME_START = 0;
	static final int SCENE_END = 1;
	static final int POST_END = 2;
	static final int FRAME_END = 3;
	private static final int MARKS = 4;
	private static final int SLOTS = 4;

	private final int[][] queries = new int[SLOTS][MARKS];
	private final boolean[][] issued = new boolean[SLOTS][MARKS];
	private int slot;
	private boolean initialized;

	// exponential moving averages, milliseconds
	private double sceneMs;
	private double postMs;
	private double presentMs;

	void init()
	{
		for (int s = 0; s < SLOTS; ++s)
		{
			glGenQueries(queries[s]);
		}
		slot = 0;
		initialized = true;
	}

	void free()
	{
		if (!initialized)
		{
			return;
		}
		for (int s = 0; s < SLOTS; ++s)
		{
			glDeleteQueries(queries[s]);
			java.util.Arrays.fill(issued[s], false);
		}
		initialized = false;
	}

	void mark(int which)
	{
		if (!initialized)
		{
			return;
		}
		glQueryCounter(queries[slot][which], GL_TIMESTAMP);
		issued[slot][which] = true;
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
		slot = (slot + 1) % SLOTS;
		boolean[] is = issued[slot];
		int[] q = queries[slot];
		if (is[FRAME_START] && is[SCENE_END] && is[POST_END] && is[FRAME_END]
			&& glGetQueryObjecti(q[FRAME_END], GL_QUERY_RESULT_AVAILABLE) == GL_TRUE)
		{
			long t0 = glGetQueryObjecti64(q[FRAME_START], GL_QUERY_RESULT);
			long t1 = glGetQueryObjecti64(q[SCENE_END], GL_QUERY_RESULT);
			long t2 = glGetQueryObjecti64(q[POST_END], GL_QUERY_RESULT);
			long t3 = glGetQueryObjecti64(q[FRAME_END], GL_QUERY_RESULT);
			sceneMs = smooth(sceneMs, (t1 - t0) / 1e6);
			postMs = smooth(postMs, (t2 - t1) / 1e6);
			presentMs = smooth(presentMs, (t3 - t2) / 1e6);
		}
		java.util.Arrays.fill(is, false);
	}

	private static double smooth(double avg, double v)
	{
		if (v < 0 || v > 1000)
		{
			return avg; // wrapped or bogus timestamp
		}
		return avg == 0 ? v : avg * 0.9 + v * 0.1;
	}

	double getSceneMs()
	{
		return sceneMs;
	}

	double getPostMs()
	{
		return postMs;
	}

	double getPresentMs()
	{
		return presentMs;
	}
}
