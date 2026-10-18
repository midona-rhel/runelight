package com.midonarhel.runelight.config;

/**
 * Automatic follows the forecast; Off keeps it clear; the rest hold one weather (for testing). The names of the
 * held weathers are the ids in weather_types.json; one missing there falls back to clear weather.
 */
public enum WeatherMode
{
	AUTOMATIC("Automatic"),
	OFF("Off"),
	CLEAR("Clear"),
	SUNNY("Sunny"),
	HARSH_SUN("Harsh sun"),
	HAZY("Hazy"),
	HIGH_CLOUDS("High thin clouds"),
	PARTLY_CLOUDY("Partly cloudy"),
	OVERCAST("Overcast"),
	DRIZZLE("Drizzle"),
	RAIN("Rain"),
	SPRING_SHOWERS("Spring showers"),
	SUMMER_RAIN("Summer rain"),
	AUTUMN_RAIN("Autumn rain"),
	THUNDERSTORM("Thunderstorm"),
	MONSOON("Tropical downpour"),
	MIST("Mist"),
	FOG("Fog"),
	FREEZING_FOG("Freezing fog"),
	SLEET("Sleet"),
	LIGHT_SNOW("Light snow"),
	SNOW("Snowfall"),
	HEAVY_SNOW("Heavy snow"),
	BLIZZARD("Blizzard"),
	SANDSTORM("Sandstorm"),
	ASH_FALL("Ash fall"),
	GALE("Gale");

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
