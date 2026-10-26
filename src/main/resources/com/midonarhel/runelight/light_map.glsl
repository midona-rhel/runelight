// The light map (LightMap.java, LightMapTexture.java): the static lights baked per storey into cells of a quarter tile.
// Two layers a storey, layer 2 * storey the filtered sums and 2 * storey + 1 the dominant lights:
//   A: the lights' summed colour, each its colour times its strength times its share of the cell (walls, doors and the
//      models in the way are in it), and in alpha the dominant light's part of that light.
//   B: read as it is, the dominant light's table index, its place from the cell's middle (x, z) and its height (world y).
// The table, in lightData from LIGHT_MAP_TABLE_BASE, has per light its strength now over the strength it was baked with
// (flicker, pulse, fades) and its radius. So only the dominant light of a cell flickers, and the light's direction and
// its vertical falloff are the dominant light's.
// Needs lightData, cluster_config, light_map_config and rooms.glsl. The map is there when the room map's header says 3.

#if LIGHT_MAP

uniform sampler2DArray lightMap;

// a surface is looked up in the map this far off it along its normal (world units): a wall's lit face is read in front of
// it, in the cell the light fell on
#define LIGHT_MAP_NORMAL_BIAS 20.0
#define LIGHT_MAP_CELL 32.0
// the most the vertical falloff (the light's distance from the point, in 3D, against its distance from the cell) may
// raise the baked light
#define LIGHT_MAP_VERTICAL_MAX 2.0

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
// colour, with the dominant light's flicker and the vertical falloff in it, in toLight the unit direction from w to
// the dominant light and in lightY its height (world y). Black, and toLight straight up, where no light reaches.
vec3 lightMapLight(vec3 w, vec3 at, int plane, out vec3 toLight, out float lightY) {
  toLight = vec3(0.0, -1.0, 0.0);
  lightY = w.y;
  float cells = roomHeader.y * 4.0;
  vec2 c = at.xz * (1.0 / LIGHT_MAP_CELL) + roomHeader.z * 4.0;
  vec4 a = texture(lightMap, vec3(c / cells, float(plane * 2)));
  if (a.r + a.g + a.b <= 0.0) {
    return vec3(0.0);
  }
  ivec2 cell = ivec2(floor(c));
  if (any(lessThan(cell, ivec2(0))) || any(greaterThanEqual(cell, ivec2(int(cells))))) {
    return vec3(0.0);
  }
  vec4 b = texelFetch(lightMap, ivec3(cell, plane * 2 + 1), 0);
  int id = int(b.x + 0.5);
  if (id <= 0) {
    return vec3(0.0);
  }
  vec4 entry = texelFetch(lightData, LIGHT_MAP_TABLE_BASE + id);
  vec2 middle = (vec2(cell) + 0.5 - roomHeader.z * 4.0) * LIGHT_MAP_CELL;
  lightY = b.w;
  vec3 l = vec3(middle.x + b.y, b.w, middle.y + b.z) - w;
  float d3 = length(l);
  float r = entry.y;
  // the bake fell off with the distance from the cell's middle to the light (in the plane); here it is the point's
  // distance, with the height
  float near = max(1.0 - length(b.yz) / r, 0.05);
  float far = max(1.0 - d3 / r, 0.0);
  float vertical = min((far * far) / (near * near), LIGHT_MAP_VERTICAL_MAX);
  toLight = l / max(d3, 1.0);
  return a.rgb * ((1.0 - a.a + a.a * entry.x) * vertical);
}

#endif
