// Rooms (RoomMap.java): which building room every tile is in, so point lights stay in their room instead of
// shining through walls. A header (roomHeader: enabled, tiles per side, extended tiles before local tile 0, whether the
// lights keep to their rooms), and in the light buffer after the lights, from texel ROOM_BASE + 1 (ROOM_BASE holds
// RoomMap's own copy of the header), ROOM_TEXELS texels per tile:
// the four planes' rooms (room + porch room * 4096, 0 outdoors), the four planes' floor heights (world y, down), the
// four planes' walls: the tile's wall bits (ROOM_WALL_*, as WallMap.java), plus 64 times the half the tile's room has
// where a diagonal wall crosses it (0 the north-west or north-east one), plus 128 times the room of the other half
// (0 outdoors; the tile's own room when both halves are in it), bit 19 a floor tile; the four planes' wall distances
// at the tile's corners (RoomAmbient), and where rain water collects on them (RoomMap.pool).
// The porch of a tile is the room whose light spills onto it through an opening: a doorway or any gap with no wall
// (the code is negative then; ROOM_ANY is the outdoors), or, for a room tile, the room across a hole in its floor or
// ceiling (positive).
// A light's room code is its second texel's w (LightClusters.java). Needs lightData and cluster_config.

uniform vec4 roomHeader;  // LightClusters.getRoomHeader

#define ROOM_TEXELS 5
// how far above a floor a point must be to be on that storey (world units): only a little slack for rounding. More
// put the foot of an upper storey's walls on the storey below, lit by its lamps and its indoor fill.
#define ROOM_PLANE_MARGIN 2.0
// a room ends this far above its floor; above it is a roof
#define ROOM_STOREY 420.0
// pixels look their room up this far off their surface along the normal: a wall's outer face is outdoors, its
// inner face in the room, a ceiling in the room below it
#define ROOM_NORMAL_BIAS 40.0
// share of a room's light on its porch, the tiles across an opening: the light spilling out of the door
#define ROOM_PORCH_SHARE 0.55
#define ROOM_ANY 4095
#define ROOM_SOFT 4096
// wall bits of a tile: its edges, and the diagonals from the south-west corner to the north-east one ("/") and from
// the north-west corner to the south-east one
#define ROOM_WALL_NORTH 1
#define ROOM_WALL_EAST 2
#define ROOM_WALL_SOUTH 4
#define ROOM_WALL_WEST 8
#define ROOM_WALL_SW_NE 16
#define ROOM_WALL_NW_SE 32

// x the room of the tile under world point w on the storey w is on, 0 outdoors, -1 unknown (no map, outside it);
// y the room whose porch it is or that lies across a hole, 0 none, plus ROOM_SOFT for an opening's porch;
// z the storey (plane), 0 where no room is
ivec3 roomPlaneAt(vec3 w) {
  vec4 h = roomHeader;
  if (h.x < 0.5) {
    return ivec3(-1, 0, 0);
  }
  int size = int(h.y);
  ivec2 t = ivec2(floor(w.xz / 128.0)) + int(h.z);
  if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, ivec2(size)))) {
    return ivec3(-1, 0, 0);
  }
  int i = ROOM_BASE + 1 + (t.x * size + t.y) * ROOM_TEXELS;
  vec4 rooms = texelFetch(lightData, i);
  if (rooms == vec4(0.0)) {
    return ivec3(0);
  }
  vec4 floors = texelFetch(lightData, i + 1);
  // the highest storey whose floor is below the point (y points down)
  int plane = 0;
  for (int k = 1; k < 4; ++k) {
    if (w.y <= floors[k] - ROOM_PLANE_MARGIN) {
      plane = k;
    }
  }
  float code = rooms[plane];
  bool soft = code < 0.0;
  code = abs(code);
  int porch = int(floor(code / 4096.0));
  int room = int(code - float(porch) * 4096.0);
  int walls = int(texelFetch(lightData, i + 2)[plane]);
  if ((walls & (ROOM_WALL_SW_NE | ROOM_WALL_NW_SE)) != 0 && ((walls >> 7) & 4095) != room) {
    // a diagonal wall crosses the tile: the far side of it is the other half's room, with no porch
    vec2 f = fract(w.xz / 128.0);
    int h = (walls & ROOM_WALL_SW_NE) != 0 ? (f.y > f.x ? 0 : 1) : (f.x + f.y > 1.0 ? 0 : 1);
    if (h != ((walls >> 6) & 1)) {
      room = (walls >> 7) & 4095;
      porch = 0;
      soft = false;
    }
  }
  if (room != 0 && w.y < floors[plane] - ROOM_STOREY) {
    room = 0;
    porch = 0;
  }
  return ivec3(room, soft && porch > 0 ? porch + ROOM_SOFT : porch, plane);
}

ivec2 roomAt(vec3 w) {
  return roomPlaneAt(w).xy;
}

// How much of a light with room code `code` reaches a point in room r (roomAt)
float roomShare(float code, ivec2 r) {
  float c = abs(code) - 1.0;
  if (r.x < 0 || c < 0.0) {
    return 1.0;
  }
  float hi = floor(c / 4096.0);
  int a = int(c - hi * 4096.0);
  int b = int(hi);
  if (a == ROOM_ANY || a == r.x || b == r.x) {
    return 1.0;
  }
  int near = r.y & (ROOM_SOFT - 1);
  if (near == 0 || (a != near && b != near && (near != ROOM_ANY || (a != 0 && b != 0)))) {
    return 0.0;
  }
  // across an opening the light only spills; through a hole in a floor it shines in full
  return r.y >= ROOM_SOFT ? ROOM_PORCH_SHARE : 1.0;
}

// roomAt for lighting: unknown (every light reaches it) when the lights don't keep to their rooms
ivec3 lightRoomPlaneAt(vec3 w) {
  return roomHeader.w > 0.5 ? roomPlaneAt(w) : ivec3(-1, 0, 0);
}

ivec2 lightRoomAt(vec3 w) {
  return lightRoomPlaneAt(w).xy;
}

// Distance in tiles (1: a tile or more) to the nearest wall from a point on a storey, baked at the tile corners
// (RoomAmbient) for outdoor tiles as well as rooms; f is the point's place in its tile.
float tileWallDistance(int index, int plane, vec2 f) {
  int packed = int(texelFetch(lightData, index + 3)[plane]);
  vec4 distances = vec4(packed & 63, (packed >> 6) & 63, (packed >> 12) & 63, (packed >> 18) & 63) / 63.0;
  float distance = mix(mix(distances.x, distances.y, f.x), mix(distances.z, distances.w, f.x), f.y);
  int walls = int(texelFetch(lightData, index + 2)[plane]);
  if ((walls & ROOM_WALL_SW_NE) != 0) distance = min(distance, abs(f.y - f.x) * 0.70710678);
  if ((walls & ROOM_WALL_NW_SE) != 0) distance = min(distance, abs(f.x + f.y - 1.0) * 0.70710678);
  return distance;
}

// Version 2 bakes wall distance; older running clients have no ambient field yet.
float roomAmbientFill(vec3 world, vec3 normal) {
  vec4 header = roomHeader;
  if (header.x < 1.5) return 0.0;
  // looked up off the surface, as the lights' rooms are: a wall's outer face is outdoors, its inner face in the room
  vec3 receiver = world + normal * ROOM_NORMAL_BIAS;
  ivec3 room = roomPlaneAt(receiver);
  if (room.x <= 0) return 0.0;
  ivec2 tile = ivec2(floor(receiver.xz / 128.0)) + int(header.z);
  int index = ROOM_BASE + 1 + (tile.x * int(header.y) + tile.y) * ROOM_TEXELS;
  float distance = tileWallDistance(index, room.z, fract(receiver.xz / 128.0));
  return mix(0.6, 1.0, smoothstep(0.0, 1.0, distance));
}

// Wall distance of the ground at a world point, on the highest storey with a floor under it; 1 where nothing is known.
float wallDistanceAt(vec3 w) {
  vec4 header = roomHeader;
  if (header.x < 1.5) return 1.0;
  ivec2 tile = ivec2(floor(w.xz / 128.0)) + int(header.z);
  int size = int(header.y);
  if (any(lessThan(tile, ivec2(0))) || any(greaterThanEqual(tile, ivec2(size)))) {
    return 1.0;
  }
  int index = ROOM_BASE + 1 + (tile.x * size + tile.y) * ROOM_TEXELS;
  vec4 floors = texelFetch(lightData, index + 1);
  vec4 walls = texelFetch(lightData, index + 2);
  int plane = 0;
  for (int k = 1; k < 4; ++k) {
    // bit 19 of a tile's walls: a floor on that storey
    if (((int(walls[k]) >> 19) & 1) != 0 && w.y <= floors[k] - ROOM_PLANE_MARGIN) {
      plane = k;
    }
  }
  return tileWallDistance(index, plane, fract(w.xz / 128.0));
}

// Where rain water collects on the ground at world point w (RoomMap.pool), 0 to 1, interpolated between the tiles'
// middles on the storey w is on, and its change per world unit (xz); 0 where nothing is known.
float puddlePoolAt(vec3 w, out vec2 grad) {
  grad = vec2(0.0);
  vec4 header = roomHeader;
  if (header.x < 1.5) return 0.0;
  int size = int(header.y);
  ivec2 here = ivec2(floor(w.xz / 128.0)) + int(header.z);
  if (any(lessThan(here, ivec2(1))) || any(greaterThanEqual(here, ivec2(size - 1)))) {
    return 0.0;
  }
  int index = ROOM_BASE + 1 + (here.x * size + here.y) * ROOM_TEXELS;
  vec4 floors = texelFetch(lightData, index + 1);
  vec4 walls = texelFetch(lightData, index + 2);
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
    v[i] = texelFetch(lightData, ROOM_BASE + 1 + (q.x * size + q.y) * ROOM_TEXELS + 4)[plane];
  }
  float x0 = mix(v[0], v[1], f.x), x1 = mix(v[2], v[3], f.x);
  grad = vec2(mix(v[1] - v[0], v[3] - v[2], f.y), x1 - x0) / 128.0;
  return mix(x0, x1, f.y);
}
