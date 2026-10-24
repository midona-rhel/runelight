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
void main() {
  float scene = sceneViewDepth(gl_FragCoord.xy);
  float a = fAlpha;
  if (fSplash != 0) {
    // behind something nearer than its foot, it is hidden
    if (fViewZ * (1.0 - SPLASH_SURFACE_TOLERANCE) - 8.0 > scene) {
      discard;
    }
    // a small, soft, bright flick on the ground, as anime draws rain landing
    float d = dot(fQuad, fQuad);
    a *= exp(-5.0 * d) * (1.0 - smoothstep(0.7, 1.0, d));
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
