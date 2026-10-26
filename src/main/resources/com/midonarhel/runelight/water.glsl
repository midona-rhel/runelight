// Water, shaded by the composite over the lowered water bed (WaterMap.java, Water.java, WaterSurface.java).
//
// The surface is real geometry: the water pass (water_vert.glsl, water_frag.glsl) draws it wherever it is in front of
// the opaque scene, and leaves how far away it is, its normal, look and foam, the water's depth by the shore and the
// caustics on the bed below in waterSurface.
//
// Looking down the water is clear over the shallows and colored by its depth, its surface tinted and lit, the waves
// shading what is seen through it; at a slant the surface turns into the sky, clouds and all, by Fresnel, with sharp
// reflections of the world. Sharp glints from the sun and moon, lamps' streaks, foam.
//
// Two passes of the composite. The first (COMPOSITE_WATER, or COMPOSITE_CLOUDS_WATER along with the clouds) runs at
// half resolution over the water only and works out the surface (waterShadeHalf), into waterShaded. The second, the
// full resolution composite, lights the bed as any ground and only blends: the bed tinted by its depth under the
// surface, then the surface over it (waterOver), using the full resolution surface for the edges.
// Included by composite_frag.glsl after its lighting helpers (shadows, point light clusters).

#include "water_common.glsl"

#define WATER_SLOTS 32

uniform int waterEnabled;
uniform usampler2D waterSurface;  // RGBA16UI, 64 bits packed by water_frag.glsl (waterUnpack), of the rect's pixels
                                  // (waterSurfaceAt); 0 no water
uniform usampler2D waterShaded;   // RGBA32UI, half resolution: the shaded surface (waterShadeHalf)
uniform int waterReflections;     // a few screen-space taps for the world in the reflection
uniform int waterCaustics;
uniform int waterDebug;
uniform int waterSlotType[WATER_SLOTS];  // each look's water type (Water.java)
uniform ivec4 waterRect;          // the pixels the surface can cover: x0, y0 to x1, y1 exclusive (WaterSurface)

layout(location = 1) out uvec4 WaterShadedOut;

// Vertical depth over which the water's color and surface fade in from the waterline over the bed and its caustics,
// so the water has no hard edge; within 1 / WATERLINE_DEPTH of the depth the shore distance gives, the bed's own depth
// sets the water's color
#define SOFT_EDGE 48.0
#define WATERLINE_DEPTH 1.0
// How much of the surface covers what is under it: the type's base opacity once the water is SURFACE_DEPTH deep
// (clear over the shallows), at least FRESNEL_BASE rising to all of it at a slant as (1 - cos)^FRESNEL_POWER, and at
// least OPACITY_SLANT of 1 - the view's elevation
#define SURFACE_DEPTH 64.0
#define FRESNEL_BASE 0.15
#define FRESNEL_POWER 2.0
#define OPACITY_SLANT 0.4
// what is seen through a wave face turned toward the camera is a little brighter, turned away darker
#define WAVE_SHADE 0.6
// under the water the bank's color goes wet: a little darker
#define WET_SAND 0.86
// The sky is reflected off a normal SKY_TILT times as steep as the waves', and the Fresnel goes by it too: each wave
// face catches a different part of the sky, the bright horizon toward the camera, the darker zenith away, which is
// what shows the waves from above (the waves themselves are too gentle to move the reflection much).
#define SKY_TILT 1.5
#define WAVE_SKY_TILT 7.0
// Underground there is no sky to reflect: the water reflects a black stand-in, a very dark tint of the cave's fog
// color, so it is told from the rock by its own dark color, the lamps on it and the cave in its screen-space reflections
#define CAVE_SKY_REFLECT 0.1
// how fast the reflected sky goes from the horizon's color to the zenith's as the reflection rises (an exponent on
// the elevation): low keeps the horizon to a narrow bright band, so the wave faces that catch it stand out
#define SKY_GRADIENT 0.45
// Highlights. Sheen: a faint tight lobe of the sun or moon on the waves (Blinn-Phong exponent, strength). Glitter:
// its reflection off the waves as if they were GLITTER_SLOPE times taller, so many small slopes face the sun, each
// catching it in a narrow lobe (GLITTER_ROUGH): many pinpoint glints instead of a wide smear. The moon's are
// MOON_SPECULAR of the sun's.
#define SHEEN_GLOSS 300.0
#define SHEEN 0.04
#define GLITTER_SLOPE 1.5
#define GLITTER_ROUGH 0.01
#define GLITTER 0.45
// How big the sun is in the water, as a multiple of its true size: the glints show over that much wider an area around
// the sun's mirror point. Slopes and lobe grow together, so each glint keeps its size, and its peak its brightness.
#define GLINT_SUN_SIZE 3.0
// The sun's mirror image on the water needs the sun's disc: under cloud it is spread over the whole sky, so the glints
// and the sheen fade out as the overcast closes, from the first cover to the second
#define GLINT_OVERCAST vec2(0.15, 0.6)
// how much of the normal's change across a pixel widens a glint's lobe (a share of the squared change: the lobe's
// variance grows by the slope's spread over the pixel)
#define GLITTER_FOOTPRINT 0.25
#define MOON_SPECULAR 0.35
// lamps: their streak's width along and across, strength, and the most one adds (linear)
#define LAMP_ALONG 0.03
#define LAMP_ACROSS 0.012
#define LAMP_SPECULAR 0.8
#define LAMP_MAX 2.0
// how much of a lamp's light the surface itself takes
#define LAMP_SURFACE 0.15
// how much of the sun's shadow on its surface the murkiest water lifts
#define MURKY_SHADOW_LIFT 0.85
// how much the murkiest water's foam takes its own color, MURKY_FOAM_LIGHTER times as light (sRGB)
#define MURKY_FOAM_TINT 0.75
#define MURKY_FOAM_LIGHTER 1.6

// Caustics: 117 HD's, carried by the flow, worked out by the water pass (water_frag.glsl) as their value above the
// mean in units of the brightest lines'. The bed gains CAUSTICS times the direct light per unit (between
// CAUSTIC_RANGE); fainter by moonlight.
#define CAUSTICS 1.1
// the caustics come in over CAUSTIC_EDGE below the waterline, their dark between the lines over CAUSTIC_DARK, and
// fade out between CAUSTIC_FADE's depths
#define CAUSTIC_EDGE 48.0
#define CAUSTIC_DARK 160.0
#define CAUSTIC_FADE vec2(24.0, 260.0)
#define CAUSTIC_RANGE vec2(-0.7, 3.0)
#define CAUSTIC_MOON 0.35
// screen-space reflection: taps along the reflected ray, growing, then halving steps onto the hit; the world is
// reflected off a calmer normal than the sky (slopes times SSR_TILT)
#define SSR_STEPS 4
#define SSR_FIRST_STEP 96.0
#define SSR_GROWTH 2.0
#define SSR_REFINE 3
#define SSR_TILT 1.0


// The foam in two flat tones with hard edges over the water, a pale tint and a bright core: the shares of the foam color
// over the water, and how far the foam's color is whitened (the shore waves' and the falls')
#define FOAM_TINT 0.55
#define FOAM_CORE 0.9
#define FOAM_WHITE 0.75

// The water pass's packed surface (water_frag.glsl): the distance to it, its normal, look slot, foam, the water's
// depth going by the shore (smooth across tiles), and the caustics on the bed below
void waterUnpack(uvec4 w, out float t, out vec3 nrm, out int slot, out float foam, out float shoreDepth,
  out float caustic) {
  uint a = w.r | w.g << 16, b = w.b | w.a << 16;
  float nx = float(a & 511u) / 511.0 * 2.0 - 1.0;
  float nz = float(a >> 9 & 511u) / 511.0 * 2.0 - 1.0;
  nrm = vec3(nx, -sqrt(max(1.0 - nx * nx - nz * nz, 0.0)), nz);
  slot = int(a >> 18 & 31u);
  foam = float(a >> 23 & 7u) / 7.0;
  float d = float(a >> 26 & 63u) / 63.0;
  shoreDepth = d * d * 512.0;
  t = float(b & 65535u) / 65535.0 * WATER_REACH;
  caustic = float(b >> 16 & 255u) / 255.0 * 4.0 - 1.0;
}

// The shore waves' foam at the pixel, in water_frag.glsl's full resolution coverage: x the tint, y the core
vec2 waterShoreOf(uvec4 w) {
  return vec2(float(w.a >> 8 & 15u), float(w.a >> 12 & 15u)) / 15.0;
}

// The shaded target's 16 bit values (GL 4.1 has no packHalf2x16): light 0 to 63 on a log scale, fine near black;
// coverage 0 to 1 linear
uint waterEncLight(float x) {
  return uint(clamp(log2(max(x, 0.0) + 1.0) / 6.0, 0.0, 1.0) * 65535.0 + 0.5);
}

float waterDecLight(uint u) {
  return exp2(float(u) / 65535.0 * 6.0) - 1.0;
}

uvec4 waterPackShaded(vec3 surface, float cover, vec3 spec) {
  return uvec4(waterEncLight(surface.r) | waterEncLight(surface.g) << 16,
    waterEncLight(surface.b) | uint(clamp(cover, 0.0, 1.0) * 65535.0 + 0.5) << 16,
    waterEncLight(spec.r) | waterEncLight(spec.g) << 16, waterEncLight(spec.b) | 1u << 16);
}

// 117 HD's fade of what is under the water (sRGB multiplier): into the depth color over the first stretch, then darker
vec3 waterDepthTint(int type, float depth) {
  vec3 fade = WATER_FADE[type];
  vec3 dc = WATER_DEPTH_COLOR[type];
  return depth < fade.x ? mix(vec3(1.0), dc, depth / fade.x)
    : dc * mix(1.0, fade.z, clamp((depth - fade.x) / (fade.y - fade.x), 0.0, 1.0));
}

int waterTypeOf(int slot) {
  return clamp(waterSlotType[slot], 1, WATER_TYPES - 1);
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
  // Schlick's Fresnel, (1 - cos)^5
  float f = 1.0 - clamp(dot(h, toCamera), 0.0, 1.0);
  float f2 = f * f;
  float fh = 0.02 + 0.98 * (f2 * f2 * f);
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
// halving steps onto the crossing (no jitter: at half resolution it showed as grain); the
// hit's color lit by `light` (the light on the surface: what stands in the water is lit about the same), sharp.
// a: how much to trust it.
vec4 waterTraceReflection(vec3 s, vec3 r, vec3 light, vec2 px) {
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
      // water reflecting water, and the sky, show the sky
      bool isWorld = !isSky(texelFetch(sceneDepth, ip, 0).r) && materialUnpack(texelFetch(sceneMaterial, ip, 0)).b <= 0.75
        && waterSurfaceAt(waterSurface, waterRect, ip).b == 0u;
      if (!isWorld)
        return vec4(0.0);
      vec2 edge = smoothstep(vec2(1.0), vec2(0.75), abs(ndc));
      float reach = 1.0 - smoothstep(stepLen * 1.5, stepLen * 2.5, behind);
      return vec4(srgbToLinear(texelFetch(sceneColor, ip, 0).rgb) * light, edge.x * edge.y * reach);
    }
    prev = t;
    stepLen *= SSR_GROWTH;
    t += stepLen;
  }
  return vec4(0.0);
}

// The sky seen along a reflected ray r (y down, so up into the sky is negative), linear: the horizon's color rising to
// the zenith's, or the cave's fog color. Also the puddles' (weather_ground.glsl).
vec3 waterSkyReflection(vec3 r) {
  vec3 fogColor = envFog(outdoors == 1 && proceduralSky == 1 ? skyHorizon(sunAltitude) : clientSkyColor,
    outdoors == 1 && skyVoid == 0, sunAltitude);
  if (outdoors == 1 && proceduralSky == 1) {
    float elev = -r.y;
    vec3 sc = mix(skyHorizon(sunAltitude), skyZenith(sunAltitude), pow(clamp(elev, 0.0, 1.0), SKY_GRADIENT));
    return srgbToLinear(envSky(sc, fogColor, elev));
  }
  if (outdoors == 0 && envCave == 1) {
    return srgbToLinear(fogColor) * CAVE_SKY_REFLECT;
  }
  return srgbToLinear(fogColor) * (outdoors == 1 ? 1.0 : 0.5);
}

vec3 waterShoulder(vec3 c) {
  return mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
}

// The lamps' light on the surface at camera-relative s (seen at pixel px, view depth viewZ): the baked lights
// (light_map.glsl) and the clusters', added to the surface's light as a little each (LAMP_SURFACE) and to lampSpec as
// their glints
void waterLamps(ivec2 px, vec3 s, float viewZ, vec3 nrm, vec3 toCamera, vec3 tAlong, vec3 tAcross,
  inout vec3 surfaceLight, inout vec3 lampSpec) {
  if (pointLightsEnabled != 1 || lightingEnabled != 1)
    return;
  // the baked lights (light_map.glsl), each as below
  int mapPlane = 0;
  if (lightMapOn()) {
    vec3 world = s + cameraPos;
    mapPlane = lightMapPlane(world);
    vec4 vis;
    ivec4 ids;
    if (lightMapSlots(world, world, mapPlane, vis, ids)) {
      for (int k = 0; k < 4; ++k) {
        vec3 color, ld;
        float lightY;
        if (lightMapLight(ids[k], vis[k], world, color, ld, lightY)) {
          surfaceLight += color * (max(-ld.y, 0.2) * LAMP_SURFACE);
          lampSpec += color * waterGlint(nrm, ld, toCamera, tAlong, tAcross, LAMP_ALONG, LAMP_ACROSS);
        }
      }
    }
  }
  int entry = clusterAt(px, viewZ);
  LightMask lights = clusterLights(entry);
  int li;
  while (nextLight(lights, li)) {
    vec4 posRadius = texelFetch(lightData, li * 2);
    vec3 l = posRadius.xyz - s;
    float d2 = dot(l, l);
    float r2 = posRadius.w * posRadius.w;
    if (d2 >= r2)
      continue;
    vec4 colorCode = texelFetch(lightData, li * 2 + 1);
    if (!lightOnStorey(colorCode.w, mapPlane)) {
      continue;
    }
    vec3 color = colorCode.rgb;
    float att = pointLightFalloff(d2, r2);
    vec3 ld = l * inversesqrt(max(d2, 1.0));
    surfaceLight += color * (att * max(-ld.y, 0.2) * LAMP_SURFACE);
    lampSpec += color * (att * waterGlint(nrm, ld, toCamera, tAlong, tAcross, LAMP_ALONG, LAMP_ACROSS));
  }
}

// ---------------------------------------------------------------------------------------------------------------
// The half resolution pass: for the half resolution pixel hpx, the nearest water surface among the full resolution
// pixels it covers (four at most), shaded: the surface's color (linear, foam in it), how much of what is under it the
// surface hides at least (Fresnel, the slant, foam; waterOver adds the depth's share), and the highlights on top
// (linear), packed by waterPackShaded. 0 where there is no water.
void waterShadeHalf(ivec2 hpx) {
  WaterShadedOut = uvec4(0u);
  // only the texels with a pixel in the rect: texel x reads pixels 2x and 2x + 1 (halfToFull); the water's own pass
  // is scissored to them as well, the one with the clouds covers every texel
  // the texels with a pixel in the surface's rect: texel h reads pixels from h * halfRatio (halfToFull)
  if (waterEnabled == 0 || any(lessThan(hpx, ivec2(floor(vec2(waterRect.xy) / halfRatio))))
    || any(greaterThanEqual(hpx, ivec2(ceil(vec2(waterRect.zw) / halfRatio)))))
    return;
  uvec4 ws = uvec4(0u);
  ivec2 px = ivec2(0);
  float t = 1e9;
  ivec2 first = halfToFull(hpx);
  for (int i = 0; i < 4; ++i) {
    // a half texel that is one scene pixel has no others to choose from
    if (i > 0 && halfRatio <= 1.0)
      break;
    ivec2 q = first + ivec2(i & 1, i >> 1);
    uvec4 w = waterSurfaceAt(waterSurface, waterRect, q);
    if (w.b == 0u)
      continue;
    float tq = float(w.b) / 65535.0 * WATER_REACH;
    if (tq < t) {
      t = tq;
      ws = w;
      px = q;
    }
  }
  if (ws.b == 0u)
    return;

  vec3 nrm;
  int slot;
  float foam, shoreDepth, caustic;
  waterUnpack(ws, t, nrm, slot, foam, shoreDepth, caustic);
  int type = waterTypeOf(slot);
  vec4 look = WATER_LOOK[type];
  vec3 v = normalize(reconstructPosition(vec2(px) + 0.5, 1.0));
  vec3 s = v * t;  // camera-relative surface point
  vec3 toCamera = -v;
  float viewZ = (viewProj * vec4(s, 1.0)).w;

  if (waterDebug != 0) {
    uint a = ws.r | ws.g << 16;
    vec3 c = srgbToLinear(vec3(float(a & 255u), float(a >> 8 & 255u), float(a >> 16 & 255u)) / 255.0);
    WaterShadedOut = waterPackShaded(c, 1.0, vec3(0.0));
    return;
  }

  // ---- light on the surface
  bool lightOn = lightingEnabled == 1;
  vec3 caster = vec3(0.0);
  vec3 surfaceLight = vec3(1.0);
  float shadow = 1.0;
  // how heavy the water is: its base opacity, from clear water's to swamp's
  float murk = smoothstep(WATER_LOOK[1].x, WATER_LOOK[5].x, look.x);
  if (outdoors == 1 && lightOn) {
    caster = strikeCaster(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    // the AO pass's sun shadow of what is under this pixel: close enough for the surface over it
    if (shadowsEnabled == 1)
      shadow = texelFetch(sceneShadow, min(hpx, textureSize(sceneShadow, 0) - 1), 0).r;
    shadow = overcastShadow(shadow * cloudShadow(s + cameraPos, casterDir));
    vec3 ambient = ambientLight(sunAltitude) * envAmbient;
    vec3 sky = skyLight(sunAltitude) * envAmbient;
    // Murky water's own color is light scattered back from within it, which a shadow on the surface only partly
    // takes away: the heavier the water, the less of the shadow it keeps
    float bodyShadow = mix(shadow, 1.0, MURKY_SHADOW_LIFT * murk);
    surfaceLight = ambient + sky * max(-nrm.y, 0.0) + caster * max(dot(nrm, casterDir), 0.0) * bodyShadow;
  } else if (lightOn) {
    surfaceLight = envCaveLight(vec3(0.0, -1.0, 0.0));
  }

  vec2 fwd = v.xz * inversesqrt(max(dot(v.xz, v.xz), 1e-6));
  vec3 tAlong = vec3(fwd.x, 0.0, fwd.y), tAcross = vec3(-fwd.y, 0.0, fwd.x);
  vec3 lampSpec = vec3(0.0);
  waterLamps(px, s, viewZ, nrm, toCamera, tAlong, tAcross, surfaceLight, lampSpec);
  // ---- what the surface reflects: the sky, and the world where the reflected ray finds it
  vec3 skyN = normalize(nrm * vec3(SKY_TILT, 1.0, SKY_TILT));
  float vn = clamp(dot(toCamera, skyN), 0.0, 1.0);
  float fresnel = mix(FRESNEL_BASE, 1.0, pow(1.0 - vn, FRESNEL_POWER));
  // The sky in the waves: as for the sun's glints, each wave taken WAVE_SKY_TILT times as steep, so the faces turned
  // away from the camera reach past the Fresnel's limit and show the sky, the ones turned toward it the water. How
  // much the surface covers what is under it stays the calm surface's (fresnel), so the water's color holds.
  vec3 waveN = normalize(nrm * vec3(WAVE_SKY_TILT, 1.0, WAVE_SKY_TILT));
  float waveSky = mix(FRESNEL_BASE, 1.0, pow(1.0 - clamp(dot(toCamera, waveN), 0.0, 1.0), FRESNEL_POWER));
  vec3 r = reflect(v, waveN);
  r.y = min(r.y, -0.02);
  r = normalize(r);
  vec3 reflection = waterSkyReflection(r);
  if (waterReflections == 1) {
    vec4 hit = waterTraceReflection(s, reflect(v, normalize(nrm * vec3(SSR_TILT, 1.0, SSR_TILT))), surfaceLight,
      vec2(px));
    reflection = mix(reflection, hit.rgb, hit.a);
  }

  // ---- highlights: a faint sheen from the sun or moon, lamp streaks (the sharp glints are the full resolution
  // blend's, waterGlitter)
  vec3 spec = min(lampSpec * LAMP_SPECULAR, vec3(LAMP_MAX));
  if (outdoors == 1 && lightOn) {
    vec3 h = normalize(casterDir + toCamera);
    float sheen = pow(max(dot(nrm, h), 0.0), SHEEN_GLOSS) * (SHEEN_GLOSS + 8.0) / 25.0 * SHEEN * fresnel;
    spec += caster * (sheen * shadow * look.z * (1.0 - foam) * (sunIsCaster == 1 ? 1.0 : MOON_SPECULAR)
      * (1.0 - smoothstep(GLINT_OVERCAST.x, GLINT_OVERCAST.y, weatherOvercast.x)));
  }

  // the surface (117 HD): its own color lit, mostly the sky's reflection; foam over it, folded in as waterOver would
  // lay it over the whole (a mix over a mix is one mix)
  vec3 surface = mix(srgbToLinear(WATER_SURFACE_COLOR[type]) * surfaceLight, reflection * waveSky, look.y);
  float cover = max(fresnel, (1.0 + toCamera.y) * OPACITY_SLANT);
  // the foam on murky water is its own water whipped up, a lighter shade of it rather than white
  vec3 foamColor = srgbToLinear(mix(WATER_FOAM_COLOR[type], WATER_SURFACE_COLOR[type] * MURKY_FOAM_LIGHTER,
    murk * MURKY_FOAM_TINT)) * surfaceLight;
  float covered = cover + foam * (1.0 - cover);
  surface = (surface * (cover * (1.0 - foam)) + foamColor * foam) / max(covered, 1e-4);
  WaterShadedOut = waterPackShaded(surface, covered, spec);
}

// A facet's normal taken GLITTER_SLOPE * GLINT_SUN_SIZE times as steep: the slopes that large reach the sun's
// mirror condition from GLINT_SUN_SIZE times farther from the mirror point, so the glints spread over that much more
// of the water, each as small as before (waterGlitter widens the lobe as much and squares its Ward norm back).
vec3 waterGlitterNormal(vec3 nrm) {
  return normalize(vec3(nrm.x * GLITTER_SLOPE * GLINT_SUN_SIZE, nrm.y, nrm.z * GLITTER_SLOPE * GLINT_SUN_SIZE));
}

// The normal the water pass left at pixel q, or none (y = 0) where there is no water
vec3 waterNormalAt(ivec2 q) {
  uvec4 ws = waterSurfaceAt(waterSurface, waterRect, q);
  if (ws.b == 0u)
    return vec3(0.0);
  uint a = ws.r | ws.g << 16;
  float nx = float(a & 511u) / 511.0 * 2.0 - 1.0;
  float nz = float(a >> 9 & 511u) / 511.0 * 2.0 - 1.0;
  return vec3(nx, -sqrt(max(1.0 - nx * nx - nz * nz, 0.0)), nz);
}

// The sun's or moon's glint off the steepened normal `steep` seen along v, its lobe widened by `footprint`, the
// squared change of the steepened normal over the pixel, times shadow (the sun's), foam and the type's shine
vec3 waterSunGlint(vec3 steep, vec3 v, float footprint, float shine, float foam, float shadow) {
  if (outdoors == 0 || lightingEnabled == 0)
    return vec3(0.0);
  vec2 fwd = v.xz * inversesqrt(max(dot(v.xz, v.xz), 1e-6));
  vec3 tAlong = vec3(fwd.x, 0.0, fwd.y), tAcross = vec3(-fwd.y, 0.0, fwd.x);
  float rough = GLITTER_ROUGH * GLINT_SUN_SIZE;
  float along = sqrt(rough * rough * 2.56 + footprint), across = sqrt(rough * rough + footprint);
  float glitter = waterGlint(steep, casterDir, -v, tAlong, tAcross, along, across);
  glitter *= GLITTER * GLINT_SUN_SIZE * GLINT_SUN_SIZE;
  vec3 caster = strikeSun(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
  return caster * (glitter * shadow * shine * (1.0 - foam) * (sunIsCaster == 1 ? 1.0 : MOON_SPECULAR)
    * (1.0 - smoothstep(GLINT_OVERCAST.x, GLINT_OVERCAST.y, weatherOvercast.x)));
}

// The sharp glints of the sun or moon on the surface at full resolution: v the view direction, shadow the sun's. A
// glint is a facet catching the sun, often smaller than a pixel: the lobe is widened by how much the (steepened)
// normal changes across the pixel, from its neighbours to the right and below, so the pixel shows the glint averaged
// over its footprint. Where the waves are fine against the pixels (far off, or at a low render scale) the glints are
// softer and dimmer instead of aliasing into specks or blocks; up close they stay pinpoints.
vec3 waterGlitter(ivec2 px, vec3 nrm, vec3 v, float shine, float foam, float shadow) {
  vec3 steep = waterGlitterNormal(nrm);
  ivec2 last = sceneTextureSize() - 1;
  vec3 nx = waterNormalAt(min(px + ivec2(1, 0), last));
  vec3 ny = waterNormalAt(min(px + ivec2(0, 1), last));
  // the slope's change over the pixel, squared; a neighbour off the water counts as no change
  vec3 dx = nx.y != 0.0 ? waterGlitterNormal(nx) - steep : vec3(0.0);
  vec3 dy = ny.y != 0.0 ? waterGlitterNormal(ny) - steep : vec3(0.0);
  return waterSunGlint(steep, v, (dot(dx, dx) + dot(dy, dy)) * GLITTER_FOOTPRINT, shine, foam, shadow);
}

// ---------------------------------------------------------------------------------------------------------------
// The full resolution blend.

// Whether water can be over pixel px, mask its material target's water channel: land terrain is never under water
// (the surface runs on under the banks, hidden, and never over land), nor is anything outside the rect
bool waterUnder(ivec2 px, float mask) {
  return waterEnabled != 0 && mask >= 0.25 && all(greaterThanEqual(px, waterRect.xy))
    && all(lessThan(px, waterRect.zw));
}

// p: camera-relative position of this pixel's scene surface (the bed, or something else under the water)
// albedo (sRGB), shadow: its color and sun shadow from the composite; lit: its lit color (sRGB); mask: the material
// target's water channel (frag.glsl)
// Returns the color with the water over it, or lit unchanged; moves p to the water surface for the fog.
vec3 waterOver(ivec2 px, inout vec3 p, vec3 albedo, float shadow, vec3 lit, float mask) {
  if (!waterUnder(px, mask))
    return lit;
  uvec4 ws = texelFetch(waterSurface, px - waterRect.xy, 0);
  if (ws.b == 0u)
    return lit;
  float t, foam, shoreDepth, caustic;
  vec3 nrm;
  int slot;
  waterUnpack(ws, t, nrm, slot, foam, shoreDepth, caustic);
  float dist = length(p);
  if (t >= dist)
    return lit;
  vec3 s = p / dist * t;
  // vertical depth of this pixel below the surface (y down)
  float depth = max(p.y - s.y, 0.0);

  // the half resolution shading around this pixel, bilinear over the texels that have water
  vec2 hp = fullToHalf(px);
  ivec2 h0 = ivec2(floor(hp));
  vec2 f = hp - vec2(h0);
  ivec2 last = textureSize(waterShaded, 0) - 1;
  vec4 surface = vec4(0.0);
  vec3 spec = vec3(0.0);
  float wsum = 0.0;
  for (int i = 0; i < 4; ++i) {
    ivec2 o = ivec2(i & 1, i >> 1);
    uvec4 w = texelFetch(waterShaded, clamp(h0 + o, ivec2(0), last), 0);
    if (w == uvec4(0u))
      continue;
    vec2 bw = mix(1.0 - f, f, vec2(o));
    float k = bw.x * bw.y + 1e-4;
    surface += vec4(waterDecLight(w.x & 65535u), waterDecLight(w.x >> 16), waterDecLight(w.y & 65535u),
      float(w.y >> 16) / 65535.0) * k;
    spec += vec3(waterDecLight(w.z & 65535u), waterDecLight(w.z >> 16), waterDecLight(w.w & 65535u)) * k;
    wsum += k;
  }
  if (wsum <= 0.0)
    return lit;
  surface /= wsum;
  spec /= wsum;
  if (waterDebug != 0) {
    p = s;
    return linearToSrgb(clamp(surface.rgb, 0.0, 1.0));
  }

  int type = waterTypeOf(slot);
  // The depth the water's color goes by: the shore distance's, smooth across tiles, so the pit the bed sinks into in
  // the middle of every shore tile doesn't show as a dark square; only right at the waterline the bed's own depth
  // fades the water in.
  float colorDepth = min(shoreDepth, depth * WATERLINE_DEPTH + 4.0);

  // the bed, lit by the composite and wet, with the caustics, fading into the depth color
  vec3 caustics = vec3(0.0);
  if (waterCaustics == 1 && outdoors == 1 && lightingEnabled == 1 && WATER_LOOK[type].x < 0.7) {
    vec3 caster = strikeSun(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    float k = max(-casterDir.y, 0.0) * shadow * CAUSTICS * (sunIsCaster == 1 ? 1.0 : CAUSTIC_MOON)
      * smoothstep(0.0, CAUSTIC_EDGE, depth) * (1.0 - smoothstep(CAUSTIC_FADE.x, CAUSTIC_FADE.y, depth));
    float dark = CAUSTIC_RANGE.x * smoothstep(0.0, CAUSTIC_DARK, depth);
    caustics = srgbToLinear(albedo) * caster * (k * clamp(caustic, dark, CAUSTIC_RANGE.y));
  }
  vec3 under = max(srgbToLinear(lit * WET_SAND) + caustics, vec3(0.0)) * srgbToLinear(waterDepthTint(type, colorDepth));
  // what is seen through a wave face turned toward the camera is a little brighter, turned away darker
  vec3 v = p / dist;
  spec += waterGlitter(px, nrm, v, WATER_LOOK[type].z, foam, shadow);
  vec2 slope = nrm.xz / nrm.y;
  under *= max(1.0 + WAVE_SHADE * dot(slope, v.xz) / max(length(v.xz), 0.25), 0.0);
  float alpha = max(WATER_LOOK[type].x * smoothstep(0.0, SURFACE_DEPTH, colorDepth), surface.a);
  // the shore waves' foam, in the flat tones the falls have, over the water as it is lit from above
  vec2 shore = waterShoreOf(ws);
  if (shore.x > 0.0) {
    vec3 up = vec3(0.0, -1.0, 0.0);
    vec3 topLight = outdoors == 1 && lightingEnabled == 1
      ? ambientLight(sunAltitude) * envAmbient + skyLight(sunAltitude) * envAmbient
        + strikeCaster(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun * max(dot(up, casterDir), 0.0) * shadow
      : vec3(1.0);
    float share = mix(FOAM_TINT * shore.x, FOAM_CORE, shore.y);
    surface.rgb = mix(surface.rgb, srgbToLinear(mix(WATER_FOAM_COLOR[type], vec3(1.0), FOAM_WHITE)) * mix(topLight, vec3(1.0), 0.5), share);
    alpha = max(alpha, share);
    spec *= 1.0 - share;
  }
  vec3 color = waterShoulder(mix(under, surface.rgb, alpha) + spec);
  // over the waterline the water fades in over the bed and its caustics: no hard edge where it meets the bank or a
  // leg in the water
  vec3 bed = max(srgbToLinear(lit) + caustics, vec3(0.0));
  vec3 result = linearToSrgb(clamp(mix(bed, color, smoothstep(0.0, 1.0, depth / SOFT_EDGE)), 0.0, 1.0));
  p = s;
  return result;
}

// ---------------------------------------------------------------------------------------------------------------
// Fake water: the faces models draw as water (fountains, wells, troughs, sinks, waterfalls; SurfaceMaterials marks
// them, the scene pass writes them as water 3 in the material target with their height above the foot of the
// model's water). The composite replaces their lit color with water shaded as the real surface is, from the face
// itself: its own normal and the world position, no texture. Level faces get the ripples and reflections of still
// water; steep ones are falls, the same water running down, streaked and foaming at its lip and foot.
// Everything here is ALU: the composite has no sampler to spare.
uniform int waterFakeOn;  // the water setting: off draws the faces as their type's plain color

// how deep the water counts as being, for the color it fades into (world units)
#define FAKE_DEPTH 100.0
// the ripples of level water: the largest ones FAKE_LONGEST tiles from crest to crest and FAKE_HEIGHT world units
// high, the finer octave after it a third as high; the seconds one cycle of them takes
#define FAKE_LONGEST 1.4
#define FAKE_HEIGHT 1.6
#define FAKE_PERIOD 9.0
// a face is a fall when its normal's vertical part is below the second and not a pool's at all below the first
#define FALL_STEEP vec2(0.5, 0.85)
// A fall is real water, running straight down at FALL_FLOW world units a second (its ripples stretched by FALL_STRETCH
// along the flow), with the contact foam dragged down it: the same cells at the same size as on flat water, one to
// one, across the fall and down it, drifting across each other as still water's foam does (FALL_DRIFT seconds a cell)
// and the whole pattern scrolling down FALL_SPEED cells a second. Cut to two flat tones with hard edges, covering
// the contact foam's own shapes and thresholds (waterFoamRim), as near the lip and the foot (FALL_NEAR tiles) as at things in
// the water, shrinking to little between: the shapes shrink, never fade.
#define FALL_FLOW 320.0
#define FALL_STRETCH 0.4
#define FALL_SPEED 2.0
#define FALL_DRIFT 40.0
#define FALL_NEAR 1.0
// how much cell height is added to the foam's in the middle of a fall and at its lip and foot
#define FALL_SHIFT vec2(0.15, -0.2)
// the foam's cells (Water.CELLS across CELL_SCALE_B world units, water_frag.glsl)
#define FALL_CELL (520.0 / 6.0)

// The slope of level fake water at world xz: two octaves of the sea's waves, running both ways
vec2 waterFakeSlope(vec2 xz) {
  const mat2 TURN = mat2(0.8, 0.6, -0.6, 0.8);
  mat2 M = TURN * (WATER_TAU / (FAKE_LONGEST * WATER_TILE));
  float tt = waterCycle(FAKE_PERIOD) * WATER_TAU;
  vec2 g = vec2(0.0);
  float amp = 1.0, chop = WAVE_CHOP;
  for (int i = 0; i < 2; ++i) {
    vec2 uv = M * xz;
    vec2 ua = uv + tt, ub = uv - tt + 2.3;
    g += transpose(M) * (seaOctaveGrad(ua, waveNoiseD(ua), chop) + seaOctaveGrad(ub, waveNoiseD(ub), chop)) * amp;
    M = TURN * M / WAVE_SHRINK;
    amp *= WAVE_FADE;
  }
  return g * (0.5 * FAKE_HEIGHT);
}

// The fake water face at pixel px (camera-relative p, facing camera normal faceN, color albedo (sRGB), the sun's
// shadow, span: its height above the foot of its water and below its top), lit as lit (sRGB): its color as water, sRGB.
vec3 waterFake(ivec2 px, vec3 p, vec3 faceN, vec3 albedo, float shadow, vec2 span, vec3 lit) {
  // the slimy greens are swamp water, the rest fresh
  int type = albedo.g > albedo.b * 1.15 ? 5 : 1;
  vec4 look = WATER_LOOK[type];
  if (waterFakeOn == 0) {
    vec3 c = WATER_SURFACE_COLOR[type];
    return c * (dot(lit, vec3(0.299, 0.587, 0.114)) / dot(c, vec3(0.299, 0.587, 0.114)));
  }
  float dist = length(p);
  vec3 v = p / dist;
  vec3 toCamera = -v;
  vec3 world = p + cameraPos;
  float fall = 1.0 - smoothstep(FALL_STEEP.x, FALL_STEEP.y, abs(faceN.y));
  float side = length(faceN.xz);
  vec2 tang = side > 1e-3 ? vec2(-faceN.z, faceN.x) / side : vec2(1.0, 0.0);

  // the surface's normal and its foam
  // level water ripples as the sea does; a fall's water runs straight down it, the ripples stretched along the flow
  float down = step(0.5, fall);
  vec2 slope = waterFakeSlope(mix(world.xz, vec2(dot(world.xz, tang), (world.y - FALL_FLOW * waterTime) * FALL_STRETCH), down)) * look.w * (1.0 + 2.0 * down);
  vec3 nrm = faceN + mix(vec3(-slope.x, 0.0, -slope.y), vec3(-slope.x * tang.x, -slope.y, -slope.x * tang.y), down);
  float foam = 0.0;
  if (fall > 0.0) {
    // the foam's cells, in cells (one to one with flat water's): across the fall, and down it
    vec2 uv = vec2(dot(world.xz, tang), world.y) / FALL_CELL - vec2(0.0, 64.0 * waterCycle(64.0 / FALL_SPEED));
    float h = 0.5 * (waterFoamCells(uv + vec2(waterCycle(FALL_DRIFT), 0.0))
      + waterFoamCells(uv.yx + vec2(0.37, -waterCycle(FALL_DRIFT * 1.37))));
    // how near the lip and the foot are: the cell height raised in the middle of the fall, shrinking its shapes, and lowered
    // at them, swelling them
    float near = clamp(max(1.0 - span.y / (WATER_TILE * FALL_NEAR), 1.0 - span.x / (WATER_TILE * FALL_NEAR)), 0.0, 1.0);
    h += mix(FALL_SHIFT.x, FALL_SHIFT.y, near);
    // the contact foam's rim (waterFoamRim) cut flat: its tint where it is half gone, its core where it begins
    float aa = max(fwidth(h), 1e-4);
    float rim = waterEdge(0.5 * (FOAM_RIM.x + FOAM_RIM.y) - h, aa), core = waterEdge(FOAM_RIM.x - h, aa);
    foam = mix(FOAM_TINT * rim, FOAM_CORE, core) * fall;
  }
  nrm = normalize(nrm);

  // ---- light on the surface, as the real water's
  bool lightOn = lightingEnabled == 1;
  vec3 surfaceLight = vec3(1.0);
  float murk = smoothstep(WATER_LOOK[1].x, WATER_LOOK[5].x, look.x);
  if (outdoors == 1 && lightOn) {
    vec3 caster = strikeCaster(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    vec3 ambient = ambientLight(sunAltitude) * envAmbient;
    vec3 sky = skyLight(sunAltitude) * envAmbient;
    float bodyShadow = mix(shadow, 1.0, MURKY_SHADOW_LIFT * murk);
    // a fall is lit as the pool below it is, not by how steep its face is
    vec3 lightN = normalize(mix(nrm, vec3(0.0, -1.0, 0.0), 0.9 * fall));
    surfaceLight = ambient + sky * max(-lightN.y, 0.0) + caster * max(dot(lightN, casterDir), 0.0) * bodyShadow;
  } else if (lightOn) {
    surfaceLight = envCaveLight(vec3(0.0, -1.0, 0.0));
  }
  vec2 fwd = v.xz * inversesqrt(max(dot(v.xz, v.xz), 1e-6));
  vec3 tAlong = vec3(fwd.x, 0.0, fwd.y), tAcross = vec3(-fwd.y, 0.0, fwd.x);
  vec3 lampSpec = vec3(0.0);
  waterLamps(px, p, (viewProj * vec4(p, 1.0)).w, nrm, toCamera, tAlong, tAcross, surfaceLight, lampSpec);

  // ---- what it reflects: the sky, and the world where the reflected ray finds it
  vec3 skyN = normalize(nrm * vec3(SKY_TILT, 1.0, SKY_TILT));
  float fresnel = mix(FRESNEL_BASE, 1.0, pow(1.0 - clamp(dot(toCamera, skyN), 0.0, 1.0), FRESNEL_POWER));
  vec3 waveN = normalize(nrm * vec3(WAVE_SKY_TILT, 1.0, WAVE_SKY_TILT));
  float waveSky = mix(FRESNEL_BASE, 1.0, pow(1.0 - clamp(dot(toCamera, waveN), 0.0, 1.0), FRESNEL_POWER));
  vec3 r = reflect(v, waveN);
  r.y = min(r.y, -0.02);
  vec3 reflection = waterSkyReflection(normalize(r));
  if (waterReflections == 1) {
    vec4 hit = waterTraceReflection(p, reflect(v, nrm), surfaceLight, vec2(px));
    reflection = mix(reflection, hit.rgb, hit.a);
  }

  // ---- the highlights, the surface and what is under it (a pool of FAKE_DEPTH, its floor the face's color gone
  // sandy)
  vec3 spec = min(lampSpec * LAMP_SPECULAR, vec3(LAMP_MAX));
  spec += waterSunGlint(waterGlitterNormal(nrm), v, 0.0, look.z, foam, shadow);
  vec3 surface = mix(srgbToLinear(WATER_SURFACE_COLOR[type]) * surfaceLight, reflection * waveSky, look.y);
  float cover = max(fresnel, (1.0 + toCamera.y) * OPACITY_SLANT);
  vec3 foamColor = srgbToLinear(WATER_FOAM_COLOR[type]) * surfaceLight;
  float covered = cover + foam * (1.0 - cover);
  surface = (surface * (cover * (1.0 - foam)) + foamColor * foam) / max(covered, 1e-4);
  vec3 bed = srgbToLinear(mix(albedo, vec3(0.62, 0.55, 0.40), 0.5)) * surfaceLight;
  vec3 under = bed * srgbToLinear(waterDepthTint(type, FAKE_DEPTH));
  float alpha = max(look.x * smoothstep(0.0, SURFACE_DEPTH, FAKE_DEPTH), covered);
  return linearToSrgb(clamp(waterShoulder(mix(under, surface, alpha) + spec), 0.0, 1.0));
}
