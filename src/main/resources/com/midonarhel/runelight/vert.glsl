/*
 * Copyright (c) 2018, Adam <Adam@sigterm.info>
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

//#define ZBUF_DEBUG
//#define BIAS_DEBUG

#include texture_config

// smallest unit of the texture which can be moved per tick. textures are all
// 128x128px - so this is equivalent to +1px
#define TEXTURE_ANIM_UNIT (1.0f / 128.0f)

#define TILE_SIZE 128.f

#define FOG_SCENE_EDGE_MIN ((-expandedMapLoadingChunks * 8 + 1) * TILE_SIZE)
#define FOG_SCENE_EDGE_MAX ((104 + expandedMapLoadingChunks * 8 - 1) * TILE_SIZE)
#define FOG_CORNER_ROUNDING 1.5
#define FOG_CORNER_ROUNDING_SQUARED (FOG_CORNER_ROUNDING * FOG_CORNER_ROUNDING)

layout(location = 0) in vec4 vertf; // w: -1 on ground vertices between overlay and underlay (GroundBlender), wind code on swaying models
layout(location = 1) in int abhsl;
layout(location = 2) in ivec4 tex;

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  float cameraX;
  float cameraY;
  float cameraZ;
};

uniform mat4 worldProj;
uniform mat4 entityProj;
uniform ivec4 entityTint;
uniform float brightness;
uniform int drawDistance;
uniform int expandedMapLoadingChunks;
uniform ivec3 base;
uniform int tick;
uniform vec2 textureAnimations[TEXTURE_COUNT];

out vec4 fColor;
noperspective centroid out float fHsl;
flat out int fTextureId;
out vec2 fUv;
out vec3 fWorldPos;
out vec3 fNormal;       // smooth vertex normal, world space; only meaningful when fNormalValid is 1
out float fNormalValid;
out vec4 fBorderColor;  // color * border weight, border weight
out vec3 fInnerColor;   // color * (1 - border weight)
flat out int fMaterial;  // SurfaceMaterials bits: emissive level in 0-1, transmissive in 2; water bed 3, land 4, snow 5-6
#ifdef ZBUF_DEBUG
out float fDepth;
#endif

#include "hsl_to_rgb.glsl"
#include "sway.glsl"
#include "edge_fade.glsl"

void main() {
  vec4 vert = vec4(vertf.xyz + base, 1);
  float a = float(abhsl >> 24 & 0xff) / 255.f;
  int bias = (abhsl >> 16) & 0xff;

  vec3 hsl = vec3(abhsl >> 10 & 63, abhsl >> 7 & 7, abhsl & 127);
  hsl += ((entityTint.xyz - hsl) * entityTint.w) / 128;
  vec3 rgb = hslToRgb(hsl);

  vec4 worldPos = entityProj * vert;

  // octahedral normal in the last texture short, 0 for flat-shaded faces (see VertexShading.java)
  int packedNormal = tex.w & 0xffff;
  if (packedNormal != 0) {
    vec2 o = vec2(float(packedNormal & 0xff), float(packedNormal >> 8 & 0xff));
    o = (o - 1.0) / 254.0 * 2.0 - 1.0;
    vec3 n = vec3(o, 1.0 - abs(o.x) - abs(o.y));
    if (n.z < 0.0)
      n.xy = (1.0 - abs(n.yx)) * vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    fNormal = mat3(entityProj) * n;
    fNormalValid = 1.0;
  } else {
    fNormal = vec3(0.0);
    fNormalValid = 0.0;
  }
  worldPos.xyz += windDisplacement(vertf.w, worldPos.xyz, fNormal, packedNormal != 0);
  vec4 screenPos = worldProj * worldPos;
  fWorldPos = worldPos.xyz;
#ifdef ZBUF_DEBUG
  fDepth = screenPos.z / screenPos.w;
#endif
  screenPos.z += float(bias) / 128.0;
  gl_Position = screenPos;
#ifdef BIAS_DEBUG
  fColor = vec4(clamp(bias, 0, 12) / 12.0, 0.0, 0.0, 1.0);
#else
  fColor = vec4(rgb, 1.f - a);
#endif
  float border = vertf.w < -0.5 ? 1.0 : 0.0;
  fBorderColor = vec4(rgb * border, border);
  fInnerColor = rgb * (1.0 - border);

  // the texture id + 1 in the low bits, material bits above it (SurfaceMaterials.java)
  fTextureId = tex.x & 0x1ff;
  fMaterial = tex.x >> 9 & 127;
  fUv = vec2(float(tex.y) / 256.f, float(tex.z) / 256.f);
  if (fTextureId > 0) {
    vec2 textureAnim = textureAnimations[min(fTextureId - 1, TEXTURE_COUNT - 1)];
    fUv += float(tick) * textureAnim * TEXTURE_ANIM_UNIT;
    fHsl = float(abhsl & 0xffff);  // only used for texture lighting, which isn't affected by tint
  } else {
    fHsl = float(((int(hsl[0]) & 63) << 10) | ((int(hsl[1]) & 7) << 7) | (int(hsl[2]) & 127));
  }
}
