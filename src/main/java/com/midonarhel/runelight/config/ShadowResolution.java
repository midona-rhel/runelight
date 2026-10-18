package com.midonarhel.runelight.config;

public enum ShadowResolution
{
	RES_1024("1024", 1024),
	RES_2048("2048", 2048),
	RES_4096("4096", 4096);

	private final String name;
	private final int size;

	ShadowResolution(String name, int size)
	{
		this.name = name;
		this.size = size;
	}

	public int getSize()
	{
		return size;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
