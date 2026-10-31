package com.midonarhel.lumina.dev;

import com.midonarhel.lumina.*;

/**
 * CPU time of the plugin's own per-frame steps and of the parts of the frame it doesn't control, averaged.
 * Steps may be begun several times a frame (only on the client thread); their times add up. Not nested: begin
 * and end the same step, never two at once unless they are different steps.
 */
public class CpuTimer
{
	// the client's scene traversal between our pre and post scene callbacks, including our draw callbacks
	public static final int CLIENT_SCENE = 0;
	public static final int ENVIRONMENT = 1;
	public static final int WEATHER = 2;
	public static final int LIGHTS = 3;
	public static final int CLUSTERS = 4;
	public static final int SHADOW_SUBMIT = 5;
	public static final int UI_UPLOAD = 6;
	// waiting in swapBuffers: large when the GPU (or vsync) is the limit
	public static final int SWAP = 7;
	// from our swap to the next frame's scene: the client's game loop, other plugins and overlays
	public static final int OUTSIDE = 8;
	// between our scene and our draw: the client drawing its interface and RuneLite's overlays into the canvas
	public static final int CLIENT_UI = 9;
	// mapping the dynamic model buffers at the start of the scene
	public static final int BUFFER_MAP = 10;
	// sending the light and cluster tables to the GPU (part of light clusters before)
	public static final int LIGHT_UPLOAD = 11;
	// the voxel build's submit; the sky capture; the occlusion map redraws
	public static final int VOXELS = 12;
	public static final int SKY_CACHE = 13;
	public static final int OCCLUSION = 14;
	// handing the skylight's grid its next rays and uploading what it traced (the tracing is on its worker)
	public static final int SKY_GRID = 15;
	static final int STAGES = 16;

	static final String[] NAMES = {
		"client scene", "environment", "weather", "light collection", "light clusters", "shadow submit",
		"UI upload", "swap wait", "outside frame", "client UI + overlays",
		"buffer map", "light upload", "scene voxels", "sky capture", "occlusion maps", "sky grid"
	};

	private final long[] start = new long[STAGES];
	private final double[] frame = new double[STAGES];
	private final double[] ms = new double[STAGES];
	// the frame that just ended, for the hitch monitor
	private final double[] last = new double[STAGES];

	public void begin(int stage)
	{
		start[stage] = System.nanoTime();
	}

	public void end(int stage)
	{
		if (start[stage] != 0)
		{
			frame[stage] += (System.nanoTime() - start[stage]) / 1e6;
			start[stage] = 0;
		}
	}

	/** Fold this frame's times into the averages. */
	public void endFrame()
	{
		for (int s = 0; s < STAGES; ++s)
		{
			ms[s] = ms[s] == 0 ? frame[s] : ms[s] * 0.95 + frame[s] * 0.05;
			last[s] = frame[s];
			frame[s] = 0;
		}
	}

	double getMs(int stage)
	{
		return ms[stage];
	}

	/** A step's time in the frame that ended last, not averaged. */
	public double getLastMs(int stage)
	{
		return last[stage];
	}

	/** The frame's time not in any step but the one outside it: the plugin's own untimed work. */
	public double untrackedMs(double cpuFrameMs)
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
