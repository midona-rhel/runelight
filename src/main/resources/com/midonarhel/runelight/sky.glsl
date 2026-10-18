// Sky and light colors, all driven by the visual sun altitude in degrees (below 0 is night).
// Colors are in the game's sRGB-ish space. Keyframes are easy to tune live: edit and save.

#define SKY_KEYS 8
const float SKY_ALT[SKY_KEYS] = float[](-30.0, -12.0, -6.0, 0.0, 6.0, 15.0, 35.0, 90.0);
const vec3 SKY_ZENITH[SKY_KEYS] = vec3[](
  vec3(0.004, 0.006, 0.020),
  vec3(0.020, 0.040, 0.100),
  vec3(0.100, 0.140, 0.280),
  vec3(0.280, 0.300, 0.480),
  vec3(0.320, 0.460, 0.700),
  vec3(0.300, 0.520, 0.820),
  vec3(0.280, 0.520, 0.860),
  vec3(0.260, 0.500, 0.850));
const vec3 SKY_HORIZON[SKY_KEYS] = vec3[](
  vec3(0.010, 0.015, 0.035),
  vec3(0.060, 0.080, 0.160),
  vec3(0.400, 0.250, 0.250),
  vec3(0.950, 0.520, 0.300),
  vec3(0.950, 0.720, 0.500),
  vec3(0.720, 0.800, 0.900),
  vec3(0.650, 0.780, 0.920),
  vec3(0.620, 0.760, 0.920));

vec3 skyKey(const vec3 keys[SKY_KEYS], float alt) {
  if (alt <= SKY_ALT[0])
    return keys[0];
  for (int i = 1; i < SKY_KEYS; ++i) {
    if (alt <= SKY_ALT[i]) {
      float t = (alt - SKY_ALT[i - 1]) / (SKY_ALT[i] - SKY_ALT[i - 1]);
      return mix(keys[i - 1], keys[i], smoothstep(0.0, 1.0, t));
    }
  }
  return keys[SKY_KEYS - 1];
}

vec3 skyZenith(float alt) {
  return skyKey(SKY_ZENITH, alt);
}

vec3 skyHorizon(float alt) {
  return skyKey(SKY_HORIZON, alt);
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
  return vec3(0.55, 0.65, 1.00) * MOON_INTENSITY * smoothstep(-2.0, -14.0, alt);
}

vec3 ambientLight(float alt) {
  vec3 day = vec3(0.60, 0.62, 0.66);
  vec3 night = vec3(0.44, 0.48, 0.60);
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
