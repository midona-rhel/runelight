package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * The scene drawn forward, like the GPU plugin's: one color and one depth buffer, multisampled when antialiasing is
 * on, that the scene shader shades into directly. resolve() copies it into the window (or the upscaler's target),
 * which also resolves the samples. Nothing else reads it.
 *
 * Depth is reversed like the deferred targets' (SceneTargets): cleared to 0, tested GL_GREATER.
 */
final class ForwardTarget
{
	private static final float[] FAR_DEPTH = {0};

	private int fbo;
	private int rboColor;
	private int rboDepth;
	private int width;
	private int height;
	private final float[] clearColor = {0, 0, 0, 1};

	void init(int width, int height, int samples)
	{
		this.width = width;
		this.height = height;

		rboColor = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboColor);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, GL_RGBA8, width, height);

		rboDepth = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, rboDepth);
		glRenderbufferStorageMultisample(GL_RENDERBUFFER, samples, SceneTargets.DEPTH_FORMAT, width, height);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);

		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rboColor);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, rboDepth);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		glBindFramebuffer(GL_FRAMEBUFFER, 0);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			free();
			throw new IllegalStateException("forward framebuffer incomplete: " + status);
		}
	}

	void free()
	{
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			fbo = 0;
		}
		if (rboColor != 0)
		{
			glDeleteRenderbuffers(rboColor);
			rboColor = 0;
		}
		if (rboDepth != 0)
		{
			glDeleteRenderbuffers(rboDepth);
			rboDepth = 0;
		}
	}

	boolean isInitialized()
	{
		return fbo != 0;
	}

	int getFbo()
	{
		return fbo;
	}

	int getWidth()
	{
		return width;
	}

	int getHeight()
	{
		return height;
	}

	/** Clear to the sky color and the far depth; expects the target bound for drawing. */
	void clear(float r, float g, float b)
	{
		clearColor[0] = r;
		clearColor[1] = g;
		clearColor[2] = b;
		glClearBufferfv(GL_COLOR, 0, clearColor);
		glClearBufferfv(GL_DEPTH, 0, FAR_DEPTH);
	}

	/** Copy the scene into the framebuffer {@code target}, the same size, resolving its samples. */
	void resolve(int target)
	{
		glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
		glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target);
		glBlitFramebuffer(0, 0, width, height, 0, 0, width, height, GL_COLOR_BUFFER_BIT, GL_NEAREST);
		glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
	}
}
