// Snow and rain puddles on the surfaces themselves (WeatherGround.java), applied to the albedo before the composite
// lights it. The AO pass leaves how much cover each pixel gets (weather_ground_ao.glsl); this decides where it lies.
// Snow is blobs of world space noise compared with a level that falls as the cover builds: a blob starts at a noise
// peak and grows outward, its edge crisp and two-tone like the water's foam, an outer band of shaded snow round the
// bright snow. Puddles are separate round blobs, one at most to a cell of the ground, most by walls, drawn as a water
// of their own: the sky and the world beside them reflected. Both edges are distances in pixels, worked out from the
// noise's gradient and the surface's footprint on screen, so they stay a pixel wide at any angle. Needs frame.glsl,
// rooms.glsl and water.glsl.

uniform int weatherGroundOn;
uniform vec2 weatherNoiseOrigin;  // world x and z of local (scene) 0, world units

// snow: noise wavelength in world units, and the noise level its blobs start at (peaks) and end at (all covered)
#define SNOW_SCALE 640.0
#define SNOW_FIRST 0.45
#define SNOW_ALL -0.6
// the outer band's width in world units, and its least in noise levels (a young blob has nothing else yet)
#define SNOW_RIM 14.0
#define SNOW_RIM_MIN 0.02
// how white the tops of scenery get
#define SNOW_SCENERY 0.8
#define SNOW_COLOR vec3(0.94, 0.96, 0.99)
#define SNOW_RIM_COLOR vec3(0.72, 0.8, 0.92)
// how far a snow covered surface's normal is rounded toward up
#define SNOW_ROUND 0.7
// the steepest ground snow settles on: none at the first, all of it at the second (up component of the normal); a
// steeper face is SNOW_SLOPE noise levels further from snow, so the blobs shrink on it
#define SNOW_STEEP 0.55
#define SNOW_FLAT 0.8
#define SNOW_SLOPE 2.0

// puddles: the ground's cells (world units) each hold a puddle's place, anywhere in the middle half of the cell, and
// its largest size; the puddle's size is that times a share by the cover and the noise
#define PUDDLE_CELL 288.0
#define PUDDLE_JITTER 0.5
#define PUDDLE_RADIUS vec2(18.0, 44.0)
// the lumpiness of the outline: share of the radius, noise wavelength
#define PUDDLE_LUMPS 0.4
#define PUDDLE_LUMP_SCALE 90.0
// the cover at which a puddle starts to grow, from the first place to the last, and how much more it grows over
#define PUDDLE_FIRST 0.05
#define PUDDLE_LAST 0.85
#define PUDDLE_GROW 0.25
// the share of the cover away from walls; puddles gather within about a tile of one
#define PUDDLE_OPEN 0.55
#define PUDDLE_WALL_REACH 0.9
// the up component of the normal of ground water pools on: none below the first, all above the second
#define PUDDLE_STEEP 0.955
#define PUDDLE_FLAT 0.995
// the rim: its width in world units, the albedo multipliers of the damp rim and of the water, how much of the
// reflection the rim keeps
#define PUDDLE_RIM 9.0
#define PUDDLE_DAMP 0.78
#define PUDDLE_DARKEN 0.5
#define PUDDLE_RIM_REFLECT 0.2
// how much the water reflects looking straight down; it rises to all of it at a slant as in the water's
// FRESNEL_POWER
#define PUDDLE_REFLECT 0.35

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

// four numbers 0 to 1 for a cell
vec4 groundRandom(vec2 cell) {
  uvec2 v = groundHash(cell);
  return vec4(v.x & 65535u, v.x >> 16u, v.y & 65535u, v.y >> 16u) / 65535.0;
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

// The puddles at xz: how far inside the nearest one's edge the point is, in world units (negative outside), and the
// direction that grows. wet 0 to 1, y the ground's height, flatness 0 to 1: how level it is.
float groundPuddles(vec2 xz, float y, float wet, float flatness, out vec2 grad) {
  vec2 cell = floor(xz / PUDDLE_CELL);
  float lumps = 1.0 + PUDDLE_LUMPS * groundPerlin(xz / PUDDLE_LUMP_SCALE).x / 0.7;
  float depth = -PUDDLE_RADIUS.y;
  grad = vec2(0.0);
  for (int j = -1; j <= 1; ++j) {
    for (int i = -1; i <= 1; ++i) {
      vec2 id = cell + vec2(i, j);
      vec4 h = groundRandom(id);
      vec2 c = (id + 0.5 + (h.xy - 0.5) * PUDDLE_JITTER) * PUDDLE_CELL;
      float r = mix(PUDDLE_RADIUS.x, PUDDLE_RADIUS.y, h.z);
      vec2 d = xz - c;
      float dist = length(d);
      if (dist > r * (1.0 + PUDDLE_LUMPS)) {
        continue;
      }
      float byWall = 1.0 - smoothstep(0.0, PUDDLE_WALL_REACH,
        wallDistanceAt(vec3(c.x - weatherNoiseOrigin.x, y, c.y - weatherNoiseOrigin.y)));
      float level = wet * mix(PUDDLE_OPEN, 1.0, byWall);
      float start = mix(PUDDLE_FIRST, PUDDLE_LAST, h.w);
      float inside = r * smoothstep(start, start + PUDDLE_GROW, level) * flatness * lumps - dist;
      if (inside > depth) {
        depth = inside;
        grad = -d / max(dist, 1e-3);
      }
    }
  }
  return depth;
}

// Snow and puddles on a surface before lighting: changes the albedo (sRGB) and the normal. p camera-relative, world
// the same in world space, pc the pixel's center; material: r emissive, b water (0 land terrain, 1 bed), a how much
// snow settles (materials.glsl); alpha: the transparency layer over it; cover: the AO pass's, upsampled (snow above
// 0, standing water below). Returns the puddle: x its coverage, y how much of that is water, not rim, for
// weatherPuddleWater.
vec2 weatherGroundSurface(inout vec3 color, inout vec3 n, vec3 p, vec3 world, vec2 pc, vec4 material, float alpha,
  float cover) {
  // nothing settles on glowing surfaces (lava, fire) or the water's bed
  float eligible = (1.0 - step(0.01, material.r)) * (1.0 - step(0.75, material.b)) * (1.0 - alpha);
  if (weatherGroundOn == 0 || abs(cover) < 0.01 || eligible <= 0.0) {
    return vec2(0.0);
  }
  bool land = material.b < 0.25;
  if (cover < 0.0 && !land) {
    return vec2(0.0);
  }
  float up = max(-n.y, 0.0);
  vec2 xz = world.xz + weatherNoiseOrigin;
  vec2 dx, dy;
  groundFootprint(pc, p, n, dx, dy);
  if (cover > 0.0) {
    vec3 noise = groundBlobs(xz, SNOW_SCALE);
    float f = noise.x - mix(SNOW_FIRST, SNOW_ALL, cover) - SNOW_SLOPE * (1.0 - smoothstep(SNOW_STEEP, SNOW_FLAT, up));
    vec2 df = vec2(dot(noise.yz, dx), dot(noise.yz, dy));
    float snow = groundEdge(f, df) * eligible;
    float rim = max(SNOW_RIM * length(noise.yz), SNOW_RIM_MIN);
    float inner = groundEdge(f - rim, df);
    if (land) {
      n = normalize(mix(n, vec3(0.0, -1.0, 0.0), SNOW_ROUND * snow));
    } else {
      snow *= material.a * SNOW_SCENERY;
    }
    color = mix(color, mix(SNOW_RIM_COLOR, SNOW_COLOR, inner), snow);
    return vec2(0.0);
  }
  vec2 grad;
  float depth = groundPuddles(xz, world.y, -cover, smoothstep(PUDDLE_STEEP, PUDDLE_FLAT, up), grad);
  vec2 df = vec2(dot(grad, dx), dot(grad, dy));
  float puddle = groundEdge(depth, df) * eligible;
  float water = groundEdge(depth - PUDDLE_RIM, df);
  color *= mix(1.0, mix(PUDDLE_DAMP, PUDDLE_DARKEN, water), puddle);
  n = normalize(mix(n, vec3(0.0, -1.0, 0.0), puddle));
  return vec2(puddle, water);
}

// A puddle's water over the ground lit as usual (lit, sRGB): the sky it reflects, by Fresnel, and the world beside
// it as the water's screen-space reflection finds it (light: the ground's, as lit the world beside it is about the
// same); the rim keeps a little of it. p camera-relative, pc the pixel's center.
vec3 weatherPuddleWater(vec3 lit, vec3 p, vec3 light, vec2 puddle, vec2 pc) {
  vec3 v = normalize(p);
  // the reflected ray off level water; y is down
  vec3 r = vec3(v.x, -v.y, v.z);
  float fresnel = mix(PUDDLE_REFLECT, 1.0, pow(1.0 - clamp(v.y, 0.0, 1.0), FRESNEL_POWER));
  float share = fresnel * puddle.x * mix(PUDDLE_RIM_REFLECT, 1.0, puddle.y);
  vec3 reflection = waterSkyReflection(r);
  if (waterReflections == 1) {
    vec4 hit = waterTraceReflection(p, r, light, pc);
    reflection = mix(reflection, hit.rgb, hit.a);
  }
  return linearToSrgb(mix(srgbToLinear(lit), reflection, share));
}
