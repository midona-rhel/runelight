package com.midonarhel.runelight.config;

public enum SwayFps
{
	SMOOTH("Every frame", 0),
	FPS_30("30 fps", 30),
	FPS_15("15 fps", 15);

	private final String name;
	private final int fps;

	SwayFps(String name, int fps)
	{
		this.name = name;
		this.fps = fps;
	}

	public int getFps()
	{
		return fps;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
