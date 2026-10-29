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
package com.midonarhel.lumina.config;

import lombok.RequiredArgsConstructor;

/**
 * 117 HD's MoonPhase (rs117.hd.config.MoonPhase), used by sky presets ({@code forceMoonPhase}) and as the default
 * moon phase setting (DYNAMIC).
 */
@RequiredArgsConstructor
public enum MoonPhase
{
	DYNAMIC(-1f, false),
	FULL_MOON(0.99f, false),
	WANING_GIBBOUS(0.75f, true),
	THIRD_QUARTER(0.5f, true),
	WANING_CRESCENT(0.25f, true),
	NEW_MOON(0.0f, false),
	WAXING_CRESCENT(0.25f, false),
	FIRST_QUARTER(0.5f, false),
	WAXING_GIBBOUS(0.75f, false),
	;

	public final float illuminatedFraction;
	public final boolean reverseDirection;
}
