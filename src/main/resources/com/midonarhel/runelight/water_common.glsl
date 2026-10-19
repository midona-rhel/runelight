// Shared by the water surface pass (water_vert.glsl, water_frag.glsl) and the composite's water shading (water.glsl):
// the water types, the wave noise and how the surface moves.
//
// The look follows 117 HD's water (BSD-2, see LICENSE): per type a surface color, a depth color the bed fades into
// over a short depth, a base opacity and how much of the sky the surface shows; caustics from their caustics map.

uniform sampler2DArray waterNoise;  // layer 0 tiling noise (b: the flow phases' jitter); layer 1 caustics
uniform float waterTime;            // seconds, wraps
uniform vec2 waterGrid;             // x: extended tile of local tile 0, y: corners per side

#define WATER_TILE 128.0
// how far the surface target's distance reaches (water_frag.glsl)
#define WATER_REACH 20480.0
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
// how fast each type's flow runs, as a share of the flow map's
const float WATER_FLOW_SPEED[WATER_TYPES] = float[](0.0, 1.0, 1.0, 1.0, 1.0, 0.35, 0.45);

// seconds per flow map phase: waves are dragged at most this long before fading to a fresh copy
#define FLOW_CYCLE 2.4
// the furthest the waves are dragged in one phase, world units: fast rapids would stretch them into streaks
#define FLOW_DRAG 360.0
// flow (world units per second) below which water just drifts with the wind; between half of it and it the two blend
#define FLOW_MIN 6.0
// the flow phases are offset across the water by up to this many cycles over this many world units
#define FLOW_JITTER 0.5
#define FLOW_JITTER_SCALE 6144.0
// the flow slows toward the bank to BANK_SLOW of it at the shore, over BANK_REACH tiles out
#define BANK_SLOW 0.15
#define BANK_REACH 2.5

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

// How far under the water surface the scene at px lies (p: camera-relative), from the surface pass's target
// (water_frag.glsl); 0 above it or with no water there
float waterDepthUnder(usampler2D surface, ivec2 px, vec3 p) {
  uint b = texelFetch(surface, px, 0).b;
  if (b == 0u) {
    return 0.0;
  }
  float dist = length(p);
  float t = float(b) / 65535.0 * WATER_REACH;
  return t < dist ? max(p.y * (1.0 - t / dist), 0.0) : 0.0;
}

// WaterMap.DEPTH: bed depth per tile from the shore; a water tile is at least WaterMap.MIN_DEPTH deep in its middle
const float BED_DEPTH[9] = float[](0.0, 82.0, 165.0, 258.0, 335.0, 385.0, 412.0, 451.0, 506.0);
#define BED_MIN 56.0

// The bed's depth going by the shore distance alone (tiles, the average of the water tiles around the corner, 0 on
// land): the bed itself sinks in a pit in the middle of every tile at the shore, which the water's color must not show
float nominalDepth(float shore) {
  float level = clamp(shore - 0.5, 0.0, 7.999);
  int i = int(level);
  float d = mix(BED_DEPTH[i], BED_DEPTH[i + 1], level - float(i));
  return shore > 0.0 ? max(d, BED_MIN * min(shore, 1.0)) : 0.0;
}

// The waves are worked out at a few steps a second into world-space maps of WAVE_MAP_TILES tiles, wrapping, which the
// surface pass blends between (WaterSurface.bake): beyond WAVE_MAP_REACH tiles from where the camera looks there are
// none
#define WAVE_MAP_TILES 128.0
#define WAVE_MAP_REACH 60.0

// A scroll of a whole number of repeats per wrap of waterTime, about one per `period` seconds
float waterCycle(float period) {
  return fract(floor(WATER_TIME_WRAP / period + 0.5) * (waterTime / WATER_TIME_WRAP));
}
