package com.midonarhel.lumina.frame;

import java.util.Arrays;

/**
 * CPU time of the frame's steps, on the client thread. A step may be begun several times a frame; its times add
 * up. Steps are not nested.
 */
public class CpuTimer
{
	// the client's scene traversal, from our pre to our post scene callback, including our draw callbacks
	public static final int CLIENT_SCENE = 0;
	// from the end of the scene to our draw: the client drawing its interface and RuneLite's overlays
	public static final int CLIENT_UI = 1;
	// our draw up to the swap: UI upload, blit, UI draw
	public static final int DRAW = 2;
	// waiting in swapBuffers: large when the GPU or vsync is the limit
	public static final int SWAP = 3;
	// from our swap to the next scene: the client's game loop, other plugins
	public static final int OUTSIDE = 4;
	public static final int STAGES = 5;

	public static final String[] NAMES = {"client_scene", "client_ui", "draw", "swap", "outside"};

	private final long[] start = new long[STAGES];
	private final double[] frame = new double[STAGES];
	private final double[] last = new double[STAGES];

	/** Start a step; does nothing if it is already running. */
	void begin(int stage)
	{
		if (start[stage] == 0)
		{
			start[stage] = System.nanoTime();
		}
	}

	void end(int stage)
	{
		if (start[stage] != 0)
		{
			frame[stage] += (System.nanoTime() - start[stage]) / 1e6;
			start[stage] = 0;
		}
	}

	/** Close the frame: its times become {@link #getLastMs}. */
	void endFrame()
	{
		// a step left running carries no time into the next frame
		Arrays.fill(start, 0);
		System.arraycopy(frame, 0, last, 0, STAGES);
		Arrays.fill(frame, 0);
	}

	/** A step's time in the frame that ended last, milliseconds. */
	public double getLastMs(int stage)
	{
		return last[stage];
	}
}
