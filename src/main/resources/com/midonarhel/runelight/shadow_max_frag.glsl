#version 330

// The greatest depth over a rectangle of the source's texels (ShadowMap.buildMax): count of them from this texel times
// scale plus offset, clamped to the source's edge as the shadow taps are.

uniform sampler2D source;
uniform int scale;
uniform ivec2 offset;
uniform ivec2 count;

layout(location = 0) out float FragMax;

void main() {
  ivec2 first = ivec2(gl_FragCoord.xy) * scale + offset;
  ivec2 last = textureSize(source, 0) - 1;
  float m = 0.0;
  for (int y = 0; y < count.y; ++y) {
    for (int x = 0; x < count.x; ++x) {
      m = max(m, texelFetch(source, clamp(first + ivec2(x, y), ivec2(0), last), 0).r);
    }
  }
  FragMax = m;
}
