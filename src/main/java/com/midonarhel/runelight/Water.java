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
 * surface mesh and its pass ({@link WaterSurface}), the tiling noise and caustics, and the water's clock. The composite
 * shades the water (water.glsl) from the surface pass's target.
 */
class Water
{
	private static final int NOISE_SIZE = 256;
	private static final long NOISE_SEED = 9157;
	// 117 HD's caustics map (layer 1 of the noise texture), its red, green and blue taken this many texels apart
	// diagonally (their aberration of 0.005 per repeat): colored fringes for one tap per copy
	private static final String CAUSTICS_MAP = "water_caustics.jpg";
	private static final float CAUSTIC_ABERRATION = 1.28f;
	// the shader's time wraps after this many seconds; every periodic motion in the water shaders repeats within it
	private static final double TIME_WRAP = 3840;

	/**
	 * This frame's clock and settings for the surface pass.
	 */
	static final class Frame
	{
		float time, rain, skyDepth;
		boolean outdoors, caustics, zeroToOne;
		// 0 off, then WaterDebug's views
		int debug;
		float[] casterDir, invViewProj;
	}

	private int texData, texNoise;
	// the water's own clock: System.nanoTime is monotonic and fine grained everywhere, where currentTimeMillis can
	// tick in 15.6 ms steps on Windows (the water stutters) and jumps when the system clock is adjusted
	private final long startNanos = System.nanoTime();
	private boolean hasData;
	// whether any of the water is in view this frame (drawSurface), and the pixels it can cover then (x0, y0, x1, y1,
	// the ends exclusive, WaterSurface.anyVisible): the passes after it skip the rest
	private boolean visible;
	private final int[] rect = new int[4];
	// the map of the scene drawn now, for the scene dump
	private WaterMap current;
	private final WaterSurface surface = new WaterSurface();
	private final Frame frame = new Frame();

	private int uniAoOn, uniAoRect;

	// A tile's look (WaterSurface.build): its water type and the game's water texture, as a key; up to SLOTS of them a
	// scene (water.glsl's WATER_SLOTS), whose types the composite gets as uniforms
	static final int SLOTS = 32;
	private final int[] slotTypes = new int[SLOTS];

	static int slotKey(int texture, int type)
	{
		return (texture & 0xFFFF) << 3 | type & 7;
	}

	static int slotType(int key)
	{
		return key & 7;
	}

	static int slotTexture(int key)
	{
		int t = key >> 3 & 0xFFFF;
		return t == 0xFFFF ? -1 : t;
	}

	/** A composite program's water uniforms. */
	static final class CompositeUniforms
	{
		private int enabled = -1, time = -1, reflections = -1, caustics = -1, debug = -1, slotType = -1,
			rect = -1, fakeOn = -1;

		/** Look them up and point the program's samplers at the units. Call with each newly compiled composite. */
		void locate(int program, int surfaceUnit, int shadedUnit)
		{
			enabled = glGetUniformLocation(program, "waterEnabled");
			time = glGetUniformLocation(program, "waterTime");
			reflections = glGetUniformLocation(program, "waterReflections");
			caustics = glGetUniformLocation(program, "waterCaustics");
			debug = glGetUniformLocation(program, "waterDebug");
			slotType = glGetUniformLocation(program, "waterSlotType");
			rect = glGetUniformLocation(program, "waterRect");
			fakeOn = glGetUniformLocation(program, "waterFakeOn");
			glUseProgram(program);
			glUniform1i(glGetUniformLocation(program, "waterSurface"), surfaceUnit);
			glUniform1i(glGetUniformLocation(program, "waterShaded"), shadedUnit);
			glUseProgram(0);
		}
	}

	/**
	 * Look up the AO program's water uniforms. Call with each newly compiled AO program.
	 */
	void locateAo(int program, int surfaceUnit)
	{
		uniAoOn = glGetUniformLocation(program, "waterAoOn");
		uniAoRect = glGetUniformLocation(program, "waterAoRect");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "waterAoSurface"), surfaceUnit);
		glUseProgram(0);
	}

	/**
	 * With the AO program in use, after {@link #drawSurface}: the surface bound so the AO fades out under the water.
	 */
	void bindAo(RuneLightConfig config, int surfaceUnit)
	{
		boolean enabled = enabled(config);
		glUniform1i(uniAoOn, enabled ? 1 : 0);
		if (enabled)
		{
			glUniform4i(uniAoRect, rect[0], rect[1], rect[2], rect[3]);
			glActiveTexture(GL_TEXTURE0 + surfaceUnit);
			glBindTexture(GL_TEXTURE_2D, surface.texture());
			glActiveTexture(GL_TEXTURE0);
		}
	}

	/**
	 * With a program in use that has a {@code waterRect} uniform at {@code uniRect} (and the surface sampler on the
	 * unit {@link #bind} used): the pixels the surface covers, none without water.
	 */
	void setRect(RuneLightConfig config, int uniRect)
	{
		boolean enabled = enabled(config);
		glUniform4i(uniRect, enabled ? rect[0] : 0, enabled ? rect[1] : 0, enabled ? rect[2] : 0,
			enabled ? rect[3] : 0);
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

	/** A loaded scene's water and its surface mesh, built off the GL thread (SceneLoader). */
	static final class Loaded
	{
		final WaterMap map;
		final WaterSurface.Mesh mesh;

		private Loaded(WaterMap map, WaterSurface.Mesh mesh)
		{
			this.map = map;
			this.mesh = mesh;
		}
	}

	/** The water of a scene being loaded (any thread), map null for none: its surface mesh is built here. */
	static Loaded prepare(WaterMap map)
	{
		return new Loaded(map, WaterSurface.build(map));
	}

	/** The water of the scene drawn now, null without; any thread. */
	WaterMap map()
	{
		return current;
	}

	/**
	 * The loaded scene became the current one: upload its water. GL thread.
	 */
	void swap(Loaded loaded)
	{
		WaterMap map = loaded.map;
		WaterSurface.Mesh mesh = loaded.mesh;

		hasData = map != null && map.hasWater && map.texture != null;
		current = hasData ? map : null;
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
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, WaterMap.CORNERS * WaterMap.PAGES, WaterMap.CORNERS, 0, GL_RGBA, GL_FLOAT, buf);
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
	 * Move the water's clock on to this frame and draw its surface into the surface target (see
	 * {@link WaterSurface#draw}), before the composite. Leaves the framebuffer and VAO bindings changed.
	 *
	 * @param rain  0-1 rain falling on open water
	 * @param focusX where the camera looks (the drawn world's middle, EdgeFade): the water is drawn around it
	 * @param reach  radius of the drawn world around it, world units
	 * @param casterDir      toward the light that casts shadows (the caustics follow it)
	 * @param depthZeroToOne whether glClipControl maps depth to 0-1 (frame.glsl)
	 */
	void drawSurface(RuneLightConfig config, float rain, boolean outdoors, int width, int height, int[] viewport,
		float[] viewProj, float[] invViewProj, float camX, float camY, float camZ, float focusX, float focusZ, float reach,
		float[] casterDir, boolean depthZeroToOne, int dataUnit, int noiseUnit)
	{
		visible = false;
		// the clock runs without water in the scene too: fake water (water.glsl waterFake) moves by it
		long now = System.nanoTime();
		frame.time = (float) (((now - startNanos) / 1e9) % TIME_WRAP);
		if (!config.water() || !hasData)
		{
			return;
		}
		if (texNoise == 0)
		{
			texNoise = createNoise();
		}
		Frame f = frame;
		f.rain = rain;
		f.outdoors = outdoors;
		f.debug = config.waterDebug().ordinal();
		// the sky's depth: 0 with glClipControl, the middle of the range without
		f.skyDepth = depthZeroToOne ? 0 : 0.5f;
		f.zeroToOne = depthZeroToOne;
		f.caustics = config.waterCaustics() && outdoors;
		f.casterDir = casterDir;
		f.invViewProj = invViewProj;

		glActiveTexture(GL_TEXTURE0 + dataUnit);
		glBindTexture(GL_TEXTURE_2D, texData);
		glActiveTexture(GL_TEXTURE0 + noiseUnit);
		glBindTexture(GL_TEXTURE_2D_ARRAY, texNoise);
		glActiveTexture(GL_TEXTURE0);
		// no water in view: the surface, its shading and the composite's water are all skipped this frame
		visible = surface.anyVisible(viewProj, viewport, camX, camY, camZ, focusX, focusZ, reach, rect);
		if (visible)
		{
			surface.draw(width, height, viewport, viewProj, camX, camY, camZ, f, rect);
		}
	}

	/**
	 * Bind the surface for the composite, after {@link #drawSurface}, before its half resolution pass
	 * ({@link #halfRect}).
	 */
	void bind(RuneLightConfig config, int surfaceUnit)
	{
		if (enabled(config))
		{
			glActiveTexture(GL_TEXTURE0 + surfaceUnit);
			glBindTexture(GL_TEXTURE_2D, surface.texture());
			glActiveTexture(GL_TEXTURE0);
		}
	}

	/**
	 * With a composite program in use: set this frame's uniforms on it.
	 */
	void setComposite(CompositeUniforms u, RuneLightConfig config)
	{
		boolean enabled = enabled(config);
		glUniform1i(u.enabled, enabled ? 1 : 0);
		// fake water is drawn wherever the model has it, with the water setting on, in view of real water or not
		glUniform1i(u.fakeOn, config.water() ? 1 : 0);
		glUniform1f(u.time, frame.time);
		glUniform1i(u.reflections, config.waterReflections() ? 1 : 0);
		if (!enabled)
		{
			return;
		}
		glUniform1i(u.caustics, config.waterCaustics() ? 1 : 0);
		glUniform1i(u.debug, config.waterDebug().ordinal());
		glUniform4i(u.rect, rect[0], rect[1], rect[2], rect[3]);
		WaterSurface.Mesh m = surface.mesh();
		for (int i = 0; i < m.slots; ++i)
		{
			slotTypes[i] = slotType(m.slotKeys[i]);
		}
		glUniform1iv(u.slotType, slotTypes);
	}

	/** Whether the water is drawn this frame: its passes run. */
	boolean enabled(RuneLightConfig config)
	{
		return visible && config.water() && hasData && surface.texture() != 0 && surface.mesh() != null;
	}

	/**
	 * The texels of the composite's half resolution water pass (water.glsl's waterShadeHalf) with a pixel in the
	 * surface's rect, which the pass is scissored to: x0, y0 to x1, y1 exclusive into r. Texel x reads pixels from
	 * x * halfRatio (halfmap.glsl's halfToFull), 2 at full render scale and 1 at 50% and below.
	 */
	void halfRect(int[] r, float halfRatio)
	{
		r[0] = (int) Math.floor(rect[0] / halfRatio);
		r[1] = (int) Math.floor(rect[1] / halfRatio);
		r[2] = (int) Math.ceil(rect[2] / halfRatio);
		r[3] = (int) Math.ceil(rect[3] / halfRatio);
	}

	/**
	 * After the composite: its two water units, which the surface pass used for the data and the noise.
	 */
	static void unbind(int surfaceUnit, int shadedUnit)
	{
		glActiveTexture(GL_TEXTURE0 + surfaceUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0 + shadedUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
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

	// Layer 0 the tiling noise (its height jitters the flow phases), layer 1 the caustics: one sampler, the composite
	// has none to spare
	private static int createNoise()
	{
		byte[] noise = noise(NOISE_SIZE, NOISE_SEED);
		byte[] caustics = caustics(NOISE_SIZE);
		ByteBuffer buf = BufferUtils.createByteBuffer(noise.length + caustics.length);
		buf.put(noise).put(caustics).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, NOISE_SIZE, NOISE_SIZE, 2, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
		// mipmaps calm the caustics in the distance instead of letting them sparkle
		glGenerateMipmap(GL_TEXTURE_2D_ARRAY);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_S, GL_REPEAT);
		glTexParameteri(GL_TEXTURE_2D_ARRAY, GL_TEXTURE_WRAP_T, GL_REPEAT);
		glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
		return tex;
	}

	// how far the noise's sine waves turn from +x either way, radians
	static final float NOISE_SPREAD = 0.6f;

	/**
	 * Tiling noise, RGBA: b a height 0-1, the rest 0. The height is a sum of sine waves with whole numbers of cycles
	 * across the texture, so it tiles, running within NOISE_SPREAD of +x with amplitude falling with frequency.
	 */
	static byte[] noise(int size, long seed)
	{
		Random rng = new Random(seed);
		int n = size * size;
		float[] h = new float[n];
		waves(rng, size, 56, 2, 22, 1.7f, NOISE_SPREAD, h);
		normalize(h);
		byte[] out = new byte[n * 4];
		for (int i = 0; i < n; ++i)
		{
			out[i * 4 + 2] = (byte) Math.round(h[i] * 255);
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
	 * Add {@code count} sine waves of kMin to kMax cycles across the texture, amplitude falling as k^-falloff, to h.
	 */
	static void waves(Random rng, int size, int count, int kMin, int kMax, float falloff, float spread, float[] h)
	{
		float[] sinX = new float[size], cosX = new float[size], sinY = new float[size], cosY = new float[size];
		for (int w = 0; w < count; ++w)
		{
			// log-uniform frequency, a direction within spread of +x either way (pi: any), snapped to whole cycles so
			// it tiles
			double k = kMin * Math.pow((double) kMax / kMin, rng.nextDouble());
			double angle = (rng.nextDouble() * 2 - 1) * spread;
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
			for (int y = 0; y < size; ++y)
			{
				for (int x = 0; x < size; ++x)
				{
					// sin(a + b) from the row and column tables
					h[y * size + x] += amp * (sinX[x] * cosY[y] + cosX[x] * sinY[y]);
				}
			}
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
}
