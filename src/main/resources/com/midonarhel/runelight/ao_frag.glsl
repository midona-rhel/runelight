#version 330

// Screen-space ambient occlusion at half resolution. Normal-oriented hemisphere samples in world space,
// tested against the depth buffer. Noisy by design; the denoise (denoise_frag.glsl) blurs it depth-aware before the composite upsamples it.
// The sun and moon shadows are drawn here too (shadow_pcss.glsl), at half resolution, and upsampled the same way.
// So is the global illumination, from the same samples: what an occluding sample hits is a surface near this one, and
// the sunlight it reflects is the bounce this one gets (screen space: what the screen doesn't show bounces nothing).

#include "frame.glsl"
#include "halfres.glsl"
#include "ign.glsl"
#include "shadow_pcss.glsl"
#include "weather_ground_ao.glsl"
#include "skylight_ao.glsl"
#include "water_common.glsl"

uniform float aoRadius;  // world units
uniform int aoSamples;   // 0: only the geometry for the upsamples (AO off, fog on)
uniform int shadowsEnabled;
uniform int giEnabled;   // 1: the samples gather the bounced light too
uniform vec3 casterDir;  // toward the light that casts shadows
uniform SCENE_SAMPLER sceneMaterial;  // r emissive, g transmissive, b water, a snow (frag.glsl)
uniform usampler2D waterAoSurface;  // the water surface pass's target (water_frag.glsl)
uniform int waterAoOn;
uniform ivec4 waterAoRect;  // the pixels the surface can cover (water.glsl's waterRect)

layout(location = 0) out vec4 FragAo;  // the geometry, see halfres.glsl
layout(location = 1) out vec4 FragShadow;  // r: lit share, 1 unshadowed; g AO, 1 unoccluded; b snow and
                                           // puddles (weather_ground_ao.glsl); a sky visibility (skylight_ao.glsl)
// sine of the minimum angle above the surface for an occluder to count; higher ignores more of the shallow folds
#define AO_ANGLE_BIAS 0.10
// none under water: it fades out over this depth below the surface (no hard line at the waterline), and isn't worked
// out below it
#define AO_WATER_DEPTH 8.0
#define TAU 6.28318530718
#define GOLDEN_ANGLE 2.39996322973

// how far the bounced light is gathered from, world units: three tiles
#define GI_REACH 384.0

// Whether the sample point s is behind the visible surface, the occluder at sp, q: its weight, 0 when none. Occluders
// far in front of the point (a wall between the camera and the floor) fade out beyond reach, and one nearly in the
// surface's own tangent plane is the neighbouring face of a shallow fold in the same low-poly mesh, not something
// casting a contact shadow or bouncing light.
float sampleHit(vec3 p, vec3 n, vec3 s, float reach, float bias, out ivec2 sp, out vec3 q) {
  vec4 clip = viewProj * vec4(s, 1.0);
  if (clip.w <= NEAR) {
    return 0.0;
  }
  vec2 ndc = clip.xy / clip.w;
  sp = ivec2(vec2(sceneViewport.xy) + (ndc * 0.5 + 0.5) * vec2(sceneViewport.zw));
  if (!inViewport(sp)) {
    return 0.0;
  }
  float sd = texelFetch(sceneDepth, sp, 0).r;
  if (isSky(sd)) {
    return 0.0;
  }
  // positive when the visible surface is in front of the sample point
  float diff = clip.w - viewDepth(sd);
  if (diff <= bias) {
    return 0.0;
  }
  float range = smoothstep(0.0, 1.0, reach / max(abs(diff), 1e-3));
  q = reconstructPosition(vec2(sp) + 0.5, sd);
  vec3 h = q - p;
  float elevation = dot(n, h) * inversesqrt(max(dot(h, h), 1.0));
  return range * smoothstep(AO_ANGLE_BIAS, AO_ANGLE_BIAS + 0.35, elevation);
}

// The sunlight the surface at sp, q reflects, in units of the caster's light (the composite colors it): its albedo
// lit by the caster, if it faces the point it lights, p. A matte surface looks equally bright from every side, and
// the samples are cosine weighted, so their mean is the irradiance.
vec3 bounceFrom(vec3 p, ivec2 sp, vec3 q) {
  vec3 nq = decodeNormal(texelFetch(sceneNormal, sp, 0).rgb);
  float lit = max(dot(nq, casterDir), 0.0);
  if (lit <= 0.0 || dot(nq, p - q) <= 0.0) {
    return vec3(0.0);
  }
  if (shadowsEnabled == 1) {
    lit *= shadowTap(q, nq);
  }
  // squared: sRGB to linear, near enough for a bounce
  vec3 albedo = texelFetch(sceneColor, sp, 0).rgb;
  return albedo * albedo * lit;
}

void main() {
  ivec2 px = halfToFull(ivec2(gl_FragCoord.xy));
  float d = texelFetch(sceneDepth, px, 0).r;
  if (isSky(d)) {
    FragAo = halfPackGeometry(0.0, vec3(0.0, 0.0, 1.0), vec3(0.0), 0.0);
    FragShadow = vec4(1.0, 1.0, 0.0, 1.0);
    return;
  }
  // sample 0 of the pixel, as in every pass but the composite, which shades the pixels that sample 0 doesn't stand for
  // by each of their samples (composite_frag.glsl)
  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  float nearOverDepth = NEAR / viewDepth(d);
  FragAo = halfPackGeometry(nearOverDepth, n, vec3(0.0), 0.0);
  FragShadow = vec4(1.0, 1.0, 0.0, 1.0);
  if (!inViewport(px)) {
    return;
  }

  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  FragShadow.b = weatherGroundAo(p, n);
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
  vec3 bounce = vec3(0.0);

  for (int i = 0; i < aoSamples; ++i) {
    // cosine-weighted direction on a rotated golden-angle spiral
    float f = (float(i) + 0.5) / float(aoSamples);
    float r = sqrt(f);
    float phi = float(i) * GOLDEN_ANGLE + noise * TAU;
    vec3 dir = t * (r * cos(phi)) + b * (r * sin(phi)) + n * sqrt(1.0 - f);
    // more samples close to the surface, where contact shadows come from
    float len = fract(f + noise * 0.618034);
    len = mix(0.1, 1.0, len * len);

    ivec2 sp;
    vec3 q;
    float occ = sampleHit(p, n, p + dir * (aoRadius * len), aoRadius, bias, sp, q);
    occlusion += occ;
    if (giEnabled == 1) {
      // the bounce reaches further than the contact shadows: where the AO's short step finds nothing, the same
      // direction a few tiles out (the ground a wall stands on, out to where its light comes from)
      float w = occ;
      if (w <= 0.0) {
        w = sampleHit(p, n, p + dir * GI_REACH, GI_REACH, bias, sp, q);
      }
      if (w > 0.0) {
        bounce += w * bounceFrom(p, sp, q);
      }
    }
  }

  FragShadow.g = 1.0 - occlusion * keep / float(aoSamples);
  if (giEnabled == 1) {
    FragAo = halfPackGeometry(nearOverDepth, n, bounce * (keep / float(aoSamples)), 1.0);
  }
}
