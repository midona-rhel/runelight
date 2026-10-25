package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Orthographic shadow map for the single directional caster (sun, moon or lightning bolt), centered on a focus
 * point near the player. Depth is stored linearly in both clip conventions: window depth = (z + depthRange) / (2 depthRange)
 * where z is the distance along the light direction from the focus.
 */
class ShadowMap
{
	private int size;
	private int fbo;
	private int texDepth;
	// two views of the same texture: raw depth for the PCSS blocker search, hardware compare for filtering
	private static int emptyTexture, emptySampler;
	private int samplerRaw;
	private int samplerCompare;

	// world space light matrix (for the shadow pass) and camera-relative (for the composite)
	final float[] lightProj = Mat4.identity();
	final float[] lightProjRel = Mat4.identity();
	private float radius;
	private float depthRange;

	/**
	 * Static scenery cache: the zones drawn into a map of their own, kept while the light and the area around the
	 * player stay about the same. Each frame that map is copied into the one sampled and only the moving models are
	 * drawn over it. A new static map is drawn into the other texture, over a few frames, while the old one is used.
	 */
	static final class View
	{
		final float[] proj = Mat4.identity();
		final float[] dir = new float[3];
		float radius, depthRange;
		float focusX, focusY, focusZ;
	}

	private final int[] staticTex = new int[2];
	private final int[] staticFbo = new int[2];
	private final View[] views = {new View(), new View()};
	// the static map in use, -1 for none; the one being drawn while building
	private int front = -1;
	private int building = -1;
	// the scenery changed under the front map: redraw it, but keep showing it meanwhile
	private boolean stale;
	private int cacheKey;
	// static maps started so far, for the timings overlay
	int builds;

	/*
	 * The front static map's greatest depth over the reach of shadow_pcss.glsl's widest filter from each tile of texels
	 * (its shadowMax): what is nearer the light than that is in shadow under every tap. The moving models only add
	 * nearer depth, so it bounds the sampled map too (buildMax).
	 */
	private int maxTex, maxSpareTex, maxFbo;
	private int maxSize;
	private int maxTile;
	// the widest filter tap: shadow_pcss.glsl's MAX_PENUMBRA_WORLD times its farthest POISSON point (1.234)
	private static final float FILTER_REACH_WORLD = 112 * 1.234f;
	// Tiles a third of the reach: the bound covers at most a third more than it must. No larger than this, so that
	// a tile's own maximum stays a short loop when the texels are small.
	private static final int MAX_TILE = 16;

	void init(int size)
	{
		this.size = size;
		texDepth = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texDepth);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, size, size, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);

		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texDepth, 0);
		glDrawBuffer(GL_NONE);
		glReadBuffer(GL_NONE);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("Shadow framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);

		samplerRaw = glGenSamplers();
		glSamplerParameteri(samplerRaw, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glSamplerParameteri(samplerRaw, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glSamplerParameteri(samplerRaw, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glSamplerParameteri(samplerRaw, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glSamplerParameteri(samplerRaw, GL_TEXTURE_COMPARE_MODE, GL_NONE);

		samplerCompare = glGenSamplers();
		glSamplerParameteri(samplerCompare, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glSamplerParameteri(samplerCompare, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glSamplerParameteri(samplerCompare, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glSamplerParameteri(samplerCompare, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glSamplerParameteri(samplerCompare, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
		glSamplerParameteri(samplerCompare, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
	}

	void free()
	{
		freeCache();
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(texDepth);
			glDeleteSamplers(samplerRaw);
			glDeleteSamplers(samplerCompare);
			fbo = texDepth = samplerRaw = samplerCompare = 0;
		}
	}

	boolean isInitialized()
	{
		return fbo != 0;
	}

	int getSize()
	{
		return size;
	}

	int getFbo()
	{
		return fbo;
	}

	float getRadius()
	{
		return radius;
	}

	float getDepthRange()
	{
		return depthRange;
	}

	/**
	 * World units covered by one shadow map texel.
	 */
	float texelWorldSize()
	{
		return 2 * radius / size;
	}

	private void initCache()
	{
		for (int i = 0; i < 2; ++i)
		{
			staticTex[i] = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, staticTex[i]);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, size, size, 0, GL_DEPTH_COMPONENT, GL_FLOAT, 0);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glBindTexture(GL_TEXTURE_2D, 0);
			staticFbo[i] = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, staticFbo[i]);
			glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, staticTex[i], 0);
			glDrawBuffer(GL_NONE);
			glReadBuffer(GL_NONE);
			int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
			if (status != GL_FRAMEBUFFER_COMPLETE)
			{
				throw new RuntimeException("Static shadow framebuffer incomplete, status 0x" + Integer.toHexString(status));
			}
		}
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
	}

	/** Drop the static cache and its textures, e.g. when caching is turned off. */
	void freeCache()
	{
		if (staticFbo[0] != 0)
		{
			glDeleteFramebuffers(staticFbo);
			glDeleteTextures(staticTex);
			staticFbo[0] = staticFbo[1] = staticTex[0] = staticTex[1] = 0;
		}
		if (maxFbo != 0)
		{
			glDeleteFramebuffers(maxFbo);
			glDeleteTextures(maxTex);
			glDeleteTextures(maxSpareTex);
			maxFbo = maxTex = maxSpareTex = maxSize = 0;
		}
		front = building = -1;
		stale = false;
	}

	/** Forget the static map: the scenery, levels or settings changed. */
	void invalidateCache()
	{
		front = building = -1;
		stale = false;
	}

	/**
	 * Some zones changed: draw the static map again, over several frames, while the old one stays in use. Forgetting
	 * it instead (invalidateCache) draws the new one in a single frame, a hitch whenever an object appears.
	 */
	void refreshCache()
	{
		stale = true;
	}

	boolean isStale()
	{
		return stale;
	}

	/** Invalidate if anything the static map depends on, hashed, changed. */
	void checkCacheKey(int key)
	{
		if (key != cacheKey)
		{
			cacheKey = key;
			invalidateCache();
		}
	}

	View frontView()
	{
		return front >= 0 ? views[front] : null;
	}

	boolean isBuilding()
	{
		return building >= 0;
	}

	View buildingView()
	{
		return views[building];
	}

	/**
	 * Start drawing a new static map, into the texture not in use, for this light and focus. Returns its framebuffer,
	 * cleared, for the zones to be drawn into, now or over the next frames.
	 */
	int startBuild(float[] dir, float fx, float fy, float fz, float radius, float depthRange, boolean zeroToOne)
	{
		if (staticFbo[0] == 0)
		{
			initCache();
		}
		building = front == 0 ? 1 : 0;
		stale = false;
		++builds;
		View v = views[building];
		System.arraycopy(dir, 0, v.dir, 0, 3);
		v.focusX = fx;
		v.focusY = fy;
		v.focusZ = fz;
		v.radius = radius;
		v.depthRange = depthRange;
		project(v.proj, dir, fx, fy, fz, radius, depthRange, size, zeroToOne);
		glBindFramebuffer(GL_FRAMEBUFFER, staticFbo[building]);
		glViewport(0, 0, size, size);
		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);
		return staticFbo[building];
	}

	int getBuildFbo()
	{
		return staticFbo[building];
	}

	/** The new static map is drawn: make it the front one, and work out its maxima with maxProgram (a VAO bound). */
	void finishBuild(int maxProgram)
	{
		front = building;
		building = -1;
		buildMax(maxProgram);
	}

	/**
	 * The maxima (shadow_max_frag.glsl): each tile's own, then over the tiles to either side along rows and then
	 * columns, as many as cover the filter's reach from any texel in the tile, plus one texel for the bilinear filter's
	 * second and one for rounding.
	 */
	private void buildMax(int program)
	{
		float texel = 2 * views[front].radius / size;
		int reach = (int) Math.ceil(FILTER_REACH_WORLD / texel) + 2;
		int tile = Math.min((reach + 2) / 3, MAX_TILE);
		int tiles = (reach + tile - 1) / tile;
		int n = (size + tile - 1) / tile;
		maxTile = tile;
		if (n != maxSize)
		{
			if (maxFbo == 0)
			{
				maxFbo = glGenFramebuffers();
				maxTex = glGenTextures();
				maxSpareTex = glGenTextures();
			}
			maxSize = n;
			for (int tex : new int[]{maxTex, maxSpareTex})
			{
				glBindTexture(GL_TEXTURE_2D, tex);
				glTexImage2D(GL_TEXTURE_2D, 0, GL_R32F, n, n, 0, GL_RED, GL_FLOAT, 0);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
				glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			}
		}
		glBindFramebuffer(GL_FRAMEBUFFER, maxFbo);
		glViewport(0, 0, n, n);
		glUseProgram(program);
		glActiveTexture(GL_TEXTURE0);
		maxPass(program, staticTex[front], maxTex, tile, 0, 0, tile, tile);
		maxPass(program, maxTex, maxSpareTex, 1, -tiles, 0, 2 * tiles + 1, 1);
		maxPass(program, maxSpareTex, maxTex, 1, 0, -tiles, 1, 2 * tiles + 1);
		glBindTexture(GL_TEXTURE_2D, 0);
	}

	private static void maxPass(int program, int from, int to, int scale, int offsetX, int offsetY, int countX, int countY)
	{
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, to, 0);
		glBindTexture(GL_TEXTURE_2D, from);
		glUniform1i(glGetUniformLocation(program, "scale"), scale);
		glUniform2i(glGetUniformLocation(program, "offset"), offsetX, offsetY);
		glUniform2i(glGetUniformLocation(program, "count"), countX, countY);
		glDrawArrays(GL_TRIANGLES, 0, 3);
	}

	/**
	 * Bind the maxima for shadow_pcss.glsl's shadowMax and return its texels per tile, its shadowMaxTile: 0 with no
	 * static map, for no bound.
	 */
	int bindMax(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, front >= 0 ? maxTex : 0);
		glBindSampler(unit, 0);
		glActiveTexture(GL_TEXTURE0);
		return front >= 0 ? maxTile : 0;
	}

	/**
	 * Make the front static map this frame's map: its matrices, and its depth copied into the sampled texture, ready
	 * for the moving models to be drawn over it. The copy is a fullscreen triangle (copyProgram, shadow_copy_frag.glsl)
	 * in the same pass as the models rather than a blit, which on Apple's GL is a pass of its own after which the map
	 * is loaded back in. Expects the shadow pass's state (depth test and writes on, GL_LESS, polygon offset) with a VAO
	 * bound, and leaves it so with the sampled map's framebuffer bound and its viewport set, but copyProgram in use.
	 */
	void useFront(float cameraX, float cameraY, float cameraZ, int copyProgram)
	{
		View v = views[front];
		radius = v.radius;
		depthRange = v.depthRange;
		System.arraycopy(v.proj, 0, lightProj, 0, 16);
		relative(lightProj, lightProjRel, cameraX, cameraY, cameraZ);
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glViewport(0, 0, size, size);
		// every texel is overwritten: clearing first only spares loading the old map
		glClearDepth(1.0);
		glClear(GL_DEPTH_BUFFER_BIT);
		glDisable(GL_POLYGON_OFFSET_FILL);
		glDepthFunc(GL_ALWAYS);
		glUseProgram(copyProgram);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, staticTex[front]);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindTexture(GL_TEXTURE_2D, 0);
		glDepthFunc(GL_LESS);
		glEnable(GL_POLYGON_OFFSET_FILL);
	}

	/**
	 * Build the light matrices.
	 *
	 * @param dir unit vector toward the light
	 * @param fx focus point
	 * @param radius half extent of the covered square, world units
	 * @param depthRange how far in front of and behind the focus casters are captured, world units
	 * @param zeroToOne whether glClipControl maps clip depth to [0, 1]
	 */
	void update(float[] dir, float fx, float fy, float fz, float cameraX, float cameraY, float cameraZ,
		float radius, float depthRange, boolean zeroToOne)
	{
		this.radius = radius;
		this.depthRange = depthRange;
		project(lightProj, dir, fx, fy, fz, radius, depthRange, size, zeroToOne);
		relative(lightProj, lightProjRel, cameraX, cameraY, cameraZ);
	}

	/**
	 * The world to light clip matrix for a light along dir around a focus, snapped to whole texels.
	 */
	static void project(float[] m, float[] dir, float fx, float fy, float fz, float radius, float depthRange, int size,
		boolean zeroToOne)
	{
		// light travels along -dir
		float lx = -dir[0], ly = -dir[1], lz = -dir[2];

		// right = normalize(cross(forward, hint)), hint is world north unless nearly parallel
		float hx = 0, hy = 0, hz = 1;
		if (Math.abs(lz) > 0.99f)
		{
			hx = 1;
			hz = 0;
		}
		float rx = ly * hz - lz * hy;
		float ry = lz * hx - lx * hz;
		float rz = lx * hy - ly * hx;
		float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
		rx /= rl;
		ry /= rl;
		rz /= rl;
		// up = cross(right, forward)
		float ux = ry * lz - rz * ly;
		float uy = rz * lx - rx * lz;
		float uz = rx * ly - ry * lx;

		// snap the focus to whole texels in light space so the shadows don't shimmer when the player moves
		float texel = 2 * radius / size;
		float cx = (float) Math.floor((rx * fx + ry * fy + rz * fz) / texel) * texel;
		float cy = (float) Math.floor((ux * fx + uy * fy + uz * fz) / texel) * texel;
		float cz = lx * fx + ly * fy + lz * fz;

		// ndc z: [0,1] with clip control, [-1,1] without; window depth ends up identical
		float zs = zeroToOne ? 1 / (2 * depthRange) : 1 / depthRange;
		float zo = zeroToOne ? (depthRange - cz) / (2 * depthRange) : -cz / depthRange;

		// column-major: m[col * 4 + row]
		m[0] = rx / radius;
		m[4] = ry / radius;
		m[8] = rz / radius;
		m[12] = -cx / radius;
		m[1] = ux / radius;
		m[5] = uy / radius;
		m[9] = uz / radius;
		m[13] = -cy / radius;
		m[2] = lx * zs;
		m[6] = ly * zs;
		m[10] = lz * zs;
		m[14] = zo;
		m[3] = 0;
		m[7] = 0;
		m[11] = 0;
		m[15] = 1;
	}

	/** camera-relative version: rel * p_rel == m * (p_rel + camera) */
	static void relative(float[] m, float[] rel, float cameraX, float cameraY, float cameraZ)
	{
		System.arraycopy(m, 0, rel, 0, 16);
		for (int row = 0; row < 3; ++row)
		{
			rel[12 + row] = m[12 + row] + m[row] * cameraX + m[4 + row] * cameraY + m[8 + row] * cameraZ;
		}
	}

	void bindForSampling(int rawUnit, int compareUnit)
	{
		glActiveTexture(GL_TEXTURE0 + rawUnit);
		glBindTexture(GL_TEXTURE_2D, texDepth);
		glBindSampler(rawUnit, samplerRaw);
		glActiveTexture(GL_TEXTURE0 + compareUnit);
		glBindTexture(GL_TEXTURE_2D, texDepth);
		glBindSampler(compareUnit, samplerCompare);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Leave the units with no map: the raw unit empty, the compare unit with a 1x1 depth texture at the far plane and
	 * a compare sampler, since a shadow sampler (in a program drawn with this map unused) must always find a depth
	 * texture with comparison on its unit, or NVIDIA flags undefined behavior. Reads as lit.
	 */
	static void unbind(int rawUnit, int compareUnit)
	{
		glActiveTexture(GL_TEXTURE0 + rawUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindSampler(rawUnit, 0);
		if (emptyTexture == 0)
		{
			emptyTexture = glGenTextures();
			glBindTexture(GL_TEXTURE_2D, emptyTexture);
			glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT16, 1, 1, 0, GL_DEPTH_COMPONENT, GL_FLOAT, new float[]{1});
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			emptySampler = glGenSamplers();
			glSamplerParameteri(emptySampler, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
			glSamplerParameteri(emptySampler, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
			glSamplerParameteri(emptySampler, GL_TEXTURE_COMPARE_MODE, GL_COMPARE_REF_TO_TEXTURE);
			glSamplerParameteri(emptySampler, GL_TEXTURE_COMPARE_FUNC, GL_LEQUAL);
		}
		glActiveTexture(GL_TEXTURE0 + compareUnit);
		glBindTexture(GL_TEXTURE_2D, emptyTexture);
		glBindSampler(compareUnit, emptySampler);
		glActiveTexture(GL_TEXTURE0);
	}

	/** Free the empty map {@link #unbind} leaves on compare units. */
	static void freeEmpty()
	{
		if (emptyTexture != 0)
		{
			glDeleteTextures(emptyTexture);
			glDeleteSamplers(emptySampler);
			emptyTexture = emptySampler = 0;
		}
	}
}
