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
package com.midonarhel.lumina.config;

import lombok.RequiredArgsConstructor;

/**
 * Where the diffuse (base) colours come from. See {@link com.midonarhel.lumina.geometry.DiffuseColors}, the one place
 * that applies it.
 */
@RequiredArgsConstructor
public enum ColorMode
{
	/**
	 * 117 HD's colours: its undo of vanilla shading with the brightness caps (as the shading mode allows), model
	 * colour overrides, inherited tile colours, textured face lightness 90 and tile override colour changes.
	 */
	HD117("117 HD"),
	/**
	 * The stock GPU plugin's colours: the game's own colours with none of 117 HD's colour changes. Models use the
	 * client's unlit face colours.
	 */
	GPU("GPU"),
	;

	private final String name;

	@Override
	public String toString()
	{
		return name;
	}
}
