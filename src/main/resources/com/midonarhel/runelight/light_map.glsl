// The light map (LightMap.java, LightMapTexture.java): the static lights baked per storey into cells of a quarter tile.
// Two layers a storey, layer 2 * storey and 2 * storey + 1, both read filtered (and so smooth, whatever the cells are):
//   A: the lights' summed colour, each its colour times its strength times its share of the cell (walls, doors and the
//      models in the way are in it), and in alpha how far the brightest light is ahead of the next, in luminance.
//   B: the lights' mean place, weighted by their luminance and premultiplied by it (the sum of luminance times the
//      place from the cell's middle, x and z, and the height, y, in sixteenths of a unit), so that it filters; and in
//      alpha the table index of the brightest light, which is read as it is (texelFetch).
// The table, in lightData from LIGHT_MAP_TABLE_BASE, has per light its strength now over the strength it was baked with
// (flicker, pulse, fades). The brightest light of a cell flickers by its lead over the others, so where
// the brightest changes from one light to another the flicker changes smoothly with it.
// Needs lightData, cluster_config, light_map_config and rooms.glsl. The map is there when the room map's header says 3.

#if LIGHT_MAP

uniform sampler2DArray lightMap;

// a surface is looked up in the map this far off it along its normal (world units): a wall's lit face is read in front of
// it, in the cell the light fell on
#define LIGHT_MAP_NORMAL_BIAS 20.0
#define LIGHT_MAP_CELL 32.0
// the places in layer B are in this many world units to a stored unit
#define LIGHT_MAP_PLACE_UNIT 16.0

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

// The baked lights' light at the world point w, read at `at` (the point itself, or off its surface) on a storey: the
// colour, with the brightest light's flicker in it, in toLight the unit direction from w to
// the lights' mean place and in lightY its height (world y). Black, and toLight straight up, where no light reaches.
vec3 lightMapLight(vec3 w, vec3 at, int plane, out vec3 toLight, out float lightY) {
  toLight = vec3(0.0, -1.0, 0.0);
  lightY = w.y;
  float cells = roomHeader.y * 4.0;
  vec2 c = at.xz * (1.0 / LIGHT_MAP_CELL) + roomHeader.z * 4.0;
  vec3 uv = vec3(c / cells, float(plane * 2));
  vec4 a = texture(lightMap, uv);
  float total = dot(a.rgb, vec3(0.2126, 0.7152, 0.0722));
  if (total <= 0.0) {
    return vec3(0.0);
  }
  ivec2 cell = ivec2(floor(c));
  if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(int(cells))))) {
    return vec3(0.0);
  }
  vec4 b = texture(lightMap, vec3(uv.xy, uv.z + 1.0));
  // the mean place of the lights: from the point read, which the filtered offsets are from
  vec3 mean = vec3(b.x, b.z, b.y) * (LIGHT_MAP_PLACE_UNIT / total);
  vec3 l = vec3(at.x + mean.x, mean.y, at.z + mean.z) - w;
  float d3 = length(l);
  toLight = l / max(d3, 1.0);
  lightY = mean.y;
  // the brightest light, the one read as it is: its flicker by its lead. Nothing read as it is may be a range or a
  // distance (a cut at one light's radius, a cell at a time): the reach of the lights is in the cells, filtered.
  int id = int(texelFetch(lightMap, ivec3(cell, plane * 2 + 1), 0).w + 0.5);
  float swing = 1.0;
  if (id > 0) {
    float lead = clamp(a.a / total, 0.0, 1.0);
    swing = 1.0 - lead + lead * texelFetch(lightData, LIGHT_MAP_TABLE_BASE + id).x;
  }
  return a.rgb * swing;
}

#endif
