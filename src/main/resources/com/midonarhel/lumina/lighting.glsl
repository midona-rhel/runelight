/*
 * Color utility functions
 * Written in 2023 by Hooder <ahooder@protonmail.com>
 * To the extent possible under law, the author(s) have dedicated all copyright and related and neighboring rights
 * to this software to the public domain worldwide. This software is distributed without any warranty.
 * You should have received a copy of the CC0 Public Domain Dedication along with this software.
 * If not, see <http://creativecommons.org/publicdomain/zero/1.0/>.
 */

// 117 HD's colour helpers (utils/color_utils.glsl, utils/hash.glsl), as used by its scene and sky shaders.

// Conversion functions to and from sRGB and linear color space.
// The implementation is based on the sRGB EOTF given in the Khronos Data Format Specification.
// Source: https://web.archive.org/web/20220808015852/https://registry.khronos.org/DataFormat/specs/1.3/dataformat.1.3.pdf
// Page number 130 (146 in the PDF)
vec3 srgbToLinear(vec3 srgb) {
  return mix(
    srgb / 12.92,
    pow((srgb + vec3(0.055)) / vec3(1.055), vec3(2.4)),
    step(vec3(0.04045), srgb));
}

vec3 linearToSrgb(vec3 rgb) {
  return mix(
    rgb * 12.92,
    1.055 * pow(rgb, vec3(1 / 2.4)) - 0.055,
    step(vec3(0.0031308), rgb));
}

float linearSrgbLuminance(vec3 c) {
  return dot(c, vec3(.2126, .7152, .0722));
}

// https://web.archive.org/web/20230619214343/https://en.wikipedia.org/wiki/HSL_and_HSV#Color_conversion_formulae
vec3 hslToSrgb(vec3 hsl) {
  float C = (1 - abs(2 * hsl[2] - 1)) * hsl[1];
  float H_prime = fract(hsl[0]) * 6;
  float m = hsl[2] - C / 2;

  float r = clamp(abs(H_prime - 3) - 1, 0, 1);
  float g = clamp(2 - abs(H_prime - 2), 0, 1);
  float b = clamp(2 - abs(H_prime - 4), 0, 1);
  return vec3(r, g, b) * C + m;
}

// Jagex HSL (6-bit hue, 3-bit saturation, 7-bit lightness, unpacked) to normalized HSL
vec3 convertHsl(vec3 HSL) {
  float H = HSL[0] / 64.f + .0078125f;
  float S = HSL[1] / 8.f + .0625f;
  float L = HSL[2] / 128.f;
  return vec3(H, S, L);
}

vec3 unpackRawHsl(int hsl) {
  // 6-bit hue | 3-bit saturation | 7-bit lightness
  float H = hsl >> 10 & 63;
  float S = hsl >> 7 & 7;
  float L = hsl & 127;
  return vec3(H, S, L);
}

#define EPS 1.0e-10

vec3 srgbToHsl(vec3 srgb) {
  float V = max(max(srgb.r, srgb.g), srgb.b);
  float X_min = min(min(srgb.r, srgb.g), srgb.b);
  float C = V - X_min;

  float H = 0;
  if (C > 0) {
    if (V == srgb.r) {
      H = mod((srgb.g - srgb.b) / C, 6);
    } else if (V == srgb.g) {
      H = (srgb.b - srgb.r) / C + 2;
    } else {
      H = (srgb.r - srgb.g) / C + 4;
    }
  }

  float L = (V + X_min) / 2;
  float divisor = 1 - abs(2 * L - 1);
  float S_L = abs(divisor) < EPS ? 0 : C / divisor;
  return vec3(H / 6, S_L, L);
}

vec3 hslToHsv(vec3 hsl) {
  float v = hsl[2] + hsl[1] * min(hsl[2], 1 - hsl[2]);
  return vec3(hsl[0], abs(v) < EPS ? 0 : 2 * (1 - hsl[2] / v), v);
}

vec3 hsvToHsl(vec3 hsv) {
  float l = hsv[2] * (1 - hsv[1] / 2);
  float divisor = min(l, 1 - l);
  return vec3(hsv[0], abs(divisor) < EPS ? 0 : (hsv[2] - l) / divisor, l);
}

vec3 srgbToHsv(vec3 rgb) {
  return hslToHsv(srgbToHsl(rgb));
}

vec3 hsvToSrgb(vec3 hsv) {
  return hslToSrgb(hsvToHsl(hsv));
}

// https://www.shadertoy.com/view/4djSRW
float hash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * .1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}
