// Wet ground and snow cover on the surfaces themselves (WeatherGround.java), applied to the albedo and normal before
// the composite lights them, so they take the AO, the shadows, the global illumination and the point lights like
// any other surface. The AO pass leaves how much snow and wetness each pixel gets (weather_ground_ao.glsl); here the
// snow becomes patches: a fixed world-space noise thresholded against that amount, so patches start in the cores
// of the noise and grow outward as the snow deepens, and melt back the same way. Upward faces fill first; sheltered
// spots, which get less, last.

uniform int weatherGroundOn;
uniform vec2 weatherNoiseOrigin;  // world xz of local 0, world units: patches stay put when the scene reloads

// the AO pass's snow (x) and wetness (y), upsampled with the AO (composite_frag.glsl's upsampleAoFog)
vec2 groundUpsampled = vec2(0.0);

#define SNOW_ALBEDO vec3(0.86, 0.89, 0.95)
// noise scales, world units: large clumps, their shape, and the fine edge
#define SNOW_CLUMP 448.0
#define SNOW_DETAIL 128.0
#define SNOW_FINE 36.0
// half width of a patch's edge, in noise units: soft but defined
#define SNOW_EDGE 0.035
// world units the snow rises over at a patch's edge, for its shading; and how far a full cover smooths the
// surface toward flat
#define SNOW_BULGE 6.0
#define SNOW_SMOOTH 0.4
// faces need to point this far up (-n.y) to start holding snow, and are fully open to it at the second
#define SNOW_FACE_LOW 0.3
#define SNOW_FACE_HIGH 0.85
#define WET_DARKEN 0.80        // albedo multiplier on fully wet surfaces
#define WET_SHEEN 0.10         // sky reflection on wet upward surfaces

float groundHash(vec2 i) {
  vec3 p3 = fract(vec3(mod(i, 4096.0).xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// value noise 0..1 with its gradient in yz
vec3 groundNoise(vec2 x) {
  vec2 i = floor(x);
  vec2 f = x - i;
  vec2 u = f * f * (3.0 - 2.0 * f);
  vec2 du = 6.0 * f * (1.0 - f);
  float a = groundHash(i);
  float b = groundHash(i + vec2(1.0, 0.0));
  float c = groundHash(i + vec2(0.0, 1.0));
  float d = groundHash(i + vec2(1.0, 1.0));
  float k = a - b - c + d;
  return vec3(a + (b - a) * u.x + (c - a) * u.y + k * u.x * u.y, du * (vec2(b - a, c - a) + k * u.yx));
}

// where snow lies first (low) to last (high), 0..1, and its gradient per world unit
vec3 snowOrder(vec2 world) {
  vec3 n = groundNoise(world / SNOW_CLUMP) * vec3(0.55, vec2(0.55 / SNOW_CLUMP))
    + groundNoise(world / SNOW_DETAIL + 17.3) * vec3(0.30, vec2(0.30 / SNOW_DETAIL))
    + groundNoise(world / SNOW_FINE + 41.7) * vec3(0.15, vec2(0.15 / SNOW_FINE));
  // a sum of noise sits near the middle; spread it so the cover follows the amount evenly
  float t = clamp((n.x - 0.2) / 0.6, 0.0, 1.0);
  return vec3(t * t * (3.0 - 2.0 * t), n.yz * (6.0 * t * (1.0 - t) / 0.6));
}

// Snow and wetness on a surface before lighting: changes the albedo (sRGB) and the normal. world is the point's
// world position (local + origin applied here), alpha the transparency layer over it (water: no snow under it).
// Returns the wetness left showing, for weatherWetSheen.
float weatherGroundSurface(inout vec3 color, inout vec3 n, vec3 world, float emissive, float waterBed, float alpha) {
  if (weatherGroundOn == 0) {
    return 0.0;
  }
  vec2 g = groundUpsampled * ((1.0 - emissive) * (1.0 - step(0.75, waterBed)) * (1.0 - alpha));
  if (max(g.x, g.y) <= 0.002) {
    return 0.0;
  }
  float up = max(-n.y, 0.0);
  float cover = 0.0;
  if (g.x > 0.002) {
    vec3 order = snowOrder(world.xz + weatherNoiseOrigin);
    float level = g.x * smoothstep(SNOW_FACE_LOW, SNOW_FACE_HIGH, up);
    // level 0 covers nothing and level 1 everything, whatever the noise
    float x = level * (1.0 + 2.0 * SNOW_EDGE) - SNOW_EDGE - order.x;
    float t = clamp((x + SNOW_EDGE) / (2.0 * SNOW_EDGE), 0.0, 1.0);
    cover = t * t * (3.0 - 2.0 * t);
    if (cover > 0.0) {
      // the snow's top rises from the patch edge inward, against the order's gradient: a rounded rim
      vec2 tilt = order.yz * (SNOW_BULGE * 6.0 * t * (1.0 - t) / (2.0 * SNOW_EDGE));
      float l = length(tilt);
      tilt *= l > 0.6 ? 0.6 / l : 1.0;
      vec3 smoothN = normalize(mix(n, vec3(0.0, -1.0, 0.0), SNOW_SMOOTH * cover));
      n = normalize(smoothN + vec3(tilt.x, 0.0, tilt.y));
      color = mix(color, SNOW_ALBEDO, cover);
    }
  }
  float wet = g.y * mix(0.6, 1.0, up) * (1.0 - cover);
  color *= mix(1.0, WET_DARKEN, wet);
  return wet;
}

// Wet surfaces reflect a little sky, upward ones most and more at grazing angles. p camera-relative.
vec3 weatherWetSheen(vec3 lit, vec3 p, vec3 n, float wet, float ao, vec3 sky) {
  if (wet <= 0.0) {
    return lit;
  }
  float fresnel = pow(1.0 - max(dot(-normalize(p), n), 0.0), 4.0);
  return lit + sky * (WET_SHEEN * max(-n.y, 0.0) * (0.3 + 0.7 * fresnel) * wet * ao);
}
