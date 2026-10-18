package com.midonarhel.runelight;

import java.util.HashMap;
import java.util.Map;
import java.util.function.LongSupplier;
import org.slf4j.Logger;

/**
 * Logs a problem that repeats every frame once per interval instead of hundreds of times a second, with a count of
 * how often it happened in between. Keyed by whatever identifies the problem (an error and where it came from).
 * Client thread only.
 */
final class LogLimiter
{
	private static final class Entry
	{
		long lastMillis;
		int suppressed;
	}

	private final long intervalMillis;
	private final LongSupplier clock;
	private final Map<String, Entry> entries = new HashMap<>();

	LogLimiter(long intervalMillis)
	{
		this(intervalMillis, System::currentTimeMillis);
	}

	LogLimiter(long intervalMillis, LongSupplier clock)
	{
		this.intervalMillis = intervalMillis;
		this.clock = clock;
	}

	/**
	 * Whether to log this occurrence now; -1 if not, else how many occurrences were held back since the last one
	 * logged.
	 */
	int check(String key)
	{
		long now = clock.getAsLong();
		Entry e = entries.get(key);
		if (e == null)
		{
			if (entries.size() > 256)
			{
				// keys are a handful of call sites; this only guards against a key with a changing part
				entries.clear();
			}
			e = new Entry();
			e.lastMillis = now;
			entries.put(key, e);
			return 0;
		}
		if (now - e.lastMillis < intervalMillis)
		{
			e.suppressed++;
			return -1;
		}
		int held = e.suppressed;
		e.suppressed = 0;
		e.lastMillis = now;
		return held;
	}

	void warn(Logger log, String key, String message, Throwable t)
	{
		int held = check(key);
		if (held < 0)
		{
			return;
		}
		String text = held > 0 ? message + " (" + held + " more since last logged)" : message;
		if (t != null)
		{
			log.warn(text, t);
		}
		else
		{
			log.warn(text);
		}
	}
}
