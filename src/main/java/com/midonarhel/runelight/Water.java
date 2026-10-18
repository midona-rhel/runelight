package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The water surface drawn by the composite pass (water.glsl): owns the scene's water data texture (from
 * {@link WaterMap}, uploaded when the scene swaps in), the tiling wave noise and caustics, and the per-frame uniforms.
 */
class Water
{
	private static final int NOISE_SIZE = 256;
	private static final long NOISE_SEED = 9157;
	private static final double SLOPE_RANGE = 2.8;
	// The caustic network (layer 1 of the noise texture): light through a tiling wave field of CAUSTIC_WAVES waves of
	// 3 to 9 cycles focused CAUSTIC_FOCUS texels per unit of slope below it, traced with CAUSTIC_RAYS rays per texel
	// per axis. Red and blue focus CAUSTIC_DISPERSION nearer and further than green (exaggerated dispersion, for
	// colored fringes), alpha at CAUSTIC_SOFT of the distance (just under the surface: softer). Stored as intensity
	// over CAUSTIC_RANGE times the mean (water.glsl decodes it).
	private static final long CAUSTIC_SEED = 5;
	private static final int CAUSTIC_WAVES = 28;
	private static final float CAUSTIC_FOCUS = 7.5f;
	private static final float CAUSTIC_DISPERSION = 0.07f;
	private static final float CAUSTIC_SOFT = 0.55f;
	private static final int CAUSTIC_RAYS = 3;
	static final float CAUSTIC_RANGE = 6;
	// the shader's time wraps after this many seconds; every periodic motion in water.glsl repeats within it
	private static final double TIME_WRAP = 3840;
	// how far the waves drift with the wind, world units per second (calm, full wind), before water.glsl's per-type
	// and config speed; the swell's own drift
	private static final double DRIFT_CALM = 5, DRIFT_WIND = 18;
	private static final double SWELL_CALM = 4, SWELL_WIND = 10;
	// the drift distances wrap at a whole number of every repeat they are used with in water.glsl (wave scale 420 at
	// any per-type speed of 0.35 or 0.45 and any whole config percent; swell scale 1650): no jump at the wrap
	private static final double DRIFT_WRAP = 420 * 100 * 20, SWELL_WRAP = 1650 * 400;
	// seconds over which the water follows a change in the wind's strength: it jumps under roofs and in gusts
	private static final double STRENGTH_EASE = 1.5;

	private int texData, texNoise;
	// the water's own clock: System.nanoTime is monotonic and fine grained everywhere, where currentTimeMillis can
	// tick in 15.6 ms steps on Windows (the waves stutter) and jumps when the system clock is adjusted
	private final long startNanos = System.nanoTime();
	// The waves' drift, integrated each frame. Velocity times the time since start would move every wave a long way
	// whenever the wind changes the least bit, which it does all the time: the water jumped about.
	private long lastNanos;
	private double driftX, driftZ, swellX, swellZ, strength;
	private boolean hasData;
	private WaterMap pending;
	private boolean pendingSet;

	private int uniEnabled, uniGrid, uniTime, uniWind, uniDrift, uniRain, uniReflections, uniClarity, uniWaves, uniSpeed,
		uniCaustics, uniDebug;

	/**
	 * Look up the composite program's water uniforms. Call with each newly compiled composite.
	 */
	void locateComposite(int program, int dataUnit, int noiseUnit)
	{
		uniEnabled = glGetUniformLocation(program, "waterEnabled");
		uniGrid = glGetUniformLocation(program, "waterGrid");
		uniTime = glGetUniformLocation(program, "waterTime");
		uniWind = glGetUniformLocation(program, "waterWind");
		uniDrift = glGetUniformLocation(program, "waterDrift");
		uniRain = glGetUniformLocation(program, "waterRain");
		uniReflections = glGetUniformLocation(program, "waterReflections");
		uniClarity = glGetUniformLocation(program, "waterClarity");
		uniWaves = glGetUniformLocation(program, "waterWaves");
		uniSpeed = glGetUniformLocation(program, "waterSpeed");
		uniCaustics = glGetUniformLocation(program, "waterCaustics");
		uniDebug = glGetUniformLocation(program, "waterDebug");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "waterData"), dataUnit);
		glUniform1i(glGetUniformLocation(program, "waterNoise"), noiseUnit);
		glUseProgram(0);
	}

	/**
	 * The water of a scene being loaded (any thread); null for none. Takes effect at {@link #swap}.
	 */
	synchronized void sceneLoaded(WaterMap map)
	{
		pending = map;
		pendingSet = true;
	}

	/**
	 * The loaded scene became the current one: upload its water. GL thread.
	 */
	void swap()
	{
		WaterMap map;
		synchronized (this)
		{
			if (!pendingSet)
			{
				return;
			}
			map = pending;
			pending = null;
			pendingSet = false;
		}

		hasData = map != null && map.hasWater && map.texture != null;
		if (!hasData)
		{
			return;
		}
		if (texData == 0)
		{
			texData = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, texData);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		}
		else
		{
			glBindTexture(GL_TEXTURE_2D, texData);
		}
		FloatBuffer buf = BufferUtils.createFloatBuffer(map.texture.length);
		buf.put(map.texture).flip();
		// full floats: heights reach thousands of units, where half floats step by 2 to 4, and the shader takes the
		// type from this texture too
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, WaterMap.CORNERS * 2, WaterMap.CORNERS, 0, GL_RGBA, GL_FLOAT, buf);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	/**
	 * The scene's water data texture when the water is drawn, else 0: the volumetric fog stops at the surface.
	 */
	int dataTexture(RuneLightConfig config)
	{
		return config.water() && hasData ? texData : 0;
	}

	/**
	 * With the composite program in use: set this frame's uniforms and bind the textures.
	 *
	 * @param wind  x, z direction the wind blows toward, and its strength 0-1
	 * @param rain  0-1 rain falling on open water
	 */
	void bind(RuneLightConfig config, float[] wind, float rain, int dataUnit, int noiseUnit)
	{
		boolean enabled = config.water() && hasData;
		glUniform1i(uniEnabled, enabled ? 1 : 0);
		if (!enabled)
		{
			return;
		}
		if (texNoise == 0)
		{
			texNoise = createNoise();
		}

		glUniform2f(uniGrid, RuneLightPlugin.SCENE_OFFSET, WaterMap.CORNERS);
		long now = System.nanoTime();
		glUniform1f(uniTime, (float) (((now - startNanos) / 1e9) % TIME_WRAP));
		advance(wind, lastNanos == 0 ? 0 : (now - lastNanos) / 1e9);
		lastNanos = now;
		glUniform3f(uniWind, wind[0], wind[1], (float) strength);
		glUniform4f(uniDrift, (float) driftX, (float) driftZ, (float) swellX, (float) swellZ);
		glUniform1f(uniRain, rain);
		glUniform1i(uniReflections, config.waterReflections() ? 1 : 0);
		glUniform1f(uniClarity, config.waterClarity() / 100f);
		glUniform1f(uniWaves, config.waterWaves() / 100f);
		glUniform1f(uniSpeed, config.waterFlowSpeed() / 100f);
		glUniform1i(uniCaustics, config.waterCaustics() ? 1 : 0);
		glUniform1i(uniDebug, config.waterDebug() ? 1 : 0);

		glActiveTexture(GL_TEXTURE0 + dataUnit);
		glBindTexture(GL_TEXTURE_2D, texData);
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texNoise);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Move the drift on by dt seconds of the given wind (direction x, z and strength).
	 */
	void advance(float[] wind, double dt)
	{
		// a stall (loading, a breakpoint) doesn't throw the waves across the lake
		dt = Math.max(0, Math.min(dt, 0.25));
		strength += (wind[2] - strength) * (1 - Math.exp(-dt / STRENGTH_EASE));
		double v = (DRIFT_CALM + (DRIFT_WIND - DRIFT_CALM) * strength) * dt;
		driftX = (driftX + wind[0] * v) % DRIFT_WRAP;
		driftZ = (driftZ + wind[1] * v) % DRIFT_WRAP;
		double sv = (SWELL_CALM + SWELL_WIND * strength) * dt;
		swellX = (swellX + wind[0] * sv) % SWELL_WRAP;
		swellZ = (swellZ + wind[1] * sv) % SWELL_WRAP;
	}

	double[] drift()
	{
		return new double[]{driftX, driftZ, swellX, swellZ, strength};
	}

	static void unbind(int dataUnit, int noiseUnit)
	{
		glActiveTexture(GL_TEXTURE0 + dataUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	void free()
	{
		if (texData != 0)
		{
			glDeleteTextures(texData);
			texData = 0;
		}
		if (texNoise != 0)
		{
			glDeleteTextures(texNoise);
			texNoise = 0;
		}
		hasData = false;
	}

	// Layer 0 the waves, layer 1 the caustics: one sampler, the composite has none to spare
	private static int createNoise()
	{
		byte[] waves = noise(NOISE_SIZE, NOISE_SEED);
		byte[] caustics = caustics(NOISE_SIZE, CAUSTIC_SEED);
		ByteBuffer buf = BufferUtils.createByteBuffer(waves.length + caustics.length);
		buf.put(waves).put(caustics).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, NOISE_SIZE, NOISE_SIZE, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		// mipmaps calm the waves in the distance instead of letting them sparkle
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		return tex;
	}

	/**
	 * Tiling wave noise, RGBA: rg the slope of a wave height field (0.5 is flat, scaled so slopes of SLOPE_RANGE
	 * standard deviations reach 0 or 1), b that height, a an independent finer height (sparkle on crests).
	 *
	 * The heights are sums of sine waves with whole numbers of cycles across the texture, so they tile, in random
	 * directions with amplitude falling with frequency like wind waves. Slopes come out exactly from the same sums,
	 * so the normals are smooth at every scale and the mipmaps fade them evenly into calm water.
	 */
	static byte[] noise(int size, long seed)
	{
		Random rng = new Random(seed);
		int n = size * size;
		float[] h = new float[n], sx = new float[n], sy = new float[n], fine = new float[n];
		waves(rng, size, 56, 2, 22, 1.7f, h, sx, sy);
		waves(rng, size, 40, 6, 40, 1.4f, fine, null, null);

		// slopes scaled by their spread rather than the rare steepest one, so the texture's range is well used
		double sum = 0;
		for (int i = 0; i < n; ++i)
		{
			sum += sx[i] * sx[i] + sy[i] * sy[i];
		}
		float max = (float) (SLOPE_RANGE * Math.sqrt(sum / (2 * n))) + 1e-6f;
		normalize(h);
		normalize(fine);
		byte[] out = new byte[n * 4];
		for (int i = 0; i < n; ++i)
		{
			out[i * 4] = (byte) Math.round((clamp(sx[i] / max) * 0.5f + 0.5f) * 255);
			out[i * 4 + 1] = (byte) Math.round((clamp(sy[i] / max) * 0.5f + 0.5f) * 255);
			out[i * 4 + 2] = (byte) Math.round(h[i] * 255);
			out[i * 4 + 3] = (byte) Math.round(fine[i] * 255);
		}
		return out;
	}

	/**
	 * Tiling caustics, RGBA: light refracted through a tiling wave surface onto a plane below it, traced as rays from a
	 * fine grid that land displaced by the surface slope and are splatted where they land. Where the surface focuses
	 * them they pile up into the bright lines of a caustic network, the cells between them get less. rgb focus at
	 * slightly different distances (dispersion), a closer (softer); each channel's mean is 1/CAUSTIC_RANGE.
	 */
	static byte[] caustics(int size, long seed)
	{
		Random rng = new Random(seed);
		int m = size * CAUSTIC_RAYS;
		float[] h = new float[m * m], gx = new float[m * m], gy = new float[m * m];
		waves(rng, m, CAUSTIC_WAVES, 3, 9, 1f, h, gx, gy);
		double sum = 0;
		for (int i = 0; i < gx.length; ++i)
		{
			sum += gx[i] * gx[i] + gy[i] * gy[i];
		}
		// waves() gives slopes per ray; in texels per unit of RMS slope per axis
		float unit = (float) (1 / Math.sqrt(sum / (2 * gx.length)));
		float[] focus = {CAUSTIC_FOCUS * (1 - CAUSTIC_DISPERSION), CAUSTIC_FOCUS, CAUSTIC_FOCUS * (1 + CAUSTIC_DISPERSION),
			CAUSTIC_FOCUS * CAUSTIC_SOFT};
		int n = size * size;
		float[][] light = new float[4][n];
		for (int y = 0; y < m; ++y)
		{
			for (int x = 0; x < m; ++x)
			{
				int i = y * m + x;
				float sx = gx[i] * unit, sy = gy[i] * unit;
				for (int c = 0; c < 4; ++c)
				{
					// where the ray lands, in texels, then shared bilinearly between the four texels around it
					float px = (x + 0.5f) / CAUSTIC_RAYS - focus[c] * sx - 0.5f;
					float py = (y + 0.5f) / CAUSTIC_RAYS - focus[c] * sy - 0.5f;
					int x0 = (int) Math.floor(px), y0 = (int) Math.floor(py);
					float fx = px - x0, fy = py - y0;
					int xa = Math.floorMod(x0, size), xb = Math.floorMod(x0 + 1, size);
					int ya = Math.floorMod(y0, size) * size, yb = Math.floorMod(y0 + 1, size) * size;
					float[] l = light[c];
					l[ya + xa] += (1 - fx) * (1 - fy);
					l[ya + xb] += fx * (1 - fy);
					l[yb + xa] += (1 - fx) * fy;
					l[yb + xb] += fx * fy;
				}
			}
		}
		byte[] out = new byte[n * 4];
		// rays per texel: the mean
		float scale = 1f / (CAUSTIC_RAYS * CAUSTIC_RAYS * CAUSTIC_RANGE);
		for (int c = 0; c < 4; ++c)
		{
			float[] l = light[c];
			for (int i = 0; i < n; ++i)
			{
				out[i * 4 + c] = (byte) Math.round(Math.min(l[i] * scale, 1f) * 255);
			}
		}
		return out;
	}

	/**
	 * Add {@code count} sine waves of kMin to kMax cycles across the texture, amplitude falling as k^-falloff, to h, and
	 * their x and y slopes (per texel) to sx and sy when given.
	 */
	static void waves(Random rng, int size, int count, int kMin, int kMax, float falloff, float[] h, float[] sx, float[] sy)
	{
		float[] sinX = new float[size], cosX = new float[size], sinY = new float[size], cosY = new float[size];
		for (int w = 0; w < count; ++w)
		{
			// log-uniform frequency, uniform direction, snapped to whole cycles so it tiles
			double k = kMin * Math.pow((double) kMax / kMin, rng.nextDouble());
			double angle = rng.nextDouble() * Math.PI * 2;
			int kx = (int) Math.round(k * Math.cos(angle)), ky = (int) Math.round(k * Math.sin(angle));
			if (kx == 0 && ky == 0)
			{
				continue;
			}
			double len = Math.sqrt(kx * kx + ky * ky);
			float amp = (float) Math.pow(len, -falloff);
			double phase = rng.nextDouble() * Math.PI * 2;
			for (int i = 0; i < size; ++i)
			{
				double ax = Math.PI * 2 * kx * i / size + phase, ay = Math.PI * 2 * ky * i / size;
				sinX[i] = (float) Math.sin(ax);
				cosX[i] = (float) Math.cos(ax);
				sinY[i] = (float) Math.sin(ay);
				cosY[i] = (float) Math.cos(ay);
			}
			float gx = (float) (amp * Math.PI * 2 * kx / size), gy = (float) (amp * Math.PI * 2 * ky / size);
			for (int y = 0; y < size; ++y)
			{
				for (int x = 0; x < size; ++x)
				{
					int i = y * size + x;
					// sin(a + b) and cos(a + b) from the row and column tables
					float s = sinX[x] * cosY[y] + cosX[x] * sinY[y];
					h[i] += amp * s;
					if (sx != null)
					{
						float c = cosX[x] * cosY[y] - sinX[x] * sinY[y];
						sx[i] += gx * c;
						sy[i] += gy * c;
					}
				}
			}
		}
	}

	private static float clamp(float v)
	{
		return Math.max(-1, Math.min(1, v));
	}

	private static void normalize(float[] v)
	{
		float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
		for (float f : v)
		{
			min = Math.min(min, f);
			max = Math.max(max, f);
		}
		for (int i = 0; i < v.length; ++i)
		{
			v[i] = (v[i] - min) / (max - min);
		}
	}
}
