// Volumetric clouds (Clouds.java): one slab of cloud between two altitudes over the whole world, drawn in the
// composite's sky branch only, so they cost nothing where scenery covers the sky. A short march through the slab
// samples a tiling 2D noise; the height inside the slab decides how much of the noise counts as cloud, which
// gives flat bases and rounded tops (2.5D clouds in the spirit of Horizon Zero Dawn's height gradients).
// A coarser detail noise pushes the shapes around a little (domain warp) and gently erodes the edges, so clouds
// aren't perfectly round blobs without turning grainy; whole regions of sky turn wispy. One light sample toward
// the sun or moon with Beer's law, a powder term, and a broad plus a narrow forward lobe: silver linings where the
// sun is behind thin edges, dark cores where it is behind thick ones. The weather field (WeatherField.java) sets
// the cover and the rain darkness by position: rain clouds over a rainy region in the distance, clear sky over a
// clear one. The same coverage, looked up along the light, gives cheap cloud shadows on the ground.
// Needs frame.glsl, sky.glsl and lightning.glsl.

uniform int cloudsEnabled;
uniform int cloudShadowsEnabled;
uniform float cloudCoverage;   // 0 clear .. 1 overcast at the player: base coverage raised by the weather
uniform float cloudBaseCoverage;  // the configured clear weather coverage
uniform float cloudWeather;    // Weather.cloudCover() at the player: rain clouds are denser, darker and grayer
uniform vec2 cloudOffset;      // world xz of the noise origin: scene base plus wind drift, wrapped
uniform float cloudDetail;     // roughness: 0 smooth and round, 1 normal, 2 very broken
// tiling: r cloud shape, equalized (1 - c covers a share c); g Worley puffs; b fine value noise; a coarse character
uniform sampler2D cloudNoise;
// The weather field's cloud cover over the scene, as differences from the player's (WeatherField.java): a grid of
// up to CLOUD_FIELD_SIZE^2 cells, x fastest, packed four to a vec4. The composite has no texture unit left.
#define CLOUD_FIELD_SIZE 24
uniform vec4 cloudField[CLOUD_FIELD_SIZE * CLOUD_FIELD_SIZE / 4];
uniform vec4 cloudFieldRect;   // xy: world xz of the grid's corner (scene coordinates), zw: cells per world unit, 0 off
uniform vec2 cloudFieldCells;  // cells used across x and z

// slab altitudes in world units above y = 0 (y points down; hills reach a few thousand)
#define CLOUD_BOTTOM 9000.0
#define CLOUD_TOP 11000.0
// world units covered by one repeat of the noise; blobs are about a quarter of it. Clouds.TILE must match.
#define CLOUD_TILE 32768.0
#define CLOUD_NOISE_SIZE 128.0
// Steps of about CLOUD_STEP_LENGTH world units across the slab, at least CLOUD_MIN_STEPS and at most CLOUD_STEPS:
// few looking up, more toward the horizon, where a ray crosses far more of the slab and too few steps show as
// grain. The noise is also blurred to CLOUD_STEP_BLUR of a step, which the steps could not resolve anyway.
#define CLOUD_STEPS 16
#define CLOUD_MIN_STEPS 6
#define CLOUD_STEP_LENGTH 900.0
#define CLOUD_STEP_BLUR 0.5
// fakes the planet's curvature: clouds at the horizon are this far away (in slab heights over this) instead of
// infinitely far, which also caps how long a ray at the horizon spends in the slab
#define CLOUD_CURVE 0.08
#define CLOUD_FADE 120000.0
// noise value needed for cloud with no coverage, and when fully overcast (below 0 so the erosion leaves no gaps)
#define CLOUD_THRESHOLD_CLEAR 1.0
#define CLOUD_THRESHOLD_OVERCAST -0.3
// erosion takes cloud away; lowering the threshold by this much (at 100% roughness) keeps the coverage
#define CLOUD_EROSION_BIAS 0.08
// excess over the threshold from nothing to full density: soft edges
#define CLOUD_SOFT 0.12
// detail noise frequency, a whole number so the wrapped wind offset stays seamless
#define CLOUD_DETAIL_SCALE 3.0
// how far the detail noise pushes the shapes around, world units: outlines a little less round
#define CLOUD_WARP 1800.0
// erosion by the detail noise at 100% roughness
#define CLOUD_DETAIL 0.18
// erosion is full at the edge and fades to CLOUD_INNER of it by an excess of CLOUD_EDGE: cores stay smooth
#define CLOUD_EDGE 0.35
#define CLOUD_INNER 0.25
// share of ragged value noise in the erosion of heaped clouds (the rest is billowy Worley); wispy ones take it alone
#define CLOUD_RAGGED 0.3
// coarse noise band over which a region of sky turns wispy: thinner, flatter-topped, softer and more ragged
#define CLOUD_WISP_LOW 0.55
#define CLOUD_WISP_HIGH 0.9
#define CLOUD_WISP_DENSITY 0.55
// threshold rise toward the top for domes, varied by the puff noise; flatter for wispy clouds
#define CLOUD_DOME 0.45
#define CLOUD_WISP_DOME 2.2
// extinction per world unit at full density
#define CLOUD_DENSITY 0.0015
#define CLOUD_RAIN_DENSITY 2.0
// the light sample toward the sun or moon
#define CLOUD_LIGHT_STEP 1500.0
// Phase: a broad lobe (share CLOUD_BROAD over isotropic) lights the body; a narrow forward lobe, attenuated
// CLOUD_FORWARD_EXTINCTION times harder so only thin edges catch it, makes silver linings around the sun
#define CLOUD_G_BROAD 0.3
#define CLOUD_BROAD 0.6
#define CLOUD_DIRECT 0.85
#define CLOUD_G_FORWARD 0.85
#define CLOUD_FORWARD 0.15
#define CLOUD_FORWARD_EXTINCTION 2.0
#define CLOUD_POWDER 0.6
#define CLOUD_SUN 1.0
#define CLOUD_MOON 1.3
// light on puffs and in crevices from the detail noise: soft shading inside the clouds
#define CLOUD_CREVICE 0.85
#define CLOUD_PUFF 1.12
// light left at the cloud base (the slab above shades it), and deep inside thick cores; rain clouds have deeper
// cores, which keeps an overcast sky mottled instead of flat gray
#define CLOUD_BASE_SHADE 0.45
#define CLOUD_CORE_SHADE 0.45
#define CLOUD_CORE 0.4
#define CLOUD_AMBIENT 0.9
// light left for rain clouds, and how much of their ambient color turns gray
#define CLOUD_RAIN_LIGHT 0.30
#define CLOUD_RAIN_GRAY 0.7
// sun near the horizon: light reaches the undersides and they glow in the sunset's colors
#define CLOUD_UNDERSIDE 0.35
#define CLOUD_UNDERSIDE_TINT vec3(1.0, 0.85, 0.75)
// below this sun altitude the moon lights the clouds; neither light reaches them there
#define CLOUD_MOON_SWITCH -8.0
// sunlight taken away under a cloud; shadows fade as the sky closes over, where it is all overcast light
#define CLOUD_SHADOW_STRENGTH 0.55
#define CLOUD_SHADOW_ALTITUDE (CLOUD_BOTTOM + (CLOUD_TOP - CLOUD_BOTTOM) * 0.3)

float cloudFieldCell(int i) {
  return cloudField[i >> 2][i & 3];
}

// How much cloudier the weather field says it is at a world xz (scene coordinates) than at the player, bilinear
float cloudFieldDelta(vec2 xz) {
  if (cloudFieldRect.z == 0.0) {
    return 0.0;
  }
  vec2 c = clamp((xz - cloudFieldRect.xy) * cloudFieldRect.zw - 0.5, vec2(0.0), cloudFieldCells - 1.0);
  ivec2 i0 = ivec2(c);
  ivec2 i1 = min(i0 + 1, ivec2(cloudFieldCells) - 1);
  vec2 f = c - vec2(i0);
  float a = mix(cloudFieldCell(i0.y * CLOUD_FIELD_SIZE + i0.x), cloudFieldCell(i0.y * CLOUD_FIELD_SIZE + i1.x), f.x);
  float b = mix(cloudFieldCell(i1.y * CLOUD_FIELD_SIZE + i0.x), cloudFieldCell(i1.y * CLOUD_FIELD_SIZE + i1.x), f.x);
  return mix(a, b, f.y);
}

// The weather under a stretch of sky: x coverage, y how wispy, z how rainy (cloudWeather there). xz: noise
// coordinates; world: the same place in scene coordinates, for the weather field. The coverage varies over a few
// repeats so the noise doesn't read as tiled, and fair weather has regions of broken, wispy cloud.
vec3 cloudLocalCover(vec2 xz, vec2 world) {
  float weather = clamp(cloudWeather + cloudFieldDelta(world), 0.0, 1.0);
  float coverage = cloudCoverage + (1.0 - cloudBaseCoverage) * (weather - cloudWeather);
  vec4 macro = textureLod(cloudNoise, xz / (4.0 * CLOUD_TILE), 2.0);
  float cov = clamp(coverage + (macro.r - 0.5) * 0.5 * (1.0 - weather), 0.0, 1.0);
  float wisp = smoothstep(CLOUD_WISP_LOW, CLOUD_WISP_HIGH, macro.a) * (1.0 - weather) * min(cloudDetail, 1.0);
  return vec3(cov, wisp, weather);
}

// Noise value needed for cloud at height fraction hf: rises toward the top for domes, a little at the base.
// cw: cloudLocalCover; puff: 0..1, varies the domes
float cloudThreshold(float hf, vec3 cw, float puff) {
  float base = mix(CLOUD_THRESHOLD_CLEAR, CLOUD_THRESHOLD_OVERCAST, cw.x) - CLOUD_EROSION_BIAS * cloudDetail;
  float dome = (hf - 0.15) * (hf - 0.15) * CLOUD_DOME * (0.5 + puff) * mix(1.0, CLOUD_WISP_DOME, cw.y);
  return base + (hf < 0.15 ? (0.15 - hf) * 0.6 : dome);
}

// The full sample: how far the noise is above the threshold for cloud (below 0 is clear sky, CLOUD_SOFT and up
// is full density, further up is deeper inside), with the warp it applied (world units) and the crevice shading.
float cloudSample(vec2 xz, float hf, vec3 cw, float lod, out vec2 warp, out float crevice) {
  vec2 p = xz / CLOUD_TILE;
  vec4 dn = textureLod(cloudNoise, p * CLOUD_DETAIL_SCALE, lod + log2(CLOUD_DETAIL_SCALE));
  warp = (dn.rb - 0.5) * (CLOUD_WARP * min(cloudDetail, 1.5));
  p += warp / CLOUD_TILE;
  float e = textureLod(cloudNoise, p, lod).r - cloudThreshold(hf, cw, dn.g);
  crevice = 1.0;
  if (e > 0.0 && cloudDetail > 0.0) {
    // erosion only takes away, and mostly at the edges: the outlines break up a little, the cores stay smooth
    float erosion = mix(1.0 - dn.g, dn.b, mix(CLOUD_RAGGED, 1.0, cw.y));
    float edge = mix(CLOUD_INNER, 1.0, 1.0 - smoothstep(0.0, CLOUD_EDGE, e));
    e -= erosion * CLOUD_DETAIL * edge * cloudDetail;
    crevice = mix(1.0, mix(CLOUD_CREVICE, CLOUD_PUFF, dn.g), min(cloudDetail, 1.0));
  }
  return e;
}

// Density without detail, for light samples and shadows (xz already warped)
float cloudDensity(vec2 xz, float hf, vec3 cw, float lod) {
  float e = textureLod(cloudNoise, xz / CLOUD_TILE, lod).r - cloudThreshold(hf, cw, 0.5);
  return clamp(e / (CLOUD_SOFT * 2.0), 0.0, 1.0);
}

// Henyey-Greenstein times 4 pi, so isotropic is 1
float cloudPhase(float mu, float g) {
  float gg = g * g;
  return (1.0 - gg) / pow(1.0 + gg - 2.0 * g * mu, 1.5);
}

// soft highlight shoulder like sky.glsl's shade(), for the clouds' own light only
vec3 cloudShoulder(vec3 c) {
  return mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
}

// Sunlight on the clouds. They are high, so they catch it a little before sunrise and after sunset, red then.
vec3 cloudSunLight(float alt) {
  vec3 warm = mix(vec3(1.00, 0.42, 0.16), vec3(0.95, 0.30, 0.32), smoothstep(0.0, -6.0, alt));
  vec3 noon = vec3(1.00, 0.90, 0.76);
  return overcastDirect(mix(warm, noon, smoothstep(2.0, 28.0, alt)) * SUN_INTENSITY
    * smoothstep(CLOUD_MOON_SWITCH, 4.0, alt));
}

// The clouds in front of the sky along dir. rgb: light scattered toward the camera (linear), a: transmittance.
// camPos: world position of the camera; sunDir, moonDir: the visual sun and moon; alt: sun altitude, degrees;
// pixelAngle: angle between neighbouring pixels' rays; noise: 0..1 per pixel, jitters the steps; steps: at most.
vec4 cloudLayer(vec3 dir, vec3 camPos, vec3 sunDir, vec3 moonDir, float alt, float pixelAngle, float noise,
  int steps) {
  float s = -dir.y;
  float horizon = smoothstep(-0.02, 0.05, s);
  if (horizon <= 0.0) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }
  float k = max(s, 0.0) + CLOUD_CURVE;
  float camAlt = -camPos.y;
  float t0 = max(CLOUD_BOTTOM - camAlt, 0.0) / k;
  float t1 = max(CLOUD_TOP - camAlt, 1.0) / k;
  float fade = horizon * exp(-t0 / CLOUD_FADE);
  vec2 origin = camPos.xz + cloudOffset;
  vec2 mid = dir.xz * (t0 + t1) * 0.5;
  vec3 cw = cloudLocalCover(origin + mid, camPos.xz + mid);
  if (cw.x <= 0.0) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }

  float across = (t1 - t0) * length(dir.xz);
  int n = int(clamp(ceil(across / CLOUD_STEP_LENGTH), float(CLOUD_MIN_STEPS), float(steps)));
  // mip level from a pixel's footprint on the slab, stretched toward the horizon (geometric mean of both axes), or
  // from the step length; below 0 when a texel covers several pixels, so the detail fetches stay sharp
  float footprint = max((t0 + t1) * 0.5 * pixelAngle / sqrt(k), across / float(n) * CLOUD_STEP_BLUR);
  float lod = log2(max(footprint * CLOUD_NOISE_SIZE / CLOUD_TILE, 1e-3));

  float rain = cw.z;
  float extinction = CLOUD_DENSITY * mix(1.0, CLOUD_RAIN_DENSITY, rain);
  bool sunLit = alt > CLOUD_MOON_SWITCH;
  vec3 lightDir = sunLit ? sunDir : moonDir;
  vec3 light = (sunLit ? cloudSunLight(alt) * CLOUD_SUN
    : moonLight(alt) * (CLOUD_MOON * smoothstep(CLOUD_MOON_SWITCH, CLOUD_MOON_SWITCH - 3.0, alt)))
    * mix(1.0, CLOUD_RAIN_LIGHT, rain);
  float core = CLOUD_CORE * (1.0 + rain);
  vec3 zenith = srgbToLinear(skyZenith(alt));
  vec3 horizonColor = srgbToLinear(skyHorizon(alt));
  float mu = dot(dir, lightDir);
  float broad = mix(1.0, cloudPhase(mu, CLOUD_G_BROAD), CLOUD_BROAD) * CLOUD_DIRECT;
  float forward = cloudPhase(mu, CLOUD_G_FORWARD) * CLOUD_FORWARD;
  // powder darkens the thin edges of the side facing the light, which is the side seen looking away from it
  float powderAmount = CLOUD_POWDER * (0.5 - 0.5 * mu);
  float lightUp = max(-lightDir.y, 0.1);
  float low = sunLit ? smoothstep(10.0, 0.0, alt) * smoothstep(-8.0, -2.0, alt) : 0.0;
  float wispDensity = mix(1.0, CLOUD_WISP_DENSITY, cw.y);
  float soft = CLOUD_SOFT * (1.0 + cw.y);

  float ds = (t1 - t0) / float(n);
  vec3 scattered = vec3(0.0);
  float transmittance = 1.0;
  for (int i = 0; i < n; ++i) {
    float f = (float(i) + noise) / float(n);
    vec2 xz = origin + dir.xz * mix(t0, t1, f);
    vec2 warp;
    float crevice;
    float e = cloudSample(xz, f, cw, lod, warp, crevice);
    if (e <= 0.0) {
      continue;
    }
    float d = min(e / soft, 1.0) * wispDensity;
    float shade = mix(1.0, CLOUD_CORE_SHADE, smoothstep(0.0, core, e));
    // toward the light from where the warped shape is; above the slab nothing is in the way
    vec2 lxz = xz + warp;
    float hfNear = f + CLOUD_LIGHT_STEP * lightUp / (CLOUD_TOP - CLOUD_BOTTOM);
    float dl = hfNear >= 1.0 ? 0.0 : cloudDensity(lxz + lightDir.xz * CLOUD_LIGHT_STEP, hfNear, cw, lod + 1.0);
    float od = (d * 0.5 + dl) * CLOUD_LIGHT_STEP * extinction;
    float powder = mix(1.0, 1.0 - exp(-2.0 * od), powderAmount);
    float base = mix(mix(CLOUD_BASE_SHADE, 1.0, f), 1.0, low);
    float direct = (exp(-od) * broad + exp(-CLOUD_FORWARD_EXTINCTION * od) * forward) * powder * base * shade * crevice;
    vec3 lit = light * direct
      + light * CLOUD_UNDERSIDE_TINT * (low * (1.0 - f) * CLOUD_UNDERSIDE * shade);

    // sky light: the zenith's color on top, the horizon's underneath, gray and dim in rain
    vec3 ambient = mix(horizonColor, zenith, f) * mix(0.55, 1.0, f) * mix(1.0, shade, 0.6) * CLOUD_AMBIENT
      * mix(1.0, crevice, 0.5);
    ambient = mix(ambient, vec3(dot(ambient, vec3(0.2126, 0.7152, 0.0722))), rain * CLOUD_RAIN_GRAY);
    ambient += lightningCloudGlow(xz - origin);

    float stepT = exp(-d * extinction * ds);
    scattered += transmittance * (lit + ambient) * (1.0 - stepT);
    transmittance *= stepT;
    if (transmittance < 0.02) {
      break;
    }
  }
  return vec4(scattered * fade, mix(1.0, transmittance, fade));
}

// The procedural sky (sRGB) with the clouds in front of it. px: pixel center in target coordinates; lightDir: the
// visual sun or moon. atmosphere.glsl's atmosphereSky does more (sun and moon through the clouds); this stays for
// anything that only needs clouds over a sky color.
vec3 cloudsOverSky(vec3 sky, vec2 px, vec3 dir, vec3 camPos, vec3 lightDir, float alt, float noise) {
  if (cloudsEnabled == 0) {
    return sky;
  }
  float pixelAngle = length(normalize(reconstructPosition(px + vec2(0.0, 1.0), 1.0)) - dir);
  vec4 c = cloudLayer(dir, camPos, lightDir, -lightDir, alt, pixelAngle, noise, CLOUD_STEPS);
  if (c.a >= 1.0) {
    return sky;
  }
  vec3 lin = srgbToLinear(max(sky, 0.0)) * c.a + cloudShoulder(c.rgb);
  return linearToSrgb(clamp(lin, 0.0, 1.0));
}

// How much sunlight reaches world position p through the clouds, 1 without clouds or their shadows
float cloudShadow(vec3 p, vec3 lightDir) {
  if (cloudsEnabled == 0 || cloudShadowsEnabled == 0 || cloudWeather >= 1.0 && cloudFieldRect.z == 0.0) {
    return 1.0;
  }
  float t = max(CLOUD_SHADOW_ALTITUDE + p.y, 0.0) / max(-lightDir.y, 0.15);
  vec2 world = p.xz + lightDir.xz * t;
  vec2 xz = world + cloudOffset;
  // the same warp as the clouds overhead, so the shadows have their shapes
  vec4 dn = textureLod(cloudNoise, xz * (CLOUD_DETAIL_SCALE / CLOUD_TILE), 1.5 + log2(CLOUD_DETAIL_SCALE));
  xz += (dn.rb - 0.5) * (CLOUD_WARP * min(cloudDetail, 1.5));
  vec3 cw = cloudLocalCover(xz, world);
  float d = cloudDensity(xz, 0.3, cw, 1.5);
  return 1.0 - d * CLOUD_SHADOW_STRENGTH * (1.0 - cw.z);
}
