package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;
import org.lwjgl.BufferUtils;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The water surface as geometry, drawn before the composite into its own target: per pixel how far away the surface
 * is and its normal, type, foam and speed (water_vert.glsl, water_frag.glsl), which the composite shades
 * (water.glsl).
 *
 * Why its own target rather than the scene's: the composite lights the scene and needs both what is under the
 * water (the bed's depth, color and normal in the scene targets) and the surface in front of it, and it has no
 * sampler to spare, so the surface goes into one RGBA16UI texture (64 bits) that takes the water data's unit there. The pass
 * runs after the MSAA resolve against the resolved depth, so it is drawn once per pixel; it has its own depth buffer
 * so the waves hide each other, and drops what is behind the scene in its fragment shader.
 *
 * The mesh: one instance per tile (the scene's water tiles and the land tiles around them, where the surface runs on
 * under the banks), built when the scene loads, grouped by chunks of {@link #CHUNK} tiles. Each tile is one flat quad
 * at the water map's corner heights; the waves are all in its normal.
 */
class WaterSurface
{
	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "water_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "water_frag.glsl");

	static final int CHUNK = 8;

	/**
	 * The instances of one scene: 4 bytes per tile (extended x, y, water type, 0), by chunk.
	 */
	static final class Mesh
	{
		byte[] tiles;
		int count;
		// per chunk with tiles: its first instance, instance count, and its bounds in extended tiles
		int[] chunkStart, chunkCount, chunkX, chunkY;
		int chunks;
	}

	private int program;
	private int uniViewProj, uniCamera, uniFocus, uniGrid, uniTime, uniWind, uniDrift, uniWaves, uniSpeed, 
		uniRain, uniOutdoors, uniDebug, uniSkyDepth, uniCausticsOn, uniCasterDir, uniInvViewProj, uniViewport,
		uniZeroToOne;

	private int vao, vboTiles, ebo;
	private Mesh mesh;
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
		// a tile's water type, or for the land around the water a neighbor's (4-neighbors first); 0 none
		byte[] type = new byte[size * size];
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int i = y * size + x;
				if (map.level[i] >= 0)
				{
					type[i] = (byte) Math.max(WaterMap.FRESH, map.type[i]);
				}
			}
		}
		byte[] out = new byte[size * size];
		for (int y = 0; y < size; ++y)
		{
			for (int x = 0; x < size; ++x)
			{
				int i = y * size + x;
				if (type[i] != 0)
				{
					out[i] = type[i];
					continue;
				}
				out[i] = neighborType(type, size, x, y);
			}
		}

		Mesh m = new Mesh();
		int chunksPerSide = (size + CHUNK - 1) / CHUNK;
		m.tiles = new byte[size * size * 4];
		m.chunkStart = new int[chunksPerSide * chunksPerSide];
		m.chunkCount = new int[m.chunkStart.length];
		m.chunkX = new int[m.chunkStart.length];
		m.chunkY = new int[m.chunkStart.length];
		for (int cy = 0; cy < chunksPerSide; ++cy)
		{
			for (int cx = 0; cx < chunksPerSide; ++cx)
			{
				int start = m.count;
				for (int y = cy * CHUNK; y < Math.min(size, cy * CHUNK + CHUNK); ++y)
				{
					for (int x = cx * CHUNK; x < Math.min(size, cx * CHUNK + CHUNK); ++x)
					{
						byte t = out[y * size + x];
						if (t == 0)
						{
							continue;
						}
						int o = m.count++ * 4;
						m.tiles[o] = (byte) x;
						m.tiles[o + 1] = (byte) y;
						m.tiles[o + 2] = t;
					}
				}
				if (m.count > start)
				{
					m.chunkStart[m.chunks] = start;
					m.chunkCount[m.chunks] = m.count - start;
					m.chunkX[m.chunks] = cx * CHUNK;
					m.chunkY[m.chunks] = cy * CHUNK;
					++m.chunks;
				}
			}
		}
		return m.count == 0 ? null : m;
	}

	private static byte neighborType(byte[] type, int size, int x, int y)
	{
		final int[][] order = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {-1, 1}, {1, -1}, {-1, -1}};
		for (int[] d : order)
		{
			int nx = x + d[0], ny = y + d[1];
			if (nx >= 0 && ny >= 0 && nx < size && ny < size && type[ny * size + nx] != 0)
			{
				return type[ny * size + nx];
			}
		}
		return 0;
	}

	/**
	 * Indices of an n x n grid of quads over (n + 1)^2 vertices, row by row.
	 */
	// one quad per tile: corners 0 sw, 1 se, 2 nw, 3 ne (water_vert.glsl)
	static final short[] QUAD = {0, 2, 1, 1, 2, 3};

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
		uniFocus = glGetUniformLocation(program, "waterFocus");
		uniGrid = glGetUniformLocation(program, "waterGrid");
		uniTime = glGetUniformLocation(program, "waterTime");
		uniWind = glGetUniformLocation(program, "waterWind");
		uniDrift = glGetUniformLocation(program, "waterDrift");
		uniWaves = glGetUniformLocation(program, "waterWaves");
		uniSpeed = glGetUniformLocation(program, "waterSpeed");
		uniRain = glGetUniformLocation(program, "waterRain");
		uniOutdoors = glGetUniformLocation(program, "waterOutdoors");
		uniDebug = glGetUniformLocation(program, "waterDebug");
		uniSkyDepth = glGetUniformLocation(program, "waterSkyDepth");
		uniCausticsOn = glGetUniformLocation(program, "waterCausticsOn");
		uniCasterDir = glGetUniformLocation(program, "waterCasterDir");
		uniInvViewProj = glGetUniformLocation(program, "waterInvViewProj");
		uniViewport = glGetUniformLocation(program, "waterViewport");
		uniZeroToOne = glGetUniformLocation(program, "waterZeroToOne");
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

	private void ensureTarget(int w, int h)
	{
		if (fbo != 0 && w == width && h == height)
		{
			return;
		}
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

	/**
	 * Draw the surface into the target (width x height, the scene targets' size) within the scene's viewport. The
	 * scene's resolved depth must be bound on the program's depth unit, the water data and noise on theirs. Leaves
	 * the framebuffer and VAO bindings changed and depth testing off.
	 *
	 * @param focusX the focal point, around which the surface moves and is drawn fine
	 * @param reach  radius of the drawn world around the focal point, world units
	 * @param params this frame's motion and settings
	 */
	void draw(int w, int h, int[] viewport, float[] viewProj, float camX, float camY, float camZ, float focusX,
		float focusZ, float reach, Water.Frame params)
	{
		ensureTarget(w, h);
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		// clears follow the write masks
		glDepthMask(true);
		glClearBufferuiv(GL_COLOR, 0, CLEAR);
		glClearBufferfv(GL_DEPTH, 0, FAR_DEPTH);
		if (!hasMesh())
		{
			return;
		}
		glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
		boolean cull = glIsEnabled(GL_CULL_FACE);
		glDisable(GL_CULL_FACE);
		glDisable(GL_BLEND);
		glEnable(GL_DEPTH_TEST);
		glDepthFunc(GL_GREATER);

		glUseProgram(program);
		glUniformMatrix4fv(uniViewProj, false, viewProj);
		glUniform3f(uniCamera, camX, camY, camZ);
		glUniform2f(uniFocus, focusX, focusZ);
		glUniform2f(uniGrid, RuneLightPlugin.SCENE_OFFSET, WaterMap.CORNERS);
		glUniform1f(uniTime, params.time);
		glUniform3f(uniWind, params.windX, params.windZ, params.strength);
		glUniform4f(uniDrift, params.driftX, params.driftZ, 0, 0);
		glUniform1f(uniWaves, params.waves);
		glUniform1f(uniSpeed, params.speed);
		glUniform1f(uniRain, params.rain);
		glUniform1i(uniOutdoors, params.outdoors ? 1 : 0);
		glUniform1i(uniDebug, params.debug ? 1 : 0);
		glUniform1f(uniSkyDepth, params.skyDepth);
		glUniform1i(uniCausticsOn, params.caustics ? 1 : 0);
		glUniform3fv(uniCasterDir, params.casterDir);
		glUniformMatrix4fv(uniInvViewProj, false, params.invViewProj);
		glUniform4i(uniViewport, viewport[0], viewport[1], viewport[2], viewport[3]);
		glUniform1i(uniZeroToOne, params.zeroToOne ? 1 : 0);

		glBindVertexArray(vao);
		glBindBuffer(GL_ARRAY_BUFFER, vboTiles);
		Mesh m = mesh;
		// consecutive chunks in reach go in one draw: their instances are contiguous
		int runStart = -1, runEnd = 0;
		for (int c = 0; c <= m.chunks; ++c)
		{
			boolean drawn = c < m.chunks && chunkDistance(focusX, focusZ, m.chunkX[c], m.chunkY[c]) <= reach;
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
		glBindBuffer(GL_ARRAY_BUFFER, 0);
		glBindVertexArray(0);
		glDisable(GL_DEPTH_TEST);
		if (cull)
		{
			glEnable(GL_CULL_FACE);
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
