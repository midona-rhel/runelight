// Surface materials (SurfaceMaterials.java): emissive surfaces show their own color regardless of light, thin
// transmissive surfaces (leaves) are also lit through from behind. Shared by the scene and composite passes.

uniform float emissiveStrength;      // 0 turns emissive materials off
uniform float transmissionStrength;  // 0 turns foliage transmission off

// light level an emissive surface is lit at, before the strength setting; above 1 so it reads as glowing
#define EMISSIVE_LIGHT 1.2
// 117 HD's thin sheet factor
#define TRANSMISSION 1.5
// share of the snow that settles on scenery with an action
#define SNOW_REDUCED 0.4

float materialEmissive(int m) {
  return float(m & 3) / 3.0;
}

float materialTransmissive(int m) {
  return float(m >> 2 & 1);
}

// How much snow settles on the surface (SurfaceMaterials.java): all, reduced on things to interact with, none on
// items and actors. Snow covers only upward faces, and terrain takes it all.
float materialSnow(int m) {
  int c = m >> 5 & 3;
  return c == 0 ? 1.0 : c == 1 ? SNOW_REDUCED : 0.0;
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
