package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Orthographic shadow map for the single directional caster (sun or moon), centered on a focus point near
 * the player. Depth is stored linearly in both clip conventions: window depth = (z + depthRange) / (2 depthRange)
 * where z is the distance along the light direction from the focus.
 */
class ShadowMap
{
	private int size;
	private int fbo;
	private int texDepth;
	// two views of the same texture: raw depth for the PCSS blocker search, hardware compare for filtering
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
	private int cacheKey;

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
		front = building = -1;
	}

	/** Forget the static map: the scenery, levels or settings changed. */
	void invalidateCache()
	{
		front = building = -1;
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

	void finishBuild()
	{
		front = building;
		building = -1;
	}

	/**
	 * Make the front static map this frame's map: its matrices, and its depth copied into the sampled texture, ready
	 * for the moving models to be drawn over it. Leaves the sampled map's framebuffer bound for drawing.
	 */
	void useFront(float cameraX, float cameraY, float cameraZ)
	{
		View v = views[front];
		radius = v.radius;
		depthRange = v.depthRange;
		System.arraycopy(v.proj, 0, lightProj, 0, 16);
		relative(lightProj, lightProjRel, cameraX, cameraY, cameraZ);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, staticFbo[front]);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fbo);
		glBlitFramebuffer(0, 0, size, size, 0, 0, size, size, GL_DEPTH_BUFFER_BIT, GL_NEAREST);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
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

	static void unbind(int rawUnit, int compareUnit)
	{
		glActiveTexture(GL_TEXTURE0 + rawUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindSampler(rawUnit, 0);
		glActiveTexture(GL_TEXTURE0 + compareUnit);
		glBindTexture(GL_TEXTURE_2D, 0);
		glBindSampler(compareUnit, 0);
		glActiveTexture(GL_TEXTURE0);
	}
}
