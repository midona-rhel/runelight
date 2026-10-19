#version 330

// The water surface as geometry (WaterSurface.java): one instance per water tile and the land tiles around it, each
// one flat quad at the surface height of the water map's corners (WaterMap.texture, so the surface runs under the
// banks and meets them where it crosses them). The fragment shader works out the normal and writes the surface for the
// composite to shade.

#include "water_common.glsl"

layout(location = 0) in uvec4 tile;  // extended tile x, y, water type, look slot (Water.java)

uniform mat4 waterViewProj;   // camera-relative world -> clip
uniform vec3 waterCamera;     // camera position, world
uniform sampler2D waterData;  // WaterMap.texture
uniform float waterSpeed;     // multiplier on flow

out vec3 vRel;        // camera-relative position, moved
out vec2 vXZ;         // world xz: the waves are evaluated there
out vec2 vFlow;       // world units per second
out vec4 vData;       // shore distance (tiles), wave energy, water share, turbulence
out float vDepth;     // how deep the bed lies here by the distance to the shore, smooth across tiles
flat out int vType;
flat out int vSlot;

// WaterMap.DEPTH: bed depth per tile from the shore; a water tile is at least WaterMap.MIN_DEPTH deep in its middle
const float BED_DEPTH[9] = float[](0.0, 82.0, 165.0, 258.0, 335.0, 385.0, 412.0, 451.0, 506.0);
#define BED_MIN 56.0

// The bed's depth going by the shore distance alone (tiles, the average of the water tiles around the corner, 0 on
// land): the bed itself sinks in a pit in the middle of every tile at the shore, which the water's color must not show
float nominalDepth(float shore) {
  float level = clamp(shore - 0.5, 0.0, 7.999);
  int i = int(level);
  float d = mix(BED_DEPTH[i], BED_DEPTH[i + 1], level - float(i));
  return shore > 0.0 ? max(d, BED_MIN * min(shore, 1.0)) : 0.0;
}

void main() {
  // corners 0 sw, 1 se, 2 nw, 3 ne (WaterSurface.QUAD)
  vec2 cell = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vec2 c = vec2(tile.xy) + cell;
  vec2 xz = (c - waterGrid.x) * WATER_TILE;
  int type = clamp(int(tile.z), 1, WATER_TYPES - 1);

  vec4 p0 = waterPage0(waterData, c);
  vec4 p1 = waterPage1(waterData, c);
  // the flow slows toward the bank within the tile
  vec2 flow = p0.gb * (WATER_TILE * waterSpeed * WATER_FLOW_SPEED[type]) * mix(BANK_SLOW, 1.0, smoothstep(0.5, 1.0, p1.b));

  vRel = vec3(xz.x, p0.r, xz.y) - waterCamera;
  vXZ = xz;
  vFlow = flow;
  vData = p1;
  vDepth = nominalDepth(p1.r);
  vType = type;
  vSlot = int(tile.w);
  gl_Position = waterViewProj * vec4(vRel, 1.0);
}
