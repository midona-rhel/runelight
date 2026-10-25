// Half resolution geometry, written by the AO pass (ao_frag.glsl) for the top-left pixel of every 2x2 block:
// r NEAR / view depth (0 for sky), g the octahedral normal, two 12 bit numbers in one float, exact in it. The
// composite's depth- and normal-aware upsamples of AO and fog read their taps' geometry from here with one small
// fetch instead of the full resolution depth and normal. The target is RG32F (SceneTargets), written once: the AO,
// which the denoise smooths, is in the shadow target.
// Needs frame.glsl.

#include "halfmap.glsl"

uniform sampler2D sceneAo;

// view depth the upsamples give the sky
#define HALF_SKY_DEPTH 1e7

vec2 octEncode(vec3 n) {
  n /= abs(n.x) + abs(n.y) + abs(n.z);
  vec2 s = vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
  return n.z >= 0.0 ? n.xy : (1.0 - abs(n.yx)) * s;
}

vec3 octDecode(vec2 e) {
  vec3 n = vec3(e, 1.0 - abs(e.x) - abs(e.y));
  if (n.z < 0.0) {
    vec2 s = vec2(n.x >= 0.0 ? 1.0 : -1.0, n.y >= 0.0 ? 1.0 : -1.0);
    n.xy = (1.0 - abs(n.yx)) * s;
  }
  return normalize(n);
}

float halfViewDepth(vec2 g) {
  return g.x > 0.0 ? NEAR / g.x : HALF_SKY_DEPTH;
}

// two whole numbers 0..4095 into one float, exactly
float halfPack(float hi, float lo) {
  return hi * 4096.0 + lo;
}

vec2 halfUnpack(float v) {
  float hi = floor(v * (1.0 / 4096.0));
  return vec2(hi, v - hi * 4096.0);
}

vec2 halfPackGeometry(float nearOverDepth, vec3 n) {
  vec2 o = floor(clamp(octEncode(n) * 0.5 + 0.5, 0.0, 1.0) * 4095.0 + 0.5);
  return vec2(nearOverDepth, halfPack(o.x, o.y));
}

vec3 halfNormal(vec2 g) {
  return octDecode(halfUnpack(g.y) / 4095.0 * 2.0 - 1.0);
}
