package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.*;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One kind of weather: a row of weather_types.json. Everything that follows the weather (clouds, sky, light, fog,
 * rain, snow, wind, lightning, wet ground and snow cover) eases toward these values in Weather.java, so a change of
 * weather always blends. Fields left out of the JSON keep the defaults below, which are plain clear weather.
 *
 * A row holds the few things a weather decides (cover, wisp, darkness, what falls and how much, fog, haze, a nudge
 * on the region's wind, lightning, persistence); the rest of what the renderer reads follows from them in
 * {@link #derive}, so there is no knob per weather for a drop's shape, the overcast's white or the like. What falls
 * has one look per kind: rain streaks, snowflakes, fast tan grit, slow grey ash.
 *
 * The catalogue itself is static: loaded once at startup (WeatherData) and replaced as a whole on a hot reload.
 * Types are compared by identity within one catalogue and by {@link #id} across reloads.
 */
public final class WeatherType
{
	// set by the catalogue
	transient String id = "CLEAR";
	transient int index;

	String name = "Clear";

	// share of the sky the clouds cover, 0 to 1
	float cover;
	// how wispy and high the clouds are, 0 heaped to 1 thin high cirrus
	float wisp;
	// how dark the clouds are, -1 bright white to 1 storm black (with the cover as usual)
	float darkness;
	// what falls: none, rain, snow, sand or ash; and how much, 0 to 1, which is just how many particles. Rain turns to
	// snow and back by the temperature (Climate)
	String fall = "none";
	float amount;
	// how much fog the weather adds, as a multiple of the region's (its humidity's, FogClimate)
	float fog = 0.15f;
	// haze, 0 to 1: a milky sky, softer light and shadows, warm in heat, tan in sand, grey in ash
	float haze;
	// what the weather adds to the region's wind (WeatherClimate.wind), about -0.3 to 0.4
	float wind;
	// 1 when lightning strikes (above 0.6 it does)
	float lightning;
	// weight of staying in this weather from one forecast slot to the next, against 1 for unrelated weather
	float persistence = 3;

	// ---------------------------------------------------------------------------------------------------------
	// Derived (derive): what the renderer and the climate read

	// clouds.glsl's look: how wispy and high, and an offset on how dark
	transient float cloudWisp, cloudDark;
	// rain and snow intensity at the default intensity setting; WeatherVariation still swells it
	transient float rain, snow;
	// 0 steady to 1 in showers: the rain comes and goes over a minute or two with breaks of sun between
	transient float showers;
	// how much falling rain and snow pile up the clouds beyond their cover (dust and ash do not)
	transient float coverSwell = 1;
	// rain drops, as multiples of rain_vert.glsl's defaults: streak length, fall speed, width, opacity, and slant
	transient float dropLength = 1, dropSpeed = 1, dropWidth = 1, dropAlpha = 1, slant = 1;
	// snow pass particles: flake size, fall speed, sway and wind drift (dust races sideways), and color (sRGB)
	transient float flakeSize = 1, flakeSpeed = 1, flakeSway = 1, flakeWind = 1;
	transient float[] flakeColor = {0.95f, 0.97f, 1.0f};
	// how white the overcast sky and light turn, 0 rain slate to 1 snow white; below 0 follows the rain/snow mix
	transient float skyWhite = -1;
	// how much of an area's own fog (swamps, deserts) the fog lets add, and the area's distance fog and sky haze
	transient float areaFog = 0.4f, distanceFog = 0.6f;
	// how calm the weather is for mist to form (FogClimate.calm), as a share of fog
	transient float morningMist = 0.8f;
	// multiplier on the fog's color: warm dust, gray ash
	transient float[] fogTint = {1, 1, 1};
	// light (sky.glsl's weatherLight): sun strength multiplier, haze 0..1, warmth -1 cold blue to 1 warm amber, and
	// sky saturation offset
	transient float sun = 1;
	transient float warmth;
	transient float skySaturation;
	// wet ground and snow cover buildup rates (multipliers), and the snow cover frost reaches without snowfall
	transient float wetRate = 1, snowRate = 1;
	transient float frost;
	// degrees C this weather adds to the region's temperature: none, the regions' own temperatures decide (Climate)
	transient float temperature;

	// the looks of what falls that isn't rain or snow: flake size, fall speed, sway, wind drift, colour (sRGB), and the
	// fog's tint and the light's warmth they bring
	private static final float[] SAND_FLAKES = {0.45f, 0.5f, 0.3f, 4.0f}, SAND_COLOR = {0.80f, 0.66f, 0.45f},
		SAND_FOG = {1.3f, 1.0f, 0.65f};
	private static final float[] ASH_FLAKES = {0.8f, 0.55f, 1, 1}, ASH_COLOR = {0.33f, 0.31f, 0.30f},
		ASH_FOG = {0.9f, 0.82f, 0.75f};
	private static final float SAND_WARMTH = 0.8f, ASH_WARMTH = 0.45f, SNOW_WARMTH = -0.2f;
	// haze dims the sun, warms the light where it is heat haze (not in fog) and pales the sky
	private static final float HAZE_SUN = 0.45f, HAZE_WARMTH = 0.45f, HAZE_SATURATION = 0.5f, COVER_SATURATION = 0.1f;

	/**
	 * Work out the derived fields from the row. Fog's share of the area's fog, distance fog and the calm for mist
	 * follow the fog, the haze and how much falls; what falls decides the particles, the fog's tint, whether it wets
	 * the ground or piles up as snow, and whether it swells the clouds.
	 */
	void derive()
	{
		cloudWisp = wisp;
		cloudDark = darkness;
		String kind = fall == null ? "none" : fall.toLowerCase();
		rain = kind.equals("rain") ? amount : 0;
		snow = kind.equals("snow") || kind.equals("sand") || kind.equals("ash") ? amount : 0;
		boolean dust = kind.equals("sand") || kind.equals("ash");
		coverSwell = dust ? 0 : 1;
		skyWhite = dust ? 0 : -1;
		snowRate = dust ? 0 : 1;
		wetRate = dust ? 0 : 1;
		float[] flakes = kind.equals("sand") ? SAND_FLAKES : kind.equals("ash") ? ASH_FLAKES : new float[]{1, 1, 1, 1};
		flakeSize = flakes[0];
		flakeSpeed = flakes[1];
		flakeSway = flakes[2];
		flakeWind = flakes[3];
		flakeColor = kind.equals("sand") ? SAND_COLOR.clone() : kind.equals("ash") ? ASH_COLOR.clone()
			: new float[]{0.95f, 0.97f, 1.0f};
		fogTint = kind.equals("sand") ? SAND_FOG.clone() : kind.equals("ash") ? ASH_FOG.clone() : new float[]{1, 1, 1};
		float extra = (float) Math.sqrt(Math.max(0, fog - 0.15f));
		areaFog = Math.min(1, 0.4f + 0.45f * extra);
		distanceFog = 0.6f + 0.55f * extra + 0.3f * haze;
		float stir = Math.min(1, 1.3f * amount + 2 * Math.max(0, wind));
		morningMist = 0.8f * (1 - stir) * (1 - 0.3f * cover);
		sun = 1 - HAZE_SUN * haze;
		float heat = 1 - smoothstep(0.5f, 2, fog);
		warmth = kind.equals("sand") ? SAND_WARMTH : kind.equals("ash") ? ASH_WARMTH : kind.equals("snow") ? SNOW_WARMTH
			: HAZE_WARMTH * haze * heat;
		skySaturation = -HAZE_SATURATION * haze - COVER_SATURATION * cover;
	}

	/** The wind in this weather over a biome: the region's, nudged, 0 calm to 1 gale. */
	float windIn(String biome)
	{
		return Math.max(0, WeatherClimate.wind(biome) + wind);
	}

	/** How gusty a wind of this strength is, 0 steady to 1 squalls: stronger winds gust more. */
	static float gustiness(float wind)
	{
		return Math.max(0, Math.min(1, 0.1f + 0.8f * wind));
	}

	private static float smoothstep(float e0, float e1, float x)
	{
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

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
		return whiteness(skyWhite, rainAmount, snowAmount, previous);
	}

	/** {@link #whiteness(float, float, float)} for a {@link #skyWhite}, so a blend of weathers can use it too. */
	static float whiteness(float skyWhite, float rainAmount, float snowAmount, float previous)
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
				t.derive();
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
	public static WeatherType get(String id)
	{
		return id != null ? catalogue.byId.get(id) : null;
	}

	/** Clear weather: what Off holds and what anything unknown falls back to. */
	static WeatherType clear()
	{
		return catalogue.clear;
	}
}
