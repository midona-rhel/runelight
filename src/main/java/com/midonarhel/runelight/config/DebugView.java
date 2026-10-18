package com.midonarhel.runelight.config;

public enum DebugView
{
	FINAL("Final"),
	DEPTH("Depth"),
	NORMALS("Normals"),
	AMBIENT_OCCLUSION("Ambient occlusion"),
	SHADOWS("Shadows"),
	LIGHTING("Lighting"),
	LIGHT_CLUSTERS("Light clusters"),
	MATERIALS("Materials"),
	GLOBAL_ILLUMINATION("Global illumination");

	private final String name;

	DebugView(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
