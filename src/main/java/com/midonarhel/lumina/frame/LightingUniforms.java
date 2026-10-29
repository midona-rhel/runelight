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
import static org.lwjgl.opengl.GL33C.glGetUniformLocation;
import static org.lwjgl.opengl.GL33C.glUniform1f;
import static org.lwjgl.opengl.GL33C.glUniform1i;
import static org.lwjgl.opengl.GL33C.glUniform3f;

/**
 * The uniform locations of one shader program for the {@link Lighting} values (frag.glsl, fog.glsl, sky.glsl,
 * output.glsl), and their upload. Locations a program does not use are -1, which GL ignores.
 */
public final class LightingUniforms
{
	private final int ambientColor, ambientStrength, lightColor, lightStrength, lightDir;
	private final int underglowColor, underglowStrength;
	private final int useFog, fogDepth, fogColor, groundFogStart, groundFogEnd, groundFogOpacity, lightningBrightness;
	private final int skyEnabled, zenithColor, horizonColor, sunColor, customGradient, horizonWidth, skySunDir, moonDir;
	private final int moonDiskColor, moonIllumination, moonVisibility, skyFogColor, skyFogDensity, skyFogHorizonHeight;
	private final int groundFogLight, skyVisibility, starHorizonHeight;

	public LightingUniforms(int program)
	{
		ambientColor = glGetUniformLocation(program, "ambientColor");
		ambientStrength = glGetUniformLocation(program, "ambientStrength");
		lightColor = glGetUniformLocation(program, "lightColor");
		lightStrength = glGetUniformLocation(program, "lightStrength");
		lightDir = glGetUniformLocation(program, "lightDir");
		underglowColor = glGetUniformLocation(program, "underglowColor");
		underglowStrength = glGetUniformLocation(program, "underglowStrength");
		useFog = glGetUniformLocation(program, "useFog");
		fogDepth = glGetUniformLocation(program, "fogDepth");
		fogColor = glGetUniformLocation(program, "fogColor");
		groundFogStart = glGetUniformLocation(program, "groundFogStart");
		groundFogEnd = glGetUniformLocation(program, "groundFogEnd");
		groundFogOpacity = glGetUniformLocation(program, "groundFogOpacity");
		lightningBrightness = glGetUniformLocation(program, "lightningBrightness");
		skyEnabled = glGetUniformLocation(program, "skyEnabled");
		zenithColor = glGetUniformLocation(program, "skyZenithColor");
		horizonColor = glGetUniformLocation(program, "skyHorizonColor");
		sunColor = glGetUniformLocation(program, "skySunColor");
		customGradient = glGetUniformLocation(program, "skyCustomGradient");
		horizonWidth = glGetUniformLocation(program, "skyHorizonWidth");
		skySunDir = glGetUniformLocation(program, "skySunDir");
		moonDir = glGetUniformLocation(program, "skyMoonDir");
		moonDiskColor = glGetUniformLocation(program, "skyMoonDiskColor");
		moonIllumination = glGetUniformLocation(program, "skyMoonIllumination");
		moonVisibility = glGetUniformLocation(program, "skyMoonVisibility");
		skyFogColor = glGetUniformLocation(program, "skyFogColor");
		skyFogDensity = glGetUniformLocation(program, "skyFogDensity");
		skyFogHorizonHeight = glGetUniformLocation(program, "skyFogHorizonHeight");
		groundFogLight = glGetUniformLocation(program, "skyGroundFogLight");
		skyVisibility = glGetUniformLocation(program, "skyVisibility");
		starHorizonHeight = glGetUniformLocation(program, "skyStarHorizonHeight");
	}

	/**
	 * Uploads the values; the program must be in use.
	 */
	public void upload(Lighting l)
	{
		vec3(ambientColor, l.ambientColor);
		glUniform1f(ambientStrength, l.ambientStrength);
		vec3(lightColor, l.lightColor);
		glUniform1f(lightStrength, l.lightStrength);
		vec3(lightDir, l.lightDir);
		vec3(underglowColor, l.underglowColor);
		glUniform1f(underglowStrength, l.underglowStrength);
		glUniform1i(useFog, l.useFog ? 1 : 0);
		glUniform1f(fogDepth, l.fogDepth);
		vec3(fogColor, l.fogColor);
		glUniform1f(groundFogStart, l.groundFogStart);
		glUniform1f(groundFogEnd, l.groundFogEnd);
		glUniform1f(groundFogOpacity, l.groundFogOpacity);
		glUniform1f(lightningBrightness, l.lightningBrightness);
		glUniform1i(skyEnabled, l.skyEnabled ? 1 : 0);
		vec3(zenithColor, l.zenithColor);
		vec3(horizonColor, l.horizonColor);
		vec3(sunColor, l.sunColor);
		glUniform1f(customGradient, l.customGradient);
		glUniform1f(horizonWidth, l.horizonWidth);
		vec3(skySunDir, l.skySunDir);
		vec3(moonDir, l.moonDir);
		vec3(moonDiskColor, l.moonDiskColor);
		glUniform1f(moonIllumination, l.moonIllumination);
		glUniform1f(moonVisibility, l.moonVisibility);
		vec3(skyFogColor, l.skyFogColor);
		glUniform1f(skyFogDensity, l.skyFogDensity);
		glUniform1f(skyFogHorizonHeight, l.skyFogHorizonHeight);
		vec3(groundFogLight, l.groundFogLight);
		glUniform1f(skyVisibility, l.skyVisibility);
		glUniform1f(starHorizonHeight, l.starHorizonHeight);
	}

	private static void vec3(int location, float[] v)
	{
		glUniform3f(location, v[0], v[1], v[2]);
	}
}
