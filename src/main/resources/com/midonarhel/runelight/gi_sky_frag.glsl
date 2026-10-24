#version 330

// The light the global illumination's patches are relit with (GiRelight.java), drawn into a 32 x 17 float target
// and read back to the CPU: rows 0-15 the sky's radiance over the whole sphere (u the azimuth, v the sine of the
// elevation from -1 to 1), from the sky cache where it is in use like sky_irradiance_frag.glsl, else the analytic
// sky; row 16 the light that casts shadows, the plain ambient plus sky light on a surface facing up (the level the
// sky's light on a patch under the open sky is scaled to), and the overcast's softening of shadows (overcastShadow of
// 0 and 1).

#include "frame.glsl"
#include "sky.glsl"
#include "environment.glsl"
#include "lightning.glsl"
#include "clouds.glsl"
#include "atmosphere.glsl"

uniform vec3 sunDir;
uniform float sunAltitude;
uniform int sunIsCaster;

out vec4 FragColor;

#define GI_SKY_W 32.0
#define GI_SKY_H 16.0
// rays that leave the grid below the horizon see the distant ground, darker than the horizon
#define GI_GROUND_MISS 0.3

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  if (px.y >= int(GI_SKY_H)) {
    vec3 v = vec3(0.0);
    if (px.x == 0) {
      v = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
    } else if (px.x == 1) {
      v = ambientLight(sunAltitude) + skyFill(vec3(0.0, -1.0, 0.0), sunAltitude, 1.0);
    } else if (px.x == 2) {
      v = vec3(overcastShadow(0.0));
    } else if (px.x == 3) {
      v = vec3(overcastShadow(1.0));
    }
    FragColor = vec4(v, 1.0);
    return;
  }

  vec2 uv = (vec2(px) + 0.5) / vec2(GI_SKY_W, GI_SKY_H);
  float s = uv.y * 2.0 - 1.0;
  float c = sqrt(max(1.0 - s * s, 0.0));
  float a = (uv.x - 0.5) * SKY_TAU;
  vec3 d = vec3(cos(a) * c, -s, sin(a) * c);

  vec3 gradient = max(skyGradient(d, sunAltitude), 0.0);
  vec3 glow = srgbToLinear(gradient + sunGlow(d, sunDir, sunAltitude)) - srgbToLinear(gradient);
  vec3 l = srgbToLinear(gradient) + glow;
  if ((skyCacheMode & 1) != 0 && skyCacheWeight > 0.0) {
    // the mip about as wide as this table
    float lod = log2(max(float(textureSize(skyCache, 0).x) / GI_SKY_W, 1.0));
    vec4 cached = textureLod(skyCache, skyCacheUv(d), lod);
    vec3 cl = cached.rgb + glow * cached.a;
    if (!any(isnan(cl)) && !any(isinf(cl))) {
      l = mix(l, cl, skyCacheWeight);
    }
  }
  l *= mix(1.0, GI_GROUND_MISS, smoothstep(0.0, -0.25, s));
  FragColor = vec4(max(l, 0.0), 1.0);
}
