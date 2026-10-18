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
 * Loads the weather catalogue (weather_types.json) and the climate (weather_climate.json). In the dev client they
 * are read from the shader source directory instead and reloaded when saved, so weather can be tuned live.
 */
final class WeatherData
{
	private static final Logger log = LoggerFactory.getLogger(WeatherData.class);

	static final String TYPES = "weather_types.json";
	static final String CLIMATE = "weather_climate.json";
	// how often the dev client looks at the files' modification times
	private static final long POLL_MILLIS = 1000;
	private static final Type TYPES_TYPE = new TypeToken<LinkedHashMap<String, WeatherType>>()
	{
	}.getType();

	private static long lastPoll;
	private static long typesStamp = Long.MIN_VALUE, climateStamp = Long.MIN_VALUE;

	private WeatherData()
	{
	}

	/** Load both files from the plugin's resources. */
	static void load(Gson gson)
	{
		try (InputStream t = WeatherData.class.getResourceAsStream(TYPES);
			InputStream c = WeatherData.class.getResourceAsStream(CLIMATE))
		{
			if (t == null || c == null)
			{
				log.warn("{} or {} missing", TYPES, CLIMATE);
				return;
			}
			install(gson, new InputStreamReader(t, StandardCharsets.UTF_8), new InputStreamReader(c, StandardCharsets.UTF_8));
		}
		catch (Exception ex)
		{
			log.error("Unable to load the weather data", ex);
		}
	}

	private static void install(Gson gson, Reader types, Reader climate)
	{
		LinkedHashMap<String, WeatherType> rows = gson.fromJson(types, TYPES_TYPE);
		WeatherClimate.Data data = gson.fromJson(climate, WeatherClimate.Data.class);
		WeatherType.install(rows);
		WeatherClimate.install(data);
		log.debug("Loaded {} weather types, climate for {} biomes", WeatherType.count(), WeatherClimate.allOdds().size());
	}

	/**
	 * Dev client: reload both files from {@code dir} when either changed since the last look, at most once a
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
		Path t = dir.resolve(TYPES), c = dir.resolve(CLIMATE);
		long ts = stamp(t), cs = stamp(c);
		if (typesStamp == Long.MIN_VALUE)
		{
			// the first look only remembers: startup loaded the same files from the resources
			typesStamp = ts;
			climateStamp = cs;
			return false;
		}
		if (ts == typesStamp && cs == climateStamp)
		{
			return false;
		}
		typesStamp = ts;
		climateStamp = cs;
		try (Reader tr = Files.newBufferedReader(t, StandardCharsets.UTF_8);
			Reader cr = Files.newBufferedReader(c, StandardCharsets.UTF_8))
		{
			install(gson, tr, cr);
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
