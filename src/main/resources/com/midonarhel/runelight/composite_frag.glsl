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
#include "weather_ground.glsl"

#define DEBUG_FINAL 0
#define DEBUG_DEPTH 1
#define DEBUG_NORMALS 2
#define DEBUG_AO 3
#define DEBUG_SHADOWS 4
#define DEBUG_LIGHTING 5
#define DEBUG_LIGHT_CLUSTERS 6
#define DEBUG_MATERIALS 7
#define DEBUG_GI 8

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
uniform sampler2D sceneShadow;  // half resolution, from the AO pass (shadow_pcss.glsl); the water samples the map
uniform sampler2DShadow shadowCompare;
uniform mat4 lightProjRel;  // camera-relative world -> light clip
uniform float shadowRadius;      // half extent of the shadow map, world units
uniform float shadowDepthRange;
uniform float shadowMapSize;

// fog, same parameters as the vanilla GPU plugin
uniform int useFog;
uniform int fogDepth;       // tiles
uniform int drawDistance;   // world units
uniform int expandedMapLoadingChunks;
uniform vec3 clientSkyColor;
// 1 where the game shows a black void around the scene (instances, player houses): it stays dark, whatever the
// procedural sky and the area say
uniform int skyVoid;

// point lights, culled into clusters on the CPU (LightClusters.java)
uniform int pointLightsEnabled;
uniform samplerBuffer lightData;     // per light: (camera-relative position, radius), (color * strength, 0)
uniform usamplerBuffer clusterData;  // per cluster: count, then light indices

out vec4 FragColor;

// ---------------------------------------------------------------------------------------------------------------
// Ambient occlusion and shadows: 4x4 depth- and normal-aware upsample of the half resolution pass, which also
// averages away its ordered noise. The volumetric fog's upsample uses the same taps, so it is done in the same loop
// when the fog is on.

float upsampleAoFog(ivec2 px, float z, vec3 n, out vec4 fog, out float shadow, out vec4 gi) {
  vec2 hp = (vec2(px) + 0.5) * 0.5 - 0.5;
  ivec2 base = ivec2(floor(hp)) - 1;
  ivec2 maxAo = textureSize(sceneAo, 0) - 1;
  bool withFog = volumetricFog == 1;
  float sum = 0.0;
  float shadowSum = 0.0;
  float wsum = 0.0;
  vec4 giSum = vec4(0.0);
  vec2 groundSum = vec2(0.0);
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
      vec3 qn = halfNormal(g);
      float w = spatial / (1.0 + abs(qz - z) / (z * 0.01 + 1.0));
      float nd = max(dot(n, qn), 0.0);
      nd *= nd;
      nd *= nd;
      w *= nd * nd;

      sum += halfAo(g) * w;
      vec4 sh = texelFetch(sceneShadow, q, 0);
      shadowSum += sh.r * w;
      groundSum += sh.gb * w;
      giSum += halfGi(g) * w;
      wsum += w;
    }
  }
  fog = !withFog ? vec4(0.0, 0.0, 0.0, 1.0)
    : fogWsum > 1e-6 ? fogSum / fogWsum : texelFetch(sceneFog, clamp(ivec2(hp + 0.5), ivec2(0), maxAo), 0);
  shadow = wsum > 1e-4 ? shadowSum / wsum : texelFetch(sceneShadow, clamp(ivec2(hp + 0.5), ivec2(0), maxAo), 0).r;
  gi = wsum > 1e-4 ? giSum / wsum : vec4(0.0);
  groundUpsampled = wsum > 1e-4 ? groundSum / wsum : texelFetch(sceneShadow, clamp(ivec2(hp + 0.5), ivec2(0), maxAo), 0).gb;
  return wsum > 1e-4 ? sum / wsum : 1.0;
}

// ---------------------------------------------------------------------------------------------------------------
// Global illumination (GlobalIllumination.java, gi.glsl): the probes' indirect light, upsampled with the AO, stands
// in for the plain ambient and sky light where the probes have it. giFloor keeps a share of the plain light, so
// interiors stay readable however little light the probes find there.

uniform float giWeight;  // 0..1: enabled, eased in and out, strength
uniform float giFloor;

vec3 giAmbient(vec3 ambient, vec4 gi) {
  float w = gi.a * giWeight;
  return w > 0.0 ? mix(ambient, max(gi.rgb, ambient * giFloor), w) : ambient;
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
  if (sunAndSky && skyVoid == 0 && proceduralSky == 1) {
    vec3 sky = atmosphereSkySteps(px, dir, cameraPos, sunDir, moonDir, sunAltitude, time, steps);
    sky = lightningOverSky(sky, dir, cameraPos);
    return envSky(sky, fogColor, -dir.y);
  }
  // what the sky branch of main() leaves there: the fog color in a void or underground, else the game's clear
  // color (with the game's own skybox, the color its fog has)
  return skyVoid == 1 || (envCave == 1 && proceduralSky == 1) ? fogColor : clientSkyColor;
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

// The opaque target's alpha is how much of the pixel geometry covers (the MSAA samples resolved; SceneTargets
// clears it to 0): at silhouettes against the sky the color, normal and material hold that share of the
// geometry's, the rest is the clear values. Undone here, so the geometry is lit as itself and blended over the sky
// by its coverage, and edges stay as smooth as the antialiasing made them. The resolved depth is one sample's, so
// an edge pixel whose depth missed the geometry borrows a neighbor's.
float borrowDepth(ivec2 px) {
  float best = 0.0;
  bool found = false;
  ivec2 last = textureSize(sceneDepth, 0) - 1;
  for (int y = -1; y <= 1; ++y) {
    for (int x = -1; x <= 1; ++x) {
      float q = texelFetch(sceneDepth, clamp(px + ivec2(x, y), ivec2(0), last), 0).r;
      // reverse-z: the nearest has the largest depth
      if (!isSky(q) && (!found || q > best)) {
        best = q;
        found = true;
      }
    }
  }
  return found ? best : -1.0;
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  vec4 color = texelFetch(sceneColor, px, 0);
  float d = texelFetch(sceneDepth, px, 0).r;
  bool inside = inViewport(px);
  bool sunAndSky = outdoors == 1;
  // the procedural sky shows; a black void or a cave shows the fog color instead
  bool openSky = sunAndSky && skyVoid == 0 && proceduralSky == 1;
  bool voidSky = skyVoid == 1 || (envCave == 1 && proceduralSky == 1);
  vec3 fogColor = envFog(openSky ? skyHorizon(sunAltitude) : clientSkyColor, sunAndSky && skyVoid == 0,
    sunAltitude);

  if (debugView == DEBUG_DEPTH) {
    // log scale from the near plane out to ~100 tiles
    float v = isSky(d) ? 1.0 : clamp(log(viewDepth(d) / NEAR) / log(12800.0 / NEAR), 0.0, 1.0);
    FragColor = vec4(vec3(v), 1.0);
    return;
  }

  if (debugView == DEBUG_NORMALS) {
    FragColor = vec4(texelFetch(sceneNormal, px, 0).rgb, 1.0);
    return;
  }

  // where the composite draws the sky itself, geometry is blended over it by coverage
  bool replaceSky = inside && (openSky || voidSky);
  float cover = replaceSky ? clamp(color.a, 0.0, 1.0) : 1.0;
  if (isSky(d) && replaceSky && cover > 0.004) {
    d = borrowDepth(px);
    if (d < 0.0) {
      cover = 0.0;
      d = 0.0;
    }
  }
  vec3 behind = vec3(0.0);
  if (replaceSky && (cover < 0.996 || isSky(d))) {
    // a point on the near plane gives the view ray; the camera is at the origin of camera-relative space
    vec3 dir = normalize(reconstructPosition(vec2(px) + 0.5, 1.0));
    behind = openSky ? skyBehind(vec2(px) + 0.5, dir, fogColor, true, CLOUD_STEPS) : fogColor;
  }

  if (isSky(d) || cover <= 0.004) {
    if (replaceSky) {
      color.rgb = behind;
    }
    // the color target's alpha is coverage; the window wants opaque
    FragColor = overTransparent(px, vec4(color.rgb, 1.0));
    // 0 is sky in both depth conventions
    FragColor.rgb = applyVolumetricFog(px, FragColor.rgb, 0.0);
    return;
  }

  // the geometry's own share of an edge pixel
  float inv = 1.0 / max(cover, 1.0 / 255.0);
  if (cover < 0.996) {
    color.rgb = clamp((color.rgb - (1.0 - cover) * clientSkyColor) * inv, 0.0, 1.0);
  }
  vec3 n = decodeNormal(cover < 0.996 ? texelFetch(sceneNormal, px, 0).rgb * inv : texelFetch(sceneNormal, px, 0).rgb);
  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  vec3 material = texelFetch(sceneMaterial, px, 0).rgb * (cover < 0.996 ? inv : 1.0);
  float emissive = material.r;
  float transmissive = material.g;

  float ao = 1.0;
  float halfShadow = 1.0;
  vec4 fog;
  vec4 gi = vec4(0.0);
  vec3 indirect = vec3(0.0);
  bool fogDone = false;
  if ((aoEnabled == 1 || shadowsEnabled == 1 || volumetricFog == 1 || giWeight > 0.0 || weatherGroundOn == 1)
    && inside) {
    ao = upsampleAoFog(px, viewDepth(d), n, fog, halfShadow, gi);
    ao = aoEnabled == 1 ? clamp(1.0 - (1.0 - ao) * aoStrength, 0.0, 1.0) : 1.0;
    fogDone = volumetricFog == 1;
  }
  // snow and wet ground change the surface itself, before it is lit
  float wet = inside && weatherGroundOn == 1 ? weatherGroundSurface(color.rgb, n, p + cameraPos, emissive, material.b,
    texelFetch(sceneAlpha, px, 0).a) : 0.0;

  float shadow = 1.0;
  vec3 light = vec3(ao);
  if (sunAndSky && lightingEnabled == 1 && inside) {
    float ndl = dot(n, casterDir);
    // faces turned away from the light get none of it, so their shadow only matters to water and the debug view
    bool needShadow = ndl > 0.0 || transmissive > 0.0 || material.b >= 0.25 || debugView == DEBUG_SHADOWS;
    if (shadowsEnabled == 1 && emissive < 1.0) {
      shadow = halfShadow;
    }
    if (needShadow) {
      shadow *= cloudShadow(p + cameraPos, casterDir);
    }
    shadow = overcastShadow(shadow);
    vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
    float up = max(-n.y, 0.0);
    indirect = giAmbient(ambientLight(sunAltitude) + skyFill(n, sunAltitude, up), gi) * envAmbient * ao;
    light = indirect
      + caster * envSun * max(ndl, 0.0) * shadow * mix(1.0, ao, AO_DIRECT);
    if (transmissive > 0.0) {
      // light through the leaf isn't occluded by the creases SSAO sees on its front
      light += caster * envSun * transmission(ndl, dot(-casterDir, normalize(-p)), transmissive) * shadow;
    }
  } else if (lightingEnabled == 1 && inside) {
    // Underground: no sun, and the game's own baked shading is removed, so a fixed soft key light from above
    // keeps shapes readable. Point lights do the rest.
    light = envCaveLight(n) * ao;
    indirect = light;
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
  if (debugView == DEBUG_GI) {
    // the indirect light alone, as a white surface would show it
    FragColor = vec4(linearToSrgb(clamp(indirect, 0.0, 1.0)), 1.0);
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
  lit = weatherWetSheen(lit, p, n, wet, ao, skyHorizon(sunAltitude));
  lit = waterOver(px, p, n, color.rgb, light, shadow, lit, material.b, fogColor);
  lit = mix(lit, fogColor, max(fogAmount(p + cameraPos), envFogAmount(p)));
  // the world fades into the sky behind it toward the draw distance, so where the culling stops never shows
  float edge = inside ? edgeFadeAmount(p + cameraPos) : 0.0;
  if (edge > 0.002) {
    lit = mix(lit, skyBehind(vec2(px) + 0.5, normalize(p), fogColor, sunAndSky, EDGE_SKY_STEPS), edge);
  }
  // over the sky by the geometry's coverage
  lit = mix(behind, lit, cover);
  FragColor = overTransparent(px, vec4(lit, 1.0));
  FragColor.rgb = fogDone ? applyFogSample(FragColor.rgb, fog) : applyVolumetricFog(px, FragColor.rgb, d);
}
