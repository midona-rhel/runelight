#version 330

// Volumetric fog at half resolution: march each view ray through a height fog layer around the player, lighting
// every sample with the sky, the sun or moon (through the shadow map, so shafts show through gaps) and nearby
// point lights. Output is the light scattered toward the camera (linear) and how much of the scene behind still
// shows; fog_apply.glsl upsamples it depth-aware in the composite. Parameters come from VolumetricFog.java.

#include "frame.glsl"
#include "sky.glsl"
#include cluster_config

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

uniform sampler3D fogNoise;  // tiling value noise, 0..1

uniform float fogDensity;    // extinction per world unit at the ground
uniform float fogHeight;     // world units for the density to drop to 1/e
uniform float fogGroundY;    // world y the height is measured from; y points down
uniform vec2 fogCenter;      // world x, z the fade is centered on: the player
uniform vec2 fogFade;        // horizontal distance from the center where the fog starts and finishes fading out
uniform float fogNear;       // distance from the camera over which the fog thickens from nothing
uniform float fogSunScatter;
uniform float fogNoiseAmount;
uniform vec3 fogTint;        // multiplies the fog's ambient color; for per-area fog later

out vec4 FragFog;

#define FOG_STEPS 12
// fog left right around the player, as a fraction of the full density
#define FOG_NEAR_FLOOR 0.15
// fraction of the fog's reach by which its color has become the sky's horizon color
#define FOG_SKY_MATCH 0.7
// the layer is cut off where it has thinned to e^-FOG_TOP, so no steps are spent in clear air above it
#define FOG_TOP 5.0
// below the reference ground (valleys) density stops growing at e^FOG_BELOW
#define FOG_BELOW 1.0
// Ambient light in the fog: mostly the horizon color, so fog and sky meet in the same tone, plus some of the
// scene's ambient light so nights stay cool blue instead of going black.
#define FOG_HORIZON 0.45
#define FOG_AMBIENT_FILL 0.20
// sun or moon light scattered toward the camera at 100% sun scattering; strong enough that shafts read against
// the ambient glow
#define FOG_SUN_SCALE 0.55
// shafts carry the caster's color more saturated than the light itself: orange at sunset, blue under the moon
#define FOG_SUN_SATURATION 1.5
// underground: no sky, so a dim neutral glow, and thinner
#define FOG_CAVE_COLOR vec3(0.030, 0.030, 0.036)
#define FOG_CAVE_DENSITY 0.5
// Henyey-Greenstein forward lobe mixed with isotropic: a glow toward the sun without the rest going dark
#define FOG_PHASE_G 0.7
#define FOG_PHASE_FORWARD 0.6
// noise: world units per texture repeat horizontally and vertically, and drift in world units per second
#define FOG_NOISE_SCALE 3072.0
#define FOG_NOISE_SCALE_Y 1024.0
#define FOG_WIND vec3(48.0, -4.0, 28.0)
// point lights glowing in the fog: every light in the sample's cluster, up to the cluster's capacity. A smaller
// cap picks different subsets in neighboring clusters and shows the cluster grid as squares.
#define FOG_POINT_LIGHTS (CLUSTER_STRIDE - 1)
#define FOG_POINT_LIGHT_SCALE 0.35

float ign(vec2 p) {
  return fract(52.9829189 * fract(dot(p, vec2(0.06711056, 0.00583715))));
}

// normalized so isotropic scattering is 1
float phase(float cosTheta) {
  float g = FOG_PHASE_G;
  float hg = (1.0 - g * g) / pow(1.0 + g * g - 2.0 * g * cosTheta, 1.5);
  return mix(1.0, hg, FOG_PHASE_FORWARD);
}

int clusterBase(ivec2 px, float z) {
  vec2 rel = (vec2(px) + 0.5 - vec2(sceneViewport.xy)) / vec2(sceneViewport.zw);
  int cx = clamp(int(rel.x * float(CLUSTERS_X)), 0, CLUSTERS_X - 1);
  int cy = clamp(int(rel.y * float(CLUSTERS_Y)), 0, CLUSTERS_Y - 1);
  int cz = z <= CLUSTER_NEAR ? 0
    : clamp(int(floor(log(z / CLUSTER_NEAR) / log(CLUSTER_FAR / CLUSTER_NEAR) * float(CLUSTERS_Z))), 0, CLUSTERS_Z - 1);
  return ((cz * CLUSTERS_Y + cy) * CLUSTERS_X + cx) * CLUSTER_STRIDE;
}

// isotropic glow from the lights in this sample's cluster; no shadowing
vec3 pointGlow(ivec2 px, float z, vec3 p) {
  int base = clusterBase(px, z);
  int count = min(int(texelFetch(clusterData, base).r), FOG_POINT_LIGHTS);
  vec3 sum = vec3(0.0);
  for (int i = 0; i < count; ++i) {
    int li = int(texelFetch(clusterData, base + 1 + i).r);
    vec4 posRadius = texelFetch(lightData, li * 2);
    vec3 l = posRadius.xyz - p;
    float d2 = dot(l, l);
    float r2 = posRadius.w * posRadius.w;
    vec4 colorFlags = texelFetch(lightData, li * 2 + 1);
    // w 1: an indoor light (building lights), which would glow in the fog outside through the walls
    if (d2 < r2 && colorFlags.w < 0.5) {
      // steeper than the surface falloff: a soft halo around the light rather than a lit ball of fog
      float att = 1.0 - sqrt(d2 / r2);
      sum += colorFlags.rgb * (att * att * att);
    }
  }
  return sum * FOG_POINT_LIGHT_SCALE;
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy) * 2;
  FragFog = vec4(0.0, 0.0, 0.0, 1.0);
  if (!inViewport(px)) {
    return;
  }

  float d = texelFetch(sceneDepth, px, 0).r;
  bool sky = isSky(d);
  // for the sky, a point on the near plane gives the ray
  vec3 hit = reconstructPosition(vec2(px) + 0.5, sky ? 1.0 : d);
  vec3 dir = normalize(hit);
  float t0 = 0.0;
  float t1 = sky ? 1e6 : length(hit);

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

  // and to the part below the top of the layer: height above ground is h0 + t * rise
  float h0 = fogGroundY - cameraPos.y;
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

  // view depth is linear along the ray too, for the light cluster lookup
  float w0 = (viewProj * vec4(0.0, 0.0, 0.0, 1.0)).w;
  float wd = (viewProj * vec4(dir, 0.0)).w;
  bool glow = pointLightsEnabled == 1;

  float dt = (t1 - t0) / float(FOG_STEPS);
  float jitter = ign(gl_FragCoord.xy);
  vec3 scatter = vec3(0.0);
  float trans = 1.0;
  for (int i = 0; i < FOG_STEPS; ++i) {
    float t = t0 + (float(i) + jitter) * dt;
    vec3 p = dir * t;
    vec3 wp = p + cameraPos;

    float h = fogGroundY - wp.y;
    float sigma = density * exp(-max(h, -fogHeight * FOG_BELOW) / fogHeight);
    // Thin around the player and right in front of the camera, thickening over fogNear, so the fog sits in the
    // distance instead of over what you're looking at; and the outer edge thins to nothing.
    float centerDist = length(wp.xz - fogCenter);
    sigma *= 1.0 - smoothstep(fogFade.x, fogFade.y, centerDist);
    sigma *= mix(FOG_NEAR_FLOOR, 1.0, smoothstep(0.0, fogNear, centerDist));
    sigma *= smoothstep(0.0, fogNear, t);
    if (fogNoiseAmount > 0.0) {
      vec3 uvw = (wp + FOG_WIND * time) / vec3(FOG_NOISE_SCALE, FOG_NOISE_SCALE_Y, FOG_NOISE_SCALE);
      float n = texture(fogNoise, uvw).r;
      sigma *= max(mix(1.0, n * 2.0, fogNoiseAmount), 0.0);
    }
    if (sigma <= 0.0) {
      continue;
    }

    vec3 light = ambient;
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
      light += sun * vis;
    }
    if (glow) {
      light += pointGlow(px, w0 + wd * t, p);
    }
    light = mix(light, ambientFar, smoothstep(fogNear, fogFade.y * FOG_SKY_MATCH, centerDist));

    float stepTrans = exp(-sigma * dt);
    scatter += trans * light * (1.0 - stepTrans);
    trans *= stepTrans;
  }

  FragFog = vec4(scatter, trans);
}
