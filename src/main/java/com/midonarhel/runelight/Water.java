package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The water surface drawn by the composite pass (water.glsl): owns the scene's water data texture (from
 * {@link WaterMap}, uploaded when the scene swaps in), the tiling wave noise and the per-frame uniforms.
 */
class Water
{
	private static final int NOISE_SIZE = 256;
	private static final long NOISE_SEED = 9157;
	private static final double SLOPE_RANGE = 2.8;
	// the shader's time wraps after this many seconds; every periodic motion in water.glsl repeats within it
	private static final double TIME_WRAP = 3840;

	private int texData, texNoise;
	private boolean hasData;
	private WaterMap pending;
	private boolean pendingSet;

	private int uniEnabled, uniGrid, uniTime, uniWind, uniRain, uniReflections, uniClarity, uniWaves, uniSpeed,
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
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16F, WaterMap.CORNERS * 2, WaterMap.CORNERS, 0, GL_RGBA, GL_FLOAT, buf);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	/**
	 * With the composite program in use: set this frame's uniforms and bind the textures.
	 *
	 * @param wind  x, z direction the wind blows toward, and its strength 0-1
	 * @param rain  0-1 rain falling on open water
	 */
	void bind(RuneLightConfig config, double seconds, float[] wind, float rain, int dataUnit, int noiseUnit)
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
		glUniform1f(uniTime, (float) (seconds % TIME_WRAP));
		glUniform3f(uniWind, wind[0], wind[1], wind[2]);
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
		glBindTexture(GL_TEXTURE_2D, texNoise);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int dataUnit, int noiseUnit)
	{
		glActiveTexture(GL_TEXTURE0 + dataUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
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

	private static int createNoise()
	{
		byte[] data = noise(NOISE_SIZE, NOISE_SEED);
		ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
		buf.put(data).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, NOISE_SIZE, NOISE_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		// mipmaps calm the waves in the distance instead of letting them sparkle
		glGenerateMipmap(GL_TEXTURE_2D);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glBindTexture(GL_TEXTURE_2D, 0);
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
