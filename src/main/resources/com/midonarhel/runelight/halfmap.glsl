// How the half resolution passes (halfres.glsl) map to the scene's pixels: the half targets are half the scene's
// size at every render scale (SceneTargets).

uniform float halfRatio;  // scene pixels per texel of the pass being drawn along each axis: 2, the fog march 4

// the scene pixel a half texel's geometry is read from
ivec2 halfToFull(ivec2 h) {
  return ivec2(floor(vec2(h) * halfRatio));
}

// the position of scene pixel px in half texel coordinates (texel centers at whole numbers)
vec2 fullToHalf(ivec2 px) {
  return (vec2(px) + 0.5) / halfRatio - 0.5;
}
