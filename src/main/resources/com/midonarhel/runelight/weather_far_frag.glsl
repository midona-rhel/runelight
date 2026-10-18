#version 330

// Rain and snow beyond the particles: one full screen pass that marches each view ray a few steps through the
// weather field (WeatherField.java) from where the particle box around the player fades out to the scenery or,
// for the sky, up to the cloud base. Wherever it rains or snows along the ray the air thickens into a soft veil,
// streaked by falling rain or mottled by drifting snow, and broken into drifting curtains by a noise looked up
// where along the ray the rain is: from afar a storm reads as shafts hanging under its clouds. Blended over the
// scene like the particles.

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"

uniform vec2 farAmount;        // rain, snow at the player (Weather's amounts with swells), the field adds to them
uniform vec2 farNear;          // half widths of the rain and snow particle boxes around the player, world units
uniform float farReach;        // world units from the camera the veil reaches
uniform vec3 windOffset;       // world units the wind has carried everything so far

out vec4 FragColor;

#define FAR_STEPS 6
// the particles fade out from this share of their box's half width; the veil fades in over the same band
#define FAR_FADE_START 0.72
// extinction per world unit at rain or snow 1: at heavy rain about half the scene behind 50 tiles of it shows
#define FAR_RAIN_DENSITY 0.7e-4
#define FAR_SNOW_DENSITY 1.0e-4
// never hides more of the scene than this
#define FAR_MAX_ALPHA 0.6
// rain hangs below the clouds: clouds.glsl's CLOUD_BOTTOM, world units above y = 0; it fades into them from this
// share of the way up
#define FAR_CEILING 9000.0
#define FAR_CEILING_FADE 0.6
#define FAR_RAIN_COLOR vec3(0.60, 0.64, 0.72)
#define FAR_SNOW_COLOR vec3(0.90, 0.92, 0.96)
// curtains: world units per blob of the noise that breaks the veil up, and the band of it from thin to thick
#define FAR_SHAFT_SCALE 2500.0
#define FAR_SHAFT_LOW 0.3
#define FAR_SHAFT_HIGH 0.7
#define FAR_SHAFT_MIN 0.25
#define FAR_SHAFT_MAX 1.4
// streaks: around the horizon, and their length in the same units (long, thin, falling)
#define FAR_STREAKS 900.0
#define FAR_STREAK_STRETCH 0.06
#define FAR_STREAK_SPEED 33.0
#define FAR_STREAK 0.35
// snow: mottling that drifts down slowly
#define FAR_FLAKES 1400.0
#define FAR_FLAKE_SPEED 0.35
#define FAR_FLAKE 0.35

float ign(vec2 p) {
  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

// value noise; x wraps every `period` cells so a pattern around the horizon has no seam
float vnoise(vec2 p, float period) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float x0 = mod(i.x, period), x1 = mod(i.x + 1.0, period);
  float a = hash12(vec2(x0, i.y));
  float b = hash12(vec2(x1, i.y));
  float c = hash12(vec2(x0, i.y + 1.0));
  float d = hash12(vec2(x1, i.y + 1.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// where a ray from o (relative to the box's center) along d leaves a square of half width a; 0 from outside it
float exitSquare(vec2 o, vec2 d, float a) {
  if (any(greaterThan(abs(o), vec2(a)))) {
    return 0.0;
  }
  vec2 t = vec2(1e9);
  if (abs(d.x) > 1e-6) {
    t.x = ((d.x > 0.0 ? a : -a) - o.x) / d.x;
  }
  if (abs(d.y) > 1e-6) {
    t.y = ((d.y > 0.0 ? a : -a) - o.y) / d.y;
  }
  return min(t.x, t.y);
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  if (!inViewport(px)) {
    discard;
  }
  float depth = texelFetch(sceneDepth, px, 0).r;
  bool sky = isSky(depth);
  vec3 hit = reconstructPosition(vec2(px) + 0.5, sky ? 1.0 : depth);
  vec3 dir = normalize(hit);

  float t1 = sky ? farReach : min(length(hit), farReach);
  float rise = -dir.y;
  float camAlt = -cameraPos.y;
  if (rise > 1e-4) {
    t1 = min(t1, max(FAR_CEILING - camAlt, 0.0) / rise);
  }
  // the particles fill the box around the player; start where the smaller box starts fading
  vec2 near = farNear * FAR_FADE_START;
  float t0 = exitSquare(cameraPos.xz - focusPos.xz, dir.xz, min(near.x, near.y));
  if (t1 <= t0) {
    discard;
  }

  float dt = (t1 - t0) / float(FAR_STEPS);
  float jitter = ign(gl_FragCoord.xy);
  float odRain = 0.0, odSnow = 0.0;
  // where along the ray the rain and snow are, weighted, for the curtains
  vec2 rainAt = vec2(0.0);
  for (int i = 0; i < FAR_STEPS; ++i) {
    vec3 wp = cameraPos + dir * (t0 + (float(i) + jitter) * dt);
    vec4 delta = weatherFieldDelta(wp.xz);
    float rain = max(farAmount.x + delta.g, 0.0);
    float snow = max(farAmount.y + delta.b, 0.0);
    if (rain + snow <= 0.0) {
      continue;
    }
    // fade in where the particles fade out (square, like their boxes)
    vec2 rel = abs(wp.xz - focusPos.xz);
    float box = max(rel.x, rel.y);
    float wr = smoothstep(farNear.x * FAR_FADE_START, farNear.x, box);
    float ws = smoothstep(farNear.y * FAR_FADE_START, farNear.y, box);
    float ceiling = 1.0 - smoothstep(FAR_CEILING * FAR_CEILING_FADE, FAR_CEILING, -wp.y);
    float r = rain * wr * ceiling, s = snow * ws * ceiling;
    odRain += r;
    odSnow += s;
    rainAt += wp.xz * (r + s);
  }
  float total = odRain + odSnow;
  if (total <= 0.0) {
    discard;
  }
  rainAt /= total;
  float shaft = mix(FAR_SHAFT_MIN, FAR_SHAFT_MAX,
    smoothstep(FAR_SHAFT_LOW, FAR_SHAFT_HIGH, vnoise((rainAt - windOffset.xz) / FAR_SHAFT_SCALE, 1e6)));
  float aRain = (1.0 - exp(-odRain * dt * FAR_RAIN_DENSITY)) * shaft;
  float aSnow = (1.0 - exp(-odSnow * dt * FAR_SNOW_DENSITY)) * shaft;
  if (aRain + aSnow < 0.002) {
    discard;
  }

  // streaks and flakes keep to directions in the world, so they don't swim as the camera turns
  float az = atan(dir.z, dir.x) / 6.28318530718 + 0.5;
  float el = asin(clamp(rise, -1.0, 1.0)) / 6.28318530718;
  float streak = vnoise(vec2(az * FAR_STREAKS, (el * FAR_STREAKS + time * FAR_STREAK_SPEED) * FAR_STREAK_STRETCH),
    FAR_STREAKS);
  aRain *= mix(1.0 - FAR_STREAK, 1.0 + FAR_STREAK, streak);
  float flake = vnoise(vec2(az * FAR_FLAKES, el * FAR_FLAKES + time * FAR_FLAKE_SPEED * FAR_FLAKES * 0.01),
    FAR_FLAKES);
  aSnow *= mix(1.0 - FAR_FLAKE, 1.0 + FAR_FLAKE, flake);

  float a = 1.0 - (1.0 - aRain) * (1.0 - aSnow);
  vec3 color = (weatherParticleColor(FAR_RAIN_COLOR, vec3(0.0)) * aRain
    + weatherParticleColor(FAR_SNOW_COLOR, vec3(0.0)) * aSnow) / max(aRain + aSnow, 1e-4);
  FragColor = vec4(color, min(a, FAR_MAX_ALPHA));
}
