package com.midonarhel.runelight;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntPredicate;

/**
 * Which scene objects the player can interact with: those whose definition lists an action (a door, a tree, a bank
 * booth). They take less snow (SurfaceMaterials). Definitions can only be read on the client thread, and scenes are
 * uploaded on other threads: the uploader notes the objects it will meet ({@link #want}, while sizing the zone), the
 * client thread works them out ({@link #resolve}) before the zone is uploaded, which then reads the answer
 * ({@link #is}). An object nobody resolved counts as scenery.
 */
final class Interactables
{
	private static final Map<Integer, Boolean> known = new ConcurrentHashMap<>();
	private static final Set<Integer> wanted = ConcurrentHashMap.newKeySet();

	private Interactables()
	{
	}

	/** Whether any of an object definition's actions is set (unset ones are null or empty). */
	static boolean hasAction(String[] actions)
	{
		if (actions != null)
		{
			for (String a : actions)
			{
				if (a != null && !a.isEmpty())
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Note an object id the next upload will read, unless it is known already. Any thread. */
	static void want(int id)
	{
		if (!known.containsKey(id))
		{
			wanted.add(id);
		}
	}

	/** Work out the wanted objects with {@code hasAction}, which reads object definitions: client thread only. */
	static void resolve(IntPredicate hasAction)
	{
		for (Integer id : wanted)
		{
			known.put(id, hasAction.test(id));
			wanted.remove(id);
		}
	}

	static boolean is(int id)
	{
		return known.getOrDefault(id, false);
	}

	/** Forget everything, for tests. */
	static void clear()
	{
		known.clear();
		wanted.clear();
	}
}
