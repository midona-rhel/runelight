// Rooms (RoomMap.java): which building room every tile is in, so point lights stay in their room instead of
// shining through walls, and the fog stays out of interiors. Stored in the light buffer after the lights, from
// texel ROOM_BASE: a header (enabled, tiles per side, extended tiles before local tile 0, whether the lights keep to
// their rooms), then four texels per tile (version 2):
// the four planes' rooms (room + porch room * 4096, 0 outdoors), the four planes' floor heights (world y, down), and
// the four planes' walls: the tile's wall bits (ROOM_WALL_*, as WallMap.java), plus 64 times the half the tile's
// room has where a diagonal wall crosses it (0 the north-west or north-east one), plus 128 times the room of the
// other half (0 outdoors; the tile's own room when both halves are in it). Bit 19 records a floor tile.
// The porch of a tile is the room whose light spills onto it through an opening: a doorway or any gap with no wall
// (the code is negative then; ROOM_ANY is the outdoors), or, for a room tile, the room across a hole in its floor or
// ceiling (positive).
// A light's room code is its second texel's w (LightClusters.java). Needs lightData and cluster_config.

// how far above a floor a point must be to be on that storey (world units): the tops of the walls stay on theirs
#define ROOM_PLANE_MARGIN 24.0
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
  vec4 h = texelFetch(lightData, ROOM_BASE);
  if (h.x < 0.5) {
    return ivec3(-1, 0, 0);
  }
  int size = int(h.y);
  ivec2 t = ivec2(floor(w.xz / 128.0)) + int(h.z);
  if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, ivec2(size)))) {
    return ivec3(-1, 0, 0);
  }
  int i = ROOM_BASE + 1 + (t.x * size + t.y) * (h.x > 1.5 ? 4 : 3);
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

// World y of the floor of the storey a point w is on, w itself when it is off the map
float roomFloorY(vec3 w) {
  vec4 h = texelFetch(lightData, ROOM_BASE);
  int size = int(h.y);
  ivec2 t = ivec2(floor(w.xz / 128.0)) + int(h.z);
  if (h.x < 0.5 || any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, ivec2(size)))) {
    return w.y;
  }
  vec4 floors = texelFetch(lightData, ROOM_BASE + 2 + (t.x * size + t.y) * (h.x > 1.5 ? 4 : 3));
  float y = floors[0];
  for (int k = 1; k < 4; ++k) {
    if (w.y <= floors[k] - ROOM_PLANE_MARGIN) {
      y = floors[k];
    }
  }
  return y;
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
  return texelFetch(lightData, ROOM_BASE).w > 0.5 ? roomPlaneAt(w) : ivec3(-1, 0, 0);
}

ivec2 lightRoomAt(vec3 w) {
  return lightRoomPlaneAt(w).xy;
}

// Version 2 bakes wall distance; older running clients have no ambient field yet.
float roomAmbientFill(vec3 world, vec3 normal) {
  vec4 header = texelFetch(lightData, ROOM_BASE);
  if (header.x < 1.5) return 0.0;
  vec3 receiver = world + normal;
  receiver.y -= max(-normal.y, 0.0) * ROOM_PLANE_MARGIN;
  ivec3 room = roomPlaneAt(receiver);
  if (room.x <= 0) return 0.0;
  ivec2 tile = ivec2(floor(receiver.xz / 128.0)) + int(header.z);
  int index = ROOM_BASE + 1 + (tile.x * int(header.y) + tile.y) * 4;
  int packed = int(texelFetch(lightData, index + 3)[room.z]);
  vec4 distances = vec4(packed & 63, (packed >> 6) & 63, (packed >> 12) & 63, (packed >> 18) & 63) / 63.0;
  vec2 f = fract(receiver.xz / 128.0);
  float distance = mix(mix(distances.x, distances.y, f.x), mix(distances.z, distances.w, f.x), f.y);
  int walls = int(texelFetch(lightData, index + 2)[room.z]);
  if ((walls & ROOM_WALL_SW_NE) != 0) distance = min(distance, abs(f.y - f.x) * 0.70710678);
  if ((walls & ROOM_WALL_NW_SE) != 0) distance = min(distance, abs(f.x + f.y - 1.0) * 0.70710678);
  return mix(0.6, 1.0, smoothstep(0.0, 1.0, distance));
}
