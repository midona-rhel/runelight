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
 * The sky and its light at one sun altitude, from {@link SkyModel#evaluate}. Every colour is linear RGB. The light
 * colours are in 117 HD's diffuse light units: 1 is the full surface colour, and they already carry the sun's and
 * moon's strength windows, the area's multipliers and the brightness scale. The sky colours are what the sky pass
 * draws; {@link #skyFill} is the scale and tint {@link SkyLight} applies to the sky's radiance to get its light on
 * surfaces.
 */
public final class SkyFrame
{
	public final float[] zenith = new float[3];
	public final float[] horizon = new float[3];
	/** The sun's glow colour, including how visible the glow is at this altitude (0 at night). */
	public final float[] glow = new float[3];
	public final float[] sunColour = new float[3];
	public final float[] moonColour = new float[3];
	public final float[] ambient = new float[3];
	public final float[] skyFill = new float[3];
	/** How visible the sun's disc and glow are, 0 to 1. */
	public float sunVisibility;
	/** How visible the moon's disc and the stars are, 0 to 1. */
	public float nightVisibility;
}
