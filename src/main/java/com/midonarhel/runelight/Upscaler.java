package com.midonarhel.runelight;

import static org.lwjgl.opengl.GL33C.*;

/**
 * Render scale below 100%: the composite and the weather draw into this target at the scene's reduced size (with a
 * stencil for the composite's MSAA edge pass), and
 * draw() scales it up to the window (upscale_frag.glsl). The interface is drawn afterwards at the window's full
 * resolution. Not allocated at 100%, where the composite draws into the window directly.
 */
class Upscaler
{
	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "composite_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "upscale_frag.glsl");

	private int fbo, texture;
	// the composite's stencil, which marks the pixels its MSAA edge pass runs on
	private int stencil;
	private int program, uniSharpness, uniOutSize;

	void setProgram(int program)
	{
		this.program = program;
		uniSharpness = glGetUniformLocation(program, "sharpness");
		uniOutSize = glGetUniformLocation(program, "outSize");
		glUseProgram(program);
		glUniform1i(glGetUniformLocation(program, "src"), 0);
		glUseProgram(0);
	}

	void freeProgram()
	{
		glDeleteProgram(program);
		program = 0;
	}

	void init(int width, int height)
	{
		texture = glGenTextures();
		glBindTexture(GL_TEXTURE_2D, texture);
		glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, 0);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
		glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
		glBindTexture(GL_TEXTURE_2D, 0);
		fbo = glGenFramebuffers();
		glBindFramebuffer(GL_FRAMEBUFFER, fbo);
		glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texture, 0);
		stencil = glGenRenderbuffers();
		glBindRenderbuffer(GL_RENDERBUFFER, stencil);
		glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, width, height);
		glBindRenderbuffer(GL_RENDERBUFFER, 0);
		glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, stencil);
		glDrawBuffers(GL_COLOR_ATTACHMENT0);
		int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
		if (status != GL_FRAMEBUFFER_COMPLETE)
		{
			free();
			throw new RuntimeException("Upscale framebuffer incomplete: 0x" + Integer.toHexString(status));
		}
	}

	void free()
	{
		if (fbo != 0)
		{
			glDeleteFramebuffers(fbo);
			glDeleteTextures(texture);
			glDeleteRenderbuffers(stencil);
			fbo = texture = stencil = 0;
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

	/**
	 * Scale the target up into the framebuffer bound for drawing, which is outWidth x outHeight. A fullscreen
	 * triangle's VAO must be bound.
	 */
	void draw(int outWidth, int outHeight, float sharpness)
	{
		glViewport(0, 0, outWidth, outHeight);
		glUseProgram(program);
		glUniform1f(uniSharpness, sharpness);
		glUniform2f(uniOutSize, outWidth, outHeight);
		glActiveTexture(GL_TEXTURE0);
		glBindTexture(GL_TEXTURE_2D, texture);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glBindTexture(GL_TEXTURE_2D, 0);
	}
}
