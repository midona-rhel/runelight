/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

// 117 HD's utils/output_transform.glsl and utils/color_filters.glsl. Needs lighting.glsl and the colorblind_mode
// and shading_config defines. Colour blindness compensation is Lumina's (117 HD's is off by default); no Windows
// HDR correction (off by default).

uniform float elapsedTime;
uniform float gammaCorrection;
uniform float saturation;
uniform float contrast;
uniform int colorFilterPrevious;
uniform int colorFilter;
uniform float colorFilterFade;

#if COLORBLIND_MODE > 0
#include "colorblind.glsl"
#endif

#if APPLY_COLOR_FILTER
#define COLOR_FILTER_NONE 0
#define COLOR_FILTER_GREYSCALE 1
#define COLOR_FILTER_SEPIA 2
#define COLOR_FILTER_HIGH_CONTRAST 3
#define COLOR_FILTER_CARTOON 4
#define COLOR_FILTER_INVERT 5
#define COLOR_FILTER_BLACK_AND_WHITE 6
#define COLOR_FILTER_CEL_SHADING 7

float perceptualLuminance(vec3 srgb) {
  return linearToSrgb(vec3(linearSrgbLuminance(srgbToLinear(srgb)))).r;
}

vec3 applySingleColorFilter(int filterIndex, vec3 srgb) {
  switch (filterIndex) {
    case COLOR_FILTER_GREYSCALE:
      return vec3(perceptualLuminance(srgb));
    case COLOR_FILTER_SEPIA:
      return srgb * mat3(
        0.393, 0.769, 0.189,
        0.349, 0.686, 0.168,
        0.272, 0.534, 0.131
      );
    case COLOR_FILTER_HIGH_CONTRAST: {
      float intensity = perceptualLuminance(srgb);
      float modifier = 2.2;
      return clamp(intensity + (srgb - intensity) * modifier, 0.0, 1.0);
    }
    case COLOR_FILTER_CARTOON: {
      float quantizationLevels = 7.0;
      return floor(srgb * quantizationLevels) / quantizationLevels;
    }
    case COLOR_FILTER_INVERT:
      return 1 - srgb;
    case COLOR_FILTER_BLACK_AND_WHITE:
      return perceptualLuminance(srgb) > 0.4 ? vec3(1) : vec3(0);
    case COLOR_FILTER_CEL_SHADING: {
      float intensity = perceptualLuminance(srgb);
      float quantizationLevels = 8.0;
      float quantizedIntensity = floor(intensity * quantizationLevels + 0.5) / quantizationLevels;
      return clamp(srgb + quantizedIntensity - intensity, 0.0, 1.0);
    }
  }

  return srgb;
}

vec3 applyColorFilter(vec3 srgb) {
  srgb = clamp(srgb, 0.0, 1.0);
  vec3 previous = applySingleColorFilter(colorFilterPrevious, srgb);
  vec3 current = applySingleColorFilter(colorFilter, srgb);
  return linearToSrgb(mix(
    srgbToLinear(previous),
    srgbToLinear(current),
    smoothstep(0, 1, colorFilterFade)
  ));
}
#endif

vec3 applyColorAdjustments(vec3 srgb) {
  srgb = clamp(srgb, 0.0, 1.0);
  if (saturation != 1.0 || contrast != 1.0) {
    vec3 hsv = srgbToHsv(srgb);
    hsv.y *= saturation;
    hsv.z = 0.5 + (hsv.z - 0.5) * contrast;
    srgb = hsvToSrgb(hsv);
  }
#if COLORBLIND_MODE > 0
  srgb = colorblind(srgb);
#endif
#if APPLY_COLOR_FILTER
  srgb = applyColorFilter(srgb);
#endif
  return srgb;
}

vec3 applyOutputCorrection(vec3 srgb) {
  srgb = pow(srgb, vec3(gammaCorrection));

  // Reduce color banding
  srgb += (hash12(gl_FragCoord.xy + elapsedTime) - 0.5) / 255.0;

  return srgb;
}
