#version 330

// Smooths the half resolution passes' dither (SceneTargets.denoise), one axis per pass: the AO pass's AO and sun
// shadow (their sampling is rotated by interleaved gradient noise, ign.glsl) and the volumetric fog (its step jitter),
// all in one depth-aware blur so the composite's small 2x2 upsample is left with an even, fine grain at most.
// Five taps along the axis weighted 1/2, 1, 1, 1, 1/2, each counting less the further its depth is from this texel's;
// the AO and shadow also by normal, so contact shadows keep their edges. The fog depends on depth only, so it takes
// the depth weight alone. The AO texel's geometry (depth, normal) and global illumination stay as they were.

#include "frame.glsl"
#include "halfres.glsl"

uniform sampler2D sceneShadow;  // half resolution: r lit share, g snow, b wetness, a sky visibility
uniform sampler2D sceneFog;     // half resolution (fog_frag.glsl): premultiplied scatter rgb, transmittance a
uniform ivec2 denoiseAxis;      // (1, 0) or (0, 1)
uniform int denoiseFog;         // 1: the fog is smoothed too

layout(location = 0) out vec4 FragAo;
layout(location = 1) out vec4 FragShadow;
layout(location = 2) out vec4 FragFog;

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  ivec2 last = textureSize(sceneAo, 0) - 1;
  vec4 g = texelFetch(sceneAo, px, 0);
  bool sky = g.g <= 0.0;
  float z = halfViewDepth(g);
  vec3 n = halfNormal(g);
  float aoSum = 0.0;
  vec4 shadowSum = vec4(0.0);
  float aoWsum = 0.0;
  vec4 fogSum = vec4(0.0);
  float wsum = 0.0;
  for (int i = -2; i <= 2; ++i) {
    ivec2 q = clamp(px + denoiseAxis * i, ivec2(0), last);
    vec4 gq = texelFetch(sceneAo, q, 0);
    // the sky is smoothed with the sky, the scenery with the scenery
    if ((gq.g <= 0.0) != sky) {
      continue;
    }
    float w = abs(i) == 2 ? 0.5 : 1.0;
    w /= 1.0 + abs(halfViewDepth(gq) - z) / (z * 0.02 + 1.0);
    if (denoiseFog == 1) {
      fogSum += texelFetch(sceneFog, q, 0) * w;
      wsum += w;
    }
    if (!sky) {
      float nd = max(dot(n, halfNormal(gq)), 0.0);
      nd *= nd;
      w *= nd * nd;
      aoSum += halfAo(gq) * w;
      shadowSum += texelFetch(sceneShadow, q, 0) * w;
      aoWsum += w;
    }
  }
  // this texel itself always counts, so wsum is at least 1 when the fog is on
  FragFog = denoiseFog == 1 ? fogSum / wsum : vec4(0.0);
  if (aoWsum <= 1e-4) {
    FragAo = g;
    FragShadow = texelFetch(sceneShadow, px, 0);
    return;
  }
  vec4 gi = halfGi(g);
  FragAo = halfPackGeometry(aoSum / aoWsum, g.g, n, gi.rgb, gi.a);
  FragShadow = shadowSum / aoWsum;
}
