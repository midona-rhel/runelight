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
	// 117 HD's water normal map (layer 3)
	private static final String NORMAL_MAP = "water_normal.png";
	private static final float CAUSTIC_ABERRATION = 1.28f;
	// The ripple cells (layer 2): a jittered grid of CELLS x CELLS points, each the top of a round dome reaching
	// CELL_RADIUS of the grid spacing, the domes added up into soft rounded cells
	private static final long CELL_SEED = 41;
	static final int CELLS = 6;
	private static final float CELL_RADIUS = 0.85f;
	// the shader's time wraps after this many seconds; every periodic motion in the water shaders repeats within it
	private static final double TIME_WRAP = 3840;
	// how far the waves drift with the wind, world units per second (calm, full wind), before the shaders' per-type
	// and config speed
	private static final double DRIFT_CALM = 5, DRIFT_WIND = 18;
	// the drift wraps at a whole number of every repeat it is used with in the shaders (the normal map's 768, 1280 and
	// 1792, least common multiple 26880, at any per-type speed of 0.35 or 0.45 and any whole config percent)
	private static final double DRIFT_WRAP = 26880.0 * 100 * 20;
	// seconds over which the water follows a change in the wind's strength: it jumps under roofs and in gusts
	private static final double STRENGTH_EASE = 1.5;

	/**
	 * This frame's motion and settings for the surface pass.
	 */
	static final class Frame
	{
		float time, windX, windZ, strength, driftX, driftZ, waves, speed, rain, skyDepth;
		boolean outdoors, debug, caustics, zeroToOne;
		float[] casterDir, invViewProj;
	}

	private int texData, texNoise;
	// the water's own clock: System.nanoTime is monotonic and fine grained everywhere, where currentTimeMillis can
	// tick in 15.6 ms steps on Windows (the waves stutter) and jumps when the system clock is adjusted
	private final long startNanos = System.nanoTime();
	// The waves' drift, integrated each frame. Velocity times the time since start would move every wave a long way
	// whenever the wind changes the least bit, which it does all the time: the water jumped about.
	private long lastNanos;
	private double driftX, driftZ, strength;
	private boolean hasData;
	private WaterMap pending;
	private WaterSurface.Mesh pendingMesh;
	private boolean pendingSet;
	private final WaterSurface surface = new WaterSurface();
	private final Frame frame = new Frame();

	private int uniEnabled, uniTime, uniReflections, uniClarity, uniCaustics, uniDebug, uniPass, uniSlotColor,
		uniSlotType;

	// A tile's look (WaterSurface.build): its water type and the game's water texture, as a key; up to SLOTS of them a
	// scene (water.glsl's WATER_SLOTS), whose colors and types the composite gets as uniforms
	static final int SLOTS = 32;
	private final float[] slotColors = new float[SLOTS * 3];
	private final int[] slotTypes = new int[SLOTS];
	// linear, when the game's texture isn't loaded (yet), by type: fresh, ocean, dark ocean, pale ocean, swamp, cave
	private static final float[][] FALLBACK_COLOR = {
		{0, 0, 0}, {0.030f, 0.085f, 0.140f}, {0.025f, 0.070f, 0.135f}, {0.018f, 0.050f, 0.100f},
		{0.045f, 0.120f, 0.165f}, {0.025f, 0.045f, 0.015f}, {0.012f, 0.025f, 0.030f}};

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

	/**
	 * Look up the composite program's water uniforms. Call with each newly compiled composite.
	 */
	void locateComposite(int program, int surfaceUnit, int shadedUnit)
	{
		uniEnabled = glGetUniformLocation(program, "waterEnabled");
		uniTime = glGetUniformLocation(program, "waterTime");
		uniReflections = glGetUniformLocation(program, "waterReflections");
		uniClarity = glGetUniformLocation(program, "waterClarity");
		uniCaustics = glGetUniformLocation(program, "waterCaustics");
		uniDebug = glGetUniformLocation(program, "waterDebug");
		uniPass = glGetUniformLocation(program, "waterPass");
		uniSlotColor = glGetUniformLocation(program, "waterSlotColor");
		uniSlotType = glGetUniformLocation(program, "waterSlotType");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "waterSurface"), surfaceUnit);
		glUniform1i(glGetUniformLocation(program, "waterShaded"), shadedUnit);
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
	 * @param casterDir      toward the light that casts shadows (the caustics follow it)
	 * @param depthZeroToOne whether glClipControl maps depth to 0-1 (frame.glsl)
	 */
	void drawSurface(RuneLightConfig config, float[] wind, float rain, boolean outdoors, int width, int height,
		int[] viewport, float[] viewProj, float[] invViewProj, float camX, float camY, float camZ, float focusX,
		float focusZ, float reach, float[] casterDir, boolean depthZeroToOne, int dataUnit, int noiseUnit)
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
		f.waves = config.waterWaves() / 100f;
		f.speed = config.waterFlowSpeed() / 100f;
		f.rain = rain;
		f.outdoors = outdoors;
		f.debug = config.waterDebug();
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
		surface.draw(width, height, viewport, viewProj, camX, camY, camZ, focusX, focusZ, reach, f);
	}

	/**
	 * With the composite program in use: set this frame's uniforms and bind the surface. Then {@link #shadeHalf}.
	 */
	void bind(RuneLightConfig config, int surfaceUnit)
	{
		boolean enabled = enabled(config);
		glUniform1i(uniEnabled, enabled ? 1 : 0);
		glUniform1i(uniPass, 0);
		if (!enabled)
		{
			return;
		}
		glUniform1f(uniTime, frame.time);
		glUniform1i(uniReflections, config.waterReflections() ? 1 : 0);
		glUniform1f(uniClarity, config.waterClarity() / 100f);
		glUniform1i(uniCaustics, config.waterCaustics() ? 1 : 0);
		glUniform1i(uniDebug, config.waterDebug() ? 1 : 0);
		WaterSurface.Mesh m = surface.mesh();
		float[] albedo = GiVoxels.textureAlbedo;
		for (int i = 0; i < m.slots; ++i)
		{
			int type = slotType(m.slotKeys[i]);
			int tex = slotTexture(m.slotKeys[i]);
			slotTypes[i] = type;
			boolean known = albedo != null && tex >= 0 && tex * 3 + 2 < albedo.length && albedo[tex * 3] >= 0;
			for (int c = 0; c < 3; ++c)
			{
				slotColors[i * 3 + c] = known ? albedo[tex * 3 + c] : FALLBACK_COLOR[Math.min(type, 6)][c];
			}
		}
		glUniform3fv(uniSlotColor, slotColors);
		glUniform1iv(uniSlotType, slotTypes);

		glActiveTexture(GL_TEXTURE0 + surfaceUnit);
		glBindTexture(GL_TEXTURE_2D, surface.texture());
		glActiveTexture(GL_TEXTURE0);
	}

	private boolean enabled(RuneLightConfig config)
	{
		return config.water() && hasData && surface.texture() != 0 && surface.mesh() != null;
	}

	/**
	 * With the composite program in use and everything bound for it (after {@link #bind}): its half resolution water
	 * pass into the shaded target, then that target bound on shadedUnit for the full resolution composite. Leaves the
	 * framebuffer and viewport changed.
	 *
	 * @param width  the scene targets' size
	 */
	void shadeHalf(RuneLightConfig config, int width, int height, int shadedUnit)
	{
		if (!enabled(config))
		{
			return;
		}
		// not bound while it is drawn into
		glActiveTexture(GL_TEXTURE0 + shadedUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glActiveTexture(GL_TEXTURE0);
		surface.bindShadedTarget(width, height);
		glUniform1i(uniPass, 1);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glUniform1i(uniPass, 0);
		glActiveTexture(GL_TEXTURE0 + shadedUnit);
		glBindTexture(GL_TEXTURE_2D, surface.shadedTexture());
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
	}

	double[] drift()
	{
		return new double[]{driftX, driftZ, strength};
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

	// Layer 0 the waves, layer 1 the caustics, layer 2 the ripple cells (foam on rapids), layer 3 117 HD's water normal
	// map: one sampler, the composite has none to spare
	private static int createNoise()
	{
		byte[] waves = noise(NOISE_SIZE, NOISE_SEED);
		byte[] caustics = caustics(NOISE_SIZE);
		byte[] cells = cells(NOISE_SIZE, CELL_SEED);
		byte[] normals = normalMap(NOISE_SIZE);
		ByteBuffer buf = BufferUtils.createByteBuffer(waves.length + caustics.length + cells.length + normals.length);
		buf.put(waves).put(caustics).put(cells).put(normals).flip();

		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D_ARRAY, tex);
		glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
		glTexImage3D(GL_TEXTURE_2D_ARRAY, 0, GL_RGBA8, NOISE_SIZE, NOISE_SIZE, 4, 0, GL_RGBA, GL_UNSIGNED_BYTE, buf);
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

	/**
	 * 117 HD's water normal map (BSD-2, see LICENSE), RGBA: rg the normal's x and z (0.5 flat), b its up, a unused.
	 * The map is 128 texels across; it is sampled bilinearly, wrapping, up to the array's size. Flat if it is missing.
	 */
	static byte[] normalMap(int size)
	{
		byte[] out = new byte[size * size * 4];
		try (InputStream in = Water.class.getResourceAsStream(NORMAL_MAP))
		{
			BufferedImage img = in != null ? ImageIO.read(in) : null;
			if (img == null)
			{
				throw new IOException("missing water normal map");
			}
			int w = img.getWidth(), h = img.getHeight();
			for (int y = 0; y < size; ++y)
			{
				float fy = (y + 0.5f) * h / size - 0.5f;
				int y0 = (int) Math.floor(fy);
				float ty = fy - y0;
				for (int x = 0; x < size; ++x)
				{
					float fx = (x + 0.5f) * w / size - 0.5f;
					int x0 = (int) Math.floor(fx);
					float tx = fx - x0;
					int a = img.getRGB(Math.floorMod(x0, w), Math.floorMod(y0, h));
					int b = img.getRGB(Math.floorMod(x0 + 1, w), Math.floorMod(y0, h));
					int c = img.getRGB(Math.floorMod(x0, w), Math.floorMod(y0 + 1, h));
					int d = img.getRGB(Math.floorMod(x0 + 1, w), Math.floorMod(y0 + 1, h));
					int o = (y * size + x) * 4;
					for (int ch = 0; ch < 3; ++ch)
					{
						int sh = 16 - ch * 8;
						float top = (a >> sh & 0xFF) * (1 - tx) + (b >> sh & 0xFF) * tx;
						float bottom = (c >> sh & 0xFF) * (1 - tx) + (d >> sh & 0xFF) * tx;
						out[o + ch] = (byte) Math.round(top * (1 - ty) + bottom * ty);
					}
					out[o + 3] = (byte) 255;
				}
			}
		}
		catch (IOException ex)
		{
			for (int i = 0; i < out.length; i += 4)
			{
				out[i] = out[i + 1] = (byte) 128;
				out[i + 2] = out[i + 3] = (byte) 255;
			}
		}
		return out;
	}

	/**
	 * Tiling round ripple cells, RGBA: rg the slope (0.5 flat, slopes of SLOPE_RANGE standard deviations at 0 or 1),
	 * b the height (1 on the highest crown, 0 the lowest hollow), a unused. The domes of the jittered points around a
	 * texel add up, so cells are round and run into each other smoothly: reflecting the sky, they read as rounded
	 * ripples rather than streaks, without the hard creases of a Voronoi pattern.
	 */
	static byte[] cells(int size, long seed)
	{
		Random rng = new Random(seed);
		float[] px = new float[CELLS * CELLS], py = new float[CELLS * CELLS];
		float spacing = (float) size / CELLS;
		for (int j = 0; j < CELLS; ++j)
		{
			for (int i = 0; i < CELLS; ++i)
			{
				px[j * CELLS + i] = (i + 0.2f + 0.6f * rng.nextFloat()) * spacing;
				py[j * CELLS + i] = (j + 0.2f + 0.6f * rng.nextFloat()) * spacing;
			}
		}
		float r = CELL_RADIUS * spacing;
		int n = size * size;
		float[] h = new float[n];
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				float sum = 0;
				for (int k = 0; k < px.length; ++k)
				{
					// the nearest copy of the point across the wrap
					float dx = Math.abs(x + 0.5f - px[k]), dy = Math.abs(y + 0.5f - py[k]);
					dx = Math.min(dx, size - dx);
					dy = Math.min(dy, size - dy);
					float d2 = (dx * dx + dy * dy) / (r * r);
					if (d2 < 1)
					{
						sum += (1 - d2) * (1 - d2) * (1 - d2);
					}
				}
				h[y * size + x] = sum;
			}
		}
		normalize(h);
		float[] sx = new float[n], sy = new float[n];
		double sum = 0;
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int i = y * size + x;
				sx[i] = (h[y * size + (x + 1) % size] - h[y * size + (x + size - 1) % size]) * 0.5f;
				sy[i] = (h[((y + 1) % size) * size + x] - h[((y + size - 1) % size) * size + x]) * 0.5f;
				sum += sx[i] * sx[i] + sy[i] * sy[i];
			}
		}
		float max = (float) (SLOPE_RANGE * Math.sqrt(sum / (2 * n))) + 1e-6f;
		byte[] out = new byte[n * 4];
		for (int i = 0; i < n; ++i)
		{
			out[i * 4] = (byte) Math.round((clamp(sx[i] / max) * 0.5f + 0.5f) * 255);
			out[i * 4 + 1] = (byte) Math.round((clamp(sy[i] / max) * 0.5f + 0.5f) * 255);
			out[i * 4 + 2] = (byte) Math.round(h[i] * 255);
			out[i * 4 + 3] = (byte) 255;
		}
		return out;
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
