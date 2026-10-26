// The scene's material target (SceneTargets), written by frag.glsl, read by the composite and the passes before it:
//   r the surface's material, a code of seven bits: emissive level (0-1, as in SurfaceMaterials), transmissive (2),
//     water (3-4: 0 land terrain, never under water; 1 anything else, under it if below the surface; 2 a bed, always
//     under it; 3 fake water, a face a model draws as water), snow (5-6: 0 none, 1 reduced, 2 all). A pixel the scene
//     did not cover is cleared to 0: a land surface with none of them.
//   a for fake water, how far above the foot of the model's water the pixel is (5 bits, steps of FOOT_STEP world
//     units) and how far below its top (3 bits, steps of TOP_STEP, high bits); 0 elsewhere
//   g, b the triangle's own normal, octahedral, facing the camera: the geometric normal of the face, which the smooth
//     one of the normal target is not. That one tilts across a face where the models' vertices are shared with the
//     faces next to it; the face itself does not.
#ifndef MATERIAL_TARGET_GLSL
#define MATERIAL_TARGET_GLSL

#include "octahedral.glsl"

// the world units a step of the fake water's span is
#define FOOT_STEP 4.0
#define TOP_STEP 4.0

// share of the snow that settles on scenery with an action
#define SNOW_REDUCED 0.4

// The target's value for a surface: its SurfaceMaterials bits (as the vertex shader passes them), its face's normal and
// for fake water its height above the foot of the water and its depth below the top, world units.
vec4 materialPack(int m, vec3 faceNormal, vec2 span) {
  int water = (m & 24) == 24 ? 3 : (m & 8) != 0 ? 2 : (m & 16) != 0 ? 0 : 1;
  // the snow bits of a model: 0 all, 1 reduced, else none
  int snow = (m >> 5 & 3) == 0 ? 2 : (m >> 5 & 3) == 1 ? 1 : 0;
  return vec4(float((m & 7) | water << 3 | snow << 5) / 255.0, octEncode(faceNormal) * 0.5 + 0.5,
    water == 3 ? (floor(clamp(span.x / FOOT_STEP + 0.5, 0.0, 31.0)) + 32.0 * floor(clamp(span.y / TOP_STEP + 0.5, 0.0, 7.0))) / 255.0 : 0.0);
}

// r emissive, g transmissive, b water (1 bed, 0 land terrain, 0.5 anything else, 1.5 fake water), a how much snow
// settles on it
vec4 materialUnpack(vec4 target) {
  int m = int(target.r * 255.0 + 0.5);
  int snow = m >> 5 & 3;
  return vec4(float(m & 3) / 3.0, float(m >> 2 & 1), float(m >> 3 & 3) * 0.5, snow == 2 ? 1.0 : snow == 1 ? SNOW_REDUCED : 0.0);
}

// Whether the unpacked material is fake water (a face drawn as water by the composite, water.glsl).
bool materialFakeWater(vec4 material) {
  return material.b > 1.25;
}

// How far above the foot of its water and below its top (world units) a fake water pixel is, to a step.
vec2 materialSpan(vec4 target) {
  float a = floor(target.a * 255.0 + 0.5);
  return vec2(mod(a, 32.0) * FOOT_STEP, floor(a / 32.0) * TOP_STEP);
}

// The face's normal, facing the camera.
vec3 materialFaceNormal(vec4 target) {
  return octDecode(target.gb * 2.0 - 1.0);
}

#endif
