#version 330

// Smooths the half resolution passes' dither (SceneTargets.denoise) in one pass: the AO pass's AO and sun shadow
// (their sampling is rotated by interleaved gradient noise, ign.glsl) and, when the sun shafts jitter it, the
// volumetric fog, all in one depth-aware blur so the composite's small 2x2 upsample is left with an even, fine grain at
// most. The 21 texels of a 5x5 square without its corners, weighted by distance as fitted to interleaved gradient
// noise (a plain 13 tap diamond left visibly more grain), each counting less the further its depth is from this
// texel's: as smooth as the two passes of a 5 tap blur this replaces. The shadow target (AO among it) is weighted by normal too, so contact shadows keep their
// edges; the fog depends on depth only, so it takes the depth weight alone. The geometry (halfres.glsl) is only read:
// it stays as the AO pass wrote it.

#include "frame.glsl"
#include "halfres.glsl"

uniform sampler2D sceneShadow;  // half resolution: r lit share, g AO, b snow and puddles, a sky visibility
uniform sampler2D sceneFog;     // half resolution (fog_frag.glsl): premultiplied scatter rgb, transmittance a
uniform int denoiseFog;         // 1: the fog is smoothed too; 2: the fog alone, at its own size (SceneTargets)

layout(location = 0) out vec4 FragShadow;
layout(location = 1) out vec4 FragFog;

// The fog at its own size, coarser than the AO targets (SceneTargets.fogRatio): the same taps and depth weights as
// the fog gets with the AO, over its own texels, each by the depth of the half texel under it.
vec4 denoiseFogAlone(ivec2 px) {
  ivec2 last = textureSize(sceneFog, 0) - 1;
  ivec2 lastHalf = textureSize(sceneAo, 0) - 1;
  vec2 g = texelFetch(sceneAo, clamp(fogToHalf(px), ivec2(0), lastHalf), 0).rg;
  bool sky = g.x <= 0.0;
  float z = halfViewDepth(g);
  vec4 sum = vec4(0.0);
  float wsum = 0.0;
  for (int y = -2; y <= 2; ++y) {
    for (int x = -2; x <= 2; ++x) {
      ivec2 a = abs(ivec2(x, y));
      if (a.x == 2 && a.y == 2) {
        continue;
      }
      float k = a.x + a.y == 0 ? 1.0 : a.x + a.y == 1 ? 1.08 : a.x == a.y ? 0.71 : a.x + a.y == 2 ? 0.79 : 0.78;
      ivec2 q = clamp(px + ivec2(x, y), ivec2(0), last);
      vec2 gq = texelFetch(sceneAo, clamp(fogToHalf(q), ivec2(0), lastHalf), 0).rg;
      if ((gq.x <= 0.0) != sky) {
        continue;
      }
      float w = k / (1.0 + abs(halfViewDepth(gq) - z) / (z * 0.02 + 1.0));
      sum += texelFetch(sceneFog, q, 0) * w;
      wsum += w;
    }
  }
  return sum / wsum;
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  if (denoiseFog == 2) {
    FragShadow = vec4(0.0);
    FragFog = denoiseFogAlone(px);
    return;
  }
  vec2 g = texelFetch(sceneAo, px, 0).rg;
  bool sky = g.x <= 0.0;
  // the sky has no AO or shadow to smooth, only fog
  if (sky && denoiseFog == 0) {
    FragShadow = texelFetch(sceneShadow, px, 0);
    FragFog = vec4(0.0);
    return;
  }
  ivec2 last = textureSize(sceneAo, 0) - 1;
  float z = halfViewDepth(g);
  vec3 n = halfNormal(g);
  vec4 shadowSum = vec4(0.0);
  float shadowWsum = 0.0;
  vec4 fogSum = vec4(0.0);
  float wsum = 0.0;
  for (int y = -2; y <= 2; ++y) {
    for (int x = -2; x <= 2; ++x) {
      ivec2 a = abs(ivec2(x, y));
      if (a.x == 2 && a.y == 2) {
        continue;
      }
      // centre, next to it, diagonal, two away, a knight's move
      float k = a.x + a.y == 0 ? 1.0 : a.x + a.y == 1 ? 1.08 : a.x == a.y ? 0.71 : a.x + a.y == 2 ? 0.79 : 0.78;
      ivec2 q = clamp(px + ivec2(x, y), ivec2(0), last);
      vec2 gq = texelFetch(sceneAo, q, 0).rg;
      // the sky is smoothed with the sky, the scenery with the scenery
      if ((gq.x <= 0.0) != sky) {
        continue;
      }
      float w = k / (1.0 + abs(halfViewDepth(gq) - z) / (z * 0.02 + 1.0));
      if (denoiseFog == 1) {
        fogSum += texelFetch(sceneFog, q, 0) * w;
        wsum += w;
      }
      if (!sky) {
        float nd = max(dot(n, halfNormal(gq)), 0.0);
        nd *= nd;
        w *= nd * nd;
        shadowSum += texelFetch(sceneShadow, q, 0) * w;
        shadowWsum += w;
      }
    }
  }
  // this texel itself always counts, so wsum is at least 1 when the fog is on
  FragFog = denoiseFog == 1 ? fogSum / wsum : vec4(0.0);
  FragShadow = shadowWsum > 1e-4 ? shadowSum / shadowWsum : texelFetch(sceneShadow, px, 0);
}
