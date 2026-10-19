// Shared by the water surface pass (water_vert.glsl, water_frag.glsl) and the composite's water shading (water.glsl):
// the water types, the wave noise and how the surface moves.
//
// The look follows 117 HD's water (BSD-2, see LICENSE): per type a surface color, a depth color the bed fades into
// over a short depth, a base opacity and how much of the sky the surface shows; caustics from their caustics map.

uniform sampler2DArray waterNoise;  // layer 0 tiling waves: rg slope, b height, a finer height; layer 1 caustics
uniform float waterTime;            // seconds, wraps
uniform vec2 waterGrid;             // x: extended tile of local tile 0, y: corners per side

#define WATER_TILE 128.0
// waterTime wraps after this many seconds (Water.java); every motion repeats a whole number of times in it
#define WATER_TIME_WRAP 3840.0
#define WATER_TAU 6.28318530718

// Water types (WaterMap.java): none, fresh, ocean, dark ocean, pale ocean, swamp, cave. 117 HD's WATER,
// WATER_OPEN, WATER_DEEP, WATER_ENDLESS, SWAMP_WATER and CAVE_WATER.
#define WATER_TYPES 7
// sRGB: the surface's own color, lit. 117 HD multiplies its sRGB colors by their light, several times brighter than
// ours: the murky types' are raised to come out as theirs do in daylight (theirs #172114 and #050D11).
const vec3 WATER_SURFACE_COLOR[WATER_TYPES] = vec3[](
  vec3(0.0),
  vec3(0.412, 0.502, 0.612),
  vec3(0.349, 0.447, 0.569),
  vec3(0.239, 0.294, 0.443),
  vec3(0.533, 0.600, 0.678),
  vec3(0.190, 0.280, 0.150),
  vec3(0.050, 0.100, 0.140));
// sRGB: what the bed turns into a little way down, before it goes dark further down
const vec3 WATER_DEPTH_COLOR[WATER_TYPES] = vec3[](
  vec3(0.0),
  vec3(0.000, 0.459, 0.557),
  vec3(0.000, 0.420, 0.560),
  vec3(0.000, 0.360, 0.520),
  vec3(0.060, 0.520, 0.600),
  vec3(0.161, 0.322, 0.102),
  vec3(0.042, 0.144, 0.162));
// sRGB foam color
const vec3 WATER_FOAM_COLOR[WATER_TYPES] = vec3[](
  vec3(0.0),
  vec3(0.690, 0.643, 0.573),
  vec3(0.690, 0.643, 0.573),
  vec3(0.690, 0.643, 0.573),
  vec3(0.690, 0.643, 0.573),
  vec3(0.451, 0.471, 0.396),
  vec3(0.300, 0.300, 0.300));
// base opacity, share of the sky in the surface color (117 HD's fresnelAmount), specular strength, normal strength.
// 117 HD's, but clear water is less opaque (theirs 0.5) so the shallows show their bed and caustics.
const vec4 WATER_LOOK[WATER_TYPES] = vec4[](
  vec4(0.0),
  vec4(0.25, 0.80, 0.50, 1.00),
  vec4(0.25, 0.80, 0.50, 1.00),
  vec4(0.30, 0.80, 0.50, 1.00),
  vec4(0.25, 0.80, 0.50, 1.00),
  vec4(0.80, 0.30, 0.10, 0.45),
  vec4(0.75, 0.10, 0.20, 0.70));
// Depths (world units, before the clarity setting) over which the bed fades into the depth color, then darker, and
// how much of the depth color is left in the deep (117 HD goes to black: the deep stays the type's color here)
const vec3 WATER_FADE[WATER_TYPES] = vec3[](
  vec3(1.0),
  vec3(150.0, 500.0, 0.55),
  vec3(150.0, 520.0, 0.50),
  vec3(130.0, 460.0, 0.42),
  vec3(170.0, 560.0, 0.55),
  vec3(40.0, 160.0, 0.45),
  vec3(60.0, 220.0, 0.35));
// Motion per type: swell height (world units), swell wave length, chop height, flow speed
const vec4 WATER_MOTION[WATER_TYPES] = vec4[](
  vec4(0.0),
  vec4(2.5, 360.0, 1.6, 1.0),
  vec4(9.0, 980.0, 2.6, 1.0),
  vec4(10.0, 1100.0, 2.8, 1.0),
  vec4(7.5, 900.0, 2.4, 1.0),
  vec4(0.0, 300.0, 0.3, 0.35),
  vec4(0.0, 300.0, 0.4, 0.45));

// The swell: SWELL_WAVES Gerstner waves around the wind's direction (angle, length and height relative to the type's)
#define SWELL_WAVES 3
const vec3 SWELL_SHAPE[SWELL_WAVES] = vec3[](
  vec3(0.0, 1.0, 1.0),
  vec3(0.52, 0.71, 0.62),
  vec3(-0.41, 0.53, 0.45));
// how steep the crests get at most (Gerstner Q, 0 sine waves, 1 sharp crests); world units per second squared of gravity;
// the waves run slower than real ones by SWELL_SLOW (they read better in the game's scale)
#define SWELL_STEEP 0.45
#define WATER_GRAVITY 1254.0
#define SWELL_SLOW 0.45

// world units per repeat of the two wave octaves; the small one is turned so they never line up
#define WAVE_SCALE_A 560.0
#define WAVE_SCALE_B 190.0
#define WAVE_MIX_A 0.58
#define WAVE_MIX_B 0.36
// seconds per flow map phase: waves are dragged at most this long before fading to a fresh copy
#define FLOW_CYCLE 2.4
// the furthest the waves are dragged in one phase, world units: fast rapids would stretch them into streaks
#define FLOW_DRAG 360.0
// flow (world units per second) below which water just drifts with the wind; between half of it and it the two blend
#define FLOW_MIN 6.0
// the flow phases are offset across the water by up to this many cycles over this many world units
#define FLOW_JITTER 0.5
#define FLOW_JITTER_SCALE 6144.0
// within a tile the flow slows toward the bank to BANK_SLOW of it
#define BANK_SLOW 0.5

vec2 waterCorner(vec2 worldXZ) {
  return clamp(worldXZ / WATER_TILE + waterGrid.x, vec2(0.0), vec2(waterGrid.y - 1.0));
}

// page 0 (height, flow x, flow z, type) and page 1 (shore, energy, water share, turbulence) at corner coordinates c,
// filtered between corners
vec4 waterPage0(sampler2D data, vec2 c) {
  return textureLod(data, (c + 0.5) / vec2(2.0 * waterGrid.y, waterGrid.y), 0.0);
}

vec4 waterPage1(sampler2D data, vec2 c) {
  return textureLod(data, (c + vec2(waterGrid.y, 0.0) + 0.5) / vec2(2.0 * waterGrid.y, waterGrid.y), 0.0);
}

// The flow phases at xz: two copies of the waves dragged along the flow half a cycle apart, each fading out before
// it has been dragged too far, so the waves move without ever stretching. m0, m1: how far each has moved; w0 the
// first one's weight. The phase is offset across the water so the fades don't pulse in step.
void waterFlowPhases(vec2 xz, vec2 flow, vec2 base, out vec2 m0, out vec2 m1, out float w0) {
  float speed = length(flow);
  vec2 drag = flow * min(1.0, FLOW_DRAG / max(speed * FLOW_CYCLE, 1e-3));
  float jitter = textureLod(waterNoise, vec3(xz / FLOW_JITTER_SCALE, 0.0), 7.0).b * FLOW_JITTER;
  float ph0 = fract(waterTime / FLOW_CYCLE + jitter);
  float ph1 = fract(ph0 + 0.5);
  w0 = 1.0 - abs(1.0 - 2.0 * ph0);
  m0 = base + drag * (ph0 * FLOW_CYCLE);
  m1 = base + drag * (ph1 * FLOW_CYCLE);
}

// A whole number of cycles per wrap of waterTime: the phase of a wave of angular frequency omega at waterTime
float waterWavePhase(float omega) {
  float cycles = floor(omega * WATER_TIME_WRAP / WATER_TAU + 0.5);
  return fract(cycles * (waterTime / WATER_TIME_WRAP)) * WATER_TAU;
}

// The swell at xz: Gerstner waves of height `amp` and length `len` around the wind direction `dir`. Returns the
// displacement (x, up, z) and adds the surface's slope (d up / d x, d up / d z) to slope.
vec3 waterSwell(vec2 xz, vec2 dir, float amp, float len, inout vec2 slope) {
  vec3 disp = vec3(0.0);
  if (amp <= 0.0)
    return disp;
  for (int i = 0; i < SWELL_WAVES; ++i) {
    vec3 shape = SWELL_SHAPE[i];
    float c = cos(shape.x), s = sin(shape.x);
    vec2 d = vec2(dir.x * c - dir.y * s, dir.x * s + dir.y * c);
    float k = WATER_TAU / (len * shape.y);
    float a = amp * shape.z;
    float omega = sqrt(WATER_GRAVITY * k) * SWELL_SLOW;
    // each wave its own fixed offset, so the crests don't all line up at the origin
    float ph = k * dot(d, xz) - waterWavePhase(omega) + float(i) * 2.1;
    float cp = cos(ph), sp = sin(ph);
    // the water moves in circles under small waves, the crests gather it toward them up to SWELL_STEEP
    float h = a * min(1.0, SWELL_STEEP / (k * a * float(SWELL_WAVES)));
    disp += vec3(d.x * h * cp, a * sp, d.y * h * cp);
    slope += d * (k * a * cp);
  }
  return disp;
}
