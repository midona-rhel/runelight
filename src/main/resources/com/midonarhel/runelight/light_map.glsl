// The light map (LightMap.java, LightMapTexture.java): the static lights baked per storey into cells of a quarter tile, one
// 3D texture. Per storey LIGHT_MAP_LAYERS slices of
//   A: the lights' summed colour, each its colour times its strength times its share of the cell at the height of the slice's
//      middle (LIGHT_MAP_LAYER_HEIGHT apart from the storey's floor, walls stop the light at all of them, models at the heights they
//      stand at), and in alpha how far the brightest light is ahead of the next, in luminance. Read filtered, across the
//      layers too: the pixel's height above its storey's floor picks the place between two of them.
// and after all the storeys' A, per storey a slice
//   B: the lights' mean place, weighted by their luminance over the layers, from the cell's middle (x, z) and the height (y),
//      in sixteenths of a unit; and in alpha the table index of the brightest light, which is read as it is (texelFetch).
//      Read filtered across the cell but never across the slices; the cells round the lit ones carry the place too, so that
//      it needs no weighting.
// The table, in lightData from LIGHT_MAP_TABLE_BASE, has per light its strength now over the strength it was baked with
// (flicker, pulse, fades) and its radius, for what is higher than the layers. The brightest light of a cell flickers by its lead over the others, so where
// the brightest changes from one light to another the flicker changes smoothly with it.
// A read never takes light across a wall, filtered or not: the cells of a tile are joined by the tile's wall bits
// (rooms.glsl), and where a filtered read would take from a cell the wall cuts it off from, it reads where it stands.
// Needs lightData, cluster_config, light_map_config and rooms.glsl. The map is there when the room map's header says 3.

#if LIGHT_MAP

uniform sampler3D lightMap;

// a surface is looked up in the map this far off it along its normal (world units): a wall's lit face is read in front of
// it, in the cell the light fell on
#define LIGHT_MAP_NORMAL_BIAS 20.0
#define LIGHT_MAP_CELL 32.0
// the places in B are in this many world units to a stored unit
#define LIGHT_MAP_PLACE_UNIT 16.0
// the slices of the texture: the colour of every layer of the four storeys, then the places of the four
#define LIGHT_MAP_SLICES float((LIGHT_MAP_LAYERS + 1) * 4)

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

// The baked lights' light at the world point w, read at `at` (the point itself, or off its surface) on a storey: the
// colour, with the brightest light's flicker in it, in toLight the unit direction from w to
// the lights' mean place and in lightY its height (world y). Black, and toLight straight up, where no light reaches.
vec3 lightMapLight(vec3 w, vec3 at, int plane, out vec3 toLight, out float lightY) {
  toLight = vec3(0.0, -1.0, 0.0);
  lightY = w.y;
  float cells = roomHeader.y * 4.0;
  vec2 c = at.xz * (1.0 / LIGHT_MAP_CELL) + roomHeader.z * 4.0;
  ivec2 cell = ivec2(floor(c));
  if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(int(cells))))) {
    return vec3(0.0);
  }
  // the height above the storey's floor, as a place between the layers' middles (the first and the last layer
  // stand for what is below and above them)
  float height = texelFetch(lightData, lightMapTile(cell, 1))[plane] - w.y;
  float layer = clamp(height * (1.0 / LIGHT_MAP_LAYER_HEIGHT) - 0.5, 0.0, float(LIGHT_MAP_LAYERS - 1));
  vec3 uv = vec3(c / cells, (float(plane * LIGHT_MAP_LAYERS) + layer + 0.5) / LIGHT_MAP_SLICES);
  vec4 a = textureLod(lightMap, uv, 0.0);
  const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
  if (dot(a.rgb, LUMA) <= 0.0) {
    return vec3(0.0);
  }
  // lit: the filter taking only from the cells that are not walled off. What it takes from is what it took from before, or
  // less, so that what was dark stays dark
  ivec2 taps[4];
  vec4 share;
  vec3 place;
  int id;
  float total;
  if (lightMapTaps(c, cell, plane, taps, share)) {
    uv.xy = c / cells;
    a = textureLod(lightMap, uv, 0.0);
    vec4 b = textureLod(lightMap, vec3(uv.xy, (float(LIGHT_MAP_LAYERS * 4 + plane) + 0.5) / LIGHT_MAP_SLICES), 0.0);
    // the mean place of the lights: from the point read, which the filtered offsets are from
    place = vec3((c.x - roomHeader.z * 4.0) * LIGHT_MAP_CELL + b.x * LIGHT_MAP_PLACE_UNIT, b.z * LIGHT_MAP_PLACE_UNIT,
      (c.y - roomHeader.z * 4.0) * LIGHT_MAP_CELL + b.y * LIGHT_MAP_PLACE_UNIT);
    // the brightest light, the one read as it is: its flicker by its lead. Nothing read as it is may be a range or a
    // distance (a cut at one light's radius, a cell at a time): the reach of the lights is in the cells, filtered.
    id = int(texelFetch(lightMap, ivec3(ivec2(floor(c)), LIGHT_MAP_LAYERS * 4 + plane), 0).w + 0.5);
  } else {
    // cell by cell: the layers' filter by hand, the places from the cells' middles
    float below = floor(layer);
    float above = min(below + 1.0, float(LIGHT_MAP_LAYERS - 1));
    int slice = plane * LIGHT_MAP_LAYERS;
    a = vec4(0.0);
    place = vec3(0.0);
    float best = -1.0;
    for (int k = 0; k < 4; ++k) {
      if (share[k] > 0.0) {
        a += share[k] * mix(texelFetch(lightMap, ivec3(taps[k], slice + int(below)), 0),
          texelFetch(lightMap, ivec3(taps[k], slice + int(above)), 0), layer - below);
        vec4 b = texelFetch(lightMap, ivec3(taps[k], LIGHT_MAP_LAYERS * 4 + plane), 0);
        place += share[k] * vec3((float(taps[k].x) + 0.5 - roomHeader.z * 4.0) * LIGHT_MAP_CELL + b.x * LIGHT_MAP_PLACE_UNIT,
          b.z * LIGHT_MAP_PLACE_UNIT, (float(taps[k].y) + 0.5 - roomHeader.z * 4.0) * LIGHT_MAP_CELL + b.y * LIGHT_MAP_PLACE_UNIT);
        if (share[k] > best) {
          best = share[k];
          id = int(b.w + 0.5);
        }
      }
    }
  }
  total = dot(a.rgb, LUMA);
  if (total <= 0.0) {
    return vec3(0.0);
  }
  vec3 l = place - w;
  toLight = l / max(length(l), 1.0);
  lightY = place.y;
  float swing = 1.0;
  float reach = 1.0;
  if (id > 0) {
    vec4 entry = texelFetch(lightData, LIGHT_MAP_TABLE_BASE + id);
    float lead = clamp(a.a / total, 0.0, 1.0);
    swing = 1.0 - lead + lead * entry.x;
    // above the top layer's middle the map holds nothing more (what is higher than a storey is an outdoor facade's upper
    // wall): the light goes on falling with the distance, from what the top layer has, as the brightest light's reach does
    float over = height - (float(LIGHT_MAP_LAYERS) - 0.5) * LIGHT_MAP_LAYER_HEIGHT;
    if (over > 0.0) {
      float near = max(1.0 - length(vec3(l.x, l.y - over, l.z)) / entry.y, 0.0);
      float far = max(1.0 - length(l) / entry.y, 0.0);
      reach = near > 0.0 ? min((far * far) / (near * near), 1.0) : 0.0;
    }
  }
  return a.rgb * (swing * reach);
}

#endif
