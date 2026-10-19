package com.midonarhel.runelight.config;

/**
 * What the water shows instead of itself, for debugging (water_frag.glsl's waterDebug).
 */
public enum WaterDebug
{
	OFF("Off"),
	MAP("Types and flow"),
	WAVES("Waves");

	private final String name;

	WaterDebug(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
