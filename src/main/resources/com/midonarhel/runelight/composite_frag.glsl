#version 330

// Runs once per pixel over the finished scene. Whole-frame effects (fog, AO, grading) go here.

#include "frame.glsl"

#define DEBUG_FINAL 0
#define DEBUG_DEPTH 1
#define DEBUG_NORMALS 2
#define DEBUG_AO 3

uniform sampler2D sceneAo;  // half resolution
uniform int debugView;
uniform int aoEnabled;
uniform float aoStrength;

out vec4 FragColor;

// 4x4 depth- and normal-aware upsample of the half resolution AO, which also blurs out its noise
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

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  vec4 color = texelFetch(sceneColor, px, 0);
  float d = texelFetch(sceneDepth, px, 0).r;
  bool sky = isSky(d);

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

  float ao = 1.0;
  if (aoEnabled == 1 && !sky && inViewport(px)) {
    ao = upsampleAo(px, viewDepth(d), decodeNormal(texelFetch(sceneNormal, px, 0).rgb));
    ao = clamp(1.0 - (1.0 - ao) * aoStrength, 0.0, 1.0);
  }

  if (debugView == DEBUG_AO) {
    FragColor = vec4(vec3(ao), 1.0);
    return;
  }

  FragColor = vec4(color.rgb * ao, color.a);
}
