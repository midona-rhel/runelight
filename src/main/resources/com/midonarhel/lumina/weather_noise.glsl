// The snow's world space noise (weather_ground.glsl), shared with the scene's vertex shader, which samples it once per
// object at its anchor tile so a tree or a rock takes snow as one body (vert.glsl's fSnowAnchor).
#ifndef WEATHER_NOISE_GLSL
#define WEATHER_NOISE_GLSL

// the snow's noise wavelength in world units
#define SNOW_SCALE 640.0

uvec2 groundHash(vec2 cell) {
  uvec2 v = uvec2(ivec2(cell)) * 1664525u + 1013904223u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  return v;
}

vec2 groundGradient(vec2 cell) {
  float a = float(groundHash(cell).x >> 8u) * (6.28318530718 / 16777216.0);
  return vec2(cos(a), sin(a));
}

// Perlin noise, about -0.7 to 0.7, and its gradient: x the noise, yz its change per unit of p
vec3 groundPerlin(vec2 p) {
  vec2 i = floor(p);
  vec2 f = p - i;
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
  vec2 ga = groundGradient(i), gb = groundGradient(i + vec2(1.0, 0.0));
  vec2 gc = groundGradient(i + vec2(0.0, 1.0)), gd = groundGradient(i + vec2(1.0, 1.0));
  float va = dot(ga, f), vb = dot(gb, f - vec2(1.0, 0.0));
  float vc = dot(gc, f - vec2(0.0, 1.0)), vd = dot(gd, f - vec2(1.0, 1.0));
  float k = va - vb - vc + vd;
  return vec3(va + u.x * (vb - va) + u.y * (vc - va) + u.x * u.y * k,
    ga + u.x * (gb - ga) + u.y * (gc - ga) + u.x * u.y * (ga - gb - gc + gd) + du * (u.yx * k + vec2(vb, vc) - va));
}

// the snow's noise: a wavelength of `scale` world units and a rougher octave on it; x the noise, yz its change per
// world unit
vec3 groundBlobs(vec2 world, float scale) {
  vec2 q = world / scale;
  vec3 a = groundPerlin(q);
  vec3 b = groundPerlin(q * 2.3 + 17.0);
  return vec3(0.7 * a.x + 0.3 * b.x, (0.7 * a.yz + 0.69 * b.yz) / scale);
}

#endif
