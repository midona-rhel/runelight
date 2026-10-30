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
package com.midonarhel.lumina.shadow;

import com.midonarhel.lumina.frame.DrawUniforms;
import com.midonarhel.lumina.frame.Shader;
import com.midonarhel.lumina.frame.ShaderException;
import com.midonarhel.lumina.frame.Template;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The depth-only program of the shadow passes ({@code shadow_vert.glsl}, {@code shadow_frag.glsl}): positions through
 * the zone base, the entity projection and the light's projection, no fragment work. One program serves both vertex
 * formats, since both carry the position at attribute 0. Its per-draw uniforms are a {@link DrawUniforms}, so
 * {@code Zone.renderOpaque} and the VAO draws set them as they do for the scene program.
 */
public final class ShadowProgram
{
	private static final Shader SHADER = new Shader()
		.add(GL_VERTEX_SHADER, "shadow_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "shadow_frag.glsl");

	int program;
	int uniLightProj;
	DrawUniforms uniforms;

	/**
	 * @param vao a VAO to bind while validating (macOS core profile has no default VAO)
	 */
	public void compile(Template template, int vao) throws ShaderException
	{
		glBindVertexArray(vao);
		program = SHADER.compile(template);
		glBindVertexArray(0);
		uniLightProj = glGetUniformLocation(program, "lightProj");
		uniforms = new DrawUniforms(program);
	}

	public void delete()
	{
		if (program != 0)
		{
			glDeleteProgram(program);
			program = 0;
		}
	}

	boolean ready()
	{
		return program != 0;
	}

	/** Uses the program and sets the light projection; the per-draw uniforms are the draws'. */
	void use(float[] lightProj)
	{
		glUseProgram(program);
		glUniformMatrix4fv(uniLightProj, false, lightProj);
	}
}
