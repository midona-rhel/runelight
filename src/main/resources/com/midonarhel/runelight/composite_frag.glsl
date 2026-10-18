#version 330

// Runs once per pixel over the finished scene: sky, lighting, shadows, AO and fog.

#include "frame.glsl"
#include "sky.glsl"

#define DEBUG_FINAL 0
#define DEBUG_DEPTH 1
#define DEBUG_NORMALS 2
#define DEBUG_AO 3
#define DEBUG_SHADOWS 4
#define DEBUG_LIGHTING 5

#define TAU 6.28318530718
#define TILE_SIZE 128.0

uniform sampler2D sceneAo;  // half resolution
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

// fog, same parameters as the vanilla GPU plugin
uniform int useFog;
uniform int fogDepth;       // tiles
uniform int drawDistance;   // world units
uniform int expandedMapLoadingChunks;
uniform vec3 clientSkyColor;

out vec4 FragColor;

// ---------------------------------------------------------------------------------------------------------------
// Ambient occlusion: 4x4 depth- and normal-aware upsample of the half resolution AO, which also blurs its noise

float upsampleAo(ivec2 px, float z, vec3 n) {
  vec2 hp = (vec2(px) + 0.5) * 0.5 - 0.5;
  ivec2 base = ivec2(floor(hp)) - 1;
  ivec2 maxAo = textureSize(sceneAo, 0) - 1;
  float sum = 0.0;
  float wsum = 0.0;
  for (int y = 0; y < 4; ++y) {
    for (int x = 0; x < 4; ++x) {
      ivec2 q = clamp(base + ivec2(x, y), ivec2(0), maxAo);
      ivec2 fq = q * 2;
      float qd = texelFetch(sceneDepth, fq, 0).r;
      if (isSky(qd)) {
        continue;
      }
      float qz = viewDepth(qd);
      vec3 qn = decodeNormal(texelFetch(sceneNormal, fq, 0).rgb);

      vec2 o = vec2(q) - hp;
      float w = exp(-dot(o, o) * 0.5);
      w *= 1.0 / (1.0 + abs(qz - z) / (z * 0.01 + 1.0));
      w *= pow(max(dot(n, qn), 0.0), 8.0);

      sum += texelFetch(sceneAo, q, 0).r * w;
      wsum += w;
    }
  }
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
#define SUN_SOFTNESS 0.025
#define MOON_SOFTNESS 0.06
#define BLOCKER_SEARCH_WORLD 96.0
#define MAX_PENUMBRA_WORLD 64.0

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

  float blockerSum = 0.0;
  float blockers = 0.0;
  for (int i = 0; i < 12; ++i) {
    float d = texture(shadowRaw, uv + rot * POISSON[i] * (BLOCKER_SEARCH_WORLD * worldToUv)).r;
    if (d < z - bias) {
      blockerSum += d;
      blockers += 1.0;
    }
  }
  if (blockers == 0.0) {
    return 1.0;
  }

  float gapWorld = (z - blockerSum / blockers) * 2.0 * shadowDepthRange;
  float softness = sunIsCaster == 1 ? SUN_SOFTNESS : MOON_SOFTNESS;
  float penumbra = clamp(gapWorld * softness, texelWorld, MAX_PENUMBRA_WORLD) * worldToUv;

  float lit = 0.0;
  for (int i = 0; i < 16; ++i) {
    lit += texture(shadowCompare, vec3(uv + rot * POISSON[i] * penumbra, z - bias));
  }
  return mix(1.0, lit / 16.0, edgeFade);
}

// ---------------------------------------------------------------------------------------------------------------
// Distance fog toward the scene edge, ported from the vanilla vertex shader so it can be applied after lighting

#define FOG_SCENE_EDGE_MIN ((-float(expandedMapLoadingChunks) * 8.0 + 1.0) * TILE_SIZE)
#define FOG_SCENE_EDGE_MAX ((104.0 + float(expandedMapLoadingChunks) * 8.0 - 1.0) * TILE_SIZE)
#define FOG_CORNER_ROUNDING 1.5
#define FOG_CORNER_ROUNDING_SQUARED (FOG_CORNER_ROUNDING * FOG_CORNER_ROUNDING)

float fogAmount(vec3 world) {
  if (useFog == 0) {
    return 0.0;
  }
  float dd = float(drawDistance);
  float fogWest = max(FOG_SCENE_EDGE_MIN, cameraPos.x - dd);
  float fogEast = min(FOG_SCENE_EDGE_MAX, cameraPos.x + dd);
  float fogSouth = max(FOG_SCENE_EDGE_MIN, cameraPos.z - dd);
  float fogNorth = min(FOG_SCENE_EDGE_MAX, cameraPos.z + dd);
  float xDist = min(world.x - fogWest, fogEast - world.x);
  float zDist = min(world.z - fogSouth, fogNorth - world.z);
  float nearest = min(xDist, zDist);
  float second = max(xDist, zDist);
  float fogDistance = nearest - FOG_CORNER_ROUNDING * TILE_SIZE *
    max(0.0, (nearest + FOG_CORNER_ROUNDING_SQUARED) / (second + FOG_CORNER_ROUNDING_SQUARED));
  return 1.0 - clamp(fogDistance / (float(fogDepth) * TILE_SIZE), 0.0, 1.0);
}

// ---------------------------------------------------------------------------------------------------------------

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  vec4 color = texelFetch(sceneColor, px, 0);
  float d = texelFetch(sceneDepth, px, 0).r;
  bool sky = isSky(d);
  bool inside = inViewport(px);
  bool sunAndSky = outdoors == 1;
  vec3 fogColor = sunAndSky && proceduralSky == 1 ? skyHorizon(sunAltitude) : clientSkyColor;

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
      color.rgb = skyColor(dir, sunDir, moonDir, sunAltitude, time);
    }
    FragColor = color;
    return;
  }

  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  vec3 p = reconstructPosition(vec2(px) + 0.5, d);

  float ao = 1.0;
  if (aoEnabled == 1 && inside) {
    ao = upsampleAo(px, viewDepth(d), n);
    ao = clamp(1.0 - (1.0 - ao) * aoStrength, 0.0, 1.0);
  }

  float shadow = 1.0;
  vec3 light = vec3(ao);
  if (sunAndSky && lightingEnabled == 1 && inside) {
    if (shadowsEnabled == 1) {
      shadow = shadowFactor(p, n, ign(gl_FragCoord.xy));
    }
    vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
    light = ambientLight(sunAltitude) * ao + caster * max(dot(n, casterDir), 0.0) * shadow;
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
    FragColor = vec4(light * 0.5, 1.0);
    return;
  }

  vec3 lit = color.rgb * light;
  lit = mix(lit, fogColor, fogAmount(p + cameraPos));
  FragColor = vec4(lit, color.a);
}
