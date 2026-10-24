// Volumetric clouds (Clouds.java): one slab of cloud between two altitudes over the whole world, drawn in the
// composite's sky branch only, so they cost nothing where scenery covers the sky. A short march through the slab
// samples a tiling 2D noise; the height inside the slab decides how much of the noise counts as cloud, which
// gives flat bases and rounded tops (2.5D clouds in the spirit of Horizon Zero Dawn's height gradients). Each
// cloud's dome is as tall as its noise is strong, so clouds are mounds of different heights rather than one
// outline stacked up to the top of the slab, and the detail pattern slides with height, so their sides are
// lumpy instead of extruded.
// The slab is seen from a fixed eye above the camera's focal point, not from the camera: the camera swinging
// around the player and rising as it pitches does not move the clouds. And the sky's horizon is put at the edge of
// the drawn world (cloudSkyDir), so the clouds come down to the land instead of leaving a band of empty haze below
// them that grows as the camera pitches down.
// A coarser detail noise pushes the shapes around a little (domain warp) and gently erodes the edges, so clouds
// aren't perfectly round blobs without turning grainy; whole regions of sky turn wispy. One light sample toward
// the sun or moon with Beer's law, a powder term, and a broad plus a narrow forward lobe: silver linings where the
// sun is behind thin edges, dark cores where it is behind thick ones. The weather field (WeatherField.java) sets
// the cover, wispiness and rain darkness by position, looked up where the view ray crosses the slab: rain clouds
// over a rainy region in the distance, clear sky over a clear one. The same coverage, looked up along the light,
// gives cheap cloud shadows on the ground, with the player's own weather (the composite has no texture unit left).
// Needs frame.glsl, sky.glsl and lightning.glsl.

uniform int cloudsEnabled;
uniform int cloudShadowsEnabled;
uniform float cloudBaseCoverage;  // the configured clear weather coverage
uniform float cloudWeather;    // Weather.cloudCover() at the player: rain clouds are denser, darker and grayer
uniform vec2 cloudOffset;      // world xz of the noise origin: scene base plus wind drift, wrapped
uniform float cloudDetail;     // roughness: 0 smooth and round, 1 normal, 2 very broken
uniform vec2 cloudType;        // the player's weather look (WeatherType): x how wispy and high 0..1, y darkness offset -1..1
// tiling: r cloud shape, equalized (1 - c covers a share c); g Worley puffs; b fine value noise; a coarse character
uniform sampler2D cloudNoise;
// The weather field's sky around the player (WeatherField.java): r cloud cover, g wispiness, b darkness offset
// (0..1 for -1..1), one bilinear texel per cell. cloudSkyRect: xy the world xz (scene coordinates) of its corner,
// zw one over its size; zero without a field, and the player's own weather is the sky everywhere.
uniform sampler2D weatherSkyTex;
uniform vec4 cloudSkyRect;
// xy: the camera's focal point minus the camera (xz), where the clouds are seen from; z: radius of the drawn world
// around the focal point; w: the camera's height above the focal point, 0 to keep the sky's horizon level
uniform vec4 cloudEye;
uniform float cloudGround;     // world y of the focal point: the cloud shadows hang at a fixed height above it

// slab altitudes in world units above the eye (cloudEye)
#define CLOUD_BOTTOM 3500.0
#define CLOUD_TOP 6000.0
// the eye's height above the focal point, for the cloud shadows only
#define CLOUD_EYE_HEIGHT 1000.0
// world units covered by one repeat of the noise; blobs are about a quarter of it. Clouds.TILE must match.
#define CLOUD_TILE 32768.0
#define CLOUD_NOISE_SIZE 128.0
// Steps of about CLOUD_STEP_LENGTH world units across the slab, at least CLOUD_MIN_STEPS and at most CLOUD_STEPS:
// few looking up, more toward the horizon, where a ray crosses far more of the slab and too few steps show as
// grain. The noise is also blurred to CLOUD_STEP_BLUR of a step, which the steps could not resolve anyway.
#define CLOUD_STEPS 16
#define CLOUD_MIN_STEPS 10
#define CLOUD_STEP_LENGTH 900.0
#define CLOUD_STEP_BLUR 0.5
// sine of the elevation (along cloudSkyDir) over which the clouds fade in above the horizon: clear sky at and below
// the first, which the cloud pass is scissored by (Clouds.HORIZON must match it)
#define CLOUD_HORIZON vec2(-0.02, 0.05)
// fakes the planet's curvature: clouds at the horizon are this far away (in slab heights over this) instead of
// infinitely far, which also caps how long a ray at the horizon spends in the slab
#define CLOUD_CURVE 0.12
#define CLOUD_FADE 120000.0
// A ray crossing the slab at a slant passes about 1 + CLOUD_SLANT * (distance across) / CLOUD_BLOB clouds' worth of
// sky, so a share c of the sky covered overhead piles up toward the horizon, where most of what OSRS's camera sees of
// the sky is. The cover along a ray is lowered to make up for it, so the share of the visible sky that is cloud
// matches the cover at every angle; overcast skies close up at the horizon as they should (above CLOUD_SLANT_KEEP).
#define CLOUD_BLOB 9000.0
#define CLOUD_SLANT 1.25
#define CLOUD_SLANT_KEEP vec2(0.55, 0.9)
// cover varies by up to +-CLOUD_VARY / 2 over a few noise repeats, the full amount from a cover of CLOUD_VARY_FULL
#define CLOUD_VARY 0.5
#define CLOUD_VARY_FULL 0.3
// noise value needed for cloud with no coverage, and when fully overcast (below 0 so the erosion leaves no gaps)
#define CLOUD_THRESHOLD_CLEAR 0.96
#define CLOUD_THRESHOLD_OVERCAST -0.3
// erosion takes cloud away; lowering the threshold by this much (at 100% roughness) keeps the coverage
#define CLOUD_EROSION_BIAS 0.08
// excess over the threshold from nothing to full density: soft edges; softer where steps are longer than
// CLOUD_SOFT_STEP (near the horizon), so the steps don't comb the edges into streaks
#define CLOUD_SOFT 0.12
#define CLOUD_SOFT_STEP 600.0
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
// Height profile: below CLOUD_BASE (height fraction) the threshold rises by up to CLOUD_BASE_RISE toward the
// bottom, a soft flat base; above it rises with the square of the height, scaled to the excess the noise can reach
// over the base threshold, so only the strongest cores reach the top and weaker ones make lower domes. The puff
// noise varies the domes by +-CLOUD_DOME_VARY; wispy clouds are flatter.
#define CLOUD_BASE 0.1
#define CLOUD_BASE_RISE 0.06
#define CLOUD_DOME 1.0
#define CLOUD_DOME_VARY 0.35
#define CLOUD_DOME_MIN_RANGE 0.1
#define CLOUD_WISP_DOME 1.5
// the detail pattern moves this far (in its repeats) from bottom to top: lumps at different heights
#define CLOUD_RISE_SHIFT vec2(0.07, 0.05)
// erosion near the base, of what it is above half the height: flat, even bases under lumpy tops
#define CLOUD_BASE_EROSION 0.4
// extinction per world unit at full density
#define CLOUD_DENSITY 0.0015
#define CLOUD_RAIN_DENSITY 2.0
// the light sample toward the sun or moon
#define CLOUD_LIGHT_STEP 1500.0
// Phase: a broad lobe (share CLOUD_BROAD over isotropic) lights the body; a narrow forward lobe, attenuated twice
// as hard (the square of the transmittance) so only thin edges catch it, makes silver linings around the sun
#define CLOUD_G_BROAD 0.3
#define CLOUD_BROAD 0.6
#define CLOUD_DIRECT 0.85
#define CLOUD_G_FORWARD 0.85
#define CLOUD_FORWARD 0.15
#define CLOUD_POWDER 0.6
// light on puffs and in crevices from the detail noise: soft shading inside the clouds
#define CLOUD_CREVICE 0.85
#define CLOUD_PUFF 1.12
// light left at the cloud base (the slab above shades it), rising to full at the top with height to the power
// CLOUD_BASE_SHADE_POWER, and deep inside thick cores; rain clouds have deeper cores, which keeps an overcast sky
// mottled instead of flat gray
#define CLOUD_BASE_SHADE 0.45
#define CLOUD_BASE_SHADE_POWER 0.7
#define CLOUD_CORE_SHADE 0.45
#define CLOUD_CORE 0.4
#define CLOUD_AMBIENT 0.9
// light left for rain clouds, and how much of their ambient color turns gray
#define CLOUD_RAIN_LIGHT 0.30
#define CLOUD_RAIN_GRAY 0.7
// sun near the horizon: light reaches the undersides and they glow in the sunset's colors
#define CLOUD_UNDERSIDE 0.35
#define CLOUD_UNDERSIDE_TINT vec3(1.0, 0.85, 0.75)
// sunlight taken away under a cloud; shadows fade as the sky closes over, where it is all overcast light
#define CLOUD_SHADOW_STRENGTH 0.55
#define CLOUD_SHADOW_ALTITUDE (CLOUD_EYE_HEIGHT + CLOUD_BOTTOM + (CLOUD_TOP - CLOUD_BOTTOM) * 0.3)

// The direction the sky and clouds are looked up in for a view ray: its elevation measured from the edge of the
// drawn world rather than from the level, so the horizon lies where the land ends. The edge is lower the higher the
// camera is, and nearer behind the camera than in front; the zenith stays where it is.
vec3 cloudSkyDir(vec3 dir) {
  float l = length(dir.xz);
  if (cloudEye.w <= 0.0 || cloudEye.z <= 0.0 || l < 1e-5) {
    return dir;
  }
  vec2 h = dir.xz / l;
  float oc = dot(h, cloudEye.xy);
  float far = max(oc + sqrt(max(cloudEye.z * cloudEye.z - dot(cloudEye.xy, cloudEye.xy) + oc * oc, 0.0)), 1.0);
  // sine of the angle the edge lies below the level
  float below = cloudEye.w * inversesqrt(cloudEye.w * cloudEye.w + far * far);
  float s = (-dir.y + below) / (1.0 + below);
  return vec3(h.x * sqrt(max(1.0 - s * s, 0.0)), -s, h.y * sqrt(max(1.0 - s * s, 0.0)));
}

// The weather of the sky over a world xz (scene coordinates): x cloud cover, y how wispy and high, z darkness offset
vec3 cloudSkyWeather(vec2 world) {
  if (cloudSkyRect.z == 0.0) {
    return vec3(cloudWeather, cloudType);
  }
  vec3 w = textureLod(weatherSkyTex, (world - cloudSkyRect.xy) * cloudSkyRect.zw, 0.0).rgb;
  return vec3(w.xy, w.z * 2.0 - 1.0);
}

// The sky under a stretch of the slab: x coverage, y how wispy, z how rainy (the weather's cover). xz: noise
// coordinates; sky: cloudSkyWeather there. The coverage varies over a few repeats so the noise doesn't read as
// tiled, and fair weather has regions of broken, wispy cloud.
vec3 cloudLocalCover(vec2 xz, vec3 sky) {
  float weather = sky.x;
  float coverage = cloudBaseCoverage + (1.0 - cloudBaseCoverage) * weather;
  vec4 macro = textureLod(cloudNoise, xz / (4.0 * CLOUD_TILE), 2.0);
  // thin skies vary less, so clear weather stays clear everywhere
  float vary = CLOUD_VARY * min(coverage / CLOUD_VARY_FULL, 1.0) * (1.0 - weather);
  float cov = clamp(coverage + (macro.r - 0.5) * vary, 0.0, 1.0);
  float wisp = smoothstep(CLOUD_WISP_LOW, CLOUD_WISP_HIGH, macro.a) * (1.0 - weather) * min(cloudDetail, 1.0);
  wisp = max(wisp, sky.y);
  // how dark: the cover, brightened or darkened by the weather's look (high thin cloud stays white)
  float dark = clamp(weather * (1.0 + sky.z), 0.0, 1.0);
  return vec3(cov, wisp, dark);
}

// The cover a ray crossing `across` world units of the slab uses, so that it shows a share c of cloud (CLOUD_SLANT)
float cloudSlantCover(float c, float across) {
  float thin = 1.0 - pow(max(1.0 - c, 0.0), 1.0 / (1.0 + CLOUD_SLANT * across / CLOUD_BLOB));
  return mix(thin, c, smoothstep(CLOUD_SLANT_KEEP.x, CLOUD_SLANT_KEEP.y, c));
}

// Noise value needed for cloud at height fraction hf: rises toward the top for domes, a little at the base.
// cw: cloudLocalCover; puff: 0..1, varies the domes
float cloudThreshold(float hf, vec3 cw, float puff) {
  float base = mix(CLOUD_THRESHOLD_CLEAR, CLOUD_THRESHOLD_OVERCAST, cw.x) - CLOUD_EROSION_BIAS * cloudDetail;
  float range = max(1.0 - base, CLOUD_DOME_MIN_RANGE);
  float u = clamp((hf - CLOUD_BASE) / (1.0 - CLOUD_BASE), 0.0, 1.0);
  float dome = u * u * range * CLOUD_DOME * mix(1.0 - CLOUD_DOME_VARY, 1.0 + CLOUD_DOME_VARY, puff)
    * mix(1.0, CLOUD_WISP_DOME, cw.y);
  return base + (hf < CLOUD_BASE ? (CLOUD_BASE - hf) / CLOUD_BASE * CLOUD_BASE_RISE : dome);
}

// The full sample: how far the noise is above the threshold for cloud (below 0 is clear sky, CLOUD_SOFT and up
// is full density, further up is deeper inside), with the warp it applied (world units) and the crevice shading.
float cloudSample(vec2 xz, float hf, vec3 cw, float lod, out vec2 warp, out float crevice) {
  vec2 p = xz / CLOUD_TILE;
  vec4 dn = textureLod(cloudNoise, p * CLOUD_DETAIL_SCALE + hf * CLOUD_RISE_SHIFT, lod + log2(CLOUD_DETAIL_SCALE));
  warp = (dn.rb - 0.5) * (CLOUD_WARP * min(cloudDetail, 1.5));
  p += warp / CLOUD_TILE;
  float e = textureLod(cloudNoise, p, lod).r - cloudThreshold(hf, cw, dn.g);
  crevice = 1.0;
  if (e > 0.0 && cloudDetail > 0.0) {
    // erosion only takes away, and mostly at the edges: the outlines break up a little, the cores stay smooth
    float erosion = mix(1.0 - dn.g, dn.b, mix(CLOUD_RAGGED, 1.0, cw.y));
    float edge = mix(CLOUD_INNER, 1.0, 1.0 - smoothstep(0.0, CLOUD_EDGE, e));
    e -= erosion * CLOUD_DETAIL * edge * cloudDetail * mix(CLOUD_BASE_EROSION, 1.0, smoothstep(0.0, 0.5, hf));
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
  float x = 1.0 + gg - 2.0 * g * mu;
  return (1.0 - gg) / (x * sqrt(x));
}

// soft highlight shoulder like sky.glsl's shade(), for the clouds' own light only
vec3 cloudShoulder(vec3 c) {
  return mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
}

// The clouds in front of the sky along dir (a cloudSkyDir). rgb: light scattered toward the camera (linear),
// a: transmittance. camPos: world position of the camera, which the clouds are seen from offset by cloudEye;
// sunDir, moonDir: the visual sun and moon; alt: sun altitude, degrees;
// pixelAngle: angle between neighbouring pixels' rays; noise: 0..1 per pixel, jitters the steps; steps: at most.
vec4 cloudLayer(vec3 dir, vec3 camPos, vec3 sunDir, vec3 moonDir, float alt, float pixelAngle, float noise,
  int steps) {
  float s = -dir.y;
  float horizon = smoothstep(CLOUD_HORIZON.x, CLOUD_HORIZON.y, s);
  if (horizon <= 0.0) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }
  float k = max(s, 0.0) + CLOUD_CURVE;
  float t0 = CLOUD_BOTTOM / k;
  float t1 = CLOUD_TOP / k;
  float fade = horizon * exp(-t0 / CLOUD_FADE);
  vec2 eye = camPos.xz + cloudEye.xy;
  vec2 origin = eye + cloudOffset;
  float across = (t1 - t0) * length(dir.xz);
  // the weather where the ray enters and leaves the slab, in between blended along it: tied to places in the sky
  // rather than to the ray, so clouds keep their size as the camera turns
  vec3 cw0 = cloudLocalCover(origin + dir.xz * t0, cloudSkyWeather(eye + dir.xz * t0));
  vec3 cw1 = cloudLocalCover(origin + dir.xz * t1, cloudSkyWeather(eye + dir.xz * t1));
  cw0.x = cloudSlantCover(cw0.x, across);
  cw1.x = cloudSlantCover(cw1.x, across);
  if (max(cw0.x, cw1.x) <= 0.0) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }

  int n = int(clamp(ceil(across / CLOUD_STEP_LENGTH), float(CLOUD_MIN_STEPS), float(steps)));
  // mip level from a pixel's footprint on the slab, stretched toward the horizon (geometric mean of both axes), or
  // from the step length; below 0 when a texel covers several pixels, so the detail fetches stay sharp
  float footprint = max((t0 + t1) * 0.5 * pixelAngle / sqrt(k), across / float(n) * CLOUD_STEP_BLUR);
  float lod = log2(max(footprint * CLOUD_NOISE_SIZE / CLOUD_TILE, 1e-3));

  // high thin clouds are thinner too
  float extinction = CLOUD_DENSITY * mix(1.0, CLOUD_WISP_DENSITY, cloudType.x);
  bool sunLit = alt > CLOUD_MOON_SWITCH;
  vec3 lightDir = sunLit ? sunDir : moonDir;
  // frame constants in the composite (sky.glsl)
  vec3 light = cloudLight(alt);
  vec3 zenith = skyZenithLinear(alt);
  vec3 horizonColor = skyHorizonLinear(alt);
  float mu = dot(dir, lightDir);
  float broad = mix(1.0, cloudPhase(mu, CLOUD_G_BROAD), CLOUD_BROAD) * CLOUD_DIRECT;
  float forward = cloudPhase(mu, CLOUD_G_FORWARD) * CLOUD_FORWARD;
  // powder darkens the thin edges of the side facing the light, which is the side seen looking away from it
  float powderAmount = CLOUD_POWDER * (0.5 - 0.5 * mu);
  float lightUp = max(-lightDir.y, 0.1);
  float low = sunLit ? smoothstep(10.0, 0.0, alt) * smoothstep(-8.0, -2.0, alt) : 0.0;

  float ds = (t1 - t0) / float(n);
  float soft = CLOUD_SOFT * max(1.0, across / float(n) / CLOUD_SOFT_STEP);
  vec3 scattered = vec3(0.0);
  float transmittance = 1.0;
  for (int i = 0; i < n; ++i) {
    float f = (float(i) + noise) / float(n);
    vec2 xz = origin + dir.xz * mix(t0, t1, f);
    vec3 cw = mix(cw0, cw1, f);
    vec2 warp;
    float crevice;
    float e = cloudSample(xz, f, cw, lod, warp, crevice);
    if (e <= 0.0) {
      continue;
    }
    float rain = cw.z;
    float d = min(e / (soft * (1.0 + cw.y)), 1.0) * mix(1.0, CLOUD_WISP_DENSITY, cw.y)
      * mix(1.0, CLOUD_RAIN_DENSITY, rain);
    float shade = mix(1.0, CLOUD_CORE_SHADE, smoothstep(0.0, CLOUD_CORE * (1.0 + rain), e));
    // toward the light from where the warped shape is; above the slab nothing is in the way
    vec2 lxz = xz + warp;
    float hfNear = f + CLOUD_LIGHT_STEP * lightUp / (CLOUD_TOP - CLOUD_BOTTOM);
    float dl = hfNear >= 1.0 ? 0.0 : cloudDensity(lxz + lightDir.xz * CLOUD_LIGHT_STEP, hfNear, cw, lod + 1.0);
    float od = (d * 0.5 + dl * mix(1.0, CLOUD_RAIN_DENSITY, rain)) * CLOUD_LIGHT_STEP * extinction;
    // the light's transmittance, and its square for the narrow lobe and the powder (twice the optical depth)
    float tl = exp(-od);
    float tl2 = tl * tl;
    float powder = mix(1.0, 1.0 - tl2, powderAmount);
    float base = mix(mix(CLOUD_BASE_SHADE, 1.0, pow(f, CLOUD_BASE_SHADE_POWER)), 1.0, low);
    float direct = (tl * broad + tl2 * forward) * powder * base * shade * crevice;
    vec3 lit = light * mix(1.0, CLOUD_RAIN_LIGHT, rain)
      * (direct + CLOUD_UNDERSIDE_TINT * (low * (1.0 - f) * CLOUD_UNDERSIDE * shade));

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
  vec4 c = cloudLayer(cloudSkyDir(dir), camPos, lightDir, -lightDir, alt, pixelAngle, noise, CLOUD_STEPS);
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
  float t = max(CLOUD_SHADOW_ALTITUDE - cloudGround + p.y, 0.0) / max(-lightDir.y, 0.15);
  vec2 world = p.xz + lightDir.xz * t;
  vec2 xz = world + cloudOffset;
  // the same warp as the clouds overhead, so the shadows have their shapes
  vec4 dn = textureLod(cloudNoise, xz * (CLOUD_DETAIL_SCALE / CLOUD_TILE), 1.5 + log2(CLOUD_DETAIL_SCALE));
  xz += (dn.rb - 0.5) * (CLOUD_WARP * min(cloudDetail, 1.5));
  vec3 cw = cloudLocalCover(xz, vec3(cloudWeather, cloudType));
  float d = cloudDensity(xz, 0.3, cw, 1.5);
  // thin high cloud casts faint shadows
  return 1.0 - d * CLOUD_SHADOW_STRENGTH * (1.0 - cw.z) * (1.0 - 0.6 * cloudType.x);
}
