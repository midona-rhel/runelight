// Skylight, the AO pass's half: how much of the sky a pixel sees, from the sky visibility ray traced through the
// scene's voxels at the tiles' corners (SkyGrid.java), a few heights above each floor, toward up and the four sides.
// A pixel reads its own tile's four corners only, so nothing blends through a wall, and weighs the directions by its
// normal (an ambient cube): under the open sky it is 1 whichever way it faces, a wall under eaves sees the sky
// sideways, the ground under them doesn't see it overhead. Written to the shadow target's alpha at half resolution;
// the composite upsamples it with the AO and scales the sky and ambient light by it (light_occlusion.glsl).
// Needs frame.glsl.

uniform int skylightOn;
// RGBA16F, SKY_GRID_W x SKY_GRID_H x SKY_GRID_D (SkyGrid.java): slice 0 per tile corner the three floors' grounds
// (world y) and the tile's diagonal walls per floor, then a slice per floor and height: up, +x, -x, +z in the left
// half, -z in the right
uniform sampler3D skyGrid;
uniform vec3 skyGridCamera;  // the camera in the grid's coordinates: tile 0 at 0
// the sky's light flowing in under cover (SkyField.java): r the light, g 1 under cover; texture coordinates are
// camera-relative world xz * skyFieldMap.xy + skyFieldMap.zw
uniform sampler2D skyField;
uniform int skyFieldOn;
uniform vec4 skyFieldMap;

// as SkyGrid.java
#define SKY_GRID_TILES 104.0
#define SKY_GRID_W (4.0 * SKY_GRID_TILES)
#define SKY_GRID_H (2.0 * SKY_GRID_TILES)
#define SKY_GRID_D 10.0
#define SKY_GRID_LOW 40.0
#define SKY_GRID_STEP 72.0
// the point moves this far off its surface along the normal, so a surface reads the air in front of it
#define SKY_NORMAL_OFFSET 32.0
// how far above a floor a point must be to be on that storey, as rooms.glsl's ROOM_PLANE_MARGIN
#define SKY_PLANE_MARGIN 24.0

// The traced visibility at grid point w (world units, y down), weighed by normal n
float skyGridVisibility(vec3 w, vec3 n) {
  vec2 t = w.xz / 128.0;
  if (any(lessThan(t, vec2(0.0))) || any(greaterThanEqual(t, vec2(SKY_GRID_TILES)))) {
    return 1.0;
  }
  vec2 tile = floor(t);
  vec2 f = t - tile;
  // a tile's four corners are a 2 x 2 block of texels: from the first's center to the last's, never past them
  vec2 c = 2.0 * tile + 0.5;
  vec4 meta = textureLod(skyGrid, vec3((c + f) / vec2(SKY_GRID_W, SKY_GRID_H), 0.5 / SKY_GRID_D), 0.0);
  // the storey w is on, and how high above its floor
  int floorIndex = w.y <= meta.z - SKY_PLANE_MARGIN ? 2 : w.y <= meta.y - SKY_PLANE_MARGIN ? 1 : 0;
  float level = clamp((meta[floorIndex] - w.y - SKY_GRID_LOW) / SKY_GRID_STEP, 0.0, 2.0) + 3.0 * float(floorIndex);
  int diagonal = int(meta.w + 0.5) / (floorIndex == 0 ? 1 : floorIndex == 1 ? 3 : 9) % 3;
  if (diagonal != 0) {
    // a diagonal wall crosses the tile: each half has its own pair of corners, the north half the north ones, the
    // south half the south ones (SkyGrid.java), blended along x
    bool north = diagonal == 1 ? f.y > f.x : f.x + f.y > 1.0;
    f.y = north ? 1.0 : 0.0;
  }
  vec2 uv = (c + f) / vec2(SKY_GRID_W, SKY_GRID_H);
  float z = (1.5 + level) / SKY_GRID_D;
  vec4 a = textureLod(skyGrid, vec3(uv, z), 0.0);
  float minusZ = textureLod(skyGrid, vec3(uv.x + 0.5, uv.y, z), 0.0).r;
  // y points down: n.y < 0 faces up; facing down, a surface sees what the sides see
  vec3 nn = n * n;
  float down = (a.y + a.z + a.w + minusZ) * 0.25;
  return nn.y * (n.y < 0.0 ? a.x : down) + nn.x * (n.x > 0.0 ? a.y : a.z) + nn.z * (n.z > 0.0 ? a.w : minusZ);
}

// 1 under the open sky. Under cover (skyField's green: a roof or a floor over the tile) the light flowing in through
// the openings counts too: it stands for the light that gets deep into a room by bouncing off the walls, which the
// traced sky doesn't see; the trace stands for what the sky lights directly (a pavilion's open sides, a window), so
// neither darkens what the other lights.
float skylightVisibility(vec3 p, vec3 n) {
  if (skylightOn == 0) {
    return 1.0;
  }
  vec3 o = p + n * SKY_NORMAL_OFFSET;
  float v = skyGridVisibility(o + skyGridCamera, n);
  if (skyFieldOn == 1) {
    vec2 field = textureLod(skyField, o.xz * skyFieldMap.xy + skyFieldMap.zw, 0.0).rg;
    v = mix(v, max(v, field.r), field.g);
  }
  return v;
}
