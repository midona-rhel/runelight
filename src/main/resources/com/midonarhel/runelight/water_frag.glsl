#version 330

// The water surface pass's fragments (WaterSurface.java): only where the surface is in front of the scene, it writes
// how far away the surface is along the view ray, its normal, type and foam, how deep the water is there and the
// caustics on the bed below, for the composite to shade (water.glsl).
//
// The normal: 117 HD's water normal map in three sizes, carried along the flow map in two phases so the current
// shows in the sky it reflects, and rain rings. The caustics are 117 HD's, carried along by the same flow and drawn out along fast water.

#include "water_common.glsl"

uniform sampler2D sceneDepth;  // the opaque scene, resolved
uniform vec3 waterWind;
uniform vec4 waterDrift;
uniform float waterRain;
uniform float waterWaves;
uniform float waterSpeed;
uniform int waterOutdoors;
uniform int waterDebug;
uniform float waterSkyDepth;   // the sky's depth in sceneDepth (frame.glsl's isSky)
uniform int waterCausticsOn;
uniform vec3 waterCasterDir;   // toward the light that casts shadows
uniform mat4 waterInvViewProj; // clip -> camera-relative world
uniform ivec4 waterViewport;   // the scene's viewport in the targets
uniform int waterZeroToOne;    // 1 with glClipControl
uniform vec3 waterCamera;

in vec3 vRel;
in vec2 vXZ;
in vec2 vFlow;
in vec4 vData;       // shore distance (tiles), wave energy, water share, turbulence
in float vDepth;     // how deep the water is here going by its distance from the shore, smooth across tiles
flat in int vType;
flat in int vSlot;

layout(location = 0) out uvec4 WaterOut;  // 64 bits packed, see water.glsl's waterUnpack

// The surface's normal map (layer 3 of waterNoise): 117 HD's strength and its seconds per repeat of each size
#define NORMAL_STRENGTH 0.09
#define NORMAL_PERIOD vec3(42.0, 36.0, 32.0)
// world units per size step: 117 HD's 128 (a tile) looked too fine at the game's camera distances
#define NORMAL_TILE 256.0
// the foam on rapids: the large ripple cells' rims (layer 2), world units per repeat
#define CELL_SCALE_B 520.0
// turbulence (rapids) adds to the ripples, and lays soft foam over them where it is strong
#define TURB_WAVES 0.6
#define TURB_FOAM vec2(0.35, 0.9)
#define FOAM 0.45
// caustics (water.glsl has the strength): world units per repeat, seconds per cycle of each copy on still water;
// mean and line values of the kept copy; drawn out along water faster than CAUSTIC_STRETCH_SPEED by up to
// CAUSTIC_STRETCH seconds of the flow
#define CAUSTIC_SCALE 298.67
#define CAUSTIC_PERIOD vec2(17.0, 23.0)
#define CAUSTIC_MEAN 0.145
#define CAUSTIC_LINE 0.30
#define CAUSTIC_STRETCH_SPEED vec2(20.0, 90.0)
#define CAUSTIC_STRETCH 0.45
// the furthest the distance target reaches (16 bits): past it water is clamped there
#define WATER_REACH 20480.0
// rain rings: cell size (world units), rings per cell per second
#define RAIN_CELL 72.0
#define RAIN_RATE 1.3
// how far a ring spreads (cells) and how wide its crest is; the splash's size (cells) and how long it lasts (share of
// a cycle); the rings' slope, how white their crest shows, and how white the water goes at most
#define RAIN_SPREAD 0.32
#define RAIN_RING 0.05
#define RAIN_SPLASH_SIZE 0.07
#define RAIN_SPLASH_TIME 0.12
#define RAIN_SLOPE 0.45
#define RAIN_CREST 0.35
#define RAIN_FOAM 0.8

float waterHash(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
}

// 117 HD's surface (BSD-2, see LICENSE): its water normal map in three sizes (6, 10 and 14 tiles a repeat), each
// scrolling on its own, the first one turned, all moved along by `move`. Their normals add up: the slope is the
// sum of the sideways parts over the sum of the upward parts, at NORMAL_STRENGTH like 117 HD's
vec2 ripples(vec2 xz, vec2 move, vec4 lods) {
  vec2 p = -(xz - move) / NORMAL_TILE;
  vec3 a = textureLod(waterNoise, vec3((p / 3.0).yx - waterCycle(NORMAL_PERIOD.x), 3.0), lods.x).rgb;
  vec3 b = textureLod(waterNoise, vec3(p / 5.0 + waterCycle(NORMAL_PERIOD.y), 3.0), lods.y).rgb;
  vec3 c = textureLod(waterNoise, vec3(p / 7.0 + waterCycle(NORMAL_PERIOD.z), 3.0), lods.z).rgb;
  vec2 side = (a.rg + b.rg + c.rg) * 2.0 - 3.0;
  return side * NORMAL_STRENGTH / max(a.b + b.b + c.b, 0.5);
}

// 117 HD's caustics at uv: two copies scrolling apart, the darker kept
vec3 causticAt(vec2 uv, float lod) {
  const vec2 DIR = vec2(1.0, -2.0);
  vec3 a = textureLod(waterNoise, vec3(uv + waterCycle(CAUSTIC_PERIOD.x) * DIR, 1.0), lod).rgb;
  vec3 b = textureLod(waterNoise, vec3(uv * 1.5 - waterCycle(CAUSTIC_PERIOD.y) * DIR, 1.0), lod).rgb;
  return min(a, b);
}

// Moved along by `move`, and drawn out along the flow by `smear` (two taps either side) when it is long
vec3 causticMoved(vec2 xz, vec2 move, vec2 smear, float lod) {
  vec2 uv = (xz - move) / CAUSTIC_SCALE;
  if (dot(smear, smear) < 64.0)
    return causticAt(uv, lod);
  vec2 s = smear / CAUSTIC_SCALE;
  return 0.5 * (causticAt(uv - s, lod) + causticAt(uv + s, lod));
}

// Raindrops landing: one at a time per cell, in two offset layers, as many cells active as it rains. Each lands as
// a small white splash, then a ring spreads from it. xy: the rings' slope, z: how white (the splash, the ring's crest)
vec3 rainRings(vec2 xz) {
  vec3 sum = vec3(0.0);
  for (int layer = 0; layer < 2; ++layer) {
    vec2 g = xz / RAIN_CELL + float(layer) * vec2(0.5, 0.31);
    vec2 cell = floor(g);
    float h = waterHash(vec3(cell, float(layer) * 7.0));
    float t = waterTime * RAIN_RATE + h * 7.0;
    float drop = floor(t);
    float phase = fract(t);
    float h2 = waterHash(vec3(cell + drop * 0.123, float(layer) + 3.0));
    if (h2 > waterRain)
      continue;
    vec2 center = vec2(waterHash(vec3(cell, drop)), waterHash(vec3(cell.yx, drop + 1.0))) * 0.5 + 0.25;
    vec2 dv = fract(g) - center;
    float dist = length(dv);
    float ring = (dist - phase * RAIN_SPREAD) / RAIN_RING;
    float fade = (1.0 - phase) * (1.0 - phase);
    float wave = sin(ring * 3.14159) * exp(-ring * ring * 0.5) * fade;
    sum.xy += dv / max(dist, 1e-3) * wave;
    // the splash where it lands, gone in a moment, then the crest of the ring
    float splash = (1.0 - smoothstep(0.0, RAIN_SPLASH_TIME, phase)) * (1.0 - smoothstep(0.02, RAIN_SPLASH_SIZE, dist));
    sum.z += max(splash, max(wave, 0.0) * fade * RAIN_CREST);
  }
  return vec3(sum.xy * RAIN_SLOPE, min(sum.z, 1.0));
}

uint pack01(float v, float bits) {
  return uint(clamp(v, 0.0, 1.0) * bits + 0.5);
}

void main() {
  // behind the opaque scene: not seen (reverse-z, larger is nearer); in front of the sky past the drawn scene: the
  // composite only shades water over what is under it
  ivec2 px = ivec2(gl_FragCoord.xy);
  float sceneZ = texelFetch(sceneDepth, px, 0).r;
  if (gl_FragCoord.z < sceneZ || sceneZ <= waterSkyDepth)
    discard;

  int type = vType;
  vec2 xz = vXZ;
  float speed = WATER_FLOW_SPEED[type];
  vec4 look = WATER_LOOK[type];
  float wind = waterWind.z;
  float energy = vData.g;
  float turb = vData.a * step(0.5, speed);
  vec2 flow = vFlow;
  float flowSpeed = length(flow);

  // mips picked from the derivatives once, here, where they are defined; every tap below is explicit
  float footprint = max(length(dFdx(xz)), length(dFdy(xz)));
  vec4 lods = vec4(waterLod(footprint, NORMAL_TILE * 3.0), waterLod(footprint, NORMAL_TILE * 5.0),
    waterLod(footprint, NORMAL_TILE * 7.0), waterLod(footprint, CELL_SCALE_B));

  // still water drifts with the wind; running water carries its ripples in two phases (water_common.glsl)
  vec2 base = waterDrift.xy * (speed * waterSpeed);
  vec2 m0 = base, m1 = base;
  float w0 = 1.0;
  float flowing = smoothstep(FLOW_MIN * 0.5, FLOW_MIN, flowSpeed);
  vec2 slope;
  if (flowing > 0.0) {
    waterFlowPhases(xz, flow, base, m0, m1, w0);
    vec2 o1 = vec2(173.0, 91.0);
    vec2 flowed = ripples(xz, m0, lods) * w0 + ripples(xz + o1, m1, lods) * (1.0 - w0);
    // blending two copies averages their slopes down: give some back
    flowed *= 1.0 + 0.4 * (1.0 - abs(2.0 * w0 - 1.0));
    slope = flowing < 1.0 ? mix(ripples(xz, base, lods), flowed, flowing) : flowed;
  } else {
    slope = ripples(xz, base, lods);
  }
  slope *= look.w * waterWaves * (0.7 + 0.5 * wind) * (0.75 + 0.35 * energy) * (1.0 + TURB_WAVES * turb);
  vec3 rain = vec3(0.0);
  if (waterRain > 0.01 && waterOutdoors == 1) {
    rain = rainRings(xz);
    slope += rain.xy * look.w;
  }
  // the surface's normal points up (-y); slope is d up / d x, d up / d z
  vec3 nrm = normalize(vec3(-slope.x, -1.0, -slope.y));

  // soft foam only on rapids: the large cells' rims, carried with the flow
  float foam = 0.0;
  float churn = smoothstep(TURB_FOAM.x, TURB_FOAM.y, turb);
  if (churn > 0.0) {
    float h = textureLod(waterNoise, vec3((xz - m0) / CELL_SCALE_B, 2.0), lods.w).b * w0
      + textureLod(waterNoise, vec3((xz + vec2(173.0, 91.0) - m1) / CELL_SCALE_B, 2.0), lods.w).b * (1.0 - w0);
    foam = (1.0 - smoothstep(0.15, 0.55, h)) * churn * FOAM;
  }
  foam = max(foam, rain.z * RAIN_FOAM);

  // ---- the caustics on the bed below, carried by the flow
  float caustic = 0.0;
  vec2 causticRB = vec2(0.0);
  if (waterCausticsOn == 1 && look.x < 0.7) {
    vec2 ndc = (vec2(px) + 0.5 - vec2(waterViewport.xy)) / vec2(waterViewport.zw) * 2.0 - 1.0;
    vec4 h = waterInvViewProj * vec4(ndc, waterZeroToOne == 1 ? sceneZ : sceneZ * 2.0 - 1.0, 1.0);
    vec3 bed = h.xyz / h.w;
    float depth = max(bed.y - vRel.y, 0.0);
    // the light reaching the bed entered the water toward the light, bent steeper by refraction
    vec2 sunXZ = waterCasterDir.xz;
    float sinI = length(sunXZ);
    float sinT = min(sinI / 1.33, 0.95);
    vec2 at = bed.xz + waterCamera.xz + sunXZ * (sinT * inversesqrt(1.0 - sinT * sinT) / max(sinI, 1e-4) * depth);
    float lod = waterLod(footprint * (1.0 + depth / max(length(vRel), 1.0)), CAUSTIC_SCALE);
    vec2 dir = flowSpeed > 1e-3 ? flow / flowSpeed : vec2(0.0);
    vec2 smear = dir * (smoothstep(CAUSTIC_STRETCH_SPEED.x, CAUSTIC_STRETCH_SPEED.y, flowSpeed)
      * CAUSTIC_STRETCH * min(flowSpeed, FLOW_DRAG / FLOW_CYCLE));
    vec3 c = causticMoved(at, mix(vec2(0.0), m0 - base, flowing), smear, lod);
    if (flowing > 0.0)
      c = c * w0 + causticMoved(at + vec2(97.0, 211.0), m1 - base, smear, lod) * (1.0 - w0);
    c = (c - CAUSTIC_MEAN) / (CAUSTIC_LINE - CAUSTIC_MEAN);
    caustic = c.g;
    causticRB = c.rb - c.g;
  }

  uvec2 packed;
  if (waterDebug == 1) {
    // type color, flow as stripes that move with it, turbulent water reddens
    const vec3 DEBUG_TYPE[WATER_TYPES] = vec3[](vec3(0.0), vec3(0.2, 0.6, 1.0), vec3(0.0, 0.2, 0.9),
      vec3(0.3, 0.3, 0.6), vec3(0.6, 0.9, 1.0), vec3(0.4, 0.6, 0.1), vec3(0.4, 0.3, 0.3));
    float tiles = flowSpeed / (WATER_TILE * max(waterSpeed * speed, 1e-3));
    vec2 dir = flowSpeed > 1e-3 ? flow / flowSpeed : vec2(0.0);
    float stripe = flowSpeed > 1e-3 ? step(0.5, fract(dot(xz / 64.0, dir) - waterTime * tiles * 2.0)) : 0.5;
    vec3 c = mix(DEBUG_TYPE[type], vec3(stripe), 0.35) * mix(0.6, 1.0, energy);
    c = mix(c, vec3(1.0, 0.25, 0.1), turb * 0.6);
    packed.x = pack01(c.r, 255.0) | pack01(c.g, 255.0) << 8 | pack01(c.b, 255.0) << 16;
  } else {
    // normal x, z (9 bits each), look slot (5: type and the game's texture, Water.java), foam (3), depth (6, square
    // root)
    packed.x = pack01(nrm.x * 0.5 + 0.5, 511.0) | pack01(nrm.z * 0.5 + 0.5, 511.0) << 9 | uint(vSlot & 31) << 18
      | pack01(foam, 7.0) << 23 | pack01(sqrt(vDepth / 512.0), 63.0) << 26;
  }
  // distance (16 bits), caustics: green -1 to 3 (8 bits), red and blue -0.5 to 0.5 from it (4 bits each)
  packed.y = pack01(length(vRel) / WATER_REACH, 65535.0) | pack01((caustic + 1.0) / 4.0, 255.0) << 16
    | pack01(causticRB.x + 0.5, 15.0) << 24 | pack01(causticRB.y + 0.5, 15.0) << 28;
  // never 0, which is no water
  packed.y = max(packed.y & 0xFFFFu, 1u) | (packed.y & 0xFFFF0000u);
  WaterOut = uvec4(packed.x & 0xFFFFu, packed.x >> 16, packed.y & 0xFFFFu, packed.y >> 16);
}
