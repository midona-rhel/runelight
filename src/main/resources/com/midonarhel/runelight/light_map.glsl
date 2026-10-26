// The light map (LightMap.java, LightMapTexture.java): which of the static lights reach a point and how visible each is, per
// storey in cells of a quarter tile, one RGBA8 3D texture. Per storey LIGHT_MAP_LAYERS slices of
//   visibility: a channel per slot, how much of the light in that slot (a light has one slot all over) is not stopped on its way
//      to the cell, at the height of the slice's middle (LIGHT_MAP_LAYER_HEIGHT apart from the storey's floor; walls stop the light
//      at all of them, models at the heights they stand at). Read filtered, across the layers too: the pixel's height above its
//      storey's floor picks the place between two of them.
// and after all the storeys' visibility, per storey two slices
//   index: the table ids of the lights in the four slots, 16 bits each, read as they are (texelFetch).
// The table, in lightData from LIGHT_MAP_TABLE_BASE, has two texels to an id: the light's place (world) and its radius now, and its
// colour times its strength now (flicker, pulse, night and fades are in it). The light on a point is worked out as the clusters do
// theirs, for each light of the point's cell, times its visibility: lightMapLight. Nothing in the map is shading.
// A read never takes visibility across a wall, filtered or not: the cells of a tile are joined by the tile's wall bits
// (rooms.glsl), and where a filtered read would take from a cell the wall cuts it off from, it reads where it stands.
// Needs lightData, cluster_config, light_map_config and rooms.glsl. The map is there when the room map's header says 3.

#if LIGHT_MAP

uniform sampler3D lightMap;

// a surface is looked up in the map this far off it along its normal (world units): a wall's lit face is read in front of
// it, in the cell the light fell on
#define LIGHT_MAP_NORMAL_BIAS 20.0
#define LIGHT_MAP_CELL 32.0
// the slices of the texture: the visibility of every layer of the four storeys, then the index of the four
#define LIGHT_MAP_INDEX_BASE (LIGHT_MAP_LAYERS * 4)
#define LIGHT_MAP_SLICES float((LIGHT_MAP_LAYERS + 2) * 4)

bool lightMapOn() {
  return roomHeader.x > 2.5;
}

// The storey of a world point: the highest with a floor tile under it whose floor is below the point.
int lightMapPlane(vec3 w) {
  int size = int(roomHeader.y);
  ivec2 t = clamp(ivec2(floor(w.xz / 128.0)) + int(roomHeader.z), ivec2(0), ivec2(size - 1));
  int i = ROOM_BASE + 1 + (t.x * size + t.y) * ROOM_TEXELS;
  vec4 floors = texelFetch(lightData, i + 1);
  vec4 walls = texelFetch(lightData, i + 2);
  int plane = 0;
  for (int k = 1; k < 4; ++k) {
    // bit 19 of a tile's walls: a floor on that storey
    if (((int(walls[k]) >> 19) & 1) != 0 && w.y <= floors[k] - ROOM_PLANE_MARGIN) {
      plane = k;
    }
  }
  return plane;
}

// the texel of lightData for a cell's tile
int lightMapTile(ivec2 cell, int offset) {
  int size = int(roomHeader.y);
  ivec2 t = clamp(cell >> 2, ivec2(0), ivec2(size - 1));
  return ROOM_BASE + 1 + (t.x * size + t.y) * ROOM_TEXELS + offset;
}

// the wall bits of a cell's tile on a storey
int lightMapWalls(ivec2 cell, int plane) {
  return int(texelFetch(lightData, lightMapTile(cell, 2))[plane]);
}

// whether a diagonal wall of the tile (walls: its bits) runs through the cell: the baked light of such a cell is
// the best of the cells beside it, whichever side they are on, and is never read as the cell's
bool lightMapCut(int walls, ivec2 cell) {
  ivec2 l = cell & 3;
  return ((walls & ROOM_WALL_SW_NE) != 0 && l.x == l.y) || ((walls & ROOM_WALL_NW_SE) != 0 && l.x + l.y == 3);
}

// whether no wall of the tile's edge stands between cell a (walls: its tile's bits) and the cell b beside it
bool lightMapOpen(ivec2 a, int wallsA, ivec2 b) {
  if (a >> 2 == b >> 2) {
    return true;
  }
  ivec2 d = b - a;
  int side = d.x > 0 ? ROOM_WALL_EAST : d.x < 0 ? ROOM_WALL_WEST : d.y > 0 ? ROOM_WALL_NORTH : ROOM_WALL_SOUTH;
  return (wallsA & side) == 0;
}

// Where a filtered read at c (cell coordinates, o the cell it is in) takes its light from, so that it does not take it from
// across a wall: the filter mixes the cell with the ones it is nearest, one along each axis and the one between.
//
// Where a wall stands between the cell and one of them, that one is left out and the others take its share (the read stays
// in the cell's middle on that axis). Returns true, c moved so that the one filtered read of the texture does it.
//
// Where a diagonal wall runs through some of the four (lightMapCut), the read is made cell by cell instead: a diagonal
// wall's cell is of no side, so it stands for the cell above it on the side of the wall the read is on (below it on the
// other), whichever way the read goes; what lies across the wall is left out; the shares are what is left, over their sum.
// A cell then has the one value however it is read, so the light is smooth along the wall, where taking it from the cell
// the read is in would step from cell to cell. Returns false, the cells and their shares in cell and share.
bool lightMapTaps(inout vec2 c, ivec2 o, int plane, out ivec2 cell[4], out vec4 share) {
  vec2 f = c - (vec2(o) + 0.5);
  ivec2 s = ivec2(f.x < 0.0 ? -1 : 1, f.y < 0.0 ? -1 : 1);
  cell[0] = o;
  cell[1] = o + ivec2(s.x, 0);
  cell[2] = o + ivec2(0, s.y);
  cell[3] = o + s;
  int walls[4];
  walls[0] = lightMapWalls(o, plane);
  bool cut = lightMapCut(walls[0], o);
  for (int k = 1; k < 4; ++k) {
    walls[k] = cell[k] >> 2 == o >> 2 ? walls[0] : lightMapWalls(cell[k], plane);
    cut = cut || lightMapCut(walls[k], cell[k]);
  }
  bool hOpen = lightMapOpen(o, walls[0], cell[1]), vOpen = lightMapOpen(o, walls[0], cell[2]);
  bool dOpen = hOpen && vOpen && lightMapOpen(cell[1], walls[1], cell[3]) && lightMapOpen(cell[2], walls[2], cell[3])
    && !lightMapCut(walls[1], cell[1]) && !lightMapCut(walls[2], cell[2]);
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
    c = vec2(o) + 0.5 + f;
    return true;
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
      if ((walls[j] & ROOM_WALL_SW_NE) != 0 && (l.y - l.x) * (at.y - at.x) < 0.0) {
        share[k] = 0.0;
      }
      if ((walls[j] & ROOM_WALL_NW_SE) != 0 && (l.x + l.y - 3.0) * (at.x + at.y - 4.0) < 0.0) {
        share[k] = 0.0;
      }
    }
  }
  for (int k = 0; k < 4; ++k) {
    if (lightMapCut(walls[k], cell[k])) {
      vec2 at = c - vec2(cell[k] >> 2 << 2);
      bool slash = (walls[k] & ROOM_WALL_SW_NE) != 0 && (cell[k] & 3).x == (cell[k] & 3).y;
      cell[k].y += (slash ? at.y - at.x : at.x + at.y - 4.0) >= 0.0 ? 1 : -1;
    }
  }
  float total = dot(share, vec4(1.0));
  share = total > 0.0 ? share / total : vec4(1.0, 0.0, 0.0, 0.0);
  return false;
}

// The lights the map holds at a point: their visibility in vis, their table ids in ids (0: none), per slot. w is the point,
// at where it is read (the point itself, or off its surface), on a storey. False where no light reaches.
bool lightMapSlots(vec3 w, vec3 at, int plane, out vec4 vis, out ivec4 ids) {
  vis = vec4(0.0);
  ids = ivec4(0);
  float cells = roomHeader.y * 4.0;
  vec2 c = at.xz * (1.0 / LIGHT_MAP_CELL) + roomHeader.z * 4.0;
  ivec2 cell = ivec2(floor(c));
  if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(int(cells))))) {
    return false;
  }
  // the lights of the cell the point is in; a cell next to a light's has it too, empty, so that filtering takes from it
  vec4 low = texelFetch(lightMap, ivec3(cell, LIGHT_MAP_INDEX_BASE + plane * 2), 0) * 255.0 + 0.5;
  vec4 high = texelFetch(lightMap, ivec3(cell, LIGHT_MAP_INDEX_BASE + plane * 2 + 1), 0) * 255.0 + 0.5;
  ids = ivec4(int(low.x) + int(low.y) * 256, int(low.z) + int(low.w) * 256, int(high.x) + int(high.y) * 256,
    int(high.z) + int(high.w) * 256);
  if (all(equal(ids, ivec4(0)))) {
    return false;
  }
  // the height above the storey's floor, as a place between the layers' middles (the first and the last layer
  // stand for what is below and above them)
  float height = texelFetch(lightData, lightMapTile(cell, 1))[plane] - w.y;
  float layer = clamp(height * (1.0 / LIGHT_MAP_LAYER_HEIGHT) - 0.5, 0.0, float(LIGHT_MAP_LAYERS - 1));
  // the filter taking only from the cells that are not walled off
  ivec2 taps[4];
  vec4 share;
  if (lightMapTaps(c, cell, plane, taps, share)) {
    vis = textureLod(lightMap, vec3(c / cells, (float(plane * LIGHT_MAP_LAYERS) + layer + 0.5) / LIGHT_MAP_SLICES), 0.0);
  } else {
    // cell by cell: the layers' filter by hand
    float below = floor(layer);
    float above = min(below + 1.0, float(LIGHT_MAP_LAYERS - 1));
    int slice = plane * LIGHT_MAP_LAYERS;
    for (int k = 0; k < 4; ++k) {
      if (share[k] > 0.0) {
        vis += share[k] * mix(texelFetch(lightMap, ivec3(taps[k], slice + int(below)), 0),
          texelFetch(lightMap, ivec3(taps[k], slice + int(above)), 0), layer - below);
      }
    }
  }
  return true;
}

// A baked light's light at the world point w, the light with table id id and visibility vis there, worked out as the
// clusters do theirs (composite_frag.glsl pointLights): 117 HD's falloff by the distance. The colour (with the falloff and
// the visibility in it), the unit direction to the light and its height (world y). False where it adds none.
bool lightMapLight(int id, float vis, vec3 w, out vec3 color, out vec3 toLight, out float lightY) {
  color = vec3(0.0);
  toLight = vec3(0.0, -1.0, 0.0);
  lightY = w.y;
  if (id == 0 || vis <= 0.0) {
    return false;
  }
  vec4 posRadius = texelFetch(lightData, LIGHT_MAP_TABLE_BASE + id * 2);
  vec3 l = posRadius.xyz - w;
  float d2 = dot(l, l);
  float r2 = posRadius.w * posRadius.w;
  if (d2 >= r2) {
    return false;
  }
  toLight = l * inversesqrt(max(d2, 1.0));
  lightY = posRadius.y;
  color = texelFetch(lightData, LIGHT_MAP_TABLE_BASE + id * 2 + 1).rgb * (vis * pointLightFalloff(d2, r2));
  return true;
}

#endif
