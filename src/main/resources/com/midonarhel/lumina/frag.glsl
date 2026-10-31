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

//#define FRAG_UVS
//#define ZBUF_DEBUG

#include colorblind_mode

uniform sampler2DArray textures;
uniform float brightness;
uniform float smoothBanding;
uniform vec4 fogColor;
uniform float textureLightMode;

in vec4 fColor;
noperspective centroid in float fHsl;
flat in int fTextureId;
in vec2 fUv;
in vec3 fWorldPos;
in vec3 fNormal;
in vec4 fClip;
flat in int fMaterial;
#ifdef ZBUF_DEBUG
in float fDepth;
#endif

layout(std140) uniform uniforms {
  float cameraYaw;
  float cameraPitch;
  float cameraX;
  float cameraY;
  float cameraZ;
};

layout(location = 0) out vec4 FragColor;
layout(location = 1) out vec4 FragNormal;
layout(location = 2) out vec4 FragAlpha;  // transparency layer, see SceneTargets
layout(location = 3) out vec4 FragMaterial;  // the material and the face's normal, see material_target.glsl

// Transparent faces are drawn in a separate pass into their own layer and lit right here, because the composite
// pass only sees the opaque surface behind them. Same lighting as the composite; shadowed by the sun's map as 117 HD
// shadows them (scene_frag.glsl), the map of the frame before, which is the one drawn when they are (the shadow pass
// comes after the scene), with its own matrix, so it lines up but moving casters lag a frame.
uniform int alphaPass;
uniform int alphaShadows;             // 1 when there is a shadow map to shade them with
uniform sampler2DShadow alphaShadowCompare;
uniform mat4 alphaLightProj;          // world -> light clip, of the frame the map was drawn in
uniform float alphaShadowTexel;       // world units per shadow map texel
uniform float alphaShadowDepthRange;  // ShadowMap.getDepthRange
uniform int alphaDepthZeroToOne;      // 1 with glClipControl (GL 4.5)

// how much of the sun reaches world position p with normal n: 4 hardware-filtered compares a texel apart
float alphaShadow(vec3 p, vec3 n) {
  if (alphaShadows == 0) {
    return 1.0;
  }
  // normal offset keeps a surface from shadowing itself, as shadow_pcss.glsl does
  vec4 lc = alphaLightProj * vec4(p + n * alphaShadowTexel * 1.5, 1.0);
  vec2 uv = lc.xy * 0.5 + 0.5;
  float z = alphaDepthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || z >= 1.0) {
    return 1.0;
  }
  float bias = 2.0 * alphaShadowTexel / (2.0 * alphaShadowDepthRange);
  vec2 t = vec2(textureSize(alphaShadowCompare, 0));
  float lit = 0.0;
  for (int i = 0; i < 4; ++i) {
    vec2 o = vec2(i & 1, i >> 1) - 0.5;
    lit += texture(alphaShadowCompare, vec3(uv + o / t, z - bias));
  }
  float edgeFade = smoothstep(0.0, 0.08, min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y)));
  return mix(1.0, lit * 0.25, edgeFade);
}
uniform int outdoors;
uniform int proceduralSky;
uniform vec3 casterDir;
uniform int sunIsCaster;
uniform float sunAltitude;

#include "sky.glsl"
#include "materials.glsl"

// The lamps (light/lamp.glsl), as the composite has them: prepared before the scene is drawn
#include cluster_config
uniform int pointLightsEnabled;
#define cameraPos vec3(cameraX, cameraY, cameraZ)
#include "light/lamp.glsl"

// The lamps' light on a transparent face at camera-relative p with normal n, from its cluster: on its front, and
// through a thin leaf from behind
vec3 alphaLamps(vec3 p, vec3 n, vec3 faceN, float transmissive) {
  if (pointLightsEnabled == 0 || fClip.w <= 0.0) {
    return vec3(0.0);
  }
  vec2 rel = fClip.xy / fClip.w * 0.5 + 0.5;
  vec3 viewDir = normalize(-p);
  vec3 wallFrom = p + n * LAMP_WALL_BIAS;
  vec3 sum = vec3(0.0);
  LightMask lamps = clusterLights(clusterEntry(rel, fClip.w));
  int li;
  while (nextLight(lamps, li)) {
    Lamp lamp = lampAt(li);
    if (!lampReaches(lamp, p, n, faceN, transmissive > 0.0)) {
      continue;
    }
    vec3 ld;
    float d;
    vec3 c = lampLight(lamp, p, wallFrom, ld, d);
    float ndl = dot(n, ld);
    sum += c * (lampWrap(lamp, ndl, d) + transmission(ndl, dot(-ld, viewDir), transmissive));
  }
  return sum;
}
#include "material_target.glsl"
#include "environment.glsl"
#include "edge_fade.glsl"

#include "hsl_to_rgb.glsl"

#if COLORBLIND_MODE > 0
#include "colorblind.glsl"
#endif

#ifdef ZBUF_DEBUG
float linear_depth(float depth) {
  // depth is computed as 100/z, solve for z
  float z = 100 / depth;
  return 1 - z / 10000;  // we don't have a far plane, but the client uses 10000
}
#endif

void main() {
  vec4 c;

  if (fTextureId > 0) {
    int textureIdx = fTextureId - 1;

    vec4 textureColor = texture(textures, vec3(fUv, float(textureIdx)));
    vec4 textureColor0 = textureLod(textures, vec3(fUv, float(textureIdx)), 0.f);

    if (textureColor0.a < 1.f)
      discard;

    textureColor = vec4(textureColor.rgb, 1.f);

    textureColor = pow(textureColor, vec4(brightness, brightness, brightness, 1.f));

    // textured triangles hsl is a 7 bit lightness 2-126
    float light = fHsl / 127.f;
    vec3 mul = (1.f - textureLightMode) * vec3(light) + textureLightMode * fColor.rgb;
    c = textureColor * vec4(mul, fColor.a);
  } else {
    // pick interpolated hsl or rgb depending on smooth banding setting
    vec3 hsl = vec3(int(fHsl) >> 10 & 63, int(fHsl) >> 7 & 7, int(fHsl) & 127);
    vec3 rgb = mix(fColor.rgb, hslToRgb(hsl), smoothBanding);
    c = vec4(rgb, fColor.a);
  }

#if COLORBLIND_MODE > 0
  c.rgb = colorblind(c.rgb);
#endif

  // opaque surfaces are lit and fogged later by the composite pass
  FragColor = c;

  // The triangle's own normal from screen-space derivatives, facing the camera: the normal where the vertices
  // carry none (0, see VertexShading.java), as 117 HD's legacy geometry shader substitutes the triangle's normal.
  // A normal that is there is used as uploaded, not turned toward the camera: 117 HD's zone renderer
  // (scene_frag.glsl, N = normalize(IN.normal)); back faces are culled, so what is drawn faces the camera.
  vec3 faceNormal = normalize(cross(dFdx(fWorldPos), dFdy(fWorldPos)));
  if (dot(faceNormal, vec3(cameraX, cameraY, cameraZ) - fWorldPos) < 0.0)
    faceNormal = -faceNormal;
  vec3 n = dot(fNormal, fNormal) > 1e-8 ? normalize(fNormal) : faceNormal;
  FragNormal = vec4(n * 0.5 + 0.5, c.a);
  // alpha 1 marks the sample as covered by geometry (SceneTargets.clear); the opaque pass writes without blending
  FragMaterial = materialPack(fMaterial, faceNormal, fUv * 256.0);

  if (alphaPass == 1) {
    vec3 light;
    if (outdoors == 1) {
      vec3 caster = strikeCaster(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude));
      float ndl = dot(n, casterDir);
      vec3 viewDir = normalize(vec3(cameraX, cameraY, cameraZ) - fWorldPos);
      light = (ambientLight(sunAltitude) + skyLight(sunAltitude) * max(-n.y, 0.0)) * envAmbient
        + caster * envSun * (max(ndl, 0.0) + transmission(ndl, dot(-casterDir, viewDir), materialTransmissive(fMaterial)))
        * alphaShadow(fWorldPos, n);
    } else {
      light = envCaveLight(n);
    }
    light += alphaLamps(fWorldPos - cameraPos, n, faceNormal, materialTransmissive(fMaterial));
    light = emissiveLight(light, materialEmissive(fMaterial));
    vec3 fog = envFog(outdoors == 1 && proceduralSky == 1 ? skyHorizon(sunAltitude) : fogColor.rgb, outdoors == 1,
      sunAltitude);
    float fogAmount = envFogAmount(fWorldPos - vec3(cameraX, cameraY, cameraZ), fWorldPos.xz);
    FragAlpha = vec4(mix(shade(c.rgb, light), fog, fogAmount), c.a);
    // transparent faces vanish toward the edge of the world, where the composite shows the sky behind them
    FragAlpha.a *= 1.0 - edgeFadeAmount(fWorldPos);
  }

#ifdef FRAG_UVS
  if (fTextureId > 0) {
    FragColor = vec4(fUv.x, 0, fUv.y, 1);
  }
#endif

#ifdef ZBUF_DEBUG
  float dc = linear_depth(fDepth);
  if (dc > 1.0) {
    FragColor = vec4(1, 0, 0, 1);
  } else if (dc < -1.0) {
    FragColor = vec4(0, 0, 1, 1);
  } else if (dc < 0.0) {
    FragColor = vec4(0, 1, 0, 1);
  } else {
    FragColor = vec4(dc, dc, dc, 1);
  }
#endif
}
