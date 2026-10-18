// Half resolution geometry, written by the AO pass (ao_frag.glsl) for the top-left pixel of every 2x2 block:
// r AO, g NEAR / view depth (0 for sky), ba octahedral normal. The composite's depth- and normal-aware
// upsamples of AO and fog read their taps' geometry from here with one small fetch instead of the full resolution
// depth and normal. Needs frame.glsl.

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
