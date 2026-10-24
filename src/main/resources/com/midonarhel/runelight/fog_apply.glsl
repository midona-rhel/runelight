// Volumetric fog in the composite: depth-aware 2x2 upsample of the half resolution fog pass (fog_frag.glsl), whose
// step jitter the denoise (denoise_frag.glsl) has already smoothed, then the scene seen through it. Needs frame.glsl and sky.glsl.
// The composite's AO upsample takes the same taps and does the fog in the same loop (upsampleAoFog).

#include "halfres.glsl"

uniform int volumetricFog;
uniform sampler2D sceneFog;  // half resolution: rgb light scattered toward the camera (linear), a transmittance

// relative view depth difference at which a fog texel's weight has halved
#define FOG_UPSAMPLE_DEPTH 0.03
#define FOG_SKY_DEPTH HALF_SKY_DEPTH

float fogDepthWeight(float qz, float z) {
  return 1.0 / (1.0 + abs(qz - z) / (z * FOG_UPSAMPLE_DEPTH + 1.0));
}

vec4 upsampleFog(ivec2 px, float z) {
  vec2 hp = fullToHalf(px);
  ivec2 base = ivec2(floor(hp));
  ivec2 maxFog = textureSize(sceneFog, 0) - 1;
  vec4 sum = vec4(0.0);
  float wsum = 0.0;
  for (int y = 0; y < 2; ++y) {
    for (int x = 0; x < 2; ++x) {
      ivec2 q = clamp(base + ivec2(x, y), ivec2(0), maxFog);
      vec2 bl = max(1.0 - abs(vec2(q) - hp), 0.0);
      float w = (bl.x * bl.y + 1e-3) * fogDepthWeight(halfViewDepth(texelFetch(sceneAo, q, 0)), z);
      sum += texelFetch(sceneFog, q, 0) * w;
      wsum += w;
    }
  }
  return wsum > 1e-6 ? sum / wsum : texelFetch(sceneFog, clamp(ivec2(hp + 0.5), ivec2(0), maxFog), 0);
}

// lin: the final color of this pixel, including distance fog and transparent surfaces, in linear space; fog:
// upsampled. Returns sRGB.
vec3 applyFogLinear(vec3 lin, vec4 fog) {
  return linearToSrgb(clamp(lin * fog.a + fog.rgb, 0.0, 1.0));
}

// the same for the final color in sRGB. Where none of the texels had fog (their rays missed it: nothing scattered,
// everything shows; a only off 1 by the upsample's division) the color stays as it is instead of going to linear and
// back.
vec3 applyFogSample(vec3 srgb, vec4 fog) {
  if (fog.rgb == vec3(0.0) && fog.a >= 1.0 - 1e-6) {
    return clamp(srgb, 0.0, 1.0);
  }
  return applyFogLinear(srgbToLinear(srgb), fog);
}

vec3 applyVolumetricFog(ivec2 px, vec3 srgb, float d) {
  if (volumetricFog == 0) {
    return srgb;
  }
  return applyFogSample(srgb, upsampleFog(px, isSky(d) ? FOG_SKY_DEPTH : viewDepth(d)));
}
