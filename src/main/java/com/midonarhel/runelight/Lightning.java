package com.midonarhel.runelight;

import java.util.Random;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Lightning in storms: now and then a strike of one to three flickers that briefly lights the sky, the clouds
 * and the scene, and sometimes a visible bolt below the clouds (lightning.glsl). The flash reaches every pass
 * through sky.glsl's weatherOvercast.z, so the scene, the transparency layer, the fog and the particles all
 * light up together.
 *
 * Kept gentle on purpose: strikes are many seconds apart, flickers within one are over a fifth of a second
 * apart (under three per second), and every flicker rises and falls smoothly.
 */
class Lightning
{
	// seconds between strikes in a full storm; longer while the storm builds or eases
	private static final float MIN_INTERVAL = 5;
	private static final float MAX_INTERVAL = 25;
	// storm strength (Weather) below which nothing strikes
	private static final float STORM_THRESHOLD = 0.6f;
	// seconds for a flicker to rise, and its decay time constant
	private static final float ATTACK = 0.05f;
	private static final float DECAY = 0.14f;
	// seconds between flickers of one strike
	private static final float FLICKER_MIN = 0.22f;
	private static final float FLICKER_MAX = 0.36f;
	// share of strikes with a visible bolt, and how far from the player it comes down, world units
	private static final float BOLT_CHANCE = 0.6f;
	private static final float BOLT_MIN_DISTANCE = 3000;
	private static final float BOLT_MAX_DISTANCE = 9000;
	// seconds after the last flicker until the strike is over
	private static final float TAIL = 1;

	private final Random random = new Random();
	private float untilNext = -1;
	private float age = -1;
	private final float[] pulseAt = new float[3];
	private final float[] pulseAmp = new float[3];
	private int pulses;
	private boolean bolt;
	private float boltX, boltZ, boltSeed;

	// this frame
	private float flash;
	private float boltVisibility;

	private int uniBolt;

	/**
	 * @param storm Weather's storm strength, 0 to 1
	 * @param focusX player position, world units; bolts come down around it
	 */
	void update(float dt, float storm, boolean enabled, float focusX, float focusZ)
	{
		boolean striking = age >= 0;
		if (!enabled || storm < STORM_THRESHOLD)
		{
			untilNext = -1;
			if (!striking)
			{
				flash = 0;
				boltVisibility = 0;
				return;
			}
		}
		else
		{
			if (untilNext < 0)
			{
				untilNext = interval(storm);
			}
			if (!striking)
			{
				untilNext -= dt;
				if (untilNext <= 0)
				{
					strike(focusX, focusZ);
					untilNext = interval(storm);
				}
			}
		}

		if (age < 0)
		{
			flash = 0;
			boltVisibility = 0;
			return;
		}
		age += dt;
		float f = 0;
		for (int i = 0; i < pulses; ++i)
		{
			f = Math.max(f, pulseAmp[i] * envelope(age - pulseAt[i]));
		}
		flash = f;
		boltVisibility = bolt ? Math.min(1, f * 1.5f) : 0;
		if (age > pulseAt[pulses - 1] + TAIL)
		{
			age = -1;
			flash = 0;
			boltVisibility = 0;
		}
	}

	private float interval(float storm)
	{
		float k = (storm - STORM_THRESHOLD) / (1 - STORM_THRESHOLD);
		return (MIN_INTERVAL + (MAX_INTERVAL - MIN_INTERVAL) * random.nextFloat()) * (1 + 2 * (1 - k));
	}

	private void strike(float focusX, float focusZ)
	{
		age = 0;
		pulses = 1 + random.nextInt(3);
		float strength = 0.6f + 0.4f * random.nextFloat();
		float at = 0;
		for (int i = 0; i < pulses; ++i)
		{
			pulseAt[i] = at;
			// later flickers are fainter
			pulseAmp[i] = strength * (i == 0 ? 1 : 0.35f + 0.4f * random.nextFloat());
			at += FLICKER_MIN + (FLICKER_MAX - FLICKER_MIN) * random.nextFloat();
		}
		bolt = random.nextFloat() < BOLT_CHANCE;
		double angle = random.nextDouble() * 2 * Math.PI;
		float distance = BOLT_MIN_DISTANCE + (BOLT_MAX_DISTANCE - BOLT_MIN_DISTANCE) * random.nextFloat();
		boltX = focusX + (float) Math.cos(angle) * distance;
		boltZ = focusZ + (float) Math.sin(angle) * distance;
		boltSeed = random.nextFloat() * 100;
	}

	/** One flicker: a quick smooth rise, then an exponential fall. */
	static float envelope(float t)
	{
		if (t <= 0)
		{
			return 0;
		}
		if (t < ATTACK)
		{
			float x = t / ATTACK;
			return x * x * (3 - 2 * x);
		}
		return (float) Math.exp(-(t - ATTACK) / DECAY);
	}

	/** 0 to 1: how bright the flash is this frame. */
	float getFlash()
	{
		return flash;
	}

	void stop()
	{
		age = -1;
		untilNext = -1;
		flash = 0;
		boltVisibility = 0;
	}

	/**
	 * Look up the composite program's lightning uniform. Call with each newly compiled composite.
	 */
	void locateComposite(int program)
	{
		uniBolt = glGetUniformLocation(program, "lightningBolt");
	}

	/**
	 * With the composite program in use: where the bolt is relative to the camera and how visible it is.
	 */
	void bind(float cameraX, float cameraZ)
	{
		glUniform4f(uniBolt, boltX - cameraX, boltZ - cameraZ, boltVisibility, boltSeed);
	}
}
