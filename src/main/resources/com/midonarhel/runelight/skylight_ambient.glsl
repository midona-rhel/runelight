// The composite's half of the skylight (skylight_ao.glsl): the sky visibility upsampled with the AO scales the
// ambient and sky light, so interiors, overhangs and the shade under trees darken. Needs rooms.glsl.

uniform int skylightOn;      // 1 when the AO pass worked the sky visibility out this frame
uniform float skylightFloor; // share of the ambient light kept where no sky is seen

// sky visibility of this pixel, from upsampleAoFog; 1 when unknown
float skyUpsampled = 1.0;

// plain: the ambient plus sky light a surface would get under the open sky
vec3 skylightAmbient(vec3 plain) {
  float v = skylightOn == 1 ? skyUpsampled : 1.0;
  return plain * mix(skylightFloor, 1.0, v);
}

// Inside a building the skylight is the light flowed in through its openings (SkyField), and what comes in through a
// door is mostly the sunlit ground outside, not just the sky: covered surfaces get this many times the sunlight on open
// ground, as much as the sky's light does (the sky's own part is in skylightAmbient). sun: the caster's light.
#define DOORWAY_DAYLIGHT 1.5
vec3 doorwayDaylight(vec3 world, vec3 n, vec3 sun) {
  if (skylightOn == 0 || roomPlaneAt(world + n * ROOM_NORMAL_BIAS).x <= 0) {
    return vec3(0.0);
  }
  return sun * (skyUpsampled * DOORWAY_DAYLIGHT * max(-casterDir.y, 0.0));
}
