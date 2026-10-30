/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

import com.midonarhel.lumina.config.ColorFilter;
import com.midonarhel.lumina.config.HdSettings;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The scene, sky and UI shader programs and the locations of their uniforms. Compiled from the shader files next to
 * the plugin class through a {@link Template}; recompiled when a setting that is a shader define changes.
 * <p>
 * The per-draw uniforms (base, entity projection, entity tint) are a {@link DrawUniforms} per program, passed to
 * {@code Zone} and {@link VAO} draws explicitly.
 */
public final class SceneProgram
{
	static final Shader PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vert.glsl")
		.add(GL_FRAGMENT_SHADER, "frag.glsl");

	static final Shader SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "sky_frag.glsl");

	// Lumina's sky pass (SkyRenderer), the same fullscreen triangle
	static final Shader LUMINA_SKY_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "sky_vert.glsl")
		.add(GL_FRAGMENT_SHADER, "lumina_sky_frag.glsl");

	static final Shader UI_PROGRAM = new Shader()
		.add(GL_VERTEX_SHADER, "vertui.glsl")
		.add(GL_FRAGMENT_SHADER, "fragui.glsl");

	public static final float[] IDENTITY = Mat4.identity();

	// 117 HD's HdPlugin.COLOR_FILTER_FADE_DURATION
	private static final float COLOR_FILTER_FADE_DURATION = 500;

	// the per-draw uniforms of the scene program (Zone and VAO draws)
	DrawUniforms sceneUniforms;

	int glProgram;
	int glUiProgram;
	int glSkyProgram;
	int glLuminaSkyProgram;

	int uniSkyInvProjection;
	int uniSkyGammaCorrection;
	int uniSkyElapsedTime;
	int uniSkyColorblindIntensity;

	int uniDrawDistance;
	int uniExpandedMapLoadingChunks;
	int uniWorldProj;
	int uniBrightness;
	int uniTex;
	int uniTexSourceDimensions;
	int uniTexTargetDimensions;
	int uniUiAlphaOverlay;
	int uniTextures;
	int uniBlockMain;
	int uniTextureLightMode;
	int uniColorblindIntensity;
	int uniUiColorblindIntensity;
	int uniDebugView;
	int uniGammaCorrection;
	int uniElapsedTime;
	int uniBlockMaterials;
	OutputUniforms sceneOutputUniforms, skyOutputUniforms;
	LightingUniforms sceneLightingUniforms, skyLightingUniforms;

	/**
	 * The uniforms of output.glsl's colour adjustments (117 HD's UBOGlobal saturation, contrast and colour filter).
	 */
	static final class OutputUniforms
	{
		private final int saturation, contrast, colorFilter, colorFilterPrevious, colorFilterFade;

		OutputUniforms(int program)
		{
			saturation = glGetUniformLocation(program, "saturation");
			contrast = glGetUniformLocation(program, "contrast");
			colorFilter = glGetUniformLocation(program, "colorFilter");
			colorFilterPrevious = glGetUniformLocation(program, "colorFilterPrevious");
			colorFilterFade = glGetUniformLocation(program, "colorFilterFade");
		}

		void upload()
		{
			glUniform1f(saturation, HdSettings.saturation);
			glUniform1f(contrast, HdSettings.contrast);
			if (HdSettings.colorFilter != ColorFilter.NONE)
			{
				glUniform1i(colorFilter, HdSettings.colorFilter.ordinal());
				glUniform1i(colorFilterPrevious, HdSettings.colorFilterPrevious.ordinal());
				long timeSinceChange = System.currentTimeMillis() - HdSettings.colorFilterChangedAt;
				glUniform1f(colorFilterFade, Math.max(0, Math.min(1, timeSinceChange / COLOR_FILTER_FADE_DURATION)));
			}
		}
	}

	/**
	 * Compiles the three programs and reads their uniform locations.
	 *
	 * @param vaoUiHandle a VAO to bind while validating: macOS core profile has no default VAO, so the shaders won't
	 *                    validate unless one is bound
	 */
	void compile(Template template, int vaoUiHandle) throws ShaderException
	{
		glBindVertexArray(vaoUiHandle);

		glProgram = PROGRAM.compile(template);
		glUiProgram = UI_PROGRAM.compile(template);
		glSkyProgram = SKY_PROGRAM.compile(template);
		glLuminaSkyProgram = LUMINA_SKY_PROGRAM.compile(template);

		glBindVertexArray(0);

		initUniforms();
	}

	private void initUniforms()
	{
		uniWorldProj = glGetUniformLocation(glProgram, "worldProj");
		sceneUniforms = new DrawUniforms(glProgram);
		uniBrightness = glGetUniformLocation(glProgram, "brightness");
		uniDrawDistance = glGetUniformLocation(glProgram, "drawDistance");
		uniExpandedMapLoadingChunks = glGetUniformLocation(glProgram, "expandedMapLoadingChunks");
		uniTextureLightMode = glGetUniformLocation(glProgram, "textureLightMode");
		uniBlockMain = glGetUniformBlockIndex(glProgram, "uniforms");
		uniTextures = glGetUniformLocation(glProgram, "textures");
		uniColorblindIntensity = glGetUniformLocation(glProgram, "colorblindIntensity");
		uniDebugView = glGetUniformLocation(glProgram, "debugView");
		uniGammaCorrection = glGetUniformLocation(glProgram, "gammaCorrection");
		sceneOutputUniforms = new OutputUniforms(glProgram);
		uniElapsedTime = glGetUniformLocation(glProgram, "elapsedTime");
		uniBlockMaterials = glGetUniformBlockIndex(glProgram, "materials");
		sceneLightingUniforms = new LightingUniforms(glProgram);

		uniSkyInvProjection = glGetUniformLocation(glSkyProgram, "invProjection");
		uniSkyGammaCorrection = glGetUniformLocation(glSkyProgram, "gammaCorrection");
		skyOutputUniforms = new OutputUniforms(glSkyProgram);
		uniSkyElapsedTime = glGetUniformLocation(glSkyProgram, "elapsedTime");
		uniSkyColorblindIntensity = glGetUniformLocation(glSkyProgram, "colorblindIntensity");
		skyLightingUniforms = new LightingUniforms(glSkyProgram);

		uniTex = glGetUniformLocation(glUiProgram, "tex");
		uniTexTargetDimensions = glGetUniformLocation(glUiProgram, "targetDimensions");
		uniTexSourceDimensions = glGetUniformLocation(glUiProgram, "sourceDimensions");
		uniUiAlphaOverlay = glGetUniformLocation(glUiProgram, "alphaOverlay");
		uniUiColorblindIntensity = glGetUniformLocation(glUiProgram, "colorblindIntensity");
	}

	void delete()
	{
		glDeleteProgram(glProgram);
		glProgram = 0;

		glDeleteProgram(glUiProgram);
		glUiProgram = 0;

		glDeleteProgram(glSkyProgram);
		glSkyProgram = 0;

		glDeleteProgram(glLuminaSkyProgram);
		glLuminaSkyProgram = 0;
	}
}
