// Directional PCSS: blocker search, mean blocker gap, percentage-closer filtering.
// Algorithm: NVIDIA PCSS Integration (2008), with per-tap receiver-plane correction.
// This map is orthographic and LINEAR: gapWorld = gapDepth * 2*shadowDepthRange.
// No perspective zBlocker divisor belongs in the angular-radius conversion.
uniform sampler2D shadowRaw;
uniform sampler2DShadow shadowCompare;
uniform mat4 lightProjRel;
uniform float shadowRadius;
uniform float shadowDepthRange;
uniform float shadowMapSize;
uniform int sunIsCaster;
uniform vec2 strikeLight;
uniform sampler2D shadowMax;
uniform int shadowMaxTile;
// Approximately 0.266 degrees angular radius for either celestial disc.
#define SUN_SOFTNESS 0.00465
#define MOON_SOFTNESS 0.00465
#define MAX_PENUMBRA_WORLD 48.0
#define SHADOW_DISK_TAPS 32
// Equal-area golden-angle disk. Fixed in shadow space, with no screen-coordinate jitter.
const vec2 SHADOW_DISK[SHADOW_DISK_TAPS] = vec2[](
  vec2(0.125000000, 0.000000000),
  vec2(-0.159645045, 0.146247939),
  vec2(0.024436233, -0.278438271),
  vec2(0.201222239, 0.262458779),
  vec2(-0.369267557, -0.065318231),
  vec2(0.349802466, -0.222515696),
  vec2(-0.117002079, 0.435241902),
  vec2(-0.223135654, -0.429634123),
  vec2(0.484115115, 0.176798064),
  vec2(-0.503641109, 0.207895728),
  vec2(0.242788294, -0.518824483),
  vec2(0.179414374, 0.572001296),
  vec2(-0.540757006, -0.313379738),
  vec2(0.634369523, -0.139464360),
  vec2(-0.387145845, 0.550675126),
  vec2(-0.089439655, -0.690199644),
  vec2(0.549071757, 0.462758258),
  vec2(-0.738878471, 0.030554945),
  vec2(0.538955125, -0.536332334),
  vec2(-0.036058186, 0.779791515),
  vec2(-0.512817532, -0.614526793),
  vec2(0.812359593, 0.109301834),
  vec2(-0.688310641, 0.478908616),
  vec2(0.188086057, -0.836061382),
  vec2(0.435033266, 0.759191055),
  vec2(-0.850448410, -0.271316239),
  vec2(0.826102401, -0.381680263),
  vec2(-0.357888202, 0.855155562),
  vec2(-0.319407334, -0.888033758),
  vec2(0.849908635, 0.446688159),
  vec2(-0.944034646, 0.248844503),
  vec2(0.536595808, -0.834529771));

// dz/du,dz/dv of this receiver plane. Projection rows are scaled orthogonal axes.
vec2 shadowReceiverGradient(vec3 n) {
  vec3 x = normalize(vec3(lightProjRel[0][0], lightProjRel[1][0], lightProjRel[2][0]));
  vec3 y = normalize(vec3(lightProjRel[0][1], lightProjRel[1][1], lightProjRel[2][1]));
  vec3 z = normalize(vec3(lightProjRel[0][2], lightProjRel[1][2], lightProjRel[2][2]));
  float nz = dot(n, z);
  // Near edge-on planes have no well-conditioned depth function over the map.
  if (abs(nz) < 0.05) return vec2(0.0);
  return -vec2(dot(n, x), dot(n, y)) / nz * (shadowRadius / shadowDepthRange);
}
float shadowFactor(vec3 p, vec3 n, float rotationUnused, out float penumbraWorld) {
  penumbraWorld = 0.0;
  vec4 lc = lightProjRel * vec4(p, 1.0);
  vec2 uv = lc.xy * 0.5 + 0.5;
  float z = depthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || z >= 1.0 || z <= 0.0)
    return 1.0;
  float edgeFade = smoothstep(0.0, 0.08, min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y)));
  float worldToUv = 1.0 / (2.0 * shadowRadius);
  float texelWorld = 2.0 * shadowRadius / shadowMapSize;
  float depthSpan = 2.0 * shadowDepthRange;
  vec2 gradient = shadowReceiverGradient(n);
  // Constant quantization allowance; hardware PCF also needs the half-texel slope allowance.
  float bias = max(0.25 * texelWorld / depthSpan, 1e-7);
  float filterBias = bias + dot(abs(gradient), vec2(0.5 / shadowMapSize));
  float angularRadius = strikeLight.x > 0.0 ? 0.0 : (sunIsCaster == 1 ? SUN_SOFTNESS : MOON_SOFTNESS);
  float searchWorld = min(MAX_PENUMBRA_WORLD, z * depthSpan * angularRadius);
  // Static max-tile shortcut disabled until its slope-dependent bound has a GPU regression.
  float gapSum = 0.0;
  int blockers = 0;
  for (int i = 0; i <= SHADOW_DISK_TAPS; ++i) {
    vec2 offset = i == SHADOW_DISK_TAPS ? vec2(0.0) : SHADOW_DISK[i] * searchWorld * worldToUv;
    // Raw depth is nearest sampled: evaluate the plane at that actual texel center.
    vec2 tap = (floor((uv + offset) * shadowMapSize) + 0.5) / shadowMapSize;
    float receiver = z + dot(gradient, tap - uv);
    float d = textureLod(shadowRaw, tap, 0.0).r;
    if (d < receiver - bias) {
      gapSum += receiver - d;
      ++blockers;
    }
  }
  if (blockers == 0) return 1.0;
  float gapWorld = gapSum / float(blockers) * depthSpan;
  float filterWorld = min(gapWorld * angularRadius, MAX_PENUMBRA_WORLD);
  // Hardware bilinear PCF provides the minimum reconstruction footprint at contact.
  float lit = 0.0;
  for (int i = 0; i < SHADOW_DISK_TAPS; ++i) {
    vec2 offset = SHADOW_DISK[i] * filterWorld * worldToUv;
    float receiver = z + dot(gradient, offset) - filterBias;
    lit += texture(shadowCompare, vec3(uv + offset, receiver));
  }
  // Sampling is deterministic; additional screen-space denoise is unnecessary and would
  // broaden this reference filter. AO and fog continue through their existing denoiser.
  return mix(1.0, lit / float(SHADOW_DISK_TAPS), edgeFade);
}
