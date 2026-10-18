package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The water surface drawn by the composite pass (water.glsl): owns the scene's water data texture (from
 * {@link WaterMap}, uploaded when the scene swaps in), the tiling ripple noise and the per-frame uniforms.
 */
class Water
{
	private static final int NOISE_SIZE = 256;
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
		byte[] data = noise(NOISE_SIZE, 9157);
		ByteBuffer buf = BufferUtils.createByteBuffer(data.length);
		buf.put(data).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, NOISE_SIZE, NOISE_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		// mipmaps calm the ripples in the distance instead of letting them sparkle
		glGenerateMipmap(GL_TEXTURE_2D);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glBindTexture(GL_TEXTURE_2D, 0);
		return tex;
	}

	/**
	 * Tiling ripple noise, RGBA: rg the slope of a smooth fractal height field (0.5 is flat, scaled so the steepest
	 * slope reaches 0 or 1), b that height, a an independent cellular noise for foam and sparkle.
	 */
	static byte[] noise(int size, long seed)
	{
		Random rng = new Random(seed);
		float[] h = new float[size * size];
		float amp = 1;
		for (int cells = 4; cells <= 32; cells *= 2, amp *= 0.55f)
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
					h[y * size + x] += amp * sample(lattice, cells, x * scale, y * scale);
				}
			}
		}
		normalize(h);

		float[] sx = new float[h.length], sy = new float[h.length];
		float max = 1e-6f;
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int i = y * size + x;
				sx[i] = h[y * size + (x + 1) % size] - h[y * size + (x + size - 1) % size];
				sy[i] = h[((y + 1) % size) * size + x] - h[((y + size - 1) % size) * size + x];
				max = Math.max(max, Math.max(Math.abs(sx[i]), Math.abs(sy[i])));
			}
		}

		// foam: inverted cellular noise, bubbly clumps
		float[] foam = new float[h.length];
		amp = 1;
		for (int cells = 8; cells <= 32; cells *= 2, amp *= 0.5f)
		{
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
					foam[y * size + x] += amp * (1 - Math.min((float) Math.sqrt(best), 1));
				}
			}
		}
		normalize(foam);

		byte[] out = new byte[h.length * 4];
		for (int i = 0; i < h.length; ++i)
		{
			out[i * 4] = (byte) Math.round((sx[i] / max * 0.5f + 0.5f) * 255);
			out[i * 4 + 1] = (byte) Math.round((sy[i] / max * 0.5f + 0.5f) * 255);
			out[i * 4 + 2] = (byte) Math.round(h[i] * 255);
			out[i * 4 + 3] = (byte) Math.round(foam[i] * 255);
		}
		return out;
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

	private static float sample(float[] l, int n, float x, float y)
	{
		int x0 = (int) Math.floor(x), y0 = (int) Math.floor(y);
		float fx = quintic(x - x0), fy = quintic(y - y0);
		float a = lerp(at(l, n, x0, y0), at(l, n, x0 + 1, y0), fx);
		float b = lerp(at(l, n, x0, y0 + 1), at(l, n, x0 + 1, y0 + 1), fx);
		return lerp(a, b, fy);
	}

	private static float at(float[] l, int n, int x, int y)
	{
		return l[Math.floorMod(y, n) * n + Math.floorMod(x, n)];
	}

	private static float quintic(float t)
	{
		return t * t * t * (t * (t * 6 - 15) + 10);
	}

	private static float lerp(float a, float b, float t)
	{
		return a + (b - a) * t;
	}
}
