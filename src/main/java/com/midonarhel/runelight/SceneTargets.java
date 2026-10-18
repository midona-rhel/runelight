package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The scene render targets: color, world-space normal and depth, as textures later passes can sample.
 * With MSAA the scene draws into multisampled renderbuffers and is resolved into the textures once per frame;
 * without MSAA it draws into the textures directly.
 */
class SceneTargets
{
	static final int COLOR_FORMAT = GL_RGBA8;
	static final int NORMAL_FORMAT = GL_RGB10_A2;
	static final int DEPTH_FORMAT = GL_DEPTH_COMPONENT32F;
	// surface material per pixel (emissive, transmissive, water), see materials.glsl and water.glsl
	static final int MATERIAL_FORMAT = GL_RGBA8;

	private int width, height, samples;

	// what the scene draws into: fboMsaa when multisampled, else fboResolve
	private int fboMsaa;
	private int rboColor, rboNormal, rboDepth, rboAlpha, rboMaterial;

	private int fboResolve;
	private int texColor, texNormal, texDepth;
	// transparent geometry, lit as it is drawn, premultiplied; blended over the lit opaque scene by the composite
	private int texAlpha;
	private int texMaterial;

	private static final int[] OPAQUE_BUFFERS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_NONE, GL_COLOR_ATTACHMENT3};
	private static final int[] ALPHA_BUFFERS = {GL_NONE, GL_NONE, GL_COLOR_ATTACHMENT2, GL_NONE};
	private static final int[] ALL_BUFFERS = {GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2, GL_COLOR_ATTACHMENT3};
	private static final float[] TRANSPARENT = {0, 0, 0, 0};

	// ambient occlusion at half resolution, upsampled depth-aware in the composite pass
	private int fboAo, texAo;
	// sun and moon shadows at half resolution, drawn by the AO pass and upsampled with it
	private int texShadow;
	private int aoWidth, aoHeight;

	void init(int width, int height, int samples)
	{
		this.width = width;
		this.height = height;
		this.samples = samples;

		texColor = texture(COLOR_FORMAT, GL_RGBA, GL_UNSIGNED_BYTE);
		texNormal = texture(NORMAL_FORMAT, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV);
		texDepth = texture(DEPTH_FORMAT, GL_DEPTH_COMPONENT, GL_FLOAT);
		texAlpha = texture(COLOR_FORMAT, GL_RGBA, GL_UNSIGNED_BYTE);
		texMaterial = texture(MATERIAL_FORMAT, GL_RGBA, GL_UNSIGNED_BYTE);

		fboResolve = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboResolve);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texColor, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texNormal, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texDepth, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_TEXTURE_2D, texAlpha, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_TEXTURE_2D, texMaterial, 0);
		glDrawBuffers(OPAQUE_BUFFERS);
		check("resolve");

		aoWidth = (width + 1) / 2;
		aoHeight = (height + 1) / 2;
		texAo = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texAo);
		// AO and the global illumination plus the half resolution depth and normal the composite's upsamples read;
		// 32 bit floats, which halfres.glsl packs whole numbers into
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, aoWidth, aoHeight, 0, GL_RGBA, GL_FLOAT, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		texShadow = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texShadow);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_R8, aoWidth, aoHeight, 0, GL_RED, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		fboAo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboAo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texAo, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texShadow, 0);
		glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
		check("ao");

		if (samples > 0)
		{
			fboMsaa = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboMsaa);
			rboColor = renderbuffer(COLOR_FORMAT);
			rboNormal = renderbuffer(NORMAL_FORMAT);
			rboDepth = renderbuffer(DEPTH_FORMAT);
			rboAlpha = renderbuffer(COLOR_FORMAT);
			rboMaterial = renderbuffer(MATERIAL_FORMAT);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColor);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_RENDERBUFFER, rboNormal);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepth);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT2, GL_RENDERBUFFER, rboAlpha);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT3, GL_RENDERBUFFER, rboMaterial);
			glDrawBuffers(OPAQUE_BUFFERS);
			check("msaa");
			glBindRenderbuffer(GL_RENDERBUFFER, 0);
		}
	}

	void free()
	{
		if (fboMsaa != 0)
		{
			glDeleteFramebuffers(fboMsaa);
			glDeleteRenderbuffers(new int[]{rboColor, rboNormal, rboDepth, rboAlpha, rboMaterial});
			fboMsaa = rboColor = rboNormal = rboDepth = rboAlpha = rboMaterial = 0;
		}
		if (fboAo != 0)
		{
			glDeleteFramebuffers(fboAo);
			glDeleteTextures(new int[]{texAo, texShadow});
			fboAo = texAo = texShadow = 0;
		}
		if (fboResolve != 0)
		{
			glDeleteFramebuffers(fboResolve);
			glDeleteTextures(new int[]{texColor, texNormal, texDepth, texAlpha, texMaterial});
			fboResolve = texColor = texNormal = texDepth = texAlpha = texMaterial = 0;
		}
	}

	boolean isInitialized()
	{
		return fboResolve != 0;
	}

	int getRenderFbo()
	{
		return samples > 0 ? fboMsaa : fboResolve;
	}

	int getAoFbo()
	{
		return fboAo;
	}

	int getAoWidth()
	{
		return aoWidth;
	}

	int getAoHeight()
	{
		return aoHeight;
	}

	/** The half resolution shadows, for the composite. */
	void bindShadow(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texShadow);
		glActiveTexture(GL_TEXTURE0);
	}

	void bindAo(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texAo);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Resolve the multisampled scene into the sampleable textures. No-op without MSAA.
	 */
	void resolve()
	{
		if (samples == 0)
		{
			return;
		}
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fboMsaa);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, fboResolve);

		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT1);
		glDrawBuffers(GL_COLOR_ATTACHMENT1);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT2);
		glDrawBuffers(GL_COLOR_ATTACHMENT2);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT3);
		glDrawBuffers(GL_COLOR_ATTACHMENT3);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);

		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glDrawBuffers(OPAQUE_BUFFERS);
	}

	/**
	 * Clear the transparency layer to nothing. Call with the render framebuffer bound, after the frame's clear,
	 * which fills every attachment with the sky color (alpha 0).
	 */
	void clearAlphaLayer()
	{
		glDrawBuffers(ALL_BUFFERS);
		glClearBufferfv(GL_COLOR, 2, TRANSPARENT);
		// The frame's clear filled the normal and material targets with the sky color too. At 0, a pixel the
		// geometry only partly covers resolves to that share of its values, which the composite divides back out
		// by the color target's alpha, the coverage.
		glClearBufferfv(GL_COLOR, 1, TRANSPARENT);
		glClearBufferfv(GL_COLOR, 3, TRANSPARENT);
		glDrawBuffers(OPAQUE_BUFFERS);
	}

	/**
	 * Route drawing to the transparency layer, or back to the opaque color and normal targets.
	 */
	void setAlphaPass(boolean alpha)
	{
		glDrawBuffers(alpha ? ALPHA_BUFFERS : OPAQUE_BUFFERS);
	}

	void bindMaterial(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texMaterial);
		glActiveTexture(GL_TEXTURE0);
	}

	void bindAlphaLayer(int unit)
	{
		glActiveTexture(GL_TEXTURE0 + unit);
		glBindTexture(GL_TEXTURE_2D, texAlpha);
		glActiveTexture(GL_TEXTURE0);
	}

	/**
	 * Bind color, normal and depth to consecutive texture units starting at {@code firstUnit}.
	 */
	void bindTextures(int firstUnit)
	{
		glActiveTexture(GL_TEXTURE0 + firstUnit);
		glBindTexture(GL_TEXTURE_2D, texColor);
		glActiveTexture(GL_TEXTURE0 + firstUnit + 1);
		glBindTexture(GL_TEXTURE_2D, texNormal);
		glActiveTexture(GL_TEXTURE0 + firstUnit + 2);
		glBindTexture(GL_TEXTURE_2D, texDepth);
		glActiveTexture(GL_TEXTURE0);
	}

	void unbindTextures(int firstUnit)
	{
		for (int i = 0; i < 3; ++i)
		{
			glActiveTexture(GL_TEXTURE0 + firstUnit + i);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
		glActiveTexture(GL_TEXTURE0);
	}

	private int texture(int internalFormat, int format, int type)
	{
		int tex = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, tex);
		glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, format, type, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		return tex;
	}

	private int renderbuffer(int internalFormat)
	{
		int rbo = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rbo);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, internalFormat, width, height);
		return rbo;
	}

	private static void check(String which)
	{
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			throw new RuntimeException("Scene " + which + " framebuffer incomplete, status 0x" + Integer.toHexString(status));
		}
	}
}
