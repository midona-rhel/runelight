// The procedural sky as the composite draws it (sky colors, clouds, the sun, moon and stars through them) and
// the sky cache (SkyCache.java): the sky and clouds captured every few in-game minutes into a small lat-long
// texture, blended between captures, and the light it sheds on surfaces facing each way (sky_irradiance_frag.glsl),
// so cloud cover and the sky's colors show on the ground. Optionally the cache also stands in for the clouds
// marched per pixel.
// Needs frame.glsl, sky.glsl, environment.glsl, lightning.glsl and clouds.glsl.

uniform sampler2D skyCache;  // linear: sky through the clouds (rgb), cloud transmittance (a); see skyCacheUv
uniform sampler2D skyIrradiance;  // linear: cosine weighted sky seen by a surface facing each way, same layout
uniform int skyCacheMode;    // bits (SkyCache.MODE_*): 1 sky light from the cache, 2 the sky itself drawn from it
uniform float skyCacheWeight;  // 0..1, how much of the sky light comes from the cache rather than skyLight: eases
                               // in and out so the light never switches between the two at once
uniform float skyHaze;       // 0..1, how hazy the air is (Weather.skyHaze): the stars fade
uniform float starBrightness;

#define SKY_TAU 6.28318530718
// the cache reaches this far below the horizon (sine of the elevation), where clouds still fade out
#define SKY_CACHE_BELOW 0.05
// sky light from the cache: the cosine weighted sky is a little brighter than the zenith color skyLight uses, so
// a clear midday sky lights as before
#define SKY_CACHE_LIGHT (SKY_LIGHT * 0.89)
// Share of the sky light that also reaches surfaces facing sideways, which see half the sky: walls take the color
// of the sky they face (warm toward a sunset, cool away from it, gray under cloud). The analytic sky light only
// lights faces turned up.
#define SKY_CACHE_SIDES 0.5
// Sun and moon discs are brighter than the sky's white (linear), so they still burn through thin cloud;
// transmittance to this power hides them behind thick cloud
#define SUN_DISC 6.0
#define MOON_DISC 2.5
#define MOON_HALO 0.012
#define DISC_CLOUD_POWER 1.5
// Stars: one at most per cell of a grid around the camera, a soft dot about a pixel wide. Most are faint
// (brightness falls off as a power), they twinkle a little, and fade toward the horizon, in haze and at dawn.
#define STAR_CELLS 320.0
#define STAR_CHANCE 0.0011
#define STAR_SIZE 0.0004
#define STAR_BRIGHTNESS 0.9
#define STAR_FAINT 2.0
#define STAR_TWINKLE 0.25
#define STAR_HORIZON 0.3
#define STAR_HAZE 0.9

// Cache layout: u is the azimuth, v the square root of the elevation's sine, so most texels go to the sky near
// the horizon, where it is seen most and changes fastest
vec2 skyCacheUv(vec3 dir) {
  // atan(0, 0) is undefined, and some drivers return NaN for it: straight up and down have any azimuth
  float u = atan(dir.z, abs(dir.x) + abs(dir.z) > 1e-6 ? dir.x : 1.0) / SKY_TAU + 0.5;
  float v = sqrt(clamp((-dir.y + SKY_CACHE_BELOW) / (1.0 + SKY_CACHE_BELOW), 0.0, 1.0));
  return vec2(u, v);
}

vec3 skyCacheDir(vec2 uv) {
  float s = uv.y * uv.y * (1.0 + SKY_CACHE_BELOW) - SKY_CACHE_BELOW;
  float c = sqrt(max(1.0 - s * s, 0.0));
  float a = (uv.x - 0.5) * SKY_TAU;
  return vec3(cos(a) * c, -s, sin(a) * c);
}

// Light from the sky on a surface facing n whose normal points up by `up` (0 sideways, 1 straight up); linear.
// The analytic sky light only lights faces turned up; the cache also lights walls with the sky they face.
vec3 skyFill(vec3 n, float alt, float up) {
  vec3 analytic = skyLight(alt) * up;
  if ((skyCacheMode & 1) == 0 || skyCacheWeight <= 0.0) {
    return analytic;
  }
  // alpha is 0 on broken texels (sky_irradiance_frag.glsl), which have no color either
  vec4 irr = textureLod(skyIrradiance, skyCacheUv(n), 0.0);
  vec3 sky = irr.rgb / max(irr.a, 1e-3) * (SKY_CACHE_LIGHT * skyBoost(alt));
  // the cache leaves out lightning, which skyLight has through skyZenith
  sky += srgbToLinear(LIGHTNING_COLOR * (LIGHTNING_SKY * weatherOvercast.z)) * SKY_LIGHT;
  vec3 cached = sky * mix(up, 0.5 + 0.5 * up, SKY_CACHE_SIDES);
  // a broken capture must not black out the light
  if (any(isnan(cached)) || any(isinf(cached))) {
    return analytic;
  }
  return mix(analytic, max(cached, 0.0), skyCacheWeight * clamp(irr.a, 0.0, 1.0));
}

vec3 stars(vec3 dir, float pixelAngle, float time) {
  vec3 q = dir * STAR_CELLS;
  vec3 cell = floor(q);
  float h = hash13(cell);
  if (h < 1.0 - STAR_CHANCE) {
    return vec3(0.0);
  }
  vec3 center = cell + 0.25 + 0.5 * vec3(hash13(cell + 1.7), hash13(cell + 3.1), hash13(cell + 5.9));
  vec3 off = q - center;
  off -= dir * dot(off, dir);
  float size = max(pixelAngle * 0.8, STAR_SIZE) * STAR_CELLS;
  float dot2 = dot(off, off) / (size * size);
  float u = (h - (1.0 - STAR_CHANCE)) / STAR_CHANCE;
  float brightness = 0.25 + 0.75 * pow(u, STAR_FAINT);
  float t = hash13(cell + 7.3);
  float twinkle = 1.0 + STAR_TWINKLE * sin(time * (1.3 + 2.4 * t) + t * 40.0);
  vec3 tint = mix(vec3(0.78, 0.85, 1.0), vec3(1.0, 0.92, 0.80), hash13(cell + 9.1));
  return tint * (brightness * twinkle * exp(-dot2) * STAR_BRIGHTNESS);
}

// Sun and moon discs and the stars, linear, seen through the clouds' transmittance T
vec3 celestial(vec3 dir, vec3 sunDir, vec3 moonDir, float alt, float time, float pixelAngle, float T) {
  float clear = overcastClear();
  float through = pow(T, DISC_CLOUD_POWER);
  float sunVis = smoothstep(-6.0, 2.0, alt) * clear;
  float mu = dot(dir, sunDir);
  vec3 c = vec3(1.0, 0.95, 0.85) * (smoothstep(0.99985, 0.99992, mu) * SUN_DISC * sunVis * through);

  float night = smoothstep(-4.0, -14.0, alt) * clear;
  float mm = max(dot(dir, moonDir), 0.0);
  c += vec3(0.85, 0.88, 1.00) * ((smoothstep(0.99990, 0.99995, mm) * MOON_DISC * through + pow(mm, 60.0) * MOON_HALO * T)
    * night);

  // stars wait for the sky to darken, and thin out toward the horizon and in hazy, damp air
  float starVis = smoothstep(-8.0, -18.0, alt) * clear * smoothstep(0.0, STAR_HORIZON, -dir.y)
    * (1.0 - STAR_HAZE * clamp(max(skyHaze, envHaze), 0.0, 1.0)) * starBrightness * T;
  if (starVis > 0.0) {
    c += stars(dir, pixelAngle, time) * starVis;
  }
  return c;
}

// 0..1 per pixel for jittering the cloud steps: interleaved gradient noise, whose fine even pattern blends into
// the soft cloud edges where white noise would read as grain
float cloudJitter(vec2 px) {
  return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

// The procedural sky in sRGB: colors, sun glow, clouds, and the sun, moon and stars through the clouds.
// px: pixel center in target coordinates. The whole sky dome is looked up along cloudSkyDir, so its horizon lies
// at the edge of the drawn world, and the sun and moon stay where the clouds' lighting has them.
vec3 atmosphereSkySteps(vec2 px, vec3 dir, vec3 camPos, vec3 sunDir, vec3 moonDir, float alt, float time, int steps) {
  float pixelAngle = length(normalize(reconstructPosition(px + vec2(0.0, 1.0), 1.0)) - dir);
  dir = cloudSkyDir(dir);
  vec3 gradient = skyGradient(dir, alt);
  vec3 glowed = gradient + sunGlow(dir, sunDir, alt);
  vec3 lin;
  float T;
  if ((skyCacheMode & 2) != 0) {
    // explicit level: the azimuth wraps around at the back, where derivatives would pick a blurry mip
    vec4 c = textureLod(skyCache, skyCacheUv(dir), 0.0);
    T = c.a;
    // the cache has neither the sun's glow, which moves, nor lightning, which flashes
    lin = c.rgb + (srgbToLinear(max(glowed, 0.0)) - srgbToLinear(max(gradient, 0.0))) * T;
    float k = max(-dir.y, 0.0) + CLOUD_CURVE;
    float t = (CLOUD_BOTTOM + CLOUD_TOP) * 0.5 / k;
    lin += lightningCloudGlow(dir.xz * t) * (1.0 - T) * 0.5;
  } else {
    vec4 c = cloudsEnabled == 1
      ? cloudLayer(dir, camPos, sunDir, moonDir, alt, pixelAngle, cloudJitter(px), steps)
      : vec4(0.0, 0.0, 0.0, 1.0);
    T = c.a;
    lin = srgbToLinear(max(glowed, 0.0)) * T + cloudShoulder(c.rgb);
  }
  lin += celestial(dir, sunDir, moonDir, alt, time, pixelAngle, T);
  return linearToSrgb(clamp(lin, 0.0, 1.0));
}

vec3 atmosphereSky(vec2 px, vec3 dir, vec3 camPos, vec3 sunDir, vec3 moonDir, float alt, float time) {
  return atmosphereSkySteps(px, dir, camPos, sunDir, moonDir, alt, time, CLOUD_STEPS);
}
