package com.midonarhel.runelight;

import java.util.LinkedHashMap;
import java.util.Map;
import org.lwjgl.opengl.GLDebugMessageCallback;
import org.lwjgl.system.Callback;
import org.slf4j.Logger;

import static org.lwjgl.opengl.GL43C.glDebugMessageCallback;

/**
 * The driver's debug output, each distinct message logged once and counted after that. A driver that warns about the
 * state of every draw (NVIDIA's undefined behavior warnings) sends thousands a frame; formatting and writing each one
 * stalls the thread that issued the call, whichever thread the driver runs the callback on.
 */
final class GlDebugLog
{
	private static final class Entry
	{
		int count;
	}

	private final Map<Integer, Entry> seen = new LinkedHashMap<>();

	/** Count this message; true the first time its id is seen, false for every repeat. */
	synchronized boolean first(int id)
	{
		Entry e = seen.get(id);
		if (e == null)
		{
			if (seen.size() > 1024)
			{
				// ids are a few dozen; this only guards against a driver that puts a changing part in the id
				seen.clear();
			}
			e = new Entry();
			seen.put(id, e);
		}
		return ++e.count == 1;
	}

	/** How often this id was seen. */
	synchronized int count(int id)
	{
		Entry e = seen.get(id);
		return e == null ? 0 : e.count;
	}

	/** The ids seen more than once, with their counts, for a summary when the callback is removed. */
	synchronized Map<Integer, Integer> repeated()
	{
		Map<Integer, Integer> out = new LinkedHashMap<>();
		for (Map.Entry<Integer, Entry> e : seen.entrySet())
		{
			if (e.getValue().count > 1)
			{
				out.put(e.getKey(), e.getValue().count);
			}
		}
		return out;
	}

	/** Install the callback; the returned handle must be freed with {@link #free}. */
	Callback install(Logger log)
	{
		GLDebugMessageCallback callback = GLDebugMessageCallback.create((source, type, id, severity, length, message, user) ->
		{
			if (first(id))
			{
				log.warn("GL debug message 0x{} (source 0x{}, type 0x{}, severity 0x{}): {}", Integer.toHexString(id),
					Integer.toHexString(source), Integer.toHexString(type), Integer.toHexString(severity),
					GLDebugMessageCallback.getMessage(length, message));
			}
		});
		glDebugMessageCallback(callback, 0);
		return callback;
	}

	/** Free the callback (the context may be gone, so no GL call) and log what repeated. */
	void free(Logger log, Callback callback)
	{
		callback.free();
		for (Map.Entry<Integer, Integer> e : repeated().entrySet())
		{
			log.warn("GL debug message 0x{} was repeated {} times", Integer.toHexString(e.getKey()), e.getValue());
		}
	}
}
