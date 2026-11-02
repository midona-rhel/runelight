#version 330

// The water surface as geometry (WaterSurface.java): one instance per water tile and the land tiles around it, each
// one flat quad at the surface height of the water map's corners (WaterMap.texture). The land tiles are only worked out
// into the wave maps (their filtering reaches past the water's edge); on the screen the surface ends with the water
// tiles. The fragment shader works out the normal and writes the surface for the composite to shade.

#include "water_common.glsl"

layout(location = 0) in uvec4 tile;  // extended tile x, y, water type, look slot (Water.java) | 128 on land

uniform mat4 waterViewProj;   // camera-relative world -> clip
uniform vec3 waterCamera;     // camera position, world
uniform sampler2D waterData;  // WaterMap.texture
uniform int waterBake;        // 1: into the wave map (WAVE_MAP_TILES tiles wrapping) rather than onto the screen

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
  // the flow slows toward the bank; the falls' own (to a lip, down a fall, out from a foot) doesn't, a fall's channel is
  // all bank
  vec2 flow = p0.gb * (WATER_TILE * WATER_FLOW_SPEED[type])
    * mix(BANK_SLOW, 1.0, smoothstep(0.0, BANK_REACH, p1.r) * smoothstep(0.5, 1.0, p1.b))
    + waterPage2(waterData, c).ba * WATER_TILE;

  vRel = vec3(xz.x, p0.r, xz.y) - waterCamera;
  vXZ = xz;
  vFlow = flow;
  vType = type;
  vSlot = int(tile.w & 31u);
  if (waterBake == 1) {
    // the tile's place in the wrapping map; whole tiles, so none straddles its edge
    vec2 t = mod(vec2(tile.xy) - waterGrid.x, WAVE_MAP_TILES) + cell;
    gl_Position = vec4(t / WAVE_MAP_TILES * 2.0 - 1.0, 0.0, 1.0);
  } else if ((tile.w & 128u) != 0u) {
    // land: not on the screen (every corner at one point, no area)
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
  } else {
    gl_Position = waterViewProj * vec4(vRel, 1.0);
  }
}
