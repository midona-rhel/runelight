// Snow and rain puddles on the surfaces themselves (WeatherGround.java), applied to the albedo before the composite
// lights it. The AO pass leaves how much cover each pixel gets (weather_ground_ao.glsl); this decides where it lies.
// Both are blobs of world space noise (stable as the scene reloads) compared with a threshold that falls as the cover
// builds: a blob starts at a noise peak and grows outward, its edge crisp but antialiased. Snow is big blobs over
// terrain and floors, and a soft whitening on the tops of scenery. Puddles are small, sparse blobs on level terrain,
// most by walls. Needs rooms.glsl and frame.glsl.

#include "ground_cover.glsl"

uniform int weatherGroundOn;
uniform vec2 weatherNoiseOrigin;  // world x and z of local (scene) 0, world units

// the AO pass's cover, upsampled with the AO (composite_frag.glsl's upsampleAoFog)
float groundUpsampled = GROUND_NONE;

// snow: noise wavelength in world units, and the noise level its blobs start at (peaks) and end at (all covered)
#define SNOW_SCALE 640.0
#define SNOW_FIRST 0.45
#define SNOW_ALL -0.6
// how soft the whitening of scenery is, in noise levels, and how white its tops get
#define SNOW_SOFT 0.3
#define SNOW_SCENERY 0.8
#define SNOW_COLOR vec3(0.94, 0.96, 0.99)
// how far a snow covered surface's normal is rounded toward up
#define SNOW_ROUND 0.7
// the steepest ground snow settles on: no snow at the first, all of it at the second (up component of the normal)
#define SNOW_STEEP 0.55
#define SNOW_FLAT 0.8

// puddles: wavelength, first and last noise level, and the share of the cover away from walls
#define PUDDLE_SCALE 300.0
#define PUDDLE_FIRST 0.4
#define PUDDLE_ALL 0.05
#define PUDDLE_OPEN 0.6
// puddles gather within about a tile of a wall
#define PUDDLE_WALL_REACH 0.9
// the up component of the normal of ground water pools on: none below the first, all above the second
#define PUDDLE_STEEP 0.955
#define PUDDLE_FLAT 0.995
#define PUDDLE_DARKEN 0.55     // albedo multiplier in a puddle
#define PUDDLE_SHEEN 0.4       // sky reflection on level puddles

vec2 groundGradient(vec2 cell) {
  uvec2 v = uvec2(ivec2(cell)) * 1664525u + 1013904223u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  float a = float(v.x >> 8u) * (6.28318530718 / 16777216.0);
  return vec2(cos(a), sin(a));
}

// Perlin noise, about -0.7 to 0.7
float groundPerlin(vec2 p) {
  vec2 i = floor(p);
  vec2 f = p - i;
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  return mix(
    mix(dot(groundGradient(i), f), dot(groundGradient(i + vec2(1.0, 0.0)), f - vec2(1.0, 0.0)), u.x),
    mix(dot(groundGradient(i + vec2(0.0, 1.0)), f - vec2(0.0, 1.0)), dot(groundGradient(i + vec2(1.0, 1.0)), f - vec2(1.0, 1.0)), u.x),
    u.y);
}

// the blobs' noise: a wavelength of `scale` world units and a rougher octave on it
float groundBlobs(vec2 world, float scale) {
  vec2 q = world / scale;
  return 0.7 * groundPerlin(q) + 0.3 * groundPerlin(q * 2.3 + 17.0);
}

// Half the width of a blob's edge, in noise levels: about a pixel on the ground, where one noise level spans about a
// wavelength. p camera-relative, n the surface normal.
float groundEdge(vec3 p, vec3 n, float scale) {
  float dist = length(p);
  float pixel = dist * 2.0 / (length(vec3(viewProj[0][1], viewProj[1][1], viewProj[2][1])) * float(sceneViewport.w));
  return 1.5 * pixel / (scale * max(abs(dot(n, p)) / dist, 0.2)) + 0.004;
}

// Snow and puddles on a surface before lighting: changes the albedo (sRGB) and the normal. p camera-relative, world
// the same in world space; material: r emissive, b water (0 land terrain, 1 bed), a how much snow settles
// (materials.glsl); alpha: the transparency layer over it. Returns the puddle coverage, for weatherWetSheen.
float weatherGroundSurface(inout vec3 color, inout vec3 n, vec3 p, vec3 world, vec4 material, float alpha) {
  if (weatherGroundOn == 0) {
    return 0.0;
  }
  float cover = groundUnpack(groundUpsampled);
  float eligible = (1.0 - material.r) * (1.0 - step(0.75, material.b)) * (1.0 - alpha);
  if (abs(cover) < 0.01 || eligible <= 0.0) {
    return 0.0;
  }
  float up = max(-n.y, 0.0);
  vec2 xz = world.xz + weatherNoiseOrigin;
  bool land = material.b < 0.25;
  if (cover > 0.0) {
    float first = mix(SNOW_FIRST, SNOW_ALL, cover);
    float noise = groundBlobs(xz, SNOW_SCALE);
    float snow;
    if (land) {
      float edge = groundEdge(p, n, SNOW_SCALE);
      snow = smoothstep(first - edge, first + edge, noise) * smoothstep(SNOW_STEEP, SNOW_FLAT, up) * eligible;
      n = normalize(mix(n, vec3(0.0, -1.0, 0.0), SNOW_ROUND * snow));
    } else {
      snow = smoothstep(first - SNOW_SOFT, first + SNOW_SOFT, noise) * smoothstep(SNOW_STEEP, SNOW_FLAT, up)
        * material.a * SNOW_SCENERY * eligible;
    }
    color = mix(color, SNOW_COLOR, snow);
    return 0.0;
  }
  if (!land) {
    return 0.0;
  }
  float byWall = 1.0 - smoothstep(0.0, PUDDLE_WALL_REACH, wallDistanceAt(world));
  float level = -cover * mix(PUDDLE_OPEN, 1.0, byWall) * smoothstep(PUDDLE_STEEP, PUDDLE_FLAT, up);
  float first = mix(PUDDLE_FIRST, PUDDLE_ALL, level);
  float edge = groundEdge(p, n, PUDDLE_SCALE);
  float puddle = smoothstep(first - edge, first + edge, groundBlobs(xz, PUDDLE_SCALE)) * smoothstep(0.0, 0.05, level) * eligible;
  color *= mix(1.0, PUDDLE_DARKEN, puddle);
  n = normalize(mix(n, vec3(0.0, -1.0, 0.0), puddle));
  return puddle;
}

// Puddles reflect the sky, more at grazing angles. p camera-relative.
vec3 weatherWetSheen(vec3 lit, vec3 p, vec3 n, float puddle, float ao, vec3 sky) {
  if (puddle <= 0.0) {
    return lit;
  }
  float fresnel = 1.0 - max(dot(-normalize(p), n), 0.0);
  fresnel *= fresnel;
  fresnel *= fresnel;
  return lit + sky * (PUDDLE_SHEEN * max(-n.y, 0.0) * (0.3 + 0.7 * fresnel) * puddle * ao);
}
