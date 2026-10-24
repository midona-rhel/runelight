#version 330

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"

in vec2 fQuad;
flat in int fSplash;
in float fViewZ;
in float fAlpha;
flat in vec3 fColor;

out vec4 FragColor;

// soft particles: drops fade over this many world units in front of the scene
#define SOFT_DEPTH 60.0
// the impact's mist, against its crisp marks
#define SPLASH_MIST 0.4

// The impact's two cels, in sprite units (x -1..1 from the foot, y up from it), as anime draws rain landing:
// 1, a crown: a thin rim and a fan of spikes with droplets thrown off above it
float crownCel(vec2 p) {
  float ring = length(vec2(p.x / 0.9, (p.y - 0.25) / 0.2));
  float aa = fwidth(ring);
  float rim = smoothstep(0.7 - aa, 0.7 + aa, ring) * (1.0 - smoothstep(1.0 - aa, 1.0 + aa, ring));

  vec2 v = p - vec2(0.0, 0.3);
  float r = length(v);
  float a = atan(v.x, v.y);
  // spikes every third of a radian, thinning outward, within a fan above the rim
  float t = abs(fract(a * 3.0 + 0.5) - 0.5);
  float width = mix(0.24, 0.05, clamp((r - 0.25) / 0.65, 0.0, 1.0));
  float aaT = fwidth(t);
  float spikes = (1.0 - smoothstep(width - aaT, width + aaT, t)) * step(0.25, r) * (1.0 - smoothstep(0.85, 0.95, r))
    * step(abs(a), 1.2);

  // droplets
  const vec3 DROPS[3] = vec3[](vec3(-0.55, 0.95, 0.08), vec3(0.12, 1.2, 0.06), vec3(0.6, 0.85, 0.08));
  float drops = 0.0;
  for (int i = 0; i < 3; ++i) {
    float d = length(p - DROPS[i].xy) / DROPS[i].z;
    drops = max(drops, 1.0 - smoothstep(1.0 - fwidth(d), 1.0 + fwidth(d), d));
  }
  return max(max(rim, spikes), drops);
}

// 2, a puff: a few soft overlapping blobs of spray, brighter at the foot
float puffCel(vec2 p) {
  vec4 blobs[4] = vec4[](vec4(-0.5, 0.3, 0.38, 1.0), vec4(0.05, 0.55, 0.45, 0.9), vec4(0.55, 0.28, 0.33, 1.0),
    vec4(-0.1, 0.22, 0.5, 1.0));
  float a = 0.0;
  for (int i = 0; i < 4; ++i) {
    a = max(a, (1.0 - smoothstep(0.35, 1.0, length(p - blobs[i].xy) / blobs[i].z)) * blobs[i].w);
  }
  return a * (1.0 - 0.35 * p.y);
}

// a low mist under both, wider than the marks
float mist(vec2 p) {
  return 1.0 - smoothstep(0.1, 1.0, length(vec2(p.x, (p.y - 0.3) / 0.5)));
}

void main() {
  float scene = sceneViewDepth(gl_FragCoord.xy);
  float a = fAlpha;
  if (fSplash != 0) {
    // behind something nearer than its foot, it is hidden
    if (fViewZ * (1.0 - SPLASH_SURFACE_TOLERANCE) - 8.0 > scene) {
      discard;
    }
    vec2 p = vec2(fQuad.x, fQuad.y * SPLASH_ASPECT);
    a *= max(fSplash == 1 ? crownCel(p) : puffCel(p), mist(p) * SPLASH_MIST);
  } else {
    if (fViewZ > scene) {
      discard;
    }
    a *= clamp((scene - fViewZ) / SOFT_DEPTH, 0.0, 1.0);
    // round the streak across and taper its ends
    a *= 1.0 - fQuad.x * fQuad.x;
    a *= smoothstep(0.0, 0.3, fQuad.y) * (1.0 - smoothstep(0.85, 1.0, fQuad.y));
  }
  if (a < 0.003) {
    discard;
  }
  FragColor = vec4(fColor, a);
}
