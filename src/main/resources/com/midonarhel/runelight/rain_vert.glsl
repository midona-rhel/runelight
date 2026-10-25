#version 330

// Rain: one instanced quad per drop or splash, no vertex buffer. Each instance derives its position from its id
// and the time, wrapped inside its layer's box (weather_common.glsl), so drops stay put in the world as the boxes
// move with the camera. Drops end on the highest scenery in their column (occlusion map), so they stop on roofs,
// hidden ones included, and never fall inside buildings.

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"
#include "water_common.glsl"
#include "wind.glsl"
#include "weather_wade.glsl"

uniform int dropCount;
uniform int mistStage;         // > 0: one of the mist's stages (rain_frag.glsl), one triangle over the target
uniform float mist;            // how strong the mist is
uniform int wadeDroplets;      // 1: the instances are the droplets of wading steps, not drops or splashes
uniform SCENE_SAMPLER sceneMaterial;  // r emissive, b water bed (frag.glsl)
uniform usampler2D waterSurface;  // the water pass's pixels, 0 where there is none (water_common.glsl)
uniform ivec4 waterRect;
uniform vec3 wind;             // steady wind on the drops, world units per second; with the gust where a drop is, its slant
uniform float fallTime;        // seconds of fall at the weather's drop speed so far (it varies, so not time)
uniform vec4 rainStyle;        // the weather's drops as multiples of the defaults: length, speed, width, opacity
uniform vec3 windOffset;       // world units the wind has carried everything so far (it varies, so not wind * time)
uniform vec2 particleAmount;   // rain at the player, and the most anywhere in the box that the drop count follows

#define FALL_SPEED 2000.0      // world units per second
#define DROP_LENGTH 40.0       // streak length at rest, times the layer's size scale
#define DROP_WIDTH 1.6         // world units; streaks are at least a pixel wide, thinner ones fade instead
#define DROP_ALPHA 0.32
#define MIN_STREAK_PX 5.0      // streaks are at least this long on screen, whatever the camera pitch
#define MIN_STREAK_DIM 0.55    // brightness of a streak stretched from nothing to that, against a full one
#define NEAR_FADE 250.0        // drops this close to the camera fade out so they don't smear the view
#define COUNT_FADE 0.1         // as the drop count changes, the last tenth of drops fade instead of popping
#define LIT_ALPHA 0.8          // drops in bright point light show up to this much more
#define RAIN_GUST 1.2          // wind multiplier at a full gust (Weather.RAIN_GUST)

#define SPLASH_SKY 0.35        // share of the sky's color in the droplets' and the mist's
#define FOOT_TAP_PX 2.0        // pixels between the taps that check what the camera sees at a sprite's foot
#define FOOT_TOLERANCE 0.04    // share of the distance, plus FOOT_MARGIN world units, that scenery must stand in
#define FOOT_MARGIN 24.0       // front of a foot to hide it


// droplets thrown up by a step wading through rain: how many each, how long they fly (seconds), their sideways and
// upward speeds at most (world units per second), the pull on them, half the sprite's height and its opacity
#define WADE_DROPLETS 6
#define WADE_FLIGHT 0.3
#define WADE_OUT 70.0
#define WADE_UP 220.0
#define WADE_GRAVITY 1300.0
#define WADE_DROPLET_SIZE 1.8
#define WADE_DROPLET_ALPHA 0.5

out vec2 fQuad;                // x across the streak / sprite -1..1, y along it 0..1 (up the sprite)
flat out int fSplash;          // 0 drop, 1 a droplet, 2 the mist
flat out float fSoft;          // world units over which a droplet hides behind nearer scenery
out float fViewZ;
out float fAlpha;
flat out vec3 fColor;

// Share of the drops that fall here: the rain here (the player's plus the weather field's difference) over the
// rain the drop count is for. The box can reach from a dry region into a rainy one.
float rainShare(vec2 worldXZ) {
  return clamp((particleAmount.x + weatherFieldDelta(worldXZ).g) / max(particleAmount.y, 1e-3), 0.0, 1.0);
}

// corners of the two triangles: (side, end)
const vec2 CORNERS[6] = vec2[](
  vec2(-1.0, 0.0), vec2(1.0, 0.0), vec2(1.0, 1.0),
  vec2(-1.0, 0.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

// framebuffer pixels per world unit at view depth 1. Clip x is a scaled rotation of the view, so the length of
// its row is the scale whatever the camera angle.
float pixelScale() {
  return length(vec3(viewProj[0][0], viewProj[1][0], viewProj[2][0])) * float(sceneViewport.z) * 0.5;
}

vec4 offsetPixels(vec4 clip, vec2 px) {
  return clip + vec4(px / (vec2(sceneViewport.zw) * 0.5) * clip.w, 0.0, 0.0);
}

void drop(int id, vec2 corner) {
  float seed = float(id) + layerSpan.w;
  vec3 h = hash31(seed + 0.5);
  float speedScale = 0.85 + 0.3 * hash11(seed * 1.37 + 3.1);
  vec3 travel = vec3(windOffset.x, FALL_SPEED * speedScale * fallTime, windOffset.z);

  // head of the drop: random start plus travel, wrapped into the layer's box
  vec3 world = layerPosition(h, travel);
  float weight = layerWeight(world);
  float share = weight > 0.0 ? rainShare(world.xz) : 0.0;
  if (share <= 0.0) {
    gl_Position = CULLED;
    return;
  }
  vec3 head = world - cameraPos;
  // slanted harder where a gust is passing
  float gustHere = 1.0 + RAIN_GUST * windGustAt(world.xz);
  vec3 vel = vec3(wind.x * gustHere, FALL_SPEED * speedScale * rainStyle.y, wind.z * gustHere);

  // stop on whatever is above the ground: drops below the top surface of their column are hidden, and the
  // streak is cut where it enters the surface
  float roofY = occluderTop(head);
  float top = roofY - cameraPos.y;
  float len = DROP_LENGTH * speedScale * rainStyle.x * layerSpan.z;
  vec3 dir = normalize(vel);
  vec3 tail = head - dir * len;
  if (tail.y >= top) {
    gl_Position = CULLED;
    return;
  }
  head.y = min(head.y, top);

  vec4 c0 = viewProj * vec4(tail, 1.0);
  vec4 c1 = viewProj * vec4(head, 1.0);
  if (c0.w < NEAR || c1.w < NEAR) {
    gl_Position = CULLED;
    return;
  }

  // The fall's direction on screen is the tangent of the head's path (the derivative of its projection), which
  // also holds when the camera looks along the fall and the streak's two ends land on the same pixel: there it
  // points to or from the center, the vanishing point of the fall.
  vec4 cd = viewProj * vec4(dir, 0.0);
  vec2 tangent = (cd.xy * c1.w - c1.xy * cd.w) * vec2(sceneViewport.zw);
  float tangentLen = length(tangent);
  vec2 along = tangentLen > 1e-9 ? tangent / tangentLen : vec2(0.0, -1.0);
  vec2 across = vec2(-along.y, along.x);

  // a streak shorter than MIN_STREAK_PX on screen is stretched back along the fall to it, and dimmed by how
  // much of it is real, so a drop falling away from the camera is a short streak and not a squashed sliver
  float alongLen = length((c1.xy / c1.w - c0.xy / c0.w) * vec2(sceneViewport.zw) * 0.5);
  float shown = max(alongLen, MIN_STREAK_PX);
  if (alongLen < MIN_STREAK_PX) {
    c0 = offsetPixels(c1, -along * MIN_STREAK_PX);
  }

  vec4 c = corner.y < 0.5 ? c0 : c1;
  float widthPx = DROP_WIDTH * rainStyle.z * layerSpan.z * pixelScale() / c.w;
  float drawn = max(widthPx, 1.0);
  gl_Position = offsetPixels(c, across * corner.x * drawn * 0.5);

  // fade near the camera
  float near = smoothstep(NEAR_FADE, NEAR_FADE * 3.0, c.w);
  float count = float(dropCount);
  float added = clamp((count - float(id)) / (count * COUNT_FADE + 1.0), 0.0, 1.0);
  vec3 points = weatherPointLights(head, c1, vec3(0.0), roofY);
  float lit = min(dot(points, vec3(0.2126, 0.7152, 0.0722)), 1.0);
  fAlpha = DROP_ALPHA * rainStyle.w * amount * share * weight * near * added * min(widthPx / drawn * 1.6, 1.0) * mix(MIN_STREAK_DIM, 1.0, alongLen / shown) * (1.0 + LIT_ALPHA * lit);
  fQuad = vec2(corner.x, corner.y);
  fSplash = 0;
  fViewZ = c.w;
  fColor = weatherParticleColor(vec3(0.62, 0.68, 0.78), points);
}

vec3 impactColor(vec3 points) {
  // the sky's own color at the horizon, whitened a little: it melts into it
  return weatherParticleColor(mix(vec3(0.80, 0.84, 0.90), skyHorizonAt(sunAltitude), SPLASH_SKY), points);
}

// One droplet of a step: thrown up and out from the foot, falling back in a short arc. It is a splash sprite, drawn
// and hidden like one, only where the camera sees the ground at the step.
void wadeDroplet(int id, vec2 corner) {
  vec4 s = wadeSteps[id / WADE_DROPLETS];
  if (s.w <= 0.0 || s.z >= WADE_FLIGHT) {
    gl_Position = CULLED;
    return;
  }
  vec3 h = hash31(float(id) * 3.71 + 0.3);
  float angle = h.x * 6.28318530718;
  vec2 out2 = vec2(cos(angle), sin(angle)) * (0.3 + 0.7 * h.y) * WADE_OUT;
  float up = (0.4 + 0.6 * h.z) * WADE_UP;
  float t = s.z;
  vec3 foot = vec3(s.x, 0.0, s.y) - cameraPos;
  float top = occluderTop(foot);
  if (top > 1e8) {
    gl_Position = CULLED;
    return;
  }
  foot.y = top - cameraPos.y;
  vec4 cf = viewProj * vec4(foot, 1.0);
  vec2 px = (cf.xy / cf.w * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy);
  if (cf.w < NEAR || any(greaterThan(abs(cf.xy), vec2(cf.w))) || abs(sceneViewDepth(px) - cf.w) > cf.w * SPLASH_SURFACE_TOLERANCE + 8.0
    || waterSurfaceAt(waterSurface, waterRect, ivec2(px)).b != 0u || texelFetch(sceneMaterial, ivec2(px), 0).r > 0.01) {
    gl_Position = CULLED;
    return;
  }
  // y points down: up is negative
  vec3 p = foot + vec3(out2.x * t, -(up * t - 0.5 * WADE_GRAVITY * t * t), out2.y * t);
  p.y = min(p.y, foot.y);
  vec4 c = viewProj * vec4(p, 1.0);
  float r = max(WADE_DROPLET_SIZE * pixelScale() / c.w, 1.0);
  gl_Position = offsetPixels(c, vec2(corner.x * SPLASH_ASPECT, corner.y) * r);
  vec3 points = weatherPointLights(p, c, vec3(0.0), top);
  float lit = min(dot(points, vec3(0.2126, 0.7152, 0.0722)), 1.0);
  fAlpha = WADE_DROPLET_ALPHA * amount * s.w * (1.0 - t / WADE_FLIGHT) * (1.0 + LIT_ALPHA * lit);
  fQuad = corner;
  fSplash = 1;
  fSoft = 8.0;
  fViewZ = c.w;
  fColor = impactColor(points);
}

void main() {
  int id = gl_InstanceID;
  vec2 corner = CORNERS[gl_VertexID];
  if (mistStage != 0) {
    // one triangle over the whole target
    vec2 v = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    gl_Position = vec4(v * 2.0 - 1.0, 0.0, 1.0);
    fQuad = vec2(0.0);
    fSplash = 2;
    fSoft = 0.0;
    fViewZ = 0.0;
    fAlpha = mist;
    fColor = impactColor(vec3(0.0));
  } else if (wadeDroplets != 0) {
    wadeDroplet(id, corner);
  } else {
    drop(id, corner);
  }
}
