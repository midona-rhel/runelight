package com.midonarhel.lumina.frame;

import java.util.Arrays;
import static org.lwjgl.opengl.GL33C.*;

/**
 * GPU time of the frame's passes, read back a few frames later so measuring never waits for the GPU.
 *
 * Uses elapsed-time queries, not timestamps: Apple's OpenGL driver returns 0 for GL_TIMESTAMP. Elapsed queries of
 * one kind cannot overlap, so passes are timed one after another: {@link #begin} ends the pass that was running.
 * A pass may be begun several times a frame; its pieces add up.
 *
 * A query counts from when the GPU reaches its begin to when it reaches its end, including any time the GPU waits
 * for the CPU in between. A pass whose commands are issued while the client is still traversing the scene (the
 * scene pass here) therefore reads as the traversal's wall time unless the GPU is the bottleneck.
 *
 * Off unless enabled: on Apple's driver ending a query can make the CPU wait.
 */
public class GpuTimer
{
	public static final int SCENE = 0;
	public static final int UI_UPLOAD = 1;
	public static final int BLIT = 2;
	public static final int UI = 3;
	/** The static shadow map's build share of the frame (only on frames that build or redraw part of it). */
	public static final int SHADOW_STATIC = 4;
	/** The dynamic shadow map (only on frames with dynamic casters). */
	public static final int SHADOW_DYNAMIC = 5;
	public static final int STAGES = 6;

	public static final String[] NAMES = {"scene", "ui_upload", "blit", "ui", "shadow_static", "shadow_dynamic"};

	// frames in flight before a slot is read back; drivers may queue 3+ frames ahead, so leave room or most frames
	// would be dropped as not ready
	private static final int SLOTS = 8;
	// queries per frame; begins beyond this in one frame are not timed
	private static final int ENTRIES = 32;

	private final int[][] queries = new int[SLOTS][ENTRIES];
	private final int[][] stageOf = new int[SLOTS][ENTRIES];
	private final int[] used = new int[SLOTS];
	private final long[] frameOf = new long[SLOTS];
	private int slot;
	private boolean active;
	private boolean initialized;
	private boolean enabled;
	private long frames;

	private final double[] read = new double[STAGES];
	private long readFrame = -1;

	void init()
	{
		for (int s = 0; s < SLOTS; ++s)
		{
			glGenQueries(queries[s]);
		}
		Arrays.fill(used, 0);
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
		}
		initialized = false;
		enabled = false;
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
		Arrays.fill(used, 0);
		readFrame = -1;
		enabled = on;
	}

	/**
	 * Start timing a pass, ending the previous one. Call right before the pass's GL commands, after the CPU work
	 * that prepares them.
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
		// The GPU reaches the end when the driver next submits; submit now so CPU work after the pass isn't counted
		glFlush();
		active = false;
	}

	/**
	 * Call once per frame after the swap: moves to the next slot and reads its results from SLOTS - 1 frames ago,
	 * if they are ready. A frame whose results aren't ready is dropped, never waited for.
	 */
	void endFrame()
	{
		if (!initialized)
		{
			return;
		}
		end();
		frameOf[slot] = frames++;
		slot = (slot + 1) % SLOTS;
		readFrame = -1;
		int n = used[slot];
		used[slot] = 0;
		if (n == 0 || glGetQueryObjecti(queries[slot][n - 1], GL_QUERY_RESULT_AVAILABLE) != GL_TRUE)
		{
			return;
		}
		Arrays.fill(read, 0);
		for (int i = 0; i < n; ++i)
		{
			int q = queries[slot][i];
			if (glGetQueryObjecti(q, GL_QUERY_RESULT_AVAILABLE) != GL_TRUE)
			{
				return;
			}
			long nanos = glGetQueryObjecti64(q, GL_QUERY_RESULT);
			read[stageOf[slot][i]] += nanos < 0 || nanos > 1_000_000_000L ? Double.NaN : nanos / 1e6;
		}
		readFrame = frameOf[slot];
	}

	/** The number of the frame about to be timed. */
	long getFrame()
	{
		return frames;
	}

	/** The frame the last endFrame read results for, or -1 if it read none. */
	long getReadFrame()
	{
		return readFrame;
	}

	/** A pass's time in the frame {@link #getReadFrame} names, milliseconds. */
	public double getReadMs(int stage)
	{
		return read[stage];
	}
}
