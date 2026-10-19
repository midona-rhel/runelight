#version 330

// The water surface pass's fragments (WaterSurface.java): only where the surface is in front of the scene, it writes
// how far away the surface is along the view ray and its normal, type, foam and how fast it runs, for the composite
// to shade (water.glsl). The normal is the swell's own plus two octaves of wave noise dragged along the flow map in
// two phases, capillary ripples on the wind and rain rings; fast water gets foam streaks along the current, and a
// band of foam laps at the shore.

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

in vec3 vRel;
in vec2 vXZ;
in vec2 vFlow;
in vec4 vData;       // shore distance (tiles), wave energy, water share, turbulence
in float vSwell;
flat in int vType;

layout(location = 0) out uvec2 WaterOut;  // floatBitsToUint(distance), packed (water.glsl's waterUnpack)

#define SWELL_DIR vec2(0.6, 0.8)
#define WAVE_SCALE_C 64.0
#define WAVE_MIX_C 0.24
// the capillary ripples run ahead of the wind's drift by this factor
#define CAPILLARY_DRIFT 1.6
// normal tilt per unit of noise slope, before the type's normal strength. 117 HD's water is gentle: broad soft
// highlights rather than a mirror of sharp facets.
#define WAVE_STRENGTH 0.34
// the swell's slope counts this much toward the normal (the geometry alone would be too subtle to read)
#define SWELL_NORMAL 1.6
// Current lines on faster water: the octave's finer height, smeared over STREAK_SMEAR seconds of the flow so the
// lines lie along the current, as foam from STREAK_SPEED to STREAK_SPEED_FULL world units per second
#define STREAK_SMEAR 2.4
#define STREAK_SPEED 24.0
#define STREAK_SPEED_FULL 90.0
#define STREAK_EDGE vec2(0.6, 0.85)
#define STREAK_FOAM 0.55
// turbulence adds to the wave height and counts as TURB_FOAM of fast water for the foam
#define TURB_WAVES 0.5
#define TURB_FOAM 0.7
// shore foam: where the share of water around drops below SHORE_FOAM_SHARE, broken up by the finer waves
#define SHORE_FOAM_SHARE vec2(0.5, 0.8)
#define SHORE_FOAM 0.45
// rain rings: cell size (world units), rings per cell per second
#define RAIN_CELL 40.0
#define RAIN_RATE 1.3

float waterHash(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
}

vec4 noiseAt(vec2 uv, float lod) {
  return textureLod(waterNoise, vec3(uv, 0.0), lod);
}

// Wave slope and the finer height of the two octaves at xz, moved along by `move`
vec3 waves2(vec2 xz, vec2 move, float la, float lb) {
  const mat2 ROT = mat2(0.8, 0.6, -0.6, 0.8);
  vec4 a = noiseAt((xz - move) / WAVE_SCALE_A, la);
  vec4 b = noiseAt(ROT * (xz - move * 1.15) / WAVE_SCALE_B + 0.37, lb);
  vec2 slope = (a.rg * 2.0 - 1.0) * WAVE_MIX_A + ((b.rg * 2.0 - 1.0) * ROT) * WAVE_MIX_B;
  return vec3(slope, a.a);
}

// Rings from raindrops: one ring at a time per cell, in two offset layers, as many cells active as it rains
vec2 rainRings(vec2 xz) {
  vec2 sum = vec2(0.0);
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
    float ring = (dist - phase * 0.3) / 0.035;
    float wave = sin(ring * 3.14159) * exp(-ring * ring * 0.5) * (1.0 - phase) * (1.0 - phase);
    sum += dv / max(dist, 1e-3) * wave;
  }
  return sum * 0.35;
}

uint pack01(float v, float bits) {
  return uint(clamp(v, 0.0, 1.0) * bits + 0.5);
}

void main() {
  // behind the opaque scene: not seen (reverse-z, larger is nearer); in front of the sky past the drawn scene: the
  // composite only shades water over what is under it
  float sceneZ = texelFetch(sceneDepth, ivec2(gl_FragCoord.xy), 0).r;
  if (gl_FragCoord.z < sceneZ || sceneZ <= waterSkyDepth)
    discard;

  int type = vType;
  vec2 xz = vXZ;
  vec4 motion = WATER_MOTION[type];
  vec4 look = WATER_LOOK[type];
  float wind = waterWind.z;
  float energy = vData.g, share = vData.b;
  float turb = vData.a * step(0.5, motion.w);
  vec2 flow = vFlow;
  float flowSpeed = length(flow);
  float fast = max(smoothstep(STREAK_SPEED, STREAK_SPEED_FULL, flowSpeed), turb * TURB_FOAM) * step(0.5, motion.w);

  // mips picked from the derivatives once, here, where they are defined; every tap below is explicit
  float footprint = max(length(dFdx(xz)), length(dFdy(xz)));
  float la = waterLod(footprint, WAVE_SCALE_A), lb = waterLod(footprint, WAVE_SCALE_B);
  float lc = waterLod(footprint, WAVE_SCALE_C);

  vec2 base = waterDrift.xy * (motion.w * waterSpeed);
  vec3 waves = waves2(xz, base, la, lb);
  float foam = 0.0;
  float flowing = smoothstep(FLOW_MIN * 0.5, FLOW_MIN, flowSpeed);
  if (flowing > 0.0) {
    vec2 m0, m1;
    float w0;
    waterFlowPhases(xz, flow, base, m0, m1, w0);
    vec2 o1 = vec2(173.0, 91.0);
    vec3 f0 = waves2(xz, m0, la, lb), f1 = waves2(xz + o1, m1, la, lb);
    vec3 flowed = f0 * w0 + f1 * (1.0 - w0);
    // blending two copies averages their slopes down: give some back
    flowed.xy *= 1.0 + 0.4 * (1.0 - abs(2.0 * w0 - 1.0));
    waves = mix(waves, flowed, flowing);
    // current lines: each copy's finer height averaged with itself a little further upstream, smeared along the flow
    if (fast > 0.0) {
      vec2 drag = flow * min(1.0, FLOW_DRAG / max(flowSpeed * FLOW_CYCLE, 1e-3));
      vec2 smear = drag * STREAK_SMEAR;
      float s0 = 0.5 * (f0.z + noiseAt((xz - m0 - smear) / WAVE_SCALE_A, la).a);
      float s1 = 0.5 * (f1.z + noiseAt((xz + o1 - m1 - smear) / WAVE_SCALE_A, la).a);
      foam = smoothstep(STREAK_EDGE.x, STREAK_EDGE.y, s0 * w0 + s1 * (1.0 - w0)) * fast * STREAK_FOAM
        * (1.0 - smoothstep(2.0, 4.0, la));
    }
  }
  float strength = WAVE_STRENGTH * look.w * waterWaves * (0.7 + 0.5 * wind) * (0.75 + 0.35 * energy)
    * (1.0 + TURB_WAVES * turb);
  vec2 slope = waves.xy * strength;
  {
    const mat2 ROT_C = mat2(0.28, -0.96, 0.96, 0.28);
    vec4 cap = noiseAt(ROT_C * (xz - waterDrift.xy * CAPILLARY_DRIFT) / WAVE_SCALE_C + 0.61, lc);
    slope += ((cap.rg * 2.0 - 1.0) * ROT_C) * (WAVE_MIX_C * WAVE_STRENGTH * look.w * waterWaves * (0.4 + 0.8 * wind));
  }
  // the swell's own slope, past where its movement has faded out too
  vec2 swellSlope = vec2(0.0);
  waterSwell(xz, normalize(SWELL_DIR), vSwell, motion.y, swellSlope);
  slope += swellSlope * SWELL_NORMAL;
  if (waterRain > 0.01 && waterOutdoors == 1)
    slope += rainRings(xz) * look.w;

  // foam lapping at the shore, broken up by the finer waves drifting past
  float lap = 1.0 - smoothstep(SHORE_FOAM_SHARE.x, SHORE_FOAM_SHARE.y, share);
  if (lap > 0.0) {
    float f = noiseAt((xz - base * 0.6) / 150.0 + 0.23, lc * 0.5).a;
    foam = max(foam, smoothstep(0.62 - 0.35 * lap, 0.9 - 0.3 * lap, f) * lap * SHORE_FOAM);
  }

  // the surface's normal points up (-y); slope is d up / d x, d up / d z
  vec3 nrm = normalize(vec3(-slope.x, -1.0, -slope.y));

  uint packed;
  if (waterDebug == 1) {
    // type color, flow as stripes that move with it, turbulent water reddens
    const vec3 DEBUG_TYPE[WATER_TYPES] = vec3[](vec3(0.0), vec3(0.2, 0.6, 1.0), vec3(0.0, 0.2, 0.9),
      vec3(0.3, 0.3, 0.6), vec3(0.6, 0.9, 1.0), vec3(0.4, 0.6, 0.1), vec3(0.4, 0.3, 0.3));
    float tiles = flowSpeed / (WATER_TILE * max(waterSpeed * motion.w, 1e-3));
    vec2 dir = flowSpeed > 1e-3 ? flow / flowSpeed : vec2(0.0);
    float stripe = flowSpeed > 1e-3 ? step(0.5, fract(dot(xz / 64.0, dir) - waterTime * tiles * 2.0)) : 0.5;
    vec3 c = mix(DEBUG_TYPE[type], vec3(stripe), 0.35) * mix(0.6, 1.0, energy);
    c = mix(c, vec3(1.0, 0.25, 0.1), turb * 0.6);
    packed = pack01(c.r, 255.0) | pack01(c.g, 255.0) << 8 | pack01(c.b, 255.0) << 16 | uint(type) << 24;
  } else {
    packed = pack01(nrm.x * 0.5 + 0.5, 2047.0) | pack01(nrm.z * 0.5 + 0.5, 2047.0) << 11 | uint(type) << 22
      | pack01(foam, 15.0) << 25 | pack01(fast, 7.0) << 29;
  }
  WaterOut = uvec2(floatBitsToUint(max(length(vRel), 1.0)), packed);
}
