#version 330

// Screen-space ambient occlusion at half resolution. Normal-oriented hemisphere samples in world space,
// tested against the depth buffer. Noisy by design; the composite pass blurs it depth-aware while upsampling.
// The sun and moon shadows are drawn here too (shadow_pcss.glsl), at half resolution, and upsampled the same way.

#include "frame.glsl"
#include "halfres.glsl"
#include "bayer.glsl"
#include "shadow_pcss.glsl"

uniform float aoRadius;  // world units
uniform int aoSamples;   // 0: only the geometry for the upsamples (AO off, fog on)
uniform int shadowsEnabled;
uniform vec3 casterDir;  // toward the light that casts shadows
uniform sampler2D sceneMaterial;  // r emissive, g transmissive (frag.glsl)

layout(location = 0) out vec4 FragAo;  // see halfres.glsl
layout(location = 1) out vec4 FragShadow;  // r: lit share, 1 unshadowed
// sine of the minimum angle above the surface for an occluder to count; higher ignores more of the shallow folds
#define AO_ANGLE_BIAS 0.10
#define TAU 6.28318530718
#define GOLDEN_ANGLE 2.39996322973

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy) * 2;
  float d = texelFetch(sceneDepth, px, 0).r;
  if (isSky(d)) {
    FragAo = vec4(1.0, 0.0, 0.0, 0.0);
    FragShadow = vec4(1.0);
    return;
  }
  // a pixel partly covering the sky holds that share of its normal and material, the rest cleared to 0
  // (the color target's alpha is that share: SceneTargets clears it to 0, geometry writes 1)
  float cover = max(texelFetch(sceneColor, px, 0).a, 1.0 / 255.0);
  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb / cover);
  FragAo = vec4(1.0, NEAR / viewDepth(d), octEncode(n));
  FragShadow = vec4(1.0);
  if (!inViewport(px)) {
    return;
  }

  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  float noise = bayer4(gl_FragCoord.xy);
  if (shadowsEnabled == 1) {
    vec2 m = texelFetch(sceneMaterial, px, 0).rg / cover;
    // emissive surfaces show no shading; a leaf lit from behind looks up its shadow on the lit side
    if (m.r < 0.999) {
      FragShadow.r = shadowFactor(p, dot(n, casterDir) < 0.0 && m.g > 0.0 ? -n : n, noise);
    }
  }
  if (aoSamples <= 0) {
    return;
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

  FragAo.r = 1.0 - occlusion / float(aoSamples);
}
