#version 330

// The water surface as geometry (WaterSurface.java): one instance per water tile and the land tiles around it, each a
// grid of gridN x gridN quads at the surface height of the water map (WaterMap.texture, filtered between corners, so
// the surface runs under the banks and meets them where it crosses them). Near the camera the grid is moved by the
// swell (Gerstner waves) and by chop carried along the flow map; the fragment shader works out the normal and writes
// the surface for the composite to shade.

#include "water_common.glsl"

layout(location = 0) in uvec4 tile;  // extended tile x, y, water type, unused

uniform mat4 waterViewProj;   // camera-relative world -> clip
uniform vec3 waterCamera;     // camera position, world
uniform vec2 waterFocus;      // focal point x, z: the waves move around it
uniform int gridN;            // quads per tile side in this draw
uniform sampler2D waterData;  // WaterMap.texture
uniform vec3 waterWind;       // xy: direction the wind blows toward (x, z), z: strength 0-1, eased
uniform vec4 waterDrift;      // world units the waves (xy) have drifted with the wind
uniform float waterWaves;     // multiplier on wave height
uniform float waterSpeed;     // multiplier on flow
uniform float waterDisplace;  // 0 flat, 1 the surface moves
uniform vec2 waterFade;       // horizontal distance from the focal point over which the movement fades out

out vec3 vRel;        // camera-relative position, moved
out vec2 vXZ;         // world xz before moving: the waves are evaluated there
out vec2 vFlow;       // world units per second
out vec4 vData;       // shore distance (tiles), wave energy, water share, turbulence
out float vSwell;     // swell height, not faded with distance (the normal keeps it)
flat out int vType;

// the swell comes from far away: one direction for all water, not the local wind's
#define SWELL_DIR vec2(0.6, 0.8)
// vertex chop from the wave noise at this mip: the grid is too coarse for its finer waves
#define CHOP_LOD 2.0

void main() {
  int n1 = gridN + 1;
  vec2 cell = vec2(float(gl_VertexID % n1), float(gl_VertexID / n1)) / float(gridN);
  vec2 c = vec2(tile.xy) + cell;
  vec2 xz = (c - waterGrid.x) * WATER_TILE;
  int type = clamp(int(tile.z), 1, WATER_TYPES - 1);

  vec4 p0 = waterPage0(waterData, c);
  vec4 p1 = waterPage1(waterData, c);
  vec4 motion = WATER_MOTION[type];
  float wind = waterWind.z;
  // the flow slows toward the bank within the tile
  vec2 flow = p0.gb * (WATER_TILE * waterSpeed * motion.w) * mix(BANK_SLOW, 1.0, smoothstep(0.5, 1.0, p1.b));
  float flowing = smoothstep(FLOW_MIN * 0.5, FLOW_MIN, length(flow));

  // calm at the bank: the surface meets it at its own level
  float bank = smoothstep(0.5, 1.0, p1.b);
  float swellAmp = motion.x * waterWaves * (0.45 + 0.9 * wind) * (0.25 + 0.75 * p1.g) * (1.0 - flowing)
    * bank * smoothstep(0.5, 2.5, p1.r);
  vec2 slope = vec2(0.0);
  vec3 disp = waterSwell(xz, normalize(SWELL_DIR), swellAmp, motion.y, slope);

  // chop: the wave noise's height, carried by the flow like the fragment shader's waves (or drifting with the wind)
  vec2 base = waterDrift.xy * (motion.w * waterSpeed);
  vec2 m0 = base, m1 = base;
  float w0 = 1.0;
  if (flowing > 0.0)
    waterFlowPhases(xz, flow, base, m0, m1, w0);
  float h0 = textureLod(waterNoise, vec3((xz - m0) / WAVE_SCALE_A, 0.0), CHOP_LOD).b;
  float h1 = textureLod(waterNoise, vec3((xz + vec2(173.0, 91.0) - m1) / WAVE_SCALE_A, 0.0), CHOP_LOD).b;
  float hs = textureLod(waterNoise, vec3((xz - base) / WAVE_SCALE_A, 0.0), CHOP_LOD).b;
  float chop = mix(hs, h0 * w0 + h1 * (1.0 - w0), flowing) * 2.0 - 1.0;
  disp.y += chop * motion.z * waterWaves * (0.6 + 0.6 * wind) * (1.0 + p1.a) * bank;

  vec3 world = vec3(xz.x, p0.r, xz.y);
  float far = length(xz - waterFocus);
  // up is -y
  world += vec3(disp.x, -disp.y, disp.z) * (waterDisplace * (1.0 - smoothstep(waterFade.x, waterFade.y, far)));

  vRel = world - waterCamera;
  vXZ = xz;
  vFlow = flow;
  vData = p1;
  vSwell = swellAmp;
  vType = type;
  gl_Position = waterViewProj * vec4(vRel, 1.0);
}
