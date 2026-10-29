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
package com.midonarhel.lumina.sky;

import com.midonarhel.lumina.scene.environment.Environment;

/**
 * What an area does to the sky's light: per-channel multipliers on the ambient light, the sun (and moon) light and
 * the sky fill's tint. All ones is the open overworld. Areas never move the sun.
 * <p>
 * For now derived from 117 HD's environments ({@link #derive}): an environment's ambient and directional colour times
 * strength divided by the OVERWORLD environment's, and the sky tint from the environment's fog colour relative to the
 * overworld's, clamped to at most 1 (black-fog caves get no sky fill). The environment handed in is the manager's
 * current, 3 second-transitioned one, so these move smoothly. Spatial blending is a later segment.
 */
public final class AreaLook
{
	public final float[] ambient = {1, 1, 1};
	public final float[] sun = {1, 1, 1};
	public final float[] skyTint = {1, 1, 1};

	public static final AreaLook OVERWORLD = new AreaLook();

	/**
	 * @param env          the area's (interpolated) environment
	 * @param envFog       its resolved fog colour, linear
	 * @param overworld    the overworld environment the multipliers are relative to
	 * @param overworldFog its resolved fog colour, linear
	 */
	public void derive(Environment env, float[] envFog, Environment overworld, float[] overworldFog)
	{
		float[] a = env.getAmbientColor(), oa = overworld.getAmbientColor();
		float[] d = env.getDirectionalColor(), od = overworld.getDirectionalColor();
		for (int i = 0; i < 3; i++)
		{
			ambient[i] = ratio(a[i] * env.ambientStrength, oa[i] * overworld.ambientStrength);
			sun[i] = ratio(d[i] * env.directionalStrength, od[i] * overworld.directionalStrength);
			skyTint[i] = Math.min(1, ratio(envFog[i], overworldFog[i]));
		}
	}

	public void set(AreaLook o)
	{
		System.arraycopy(o.ambient, 0, ambient, 0, 3);
		System.arraycopy(o.sun, 0, sun, 0, 3);
		System.arraycopy(o.skyTint, 0, skyTint, 0, 3);
	}

	public boolean sameAs(AreaLook o)
	{
		for (int i = 0; i < 3; i++)
		{
			if (ambient[i] != o.ambient[i] || sun[i] != o.sun[i] || skyTint[i] != o.skyTint[i])
			{
				return false;
			}
		}
		return true;
	}

	private static float ratio(float v, float reference)
	{
		return reference > 1e-6f ? Math.max(0, v / reference) : 0;
	}
}
