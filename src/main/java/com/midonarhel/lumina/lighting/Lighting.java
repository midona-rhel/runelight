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
package com.midonarhel.lumina.lighting;

/**
 * The scene's light, fog and sky for the current frame: the single place these values flow through on the Java side.
 * A {@link LightingSource} fills it once per frame and the plugin uploads it unchanged as the scene and sky shaders'
 * uniforms. The fields are 117 HD's global and sky uniform block entries of the same names (UBOGlobal, UBOSky).
 * <p>
 * Colours are linear RGB. Directions are in world space with Y pointing down; {@link #lightDir} points from a surface
 * towards the light, {@link #skySunDir} and {@link #moonDir} are 117 HD's sky directions (Y up).
 */
public final class Lighting
{
	// UBOGlobal
	public final float[] ambientColor = {1, 1, 1};
	public float ambientStrength = 1;
	public final float[] lightColor = {1, 1, 1};
	public float lightStrength;
	public final float[] lightDir = {0, -1, 0};
	public final float[] underglowColor = new float[3];
	public float underglowStrength;
	public boolean useFog;
	public float fogDepth;
	public final float[] fogColor = new float[3];
	public float groundFogStart;
	public float groundFogEnd;
	public float groundFogOpacity;
	public float lightningBrightness;
	/**
	 * Whether the vanilla skybox model is drawn instead of the sky (117 HD's shouldRenderVanillaSkybox).
	 */
	public boolean vanillaSkybox;

	// UBOSky (the parts scene fog and the sky background read)
	public boolean skyEnabled;
	public final float[] zenithColor = new float[3];
	public final float[] horizonColor = new float[3];
	public final float[] sunColor = new float[3];
	public float customGradient;
	public float horizonWidth;
	public final float[] skySunDir = new float[3];
	public final float[] moonDir = new float[3];
	public final float[] moonDiskColor = new float[3];
	public float moonIllumination;
	public float moonVisibility;
	public final float[] skyFogColor = new float[3];
	public float skyFogDensity;
	public float skyFogHorizonHeight;
	public final float[] groundFogLight = new float[3];
	public float skyVisibility;
	public float starHorizonHeight;
}
