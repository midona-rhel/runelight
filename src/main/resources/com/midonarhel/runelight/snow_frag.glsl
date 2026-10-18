#version 330

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"

in vec2 fQuad;
in float fViewZ;
in float fAlpha;
flat in vec3 fColor;

out vec4 FragColor;

// soft particles: flakes fade over this many world units in front of the scene
#define SOFT_DEPTH 40.0

void main() {
  float r2 = dot(fQuad, fQuad);
  if (r2 > 1.0) {
    discard;
  }
  float scene = sceneViewDepth(gl_FragCoord.xy);
  if (fViewZ > scene) {
    discard;
  }
  float a = fAlpha * (1.0 - r2) * clamp((scene - fViewZ) / SOFT_DEPTH, 0.0, 1.0);
  if (a < 0.003) {
    discard;
  }
  FragColor = vec4(fColor, a);
}
