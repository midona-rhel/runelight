#version 330

// The static shadow cache copied into the sampled map (ShadowMap.useFront), drawn at the start of the casters' own pass:
// a blit was a pass of its own, and the casters' pass then loaded the whole map again. Both are 32-bit float depth, so
// the depth goes through unchanged.

uniform sampler2D staticDepth;

void main() {
  gl_FragDepth = texelFetch(staticDepth, ivec2(gl_FragCoord.xy), 0).r;
}
