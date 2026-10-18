package com.midonarhel.runelight.config;

public enum Quality
{
	LOW("Low"),
	MEDIUM("Medium"),
	HIGH("High");

	private final String name;

	Quality(String name)
	{
		this.name = name;
	}

	/** One of three values by quality, low to high. */
	public int pick(int low, int medium, int high)
	{
		return this == LOW ? low : this == MEDIUM ? medium : high;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
