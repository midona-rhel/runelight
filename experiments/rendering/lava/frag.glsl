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
#include msaa_config

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
flat in float fSnowAnchor;
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
layout(location = 4) out vec4 FragUnderwaterAlpha;
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
// through a thin leaf from behind. The face normal faceN only moves the point off its surface for the wall check;
// whether and how much a lamp lights the face goes by n, the normal the sun uses
vec3 alphaLamps(vec3 p, vec3 n, vec3 faceN, float transmissive) {
  if (pointLightsEnabled == 0) {
    return vec3(0.0);
  }
  vec3 sum = vec3(0.0);
  LightMask mask = clusterLights(clusterEntry(fClip.xy / fClip.w * 0.5 + 0.5, fClip.w));
  int li;
  while (nextLight(mask, li)) {
    Lamp lamp = lampAt(li);
    if (!lampReaches(lamp, p, n, transmissive > 0.0)) continue;
    float side = dot(faceN, lamp.pos - p) >= 0.0 ? 1.0 : -1.0;
    vec3 ld; float d;
    vec3 c = lampLight(lamp, p, p + faceN * (LAMP_WALL_BIAS * side), false, ld, d);
    float ndl = dot(n, ld);
    sum += c * (max(ndl, 0.0) + transmission(ndl, dot(-ld, normalize(-p)), transmissive));
  }
  return sum;
}

#include "material_target.glsl"
#include "environment.glsl"
#include "rooms.glsl"
#include "skylight_ao.glsl"
#include "indoor_ambient.glsl"
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


uniform sampler2D alphaWaterData;
uniform vec2 alphaWaterGrid; // extended tile offset, corners per side
uniform int alphaWaterEnabled;

vec2 alphaWaterCell(vec2 xz) { return xz / 128.0 + alphaWaterGrid.x; }
float alphaWaterHeight(vec2 cell) {
  return textureLod(alphaWaterData, (cell + 0.5) / vec2(3.0 * alphaWaterGrid.y, alphaWaterGrid.y), 0.0).r;
}
bool alphaBehindWater(vec3 world) {
  if (alphaWaterEnabled == 0) return false;
  vec3 camera = vec3(cameraX, cameraY, cameraZ);
  vec3 ray = world - camera;
  if (ray.y <= 0.001) return false;
  vec2 cell = alphaWaterCell(world.xz);
  if (any(lessThan(cell, vec2(0.0))) || any(greaterThanEqual(cell, vec2(alphaWaterGrid.y - 1.0)))) return false;
  float t = (alphaWaterHeight(cell) - camera.y) / ray.y;
  // Locate the crossing, not the object's footprint: shore geometry may stand behind a water tile.
  for (int i = 0; i < 2; ++i) {
    cell = alphaWaterCell(camera.xz + ray.xz * t);
    if (any(lessThan(cell, vec2(0.0))) || any(greaterThanEqual(cell, vec2(alphaWaterGrid.y - 1.0)))) return false;
    t = (alphaWaterHeight(cell) - camera.y) / ray.y;
  }
  cell = alphaWaterCell(camera.xz + ray.xz * t);
  if (t <= 0.0 || t >= 1.0 || any(lessThan(cell, vec2(0.0)))
      || any(greaterThanEqual(cell, vec2(alphaWaterGrid.y - 1.0)))) return false;
  return texelFetch(alphaWaterData, ivec2(floor(cell)), 0).a >= 0.5;
}

void main() {
  vec4 c;
  // the share of the pixel's samples a cut-out texture's edge covers (alpha to coverage), 1 elsewhere
  float coverage = 1.0;

  if (fTextureId > 0) {
    int textureIdx = fTextureId - 1;

    vec4 textureColor = texture(textures, vec3(fUv, float(textureIdx)));
    vec4 textureColor0 = textureLod(textures, vec3(fUv, float(textureIdx)), 0.f);

#if MSAA_SAMPLES > 0
    if (alphaPass == 0) {
      // Alpha to coverage (SceneTargets: on in the opaque pass): the samples a cut-out's edge covers, so its edge is
      // antialiased like a triangle's. The textures are drawn nearest, so the edge is where the texels change: the
      // texels' alpha blended bilinearly at the top level crosses 0.5 there, and the coverage ramps over a pixel
      // across that line. Inside the texels the alpha test kept, it is whole; where it dropped them, nothing.
      ivec2 size = textureSize(textures, 0).xy;
      vec2 st = fUv * vec2(size) - 0.5;
      ivec2 t0 = ivec2(floor(st));
      vec2 f = st - vec2(t0);
      // as the array is sampled (TextureManager): u clamped to the edge, v repeating
      int x0 = clamp(t0.x, 0, size.x - 1), x1 = clamp(t0.x + 1, 0, size.x - 1);
      int y0 = t0.y & (size.y - 1), y1 = (t0.y + 1) & (size.y - 1);
      float a00 = texelFetch(textures, ivec3(x0, y0, textureIdx), 0).a;
      float a10 = texelFetch(textures, ivec3(x1, y0, textureIdx), 0).a;
      float a01 = texelFetch(textures, ivec3(x0, y1, textureIdx), 0).a;
      float a11 = texelFetch(textures, ivec3(x1, y1, textureIdx), 0).a;
      float edge = mix(mix(a00, a10, f.x), mix(a01, a11, f.x), f.y) - 0.5;
      coverage = clamp(edge / max(fwidth(edge), 1e-4) + 0.5, 0.0, 1.0);
      if (coverage <= 0.0)
        discard;
    } else if (textureColor0.a < 1.f)
      discard;
#else
    if (textureColor0.a < 1.f)
      discard;
#endif

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

  // opaque surfaces are lit and fogged later by the composite pass; under MSAA the opaque pass's alpha is the
  // coverage alpha to coverage takes (geometry's samples keep more than 0, the clear's 0)
  FragColor = vec4(c.rgb, c.a * coverage);

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
  // all but fake water: the snow's noise at the object's anchor in a (materialSnowAnchor)
  if ((fMaterial & 24) != 24) {
    FragMaterial.a = clamp(fSnowAnchor * 0.5 + 0.5, 0.0, 1.0);
  }

  if (alphaPass == 1) {
    vec3 light;
    if (outdoors == 1) {
      vec3 caster = strikeCaster(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude));
      float ndl = dot(n, casterDir);
      vec3 viewDir = normalize(vec3(cameraX, cameraY, cameraZ) - fWorldPos);
      int room = roomOfSurface(fWorldPos, n);
      float exposure = skylightOn == 1 ? skylightVisibility(fWorldPos - cameraPos, n)
        : (room > 0 ? 0.0 : 1.0);
      light = environmentSkylight(skyLight(sunAltitude) * max(-n.y, 0.0), exposure);
      if (room > 0) light += roomWarmFill(room, fWorldPos, n);
      light += caster * envSun * (max(ndl, 0.0) + transmission(ndl, dot(-casterDir, viewDir), materialTransmissive(fMaterial)))
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
    FragUnderwaterAlpha = vec4(0.0);
    if (alphaBehindWater(fWorldPos)) {
      FragUnderwaterAlpha = FragAlpha;
      FragAlpha = vec4(0.0);
    }
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
