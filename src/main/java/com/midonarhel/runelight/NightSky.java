package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

import java.util.Random;

/**
 * The moving parts of the night sky that night_sky.glsl draws: the turn of the stars, the shooting stars and the
 * aurora. All of it is worked out on the CPU from the clocks, a few numbers a frame, so the shader only draws.
 *
 * Directions are unit vectors in game coordinates: x east, z north, y pointing DOWN.
 */
final class NightSky
{
	// the stars turn once a day about a pole this far from straight up, toward north
	private static final double POLE_TILT = Math.toRadians(35);

	// A shooting star every so often: each slot of real time has one with this chance, somewhere in it. They begin
	// where the camera looks, since the sky shows only a little above the horizon.
	static final float SLOT_SECONDS = 12;
	static final float METEOR_CHANCE = 0.45f;
	static final float METEOR_LIFE_MIN = 0.7f, METEOR_LIFE_MAX = 1.2f;
	// radians a second along the sky, and the length of the tail
	private static final float METEOR_SPEED_MIN = 0.3f, METEOR_SPEED_MAX = 0.5f;
	private static final float METEOR_TRAIL = 0.12f;
	// where it begins, around where the camera looks: radians of azimuth either side, and elevation above the view's
	private static final float METEOR_SPREAD = 0.6f;
	private static final float METEOR_ELEVATION_MIN = 0.12f, METEOR_ELEVATION_MAX = 1.1f;
	// it fades out before it falls below this elevation, but flies at least this far (radians)
	private static final float METEOR_END_ELEVATION = 0.05f, METEOR_REACH_MIN = 0.15f;

	// the aurora's biomes (weather_regions.json): the Fremennik and the frozen north
	private static final String[] AURORA_BIOMES = {"TUNDRA", "ARCTIC"};
	// share of nights without one
	static final float AURORA_NO_NIGHT = 0.3f;
	static final float AURORA_FADE_SECONDS = 6;

	/** night_sky.glsl's starFrame, column-major. */
	final float[] frame = new float[9];
	/** The shooting star's head (direction, brightness; 0: none) and tail. */
	final float[] meteorHead = new float[4];
	final float[] meteorTail = new float[3];
	/** The aurora's strength where the player is, 0 to 1. */
	float aurora;
	/** The angle one pixel covers in the middle of the screen, radians. */
	private float pixel;

	private long slot = Long.MIN_VALUE;
	private boolean meteorOn;
	private float meteorDelay, meteorLife, meteorSpeed;
	private final float[] meteorStart = new float[3];
	private final float[] meteorTravel = new float[3];
	private float lastSeconds = Float.NaN;

	/**
	 * The rotation that turns the sky's directions into the stars' frame, for the day's phase (DayCycle.phase, 0 to 1).
	 */
	static void starFrame(double phase, float[] out)
	{
		double angle = 2 * Math.PI * phase;
		double c = Math.cos(angle), s = Math.sin(angle);
		double ax = 0, ay = -Math.cos(POLE_TILT), az = Math.sin(POLE_TILT);
		// Rodrigues, column-major: c I + s [a]x + (1 - c) a a^T
		out[0] = (float) (c + (1 - c) * ax * ax);
		out[1] = (float) ((1 - c) * ax * ay + s * az);
		out[2] = (float) ((1 - c) * ax * az - s * ay);
		out[3] = (float) ((1 - c) * ay * ax - s * az);
		out[4] = (float) (c + (1 - c) * ay * ay);
		out[5] = (float) ((1 - c) * ay * az + s * ax);
		out[6] = (float) ((1 - c) * az * ax + s * ay);
		out[7] = (float) ((1 - c) * az * ay - s * ax);
		out[8] = (float) (c + (1 - c) * az * az);
	}

	static boolean isAuroraBiome(String biome)
	{
		for (String b : AURORA_BIOMES)
		{
			if (b.equals(biome))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * How strong the aurora is on the night around this game time, 0 to 1: some nights have none, the others a
	 * different strength each. The night changes at noon.
	 */
	static float auroraNight(long gameMillis)
	{
		long z = GameClock.day(gameMillis + GameClock.DAY / 2) * 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		float h = ((z ^ (z >>> 31)) >>> 40) / (float) (1 << 24);
		return h < AURORA_NO_NIGHT ? 0 : 0.45f + 0.55f * (h - AURORA_NO_NIGHT) / (1 - AURORA_NO_NIGHT);
	}

	/**
	 * Once a frame.
	 *
	 * @param seconds real seconds, steadily increasing
	 * @param phase DayCycle's phase
	 * @param forward the direction the camera looks, null when unknown
	 * @param pixelAngle the angle one pixel covers in the middle of the screen, radians
	 * @param biome the weather biome of the place the camera looks at, null underground
	 * @param auroraOn the setting
	 */
	void update(float seconds, double phase, float[] forward, float pixelAngle, String biome, long gameMillis,
		boolean auroraOn)
	{
		pixel = pixelAngle;
		float dt = Float.isNaN(lastSeconds) ? 0 : Math.max(0, Math.min(0.1f, seconds - lastSeconds));
		lastSeconds = seconds;
		starFrame(phase, frame);
		updateMeteor(seconds, forward);
		float target = auroraOn && isAuroraBiome(biome) ? auroraNight(gameMillis) : 0;
		float step = dt / AURORA_FADE_SECONDS;
		aurora = aurora < target ? Math.min(target, aurora + step) : Math.max(target, aurora - step);
	}

	private void updateMeteor(float seconds, float[] forward)
	{
		long now = (long) Math.floor(seconds / SLOT_SECONDS);
		if (now != slot)
		{
			slot = now;
			roll(forward);
		}
		float age = seconds - slot * SLOT_SECONDS - meteorDelay;
		if (!meteorOn || age < 0 || age > meteorLife)
		{
			meteorHead[3] = 0;
			return;
		}
		float travelled = meteorSpeed * age;
		along(travelled, meteorHead);
		along(Math.max(0, travelled - METEOR_TRAIL * smooth(age / 0.15f)), meteorTail);
		// in and out quickly
		meteorHead[3] = smooth(age / 0.1f) * smooth((meteorLife - age) / 0.3f);
	}

	/** The point angle radians from where the shooting star began, along its great circle. */
	private void along(float angle, float[] out)
	{
		float c = (float) Math.cos(angle), s = (float) Math.sin(angle);
		for (int i = 0; i < 3; ++i)
		{
			out[i] = meteorStart[i] * c + meteorTravel[i] * s;
		}
	}

	private static float smooth(float x)
	{
		float t = Math.max(0, Math.min(1, x));
		return t * t * (3 - 2 * t);
	}

	/** Decide this slot's shooting star, if it has one: where it begins, which way it falls, when and how fast. */
	private void roll(float[] forward)
	{
		Random random = new Random(slot * 0x9E3779B97F4A7C15L + 0x1234567L);
		meteorOn = forward != null && random.nextFloat() < METEOR_CHANCE;
		float life = METEOR_LIFE_MIN + (METEOR_LIFE_MAX - METEOR_LIFE_MIN) * random.nextFloat();
		float delay = random.nextFloat() * (SLOT_SECONDS - life);
		float speed = METEOR_SPEED_MIN + (METEOR_SPEED_MAX - METEOR_SPEED_MIN) * random.nextFloat();
		float spread = (2 * random.nextFloat() - 1) * METEOR_SPREAD;
		float lift = random.nextFloat();
		float lean = (random.nextBoolean() ? 1 : -1) * (0.2f + 0.7f * random.nextFloat());
		if (!meteorOn)
		{
			return;
		}
		meteorDelay = delay;
		meteorSpeed = speed;
		double azimuth = Math.atan2(forward[2], forward[0]) + spread;
		double looking = Math.asin(Math.max(-1, Math.min(1, -forward[1])));
		double elevation = Math.max(METEOR_ELEVATION_MIN, Math.min(METEOR_ELEVATION_MAX, looking + 0.1 + 0.35 * lift));
		double c = Math.cos(elevation);
		float[] s = meteorStart;
		s[0] = (float) (Math.cos(azimuth) * c);
		s[1] = (float) -Math.sin(elevation);
		s[2] = (float) (Math.sin(azimuth) * c);
		// down along the sky, leaning to one side: the tangent of the way down, and of the way across
		float[] down = {-s[1] * s[0], 1 - s[1] * s[1], -s[1] * s[2]};
		normalize(down);
		float[] side = {s[1] * down[2] - s[2] * down[1], s[2] * down[0] - s[0] * down[2], s[0] * down[1] - s[1] * down[0]};
		float cl = (float) Math.cos(lean), sl = (float) Math.sin(lean);
		for (int i = 0; i < 3; ++i)
		{
			meteorTravel[i] = down[i] * cl + side[i] * sl;
		}
		// it burns out before it reaches the horizon, which hides it
		float reach = speed * life;
		while (reach > METEOR_REACH_MIN && elevationAt(reach) < METEOR_END_ELEVATION)
		{
			reach *= 0.85f;
		}
		meteorLife = reach / speed;
	}

	private float elevationAt(float angle)
	{
		return (float) Math.asin(-(meteorStart[1] * Math.cos(angle) + meteorTravel[1] * Math.sin(angle)));
	}

	private static void normalize(float[] v)
	{
		float l = (float) Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
		for (int i = 0; i < 3; ++i)
		{
			v[i] /= l;
		}
	}

	/** A composite program's night sky uniforms. */
	static final class Uniforms
	{
		private int frame = -1, nebula = -1, aurora = -1, head = -1, tail = -1, pixel = -1;

		void locate(int program)
		{
			frame = glGetUniformLocation(program, "starFrame");
			nebula = glGetUniformLocation(program, "nebulaStrength");
			aurora = glGetUniformLocation(program, "auroraStrength");
			head = glGetUniformLocation(program, "meteorHead");
			tail = glGetUniformLocation(program, "meteorTail");
			pixel = glGetUniformLocation(program, "starPixel");
		}
	}

	/** With a composite program in use: this frame's night sky. nebula: the setting, 1 as authored. */
	void set(Uniforms u, float nebula)
	{
		glUniformMatrix3fv(u.frame, false, frame);
		glUniform1f(u.nebula, nebula);
		glUniform1f(u.aurora, aurora);
		glUniform4fv(u.head, meteorHead);
		glUniform3fv(u.tail, meteorTail);
		glUniform1f(u.pixel, pixel);
	}
}
