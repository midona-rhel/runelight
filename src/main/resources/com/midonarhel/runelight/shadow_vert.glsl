#version 330

// Depth-only pass from the sun or moon. Same vertex formats as the scene pass.

layout(location = 0) in vec4 vertf; // w: wind code on swaying models (wind.glsl)
layout(location = 1) in int abhsl;
layout(location = 2) in ivec4 tex;

uniform mat4 lightProj;   // world -> light clip
uniform mat4 entityProj;
uniform ivec3 base;

flat out int fTextureId;
flat out int fEmissive;
flat out int fTransmissive;
out vec2 fUv;

#include "wind.glsl"

void main() {
  vec4 worldPos = entityProj * vec4(vertf.xyz + base, 1.0);
  vec3 noNormal = vec3(0.0);
  worldPos.xyz += windDisplacement(vertf.w, worldPos.xyz, noNormal, false);
  gl_Position = lightProj * worldPos;
  fTextureId = tex.x & 0x3ff;  // material bits above, see SurfaceMaterials.java
  fEmissive = tex.x >> 10 & 3;
  fTransmissive = tex.x >> 12 & 1;
  fUv = vec2(float(tex.y) / 256.0, float(tex.z) / 256.0);
}
