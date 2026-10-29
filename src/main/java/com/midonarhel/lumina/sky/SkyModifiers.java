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

/**
 * The weather's seam into {@link SkyModel}: the old renderer's weatherOvercast and weatherLight uniforms. All zero
 * is the clear sky, and every function of the model is the identity then. Nothing fills these yet (weather is a
 * later segment); {@link #NEUTRAL} is what the sky source passes.
 */
public final class SkyModifiers
{
	public static final SkyModifiers NEUTRAL = new SkyModifiers();

	/** Cloud cover, 0 clear to 1 fully overcast. */
	public float overcast;
	/** What falls from the cover, 0 rain to 1 snow. */
	public float snow;
	/** A milky sky with softer light, 0 to 1. */
	public float haze;
	/** -1 cold to 1 amber (dust, ash, heat). */
	public float warmth;
	/** Sky saturation offset, 0 as authored. */
	public float saturation;
	/** The sun's strength minus 1: above 0 is a harsh sun. */
	public float sunStrength;

	public boolean isNeutral()
	{
		return overcast == 0 && snow == 0 && haze == 0 && warmth == 0 && saturation == 0 && sunStrength == 0;
	}

	public boolean sameAs(SkyModifiers o)
	{
		return overcast == o.overcast && snow == o.snow && haze == o.haze && warmth == o.warmth
			&& saturation == o.saturation && sunStrength == o.sunStrength;
	}
}
