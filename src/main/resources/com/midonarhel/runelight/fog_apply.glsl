// Volumetric fog in the composite: depth-aware upsample of the half resolution fog pass (fog_frag.glsl), which
// also blurs its per-pixel step jitter, then the scene seen through it. Needs frame.glsl and sky.glsl.

uniform int volumetricFog;
uniform sampler2D sceneFog;  // half resolution: rgb light scattered toward the camera (linear), a transmittance

// relative view depth difference at which a fog texel's weight has halved
#define FOG_UPSAMPLE_DEPTH 0.03
#define FOG_SKY_DEPTH 1e7

vec4 upsampleFog(ivec2 px, float z) {
  vec2 hp = (vec2(px) + 0.5) * 0.5 - 0.5;
  ivec2 base = ivec2(floor(hp)) - 1;
  ivec2 maxFog = textureSize(sceneFog, 0) - 1;
  vec4 sum = vec4(0.0);
  float wsum = 0.0;
  for (int y = 0; y < 4; ++y) {
    for (int x = 0; x < 4; ++x) {
      ivec2 q = clamp(base + ivec2(x, y), ivec2(0), maxFog);
      float qd = texelFetch(sceneDepth, q * 2, 0).r;
      float qz = isSky(qd) ? FOG_SKY_DEPTH : viewDepth(qd);

      vec2 o = vec2(q) - hp;
      float w = exp(-dot(o, o) * 0.5);
      w *= 1.0 / (1.0 + abs(qz - z) / (z * FOG_UPSAMPLE_DEPTH + 1.0));

      sum += texelFetch(sceneFog, q, 0) * w;
      wsum += w;
    }
  }
  return wsum > 1e-6 ? sum / wsum : texelFetch(sceneFog, clamp(ivec2(hp + 0.5), ivec2(0), maxFog), 0);
}

// srgb: the final color of this pixel, including distance fog and transparent surfaces
vec3 applyVolumetricFog(ivec2 px, vec3 srgb, float d) {
  if (volumetricFog == 0) {
    return srgb;
  }
  vec4 fog = upsampleFog(px, isSky(d) ? FOG_SKY_DEPTH : viewDepth(d));
  return linearToSrgb(clamp(srgbToLinear(srgb) * fog.a + fog.rgb, 0.0, 1.0));
}
