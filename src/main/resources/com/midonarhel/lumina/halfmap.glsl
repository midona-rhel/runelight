// How the half resolution passes (halfres.glsl) map to the scene's pixels. The half targets are half the window's
// size, but never larger than the scene's, so below 100% render scale a half texel covers fewer scene pixels, down
// to one (SceneTargets).

uniform float halfRatio;  // scene pixels per half resolution texel along each axis: 2 at 100%, 1 at 50% and below
uniform float fogRatio;   // scene pixels per fog texel: at least 2, so below 100% coarser than the half targets

// the half resolution texel under fog texel q: the one holding the scene pixel the fog's march started from
// (fog_frag.glsl's halfToFull at the fog's ratio); q itself when the fog is at the half targets' size
ivec2 fogToHalf(ivec2 q) {
  return ivec2(floor(floor(vec2(q) * fogRatio) / halfRatio));
}

// the scene pixel a half texel's geometry is read from
ivec2 halfToFull(ivec2 h) {
  return ivec2(floor(vec2(h) * halfRatio));
}

// the position of scene pixel px in half texel coordinates (texel centers at whole numbers)
vec2 fullToHalf(ivec2 px) {
  return (vec2(px) + 0.5) / halfRatio - 0.5;
}
