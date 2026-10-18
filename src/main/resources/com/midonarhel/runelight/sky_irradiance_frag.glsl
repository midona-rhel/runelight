#version 330

// Light from the sky cache on a surface facing each way (SkyCache.java): the cosine weighted average of the
// shown cache over the upper hemisphere, for the normal each texel stands for in atmosphere.glsl's layout.
// 32 directions spread evenly over the hemisphere, read from a blurred mip so clouds between them still count,
// from the two captures being blended.

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

out vec4 FragColor;

#define IRRADIANCE_RINGS 4
#define IRRADIANCE_AZIMUTHS 8
#define IRRADIANCE_LOD 4.0

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
      vec2 uv = skyCacheUv(d);
      vec3 l = mix(textureLod(radiance, uv, IRRADIANCE_LOD).rgb, textureLod(radianceTo, uv, IRRADIANCE_LOD).rgb, blend);
      sum += l * w;
      wsum += w;
    }
  }
  FragColor = vec4(sum / max(wsum, 1e-4), 1.0);
}
