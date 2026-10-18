// Per-area ambience from EnvironmentManager.java. Neutral values (multipliers 1, no fog, no haze) give
// RuneLight's normal look; areas tint the light, add distance fog in their own color and haze over the sky.
// Needs sky.glsl.

uniform vec3 envAmbient;   // multiplies ambient and sky fill (linear)
uniform vec3 envSun;       // multiplies the sun or moon, and the cave key light (linear)
uniform vec4 envFogColor;  // sRGB, a: how much it replaces the sky-colored fog
uniform float envFogDepth; // 117 HD-style fog density, already scaled for the draw distance; 0 is off
uniform float envHaze;     // share of the sky hidden behind fog
uniform vec3 envSkyTint;   // sRGB multiplier on the procedural sky
uniform float envDrawDistance; // game units
uniform int envCave;       // 1 underground by area (not merely with sun lighting turned off)

// how much of the horizon's own color an area's fog picks up outdoors, so sunsets and nights still show in it
#define ENV_FOG_SKY_SHARE 0.35
// brightest horizon color of the day in sky.glsl, for scaling area fog with daylight
#define ENV_DAY_HORIZON vec3(0.576, 0.682, 0.796)
// haze is thickest at the horizon and thins toward the zenith by this much
#define ENV_HAZE_ZENITH 0.55

float envLuma(vec3 c) {
  return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// The fog color for this frame. skyFog is what fog would be without areas (the horizon or the client sky).
vec3 envFog(vec3 skyFog, bool outdoors, float alt) {
  if (envFogColor.a <= 0.0)
    return skyFog;
  vec3 c = envFogColor.rgb;
  if (outdoors) {
    vec3 horizon = skyHorizon(alt);
    float day = clamp(envLuma(horizon) / envLuma(ENV_DAY_HORIZON), 0.03, 1.0);
    c = mix(c * day, horizon, ENV_FOG_SKY_SHARE);
  }
  return mix(skyFog, c, envFogColor.a);
}

// 117 HD's distance fog (utils/fog.glsl), exponential from a start distance that moves closer as fog thickens.
// rel: camera-relative position.
float envFogAmount(vec3 rel) {
  if (envFogDepth <= 0.0)
    return 0.0;
  float dd = min(envDrawDistance, 90.0 * 128.0);
  float start = (0.3 - clamp(envFogDepth, 0.0, 1000.0) / 1000.0 * 0.3) * dd;
  // halve vertical distance, as 117 HD does, so looking down from high up does not fog the ground
  float dist = length(vec3(rel.x, rel.y * 0.5, rel.z));
  float d = max(dist - start, 0.0) / max(dd - start, 1.0);
  return 1.0 - clamp(exp(-d * envFogDepth / 100.0), 0.0, 1.0);
}

// Procedural sky with the area's tint and haze. elev: view ray elevation, -dir.y.
vec3 envSky(vec3 sky, vec3 fog, float elev) {
  sky *= envSkyTint;
  float h = envHaze * mix(1.0, ENV_HAZE_ZENITH, clamp(elev, 0.0, 1.0));
  return mix(sky, fog, h);
}

// sky.glsl's cave light with the area's tint: ambient part and key light part
vec3 envCaveLight(vec3 n) {
  return vec3(CAVE_AMBIENT) * envAmbient + vec3(CAVE_KEY) * envSun * max(dot(n, CAVE_KEY_DIR), 0.0);
}
