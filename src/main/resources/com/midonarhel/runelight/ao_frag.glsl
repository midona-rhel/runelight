#version 330

// Screen-space ambient occlusion at half resolution. Normal-oriented hemisphere samples in world space,
// tested against the depth buffer. Noisy by design; the composite pass blurs it depth-aware while upsampling.

#include "frame.glsl"

uniform float aoRadius;  // world units

out float FragAo;

#define SAMPLES 12
#define TAU 6.28318530718
#define GOLDEN_ANGLE 2.39996322973

// interleaved gradient noise: cheap per-pixel rotation that the 4x4 upsample blur averages out
float ign(vec2 p) {
  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy) * 2;
  if (!inViewport(px)) {
    FragAo = 1.0;
    return;
  }

  float d = texelFetch(sceneDepth, px, 0).r;
  if (isSky(d)) {
    FragAo = 1.0;
    return;
  }

  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  vec3 t = normalize(cross(n, abs(n.y) < 0.99 ? vec3(0.0, 1.0, 0.0) : vec3(1.0, 0.0, 0.0)));
  vec3 b = cross(n, t);

  float noise = ign(gl_FragCoord.xy);
  float bias = aoRadius * 0.04;
  float occlusion = 0.0;

  for (int i = 0; i < SAMPLES; ++i) {
    // cosine-weighted direction on a rotated golden-angle spiral
    float f = (float(i) + 0.5) / float(SAMPLES);
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
    // ignore occluders far in front of the sample, e.g. a wall between the camera and the floor
    float range = smoothstep(0.0, 1.0, aoRadius / max(abs(diff), 1e-3));
    occlusion += step(bias, diff) * range;
  }

  FragAo = 1.0 - occlusion / float(SAMPLES);
}
