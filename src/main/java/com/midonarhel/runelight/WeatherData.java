package com.midonarhel.runelight;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loads the weather catalogue (weather_types.json), the climate (weather_climate.json) and the map of which biome
 * each place has (weather_regions.json). In the dev client they are read from the shader source directory instead and
 * reloaded when saved, so weather can be tuned live.
 */
final class WeatherData
{
	private static final Logger log = LoggerFactory.getLogger(WeatherData.class);

	static final String TYPES = "weather_types.json";
	static final String CLIMATE = "weather_climate.json";
	static final String REGIONS = "weather_regions.json";
	// how often the dev client looks at the files' modification times
	private static final long POLL_MILLIS = 1000;
	private static final Type TYPES_TYPE = new TypeToken<LinkedHashMap<String, WeatherType>>()
	{
	}.getType();

	private static long lastPoll;
	private static long typesStamp = Long.MIN_VALUE, climateStamp = Long.MIN_VALUE, regionsStamp = Long.MIN_VALUE;

	private WeatherData()
	{
	}

	/** Load the files from the plugin's resources. */
	static void load(Gson gson)
	{
		try (InputStream t = WeatherData.class.getResourceAsStream(TYPES);
			InputStream c = WeatherData.class.getResourceAsStream(CLIMATE);
			InputStream r = WeatherData.class.getResourceAsStream(REGIONS))
		{
			if (t == null || c == null || r == null)
			{
				log.warn("{}, {} or {} missing", TYPES, CLIMATE, REGIONS);
				return;
			}
			install(gson, new InputStreamReader(t, StandardCharsets.UTF_8), new InputStreamReader(c, StandardCharsets.UTF_8),
				new InputStreamReader(r, StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			log.error("Unable to load the weather data", ex);
		}
	}

	private static void install(Gson gson, Reader types, Reader climate, Reader regions)
	{
		LinkedHashMap<String, WeatherType> rows = gson.fromJson(types, TYPES_TYPE);
		WeatherClimate.Data data = gson.fromJson(climate, WeatherClimate.Data.class);
		WeatherRegions.Data map = gson.fromJson(regions, WeatherRegions.Data.class);
		WeatherType.install(rows);
		WeatherClimate.install(data);
		WeatherRegions.install(map);
		log.debug("Loaded {} weather types, climate for {} biomes", WeatherType.count(), WeatherClimate.allOdds().size());
	}

	/**
	 * Dev client: reload the files from {@code dir} when any changed since the last look, at most once a
	 * second. A file that fails to parse leaves the weather as it was.
	 *
	 * @return true when the weather data was replaced
	 */
	static boolean reloadIfChanged(Path dir, Gson gson)
	{
		long now = System.currentTimeMillis();
		if (dir == null || now - lastPoll < POLL_MILLIS)
		{
			return false;
		}
		lastPoll = now;
		Path t = dir.resolve(TYPES), c = dir.resolve(CLIMATE), r = dir.resolve(REGIONS);
		long ts = stamp(t), cs = stamp(c), rs = stamp(r);
		if (typesStamp == Long.MIN_VALUE)
		{
			// the first look only remembers: startup loaded the same files from the resources
			typesStamp = ts;
			climateStamp = cs;
			regionsStamp = rs;
			return false;
		}
		if (ts == typesStamp && cs == climateStamp && rs == regionsStamp)
		{
			return false;
		}
		typesStamp = ts;
		climateStamp = cs;
		regionsStamp = rs;
		try (Reader tr = Files.newBufferedReader(t, StandardCharsets.UTF_8);
			Reader cr = Files.newBufferedReader(c, StandardCharsets.UTF_8);
			Reader rr = Files.newBufferedReader(r, StandardCharsets.UTF_8))
		{
			install(gson, tr, cr, rr);
			log.info("Weather data reloaded");
			return true;
		}
		catch (Exception ex)
		{
			log.error("Weather data reload failed", ex);
			return false;
		}
	}

	private static long stamp(Path p)
	{
		try
		{
			return Files.getLastModifiedTime(p).toMillis() ^ Files.size(p) << 40;
		}
		catch (Exception ex)
		{
			return 0;
		}
	}
}
