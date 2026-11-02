// Snow and rain puddles on the surfaces themselves (WeatherGround.java), applied to the albedo before the composite
// lights it. The AO pass leaves how much cover each pixel gets (weather_ground_ao.glsl); this decides where it lies.
// Snow is blobs of world space noise compared with a level that falls as the cover builds: a blob starts at a noise
// peak and grows outward, smoothly with the cover, its edge crisp and two-tone like the water's foam, an outer band of
// shaded snow round the bright snow. Scenery takes it on what faces up, in the same blobs a little ahead of the
// ground's, white at full brightness. Puddles grow where water collects, in hollows and along walls (RoomMap.pool), as
// the ground soaks, their edges pushed around by noise, drawn as a water of their own reflecting the sky. Both edges
// are distances in pixels, worked out from the gradients and the surface's footprint on screen, so they stay a pixel
// wide at any angle. Needs frame.glsl, rooms.glsl and water.glsl.

#include "weather_wade.glsl"
#include "weather_noise.glsl"

uniform int weatherGroundOn;
uniform vec2 weatherNoiseOrigin;  // world x and z of local (scene) 0, world units

// snow: the noise level its blobs start at (peaks) and end at (all covered); the wavelength is weather_noise.glsl's
#define SNOW_FIRST 0.45
#define SNOW_ALL -0.6
// how far ahead of the cover the level runs: the 16 steps' rounding up on average, kept when they went
#define SNOW_LEAD 0.03125
// the outer band's width in world units, and its least in noise levels (a young blob has nothing else yet)
#define SNOW_RIM 14.0
#define SNOW_RIM_MIN 0.02
#define SNOW_COLOR vec3(0.94, 0.96, 0.99)
#define SNOW_RIM_COLOR vec3(0.72, 0.8, 0.92)
// how far a snow covered surface's normal is rounded toward up
#define SNOW_ROUND 0.7
// the steepest ground snow settles on: none at the first, all of it at the second (up component of the normal); a
// steeper face is SNOW_SLOPE noise levels further from snow, so the blobs shrink on it
#define SNOW_STEEP 0.55
#define SNOW_FLAT 0.8
#define SNOW_SLOPE 2.0
// scenery and vegetation, as the first snow had it (f204e8e2): every upward face, none at the first up component and
// all at the second, blended in between; each object as one body, by the noise at its anchor tile, so no blob's edge
// crosses a tree. It settles once the level is SNOW_SCENERY_LEAD noise levels short of the anchor's noise and is
// whole SNOW_SCENERY_GROW levels on, the object whitening evenly as the snow builds
#define SNOW_SCENERY_STEEP 0.45
#define SNOW_SCENERY_FLAT 0.85
#define SNOW_SCENERY_LEAD 0.08
#define SNOW_SCENERY_GROW 0.15

// puddles: they grow from where water collects (RoomMap.pool: hollows and the feet of walls), the edge pushed around
// by noise of this wavelength (world units) and strength so they are gloopy rather than round; the pool level at
// which the first puddles appear and how much lower it reaches when the ground is soaked
#define PUDDLE_LUMP_SCALE 110.0
#define PUDDLE_LUMPS 0.22
#define PUDDLE_FIRST 0.85
#define PUDDLE_ALL 0.25
// the up component of the normal of ground water pools on: none below the first, all above the second
#define PUDDLE_STEEP 0.955
#define PUDDLE_FLAT 0.995
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
// the same in world space, pc the pixel's center; material: r emissive, b water (0 land terrain, 1 bed), a how much
// snow settles (materials.glsl); anchor: the snow's noise at the object's anchor tile (materialSnowAnchor); alpha:
// the transparency layer over it; cover: the AO pass's, upsampled (snow above
// 0, standing water below). Returns the puddle: x its coverage, y how much of that is water, not rim, for
// weatherPuddleWater.
vec2 weatherGroundSurface(inout vec3 color, inout vec3 n, vec3 p, vec3 world, vec2 pc, vec4 material, float anchor,
  float alpha, float cover) {
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
    // the level follows the cover smoothly, so a blob's edge creeps outward as the snow builds; through the tops of
    // the 16 steps it used to take on average, so a cover shows as much snow as it did
    float level = mix(SNOW_FIRST, SNOW_ALL, min(cover + SNOW_LEAD, 1.0));
    vec2 df = vec2(dot(noise.yz, dx), dot(noise.yz, dy));
    float snow, inner;
    if (land) {
      float f = noise.x - level - SNOW_SLOPE * (1.0 - smoothstep(SNOW_STEEP, SNOW_FLAT, up));
      // none on the tiles that stay clear (SnowTiles: 117 HD's floors, bridges, paths)
      snow = groundEdge(f, df) * step(0.01, material.a);
      float rim = max(SNOW_RIM * length(noise.yz), SNOW_RIM_MIN);
      inner = groundEdge(f - rim, df);
    } else {
      float body = smoothstep(0.0, SNOW_SCENERY_GROW, anchor - level + SNOW_SCENERY_LEAD);
      snow = smoothstep(SNOW_SCENERY_STEEP, SNOW_SCENERY_FLAT, up) * body * step(0.01, material.a);
      inner = 1.0;
    }
    snow *= eligible;
    n = normalize(mix(n, vec3(0.0, -1.0, 0.0), SNOW_ROUND * snow));
    color = mix(color, mix(SNOW_RIM_COLOR, SNOW_COLOR, inner), snow);
    return vec2(0.0);
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
  return vec2(puddle, water);
}

// A puddle's water over the ground lit as usual (lit, sRGB): the sky, and the world where a screen-space ray finds it
// (light: the surface's light, linear, for the world it shows), by Fresnel; the rim keeps a little of it. Ripples from
// waded steps tilt it, and their crests catch the light. p camera-relative, pc the pixel's center.
vec3 weatherPuddleWater(vec3 lit, vec3 p, vec3 light, vec2 puddle, vec2 pc) {
  vec3 v = normalize(p);
  // the reflected ray off level water; y is down
  vec3 r = vec3(v.x, -v.y, v.z);
  vec2 ripple = puddle.y > 0.0 ? wadeRippleSlope(p.xz + cameraPos.xz) * puddle.y : vec2(0.0);
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
