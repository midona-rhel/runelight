// Rooms (RoomMap.java): which building room every tile is in, so point lights stay in their room instead of
// shining through walls, and the fog stays out of interiors. Stored in the light buffer after the lights, from
// texel ROOM_BASE: a header (enabled, tiles per side, extended tiles before local tile 0, whether the lights keep to
// their rooms), then two texels per tile:
// the four planes' rooms (room + porch room * 4096, 0 outdoors) and the four planes' floor heights (world y, down).
// The porch of an outdoor tile is the room whose doorway it lies across, that of a room tile the room across a hole
// in its floor or ceiling.
// A light's room code is its second texel's w (LightClusters.java). Needs lightData and cluster_config.

// how far above a floor a point must be to be on that storey (world units): the tops of the walls stay on theirs
#define ROOM_PLANE_MARGIN 24.0
// a room ends this far above its floor; above it is a roof
#define ROOM_STOREY 420.0
// pixels look their room up this far off their surface along the normal: a wall's outer face is outdoors, its
// inner face in the room, a ceiling in the room below it
#define ROOM_NORMAL_BIAS 40.0
// share of a room's light on its porch, the outdoor tile across an opening: the light spilling out of the door
#define ROOM_PORCH_SHARE 0.55
#define ROOM_ANY 4095

// x the room of the tile under world point w on the storey w is on, 0 outdoors, -1 unknown (no map, outside it);
// y the room whose porch it is or that lies across a hole, 0 none; z the storey (plane), 0 where no room is
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
  int i = ROOM_BASE + 1 + (t.x * size + t.y) * 2;
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
  int porch = int(floor(code / 4096.0));
  int room = int(code - float(porch) * 4096.0);
  if (room != 0 && w.y < floors[plane] - ROOM_STOREY) {
    room = 0;
    porch = 0;
  }
  return ivec3(room, porch, plane);
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
  if (r.y == 0 || (a != r.y && b != r.y)) {
    return 0.0;
  }
  // across a doorway the light only spills; through a hole in a floor it shines in full
  return r.x == 0 ? ROOM_PORCH_SHARE : 1.0;
}

// roomAt for lighting: unknown (every light reaches it) when the lights don't keep to their rooms
ivec3 lightRoomPlaneAt(vec3 w) {
  return texelFetch(lightData, ROOM_BASE).w > 0.5 ? roomPlaneAt(w) : ivec3(-1, 0, 0);
}

ivec2 lightRoomAt(vec3 w) {
  return lightRoomPlaneAt(w).xy;
}
