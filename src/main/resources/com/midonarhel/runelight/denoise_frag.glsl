#version 330

// Smooths the AO pass's half resolution AO and sun shadow (SceneTargets.denoise), one axis per pass. Both are worked
// out with a 4x4 Bayer dither (ao_frag.glsl), which the composite's 2x2 upsample can't average out: it showed as grain.
// Five taps along the axis weighted 1/2, 1, 1, 1, 1/2 cover one period of the pattern exactly, so it cancels; each
// tap also counts less the further its depth and normal are from this texel's, so shadows and AO keep their edges.
// The AO texel's geometry (depth, normal) and global illumination stay as they were.

#include "frame.glsl"
#include "halfres.glsl"

uniform sampler2D sceneShadow;  // half resolution: r lit share, g snow, b wetness, a sky visibility
uniform ivec2 denoiseAxis;      // (1, 0) or (0, 1)

layout(location = 0) out vec4 FragAo;
layout(location = 1) out vec4 FragShadow;

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  ivec2 last = textureSize(sceneAo, 0) - 1;
  vec4 g = texelFetch(sceneAo, px, 0);
  vec4 shadow = texelFetch(sceneShadow, px, 0);
  if (g.g <= 0.0) {
    // the sky: nothing to smooth
    FragAo = g;
    FragShadow = shadow;
    return;
  }
  float z = halfViewDepth(g);
  vec3 n = halfNormal(g);
  float aoSum = 0.0;
  vec4 shadowSum = vec4(0.0);
  float wsum = 0.0;
  for (int i = -2; i <= 2; ++i) {
    ivec2 q = clamp(px + denoiseAxis * i, ivec2(0), last);
    vec4 gq = texelFetch(sceneAo, q, 0);
    if (gq.g <= 0.0) {
      continue;
    }
    float w = abs(i) == 2 ? 0.5 : 1.0;
    w /= 1.0 + abs(halfViewDepth(gq) - z) / (z * 0.02 + 1.0);
    float nd = max(dot(n, halfNormal(gq)), 0.0);
    nd *= nd;
    w *= nd * nd;
    aoSum += halfAo(gq) * w;
    shadowSum += texelFetch(sceneShadow, q, 0) * w;
    wsum += w;
  }
  if (wsum <= 1e-4) {
    FragAo = g;
    FragShadow = shadow;
    return;
  }
  vec4 gi = halfGi(g);
  FragAo = halfPackGeometry(aoSum / wsum, g.g, n, gi.rgb, gi.a);
  FragShadow = shadowSum / wsum;
}
