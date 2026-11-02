// How far the denoise (denoise_frag.glsl) may spread a texel's sun shadow: the AO pass (ao_frag.glsl) writes it into
// the whole part of the shadow target's r, over the lit share (0..1), as a step 0..3 of the radius on screen its
// filter's taps were dithered over. An undithered texel (a sharp edge, a small shadow, a contact) is spread nowhere;
// a dithered penumbra no further than it reaches. The half float keeps the lit share to 1/256 at the widest step.

// tap distances squared each step reaches: the texel, its four neighbours, the 3x3 square, all 21 of the denoise
const float SHADOW_REACH_D2[4] = float[](0.0, 1.0, 2.0, 5.0);

// penumbraTexels: the dithered penumbra's radius in half resolution texels, 0 for none
float shadowReachPack(float lit, float penumbraTexels) {
  float s = penumbraTexels <= 0.0 ? 0.0 : penumbraTexels < 0.75 ? 1.0 : penumbraTexels < 1.5 ? 2.0 : 3.0;
  return lit + 2.0 * s;
}

int shadowReachStep(float r) {
  return int(floor(r * 0.5));
}

float shadowReachLit(float r) {
  return r - 2.0 * floor(r * 0.5);
}
