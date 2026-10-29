/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
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
package com.midonarhel.lumina.scene.sky;

import com.midonarhel.lumina.scene.environment.Environment;

/**
 * 117 HD's SkyState (rs117.hd.scene.daylight_cycle.SkyState), without the fields of features not ported.
 */
public final class SkyState
{
	public boolean cycleActive;
	public long utcMillis;
	public final float[] latLon = new float[2];
	public long transitionId;
	public Environment fromEnvironment;
	public Environment toEnvironment;
	public float transitionProgress;
	public float moonDirectionalStrength;
	public final float[] sunAngles = new float[2];
	public final float[] moonAngles = new float[2];
	public final float[] shadowAngles = new float[2];
	public final float[] sunDirection = new float[3];
	public final float[] moonDirection = new float[3];
	public final float[] moonSurfaceLightDirection = new float[3];
	public float moonIllumination;
	public float moonLightIllumination;
	public float sunAltitudeDegrees;
	public float moonAltitudeDegrees;
	public float moonVisibility;

	public static class GradientSample
	{
		public final float[] zenith = new float[3];
		public final float[] horizon = new float[3];
		public final float[] sunGlow = new float[3];
	}

	public static final class LightingSample extends GradientSample
	{
		public final SkyState sky = new SkyState();
		public final float[] referenceFogColorLinear = new float[3];
	}
}
