// The procedural sky as the composite draws it (sky colors, clouds, the sun, moon and stars through them) and
// the sky cache (SkyCache.java): the sky and clouds captured every few in-game minutes into a small lat-long
// texture, blended between captures, and the light it sheds on surfaces facing each way (sky_irradiance_frag.glsl),
// so cloud cover and the sky's colors show on the ground.
// Needs frame.glsl, sky.glsl, environment.glsl, lightning.glsl and clouds.glsl.

#include "night_sky.glsl"

uniform sampler2D skyCache;  // linear: sky through the clouds (rgb), cloud transmittance (a); see skyCacheUv
uniform sampler2D skyIrradiance;  // linear: cosine weighted sky seen by a surface facing each way, same layout
uniform int skyCacheMode;    // SkyCache.MODE_LIGHT (1): the sky light comes from the cache
uniform float skyCacheWeight;  // 0..1, how much of the sky light comes from the cache rather than skyLight: eases
                               // in and out so the light never switches between the two at once
uniform float skyHaze;       // 0..1, how hazy the air is (Weather.skyHaze): the stars fade

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
// how much of the night sky thick haze hides
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
  sky += skyLightning();
  vec3 cached = sky * mix(up, 0.5 + 0.5 * up, SKY_CACHE_SIDES);
  // a broken capture must not black out the light
  if (any(isnan(cached)) || any(isinf(cached))) {
    return analytic;
  }
  return mix(analytic, max(cached, 0.0), skyCacheWeight * clamp(irr.a, 0.0, 1.0));
}

// Sun and moon discs and the night sky (night_sky.glsl), linear, seen through the clouds' transmittance T
vec3 celestial(vec3 dir, vec3 sunDir, vec3 moonDir, float alt, float time, float pixelAngle, float T) {
  float clear = overcastClear();
  float through = pow(T, DISC_CLOUD_POWER);
  float sunVis = smoothstep(-6.0, 2.0, alt) * clear;
  float mu = dot(dir, sunDir);
  vec3 c = vec3(1.0, 0.95, 0.85) * (smoothstep(0.99985, 0.99992, mu) * SUN_DISC * sunVis * through);

  float night = smoothstep(-4.0, -14.0, alt) * clear;
  float mm = max(dot(dir, moonDir), 0.0);
  // the halo goes as mm^60, multiplied out
  float m3 = mm * mm * mm;
  float m15 = m3 * m3;
  m15 *= m15 * m3;
  float m60 = m15 * m15;
  m60 *= m60;
  c += vec3(0.85, 0.88, 1.00) * ((smoothstep(0.99990, 0.99995, mm) * MOON_DISC * through + m60 * MOON_HALO * T)
    * night);

  // the stars, nebula and the rest wait for the sky to darken, and thin out in hazy, damp air
  float visible = clear * (1.0 - STAR_HAZE * clamp(max(skyHaze, envHaze), 0.0, 1.0));
  c += nightSky(dir, moonDir, alt, time, pixelAngle, visible, T);
  return c;
}

// 0..1 per pixel for jittering the cloud steps: interleaved gradient noise, whose fine even pattern blends into
// the soft cloud edges where white noise would read as grain
float cloudJitter(vec2 px) {
  return fract(52.9829189 * fract(dot(px, vec2(0.06711056, 0.00583715))));
}

#ifdef CLOUDS_HALF_RES
// The procedural sky in sRGB: colors, sun glow, clouds, and the sun, moon and stars through the clouds.
// px: pixel center in target coordinates. The whole sky dome is looked up along cloudSkyDir, so its horizon lies
// at the edge of the drawn world, and the sun and moon stay where the clouds' lighting has them. The composite's
// only: the clouds come from its half resolution cloud pass (composite_frag.glsl).
vec3 atmosphereSky(vec2 px, vec3 dir, vec3 camPos, vec3 sunDir, vec3 moonDir, float alt, float time) {
  float pixelAngle = length(normalize(reconstructPosition(px + vec2(0.0, 1.0), 1.0)) - dir);
  dir = cloudSkyDir(dir);
  vec3 glowed = skyGradient(dir, alt) + sunGlow(dir, sunDir, alt);
  vec3 lin;
  float T;
  // the half resolution clouds, filtered; px is in scene pixels
  vec4 c = cloudsEnabled == 1
    ? textureLod(cloudHalf, px / (halfRatio * vec2(textureSize(cloudHalf, 0))), 0.0)
    : vec4(0.0, 0.0, 0.0, 1.0);
  T = c.a;
  lin = srgbToLinear(max(glowed, 0.0)) * T + cloudShoulder(c.rgb);
  lin += celestial(dir, sunDir, moonDir, alt, time, pixelAngle, T);
  return linearToSrgb(clamp(lin, 0.0, 1.0));
}
#endif
