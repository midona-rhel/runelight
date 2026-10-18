#version 330

// Depth-only pass from the sun or moon. Same vertex formats as the scene pass.

layout(location = 0) in vec3 vertf;
layout(location = 1) in int abhsl;
layout(location = 2) in ivec4 tex;

uniform mat4 lightProj;   // world -> light clip
uniform mat4 entityProj;
uniform ivec3 base;

flat out int fTextureId;
out vec2 fUv;

void main() {
  vec4 worldPos = entityProj * vec4(vertf + base, 1.0);
  gl_Position = lightProj * worldPos;
  fTextureId = tex.x;
  fUv = vec2(float(tex.y) / 256.0, float(tex.z) / 256.0);
}
