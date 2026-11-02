// Rooms (RoomMap.java, sent by RoomTexture.java): which building room every tile is in, for the room fill and puddles.
// roomTexels has a layer per kind, an RGBA texel per tile holding the four planes': the rooms (0 outdoors), the floor
// heights (world y, down), the walls: the tile's wall bits (ROOM_WALL_*, as WallMap.java), plus 64 times the half the
// tile's room has where a diagonal wall crosses it (0 the north-west or north-east one), plus 128 times the room of
// the other half (0 outdoors; the tile's own room when both halves are in it), bit 19 a floor tile; and where rain
// water collects on them (RoomMap.pool). Rooms don't touch the lamps: walls alone stop those (light/lamp.glsl).
// The fill per room id is in lightData from ROOM_FILL_BASE. Needs lightData and cluster_config.

uniform sampler2DArray roomTexels;
uniform vec3 roomHeader;  // RoomTexture.header: 1 when there is a map, tiles per side, extended tiles before local tile 0

#define ROOM_LAYER_ROOMS 0
#define ROOM_LAYER_FLOORS 1
#define ROOM_LAYER_WALLS 2
#define ROOM_LAYER_POOLS 3
// how far above a floor a point must be to be on that storey (world units): only a little slack for rounding. More
// put the foot of an upper storey's walls on the storey below, lit by its indoor fill.
#define ROOM_PLANE_MARGIN 2.0
// a room ends this far above its floor; above it is a roof
#define ROOM_STOREY 420.0
// pixels look their room up this far off their surface along the normal: a wall's outer face is outdoors, its
// inner face in the room, a ceiling in the room below it
#define ROOM_NORMAL_BIAS 40.0
// wall bits of a tile: its edges, and the diagonals from the south-west corner to the north-east one ("/") and from
// the north-west corner to the south-east one
#define ROOM_WALL_NORTH 1
#define ROOM_WALL_EAST 2
#define ROOM_WALL_SOUTH 4
#define ROOM_WALL_WEST 8
#define ROOM_WALL_SW_NE 16
#define ROOM_WALL_NW_SE 32

vec4 roomTexel(ivec2 t, int layer) {
  return texelFetch(roomTexels, ivec3(t, layer), 0);
}

// x the room of the tile under world point w on the storey w is on, 0 outdoors, -1 unknown (no map, outside it);
// y the storey (plane), 0 where no room is
ivec2 roomPlaneAt(vec3 w) {
  vec3 h = roomHeader;
  if (h.x < 0.5) {
    return ivec2(-1, 0);
  }
  int size = int(h.y);
  ivec2 t = ivec2(floor(w.xz / 128.0)) + int(h.z);
  if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, ivec2(size)))) {
    return ivec2(-1, 0);
  }
  vec4 rooms = roomTexel(t, ROOM_LAYER_ROOMS);
  if (rooms == vec4(0.0)) {
    return ivec2(0);
  }
  vec4 floors = roomTexel(t, ROOM_LAYER_FLOORS);
  // the highest storey whose floor is below the point (y points down)
  int plane = 0;
  for (int k = 1; k < 4; ++k) {
    if (w.y <= floors[k] - ROOM_PLANE_MARGIN) {
      plane = k;
    }
  }
  int room = int(rooms[plane]);
  int walls = int(roomTexel(t, ROOM_LAYER_WALLS)[plane]);
  if ((walls & (ROOM_WALL_SW_NE | ROOM_WALL_NW_SE)) != 0 && ((walls >> 7) & 4095) != room) {
    // a diagonal wall crosses the tile: the far side of it is the other half's room
    vec2 f = fract(w.xz / 128.0);
    int side = (walls & ROOM_WALL_SW_NE) != 0 ? (f.y > f.x ? 0 : 1) : (f.x + f.y > 1.0 ? 0 : 1);
    if (side != ((walls >> 6) & 1)) {
      room = (walls >> 7) & 4095;
    }
  }
  if (room != 0 && w.y < floors[plane] - ROOM_STOREY) {
    room = 0;
  }
  return ivec2(room, plane);
}

// The fill a room is given (RoomLight.java), in luminance: the interior brightness, the same for every room. In lightData
// from ROOM_FILL_BASE, a texel a room id.
float roomFillLevel(int room) {
  return texelFetch(lightData, ROOM_FILL_BASE + room).x;
}

// The room of a surface at world point world with normal n, looked up off the surface as a wall's two faces need
// (ROOM_NORMAL_BIAS); 0 outdoors or unknown
int roomOfSurface(vec3 world, vec3 n) {
  return max(roomPlaneAt(world + n * ROOM_NORMAL_BIAS).x, 0);
}

// Where rain water collects on the ground at world point w (RoomMap.pool), 0 to 1, interpolated between the tiles'
// middles on the storey w is on, and its change per world unit (xz); 0 where nothing is known.
float puddlePoolAt(vec3 w, out vec2 grad) {
  grad = vec2(0.0);
  vec3 header = roomHeader;
  if (header.x < 0.5) return 0.0;
  int size = int(header.y);
  ivec2 here = ivec2(floor(w.xz / 128.0)) + int(header.z);
  if (any(lessThan(here, ivec2(1))) || any(greaterThanEqual(here, ivec2(size - 1)))) {
    return 0.0;
  }
  vec4 floors = roomTexel(here, ROOM_LAYER_FLOORS);
  vec4 walls = roomTexel(here, ROOM_LAYER_WALLS);
  int plane = 0;
  for (int k = 1; k < 4; ++k) {
    if (((int(walls[k]) >> 19) & 1) != 0 && w.y <= floors[k] - ROOM_PLANE_MARGIN) {
      plane = k;
    }
  }
  vec2 t = w.xz / 128.0 - 0.5;
  ivec2 b = ivec2(floor(t)) + int(header.z);
  vec2 f = fract(t);
  float v[4];
  for (int i = 0; i < 4; ++i) {
    ivec2 q = clamp(b + ivec2(i & 1, i >> 1), ivec2(0), ivec2(size - 1));
    v[i] = roomTexel(q, ROOM_LAYER_POOLS)[plane];
  }
  float x0 = mix(v[0], v[1], f.x), x1 = mix(v[2], v[3], f.x);
  grad = vec2(mix(v[1] - v[0], v[3] - v[2], f.y), x1 - x0) / 128.0;
  return mix(x0, x1, f.y);
}
