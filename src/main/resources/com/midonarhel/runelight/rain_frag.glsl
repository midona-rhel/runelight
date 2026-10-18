#version 330

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"

in vec2 fQuad;
flat in int fSplash;
in float fViewZ;
in float fAlpha;
flat in vec3 fColor;

uniform sampler2D sceneMaterial;  // b: 1 on the water bed; rain on water makes rings there instead (water.glsl)

out vec4 FragColor;

// how far behind a surface a splash may sit and still show on it, as a fraction of view depth
#define SPLASH_SURFACE_TOLERANCE 0.02
// soft particles: drops fade over this many world units in front of the scene
#define SOFT_DEPTH 60.0

void main() {
  float scene = sceneViewDepth(gl_FragCoord.xy);
  float a = fAlpha;
  if (fSplash == 1) {
    // only on a surface the camera actually sees here, so splashes on hidden roofs or behind walls don't show
    float tol = fViewZ * SPLASH_SURFACE_TOLERANCE + 8.0;
    if (abs(scene - fViewZ) > tol || texelFetch(sceneMaterial, ivec2(gl_FragCoord.xy), 0).b > 0.75) {
      discard;
    }
    float r = length(fQuad);
    a *= (1.0 - smoothstep(0.75, 1.0, r)) * smoothstep(0.25, 0.6, r);
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
