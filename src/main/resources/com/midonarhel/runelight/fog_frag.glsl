#version 330

// Volumetric fog at half resolution: march each view ray through a height fog layer around the player, lighting
// every sample with the sky, the sun or moon (through the shadow map, so shafts show through gaps) and nearby
// point lights. Output is the light scattered toward the camera (linear) and how much of the scene behind still
// shows; the denoise (denoise_frag.glsl) smooths its step jitter and fog_apply.glsl upsamples it depth-aware in the
// composite. Parameters come from VolumetricFog.java.

#include "frame.glsl"
#include "sky.glsl"
#include cluster_config
#include "fog_cover.glsl"

uniform vec3 cameraPos;
uniform float time;
uniform int outdoors;
uniform int proceduralSky;
uniform vec3 clientSkyColor;
uniform vec3 casterDir;
uniform int sunIsCaster;
uniform float sunAltitude;

uniform int shadowsEnabled;
uniform sampler2DShadow shadowCompare;
uniform mat4 lightProjRel;
uniform float shadowDepthRange;

uniform int pointLightsEnabled;
uniform samplerBuffer lightData;
uniform usamplerBuffer clusterData;

#include "rooms.glsl"

// inside building rooms (rooms.glsl) the fog's density is multiplied by this; 1 leaves interiors foggy
uniform float fogIndoor;

// The water (Water.java, water.glsl): a ray down to something under the water ends at the surface, the fog doesn't
// go on under it
uniform int waterEnabled;
uniform sampler2D waterData;  // page 0: height, flow, type (WaterMap.texture)
uniform vec2 waterGrid;       // x: extended tile of local tile 0, y: corners per side

uniform sampler3D fogNoise;  // tiling value noise, 0..1: r patches, g banks (constant with height)

uniform float fogDensity;    // extinction per world unit at the ground
uniform float fogHeight;     // world units for the density to drop to 1/e
uniform float fogHighestY;   // world y of the highest ground within the fog's reach; y points down
// The terrain the height is measured from (FogGround.java): ground y per tile corner, the planes' pages side by side
uniform sampler2D fogGround;
uniform vec3 fogGroundGrid;  // x: extended tile of local tile 0, y: corners per side, z: texel x of the plane's page
uniform vec2 fogCenter;      // world x, z the fade is centered on: the player
uniform vec2 fogFade;        // horizontal distance from the center where the fog starts and finishes fading out
uniform float fogNear;       // distance from the camera over which the fog thickens from nothing
uniform float fogSunScatter;
uniform float fogMaxCover;   // most the fog may hide around the player (fog_cover.glsl), 1 for no limit
uniform float fogNoiseAmount;
uniform float fogBankAmount;
uniform vec3 fogTint;        // multiplies the fog's ambient color; for per-area fog later
uniform int fogSteps;        // march steps, at most FOG_MAX_STEPS (Performance: fog quality)

// Fog by region (WeatherField.java): the weather field's fog here relative to the player's, x the player's fog
// multiplier minus the field's there, y one over the player's multiplier, z 1 when on
uniform sampler2D weatherField;
uniform vec4 weatherFieldRect;
uniform vec3 weatherFieldFog;

#define FOG_FIELD_MAX 6.0

float weatherFogFactor(vec2 worldXZ) {
  if (weatherFieldFog.z == 0.0) {
    return 1.0;
  }
  float f = textureLod(weatherField, (worldXZ - weatherFieldRect.xy) * weatherFieldRect.zw, 0.0).a * 4.0;
  return clamp((f + weatherFieldFog.x) * weatherFieldFog.y, 0.0, FOG_FIELD_MAX);
}

out vec4 FragFog;

#define FOG_MAX_STEPS 16
// fog left right around the player, as a fraction of the full density
#define FOG_NEAR_FLOOR 0.15
// fraction of the fog's reach by which its color has become the sky's horizon color
#define FOG_SKY_MATCH 0.7
// the layer is cut off where it has thinned to e^-FOG_TOP above the highest ground, so no steps are spent in clear
// air above it
#define FOG_TOP 5.0
// below the local ground density stops growing at e^FOG_BELOW
#define FOG_BELOW 1.0
// Ambient light in the fog: mostly the horizon color, so fog and sky meet in the same tone, plus some of the
// scene's ambient light so nights stay cool blue instead of going black. Kept low: a flat glow turns the fog into a
// white veil, while light from the sun and lamps makes it a lit volume.
#define FOG_HORIZON 0.32
#define FOG_AMBIENT_FILL 0.14
// share of the sun's and the lamps' glow kept when the cover limit takes opacity out of the fog (1: all of it)
#define FOG_LIGHT_COVER_KEEP 0.75
// sun or moon light scattered toward the camera at 100% sun scattering; strong enough that shafts read against
// the ambient glow
#define FOG_SUN_SCALE 0.8
// shafts carry the caster's color more saturated than the light itself: orange at sunset, blue under the moon
#define FOG_SUN_SATURATION 1.5
// underground: no sky, so a dim neutral glow, and thinner
#define FOG_CAVE_COLOR vec3(0.030, 0.030, 0.036)
#define FOG_CAVE_DENSITY 0.5
// Henyey-Greenstein forward lobe mixed with isotropic: a glow toward the sun without the rest going dark
#define FOG_PHASE_G 0.7
#define FOG_PHASE_FORWARD 0.6
// noise: world units per texture repeat horizontally and vertically, and drift in world units per second. The
// slow vertical drift moves the patches through the 3D noise, so they change shape as they go.
#define FOG_NOISE_SCALE 8192.0
#define FOG_NOISE_SCALE_Y 1024.0
#define FOG_WIND vec3(48.0, -4.0, 28.0)
// banks: the band of the bank noise that goes from thin to thick; narrower is more contrast
#define FOG_BANK_LOW 0.2
#define FOG_BANK_HIGH 0.8
// Point lights glowing in the fog: every light in the pixel's fog column (LightClusters), each integrated in closed
// form along the part of the ray inside its sphere instead of being sampled at every step. The halo falls off as
// (c^2 / (c^2 + d^2) - m) / (1 - m), c = FOG_GLOW_CORE * radius, m its value at the radius: close to the cubic
// falloff it replaced (0.37, 0.10, 0.03 against 0.42, 0.13, 0.02 at a quarter, half and three quarters of the
// radius), and the integral along a straight line is an arctangent.
#define FOG_POINT_LIGHT_SCALE 0.6
#define FOG_GLOW_CORE 0.2

#include "halfmap.glsl"
#include "ign.glsl"

// World y of the ground at a point, the terrain's own slopes between the tile corners; the edge of the scene goes on
float fogGroundAt(vec2 worldXZ) {
  vec2 c = clamp(worldXZ / 128.0 + fogGroundGrid.x, vec2(0.0), vec2(fogGroundGrid.y - 1.0));
  return textureLod(fogGround, (c + vec2(0.5 + fogGroundGrid.z, 0.5)) / vec2(textureSize(fogGround, 0)), 0.0).r;
}

vec2 fogWaterCell(vec2 worldXZ) {
  return clamp(worldXZ / 128.0 + waterGrid.x, vec2(0.0), vec2(waterGrid.y - 1.0));
}

float fogWaterLevel(vec2 c) {
  return textureLod(waterData, (c + 0.5) / vec2(2.0 * waterGrid.y, waterGrid.y), 0.0).r;
}

// Distance along the ray dir to where it enters the water above the camera-relative point hit, or -1 when hit
// isn't under water (or the camera is). Same two steps onto the crossing as water.glsl.
float waterSurfaceDistance(vec3 hit, vec3 dir) {
  if (waterEnabled == 0 || dir.y < 1e-3) {
    return -1.0;
  }
  vec3 world = hit + cameraPos;
  vec2 c = fogWaterCell(world.xz);
  ivec2 cell = clamp(ivec2(floor(c)), ivec2(0), ivec2(int(waterGrid.y) - 2));
  if (texelFetch(waterData, cell, 0).a < 0.5) {
    return -1.0;
  }
  float level = fogWaterLevel(c);
  // y points down: the camera below the surface, or the point above it
  if (cameraPos.y >= level || world.y <= level) {
    return -1.0;
  }
  float t = (level - cameraPos.y) / dir.y;
  t = (fogWaterLevel(fogWaterCell(cameraPos.xz + dir.xz * t)) - cameraPos.y) / dir.y;
  return t > 0.0 ? t : -1.0;
}

// normalized so isotropic scattering is 1
float phase(float cosTheta) {
  float g = FOG_PHASE_G;
  float hg = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosTheta, 1.5);
  return mix(1.0, hg, FOG_PHASE_FORWARD);
}

int columnBase(ivec2 px) {
  vec2 rel = (vec2(px) + 0.5 - vec2(sceneViewport.xy)) / vec2(sceneViewport.zw);
  int cx = clamp(int(rel.x * float(CLUSTERS_X)), 0, CLUSTERS_X - 1);
  int cy = clamp(int(rel.y * float(CLUSTERS_Y)), 0, CLUSTERS_Y - 1);
  return (CLUSTERS_X * CLUSTERS_Y * CLUSTERS_Z + cy * CLUSTERS_X + cx) * CLUSTER_STRIDE;
}

// Light from the point lights scattered toward the camera along the ray dir between t0 and t0 + steps * dt.
// weight[i]: scattering per unit length in step i (transmittance to the step times the share it scatters, over
// dt), so each light's integral is split over the steps its sphere spans. No shadowing.
vec3 pointGlow(ivec2 px, vec3 dir, float t0, float dt, int steps, float weight[FOG_MAX_STEPS]) {
  int base = columnBase(px);
  int count = int(texelFetch(clusterData, base).r);
  float tEnd = t0 + dt * float(steps);
  vec3 sum = vec3(0.0);
  for (int k = 0; k < count; ++k) {
    int li = int(texelFetch(clusterData, base + 1 + k).r);
    vec4 posRadius = texelFetch(lightData, li * 2);
    float tc = dot(posRadius.xyz, dir);
    float h2 = max(dot(posRadius.xyz, posRadius.xyz) - tc * tc, 0.0);
    float r2 = posRadius.w * posRadius.w;
    if (h2 >= r2) {
      continue;
    }
    float hc = sqrt(r2 - h2);
    float a = max(tc - hc, t0);
    float b = min(tc + hc, tEnd);
    if (b <= a) {
      continue;
    }
    float c2 = r2 * (FOG_GLOW_CORE * FOG_GLOW_CORE);
    float q = sqrt(c2 + h2);
    float m = c2 / (c2 + r2);
    float k1 = c2 / q;
    int i0 = clamp(int((a - t0) / dt), 0, steps - 1);
    int i1 = clamp(int((b - t0) / dt), 0, steps - 1);
    float glow = 0.0;
    float s0 = a - tc;
    float f0 = k1 * atan(s0 / q) - m * s0;
    for (int i = i0; i <= i1; ++i) {
      float s1 = min(t0 + dt * float(i + 1), b) - tc;
      float f1 = k1 * atan(s1 / q) - m * s1;
      glow += (f1 - f0) * weight[i];
      f0 = f1;
    }
    sum += texelFetch(lightData, li * 2 + 1).rgb * (glow / (1.0 - m));
  }
  return sum * FOG_POINT_LIGHT_SCALE;
}

void main() {
  ivec2 px = halfToFull(ivec2(gl_FragCoord.xy));
  FragFog = vec4(0.0, 0.0, 0.0, 1.0);
  if (!inViewport(px)) {
    return;
  }

  float d = texelFetch(sceneDepth, px, 0).r;
  bool sky = isSky(d);
  // Underground there is no sky, only the void around the cave: a ray into it would run on through fog forever
  // (straight down nothing clips it) and gather the cave's lamps into a pale wall. It stays the void.
  if (sky && outdoors == 0) {
    return;
  }
  // for the sky, a point on the near plane gives the ray
  vec3 hit = reconstructPosition(vec2(px) + 0.5, sky ? 1.0 : d);
  vec3 dir = normalize(hit);
  float t0 = 0.0;
  float t1 = sky ? 1e6 : length(hit);
  if (!sky) {
    // no fog under water: the ray stops at the surface
    float tw = waterSurfaceDistance(hit, dir);
    if (tw > 0.0) {
      t1 = min(t1, tw);
    }
  }

  // Clip the ray to the cylinder around the player where there is fog at all: the steps are spent there, and
  // distant scenery and the sky only see the fog between them and the camera instead of turning into a wall.
  vec2 o = cameraPos.xz - fogCenter;
  vec2 dh = dir.xz;
  float a = dot(dh, dh);
  float b = dot(o, dh);
  float c = dot(o, o) - fogFade.y * fogFade.y;
  if (a < 1e-8) {
    t1 = c <= 0.0 ? t1 : -1.0;
  } else {
    float disc = b * b - a * c;
    if (disc <= 0.0) {
      return;
    }
    float sq = sqrt(disc);
    t0 = max(t0, (-b - sq) / a);
    t1 = min(t1, (-b + sq) / a);
  }

  // and to the part below the top of the layer, which is flat: a few heights above the highest ground, so over low
  // ground some of the ray is clear air that the per-step height then thins out. Height above that is h0 + t * rise.
  float h0 = fogHighestY - cameraPos.y;
  float top = fogHeight * FOG_TOP;
  float rise = -dir.y;
  if (abs(rise) < 1e-5) {
    t1 = h0 <= top ? t1 : -1.0;
  } else if (rise > 0.0) {
    t1 = min(t1, (top - h0) / rise);
  } else {
    t0 = max(t0, (top - h0) / rise);
  }
  if (t1 <= t0) {
    return;
  }

  bool sunAndSky = outdoors == 1;
  vec3 ambient;
  // far away the fog takes exactly the sky's horizon color, so distant fog and sky meet without a band
  vec3 ambientFar;
  vec3 sun = vec3(0.0);
  float density = fogDensity;
  if (sunAndSky) {
    vec3 horizon = proceduralSky == 1 ? skyHorizon(sunAltitude) : clientSkyColor;
    ambient = srgbToLinear(horizon) * FOG_HORIZON + ambientLight(sunAltitude) * FOG_AMBIENT_FILL;
    ambientFar = srgbToLinear(horizon);
    vec3 caster = sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude);
    float luma = dot(caster, vec3(0.2126, 0.7152, 0.0722));
    caster = max(mix(vec3(luma), caster, FOG_SUN_SATURATION), 0.0);
    sun = caster * phase(dot(dir, casterDir)) * (fogSunScatter * FOG_SUN_SCALE);
  } else {
    ambient = FOG_CAVE_COLOR;
    ambientFar = FOG_CAVE_COLOR;
    density *= FOG_CAVE_DENSITY;
  }
  ambient *= fogTint;

  // the shadow map projection is orthographic, so its coordinates are linear along the ray
  bool shafts = sunAndSky && shadowsEnabled == 1 && fogSunScatter > 0.0;
  vec3 lc0 = (lightProjRel * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
  vec3 lcd = (lightProjRel * vec4(dir, 0.0)).xyz;
  float shadowBias = 24.0 / (2.0 * shadowDepthRange);

  // the sun's light in the fog; at night with no moon up the shadow map has nothing to add
  shafts = shafts && dot(sun, vec3(1.0)) > 1e-4;
  bool glow = pointLightsEnabled == 1;

  int steps = clamp(fogSteps, 1, FOG_MAX_STEPS);
  float dt = (t1 - t0) / float(steps);
  // blurred away with the AO and shadows (denoise_frag.glsl)
  float jitter = ign(gl_FragCoord.xy);
  // the flat glow and the sun's shafts apart: the cover limit takes more of the first
  vec3 scatterAmbient = vec3(0.0);
  vec3 scatterSun = vec3(0.0);
  float trans = 1.0;
  float weight[FOG_MAX_STEPS];
  for (int i = 0; i < FOG_MAX_STEPS; ++i) {
    weight[i] = 0.0;
  }
  for (int i = 0; i < steps; ++i) {
    float t = t0 + (float(i) + jitter) * dt;
    vec3 p = dir * t;
    vec3 wp = p + cameraPos;

    float h = fogGroundAt(wp.xz) - wp.y;
    float sigma = density * exp(-max(h, -fogHeight * FOG_BELOW) / fogHeight);
    // Thin around the player and right in front of the camera, thickening over fogNear, so the fog sits in the
    // distance instead of over what you're looking at; and the outer edge thins to nothing.
    float centerDist = length(wp.xz - fogCenter);
    sigma *= 1.0 - smoothstep(fogFade.x, fogFade.y, centerDist);
    sigma *= mix(FOG_NEAR_FLOOR, 1.0, smoothstep(0.0, fogNear, centerDist));
    sigma *= smoothstep(0.0, fogNear, t);
    sigma *= weatherFogFactor(wp.xz);
    if (fogNoiseAmount > 0.0 || fogBankAmount > 0.0) {
      vec3 uvw = (wp + FOG_WIND * time) / vec3(FOG_NOISE_SCALE, FOG_NOISE_SCALE_Y, FOG_NOISE_SCALE);
      vec2 n = texture(fogNoise, uvw).rg;
      // both average about 1, so they move fog around without changing how much there is
      sigma *= max(mix(1.0, n.r * 2.0, fogNoiseAmount), 0.0);
      sigma *= mix(1.0, smoothstep(FOG_BANK_LOW, FOG_BANK_HIGH, n.g) * 2.0, fogBankAmount);
    }
    if (sigma > 0.0 && fogIndoor < 1.0 && roomAt(wp).x > 0) {
      // no fog inside buildings
      sigma *= fogIndoor;
    }
    if (sigma <= 0.0) {
      continue;
    }

    vec3 direct = vec3(0.0);
    if (sunAndSky) {
      float vis = 1.0;
      if (shafts) {
        vec3 lc = lc0 + lcd * t;
        vec2 uv = lc.xy * 0.5 + 0.5;
        float z = depthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5;
        float edge = min(min(uv.x, uv.y), min(1.0 - uv.x, 1.0 - uv.y));
        if (edge > 0.0 && z < 1.0) {
          // the map only covers the area around the player; shafts fade out toward its edge
          vis = mix(1.0, texture(shadowCompare, vec3(uv, z - shadowBias)), smoothstep(0.0, 0.08, edge));
        }
      }
      direct = sun * vis;
    }
    float far = smoothstep(fogNear, fogFade.y * FOG_SKY_MATCH, centerDist);

    float stepTrans = exp(-sigma * dt);
    float scattered = trans * (1.0 - stepTrans);
    scatterAmbient += mix(ambient, ambientFar, far) * scattered;
    scatterSun += direct * ((1.0 - far) * scattered);
    // the point lights' share fades into the far color like the rest of the light
    weight[i] = scattered * (1.0 - far) / dt;
    trans *= stepTrans;
  }
  vec3 lamps = glow ? pointGlow(px, dir, t0, dt, steps, weight) : vec3(0.0);

  // Limit how much of the view the fog hides where the ray ends. The flat glow shrinks with the cover; the light
  // from the sun and the lamps mostly stays, so thick fog still shows where it is lit.
  float cover = 1.0 - trans;
  float limited = capFogCover(cover, fogMaxCover, length(cameraPos.xz + dir.xz * t1 - fogCenter));
  float keep = cover > 1e-5 ? limited / cover : 1.0;
  float lightKeep = mix(keep, 1.0, FOG_LIGHT_COVER_KEEP);

  FragFog = vec4(scatterAmbient * keep + (scatterSun + lamps) * lightKeep, 1.0 - limited);
  // and no fog in the dark under the world (composite_frag.glsl)
  if (sky) {
    FragFog = mix(FragFog, vec4(0.0, 0.0, 0.0, 1.0), voidBelow(dir));
  }
}
