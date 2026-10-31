// Interleaved gradient noise, 0..1, static in screen space. The half resolution passes rotate and jitter their samples
// by it; the denoise (denoise_frag.glsl) then averages it away. Unlike an ordered 4x4 pattern it has no period for a
// small filter to leave a grid of, so what is left after the blur is an even, fine grain.
float ign(vec2 fragCoord) {
  return fract(52.9829189 * fract(dot(fragCoord, vec2(0.06711056, 0.00583715))));
}
