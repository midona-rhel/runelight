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
// (flicker, pulse, fades). The brightest light of a cell flickers by its lead over the others, so where
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
// above the top layer's storey height the light fades out over this much: the map knows nothing of what is higher (the eaves
// and roofs above it, an outdoor facade's upper storeys)
#define LIGHT_MAP_FADE 60.0
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
// the best of the cells beside it, whichever side they are on, and is never read
bool lightMapCut(int walls, ivec2 cell) {
  ivec2 l = cell & 3;
  return ((walls & ROOM_WALL_SW_NE) != 0 && l.x == l.y) || ((walls & ROOM_WALL_NW_SE) != 0 && l.x + l.y == 3);
}

// whether light gets from cell a (walls: its tile's bits) to the cell b beside it: no wall on the edge between, a tile's
// edge being the tile's own bit, and no diagonal wall through either
bool lightMapJoined(ivec2 a, int wallsA, ivec2 b, int plane) {
  int wallsB = wallsA;
  if (a >> 2 != b >> 2) {
    ivec2 d = b - a;
    int side = d.x > 0 ? ROOM_WALL_EAST : d.x < 0 ? ROOM_WALL_WEST : d.y > 0 ? ROOM_WALL_NORTH : ROOM_WALL_SOUTH;
    if ((wallsA & side) != 0) {
      return false;
    }
    wallsB = lightMapWalls(b, plane);
  }
  return !lightMapCut(wallsA, a) && !lightMapCut(wallsB, b);
}

// Where a filtered read at c (cell coordinates, o the cell it is in) takes its light from, so that it does not take it from
// across a wall: the filter mixes the cell with the ones it is nearest, one along each axis and the one between, and
// where a wall stands between it and one of them, the read stays in the cell's middle on that axis.
vec2 lightMapSide(vec2 c, ivec2 o, int plane) {
  int walls = lightMapWalls(o, plane);
  vec2 f = c - (vec2(o) + 0.5);
  if (lightMapCut(walls, o)) {
    // in a cell a diagonal wall runs through: its light is of no side, the nearest cell on the side of the point's has it
    bool slash = (walls & ROOM_WALL_SW_NE) != 0 && (o & 3).x == (o & 3).y;
    float side = slash ? f.y - f.x : f.x + f.y;
    ivec2 s = slash ? (side > 0.0 ? ivec2(-1, 1) : ivec2(1, -1)) : (side > 0.0 ? ivec2(1, 1) : ivec2(-1, -1));
    return vec2(f.x * float(s.x) > f.y * float(s.y) ? o + ivec2(s.x, 0) : o + ivec2(0, s.y)) + 0.5;
  }
  ivec2 s = ivec2(f.x < 0.0 ? -1 : 1, f.y < 0.0 ? -1 : 1);
  ivec2 h = o + ivec2(s.x, 0), v = o + ivec2(0, s.y);
  bool hOpen = lightMapJoined(o, walls, h, plane), vOpen = lightMapJoined(o, walls, v, plane);
  if (!hOpen) {
    f.x = 0.0;
  }
  if (!vOpen) {
    f.y = 0.0;
  }
  if (hOpen && vOpen) {
    // the one between is the filter's only if both ways round to it are open
    ivec2 d = o + s;
    int wallsH = h >> 2 == o >> 2 ? walls : lightMapWalls(h, plane), wallsV = v >> 2 == o >> 2 ? walls : lightMapWalls(v, plane);
    if (!lightMapJoined(h, wallsH, d, plane) || !lightMapJoined(v, wallsV, d, plane)) {
      if (abs(f.x) < abs(f.y)) {
        f.x = 0.0;
      } else {
        f.y = 0.0;
      }
    }
  }
  return vec2(o) + 0.5 + f;
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
  float fade = 1.0 - smoothstep(LIGHT_MAP_LAYERS * LIGHT_MAP_LAYER_HEIGHT, LIGHT_MAP_LAYERS * LIGHT_MAP_LAYER_HEIGHT + LIGHT_MAP_FADE, height);
  if (fade <= 0.0) {
    return vec3(0.0);
  }
  float layer = clamp(height * (1.0 / LIGHT_MAP_LAYER_HEIGHT) - 0.5, 0.0, float(LIGHT_MAP_LAYERS - 1));
  vec3 uv = vec3(c / cells, (float(plane * LIGHT_MAP_LAYERS) + layer + 0.5) / LIGHT_MAP_SLICES);
  vec4 a = textureLod(lightMap, uv, 0.0);
  const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
  if (dot(a.rgb, LUMA) <= 0.0) {
    return vec3(0.0);
  }
  // lit: the filter taking only from the cells that are not walled off. What it takes from is what it took from before, or
  // less, so that what was dark stays dark
  c = lightMapSide(c, cell, plane);
  uv.xy = c / cells;
  a = textureLod(lightMap, uv, 0.0);
  float total = dot(a.rgb, LUMA);
  if (total <= 0.0) {
    return vec3(0.0);
  }
  vec4 b = textureLod(lightMap, vec3(uv.xy, (float(LIGHT_MAP_LAYERS * 4 + plane) + 0.5) / LIGHT_MAP_SLICES), 0.0);
  // the mean place of the lights: from the point read, which the filtered offsets are from
  vec3 mean = vec3(b.x, b.z, b.y) * LIGHT_MAP_PLACE_UNIT;
  vec3 l = vec3((c.x - roomHeader.z * 4.0) * LIGHT_MAP_CELL + mean.x, mean.y, (c.y - roomHeader.z * 4.0) * LIGHT_MAP_CELL + mean.z) - w;
  toLight = l / max(length(l), 1.0);
  lightY = mean.y;
  // the brightest light, the one read as it is: its flicker by its lead. Nothing read as it is may be a range or a
  // distance (a cut at one light's radius, a cell at a time): the reach of the lights is in the cells, filtered.
  int id = int(texelFetch(lightMap, ivec3(ivec2(floor(c)), LIGHT_MAP_LAYERS * 4 + plane), 0).w + 0.5);
  float swing = 1.0;
  if (id > 0) {
    float lead = clamp(a.a / total, 0.0, 1.0);
    swing = 1.0 - lead + lead * texelFetch(lightData, LIGHT_MAP_TABLE_BASE + id).x;
  }
  return a.rgb * (swing * fade);
}

#endif
