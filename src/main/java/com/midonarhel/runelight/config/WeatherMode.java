package com.midonarhel.runelight.config;

public enum WeatherMode
{
	OFF("Off"),
	RAIN("Rain"),
	SNOW("Snow");

	private final String name;

	WeatherMode(String name)
	{
		this.name = name;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
