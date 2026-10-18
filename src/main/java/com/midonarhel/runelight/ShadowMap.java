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
		float[] m = lightProj;
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

		// camera-relative version: lightProjRel * p_rel == lightProj * (p_rel + camera)
		System.arraycopy(m, 0, lightProjRel, 0, 16);
		for (int row = 0; row < 3; ++row)
		{
			lightProjRel[12 + row] = m[12 + row] + m[row] * cameraX + m[4 + row] * cameraY + m[8 + row] * cameraZ;
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
