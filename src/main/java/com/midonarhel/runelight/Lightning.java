package com.midonarhel.runelight;

import java.util.Random;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Lightning in storms: now and then a strike that briefly lights the sky, the clouds and the scene, and a bolt
 * below the clouds (lightning.glsl). The flash reaches every pass through sky.glsl's weatherOvercast.z, so the
 * scene, the transparency layer, the fog and the particles all light up together.
 *
 * The strike lights the scene through the sun's own shadow system, one shadow map and no other: it takes over the
 * light that casts shadows, so that everything in range throws long, sharp shadows away from the bolt.
 *
 * 1. The sun's or moon's light fades out, leaving the ambient light alone (FADE_OUT).
 * 2. The light turns to the bolt, low over the horizon, while it is out; the shadow map is drawn for that direction
 *    in the dark (AIM waits for it).
 * 3. One or two pulses of pure white light, each rising and falling smoothly, with the sky's flash in step.
 * 4. The light turns back to the sun or moon, still dark, and the shadow map is drawn for that again (RETURN),
 *    then the sun fades back in (FADE_IN).
 *
 * The map is drawn over a few frames while the old one stays in use (RuneLightPlugin.renderStaticShadows), so a
 * rebuild costs no more than the sun's own slow turning does, twice a strike.
 */
class Lightning
{
	// seconds between strikes in a full storm; longer while the storm builds or eases
	private static final float MIN_INTERVAL = 5;
	private static final float MAX_INTERVAL = 25;
	// storm strength (Weather) below which nothing strikes
	private static final float STORM_THRESHOLD = 0.6f;
	// seconds for the sun to fade out and back in
	private static final float FADE_OUT = 0.15f;
	private static final float FADE_IN = 0.3f;
	// seconds the dark waits at least, and at most, for the shadow map to be drawn for the light's new direction
	private static final float TURN_MIN = 0.1f;
	private static final float TURN_MAX = 0.6f;
	// seconds for a pulse to rise and to fall, and from the start of one to the next
	private static final float PULSE_RISE = 0.05f;
	private static final float PULSE_FALL = 0.15f;
	private static final float PULSE_STEP = 0.3f;
	// the bolt's light is this low over the horizon, degrees, and how far from the player it comes down, world units
	private static final float MIN_ELEVATION = 10;
	private static final float MAX_ELEVATION = 20;
	private static final float BOLT_MIN_DISTANCE = 3000;
	private static final float BOLT_MAX_DISTANCE = 9000;

	private enum Phase { OFF, FADE_OUT, AIM, PULSES, RETURN, FADE_IN }

	private final Random random = new Random();
	private float untilNext = -1;
	private Phase phase = Phase.OFF;
	// seconds into the phase
	private float age;
	private final float[] pulseAmp = new float[2];
	private int pulses;
	// the bolt's light: its direction (toward it, y down like DayCycle.casterDir) and where it comes down
	private final float[] boltDir = new float[3];
	private float boltX, boltZ, boltSeed;
	// whether the shadow map was drawn for the light's direction as of last frame (RuneLightPlugin)
	private boolean shadowsReady;

	// this frame
	private float darkness;
	private float flash;

	/**
	 * @param storm Weather's storm strength, 0 to 1
	 * @param focusX player position, world units; bolts come down around it
	 */
	void update(float dt, float storm, boolean enabled, float focusX, float focusZ)
	{
		if (phase == Phase.OFF)
		{
			if (!enabled || storm < STORM_THRESHOLD)
			{
				untilNext = -1;
				return;
			}
			if (untilNext < 0)
			{
				untilNext = interval(storm);
			}
			untilNext -= dt;
			if (untilNext > 0)
			{
				return;
			}
			untilNext = interval(storm);
			strike(focusX, focusZ);
		}

		age += dt;
		switch (phase)
		{
			case FADE_OUT:
				darkness = smooth(age / FADE_OUT);
				if (age >= FADE_OUT)
				{
					enter(Phase.AIM);
				}
				break;
			case AIM:
				if (age >= TURN_MIN && (shadowsReady || age >= TURN_MAX))
				{
					enter(Phase.PULSES);
				}
				break;
			case PULSES:
				flash = 0;
				for (int i = 0; i < pulses; ++i)
				{
					flash = Math.max(flash, pulseAmp[i] * pulse(age - i * PULSE_STEP));
				}
				if (age >= (pulses - 1) * PULSE_STEP + PULSE_RISE + PULSE_FALL)
				{
					flash = 0;
					enter(Phase.RETURN);
				}
				break;
			case RETURN:
				if (age >= TURN_MIN && (shadowsReady || age >= TURN_MAX))
				{
					enter(Phase.FADE_IN);
				}
				break;
			case FADE_IN:
				darkness = 1 - smooth(age / FADE_IN);
				if (age >= FADE_IN)
				{
					stop();
				}
				break;
			default:
				break;
		}
	}

	private void enter(Phase next)
	{
		phase = next;
		age = 0;
		// the map drawn for the old direction doesn't count for the new one
		shadowsReady = false;
	}

	private float interval(float storm)
	{
		float k = (storm - STORM_THRESHOLD) / (1 - STORM_THRESHOLD);
		return (MIN_INTERVAL + (MAX_INTERVAL - MIN_INTERVAL) * random.nextFloat()) * (1 + 2 * (1 - k));
	}

	private void strike(float focusX, float focusZ)
	{
		phase = Phase.FADE_OUT;
		age = 0;
		shadowsReady = false;
		pulses = 1 + random.nextInt(2);
		for (int i = 0; i < pulses; ++i)
		{
			pulseAmp[i] = 0.8f + 0.2f * random.nextFloat();
		}
		double angle = random.nextDouble() * 2 * Math.PI;
		float distance = BOLT_MIN_DISTANCE + (BOLT_MAX_DISTANCE - BOLT_MIN_DISTANCE) * random.nextFloat();
		boltX = focusX + (float) Math.cos(angle) * distance;
		boltZ = focusZ + (float) Math.sin(angle) * distance;
		boltSeed = random.nextFloat() * 100;
		double elevation = Math.toRadians(MIN_ELEVATION + (MAX_ELEVATION - MIN_ELEVATION) * random.nextFloat());
		boltDir[0] = (float) (Math.cos(angle) * Math.cos(elevation));
		boltDir[1] = (float) -Math.sin(elevation);
		boltDir[2] = (float) (Math.sin(angle) * Math.cos(elevation));
	}

	private static float smooth(float x)
	{
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}

	/** One pulse at t seconds from its start: a smooth rise, then a smooth fall. */
	static float pulse(float t)
	{
		return t < PULSE_RISE ? smooth(t / PULSE_RISE) : 1 - smooth((t - PULSE_RISE) / PULSE_FALL);
	}

	/**
	 * While the light is turned to the bolt, set the direction of the light that casts shadows to it.
	 *
	 * @return whether it did, in which case the caster counts as the sun
	 */
	boolean aim(float[] casterDir)
	{
		if (phase != Phase.AIM && phase != Phase.PULSES)
		{
			return false;
		}
		System.arraycopy(boltDir, 0, casterDir, 0, 3);
		return true;
	}

	/** Whether the shadow map is drawn for the light's direction now; call each frame after drawing it. */
	void setShadowsReady(boolean ready)
	{
		shadowsReady = ready;
	}

	/** 0 to 1: how bright the flash is this frame. */
	float getFlash()
	{
		return flash;
	}

	void stop()
	{
		phase = Phase.OFF;
		untilNext = -1;
		darkness = 0;
		flash = 0;
	}

	/** sky.glsl's strikeLight: how much of the sun's light is out and how bright the bolt's is. */
	void getStrike(float[] out)
	{
		out[0] = darkness;
		out[1] = flash;
	}

	/**
	 * A composite program's lightning uniform. Call with each newly compiled composite.
	 */
	static int locateComposite(int program)
	{
		return glGetUniformLocation(program, "lightningBolt");
	}

	/**
	 * With a composite program in use: where the bolt is relative to the camera and how visible it is (location: its
	 * locateComposite).
	 */
	void bind(int location, float cameraX, float cameraZ)
	{
		glUniform4f(location, boltX - cameraX, boltZ - cameraZ, Math.min(1, flash * 1.5f), boltSeed);
	}
}
