#version 330

// SkyPanorama: the clear night sky as the composite draws it (sky.glsl's gradient, night_sky.glsl's stars, nebula and
// aurora, atmosphere.glsl's composition without clouds), over azimuth -180..180 degrees (north in the middle, east to
// the right) and elevation 0..90 (bottom to top). reference 1: 117 HD's night instead, its nebula and aurora over its
// near-black night sky (STARFIELD_BACKGROUND_COLOR) with the aurora's contrast term, no stars (117 HD draws its own as
// sprites), for the shape and the brightness side by side.

#include "sky.glsl"
#include "night_sky.glsl"

uniform vec2 size;
uniform float alt;
uniform float time;
uniform int reference;

out vec4 FragColor;

void main() {
  vec2 uv = gl_FragCoord.xy / size;
  float az = (uv.x - 0.5) * NIGHT_TAU;
  float el = uv.y * NIGHT_TAU * 0.25;
  vec3 dir = vec3(sin(az) * cos(el), -sin(el), cos(az) * cos(el));
  float pixelAngle = NIGHT_TAU * 0.25 / size.y;
  vec3 moonDir = vec3(0.0, 1.0, 0.0);
  vec3 lin;
  if (reference == 1) {
    vec3 sky = vec3(0.000235294, 0.000235294, 0.000403251);
    lin = sky + proceduralNebula(starFrame * dir) * nebulaStrength + proceduralAurora(dir, time) * auroraStrength * smoothstep(-6.0, -18.0, alt)
      / (1.0 + overcastLuma(sky) * 1200.0);
  } else {
    vec3 sky = srgbToLinear(max(skyGradient(dir, alt), 0.0));
    lin = sky + nightSky(dir, moonDir, alt, time, pixelAngle, 1.0, 1.0, overcastLuma(sky));
  }
  FragColor = vec4(linearToSrgb(clamp(lin, 0.0, 1.0)), 1.0);
}
