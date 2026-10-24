package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import net.runelite.api.Actor;
import net.runelite.api.NPC;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;

/**
 * The steps of people wading through rain: while a player or a small NPC moves, it leaves a step under its feet every
 * STEP_SECONDS, and each step lives LIFE seconds. Puddles show them as ripple rings (weather_wade.glsl) and the rain
 * pass kicks up a few droplets from a fresh one (rain_vert.glsl). The nearest MAX_STEPS live steps go to the shaders as
 * vec4s: local x, local z, age in seconds, strength (0 where unused).
 */
final class WadeSteps
{
	// steps in the shaders' array (WADE_MAX in weather_wade.glsl)
	static final int MAX_STEPS = 24;
	// how long a step shows (WADE_LIFE in weather_wade.glsl)
	static final float LIFE = 1.2f;
	// seconds between one moving actor's steps
	private static final float STEP_SECONDS = 0.35f;
	// an actor that moved at least this far since the last frame (local units) is walking; much farther, it teleported
	private static final float MOVED_MIN = 1f;
	private static final float MOVED_MAX = 2f * Perspective.LOCAL_TILE_SIZE;
	// steps are only made this far from the camera, where they can be seen
	private static final float REACH = 24f * Perspective.LOCAL_TILE_SIZE;
	// live steps kept before the nearest are picked
	private static final int POOL = 96;

	private static final class Track
	{
		float x, z;
		long lastStep;
		int seen;
	}

	private final Map<Actor, Track> tracks = new IdentityHashMap<>();
	private final float[] stepX = new float[POOL], stepZ = new float[POOL];
	private final long[] born = new long[POOL];
	private int count;
	private int frame;
	// the uniform array, and the squared distances of the steps picked into it
	private final float[] uniform = new float[MAX_STEPS * 4];
	private final float[] picked = new float[MAX_STEPS];

	/**
	 * Follow this frame's actors and make steps for the moving ones. Players are all counted; an NPC counts when it is
	 * one tile in size, which takes in the humanoids (guards, villagers, monks) and some small animals too: ripples
	 * round a rat's feet are no harm.
	 */
	void update(WorldView view, Player self, float cameraX, float cameraZ, boolean enabled)
	{
		long now = System.nanoTime();
		frame++;
		if (enabled && view != null && self != null)
		{
			int plane = view.getPlane();
			follow(self, plane, cameraX, cameraZ, now);
			for (Player player : view.players())
			{
				if (player != null && player != self)
				{
					follow(player, plane, cameraX, cameraZ, now);
				}
			}
			for (NPC npc : view.npcs())
			{
				if (npc != null && !npc.isDead() && npc.getComposition() != null && npc.getComposition().getSize() == 1)
				{
					follow(npc, plane, cameraX, cameraZ, now);
				}
			}
		}
		for (Iterator<Track> it = tracks.values().iterator(); it.hasNext(); )
		{
			if (it.next().seen != frame)
			{
				it.remove();
			}
		}
		long life = (long) (LIFE * 1e9f);
		int kept = 0;
		for (int i = 0; i < count; i++)
		{
			if (now - born[i] < life)
			{
				stepX[kept] = stepX[i];
				stepZ[kept] = stepZ[i];
				born[kept++] = born[i];
			}
		}
		count = kept;
	}

	private void follow(Actor actor, int plane, float cameraX, float cameraZ, long now)
	{
		LocalPoint at = actor.getLocalLocation();
		if (at == null || actor.getWorldLocation().getPlane() != plane)
		{
			return;
		}
		Track t = tracks.get(actor);
		if (t == null)
		{
			t = new Track();
			t.x = at.getX();
			t.z = at.getY();
			tracks.put(actor, t);
		}
		t.seen = frame;
		float moved = (float) Math.hypot(at.getX() - t.x, at.getY() - t.z);
		t.x = at.getX();
		t.z = at.getY();
		if (moved < MOVED_MIN || moved > MOVED_MAX || now - t.lastStep < (long) (STEP_SECONDS * 1e9f)
			|| Math.hypot(t.x - cameraX, t.z - cameraZ) > REACH)
		{
			return;
		}
		t.lastStep = now;
		if (count == POOL)
		{
			// the oldest goes
			System.arraycopy(stepX, 1, stepX, 0, POOL - 1);
			System.arraycopy(stepZ, 1, stepZ, 0, POOL - 1);
			System.arraycopy(born, 1, born, 0, POOL - 1);
			count--;
		}
		stepX[count] = t.x;
		stepZ[count] = t.z;
		born[count++] = now;
	}

	/** The nearest MAX_STEPS live steps to the camera, as the shaders' vec4 array; the rest of it is zero. */
	float[] steps(float cameraX, float cameraZ)
	{
		long now = System.nanoTime();
		Arrays.fill(uniform, 0);
		int n = 0;
		for (int i = 0; i < count; i++)
		{
			float age = (now - born[i]) * 1e-9f;
			if (age >= LIFE)
			{
				continue;
			}
			float dx = stepX[i] - cameraX, dz = stepZ[i] - cameraZ;
			float d = dx * dx + dz * dz;
			// insert in order of distance, dropping the farthest when full
			int j = Math.min(n, MAX_STEPS - 1);
			if (n == MAX_STEPS && d >= picked[j])
			{
				continue;
			}
			for (; j > 0 && picked[j - 1] > d; j--)
			{
				picked[j] = picked[j - 1];
				System.arraycopy(uniform, (j - 1) * 4, uniform, j * 4, 4);
			}
			picked[j] = d;
			uniform[j * 4] = stepX[i];
			uniform[j * 4 + 1] = stepZ[i];
			uniform[j * 4 + 2] = age;
			uniform[j * 4 + 3] = 1;
			n = Math.min(n + 1, MAX_STEPS);
		}
		return uniform;
	}
}
