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
#version 330

#include colorblind_mode
#include shading_config

// Lumina's sky background (frame.SkyRenderer): the old renderer's skyGradient, sunGlow, sun and moon discs and the
// void below the horizon, from the colours sky.SkySource puts in the Lighting fields named below (all linear). Drawn
// after the opaque geometry at the far depth; the output transform is the scene's. Stars are a later segment.

uniform mat4 invProjection;   // inverse of the scene's projection matrix (worldProj)
uniform vec3 skyZenithColor;
uniform vec3 skyHorizonColor;
uniform vec3 skySunColor;     // the sun's glow colour, with its visibility in it
uniform vec3 skySunDir;       // toward the sun, y up
uniform vec3 skyMoonDir;      // toward the moon, y up
uniform vec3 skyMoonDiskColor;
uniform float skySunVisibility;   // the sun's disc, 0 to 1
uniform float skyMoonVisibility;  // the moon's disc and halo, 0 to 1
uniform float skyVisibility;      // 1 under the open sky, 0 in an area without one
uniform vec3 skyFogColor;         // the area's own fog colour, shown where the sky is not

in vec2 fScreenPos;

out vec4 FragColor;

#include "lighting.glsl"
#include "output.glsl"
#include "lumina_glow.glsl"

// How far a view ray (y down) points down past where any sky could be: none at the horizon and shallow angles (the
// fog and horizon past the map's edge), all of it from VOID_BELOW.y down. What is seen there is the dark under the
// world, not sky.
#define VOID_BELOW vec2(0.35, 0.7)

// The sky's colours from the horizon to the zenith, without the sun, moon or stars. dir: normalized view ray, y down
vec3 skyGradient(vec3 dir) {
  float elev = -dir.y;
  return elev >= 0.0
    ? mix(skyHorizonColor, skyZenithColor, pow(clamp(elev, 0.0, 1.0), 0.45))
    : skyHorizonColor * mix(1.0, 0.65, clamp(-elev * 4.0, 0.0, 1.0));
}

void main() {
  // Unproject two points along the pixel ray to get the view direction. Lumina's projection has no far plane and
  // maps view depth z to NDC depth 100 / z, so NDC depths 1 and 0.5 are both finite points on the ray.
  vec4 nearWorld = invProjection * vec4(fScreenPos, 1.0, 1.0);
  vec4 farWorld = invProjection * vec4(fScreenPos, 0.5, 1.0);
  vec3 dir = normalize(farWorld.xyz / farWorld.w - nearWorld.xyz / nearWorld.w);

  vec3 sunDir = vec3(skySunDir.x, -skySunDir.y, skySunDir.z);
  vec3 moonDir = vec3(skyMoonDir.x, -skyMoonDir.y, skyMoonDir.z);

  // the gradient and the wide warm glow around the sun, visible a little below the horizon for sunsets
  vec3 color = skyGradient(dir) + luminaSunGlow(dir, sunDir);

  // sun disc
  float mu = max(dot(dir, sunDir), 0.0);
  color += vec3(1.0, 0.89, 0.70) * smoothstep(0.99985, 0.99992, mu) * skySunVisibility;

  // moon disc with a faint halo
  float mm = max(dot(dir, moonDir), 0.0);
  color += skyMoonDiskColor * (smoothstep(0.99990, 0.99995, mm) + pow(mm, 60.0) * 0.08) * skyMoonVisibility;

  // the dark under the world
  color = mix(color, vec3(0.0), smoothstep(VOID_BELOW.x, VOID_BELOW.y, dir.y));

  // areas without a sky show their own fog colour
  color = mix(skyFogColor, color, skyVisibility);

  vec3 srgb = linearToSrgb(color);
  srgb = applyColorAdjustments(srgb);
  srgb = applyOutputCorrection(srgb);
  FragColor = vec4(srgb, 1.0);
}
