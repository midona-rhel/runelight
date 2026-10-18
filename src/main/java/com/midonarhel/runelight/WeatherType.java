package com.midonarhel.runelight;

/**
 * The kinds of weather automatic weather picks from, and what each one asks of the sky, the particles and the
 * fog. Weather.java eases toward these targets, so a change of weather always blends.
 */
enum WeatherType
{
	// cover, rain, snow, wind, fog, areaFog, distanceFog, morningMist, storm
	CLEAR("Clear", 0.00f, 0, 0, 0.25f, 0.15f, 0.40f, 0.60f, 0.80f, 0),
	PARTLY_CLOUDY("Partly cloudy", 0.25f, 0, 0, 0.35f, 0.20f, 0.50f, 0.70f, 0.80f, 0),
	CLOUDY("Cloudy", 0.60f, 0, 0, 0.50f, 0.35f, 0.70f, 0.85f, 0.60f, 0),
	FOG("Fog", 0.45f, 0, 0, 0.05f, 2.20f, 1.00f, 1.50f, 0.50f, 0),
	RAIN("Rain", 0.85f, 0.55f, 0, 0.60f, 0.90f, 0.80f, 1.10f, 0.20f, 0),
	STORM("Storm", 1.00f, 1.00f, 0, 1.00f, 1.10f, 0.90f, 1.25f, 0, 1),
	SNOW("Snow", 0.80f, 0, 0.50f, 0.40f, 0.80f, 0.80f, 1.10f, 0.20f, 0),
	BLIZZARD("Blizzard", 1.00f, 0, 1.00f, 1.00f, 1.80f, 1.00f, 1.60f, 0, 0);

	final String displayName;
	// share of the sky the clouds cover, 0 to 1
	final float cover;
	// rain and snow intensity at the default intensity setting; WeatherVariation still swells it
	final float rain, snow;
	// 0 calm to 1 gale: slants rain and snow, drives gusts
	final float wind;
	// multiplier on the volumetric fog density, and how much of an area's own fog (swamps, deserts) it lets add
	final float fog, areaFog;
	// multiplier on the area's distance fog and sky haze (environment.glsl)
	final float distanceFog;
	// extra fog around dawn, as a share of fog: morning mist in calm weather. With FogClimate, how calm the weather
	// is for mist to form at all (relative to clear weather)
	final float morningMist;
	// 1 when lightning strikes
	final float storm;

	WeatherType(String displayName, float cover, float rain, float snow, float wind, float fog, float areaFog,
		float distanceFog, float morningMist, float storm)
	{
		this.displayName = displayName;
		this.cover = cover;
		this.rain = rain;
		this.snow = snow;
		this.wind = wind;
		this.fog = fog;
		this.areaFog = areaFog;
		this.distanceFog = distanceFog;
		this.morningMist = morningMist;
		this.storm = storm;
	}

	@Override
	public String toString()
	{
		return displayName;
	}
}
