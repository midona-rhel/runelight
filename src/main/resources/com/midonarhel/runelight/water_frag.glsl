#version 330

// The water surface pass's fragments (WaterSurface.java): only where the surface is in front of the scene, it writes
// how far away the surface is along the view ray, its normal, type and foam, how deep the water is there and the
// caustics on the bed below, for the composite to shade (water.glsl).
//
// The normal: a choppy sea of sine octaves carried along the flow map in two phases (still water runs slower), worked
// out with the foam a few steps a second into world-space wave maps (waterBake, WaterSurface.bake) and blended here,
// and rain rings. The caustics are 117 HD's, carried along by the same flow and drawn out along fast water.

#include "water_common.glsl"

#include "scene_samplers.glsl"
uniform SCENE_SAMPLER sceneDepth;  // the opaque scene (sample 0)
uniform sampler2D waterData;    // WaterMap.texture (water_vert.glsl)
uniform sampler2D waterContact; // WaterMap.contact: how close something standing in the water is, 4 texels a tile
uniform vec3 waterWind;
uniform vec4 waterDrift;
uniform float waterRain;
uniform int waterOutdoors;
uniform int waterDebug;         // 1 types and flow, 2 the waves as a relief (WaterDebug)
uniform float waterSkyDepth;   // the sky's depth in sceneDepth (frame.glsl's isSky)
uniform int waterCausticsOn;
uniform vec3 waterCasterDir;   // toward the light that casts shadows
uniform mat4 waterInvViewProj; // clip -> camera-relative world
uniform ivec4 waterViewport;   // the scene's viewport in the targets
uniform int waterZeroToOne;    // 1 with glClipControl
uniform vec3 waterCamera;
uniform int waterBake;          // 1: working out the waves into the wave map (WaterSurface.bake)
uniform sampler2D waterWavesA;  // the wave maps either side of now: slope xy, foam
uniform sampler2D waterWavesB;
uniform float waterWaveBlend;   // how far now is from A toward B
uniform vec2 waterFocus;        // where the camera looks, world (the wave maps reach WAVE_MAP_REACH tiles from it)
uniform ivec2 waterOrigin;      // the targets' pixel at the surface target's corner (WaterSurface.draw)

in vec3 vRel;
in vec2 vXZ;
in vec2 vFlow;
flat in int vType;
flat in int vSlot;

layout(location = 0) out uvec4 WaterOut;  // 64 bits packed, see water.glsl's waterUnpack
layout(location = 1) out vec4 WaveOut;    // the wave map: slope xy, foam

// still water (no flow) drifts less with the wind
#define STILL_DRIFT 0.4
// the sea's still water runs as fast as running water's (STILL_SLOW, water_common.glsl), and drifts more
#define OCEAN_STILL_SLOW 1.0
#define OCEAN_STILL_DRIFT 1.0
// the waves' height rises to FAST_WAVES (water_common.glsl) at FAST_FLOW world units a second
#define FAST_FLOW 90.0
// toward the shore the waves flatten to SHORE_WAVES of their height, over SHORE_CALM tiles out
#define SHORE_WAVES 0.05
#define SHORE_CALM 3.0
// the foam on rapids: the large ripple cells' rims (layer 2), world units per repeat
#define CELL_SCALE_B 520.0
// on still water the foam is two copies of the cells drifting across each other, one cell every FOAM_STILL_PERIOD
// seconds and a little slower
#define FOAM_STILL_PERIOD 40.0
// turbulence (rapids) adds to the ripples, and lays soft foam over them where it is strong
#define TURB_WAVES 0.6
#define TURB_FOAM vec2(0.35, 0.9)
// running water foams along its banks: from BANK_FOAM_FLOW.x world units a second, fully at .y, as much as BANK_FOAM
// of the rapids' churn, fading out between BANK_FOAM_REACH's tiles from the shore
#define BANK_FOAM_FLOW vec2(25.0, 80.0)
#define BANK_FOAM 0.7
#define BANK_FOAM_REACH vec2(0.4, 1.3)
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
// around things standing in the water (WaterMap.contact): waves up to 1 + CONTACT_WAVES as rough, and foam (contactFoam)
#define CONTACT_WAVES 0.8
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

float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
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

// The foam cells' height at xz (layer 2 of the noise, in cells) of still water, drifted by base: two copies drifting across
// each other. lod: the mip to read.
float waterFoamStill(vec2 xz, vec2 base, float lod) {
  vec2 uv = (xz - base) / CELL_SCALE_B;
  return 0.5 * (textureLod(waterNoise, vec3(uv + vec2(waterCycle(FOAM_STILL_PERIOD), 0.0), 2.0), lod).b
    + textureLod(waterNoise, vec3(uv.yx + vec2(0.37, -waterCycle(FOAM_STILL_PERIOD * 1.37)), 2.0), lod).b);
}

// The foam cells' height at xz, carried with the flow as running water's ripples are, and as still water's where it is not
float waterFoamHeight(vec2 xz, vec2 base, vec2 m0, vec2 m1, float w0, float flowing, float lod) {
  float h = textureLod(waterNoise, vec3((xz - m0) / CELL_SCALE_B, 2.0), lod).b * w0
    + textureLod(waterNoise, vec3((xz + vec2(173.0, 91.0) - m1) / CELL_SCALE_B, 2.0), lod).b * (1.0 - w0);
  return flowing < 1.0 ? mix(waterFoamStill(xz, base, lod), h, flowing) : h;
}

// The foam around things standing in the water (WaterMap.contact) at xz, as hard edged coverage (the water pass is at
// the screen's resolution): x the tint, y the core, from the foam cells' height h. The cells' shapes shrink with the
// distance from the thing (CONTACT_SHRINK), never fade.
vec2 contactFoam(vec2 xz, float h) {
  float near = textureLod(waterContact, (xz / WATER_TILE + waterGrid.x) / (waterGrid.y - 1.0), 0.0).r;
  float hn = h + CONTACT_SHRINK * (1.0 - near);
  float aa = max(fwidth(hn), 1e-4);
  return step(0.001, near) * vec2(waterEdge(FOAM_CUT.y - hn, aa), waterEdge(FOAM_CUT.x - hn, aa));
}

// The terrain falls' frame at xz (WaterMap.frameX): the fall's direction times 1 plus how much longer its slope has made the
// run so far, in tiles; zero away from falls. Where there is one the foam is laid out along the water's surface, not the
// ground (fallLaid), so it is as large on the slope as on the flat, and its cells carried straight down the fall, a repeat of
// them every FLOW_CYCLE seconds, over the whole of it, the pool above and below included: one pattern, not one a tile.
vec2 fallFrame(vec2 xz) {
  vec2 frame = waterPage2(waterData, xz / WATER_TILE + waterGrid.x).ba;
  return dot(frame, frame) > 0.25 ? frame : vec2(0.0);
}

// xz laid out along a fall's surface
vec2 fallLaid(vec2 xz, vec2 frame) {
  float stretch = length(frame);
  return stretch > 0.0 ? xz + frame / stretch * (WATER_TILE * (stretch - 1.0)) : xz;
}

// The foam cells' height on and around a fall, at its laid out xz (fallLaid)
float fallFoamHeight(vec2 laid, vec2 frame, float lod) {
  return waterFoamStill(laid, normalize(frame) * (CELL_SCALE_B * waterCycle(FLOW_CYCLE)), lod);
}

// The waves' slope at xz and the foam on them (the wave map's, WaterSurface.bake), for water of this type and flow
void waterWaveField(vec2 xz, int type, vec2 flow, out vec2 slope, out float foam) {
  float speed = WATER_FLOW_SPEED[type];
  vec4 look = WATER_LOOK[type];
  float wind = waterWind.z;
  // page 1 here (shore distance in tiles, wave energy, water share, turbulence), filtered between the corners in the
  // fragment: through the vertices it bent along each quad's diagonal, which showed as straight edges in the color
  vec4 data = waterPage1(waterData, xz / WATER_TILE + waterGrid.x);
  float energy = data.g;
  float turb = data.a * step(0.5, speed);
  float flowSpeed = length(flow);

  // the map's texels: waves down to half a texel's worth are kept (the map's mipmaps calm them further off)
  float footprint = max(length(dFdx(xz)), length(dFdy(xz))) * 0.5;
  float lodFoam = waterLod(footprint, CELL_SCALE_B);

  // still water drifts with the wind, slowly; running water carries its ripples in two phases (water_common.glsl)
  bool ocean = type == 2 || type == 3 || type == 4;
  float stillSlow = ocean ? OCEAN_STILL_SLOW : STILL_SLOW;
  vec2 base = waterDrift.xy * (speed * (ocean ? OCEAN_STILL_DRIFT : STILL_DRIFT));
  vec2 m0 = base, m1 = base;
  float w0 = 1.0;
  float flowing = smoothstep(FLOW_MIN * 0.5, FLOW_MIN, flowSpeed);
  if (flowing > 0.0) {
    waterFlowPhases(xz, flow, base, m0, m1, w0);
    vec2 o1 = vec2(173.0, 91.0);
    // running water's waves run with it
    vec2 dir = flow / flowSpeed;
    vec2 flowed = ripples(xz, m0, footprint, 1.0, FLOW_WAVE_SIZE, dir) * w0
      + ripples(xz + o1, m1, footprint, 1.0, FLOW_WAVE_SIZE, dir) * (1.0 - w0);
    // blending two copies averages their slopes down: give some back
    flowed *= 1.0 + 0.4 * (1.0 - abs(2.0 * w0 - 1.0));
    slope = flowing < 1.0
      ? mix(ripples(xz, base, footprint, stillSlow, STILL_WAVE_SIZE, WAVE_DIR), flowed, flowing) : flowed;
  } else {
    slope = ripples(xz, base, footprint, stillSlow, STILL_WAVE_SIZE, WAVE_DIR);
  }
  float contact = textureLod(waterContact, (xz / WATER_TILE + waterGrid.x) / (waterGrid.y - 1.0), 0.0).r;
  contact *= contact;
  slope *= look.w * (0.7 + 0.5 * wind) * (0.75 + 0.35 * energy) * (1.0 + TURB_WAVES * turb)
    * mix(STILL_WAVES, FAST_WAVES, smoothstep(FLOW_MIN * 0.5, FAST_FLOW, flowSpeed)) * (1.0 + CONTACT_WAVES * contact)
    * mix(SHORE_WAVES, 1.0, smoothstep(0.0, SHORE_CALM, data.r) * smoothstep(0.5, 1.0, data.b));
  // soft foam on rapids: the large cells' rims, carried with the flow
  foam = 0.0;
  // fast water foams a little along its banks: by the flow before it slows toward them (water_vert.glsl)
  float fast = length(waterPage0(waterData, xz / WATER_TILE + waterGrid.x).gb) * WATER_TILE * speed;
  float bankFoam = smoothstep(BANK_FOAM_FLOW.x, BANK_FOAM_FLOW.y, fast)
    * (1.0 - smoothstep(BANK_FOAM_REACH.x, BANK_FOAM_REACH.y, data.r));
  float churn = max(smoothstep(TURB_FOAM.x, TURB_FOAM.y, turb), bankFoam * BANK_FOAM);
  // a fall's foam is the hard edged one of its frame, in every pixel
  if (churn > 0.0 && fallFrame(xz) == vec2(0.0)) {
    float h = waterFoamHeight(xz, base, m0, m1, w0, flowing, lodFoam);
    foam = waterFoamRim(h) * churn * FOAM;
  }
}

// The hard edged foam at xz: around things standing in the water, and on terrain falls and at their lip and foot (the
// composite lays them in the same two tones): x the tint, y the core. The foam cells are the water's own, carried with
// its flow (m0, m1, w0, flowing), or on a fall laid out along its slope.
vec2 hardFoam(vec2 xz, vec2 base, vec2 m0, vec2 m1, float w0, float flowing, float footprint) {
  vec2 frame = fallFrame(xz);
  vec2 laid = fallLaid(xz, frame);
  float laidLod = waterLod(max(length(dFdx(laid)), length(dFdy(laid))), CELL_SCALE_B);
  float cells = frame != vec2(0.0) ? fallFoamHeight(laid, frame, laidLod)
    : waterFoamHeight(xz, base, m0, m1, w0, flowing, waterLod(footprint, CELL_SCALE_B));
  return contactFoam(xz, cells);
}

void main() {
  if (waterBake == 1) {
    vec2 slope;
    float foam;
    waterWaveField(vXZ, vType, vFlow, slope, foam);
    WaveOut = vec4(slope, foam, 1.0);
    return;
  }
  // behind the opaque scene: not seen (reverse-z, larger is nearer); in front of the sky past the drawn scene: the
  // composite only shades water over what is under it
  ivec2 px = ivec2(gl_FragCoord.xy) + waterOrigin;
  float sceneZ = texelFetch(sceneDepth, px, 0).r;
  if (gl_FragCoord.z < sceneZ || sceneZ <= waterSkyDepth)
    discard;

  int type = vType;
  vec2 xz = vXZ;
  float speed = WATER_FLOW_SPEED[type];
  vec4 look = WATER_LOOK[type];
  float wind = waterWind.z;
  // page 1 here (shore distance in tiles, wave energy, water share, turbulence), filtered between the corners in the
  // fragment: through the vertices it bent along each quad's diagonal, which showed as straight edges in the color
  vec4 data = waterPage1(waterData, xz / WATER_TILE + waterGrid.x);
  float energy = data.g;
  float turb = data.a * step(0.5, speed);
  vec2 flow = vFlow;
  float flowSpeed = length(flow);

  // mips picked from the derivatives once, here, where they are defined; every tap below is explicit
  float footprint = max(length(dFdx(xz)), length(dFdy(xz)));

  // the waves and foam from the wave maps (waterWaveField, a few steps a second, blended), none past their reach
  vec2 wuv = xz / (WATER_TILE * WAVE_MAP_TILES);
  // a little sharper than the mipmaps would pick: they average the slopes away
  vec4 wave = mix(texture(waterWavesA, wuv, -0.5), texture(waterWavesB, wuv, -0.5), waterWaveBlend)
    * (1.0 - smoothstep(WAVE_MAP_REACH - 8.0, WAVE_MAP_REACH, length(xz - waterFocus) / WATER_TILE));
  vec2 slope = wave.xy;
  float foam = wave.z;
  // the flow's phases for the caustics
  bool ocean = type == 2 || type == 3 || type == 4;
  vec2 base = waterDrift.xy * (speed * (ocean ? OCEAN_STILL_DRIFT : STILL_DRIFT));
  vec2 m0 = base, m1 = base;
  float w0 = 1.0;
  float flowing = smoothstep(FLOW_MIN * 0.5, FLOW_MIN, flowSpeed);
  if (flowing > 0.0)
    waterFlowPhases(xz, flow, base, m0, m1, w0);
  vec3 rain = vec3(0.0);
  if (waterRain > 0.01 && waterOutdoors == 1) {
    rain = rainRings(xz);
    slope += rain.xy * look.w;
  }
  // the surface's normal points up (-y); slope is d up / d x, d up / d z
  vec3 nrm = normalize(vec3(-slope.x, -1.0, -slope.y));

  foam = max(foam, rain.z * RAIN_FOAM);

  // ---- the caustics on the bed below, carried by the flow
  float caustic = 0.0;
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
  }

  uvec2 bits;
  if (waterDebug == 2) {
    // the waves as a relief lit from one side, crests light on one face and dark on the other; foam red
    vec3 c = vec3(clamp(0.5 + 2.5 * dot(slope, vec2(0.7071, 0.7071)), 0.0, 1.0));
    c = mix(c, vec3(1.0, 0.2, 0.1), foam);
    bits.x = pack01(c.r, 255.0) | pack01(c.g, 255.0) << 8 | pack01(c.b, 255.0) << 16;
  } else if (waterDebug == 1) {
    // type color, flow as stripes that move with it, turbulent water reddens
    const vec3 DEBUG_TYPE[WATER_TYPES] = vec3[](vec3(0.0), vec3(0.2, 0.6, 1.0), vec3(0.0, 0.2, 0.9),
      vec3(0.3, 0.3, 0.6), vec3(0.6, 0.9, 1.0), vec3(0.4, 0.6, 0.1), vec3(0.4, 0.3, 0.3));
    float tiles = flowSpeed / (WATER_TILE * max(speed, 1e-3));
    vec2 dir = flowSpeed > 1e-3 ? flow / flowSpeed : vec2(0.0);
    float stripe = flowSpeed > 1e-3 ? step(0.5, fract(dot(xz / 64.0, dir) - waterTime * tiles * 2.0)) : 0.5;
    vec3 c = mix(DEBUG_TYPE[type], vec3(stripe), 0.35) * mix(0.6, 1.0, energy);
    c = mix(c, vec3(1.0, 0.25, 0.1), turb * 0.6);
    bits.x = pack01(c.r, 255.0) | pack01(c.g, 255.0) << 8 | pack01(c.b, 255.0) << 16;
  } else {
    // normal x, z (9 bits each), look slot (5: type and the game's texture, Water.java), foam (3), depth (6, square
    // root)
    bits.x = pack01(nrm.x * 0.5 + 0.5, 511.0) | pack01(nrm.z * 0.5 + 0.5, 511.0) << 9 | uint(vSlot & 31) << 18
      | pack01(foam, 7.0) << 23 | pack01(sqrt(nominalDepth(data.r) / 512.0), 63.0) << 26;
  }
  // distance (16 bits), caustics: -1 to 3 (8 bits), the hard edged foam's rim and core coverage (4 bits each)
  vec2 hard = hardFoam(xz, base, m0, m1, w0, flowing, footprint);
  bits.y = pack01(length(vRel) / WATER_REACH, 65535.0) | pack01((caustic + 1.0) / 4.0, 255.0) << 16
    | pack01(hard.x, 15.0) << 24 | pack01(hard.y, 15.0) << 28;
  // never 0, which is no water
  bits.y = max(bits.y & 0xFFFFu, 1u) | (bits.y & 0xFFFF0000u);
  WaterOut = uvec4(bits.x & 0xFFFFu, bits.x >> 16, bits.y & 0xFFFFu, bits.y >> 16);
}
