package com.midonarhel.runelight;

import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Lightning in storms: now and then a strike that lights the sky a little, shows a bolt below the clouds
 * (lightning.glsl) and, for the length of its flash, lights the scene itself.
 *
 * The scene is lit through the sun's own shadow system, one shadow map and no other. Each pulse of the flash rises,
 * holds and falls. While the bolt's light is brighter than the sun's or moon's, the bolt is the light that casts
 * shadows: the direction is the bolt's, 30 to 40 degrees over the horizon, the light pure white, the shadows sharp
 * and long. Below it, the sun is back. The sun is never faded out.
 *
 * The shadow map for the bolt's direction is drawn before the flash begins (PREPARE, a few frames), beside the sun's,
 * and both stay drawn: taking over and handing back are each a swap of the two (ShadowMap.swapFront), never a
 * redraw, so the light and its shadows change in the same frame.
 *
 * A strike is a pulse or two about a second apart, so a viewer sees at most about one flash a second.
 */
class Lightning
{
	private static final Logger log = LoggerFactory.getLogger(Lightning.class);

	// seconds between strikes in a full storm; longer while the storm builds or eases
	private static final float MIN_INTERVAL = 5;
	private static final float MAX_INTERVAL = 25;
	// storm strength (Weather) below which nothing strikes
	private static final float STORM_THRESHOLD = 0.6f;
	// the bolt's light at the top of a pulse, linear: the one brightness knob (sky.glsl's strikeLight.y). The noon
	// sun is about 2.6, so this is a strong sun's.
	static final float BRIGHTNESS = 4;
	// seconds to draw the bolt's shadow map before the flash, at most; a strike that can't is dropped
	private static final float PREPARE_MAX = 1;
	// seconds for a pulse to rise, to hold at its top and to fall, and from the start of one pulse to the next
	private static final float PULSE_RISE = 0.05f;
	private static final float PULSE_HOLD = 0.4f;
	private static final float PULSE_FALL = 0.4f;
	private static final float PULSE_STEP = 1.1f;
	// the bolt's light is this high over the horizon, degrees, and how far from the player it comes down, world
	// units. Not lower than DayCycle's lowest sun: the shadow map's bias is tuned for it, and a lower light shadows
	// level ground with its own texels.
	private static final float MIN_ELEVATION = 30;
	private static final float MAX_ELEVATION = 40;
	private static final float BOLT_MIN_DISTANCE = 3000;
	private static final float BOLT_MAX_DISTANCE = 9000;

	private enum Phase { OFF, PREPARE, PULSES }

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
	private boolean requested;
	// from the plugin, each frame: the brightness of the sun's or moon's light, whether a shadow map for the bolt's
	// direction is drawn, and whether the map in use is for the light in use (the latter for the log)
	private float sunStrength;
	private boolean boltMapReady;
	private boolean mapMatchesLight = true;
	// for the log: when the strike began, and the frames since
	private long startedAt;
	private int frame, prepareFrames;

	// this frame
	private float flash;
	private boolean takeover;

	/**
	 * @param storm Weather's storm strength, 0 to 1
	 * @param focusX player position, world units; bolts come down around it
	 */
	void update(float dt, float storm, boolean enabled, float focusX, float focusZ)
	{
		if (phase == Phase.OFF)
		{
			if (requested)
			{
				strike(focusX, focusZ);
			}
			else if (!enabled || storm < STORM_THRESHOLD)
			{
				untilNext = -1;
				return;
			}
			else
			{
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
		}
		requested = false;

		age += dt;
		++frame;
		if (phase == Phase.PREPARE)
		{
			if (boltMapReady)
			{
				prepareFrames = frame;
				if (log.isDebugEnabled())
				{
					log("PULSES begin, the bolt's shadow map drawn after " + frame + " frames, " + Math.round(age * 1000)
						+ " ms");
				}
				phase = Phase.PULSES;
				age = 0;
			}
			else if (age >= PREPARE_MAX)
			{
				log("dropped: no shadow map for the bolt after " + frame + " frames");
				stop();
			}
			return;
		}

		flash = 0;
		for (int i = 0; i < pulses; ++i)
		{
			flash = Math.max(flash, pulseAmp[i] * pulse(age - i * PULSE_STEP));
		}
		boolean now = boltMapReady && flash * BRIGHTNESS > sunStrength;
		if (now != takeover && log.isDebugEnabled())
		{
			log("TAKEOVER " + (now ? "on" : "off") + " at frame " + frame + " (" + (frame - prepareFrames)
				+ " into the flash): bolt light " + flash * BRIGHTNESS + ", sun or moon light " + sunStrength
				+ ", shadow map for the bolt " + (boltMapReady ? "drawn" : "not drawn"));
		}
		takeover = now;
		if (age >= (pulses - 1) * PULSE_STEP + PULSE_RISE + PULSE_HOLD + PULSE_FALL)
		{
			stop();
		}
	}

	/** Strike on the next update, in any weather. */
	void strikeNow()
	{
		requested = true;
	}

	private float interval(float storm)
	{
		float k = (storm - STORM_THRESHOLD) / (1 - STORM_THRESHOLD);
		return (MIN_INTERVAL + (MAX_INTERVAL - MIN_INTERVAL) * random.nextFloat()) * (1 + 2 * (1 - k));
	}

	private void strike(float focusX, float focusZ)
	{
		startedAt = System.nanoTime();
		phase = Phase.PREPARE;
		age = 0;
		frame = 0;
		boltMapReady = false;
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
		if (log.isDebugEnabled())
		{
			log("PREPARE begins: " + pulses + " pulses, bolt at " + Math.round(boltX) + ", " + Math.round(boltZ)
				+ ", elevation " + Math.round(Math.toDegrees(elevation)) + " degrees");
		}
	}

	// one line per event: the time since the strike began, and the state of the shadows
	private void log(String what)
	{
		log.debug("lightning +{} ms: {}", (System.nanoTime() - startedAt) / 1000000, what);
	}

	private static float smooth(float x)
	{
		x = Math.max(0, Math.min(1, x));
		return x * x * (3 - 2 * x);
	}

	/** One pulse at t seconds from its start: a quick rise, a hold at the top, a long fall. */
	static float pulse(float t)
	{
		return t < PULSE_RISE + PULSE_HOLD ? smooth(t / PULSE_RISE) : 1 - smooth((t - PULSE_RISE - PULSE_HOLD) / PULSE_FALL);
	}

	/**
	 * While the bolt's light is the one that casts shadows, set the direction of that light to it.
	 *
	 * @return whether it did, in which case the caster counts as the sun
	 */
	boolean aim(float[] casterDir)
	{
		if (!takeover)
		{
			return false;
		}
		System.arraycopy(boltDir, 0, casterDir, 0, 3);
		return true;
	}

	/** The bolt's direction while a strike needs a shadow map for it, else null. */
	float[] getBoltDir()
	{
		return phase == Phase.OFF ? null : boltDir;
	}

	/** The brightness of the sun's or moon's light (DayCycle.directStrength), which the bolt has to pass. */
	void setSunStrength(float strength)
	{
		sunStrength = strength;
	}

	/**
	 * What the plugin made of the shadow maps this frame, after drawing them: whether one is drawn for the bolt's
	 * direction, and whether the one in use is for the light in use.
	 */
	void setShadowMaps(boolean boltReady, boolean matchesLight)
	{
		if (phase != Phase.OFF && matchesLight != mapMatchesLight && log.isDebugEnabled())
		{
			log("shadow map " + (matchesLight ? "matches" : "does not match") + " the light at frame " + frame);
		}
		boltMapReady = boltReady;
		mapMatchesLight = matchesLight;
	}

	/** 0 to 1: how bright the flash is this frame. */
	float getFlash()
	{
		return flash;
	}

	void stop()
	{
		if (phase != Phase.OFF && log.isDebugEnabled())
		{
			log("OFF after " + frame + " frames");
		}
		phase = Phase.OFF;
		untilNext = -1;
		takeover = false;
		flash = 0;
	}

	/** sky.glsl's strikeLight: 1 while the bolt casts shadows, and then how bright its light is. */
	void getStrike(float[] out)
	{
		out[0] = takeover ? 1 : 0;
		out[1] = takeover ? flash * BRIGHTNESS : 0;
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
