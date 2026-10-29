/*
 * Copyright (c) 2026, Midona
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.lighting.Lighting;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Lumina's sky background pass (lumina_sky_frag.glsl with sky_vert.glsl): one fullscreen triangle at the far depth,
 * drawn after the top-level opaque geometry with the scene's GEQUAL depth test and no depth write, so it fills only
 * what nothing has covered: the gradient, the sun's glow, the sun and moon discs and the void below the horizon, from
 * the {@link Lighting} values the sky source fills. The far depth is NDC 0 under glClipControl ZERO_TO_ONE (the
 * reverse-Z far the scene clears to) and -1 under GL's default depth range (macOS), {@link #farNdcDepth}.
 */
final class SkyRenderer
{
	float farNdcDepth;

	private int program;
	private int uniInvProjection;
	private int uniGammaCorrection;
	private int uniElapsedTime;
	private int uniColorblindIntensity;
	private int uniFarDepth;
	private SceneProgram.OutputUniforms outputUniforms;
	private LightingUniforms lightingUniforms;

	/** Reads the uniform locations of a freshly compiled program. */
	void init(int program)
	{
		this.program = program;
		uniInvProjection = glGetUniformLocation(program, "invProjection");
		uniGammaCorrection = glGetUniformLocation(program, "gammaCorrection");
		uniElapsedTime = glGetUniformLocation(program, "elapsedTime");
		uniColorblindIntensity = glGetUniformLocation(program, "colorblindIntensity");
		uniFarDepth = glGetUniformLocation(program, "skyFarDepth");
		outputUniforms = new SceneProgram.OutputUniforms(program);
		lightingUniforms = new LightingUniforms(program);
	}

	/**
	 * Draws the sky. Expects the scene's state: depth test on with GL_GEQUAL, the scene FBO and viewport bound.
	 * Leaves the sky program in use.
	 *
	 * @param vao a VAO to bind (the triangle's vertices come from gl_VertexID)
	 */
	void draw(Lighting lighting, float[] invProjection, float gammaCorrection, float ditherTime, float colorblindIntensity, int vao)
	{
		glUseProgram(program);
		lightingUniforms.upload(lighting);
		glUniformMatrix4fv(uniInvProjection, false, invProjection);
		glUniform1f(uniGammaCorrection, gammaCorrection);
		outputUniforms.upload();
		glUniform1f(uniElapsedTime, ditherTime);
		glUniform1f(uniColorblindIntensity, colorblindIntensity);
		glUniform1f(uniFarDepth, farNdcDepth);

		glDisable(GL_BLEND);
		glDepthMask(false);
		glBindVertexArray(vao);
		glDrawArrays(GL_TRIANGLES, 0, 3);
		glDepthMask(true);
		glEnable(GL_BLEND);
	}
}
