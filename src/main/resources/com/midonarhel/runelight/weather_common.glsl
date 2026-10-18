// Shared by the rain, snow and weather surface passes. Include after frame.glsl and sky.glsl.
// World space is game units (128 per tile), y points down; positions here are camera-relative unless named world.

// Top-down depth of all static scenery around the player, every roof included (Weather.java). Window depth grows
// downward: depth = (worldY - occlusionCenterY + occlusionDepthRange) / (2 occlusionDepthRange). Cleared to 1.
uniform sampler2D occlusionMap;
uniform mat4 occlusionProjRel;     // camera-relative world -> occlusion clip
uniform float occlusionCenterY;
uniform float occlusionDepthRange;

uniform vec3 cameraPos;            // world
uniform vec3 focusPos;             // player ground position, world
uniform float time;                // seconds, wraps every 10 minutes
uniform float sunAltitude;
uniform vec3 casterDir;
uniform int sunIsCaster;
uniform float amount;              // 0-1: fade in for particles, buildup for surfaces

// Occlusion map texel and depth of a camera-relative point. Returns false outside the map.
bool occlusionLookup(vec3 p, out vec2 uv, out float depth) {
  vec4 c = occlusionProjRel * vec4(p, 1.0);
  uv = c.xy * 0.5 + 0.5;
  depth = depthZeroToOne == 1 ? c.z : c.z * 0.5 + 0.5;
  return all(greaterThanEqual(uv, vec2(0.0))) && all(lessThanEqual(uv, vec2(1.0)));
}

// World y (down) of the highest scenery above or below this point's column; +big if there is none
float occluderTop(vec3 p) {
  vec2 uv;
  float depth;
  if (!occlusionLookup(p, uv, depth)) {
    return 1e9;
  }
  float d = textureLod(occlusionMap, uv, 0.0).r;
  if (d >= 1.0) {
    return 1e9;
  }
  return (d * 2.0 - 1.0) * occlusionDepthRange + occlusionCenterY;
}

// 1 where nothing is above the point (it is under open sky), 0 when something covers it.
// bias: world units a point may sit below the top surface and still count, for the surface itself.
float skyExposure(vec3 p, float bias) {
  return p.y + cameraPos.y <= occluderTop(p) + bias ? 1.0 : 0.0;
}

// Light falling on weather: sky ambient plus a bit of the sun or moon, linear like the composite
vec3 weatherLight() {
  vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
  return ambientLight(sunAltitude) + skyLight(sunAltitude) + caster * 0.35;
}

// Hashes for per-particle randomness from gl_InstanceID, no texture needed
float hash11(float p) {
  p = fract(p * 0.1031);
  p *= p + 33.33;
  p *= p + p;
  return fract(p);
}

vec3 hash31(float p) {
  vec3 p3 = fract(vec3(p) * vec3(0.1031, 0.1030, 0.0973));
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.xxy + p3.yzz) * p3.zyx);
}

// Keep a world position inside a box of the given size around center, wrapping so particles stay put in the
// world while the box follows the player
vec3 wrapAround(vec3 world, vec3 center, vec3 size) {
  vec3 lo = center - size * 0.5;
  return lo + mod(world - lo, size);
}

// View depth of the opaque scene under a pixel, very large for sky
float sceneViewDepth(vec2 fragCoord) {
  float d = texelFetch(sceneDepth, ivec2(fragCoord), 0).r;
  return isSky(d) ? 1e9 : viewDepth(d);
}

// Clip position that the rasterizer discards, for particles that should not draw
const vec4 CULLED = vec4(2.0, 2.0, 2.0, 1.0);
