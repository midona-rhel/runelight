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

	private int width, height, samples;

	// what the scene draws into: fboMsaa when multisampled, else fboResolve
	private int fboMsaa;
	private int rboColor, rboNormal, rboDepth;

	private int fboResolve;
	private int texColor, texNormal, texDepth;

	void init(int width, int height, int samples)
	{
		this.width = width;
		this.height = height;
		this.samples = samples;

		texColor = texture(COLOR_FORMAT, GL_RGBA, GL_UNSIGNED_BYTE);
		texNormal = texture(NORMAL_FORMAT, GL_RGBA, GL_UNSIGNED_INT_2_10_10_10_REV);
		texDepth = texture(DEPTH_FORMAT, GL_DEPTH_COMPONENT, GL_FLOAT);

		fboResolve = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fboResolve);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texColor, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_TEXTURE_2D, texNormal, 0);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texDepth, 0);
		glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
		check("resolve");

		if (samples > 0)
		{
			fboMsaa = glGenFramebuffers();
			glBindFramebuffer(GL_FRAMEBUFFER, fboMsaa);
			rboColor = renderbuffer(COLOR_FORMAT);
			rboNormal = renderbuffer(NORMAL_FORMAT);
			rboDepth = renderbuffer(DEPTH_FORMAT);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColor);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT1, GL_RENDERBUFFER, rboNormal);
			glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepth);
			glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
			check("msaa");
			glBindRenderbuffer(GL_RENDERBUFFER, 0);
		}
	}

	void free()
	{
		if (fboMsaa != 0)
		{
			glDeleteFramebuffers(fboMsaa);
			glDeleteRenderbuffers(new int[]{rboColor, rboNormal, rboDepth});
			fboMsaa = rboColor = rboNormal = rboDepth = 0;
		}
		if (fboResolve != 0)
		{
			glDeleteFramebuffers(fboResolve);
			glDeleteTextures(new int[]{texColor, texNormal, texDepth});
			fboResolve = texColor = texNormal = texDepth = 0;
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

		glReadBuffer(GL_COLOR_ATTACHMENT0);
		glDrawBuffers(new int[]{GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1});
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
