// 4x4 ordered (Bayer) pattern, 0..1: every 4x4 block of pixels holds each of the 16 values once. The half
// resolution passes rotate and jitter their samples by it, and the composite's 4x4 upsample, nearly flat, averages
// it away exactly instead of leaving the grain interleaved gradient noise leaves without temporal accumulation.
float bayer4(vec2 fragCoord) {
  ivec2 i = ivec2(fragCoord) & 3;
  const int B[16] = int[](0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5);
  return (float(B[i.y * 4 + i.x]) + 0.5) / 16.0;
}
