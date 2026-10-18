#version 330

// Snow: one instanced camera-facing sprite per flake, no vertex buffer. Flakes drift down slowly and sway, wrapped
// inside their layer's box (weather_common.glsl). Flakes below the highest scenery in their column (occlusion map) are
// hidden, so there is no snow inside buildings, under hidden roofs included.

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"
#include "wind.glsl"

uniform int dropCount;
uniform vec3 windOffset;       // world units the wind has carried everything so far
uniform float fallTime;        // seconds of fall at the weather's flake speed so far (it varies, so not time)
uniform vec4 flakeStyle;       // the weather's flakes as multiples of the defaults: size, fall speed, sway, (wind drift)
uniform vec3 flakeColor;       // sRGB: white snow, tan dust, gray ash
uniform vec2 particleAmount;   // snow at the player, and the most anywhere in the box that the flake count follows

#define FALL_SPEED 190.0       // world units per second
#define SWAY 70.0              // world units
#define FLAKE_SIZE 8.0         // radius, world units, times the layer's size scale; never under MIN_PIXELS
#define MIN_PIXELS 2.2
#define FLAKE_ALPHA 0.85
#define NEAR_FADE 200.0
#define GUST_SWAY 1.0          // extra sway at full gust, as a multiple of SWAY
#define COUNT_FADE 0.1         // as the flake count changes, the last tenth fade instead of popping
#define LIT_ALPHA 0.3

out vec2 fQuad;
out float fViewZ;
out float fAlpha;
flat out vec3 fColor;

const vec2 CORNERS[6] = vec2[](
  vec2(-1.0, -1.0), vec2(1.0, -1.0), vec2(1.0, 1.0),
  vec2(-1.0, -1.0), vec2(1.0, 1.0), vec2(-1.0, 1.0));

float pixelScale() {
  return length(vec3(viewProj[0][0], viewProj[1][0], viewProj[2][0])) * float(sceneViewport.z) * 0.5;
}

void main() {
  int id = gl_InstanceID;
  vec2 corner = CORNERS[gl_VertexID];
  float seed = float(id) + layerSpan.w;
  vec3 h = hash31(seed + 0.5);
  vec3 r = hash31(seed * 3.17 + 11.0);

  float speed = FALL_SPEED * (0.7 + 0.6 * r.x);
  vec3 drift = vec3(windOffset.x, speed * fallTime, windOffset.z);
  vec3 world = layerPosition(h, drift);
  // two sways at different rates so the flakes don't move in lockstep, wider where a gust is passing
  float ph = r.y * 6.2832;
  float sway = SWAY * flakeStyle.z * (1.0 + GUST_SWAY * windGustAt(world.xz));
  world.x += sin(time * (0.6 + 0.5 * r.z) + ph) * sway;
  world.z += cos(time * (0.5 + 0.4 * r.x) + ph * 1.7) * sway;
  // the share of the flakes that fall here: the box can reach from a clear region into a snowy one
  float weight = layerWeight(world);
  float share = weight > 0.0
    ? clamp((particleAmount.x + weatherFieldDelta(world.xz).b) / max(particleAmount.y, 1e-3), 0.0, 1.0) : 0.0;
  if (share <= 0.0) {
    gl_Position = CULLED;
    return;
  }
  vec3 p = world - cameraPos;

  // hidden below the top surface of their column; fade out over the last bit above it
  float roofY = occluderTop(p);
  float above = roofY - p.y - cameraPos.y;
  if (above <= 0.0) {
    gl_Position = CULLED;
    return;
  }

  vec4 c = viewProj * vec4(p, 1.0);
  if (c.w < NEAR) {
    gl_Position = CULLED;
    return;
  }
  float radiusPx = FLAKE_SIZE * flakeStyle.x * layerSpan.z * (0.6 + 0.8 * r.z) * pixelScale() / c.w;
  float drawn = max(radiusPx, MIN_PIXELS);
  gl_Position = c + vec4(corner * drawn / (vec2(sceneViewport.zw) * 0.5) * c.w, 0.0, 0.0);

  float near = smoothstep(NEAR_FADE, NEAR_FADE * 3.0, c.w);
  // flakes smaller than a pixel fade instead of shrinking
  float sub = min(radiusPx / drawn, 1.0);
  float count = float(dropCount);
  float added = clamp((count - float(id)) / (count * COUNT_FADE + 1.0), 0.0, 1.0);
  vec3 points = weatherPointLights(p, c, vec3(0.0), roofY);
  float lit = min(dot(points, vec3(0.2126, 0.7152, 0.0722)), 1.0);
  fAlpha = FLAKE_ALPHA * amount * share * weight * near * smoothstep(0.0, 16.0, above) * sub * sub * added * (1.0 + LIT_ALPHA * lit);
  fQuad = corner;
  fViewZ = c.w;
  fColor = weatherParticleColor(flakeColor, points);
}
