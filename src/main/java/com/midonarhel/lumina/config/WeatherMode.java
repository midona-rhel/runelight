package com.midonarhel.lumina.config;

/**
 * Automatic follows the forecast; Off keeps it clear; the rest hold one weather (for testing). The names of the
 * held weathers are the ids in weather_types.json; one missing there falls back to clear weather.
 */
public enum WeatherMode
{
	AUTOMATIC("Automatic"),
	OFF("Off"),
	CLEAR("Clear"),
	OVERCAST("Overcast"),
	RAIN("Rain"),
	STORM("Storm"),
	SNOW("Snow"),
	FOG("Fog"),
	SANDSTORM("Sandstorm"),
	ASH_FALL("Ash fall"),
	HAZY("Haze");

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
