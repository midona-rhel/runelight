// The scene's material target (SceneTargets), written by frag.glsl, read by the composite and the passes before it:
//   r the surface's material, a code of seven bits: emissive level (0-1, as in SurfaceMaterials), transmissive (2),
//     water (3-4: 0 land terrain, never under water; 1 anything else, under it if below the surface; 2 a bed, always
//     under it; 3 fake water, a face a model draws as water), snow (5-6: 0 none, 1 reduced, 2 all). A pixel the scene
//     did not cover is cleared to 0: a land surface with none of them.
//   fake water has no emissive, transmissive or snow, so its other five bits are 0
//   a unused (0)
//   g, b the triangle's own normal, octahedral, facing the camera: the geometric normal of the face, which the smooth
//     one of the normal target is not. That one tilts across a face where the models' vertices are shared with the
//     faces next to it; the face itself does not.
#ifndef MATERIAL_TARGET_GLSL
#define MATERIAL_TARGET_GLSL

#include "octahedral.glsl"

// share of the snow that settles on scenery with an action
#define SNOW_REDUCED 0.4

// The target's value for a surface: its SurfaceMaterials bits (as the vertex shader passes them) and its face's normal.
vec4 materialPack(int m, vec3 faceNormal) {
  int water = (m & 24) == 24 ? 3 : (m & 8) != 0 ? 2 : (m & 16) != 0 ? 0 : 1;
  // the snow bits of a model: 0 all, 1 reduced, else none
  int snow = (m >> 5 & 3) == 0 ? 2 : (m >> 5 & 3) == 1 ? 1 : 0;
  int code = water == 3 ? 24 : (m & 7) | water << 3 | snow << 5;
  return vec4(float(code) / 255.0, octEncode(faceNormal) * 0.5 + 0.5, 0.0);
}

// r emissive, g transmissive, b water (1 bed, 0 land terrain, 0.5 anything else, 1.5 fake water), a how much snow
// settles on it
vec4 materialUnpack(vec4 target) {
  int m = int(target.r * 255.0 + 0.5);
  if ((m & 24) == 24) {
    return vec4(0.0, 0.0, 1.5, 0.0);
  }
  int snow = m >> 5 & 3;
  return vec4(float(m & 3) / 3.0, float(m >> 2 & 1), float(m >> 3 & 3) * 0.5, snow == 2 ? 1.0 : snow == 1 ? SNOW_REDUCED : 0.0);
}

// Whether the unpacked material is fake water (a face drawn as water by the composite, water.glsl).
bool materialFakeWater(vec4 material) {
  return material.b > 1.25;
}

// The face's normal, facing the camera.
vec3 materialFaceNormal(vec4 target) {
  return octDecode(target.gb * 2.0 - 1.0);
}

#endif
