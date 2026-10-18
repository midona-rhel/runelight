#version 330

// Alpha-tested textures (leaves, fences, grates) cast shadows with their holes

uniform sampler2DArray textures;
uniform int texturesReady;

flat in int fTextureId;
in vec2 fUv;

void main() {
  if (fTextureId > 0 && texturesReady == 1) {
    if (textureLod(textures, vec3(fUv, float(fTextureId - 1)), 0.0).a < 0.5)
      discard;
  }
}
