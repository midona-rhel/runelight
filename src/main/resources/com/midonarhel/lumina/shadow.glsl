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

// Lumina's shadow receiver (com.midonarhel.lumina.shadow.ShadowRenderer fills the uniforms through lighting.Lighting).
// The static map holds the scenery, the dynamic map this frame's moving models; a receiver is as lit as the darker of
// the two says. Each map: the fragment, pushed off its surface along the normal by a texel and a half, is projected
// into the map's (u, v, depth); a 3x3 tap box of hardware-compared bilinear fetches (sampler2DShadow) against the
// depth less a two-texel bias gives the lit fraction; the result fades to lit over the outer 8% of the map and is
// lit outside it. PCSS softening is the next segment.

uniform int shadowsOn;             // bit 1: the static map holds a scene, bit 2: the dynamic map was drawn this frame
uniform mat4 shadowStaticProj;     // local world -> (u, v, depth) in [0,1]
uniform mat4 shadowDynamicProj;
uniform sampler2DShadow shadowStaticMap;
uniform sampler2DShadow shadowDynamicMap;
uniform vec2 shadowTexel;          // one texel in uv: static, dynamic
uniform vec2 shadowBias;           // the depth bias in [0,1] depth units: static, dynamic
uniform float shadowNormalOffset;  // world units along the normal

float luminaShadowMapLit(sampler2DShadow map, mat4 proj, float texel, float bias, vec3 p) {
  vec4 lc = proj * vec4(p, 1.0);
  vec2 uv = lc.xy;
  float z = lc.z - bias;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || z >= 1.0)
    return 1.0;
  float edge = smoothstep(0.0, 0.08, min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y)));
  float lit = 0.0;
  for (int y = -1; y <= 1; ++y)
    for (int x = -1; x <= 1; ++x)
      lit += texture(map, vec3(uv + vec2(x, y) * texel, z));
  return mix(1.0, lit / 9.0, edge);
}

// How lit the point is by the caster, 1 in full light, 0 in full shadow; n is the shading normal (unit, y down).
float luminaShadowLit(vec3 p, vec3 n) {
  if (shadowsOn == 0)
    return 1.0;
  vec3 q = p + n * shadowNormalOffset;
  float lit = 1.0;
  if ((shadowsOn & 1) != 0)
    lit = luminaShadowMapLit(shadowStaticMap, shadowStaticProj, shadowTexel.x, shadowBias.x, q);
  if ((shadowsOn & 2) != 0)
    lit = min(lit, luminaShadowMapLit(shadowDynamicMap, shadowDynamicProj, shadowTexel.y, shadowBias.y, q));
  return lit;
}
