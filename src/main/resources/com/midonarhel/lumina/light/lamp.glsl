// Point lights share one binary visibility rule: only structural walls and roofs block.
// Positions are camera-relative; blocker geometry is scene-local, with finite height.
uniform samplerBuffer lightData;
uniform samplerBuffer lampBlockers;
#include "light/light_clusters.glsl"
#define LAMP_WALL_BIAS 2.0
struct Lamp { vec3 pos; float radius; vec3 color; vec2 origin; };
vec4 lampSphere(int li) { return texelFetch(lightData, li * 4); }
Lamp lampWith(int li, vec4 sphere) {
  return Lamp(sphere.xyz, abs(sphere.w), texelFetch(lightData, li * 4 + 1).rgb,
    texelFetch(lightData, li * 4 + 2).xy);
}
Lamp lampAt(int li) { return lampWith(li, lampSphere(li)); }
float lampFalloff(float d2, float r2) {
  float att = 1.0 - sqrt(d2 / r2);
  return att * att;
}

// Double-sided segment intersection with a triangle or merged planar rectangle. Endpoints have one unit of slack for reconstruction.
bool lampPrimitiveBlocks(int at, vec3 p, vec3 ray, float margin, bool rectangle) {
  vec4 plane = texelFetch(lampBlockers, at + 2);
  float denominator = dot(plane.xyz, ray);
  if (abs(denominator) < 0.00001) return false;
  float t = -(dot(plane.xyz, p) + plane.w) / denominator;
  if (t <= margin || t >= 1.0 - margin) return false;
  vec4 hit = vec4(p + ray * t, 1.0);
  float u = dot(texelFetch(lampBlockers, at), hit);
  float v = dot(texelFetch(lampBlockers, at + 1), hit);
  return u >= -0.00001 && v >= -0.00001 && u <= 1.00001 && v <= 1.00001
    && (rectangle || u + v <= 1.00001);
}

float lampOpen(Lamp lamp, vec3 p, vec3 wallFrom, bool inAir) {
  vec3 from = wallFrom + cameraPos;
  vec3 to = vec3(lamp.origin.x, lamp.pos.y, lamp.origin.y) + cameraPos;
  vec3 ray = to - from;
  float margin = min(0.49, 1.0 / max(length(ray), 1.0));
  vec3 inverseRay = 1.0 / mix(ray, vec3(1e-20), lessThan(abs(ray), vec3(1e-20)));
  int end = int(texelFetch(lampBlockers, 0).w);
  int node = 0;
  while (node < end) {
    vec4 lo = texelFetch(lampBlockers, node);
    vec4 hi = texelFetch(lampBlockers, node + 1);
    vec3 a = (lo.xyz - from) * inverseRay, b = (hi.xyz - from) * inverseRay;
    vec3 nearT = min(a, b), farT = max(a, b);
    float enter = max(margin, max(nearT.x, max(nearT.y, nearT.z)));
    float leave = min(1.0 - margin, min(farT.x, min(farT.y, farT.z)));
    if (enter > leave) {
      node = int(lo.w);
      continue;
    }
    int metadata = int(hi.w);
    int count = metadata & 7;
    for (int i = 0; i < count; ++i)
      if (lampPrimitiveBlocks(node + 2 + i * 3, from, ray, margin, (metadata & (8 << i)) != 0)) return 0.0;
    node += 2 + count * 3;
  }
  return 1.0;
}


vec3 lampLight(Lamp lamp, vec3 p, vec3 wallFrom, bool inAir, out vec3 ld, out float d) {
  vec3 l = lamp.pos - p;
  float d2 = dot(l, l);
  float r2 = lamp.radius * lamp.radius;
  d = sqrt(max(d2, 1.0));
  ld = l / d;
  if (d2 >= r2) {
    return vec3(0.0);
  }
  float open = lampOpen(lamp, p, wallFrom, inAir);
  return open > 0.0 ? lamp.color * (lampFalloff(d2, r2) * open) : vec3(0.0);
}

bool lampSphereReaches(vec4 sphere, vec3 p, vec3 n, bool backToo) {
  vec3 l = sphere.xyz - p;
  return dot(l, l) < sphere.w * sphere.w && (backToo || dot(n, l) > 0.0);
}

// Whether the lamp is within reach of a surface at p and in front of it by its smooth normal n, as the sun's light is
// by its normal (a face's own normal cut the light triangle by triangle across a round mesh); a surface turned away is
// lit only through, with backToo. No wrap past the horizon: it lit a roof's slope from the lanterns under its eave.
bool lampReaches(Lamp lamp, vec3 p, vec3 n, bool backToo) {
  return lampSphereReaches(vec4(lamp.pos, lamp.radius), p, n, backToo);
}
