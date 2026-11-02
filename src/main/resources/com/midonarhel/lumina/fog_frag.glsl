#version 330

// Volumetric fog at half resolution: march each view ray through a height fog layer around the player, lighting
// every sample with the sky, the sun or moon (through the shadow map, so shafts show through gaps) and nearby
// point lights. Output is the light scattered toward the camera (linear) and how much of the scene behind still
// shows; the denoise (denoise_frag.glsl) smooths its step jitter and fog_apply.glsl upsamples it depth-aware in the
// composite. Parameters come from VolumetricFog.java.

#include "frame.glsl"
// the sky's colors at this frame's sun altitude from composite_vert.glsl, not worked out again at every texel
#define SKY_FRAME_CONSTANTS
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

#include "light/lamp.glsl"

// inside building rooms (RoomMap.java, in fogGround) the fog's density is multiplied by this; 1 leaves interiors
// foggy
uniform float fogIndoor;
// and in the rooms whose roof is hidden (OpenRooms.java): open to the sky, so the fog comes back inside and their
// lamps glow in it
uniform float fogIndoorOpen;

// The water (Water.java, water.glsl): a ray down to something under the water ends at the surface, the fog doesn't
// go on under it
uniform int waterEnabled;
uniform sampler2D waterData;  // page 0: height, flow, type (WaterMap.texture)
uniform vec2 waterGrid;       // x: extended tile of local tile 0, y: corners per side

uniform sampler3D fogNoise;  // tiling value noise, 0..1: r patches, g banks (constant with height)

uniform float fogDensity;    // extinction per world unit at the ground
uniform float fogHeight;     // world units for the density to drop to 1/e
uniform float fogHighestY;   // world y of the highest ground within the fog's reach; y points down
// The terrain the height is measured from (FogGround.java): ground y per tile corner, the planes' pages side by side;
// gba the rooms (FogGround.texels, fogRoomFactor)
uniform sampler2D fogGround;
// the ground drawn before it, and its share while the new one eases in (LayerEase, VolumetricFog); 0 when none
uniform sampler2D fogGroundPrev;
uniform float fogGroundPrevWeight;
uniform vec3 fogGroundGrid;  // x: extended tile of local tile 0, y: corners per side, z: texel x of the plane's page
uniform vec2 fogCenter;      // world x, z the fade is centered on: the player
uniform vec2 fogFade;        // horizontal distance from the center where the fog starts and finishes fading out
uniform float fogNear;       // distance from the camera over which the fog thickens from nothing
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
// how much the fog drifts in patches and gathers in large banks, 0 even, 1 fully (VolumetricFog.NOISE_AMOUNT and
// BANK_AMOUNT)
#define FOG_NOISE_AMOUNT 0.5
#define FOG_BANK_AMOUNT 0.5
// banks: the band of the bank noise that goes from thin to thick; narrower is more contrast
#define FOG_BANK_LOW 0.2
#define FOG_BANK_HIGH 0.8
// Lamps glowing in the fog: every lamp in the pixel's fog column (LightClusters), its light (light/lamp.glsl's
// falloff and colour) integrated in closed form along the part of the ray inside its sphere instead of being sampled
// at every step, and stopped by the walls as the lamp's light is, seen from where the ray passes closest to it. The
// share of it the fog scatters toward the camera: as much as the glow it replaced gave a ray through a lamp's middle.
#define FOG_LAMP_SCATTER 0.44

#include "halfmap.glsl"
#include "ign.glsl"

// The ground at a point: r its world y, the terrain's own slopes between the tile corners, and the rooms there for
// fogRoomFactor; the edge of the scene goes on
vec4 fogGroundAt(vec2 worldXZ) {
  vec2 c = clamp(worldXZ / 128.0 + fogGroundGrid.x, vec2(0.0), vec2(fogGroundGrid.y - 1.0));
  vec2 uv = (c + vec2(0.5 + fogGroundGrid.z, 0.5)) / vec2(textureSize(fogGround, 0));
  vec4 g = textureLod(fogGround, uv, 0.0);
  if (fogGroundPrevWeight > 0.0) {
    g = mix(g, textureLod(fogGroundPrev, uv, 0.0), fogGroundPrevWeight);
  }
  return g;
}

// What the rooms leave of the fog's density at world y, from fogGroundAt there: none outdoors, fogIndoor in rooms,
// fogIndoorOpen in the open ones, up to the top of their storeys (a, y points down). Every corner of a room's
// outline is in it (g, b), so the remap keeps the room whole and fades it out half a tile outside its walls.
float fogRoomFactor(vec4 ground, float y) {
  vec2 inside = clamp(ground.gb * 2.0 - 1.0, 0.0, 1.0) * float(y >= ground.a);
  return 1.0 + inside.x * (fogIndoor - 1.0) + inside.y * (fogIndoorOpen - fogIndoor);
}

vec2 fogWaterCell(vec2 worldXZ) {
  return clamp(worldXZ / 128.0 + waterGrid.x, vec2(0.0), vec2(waterGrid.y - 1.0));
}

float fogWaterLevel(vec2 c) {
  return textureLod(waterData, (c + 0.5) / vec2(3.0 * waterGrid.y, waterGrid.y), 0.0).r;
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

// stretches of the part of a ray in a lamp's reach: each takes the lamp's light where it gets to (the walls, the light
// map) as at one point of it, and its falloff in full. One (the whole of it at the ray's point nearest the lamp, where
// the glow is brightest) was off a 32-sample reference by up to 27 levels in the forests, where trees shade the glow
// along the ray (tree1 53k pixels over 8); two by at most 10, for 0.13 ms more on fps1
#define FOG_LAMP_PIECES 2

// The lamp's falloff (lampFalloff: (1 - d / r)^2) along a line passing sqrt(h2) from it, from s0 to s1 along the line
// from its point nearest the lamp, in closed form: r times, in units of r (u = s / r, e = h / r),
// u - (u sqrt(e^2 + u^2) + e^2 asinh(u / e)) + e^2 u + u^3 / 3
float lampFalloffAlong(float h2, float r, float s0, float s1) {
  float e2 = h2 / (r * r), e = sqrt(e2);
  vec2 u = vec2(s0, s1) / r;
  vec2 d = sqrt(e2 + u * u);
  vec2 ash = e > 1e-4 ? e2 * asinh(u / e) : vec2(0.0);
  vec2 f = u - (u * d + ash) + e2 * u + u * u * u * (1.0 / 3.0);
  return max(f.y - f.x, 0.0) * r;
}

// Light from the lamps scattered toward the camera along the ray dir, up to tEnd (what the ray ends at), as a glow
// round each lamp: its falloff along the part of the ray in its reach, in closed form (lampFalloffAlong), times the fog
// where the lamp is (its density, height layer, room and weather there; none inside rooms), and only where the lamp's
// light gets to: of the ray's lamps the LAMP_BUDGET brightest by their glow before walls (the same cap as a surface's,
// light/lamp.glsl), each its part of the ray in FOG_LAMP_PIECES stretches, each taking it as a point of it in the air
// takes it (at a point jittered along the stretch, the fog's own jitter, which the denoise smooths): the light map's in
// the cell the point is in (lampMapVisibilityNearest; the map's filter across cells is for surfaces), which a lamp once its map
// has eased in takes alone, as the map stops it at walls; a lamp the map doesn't carry, or not yet in full, by the walls'
// share too (lampWallsOpen: walls up to their tops, no floor or ceiling; above a wall the glow spreads on into the air).
// So a wall cuts the glow where it stands, the lamp on screen or off, and the glow fades across the cut rather than
// switching. No other edge: the falloff is 0 at the reach, and the ray's end clips the glow as it clips everything
// behind it.
vec3 lampGlow(ivec2 px, vec3 dir, float tEnd, float density, float jitter) {
  LightMask lamps = clusterLights(columnEntry((vec2(px) + 0.5 - vec2(sceneViewport.xy)) / vec2(sceneViewport.zw)));
  float below = -fogHeight * FOG_BELOW;
  // the ray's brightest LAMP_BUDGET lamps by their glow before walls (each one's fog kept in the budget's open)
  LampBudget budget = lampBudgetEmpty();
  int li;
  while (nextLight(lamps, li)) {
    vec4 sphere = lampSphere(li);
    float tc = dot(sphere.xyz, dir);
    float h2 = max(dot(sphere.xyz, sphere.xyz) - tc * tc, 0.0);
    float r2 = sphere.w * sphere.w;
    if (h2 >= r2) {
      continue;
    }
    float hc = sqrt(r2 - h2);
    float a = max(tc - hc, 0.0);
    float b = min(tc + hc, tEnd);
    if (b <= a) {
      continue;
    }
    // the fog where the lamp is
    vec3 w = sphere.xyz + cameraPos;
    vec4 ground = fogGroundAt(w.xz);
    float sigma = density * exp(-max(ground.r - w.y, below) / fogHeight) * fogRoomFactor(ground, w.y)
      * weatherFogFactor(w.xz);
    if (sigma <= 0.0) {
      continue;
    }
    float whole = lampFalloffAlong(h2, abs(sphere.w), a - tc, b - tc);
    lampBudgetAdd(budget, lampLuminance(lampColorOf(li)) * whole * sigma, li, sigma);
  }
  vec3 sum = vec3(0.0);
  for (int k = 0; k < LAMP_BUDGET; ++k) {
    int kl = budget.who[k];
    if (kl < 0) {
      break;
    }
    Lamp lamp = lampAt(kl);
    float tc = dot(lamp.pos, dir);
    float h2 = max(dot(lamp.pos, lamp.pos) - tc * tc, 0.0);
    float hc = sqrt(max(lamp.radius * lamp.radius - h2, 0.0));
    float a = max(tc - hc, 0.0);
    float b = min(tc + hc, tEnd);
    float part = (b - a) / float(FOG_LAMP_PIECES);
    bool mapAlone = lamp.mapId > 0 && lamp.mapEase >= 1.0;
    float glow = 0.0;
    for (int j = 0; j < FOG_LAMP_PIECES; ++j) {
      float s0 = a + float(j) * part;
      vec3 q = dir * (FOG_LAMP_PIECES == 1 ? clamp(tc, a, b) : s0 + jitter * part);
      float open = mapAlone ? 1.0 : lampWallsOpen(lamp, q, q, true);
      if (lamp.mapId > 0 && open > 0.0) {
        open = min(open, mix(1.0, lampMapVisibilityNearest(lamp.mapId, q), lamp.mapEase));
      }
      if (open > 0.0) {
        glow += lampFalloffAlong(h2, lamp.radius, s0 - tc, s0 + part - tc) * open;
      }
    }
    sum += lamp.color * (glow * budget.open[k] * lampBudgetFade(budget, k));
  }
  return sum * FOG_LAMP_SCATTER;
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

  // Clip the ray to the part below the top of the layer, which is flat: a few heights above the highest ground, so
  // over low ground some of the ray is clear air that the per-step height then thins out. Height above that is
  // h0 + t * rise. The cheapest test first: with the camera above the layer every ray up into the sky ends here.
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

  // and to the cylinder around the player where there is fog at all: the steps are spent there, and distant scenery
  // and the sky only see the fog between them and the camera instead of turning into a wall.
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
  if (t1 <= t0) {
    return;
  }

  // No fog under water: the ray stops at the surface. Looked up only for rays that reach the fog.
  if (!sky) {
    float tw = waterSurfaceDistance(hit, dir);
    if (tw > 0.0) {
      t1 = min(t1, tw);
      if (t1 <= t0) {
        return;
      }
    }
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
    vec3 caster = strikeSun(sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude));
    float luma = dot(caster, vec3(0.2126, 0.7152, 0.0722));
    caster = max(mix(vec3(luma), caster, FOG_SUN_SATURATION), 0.0);
    sun = caster * phase(dot(dir, casterDir)) * FOG_SUN_SCALE;
  } else {
    ambient = FOG_CAVE_COLOR;
    ambientFar = FOG_CAVE_COLOR;
    density *= FOG_CAVE_DENSITY;
  }
  ambient *= fogTint;

  // the shadow map projection is orthographic, so its coordinates are linear along the ray
  bool shafts = sunAndSky && shadowsEnabled == 1;
  vec3 lc0 = (lightProjRel * vec4(0.0, 0.0, 0.0, 1.0)).xyz;
  vec3 lcd = (lightProjRel * vec4(dir, 0.0)).xyz;
  float shadowBias = 24.0 / (2.0 * shadowDepthRange);

  // the sun's light in the fog; at night with no moon up the shadow map has nothing to add
  shafts = shafts && dot(sun, vec3(1.0)) > 1e-4;
  bool glow = pointLightsEnabled == 1;

  int steps = clamp(fogSteps, 1, FOG_MAX_STEPS);
  float dt = (t1 - t0) / float(steps);
  // Each step is a segment of the ray. Its density is integrated exactly from the height above the ground at its two
  // ends, so the dense layer at the ground needs no sampling noise; the rooms, which come with the ground, are
  // averaged over the two ends, and the other slow factors (fades, noise patches) are taken at its middle. Only the
  // shadow map, for the shafts, is sampled at a jittered point, and that grain the denoise (denoise_frag.glsl) smooths
  // away. Each end's ground and exponential serve both segments it ends and starts.
  float jitter = ign(gl_FragCoord.xy);
  float below = -fogHeight * FOG_BELOW;
  float invHeight = 1.0 / fogHeight;
  vec3 wStart = dir * t0 + cameraPos;
  vec4 groundStart = fogGroundAt(wStart.xz);
  float hStart = max(groundStart.r - wStart.y, below);
  float eStart = exp(-hStart * invHeight);
  float roomStart = fogRoomFactor(groundStart, wStart.y);
  // the noise's coordinates are linear along the ray
  vec3 noiseStart = (cameraPos + FOG_WIND * time) / vec3(FOG_NOISE_SCALE, FOG_NOISE_SCALE_Y, FOG_NOISE_SCALE);
  vec3 noiseDir = dir / vec3(FOG_NOISE_SCALE, FOG_NOISE_SCALE_Y, FOG_NOISE_SCALE);
  // the flat glow and the sun's shafts apart: the cover limit takes more of the first
  vec3 scatterAmbient = vec3(0.0);
  vec3 scatterSun = vec3(0.0);
  float trans = 1.0;
  for (int i = 0; i < steps; ++i) {
    float tEnd = t0 + float(i + 1) * dt;
    vec3 wEnd = dir * tEnd + cameraPos;
    vec4 groundEnd = fogGroundAt(wEnd.xz);
    float hEnd = max(groundEnd.r - wEnd.y, below);
    // the integral of exp(-h / H) over the segment, h linear along it, in units of dt
    float eEnd = exp(-hEnd * invHeight);
    float dh = (hEnd - hStart) * invHeight;
    float layer = abs(dh) > 1e-3 ? (eStart - eEnd) / dh : 0.5 * (eStart + eEnd);
    float roomEnd = fogRoomFactor(groundEnd, wEnd.y);
    // no fog inside buildings, a little in the ones open to the sky
    float room = 0.5 * (roomStart + roomEnd);
    hStart = hEnd;
    eStart = eEnd;
    roomStart = roomEnd;

    float t = tEnd - 0.5 * dt;
    vec3 wp = dir * t + cameraPos;
    float sigma = density * layer * room;
    // Thin around the player and right in front of the camera, thickening over fogNear, so the fog sits in the
    // distance instead of over what you're looking at; and the outer edge thins to nothing.
    float centerDist = length(wp.xz - fogCenter);
    sigma *= 1.0 - smoothstep(fogFade.x, fogFade.y, centerDist);
    sigma *= mix(FOG_NEAR_FLOOR, 1.0, smoothstep(0.0, fogNear, centerDist));
    sigma *= smoothstep(0.0, fogNear, t);
    sigma *= weatherFogFactor(wp.xz);
    vec2 n = texture(fogNoise, noiseStart + noiseDir * t).rg;
    // both average about 1, so they move fog around without changing how much there is
    sigma *= max(mix(1.0, n.r * 2.0, FOG_NOISE_AMOUNT), 0.0);
    sigma *= mix(1.0, smoothstep(FOG_BANK_LOW, FOG_BANK_HIGH, n.g) * 2.0, FOG_BANK_AMOUNT);
    if (sigma <= 0.0) {
      continue;
    }

    vec3 direct = vec3(0.0);
    if (sunAndSky) {
      float vis = 1.0;
      if (shafts) {
        vec3 lc = lc0 + lcd * (t0 + (float(i) + jitter) * dt);
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
    trans *= stepTrans;
  }
  vec3 lamps = glow ? lampGlow(px, dir, t1, density, jitter) : vec3(0.0);

  // Limit how much of the view the fog hides where the ray ends. The flat glow shrinks with the cover; the light
  // from the sun and the lamps mostly stays, so thick fog still shows where it is lit.
  float cover = 1.0 - trans;
  float limited = capFogCover(cover, length(cameraPos.xz + dir.xz * t1 - fogCenter));
  float keep = cover > 1e-5 ? limited / cover : 1.0;
  float lightKeep = mix(keep, 1.0, FOG_LIGHT_COVER_KEEP);

  FragFog = vec4(scatterAmbient * keep + (scatterSun + lamps) * lightKeep, 1.0 - limited);
  // and no fog in the dark under the world (composite_frag.glsl)
  if (sky) {
    FragFog = mix(FragFog, vec4(0.0, 0.0, 0.0, 1.0), voidBelow(dir));
  }
}
