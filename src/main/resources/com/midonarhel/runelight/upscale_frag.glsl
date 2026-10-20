#version 330

// Scales the reduced resolution scene up to the window (Upscaler). The hardware's bilinear filter does the scaling;
// the sharpening adds back the difference to the four neighbouring texels, held within their range so edges don't
// ring. The interface is drawn afterwards at full resolution.

uniform sampler2D src;
uniform float sharpness;  // 0..1
uniform vec2 outSize;     // the window's size in pixels

layout(location = 0) out vec4 FragColor;

void main() {
  vec2 uv = gl_FragCoord.xy / outSize;
  vec3 c = texture(src, uv).rgb;
  if (sharpness > 0.0) {
    vec2 t = 1.0 / vec2(textureSize(src, 0));
    vec3 n = texture(src, uv + vec2(0.0, t.y)).rgb;
    vec3 s = texture(src, uv - vec2(0.0, t.y)).rgb;
    vec3 e = texture(src, uv + vec2(t.x, 0.0)).rgb;
    vec3 w = texture(src, uv - vec2(t.x, 0.0)).rgb;
    vec3 lo = min(min(n, s), min(min(e, w), c));
    vec3 hi = max(max(n, s), max(max(e, w), c));
    c = clamp(c + (c - (n + s + e + w) * 0.25) * sharpness * 2.0, lo, hi);
  }
  FragColor = vec4(c, 1.0);
}
