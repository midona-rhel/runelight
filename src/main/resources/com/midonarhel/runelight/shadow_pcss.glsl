// Sun and moon shadows: PCSS. Blocker search for the average occluder depth, then a PCF kernel sized by the
// distance between occluder and receiver, so contact shadows are sharp and long ones soften. Drawn at half
// resolution in the AO pass (ao_frag.glsl), rotated by interleaved gradient noise (ign.glsl), which the denoise
// (denoise_frag.glsl) averages away: soft edges without grain. Needs frame.glsl.

uniform sampler2D shadowRaw;
uniform sampler2DShadow shadowCompare;
uniform mat4 lightProjRel;  // camera-relative world -> light clip
uniform float shadowRadius;      // half extent of the shadow map, world units
uniform float shadowDepthRange;
uniform float shadowMapSize;
uniform int casterKind;  // 0 moon, 1 sun, 2 lightning bolt
// The static map's greatest depth within the filter's reach of each tile of shadowMaxTile texels (ShadowMap.buildMax);
// 0 tiles: none
uniform sampler2D shadowMax;
uniform int shadowMaxTile;

const vec2 POISSON[16] = vec2[](
  vec2(-0.94201624, -0.39906216), vec2(0.94558609, -0.76890725),
  vec2(-0.09418410, -0.92938870), vec2(0.34495938, 0.29387760),
  vec2(-0.91588581, 0.45771432), vec2(-0.81544232, -0.87912464),
  vec2(-0.38277543, 0.27676845), vec2(0.97484398, 0.75648379),
  vec2(0.44323325, -0.97511554), vec2(0.53742981, -0.47373420),
  vec2(-0.26496911, -0.41893023), vec2(0.79197514, 0.19090188),
  vec2(-0.24188840, 0.99706507), vec2(-0.81409955, 0.91437590),
  vec2(0.19984126, 0.78641367), vec2(0.14383161, -0.14100790));

#define SHADOW_TAU 6.28318530718
// world units of penumbra per world unit between occluder and receiver: the light's apparent size
#define SUN_SOFTNESS 0.08
#define MOON_SOFTNESS 0.14
#define BLOCKER_SEARCH_WORLD 160.0
// with the POISSON points, the filter's reach ShadowMap.FILTER_REACH_WORLD bounds the shadow under
#define MAX_PENUMBRA_WORLD 112.0
#define SHADOW_SEARCH_TAPS 12
#define SHADOW_FILTER_TAPS 16

// rotation: 0..1, turns the tap pattern
float shadowFactor(vec3 p, vec3 n, float rotation) {
  float texelWorld = 2.0 * shadowRadius / shadowMapSize;
  // normal offset keeps surfaces from shadowing themselves
  vec4 lc = lightProjRel * vec4(p + n * texelWorld * 1.5, 1.0);
  vec2 uv = lc.xy * 0.5 + 0.5;
  float z = depthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || z >= 1.0) {
    return 1.0;
  }
  float edgeFade = smoothstep(0.0, 0.08, min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y)));
  float bias = 2.0 * texelWorld / (2.0 * shadowDepthRange);

  // In the middle of a shadow: all the filter can reach is nearer the light than this texel, so every compare fails
  // whatever the penumbra (and the search's nearest taps, well within that, find blockers): the same as the full
  // filter at a fraction of its taps. The moving models only bring the depth nearer than the static map's.
  if (shadowMaxTile > 0) {
    ivec2 tile = min(ivec2(uv * shadowMapSize) / shadowMaxTile, textureSize(shadowMax, 0) - 1);
    if (texelFetch(shadowMax, tile, 0).r < z - bias) {
      return 1.0 - edgeFade;
    }
  }

  float a = rotation * SHADOW_TAU;
  mat2 rot = mat2(cos(a), sin(a), -sin(a), cos(a));
  float worldToUv = 1.0 / (2.0 * shadowRadius);

  float blockerSum = 0.0;
  float blockers = 0.0;
  for (int i = 0; i < SHADOW_SEARCH_TAPS; ++i) {
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
  float softness = casterKind != 0 ? SUN_SOFTNESS : MOON_SOFTNESS;
  float penumbraWorld = clamp(gapWorld * softness, texelWorld, MAX_PENUMBRA_WORLD);
  float penumbra = penumbraWorld * worldToUv;
  // a kernel within a texel or two of hardware filtered compares needs no more than half the taps
  int filterTaps = penumbraWorld < texelWorld * 2.0 ? SHADOW_FILTER_TAPS / 2 : SHADOW_FILTER_TAPS;

  float lit = 0.0;
  for (int i = 0; i < filterTaps; ++i) {
    lit += texture(shadowCompare, vec3(uv + rot * POISSON[i] * penumbra, z - bias));
  }
  return mix(1.0, lit / float(filterTaps), edgeFade);
}
