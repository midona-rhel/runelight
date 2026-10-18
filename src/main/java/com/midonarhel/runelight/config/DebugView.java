package com.midonarhel.runelight.config;

public enum DebugView
{
	FINAL("Final"),
	DEPTH("Depth"),
	NORMALS("Normals");

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
