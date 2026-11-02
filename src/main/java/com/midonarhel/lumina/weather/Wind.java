package com.midonarhel.lumina.weather;

import com.midonarhel.lumina.*;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The wind: a mean strength and gustiness that ease toward the weather's, a direction that wanders slowly, and gust
 * fronts that travel across the world with it, so a gust visibly passes over (rain slants harder, snow swirls,
 * foliage bends, water roughens) instead of everything gusting at once. It moves the clouds, slants the rain,
 * carries the snow and ruffles the water.
 *
 * Shaders get it through wind.glsl (uniforms windDir, windStrength, windGust, windTime, windFront, windGustiness),
 * set on any program by {@link #apply}. windGustAt(worldXZ) there is the same function as {@link #gustAt}.
 */
public final class Wind
{
	// gust fronts: world units per noise cell along the wind and across it; the noise repeats every GUST_PERIOD
	// cells along the wind, so the front position can wrap. Must match wind.glsl.
	static final float GUST_LENGTH = 2400;
	static final float GUST_WIDTH = 7000;
	static final float GUST_PERIOD = 256;
	// share of the noise that is a gust: from GUST_LOW it rises to full at GUST_HIGH. Must match wind.glsl.
	static final float GUST_LOW = 0.5f, GUST_HIGH = 0.85f;
	// world units per second the fronts travel: one speed, so where they are is a function of the time alone and the
	// same for every player
	private static final float FRONT_SPEED = 660;
	// the direction wanders this far either way (radians) over minutes, and wobbles a little over seconds, more in
	// gusty weather; the wander repeats every DIR_CELLS of its noise (68 hours), so the clouds' drift, its integral,
	// is a sum over one period worked out once (drift)
	private static final float DIR_WANDER = 1.2f;
	private static final double DIR_PERIOD = 240;
	private static final int DIR_CELLS = 1024;
	// the prevailing direction, the same for everyone
	private static final float BASE_ANGLE = (float) (WeatherVariation.noise(1 + 0.5, 7) * 2 * Math.PI);
	// Gauss-Legendre nodes and weights on 0..1, for the drift within a cell
	private static final double[] GAUSS_X = {0.0198550717512319, 0.1016667612931866, 0.2372337950418355,
		0.4082826787521751, 0.5917173212478249, 0.7627662049581645, 0.8983332387068134, 0.9801449282487681};
	private static final double[] GAUSS_W = {0.0506142681451881, 0.1111905172266872, 0.1568533229389436,
		0.1813418916891810, 0.1813418916891810, 0.1568533229389436, 0.1111905172266872, 0.0506142681451881};
	// the drift over each whole cell of a period, summed up to its start; [DIR_CELLS] is a whole period's
	private static double[] driftX, driftZ;
	private static final float WOBBLE = 0.1f;
	private static final double WOBBLE_PERIOD = 18;
	// strength lulls and swells around the weather's mean
	private static final float LULL = 0.75f, SWELL = 1.25f;
	private static final double LULL_PERIOD = 45;
	// seconds for the mean and the gustiness to follow a change of weather
	private static final float EASE_SECONDS = 10;
	// windTime: sway phase per second in calm air, and added per unit of strength and gust
	private static final float TIME_CALM = 0.5f;
	private static final float TIME_RATE = 1.5f;

	/** Uniform locations of one program that reads the wind. */
	public static final class Locations
	{
		int dir = -1, strength = -1, gust = -1, time = -1, front = -1, gustiness = -1;
	}

	private boolean initialized;
	private float mean, gustiness;

	// this frame
	private float dirX = 1, dirZ;
	private float strength;
	private float gust;
	private double time;
	private double front;

	/** The direction's slow wander at real time t (GameClock.realSeconds), radians, wobble left out. */
	static double wander(double t)
	{
		return BASE_ANGLE + DIR_WANDER * (WeatherVariation.noise(t / DIR_PERIOD, 11, DIR_CELLS) * 2 - 1);
	}

	/**
	 * How far something carried along the wander's direction at one world unit a second has gone at real time t:
	 * the integral of the direction from the epoch, x and z into out. A function of the time alone, so the clouds
	 * are in the same place for every player; exact to the quadrature, and smooth.
	 */
	public static void drift(double t, double[] out)
	{
		if (driftX == null)
		{
			double[] x = new double[DIR_CELLS + 1], z = new double[DIR_CELLS + 1];
			for (int k = 0; k < DIR_CELLS; ++k)
			{
				double[] d = cellDrift(k, 1);
				x[k + 1] = x[k] + d[0];
				z[k + 1] = z[k] + d[1];
			}
			driftX = x;
			driftZ = z;
		}
		double cells = t / DIR_PERIOD;
		double fl = Math.floor(cells);
		long cell = (long) fl;
		long periods = Math.floorDiv(cell, DIR_CELLS);
		int k = (int) Math.floorMod(cell, DIR_CELLS);
		double[] d = cellDrift(cell, cells - fl);
		out[0] = periods * driftX[DIR_CELLS] + driftX[k] + d[0];
		out[1] = periods * driftZ[DIR_CELLS] + driftZ[k] + d[1];
	}

	// the drift over the first share f of a cell, seconds times the direction
	private static double[] cellDrift(long cell, double f)
	{
		double x = 0, z = 0;
		for (int i = 0; i < GAUSS_X.length; ++i)
		{
			double a = wander((cell + f * GAUSS_X[i]) * DIR_PERIOD);
			x += GAUSS_W[i] * Math.cos(a);
			z += GAUSS_W[i] * Math.sin(a);
		}
		return new double[]{x * f * DIR_PERIOD, z * f * DIR_PERIOD};
	}

	/**
	 * Advance the wind. Continuous whatever changes: the mean and gustiness ease, the direction is smooth noise of
	 * the time, and the fronts and sway phase only ever move forward.
	 *
	 * @param t       seconds, smooth (the weather's clock)
	 * @param mean    the weather's mean wind, 0 calm to 1 gale
	 * @param gusty   the weather's gustiness, 0 to 1
	 * @param varying whether direction and strength wander and gusts blow; otherwise a steady wind
	 * @param gradual false applies a change at once
	 * @param px      player position, world units, for the gust there
	 */
	void update(double t, float dt, float mean, float gusty, boolean varying, boolean gradual, float px, float pz)
	{
		if (!initialized || !gradual)
		{
			initialized = true;
			this.mean = mean;
			this.gustiness = gusty;
		}
		else
		{
			this.mean = Weather.ease(this.mean, mean, dt, EASE_SECONDS);
			this.gustiness = Weather.ease(this.gustiness, gusty, dt, EASE_SECONDS);
		}
		double angle = BASE_ANGLE;
		float lull = 1;
		if (varying)
		{
			angle = wander(t) + WOBBLE * (1 + gustiness) * (WeatherVariation.noise(t / WOBBLE_PERIOD, 12) * 2 - 1);
			lull = LULL + (SWELL - LULL) * WeatherVariation.noise(t / LULL_PERIOD, 5);
		}
		dirX = (float) Math.cos(angle);
		dirZ = (float) Math.sin(angle);
		strength = this.mean * lull;

		double period = (double) GUST_LENGTH * GUST_PERIOD;
		front = Math.floorMod((long) (t * FRONT_SPEED * 1000), (long) (period * 1000)) / 1000.0;
		gust = varying ? gustAt(px, pz) : 0;
		time += dt * (TIME_CALM + TIME_RATE * (strength + 0.5f * gust));
	}

	/** Gust strength 0..1 at a world position (scene local units), as wind.glsl's windGustAt. */
	float gustAt(float x, float z)
	{
		return gustAt(x, z, dirX, dirZ, (float) front, gustiness);
	}

	static float gustAt(float x, float z, float dirX, float dirZ, float front, float gustiness)
	{
		if (gustiness <= 0)
		{
			return 0;
		}
		float along = (x * dirX + z * dirZ - front) / GUST_LENGTH;
		float across = (z * dirX - x * dirZ) / GUST_WIDTH;
		float n = noise(along, GUST_PERIOD);
		float m = noise(across + 37, 1 << 20);
		return gustiness * smoothstep(GUST_LOW, GUST_HIGH, n) * (0.5f + 0.5f * m);
	}

	/** Periodic 1D value noise with a quintic fade, the same as wind.glsl's windNoise. */
	static float noise(float x, float period)
	{
		float i = (float) Math.floor(x);
		float f = x - i;
		float u = f * f * f * (f * (f * 6 - 15) + 10);
		float a = hash(mod(i, period));
		float b = hash(mod(i + 1, period));
		return a + (b - a) * u;
	}

	private static float hash(float p)
	{
		p = fract(p * 0.1031f);
		p *= p + 33.33f;
		p *= p + p;
		return fract(p);
	}

	private static float fract(float x)
	{
		return x - (float) Math.floor(x);
	}

	private static float mod(float x, float y)
	{
		return x - y * (float) Math.floor(x / y);
	}

	private static float smoothstep(float e0, float e1, float x)
	{
		float t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
		return t * t * (3 - 2 * t);
	}

	/** Direction the wind blows toward, x (east), normalized with {@link #getDirZ}. */
	public float getDirX()
	{
		return dirX;
	}

	/** Direction the wind blows toward, z (north). */
	public float getDirZ()
	{
		return dirZ;
	}

	/** Steady strength without gusts, 0 calm, 1 gale, above 1 stronger. */
	float getStrength()
	{
		return strength;
	}

	/** The eased mean of the weather, without lulls: the clouds' drift. */
	public float getMean()
	{
		return mean;
	}

	/** Gust at the player, 0..1. */
	float getGust()
	{
		return gust;
	}

	float getGustiness()
	{
		return gustiness;
	}

	/** Accumulated sway phase: grows faster in stronger wind, never jumps. */
	double getTime()
	{
		return time;
	}

	/** World units the gust fronts have travelled, wrapped. */
	float getFront()
	{
		return (float) front;
	}

	/** Compass direction the wind comes from: N, NE, ... (it blows toward dir). */
	String fromCompass()
	{
		double deg = Math.toDegrees(Math.atan2(-dirX, -dirZ));
		String[] names = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};
		return names[(int) Math.floorMod(Math.round(deg / 45), 8)];
	}

	public static Locations locate(int program)
	{
		Locations l = new Locations();
		if (program == 0)
		{
			return l;
		}
		l.dir = glGetUniformLocation(program, "windDir");
		l.strength = glGetUniformLocation(program, "windStrength");
		l.gust = glGetUniformLocation(program, "windGust");
		l.time = glGetUniformLocation(program, "windTime");
		l.front = glGetUniformLocation(program, "windFront");
		l.gustiness = glGetUniformLocation(program, "windGustiness");
		return l;
	}

	/**
	 * With the program in use: set its wind uniforms. Underground, or with {@code still}, the air is calm but the
	 * phase keeps going, so nothing jumps on the way out.
	 */
	public void apply(Locations l, boolean still)
	{
		if (l == null)
		{
			return;
		}
		glUniform2f(l.dir, dirX, dirZ);
		glUniform1f(l.strength, still ? 0 : strength);
		glUniform1f(l.gust, still ? 0 : gust);
		glUniform1f(l.time, (float) time);
		glUniform1f(l.front, (float) front);
		glUniform1f(l.gustiness, still ? 0 : gustiness);
	}
}
