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
  vec3(0.435, 0.561, 0.769),
  vec3(0.361, 0.576, 0.812),
  vec3(0.310, 0.525, 0.753));

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

vec3 skyZenith(float alt) {
  if (alt <= ZENITH_ALT[0])
    return ZENITH_COLOR[0];
  for (int i = 1; i < ZENITH_KEYS; ++i) {
    if (alt <= ZENITH_ALT[i])
      return mix(ZENITH_COLOR[i - 1], ZENITH_COLOR[i], smoothstep(0.0, 1.0, (alt - ZENITH_ALT[i - 1]) / (ZENITH_ALT[i] - ZENITH_ALT[i - 1])));
  }
  return ZENITH_COLOR[ZENITH_KEYS - 1];
}

vec3 skyHorizon(float alt) {
  if (alt <= HORIZON_ALT[0])
    return HORIZON_COLOR[0];
  for (int i = 1; i < HORIZON_KEYS; ++i) {
    if (alt <= HORIZON_ALT[i])
      return mix(HORIZON_COLOR[i - 1], HORIZON_COLOR[i], smoothstep(0.0, 1.0, (alt - HORIZON_ALT[i - 1]) / (HORIZON_ALT[i] - HORIZON_ALT[i - 1])));
  }
  return HORIZON_COLOR[HORIZON_KEYS - 1];
}

// Direct light from the sun (day) or moon (night). Only one casts at a time; both are ~0 at the switch.
#define SUN_INTENSITY 0.50
#define MOON_INTENSITY 0.30

vec3 sunLight(float alt) {
  vec3 warm = vec3(1.00, 0.55, 0.30);
  vec3 noon = vec3(1.00, 0.96, 0.88);
  return mix(warm, noon, smoothstep(2.0, 25.0, alt)) * SUN_INTENSITY * smoothstep(-4.0, 10.0, alt);
}

vec3 moonLight(float alt) {
  return vec3(0.56, 0.66, 1.00) * MOON_INTENSITY * smoothstep(-2.0, -14.0, alt);
}

vec3 ambientLight(float alt) {
  vec3 day = vec3(0.60, 0.62, 0.66);
  vec3 night = vec3(0.36, 0.44, 0.66);
  vec3 a = mix(night, day, smoothstep(-10.0, 15.0, alt));
  // warmer at sunrise and sunset
  float dusk = smoothstep(-8.0, 2.0, alt) * (1.0 - smoothstep(2.0, 20.0, alt));
  return a * mix(vec3(1.0), vec3(1.08, 0.88, 0.80), dusk);
}

float hash13(vec3 p) {
  p = fract(p * 0.1031);
  p += dot(p, p.zyx + 31.32);
  return fract((p.x + p.y) * p.z);
}

// dir: normalized view ray, game coordinates (y down)
vec3 skyColor(vec3 dir, vec3 sunDir, vec3 moonDir, float alt, float time) {
  float elev = -dir.y;
  vec3 zenith = skyZenith(alt);
  vec3 horizon = skyHorizon(alt);
  vec3 col = elev >= 0.0
    ? mix(horizon, zenith, pow(clamp(elev, 0.0, 1.0), 0.45))
    : horizon * mix(1.0, 0.65, clamp(-elev * 4.0, 0.0, 1.0));

  // sun: wide warm glow plus a disc, visible a little below the horizon for sunsets
  float sunVis = smoothstep(-6.0, 2.0, alt);
  float mu = max(dot(dir, sunDir), 0.0);
  vec3 glowColor = mix(vec3(1.0, 0.55, 0.30), vec3(1.0, 0.85, 0.65), smoothstep(0.0, 25.0, alt));
  col += glowColor * (pow(mu, 10.0) * 0.30 + pow(mu, 120.0) * 0.50) * sunVis;
  col += vec3(1.0, 0.95, 0.85) * smoothstep(0.99985, 0.99992, mu) * sunVis;

  // night: moon disc with a faint halo, and stars above the horizon
  float night = smoothstep(-4.0, -14.0, alt);
  float mm = max(dot(dir, moonDir), 0.0);
  col += vec3(0.85, 0.88, 1.00) * (smoothstep(0.99990, 0.99995, mm) + pow(mm, 60.0) * 0.08) * night;

  vec3 cell = floor(dir * 320.0);
  float star = step(0.9986, hash13(cell));
  float twinkle = 0.7 + 0.3 * sin(time * 2.0 + hash13(cell + 7.0) * 40.0);
  col += vec3(star * twinkle * night * smoothstep(0.0, 0.15, elev));

  return col;
}
