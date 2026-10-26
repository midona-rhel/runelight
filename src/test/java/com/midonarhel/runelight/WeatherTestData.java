package com.midonarhel.runelight;

import com.google.gson.Gson;
import com.midonarhel.runelight.config.WeatherMode;

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

	/** A frame of the weather a manual mode asks for, over a field of one cell, outdoors or not. */
	static void drive(Weather weather, WeatherMode mode, boolean outdoors)
	{
		load();
		WeatherField field = weather.getField();
		field.layout(1, 1, 0, 0, new long[]{Long.MIN_VALUE}, new String[]{null});
		WeatherType type = Weather.resolve(mode);
		field.update(b -> type, null, 0.6f, false, 12, 0);
		field.focus(WeatherField.CELL / 2f, WeatherField.CELL / 2f);
		weather.update(false, false, true, outdoors, 0, 0, 0, 0);
	}
}
