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
// Depths (world units) over which the bed fades into the depth color, then darker, and
// how much of the depth color is left in the deep (117 HD goes to black: the deep stays the type's color here)
const vec3 WATER_FADE[WATER_TYPES] = vec3[](
  vec3(1.0),
  vec3(240.0, 640.0, 0.55),
  vec3(240.0, 660.0, 0.50),
  vec3(210.0, 600.0, 0.42),
  vec3(270.0, 700.0, 0.55),
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

// page 0 (height, flow x, flow z, type), page 1 (shore, energy, water share, turbulence) and page 2 (see below) at
// corner coordinates c, filtered between corners
vec4 waterPage0(sampler2D data, vec2 c) {
  return textureLod(data, (c + 0.5) / vec2(3.0 * waterGrid.y, waterGrid.y), 0.0);
}

vec4 waterPage1(sampler2D data, vec2 c) {
  return textureLod(data, (c + vec2(waterGrid.y, 0.0) + 0.5) / vec2(3.0 * waterGrid.y, waterGrid.y), 0.0);
}

// page 2: the straight line distance to the nearest land corner in tiles, the share of the corner's water that is open
// sea (the shore waves' field, WaterMap)
vec4 waterPage2(sampler2D data, vec2 c) {
  return textureLod(data, (c + vec2(2.0 * waterGrid.y, 0.0) + 0.5) / vec2(3.0 * waterGrid.y, waterGrid.y), 0.0);
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

// The surface pass's target at pixel px (water_frag.glsl): it holds only the pixels of rect (x0, y0 to x1, y1
// exclusive, WaterSurface.anyVisible), from its corner; none outside it
uvec4 waterSurfaceAt(usampler2D surface, ivec4 rect, ivec2 px) {
  return all(greaterThanEqual(px, rect.xy)) && all(lessThan(px, rect.zw)) ? texelFetch(surface, px - rect.xy, 0)
    : uvec4(0u);
}

// How far under the water surface the scene at px lies (p: camera-relative), from the surface pass's target
// (waterSurfaceAt); 0 above it or with no water there
float waterDepthUnder(usampler2D surface, ivec4 rect, ivec2 px, vec3 p) {
  uint b = waterSurfaceAt(surface, rect, px).b;
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
#define WAVE_MAP_TILES 96.0
#define WAVE_MAP_REACH 46.0

// A hard edge: 1 where inside (a distance, positive within the shape) is positive, 0 where negative, over about a pixel
// (aa, the distance a pixel spans)
float waterEdge(float inside, float aa) {
  return clamp(0.5 + inside / max(aa, 1e-5), 0.0, 1.0);
}

// A scroll of a whole number of repeats per wrap of waterTime, about one per `period` seconds
float waterCycle(float period) {
  return fract(floor(WATER_TIME_WRAP / period + 0.5) * (waterTime / WATER_TIME_WRAP));
}

// ---------------------------------------------------------------------------------------------------------------
// Procedural waves, also used by the composite for water the models only fake (water.glsl waterFake)

// The waves: the standard choppy sea of sine octaves. Each octave is a grid of sine waves warped by noise (so it
// never tiles or lines up), folded to 1 - |sin| and raised to WAVE_CHOP: sharp crests over broad rounded troughs.
// WAVE_OCTAVES of them, the first WAVE_LONGEST tiles from crest to crest, each next turned (about 37 degrees) and
// WAVE_SHRINK as long, WAVE_FADE as high, and a little less choppy. Each runs both ways at WAVE_SPEED wavelengths a
// second, which makes them heave in place rather than slide. WAVE_HEIGHT: world units of the first.
#define WAVE_DIR vec2(0.70710678, 0.70710678)
#define WAVE_OCTAVES 3
#define WAVE_LONGEST 5.5
#define WAVE_SHRINK 0.53
#define WAVE_FADE 0.3
#define WAVE_CHOP 1.3
#define WAVE_SPEED 0.11
#define WAVE_HEIGHT 5.5

float waterHash(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

// The foam on rapids, and on a fall's lip and foot: the rims of the large ripple cells (height h, Water.cells), as much
// of the water's foam colour as FOAM of it where the water is fully churned
#define FOAM 0.45
// the cells' height the foam's rim fades out over, and how far its shapes shrink away from things in the water (a
// cell's height raised by this much at no closeness, none at full)
#define FOAM_RIM vec2(0.15, 0.55)
#define CONTACT_SHRINK 0.5
float waterFoamRim(float h) {
  return 1.0 - smoothstep(FOAM_RIM.x, FOAM_RIM.y, h);
}

// The ripple cells' height at p, in cells, as Water.cells makes them for the water pass's texture: the domes of the
// jittered points around p added up
float waterFoamCells(vec2 p) {
  vec2 id = floor(p);
  float sum = 0.0;
  for (int j = -1; j <= 1; ++j) {
    for (int i = -1; i <= 1; ++i) {
      vec2 c = id + vec2(float(i), float(j));
      vec2 at = c + 0.2 + 0.6 * vec2(waterHash(vec3(mod(c, 64.0), 11.0)), waterHash(vec3(mod(c, 64.0), 12.0)));
      vec2 d = (p - at) / 0.85;
      float k = max(1.0 - dot(d, d), 0.0);
      sum += k * k * k;
    }
  }
  return min(sum, 1.0);
}

// Value noise, not tiling (hashed lattice): -1 to 1
float waveNoise(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  float a = waterHash(vec3(i, 7.0)), b = waterHash(vec3(i + vec2(1.0, 0.0), 7.0));
  float c = waterHash(vec3(i + vec2(0.0, 1.0), 7.0)), d = waterHash(vec3(i + vec2(1.0, 1.0), 7.0));
  return mix(mix(a, b, u.x), mix(c, d, u.x), u.y) * 2.0 - 1.0;
}

// Value noise with its gradient, not tiling (hashed lattice): x the value -1 to 1, yz its gradient
vec3 waveNoiseD(vec2 p) {
  vec2 i = floor(p), f = fract(p);
  vec2 u = f * f * (3.0 - 2.0 * f);
  vec2 du = 6.0 * f * (1.0 - f);
  float a = waterHash(vec3(i, 7.0)), b = waterHash(vec3(i + vec2(1.0, 0.0), 7.0));
  float c = waterHash(vec3(i + vec2(0.0, 1.0), 7.0)), d = waterHash(vec3(i + vec2(1.0, 1.0), 7.0));
  float k1 = b - a, k2 = c - a, k3 = a - b - c + d;
  return vec3(a + k1 * u.x + k2 * u.y + k3 * u.x * u.y, du * vec2(k1 + k3 * u.y, k2 + k3 * u.x)) * 2.0
    - vec3(1.0, 0.0, 0.0);
}

// The gradient (by uv) of one octave of choppy waves at uv (radians of its sines): a grid of sines warped by noise,
// folded to 1 - |sin| blended toward |cos|, their product raised so the crests come to a point. Worked out exactly
// rather than by looking the height up three times.
vec2 seaOctaveGrad(vec2 uv, vec3 n, float chop) {
  vec2 w = uv + n.x;
  vec2 s = sin(w), c = cos(w);
  vec2 wv = 1.0 - abs(s), dwv = -sign(s) * c;
  vec2 swv = abs(c), dswv = -sign(c) * s;
  vec2 m = wv + (swv - wv) * wv;
  vec2 dm = dwv * (1.0 + swv - 2.0 * wv) + dswv * wv;
  float P = max(m.x * m.y, 1e-4);
  vec2 dP = vec2(dm.x * m.y, m.x * dm.y);
  float R = max(1.0 - pow(P, 0.65), 1e-4);
  vec2 g = -chop * pow(R, chop - 1.0) * 0.65 * pow(P, -0.35) * dP;
  // through the warp: w = uv + n(uv) on both axes
  return g.x * vec2(1.0 + n.y, n.z) + g.y * vec2(n.y, 1.0 + n.z);
}
