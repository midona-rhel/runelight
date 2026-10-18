#version 330

// One capture of the sky cache (SkyCache.java): the sky's colors through the clouds, linear, and the clouds'
// transmittance, over a lat-long map (atmosphere.glsl's skyCacheUv). No sun glow, discs, stars or lightning: they
// move or flash and are added where the cache is used.

#include "frame.glsl"
#include "sky.glsl"
#include "environment.glsl"
#include "lightning.glsl"
#include "clouds.glsl"
#include "atmosphere.glsl"

uniform vec3 cameraPos;
uniform vec3 sunDir;
uniform vec3 moonDir;
uniform float sunAltitude;
uniform vec2 cacheSize;
// cloud steps: 24 when the sky is drawn from the cache, where grain would be magnified; 12 for the sky light only
uniform int captureSteps;

out vec4 FragColor;

void main() {
  vec3 dir = skyCacheDir(gl_FragCoord.xy / cacheSize);
  vec3 base = srgbToLinear(max(skyGradient(dir, sunAltitude), 0.0));
  vec4 c = vec4(0.0, 0.0, 0.0, 1.0);
  if (cloudsEnabled == 1) {
    c = cloudLayer(dir, cameraPos, sunDir, moonDir, sunAltitude, SKY_TAU / cacheSize.x, cloudJitter(gl_FragCoord.xy),
      captureSteps);
  }
  vec4 o = vec4(base * c.a + cloudShoulder(c.rgb), c.a);
  // one NaN texel would spread through the mips into the whole sky light (and some drivers produce them where
  // others don't): a broken texel shows the clear sky instead
  bool bad = any(isnan(o)) || any(isinf(o));
  FragColor = bad ? vec4(base, 1.0) : clamp(o, vec4(0.0), vec4(64.0, 64.0, 64.0, 1.0));
}
