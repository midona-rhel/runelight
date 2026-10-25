// Sky and light colors, all driven by the visual sun altitude in degrees (below 0 is night).
// Colors are in the game's sRGB-ish space. Keyframes are easy to tune live: edit and save.

// Stylized palette: sunrise and sunset go red-orange at the horizon with purple above it, nights are cool blue.
#define ZENITH_KEYS 8
const float ZENITH_ALT[ZENITH_KEYS] = float[](-30.0, -12.0, -6.0, -2.0, 0.0, 6.0, 20.0, 90.0);
const vec3 ZENITH_COLOR[ZENITH_KEYS] = vec3[](
  vec3(0.020, 0.024, 0.102),
  vec3(0.071, 0.102, 0.247),
  vec3(0.169, 0.137, 0.314),
  vec3(0.290, 0.180, 0.361),
  vec3(0.369, 0.290, 0.478),
  vec3(0.410, 0.550, 0.790),
  vec3(0.300, 0.550, 0.870),
  vec3(0.220, 0.490, 0.860));

#define HORIZON_KEYS 9
const float HORIZON_ALT[HORIZON_KEYS] = float[](-30.0, -12.0, -6.0, -2.0, 0.0, 4.0, 12.0, 25.0, 90.0);
const vec3 HORIZON_COLOR[HORIZON_KEYS] = vec3[](
  vec3(0.043, 0.039, 0.102),
  vec3(0.102, 0.082, 0.188),
  vec3(0.247, 0.129, 0.251),
  vec3(0.627, 0.267, 0.180),
  vec3(0.890, 0.443, 0.227),
  vec3(0.949, 0.631, 0.369),
  vec3(0.851, 0.722, 0.604),
  vec3(0.663, 0.737, 0.827),
  vec3(0.576, 0.682, 0.796));

// Nights are this much brighter than the palette below (sRGB, the sky itself), fading in as the sun sinks from 2 to
// 14 degrees under the horizon like the moon does, so dusk and dawn stay smooth. The lights get theirs in linear.
#define NIGHT_SKY_BRIGHTNESS 1.5
#define NIGHT_LIGHT_BRIGHTNESS 1.5

float nightGain(float alt, float gain) {
  return mix(1.0, gain, smoothstep(-2.0, -14.0, alt));
}

vec3 skyZenithPalette(float alt) {
  if (alt <= ZENITH_ALT[0])
    return ZENITH_COLOR[0];
  for (int i = 1; i < ZENITH_KEYS; ++i) {
    if (alt <= ZENITH_ALT[i])
      return mix(ZENITH_COLOR[i - 1], ZENITH_COLOR[i], smoothstep(0.0, 1.0, (alt - ZENITH_ALT[i - 1]) / (ZENITH_ALT[i] - ZENITH_ALT[i - 1])));
  }
  return ZENITH_COLOR[ZENITH_KEYS - 1];
}

vec3 skyZenithClear(float alt) {
  return skyZenithPalette(alt) * nightGain(alt, NIGHT_SKY_BRIGHTNESS);
}

vec3 skyHorizonPalette(float alt) {
  if (alt <= HORIZON_ALT[0])
    return HORIZON_COLOR[0];
  for (int i = 1; i < HORIZON_KEYS; ++i) {
    if (alt <= HORIZON_ALT[i])
      return mix(HORIZON_COLOR[i - 1], HORIZON_COLOR[i], smoothstep(0.0, 1.0, (alt - HORIZON_ALT[i - 1]) / (HORIZON_ALT[i] - HORIZON_ALT[i - 1])));
  }
  return HORIZON_COLOR[HORIZON_KEYS - 1];
}

vec3 skyHorizonClear(float alt) {
  return skyHorizonPalette(alt) * nightGain(alt, NIGHT_SKY_BRIGHTNESS);
}

// ---------------------------------------------------------------------------------------------------------------
// Overcast: rain and snow gray the sky, dim and soften the sun and moon and let the sky's fill light take over.
// Everything that uses the sky palette or the light colors above goes through here, so the scene, the
// transparency layer, the fog and the weather particles all agree. Neutral (clear) at weatherOvercast.x = 0.
// Lightning flashes (weatherOvercast.z) go through the same functions, so everything flashes together.

uniform vec3 weatherOvercast;  // x: 0 clear .. 1 fully overcast, y: 0 rain .. 1 snow, z: lightning flash 0..1 (Weather.java)
// The weather's light (WeatherType): x sun strength - 1 (harsh sun above 0), y haze 0..1 (a milky sky, softer
// light and shadows), z warmth -1 cold .. 1 amber (dust, ash, heat), w sky saturation offset. All 0 is neutral.
uniform vec4 weatherLight;

// haze: share of the sky turned milky at full haze, its color, the sun and moon light it takes away, how much it
// softens shadows and lifts the ambient light (scattered sunlight), and how much of the sun's disc it hides
#define HAZE_SKY 0.6
#define HAZE_COLOR vec3(0.88, 0.87, 0.84)
#define HAZE_BRIGHTNESS 1.12
#define HAZE_DIRECT 0.35
#define HAZE_SHADOW 0.35
#define HAZE_AMBIENT 0.25
#define HAZE_DISC 0.6
// warmth tints at +1 and -1, on the direct light fully and on the ambient and sky by WARMTH_FILL
#define WARM_TINT vec3(1.12, 0.96, 0.76)
#define COOL_TINT vec3(0.88, 0.96, 1.12)
#define WARMTH_FILL 0.5
// harsh sun: shadows this much deeper per unit of extra strength, for crisp contrast
#define HARSH_SHADOW 0.6

// a lightning flash: cool white, added to the sky (sRGB) and to the ambient light (linear) at a full flash, and, as
// the light that casts shadows while a bolt strikes (casterKind 2), its strength (linear) at a full flash. The key
// is what the flash shows: the ambient only fills in the shadows it casts.
#define LIGHTNING_COLOR vec3(0.80, 0.86, 1.00)
#define LIGHTNING_SKY 0.45
#define LIGHTNING_AMBIENT 0.6
#define LIGHTNING_KEY 2.2

// the overcast sky's tint and brightness relative to the clear sky's luminance: dark slate in rain, soft white in snow
#define OVERCAST_RAIN_TINT vec3(0.86, 0.93, 1.05)
#define OVERCAST_SNOW_TINT vec3(0.98, 0.99, 1.02)
#define OVERCAST_RAIN_SKY 0.62
#define OVERCAST_SNOW_SKY 1.18
// share of the clear sky's own color left at full cover: an overcast sky is gray, sunsets only a faint warmth
#define OVERCAST_SKY_KEEP 0.06
// how far zenith and horizon move toward one brightness: an overcast sky is nearly even
#define OVERCAST_SKY_FLATTEN 0.7
// sun or moon left at full cover, and how much of its color it keeps: next to none, it is gray daylight, tinted like
// the overcast sky. The color goes well before the light does: gone by OVERCAST_DIRECT_GRAY of the cover.
#define OVERCAST_DIRECT_RAIN 0.30
#define OVERCAST_DIRECT_SNOW 0.42
#define OVERCAST_DIRECT_SATURATION 0.05
#define OVERCAST_DIRECT_GRAY 0.5
// ambient sky light at full cover: grayer, a little lower in rain, brighter in snow
#define OVERCAST_AMBIENT_RAIN 0.95
#define OVERCAST_AMBIENT_SNOW 1.30
#define OVERCAST_AMBIENT_GRAY 0.85
// shadows this much fainter at full cover: the light comes from the whole sky
#define OVERCAST_SHADOW 0.55
// How bright a sunlit surface is at full cover compared to under a clear sky (sun, ambient and sky light together).
// The sun is mostly gone and the ambient light stays as it is, so the sky light, the whole overcast dome shining
// down, makes up the difference (overcastSkyBoost); it is never dimmed, and at most this many times its plain level.
#define OVERCAST_TARGET_BRIGHTNESS 0.9
#define OVERCAST_SKY_BOOST_MAX 200.0
// the elevation range (degrees) the shadow caster stays within (DayCycle), whose sine is the light on level ground
#define OVERCAST_CASTER_ELEVATION vec2(30.0, 68.0)

float overcastLuma(vec3 c) {
  return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

vec3 weatherTint(float amount) {
  float w = weatherLight.z * amount;
  return w >= 0.0 ? mix(vec3(1.0), WARM_TINT, w) : mix(vec3(1.0), COOL_TINT, -w);
}

// The weather's sky before cloud cover: more or less saturated, milky in haze, tinted by warmth
vec3 weatherSky(vec3 c) {
  if (weatherLight == vec4(0.0))
    return c;
  float l = overcastLuma(c);
  c = max(mix(vec3(l), c, 1.0 + weatherLight.w), 0.0);
  vec3 haze = l * HAZE_BRIGHTNESS * HAZE_COLOR;
  return mix(c, haze, weatherLight.y * HAZE_SKY) * weatherTint(WARMTH_FILL);
}

// the sky c (after weatherSky) at cover o; even: the clear sky's mean luminance
vec3 overcastSkyTo(vec3 c, float even, float o) {
  float l = mix(overcastLuma(c), even, OVERCAST_SKY_FLATTEN);
  float s = weatherOvercast.y;
  vec3 gray = l * mix(OVERCAST_RAIN_SKY, OVERCAST_SNOW_SKY, s) * mix(OVERCAST_RAIN_TINT, OVERCAST_SNOW_TINT, s);
  return mix(c, mix(gray, c, OVERCAST_SKY_KEEP), o);
}

float clearSkyMean(float alt) {
  return 0.5 * (overcastLuma(skyZenithClear(alt)) + overcastLuma(skyHorizonClear(alt)));
}

vec3 overcastSky(vec3 c, float alt) {
  c = weatherSky(c);
  float o = weatherOvercast.x;
  if (o <= 0.0)
    return c;
  return overcastSkyTo(c, clearSkyMean(alt), o);
}

// the sun or moon color c at cover o
vec3 overcastDirectAt(vec3 c, float o) {
  c *= (1.0 - HAZE_DIRECT * weatherLight.y) * weatherTint(1.0);
  float s = weatherOvercast.y;
  vec3 muted = mix(overcastLuma(c) * mix(OVERCAST_RAIN_TINT, OVERCAST_SNOW_TINT, s), c, OVERCAST_DIRECT_SATURATION);
  return mix(c, muted, smoothstep(0.0, OVERCAST_DIRECT_GRAY, o)) * mix(1.0, mix(OVERCAST_DIRECT_RAIN, OVERCAST_DIRECT_SNOW, s), o);
}

vec3 overcastDirect(vec3 c) {
  return overcastDirectAt(c, weatherOvercast.x);
}

vec3 overcastAmbient(vec3 c, float o) {
  float s = weatherOvercast.y;
  vec3 gray = overcastLuma(c) * mix(OVERCAST_RAIN_TINT, OVERCAST_SNOW_TINT, s);
  vec3 a = mix(c, gray, OVERCAST_AMBIENT_GRAY) * mix(OVERCAST_AMBIENT_RAIN, OVERCAST_AMBIENT_SNOW, s);
  return mix(c, a, o) * (1.0 + HAZE_AMBIENT * weatherLight.y) * weatherTint(WARMTH_FILL);
}

// sun or moon shadow term under cover
float overcastShadow(float shadow) {
  float soft = clamp(weatherOvercast.x * OVERCAST_SHADOW + weatherLight.y * HAZE_SHADOW, 0.0, 1.0);
  shadow = mix(shadow, 1.0, soft);
  return clamp(shadow - max(weatherLight.x, 0.0) * HARSH_SHADOW * (1.0 - shadow), 0.0, 1.0);
}

// how much of the sun and moon discs, their glow and the stars still show
float overcastClear() {
  return (1.0 - smoothstep(0.15, 0.85, weatherOvercast.x)) * (1.0 - HAZE_DISC * weatherLight.y);
}

vec3 skyZenithAt(float alt) {
  return overcastSky(skyZenithClear(alt), alt) + LIGHTNING_COLOR * (LIGHTNING_SKY * weatherOvercast.z);
}

vec3 skyHorizonAt(float alt) {
  return overcastSky(skyHorizonClear(alt), alt) + LIGHTNING_COLOR * (LIGHTNING_SKY * 0.8 * weatherOvercast.z);
}

// Light colors. These are LINEAR (the composite lights in linear space, like 117 HD) and are multiplied with
// the surface color, so 1.0 means "full surface color". The sun is much stronger than the ambient on purpose:
// that contrast between sunlit and shaded faces is what makes sunlight read as sunlight.
#define SUN_INTENSITY 2.9
// Under a clear sky with the sun well up the sky's share of the light is smaller against the sun's, so sunlit and
// shaded faces part more: the ambient light drops to this much, a little further in harsh sun. Rain, snow and haze
// keep their soft, even light.
#define CLEAR_AMBIENT 0.80
#define HARSH_AMBIENT 0.08
#define MOON_INTENSITY 0.675
#define SKY_LIGHT 0.45

vec3 srgbToLinear(vec3 c) {
  return mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(vec3(0.04045), c));
}

vec3 linearToSrgb(vec3 c) {
  return mix(c * 12.92, 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055, step(vec3(0.0031308), c));
}

// The lights at cover o; sunLightAt and the others use the weather's own
vec3 sunLightCover(float alt, float o) {
  vec3 warm = vec3(1.00, 0.42, 0.16);
  vec3 noon = vec3(1.00, 0.90, 0.76);
  return overcastDirectAt(mix(warm, noon, smoothstep(2.0, 28.0, alt)) * SUN_INTENSITY * smoothstep(-4.0, 10.0, alt), o)
    * (1.0 + weatherLight.x);
}

vec3 moonLightCover(float alt, float o) {
  return overcastDirectAt(vec3(0.42, 0.55, 1.00) * MOON_INTENSITY * smoothstep(-2.0, -14.0, alt), o);
}

// Light from the whole sky, cool blue by day; fills shadows (without lightning)
vec3 ambientCover(float alt, float o) {
  vec3 day = vec3(0.30, 0.38, 0.58);
  vec3 night = vec3(0.10, 0.14, 0.30);
  vec3 a = mix(night, day, smoothstep(-10.0, 15.0, alt)) * nightGain(alt, NIGHT_LIGHT_BRIGHTNESS);
  // warmer, a little darker around sunrise and sunset
  float dusk = smoothstep(-8.0, 2.0, alt) * (1.0 - smoothstep(2.0, 20.0, alt));
  float clearSun = smoothstep(8.0, 35.0, alt) * (1.0 - o) * (1.0 - weatherLight.y);
  a *= mix(1.0, CLEAR_AMBIENT - HARSH_AMBIENT * clamp(weatherLight.x, 0.0, 1.0), clearSun);
  return overcastAmbient(a * mix(vec3(1.0), vec3(1.10, 0.80, 0.80), dusk), o);
}

vec3 sunLightAt(float alt) {
  return sunLightCover(alt, weatherOvercast.x);
}

vec3 moonLightAt(float alt) {
  return moonLightCover(alt, weatherOvercast.x);
}

vec3 ambientLightAt(float alt) {
  return ambientCover(alt, weatherOvercast.x) + LIGHTNING_COLOR * (LIGHTNING_AMBIENT * weatherOvercast.z);
}

// How much the sky light is raised under cloud, so that a sunlit, level surface gets OVERCAST_TARGET_BRIGHTNESS of
// the light it gets under a clear sky (at the same hour and weather look). Energy balance on that surface, in
// luminance: the clear sky gives sun * ndl + ambient + sky; under cover o the sun is cut down and the ambient light
// stays, and the sky light has to carry the rest. skyClear, skyOver: the sky light before the boost without and
// with cover.
float overcastSkyBoost(float alt, float skyClear, float skyOver) {
  float o = weatherOvercast.x;
  float ndl = sin(radians(clamp(abs(alt), OVERCAST_CASTER_ELEVATION.x, OVERCAST_CASTER_ELEVATION.y)));
  float clearLight = overcastLuma(sunLightCover(alt, 0.0) + moonLightCover(alt, 0.0)) * ndl
    + overcastLuma(ambientCover(alt, 0.0)) + skyClear;
  float direct = overcastLuma(sunLightCover(alt, o) + moonLightCover(alt, o)) * ndl
    + overcastLuma(ambientCover(alt, o));
  float need = clearLight * mix(1.0, OVERCAST_TARGET_BRIGHTNESS, o) - direct;
  return clamp(need / max(skyOver, 1e-5), 1.0, OVERCAST_SKY_BOOST_MAX);
}

// Light from the sky above on upward-facing surfaces, tinted by the sky (linear, before the lightning), and the
// boost under cloud
vec3 skyLightBase(float alt, out float boost) {
  vec3 z = skyZenithClear(alt);
  vec3 sky = weatherSky(z);
  float o = weatherOvercast.x;
  boost = 1.0;
  if (o <= 0.0)
    return sky;
  vec3 base = overcastSkyTo(sky, 0.5 * (overcastLuma(z) + overcastLuma(skyHorizonClear(alt))), o);
  boost = overcastSkyBoost(alt, overcastLuma(srgbToLinear(sky)) * SKY_LIGHT, overcastLuma(srgbToLinear(base)) * SKY_LIGHT);
  return base;
}

// Extra light on upward-facing surfaces from the sky above them, tinted by the sky
vec3 skyLightAt(float alt) {
  float boost;
  vec3 base = skyLightBase(alt, boost);
  vec3 lin = srgbToLinear(base) * boost;
  if (weatherOvercast.z > 0.0) {
    lin += srgbToLinear(base + LIGHTNING_COLOR * (LIGHTNING_SKY * weatherOvercast.z)) - srgbToLinear(base);
  }
  return lin * SKY_LIGHT;
}

float skyBoostAt(float alt) {
  float boost;
  skyLightBase(alt, boost);
  return boost;
}

// the lightning flash's part of skyLightAt, for light that leaves it out (atmosphere.glsl's sky cache)
vec3 skyLightningAt() {
  return srgbToLinear(LIGHTNING_COLOR * (LIGHTNING_SKY * weatherOvercast.z)) * SKY_LIGHT;
}

// The light on the clouds (clouds.glsl). They are high, so they catch the sun a little before sunrise and after
// sunset, red then; below CLOUD_MOON_SWITCH the moon lights them, and neither light reaches them around it.
#define CLOUD_MOON_SWITCH -8.0
#define CLOUD_SUN 1.0
#define CLOUD_MOON 1.3

vec3 cloudLightAt(float alt) {
  if (alt > CLOUD_MOON_SWITCH) {
    vec3 warm = mix(vec3(1.00, 0.42, 0.16), vec3(0.95, 0.30, 0.32), smoothstep(0.0, -6.0, alt));
    vec3 noon = vec3(1.00, 0.90, 0.76);
    return overcastDirect(mix(warm, noon, smoothstep(2.0, 28.0, alt)) * SUN_INTENSITY
      * smoothstep(CLOUD_MOON_SWITCH, 4.0, alt)) * CLOUD_SUN;
  }
  return moonLightAt(alt) * (CLOUD_MOON * smoothstep(CLOUD_MOON_SWITCH, CLOUD_MOON_SWITCH - 3.0, alt));
}

// The colors above at a sun altitude. The composite uses them at every pixel, always at this frame's altitude: there
// composite_vert.glsl works them out once and hands them over (SKY_FRAME_CONSTANTS), instead of every pixel walking
// the palette and the overcast mixing again, which cost over a millisecond.
#ifdef SKY_FRAME_CONSTANTS
flat in vec3 skyFrame[11];
vec3 skyZenith(float alt) { return skyFrame[0]; }
vec3 skyHorizon(float alt) { return skyFrame[1]; }
vec3 sunLight(float alt) { return skyFrame[2]; }
vec3 moonLight(float alt) { return skyFrame[3]; }
vec3 ambientLight(float alt) { return skyFrame[4]; }
vec3 skyLight(float alt) { return skyFrame[5]; }
float skyBoost(float alt) { return skyFrame[6].x; }
vec3 skyLightning() { return skyFrame[7]; }
vec3 cloudLight(float alt) { return skyFrame[8]; }
vec3 skyZenithLinear(float alt) { return skyFrame[9]; }
vec3 skyHorizonLinear(float alt) { return skyFrame[10]; }
#else
vec3 skyZenith(float alt) { return skyZenithAt(alt); }
vec3 skyHorizon(float alt) { return skyHorizonAt(alt); }
vec3 sunLight(float alt) { return sunLightAt(alt); }
vec3 moonLight(float alt) { return moonLightAt(alt); }
vec3 ambientLight(float alt) { return ambientLightAt(alt); }
vec3 skyLight(float alt) { return skyLightAt(alt); }
float skyBoost(float alt) { return skyBoostAt(alt); }
vec3 skyLightning() { return skyLightningAt(); }
vec3 cloudLight(float alt) { return cloudLightAt(alt); }
vec3 skyZenithLinear(float alt) { return srgbToLinear(skyZenithAt(alt)); }
vec3 skyHorizonLinear(float alt) { return srgbToLinear(skyHorizonAt(alt)); }
#endif

// The light that casts shadows, linear: the sun (casterKind 1), the moon (0) or, while a bolt strikes (2), the bolt
vec3 casterLight(float alt, int kind) {
  if (kind == 2) {
    return LIGHTNING_COLOR * (LIGHTNING_KEY * weatherOvercast.z);
  }
  return kind == 1 ? sunLight(alt) : moonLight(alt);
}

// Underground: no sun, so a fixed soft key light from above keeps shapes readable. y points down.
#define CAVE_AMBIENT 0.50
#define CAVE_KEY 0.40
#define CAVE_KEY_DIR normalize(vec3(-0.35, -1.0, 0.45))

vec3 caveLight(vec3 n) {
  return vec3(CAVE_AMBIENT) + vec3(CAVE_KEY) * max(dot(n, CAVE_KEY_DIR), 0.0);
}

// linear value above which highlights are compressed toward 1
#define SHOULDER 0.75

// Light an sRGB surface color in linear space, with a soft highlight shoulder; stays linear
vec3 shadeLinear(vec3 srgb, vec3 light) {
  vec3 c = srgbToLinear(srgb) * light;
  c = mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
  return clamp(c, 0.0, 1.0);
}

// the same, returning sRGB
vec3 shade(vec3 srgb, vec3 light) {
  return linearToSrgb(shadeLinear(srgb, light));
}

float hash13(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

// The sky's colors from the horizon to the zenith, without the sun, moon or stars. dir: normalized view ray, game
// coordinates (y down)
vec3 skyGradient(vec3 dir, float alt) {
  float elev = -dir.y;
  vec3 zenith = skyZenith(alt);
  vec3 horizon = skyHorizon(alt);
  return elev >= 0.0
    ? mix(horizon, zenith, pow(clamp(elev, 0.0, 1.0), 0.45))
    : horizon * mix(1.0, 0.65, clamp(-elev * 4.0, 0.0, 1.0));
}

// Wide warm glow around the sun, added to skyGradient; visible a little below the horizon for sunsets
vec3 sunGlow(vec3 dir, vec3 sunDir, float alt) {
  float sunVis = smoothstep(-6.0, 2.0, alt) * overcastClear();
  float mu = max(dot(dir, sunDir), 0.0);
  vec3 glowColor = mix(vec3(1.0, 0.55, 0.30), vec3(1.0, 0.85, 0.65), smoothstep(0.0, 25.0, alt));
  // mu^10 and mu^120, multiplied out
  float m2 = mu * mu;
  float m4 = m2 * m2;
  float m10 = m4 * m4 * m2;
  float m40 = m10 * m10;
  m40 *= m40;
  return glowColor * (m10 * 0.30 + m40 * m40 * m40 * 0.50) * sunVis;
}

// dir: normalized view ray, game coordinates (y down)
vec3 skyColor(vec3 dir, vec3 sunDir, vec3 moonDir, float alt, float time) {
  float elev = -dir.y;
  vec3 col = skyGradient(dir, alt) + sunGlow(dir, sunDir, alt);

  // sun disc
  float sunVis = smoothstep(-6.0, 2.0, alt) * overcastClear();
  float mu = max(dot(dir, sunDir), 0.0);
  col += vec3(1.0, 0.95, 0.85) * smoothstep(0.99985, 0.99992, mu) * sunVis;

  // night: moon disc with a faint halo, and stars above the horizon
  float night = smoothstep(-4.0, -14.0, alt) * overcastClear();
  float mm = max(dot(dir, moonDir), 0.0);
  col += vec3(0.85, 0.88, 1.00) * (smoothstep(0.99990, 0.99995, mm) + pow(mm, 60.0) * 0.08) * night;

  vec3 cell = floor(dir * 320.0);
  float star = step(0.9986, hash13(cell));
  float twinkle = 0.7 + 0.3 * sin(time * 2.0 + hash13(cell + 7.0) * 40.0);
  col += vec3(star * twinkle * night * smoothstep(0.0, 0.15, elev));

  return col;
}

// How far a view ray (camera-relative, y down) points down past where any sky could be: none at the horizon and
// shallow angles (the fog and horizon past the map's edge), all of it from VOID_BELOW.y down. What is seen there is
// the dark under the world, not sky.
#define VOID_BELOW vec2(0.35, 0.7)
float voidBelow(vec3 dir) {
  return smoothstep(VOID_BELOW.x, VOID_BELOW.y, dir.y);
}
