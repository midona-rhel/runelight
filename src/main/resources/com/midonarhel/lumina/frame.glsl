// Shared by the full-screen passes: scene buffers, camera data and position reconstruction.
// Positions are camera-relative world space (world minus camera), in game units: 128 per tile, y points down.

#include "scene_samplers.glsl"
uniform SCENE_SAMPLER sceneColor;  // a: 1 where geometry was drawn, 0 on the clear
uniform SCENE_SAMPLER sceneNormal;  // world space, n * 0.5 + 0.5
uniform SCENE_SAMPLER sceneDepth;   // reverse-z, far = 0

uniform mat4 viewProj;          // camera-relative world -> clip
uniform mat4 invViewProj;
uniform ivec4 sceneViewport;    // game viewport inside the targets: x, y, width, height in pixels
uniform int depthZeroToOne;     // 1 with glClipControl (GL 4.5), 0 on macOS

// near plane of the client projection (Mat4.projection)
#define NEAR 50.0

bool isSky(float d) {
  return depthZeroToOne == 1 ? d <= 0.0 : d <= 0.5;
}

// Distance from the camera along the view axis
float viewDepth(float d) {
  return depthZeroToOne == 1 ? 2.0 * NEAR / d : NEAR / (d - 0.5);
}

bool inViewport(ivec2 px) {
  return all(greaterThanEqual(px, sceneViewport.xy)) && all(lessThan(px, sceneViewport.xy + sceneViewport.zw));
}

// px is a pixel center in target coordinates
vec3 reconstructPosition(vec2 px, float d) {
  vec2 ndc = (px - vec2(sceneViewport.xy)) / vec2(sceneViewport.zw) * 2.0 - 1.0;
  float z = depthZeroToOne == 1 ? d : d * 2.0 - 1.0;
  vec4 h = invViewProj * vec4(ndc, z, 1.0);
  return h.xyz / h.w;
}

vec3 decodeNormal(vec3 e) {
  return normalize(e * 2.0 - 1.0);
}
