#version 330

// The water surface pass's fragments (WaterSurface.java): only where the surface is in front of the scene, it writes
// how far away the surface is along the view ray, its normal, type and foam, how deep the water is there and the
// caustics on the bed below, for the composite to shade (water.glsl).
//
// The normal: two trains of wind waves with 117 HD's water normal map for detail, carried along the flow map in two
// phases so the current shows in the sky it reflects (still water runs slower), and rain rings. The caustics are
// 117 HD's, carried along by the same flow and drawn out along fast water.

#include "water_common.glsl"

uniform sampler2D sceneDepth;  // the opaque scene, resolved
uniform sampler2D waterData;    // WaterMap.texture (water_vert.glsl)
uniform sampler2D waterContact; // WaterMap.contact: how close something standing in the water is, 4 texels a tile
uniform vec3 waterWind;
uniform vec4 waterDrift;
uniform float waterRain;
uniform float waterWaves;
uniform float waterSpeed;
uniform int waterOutdoors;
uniform int waterDebug;         // 1 types and flow, 2 the waves as a relief (WaterDebug)
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
flat in int vType;
flat in int vSlot;

layout(location = 0) out uvec4 WaterOut;  // 64 bits packed, see water.glsl's waterUnpack

// The waves: broad, gentle swells rather than crests (the look of calm open water in a stylised game): smooth noise,
// not tiling, WAVE_OCTAVES sizes from WAVE_LONGEST tiles across, each WAVE_SHRINK as large and WAVE_FADE as steep,
// each drifting its own way at WAVE_SPEED tiles a second and bent by the one before (domain warp, WAVE_WARP), so the
// shapes change as they move and never line up into stripes or a grid. WAVE_STRENGTH: their slope.
#define WAVE_DIR vec2(0.70710678, 0.70710678)
#define WAVE_OCTAVES 4
#define WAVE_LONGEST 1.4
#define WAVE_SHRINK 0.47
#define WAVE_FADE 0.7
#define WAVE_SPEED 0.12
#define WAVE_WARP 0.35
// each size's swells are WAVE_STRETCH times longer along their crests than across: ridges, not round bubbles
#define WAVE_STRETCH 1.6
// how sharp the crests come to a point (smaller sharper), and their steepness against the troughs'
#define WAVE_CREST_SOFT 0.08
#define WAVE_CREST 0.6
#define WAVE_STRENGTH 0.34
// still water (no flow) runs its waves and its detail this many times slower, and drifts less with the wind
#define STILL_SLOW 1.25
#define STILL_DRIFT 0.4
// the sea's still water less so
#define OCEAN_STILL_SLOW 1.0
#define OCEAN_STILL_DRIFT 1.0
// The sea's swell at the shore: long low waves rolling in toward it, their crests along it, SWELL_LENGTH tiles apart
// and SWELL_PERIOD seconds each, at most SWELL_SLOPE steep, from SWELL_REACH.y tiles out strengthening to
// SWELL_REACH.x and dying on the beach; stronger and weaker along the shore every SWELL_PATCH world units or so
#define SWELL_LENGTH 1.6
#define SWELL_PERIOD 7.0
#define SWELL_SLOPE 0.18
#define SWELL_REACH vec2(1.2, 4.0)
#define SWELL_PATCH 2400.0
// the waves' size on still and on running water (as steep either way): more of them, denser, the smaller
#define STILL_WAVE_SIZE 0.55
#define FLOW_WAVE_SIZE 0.6
// the waves' height by the flow: STILL_WAVES on still water, rising to FAST_WAVES at FAST_FLOW world units a second
#define STILL_WAVES 0.6
#define FAST_WAVES 1.8
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
// around things standing in the water (WaterMap.contact): the rapids' foam, up to CONTACT_FOAM of it over a faint
// CONTACT_FILM, and waves up to 1 + CONTACT_WAVES as rough
#define CONTACT_FOAM 0.3
#define CONTACT_FILM 0.06
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

float waterHash(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
}

// Value noise, not tiling (hashed lattice), and its gradient: xy the gradient, z the value 0 to 1
vec3 waveNoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  vec2 du = 30.0 * f * f * (f * (f - 2.0) + 1.0);
  float a = waterHash(vec3(i, 7.0)), b = waterHash(vec3(i + vec2(1.0, 0.0), 7.0));
  float c = waterHash(vec3(i + vec2(0.0, 1.0), 7.0)), d = waterHash(vec3(i + vec2(1.0, 1.0), 7.0));
  float k1 = b - a, k2 = c - a, k3 = a - b - c + d;
  return vec3(du * vec2(k1 + k3 * u.y, k2 + k3 * u.x), a + k1 * u.x + k2 * u.y + k3 * u.x * u.y);
}

// The waves' slope at q (world units), drifting along dir, `slow` times slower and `size` times as large
vec2 crestWaves(vec2 q, vec2 dir, float footprint, float slow, float size) {
  float len = WAVE_LONGEST * WATER_TILE * size, amp = 1.0, total = 0.0;
  vec2 slope = vec2(0.0), warp = vec2(0.0);
  for (int i = 0; i < WAVE_OCTAVES; ++i) {
    // each its own way, near the wind's
    float a = (float(i) - 1.5) * 0.7;
    vec2 d = vec2(dir.x * cos(a) - dir.y * sin(a), dir.x * sin(a) + dir.y * cos(a));
    // in the wave's own frame: along d (across its crests) and along the crests, stretched
    vec2 c = vec2(-d.y, d.x);
    vec2 w = q + warp;
    vec2 p = vec2(dot(w, d), dot(w, c) / WAVE_STRETCH) / len - vec2(waterTime * WAVE_SPEED * WATER_TILE / len / slow, 0.0)
      + float(i) * 13.7;
    vec3 n = waveNoise(p);
    // its gradient back in world directions
    n.xy = d * n.x + c * (n.y / WAVE_STRETCH);
    // Sharp crests: folded where the noise crosses its middle (a crease, just softened against shimmer), and squared,
    // so the crest stands steep and narrow over broad, gentle troughs
    float m = 2.0 * n.z - 1.0;
    float am = sqrt(m * m + WAVE_CREST_SOFT);
    float h = 1.0 - am;
    n.xy *= 2.0 * h * (-2.0 * m / am) * WAVE_CREST;
    // finer than a few pixels: faded, it only shimmers
    float vis = smoothstep(4.0, 10.0, len / max(footprint, 1e-3));
    slope += n.xy * (amp * vis);
    warp += n.xy * (WAVE_WARP * len);
    total += amp;
    len *= WAVE_SHRINK;
    amp *= WAVE_FADE;
  }
  return slope * (WAVE_STRENGTH / total);
}

// The waves at xz moved along by `move`, running along dir: blended from the two nearest of eight fixed directions,
// as turning their coordinates to follow a winding flow would swirl them, more the further from the origin; the
// weights keep their strength.
vec2 ripples(vec2 xz, vec2 move, float footprint, float slow, float size, vec2 dir) {
  vec2 q = xz - move;
  float sector = atan(dir.y, dir.x) / (WATER_TAU / 8.0);
  float s0 = floor(sector);
  float f = (sector - s0) * (WATER_TAU / 4.0);
  vec2 waves = vec2(0.0);
  // only the directions that count (a flow along one of the eight, or still water, takes one)
  if (cos(f) > 1e-3) {
    waves += crestWaves(q, vec2(cos(s0 * WATER_TAU / 8.0), sin(s0 * WATER_TAU / 8.0)), footprint, slow, size) * cos(f);
  }
  if (sin(f) > 1e-3) {
    waves += crestWaves(q, vec2(cos((s0 + 1.0) * WATER_TAU / 8.0), sin((s0 + 1.0) * WATER_TAU / 8.0)), footprint, slow,
      size) * sin(f);
  }
  return waves;
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
  // page 1 here (shore distance in tiles, wave energy, water share, turbulence), filtered between the corners in the
  // fragment: through the vertices it bent along each quad's diagonal, which showed as straight edges in the color
  vec4 data = waterPage1(waterData, xz / WATER_TILE + waterGrid.x);
  float energy = data.g;
  float turb = data.a * step(0.5, speed);
  vec2 flow = vFlow;
  float flowSpeed = length(flow);

  // mips picked from the derivatives once, here, where they are defined; every tap below is explicit
  float footprint = max(length(dFdx(xz)), length(dFdy(xz)));
  float lodFoam = waterLod(footprint, CELL_SCALE_B);

  // still water drifts with the wind, slowly; running water carries its ripples in two phases (water_common.glsl)
  bool ocean = type == 2 || type == 3 || type == 4;
  float stillSlow = ocean ? OCEAN_STILL_SLOW : STILL_SLOW;
  vec2 base = waterDrift.xy * (speed * waterSpeed * (ocean ? OCEAN_STILL_DRIFT : STILL_DRIFT));
  vec2 m0 = base, m1 = base;
  float w0 = 1.0;
  float flowing = smoothstep(FLOW_MIN * 0.5, FLOW_MIN, flowSpeed);
  vec2 slope;
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
  slope *= look.w * waterWaves * (0.7 + 0.5 * wind) * (0.75 + 0.35 * energy) * (1.0 + TURB_WAVES * turb)
    * mix(STILL_WAVES, FAST_WAVES, smoothstep(FLOW_MIN * 0.5, FAST_FLOW, flowSpeed)) * (1.0 + CONTACT_WAVES * contact)
    * mix(SHORE_WAVES, 1.0, smoothstep(0.0, SHORE_CALM, data.r) * smoothstep(0.5, 1.0, data.b));
  if (ocean && flowing < 1.0) {
    // the swell, across the shore distance's gradient (half a tile either way: smooth across the corners)
    vec2 c = xz / WATER_TILE + waterGrid.x;
    float shore = data.r;
    float reach = smoothstep(0.15, 0.7, shore) * (1.0 - smoothstep(SWELL_REACH.x, SWELL_REACH.y, shore));
    if (reach > 0.0) {
      vec2 g = vec2(waterPage1(waterData, c + vec2(0.5, 0.0)).r - waterPage1(waterData, c - vec2(0.5, 0.0)).r,
        waterPage1(waterData, c + vec2(0.0, 0.5)).r - waterPage1(waterData, c - vec2(0.0, 0.5)).r);
      float patch = textureLod(waterNoise, vec3(xz / SWELL_PATCH, 0.0), 8.0).b;
      float phase = WATER_TAU * (shore / SWELL_LENGTH + waterCycle(SWELL_PERIOD));
      slope += g / max(length(g), 1e-3) * (SWELL_SLOPE * cos(phase) * reach * (1.0 - flowing) * (0.4 + 1.2 * patch)
        * waterWaves);
    }
  }
  vec3 rain = vec3(0.0);
  if (waterRain > 0.01 && waterOutdoors == 1) {
    rain = rainRings(xz);
    slope += rain.xy * look.w;
  }
  // the surface's normal points up (-y); slope is d up / d x, d up / d z
  vec3 nrm = normalize(vec3(-slope.x, -1.0, -slope.y));

  // soft foam on rapids and around things standing in the water: the large cells' rims, carried with the flow
  float foam = 0.0;
  float churn = smoothstep(TURB_FOAM.x, TURB_FOAM.y, turb);
  if (churn > 0.0 || contact > 0.0) {
    float h = textureLod(waterNoise, vec3((xz - m0) / CELL_SCALE_B, 2.0), lodFoam).b * w0
      + textureLod(waterNoise, vec3((xz + vec2(173.0, 91.0) - m1) / CELL_SCALE_B, 2.0), lodFoam).b * (1.0 - w0);
    if (flowing < 1.0) {
      vec2 uv = (xz - base) / CELL_SCALE_B;
      float still = 0.5 * (textureLod(waterNoise, vec3(uv + vec2(waterCycle(FOAM_STILL_PERIOD), 0.0), 2.0), lodFoam).b
        + textureLod(waterNoise, vec3(uv.yx + vec2(0.37, -waterCycle(FOAM_STILL_PERIOD * 1.37)), 2.0), lodFoam).b);
      h = mix(still, h, flowing);
    }
    float rim = 1.0 - smoothstep(0.15, 0.55, h);
    foam = max(rim * churn * FOAM, contact * (rim * CONTACT_FOAM + CONTACT_FILM));
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
    if (depth < CAUSTIC_FADE.y) {
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
  }

  uvec2 packed;
  if (waterDebug == 2) {
    // the waves as a relief lit from one side, crests light on one face and dark on the other; foam red
    vec3 c = vec3(clamp(0.5 + 2.5 * dot(slope, vec2(0.7071, 0.7071)), 0.0, 1.0));
    c = mix(c, vec3(1.0, 0.2, 0.1), foam);
    packed.x = pack01(c.r, 255.0) | pack01(c.g, 255.0) << 8 | pack01(c.b, 255.0) << 16;
  } else if (waterDebug == 1) {
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
      | pack01(foam, 7.0) << 23 | pack01(sqrt(nominalDepth(data.r) / 512.0), 63.0) << 26;
  }
  // distance (16 bits), caustics: green -1 to 3 (8 bits), red and blue -0.5 to 0.5 from it (4 bits each)
  packed.y = pack01(length(vRel) / WATER_REACH, 65535.0) | pack01((caustic + 1.0) / 4.0, 255.0) << 16
    | pack01(causticRB.x + 0.5, 15.0) << 24 | pack01(causticRB.y + 0.5, 15.0) << 28;
  // never 0, which is no water
  packed.y = max(packed.y & 0xFFFFu, 1u) | (packed.y & 0xFFFF0000u);
  WaterOut = uvec4(packed.x & 0xFFFFu, packed.x >> 16, packed.y & 0xFFFFu, packed.y >> 16);
}
