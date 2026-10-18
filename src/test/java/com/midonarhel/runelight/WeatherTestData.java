package com.midonarhel.runelight;

import com.google.gson.Gson;

/** Loads the weather catalogue and climate from the resources once, for the tests. */
final class WeatherTestData
{
	private static boolean loaded;

	private WeatherTestData()
	{
	}

	static synchronized void load()
	{
		if (!loaded)
		{
			WeatherData.load(new Gson());
			loaded = true;
		}
	}

	/** The type with this id; fails the test when the catalogue lacks it. */
	static WeatherType type(String id)
	{
		load();
		WeatherType t = WeatherType.get(id);
		if (t == null)
		{
			throw new AssertionError("no weather type " + id);
		}
		return t;
	}
}
