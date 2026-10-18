#version 330

// Snow: one instanced camera-facing sprite per flake, no vertex buffer. Flakes drift down slowly and sway, wrapped
// inside a box that follows the player. Flakes below the highest scenery in their column (occlusion map) are
// hidden, so there is no snow inside buildings, under hidden roofs included.

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"

uniform int dropCount;
uniform vec3 wind;             // world units per second

#define BOX_HALF 1920.0
#define BOX_ABOVE 1792.0
#define BOX_BELOW 640.0
#define FALL_SPEED 190.0       // world units per second
#define SWAY 70.0              // world units
#define FLAKE_SIZE 5.0         // radius, world units; never under MIN_PIXELS
#define MIN_PIXELS 1.2
#define FLAKE_ALPHA 0.85
#define NEAR_FADE 200.0

out vec2 fQuad;
out float fViewZ;
out float fAlpha;
flat out vec3 fColor;

const vec2 CORNERS[6] = vec2[](
  vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
  vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

float pixelScale() {
  return length(vec3(viewProj[0][0], viewProj[1][0], viewProj[2][0])) * float(sceneViewport.z) * 0.5;
}

void main() {
  int id = gl_InstanceID;
  vec2 corner = CORNERS[gl_VertexID];
  vec3 h = hash31(float(id) + 0.5);
  vec3 r = hash31(float(id) * 3.17 + 11.0);

  float speed = FALL_SPEED * (0.7 + 0.6 * r.x);
  vec3 drift = vec3(wind.x, speed, wind.z) * time;
  // two sways at different rates so the flakes don't move in lockstep
  float ph = r.y * 6.2832;
  drift.x += sin(time * (0.6 + 0.5 * r.z) + ph) * SWAY;
  drift.z += cos(time * (0.5 + 0.4 * r.x) + ph * 1.7) * SWAY;

  vec3 size = vec3(BOX_HALF * 2.0, BOX_ABOVE + BOX_BELOW, BOX_HALF * 2.0);
  vec3 center = focusPos + vec3(0.0, (BOX_BELOW - BOX_ABOVE) * 0.5, 0.0);
  vec3 world = wrapAround(h * size + drift, center, size);
  vec3 p = world - cameraPos;

  // hidden below the top surface of their column; fade out over the last bit above it
  float above = occluderTop(p) - p.y - cameraPos.y;
  if (above <= 0.0) {
    gl_Position = CULLED;
    return;
  }

  vec4 c = viewProj * vec4(p, 1.0);
  if (c.w < NEAR) {
    gl_Position = CULLED;
    return;
  }
  float radiusPx = FLAKE_SIZE * (0.6 + 0.8 * r.z) * pixelScale() / c.w;
  float drawn = max(radiusPx, MIN_PIXELS);
  gl_Position = c + vec4(corner * drawn / (vec2(sceneViewport.zw) * 0.5) * c.w, 0.0, 0.0);

  vec2 rel = abs(world.xz - focusPos.xz) / BOX_HALF;
  float edge = 1.0 - smoothstep(0.7, 1.0, max(rel.x, rel.y));
  float near = smoothstep(NEAR_FADE, NEAR_FADE * 3.0, c.w);
  // flakes smaller than a pixel fade instead of shrinking
  float sub = min(radiusPx / drawn, 1.0);
  fAlpha = FLAKE_ALPHA * amount * edge * near * smoothstep(0.0, 16.0, above) * sub * sub;
  fQuad = corner;
  fViewZ = c.w;
  fColor = linearToSrgb(clamp(vec3(0.95, 0.97, 1.0) * weatherLight(), 0.0, 1.0));
}
