#version 330

// Screen-space ambient occlusion at half resolution. Normal-oriented hemisphere samples in world space,
// tested against the depth buffer. Noisy by design; the composite pass blurs it depth-aware while upsampling.

#include "frame.glsl"
#include "halfres.glsl"

uniform float aoRadius;  // world units
uniform int aoSamples;   // 0: only the geometry for the upsamples (AO off, fog on)

out vec4 FragAo;  // see halfres.glsl
// sine of the minimum angle above the surface for an occluder to count; higher ignores more of the shallow folds
#define AO_ANGLE_BIAS 0.10
#define TAU 6.28318530718
#define GOLDEN_ANGLE 2.39996322973

// interleaved gradient noise: cheap per-pixel rotation that the 4x4 upsample blur averages out
float ign(vec2 p) {
  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy) * 2;
  float d = texelFetch(sceneDepth, px, 0).r;
  if (isSky(d)) {
    FragAo = vec4(1.0, 0.0, 0.0, 0.0);
    return;
  }
  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  FragAo = vec4(1.0, NEAR / viewDepth(d), octEncode(n));
  if (aoSamples <= 0 || !inViewport(px)) {
    return;
  }

  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
  vec3 b = cross(n, t);

  float noise = ign(gl_FragCoord.xy);
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
