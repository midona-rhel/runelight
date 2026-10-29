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

// 117 HD's scene_vert.glsl (zone renderer) on Lumina's vertex formats: each vertex carries the alpha/bias/HSL of all
// three vertices of its face (117 HD's face buffer), and gl_VertexID % 3 is the vertex's index in the face, since
// faces are drawn as consecutive triplets.
layout(location = 0) in vec3 vertf;
layout(location = 1) in ivec3 abhsl;  // alpha, bias and HSL of the face's vertices 0, 1, 2
layout(location = 2) in ivec2 texFlags; // vanilla texture id + 1, flags (bits 0-2 overlay of vertex 0-2, 3 upwards normals, 4 vanilla UVs, 8-15 sky visibility)
layout(location = 4) in vec2 uv;
layout(location = 3) in vec4 normalMaterial; // unnormalized normal (zero: drawn unlit, vanilla skybox), material slot

uniform mat4 worldProj;
uniform mat4 entityProj;
uniform ivec3 base;

flat out ivec3 fAlphaBiasHsl;
flat out int fFlags;
flat out int fTextureId;
flat out int fMaterial;
flat out int fLit;
centroid out vec3 fTexBlend;
out vec3 fPosition;
out vec3 fNormal;
out vec2 fUv;
out float fSkyVis;  // the vertex's sky visibility (Lumina's sky fill scale), interpolated
#ifdef ZBUF_DEBUG
out float fDepth;
#endif

void main() {
  int vertex = gl_VertexID % 3;

  // entityProj places sub-scenes (boats) in the world, rotating them too (117 HD's world view projection)
  vec4 worldPos = entityProj * vec4(vertf + base, 1);
  fPosition = worldPos.xyz;
  vec3 normal = normalMaterial.xyz;
  fNormal = mat3(entityProj) * normal;
  fLit = normal != vec3(0) ? 1 : 0;
  fMaterial = int(normalMaterial.w);

  fAlphaBiasHsl = abhsl;
  fFlags = texFlags.y;
  // read before BIAS_DEBUG below overwrites fFlags bits 8 and up with the depth bias
  fSkyVis = float((texFlags.y >> 8) & 0xff) / 255.0;
  fTexBlend = vec3(0);
  fTexBlend[vertex] = 1.0;

  vec4 clipPosition = worldProj * worldPos;
#ifdef ZBUF_DEBUG
  fDepth = clipPosition.z / clipPosition.w;
#endif
  int depthBias = (abhsl[vertex] >> 16) & 0xff;
#ifdef BIAS_DEBUG
  fFlags = depthBias << 8;
#endif
  clipPosition.z += depthBias / 128.0;
  gl_Position = clipPosition;

  fTextureId = texFlags.x;  // the vanilla texture id + 1
  // 117 HD's computeVertexUvs: geometry and vanilla UVs pass through (world UVs are computed on upload)
  fUv = uv;
}
