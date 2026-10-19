package com.midonarhel.runelight;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.Random;
import javax.imageio.ImageIO;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The scene's water: owns the water data texture (from {@link WaterMap}, uploaded when the scene swaps in), the
 * surface mesh and its pass ({@link WaterSurface}), the tiling wave noise and caustics, and the per-frame motion. The
 * composite shades the water (water.glsl) from the surface pass's target.
 */
class Water
{
	private static final int NOISE_SIZE = 256;
	private static final long NOISE_SEED = 9157;
	private static final double SLOPE_RANGE = 2.8;
	// 117 HD's caustics map (layer 1 of the noise texture), its red, green and blue taken this many texels apart
	// diagonally (their aberration of 0.005 per repeat): colored fringes for one tap per copy
	private static final String CAUSTICS_MAP = "water_caustics.jpg";
	private static final float CAUSTIC_ABERRATION = 1.28f;
	// the shader's time wraps after this many seconds; every periodic motion in the water shaders repeats within it
	private static final double TIME_WRAP = 3840;
	// how far the waves drift with the wind, world units per second (calm, full wind), before the shaders' per-type
	// and config speed; the swell's own drift
	private static final double DRIFT_CALM = 5, DRIFT_WIND = 18;
	private static final double SWELL_CALM = 4, SWELL_WIND = 10;
	// the drift distances wrap at a whole number of every repeat they are used with in the shaders (wave scale 560 at
	// any per-type speed of 0.35 or 0.45 and any whole config percent; swell scale 1650): no jump at the wrap
	private static final double DRIFT_WRAP = 560 * 100 * 20, SWELL_WRAP = 1650 * 400;
	// seconds over which the water follows a change in the wind's strength: it jumps under roofs and in gusts
	private static final double STRENGTH_EASE = 1.5;

	/**
	 * This frame's motion and settings for the surface pass.
	 */
	static final class Frame
	{
		float time, windX, windZ, strength, driftX, driftZ, swellX, swellZ, waves, speed, displace, rain, skyDepth;
		boolean outdoors, debug;
	}

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
	private WaterSurface.Mesh pendingMesh;
	private boolean pendingSet;
	private final WaterSurface surface = new WaterSurface();
	private final Frame frame = new Frame();

	private int uniEnabled, uniTime, uniReflections, uniClarity, uniCaustics, uniDebug;

	/**
	 * Look up the composite program's water uniforms. Call with each newly compiled composite.
	 */
	void locateComposite(int program, int surfaceUnit, int noiseUnit)
	{
		uniEnabled = glGetUniformLocation(program, "waterEnabled");
		uniTime = glGetUniformLocation(program, "waterTime");
		uniReflections = glGetUniformLocation(program, "waterReflections");
		uniClarity = glGetUniformLocation(program, "waterClarity");
		uniCaustics = glGetUniformLocation(program, "waterCaustics");
		uniDebug = glGetUniformLocation(program, "waterDebug");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "waterSurface"), surfaceUnit);
		glUniform1i(glGetUniformLocation(program, "waterNoise"), noiseUnit);
		glUseProgram(0);
	}

	/**
	 * The surface pass's program, newly compiled (0 to drop it).
	 */
	void setSurfaceProgram(int program, int depthUnit, int dataUnit, int noiseUnit)
	{
		surface.freeProgram();
		if (program != 0)
		{
			surface.setProgram(program, depthUnit, dataUnit, noiseUnit);
		}
	}

	void freeSurfaceProgram()
	{
		surface.freeProgram();
	}

	/**
	 * The water of a scene being loaded (any thread); null for none. Takes effect at {@link #swap}. Builds its
	 * surface mesh here, off the GL thread.
	 */
	void sceneLoaded(WaterMap map)
	{
		WaterSurface.Mesh mesh = WaterSurface.build(map);
		synchronized (this)
		{
			pending = map;
			pendingMesh = mesh;
			pendingSet = true;
		}
	}

	/**
	 * The loaded scene became the current one: upload its water. GL thread.
	 */
	void swap()
	{
		WaterMap map;
		WaterSurface.Mesh mesh;
		synchronized (this)
		{
			if (!pendingSet)
			{
				return;
			}
			map = pending;
			mesh = pendingMesh;
			pending = null;
			pendingMesh = null;
			pendingSet = false;
		}

		hasData = map != null && map.hasWater && map.texture != null;
		surface.upload(hasData ? mesh : null);
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
	 * Move the water on to this frame and draw its surface into the surface target (see {@link WaterSurface#draw}),
	 * before the composite. Leaves the framebuffer and VAO bindings changed.
	 *
	 * @param wind  x, z direction the wind blows toward, and its strength 0-1
	 * @param rain  0-1 rain falling on open water
	 * @param focusX where the camera looks (the drawn world's middle, EdgeFade): the waves move around it
	 * @param reach  radius of the drawn world around it, world units
	 * @param depthZeroToOne whether glClipControl maps depth to 0-1 (frame.glsl)
	 */
	void drawSurface(RuneLightConfig config, float[] wind, float rain, boolean outdoors, int width, int height,
		int[] viewport, float[] viewProj, float camX, float camY, float camZ, float focusX, float focusZ, float reach,
		boolean depthZeroToOne, int dataUnit, int noiseUnit)
	{
		if (!config.water() || !hasData)
		{
			return;
		}
		if (texNoise == 0)
		{
			texNoise = createNoise();
		}
		long now = System.nanoTime();
		advance(wind, lastNanos == 0 ? 0 : (now - lastNanos) / 1e9);
		lastNanos = now;
		Frame f = frame;
		f.time = (float) (((now - startNanos) / 1e9) % TIME_WRAP);
		f.windX = wind[0];
		f.windZ = wind[1];
		f.strength = (float) strength;
		f.driftX = (float) driftX;
		f.driftZ = (float) driftZ;
		f.swellX = (float) swellX;
		f.swellZ = (float) swellZ;
		f.waves = config.waterWaves() / 100f;
		f.speed = config.waterFlowSpeed() / 100f;
		f.displace = config.waterMovingSurface() ? 1 : 0;
		f.rain = rain;
		f.outdoors = outdoors;
		f.debug = config.waterDebug();
		// the sky's depth: 0 with glClipControl, the middle of the range without
		f.skyDepth = depthZeroToOne ? 0 : 0.5f;

		glActiveTexture(GL_TEXTURE0 + dataUnit);
		glBindTexture(GL_TEXTURE_2D, texData);
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texNoise);
		glActiveTexture(GL_TEXTURE0);
		surface.draw(width, height, viewport, viewProj, camX, camY, camZ, focusX, focusZ, reach, f);
	}

	/**
	 * With the composite program in use: set this frame's uniforms and bind the surface and the noise.
	 */
	void bind(RuneLightConfig config, int surfaceUnit, int noiseUnit)
	{
		boolean enabled = config.water() && hasData && surface.texture() != 0 && texNoise != 0;
		glUniform1i(uniEnabled, enabled ? 1 : 0);
		if (!enabled)
		{
			return;
		}
		glUniform1f(uniTime, frame.time);
		glUniform1i(uniReflections, config.waterReflections() ? 1 : 0);
		glUniform1f(uniClarity, config.waterClarity() / 100f);
		glUniform1i(uniCaustics, config.waterCaustics() ? 1 : 0);
		glUniform1i(uniDebug, config.waterDebug() ? 1 : 0);

		glActiveTexture(GL_TEXTURE0 + surfaceUnit);
		glBindTexture(GL_TEXTURE_2D, surface.texture());
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
		surface.free();
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
		byte[] caustics = caustics(NOISE_SIZE);
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
	 * 117 HD's caustics map as RGBA: red, green and blue each the map shifted by {@link #CAUSTIC_ABERRATION} texels
	 * along a different diagonal (as their shader samples it per channel), alpha the map itself. A flat mid gray if it
	 * can't be read.
	 */
	static byte[] caustics(int size)
	{
		float[] map = new float[size * size];
		try (InputStream in = Water.class.getResourceAsStream(CAUSTICS_MAP))
		{
			BufferedImage img = in != null ? ImageIO.read(in) : null;
			if (img == null || img.getWidth() != size || img.getHeight() != size)
			{
				throw new IOException("unexpected caustics map");
			}
			for (int y = 0; y < size; ++y)
			{
				for (int x = 0; x < size; ++x)
				{
					map[y * size + x] = (img.getRGB(x, y) >> 8 & 0xFF) / 255f;
				}
			}
		}
		catch (IOException ex)
		{
			java.util.Arrays.fill(map, 0.2f);
		}
		return aberrate(map, size, CAUSTIC_ABERRATION);
	}

	static byte[] aberrate(float[] map, int size, float shift)
	{
		final float[][] offsets = {{shift, shift}, {shift, -shift}, {-shift, -shift}, {0, 0}};
		byte[] out = new byte[size * size * 4];
		for (int c = 0; c < 4; ++c)
		{
			float ox = offsets[c][0], oy = offsets[c][1];
			int x0 = (int) Math.floor(ox), y0 = (int) Math.floor(oy);
			float fx = ox - x0, fy = oy - y0;
			for (int y = 0; y < size; ++y)
			{
				int ya = Math.floorMod(y + y0, size) * size, yb = Math.floorMod(y + y0 + 1, size) * size;
				for (int x = 0; x < size; ++x)
				{
					int xa = Math.floorMod(x + x0, size), xb = Math.floorMod(x + x0 + 1, size);
					float v = (map[ya + xa] * (1 - fx) + map[ya + xb] * fx) * (1 - fy)
						+ (map[yb + xa] * (1 - fx) + map[yb + xb] * fx) * fy;
					out[(y * size + x) * 4 + c] = (byte) Math.round(Math.min(Math.max(v, 0f), 1f) * 255);
				}
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
