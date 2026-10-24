#version 330

// Screen-space ambient occlusion at half resolution. Normal-oriented hemisphere samples in world space,
// tested against the depth buffer. Noisy by design; the denoise (denoise_frag.glsl) blurs it depth-aware before the composite upsamples it.
// The sun and moon shadows are drawn here too (shadow_pcss.glsl), at half resolution, and upsampled the same way.

#include "frame.glsl"
#include "halfres.glsl"
#include "ign.glsl"
#include "shadow_pcss.glsl"
#include "gi.glsl"
#include "weather_ground_ao.glsl"
#include "skylight_ao.glsl"
#include "water_common.glsl"

uniform float aoRadius;  // world units
uniform int aoSamples;   // 0: only the geometry for the upsamples (AO off, fog on)
uniform int shadowsEnabled;
uniform vec3 casterDir;  // toward the light that casts shadows
uniform SCENE_SAMPLER sceneMaterial;  // r emissive, g transmissive, b water, a snow (frag.glsl)
uniform usampler2D waterAoSurface;  // the water surface pass's target (water_frag.glsl)
uniform int waterAoOn;
uniform ivec4 waterAoRect;  // the pixels the surface can cover (water.glsl's waterRect)

layout(location = 0) out vec4 FragAo;  // the geometry, see halfres.glsl
layout(location = 1) out vec4 FragShadow;  // r: lit share, 1 unshadowed; g AO, 1 unoccluded; b snow and
                                           // puddles (ground_cover.glsl); a sky visibility (skylight_ao.glsl)
// sine of the minimum angle above the surface for an occluder to count; higher ignores more of the shallow folds
#define AO_ANGLE_BIAS 0.10
// none under water: it fades out over this depth below the surface (no hard line at the waterline), and isn't worked
// out below it
#define AO_WATER_DEPTH 8.0
#define TAU 6.28318530718
#define GOLDEN_ANGLE 2.39996322973

void main() {
  ivec2 px = halfToFull(ivec2(gl_FragCoord.xy));
  float d = texelFetch(sceneDepth, px, 0).r;
  if (isSky(d)) {
    FragAo = halfPackGeometry(0.0, vec3(0.0, 0.0, 1.0), vec3(0.0), 0.0);
    FragShadow = vec4(1.0, 1.0, GROUND_NONE, 1.0);
    return;
  }
  // sample 0 of the pixel, as in every pass but the composite, which shades the pixels that sample 0 doesn't stand for
  // by each of their samples (composite_frag.glsl)
  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  float nearOverDepth = NEAR / viewDepth(d);
  FragAo = halfPackGeometry(nearOverDepth, n, vec3(0.0), 0.0);
  FragShadow = vec4(1.0, 1.0, GROUND_NONE, 1.0);
  if (!inViewport(px)) {
    return;
  }

  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  FragShadow.b = groundPack(weatherGroundAo(p, n));
  vec4 gi = giEnabled == 1 ? giIrradiance(p, n) : vec4(0.0);
  FragAo = halfPackGeometry(nearOverDepth, n, gi.rgb, gi.a);
  float noise = ign(gl_FragCoord.xy);
  FragShadow.a = skylightVisibility(p, n);
  if (shadowsEnabled == 1) {
    vec2 m = texelFetch(sceneMaterial, px, 0).rg;
    // emissive surfaces show no shading; a leaf lit from behind looks up its shadow on the lit side
    if (m.r < 0.999) {
      FragShadow.r = shadowFactor(p, dot(n, casterDir) < 0.0 && m.g > 0.0 ? -n : n, noise);
    }
  }
  if (aoSamples <= 0) {
    return;
  }
  // land terrain is never under water: the surface runs on under the banks, hidden (water.glsl)
  float keep = 1.0;
  if (waterAoOn == 1 && all(greaterThanEqual(px, waterAoRect.xy)) && all(lessThan(px, waterAoRect.zw))
    && texelFetch(sceneMaterial, px, 0).b >= 0.25) {
    keep = 1.0 - smoothstep(0.0, AO_WATER_DEPTH, waterDepthUnder(waterAoSurface, waterAoRect, px, p));
    if (keep <= 0.0) {
      return;
    }
  }

  vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
  vec3 b = cross(n, t);

  float bias = aoRadius * 0.04;
  float occlusion = 0.0;

  for (int i = 0; i < aoSamples; ++i) {
    // cosine-weighted direction on a rotated golden-angle spiral
    float f = (float(i) + 0.5) / float(aoSamples);
    float r = sqrt(f);
    float phi = float(i) * GOLDEN_ANGLE + noise * TAU;
    vec3 dir = t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(1.0 - f);
    // more samples close to the surface, where contact shadows come from
    float len = fract(f + noise * 0.618034);
    len = mix(0.1, 1.0, len * len);

    vec3 s = p + dir * (aoRadius * len);
    vec4 clip = viewProj * vec4(s, 1.0);
    if (clip.w <= NEAR) {
      continue;
    }

    vec2 ndc = clip.xy / clip.w;
    ivec2 sp = ivec2(vec2(sceneViewport.xy) + (ndc * 0.5 + 0.5) * vec2(sceneViewport.zw));
    if (!inViewport(sp)) {
      continue;
    }

    float sd = texelFetch(sceneDepth, sp, 0).r;
    if (isSky(sd)) {
      continue;
    }

    // positive when the visible surface is in front of the sample point
    float diff = clip.w - viewDepth(sd);
    if (diff <= bias) {
      continue;
    }
    // ignore occluders far in front of the sample, e.g. a wall between the camera and the floor
    float range = smoothstep(0.0, 1.0, aoRadius / max(abs(diff), 1e-3));
    // Angle bias: an occluder that sits nearly in this surface's own tangent plane is just the neighboring
    // face of a shallow fold in the same low-poly mesh, not something casting a contact shadow.
    vec3 q = reconstructPosition(vec2(sp) + 0.5, sd);
    vec3 h = q - p;
    float elevation = dot(n, h) * inversesqrt(max(dot(h, h), 1.0));
    occlusion += range * smoothstep(AO_ANGLE_BIAS, AO_ANGLE_BIAS + 0.35, elevation);
  }

  FragShadow.g = 1.0 - occlusion * keep / float(aoSamples);
}
