// The weather's cover on a pixel, in one 8 bit channel: the AO pass (weather_ground_ao.glsl) writes it into the shadow
// target's b, the composite (weather_ground.glsl) reads it back after the upsample. Snow is above GROUND_NONE and
// standing water below it, both 0 to 1 (snow wins where both are there), and GROUND_NONE is exact in 8 bits so that
// uncovered ground stays uncovered through the denoise and the upsample.

#define GROUND_NONE (128.0 / 255.0)

float groundPack(float cover) {
  return (128.0 + 127.0 * clamp(cover, -1.0, 1.0)) / 255.0;
}

float groundUnpack(float b) {
  return (b * 255.0 - 128.0) / 127.0;
}
