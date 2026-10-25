package com.midonarhel.runelight;

/**
 * When a light is there and how bright: 117 HD's life of a light. It spawns spawnDelay after it is made, fades in,
 * and goes when whatever it is attached to goes (the parent), after despawnDelay and a fade out, or at once with
 * despawnWithParent. With fixedDespawnTime it lasts spawnDelay + despawnDelay whatever the parent does. A light tied
 * to an animation starts over each time the parent starts playing it.
 */
final class LightLife
{
	// seconds a light hidden or shown again takes to fade
	static final float HIDING_FADE = 0.064f;

	private final float spawnDelay;
	private float fadeIn;
	private final float fadeOut;
	private float despawnDelay;
	private final boolean despawnWithParent;
	// no end until the parent goes
	private final boolean dynamic;
	// comes back each time the parent starts the animation
	private final boolean replayable;

	private float elapsed;
	// -1: not known yet
	private float lifetime = -1;
	private boolean parentExists;
	private boolean hidden;
	private float changedHidingAt = -1;

	/**
	 * @param fadesIn whether it fades in at all: not the lights a scene starts with
	 */
	LightLife(LightDefinition d, boolean fadesIn)
	{
		spawnDelay = Math.max(0, d.spawnDelay) / 1000f;
		fadeIn = fadesIn ? Math.max(0, d.fadeInDuration) / 1000f : 0;
		fadeOut = Math.max(0, d.fadeOutDuration) / 1000f;
		despawnDelay = Math.max(0, d.despawnDelay) / 1000f;
		despawnWithParent = d.despawnWithParent;
		if (d.fixedDespawnTime)
		{
			lifetime = spawnDelay + despawnDelay;
		}
		dynamic = lifetime == -1;
		if (dynamic)
		{
			// room to fade out
			despawnDelay = Math.max(despawnDelay, fadeOut);
		}
		replayable = d.animationSpecific();
		if (replayable)
		{
			// there until the animation plays
			if (dynamic)
			{
				lifetime = 0;
			}
			else
			{
				elapsed = lifetime;
			}
		}
	}

	/**
	 * Move on by dt seconds.
	 *
	 * @param parentExists whether the parent is there and, for a light tied to an animation, playing it
	 */
	void update(boolean parentExists, float dt)
	{
		if (parentExists != this.parentExists)
		{
			this.parentExists = parentExists;
			if (parentExists)
			{
				if (replayable)
				{
					elapsed = 0;
					changedHidingAt = -1;
					if (dynamic)
					{
						lifetime = -1;
					}
				}
			}
			else if (despawnWithParent)
			{
				lifetime = 0;
			}
			else if (lifetime == -1)
			{
				lifetime = Math.max(spawnDelay + fadeIn, elapsed) + despawnDelay;
			}
		}
		elapsed += dt;
	}

	/**
	 * Hide the light, or show it again, with a short fade unless it was only just made or the plane changed.
	 */
	void hide(boolean hide, boolean changedPlanes)
	{
		if (hide == hidden)
		{
			return;
		}
		hidden = hide;
		if (!changedPlanes && elapsed > 0)
		{
			// start fading from where the fade the other way had got
			float begin = elapsed;
			if (changedHidingAt != -1)
			{
				begin -= Math.max(0, HIDING_FADE - (elapsed - changedHidingAt));
			}
			changedHidingAt = begin;
		}
	}

	private boolean spawned()
	{
		return spawnDelay <= elapsed && (lifetime == -1 || elapsed < lifetime);
	}

	/**
	 * Whether the light is there to be drawn.
	 */
	boolean visible()
	{
		if (!spawned())
		{
			return false;
		}
		// hidden: only while it fades out
		return !hidden || changedHidingAt != -1 && elapsed - changedHidingAt < HIDING_FADE;
	}

	/**
	 * 0 to 1, what its strength is multiplied by: fading in, fading out, and hiding.
	 */
	float brightness()
	{
		float b = 1;
		if (fadeIn > 0)
		{
			b *= saturate((elapsed - spawnDelay) / fadeIn);
		}
		if (fadeOut > 0 && lifetime != -1)
		{
			b *= saturate((lifetime - elapsed) / fadeOut);
		}
		float hiding = changedHidingAt == -1 ? 1 : saturate((elapsed - changedHidingAt) / HIDING_FADE);
		return b * (hidden ? 1 - hiding : hiding);
	}

	/**
	 * Whether it is done with: it ran its course (unless it comes back with its animation), or its parent is gone and
	 * it is not there.
	 */
	boolean over(boolean parentGone)
	{
		if (!replayable && lifetime != -1 && lifetime < elapsed)
		{
			return true;
		}
		return parentGone && !spawned();
	}

	private static float saturate(float v)
	{
		return Math.max(0, Math.min(1, v));
	}
}
