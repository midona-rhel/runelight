/*
 * Copyright (c) 2023, Hooder <ahooder@protonmail.com>
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
package com.midonarhel.lumina.shading;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 117 HD's "Shading mode" setting (rs117.hd.config.ShadingMode), ported literally.
 * <ul>
 * <li>{@code unlitFaceColors}: request the client's unlit model face colours ({@code UNLIT_FACE_COLORS}) and use
 * them for models; Material.NONE's brightness becomes 0.8.</li>
 * <li>{@code undoVanillaShading}: approximately reverse vanilla's baked model shading (with the brightness caps).</li>
 * <li>{@code directionalShading}: false defines {@code DISABLE_DIRECTIONAL_SHADING} (N.L fixed at 0.7).</li>
 * </ul>
 */
@Getter
@RequiredArgsConstructor
public enum ShadingMode
{
	DEFAULT("117 HD", false, true, true),
	VANILLA("Vanilla", false, false, false),
	NONE("None", true, false, false),
	UNLIT("Experimental", true, false, true),
	;

	private final String name;
	public final boolean unlitFaceColors;
	public final boolean undoVanillaShading;
	public final boolean directionalShading;

	@Override
	public String toString()
	{
		return name;
	}
}
