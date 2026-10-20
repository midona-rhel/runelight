// Half resolution geometry, written by the AO pass (ao_frag.glsl) for the top-left pixel of every 2x2 block:
// g NEAR / view depth (0 for sky); r, b and a each hold a 24 bit integer, exact in a float: r AO (8 bits), the
// global illumination's weight (4) and red (12), b the octahedral normal (12 + 12), a the global illumination's
// green and blue (12 + 12, square root encoded). The composite's depth- and normal-aware upsamples of AO, GI and
// fog read their taps' geometry from here with one small fetch instead of the full resolution depth and normal.
// The target is RGBA32F (SceneTargets). Needs frame.glsl.

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

float halfViewDepth(vec4 g) {
  return g.g > 0.0 ? NEAR / g.g : HALF_SKY_DEPTH;
}

// irradiance the 12 bit square root encoding reaches (linear light units)
#define HALF_GI_RANGE 4.0

// two whole numbers 0..4095 into one float, exactly
float halfPack(float hi, float lo) {
  return hi * 4096.0 + lo;
}

vec2 halfUnpack(float v) {
  float hi = floor(v * (1.0 / 4096.0));
  return vec2(hi, v - hi * 4096.0);
}

vec4 halfPackGeometry(float ao, float nearOverDepth, vec3 n, vec3 gi, float giWeight) {
  vec3 e = floor(sqrt(clamp(gi / HALF_GI_RANGE, 0.0, 1.0)) * 4095.0 + 0.5);
  float a8 = floor(clamp(ao, 0.0, 1.0) * 255.0 + 0.5);
  float w4 = floor(clamp(giWeight, 0.0, 1.0) * 15.0 + 0.5);
  vec2 o = floor(clamp(octEncode(n) * 0.5 + 0.5, 0.0, 1.0) * 4095.0 + 0.5);
  return vec4(halfPack(a8 * 16.0 + w4, e.r), nearOverDepth, halfPack(o.x, o.y), halfPack(e.g, e.b));
}

float halfAo(vec4 g) {
  return floor(halfUnpack(g.r).x * (1.0 / 16.0)) / 255.0;
}

vec3 halfNormal(vec4 g) {
  return octDecode(halfUnpack(g.b) / 4095.0 * 2.0 - 1.0);
}

// rgb the global illumination's irradiance, a its weight
vec4 halfGi(vec4 g) {
  vec2 r = halfUnpack(g.r);
  vec3 e = vec3(r.y, halfUnpack(g.a)) / 4095.0;
  float w = r.x - floor(r.x * (1.0 / 16.0)) * 16.0;
  return vec4(e * e * HALF_GI_RANGE, w / 15.0);
}
