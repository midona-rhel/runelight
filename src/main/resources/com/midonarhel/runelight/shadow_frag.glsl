#version 330

// Alpha-tested textures (leaves, fences, grates) cast shadows with their holes

uniform sampler2DArray textures;
uniform int texturesReady;
uniform int emissiveCasts;  // 0 while glowing materials are on

flat in int fTextureId;
flat in int fEmissive;
in vec2 fUv;

void main() {
  // light sources (flames, spells, spirits) don't block light
  if (fEmissive != 0 && emissiveCasts == 0)
    discard;
  if (fTextureId > 0 && texturesReady == 1) {
    if (textureLod(textures, vec3(fUv, float(fTextureId - 1)), 0.0).a < 0.5)
      discard;
  }
}
