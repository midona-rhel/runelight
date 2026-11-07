package com.midonarhel.lumina.scene;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import net.runelite.api.DynamicObject;
import net.runelite.api.Renderable;

/** Object-name metadata for the water-bed extrusion exception; definitions resolve on the client thread. */
final class BoatObjects
{
	private static final Map<Integer, Boolean> known = new ConcurrentHashMap<>();
	private static final Set<Integer> wanted = ConcurrentHashMap.newKeySet();

	private BoatObjects() { }

	static boolean namedBoat(String name)
	{
		if (name == null) return false;
		String lower = name.trim().toLowerCase(Locale.ROOT);
		// Boat, fishing boat, rowboat, longboat, etc.; not boathouse or boat house scenery.
		return lower.endsWith("boat") || lower.endsWith("boats");
	}

	static void want(int id)
	{
		if (!known.containsKey(id)) wanted.add(id);
	}

	/** Called beside Interactables.resolve before worker uploads; the callback may read client definitions. */
	static void resolve(IntFunction<String> name)
	{
		for (Integer id : wanted)
		{
			known.put(id, namedBoat(name.apply(id)));
			wanted.remove(id);
		}

	}

	static boolean is(int id, Renderable renderable)
	{
		if (renderable instanceof DynamicObject)
		{
			var shown = ((DynamicObject) renderable).getRecordedObjectComposition();
			if (shown != null) return namedBoat(shown.getName());
		}
		return known.getOrDefault(id, false);
	}

	static void clear()
	{
		known.clear();
		wanted.clear();
	}
}
