// Wet ground on the surfaces themselves (WeatherGround.java), applied to the albedo before the composite lights it.
// The AO pass leaves how wet each pixel gets (weather_ground_ao.glsl). Snow cover on the ground was removed: the
// falling snow stays, the ground doesn't turn white.

uniform int weatherGroundOn;
uniform vec2 weatherNoiseOrigin;  // unused since the snow cover went; kept so the uniform location stays valid

// the AO pass's wetness, upsampled with the AO (composite_frag.glsl's upsampleAoFog)
float wetUpsampled = 0.0;

#define WET_DARKEN 0.80        // albedo multiplier on fully wet surfaces
#define WET_SHEEN 0.10         // sky reflection on wet upward surfaces

// Wetness on a surface before lighting: darkens the albedo (sRGB). alpha: the transparency layer over it.
// Returns the wetness, for weatherWetSheen.
float weatherGroundSurface(inout vec3 color, inout vec3 n, vec3 world, float emissive, float waterBed, float alpha) {
  if (weatherGroundOn == 0) {
    return 0.0;
  }
  float g = wetUpsampled * ((1.0 - emissive) * (1.0 - step(0.75, waterBed)) * (1.0 - alpha));
  if (g <= 0.002) {
    return 0.0;
  }
  float up = max(-n.y, 0.0);
  float wet = g * mix(0.6, 1.0, up);
  color *= mix(1.0, WET_DARKEN, wet);
  return wet;
}

// Wet surfaces reflect a little sky, upward ones most and more at grazing angles. p camera-relative.
vec3 weatherWetSheen(vec3 lit, vec3 p, vec3 n, float wet, float ao, vec3 sky) {
  if (wet <= 0.0) {
    return lit;
  }
  float fresnel = 1.0 - max(dot(-normalize(p), n), 0.0);
  fresnel *= fresnel;
  fresnel *= fresnel;
  return lit + sky * (WET_SHEEN * max(-n.y, 0.0) * (0.3 + 0.7 * fresnel) * wet * ao);
}
