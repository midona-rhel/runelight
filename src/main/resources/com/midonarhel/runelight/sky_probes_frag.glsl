#version 330

// The sky probes' update pass (SkyProbes.java), one draw per volume slice, one fragment per probe (layout: see
// sky_probe_layout.glsl). A layer's probes cast RAYS rays over the upper hemisphere, each marched against the top-down
// map of the scenery (OcclusionMap.java), the heightfield: a ray is blocked as soon as the highest scenery over a point
// of it is above that point. The visibility of the sky over the hemisphere is projected onto L1 spherical harmonics,
// four coefficients: the constant term and the three linear ones along x, up and z. The rays are different every
// update (turned, jittered along their length), and the result is blended into the stored one by the blend constant
// (SkyProbes.BLEND), so it converges on the full integral over a second or two without flicker.
//
// Probes new to the window (skySnap) are worked out with more rays and written as they are. The last slice is the
// columns' ground and top.

#include "sky_probe_layout.glsl"

uniform ivec2 skyOrigin;       // the window's lowest probe, in probe steps (world units / SKY_PROBE_SPACING)
uniform ivec2 skyKnownOrigin;  // the window as of this slice's last update: a probe outside it is new
uniform int skySlice;          // the layer, or SKY_LAYERS for the columns
uniform int skySnap;           // 1: only the probes new to the window; 0: every probe, to be blended in
uniform int skyFrame;          // counts the updates; turns the rays

// The ground at the corners of the scene's tiles, (x, z) in tiles plus terrainOffset, world y (down)
uniform sampler2D terrainHeights;
uniform int terrainOffset;

// Top-down depth of all scenery around the player (Weather.java describes it): window depth =
// (world y - occlusionCenterY + occlusionDepthRange) / (2 occlusionDepthRange), cleared to 1
uniform sampler2D occlusionMap;
uniform mat4 occlusionProj;    // world -> occlusion clip
uniform float occlusionCenterY;
uniform float occlusionDepthRange;

out vec4 FragSky;

// rays per probe each update, and per probe new to the window
#define RAYS 24
#define SNAP_RAYS 32
// Steps along a ray, from the first to the farthest REACH (32 tiles), each a constant ratio longer than the last. The
// first is jittered within its stretch, so thin things hidden between steps are found by some updates.
#define STEPS 12
#define NEAREST_STEP 24.0
#define REACH 4096.0
const float STEP_RATIO = pow(REACH / NEAREST_STEP, 1.0 / float(STEPS));
// world units the highest scenery over a ray's point may be above it before the ray is blocked: the map's texels are
// 16 across, and slopes rise within one
#define RAY_CLEARANCE 12.0
// the offset of the taps that find the top of a column, around the probe: a quarter of the map's texels' centers
#define COLUMN_TAP 8.0

#define TAU 6.28318530718
#define GOLDEN_ANGLE 2.39996322973
// real spherical harmonics: the constant term, and the linear terms' factor
#define SH_0 0.282095
#define SH_1 0.488603
// the coefficients of the hemisphere seen whole: (2 pi) SH_0, and along up the integral of SH_1 cos(theta), pi SH_1
const vec4 OPEN_SKY = vec4(TAU * SH_0, 0.0, 0.5 * TAU * SH_1, 0.0);

uint pcg(uint v) {
  uint s = v * 747796405u + 2891336453u;
  uint w = ((s >> ((s >> 28u) + 4u)) ^ s) * 277803737u;
  return (w >> 22u) ^ w;
}

float unitFloat(uint v) {
  return float(v >> 8u) / 16777216.0;
}

// World y (down) of the highest scenery over a position; huge where nothing is, or past the map
float sceneryTop(vec2 xz) {
  vec2 uv = (occlusionProj * vec4(xz.x, occlusionCenterY, xz.y, 1.0)).xy * 0.5 + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThanEqual(uv, vec2(1.0)))) {
    return 1e9;
  }
  float d = textureLod(occlusionMap, uv, 0.0).r;
  return d >= 1.0 ? 1e9 : (d * 2.0 - 1.0) * occlusionDepthRange + occlusionCenterY;
}

// The ground's height: the renderer's two triangles to a tile, split from its south east to its north west corner
float groundHeight(vec2 xz) {
  vec2 t = clamp(xz / 128.0 + float(terrainOffset), vec2(0.0), vec2(textureSize(terrainHeights, 0)) - 1.001);
  ivec2 i = ivec2(floor(t));
  vec2 f = t - vec2(i);
  float sw = texelFetch(terrainHeights, i, 0).r;
  float se = texelFetch(terrainHeights, i + ivec2(1, 0), 0).r;
  float nw = texelFetch(terrainHeights, i + ivec2(0, 1), 0).r;
  float ne = texelFetch(terrainHeights, i + ivec2(1, 1), 0).r;
  return f.x + f.y > 1.0
    ? se * (1.0 - f.y) + nw * (1.0 - f.x) + ne * (f.x + f.y - 1.0)
    : sw * (1.0 - f.x - f.y) + se * f.x + nw * f.y;
}

void main() {
  ivec2 slot = ivec2(gl_FragCoord.xy);
  ivec2 probe = skyOrigin + ivec2(skyProbeSlot(slot.x - skyOrigin.x), skyProbeSlot(slot.y - skyOrigin.y));
  vec2 center = (vec2(probe) + 0.5) * SKY_PROBE_SPACING;
  float ground = groundHeight(center);
  if (skySlice == SKY_LAYERS) {
    // the terrain is in the map too, but the map is limited in depth
    float top = min(min(min(sceneryTop(center + vec2(-COLUMN_TAP, -COLUMN_TAP)), sceneryTop(center + vec2(COLUMN_TAP, -COLUMN_TAP))),
      min(sceneryTop(center + vec2(-COLUMN_TAP, COLUMN_TAP)), sceneryTop(center + vec2(COLUMN_TAP, COLUMN_TAP)))), ground);
    FragSky = vec4(ground, top, 0.0, 0.0);
    return;
  }
  bool isNew = any(lessThan(probe, skyKnownOrigin)) || any(greaterThanEqual(probe, skyKnownOrigin + SKY_PROBES));
  if (skySnap == 1 && !isNew) {
    discard;
  }

  vec3 origin = vec3(center.x, ground - SKY_LAYER_BASE - float(skySlice) * SKY_LAYER_STEP, center.y);
  int rays = skySnap == 1 ? SNAP_RAYS : RAYS;
  uint seed = pcg(uint(probe.x) * 73856093u ^ uint(probe.y) * 19349663u ^ uint(skySlice) * 83492791u ^ uint(skyFrame) * 2654435761u);
  float turn = unitFloat(seed) * TAU;
  // the coefficients of the rays that are blocked
  vec4 blocked = vec4(0.0);
  for (int j = 0; j < rays; ++j) {
    uint r = pcg(seed + uint(j) + 1u);
    // an even spread over the hemisphere: a stretch of the height of the direction each, a golden angle apart
    float up = (float(j) + unitFloat(r)) / float(rays);
    float side = sqrt(1.0 - up * up);
    float phi = turn + float(j) * GOLDEN_ANGLE;
    vec3 dir = vec3(side * cos(phi), -up, side * sin(phi));
    float travel = NEAREST_STEP * pow(STEP_RATIO, unitFloat(pcg(r)));
    for (int i = 0; i < STEPS; ++i, travel *= STEP_RATIO) {
      vec3 q = origin + dir * travel;
      if (sceneryTop(q.xz) < q.y - RAY_CLEARANCE) {
        blocked += vec4(SH_0, SH_1 * dir.x, SH_1 * up, SH_1 * dir.z);
        break;
      }
    }
  }
  FragSky = OPEN_SKY - blocked * (TAU / float(rays));
}
