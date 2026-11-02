#version 330

// Runs once per pixel over the finished scene: sky, lighting, shadows, AO and fog. Compiled once for each of its
// passes (LuminaPlugin.CompositeProgram), so that each program holds only its own path: the full resolution
// composite, and at half resolution the clouds and the water, apart or, when both are drawn, in one pass. With MSAA
// the full resolution one shades each pixel's first surface, and a second pass (COMPOSITE_EDGES) blends the second one
// over the pixels that have one, which a small pass (COMPOSITE_MARK) marks in the stencil first where the target has
// one.
#define COMPOSITE_FULL 0
#define COMPOSITE_CLOUDS 1
#define COMPOSITE_WATER 2
#define COMPOSITE_CLOUDS_WATER 3
#define COMPOSITE_EDGES 4
#define COMPOSITE_MARK 5
#include composite_pass

#include "frame.glsl"
#define SKY_FRAME_CONSTANTS
#include "sky.glsl"
#include "materials.glsl"
#include "material_target.glsl"
#include "environment.glsl"
#include cluster_config
#include "fog_apply.glsl"
#include "lightning.glsl"
#include "clouds.glsl"
// The clouds are marched at half resolution, by the cloud pass (COMPOSITE_CLOUDS, into cloudHalf), and the sky below
// filters them from there (atmosphere.glsl): they are soft, and marched at every pixel they cost the sky most of its
// time. The stars, sun and moon in front of them stay at full resolution.
#define CLOUDS_HALF_RES
uniform sampler2D cloudHalf;
#include "atmosphere.glsl"
#include "edge_fade.glsl"

#define DEBUG_FINAL 0
#define DEBUG_DEPTH 1
#define DEBUG_NORMALS 2
#define DEBUG_AO 3
#define DEBUG_SHADOWS 4
#define DEBUG_LIGHTING 5
#define DEBUG_LIGHT_CLUSTERS 6
#define DEBUG_MATERIALS 7
#define DEBUG_SKYLIGHT 8

#define TAU 6.28318530718

// how much AO also darkens direct sun and point light; physically 0, but contact shading reads better with some
#define AO_DIRECT 1.0
// share of the sun put back inside shadows where the sky is open (the user's choice; 117 HD has none). 0.7 left open
// ground in shadow at 1.5-1.7:1 to the lit ground beside it (snowtree1, 12h and 16h): shadows barely read.
#define DAYLIGHT_FILL 0.45


#define TILE_SIZE 128.0

uniform SCENE_SAMPLER sceneAlpha;  // transparent geometry, already lit, premultiplied; with MSAA per sample
uniform SCENE_SAMPLER sceneMaterial;  // the material and the face's normal (material_target.glsl)
#include debug_view
uniform int aoEnabled;
uniform float aoStrength;

uniform vec3 cameraPos;     // world position of the camera
uniform float time;         // seconds, for twinkling

// lighting
uniform int outdoors;       // 0 underground: no sun, no sky
uniform int proceduralSky;  // 0 where the game draws its own skybox
uniform int lightingEnabled;
uniform vec3 sunDir;        // visual positions, toward the light
uniform vec3 moonDir;
uniform vec3 casterDir;     // the light that casts shadows, elevation clamped
uniform int sunIsCaster;
uniform float sunAltitude;  // degrees

// shadows
uniform int shadowsEnabled;
uniform sampler2D sceneShadow;  // half resolution, from the AO pass (ao_frag.glsl): r shadow, g AO, b snow and puddles,
                                // a sky visibility; the water samples the map
uniform sampler2DShadow shadowCompare;
uniform mat4 lightProjRel;  // camera-relative world -> light clip
uniform float shadowRadius;      // half extent of the shadow map, world units
uniform float shadowDepthRange;
uniform float shadowMapSize;

uniform int drawDistance;   // world units
uniform int expandedMapLoadingChunks;
uniform vec3 clientSkyColor;
// 1 where the game shows a black void around the scene (instances, player houses): it stays dark, whatever the
// procedural sky and the area say
uniform int skyVoid;

// point lights, culled into clusters on the CPU (light/LightClusters.java)
uniform int pointLightsEnabled;

#include "light/lamp.glsl"
#include "rooms.glsl"
#include "skylight_ambient.glsl"

// 0 legacy/debug, 1 ambient, 2 sun, 3 points, 4 surfaces, 5 atmosphere.
#include "composite_mode"
uniform sampler2DArray compositeParts;
layout(location = 0) out vec4 FragColor;

// ---------------------------------------------------------------------------------------------------------------
// Ambient occlusion and shadows: 2x2 depth- and normal-aware upsample of the half resolution pass, whose noise the
// denoise (denoise_frag.glsl) has smoothed. The volumetric fog's upsample uses the same taps, so it is done in the
// same loop when the fog is on.

float upsampleAoFog(ivec2 px, float z, vec3 n, out vec4 fog, out float shadow, out float ground) {
  vec2 hp = fullToHalf(px);
  ivec2 base = ivec2(floor(hp));
  ivec2 maxAo = textureSize(sceneAo, 0) - 1;
  // the fog in the same taps when it is at the half targets' size; else on its own (upsampleFog)
  bool withFog = volumetricFog == 1 && fogRatio == halfRatio;
  vec4 shSum = vec4(0.0);
  float wsum = 0.0;
  vec4 fogSum = vec4(0.0);
  float fogWsum = 0.0;
  // the 2x2 half-res texels around the pixel, bilinear weights refined by depth and normal: a 4x4 footprint read
  // 48 texels a pixel and cost over a millisecond for no visible difference
  for (int y = 0; y < 2; ++y) {
    for (int x = 0; x < 2; ++x) {
      ivec2 q = clamp(base + ivec2(x, y), ivec2(0), maxAo);
      vec2 g = texelFetch(sceneAo, q, 0).rg;
      float qz = halfViewDepth(g);
      vec2 bl = max(1.0 - abs(vec2(q) - hp), 0.0);
      float spatial = bl.x * bl.y + 1e-3;
      if (withFog) {
        float fw = spatial * fogDepthWeight(qz, z);
        fogSum += texelFetch(sceneFog, q, 0) * fw;
        fogWsum += fw;
      }
      if (g.x <= 0.0) {
        continue;
      }
      vec3 qn = halfNormal(g);
      float w = spatial / (1.0 + abs(qz - z) / (z * 0.01 + 1.0));
      float nd = max(dot(n, qn), 0.0);
      nd *= nd;
      nd *= nd;
      w *= nd * nd;

      shSum += texelFetch(sceneShadow, q, 0) * w;
      wsum += w;
    }
  }
  fog = volumetricFog != 1 ? vec4(0.0, 0.0, 0.0, 1.0) : !withFog ? upsampleFog(px, z)
    : fogWsum > 1e-6 ? fogSum / fogWsum : texelFetch(sceneFog, clamp(ivec2(hp + 0.5), ivec2(0), maxAo), 0);
  vec4 sh = wsum > 1e-4 ? shSum / wsum : texelFetch(sceneShadow, clamp(ivec2(hp + 0.5), ivec2(0), maxAo), 0);
  shadow = sh.r;
  ground = sh.b;
  skyUpsampled = sh.a;
  return wsum > 1e-4 ? sh.g : 1.0;
}

// the color of a room's fill, linear, of luminance 1 (BuildingLights.FILL_TINT)
uniform vec3 interiorTint;
// sky visibility at which a spot counts as fully indoors, and as not indoors at all
#define INTERIOR_SKY_CLOSED 0.1
#define INTERIOR_SKY_OPEN 0.4

// The sky this pixel would show if nothing stood in front of it, in sRGB: what the world melts into at its edge.
vec3 skyBehind(vec2 px, vec3 dir, vec3 fogColor, bool sunAndSky) {
  if (sunAndSky && skyVoid == 0 && proceduralSky == 1) {
    vec3 sky = atmosphereSky(px, dir, cameraPos, sunDir, moonDir, sunAltitude, time);
    sky = lightningOverSky(sky, dir, cameraPos);
    return envSky(sky, fogColor, -dir.y);
  }
  // what the sky branch of main() leaves there: the fog color in a void or underground, else the game's clear
  // color (with the game's own skybox, the color its fog has)
  return skyVoid == 1 || (envCave == 1 && proceduralSky == 1) ? fogColor : clientSkyColor;
}

// ---------------------------------------------------------------------------------------------------------------
// Point lights: only the lights listed in this pixel's cluster are evaluated

int clusterAt(ivec2 px, float z) {
  return clusterEntry((vec2(px) + 0.5 - vec2(sceneViewport.xy)) / vec2(sceneViewport.zw), z);
}

int clusterLightCount(int entry) {
  LightMask m = clusterLights(entry);
  int count = 0, li;
  while (nextLight(m, li)) {
    ++count;
  }
  return count;
}

// One lamp's light on a surface at camera-relative p with normal n, where open of it gets there: on its front into sum,
// and through thin foliage from behind into through.
void lampOnSurface(Lamp lamp, float open, vec3 p, vec3 n, vec3 viewDir, float transmissive, inout vec3 sum,
    inout vec3 through) {
  vec3 l = lamp.pos - p;
  float d2 = dot(l, l);
  float r2 = lamp.radius * lamp.radius;
  if (d2 >= r2 || open <= 0.0) {
    return;
  }
  vec3 ld = l * inversesqrt(max(d2, 1.0));
  vec3 c = lamp.color * (lampFalloff(d2, r2) * open);
  float ndl = dot(n, ld);
  sum += c * max(ndl, 0.0);
  // thin foliage is also lit from behind: magic tree canopies glow around the lamps inside them
  through += c * transmission(ndl, dot(-ld, viewDir), transmissive);
}

// The lamps' light on a surface at camera-relative p with normal n, the smooth normal the sun uses, from the lamps of
// cluster entry (light/lamp.glsl): on its front, and through thin foliage from behind into through. Surfaces turned
// away receive neither. The face normal faceN only moves the point off its surface for the wall check.
//
// The lamps the light map carries in full (lampOnMap) come from its read, once: a cell holds the at most four that
// light it, with how much of each gets there (four a cell, the user's cap), so the point takes those and no other, the
// walls walked for them only on a tile with a wall (lampMapLampOpen). The cluster's list says whether any of them
// reaches the point, and gives the lamps the map doesn't carry yet, each stopped by its walls (lampOpen).
vec3 lampsOnSurface(int entry, vec3 p, vec3 n, vec3 faceN, float transmissive, out vec3 through) {
  vec3 viewDir = transmissive > 0.0 ? normalize(-p) : vec3(0.0);
  // off the surface along its face's own normal: a smooth normal tilts toward a wall's top and ends
  vec3 wallFrom = p + faceN * LAMP_WALL_BIAS;
  bool backToo = transmissive > 0.0;
  vec3 sum = vec3(0.0);
  through = vec3(0.0);
  bool mapped = false;
  LightMask lamps = clusterLights(entry);
  int li;
  while (nextLight(lamps, li)) {
    vec4 sphere = lampSphere(li);
    if (!lampSphereReaches(sphere, p, n, backToo)) {
      continue;
    }
    if (lampOnMap(sphere)) {
      mapped = true;
      continue;
    }
    Lamp lamp = lampWith(li, sphere);
    lampOnSurface(lamp, lampOpen(lamp, p, wallFrom, false), p, n, viewDir, transmissive, sum, through);
  }
  if (mapped) {
    vec4 vis;
    ivec4 ids;
    lampMapRead(p, wallFrom, vis, ids);
    for (int k = 0; k < 4; ++k) {
      int ml = ids[k] > 0 && vis[k] > 0.0 ? lampOfMapId(ids[k]) : -1;
      if (ml < 0) {
        continue;
      }
      vec4 sphere = lampSphere(ml);
      if (lampSphereReaches(sphere, p, n, backToo)) {
        Lamp lamp = lampWith(ml, sphere);
        lampOnSurface(lamp, lampMapLampOpen(lamp, vis[k], p, wallFrom), p, n, viewDir, transmissive, sum, through);
      }
    }
  }
  return sum;
}

// ---------------------------------------------------------------------------------------------------------------

// Transparent surfaces were lit as they were drawn, into their own layer; put them over the lit scene
vec4 overTransparent(vec4 a, vec4 base) {
  return vec4(a.rgb + base.rgb * (1.0 - a.a), base.a);
}

// ---------------------------------------------------------------------------------------------------------------

#include "water.glsl"

// ---------------------------------------------------------------------------------------------------------------

#include "weather_ground.glsl"

// ---------------------------------------------------------------------------------------------------------------

// Whether the sky can show in the scene pixel q: through a sample no geometry covers, or (sample 0's) the world's fade
// at its edge. With MSAA the sky shows behind a silhouette through the samples that miss.
bool skyShowsAt(ivec2 q) {
  for (int s = 0; s < SCENE_SAMPLES; ++s) {
    if (isSky(texelFetch(sceneDepth, q, s).r) || texelFetch(sceneColor, q, s).a < 0.996) {
      return true;
    }
  }
  return edgeFadeAmount(reconstructPosition(vec2(q) + 0.5, texelFetch(sceneDepth, q, 0).r) + cameraPos) > 0.002;
}

// The half resolution clouds (into cloudHalf). The pass is scissored to the rows that can hold cloud
// (Clouds.skyRows), over a target cleared to clear sky. cloudsAllSky: every texel over the horizon, whatever the
// scene shows, for a result the next frame keeps (Composite's half rate), so sky that geometry uncovers there finds
// its clouds.
uniform int cloudsAllSky;

vec4 cloudsHalf() {
  // the ray through the middle of the scene pixels this texel covers
  vec2 c = gl_FragCoord.xy * halfRatio;
  vec3 dir = normalize(reconstructPosition(c, 1.0));
  vec3 skyDir = cloudSkyDir(dir);
  // at and below the clouds' horizon cloudLayer gives clear sky whatever the scene shows: tested first, the texels
  // under it skip the scene fetches (the rows the scissor keeps for the water, in the pass shared with it)
  if (-skyDir.y <= CLOUD_HORIZON.x) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }
  // Only where the sky shows: in the scene pixels this texel covers and the ones around them that the sky's
  // filtering of the clouds reaches (atmosphere.glsl's cloudsFiltered, a texel and a half out), or behind
  // geometry's edges or its fade at the edge of the world. Elsewhere clear sky. The corners of that square and its
  // middle are tested.
  bool needed = cloudsAllSky == 1;
  ivec2 last = sceneTextureSize() - 1;
  float reach = halfRatio * 1.5 + 1.0;
  for (int i = 0; i < 5 && !needed; ++i) {
    vec2 at = i < 4 ? c + vec2(i & 1, i >> 1) * (2.0 * reach) - reach : c;
    needed = skyShowsAt(clamp(ivec2(at), ivec2(0), last));
  }
  if (!needed) {
    return vec4(0.0, 0.0, 0.0, 1.0);
  }
  float pixelAngle = length(normalize(reconstructPosition(c + vec2(0.0, 1.0), 1.0)) - dir);
  // jittered per cloud texel: the noise is only even at a stride of one texel, at a scene pixel stride of two
  // it ramps across the sky and combs the clouds into bands
  return cloudLayer(skyDir, cameraPos, sunDir, moonDir, sunAltitude, pixelAngle, cloudJitter(gl_FragCoord.xy),
    CLOUD_STEPS);
}

// What the scene holds for one sample of a pixel
struct SceneSample {
  vec4 color;     // a: 1 where geometry was drawn, 0 on the clear
  float depth;
  vec3 normal;    // as stored, n * 0.5 + 0.5
  vec4 material;  // materialUnpack
  vec3 faceNormal;
  float end;      // fake water: the depth its colour goes by, world units (the uploaders')
  float anchor;   // anything else: the snow's noise at its object's anchor tile (materialSnowAnchor)
};

SceneSample sceneSample(ivec2 px, int s) {
  vec4 target = texelFetch(sceneMaterial, px, s);
  return SceneSample(texelFetch(sceneColor, px, s), texelFetch(sceneDepth, px, s).r, texelFetch(sceneNormal, px, s).rgb,
    materialUnpack(target), materialFaceNormal(target), materialEnd(target), materialSnowAnchor(target));
}

// Lights one scene sample of the pixel px: sky or surface, then the transparent layer over it, then the fog.
// transparent is the pixel's, already lit, premultiplied: the wet ground's coverage test and the final blend read it.
vec4 shadeScene(ivec2 px, SceneSample sm, vec4 transparent) {
  vec4 color = sm.color;
  float d = sm.depth;
  bool inside = inViewport(px);
  bool sunAndSky = outdoors == 1;
  // the procedural sky shows; a black void or a cave shows the fog color instead
  bool openSky = sunAndSky && skyVoid == 0 && proceduralSky == 1;
  bool voidSky = skyVoid == 1 || (envCave == 1 && proceduralSky == 1);
  vec3 fogColor = envFog(openSky ? skyHorizon(sunAltitude) : clientSkyColor, sunAndSky && skyVoid == 0,
    sunAltitude);

  if (debugView == DEBUG_DEPTH) {
    // log scale from the near plane out to ~100 tiles
    float v = isSky(d) ? 1.0 : clamp(log(viewDepth(d) / NEAR) / log(12800.0 / NEAR), 0.0, 1.0);
    return vec4(vec3(v), 1.0);
  }

  if (debugView == DEBUG_NORMALS) {
    return vec4(sm.normal, 1.0);
  }

  // where the composite draws the sky itself, a sample no geometry covers shows it
  bool replaceSky = inside && (openSky || voidSky);
  if (isSky(d) || (replaceSky && color.a <= 0.004)) {
    if (compositePart > 0 && compositePart < 5) { return vec4(0.0); }
    if (replaceSky) {
      // a point on the near plane gives the view ray; the camera is at the origin of camera-relative space
      vec3 dir = normalize(reconstructPosition(vec2(px) + 0.5, 1.0));
      color.rgb = openSky ? skyBehind(vec2(px) + 0.5, dir, fogColor, true) : fogColor;
      // looking steeply down past the world (into a trapdoor, off a cliff) there is no sky, only the dark under it
      color.rgb *= 1.0 - voidBelow(dir);
    }
    // the color target's alpha says whether geometry covers it; the window wants opaque
    vec4 result = overTransparent(transparent, vec4(color.rgb, 1.0));
    // 0 is sky in both depth conventions
    result.rgb = applyVolumetricFog(px, result.rgb, 0.0);
    // the sky's broad gradients band in the 8-bit window: a triangular dither of one step hides it
    vec2 q = vec2(px);
    result.rgb += (fract(52.9829189 * fract(dot(q, vec2(0.06711056, 0.00583715))))
      - fract(52.9829189 * fract(dot(q + 0.5, vec2(0.00583715, 0.06711056))))) / 255.0;
    return result;
  }

  vec3 n = decodeNormal(sm.normal);
  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  vec4 material = sm.material;
  // a fall's face holds its pools' bed colour (SceneUploader): lit as their bed is, as ground facing up (water.glsl
  // waterFake)
  if (materialFakeWater(material) && waterFakeFall(sm.faceNormal)) {
    n = vec3(0.0, -1.0, 0.0);
  }
  float emissive = material.r;
  float transmissive = material.g;

  float ao = 1.0;
  float halfShadow = 1.0;
  vec4 fog;
  float ground = 0.0;
  vec3 indirect = vec3(0.0);
  bool fogDone = false;
  if ((aoEnabled == 1 || shadowsEnabled == 1 || volumetricFog == 1 || weatherGroundOn == 1
    || skylightOn == 1) && inside) {
    ao = upsampleAoFog(px, viewDepth(d), n, fog, halfShadow, ground);
    ao = aoEnabled == 1 ? clamp(1.0 - (1.0 - ao) * aoStrength, 0.0, 1.0) : 1.0;
    fogDone = volumetricFog == 1;
  }
  // snow and puddles change the surface itself, before it is lit
  vec2 wet = compositePart != 5 && inside && weatherGroundOn == 1
    ? weatherGroundSurface(color.rgb, n, p, p + cameraPos, vec2(px) + 0.5, material, sm.anchor, transparent.a, ground)
    : vec2(0.0);

  float shadow = 1.0;
  vec3 light = vec3(ao);
  if (compositePart <= 2 && sunAndSky && lightingEnabled == 1 && inside) {
    float ndl = dot(n, casterDir);
    // faces turned away from the light get none of it, so their shadow only matters to water and the debug view
    bool needShadow = ndl > 0.0 || transmissive > 0.0 || material.b >= 0.25 || debugView == DEBUG_SHADOWS;
    if (shadowsEnabled == 1 && emissive < 1.0) {
      shadow = halfShadow;
    }
    if (needShadow) {
      shadow *= cloudShadow(p + cameraPos, casterDir);
    }
    shadow = overcastShadow(shadow);
    vec3 caster = strikeCaster(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude));
    float up = max(-n.y, 0.0);
    if (compositePart != 2) {
    vec3 plain = ambientLight(sunAltitude) + skyFill(n, sunAltitude, up);
    indirect = skylightAmbient(plain) * envAmbient * ao;
    // the daylight fill: sun put back inside shadows where the sky is open (open question 1 of
    // docs/117hd-world-shading.md: 117 HD has none)
    float daylightFill = skylightOn == 1 ? DAYLIGHT_FILL * clamp(skyUpsampled, 0.0, 1.0) : 0.0;
    indirect += caster * envSun * max(ndl, 0.0) * (1.0 - shadow) * daylightFill * ao;
    }
    light = indirect
      + caster * envSun * max(ndl, 0.0) * shadow * mix(1.0, ao, AO_DIRECT);
    // A room's fill: the interior brightness, one level for every room (RoomLight: the setting and the time of day,
    // not its lamps), spread flat and directionless over the whole room like ambient, darkened by AO as the ambient is.
    int roomId = roomOfSurface(p + cameraPos, n);
    if (roomId > 0) {
      // how closed in the spot is: a room with its roof hidden still counts, a covered gateway open at both ends
      // doesn't, and the fill fades out toward a room's doorways and open sides instead of ending at its tiles' edge
      float enclosed = skylightOn == 1 ? 1.0 - smoothstep(INTERIOR_SKY_CLOSED, INTERIOR_SKY_OPEN, skyUpsampled) : 1.0;
      light += interiorTint * roomFillLevel(roomId) * enclosed * ao;
    }
    if (transmissive > 0.0) {
      // light through the leaf isn't occluded by the creases SSAO sees on its front
      light += caster * envSun * transmission(ndl, dot(-casterDir, normalize(-p)), transmissive) * shadow;
    }
  } else if (compositePart <= 2 && lightingEnabled == 1 && inside) {
    // Underground: no sun, and the game's own baked shading is removed, so a fixed soft key light from above
    // keeps shapes readable. Point lights do the rest.
    light = envCaveLight(n) * ao;
    indirect = light;
  }

  if (compositePart == 1) { return vec4(indirect, 0.0); }
  if (compositePart == 2) { return vec4(light - indirect, shadow); }
  if (compositePart == 3) light = vec3(0.0);
  int cluster = clusterAt(px, viewDepth(d));
  if ((compositePart == 0 || compositePart == 3) && pointLightsEnabled == 1 && inside) {
    vec3 through;
    light += lampsOnSurface(cluster, p, n, sm.faceNormal, transmissive, through) * mix(1.0, ao, AO_DIRECT);
    // the canopy interior SSAO darkens is exactly where light inside a tree shines through
    light += through;
  }
  if (compositePart == 3) { return vec4(light, 0.0); }
  if (compositePart == 4) {
    vec4 sun = texelFetch(compositeParts, ivec3(px, 1), 0);
    light = texelFetch(compositeParts, ivec3(px, 0), 0).rgb + sun.rgb
      + texelFetch(compositeParts, ivec3(px, 2), 0).rgb;
    shadow = sun.a;
  }
  // emissive surfaces show their own color: no shading, shadows or AO, but fog below still applies
  light = emissiveLight(light, emissive);

  if (debugView == DEBUG_LIGHT_CLUSTERS) {
    // lights evaluated per pixel: blue 0, green ~4, red 8+
    float c = pointLightsEnabled == 1 ? float(clusterLightCount(cluster)) / 8.0 : 0.0;
    return vec4(clamp(vec3(c * 2.0 - 1.0, 1.0 - abs(c * 2.0 - 1.0), 1.0 - c * 2.0), 0.0, 1.0), 1.0);
  }

  if (debugView == DEBUG_MATERIALS) {
    // emissive red, transmissive green, over the dimmed scene
    vec3 tint = vec3(emissive, transmissive, 0.0);
    return vec4(mix(color.rgb * 0.35, tint, max(emissive, transmissive) * 0.8), 1.0);
  }

  if (debugView == DEBUG_AO) {
    return vec4(vec3(ao), 1.0);
  }
  if (debugView == DEBUG_SKYLIGHT) {
    // sky visibility, red where it isn't worked out
    return vec4(skylightOn == 1 ? vec3(skyUpsampled) : vec3(0.5, 0.0, 0.0), 1.0);
  }
  if (debugView == DEBUG_SHADOWS) {
    return vec4(vec3(shadow), 1.0);
  }
  if (debugView == DEBUG_LIGHTING) {
    return vec4(linearToSrgb(clamp(light * 0.35, 0.0, 1.0)), 1.0);
  }

  vec3 lit;
  // Where nothing but the volumetric fog is left to change the lit color, it stays linear and is fogged as it is,
  // instead of going to sRGB and back (applyFogSample). Not on the faces models draw as water: waterUnder only holds
  // within the real water's rect, so a fountain was water only while a river or moat was in view.
  bool linearLit = false;
  if (compositePart != 5) {
    lit = shadeLinear(color.rgb, light);
    linearLit = fogDone && compositePart == 0 && wet.x <= 0.0 && transparent == vec4(0.0)
      && !waterUnder(px, material.b) && !materialFakeWater(material);
    if (!linearLit) {
      lit = linearToSrgb(lit);
      if (wet.x > 0.0) {
        lit = weatherPuddleWater(lit, p, light, wet, vec2(px) + 0.5);
      }
      // a face a model lays on a terrain fall as water is the bed of the fall's real surface over it (water_vert.glsl):
      // the pools above and below it and the fall are one surface
      bool fakeFall = materialFakeWater(material) && waterFakeFall(sm.faceNormal);
      bool over;
      lit = waterOver(px, p, color.rgb, shadow, lit, material.b, fakeFall, over);
      if (materialFakeWater(material) && !(fakeFall && over)) {
        lit = waterFake(px, p, sm.faceNormal, color.rgb, shadow, sm.end, lit);
      }
      if (compositePart == 4) { return vec4(lit, 1.0); }
    }
  } else {
    lit = texelFetch(compositeParts, ivec3(px, 0), 0).rgb;
  }
  float distanceFog = envFogAmount(p, (p + cameraPos).xz);
  // the world fades into the sky behind it toward the draw distance, so where the culling stops never shows
  float edge = inside ? edgeFadeAmount(p + cameraPos) : 0.0;
  if (linearLit) {
    if (distanceFog <= 0.0 && edge <= 0.002) {
      return vec4(applyFogLinear(lit, fog), 1.0);
    }
    lit = linearToSrgb(lit);
  }
  lit = mix(lit, fogColor, distanceFog);
  if (edge > 0.002) {
    lit = mix(lit, skyBehind(vec2(px) + 0.5, normalize(p), fogColor, sunAndSky), edge);
  }
  vec4 result = overTransparent(transparent, vec4(lit, 1.0));
  result.rgb = fogDone ? applyFogSample(result.rgb, fog) : applyVolumetricFog(px, result.rgb, d);
  return result;
}

#if MSAA_SAMPLES > 0
// The samples of a pixel are one surface when their depth, normal and material agree within these: a view depth
// relative to the sample's (a plane at a grazing angle spans ~0.2% over a pixel's samples), the stored normal's and
// material's channels. Their colors may differ, as the triangles meeting inside a flat surface's pixel do.
#define MSAA_DEPTH_TOLERANCE 0.005
#define MSAA_NORMAL_TOLERANCE 0.03
#define MSAA_MATERIAL_TOLERANCE 0.05

// A pixel's samples are split into two surfaces: the samples that agree with sample 0 (depth, normal and material)
// are one, all the others a second, each shaded with the mean of its samples' colors (the second with its first
// sample's depth, normal and material) and weighted by its share of the samples. Where the pixel is one surface that
// is the single shading the no-MSAA path does, with the colors antialiased; at a silhouette against the sky, or a fold
// between faces, each side is lit with its own values and the lit results are averaged, not the normals. The full
// pass shades the first surface of every pixel, the edge pass (COMPOSITE_EDGES) the second where there is one, blended
// over it by its share: each program holds the shading once, so the pixels that are one surface pay no more than
// without MSAA. Both in one program, the shading inlined twice, the composite took nine times as long.
bool sameSurface(SceneSample a, float vz, ivec2 px, int t) {
  float dt = texelFetch(sceneDepth, px, t).r;
  if (isSky(dt) != isSky(a.depth) || (!isSky(dt) && abs(viewDepth(dt) - vz) > MSAA_DEPTH_TOLERANCE * vz)) {
    return false;
  }
  return all(lessThanEqual(abs(texelFetch(sceneNormal, px, t).rgb - a.normal), vec3(MSAA_NORMAL_TOLERANCE)))
    && all(lessThanEqual(abs(materialUnpack(texelFetch(sceneMaterial, px, t)) - a.material), vec4(MSAA_MATERIAL_TOLERANCE)));
}

#if COMPOSITE_PASS == COMPOSITE_MARK
// whether the pixel has a second surface: the edge pass's own test, alone
bool twoSurfaces(ivec2 px) {
  SceneSample first = sceneSample(px, 0);
  float vz = viewDepth(first.depth);
  for (int t = 1; t < MSAA_SAMPLES; ++t) {
    if (!sameSurface(first, vz, px, t)) {
      return true;
    }
  }
  return false;
}
#elif COMPOSITE_PASS == COMPOSITE_EDGES
// the second surface, its share of the samples in alpha; none: discarded. It lies under the transparent geometry of
// its own samples, averaged.
vec4 shadeSamples(ivec2 px) {
  SceneSample first = sceneSample(px, 0);
  float vz = viewDepth(first.depth);
  vec4 colors = vec4(0.0);
  vec4 transparent = vec4(0.0);
  int count = 0;
  int other = -1;
  for (int t = 1; t < MSAA_SAMPLES; ++t) {
    if (!sameSurface(first, vz, px, t)) {
      colors += texelFetch(sceneColor, px, t);
      transparent += texelFetch(sceneAlpha, px, t);
      ++count;
      other = other < 0 ? t : other;
    }
  }
  if (other < 0) {
    discard;
  }
  SceneSample second = sceneSample(px, other);
  second.color = colors / float(count);
  return vec4(shadeScene(px, second, transparent / float(count)).rgb, float(count) / float(MSAA_SAMPLES));
}
#elif COMPOSITE_PASS == COMPOSITE_FULL
// the first surface; whether the pixel has a second, the edge pass's own test, marked in the depth (Composite.draw)
// It lies under the transparent geometry of its own samples, averaged.
vec4 shadeSamples(ivec2 px) {
  SceneSample first = sceneSample(px, 0);
  float vz = viewDepth(first.depth);
  vec4 colors = first.color;
  vec4 transparent = texelFetch(sceneAlpha, px, 0);
  int count = 1;
  for (int t = 1; t < MSAA_SAMPLES; ++t) {
    if (sameSurface(first, vz, px, t)) {
      colors += texelFetch(sceneColor, px, t);
      transparent += texelFetch(sceneAlpha, px, t);
      ++count;
    }
  }
  gl_FragDepth = count < MSAA_SAMPLES ? 1.0 : 0.0;
  first.color = colors / float(count);
  return shadeScene(px, first, transparent / float(count));
}
#endif
#endif

void main() {
#if COMPOSITE_PASS == COMPOSITE_MARK
  // only its stencil writes count
#if MSAA_SAMPLES > 0
  if (!twoSurfaces(ivec2(gl_FragCoord.xy))) {
    discard;
  }
#endif
  FragColor = vec4(0.0);
#elif COMPOSITE_PASS != COMPOSITE_FULL && COMPOSITE_PASS != COMPOSITE_EDGES
  // the half resolution passes: the clouds, the water's surface (water.glsl), or both
#if COMPOSITE_PASS != COMPOSITE_WATER
  FragColor = cloudsHalf();
#endif
#if COMPOSITE_PASS != COMPOSITE_CLOUDS
  waterShadeHalf(ivec2(gl_FragCoord.xy));
#endif
#else
  ivec2 px = ivec2(gl_FragCoord.xy);
#if MSAA_SAMPLES > 0
  // each surface under its own samples' transparent geometry
  FragColor = shadeSamples(px);
#else
  // transparent geometry over this pixel (lit as it is drawn)
  FragColor = shadeScene(px, sceneSample(px, 0), texelFetch(sceneAlpha, px, 0));
#endif
#endif
}
