// Water, shaded by the composite over the lowered water bed (WaterMap.java, Water.java, WaterSurface.java).
//
// The surface is real geometry: the water pass (water_vert.glsl, water_frag.glsl) draws it, moved by the swell and the
// chop, wherever it is in front of the opaque scene, and leaves how far away it is, its normal, type and foam, the
// water's depth by the shore and the caustics on the bed below in waterSurface. The bed and anything else under the
// water are ordinary pixels of the scene buffers; here they are seen through that surface: refracted, lit by the
// caustics, fading into the type's depth color over a short depth, then the surface over them after 117 HD (BSD-2,
// see LICENSE): its own color lit and the sky's reflection, which the ripples carried by the current move about.
// Objects standing in the water are covered by the real surface, so the waterline follows the waves. Soft broad
// highlights from the sun and moon, mild glitter, lamp glints in their rooms.
// Included by composite_frag.glsl after its lighting helpers (shadows, point light clusters).

#include "water_common.glsl"

uniform int waterEnabled;
uniform usampler2D waterSurface;  // RGBA16UI, 64 bits packed by water_frag.glsl (waterUnpack); 0 no water
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
// vertical depth over which the surface fades in from the waterline; within 1 / WATERLINE_DEPTH of the depth the
// shore distance gives, the bed's own depth sets the water's color
#define SOFT_EDGE 32.0
#define WATERLINE_DEPTH 4.0
// The bed is lit under the water as if it were flat (by BED_FLAT, past the first BED_FLAT_DEPTH units): the light
// down there is scattered anyway, and the steep sides of the pits in shore tiles lit by their own normals showed as
// dark and bright squares
#define BED_FLAT 0.85
#define BED_FLAT_DEPTH 16.0
// share of the bed's own ambient light (skylight, AO) that gives way to the open water's, and how much of the sky
// light (straight up) that counts
#define BED_SCATTER 0.8
// how much deeper than they are things standing in the water count for the water's color over them
#define OBJECT_DEPTH 8.0
#define OBJECT_FADE 150.0
#define BED_SKY 0.8
// How much of the surface covers what is under it: the type's base opacity once the water is SURFACE_DEPTH deep
// (clear over the shallows), at least FRESNEL_BASE rising to all of it at a slant as (1 - cos)^FRESNEL_POWER, and at
// least OPACITY_SLANT of 1 - the view's elevation. 117 HD's is the base opacity everywhere and a straight line from
// 0.4 (FRESNEL_POWER 1): the water hid the bed, the caustics and the depth colour at the game's usual camera angles.
#define SURFACE_DEPTH 64.0
#define FRESNEL_BASE 0.15
#define FRESNEL_POWER 2.0
#define OPACITY_SLANT 0.4
// The sky is reflected off a normal whose slope is SKY_TILT of the waves': the ripples carried by the current move the
// sky's gradient and clouds about, which is what shows the flow. 117 HD flattens it to 0.2.
#define SKY_TILT 0.75
#define SKY_CLOUD_MAX 1.25
// Highlights. Sheen: a broad soft lobe of the sun or moon on the waves (Blinn-Phong exponent, strength). Glitter:
// its reflection on the waves' slopes exaggerated GLITTER_SLOPE times (117 HD reflects the disk that way), a soft
// path toward the camera of GLITTER_ROUGH width.
#define SHEEN_GLOSS 60.0
#define SHEEN 0.10
#define GLITTER_SLOPE 1.6
#define GLITTER_ROUGH 0.1
#define GLITTER 0.3
#define LAMP_SPECULAR 0.2
// the most a lamp's glint adds (linear): a lamp next to the water left a pale blotch on it
#define LAMP_MAX 0.35
// the moon's glitter and sheen are this much of the sun's, and the surface's shadow (radius in shadow map texels)
#define MOON_SPECULAR 0.35
#define SURFACE_SHADOW_SOFT 3.0
#define LAMP_ALONG 0.14
#define LAMP_ACROSS 0.06
// what is seen through a wave face turned toward the camera is a little brighter, turned away darker
#define WAVE_SHADE 0.6
// Caustics: 117 HD's, carried by the flow, worked out by the water pass (water_frag.glsl) as their value above the
// mean in units of the brightest lines'. The light on the bed gains CAUSTICS times the direct light per unit, so the
// lines brighten it and the cells between them darken it a little (down to CAUSTIC_RANGE.x, up to .y); none at
// CAUSTIC_DEPTH. 117 HD multiplies the bed by 1 + 16 times the value, which brightens all of it.
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
// its blur (pixels, plus per world unit along the ray), the darkest a reflected object gets as a share of the sky's
// reflection, the distance along the ray over which it fades out, and how much of the sky it replaces at most
#define SSR_BLUR 4.0
#define SSR_BLUR_GROWTH 0.012
#define SSR_FLOOR 0.6
#define SSR_FADE vec2(500.0, 1600.0)
#define SSR_STRENGTH 0.65
// the world is reflected off a calmer normal than the sky (slopes times this): wobbling reflections of posts and reeds
// broke up into dark blobs
#define SSR_TILT 0.35

// how far the surface target's distance reaches (water_frag.glsl)
#define WATER_REACH 20480.0

// The water pass's packed surface (water_frag.glsl): the distance to it, its normal, type, foam, the water's depth
// going by the shore (smooth across tiles), and the caustics on the bed below
void waterUnpack(uvec4 w, out float t, out vec3 nrm, out int type, out float foam, out float shoreDepth,
  out vec3 caustic) {
  uint a = w.r | w.g << 16, b = w.b | w.a << 16;
  float nx = float(a & 1023u) / 1023.0 * 2.0 - 1.0;
  float nz = float(a >> 10 & 1023u) / 1023.0 * 2.0 - 1.0;
  nrm = vec3(nx, -sqrt(max(1.0 - nx * nx - nz * nz, 0.0)), nz);
  type = clamp(int(a >> 20 & 7u), 1, WATER_TYPES - 1);
  foam = float(a >> 23 & 7u) / 7.0;
  float d = float(a >> 26 & 63u) / 63.0;
  shoreDepth = d * d * 512.0;
  t = float(b & 65535u) / 65535.0 * WATER_REACH;
  float g = float(b >> 16 & 255u) / 255.0 * 4.0 - 1.0;
  caustic = g + vec3(float(b >> 24 & 15u) / 15.0 - 0.5, 0.0, float(b >> 28 & 15u) / 15.0 - 0.5);
}

float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
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

// The world as seen in the reflection: a few growing steps along the reflected ray against the depth buffer, from a
// start jittered per pixel (so the hits don't line up into stair steps), then halving steps onto the crossing. The
// hit is blurred over SSR_BLUR taps around it, wider further along the ray, each tap that isn't the world (sky,
// water) counting as the sky; the world's colors are lit by `light` (the light on the surface, lamps and all: what
// stands in the water is lit about the same) and kept no darker than SSR_FLOOR of the sky, so dark posts and reeds
// tint the reflection instead of cutting black blocks out of it. a: how much to trust it, fading with distance.
vec4 waterTraceReflection(vec3 s, vec3 r, vec3 light, vec3 sky, vec2 px) {
  float stepLen = SSR_FIRST_STEP;
  float prev = 0.0;
  float t = stepLen * (0.5 + hash13(vec3(px, 7.0)));
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
      // the blur: a cross of taps SSR_BLUR pixels and more apart, growing along the ray
      float radius = SSR_BLUR + hi * SSR_BLUR_GROWTH;
      vec3 sum = vec3(0.0);
      float world = 0.0;
      ivec2 last = textureSize(sceneColor, 0) - 1;
      for (int k = 0; k < 5; ++k) {
        ivec2 o = k == 0 ? ivec2(0) : ivec2(radius * vec2(k == 1 ? 1.0 : k == 2 ? -1.0 : 0.3, k == 3 ? 1.0 : k == 4 ? -1.0 : 0.3));
        ivec2 q = clamp(ip + o, ivec2(0), last);
        // water reflecting water, and the sky, show the sky
        bool isWorld = !isSky(texelFetch(sceneDepth, q, 0).r) && texelFetch(sceneMaterial, q, 0).b <= 0.75
          && texelFetch(waterSurface, q, 0).b == 0u;
        vec3 c = isWorld ? max(srgbToLinear(texelFetch(sceneColor, q, 0).rgb) * light, sky * SSR_FLOOR) : sky;
        sum += c;
        world += isWorld ? 1.0 : 0.0;
      }
      vec2 edge = smoothstep(vec2(1.0), vec2(0.75), abs(ndc));
      float reach = 1.0 - smoothstep(stepLen * 1.5, stepLen * 2.5, behind);
      float near = 1.0 - smoothstep(SSR_FADE.x, SSR_FADE.y, hi);
      return vec4(sum / 5.0, edge.x * edge.y * reach * near * (world / 5.0) * SSR_STRENGTH);
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
// n, albedo (sRGB), light (linear), shadow: its normal, color, light and sun shadow from the composite; indirect: the
// part of light that is ambient and sky light (skylight and AO in it)
// lit: its lit color (sRGB); mask: the material target's water channel (frag.glsl)
// Returns the color with the water over it, or lit unchanged; moves p to the water surface for the fog.
vec3 waterOver(ivec2 px, inout vec3 p, vec3 n, vec3 albedo, vec3 light, vec3 indirect, float shadow, vec3 lit,
  float mask, vec3 fogColor) {
  // land terrain is never under water: the surface runs on under the banks, hidden, and never over land
  if (waterEnabled == 0 || mask < 0.25)
    return lit;
  uvec4 ws = texelFetch(waterSurface, px, 0);
  if (ws.b == 0u)
    return lit;
  float t;
  vec3 nrm, causticValue;
  int type;
  float foam, shoreDepth;
  waterUnpack(ws, t, nrm, type, foam, shoreDepth, causticValue);
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
    uint a = ws.r | ws.g << 16;
    return vec3(float(a & 255u), float(a >> 8 & 255u), float(a >> 16 & 255u)) / 255.0;
  }

  vec4 look = WATER_LOOK[type];
  // vertical depth of this pixel below the surface (y down)
  float depth = p.y - s.y;
  float under0 = max(depth, 0.0);
  // The depth the water's color goes by: the shore distance's, smooth across tiles, so the pit the bed sinks into in
  // the middle of every shore tile doesn't show as a dark square; only right at the waterline the bed's own depth
  // fades the water in.
  float colorDepth = min(shoreDepth, under0 * WATERLINE_DEPTH + 4.0);
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
      vec4 lc4 = lightProjRel * vec4(s, 1.0);
      vec2 uv = lc4.xy * 0.5 + 0.5;
      float z = depthZeroToOne == 1 ? lc4.z : lc4.z * 0.5 + 0.5;
      float bias = 4.0 * (2.0 * shadowRadius / shadowMapSize) / (2.0 * shadowDepthRange);
      // eight filtered taps over a few texels: one tap, even four, drew the shadow map's texels as hard dark blocks
      // under piers and reeds
      if (all(greaterThan(uv, vec2(0.0))) && all(lessThan(uv, vec2(1.0))) && z < 1.0) {
        const vec2 DISK[8] = vec2[](vec2(-0.61, -0.33), vec2(0.17, -0.89), vec2(0.82, -0.27), vec2(0.55, 0.62),
          vec2(-0.08, 0.41), vec2(-0.72, 0.58), vec2(0.29, 0.05), vec2(-0.27, -0.03));
        float sum = 0.0;
        for (int k = 0; k < 8; ++k)
          sum += textureLod(shadowCompare, vec3(uv + DISK[k] * (SURFACE_SHADOW_SOFT / shadowMapSize), z - bias), 0.0);
        surfShadow = sum / 8.0;
      }
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
    float seenDepth = under0;
    // the bed wobbles under the waves: strongest where the water is deep enough to bend the view far
    vec3 pr = p + vec3(slope.x, 0.0, slope.y) * (REFRACT * smoothstep(0.0, REFRACT_DEPTH, under0));
    vec4 clip = viewProj * vec4(pr, 1.0);
    if (clip.w > NEAR) {
      ivec2 rp = ivec2((clip.xy / clip.w * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy));
      if (inViewport(rp)) {
        float dr = texelFetch(sceneDepth, rp, 0).r;
        float mr = texelFetch(sceneMaterial, rp, 0).b;
        // only take the offset pixel if it is under the water too, seen through it from here, and further away than
        // the surface, or posts and reeds standing in the water would be copied into it
        vec3 q = reconstructPosition(vec2(rp) + 0.5, dr);
        if (!isSky(dr) && mr >= 0.25 && q.y > s.y + 2.0 && length(q) > t + 2.0
          && texelFetch(waterSurface, rp, 0).b != 0u) {
          seen = texelFetch(sceneColor, rp, 0).rgb;
          seenBed = mr > 0.75;
          seenDepth = q.y - s.y;
        }
      }
    }
    if (seenBed)
      seen = waterBedAlbedo(seen, under0);
    else
      // Things standing in the water (posts, reeds, legs) sink into its color faster than the bed: their dark
      // submerged parts, wobbling with the refraction, read as dark silhouettes of the things above
      colorDepth = max(colorDepth, min(seenDepth * OBJECT_DEPTH, max(shoreDepth, OBJECT_FADE)));
    vec3 bedLight = light;
    if (outdoors == 1 && lightOn) {
      // Under the water the ambient light is scattered: the sky visibility (a top-down map of 1/8 tile cells) and the
      // AO the bed got under piers, reeds and fallen trees mostly give way to the open water's, or they showed through
      // the water as dark blocky shapes of the things above, worst at night when the ambient light is most of it.
      vec3 open = (ambientLight(sunAltitude) + skyLight(sunAltitude) * BED_SKY) * envAmbient;
      bedLight += (open - indirect) * (BED_SCATTER * smoothstep(0.0, BED_FLAT_DEPTH, under0));
    } else if (lightOn) {
      bedLight += (envCaveLight(vec3(0.0, -1.0, 0.0)) - indirect) * (BED_SCATTER * smoothstep(0.0, BED_FLAT_DEPTH, under0));
    }
    if (bed && outdoors == 1 && lightOn) {
      float flatten = BED_FLAT * smoothstep(0.0, BED_FLAT_DEPTH, under0);
      bedLight += caster * (shadow * flatten * (max(-casterDir.y, 0.0) - max(dot(n, casterDir), 0.0)));
      bedLight = max(bedLight, vec3(0.0));
    }
    if (waterCaustics == 1 && outdoors == 1 && lightOn && look.x < 0.7) {
      // the direct light reaching the bed (shadow has the clouds and overcast in it), faint by moonlight and on
      // objects, gone by CAUSTIC_DEPTH
      float fade = max(1.0 - under0 / (CAUSTIC_DEPTH * waterClarity), 0.0);
      float k = (bed ? max(-casterDir.y, 0.0) : max(dot(n, casterDir), 0.0)) * shadow * (sunIsCaster == 1 ? 1.0 : CAUSTIC_MOON)
        * (bed ? 1.0 : CAUSTIC_OBJECTS) * CAUSTICS * smoothstep(0.0, 6.0, under0) * fade * fade;
      bedLight += caster * (k * clamp(causticValue, CAUSTIC_RANGE.x, CAUSTIC_RANGE.y));
    }
    under = shade(seen, bedLight) * waterDepthTint(type, colorDepth);
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
    // the sky as drawn this frame, clouds and all, when the sky cache has it: the current moves them about
    // No brighter than the sky's own colors there by SKY_CLOUD_MAX: lit cloud tops in the cache made pale blotches on
    // water at night and in the rain.
    if ((skyCacheMode & 2) != 0) {
      vec3 cached = textureLod(skyCache, skyCacheUv(cloudSkyDir(r)), 0.0).rgb;
      sc = linearToSrgb(clamp(min(cached, srgbToLinear(sc) * SKY_CLOUD_MAX), 0.0, 1.0));
    }
    reflection = srgbToLinear(envSky(sc, fogColor, elev));
  } else {
    reflection = srgbToLinear(fogColor) * (outdoors == 1 ? 1.0 : 0.5);
  }
  if (waterReflections == 1) {
    vec4 hit = waterTraceReflection(s, reflect(v, normalize(nrm * vec3(SSR_TILT, 1.0, SSR_TILT))), waterLight, reflection,
      vec2(px));
    reflection = mix(reflection, hit.rgb, hit.a);
  }
  vec3 surface = mix(shade(WATER_SURFACE_COLOR[type], waterLight), linearToSrgb(clamp(reflection * fresnel, 0.0, 1.0)),
    look.y);
  float alpha = max(look.x * smoothstep(0.0, SURFACE_DEPTH, colorDepth), max(fresnel, (1.0 + toCamera.y) * OPACITY_SLANT));
  vec3 color = mix(under, surface, alpha);
  // foam lies on the surface
  color = mix(color, shade(WATER_FOAM_COLOR[type], waterLight), foam);

  // ---- highlights: a broad soft sheen and a mild glitter path from the sun or moon, lamp glints
  vec3 spec = min(lampSpec * LAMP_SPECULAR, vec3(LAMP_MAX));
  if (outdoors == 1 && lightOn) {
    vec3 h = normalize(casterDir + toCamera);
    float sheen = pow(max(dot(nrm, h), 0.0), SHEEN_GLOSS) * (SHEEN_GLOSS + 8.0) / 25.0 * SHEEN * fresnel;
    vec3 steep = normalize(vec3(nrm.x * GLITTER_SLOPE, nrm.y, nrm.z * GLITTER_SLOPE));
    // far off the waves are finer than a pixel: the path widens instead of sparkling
    float rough = GLITTER_ROUGH + 0.02 * waterLod(footprint, 190.0);
    float glitter = waterGlint(steep, casterDir, toCamera, tAlong, tAcross, rough * 1.6, rough) * GLITTER;
    spec += caster * ((sheen + glitter) * surfShadow * look.z * (1.0 - foam) * (sunIsCaster == 1 ? 1.0 : MOON_SPECULAR));
  }
  // over the waterline the surface fades in: no hard edge where it meets the bank or a leg in the water
  vec3 result = linearToSrgb(clamp(waterShoulder(srgbToLinear(color) + spec), 0.0, 1.0));
  result = mix(lit, result, smoothstep(0.0, 1.0, depth / SOFT_EDGE));

  p = s;
  return result;
}
