package com.midonarhel.runelight;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One kind of weather: a row of weather_types.json. Everything that follows the weather (clouds, sky, light, fog,
 * rain, snow, wind, lightning, wet ground and snow cover) eases toward these values in Weather.java, so a change of
 * weather always blends. Fields left out of the JSON keep the defaults below, which are plain clear weather.
 *
 * The catalogue itself is static: loaded once at startup (WeatherData) and replaced as a whole on a hot reload.
 * Types are compared by identity within one catalogue and by {@link #id} across reloads.
 */
final class WeatherType
{
	// set by the catalogue
	transient String id = "CLEAR";
	transient int index;

	String name = "Clear";

	// share of the sky the clouds cover, 0 to 1
	float cover;
	// clouds.glsl: how wispy and high they look (1: thin high cirrus), and an offset on how dark (-1 bright white,
	// +1 storm black); both blend with the cover as usual
	float cloudWisp;
	float cloudDark;

	// rain and snow intensity at the default intensity setting; WeatherVariation still swells it. Both: sleet
	float rain, snow;
	// 0 steady to 1 in showers: the rain comes and goes over a minute or two with breaks of sun between
	float showers;
	// how much falling rain and snow pile up the clouds beyond their cover (dust and ash do not)
	float coverSwell = 1;

	// rain drops, as multiples of rain_vert.glsl's defaults: streak length, fall speed, width, opacity, and slant
	// (how far the wind carries them)
	float dropLength = 1, dropSpeed = 1, dropWidth = 1, dropAlpha = 1, slant = 1;
	// snow pass particles: flake size, fall speed, sway and wind drift (dust races sideways), and color (sRGB)
	float flakeSize = 1, flakeSpeed = 1, flakeSway = 1, flakeWind = 1;
	float[] flakeColor = {0.95f, 0.97f, 1.0f};
	// how white the overcast sky and light turn, 0 rain slate to 1 snow white; below 0 follows the rain/snow mix
	float skyWhite = -1;

	// mean wind 0 calm to 1 gale (above 1 stronger still), and how gusty, 0 steady to 1 squalls
	float wind = 0.25f;
	float gustiness = 0.35f;

	// multiplier on the volumetric fog density, and how much of an area's own fog (swamps, deserts) it lets add
	float fog = 0.15f;
	float areaFog = 0.4f;
	// multiplier on the area's distance fog and sky haze (environment.glsl)
	float distanceFog = 0.6f;
	// extra fog around dawn, as a share of fog: morning mist in calm weather. With FogClimate, how calm the weather
	// is for mist to form at all (relative to clear weather)
	float morningMist = 0.8f;
	// multiplier on the fog's color: warm dust, gray ash
	float[] fogTint = {1, 1, 1};

	// light (sky.glsl's weatherLight): sun strength multiplier (harsh sun above 1), haze 0..1 (milky sky, softer
	// light and shadows), warmth -1 cold blue to 1 warm amber, and sky saturation offset (deep blue above 0)
	float sun = 1;
	float haze;
	float warmth;
	float skySaturation;

	// 1 when lightning strikes (above 0.6 it does)
	float lightning;
	// wet ground and snow cover buildup rates (multipliers), and the snow cover frost reaches without snowfall
	float wetRate = 1, snowRate = 1;
	float frost;
	// degrees C this weather adds to the region's temperature (harsh sun warms, a blizzard chills), on top of the
	// cooling of whatever falls and the clouds damping the day's swing (Climate)
	float temperature;
	// weight of staying in this weather from one forecast slot to the next, against 1 for unrelated weather
	float persistence = 3;

	@Override
	public String toString()
	{
		return name;
	}

	/** Wet ground and snow cover: whether this weather piles up snow cover (rather than dust or ash). */
	boolean whitens()
	{
		return snow > 0 && snowRate > 0;
	}

	/** Whether rain or snow falls, which the temperature decides between (not dust or ash). */
	boolean precipitates()
	{
		return rain > 0 || whitens();
	}

	/** A copy of every field, for a variant with its rain and snow redistributed (Climate). */
	WeatherType copy()
	{
		WeatherType c = new WeatherType();
		try
		{
			for (java.lang.reflect.Field f : WeatherType.class.getDeclaredFields())
			{
				if (!java.lang.reflect.Modifier.isStatic(f.getModifiers()))
				{
					f.setAccessible(true);
					f.set(c, f.get(this));
				}
			}
		}
		catch (IllegalAccessException ex)
		{
			throw new IllegalStateException(ex);
		}
		return c;
	}

	/** How white the overcast is for this weather with these amounts falling. */
	float whiteness(float rainAmount, float snowAmount, float previous)
	{
		if (skyWhite >= 0)
		{
			return skyWhite;
		}
		float total = rainAmount + snowAmount;
		return total > 0 ? snowAmount / total : previous;
	}

	// ---------------------------------------------------------------------------------------------------------
	// The catalogue

	private static final WeatherType BUILTIN_CLEAR = new WeatherType();
	private static volatile Catalogue catalogue = new Catalogue(Collections.singletonMap("CLEAR", BUILTIN_CLEAR));

	private static final class Catalogue
	{
		final WeatherType[] types;
		final Map<String, WeatherType> byId;
		final WeatherType clear;

		Catalogue(Map<String, WeatherType> rows)
		{
			byId = new LinkedHashMap<>();
			int i = 0;
			for (Map.Entry<String, WeatherType> e : rows.entrySet())
			{
				WeatherType t = e.getValue();
				t.id = e.getKey();
				t.index = i++;
				if (t.name == null)
				{
					t.name = t.id;
				}
				byId.put(t.id, t);
			}
			types = byId.values().toArray(new WeatherType[0]);
			WeatherType c = byId.get("CLEAR");
			clear = c != null ? c : types.length > 0 ? types[0] : BUILTIN_CLEAR;
		}
	}

	private static int version;

	/** Replace the catalogue: ids are the map's keys, in its order. Empty keeps the old one. */
	static synchronized void install(Map<String, WeatherType> rows)
	{
		if (rows == null || rows.isEmpty())
		{
			return;
		}
		catalogue = new Catalogue(rows);
		version++;
	}

	/** Changes each time the catalogue is replaced. */
	static int version()
	{
		return version;
	}

	static WeatherType[] all()
	{
		return catalogue.types;
	}

	static List<WeatherType> list()
	{
		return java.util.Arrays.asList(catalogue.types);
	}

	static int count()
	{
		return catalogue.types.length;
	}

	/** The type with this id, or null. */
	static WeatherType get(String id)
	{
		return id != null ? catalogue.byId.get(id) : null;
	}

	/** Clear weather: what Off holds and what anything unknown falls back to. */
	static WeatherType clear()
	{
		return catalogue.clear;
	}
}
