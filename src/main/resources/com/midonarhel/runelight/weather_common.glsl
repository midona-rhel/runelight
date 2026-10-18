// Shared by the rain, snow and weather surface passes. Include after frame.glsl and sky.glsl.

#include cluster_config
// World space is game units (128 per tile), y points down; positions here are camera-relative unless named world.

// Top-down depth of all static scenery around the player, every roof included (Weather.java). Window depth grows
// downward: depth = (worldY - occlusionCenterY + occlusionDepthRange) / (2 occlusionDepthRange). Cleared to 1.
uniform sampler2D occlusionMap;
uniform sampler2DShadow occlusionCompare;  // same map, bilinear compare: smooth edges
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

// The weather over the whole scene (WeatherField.java), one bilinear texel per chunk: r cloud cover, g rain,
// b snow, a fog / 4; the ground: r wet, g snow cover. Used as differences from its value at the player, which
// are added to the player's own weather; all zero when the field is off.
uniform sampler2D weatherField;
uniform sampler2D weatherGround;
uniform vec4 weatherFieldRect;     // xy: world xz of the field's corner, zw: one over its size
uniform vec4 weatherFieldFocus;    // the field at the player, fog unscaled
uniform vec2 weatherGroundFocus;
uniform float weatherFieldOn;

vec2 weatherFieldUv(vec2 worldXZ) {
  return (worldXZ - weatherFieldRect.xy) * weatherFieldRect.zw;
}

// cover, rain, snow, fog here minus at the player
vec4 weatherFieldDelta(vec2 worldXZ) {
  if (weatherFieldOn == 0.0) {
    return vec4(0.0);
  }
  return (textureLod(weatherField, weatherFieldUv(worldXZ), 0.0) * vec4(1.0, 1.0, 1.0, 4.0) - weatherFieldFocus);
}

// wet, snow cover here minus at the player
vec2 weatherGroundDelta(vec2 worldXZ) {
  if (weatherFieldOn == 0.0) {
    return vec2(0.0);
  }
  return textureLod(weatherGround, weatherFieldUv(worldXZ), 0.0).rg - weatherGroundFocus;
}

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

// point light on particles counts this much more than on surfaces: drops are bright where light catches them
#define WEATHER_PARTICLE_POINT_BOOST 2.0
// world units between the smooth exposure taps
#define SMOOTH_EXPOSURE_STEP 64.0

// Like skyExposure, but filtered: bilinear compares in a 3x3 tent, so shelter edges fade over about a tile and a
// half instead of following the map's texels
float smoothExposure(vec3 p, float bias) {
  float sum = 0.0;
  for (int x = -1; x <= 1; ++x) {
    for (int z = -1; z <= 1; ++z) {
      vec2 uv;
      float depth;
      float e = 1.0;
      if (occlusionLookup(p + vec3(float(x), 0.0, float(z)) * SMOOTH_EXPOSURE_STEP, uv, depth)) {
        e = texture(occlusionCompare, vec3(uv, depth - bias / (2.0 * occlusionDepthRange)));
      }
      sum += (x == 0 ? 2.0 : 1.0) * (z == 0 ? 2.0 : 1.0) * e;
    }
  }
  return sum / 16.0;
}

// Light falling on weather: sky ambient plus a bit of the sun or moon, linear like the composite
vec3 weatherLight() {
  vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
  return ambientLight(sunAltitude) + skyLight(sunAltitude) + caster * 0.35;
}

// Particle color: the weather's light plus point lights, with the composite's soft shoulder instead of a hard
// clip, so lights still show on drops by day
vec3 weatherParticleColor(vec3 srgb, vec3 points) {
  return shade(srgb, weatherLight() + points * WEATHER_PARTICLE_POINT_BOOST);
}

// Point lights, culled into clusters on the CPU (LightClusters.java); same tables and lookup as the composite
uniform int pointLightsEnabled;
uniform samplerBuffer lightData;     // per light: (camera-relative position, radius), (color * strength, 0)
uniform usamplerBuffer clusterData;  // per cluster: count, then light indices

// particles are lit at their center, once per vertex; global trim against the surface lighting
#define WEATHER_POINT_LIGHT_SCALE 1.0
// extra light seen through a particle when looking toward the light, at most this many times the plain light
#define WEATHER_FORWARD_SCATTER 2.0
#define WEATHER_FORWARD_POWER 6.0
// Particles past this view depth (world units) skip the point lights, fading out over the last quarter: there a
// streak is under a pixel wide and mostly faded, and every one of its six vertices would loop over the lights.
#define WEATHER_PARTICLE_LIGHT_FAR 5120.0

// Light from the point lights around a camera-relative point. clip: the point's clip position (w is view depth),
// for the cluster lookup. n: surface normal, or zero for particles, which take light from every side and scatter
// it forward toward the camera.
vec3 weatherPointLights(vec3 p, vec4 clip, vec3 n) {
  if (pointLightsEnabled == 0 || clip.w <= 0.0) {
    return vec3(0.0);
  }
  vec2 rel = clip.xy / clip.w * 0.5 + 0.5;
  if (any(lessThan(rel, vec2(0.0))) || any(greaterThan(rel, vec2(1.0)))) {
    // off screen: its cluster isn't the one the lights were culled into
    return vec3(0.0);
  }
  float z = clip.w;
  int cx = clamp(int(rel.x * float(CLUSTERS_X)), 0, CLUSTERS_X - 1);
  int cy = clamp(int(rel.y * float(CLUSTERS_Y)), 0, CLUSTERS_Y - 1);
  int cz = z <= CLUSTER_NEAR ? 0
    : clamp(int(floor(log(z / CLUSTER_NEAR) / log(CLUSTER_FAR / CLUSTER_NEAR) * float(CLUSTERS_Z))), 0, CLUSTERS_Z - 1);
  int base = ((cz * CLUSTERS_Y + cy) * CLUSTERS_X + cx) * CLUSTER_STRIDE;

  bool particle = dot(n, n) < 0.5;
  float reach = particle ? 1.0 - smoothstep(WEATHER_PARTICLE_LIGHT_FAR * 0.75, WEATHER_PARTICLE_LIGHT_FAR, z) : 1.0;
  if (reach <= 0.0) {
    return vec3(0.0);
  }
  vec3 view = normalize(p);
  vec3 sum = vec3(0.0);
  int count = int(texelFetch(clusterData, base).r);
  for (int i = 0; i < count; ++i) {
    int li = int(texelFetch(clusterData, base + 1 + i).r);
    vec4 posRadius = texelFetch(lightData, li * 2);
    vec3 l = posRadius.xyz - p;
    float d2 = dot(l, l);
    float r2 = posRadius.w * posRadius.w;
    if (d2 >= r2) {
      continue;
    }
    // 117 HD's falloff, as in the composite
    float att = 1.0 - sqrt(d2 / r2);
    att *= att;
    vec3 ld = l * inversesqrt(max(d2, 1.0));
    // drops between the camera and a light glow against it
    float k = particle ? 1.0 + WEATHER_FORWARD_SCATTER * pow(max(dot(ld, view), 0.0), WEATHER_FORWARD_POWER)
      : max(dot(n, ld), 0.0);
    sum += texelFetch(lightData, li * 2 + 1).rgb * att * k;
  }
  return sum * (WEATHER_POINT_LIGHT_SCALE * reach);
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
