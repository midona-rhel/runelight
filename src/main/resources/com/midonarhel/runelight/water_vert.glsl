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
flat out int vType;
flat out int vSlot;

void main() {
  // corners 0 sw, 1 se, 2 nw, 3 ne (WaterSurface.QUAD)
  vec2 cell = vec2(float(gl_VertexID & 1), float(gl_VertexID >> 1));
  vec2 c = vec2(tile.xy) + cell;
  vec2 xz = (c - waterGrid.x) * WATER_TILE;
  int type = clamp(int(tile.z), 1, WATER_TYPES - 1);

  vec4 p0 = waterPage0(waterData, c);
  vec4 p1 = waterPage1(waterData, c);
  // the flow slows toward the bank
  vec2 flow = p0.gb * (WATER_TILE * waterSpeed * WATER_FLOW_SPEED[type])
    * mix(BANK_SLOW, 1.0, smoothstep(0.0, BANK_REACH, p1.r) * smoothstep(0.5, 1.0, p1.b));

  vRel = vec3(xz.x, p0.r, xz.y) - waterCamera;
  vXZ = xz;
  vFlow = flow;
  vType = type;
  vSlot = int(tile.w);
  gl_Position = waterViewProj * vec4(vRel, 1.0);
}
