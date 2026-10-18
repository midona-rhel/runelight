#version 330

// Light from the sky cache on a surface facing each way (SkyCache.java): the cosine weighted average of the
// shown cache over the upper hemisphere, for the normal each texel stands for in atmosphere.glsl's layout.
// 32 directions spread evenly over the hemisphere, read from a blurred mip so clouds between them still count,
// from the two captures being blended. The sun's glow, which the captures leave out because it moves, is added
// here where the clouds let it through: surfaces facing a sunset take on its warmth.

#include "frame.glsl"
#include "sky.glsl"
#include "environment.glsl"
#include "lightning.glsl"
#include "clouds.glsl"
#include "atmosphere.glsl"

uniform sampler2D radiance;
uniform sampler2D radianceTo;
uniform float blend;
uniform vec2 irradianceSize;
uniform float radianceLod;  // the mip about IRRADIANCE_AZIMUTHS * 4 texels wide
uniform vec3 sunDir;
uniform float sunAltitude;

out vec4 FragColor;

#define IRRADIANCE_RINGS 4
#define IRRADIANCE_AZIMUTHS 8

void main() {
  vec3 n = skyCacheDir(gl_FragCoord.xy / irradianceSize);
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int j = 0; j < IRRADIANCE_RINGS; ++j) {
    // even steps in the sine of the elevation are even steps in solid angle
    float s = (float(j) + 0.5) / float(IRRADIANCE_RINGS);
    float c = sqrt(1.0 - s * s);
    for (int i = 0; i < IRRADIANCE_AZIMUTHS; ++i) {
      float a = (float(i) + 0.5 * float(j & 1)) / float(IRRADIANCE_AZIMUTHS) * SKY_TAU;
      vec3 d = vec3(cos(a) * c, -s, sin(a) * c);
      float w = max(dot(n, d), 0.0);
      if (w <= 0.0) {
        continue;
      }
      vec2 uv = skyCacheUv(d);
      vec4 l = mix(textureLod(radiance, uv, radianceLod), textureLod(radianceTo, uv, radianceLod), blend);
      vec3 gradient = max(skyGradient(d, sunAltitude), 0.0);
      vec3 glow = srgbToLinear(gradient + sunGlow(d, sunDir, sunAltitude)) - srgbToLinear(gradient);
      sum += (l.rgb + glow * l.a) * w;
      wsum += w;
    }
  }
  FragColor = vec4(sum / max(wsum, 1e-4), 1.0);
}
