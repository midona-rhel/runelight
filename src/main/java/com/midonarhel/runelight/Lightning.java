package com.midonarhel.runelight;

import java.util.Random;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Lightning in storms: now and then a strike of one to four pulses that briefly lights the sky, the clouds and the
 * scene, and sometimes a visible bolt below the clouds (lightning.glsl). The flash reaches every pass through
 * sky.glsl's weatherOvercast.z, so the scene, the transparency layer, the fog and the particles all light up
 * together.
 *
 * While a strike lasts the bolt is also the light that casts shadows (DayCycle.castFromBolt): the strike is
 * announced LEAD seconds before its first pulse, so the shadow map of its direction is drawn in the meantime
 * (RuneLightPlugin.renderShadowMap) and the flash needs no frame that draws one.
 *
 * Kept gentle on purpose: strikes are many seconds apart, pulses within one are over a third of a second apart
 * (under three per second), and every pulse rises and falls smoothly.
 */
class Lightning
{
	// seconds between strikes in a full storm; longer while the storm builds or eases
	private static final float MIN_INTERVAL = 5;
	private static final float MAX_INTERVAL = 25;
	// storm strength (Weather) below which nothing strikes
	private static final float STORM_THRESHOLD = 0.6f;
	// seconds from the strike being announced to its first pulse
	static final float LEAD = 0.4f;
	// seconds for a pulse to rise
	private static final float ATTACK = 0.05f;
	// the decay time constants of a pulse, seconds: a long single flash, or short stutters
	private static final float DECAY_SINGLE_MIN = 0.16f;
	private static final float DECAY_SINGLE_MAX = 0.26f;
	private static final float DECAY_FLICKER_MIN = 0.07f;
	private static final float DECAY_FLICKER_MAX = 0.14f;
	// seconds between pulses of one strike
	private static final float PULSE_GAP_MIN = 0.34f;
	private static final float PULSE_GAP_MAX = 0.5f;
	// the shares of strikes with one pulse and with two; the rest flicker three or four times
	private static final float SINGLE_CHANCE = 0.4f;
	private static final float DOUBLE_CHANCE = 0.3f;
	// a strike's strongest pulse
	private static final float STRENGTH_MIN = 0.5f;
	// share of strikes with a visible bolt, and how far from the player it comes down, world units
	private static final float BOLT_CHANCE = 0.6f;
	private static final float BOLT_MIN_DISTANCE = 1800;
	private static final float BOLT_MAX_DISTANCE = 9000;
	// the last pulse is over, for the light that casts shadows, after this many of its decay time constants
	private static final float KEY_DECAYS = 4;
	// seconds after the last pulse until the glow in the clouds is over
	private static final float TAIL = 1;
	private static final int MAX_PULSES = 4;

	private final Random random;
	private float untilNext = -1;
	// seconds since the first pulse, negative while announced; unused when not striking
	private float age;
	private boolean striking;
	private final float[] pulseAt = new float[MAX_PULSES];
	private final float[] pulseAmp = new float[MAX_PULSES];
	private float decay;
	private int pulses;
	private boolean bolt;
	private float boltX, boltZ, boltSeed;
	// toward the strike, as DayCycle.casterDir
	private final float[] direction = new float[3];

	// this frame
	private float flash;
	private float boltVisibility;

	Lightning()
	{
		this(new Random());
	}

	Lightning(Random random)
	{
		this.random = random;
	}

	/**
	 * @param storm Weather's storm strength, 0 to 1
	 * @param focusX player position, world units; bolts come down around it
	 */
	void update(float dt, float storm, boolean enabled, float focusX, float focusZ)
	{
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

		if (!striking)
		{
			flash = 0;
			boltVisibility = 0;
			return;
		}
		age += dt;
		float f = 0;
		for (int i = 0; i < pulses; ++i)
		{
			f = Math.max(f, pulseAmp[i] * envelope(age - pulseAt[i], decay));
		}
		flash = f;
		boltVisibility = bolt ? Math.min(1, f * 1.5f) : 0;
		if (age > pulseAt[pulses - 1] + TAIL)
		{
			striking = false;
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
		striking = true;
		age = -LEAD;
		float kind = random.nextFloat();
		boolean flicker = kind >= SINGLE_CHANCE + DOUBLE_CHANCE;
		pulses = kind < SINGLE_CHANCE ? 1 : flicker ? 3 + random.nextInt(2) : 2;
		// a flicker stutters, a single flash lingers
		float decayMin = flicker ? DECAY_FLICKER_MIN : DECAY_SINGLE_MIN;
		float decayMax = flicker ? DECAY_FLICKER_MAX : DECAY_SINGLE_MAX;
		decay = decayMin + (decayMax - decayMin) * random.nextFloat();
		float strength = STRENGTH_MIN + (1 - STRENGTH_MIN) * random.nextFloat();
		float at = 0;
		for (int i = 0; i < pulses; ++i)
		{
			pulseAt[i] = at;
			// later pulses are fainter
			pulseAmp[i] = strength * (i == 0 ? 1 : 0.35f + 0.4f * random.nextFloat());
			at += PULSE_GAP_MIN + (PULSE_GAP_MAX - PULSE_GAP_MIN) * random.nextFloat();
		}
		bolt = random.nextFloat() < BOLT_CHANCE;
		double angle = random.nextDouble() * 2 * Math.PI;
		float distance = BOLT_MIN_DISTANCE + (BOLT_MAX_DISTANCE - BOLT_MIN_DISTANCE) * random.nextFloat();
		boltX = focusX + (float) Math.cos(angle) * distance;
		boltZ = focusZ + (float) Math.sin(angle) * distance;
		boltSeed = random.nextFloat() * 100;
		DayCycle.boltDirection(direction, boltX - focusX, boltZ - focusZ);
	}

	/** One pulse: a quick smooth rise, then an exponential fall with time constant decay. */
	static float envelope(float t, float decay)
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
		return (float) Math.exp(-(t - ATTACK) / decay);
	}

	/** 0 to 1: how bright the flash is this frame. */
	float getFlash()
	{
		return flash;
	}

	/**
	 * Whether a strike is announced or under way: the shadow map of its direction is needed (direction).
	 */
	boolean isStriking()
	{
		return striking;
	}

	/**
	 * Whether the bolt is the light that casts shadows now: from the first pulse until the last has died away. The
	 * flashes between pulses are in it too, so the shadows hold still instead of turning back and forth.
	 */
	boolean isKey()
	{
		return striking && age >= 0 && age <= pulseAt[pulses - 1] + ATTACK + KEY_DECAYS * decay;
	}

	/** Whether storms strike now or may soon, so the shadow map for the bolts is kept. */
	boolean isStorming()
	{
		return striking || untilNext >= 0;
	}

	/** Toward the strike, as DayCycle.casterDir; for the strike being announced or under way. */
	float[] getDirection()
	{
		return direction;
	}

	void stop()
	{
		striking = false;
		untilNext = -1;
		flash = 0;
		boltVisibility = 0;
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
		glUniform4f(location, boltX - cameraX, boltZ - cameraZ, boltVisibility, boltSeed);
	}
}
