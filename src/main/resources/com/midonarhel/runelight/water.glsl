// Water, shaded by the composite over the lowered water bed (WaterMap.java, Water.java, WaterSurface.java).
//
// The surface is real geometry: the water pass (water_vert.glsl, water_frag.glsl) draws it, moved by the swell and the
// chop, wherever it is in front of the opaque scene, and leaves how far away it is along the view ray and its normal,
// type, foam and speed in waterSurface. The bed and anything else under the water are ordinary pixels of the scene
// buffers; here they are seen through that surface: refracted, lit by the caustics, fading into the type's depth
// color over a short depth, then the surface over them as 117 HD draws it (BSD-2, see LICENSE): its own color lit,
// mostly the sky's reflection, at least half opaque and more so at a slant. Objects standing in the water are
// covered by the real surface, so the waterline follows the waves. Soft broad highlights from the sun and moon,
// mild glitter, lamp glints in their rooms.
// Included by composite_frag.glsl after its lighting helpers (shadows, point light clusters).

#include "water_common.glsl"

uniform int waterEnabled;
uniform usampler2D waterSurface;  // r: floatBitsToUint(distance to the surface), 0 none; g: packed (waterUnpack)
uniform int waterReflections;     // a few screen-space taps for the world in the reflection
uniform float waterClarity;       // multiplier on how deep the bed stays visible
uniform int waterCaustics;
uniform int waterDebug;

// Under water the bank's color goes wet: a little darker, cooler and less saturated over BED_WET_DEPTH
#define WET_SAND 0.86
#define BED_WET_TINT vec3(0.90, 0.95, 1.0)
#define BED_DESATURATE 0.25
#define BED_WET_DEPTH 48.0
// how far the bed seen through the water shifts per unit of surface slope, world units, once the water is deep
#define REFRACT 300.0
#define REFRACT_DEPTH 70.0
// vertical depth over which the surface fades in from the waterline
#define SOFT_EDGE 10.0
// How much of the surface covers what is under it: the type's base opacity once the water is SURFACE_DEPTH deep
// (clear over the shallows), at least FRESNEL_BASE rising to all of it at a slant as (1 - cos)^FRESNEL_POWER, and at
// least OPACITY_SLANT of 1 - the view's elevation. 117 HD's is the base opacity everywhere and a straight line from
// 0.4 (FRESNEL_POWER 1): the water hid the bed, the caustics and the depth colour at the game's usual camera angles.
#define SURFACE_DEPTH 64.0
#define FRESNEL_BASE 0.15
#define FRESNEL_POWER 2.0
#define OPACITY_SLANT 0.4
// the sky is reflected off a flattened normal (117 HD): the waves barely move it
#define SKY_TILT 0.2
// Highlights. Sheen: a broad soft lobe of the sun or moon on the waves (Blinn-Phong exponent, strength). Glitter:
// its reflection on the waves' slopes exaggerated GLITTER_SLOPE times (117 HD reflects the disk that way), a soft
// path toward the camera of GLITTER_ROUGH width.
#define SHEEN_GLOSS 60.0
#define SHEEN 0.10
#define GLITTER_SLOPE 1.6
#define GLITTER_ROUGH 0.1
#define GLITTER 0.3
#define LAMP_SPECULAR 0.4
#define LAMP_ALONG 0.14
#define LAMP_ACROSS 0.06
// what is seen through a wave face turned toward the camera is a little brighter, turned away darker
#define WAVE_SHADE 0.6
// 117 HD's caustics: their map (Water.java), two copies scrolling apart and the darker one kept, colored fringes
// from the channels being offset (baked into the texture). World units per repeat; seconds per cycle of each copy.
#define CAUSTIC_SCALE 298.67
#define CAUSTIC_PERIOD vec2(17.0, 23.0)
// The kept copy's values: about CAUSTIC_MEAN on average, CAUSTIC_LINE on the brightest 1% (measured on the map).
// The light on the bed gains CAUSTICS times the direct light per unit above the mean, so the lines brighten it and
// the cells between them darken it a little (down to CAUSTIC_RANGE.x, up to .y); none at CAUSTIC_DEPTH. 117 HD
// multiplies the bed by 1 + 16 times the value, which brightens all of it.
#define CAUSTIC_MEAN 0.145
#define CAUSTIC_LINE 0.30
#define CAUSTIC_RANGE vec2(-0.7, 3.0)
// by moonlight, and on things standing in the water rather than the bed
#define CAUSTICS 1.6
#define CAUSTIC_DEPTH 512.0
#define CAUSTIC_MOON 0.35
#define CAUSTIC_OBJECTS 0.5
// screen-space reflection: taps along the reflected ray, growing, then halving steps onto the hit
#define SSR_STEPS 4
#define SSR_FIRST_STEP 96.0
#define SSR_GROWTH 2.0
#define SSR_REFINE 3

// The water pass's packed surface: normal x, z (11 bits each), type (3), foam (4), speed (3)
void waterUnpack(uint g, out vec3 nrm, out int type, out float foam, out float fast) {
  float nx = float(g & 2047u) / 2047.0 * 2.0 - 1.0;
  float nz = float(g >> 11 & 2047u) / 2047.0 * 2.0 - 1.0;
  nrm = vec3(nx, -sqrt(max(1.0 - nx * nx - nz * nz, 0.0)), nz);
  type = clamp(int(g >> 22 & 7u), 1, WATER_TYPES - 1);
  foam = float(g >> 25 & 15u) / 15.0;
  fast = float(g >> 29 & 7u) / 7.0;
}

float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
}

// A scroll of a whole number of repeats per wrap of waterTime, about one per `period` seconds
float waterCycle(float period) {
  return fract(floor(WATER_TIME_WRAP / period + 0.5) * (waterTime / WATER_TIME_WRAP));
}

// The caustics at world xz: 0 to 1 per channel
vec3 waterCaustic(vec2 xz, float lod) {
  const vec2 DIR = vec2(1.0, -2.0);
  vec2 uv = xz / CAUSTIC_SCALE;
  vec3 a = textureLod(waterNoise, vec3(uv + waterCycle(CAUSTIC_PERIOD.x) * DIR, 1.0), lod).rgb;
  vec3 b = textureLod(waterNoise, vec3(uv * 1.5 - waterCycle(CAUSTIC_PERIOD.y) * DIR, 1.0), lod).rgb;
  return min(a, b);
}

// A glint off the water: Ward's anisotropic lobe around the normal, `along` the RMS slope toward the camera, `across`
// sideways. Reflections of lights on rippled water are streaks pointing at the viewer.
float waterGlint(vec3 nrm, vec3 toLight, vec3 toCamera, vec3 tAlong, vec3 tAcross, float along, float across) {
  vec3 h = toLight + toCamera;
  h *= inversesqrt(max(dot(h, h), 1e-8));
  float nh = dot(nrm, h);
  if (nh <= 0.0)
    return 0.0;
  vec3 hs = h / nh - nrm;
  float ea = dot(hs, tAlong) / along, ec = dot(hs, tAcross) / across;
  float fh = 0.02 + 0.98 * pow(1.0 - clamp(dot(h, toCamera), 0.0, 1.0), 5.0);
  return exp(-(ea * ea + ec * ec)) / (3.14159 * along * across) * fh * clamp(dot(nrm, toLight) * 4.0, 0.0, 1.0);
}

// Where the reflected ray is at t: its pixel, and how far it is behind the depth buffer there (negative in front)
// (1e9 off screen, -1e9 against the sky)
float waterRayBehind(vec3 s, vec3 r, float t, out ivec2 ip, out vec2 ndc) {
  vec4 clip = viewProj * vec4(s + r * t, 1.0);
  ip = ivec2(0);
  ndc = vec2(2.0);
  if (clip.w <= NEAR)
    return 1e9;
  ndc = clip.xy / clip.w;
  if (any(greaterThan(abs(ndc), vec2(1.0))))
    return 1e9;
  ip = ivec2((ndc * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy));
  float dq = texelFetch(sceneDepth, ip, 0).r;
  return isSky(dq) ? -1e9 : clip.w - viewDepth(dq);
}

// The world as seen in the reflection: a few growing steps along the reflected ray against the depth buffer, then
// halving steps onto the crossing, the hit taking its own color under a rough guess of its light. a: how much to
// trust it.
vec4 waterTraceReflection(vec3 s, vec3 r, vec3 approxLight) {
  float stepLen = SSR_FIRST_STEP;
  float prev = 0.0;
  float t = stepLen;
  ivec2 ip;
  vec2 ndc;
  for (int i = 0; i < SSR_STEPS; ++i) {
    float behind = waterRayBehind(s, r, t, ip, ndc);
    if (behind >= 1e9)
      break;
    if (behind > 0.0 && behind < stepLen * 2.5) {
      float lo = prev, hi = t;
      for (int j = 0; j < SSR_REFINE; ++j) {
        float mid = 0.5 * (lo + hi);
        ivec2 mp;
        vec2 mn;
        float b = waterRayBehind(s, r, mid, mp, mn);
        if (b > 0.0 && b < 1e9) {
          hi = mid;
          ip = mp;
          ndc = mn;
        } else {
          lo = mid;
        }
      }
      // water reflecting water shows the sky instead
      if (texelFetch(sceneMaterial, ip, 0).b > 0.75 || texelFetch(waterSurface, ip, 0).r != 0u)
        break;
      vec2 edge = smoothstep(vec2(1.0), vec2(0.75), abs(ndc));
      float reach = 1.0 - smoothstep(stepLen * 1.5, stepLen * 2.5, behind);
      return vec4(srgbToLinear(texelFetch(sceneColor, ip, 0).rgb) * approxLight, edge.x * edge.y * reach);
    }
    prev = t;
    stepLen *= SSR_GROWTH;
    t += stepLen;
  }
  return vec4(0.0);
}

// The bank's color (sRGB) gone wet `depth` under the surface
vec3 waterBedAlbedo(vec3 land, float depth) {
  float k = smoothstep(0.0, BED_WET_DEPTH, depth);
  vec3 bed = mix(land, vec3(dot(land, vec3(0.299, 0.587, 0.114))), BED_DESATURATE * k);
  return bed * mix(vec3(1.0), BED_WET_TINT, k) * WET_SAND;
}

// 117 HD's fade of what is under the water (sRGB multiplier): into the depth color over the first stretch, then darker
vec3 waterDepthTint(int type, float depth) {
  vec3 fade = WATER_FADE[type];
  fade.xy *= waterClarity;
  vec3 dc = WATER_DEPTH_COLOR[type];
  return depth < fade.x ? mix(vec3(1.0), dc, depth / fade.x)
    : dc * mix(1.0, fade.z, clamp((depth - fade.x) / (fade.y - fade.x), 0.0, 1.0));
}

vec3 waterShoulder(vec3 c) {
  return mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
}

// p: camera-relative position of this pixel's scene surface (the bed, or something else under the water)
// n, albedo (sRGB), light (linear), shadow: its normal, color, light and sun shadow from the composite
// lit: its lit color (sRGB); mask: the material target's water channel (frag.glsl)
// Returns the color with the water over it, or lit unchanged; moves p to the water surface for the fog.
vec3 waterOver(ivec2 px, inout vec3 p, vec3 n, vec3 albedo, vec3 light, float shadow, vec3 lit, float mask,
  vec3 fogColor) {
  // land terrain is never under water: the surface runs on under the banks, hidden, and never over land
  if (waterEnabled == 0 || mask < 0.25)
    return lit;
  uvec2 ws = texelFetch(waterSurface, px, 0).rg;
  if (ws.r == 0u)
    return lit;
  float t = uintBitsToFloat(ws.r);
  float dist = length(p);
  if (t >= dist)
    return lit;
  vec3 v = p / dist;
  vec3 s = v * t;  // camera-relative surface point
  vec3 sw = s + cameraPos;
  vec3 world = p + cameraPos;
  bool bed = mask > 0.75;

  if (waterDebug == 1) {
    p = s;
    return vec3(float(ws.g & 255u), float(ws.g >> 8 & 255u), float(ws.g >> 16 & 255u)) / 255.0;
  }

  vec3 nrm;
  int type;
  float foam, fast;
  waterUnpack(ws.g, nrm, type, foam, fast);
  vec4 look = WATER_LOOK[type];
  // vertical depth of this pixel below the surface (y down), and how far the view travels under it
  float depth = p.y - s.y;
  float under0 = max(depth, 0.0);
  vec2 slope = nrm.xz / nrm.y;
  vec3 toCamera = -v;
  float viewZ = (viewProj * vec4(s, 1.0)).w;
  // world units one pixel covers on the surface, stretched where it is seen at a slant
  float pixelNear = length(reconstructPosition(vec2(px) + vec2(1.5, 0.5), 1.0) - reconstructPosition(vec2(px) + 0.5, 1.0));
  float footprint = pixelNear * viewZ / viewDepth(1.0) * inversesqrt(max(abs(v.y), 0.05));
  vec2 fwd = v.xz * inversesqrt(max(dot(v.xz, v.xz), 1e-6));
  vec3 tAlong = vec3(fwd.x, 0.0, fwd.y), tAcross = vec3(-fwd.y, 0.0, fwd.x);

  // ---- light falling on the surface
  bool lightOn = lightingEnabled == 1;
  vec3 caster = vec3(0.0);
  float surfShadow = 1.0;
  vec3 waterLight;
  if (outdoors == 1 && lightOn) {
    caster = (sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    if (shadowsEnabled == 1) {
      // one filtered tap: the surface is soft and moving anyway
      vec4 lc4 = lightProjRel * vec4(s, 1.0);
      vec2 uv = lc4.xy * 0.5 + 0.5;
      float z = depthZeroToOne == 1 ? lc4.z : lc4.z * 0.5 + 0.5;
      float bias = 4.0 * (2.0 * shadowRadius / shadowMapSize) / (2.0 * shadowDepthRange);
      if (all(greaterThan(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0))) && z < 1.0)
        surfShadow = textureLod(shadowCompare, vec3(uv, z - bias), 0.0);
    }
    surfShadow = overcastShadow(surfShadow * cloudShadow(sw, casterDir));
    waterLight = (ambientLight(sunAltitude) + skyLight(sunAltitude) * max(-nrm.y, 0.0)) * envAmbient
      + caster * max(dot(nrm, casterDir), 0.0) * surfShadow;
  } else if (lightOn) {
    waterLight = envCaveLight(vec3(0.0, -1.0, 0.0));
  } else {
    waterLight = vec3(1.0);
  }

  vec3 lampSpec = vec3(0.0);
  if (pointLightsEnabled == 1 && lightOn) {
    int cbase = clusterBase(px, viewZ);
    int count = clusterLightCount(cbase);
    // lights in buildings stay there (rooms.glsl)
    ivec2 room = count > 0 ? lightRoomAt(sw - vec3(0.0, ROOM_NORMAL_BIAS, 0.0)) : ivec2(-1, 0);
    for (int i = 0; i < count; ++i) {
      int li = int(texelFetch(clusterData, cbase + 1 + i).r);
      vec4 posRadius = texelFetch(lightData, li * 2);
      vec3 l = posRadius.xyz - s;
      float d2 = dot(l, l);
      float r2 = posRadius.w * posRadius.w;
      if (d2 >= r2)
        continue;
      vec4 colorCode = texelFetch(lightData, li * 2 + 1);
      vec3 color = colorCode.rgb * roomShare(colorCode.w, room);
      float att = 1.0 - sqrt(d2 / r2);
      att *= att;
      vec3 ld = l * inversesqrt(max(d2, 1.0));
      waterLight += color * att * max(-ld.y, 0.2) * 0.5;
      lampSpec += color * (att * waterGlint(nrm, ld, toCamera, tAlong, tAcross, LAMP_ALONG, LAMP_ACROSS));
    }
  }

  // ---- what is seen through the surface: refracted, lit with the caustics, fading into the depth color
  vec3 under;
  {
    vec3 seen = albedo;
    bool seenBed = bed;
    vec3 seenP = p;
    // the bed wobbles under the waves: strongest where the water is deep enough to bend the view far
    vec3 pr = p + vec3(slope.x, 0.0, slope.y) * (REFRACT * smoothstep(0.0, REFRACT_DEPTH, under0));
    vec4 clip = viewProj * vec4(pr, 1.0);
    if (clip.w > NEAR) {
      ivec2 rp = ivec2((clip.xy / clip.w * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy));
      if (inViewport(rp)) {
        float dr = texelFetch(sceneDepth, rp, 0).r;
        float mr = texelFetch(sceneMaterial, rp, 0).b;
        // only take the offset pixel if it is under the water too, or things above it would show in it
        vec3 q = reconstructPosition(vec2(rp) + 0.5, dr);
        if (!isSky(dr) && mr >= 0.25 && q.y > s.y + 2.0) {
          seen = texelFetch(sceneColor, rp, 0).rgb;
          seenBed = mr > 0.75;
          seenP = q;
        }
      }
    }
    if (seenBed)
      seen = waterBedAlbedo(seen, under0);
    vec3 bedLight = light;
    if (waterCaustics == 1 && outdoors == 1 && lightOn && look.x < 0.7) {
      // the direct light reaching the bed (shadow has the clouds and overcast in it), faint by moonlight and on
      // objects, gone by CAUSTIC_DEPTH
      float fade = max(1.0 - under0 / (CAUSTIC_DEPTH * waterClarity), 0.0);
      float k = max(dot(n, casterDir), 0.0) * shadow * (sunIsCaster == 1 ? 1.0 : CAUSTIC_MOON)
        * (bed ? 1.0 : CAUSTIC_OBJECTS) * CAUSTICS * smoothstep(0.0, 6.0, under0) * fade * fade
        * (1.0 - 0.5 * fast);
      if (k > 0.004) {
        // the light reaching this point entered the water toward the sun, bent steeper by refraction
        vec2 sunXZ = casterDir.xz;
        float sinI = length(sunXZ);
        float sinT = min(sinI / 1.33, 0.95);
        vec2 entry = seenP.xz + cameraPos.xz + sunXZ * (sinT * inversesqrt(1.0 - sinT * sinT) / max(sinI, 1e-4) * under0);
        float lod = waterLod(footprint * dist / t, CAUSTIC_SCALE);
        vec3 c = (waterCaustic(entry, lod) - CAUSTIC_MEAN) / (CAUSTIC_LINE - CAUSTIC_MEAN);
        bedLight += caster * (k * clamp(c, CAUSTIC_RANGE.x, CAUSTIC_RANGE.y));
      }
    }
    under = shade(seen, bedLight) * waterDepthTint(type, under0);
    under *= max(1.0 + WAVE_SHADE * dot(slope, v.xz) / max(length(v.xz), 0.25), 0.0);
  }

  // ---- the surface (117 HD): its own color lit, mostly the sky's reflection, at least its base opacity
  float vn = clamp(dot(toCamera, nrm), 0.0, 1.0);
  float fresnel = mix(FRESNEL_BASE, 1.0, pow(1.0 - vn, FRESNEL_POWER));
  vec3 skyN = normalize(nrm * vec3(SKY_TILT, 1.0, SKY_TILT));
  vec3 r = reflect(v, skyN);
  r.y = min(r.y, -0.02);
  r = normalize(r);
  vec3 reflection;
  if (outdoors == 1 && proceduralSky == 1) {
    float elev = -r.y;
    vec3 sc = mix(skyHorizon(sunAltitude), skyZenith(sunAltitude), pow(clamp(elev, 0.0, 1.0), 0.45));
    reflection = srgbToLinear(envSky(sc, fogColor, elev));
  } else {
    reflection = srgbToLinear(fogColor) * (outdoors == 1 ? 1.0 : 0.5);
  }
  if (waterReflections == 1) {
    vec3 approx = outdoors == 1
      ? (ambientLight(sunAltitude) + skyLight(sunAltitude) * 0.5) * envAmbient + caster * 0.5
      : envCaveLight(vec3(0.0, -1.0, 0.0));
    vec4 hit = waterTraceReflection(s, reflect(v, normalize(nrm * vec3(0.6, 1.0, 0.6))), approx);
    reflection = mix(reflection, hit.rgb, hit.a);
  }
  vec3 surface = mix(shade(WATER_SURFACE_COLOR[type], waterLight), linearToSrgb(clamp(reflection * fresnel, 0.0, 1.0)),
    look.y);
  float alpha = max(look.x * smoothstep(0.0, SURFACE_DEPTH, under0), max(fresnel, (1.0 + toCamera.y) * OPACITY_SLANT));
  vec3 color = mix(under, surface, alpha);
  // foam lies on the surface
  color = mix(color, shade(WATER_FOAM_COLOR[type], waterLight), foam);

  // ---- highlights: a broad soft sheen and a mild glitter path from the sun or moon, lamp glints
  vec3 spec = lampSpec * LAMP_SPECULAR;
  if (outdoors == 1 && lightOn) {
    vec3 h = normalize(casterDir + toCamera);
    float sheen = pow(max(dot(nrm, h), 0.0), SHEEN_GLOSS) * (SHEEN_GLOSS + 8.0) / 25.0 * SHEEN * fresnel;
    vec3 steep = normalize(vec3(nrm.x * GLITTER_SLOPE, nrm.y, nrm.z * GLITTER_SLOPE));
    // far off the waves are finer than a pixel: the path widens instead of sparkling
    float rough = GLITTER_ROUGH + 0.02 * waterLod(footprint, 190.0);
    float glitter = waterGlint(steep, casterDir, toCamera, tAlong, tAcross, rough * 1.6, rough) * GLITTER;
    spec += caster * ((sheen + glitter) * surfShadow * look.z * (1.0 - foam));
  }
  // over the waterline the surface fades in: no hard edge where it meets the bank or a leg in the water
  vec3 result = linearToSrgb(clamp(waterShoulder(srgbToLinear(color) + spec), 0.0, 1.0));
  result = mix(lit, result, smoothstep(0.0, SOFT_EDGE, depth + 2.0));

  p = s;
  return result;
}
