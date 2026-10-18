package com.midonarhel.runelight.config;

public enum WeatherMode
{
	AUTOMATIC("Automatic"),
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
