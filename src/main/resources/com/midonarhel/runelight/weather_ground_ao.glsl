// Snow and puddles, the AO pass's half (WeatherGround.java): how much cover this pixel gets, the region's ground (the
// weather field, as a difference from the player's) times how open to the sky the pixel is. Written to the shadow
// target's b at half resolution (ground_cover.glsl); it is smooth, so the composite's upsample carries it well and
// adds the detail (weather_ground.glsl). Needs frame.glsl.

#include "ground_cover.glsl"

uniform int weatherGroundOn;
// top-down depth of the scenery around the player, every roof included (Weather.java); bilinear compare
uniform sampler2DShadow occlusionCompare;
uniform mat4 occlusionProjRel;     // camera-relative world -> occlusion clip
uniform float occlusionDepthRange;
// the weather field's ground: r wet, g snow cover, one bilinear texel per chunk
uniform sampler2D weatherGround;
uniform vec4 groundFieldRect;      // xy: camera-relative xz of the field's corner, zw: one over its size
uniform vec2 weatherGroundFocus;   // the field's ground at the player
uniform vec2 weatherGroundLocal;   // the player's own wet and snow cover
uniform float weatherFieldOn;

// world units a surface may sit below its column's top and still count as open, for the surface itself
#define GROUND_EXPOSURE_BIAS 40.0
// world units between the exposure taps: shelter edges fade over about a tile and a half
#define GROUND_EXPOSURE_STEP 64.0
// offset along the normal, so walls right below an edge aren't covered by themselves
#define GROUND_NORMAL_OFFSET 12.0

// 1 under the open sky, 0 sheltered, smooth between: bilinear compares in a 3x3 tent. Past the map nothing is known
// to cover a point: it counts as open.
float groundExposure(vec3 p) {
  float sum = 0.0;
  for (int x = -1; x <= 1; ++x) {
    for (int z = -1; z <= 1; ++z) {
      vec4 c = occlusionProjRel * vec4(p + vec3(float(x), 0.0, float(z)) * GROUND_EXPOSURE_STEP, 1.0);
      vec2 uv = c.xy * 0.5 + 0.5;
      float depth = depthZeroToOne == 1 ? c.z : c.z * 0.5 + 0.5;
      float e = 1.0;
      if (all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)))) {
        e = texture(occlusionCompare, vec3(uv, depth - GROUND_EXPOSURE_BIAS / (2.0 * occlusionDepthRange)));
      }
      sum += (x == 0 ? 2.0 : 1.0) * (z == 0 ? 2.0 : 1.0) * e;
    }
  }
  return sum / 16.0;
}

// snow cover that keeps the puddles away: they are gone once this much has settled
#define GROUND_SNOW_OVER_WATER 0.125

// the cover at a camera-relative point times its exposure (ground_cover.glsl): snow, else water; 0 when off
float weatherGroundAo(vec3 p, vec3 n) {
  if (weatherGroundOn == 0) {
    return 0.0;
  }
  vec2 g = weatherGroundLocal;
  if (weatherFieldOn != 0.0) {
    g += textureLod(weatherGround, (p.xz - groundFieldRect.xy) * groundFieldRect.zw, 0.0).rg - weatherGroundFocus;
  }
  g = clamp(g, 0.0, 1.0);
  // puddles freeze and disappear under the snow that settles on them
  float water = g.x * (1.0 - min(g.y / GROUND_SNOW_OVER_WATER, 1.0));
  float cover = g.y > water ? g.y : -water;
  if (cover == 0.0) {
    return 0.0;
  }
  return cover * groundExposure(p + n * GROUND_NORMAL_OFFSET);
}
