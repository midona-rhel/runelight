package com.midonarhel.lumina.dev;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Developer: the frame's GPU time by section, for the bench (harness GpuCostProbe). Each {@link #section} ends the
 * section before it and starts its own GL_TIME_ELAPSED query, so the sections are consecutive and cover the frame
 * from the first to {@link #end}. Off (one static read per call) unless the bench sets {@link #on}; GL_TIME_ELAPSED
 * queries don't nest, so Composite.passTimers stays null while it is on.
 */
public final class GpuSections
{
	public static boolean on;
	// each section begun on an idle GPU (glFinish at each boundary): the passes don't overlap, so each one's time is its
	// own (a tiler runs passes side by side and splits a query's time oddly otherwise)
	public static boolean serial;

	private static final int MAX = 48;
	private static int[] queries;
	private static final String[] names = new String[MAX];
	private static int count;
	private static boolean open;

	private GpuSections()
	{
	}

	/** Forgets the queries (of a context that is gone: the bench makes one per renderer). */
	public static void reset()
	{
		queries = null;
		count = 0;
		open = false;
	}

	/** Ends the open section, if any, and starts the one named. */
	public static void section(String name)
	{
		if (!on)
		{
			return;
		}
		if (queries == null)
		{
			queries = new int[MAX];
			glGenQueries(queries);
		}
		if (open)
		{
			glEndQuery(GL_TIME_ELAPSED);
			open = false;
		}
		if (serial)
		{
			glFinish();
		}
		if (count == MAX)
		{
			return;
		}
		names[count] = name;
		glBeginQuery(GL_TIME_ELAPSED, queries[count++]);
		open = true;
	}

	/** Ends the open section. */
	public static void end()
	{
		if (on && open)
		{
			glEndQuery(GL_TIME_ELAPSED);
			open = false;
		}
	}

	/** The frame's sections (waits for the GPU), in order: their names into names, their ms into ms; the count. Resets. */
	public static int read(String[] namesOut, double[] msOut)
	{
		end();
		int n = count;
		for (int i = 0; i < n; ++i)
		{
			namesOut[i] = names[i];
			msOut[i] = glGetQueryObjecti64(queries[i], GL_QUERY_RESULT) / 1e6;
		}
		count = 0;
		return n;
	}
}
