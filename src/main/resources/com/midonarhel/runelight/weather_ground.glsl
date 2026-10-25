// Snow and rain puddles on the surfaces themselves (WeatherGround.java), applied to the albedo before the composite
// lights it. The AO pass leaves how much cover each pixel gets (weather_ground_ao.glsl); this decides where it lies.
// Snow is the water foam's pattern (ground_pattern.glsl) compared with a level that rises smoothly as the cover builds:
// it starts in the gaps between cells and spreads over their crowns until everything is covered, the edge as soft as
// the foam's. The ground's pattern is worked out here; scenery brings its own from the scene pass, laid out on the
// model at rest so it doesn't slide as plants sway. Puddles grow where water collects, in hollows and along walls
// (RoomMap.pool), as the ground soaks, their edges pushed around by noise, drawn as a water of their own reflecting the
// sky, with a damp rim. Their edge is a distance in pixels, worked out from the gradients and the
// surface's footprint on screen, so it stays a pixel wide at any angle. Raindrops land in the puddles as rings that
// spread over the water. Needs frame.glsl, rooms.glsl and water.glsl.

#include "weather_wade.glsl"
#include "ground_pattern.glsl"

uniform int weatherGroundOn;
uniform vec2 weatherNoiseOrigin;  // world x and z of local (scene) 0, world units
uniform float rainRate;           // how hard it rains here, 0 to 1 and over: the rings in the puddles

// snow: the pattern level at which it starts to settle (nothing yet) and at which everything is covered, and the foam's
// edge: a surface is covered where the level is this far above the pattern
#define SNOW_FIRST 0.0
#define SNOW_ALL 1.9
#define SNOW_EDGE 0.4
#define SNOW_COLOR vec3(0.94, 0.96, 0.99)
// how far a snow covered surface's normal is rounded toward up
#define SNOW_ROUND 0.7
// the steepest ground snow settles on: none at the first, all of it at the second (up component of the normal); a
// steeper face is SNOW_SLOPE levels further from snow, so the cells' gaps shrink on it. Foliage is not steep: its
// faces stand every way, the leaves covered whole
#define SNOW_STEEP 0.55
#define SNOW_FLAT 0.8
#define SNOW_SLOPE 2.0

// puddles: they grow from where water collects (RoomMap.pool: hollows and the feet of walls), the edge pushed around
// by noise of this wavelength (world units) and strength so they are gloopy rather than round; the pool level at
// which the first puddles appear and how much lower it reaches when the ground is soaked
#define PUDDLE_LUMP_SCALE 110.0
#define PUDDLE_LUMPS 0.22
#define PUDDLE_FIRST 0.7
#define PUDDLE_ALL 0.15
// the up component of the normal of ground water pools on: none below the first, all above the second (ground is
// rarely dead level: a gentle roll of a few degrees still holds water)
#define PUDDLE_STEEP 0.93
#define PUDDLE_FLAT 0.98
// the rim: its width in world units, the albedo multipliers of the damp rim and of the water, how much of the
// reflection the rim keeps
#define PUDDLE_RIM 9.0
#define PUDDLE_DAMP 0.78
#define PUDDLE_DARKEN 0.35
#define PUDDLE_RIM_REFLECT 0.2
// how much the water reflects looking straight down, and at most at a slant; how much of the world it shows over
// the sky where a screen-space ray finds it (dimmer than a mirror: muddy, rippled water)
#define PUDDLE_REFLECT 0.55
#define PUDDLE_REFLECT_MAX 0.85
#define PUDDLE_WORLD 0.6
// how bright the crest of a ripple is, added to the water (linear)
#define PUDDLE_CREST 0.25
// raindrop rings: the ground's cells (world units) each hold one drop at a time, anywhere in the middle half of the
// cell, so the four cells nearest a point hold every ring that can reach it (a ring reaches RAIN_RING_SPEED *
// RAIN_RING_LIFE, within three quarters of a cell). A drop lands every so many seconds (least, most) if a hash of the
// cell and the cycle is under how hard it rains. The ring spreads at a speed in world units per second and fades out
// over its life in seconds; it is this wide, and tilts the water's surface by this much at most.
#define RAIN_RING_CELL 64.0
#define RAIN_RING_PERIOD vec2(1.3, 2.6)
#define RAIN_RING_LIFE 1.1
#define RAIN_RING_SPEED 36.0
#define RAIN_RING_WIDTH 3.5
#define RAIN_RING_SLOPE 0.5
// the share of the water's rain that reaches it: from the cover under which the water is too little to be rained on
// to the cover at which it is all there
#define RAIN_RING_REACH vec2(0.05, 0.3)

vec2 groundGradient(vec2 cell) {
  float a = float(groundHash(cell).x >> 8u) * (6.28318530718 / 16777216.0);
  return vec2(cos(a), sin(a));
}

// Slope of the water's surface from the raindrops landing on it at world xz: the rings of the four cells around it, a
// gaussian ring's derivative as the waded steps' (weather_wade.glsl). Round on the ground from any camera angle,
// since it is worked out in the ground's own coordinates.
vec2 rainRingSlope(vec2 xz) {
  vec2 base = floor(xz / RAIN_RING_CELL - 0.5);
  vec2 slope = vec2(0.0);
  for (int k = 0; k < 4; ++k) {
    vec2 id = base + vec2(k & 1, k >> 1);
    uvec2 h = groundHash(id);
    float period = mix(RAIN_RING_PERIOD.x, RAIN_RING_PERIOD.y, float(h.x & 255u) / 255.0);
    float t = time / period + float(h.y & 255u) / 255.0;
    float cycle = floor(t);
    float age = (t - cycle) * period;
    if (age >= RAIN_RING_LIFE) {
      continue;
    }
    uvec2 g = groundHash(id + vec2(cycle * 7.0, cycle * 13.0));
    if (float(g.y & 65535u) / 65535.0 >= rainRate) {
      continue;
    }
    vec2 centre = (id + 0.25 + 0.5 * vec2(g.x & 65535u, g.x >> 16u) / 65535.0) * RAIN_RING_CELL;
    vec2 away = xz - centre;
    float d = length(away);
    float x = (d - RAIN_RING_SPEED * age) / RAIN_RING_WIDTH;
    float fade = 1.0 - age / RAIN_RING_LIFE;
    slope += away / max(d, 1e-3) * (-x * exp(-x * x) * fade * fade * RAIN_RING_SLOPE);
  }
  return slope;
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

// How far the surface's world position (xz) moves across one pixel on screen, right and down: the ground plane
// through p with normal n cut by the neighbouring pixels' rays. pc this pixel's center.
void groundFootprint(vec2 pc, vec3 p, vec3 n, out vec2 dx, out vec2 dy) {
  float pn = dot(n, p);
  vec3 rx = reconstructPosition(pc + vec2(1.0, 0.0), 1.0);
  vec3 ry = reconstructPosition(pc + vec2(0.0, 1.0), 1.0);
  // at a grazing angle the footprint is long, but not endless
  float cx = dot(n, rx), cy = dot(n, ry);
  cx = (cx < 0.0 ? -1.0 : 1.0) * max(abs(cx), 0.1 * length(rx));
  cy = (cy < 0.0 ? -1.0 : 1.0) * max(abs(cy), 0.1 * length(ry));
  dx = (rx * (pn / cx) - p).xz;
  dy = (ry * (pn / cy) - p).xz;
}

// How much of a pixel a blob covers: f how far inside its edge the pixel is, df how that changes across a pixel
// (right and down). A distance estimate in pixels: the edge is about a pixel wide whatever the gradient.
float groundEdge(float f, vec2 df) {
  return smoothstep(-0.7, 0.7, f / max(length(df), 1e-5));
}

// Snow and puddles on a surface before lighting: changes the albedo (sRGB) and the normal. p camera-relative, world
// the same in world space, pc the pixel's center; material: r emissive, g transmissive, b water (0 land terrain, 1
// bed), a for scenery the snow pattern at rest plus its class (frag.glsl); alpha: the transparency layer over it;
// cover: the AO pass's, upsampled (snow above 0, standing water below). Returns the puddle: x its coverage, y how much
// of that is water, not rim, z how much of the rain reaches it, for weatherPuddleWater. The
// cover is the water times the sky the AO pass found open above the pixel (the occlusion map), so none is left under
// a roof or a canopy to be rained on.
vec3 weatherGroundSurface(inout vec3 color, inout vec3 n, vec3 p, vec3 world, vec2 pc, vec4 material, float alpha,
  float cover) {
  // nothing settles on glowing surfaces (lava, fire) or the water's bed
  float eligible = (1.0 - step(0.01, material.r)) * (1.0 - step(0.75, material.b)) * (1.0 - alpha);
  if (weatherGroundOn == 0 || abs(cover) < 0.01 || eligible <= 0.0) {
    return vec3(0.0);
  }
  bool land = material.b < 0.25;
  if (cover < 0.0 && !land) {
    return vec3(0.0);
  }
  float up = max(-n.y, 0.0);
  vec2 xz = world.xz + weatherNoiseOrigin;
  vec2 dx, dy;
  groundFootprint(pc, p, n, dx, dy);
  if (cover > 0.0) {
    float level = mix(SNOW_FIRST, SNOW_ALL, cover);
    if (material.g < 0.5) {
      level -= SNOW_SLOPE * (1.0 - smoothstep(SNOW_STEEP, SNOW_FLAT, up));
    }
    float height = material.a * SNOW_STORED;
    float edge = SNOW_EDGE;
    if (land) {
      vec3 pattern = snowPattern(xz);
      height = pattern.x;
      // never sharper than a pixel
      edge = max(SNOW_EDGE, 1.4 * length(vec2(dot(pattern.yz, dx), dot(pattern.yz, dy))));
    }
    float snow = smoothstep(0.0, edge, level - height) * eligible;
    if (land) {
      n = normalize(mix(n, vec3(0.0, -1.0, 0.0), SNOW_ROUND * snow));
    }
    color = mix(color, SNOW_COLOR, snow);
    return vec3(0.0);
  }
  // the pool level the water has reached, lower as the ground soaks, and the edge's noise
  vec2 grad;
  float pool = puddlePoolAt(world, grad) * smoothstep(PUDDLE_STEEP, PUDDLE_FLAT, up);
  vec3 lumps = groundPerlin(xz / PUDDLE_LUMP_SCALE);
  float depth = pool + PUDDLE_LUMPS * lumps.x - mix(PUDDLE_FIRST, PUDDLE_ALL, -cover);
  grad += PUDDLE_LUMPS * lumps.yz / PUDDLE_LUMP_SCALE;
  vec2 df = vec2(dot(grad, dx), dot(grad, dy));
  float puddle = groundEdge(depth, df) * eligible;
  float water = groundEdge(depth - PUDDLE_RIM * length(grad), df);
  color *= mix(1.0, mix(PUDDLE_DAMP, PUDDLE_DARKEN, water), puddle);
  n = normalize(mix(n, vec3(0.0, -1.0, 0.0), puddle));
  return vec3(puddle, water, smoothstep(RAIN_RING_REACH.x, RAIN_RING_REACH.y, -cover));
}

// A puddle's water over the ground lit as usual (lit, sRGB): the sky, and the world where a screen-space ray finds it
// (light: the surface's light, linear, for the world it shows), by Fresnel; the damp ground keeps a little of it. Ripples from
// waded steps and from the raindrops tilt it, and their crests catch the light. p camera-relative, pc the pixel's
// center.
vec3 weatherPuddleWater(vec3 lit, vec3 p, vec3 light, vec3 puddle, vec2 pc) {
  vec3 v = normalize(p);
  // the reflected ray off level water; y is down
  vec3 r = vec3(v.x, -v.y, v.z);
  vec2 ripple = vec2(0.0);
  if (puddle.y > 0.0) {
    ripple = wadeRippleSlope(p.xz + cameraPos.xz);
    if (rainRate > 0.0 && puddle.z > 0.0) {
      ripple += rainRingSlope(p.xz + cameraPos.xz + weatherNoiseOrigin) * puddle.z;
    }
    ripple *= puddle.y;
  }
  r = normalize(r + vec3(ripple.x, 0.0, ripple.y));
  float fresnel = mix(PUDDLE_REFLECT, PUDDLE_REFLECT_MAX, pow(1.0 - clamp(v.y, 0.0, 1.0), FRESNEL_POWER));
  float share = fresnel * puddle.x * mix(PUDDLE_RIM_REFLECT, 1.0, puddle.y);
  vec3 reflection = waterSkyReflection(r);
  if (waterReflections == 1) {
    vec4 hit = waterTraceReflection(p, r, light, pc);
    reflection = mix(reflection, hit.rgb, hit.a * PUDDLE_WORLD);
  }
  return linearToSrgb(mix(srgbToLinear(lit), reflection, share) + PUDDLE_CREST * length(ripple) * puddle.x);
}
