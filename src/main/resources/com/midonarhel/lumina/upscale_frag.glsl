#version 330

// Scales the reduced resolution scene up to the window (Upscaler). Sharpness blends from the plain bilinear filter
// to a Catmull-Rom reconstruction (its five-tap form, which lets the hardware's bilinear do four taps in one), held
// within the range of the four texels the pixel lies between so edges don't ring. Unlike an unsharp mask it never
// pushes the scene's fine grain past what the bilinear filter gives it. The interface is drawn afterwards at full
// resolution.

uniform sampler2D src;
uniform float sharpness;  // 0..1
uniform vec2 outSize;     // the window's size in pixels

layout(location = 0) out vec4 FragColor;

void main() {
  vec2 size = vec2(textureSize(src, 0));
  // the window pixel's center in texel coordinates, where a texel's center is at x + 0.5
  vec2 pos = gl_FragCoord.xy / outSize * size;
  if (sharpness <= 0.0) {
    FragColor = vec4(texture(src, pos / size).rgb, 1.0);
    return;
  }
  vec2 center = floor(pos - 0.5) + 0.5;  // the center of the texel at or before pos along each axis
  vec2 f = pos - center;                 // 0..1 from it toward the next
  vec2 f2 = f * f;
  vec2 f3 = f2 * f;
  vec2 w0 = -0.5 * f3 + f2 - 0.5 * f;
  vec2 w1 = 1.5 * f3 - 2.5 * f2 + 1.0;
  vec2 w2 = -1.5 * f3 + 2.0 * f2 + 0.5 * f;
  vec2 w3 = 0.5 * f3 - 0.5 * f2;
  vec2 w12 = w1 + w2;
  vec2 p0 = (center - 1.0) / size;
  vec2 p3 = (center + 2.0) / size;
  vec2 p12 = (center + w2 / w12) / size;
  vec3 cubic = texture(src, vec2(p12.x, p0.y)).rgb * (w12.x * w0.y)
    + texture(src, vec2(p0.x, p12.y)).rgb * (w0.x * w12.y)
    + texture(src, p12).rgb * (w12.x * w12.y)
    + texture(src, vec2(p3.x, p12.y)).rgb * (w3.x * w12.y)
    + texture(src, vec2(p12.x, p3.y)).rgb * (w12.x * w3.y);
  // the four corner taps are left out, so the weights of the five that remain are scaled back to 1
  cubic /= w12.x * w0.y + w0.x * w12.y + w12.x * w12.y + w3.x * w12.y + w12.x * w3.y;

  ivec2 last = ivec2(size) - 1;
  ivec2 a = clamp(ivec2(floor(center)), ivec2(0), last);
  ivec2 b = clamp(a + 1, ivec2(0), last);
  vec3 t00 = texelFetch(src, a, 0).rgb;
  vec3 t10 = texelFetch(src, ivec2(b.x, a.y), 0).rgb;
  vec3 t01 = texelFetch(src, ivec2(a.x, b.y), 0).rgb;
  vec3 t11 = texelFetch(src, b, 0).rgb;
  vec3 bilinear = mix(mix(t00, t10, f.x), mix(t01, t11, f.x), f.y);
  vec3 lo = min(min(t00, t10), min(t01, t11));
  vec3 hi = max(max(t00, t10), max(t01, t11));
  FragColor = vec4(clamp(mix(bilinear, cubic, sharpness), lo, hi), 1.0);
}
