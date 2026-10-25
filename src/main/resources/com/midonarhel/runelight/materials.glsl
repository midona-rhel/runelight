// Surface materials (SurfaceMaterials.java): emissive surfaces show their own color regardless of light, thin
// transmissive surfaces (leaves) are also lit through from behind. Shared by the scene and composite passes.

uniform float emissiveStrength;      // 0 turns emissive materials off
uniform float transmissionStrength;  // 0 turns foliage transmission off

// light level an emissive surface is lit at, before the strength setting; above 1 so it reads as glowing
#define EMISSIVE_LIGHT 1.2
// 117 HD's thin sheet factor
#define TRANSMISSION 1.5
// how far above the snow's pattern (ground_pattern.glsl) things to interact with sit, so less snow settles on them, and
// items and actors, which none reaches
#define SNOW_REDUCED 0.35
#define SNOW_NEVER 2.5
// the scene pass stores that plus the pattern in the material target, over this
#define SNOW_STORED 2.5

float materialEmissive(int m) {
  return float(m & 3) / 3.0;
}

float materialTransmissive(int m) {
  return float(m >> 2 & 1);
}

// How far above the snow's pattern a surface sits (SurfaceMaterials.java): 0 for scenery, SNOW_REDUCED for things to
// interact with, SNOW_NEVER for items and actors. Flat faces (up: the normal's up component) are the ground the
// object lies on, a farming patch or a floor tile, and take all of it.
float materialSnowOffset(int m, float up) {
  int c = m >> 5 & 3;
  return c == 0 ? 0.0 : c == 1 ? SNOW_REDUCED * (1.0 - smoothstep(0.85, 0.99, up)) : SNOW_NEVER;
}

// Light reaching the eye through a thin sheet lit from behind, after 117 HD's thinSheetTransmission.
// incidence: N.L with N facing the camera; viewTowardLight: how directly the view looks into the light.
float transmission(float incidence, float viewTowardLight, float transmissive) {
  if (incidence >= 0.0)
    return 0.0;
  return -incidence * transmissive * transmissionStrength * TRANSMISSION * max(viewTowardLight, 0.0);
}

vec3 emissiveLight(vec3 light, float emissive) {
  return mix(light, vec3(EMISSIVE_LIGHT * emissiveStrength), emissive * step(0.001, emissiveStrength));
}
