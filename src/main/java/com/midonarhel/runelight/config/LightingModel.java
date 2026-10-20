package com.midonarhel.runelight.config;

public enum LightingModel
{
	SKYLIGHT("Skylight"),
	SKYLIGHT_GI("Skylight + GI bounce");

	private final String name;

	LightingModel(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
