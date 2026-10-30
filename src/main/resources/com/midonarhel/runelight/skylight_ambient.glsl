// The composite's half of the skylight (skylight_ao.glsl): the sky visibility upsampled with the AO scales the
// ambient and sky light, so interiors, overhangs and the shade under trees darken.

uniform int skylightOn;      // 1 when the AO pass worked the sky visibility out this frame
// share of the ambient light kept where no sky is seen, such as deep inside buildings
#define SKYLIGHT_FLOOR 0.4

// sky visibility of this pixel, from upsampleAoFog; 1 when unknown
float skyUpsampled = 1.0;

// plain: the ambient plus sky light a surface would get under the open sky
vec3 skylightAmbient(vec3 plain) {
  float v = skylightOn == 1 ? skyUpsampled : 1.0;
  return plain * mix(SKYLIGHT_FLOOR, 1.0, v);
}
