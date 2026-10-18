package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Volumetric clouds in the sky, drawn inline in the composite's sky branch (clouds.glsl), plus optional cloud
 * shadows on the ground. This class owns the tiling cloud noise texture and the per-frame cloud uniforms: how
 * much of the sky is covered (the configured clear-weather coverage, closing up as rain or snow comes in) and
 * the wind drift.
 */
class Clouds
{
	private static final int NOISE_SIZE = 128;
	// world units per repeat of the noise; must match CLOUD_TILE in clouds.glsl
	private static final double TILE = 32768;
	// the shader samples at 1/4, 1 and 3 times the tile frequency, so the offset can wrap every 4 tiles
	private static final double WRAP = TILE * 4;
	// world units per second at 100% speed, about two tiles; rain and snow blow twice as fast
	private static final float WIND_SPEED = 256;
	private static final float RAIN_WIND = 2;
	// roughly along the rain's slant (Weather.draw)
	private static final float WIND_X = 0.87f, WIND_Z = 0.49f;

	private int uniEnabled, uniShadows, uniCoverage, uniWeather, uniOffset;
	private int texNoise;
	private double windX, windZ;
	private long lastNanos;

	/**
	 * Look up the composite program's cloud uniforms. Call with each newly compiled composite.
	 */
	void locateComposite(int program, int noiseUnit)
	{
		uniEnabled = glGetUniformLocation(program, "cloudsEnabled");
		uniShadows = glGetUniformLocation(program, "cloudShadowsEnabled");
		uniCoverage = glGetUniformLocation(program, "cloudCoverage");
		uniWeather = glGetUniformLocation(program, "cloudWeather");
		uniOffset = glGetUniformLocation(program, "cloudOffset");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "cloudNoise"), noiseUnit);
		glUseProgram(0);
	}

	/**
	 * With the composite program in use: advance the wind, set the uniforms and bind the noise to
	 * {@code noiseUnit} (its 2D target).
	 *
	 * @param cloudCover Weather.cloudCover(), 0 clear to 1 overcast
	 * @param baseX world x of the scene's origin, in world units, so clouds stay put when the scene reloads
	 */
	void bind(boolean enabled, boolean shadows, int coveragePercent, int speedPercent, float cloudCover,
		long baseX, long baseZ, int noiseUnit)
	{
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min((now - lastNanos) / 1e9f, 0.25f);
		lastNanos = now;

		glUniform1i(uniEnabled, enabled ? 1 : 0);
		if (!enabled)
		{
			return;
		}
		if (texNoise == 0)
		{
			texNoise = createNoise();
		}

		float speed = WIND_SPEED * speedPercent / 100f * (1 + (RAIN_WIND - 1) * cloudCover);
		windX = (windX + WIND_X * speed * dt) % WRAP;
		windZ = (windZ + WIND_Z * speed * dt) % WRAP;

		float base = coveragePercent / 100f;
		glUniform1i(uniShadows, shadows ? 1 : 0);
		glUniform1f(uniCoverage, base + (1 - base) * cloudCover);
		glUniform1f(uniWeather, cloudCover);
		// the shader adds camera-relative scene positions; take the scene origin and the drift away from the noise
		glUniform2f(uniOffset, (float) Math.floorMod(baseX, (long) WRAP) - (float) windX,
			(float) Math.floorMod(baseZ, (long) WRAP) - (float) windZ);

		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D, texNoise);
		glActiveTexture(GL_TEXTURE0);
	}

	static void unbind(int noiseUnit)
	{
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
	}

	void free()
	{
		if (texNoise != 0)
		{
			glDeleteTextures(texNoise);
			texNoise = 0;
		}
	}

	private static int createNoise()
	{
		byte[] data = noise(NOISE_SIZE, 4321);
		ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
		buf.put(data).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RG8, NOISE_SIZE, NOISE_SIZE, 0, GL_RG, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		// mipmaps keep distant clouds near the horizon from shimmering
		glGenerateMipmap(GL_TEXTURE_2D);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glBindTexture(GL_TEXTURE_2D, 0);
		return tex;
	}

	/**
	 * Two tiling noises, interleaved: r is the cloud shape, fractal value noise (soft masses) mixed with inverted
	 * fractal Worley noise (round puffs) and equalized so that a threshold of 1 - c covers a share c of the sky;
	 * g is the Worley noise alone, normalized, for eroding edges. Both start at 4 cells per repeat.
	 */
	static byte[] noise(int size, long seed)
	{
		Random rng = new Random(seed);
		float[] value = new float[size * size];
		float[] worley = new float[size * size];
		float amp = 1;
		for (int cells = 4; cells <= 32; cells *= 2, amp *= 0.5f)
		{
			float[] lattice = new float[cells * cells];
			for (int i = 0; i < lattice.length; ++i)
			{
				lattice[i] = rng.nextFloat();
			}
			float scale = (float) cells / size;
			for (int y = 0; y < size; ++y)
			{
				for (int x = 0; x < size; ++x)
				{
					value[y * size + x] += amp * sampleLattice(lattice, cells, x * scale, y * scale);
				}
			}
		}

		amp = 1;
		for (int cells = 4; cells <= 16; cells *= 2, amp *= 0.5f)
		{
			// one feature point per cell, in cell units
			float[] fx = new float[cells * cells], fy = new float[cells * cells];
			for (int i = 0; i < fx.length; ++i)
			{
				fx[i] = rng.nextFloat();
				fy[i] = rng.nextFloat();
			}
			float scale = (float) cells / size;
			for (int y = 0; y < size; ++y)
			{
				for (int x = 0; x < size; ++x)
				{
					float px = (x + 0.5f) * scale, py = (y + 0.5f) * scale;
					int cx = (int) Math.floor(px), cy = (int) Math.floor(py);
					float best = Float.MAX_VALUE;
					for (int oy = -1; oy <= 1; ++oy)
					{
						for (int ox = -1; ox <= 1; ++ox)
						{
							int i = Math.floorMod(cy + oy, cells) * cells + Math.floorMod(cx + ox, cells);
							float dx = cx + ox + fx[i] - px, dy = cy + oy + fy[i] - py;
							best = Math.min(best, dx * dx + dy * dy);
						}
					}
					worley[y * size + x] += amp * (1 - Math.min((float) Math.sqrt(best), 1));
				}
			}
		}

		normalize(value);
		normalize(worley);
		float[] shape = new float[value.length];
		for (int i = 0; i < shape.length; ++i)
		{
			shape[i] = value[i] * 0.55f + worley[i] * 0.45f;
		}
		equalize(shape);
		byte[] bytes = new byte[size * size * 2];
		for (int i = 0; i < value.length; ++i)
		{
			bytes[i * 2] = (byte) Math.round(shape[i] * 255);
			bytes[i * 2 + 1] = (byte) Math.round(worley[i] * 255);
		}
		return bytes;
	}

	/**
	 * Replace each value by its rank, 0..1, keeping the shapes but making every level equally common.
	 */
	private static void equalize(float[] v)
	{
		Integer[] order = new Integer[v.length];
		for (int i = 0; i < order.length; ++i)
		{
			order[i] = i;
		}
		java.util.Arrays.sort(order, (a, b) -> Float.compare(v[a], v[b]));
		for (int r = 0; r < order.length; ++r)
		{
			v[order[r]] = (float) r / (order.length - 1);
		}
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

	private static float sampleLattice(float[] l, int n, float x, float y)
	{
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
		float fx = smooth(x - x0), fy = smooth(y - y0);
		float a = lerp(at(l, n, x0, y0), at(l, n, x0 + 1, y0), fx);
		float b = lerp(at(l, n, x0, y0 + 1), at(l, n, x0 + 1, y0 + 1), fx);
		return lerp(a, b, fy);
	}

	private static float at(float[] l, int n, int x, int y)
	{
		return l[Math.floorMod(y, n) * n + Math.floorMod(x, n)];
	}

	private static float smooth(float t)
	{
		return t * t * (3 - 2 * t);
	}

	private static float lerp(float a, float b, float t)
	{
		return a + (b - a) * t;
	}
}
