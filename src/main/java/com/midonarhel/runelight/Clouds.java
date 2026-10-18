package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.util.Random;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Volumetric clouds in the sky, drawn inline in the composite's sky branch (clouds.glsl), plus optional cloud
 * shadows on the ground. This class owns the tiling cloud noise texture and the per-frame cloud uniforms: how
 * much of the sky is covered (the configured clear-weather coverage, closing up as rain or snow comes in) and
 * the wind drift, for the composite and for the sky cache's captures (SkyCache.java).
 */
class Clouds
{
	private static final int NOISE_SIZE = 128;
	// share of ragged value noise in the cloud shape, the rest is round Worley puffs
	private static final float SHAPE_VALUE = 0.65f;
	// world units per repeat of the noise; must match CLOUD_TILE in clouds.glsl
	private static final double TILE = 32768;
	// the shader samples at 1/4, 1 and 3 times the tile frequency, so the offset can wrap every 4 tiles
	private static final double WRAP = TILE * 4;
	// world units per second at 100% speed: in calm air, and added per unit of the wind's (eased, gustless) mean,
	// so a gale drives them about three times as fast as a calm day; the wind's direction (Wind.java)
	private static final float WIND_SPEED_CALM = 160;
	private static final float WIND_SPEED = 320;

	/** Uniform locations of one program that draws clouds: the composite, and the sky cache's capture. */
	static final class Locations
	{
		int enabled, shadows, coverage, weather, offset, detail, type;
		int base, field, fieldRect, fieldCells;
		int eye, ground;
	}

	private final Locations composite = new Locations();
	private int texNoise;
	private double windX, windZ;
	private long lastNanos;

	// this frame's values, from update()
	private boolean enabled, shadows;
	private float coverage, weather, detail, base;
	private WeatherField field;
	private float offsetX, offsetZ;
	private double originX, originZ;
	// the weather's cloud look (wispy, dark) and the wind that carries them
	private float wisp, dark;
	private Wind wind;
	// where the clouds are seen from (clouds.glsl's cloudEye): the focal point relative to the camera, the drawn
	// world's radius and the camera's height above the focal point, 0 for a level horizon; the focal point's y
	private float eyeX, eyeZ, eyeRadius, eyeHeight, ground;
	private double focalX, focalZ;

	/**
	 * The weather's look of the clouds and its wind; call before {@link #update}.
	 *
	 * @param wisp how wispy and high, 0..1
	 * @param dark darkness offset, -1 bright .. 1 storm dark
	 */
	void setWeather(float wisp, float dark, Wind wind)
	{
		this.wisp = wisp;
		this.dark = dark;
		this.wind = wind;
	}

	/**
	 * Where the clouds are seen from this frame: above the camera's focal point, so that the camera swinging around
	 * the player and rising as it pitches leaves them in place. With {@code horizonAtEdge} the sky's horizon is put
	 * where the drawn world (EdgeFade) ends. World coordinates of the scene, y down.
	 */
	void setEye(float cameraX, float cameraY, float cameraZ, float focalX, float focalY, float focalZ, float radius,
		boolean horizonAtEdge)
	{
		this.focalX = focalX;
		this.focalZ = focalZ;
		eyeX = focalX - cameraX;
		eyeZ = focalZ - cameraZ;
		eyeRadius = radius;
		eyeHeight = horizonAtEdge ? Math.max(focalY - cameraY, 0) : 0;
		ground = focalY;
	}

	/**
	 * Look up the composite program's cloud uniforms. Call with each newly compiled composite.
	 */
	void locateComposite(int program, int noiseUnit)
	{
		locate(composite, program, noiseUnit);
	}

	static void locate(Locations l, int program, int noiseUnit)
	{
		l.enabled = glGetUniformLocation(program, "cloudsEnabled");
		l.shadows = glGetUniformLocation(program, "cloudShadowsEnabled");
		l.coverage = glGetUniformLocation(program, "cloudCoverage");
		l.weather = glGetUniformLocation(program, "cloudWeather");
		l.offset = glGetUniformLocation(program, "cloudOffset");
		l.detail = glGetUniformLocation(program, "cloudDetail");
		l.type = glGetUniformLocation(program, "cloudType");
		l.base = glGetUniformLocation(program, "cloudBaseCoverage");
		l.field = glGetUniformLocation(program, "cloudField");
		l.fieldRect = glGetUniformLocation(program, "cloudFieldRect");
		l.fieldCells = glGetUniformLocation(program, "cloudFieldCells");
		l.eye = glGetUniformLocation(program, "cloudEye");
		l.ground = glGetUniformLocation(program, "cloudGround");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "cloudNoise"), noiseUnit);
		glUseProgram(0);
	}

	/**
	 * Advance the wind and decide this frame's cloud values. Call once per frame, before {@link #apply}.
	 *
	 * @param cloudCover Weather.cloudCover(), 0 clear to 1 overcast
	 * @param baseX world x of the scene's origin, in world units, so clouds stay put when the scene reloads
	 * @param field the weather over the scene: rain clouds over rainy regions, clear sky over clear ones
	 */
	void update(boolean enabled, boolean shadows, int coveragePercent, int speedPercent, int detailPercent,
		float cloudCover, long baseX, long baseZ, WeatherField field)
	{
		this.field = field;
		long now = System.nanoTime();
		float dt = lastNanos == 0 ? 0 : Math.min((now - lastNanos) / 1e9f, 0.25f);
		lastNanos = now;

		this.enabled = enabled;
		this.shadows = shadows;
		float mean = wind != null ? wind.getMean() : 0.25f;
		float dx = wind != null ? wind.getDirX() : 0.87f, dz = wind != null ? wind.getDirZ() : 0.49f;
		float speed = (WIND_SPEED_CALM + WIND_SPEED * mean) * speedPercent / 100f;
		windX = (windX + dx * speed * dt) % WRAP;
		windZ = (windZ + dz * speed * dt) % WRAP;

		base = coveragePercent / 100f;
		coverage = base + (1 - base) * cloudCover;
		weather = cloudCover;
		detail = detailPercent / 100f;
		// the shader adds camera-relative scene positions; take the scene origin and the drift away from the noise
		offsetX = (float) Math.floorMod(baseX, (long) WRAP) - (float) windX;
		offsetZ = (float) Math.floorMod(baseZ, (long) WRAP) - (float) windZ;
		originX = baseX - windX;
		originZ = baseZ - windZ;
	}

	/**
	 * With a cloud drawing program in use: set this frame's uniforms.
	 */
	void apply(Locations l)
	{
		glUniform1i(l.enabled, enabled ? 1 : 0);
		glUniform1i(l.shadows, shadows ? 1 : 0);
		glUniform1f(l.coverage, coverage);
		glUniform1f(l.weather, weather);
		glUniform1f(l.detail, detail);
		glUniform2f(l.type, wisp, dark);
		glUniform2f(l.offset, offsetX, offsetZ);
		glUniform1f(l.base, base);
		glUniform4f(l.eye, eyeX, eyeZ, eyeRadius, eyeHeight);
		glUniform1f(l.ground, ground);
		if (field != null)
		{
			field.applyClouds(l.field, l.fieldRect, l.fieldCells);
		}
		else
		{
			glUniform4f(l.fieldRect, 0, 0, 0, 0);
		}
	}

	/**
	 * Bind the noise to {@code noiseUnit} (its 2D target).
	 */
	void bindNoise(int noiseUnit)
	{
		if (texNoise == 0)
		{
			texNoise = createNoise();
		}
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D, texNoise);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * With the composite program in use: set its uniforms and bind the noise. {@link #update} first.
	 */
	void bindComposite(int noiseUnit)
	{
		apply(composite);
		if (enabled)
		{
			bindNoise(noiseUnit);
		}
	}

	boolean isEnabled()
	{
		return enabled;
	}

	float getCoverage()
	{
		return coverage;
	}

	/** Scene xz the clouds are seen from (setEye): the camera's focal point. */
	double getEyeX()
	{
		return focalX;
	}

	double getEyeZ()
	{
		return focalZ;
	}

	/** World xz the clouds have drifted to, plus the scene origin: how far they have moved, in world units. */
	double getOriginX()
	{
		return originX;
	}

	double getOriginZ()
	{
		return originZ;
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
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, NOISE_SIZE, NOISE_SIZE, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
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
	 * Four tiling noises, interleaved, all starting at a few cells per repeat:
	 * r is the cloud shape, fractal value noise (ragged masses) mixed with inverted fractal Worley noise (puffs) and
	 * equalized so that a threshold of 1 - c covers a share c of the sky;
	 * g is the Worley noise alone, for billowy erosion of the edges;
	 * b is finer fractal value noise, for ragged, wispy erosion;
	 * a is very coarse value noise: where the sky has broken, wispy clouds rather than heaped ones.
	 */
	static byte[] noise(int size, long seed)
	{
		Random rng = new Random(seed);
		float[] value = fractalValue(size, rng, 4, 32);
		float[] worley = new float[size * size];
		float amp = 1;
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
		float[] fine = fractalValue(size, rng, 8, 64);
		float[] coarse = fractalValue(size, rng, 2, 4);

		normalize(value);
		normalize(worley);
		normalize(fine);
		equalize(coarse);
		float[] shape = new float[value.length];
		for (int i = 0; i < shape.length; ++i)
		{
			shape[i] = value[i] * SHAPE_VALUE + worley[i] * (1 - SHAPE_VALUE);
		}
		equalize(shape);
		byte[] bytes = new byte[size * size * 4];
		for (int i = 0; i < value.length; ++i)
		{
			bytes[i * 4] = (byte) Math.round(shape[i] * 255);
			bytes[i * 4 + 1] = (byte) Math.round(worley[i] * 255);
			bytes[i * 4 + 2] = (byte) Math.round(fine[i] * 255);
			bytes[i * 4 + 3] = (byte) Math.round(coarse[i] * 255);
		}
		return bytes;
	}

	/** Tiling fractal value noise from minCells to maxCells per repeat, halving the amplitude each octave. */
	private static float[] fractalValue(int size, Random rng, int minCells, int maxCells)
	{
		float[] out = new float[size * size];
		float amp = 1;
		for (int cells = minCells; cells <= maxCells; cells *= 2, amp *= 0.5f)
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
					out[y * size + x] += amp * sampleLattice(lattice, cells, x * scale, y * scale);
				}
			}
		}
		return out;
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
