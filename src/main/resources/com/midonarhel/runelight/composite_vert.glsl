#version 330

// Fullscreen triangle from gl_VertexID, no vertex buffer needed. Also works out the sky and light colors at this
// frame's sun altitude once, for the composite (sky.glsl's SKY_FRAME_CONSTANTS); the other passes drawn with this
// triangle ignore them.

uniform float sunAltitude;  // degrees

#include "sky.glsl"

flat out vec3 skyFrame[6];

void main() {
  vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
  gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
  skyFrame[0] = skyZenithAt(sunAltitude);
  skyFrame[1] = skyHorizonAt(sunAltitude);
  skyFrame[2] = sunLightAt(sunAltitude);
  skyFrame[3] = moonLightAt(sunAltitude);
  skyFrame[4] = ambientLightAt(sunAltitude);
  skyFrame[5] = skyLightAt(sunAltitude);
}
