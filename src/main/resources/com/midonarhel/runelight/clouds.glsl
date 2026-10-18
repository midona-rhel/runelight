// Volumetric clouds (Clouds.java): one slab of cloud between two altitudes over the whole world, drawn in the
// composite's sky branch only, so they cost nothing where scenery covers the sky. A short march through the slab
// samples a tiling 2D noise; the height inside the slab decides how much of the noise counts as cloud, which
// gives flat bases and rounded tops (2.5D clouds in the spirit of Horizon Zero Dawn's height gradients). One
// light sample per step with Beer's law, a powder term and a forward scattering lobe for silver linings.
// The same coverage, looked up along the light, gives cheap cloud shadows on the ground.
// Needs frame.glsl and sky.glsl.

uniform int cloudsEnabled;
uniform int cloudShadowsEnabled;
uniform float cloudCoverage;   // 0 clear .. 1 overcast, base coverage raised by the weather
uniform float cloudWeather;    // Weather.cloudCover(): rain clouds are denser, darker and grayer
uniform vec2 cloudOffset;      // world xz of the noise origin: scene base plus wind drift, wrapped
uniform sampler2D cloudNoise;  // tiling: r cloud shape, equalized (1 - c covers a share c), g puffy Worley detail

// slab altitudes in world units above y = 0 (y points down; hills reach a few thousand)
#define CLOUD_BOTTOM 9000.0
#define CLOUD_TOP 12000.0
// world units covered by one repeat of the noise; blobs are about a quarter of it. Clouds.TILE must match.
#define CLOUD_TILE 32768.0
// detail noise frequency, a whole number so the wrapped wind offset stays seamless
#define CLOUD_DETAIL_SCALE 3.0
#define CLOUD_DETAIL 0.14
#define CLOUD_NOISE_SIZE 128.0
#define CLOUD_STEPS 12
// fakes the planet's curvature: clouds at the horizon are this far away (in slab heights over this) instead of
// infinitely far, which also caps how long a ray at the horizon spends in the slab
#define CLOUD_CURVE 0.08
#define CLOUD_FADE 120000.0
// noise value needed for cloud with no coverage, and when fully overcast (below 0 so the erosion leaves no gaps)
#define CLOUD_THRESHOLD_CLEAR 1.0
#define CLOUD_THRESHOLD_OVERCAST -0.3
#define CLOUD_SOFT 0.16
// extinction per world unit at full density
#define CLOUD_DENSITY 0.0012
#define CLOUD_RAIN_DENSITY 2.0
#define CLOUD_LIGHT_STEP 1500.0
#define CLOUD_FORWARD 0.6
#define CLOUD_POWDER 0.6
#define CLOUD_SUN 1.0
#define CLOUD_MOON 0.6
// light left at the cloud base (the slab above shades it), and deep inside thick cores; rain clouds have deeper
// cores, which keeps an overcast sky mottled instead of flat gray
#define CLOUD_BASE_SHADE 0.45
#define CLOUD_CORE_SHADE 0.5
#define CLOUD_CORE 0.5
#define CLOUD_AMBIENT 0.9
// light left for rain clouds, and how much of their ambient color turns gray
#define CLOUD_RAIN_LIGHT 0.30
#define CLOUD_RAIN_GRAY 0.7
// sunlight taken away under a cloud; shadows fade as the sky closes over, where it is all overcast light
#define CLOUD_SHADOW_STRENGTH 0.55
#define CLOUD_SHADOW_ALTITUDE (CLOUD_BOTTOM + (CLOUD_TOP - CLOUD_BOTTOM) * 0.3)

// Coverage around a point: the configured coverage, varied over a few repeats so the noise doesn't read as tiled
float cloudLocalCoverage(vec2 xz) {
  float macro = textureLod(cloudNoise, xz / (4.0 * CLOUD_TILE), 2.0).r;
  return clamp(cloudCoverage + (macro - 0.5) * 0.5 * (1.0 - cloudWeather), 0.0, 1.0);
}

// Noise value needed for cloud at height fraction hf: rises toward the top for domes, a little at the base
float cloudThreshold(float hf, float cov) {
  float base = mix(CLOUD_THRESHOLD_CLEAR, CLOUD_THRESHOLD_OVERCAST, cov);
  float shape = hf < 0.15 ? (0.15 - hf) * 0.6 : (hf - 0.15) * (hf - 0.15) * 0.45;
  return base + shape;
}

// How far the noise is above the threshold for cloud: below 0 is clear sky, CLOUD_SOFT and up is full density,
// and further up is deeper inside a cloud
float cloudExcess(vec2 xz, float hf, float cov, float lod, bool detail) {
  float e = textureLod(cloudNoise, xz / CLOUD_TILE, lod).r - cloudThreshold(hf, cov);
  // erosion only takes away, so empty space needs no second fetch
  if (detail && e > 0.0) {
    // wispy, eroded edges
    e -= (1.0 - textureLod(cloudNoise, xz * (CLOUD_DETAIL_SCALE / CLOUD_TILE), lod + log2(CLOUD_DETAIL_SCALE)).g) * CLOUD_DETAIL;
  }
  return e;
}

float cloudDensity(vec2 xz, float hf, float cov, float lod, bool detail) {
  return clamp(cloudExcess(xz, hf, cov, lod, detail) / CLOUD_SOFT, 0.0, 1.0);
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

// The clouds in front of the sky along dir. rgb: light scattered toward the camera (linear), a: transmittance.
// camPos: world position of the camera; lightDir: toward the sun or moon; alt: sun altitude, degrees;
// pixelAngle: angle between neighbouring pixels' rays; noise: 0..1 per pixel, jitters the steps.
vec4 cloudLayer(vec3 dir, vec3 camPos, vec3 lightDir, float alt, float pixelAngle, float noise) {
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
  float cov = cloudLocalCoverage(origin + dir.xz * (t0 + t1) * 0.5);
  if (cov <= 0.0) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }

  // mip level from a pixel's footprint on the slab, stretched toward the horizon (geometric mean of both axes)
  float footprint = (t0 + t1) * 0.5 * pixelAngle / sqrt(k);
  float lod = log2(max(footprint * CLOUD_NOISE_SIZE / CLOUD_TILE, 1.0));

  float rain = cloudWeather;
  float extinction = CLOUD_DENSITY * mix(1.0, CLOUD_RAIN_DENSITY, rain);
  vec3 light = (sunLight(alt) * CLOUD_SUN + moonLight(alt) * CLOUD_MOON) * mix(1.0, CLOUD_RAIN_LIGHT, rain);
  float core = CLOUD_CORE * (1.0 + rain);
  vec3 zenith = srgbToLinear(skyZenith(alt));
  vec3 horizonColor = srgbToLinear(skyHorizon(alt));
  float mu = dot(dir, lightDir);
  float phase = mix(1.0, cloudPhase(mu, CLOUD_FORWARD), 0.5);
  // powder darkens the thin edges of the side facing the light, which is the side seen looking away from it
  float powderAmount = CLOUD_POWDER * (0.5 - 0.5 * mu);
  float lightUp = max(-lightDir.y, 0.1);

  float ds = (t1 - t0) / float(CLOUD_STEPS);
  vec3 scattered = vec3(0.0);
  float transmittance = 1.0;
  for (int i = 0; i < CLOUD_STEPS; ++i) {
    float f = (float(i) + noise) / float(CLOUD_STEPS);
    vec2 xz = origin + dir.xz * mix(t0, t1, f);
    float e = cloudExcess(xz, f, cov, lod, true);
    if (e <= 0.0) {
      continue;
    }
    float d = min(e / CLOUD_SOFT, 1.0);
    float shade = mix(1.0, CLOUD_CORE_SHADE, smoothstep(0.0, core, e));
    // one step toward the light; above the slab nothing is in the way
    float hfLight = f + CLOUD_LIGHT_STEP * lightUp / (CLOUD_TOP - CLOUD_BOTTOM);
    float dl = hfLight >= 1.0 ? 0.0 : cloudDensity(xz + lightDir.xz * CLOUD_LIGHT_STEP, hfLight, cov, lod + 1.0, false);
    float od = (d * 0.5 + dl) * extinction * CLOUD_LIGHT_STEP;
    float direct = exp(-od) * mix(1.0, 1.0 - exp(-2.0 * od), powderAmount) * phase * mix(CLOUD_BASE_SHADE, 1.0, f) * shade;

    // sky light: the zenith's color on top, the horizon's underneath, gray and dim in rain
    vec3 ambient = mix(horizonColor, zenith, f) * mix(0.55, 1.0, f) * mix(1.0, shade, 0.6) * CLOUD_AMBIENT;
    ambient = mix(ambient, vec3(dot(ambient, vec3(0.2126, 0.7152, 0.0722))), rain * CLOUD_RAIN_GRAY);

    float stepT = exp(-d * extinction * ds);
    scattered += transmittance * (light * direct + ambient) * (1.0 - stepT);
    transmittance *= stepT;
    if (transmittance < 0.02) {
      break;
    }
  }
  return vec4(scattered * fade, mix(1.0, transmittance, fade));
}

// The procedural sky (sRGB) with the clouds in front of it. px: pixel center in target coordinates.
vec3 cloudsOverSky(vec3 sky, vec2 px, vec3 dir, vec3 camPos, vec3 lightDir, float alt, float noise) {
  if (cloudsEnabled == 0) {
    return sky;
  }
  float pixelAngle = length(normalize(reconstructPosition(px + vec2(0.0, 1.0), 1.0)) - dir);
  vec4 c = cloudLayer(dir, camPos, lightDir, alt, pixelAngle, noise);
  if (c.a >= 1.0) {
    return sky;
  }
  vec3 lin = srgbToLinear(max(sky, 0.0)) * c.a + cloudShoulder(c.rgb);
  return linearToSrgb(clamp(lin, 0.0, 1.0));
}

// How much sunlight reaches world position p through the clouds, 1 without clouds or their shadows
float cloudShadow(vec3 p, vec3 lightDir) {
  if (cloudsEnabled == 0 || cloudShadowsEnabled == 0 || cloudWeather >= 1.0) {
    return 1.0;
  }
  float t = max(CLOUD_SHADOW_ALTITUDE + p.y, 0.0) / max(-lightDir.y, 0.15);
  vec2 xz = p.xz + lightDir.xz * t + cloudOffset;
  float d = cloudDensity(xz, 0.3, cloudLocalCoverage(xz), 1.5, false);
  return 1.0 - d * CLOUD_SHADOW_STRENGTH * (1.0 - cloudWeather);
}
