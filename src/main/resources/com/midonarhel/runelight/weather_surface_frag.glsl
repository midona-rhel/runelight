#version 330

// Wet surfaces in rain, snow cover in snow, on surfaces under open sky only. Blended over the lit scene as
// out = color + scene * alpha, so rain can darken without knowing the scene color and snow can cover it.

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"

uniform int surfaceMode;       // 1 wet, 2 snow
uniform float fadeDistance;    // world units from the player where the effect has faded out (past the scene)
uniform int shadowsEnabled;
uniform sampler2DShadow shadowCompare;
uniform mat4 lightProjRel;
uniform sampler2D sceneAlpha;  // transparency layer: no snow or wet look under water
uniform sampler2D sceneMaterial;  // b: 1 on the water bed (water.glsl), which the water surface covers

out vec4 FragColor;

#define WET_DARKEN 0.80        // scene color multiplier on fully wet surfaces
#define WET_SHEEN 0.10         // sky reflection on wet upward surfaces
#define SNOW_ALBEDO vec3(0.86, 0.89, 0.95)
#define EXPOSURE_BIAS 40.0     // world units a surface may sit below its column's top and still be exposed

float valueNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  float a = hash13(vec3(i, 0.0));
  float b = hash13(vec3(i + vec2(1.0, 0.0), 0.0));
  float c = hash13(vec3(i + vec2(0.0, 1.0), 0.0));
  float d = hash13(vec3(i + vec2(1.0, 1.0), 0.0));
  return mix(mix(a, b, f.x), mix(c, d, f.x), f.y);
}

// Exposure averaged over a few occlusion texels, so the edge under an eave is soft instead of stair-stepped
float softExposure(vec3 p) {
  return smoothExposure(p, EXPOSURE_BIAS);
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  float d = texelFetch(sceneDepth, px, 0).r;
  if (!inViewport(px) || isSky(d)) {
    discard;
  }
  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  vec3 world = p + cameraPos;
  float fade = 1.0 - smoothstep(fadeDistance * 0.8, fadeDistance, length(world.xz - focusPos.xz));
  if (fade <= 0.0) {
    discard;
  }
  // the player's wetness or snow cover plus the weather field's difference here: dry in a dry region
  vec2 ground = weatherGroundDelta(world.xz);
  float here = clamp(amount + (surfaceMode == 1 ? ground.x : ground.y), 0.0, 1.0);
  if (here <= 0.0) {
    discard;
  }
  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  float up = max(-n.y, 0.0);

  // offset along the normal so a surface isn't covered by itself, mostly for walls right below an edge. Past the
  // occlusion map nothing is known to cover a surface: it counts as exposed.
  float exposed = softExposure(p + n * 12.0) * fade * here * (1.0 - texelFetch(sceneAlpha, px, 0).a)
    * (1.0 - step(0.75, texelFetch(sceneMaterial, px, 0).b));
  if (exposed <= 0.0) {
    discard;
  }

  if (surfaceMode == 1) {
    // everything gets darker when wet, flat ground most; it reflects a bit of sky
    float wet = exposed * mix(0.6, 1.0, up);
    vec3 view = normalize(p);
    float fresnel = pow(1.0 - max(dot(-view, n), 0.0), 4.0);
    vec3 sheen = skyHorizon(sunAltitude) * WET_SHEEN * up * (0.3 + 0.7 * fresnel);
    FragColor = vec4(sheen * wet, mix(1.0, WET_DARKEN, wet));
    return;
  }

  // snow settles on upward faces, in patches that thin out on slopes
  // broad patches: small-scale noise reads as speckle at this distance
  float patches = valueNoise(world.xz / 192.0) * 0.65 + valueNoise(world.xz / 56.0) * 0.35;
  float cover = smoothstep(0.25, 0.9, up) * smoothstep(0.15, 0.55, patches + here * 0.45) * exposed;
  if (cover <= 0.002) {
    discard;
  }
  float shadow = 1.0;
  if (shadowsEnabled == 1) {
    vec4 lc = lightProjRel * vec4(p + n * 24.0, 1.0);
    vec3 s = vec3(lc.xy * 0.5 + 0.5, depthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5);
    if (all(greaterThan(s, vec3(0.0))) && all(lessThan(s, vec3(1.0)))) {
      // 4 taps: a soft edge, not the shadow map's texels
      vec2 o = vec2(1.5 / 1024.0);
      shadow = 0.25 * (texture(shadowCompare, s + vec3(-o.x, -o.y * 0.4, 0.0)) + texture(shadowCompare, s + vec3(o.x * 0.4, -o.y, 0.0))
        + texture(shadowCompare, s + vec3(o.x, o.y * 0.4, 0.0)) + texture(shadowCompare, s + vec3(-o.x * 0.4, o.y, 0.0)));
    }
  }
  shadow = overcastShadow(shadow);
  vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
  vec3 light = ambientLight(sunAltitude) + skyLight(sunAltitude) * up + caster * max(dot(n, casterDir), 0.0) * shadow;
  // torches and lamps outside light the snow like the ground under it; the snow is the top of its column, so
  // lights below it (inside the house it lies on) don't
  light += weatherPointLights(p, viewProj * vec4(p, 1.0), n, world.y);
  vec3 snow = shade(SNOW_ALBEDO, light);
  FragColor = vec4(snow * cover, 1.0 - cover);
}
