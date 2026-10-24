package com.midonarhel.runelight;

/**
 * How much fog a region makes by itself over the day: mist at dawn that burns off as the sun climbs, some again at
 * dusk and through the night in humid places, almost none in deserts, which get a faint midday heat haze instead.
 * Cold regions keep their mist low. Weather.java multiplies the weather's own fog by this and eases the result, so
 * it changes smoothly with the game clock and blends when the region or the weather changes.
 *
 * All pure functions of the biome, the clock hour and the weather, so they can be tested and tuned offline.
 */
final class FogClimate
{
	/** One biome's fog behaviour, from weather_climate.json. */
	static final class Profile
	{
		// multiplier on all of the weather's fog: how damp the region is
		final float humidity;
		// mist at dawn, at dusk and through the night, each as a multiple of the weather's own fog
		final float dawn, dusk, night;
		// midday heat haze in clear weather, added to the humidity
		final float haze;
		// height of the mist as a share of the configured fog height: mist lies low
		final float mistHeight;

		Profile(float humidity, float dawn, float dusk, float night, float haze, float mistHeight)
		{
			this.humidity = humidity;
			this.dawn = dawn;
			this.dusk = dusk;
			this.night = night;
			this.haze = haze;
			this.mistHeight = mistHeight;
		}
	}

	// no mist, no haze: for a climate without fog profiles
	static final Profile PLAIN = new Profile(1, 0, 0, 0, 0, 1);

	// the fog multiplier rises freely up to here and then only approaches FOG_CAP, so mist on top of foggy
	// weather thickens it without turning it into a wall
	static final float FOG_KNEE = 2.2f;
	static final float FOG_CAP = 3.2f;
	// and never past this: about dawn fog over grassland, the most a player should have to see through
	static final float FOG_MAX = 3.0f;
	// heat haze rises higher than the fog it adds to: height multiplier at pure haze
	private static final float HAZE_HEIGHT = 2.5f;

	private FogClimate()
	{
	}

	/** The biome's profile; unknown and missing biomes behave like the default biome. */
	static Profile profile(String biome)
	{
		return WeatherClimate.fog(biome);
	}

	/**
	 * How calm the weather is for mist to form, 0 to 1: clear and still nights make mist, wind and rain disperse it.
	 */
	static float calm(WeatherType type)
	{
		return Math.min(1, type.morningMist / WeatherType.clear().morningMist);
	}

	/** Dawn mist, 0 to 1: forms before sunrise (06:00), peaks around 06:30 and has burned off by 10:30. */
	static float dawn(double hour)
	{
		return smooth(3, 6, hour) * (1 - smooth(6.5, 10.5, hour));
	}

	/** Evening mist, 0 to 1: settles after sunset and gives way to the night mist before midnight. */
	static float dusk(double hour)
	{
		return smooth(17.5, 20, hour) * (1 - smooth(21, 23.5, hour));
	}

	/** Night mist, 0 to 1: from late evening until it turns into the dawn mist. */
	static float night(double hour)
	{
		return hour >= 12 ? smooth(20.5, 23, hour) : 1 - smooth(4, 6.5, hour);
	}

	/** Midday, 0 to 1: the hours of heat haze. */
	static float midday(double hour)
	{
		return smooth(9.5, 12, hour) * (1 - smooth(14.5, 17, hour));
	}

	/** Mist as a multiple of the weather's fog, before calm and humidity. */
	static float mist(Profile p, double hour)
	{
		hour = wrap(hour);
		return p.dawn * dawn(hour) + p.dusk * dusk(hour) + p.night * night(hour);
	}

	/**
	 * Multiplier on the weather's fog for this region and hour.
	 *
	 * @param calm  {@link #calm}: how much mist the weather lets form
	 * @param cover cloud cover 0 to 1; heat haze needs sun
	 */
	static float density(Profile p, double hour, float calm, float cover)
	{
		hour = wrap(hour);
		return p.humidity * (1 + calm * mist(p, hour)) + p.haze * midday(hour) * (1 - cover);
	}

	/** Multiplier on the fog height: low lying mist, rising heat haze. */
	static float height(Profile p, double hour, float calm, float cover)
	{
		hour = wrap(hour);
		float mist = calm * mist(p, hour);
		float haze = p.haze * midday(hour) * (1 - cover);
		float total = p.humidity * (1 + mist) + haze;
		float mistShare = p.humidity * mist / total;
		float hazeShare = haze / total;
		return (1 + (p.mistHeight - 1) * mistShare) * (1 + (HAZE_HEIGHT - 1) * hazeShare);
	}

	/** The weather's fog times the climate, softly capped above FOG_KNEE and never past FOG_MAX. */
	static float total(float weatherFog, float climate)
	{
		float x = weatherFog * climate;
		if (x <= FOG_KNEE)
		{
			return x;
		}
		float over = x - FOG_KNEE, room = FOG_CAP - FOG_KNEE;
		return Math.min(FOG_KNEE + room * over / (room + over), FOG_MAX);
	}

	/**
	 * How hazy the air is for the stars, 0 to 1: damp regions and thick fog hide the faint ones.
	 *
	 * @param humidity the region's (eased) {@link Profile#humidity}
	 * @param fog the eased fog multiplier
	 */
	static float haze(float humidity, float fog)
	{
		return Math.max(0, Math.min(0.85f, 0.25f * humidity + 0.3f * (fog - WeatherType.clear().fog)));
	}

	private static double wrap(double hour)
	{
		return hour - Math.floor(hour / 24) * 24;
	}

	private static float smooth(double lo, double hi, double x)
	{
		double t = Math.max(0, Math.min(1, (x - lo) / (hi - lo)));
		return (float) (t * t * (3 - 2 * t));
	}
}
