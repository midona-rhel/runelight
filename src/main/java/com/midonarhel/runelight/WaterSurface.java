package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The water surface as geometry, drawn before the composite into its own target: per pixel how far away the surface
 * is and its normal, type and foam (water_vert.glsl, water_frag.glsl), which the composite shades
 * (water.glsl).
 *
 * Why its own target rather than the scene's: the composite lights the scene and needs both what is under the
 * water (the bed's depth, color and normal in the scene targets) and the surface in front of it, and it has no
 * sampler to spare, so the surface goes into one RGBA16UI texture (64 bits) that takes the water data's unit there. The pass
 * runs against sample 0 of the scene's depth, so it is drawn once per pixel, not per MSAA sample; it has its own depth buffer
 * so the nearest surface wins where water stands on several levels, and drops what is behind the scene in its fragment
 * shader.
 *
 * The mesh: one instance per tile (the scene's water tiles and the land tiles around them, where the surface runs on
 * under the banks), built when the scene loads, grouped by chunks of {@link #CHUNK} tiles. Each tile is one flat quad
 * at the water map's corner heights, flat but for the rain rings in its normal.
 */
class WaterSurface
{
	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "water_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "water_frag.glsl");

	static final int CHUNK = 8;
	// how far the surface can be off its chunk's tile heights (land corners take the nearest water's level), world
	// units, and more
	static final int HEIGHT_MARGIN = 256;

	/**
	 * The instances of one scene: 4 bytes per tile (extended x, y, water type, look slot), by chunk.
	 */
	static final class Mesh
	{
		byte[] tiles;
		int count;
		// per chunk with tiles: its first instance, instance count, its bounds in extended tiles, and the lowest and
		// highest height (y, down) its surface can be at
		int[] chunkStart, chunkCount, chunkX, chunkY, chunkMinY, chunkMaxY;
		int chunks;
		// the looks (Water.slotKey) the tiles use, by slot; more than Water.SLOTS share the last
		final int[] slotKeys = new int[Water.SLOTS];
		int slots;

		int slot(int key)
		{
			for (int i = 0; i < slots; ++i)
			{
				if (slotKeys[i] == key)
				{
					return i;
				}
			}
			if (slots < slotKeys.length)
			{
				slotKeys[slots] = key;
				return slots++;
			}
			// out of slots: the first with the same type
			for (int i = 0; i < slots; ++i)
			{
				if (Water.slotType(slotKeys[i]) == Water.slotType(key))
				{
					return i;
				}
			}
			return slots - 1;
		}
	}

	private int program;
	private int uniViewProj, uniCamera, uniGrid, uniTime, uniRain, uniOutdoors, uniDebug, uniSkyDepth, uniCausticsOn,
		uniCasterDir, uniInvViewProj, uniViewport, uniZeroToOne, uniOrigin;

	// per chunk this frame: drawn (within reach and in view)
	private boolean[] chunkDrawn;

	private int vao, vboTiles, ebo;
	private Mesh mesh;
	// The target holds only the pixels of the rect (anyVisible), from its corner: a clear of all of it is cheap on a
	// tiled GPU, but it is still written out whole. Sized in steps of TARGET_STEP pixels, and kept while the rect fits
	// and needs at most a step less (no new one each frame while the rect's edge hovers about a step).
	private static final int TARGET_STEP = 256;
	private int fbo, texSurface, rboDepth, width, height;

	private static final int[] CLEAR = {0, 0, 0, 0};
	private static final float[] FAR_DEPTH = {0};

	/**
	 * The instances for the water of a scene, or null for none.
	 */
	static Mesh build(WaterMap map)
	{
		if (map == null || !map.hasWater)
		{
			return null;
		}
		final int size = WaterMap.SIZE;
		// a tile's look: its water type and the game's water texture (Water.slotKey), or for the land around the
		// water a neighbor's (4-neighbors first); -1 none
		int[] key = new int[size * size];
		java.util.Arrays.fill(key, -1);
		for (int i = 0; i < key.length; ++i)
		{
			if (map.level[i] >= 0)
			{
				key[i] = Water.slotKey(map.waterTexture[i], Math.max(WaterMap.FRESH, map.type[i]));
			}
		}
		int[] out = new int[size * size];
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int i = y * size + x;
				out[i] = key[i] >= 0 ? key[i] : neighborKey(key, size, x, y);
			}
		}

		Mesh m = new Mesh();
		int chunksPerSide = (size + CHUNK - 1) / CHUNK;
		m.tiles = new byte[size * size * 4];
		m.chunkStart = new int[chunksPerSide * chunksPerSide];
		m.chunkCount = new int[m.chunkStart.length];
		m.chunkX = new int[m.chunkStart.length];
		m.chunkY = new int[m.chunkStart.length];
		m.chunkMinY = new int[m.chunkStart.length];
		m.chunkMaxY = new int[m.chunkStart.length];
		for (int cy = 0; cy < chunksPerSide; ++cy)
		{
			for (int cx = 0; cx < chunksPerSide; ++cx)
			{
				int start = m.count;
				for (int y = cy * CHUNK; y < Math.min(size, cy * CHUNK + CHUNK); ++y)
				{
					for (int x = cx * CHUNK; x < Math.min(size, cx * CHUNK + CHUNK); ++x)
					{
						int k = out[y * size + x];
						if (k < 0)
						{
							continue;
						}
						int o = m.count++ * 4;
						m.tiles[o] = (byte) x;
						m.tiles[o + 1] = (byte) y;
						m.tiles[o + 2] = (byte) Water.slotType(k);
						m.tiles[o + 3] = (byte) m.slot(k);
					}
				}
				if (m.count > start)
				{
					m.chunkStart[m.chunks] = start;
					m.chunkCount[m.chunks] = m.count - start;
					m.chunkX[m.chunks] = cx * CHUNK;
					m.chunkY[m.chunks] = cy * CHUNK;
					int[] range = map.heightRange(cx * CHUNK, cy * CHUNK, Math.min(size, cx * CHUNK + CHUNK),
						Math.min(size, cy * CHUNK + CHUNK));
					m.chunkMinY[m.chunks] = range[0] - HEIGHT_MARGIN;
					m.chunkMaxY[m.chunks] = range[1] + HEIGHT_MARGIN;
					++m.chunks;
				}
			}
		}
		return m.count == 0 ? null : m;
	}

	private static int neighborKey(int[] key, int size, int x, int y)
	{
		final int[][] order = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
		for (int[] d : order)
		{
			int nx = x + d[0], ny = y + d[1];
			if (nx >= 0 && ny >= 0 && nx < size && ny < size && key[ny * size + nx] >= 0)
			{
				return key[ny * size + nx];
			}
		}
		return -1;
	}

	// one quad per tile: corners 0 sw, 1 se, 2 nw, 3 ne (water_vert.glsl)
	static final short[] QUAD = {0, 2, 1, 1, 2, 3};

	/**
	 * Whether any chunk drawn this frame (within reach of the focal point) is in the view: without one the water's
	 * passes have nothing to draw. viewProj is camera-relative. With one, rect is set to the targets' pixels the chunks
	 * in view can cover (x0, y0, x1, y1, the ends exclusive): their boxes' projected corners' bounds a pixel wider,
	 * within the viewport, or all of it when one reaches behind the camera. Marks the chunks drawn for the draw.
	 */
	boolean anyVisible(float[] m, int[] viewport, float camX, float camY, float camZ, float focusX, float focusZ,
		float reach, int[] rect)
	{
		Mesh mesh = this.mesh;
		if (mesh == null)
		{
			return false;
		}
		boolean any = false, whole = false;
		float minX = 1, minY = 1, maxX = -1, maxY = -1;
		for (int c = 0; c < mesh.chunks; ++c)
		{
			chunkDrawn[c] = chunkDistance(focusX, focusZ, mesh.chunkX[c], mesh.chunkY[c]) <= reach;
			if (!chunkDrawn[c])
			{
				continue;
			}
			float x0 = (mesh.chunkX[c] - RuneLightPlugin.SCENE_OFFSET) * 128f - camX;
			float z0 = (mesh.chunkY[c] - RuneLightPlugin.SCENE_OFFSET) * 128f - camZ;
			float y0 = mesh.chunkMinY[c] - camY, y1 = mesh.chunkMaxY[c] - camY;
			// outside when all eight corners are beyond the same side of the view, or behind the camera
			int left = 0, right = 0, below = 0, above = 0, behind = 0;
			float cMinX = Float.MAX_VALUE, cMinY = Float.MAX_VALUE, cMaxX = -Float.MAX_VALUE, cMaxY = -Float.MAX_VALUE;
			for (int k = 0; k < 8; ++k)
			{
				float x = x0 + ((k & 1) != 0 ? CHUNK * 128f : 0), y = (k & 2) != 0 ? y1 : y0;
				float z = z0 + ((k & 4) != 0 ? CHUNK * 128f : 0);
				float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
				float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
				float w = m[3] * x + m[7] * y + m[11] * z + m[15];
				left += cx < -w ? 1 : 0;
				right += cx > w ? 1 : 0;
				below += cy < -w ? 1 : 0;
				above += cy > w ? 1 : 0;
				behind += w <= 0 ? 1 : 0;
				if (w > 0)
				{
					cMinX = Math.min(cMinX, cx / w);
					cMaxX = Math.max(cMaxX, cx / w);
					cMinY = Math.min(cMinY, cy / w);
					cMaxY = Math.max(cMaxY, cy / w);
				}
			}
			if (left < 8 && right < 8 && below < 8 && above < 8 && behind < 8)
			{
				any = true;
				// its projection is unbounded when it reaches behind the camera, else within its corners'
				whole |= behind > 0;
				minX = Math.min(minX, cMinX);
				maxX = Math.max(maxX, cMaxX);
				minY = Math.min(minY, cMinY);
				maxY = Math.max(maxY, cMaxY);
			}
		}
		if (whole)
		{
			minX = minY = -1;
			maxX = maxY = 1;
		}
		if (any)
		{
			rect[0] = pixelBelow(minX, viewport[0], viewport[2]);
			rect[1] = pixelBelow(minY, viewport[1], viewport[3]);
			rect[2] = pixelAbove(maxX, viewport[0], viewport[2]);
			rect[3] = pixelAbove(maxY, viewport[1], viewport[3]);
		}
		return any;
	}

	// an NDC coordinate as the targets' pixels a viewport of size at origin puts it at, a pixel out for the rounding
	private static int pixelBelow(float ndc, int origin, int size)
	{
		return Math.max(origin, origin + (int) Math.floor((Math.max(ndc, -1) * 0.5f + 0.5f) * size) - 1);
	}

	private static int pixelAbove(float ndc, int origin, int size)
	{
		return Math.min(origin + size, origin + (int) Math.ceil((Math.min(ndc, 1) * 0.5f + 0.5f) * size) + 1);
	}

	/**
	 * Horizontal distance from (px, pz) to a chunk's square, world units, with the chunk at extended tile (cx, cy).
	 */
	static float chunkDistance(float px, float pz, int cx, int cy)
	{
		float x0 = (cx - RuneLightPlugin.SCENE_OFFSET) * 128f, z0 = (cy - RuneLightPlugin.SCENE_OFFSET) * 128f;
		float x1 = x0 + CHUNK * 128f, z1 = z0 + CHUNK * 128f;
		float dx = Math.max(0, Math.max(x0 - px, px - x1));
		float dz = Math.max(0, Math.max(z0 - pz, pz - z1));
		return (float) Math.sqrt(dx * dx + dz * dz);
	}

	void setProgram(int program, int depthUnit, int dataUnit, int noiseUnit)
	{
		this.program = program;
		uniViewProj = glGetUniformLocation(program, "waterViewProj");
		uniCamera = glGetUniformLocation(program, "waterCamera");
		uniGrid = glGetUniformLocation(program, "waterGrid");
		uniTime = glGetUniformLocation(program, "waterTime");
		uniRain = glGetUniformLocation(program, "waterRain");
		uniOutdoors = glGetUniformLocation(program, "waterOutdoors");
		uniDebug = glGetUniformLocation(program, "waterDebug");
		uniSkyDepth = glGetUniformLocation(program, "waterSkyDepth");
		uniCausticsOn = glGetUniformLocation(program, "waterCausticsOn");
		uniCasterDir = glGetUniformLocation(program, "waterCasterDir");
		uniInvViewProj = glGetUniformLocation(program, "waterInvViewProj");
		uniViewport = glGetUniformLocation(program, "waterViewport");
		uniZeroToOne = glGetUniformLocation(program, "waterZeroToOne");
		uniOrigin = glGetUniformLocation(program, "waterOrigin");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "sceneDepth"), depthUnit);
		glUniform1i(glGetUniformLocation(program, "waterData"), dataUnit);
		glUniform1i(glGetUniformLocation(program, "waterNoise"), noiseUnit);
		glUseProgram(0);
	}

	void freeProgram()
	{
		glDeleteProgram(program);
		program = 0;
	}

	/**
	 * Upload a scene's mesh (null for none). GL thread.
	 */
	void upload(Mesh m)
	{
		mesh = m;
		if (m == null)
		{
			return;
		}
		chunkDrawn = new boolean[m.chunks];
		if (vao == 0)
		{
			vao = glGenVertexArrays();
			vboTiles = glGenBuffers();
			ebo = glGenBuffers();
			glBindVertexArray(vao);
			ShortBuffer idx = BufferUtils.createShortBuffer(6);
			idx.put(QUAD).flip();
			glBindBuffer(GL_ELEMENT_ARRAY_BUFFER, ebo);
			glBufferData(GL_ELEMENT_ARRAY_BUFFER, idx, GL_STATIC_DRAW);
			glBindBuffer(GL_ARRAY_BUFFER, vboTiles);
			glEnableVertexAttribArray(0);
			glVertexAttribDivisor(0, 1);
			glBindVertexArray(0);
		}
		ByteBuffer buf = BufferUtils.createByteBuffer(m.count * 4);
		buf.put(m.tiles, 0, m.count * 4).flip();
		glBindBuffer(GL_ARRAY_BUFFER, vboTiles);
		glBufferData(GL_ARRAY_BUFFER, buf, GL_STATIC_DRAW);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
	}

	boolean hasMesh()
	{
		return mesh != null && program != 0;
	}

	int texture()
	{
		return texSurface;
	}

	Mesh mesh()
	{
		return mesh;
	}

	/**
	 * The target for a rect of w x h pixels, within targets of maxW x maxH.
	 */
	private void ensureTarget(int w, int h, int maxW, int maxH)
	{
		if (fbo != 0 && fits(w, width, maxW) && fits(h, height, maxH))
		{
			return;
		}
		w = steps(w, maxW);
		h = steps(h, maxH);
		freeTarget();
		width = w;
		height = h;
		texSurface = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texSurface);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA16UI, w, h, 0, GL_RGBA_INTEGER, GL_UNSIGNED_SHORT, (ByteBuffer) null);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		rboDepth = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboDepth);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT32F, w, h);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texSurface, 0);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepth);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new IllegalStateException("water surface framebuffer incomplete: " + status);
		}
	}

	private static int steps(int size, int max)
	{
		return Math.min(max, Math.max(1, (size + TARGET_STEP - 1) / TARGET_STEP) * TARGET_STEP);
	}

	private static boolean fits(int size, int current, int max)
	{
		return size <= current && current <= steps(size, max) + TARGET_STEP;
	}

	/**
	 * Draw the surface into the target (the rect's pixels of targets of w x h) within the scene's viewport. The
	 * scene's depth (sample 0) must be bound on the program's depth unit, the water data and noise on theirs. Leaves
	 * the framebuffer and VAO bindings changed and depth testing off.
	 *
	 * @param params this frame's settings
	 * @param rect   the pixels the chunks in view can cover, and the chunks drawn (anyVisible, just before)
	 */
	void draw(int w, int h, int[] viewport, float[] viewProj, float camX, float camY, float camZ, Water.Frame params,
		int[] rect)
	{
		int rw = rect[2] - rect[0], rh = rect[3] - rect[1];
		ensureTarget(rw, rh, w, h);
		if (!hasMesh())
		{
			glBindFramebuffer(GL_FRAMEBUFFER, fbo);
			glClearBufferuiv(GL_COLOR, 0, CLEAR);
			return;
		}
		boolean cull = glIsEnabled(GL_CULL_FACE);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);

		glUseProgram(program);
		glUniformMatrix4fv(uniViewProj, false, viewProj);
		glUniform3f(uniCamera, camX, camY, camZ);
		glUniform2f(uniGrid, RuneLightPlugin.SCENE_OFFSET, WaterMap.CORNERS);
		glUniform1f(uniTime, params.time);
		glUniform1f(uniRain, params.rain);
		glUniform1i(uniOutdoors, params.outdoors ? 1 : 0);
		glUniform1i(uniDebug, params.debug);
		glUniform1f(uniSkyDepth, params.skyDepth);
		glUniform1i(uniCausticsOn, params.caustics ? 1 : 0);
		glUniform3fv(uniCasterDir, params.casterDir);
		glUniformMatrix4fv(uniInvViewProj, false, params.invViewProj);
		glUniform4i(uniViewport, viewport[0], viewport[1], viewport[2], viewport[3]);
		glUniform1i(uniZeroToOne, params.zeroToOne ? 1 : 0);
		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vboTiles);

		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		// clears follow the write masks
		glDepthMask(true);
		glClearBufferuiv(GL_COLOR, 0, CLEAR);
		glClearBufferfv(GL_DEPTH, 0, FAR_DEPTH);
		// the rect's corner at the target's
		glUniform2i(uniOrigin, rect[0], rect[1]);
		glViewport(viewport[0] - rect[0], viewport[1] - rect[1], viewport[2], viewport[3]);
		// the clears stay whole: on a tiled GPU a scissored one loads the targets first
		glEnable(GL_SCISSOR_TEST);
		glScissor(0, 0, rw, rh);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);
		drawChunks();
		glDisable(GL_SCISSOR_TEST);
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
		glDisable(GL_DEPTH_TEST);
		if (cull)
		{
			glEnable(GL_CULL_FACE);
		}
	}

	/**
	 * Draw the chunks drawn this frame.
	 */
	private void drawChunks()
	{
		Mesh m = mesh;
		// consecutive chunks drawn go in one draw: their instances are contiguous
		int runStart = -1, runEnd = 0;
		for (int c = 0; c <= m.chunks; ++c)
		{
			boolean drawn = c < m.chunks && chunkDrawn[c];
			if (runStart >= 0 && (!drawn || c == m.chunks))
			{
				glVertexAttribIPointer(0, 4, GL_UNSIGNED_BYTE, 4, runStart * 4L);
				glDrawElementsInstanced(GL_TRIANGLES, 6, GL_UNSIGNED_SHORT, 0L, runEnd - runStart);
				runStart = -1;
			}
			if (drawn)
			{
				if (runStart < 0)
				{
					runStart = m.chunkStart[c];
				}
				runEnd = m.chunkStart[c] + m.chunkCount[c];
			}
		}
	}

	private void freeTarget()
	{
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(texSurface);
			glDeleteRenderbuffers(rboDepth);
			fbo = texSurface = rboDepth = 0;
		}
	}

	void free()
	{
		freeTarget();
		if (vao != 0)
		{
			glDeleteVertexArrays(vao);
			glDeleteBuffers(vboTiles);
			glDeleteBuffers(ebo);
			vao = vboTiles = ebo = 0;
		}
		mesh = null;
	}
}
