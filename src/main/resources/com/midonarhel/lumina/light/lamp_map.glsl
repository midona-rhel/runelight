// The light map (light/LightMap.java, light/LightMapBake.java, light/LightMapBuffer.java): how much of each fixed lamp
// reaches a point, baked per storey in cells of a quarter tile (soft line of sight from a disc 0.8 tiles wide, light
// wrapping round corners, a blur that never crosses a wall, the models at the heights they stand at, the other storeys
// through their openings), LIGHT_MAP_LAYERS layers over a storey's height, read filtered across the layers. A cell holds
// four slots: the table ids of the lamps in them and their visibility; a lamp has one slot for as far as it reaches, so a
// slot's visibility filtered from the cells round a point is that lamp's. In the light buffer after its fixed part, as
// LightMapBuffer lays it out: sparse blocks of 32 x 32 cells found through an index per storey, filtered here cell by
// cell. A read never takes visibility across a wall, filtered or not: the cells of a tile are joined by the tile's wall
// bits (LampWalls' layer of the walls now), and where a filtered read would take from a cell a wall cuts it off from, it
// reads where it stands.
//
// lamp.glsl's lampOpen takes the stricter of this and the walls: light/lamp.glsl includes it. Needs lightData,
// cluster_config and lamp.glsl's wall bits.

// a surface is looked up in the map this far off it along its normal (world units): a wall's lit face is read in front of
// it, in the cell the light fell on; and the storey it is on this far off it
#define LAMP_MAP_NORMAL_BIAS 20.0
#define LAMP_MAP_PLANE_BIAS 40.0
// how far above a floor (world units) a point must be to be on that storey
#define LAMP_MAP_PLANE_MARGIN 2.0
#define LAMP_MAP_CELL 32.0

// the header: blocks a side (0: no map), cells before local cell 0, tiles a side, tiles before local tile 0
vec4 lampMapHeader() {
  return texelFetch(lightData, LIGHT_MAP_HEADER);
}

// the storeys' floors (world y, down) on the map's extended tile t; a storey above the first with no floor there, far up
vec4 lampMapFloors(ivec2 t, vec4 h) {
  int size = int(h.z);
  t = clamp(t, ivec2(0), ivec2(size - 1));
  return texelFetch(lightData, LIGHT_MAP_FLOORS + t.x * size + t.y);
}

// The storey of a world point: the highest with a floor tile under it whose floor is below the point.
int lampMapPlane(vec3 w, vec4 h) {
  vec4 floors = lampMapFloors(ivec2(floor(w.xz / 128.0)) + int(h.w), h);
  int plane = 0;
  for (int k = 1; k < 4; ++k) {
    if (w.y <= floors[k] - LAMP_MAP_PLANE_MARGIN) {
      plane = k;
    }
  }
  return plane;
}

// the wall bits of a cell's tile on a storey (LampWalls' layer of the walls now: a wall on the edge toward a side,
// either tile's, and the diagonals)
int lampMapWalls(ivec2 cell, int plane, vec4 h) {
  int tiles = int(lampWallsHeader.x);
  if (tiles == 0) {
    return 0;
  }
  ivec2 t = (cell >> 2) - int(h.w) + int(lampWallsHeader.y);
  return int(lampWallBits(t, plane, 0, tiles));
}

// whether a diagonal wall of the tile (walls: its bits) runs through the cell: the baked light of such a cell is
// the best of the cells beside it, whichever side they are on, and is never read as the cell's
bool lampMapCut(int walls, ivec2 cell) {
  ivec2 l = cell & 3;
  return ((walls & int(LAMP_WALL_SW_NE)) != 0 && l.x == l.y) || ((walls & int(LAMP_WALL_NW_SE)) != 0 && l.x + l.y == 3);
}

// whether no wall of the tile's edge stands between cell a (walls: its tile's bits) and the cell b beside it
bool lampMapOpen(ivec2 a, int wallsA, ivec2 b) {
  if (a >> 2 == b >> 2) {
    return true;
  }
  ivec2 d = b - a;
  uint side = d.x > 0 ? LAMP_WALL_EAST : d.x < 0 ? LAMP_WALL_WEST : d.y > 0 ? LAMP_WALL_NORTH : LAMP_WALL_SOUTH;
  return (uint(wallsA) & side) == 0u;
}

// The place of the block that holds a cell on a storey, -1 where none does.
int lampMapPlace(ivec2 cell, int plane, vec4 h) {
  int perSide = int(h.x);
  ivec2 blk = cell >> 5;
  if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(blk, ivec2(perSide)))) {
    return -1;
  }
  int entry = (plane * perSide + blk.y) * perSide + blk.x;
  return int(texelFetch(lightData, LIGHT_MAP_INDEX + (entry >> 2))[entry & 3] + 0.5) - 1;
}

// A cell's two texels (LightMapBuffer: eight numbers of 24 bits) in the block at a place; zeros for none (place < 0).
void lampMapCellIn(ivec2 cell, int place, out uvec4 a, out uvec4 b) {
  a = uvec4(0u);
  b = uvec4(0u);
  if (place < 0) {
    return;
  }
  int i = LIGHT_MAP_BLOCKS + place * LIGHT_MAP_BLOCK_TEXELS + (((cell.y & 31) << 5) + (cell.x & 31)) * 2;
  a = uvec4(texelFetch(lightData, i) + 0.5);
  b = uvec4(texelFetch(lightData, i + 1) + 0.5);
}

// A cell's two texels; false where no block of the storey holds it.
bool lampMapCell(ivec2 cell, int plane, vec4 h, out uvec4 a, out uvec4 b) {
  int place = lampMapPlace(cell, plane, h);
  lampMapCellIn(cell, place, a, b);
  return place >= 0;
}

#if LIGHT_MAP_LAYERS != 4
#error lampMapLayer picks the bytes of four layers
#endif
// a layer's visibility of the four slots in a cell's numbers: bytes layer * 4 to layer * 4 + 3, three to a number
// (layer 0: bytes 0-3, the first number's three and the second's first; and so on), picked without indexing an array
vec4 lampMapLayer(uvec4 a, uvec4 b, int layer) {
  uvec4 w = layer == 0 ? uvec4(a.x, a.x, a.x, a.y) : layer == 1 ? uvec4(a.y, a.y, a.z, a.z)
    : layer == 2 ? uvec4(a.z, a.w, a.w, a.w) : uvec4(b.x, b.x, b.x, b.y);
  uvec4 shift = layer == 0 ? uvec4(0u, 8u, 16u, 0u) : layer == 1 ? uvec4(8u, 16u, 0u, 8u)
    : layer == 2 ? uvec4(16u, 0u, 8u, 16u) : uvec4(0u, 8u, 16u, 0u);
  return vec4((w >> shift) & 255u) * (1.0 / 255.0);
}

// the table ids of the cell's slots (0: none)
ivec4 lampMapIds(uvec4 b) {
  return ivec4(b.z & 4095u, b.z >> 12u, b.w & 4095u, b.w >> 12u);
}

// Which cells a filtered read at c (cell coordinates, o the cell it is in) takes its light from, and how much of each,
// so that it does not take it from across a wall: the filter mixes the cell with the ones it is nearest, one along each
// axis and the one between.
//
// Where a wall stands between the cell and one of them, that one is left out and the others take its share (the read
// stays in the cell's middle on that axis).
//
// Where a diagonal wall runs through some of the four (lampMapCut), the read is made with a diagonal wall's cell of no
// side: it stands for the cell above it on the side of the wall the read is on (below it on the other), whichever way the
// read goes; what lies across the wall is left out; the shares are what is left, over their sum. A cell then has the one
// value however it is read, so the light is smooth along the wall.
void lampMapTaps(vec2 c, ivec2 o, int plane, vec4 h, out ivec2 cell[4], out vec4 share) {
  vec2 f = c - (vec2(o) + 0.5);
  ivec2 s = ivec2(f.x < 0.0 ? -1 : 1, f.y < 0.0 ? -1 : 1);
  cell[0] = o;
  cell[1] = o + ivec2(s.x, 0);
  cell[2] = o + ivec2(0, s.y);
  cell[3] = o + s;
  int walls[4];
  walls[0] = lampMapWalls(o, plane, h);
  bool cut = lampMapCut(walls[0], o);
  for (int k = 1; k < 4; ++k) {
    walls[k] = cell[k] >> 2 == o >> 2 ? walls[0] : lampMapWalls(cell[k], plane, h);
    cut = cut || lampMapCut(walls[k], cell[k]);
  }
  bool hOpen = lampMapOpen(o, walls[0], cell[1]), vOpen = lampMapOpen(o, walls[0], cell[2]);
  bool dOpen = hOpen && vOpen && lampMapOpen(cell[1], walls[1], cell[3]) && lampMapOpen(cell[2], walls[2], cell[3])
    && !lampMapCut(walls[1], cell[1]) && !lampMapCut(walls[2], cell[2]);
  if (!cut) {
    if (!hOpen) {
      f.x = 0.0;
    }
    if (!vOpen) {
      f.y = 0.0;
    }
    if (hOpen && vOpen && !dOpen) {
      // the one between is the filter's only if both ways round to it are open
      if (abs(f.x) < abs(f.y)) {
        f.x = 0.0;
      } else {
        f.y = 0.0;
      }
    }
    vec2 a = abs(f);
    share = vec4((1.0 - a.x) * (1.0 - a.y), a.x * (1.0 - a.y), (1.0 - a.x) * a.y, a.x * a.y);
    return;
  }
  vec2 a = abs(f);
  share = vec4((1.0 - a.x) * (1.0 - a.y), a.x * (1.0 - a.y), (1.0 - a.x) * a.y, a.x * a.y);
  share.yw *= hOpen ? 1.0 : 0.0;
  share.zw *= vOpen ? 1.0 : 0.0;
  share.w *= dOpen ? 1.0 : 0.0;
  for (int j = 0; j < 4; ++j) {
    // across a diagonal wall of the tile of any of the four: on the other side of its line from the read
    ivec2 origin = cell[j] >> 2 << 2;
    vec2 at = c - vec2(origin);
    for (int k = 0; k < 4; ++k) {
      vec2 l = vec2(cell[k] - origin);
      if ((walls[j] & int(LAMP_WALL_SW_NE)) != 0 && (l.y - l.x) * (at.y - at.x) < 0.0) {
        share[k] = 0.0;
      }
      if ((walls[j] & int(LAMP_WALL_NW_SE)) != 0 && (l.x + l.y - 3.0) * (at.x + at.y - 4.0) < 0.0) {
        share[k] = 0.0;
      }
    }
  }
  for (int k = 0; k < 4; ++k) {
    if (lampMapCut(walls[k], cell[k])) {
      vec2 at = c - vec2(cell[k] >> 2 << 2);
      bool slash = (walls[k] & int(LAMP_WALL_SW_NE)) != 0 && (cell[k] & 3).x == (cell[k] & 3).y;
      cell[k].y += (slash ? at.y - at.x : at.x + at.y - 4.0) >= 0.0 ? 1 : -1;
    }
  }
  float total = dot(share, vec4(1.0));
  share = total > 0.0 ? share / total : vec4(1.0, 0.0, 0.0, 0.0);
}

// The lamps the map holds at a point: their visibility in vis, their table ids in ids (0: none), per slot. w is the
// world point, at where it is read (the point itself, or off its surface), on a storey. False where no lamp reaches.
bool lampMapSlots(vec3 w, vec3 at, int plane, vec4 h, out vec4 vis, out ivec4 ids) {
  vis = vec4(0.0);
  ids = ivec4(0);
  vec2 c = at.xz * (1.0 / LAMP_MAP_CELL) + h.y;
  ivec2 cell = ivec2(floor(c));
  uvec4 a, b;
  // the lamps of the cell the point is in; a cell next to a lamp's has it too, empty, so that filtering takes from it
  int place = lampMapPlace(cell, plane, h);
  if (place < 0) {
    return false;
  }
  lampMapCellIn(cell, place, a, b);
  ids = lampMapIds(b);
  if (all(equal(ids, ivec4(0)))) {
    return false;
  }
  // the height above the storey's floor, as a place between the layers' middles (the first and the last layer stand
  // for what is below and above them)
  float height = lampMapFloors(cell >> 2, h)[plane] - w.y;
  float layer = clamp(height * (1.0 / LIGHT_MAP_LAYER_HEIGHT) - 0.5, 0.0, float(LIGHT_MAP_LAYERS - 1));
  int below = int(floor(layer));
  int above = min(below + 1, LIGHT_MAP_LAYERS - 1);
  float up = layer - float(below);
  // the filter taking only from the cells that are not walled off; the layers' filter by hand
  ivec2 taps[4];
  vec4 share;
  lampMapTaps(c, cell, plane, h, taps, share);
  for (int k = 0; k < 4; ++k) {
    if (share[k] > 0.0) {
      uvec4 ta = a, tb = b;
      if (taps[k] != cell) {
        // a cell of the same block (the most of them) has its place
        lampMapCellIn(taps[k], all(equal(taps[k] >> 5, cell >> 5)) ? place : lampMapPlace(taps[k], plane, h), ta, tb);
      }
      vis += share[k] * mix(lampMapLayer(ta, tb, below), lampMapLayer(ta, tb, above), up);
    }
  }
  return true;
}

// the slots at the point last read: lamps one after another at the same point read the map once
vec3 lampMapReadP = vec3(1e30);
vec3 lampMapReadFrom = vec3(1e30);
vec4 lampMapReadVis = vec4(0.0);
ivec4 lampMapReadIds = ivec4(0);

// How much of the lamp with table id id the map lets reach camera-relative point p: off a surface, wallFrom is p moved
// off it by LAMP_WALL_BIAS along its normal (the map is read LAMP_MAP_NORMAL_BIAS off it, its storey found
// LAMP_MAP_PLANE_BIAS off it); in the air, p. 0 where the map does not hold the lamp.
float lampMapVisibility(int id, vec3 p, vec3 wallFrom) {
  if (p != lampMapReadP || wallFrom != lampMapReadFrom) {
    lampMapReadP = p;
    lampMapReadFrom = wallFrom;
    lampMapReadVis = vec4(0.0);
    lampMapReadIds = ivec4(0);
    vec4 h = lampMapHeader();
    if (h.x > 0.5) {
      vec3 off = (wallFrom - p) * (1.0 / LAMP_WALL_BIAS);
      vec3 w = p + cameraPos;
      int plane = lampMapPlane(w + off * LAMP_MAP_PLANE_BIAS, h);
      lampMapSlots(w, w + off * LAMP_MAP_NORMAL_BIAS, plane, h, lampMapReadVis, lampMapReadIds);
    }
  }
  for (int k = 0; k < 4; ++k) {
    if (lampMapReadIds[k] == id) {
      return lampMapReadVis[k];
    }
  }
  return 0.0;
}

// How much of the lamp with table id id the map lets reach camera-relative point p in the air, cheaply: the cell p is
// in, its layers filtered, no filter across the cells (the fog's glow: jittered along the ray and denoised, and the walls
// looked for besides, lampWallsOpen). 0 where the map does not hold the lamp.
float lampMapVisibilityNearest(int id, vec3 p) {
  vec4 h = lampMapHeader();
  if (h.x < 0.5) {
    return 0.0;
  }
  vec3 w = p + cameraPos;
  int plane = lampMapPlane(w, h);
  ivec2 cell = ivec2(floor(w.xz * (1.0 / LAMP_MAP_CELL) + h.y));
  uvec4 a, b;
  if (!lampMapCell(cell, plane, h, a, b)) {
    return 0.0;
  }
  ivec4 ids = lampMapIds(b);
  int s = ids.x == id ? 0 : ids.y == id ? 1 : ids.z == id ? 2 : ids.w == id ? 3 : -1;
  if (s < 0) {
    return 0.0;
  }
  float height = lampMapFloors(cell >> 2, h)[plane] - w.y;
  float layer = clamp(height * (1.0 / LIGHT_MAP_LAYER_HEIGHT) - 0.5, 0.0, float(LIGHT_MAP_LAYERS - 1));
  int below = int(floor(layer));
  int above = min(below + 1, LIGHT_MAP_LAYERS - 1);
  return mix(lampMapLayer(a, b, below)[s], lampMapLayer(a, b, above)[s], layer - float(below));
}
