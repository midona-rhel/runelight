// Water, shaded by the composite over the lowered water bed (WaterMap.java, Water.java, WaterSurface.java).
//
// The surface is real geometry: the water pass (water_vert.glsl, water_frag.glsl) draws it wherever it is in front of
// the opaque scene, and leaves how far away it is, its normal, look and foam, the water's depth by the shore and the
// caustics on the bed below in waterSurface.
//
// The look starts from the game's own water: the body of the water is the color of the tile's water texture (its
// mean, Water.java), lit like everything else, so every lake, river and swamp keeps the color the game gives it. It
// is mostly opaque: the bed only shows in the shallows, fading into the body color over a short depth, with the
// caustics on it. Over that, the surface: the sky by Fresnel (faint looking down, strong at a slant), sharp
// reflections of the world, the sun's and moon's glints and lamps' streaks, and foam.
//
// Two passes of the composite program. The first (waterPass 1) runs at half resolution over the water only and
// works out the surface and the lit body color (waterShadeHalf), into waterShaded. The second, the full resolution
// composite, lights the bed as any ground and only blends: the bed into the body color by its own depth under the
// surface, then the surface over it (waterOver), using the full resolution surface for the edges.
// Included by composite_frag.glsl after its lighting helpers (shadows, point light clusters).

#include "water_common.glsl"

#define WATER_SLOTS 32

uniform int waterEnabled;
uniform usampler2D waterSurface;  // RGBA16UI, 64 bits packed by water_frag.glsl (waterUnpack); 0 no water
uniform usampler2D waterShaded;   // RGBA32UI, half resolution: the surface and the body (waterShadeHalf)
uniform int waterPass;            // 1 while the half resolution water pass runs
uniform int waterReflections;     // a few screen-space taps for the world in the reflection
uniform float waterClarity;       // multiplier on how deep the bed stays visible
uniform int waterCaustics;
uniform int waterDebug;
uniform vec3 waterSlotColor[WATER_SLOTS];  // linear: the mean color of each look's game water texture
uniform int waterSlotType[WATER_SLOTS];    // each look's water type

layout(location = 1) out uvec4 WaterShadedOut;

// vertical depth over which the surface fades in from the waterline; within 1 / WATERLINE_DEPTH of the depth the
// shore distance gives, the bed's own depth sets the water's color (the bed sinks in a pit in the middle of every
// shore tile, which must not show)
#define SOFT_EDGE 24.0
#define WATERLINE_DEPTH 4.0
// the body: world units of depth over which the bed fades into the body color (to 1 - 1/e), per type before the
// clarity setting, and how the body is lit: the sky above it, the sun through it, lamps around it
const float WATER_BODY_DEPTH[WATER_TYPES] = float[](1.0, 70.0, 55.0, 45.0, 90.0, 22.0, 35.0);
#define BODY_SKY 0.5
#define BODY_SUN 0.35
#define BODY_LAMP 0.35
// the surface: Fresnel from FRESNEL_BASE looking straight down to all of it at a slant, (1 - cos)^FRESNEL_POWER,
// times the type's sky share (WATER_LOOK.y)
#define FRESNEL_BASE 0.06
#define FRESNEL_POWER 4.0
// The sky is reflected off a normal whose slope is SKY_TILT of the waves': the ripples carried by the current move the
// sky's gradient and clouds about. Cloud tops in the sky cache no brighter than SKY_CLOUD_MAX times the sky there.
#define SKY_TILT 0.75
#define SKY_CLOUD_MAX 1.25
// Highlights. Sheen: a broad soft lobe of the sun or moon on the waves (Blinn-Phong exponent, strength). Glitter:
// its reflection on the waves' slopes exaggerated GLITTER_SLOPE times, a soft path toward the camera of GLITTER_ROUGH
// width. The moon's are MOON_SPECULAR of the sun's.
#define SHEEN_GLOSS 60.0
#define SHEEN 0.10
#define GLITTER_SLOPE 1.6
#define GLITTER_ROUGH 0.1
#define GLITTER 0.3
#define MOON_SPECULAR 0.35
// lamps: their streak's width along and across, strength, and the most one adds (linear)
#define LAMP_ALONG 0.03
#define LAMP_ACROSS 0.012
#define LAMP_SPECULAR 0.8
#define LAMP_MAX 2.0
// Caustics: 117 HD's, carried by the flow, worked out by the water pass (water_frag.glsl) as their value above the
// mean in units of the brightest lines'. The bed gains CAUSTICS times the direct light per unit (between
// CAUSTIC_RANGE), only where it still shows through the body; fainter by moonlight.
#define CAUSTICS 1.6
#define CAUSTIC_RANGE vec2(-0.7, 3.0)
#define CAUSTIC_MOON 0.35
// screen-space reflection: taps along the reflected ray, growing, then halving steps onto the hit; the world is
// reflected off a calmer normal than the sky (slopes times SSR_TILT)
#define SSR_STEPS 4
#define SSR_FIRST_STEP 96.0
#define SSR_GROWTH 2.0
#define SSR_REFINE 3
#define SSR_TILT 0.6

// how far the surface target's distance reaches (water_frag.glsl)
#define WATER_REACH 20480.0

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

// The shaded target's 16 bit values (GL 4.1 has no packHalf2x16): light 0 to 63 on a log scale, fine near black;
// coverage 0 to 1 linear
uint waterEncLight(float x) {
  return uint(clamp(log2(max(x, 0.0) + 1.0) / 6.0, 0.0, 1.0) * 65535.0 + 0.5);
}

float waterDecLight(uint u) {
  return exp2(float(u) / 65535.0 * 6.0) - 1.0;
}

uvec4 waterPackShaded(vec3 surface, float cover, vec3 body) {
  return uvec4(waterEncLight(surface.r) | waterEncLight(surface.g) << 16,
    waterEncLight(surface.b) | uint(clamp(cover, 0.0, 1.0) * 65535.0 + 0.5) << 16,
    waterEncLight(body.r) | waterEncLight(body.g) << 16, waterEncLight(body.b) | 1u << 16);
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
// start jittered per pixel (so the hits don't line up into stair steps), then halving steps onto the crossing; the
// hit's color lit by `light` (the light on the surface: what stands in the water is lit about the same), sharp.
// a: how much to trust it.
vec4 waterTraceReflection(vec3 s, vec3 r, vec3 light, vec2 px) {
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
      // water reflecting water, and the sky, show the sky
      bool isWorld = !isSky(texelFetch(sceneDepth, ip, 0).r) && texelFetch(sceneMaterial, ip, 0).b <= 0.75
        && texelFetch(waterSurface, ip, 0).b == 0u;
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

vec3 waterShoulder(vec3 c) {
  return mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
}

// ---------------------------------------------------------------------------------------------------------------
// The half resolution pass: for the half resolution pixel hpx, the nearest water surface among the four full
// resolution pixels it covers, shaded: the surface (linear, premultiplied: what it adds) and how much of what is under
// it it hides, and the body's lit color (linear). Packed as halves: surface rg, surface b and coverage, body rg,
// body b. 0 where there is no water.
void waterShadeHalf(ivec2 hpx) {
  WaterShadedOut = uvec4(0u);
  if (waterEnabled == 0)
    return;
  ivec2 last = textureSize(waterSurface, 0) - 1;
  uvec4 ws = uvec4(0u);
  ivec2 px = ivec2(0);
  float t = 1e9;
  for (int i = 0; i < 4; ++i) {
    ivec2 q = min(hpx * 2 + ivec2(i & 1, i >> 1), last);
    uvec4 w = texelFetch(waterSurface, q, 0);
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

  if (waterDebug == 1) {
    uint a = ws.r | ws.g << 16;
    vec3 c = srgbToLinear(vec3(float(a & 255u), float(a >> 8 & 255u), float(a >> 16 & 255u)) / 255.0);
    WaterShadedOut = waterPackShaded(c, 1.0, vec3(0.0));
    return;
  }

  // ---- light on the surface and in the body
  bool lightOn = lightingEnabled == 1;
  vec3 caster = vec3(0.0);
  vec3 surfaceLight = vec3(1.0);
  vec3 bodyLight = vec3(1.0);
  float shadow = 1.0;
  if (outdoors == 1 && lightOn) {
    caster = (sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    // the AO pass's sun shadow of what is under this pixel: close enough for the surface over it
    if (shadowsEnabled == 1)
      shadow = texelFetch(sceneShadow, min(hpx, textureSize(sceneShadow, 0) - 1), 0).r;
    shadow = overcastShadow(shadow * cloudShadow(s + cameraPos, casterDir));
    vec3 ambient = ambientLight(sunAltitude) * envAmbient;
    vec3 sky = skyLight(sunAltitude) * envAmbient;
    surfaceLight = ambient + sky * max(-nrm.y, 0.0) + caster * max(dot(nrm, casterDir), 0.0) * shadow;
    bodyLight = ambient + sky * BODY_SKY + caster * (max(-casterDir.y, 0.0) * BODY_SUN * shadow);
  } else if (lightOn) {
    surfaceLight = bodyLight = envCaveLight(vec3(0.0, -1.0, 0.0));
  }

  vec2 fwd = v.xz * inversesqrt(max(dot(v.xz, v.xz), 1e-6));
  vec3 tAlong = vec3(fwd.x, 0.0, fwd.y), tAcross = vec3(-fwd.y, 0.0, fwd.x);
  vec3 lampSpec = vec3(0.0);
  if (pointLightsEnabled == 1 && lightOn) {
    int cbase = clusterBase(px, viewZ);
    int count = clusterLightCount(cbase);
    // lights in buildings stay there (rooms.glsl)
    ivec2 room = count > 0 ? lightRoomAt(s + cameraPos - vec3(0.0, ROOM_NORMAL_BIAS, 0.0)) : ivec2(-1, 0);
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
      bodyLight += color * (att * max(-ld.y, 0.2) * BODY_LAMP);
      lampSpec += color * (att * waterGlint(nrm, ld, toCamera, tAlong, tAcross, LAMP_ALONG, LAMP_ACROSS));
    }
  }
  vec3 body = waterSlotColor[slot] * bodyLight;

  // ---- what the surface reflects: the sky, and the world where the reflected ray finds it
  float vn = clamp(dot(toCamera, nrm), 0.0, 1.0);
  float fresnel = mix(FRESNEL_BASE, 1.0, pow(1.0 - vn, FRESNEL_POWER)) * look.y;
  vec3 skyN = normalize(nrm * vec3(SKY_TILT, 1.0, SKY_TILT));
  vec3 r = reflect(v, skyN);
  r.y = min(r.y, -0.02);
  r = normalize(r);
  vec3 fogColor = envFog(outdoors == 1 && proceduralSky == 1 ? skyHorizon(sunAltitude) : clientSkyColor,
    outdoors == 1 && skyVoid == 0, sunAltitude);
  vec3 reflection;
  if (outdoors == 1 && proceduralSky == 1) {
    float elev = -r.y;
    vec3 sc = mix(skyHorizon(sunAltitude), skyZenith(sunAltitude), pow(clamp(elev, 0.0, 1.0), 0.45));
    // the sky as drawn this frame, clouds and all, when the sky cache has it: the current moves them about
    if ((skyCacheMode & 2) != 0) {
      vec3 cached = textureLod(skyCache, skyCacheUv(cloudSkyDir(r)), 0.0).rgb;
      sc = linearToSrgb(clamp(min(cached, srgbToLinear(sc) * SKY_CLOUD_MAX), 0.0, 1.0));
    }
    reflection = srgbToLinear(envSky(sc, fogColor, elev));
  } else {
    reflection = srgbToLinear(fogColor) * (outdoors == 1 ? 1.0 : 0.5);
  }
  if (waterReflections == 1) {
    vec4 hit = waterTraceReflection(s, reflect(v, normalize(nrm * vec3(SSR_TILT, 1.0, SSR_TILT))), surfaceLight,
      vec2(px));
    reflection = mix(reflection, hit.rgb, hit.a);
  }

  // ---- highlights: a broad soft sheen and a glitter path from the sun or moon, lamp streaks
  vec3 spec = min(lampSpec * LAMP_SPECULAR, vec3(LAMP_MAX));
  if (outdoors == 1 && lightOn) {
    vec3 h = normalize(casterDir + toCamera);
    float sheen = pow(max(dot(nrm, h), 0.0), SHEEN_GLOSS) * (SHEEN_GLOSS + 8.0) / 25.0 * SHEEN * fresnel;
    vec3 steep = normalize(vec3(nrm.x * GLITTER_SLOPE, nrm.y, nrm.z * GLITTER_SLOPE));
    float glitter = waterGlint(steep, casterDir, toCamera, tAlong, tAcross, GLITTER_ROUGH * 1.6, GLITTER_ROUGH) * GLITTER;
    spec += caster * ((sheen + glitter) * shadow * look.z * (1.0 - foam) * (sunIsCaster == 1 ? 1.0 : MOON_SPECULAR));
  }

  // the surface over the body: the reflection by Fresnel, the foam over it, the highlights on top
  vec3 foamColor = srgbToLinear(WATER_FOAM_COLOR[type]) * surfaceLight;
  vec3 surface = mix(reflection * fresnel, foamColor, foam) + spec;
  float cover = mix(fresnel, 1.0, foam);
  WaterShadedOut = waterPackShaded(surface, cover, body);
}

// ---------------------------------------------------------------------------------------------------------------
// The full resolution blend.
// p: camera-relative position of this pixel's scene surface (the bed, or something else under the water)
// albedo (sRGB), shadow: its color and sun shadow from the composite; lit: its lit color (sRGB); mask: the material
// target's water channel (frag.glsl)
// Returns the color with the water over it, or lit unchanged; moves p to the water surface for the fog.
vec3 waterOver(ivec2 px, inout vec3 p, vec3 albedo, float shadow, vec3 lit, float mask) {
  // land terrain is never under water: the surface runs on under the banks, hidden, and never over land
  if (waterEnabled == 0 || mask < 0.25)
    return lit;
  uvec4 ws = texelFetch(waterSurface, px, 0);
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
  vec2 hp = (vec2(px) + 0.5) * 0.5 - 0.5;
  ivec2 h0 = ivec2(floor(hp));
  vec2 f = hp - vec2(h0);
  ivec2 last = textureSize(waterShaded, 0) - 1;
  vec4 surface = vec4(0.0);
  vec3 body = vec3(0.0);
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
    body += vec3(waterDecLight(w.z & 65535u), waterDecLight(w.z >> 16), waterDecLight(w.w & 65535u)) * k;
    wsum += k;
  }
  if (wsum <= 0.0)
    return lit;
  surface /= wsum;
  body /= wsum;
  if (waterDebug == 1) {
    p = s;
    return linearToSrgb(clamp(surface.rgb, 0.0, 1.0));
  }

  int type = waterTypeOf(slot);
  // The depth the body's color goes by: the shore distance's, smooth across tiles, so the pit the bed sinks into in
  // the middle of every shore tile doesn't show as a dark square; only right at the waterline the bed's own depth
  // fades the water in.
  float colorDepth = min(shoreDepth, depth * WATERLINE_DEPTH + 4.0);
  float bodyAmount = 1.0 - exp(-colorDepth / (WATER_BODY_DEPTH[type] * waterClarity));

  // the bed, lit by the composite, with the caustics where it still shows through
  vec3 under = srgbToLinear(lit);
  if (waterCaustics == 1 && outdoors == 1 && lightingEnabled == 1) {
    vec3 caster = (sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    float k = max(-casterDir.y, 0.0) * shadow * CAUSTICS * (sunIsCaster == 1 ? 1.0 : CAUSTIC_MOON)
      * smoothstep(0.0, 6.0, depth) * (1.0 - bodyAmount);
    under += srgbToLinear(albedo) * caster * (k * clamp(caustic, CAUSTIC_RANGE.x, CAUSTIC_RANGE.y));
  }
  under = mix(max(under, vec3(0.0)), body, bodyAmount);
  vec3 color = under * (1.0 - surface.a) + surface.rgb;
  // over the waterline the surface fades in: no hard edge where it meets the bank or a leg in the water
  vec3 result = mix(lit, linearToSrgb(clamp(waterShoulder(color), 0.0, 1.0)), smoothstep(0.0, 1.0, depth / SOFT_EDGE));
  p = s;
  return result;
}
