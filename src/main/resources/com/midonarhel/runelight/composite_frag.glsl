#version 330

// Runs once per pixel over the finished scene: sky, lighting, shadows, AO and fog.

#include "frame.glsl"
#include "sky.glsl"
#include "materials.glsl"
#include "environment.glsl"
#include cluster_config
#include "fog_apply.glsl"
#include "lightning.glsl"
#include "clouds.glsl"
#include "atmosphere.glsl"
#include "edge_fade.glsl"

#define DEBUG_FINAL 0
#define DEBUG_DEPTH 1
#define DEBUG_NORMALS 2
#define DEBUG_AO 3
#define DEBUG_SHADOWS 4
#define DEBUG_LIGHTING 5
#define DEBUG_LIGHT_CLUSTERS 6
#define DEBUG_MATERIALS 7

#define TAU 6.28318530718

// how much AO also darkens direct sun and point light; physically 0, but contact shading reads better with some
#define AO_DIRECT 1.0


#define TILE_SIZE 128.0
// cloud steps for the sky seen through the edge fade: it is mostly hidden by the scenery in front
#define EDGE_SKY_STEPS (CLOUD_STEPS / 2)

uniform sampler2D sceneAlpha;  // transparent geometry, already lit, premultiplied
uniform sampler2D sceneMaterial;  // r emissive, g transmissive, b water (frag.glsl)
uniform int debugView;
uniform int aoEnabled;
uniform float aoStrength;

uniform vec3 cameraPos;     // world position of the camera
uniform float time;         // seconds, for twinkling

// lighting
uniform int outdoors;       // 0 underground: no sun, no sky
uniform int proceduralSky;  // 0 where the game draws its own skybox
uniform int lightingEnabled;
uniform vec3 sunDir;        // visual positions, toward the light
uniform vec3 moonDir;
uniform vec3 casterDir;     // the light that casts shadows, elevation clamped
uniform int sunIsCaster;
uniform float sunAltitude;  // degrees

// shadows
uniform int shadowsEnabled;
uniform sampler2D shadowRaw;
uniform sampler2DShadow shadowCompare;
uniform mat4 lightProjRel;  // camera-relative world -> light clip
uniform float shadowRadius;      // half extent of the shadow map, world units
uniform float shadowDepthRange;
uniform float shadowMapSize;
uniform int shadowQuality;  // 0 low, 1 medium, 2 high (Performance)

// fog, same parameters as the vanilla GPU plugin
uniform int useFog;
uniform int fogDepth;       // tiles
uniform int drawDistance;   // world units
uniform int expandedMapLoadingChunks;
uniform vec3 clientSkyColor;

// point lights, culled into clusters on the CPU (LightClusters.java)
uniform int pointLightsEnabled;
uniform samplerBuffer lightData;     // per light: (camera-relative position, radius), (color * strength, 0)
uniform usamplerBuffer clusterData;  // per cluster: count, then light indices

out vec4 FragColor;

// ---------------------------------------------------------------------------------------------------------------
// Ambient occlusion: 4x4 depth- and normal-aware upsample of the half resolution AO, which also blurs its noise.
// The volumetric fog's upsample uses the same taps, so it is done in the same loop when the fog is on.

float upsampleAoFog(ivec2 px, float z, vec3 n, out vec4 fog) {
  vec2 hp = (vec2(px) + 0.5) * 0.5 - 0.5;
  ivec2 base = ivec2(floor(hp)) - 1;
  ivec2 maxAo = textureSize(sceneAo, 0) - 1;
  bool withFog = volumetricFog == 1;
  float sum = 0.0;
  float wsum = 0.0;
  vec4 fogSum = vec4(0.0);
  float fogWsum = 0.0;
  for (int y = 0; y < 4; ++y) {
    for (int x = 0; x < 4; ++x) {
      ivec2 q = clamp(base + ivec2(x, y), ivec2(0), maxAo);
      vec4 g = texelFetch(sceneAo, q, 0);
      float qz = halfViewDepth(g);
      float spatial = upsampleSpatial(vec2(q) - hp);
      if (withFog) {
        float fw = spatial * fogDepthWeight(qz, z);
        fogSum += texelFetch(sceneFog, q, 0) * fw;
        fogWsum += fw;
      }
      if (g.g <= 0.0) {
        continue;
      }
      vec3 qn = octDecode(g.ba);
      float w = spatial / (1.0 + abs(qz - z) / (z * 0.01 + 1.0));
      float nd = max(dot(n, qn), 0.0);
      nd *= nd;
      nd *= nd;
      w *= nd * nd;

      sum += g.r * w;
      wsum += w;
    }
  }
  fog = !withFog ? vec4(0.0, 0.0, 0.0, 1.0)
    : fogWsum > 1e-6 ? fogSum / fogWsum : texelFetch(sceneFog, clamp(ivec2(hp + 0.5), ivec2(0), maxAo), 0);
  return wsum > 1e-4 ? sum / wsum : 1.0;
}

// ---------------------------------------------------------------------------------------------------------------
// Shadows: PCSS. Blocker search for the average occluder depth, then a PCF kernel sized by the distance between
// occluder and receiver, so contact shadows are sharp and long ones soften.

const vec2 POISSON[16] = vec2[](
  vec2(-0.94201624, -0.39906216), vec2(0.94558609, -0.76890725),
  vec2(-0.09418410, -0.92938870), vec2(0.34495938, 0.29387760),
  vec2(-0.91588581, 0.45771432), vec2(-0.81544232, -0.87912464),
  vec2(-0.38277543, 0.27676845), vec2(0.97484398, 0.75648379),
  vec2(0.44323325, -0.97511554), vec2(0.53742981, -0.47373420),
  vec2(-0.26496911, -0.41893023), vec2(0.79197514, 0.19090188),
  vec2(-0.24188840, 0.99706507), vec2(-0.81409955, 0.91437590),
  vec2(0.19984126, 0.78641367), vec2(0.14383161, -0.14100790));

// world units of penumbra per world unit between occluder and receiver: the light's apparent size
#define SUN_SOFTNESS 0.08
#define MOON_SOFTNESS 0.14
#define BLOCKER_SEARCH_WORLD 160.0
#define MAX_PENUMBRA_WORLD 112.0

float ign(vec2 p) {
  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

float shadowFactor(vec3 p, vec3 n, float noise) {
  float texelWorld = 2.0 * shadowRadius / shadowMapSize;
  // normal offset keeps surfaces from shadowing themselves
  vec4 lc = lightProjRel * vec4(p + n * texelWorld * 1.5, 1.0);
  vec2 uv = lc.xy * 0.5 + 0.5;
  float z = depthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || z >= 1.0) {
    return 1.0;
  }
  float edgeFade = smoothstep(0.0, 0.08, min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y)));

  float a = noise * TAU;
  mat2 rot = mat2(cos(a), sin(a), -sin(a), cos(a));
  float worldToUv = 1.0 / (2.0 * shadowRadius);
  float bias = 2.0 * texelWorld / (2.0 * shadowDepthRange);

  int searchTaps = shadowQuality == 2 ? 12 : shadowQuality == 1 ? 8 : 6;
  int filterTaps = shadowQuality == 2 ? 16 : shadowQuality == 1 ? 12 : 8;
  float blockerSum = 0.0;
  float blockers = 0.0;
  for (int i = 0; i < searchTaps; ++i) {
    float d = texture(shadowRaw, uv + rot * POISSON[i] * (BLOCKER_SEARCH_WORLD * worldToUv)).r;
    if (d < z - bias) {
      blockerSum += d;
      blockers += 1.0;
    }
  }
  if (blockers == 0.0) {
    return 1.0;
  }
  // below high quality, a search that only finds blockers is taken as full shadow; on high the filter still runs
  // for the small gaps (leaves) the search may have missed
  if (shadowQuality < 2 && blockers == float(searchTaps)) {
    return mix(1.0, 0.0, edgeFade);
  }

  float gapWorld = (z - blockerSum / blockers) * 2.0 * shadowDepthRange;
  float softness = sunIsCaster == 1 ? SUN_SOFTNESS : MOON_SOFTNESS;
  float penumbraWorld = clamp(gapWorld * softness, texelWorld, MAX_PENUMBRA_WORLD);
  float penumbra = penumbraWorld * worldToUv;
  // a kernel within a texel or two of hardware filtered compares needs no more than half the taps
  if (penumbraWorld < texelWorld * 2.0) {
    filterTaps = max(filterTaps / 2, 4);
  }

  float lit = 0.0;
  for (int i = 0; i < filterTaps; ++i) {
    lit += texture(shadowCompare, vec3(uv + rot * POISSON[i] * penumbra, z - bias));
  }
  return mix(1.0, lit / float(filterTaps), edgeFade);
}

// ---------------------------------------------------------------------------------------------------------------
// Distance fog by the game's fog depth setting, from the same edge as the edge fade, as in vert.glsl

float fogAmount(vec3 world) {
  if (useFog == 0) {
    return 0.0;
  }
  return 1.0 - clamp(edgeDistance(world) / (float(fogDepth) * TILE_SIZE), 0.0, 1.0);
}

// The sky this pixel would show if nothing stood in front of it, in sRGB: what the world melts into at its edge.
// steps: cloud march steps, fewer behind the scenery, where it shows only through the fade.
vec3 skyBehind(vec2 px, vec3 dir, vec3 fogColor, bool sunAndSky, int steps) {
  if (sunAndSky && proceduralSky == 1) {
    vec3 sky = atmosphereSkySteps(px, dir, cameraPos, sunDir, moonDir, sunAltitude, time, steps);
    sky = lightningOverSky(sky, dir, cameraPos);
    return envSky(sky, fogColor, -dir.y);
  }
  // what the sky branch of main() leaves there: the area's fog color underground, else the game's clear color
  // (with the game's own skybox, the color its fog has)
  return envCave == 1 && proceduralSky == 1 ? fogColor : clientSkyColor;
}

// ---------------------------------------------------------------------------------------------------------------
// Point lights: only the lights listed in this pixel's cluster are evaluated

// lights.json colors are linear and strengths are 117 HD's, used as they are; this is only a global trim
#define POINT_LIGHT_SCALE 1.0

int clusterBase(ivec2 px, float z) {
  vec2 rel = (vec2(px) + 0.5 - vec2(sceneViewport.xy)) / vec2(sceneViewport.zw);
  int cx = clamp(int(rel.x * float(CLUSTERS_X)), 0, CLUSTERS_X - 1);
  int cy = clamp(int(rel.y * float(CLUSTERS_Y)), 0, CLUSTERS_Y - 1);
  int cz = z <= CLUSTER_NEAR ? 0
    : clamp(int(floor(log(z / CLUSTER_NEAR) / log(CLUSTER_FAR / CLUSTER_NEAR) * float(CLUSTERS_Z))), 0, CLUSTERS_Z - 1);
  return ((cz * CLUSTERS_Y + cy) * CLUSTERS_X + cx) * CLUSTER_STRIDE;
}

int clusterLightCount(int base) {
  return int(texelFetch(clusterData, base).r);
}

// returns the light on the front; light through thin foliage from behind goes to throughOut
vec3 pointLights(int base, vec3 p, vec3 n, float transmissive, out vec3 throughOut) {
  vec3 viewDir = normalize(-p);
  vec3 through = vec3(0.0);
  vec3 sum = vec3(0.0);
  int count = clusterLightCount(base);
  for (int i = 0; i < count; ++i) {
    int li = int(texelFetch(clusterData, base + 1 + i).r);
    vec4 posRadius = texelFetch(lightData, li * 2);
    vec3 color = texelFetch(lightData, li * 2 + 1).rgb;

    vec3 l = posRadius.xyz - p;
    float d2 = dot(l, l);
    float r2 = posRadius.w * posRadius.w;
    if (d2 >= r2) {
      continue;
    }
    // 117 HD's falloff; we light in linear space like it does
    float att = 1.0 - sqrt(d2 / r2);
    att *= att;
    vec3 ld = l * inversesqrt(max(d2, 1.0));
    float ndl = dot(n, ld);
    sum += color * att * max(ndl, 0.0) * POINT_LIGHT_SCALE;
    // thin foliage is also lit from behind: magic tree canopies glow around the lights inside them
    through += color * att * transmission(ndl, dot(-ld, viewDir), transmissive) * POINT_LIGHT_SCALE;
  }
  throughOut = through;
  return sum;
}

// ---------------------------------------------------------------------------------------------------------------

// Transparent surfaces were lit as they were drawn, into their own layer; put them over the lit scene
vec4 overTransparent(ivec2 px, vec4 base) {
  vec4 a = texelFetch(sceneAlpha, px, 0);
  return vec4(a.rgb + base.rgb * (1.0 - a.a), base.a);
}

// ---------------------------------------------------------------------------------------------------------------

#include "water.glsl"

// ---------------------------------------------------------------------------------------------------------------

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  vec4 color = texelFetch(sceneColor, px, 0);
  float d = texelFetch(sceneDepth, px, 0).r;
  bool sky = isSky(d);
  bool inside = inViewport(px);
  bool sunAndSky = outdoors == 1;
  vec3 fogColor = envFog(sunAndSky && proceduralSky == 1 ? skyHorizon(sunAltitude) : clientSkyColor, sunAndSky,
    sunAltitude);

  if (debugView == DEBUG_DEPTH) {
    // log scale from the near plane out to ~100 tiles
    float v = sky ? 1.0 : clamp(log(viewDepth(d) / NEAR) / log(12800.0 / NEAR), 0.0, 1.0);
    FragColor = vec4(vec3(v), 1.0);
    return;
  }

  if (debugView == DEBUG_NORMALS) {
    FragColor = vec4(texelFetch(sceneNormal, px, 0).rgb, 1.0);
    return;
  }

  if (sky) {
    if (inside && sunAndSky && proceduralSky == 1) {
      // a point on the near plane gives the view ray; the camera is at the origin of camera-relative space
      vec3 dir = normalize(reconstructPosition(vec2(px) + 0.5, 1.0));
      color.rgb = skyBehind(vec2(px) + 0.5, dir, fogColor, true, CLOUD_STEPS);
    } else if (inside && envCave == 1 && proceduralSky == 1) {
      // underground the void behind the scene takes the area's fog color, so fogged walls fade into it
      color.rgb = fogColor;
    }
    FragColor = overTransparent(px, color);
    FragColor.rgb = applyVolumetricFog(px, FragColor.rgb, d);
    return;
  }

  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  vec3 material = texelFetch(sceneMaterial, px, 0).rgb;
  float emissive = material.r;
  float transmissive = material.g;

  float ao = 1.0;
  vec4 fog;
  bool fogDone = false;
  if (aoEnabled == 1 && inside) {
    ao = upsampleAoFog(px, viewDepth(d), n, fog);
    ao = clamp(1.0 - (1.0 - ao) * aoStrength, 0.0, 1.0);
    fogDone = volumetricFog == 1;
  }

  float shadow = 1.0;
  vec3 light = vec3(ao);
  if (sunAndSky && lightingEnabled == 1 && inside) {
    float ndl = dot(n, casterDir);
    // faces turned away from the light get none of it, so their shadow only matters to water and the debug view
    bool needShadow = ndl > 0.0 || transmissive > 0.0 || material.b >= 0.25 || debugView == DEBUG_SHADOWS;
    if (shadowsEnabled == 1 && emissive < 1.0 && needShadow) {
      // a leaf lit from behind looks up its shadow on the lit side, or it would shadow itself
      shadow = shadowFactor(p, ndl < 0.0 && transmissive > 0.0 ? -n : n, ign(gl_FragCoord.xy));
    }
    if (needShadow) {
      shadow *= cloudShadow(p + cameraPos, casterDir);
    }
    shadow = overcastShadow(shadow);
    vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
    float up = max(-n.y, 0.0);
    light = (ambientLight(sunAltitude) + skyFill(n, sunAltitude, up)) * envAmbient * ao
      + caster * envSun * max(ndl, 0.0) * shadow * mix(1.0, ao, AO_DIRECT);
    if (transmissive > 0.0) {
      // light through the leaf isn't occluded by the creases SSAO sees on its front
      light += caster * envSun * transmission(ndl, dot(-casterDir, normalize(-p)), transmissive) * shadow;
    }
  } else if (lightingEnabled == 1 && inside) {
    // Underground: no sun, and the game's own baked shading is removed, so a fixed soft key light from above
    // keeps shapes readable. Point lights do the rest.
    light = envCaveLight(n) * ao;
  }

  int cluster = clusterBase(px, viewDepth(d));
  if (pointLightsEnabled == 1 && inside) {
    vec3 through;
    light += pointLights(cluster, p, n, transmissive, through) * mix(1.0, ao, AO_DIRECT);
    // the canopy interior SSAO darkens is exactly where light inside a tree shines through
    light += through;
  }
  // emissive surfaces show their own color: no shading, shadows or AO, but fog below still applies
  light = emissiveLight(light, emissive);

  if (debugView == DEBUG_LIGHT_CLUSTERS) {
    // lights evaluated per pixel: blue 0, green ~4, red 8+
    float c = pointLightsEnabled == 1 ? float(clusterLightCount(cluster)) / 8.0 : 0.0;
    FragColor = vec4(clamp(vec3(c * 2.0 - 1.0, 1.0 - abs(c * 2.0 - 1.0), 1.0 - c * 2.0), 0.0, 1.0), 1.0);
    return;
  }

  if (debugView == DEBUG_MATERIALS) {
    // emissive red, transmissive green, over the dimmed scene
    vec3 tint = vec3(emissive, transmissive, 0.0);
    FragColor = vec4(mix(color.rgb * 0.35, tint, max(emissive, transmissive) * 0.8), 1.0);
    return;
  }

  if (debugView == DEBUG_AO) {
    FragColor = vec4(vec3(ao), 1.0);
    return;
  }
  if (debugView == DEBUG_SHADOWS) {
    FragColor = vec4(vec3(shadow), 1.0);
    return;
  }
  if (debugView == DEBUG_LIGHTING) {
    FragColor = vec4(linearToSrgb(clamp(light * 0.35, 0.0, 1.0)), 1.0);
    return;
  }

  vec3 lit = shade(color.rgb, light);
  lit = waterOver(px, p, n, color.rgb, light, shadow, lit, material.b, fogColor);
  lit = mix(lit, fogColor, max(fogAmount(p + cameraPos), envFogAmount(p)));
  // the world fades into the sky behind it toward the draw distance, so where the culling stops never shows
  float edge = inside ? edgeFadeAmount(p + cameraPos) : 0.0;
  if (edge > 0.002) {
    lit = mix(lit, skyBehind(vec2(px) + 0.5, normalize(p), fogColor, sunAndSky, EDGE_SKY_STEPS), edge);
  }
  FragColor = overTransparent(px, vec4(lit, color.a));
  FragColor.rgb = fogDone ? applyFogSample(FragColor.rgb, fog) : applyVolumetricFog(px, FragColor.rgb, d);
}
