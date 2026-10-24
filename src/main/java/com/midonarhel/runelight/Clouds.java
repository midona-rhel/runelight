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
	// sine of the elevation along clouds.glsl's cloudSkyDir at and below which the clouds are clear sky: the first
	// of CLOUD_HORIZON
	private static final double HORIZON = -0.02;
	// what skyRows allows for the GPU's rounding of the same rays, in the sine of their elevation
	private static final double ROWS_SLACK = 1e-3;

	/** Uniform locations of one program that draws clouds: the composite's, and the sky cache's capture. */
	static final class Locations
	{
		int enabled, shadows, coverage, weather, offset, detail, type;
		int base, field, fieldRect, fieldCells;
		int eye, ground;
	}

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
	 * The rows of the composite's half resolution cloud pass (composite_frag.glsl's cloudsHalf) that can hold cloud:
	 * the first in {@code rows[0]}, one past the last in {@code rows[1]}, the same for none. The pass is scissored to
	 * them over clear sky. Call after {@link #setEye}.
	 * <p>
	 * Conservative, so the scissor changes nothing: a texel holds cloud only if the ray through its center, dir, is
	 * above the clouds' horizon, -cloudSkyDir(dir).y > HORIZON (cloudLayer's first test). With y the normalized
	 * dir.y, cloudSkyDir gives (-y + below) / (1 + below), so that needs y < below * (1 - HORIZON) - HORIZON, which
	 * grows with below; where it leaves dir as it is (no height or radius, straight up or down), y < -HORIZON, less.
	 * So a row can only hold cloud if the lowest y of its rays is under the bound for the largest below of its rays
	 * (rowBelow). The texel centers of a row lie on a segment of the near plane, and the lowest y of the rays through
	 * a segment is at one of its ends or where it turns (lowestRay).
	 *
	 * @param invViewProj the composite's invViewProj (camera relative)
	 * @param viewport the composite's sceneViewport: the game viewport inside the targets, pixels
	 * @param width the half resolution target's width, texels
	 * @param height its height
	 * @param halfRatio scene pixels per texel
	 */
	void skyRows(int[] rows, float[] invViewProj, int[] viewport, int width, int height, float halfRatio)
	{
		// texel centers in scene pixels, as cloudsHalf has them, to NDC (frame.glsl's reconstructPosition)
		double x0 = (0.5 * halfRatio - viewport[0]) / viewport[2] * 2 - 1;
		double x1 = ((width - 0.5) * halfRatio - viewport[0]) / viewport[2] * 2 - 1;
		double[] a = new double[3], b = new double[3];
		int first = -1, end = 0;
		for (int j = 0; j < height; ++j)
		{
			double y = ((j + 0.5) * halfRatio - viewport[1]) / viewport[3] * 2 - 1;
			nearPoint(a, invViewProj, x0, y);
			nearPoint(b, invViewProj, x1, y);
			if (lowestRay(a, b) < rowBelow(a, b) * (1 - HORIZON) - HORIZON + ROWS_SLACK)
			{
				first = first < 0 ? j : first;
				end = j + 1;
			}
		}
		// a row more each side for the GPU's rounding of the texel centers
		rows[0] = first < 0 ? 0 : Math.max(first - 1, 0);
		rows[1] = first < 0 ? 0 : Math.min(end + 1, height);
	}

	/**
	 * The largest below (clouds.glsl's cloudSkyDir) of the rays through the segment from a to b: h / sqrt(h * h +
	 * far * far) for the eye's height h, at the smallest distance far to the edge of the drawn world along their
	 * headings. far = oc + sqrt(r * r - |e| * |e| + oc * oc), at least 1, for the focal point e from the camera and
	 * oc = dot(heading, e), grows with oc while the camera is inside the edge; the headings sweep the shorter arc
	 * between those of the ends (the segment's shadow on the ground), over which oc is least at an end or at the
	 * heading away from e, -|e|. With the camera outside the edge far can be anything down to 1.
	 */
	private double rowBelow(double[] a, double[] b)
	{
		if (eyeHeight <= 0 || eyeRadius <= 0)
		{
			return 0;
		}
		double e = Math.hypot(eyeX, eyeZ);
		double inside = (double) eyeRadius * eyeRadius - e * e;
		double far = 1;
		if (inside > 0)
		{
			double la = Math.hypot(a[0], a[2]), lb = Math.hypot(b[0], b[2]);
			double oc = -e;
			if (la > 1e-9 && lb > 1e-9)
			{
				double ax = a[0] / la, az = a[2] / la, bx = b[0] / lb, bz = b[2] / lb;
				// the heading away from e is between the ends: on the inner side of both, the arc short of a half turn
				double cross = ax * bz - az * bx;
				boolean away = (ax * eyeZ - az * eyeX <= 0) == (cross >= 0)
					&& (eyeX * bz - eyeZ * bx <= 0) == (cross >= 0)
					|| ax * bx + az * bz < 0 && Math.abs(cross) < 1e-6;
				if (!away)
				{
					oc = Math.min(ax * eyeX + az * eyeZ, bx * eyeX + bz * eyeZ);
				}
			}
			far = Math.max(oc + Math.sqrt(inside + oc * oc), 1);
		}
		return eyeHeight / Math.hypot(eyeHeight, far);
	}

	/** The camera relative point on the near plane at NDC x, y, into p. */
	private static void nearPoint(double[] p, float[] m, double x, double y)
	{
		// column major; the near plane is at NDC z 1 in both depth conventions (reverse z)
		double w = m[3] * x + m[7] * y + m[11] + m[15];
		for (int i = 0; i < 3; ++i)
		{
			p[i] = (m[i] * x + m[4 + i] * y + m[8 + i] + m[12 + i]) / w;
		}
	}

	/**
	 * The lowest normalized y of the rays from the camera through the segment from a to b: y(t) = (a + t d).y /
	 * |a + t d| with d = b - a, whose derivative is zero only where its numerator, linear in t, is.
	 */
	private static double lowestRay(double[] a, double[] b)
	{
		double dx = b[0] - a[0], dy = b[1] - a[1], dz = b[2] - a[2];
		double aa = a[0] * a[0] + a[1] * a[1] + a[2] * a[2];
		double ad = a[0] * dx + a[1] * dy + a[2] * dz;
		double dd = dx * dx + dy * dy + dz * dz;
		double lowest = Math.min(a[1] / Math.sqrt(aa), b[1] / Math.sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]));
		double den = dy * ad - a[1] * dd;
		if (den != 0)
		{
			double t = (a[1] * ad - dy * aa) / den;
			if (t > 0 && t < 1)
			{
				lowest = Math.min(lowest, (a[1] + t * dy) / Math.sqrt(aa + 2 * t * ad + t * t * dd));
			}
		}
		return lowest;
	}

	/** Look up a program's cloud uniforms into l and point its noise sampler at noiseUnit. */
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
