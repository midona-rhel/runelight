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

import com.midonarhel.lumina.scene.SceneData;

/**
 * Something that decides the scene's light each frame. The plugin holds exactly one source and asks it to fill
 * {@link Lighting} before uploading the lighting uniforms. Today that is {@link SkyLighting} (117 HD's environment,
 * sky and daylight-cycle lighting).
 */
public interface LightingSource
{
	/**
	 * Writes the light for the current frame. Called on the client thread, once per frame, before the scene is drawn.
	 *
	 * @param scene           the top-level scene's data, or null before one is loaded
	 * @param elapsedTime     seconds since the plugin started
	 * @param drawDistance    the draw distance in tiles
	 * @param hasSkyboxModel  whether the scene has a vanilla skybox model
	 */
	void fill(Lighting lighting, SceneData scene, double elapsedTime, int drawDistance, boolean hasSkyboxModel);
}
