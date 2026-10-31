#version 330

// Alpha-tested textures (leaves, fences, grates) cast shadows with their holes

uniform sampler2DArray textures;
uniform int texturesReady;
uniform int emissiveCasts;  // 0 while glowing materials are on
uniform int foliageOpen;    // 1 for the rain occlusion map: leaves don't keep rain off, or trees leave blotchy dry patches

flat in int fTextureId;
flat in int fEmissive;
flat in int fTransmissive;
in vec2 fUv;

void main() {
  // light sources (flames, spells, spirits) don't block light
  if (fEmissive != 0 && emissiveCasts == 0)
    discard;
  if (fTransmissive != 0 && foliageOpen == 1)
    discard;
  if (fTextureId > 0 && texturesReady == 1) {
    if (textureLod(textures, vec3(fUv, float(fTextureId - 1)), 0.0).a < 0.5)
      discard;
  }
}
