// Water surfaces, drawn by the composite over the lowered water bed (WaterMap.java, Water.java).
//
// The bed and anything else under the water are ordinary pixels of the scene buffers. For those, this finds where
// the view ray crosses the water surface, then shades what is seen through it (refracted, absorbed on the way down
// and back up, tinted by the water's depth) and puts the surface on top: the sky's reflection, roughly the world's,
// sun and lamp glints. The surface normal is two octaves of wave noise moved along the flow map plus a slow swell.
// Shading after 117 HD's water (BSD-2, see LICENSE): the bed darkens toward the water's depth color with depth,
// there is no hard waterline, the waves are lit like a surface, and the sky reflection uses a flattened normal so
// steep waves don't reflect the ground.
// Included by composite_frag.glsl after its lighting helpers (shadows, point light clusters, ign).

uniform int waterEnabled;
uniform sampler2D waterData;   // see WaterMap.texture: page 0 (height, flow x, flow z, type), page 1 (shore, energy)
uniform sampler2D waterNoise;  // tiling waves: rg slope, b height, a an independent finer height
uniform vec2 waterGrid;        // x: extended tile of local tile 0, y: corners per side
uniform float waterTime;       // seconds, wraps
uniform vec3 waterWind;        // xy: direction the wind blows toward (x, z), z: strength 0-1, eased
uniform vec4 waterDrift;       // world units the waves (xy) and the swell (zw) have drifted with the wind
uniform float waterRain;       // 0-1
uniform int waterReflections;  // a few screen-space taps for the world in the reflection
uniform float waterClarity;    // multiplier on how far light gets through
uniform float waterWaves;      // multiplier on wave height
uniform float waterSpeed;      // multiplier on flow
uniform int waterCaustics;
uniform int waterDebug;

#define WATER_TILE 128.0
// waterTime wraps after this many seconds (Water.java); every motion below repeats a whole number of times in it
#define WATER_TIME_WRAP 3840.0

// Water types (WaterMap.java): none, fresh, ocean, dark ocean, pale ocean, swamp, cave
#define WATER_TYPES 7
// extinction per tile of water the light passes, linear rgb: red goes first, murky water swallows everything
const vec3 WATER_EXTINCTION[WATER_TYPES] = vec3[](
  vec3(1.0),
  vec3(0.58, 0.22, 0.15),
  vec3(0.70, 0.24, 0.15),
  vec3(0.90, 0.40, 0.30),
  vec3(0.50, 0.17, 0.12),
  vec3(3.20, 2.40, 3.40),
  vec3(2.20, 1.40, 1.20));
// light scattered back out of deep water per unit of light falling on it (linear): the color the middle of a
// lake goes to. Kept dark so deep water reads deep rather than milky.
const vec3 WATER_DEEP[WATER_TYPES] = vec3[](
  vec3(0.0),
  vec3(0.003, 0.020, 0.036),
  vec3(0.002, 0.016, 0.042),
  vec3(0.003, 0.010, 0.018),
  vec3(0.007, 0.042, 0.062),
  vec3(0.010, 0.013, 0.004),
  vec3(0.002, 0.008, 0.009));
// reflectivity, wave strength, flow speed, specular gloss
// the bed's own color (sRGB, WaterMap.bedColor at the default brightness): sand, pale sand, mud, dark stone. The
// bed's vertices carry the bank's colors (SceneUploader.uploadBed); this takes over with depth.
const vec3 WATER_BED[WATER_TYPES] = vec3[](
  vec3(0.571, 0.521, 0.296),
  vec3(0.571, 0.521, 0.296),
  vec3(0.571, 0.521, 0.296),
  vec3(0.571, 0.521, 0.296),
  vec3(0.591, 0.550, 0.376),
  vec3(0.301, 0.287, 0.156),
  vec3(0.329, 0.300, 0.252));
// depths (world units) over which the bank's color turns into the bed's, and the bed darkens further down
#define BED_BLEND_NEAR 4.0
#define BED_BLEND_FAR 56.0
#define BED_DARKEN 0.3
#define BED_DARKEN_DEPTH 400.0
const vec4 WATER_SURFACE[WATER_TYPES] = vec4[](
  vec4(0.0),
  vec4(1.00, 0.85, 1.00, 900.0),
  vec4(1.00, 1.00, 1.00, 1100.0),
  vec4(0.95, 1.00, 1.00, 800.0),
  vec4(1.00, 0.85, 1.00, 1000.0),
  vec4(0.55, 0.22, 0.35, 160.0),
  vec4(0.65, 0.30, 0.45, 220.0));

// world units per repeat of the two wave octaves and the swell; the second octave is turned so they never line up
#define WAVE_SCALE_A 420.0
#define WAVE_SCALE_B 170.0
#define SWELL_SCALE 1650.0
// normal tilt per unit of noise slope: waves, and the swell on open water (scaled by wave energy)
#define WAVE_STRENGTH 0.42
#define SWELL_STRENGTH 0.14
// what is seen through a wave face turned toward the camera is brighter, turned away darker: makes the waves read
// even where nothing bright is reflected
#define WAVE_SHADE 1.2
// seconds per flow map phase: waves are dragged at most this long before fading to a fresh copy
#define FLOW_CYCLE 2.4
// the furthest the waves are dragged in one phase, world units: fast rapids would otherwise stretch them into
// streaks that snap back at every fade
#define FLOW_DRAG 360.0
// flow (world units per second) below which water just drifts with the wind, without flow map phases; between
// half of it and it the two blend
#define FLOW_MIN 6.0
// the flow phases are offset across the water by up to this many cycles, over this many world units, so the fades
// don't pulse in step. Kept gentle: a phase that changes quickly across the water drags neighbouring waves apart.
#define FLOW_JITTER 0.5
#define FLOW_JITTER_SCALE 6144.0
// how far the bed seen through the water shifts per unit of normal tilt, world units, once the water is deep
#define REFRACT 150.0
#define REFRACT_DEPTH 90.0
// Fresnel at normal incidence, for the glints; the reflection never drops below REFLECT_MIN, a little more than
// physical so the sky shows on water seen from above; the sky reflection's normal keeps SKY_TILT of the tilt
#define WATER_F0 0.02
// the gloss drops as wave detail gets smaller than a pixel, so highlights don't sparkle from pixel to pixel
#define GLOSS_AA 0.004
#define REFLECT_MIN 0.09
#define SKY_TILT 0.6
// glints: sun and moon (tight highlight, broad sheen, sparkle on wave crests), point lights
#define SPECULAR 0.45
#define SHEEN 0.06
#define SPARKLE 1.2
#define LAMP_SPECULAR 0.6
// vertical depth over which the surface fades in from the waterline: no hard edge
#define SOFT_EDGE 22.0
// bed just above the waterline is darker where the water just left it
#define WET_SAND 0.86
#define WET_HEIGHT 12.0
// caustics: a subtle dappling of the sunlight on the bed, a share of the direct light
#define CAUSTICS 0.12
#define CAUSTIC_SCALE 260.0
// rain rings: cell size (world units), rings per cell per second
#define RAIN_CELL 40.0
#define RAIN_RATE 1.3
// screen-space reflection: taps along the reflected ray, growing, then halving steps onto the hit
#define SSR_STEPS 4
#define SSR_FIRST_STEP 96.0
#define SSR_GROWTH 2.0
#define SSR_REFINE 3

vec2 waterCell(vec2 worldXZ) {
  return clamp(worldXZ / WATER_TILE + waterGrid.x, vec2(0.0), vec2(waterGrid.y - 1.0));
}

// Explicit lod everywhere in here: the water is drawn in branches, where implicit derivatives are undefined (NVIDIA
// and AMD do return garbage there)
vec4 waterPage0(vec2 c) {
  return textureLod(waterData, (c + 0.5) / vec2(2.0 * waterGrid.y, waterGrid.y), 0.0);
}

vec4 waterPage1(vec2 c) {
  return textureLod(waterData, (c + vec2(waterGrid.y, 0.0) + 0.5) / vec2(2.0 * waterGrid.y, waterGrid.y), 0.0);
}

int waterTypeAt(vec2 c) {
  ivec2 t = clamp(ivec2(floor(c)), ivec2(0), ivec2(int(waterGrid.y) - 2));
  return clamp(int(texelFetch(waterData, t, 0).a + 0.5), 0, WATER_TYPES - 1);
}

// Mip level for noise repeating every `repeat` world units, seen with `footprint` world units per pixel. Picked by
// hand: the water is drawn in branches, where texture() can't take derivatives from its neighbors.
float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
}

// A drift (world units per second) the scrolling noise repeats exactly with when the time wraps
vec2 waterScroll(vec2 velocity, float repeat) {
  vec2 cycles = floor(velocity / repeat * WATER_TIME_WRAP + 0.5);
  return fract(cycles * (waterTime / WATER_TIME_WRAP));
}

// Wave slope and the finer height (for sparkle) of the two octaves at xz, moved along by `move` (world units)
vec3 waterWaves2(vec2 xz, vec2 move, float la, float lb) {
  const mat2 ROT = mat2(0.8, 0.6, -0.6, 0.8);
  vec4 a = textureLod(waterNoise, (xz - move) / WAVE_SCALE_A, la);
  vec4 b = textureLod(waterNoise, ROT * (xz - move * 1.15) / WAVE_SCALE_B + 0.37, lb);
  // the small octave is turned: turn its slope back
  vec2 slope = (a.rg * 2.0 - 1.0) * 0.62 + ((b.rg * 2.0 - 1.0) * ROT) * 0.38;
  return vec3(slope, a.a);
}

// Rings from raindrops: one ring at a time per cell, in two offset layers, as many cells active as it rains
vec2 waterRainRings(vec2 xz) {
  vec2 sum = vec2(0.0);
  for (int layer = 0; layer < 2; ++layer) {
    vec2 g = xz / RAIN_CELL + float(layer) * vec2(0.5, 0.31);
    vec2 cell = floor(g);
    float h = hash13(vec3(cell, float(layer) * 7.0));
    float t = waterTime * RAIN_RATE + h * 7.0;
    float drop = floor(t);
    float phase = fract(t);
    // a new spot and a new chance to fall every drop
    float h2 = hash13(vec3(cell + drop * 0.123, float(layer) + 3.0));
    if (h2 > waterRain)
      continue;
    vec2 center = vec2(hash13(vec3(cell, drop)), hash13(vec3(cell.yx, drop + 1.0))) * 0.5 + 0.25;
    vec2 dv = fract(g) - center;
    float dist = length(dv);
    float ring = (dist - phase * 0.3) / 0.035;
    float wave = sin(ring * 3.14159) * exp(-ring * ring * 0.5) * (1.0 - phase) * (1.0 - phase);
    sum += dv / max(dist, 1e-3) * wave;
  }
  return sum * 0.35;
}

// Soft dappled light on the bed: two drifting copies of the wave height, bright where both are high
float waterCausticPattern(vec2 xz, float footprint) {
  float lod = waterLod(footprint, CAUSTIC_SCALE * 0.7);
  float a = textureLod(waterNoise, xz / CAUSTIC_SCALE + waterScroll(vec2(23.0, 11.0), CAUSTIC_SCALE), lod).b;
  float b = textureLod(waterNoise, xz / (CAUSTIC_SCALE * 0.7) + 0.21 + waterScroll(vec2(-15.0, 19.0), CAUSTIC_SCALE * 0.7), lod).b;
  return smoothstep(0.35, 0.95, a * b * 2.0);
}

// Where the reflected ray is at t: its pixel, and how far it is behind the depth buffer there (negative in front)
// (1e9 off screen, -1e9 against the sky)
float waterRayBehind(vec3 s, vec3 r, float t, out ivec2 ip, out vec2 ndc) {
  vec4 clip = viewProj * vec4(s + r * t, 1.0);
  ip = ivec2(0);
  ndc = vec2(2.0);
  if (clip.w <= NEAR)
    return 1e9;
  ndc = clip.xy / clip.w;
  if (any(greaterThan(abs(ndc), vec2(1.0))))
    return 1e9;
  ip = ivec2((ndc * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy));
  float dq = texelFetch(sceneDepth, ip, 0).r;
  return isSky(dq) ? -1e9 : clip.w - viewDepth(dq);
}

// The world as seen in the reflection: a few growing steps along the reflected ray against the depth buffer, then
// halving steps onto the crossing, the hit taking its own color under a rough guess of its light. a: how much to
// trust it. No per-pixel jitter: the same ray finds the same hit every frame.
vec4 waterTraceReflection(vec3 s, vec3 r, vec3 approxLight) {
  float stepLen = SSR_FIRST_STEP;
  float prev = 0.0;
  float t = stepLen;
  ivec2 ip;
  vec2 ndc;
  for (int i = 0; i < SSR_STEPS; ++i) {
    float behind = waterRayBehind(s, r, t, ip, ndc);
    if (behind >= 1e9)
      break;
    if (behind > 0.0 && behind < stepLen * 2.5) {
      float lo = prev, hi = t;
      for (int j = 0; j < SSR_REFINE; ++j) {
        float mid = 0.5 * (lo + hi);
        ivec2 mp;
        vec2 mn;
        float b = waterRayBehind(s, r, mid, mp, mn);
        if (b > 0.0 && b < 1e9) {
          hi = mid;
          ip = mp;
          ndc = mn;
        } else {
          lo = mid;
        }
      }
      // water reflecting water shows the sky instead
      if (texelFetch(sceneMaterial, ip, 0).b > 0.75)
        break;
      vec2 edge = smoothstep(vec2(1.0), vec2(0.75), abs(ndc));
      // a hit only just within reach of the step fades rather than popping
      float reach = 1.0 - smoothstep(stepLen * 1.5, stepLen * 2.5, behind);
      return vec4(srgbToLinear(texelFetch(sceneColor, ip, 0).rgb) * approxLight, edge.x * edge.y * reach);
    }
    prev = t;
    stepLen *= SSR_GROWTH;
    t += stepLen;
  }
  return vec4(0.0);
}

// What the bed looks like `depth` under the surface: the bank's color (land, sRGB) near the waterline, the bed's own
// further down, a little uneven
vec3 waterBedAlbedo(vec3 land, float depth, int type, vec2 xz, float footprint) {
  float vary = 0.9 + 0.2 * textureLod(waterNoise, xz / 900.0, waterLod(footprint, 900.0)).b;
  vec3 bed = WATER_BED[type] * vary * (1.0 - BED_DARKEN * smoothstep(40.0, BED_DARKEN_DEPTH, depth));
  return mix(land, bed, smoothstep(BED_BLEND_NEAR, BED_BLEND_FAR, depth));
}

vec3 waterShoulder(vec3 c) {
  return mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
}

// p: camera-relative position of this pixel's scene surface (the bed, or something else under the water)
// n, albedo (sRGB), light (linear), shadow: its normal, color, light and sun shadow from the composite
// lit: its lit color (sRGB); mask: the material target's water channel (frag.glsl)
// Returns the color with the water over it, or lit unchanged; moves p to the water surface for the fog.
vec3 waterOver(ivec2 px, inout vec3 p, vec3 n, vec3 albedo, vec3 light, float shadow, vec3 lit, float mask,
  vec3 fogColor) {
  // land terrain is never under water: everything but the bed and objects standing in water stops here
  if (waterEnabled == 0 || mask < 0.25)
    return lit;
  bool bed = mask > 0.75;
  vec3 world = p + cameraPos;
  vec2 cBed = waterCell(world.xz);
  int t0 = waterTypeAt(cBed);
  if (t0 == 0 && !bed)
    return lit;

  // where the view ray meets the surface: the surface height above the pixel, then once more at the crossing so
  // water on a slope lines up
  float dist = length(p);
  vec3 v = p / dist;
  if (v.y < 1e-3)
    return lit;
  vec4 surf0 = waterPage0(cBed);
  if (cameraPos.y >= surf0.r)
    return lit;  // under the surface
  float t = (surf0.r - cameraPos.y) / v.y;
  vec2 c = cBed;
  // Follow the ray to where it crosses the surface: always the same two steps, so the crossing moves smoothly with
  // the view. Land corners carry the nearest water's level (WaterMap.spreadLevel), so the surface never climbs a bank
  // and level water settles on the first step.
  for (int i = 0; i < 2; ++i) {
    c = waterCell(cameraPos.xz + v.xz * t);
    surf0 = waterPage0(c);
    t = (surf0.r - cameraPos.y) / v.y;
  }
  if (t <= 0.0)
    return lit;
  float path = dist - t;
  // vertical depth of this pixel below the surface
  float depth = path * v.y;
  if (depth <= 0.0) {
    // above the water: the bed right at the waterline is wet
    return bed ? lit * mix(WET_SAND, 1.0, smoothstep(0.0, WET_HEIGHT, -depth)) : lit;
  }

  int type = waterTypeAt(c);
  if (type == 0)
    type = t0 != 0 ? t0 : 1;
  vec4 surf = WATER_SURFACE[type];
  vec3 s = v * t;  // camera-relative surface point
  vec3 sw = s + cameraPos;
  vec4 page1 = waterPage1(c);
  float energy = page1.g;
  float wind = waterWind.z;
  float viewZ = (viewProj * vec4(s, 1.0)).w;
  // world units one pixel covers on the surface, stretched where it is seen at a slant
  float pixelNear = length(reconstructPosition(vec2(px) + vec2(1.5, 0.5), 1.0) - reconstructPosition(vec2(px) + 0.5, 1.0));
  float footprint = pixelNear * viewZ / viewDepth(1.0) * inversesqrt(max(v.y, 0.05));
  float la = waterLod(footprint, WAVE_SCALE_A), lb = waterLod(footprint, WAVE_SCALE_B);

  // ---- surface normal: two wave octaves carried by the flow, the swell, rain rings
  vec2 flow = surf0.gb * (WATER_TILE * waterSpeed * surf.z);
  float flowSpeed = length(flow);
  flow *= min(1.0, FLOW_DRAG / max(flowSpeed * FLOW_CYCLE, 1e-3));
  // the wind's drift (integrated in Water.java, so a change in the wind changes the speed, never the position)
  vec2 base = waterDrift.xy * (surf.z * waterSpeed);
  vec3 waves = waterWaves2(sw.xz, base, la, lb);
  // blended in rather than switched, so there is no seam where the flow crosses the threshold
  float flowing = smoothstep(FLOW_MIN * 0.5, FLOW_MIN, flowSpeed);
  if (flowing > 0.0) {
    // Two copies dragged with the flow half a cycle apart, each fading out before it has been dragged too far,
    // so the waves move without ever stretching. The phase is offset across the water so the fades don't pulse
    // in step.
    float jitter = textureLod(waterNoise, sw.xz / FLOW_JITTER_SCALE, 7.0).b * FLOW_JITTER;
    float ph0 = fract(waterTime / FLOW_CYCLE + jitter);
    float ph1 = fract(ph0 + 0.5);
    float w0 = 1.0 - abs(1.0 - 2.0 * ph0);
    vec3 flowed = waterWaves2(sw.xz, base + flow * (ph0 * FLOW_CYCLE), la, lb) * w0
      + waterWaves2(sw.xz + vec2(173.0, 91.0), base + flow * (ph1 * FLOW_CYCLE), la, lb) * (1.0 - w0);
    // blending two copies averages their slopes down: give some back
    flowed.xy *= 1.0 + 0.4 * (1.0 - abs(2.0 * w0 - 1.0));
    waves = mix(waves, flowed, flowing);
  }
  float strength = WAVE_STRENGTH * surf.y * waterWaves * (0.7 + 0.5 * wind) * (0.75 + 0.35 * energy);
  vec2 slope = waves.xy * strength;
  // the swell: long soft undulations, so open water isn't flat
  vec2 swell = textureLod(waterNoise, (sw.xz - waterDrift.zw) / SWELL_SCALE, waterLod(footprint, SWELL_SCALE)).rg * 2.0 - 1.0;
  slope += swell * (SWELL_STRENGTH * surf.y * waterWaves * (0.3 + 0.7 * energy) * (0.6 + 0.8 * wind));
  if (waterRain > 0.01 && outdoors == 1)
    slope += waterRainRings(sw.xz) * surf.y;
  vec3 nrm = normalize(vec3(-slope.x, -1.0, -slope.y));
  vec3 toCamera = -v;

  // ---- light falling on the water
  bool lightOn = lightingEnabled == 1;
  vec3 caster = vec3(0.0);
  float surfShadow = 1.0;
  vec3 waterLight;
  if (outdoors == 1 && lightOn) {
    caster = (sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    if (shadowsEnabled == 1) {
      // one filtered tap: the surface is soft and moving anyway
      vec4 lc = lightProjRel * vec4(s, 1.0);
      vec2 uv = lc.xy * 0.5 + 0.5;
      float z = depthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5;
      float bias = 4.0 * (2.0 * shadowRadius / shadowMapSize) / (2.0 * shadowDepthRange);
      if (all(greaterThan(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0))) && z < 1.0)
        surfShadow = textureLod(shadowCompare, vec3(uv, z - bias), 0.0);
    }
    surfShadow = overcastShadow(surfShadow * cloudShadow(sw, casterDir));
    // the waves are lit like 117 HD's water surface: the light falling on each face is what scatters back out
    waterLight = (ambientLight(sunAltitude) + skyLight(sunAltitude) * max(-nrm.y, 0.0)) * envAmbient
      + caster * max(dot(nrm, casterDir), 0.0) * surfShadow;
  } else if (lightOn) {
    waterLight = envCaveLight(vec3(0.0, -1.0, 0.0));
  } else {
    waterLight = vec3(1.0);
  }

  // gloss from the wave detail per pixel: the finer octave's texels per pixel
  float texels = footprint * 256.0 / WAVE_SCALE_B;
  float gloss = surf.w / (1.0 + surf.w * GLOSS_AA * texels * texels);
  vec3 lampSpec = vec3(0.0);
  if (pointLightsEnabled == 1 && lightOn) {
    int base = clusterBase(px, viewZ);
    int count = clusterLightCount(base);
    // lights in buildings stay there (rooms.glsl)
    ivec2 room = count > 0 ? lightRoomAt(sw - vec3(0.0, ROOM_NORMAL_BIAS, 0.0)) : ivec2(-1, 0);
    for (int i = 0; i < count; ++i) {
      int li = int(texelFetch(clusterData, base + 1 + i).r);
      vec4 posRadius = texelFetch(lightData, li * 2);
      vec3 l = posRadius.xyz - s;
      float d2 = dot(l, l);
      float r2 = posRadius.w * posRadius.w;
      if (d2 >= r2)
        continue;
      vec4 colorCode = texelFetch(lightData, li * 2 + 1);
      vec3 color = colorCode.rgb * roomShare(colorCode.w, room);
      float att = 1.0 - sqrt(d2 / r2);
      att *= att;
      vec3 ld = l * inversesqrt(max(d2, 1.0));
      waterLight += color * att * max(-ld.y, 0.2) * 0.5;
      float g = gloss * 0.25;
      vec3 hl = ld + toCamera;
      lampSpec += color * att * pow(max(dot(nrm, hl * inversesqrt(max(dot(hl, hl), 1e-8))), 0.0), g) * (g + 8.0) / 25.0;
    }
  }

  // ---- what is seen through the surface: refracted, absorbed down to the bed and back up, the water's own color
  // filling in what was absorbed
  vec3 ext = WATER_EXTINCTION[type] / waterClarity;
  vec3 under;
  {
    vec3 seen = albedo;
    bool seenBed = bed;
    vec3 pr = p + vec3(slope.x, 0.0, slope.y) * (REFRACT * min(depth / REFRACT_DEPTH, 1.0));
    vec4 clip = viewProj * vec4(pr, 1.0);
    if (clip.w > NEAR) {
      ivec2 rp = ivec2((clip.xy / clip.w * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy));
      if (inViewport(rp)) {
        float dr = texelFetch(sceneDepth, rp, 0).r;
        float mr = texelFetch(sceneMaterial, rp, 0).b;
        // only take the offset pixel if it is under this water too, or things above it would show in it
        if (!isSky(dr) && mr >= 0.25 && reconstructPosition(vec2(rp) + 0.5, dr).y > s.y + 2.0) {
          seen = texelFetch(sceneColor, rp, 0).rgb;
          seenBed = mr > 0.75;
        }
      }
    }
    if (seenBed)
      seen = waterBedAlbedo(seen, depth, type, world.xz, footprint);
    vec3 bedLight = light;
    if (waterCaustics == 1 && outdoors == 1 && lightOn && surf.y > 0.5) {
      float fade = smoothstep(6.0, 40.0, depth) * exp(-depth / 300.0);
      bedLight += caster * (max(-n.y, 0.0) * shadow * CAUSTICS * fade
        * waterCausticPattern(world.xz + slope * 60.0, footprint * dist / t));
    }
    // sunlight reaching the bed went down through the water, what we see of it comes up along the view
    vec3 down = exp(-ext * (depth / WATER_TILE));
    vec3 up = exp(-ext * (path / WATER_TILE));
    under = srgbToLinear(seen) * bedLight * down * up + WATER_DEEP[type] * waterLight * (1.0 - up);
    under *= max(1.0 + WAVE_SHADE * dot(slope, v.xz) / max(length(v.xz), 0.25), 0.0);
  }

  // ---- reflection: the sky (or the gloom underground), and roughly the world
  float cosV = max(dot(nrm, toCamera), 0.0);
  float fresnel = (REFLECT_MIN + (1.0 - REFLECT_MIN) * pow(1.0 - cosV, 5.0)) * surf.x;
  // a calmer normal for what is reflected, so steep little waves don't reflect the ground below the horizon
  vec3 calm = normalize(vec3(nrm.x * SKY_TILT, nrm.y, nrm.z * SKY_TILT));
  vec3 r = reflect(v, calm);
  r.y = min(r.y, -0.02);
  r = normalize(r);
  vec3 reflection;
  if (outdoors == 1 && proceduralSky == 1) {
    float elev = -r.y;
    vec3 sc = mix(skyHorizon(sunAltitude), skyZenith(sunAltitude), pow(clamp(elev, 0.0, 1.0), 0.45));
    reflection = srgbToLinear(envSky(sc, fogColor, elev));
  } else {
    reflection = srgbToLinear(fogColor) * (outdoors == 1 ? 1.0 : 0.5);
  }
  if (waterReflections == 1) {
    vec3 approx = outdoors == 1
      ? (ambientLight(sunAltitude) + skyLight(sunAltitude) * 0.5) * envAmbient + caster * 0.5
      : envCaveLight(vec3(0.0, -1.0, 0.0));
    vec4 hit = waterTraceReflection(s, r, approx);
    reflection = mix(reflection, hit.rgb, hit.a);
  }
  vec3 color = mix(under, reflection, fresnel);

  // ---- glints: a tight highlight and a broad sheen, and sparkle where the finer waves crest
  if (outdoors == 1 && lightOn) {
    vec3 h = casterDir + toCamera;
    h *= inversesqrt(max(dot(h, h), 1e-8));
    float nh = max(dot(nrm, h), 0.0);
    float fh = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - clamp(dot(h, toCamera), 0.0, 1.0), 5.0);
    float spec = pow(nh, gloss) * (gloss + 8.0) / 25.0 + pow(nh, 40.0) * SHEEN;
    // the sparkle is per-pixel detail: gone before the finer waves get smaller than a pixel
    spec += smoothstep(0.72, 0.95, waves.z) * pow(nh, 60.0) * SPARKLE * strength / WAVE_STRENGTH
      * (1.0 - smoothstep(0.0, 1.5, lb));
    color += caster * (spec * fh * surfShadow * SPECULAR * surf.x * 4.0);
  }
  color += lampSpec * (LAMP_SPECULAR * surf.x);

  // ---- fade in from the waterline: the bed just under the surface shows through, darkened like wet ground
  vec3 result = mix(srgbToLinear(bed ? lit * WET_SAND : lit), color, smoothstep(0.0, SOFT_EDGE, depth));
  result = linearToSrgb(clamp(waterShoulder(result), 0.0, 1.0));

  if (waterDebug == 1) {
    // type color, flow as stripes that move with it
    const vec3 DEBUG_TYPE[WATER_TYPES] = vec3[](vec3(0.0), vec3(0.2, 0.6, 1.0), vec3(0.0, 0.2, 0.9),
      vec3(0.3, 0.3, 0.6), vec3(0.6, 0.9, 1.0), vec3(0.4, 0.6, 0.1), vec3(0.4, 0.3, 0.3));
    float speed = length(surf0.gb);
    vec2 dir = speed > 1e-3 ? surf0.gb / speed : vec2(0.0);
    float stripe = speed > 1e-3 ? step(0.5, fract(dot(sw.xz / 64.0, dir) - waterTime * speed * 2.0)) : 0.5;
    result = mix(DEBUG_TYPE[type], vec3(stripe), 0.35) * mix(0.6, 1.0, energy);
  }

  p = s;
  return result;
}
