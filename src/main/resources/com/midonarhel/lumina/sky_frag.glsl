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
#version 330

#include colorblind_mode
#include shading_config

// 117 HD's sky_frag.glsl with the sky background only: sampleSky's background equals foggedSkyColor (the gradient,
// sun glow and sky fog); the sun and moon disks, the moon halo, stars, nebulae and aurorae are not drawn.

uniform mat4 invProjection; // inverse of the scene's projection matrix (worldProj)
uniform vec3 fogColor;      // 117 HD's UBOGlobal fogColor (linear)

in vec2 fScreenPos;

out vec4 FragColor;

#include "lighting.glsl"
#include "output.glsl"
#include "sky.glsl"

void main() {
  vec3 color = fogColor;

  if (skyEnabled != 0) {
    // Unproject two points along the pixel ray to get the view direction. Lumina's projection has no far plane
    // and maps view depth z to NDC depth 100 / z, so NDC depths 1 and 0.5 are both finite points on the ray.
    vec4 nearWorld = invProjection * vec4(fScreenPos, 1.0, 1.0);
    vec4 farWorld = invProjection * vec4(fScreenPos, 0.5, 1.0);
    vec3 viewDir = normalize(farWorld.xyz / farWorld.w - nearWorld.xyz / nearWorld.w);
    color = foggedSkyColor(viewDir);
  }

  vec3 srgb = linearToSrgb(color);
  srgb = applyColorAdjustments(srgb);
  srgb = applyOutputCorrection(srgb);
  FragColor = vec4(srgb, 1.0);
}
