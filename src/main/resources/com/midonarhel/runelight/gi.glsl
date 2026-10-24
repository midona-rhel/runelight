// Global illumination: radiosity between tile sized patches of floor and wall (GiPatches.java), relit on the CPU
// whenever the sky or sun changes (GlobalIllumination.java). Looked up at half resolution in the AO pass by the surface
// the pixel is on, never by the space around it, so nothing leaks through a wall, a roof or from another floor: a floor
// pixel reads the floor patches around it, a wall pixel the patches of its own wall face, and anything else (roofs,
// trees, objects, people) the ground under it, less the higher it is above it. The composite blends the result with its
// plain ambient light. Needs frame.glsl.

// RGBA16F, patch i at (i & GI_PATCH_MASK, i >> GI_PATCH_BITS): rgb the light the patch gathers by bouncing, in the units of
// the composite's ambient light, a 1 for a patch and 0 for none. The first patches are the floors', one slot per floor
// and tile: floor f's tile (x, z) is patch f * GI_TILES + z * GI_SIZE + x.
uniform sampler2D giPatches;
// RGBA32F, a texel per tile (x, z): rgb the world y of the ground of floors 0, 1 and 2 (y is down).
uniform sampler2D giFloors;
// RGBA32F, a texel per wall face: face s of tile (x, z) on floor f at (x * GI_FACES + s, f * GI_SIZE + z). The faces are
// the four sides of the tile (north 0, east 1, south 2, west 3) and the two sides of a diagonal wall (4 looks west, 5
// east); a face looks into the tile. r the first patch of the face, its bands one after another going up, g the number of
// bands plus 8 for a diagonal wall from the south-west corner to the north-east one ("/") or 16 for the other ("\"), b the
// world y of its base, a the height of a band. g is 0 where there is no face.
uniform sampler2D giWalls;
uniform int giEnabled;
uniform vec3 giCamera;       // camera position relative to the patches' grid corner, world y

#define GI_SIZE 104
#define GI_TILES 10816
#define GI_FACES 6
#define GI_PATCH_BITS 9
#define GI_PATCH_MASK 511
#define GI_TILE 128.0
#define GI_SQRT_HALF 0.70710678
// tiles over which the light fades back to the plain ambient toward the grid's edges
#define GI_EDGE_FADE 3.0
// a surface facing no more up or down than this (the y of its normal) is a wall
#define GI_WALL_NORMAL 0.5
// a horizontal normal is along an axis when its smaller component is less than this share of its greater one (tan 22.5)
#define GI_AXIS_RATIO 0.41421356
// a wall pixel lies this near the wall's line (the tile's edge, or its diagonal)
#define GI_WALL_REACH 40.0
// a wall pixel this far (bands) above the top of the wall is above it
#define GI_WALL_TOP 0.25
// a floor is under a pixel when it is at most this far (world units) below it; on a wall, which can be taller than
// the gap to the floor above when nothing is built over it, only just
#define GI_FLOOR_SLACK 64.0
#define GI_WALL_SLACK 8.0
// a neighbouring tile's ground counts when it is within this of the pixel's own tile's, and not at all beyond twice that
#define GI_FLOOR_TOLERANCE 96.0
// the ground's light fades out on things above it between these heights
#define GI_OBJECT_FADE_START 64.0
#define GI_OBJECT_FADE_END 256.0
// A pixel facing down (a ceiling, the underside of an eave) is taken to be this much lower for finding its floor, which
// puts it under the floor above it rather than on it.
#define GI_DOWN_BIAS 96.0
// the share of the patches around a pixel that makes the light count fully; with less it fades back to the plain ambient
#define GI_FULL_WEIGHT 0.5

bool giInside(ivec2 t) {
  return all(greaterThanEqual(t, ivec2(0))) && all(lessThan(t, ivec2(GI_SIZE)));
}

// The floor a pixel at world height y is over, given a tile's three: the highest one that is not above it.
int giPlane(vec3 floors, float y, float slack) {
  return floors.b >= y - slack ? 2 : floors.g >= y - slack ? 1 : 0;
}

// Patch id: its light (rgb) and whether there is one (a).
vec4 giPatch(float id) {
  int i = int(id + 0.5);
  return texelFetch(giPatches, ivec2(i & GI_PATCH_MASK, i >> GI_PATCH_BITS), 0);
}

// The light under a point at world position world whose height for finding its floor is y: the floor patches of the 2x2
// tiles around it, on the floor under its own tile, blended by how close their centers are, and left out where their
// ground is not about the same height as that tile's. Renormalized over the patches there are. rgb the light, a how
// much of the point's surroundings have patches, less the higher the point is above that floor.
vec4 giFloor(vec3 world, float y) {
  ivec2 own = ivec2(floor(world.xz / GI_TILE));
  if (!giInside(own)) {
    return vec4(0.0);
  }
  vec3 ownFloors = texelFetch(giFloors, own, 0).rgb;
  int plane = giPlane(ownFloors, y, GI_FLOOR_SLACK);
  float ground = ownFloors[plane];
  float fade = 1.0 - smoothstep(GI_OBJECT_FADE_START, GI_OBJECT_FADE_END, ground - y);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  vec2 u = world.xz / GI_TILE - 0.5;
  ivec2 t0 = ivec2(floor(u));
  vec2 f = u - vec2(t0);
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int k = 0; k < 4; ++k) {
    ivec2 d = ivec2(k & 1, k >> 1);
    ivec2 t = t0 + d;
    float w = (d.x == 1 ? f.x : 1.0 - f.x) * (d.y == 1 ? f.y : 1.0 - f.y);
    if (w <= 0.0 || !giInside(t)) {
      continue;
    }
    float h = texelFetch(giFloors, t, 0).rgb[plane];
    w *= 1.0 - smoothstep(GI_FLOOR_TOLERANCE, 2.0 * GI_FLOOR_TOLERANCE, abs(h - ground));
    if (w <= 0.0) {
      continue;
    }
    vec4 patch = giPatch(float(plane * GI_TILES + t.y * GI_SIZE + t.x));
    if (patch.a > 0.5) {
      sum += patch.rgb * w;
      wsum += w;
    }
  }
  if (wsum <= 0.0) {
    return vec4(0.0);
  }
  return vec4(sum / wsum, clamp(wsum / GI_FULL_WEIGHT, 0.0, 1.0) * fade);
}

vec4 giFace(ivec2 tile, int face, int plane) {
  return texelFetch(giWalls, ivec2(tile.x * GI_FACES + face, plane * GI_SIZE + tile.y), 0);
}

// The bands of the wall face e (a giWalls texel) around world height y, blended between the two whose centers are nearest.
// rgb the light, a whether the face has patches there: 0 above the wall.
vec4 giBands(vec4 e, float y) {
  int bands = int(e.g + 0.5) & 7;
  if (bands == 0) {
    return vec4(0.0);
  }
  float b = (e.b - y) / e.a;
  if (b > float(bands) + GI_WALL_TOP) {
    return vec4(0.0);
  }
  b = clamp(b - 0.5, 0.0, float(bands - 1));
  float k0 = floor(b);
  float k1 = min(k0 + 1.0, float(bands - 1));
  vec4 p0 = giPatch(e.r + k0);
  vec4 p1 = giPatch(e.r + k1);
  float w0 = (1.0 - (b - k0)) * p0.a;
  float w1 = (b - k0) * p1.a;
  float w = w0 + w1;
  return w > 0.0 ? vec4((p0.rgb * w0 + p1.rgb * w1) / w, 1.0) : vec4(0.0);
}

// The light on a pixel at world position world on the wall face that its normal n (mostly horizontal) points away from:
// the face of the tile it looks into, found from the pixel's position and the way it faces (an edge between tiles for a
// normal along an axis, a tile's diagonal for one between them), blended along the wall with the next tile's face
// and between its bands. rgb the light, a how much of it there is; 0 if the pixel is not on a wall that has patches.
vec4 giWall(vec3 world, vec3 n) {
  vec2 a = abs(n.xz);
  bool diagonal = min(a.x, a.y) >= GI_AXIS_RATIO * max(a.x, a.y);
  ivec2 own;
  ivec2 alongTile = ivec2(0);
  float along = 0.0;
  int face;
  if (diagonal) {
    own = ivec2(floor(world.xz / GI_TILE));
    face = n.x > 0.0 ? 5 : 4;
  } else if (a.x >= a.y) {
    // the edge between two tiles in x: the tile the normal points into has the wall on its west side if it points east
    float edge = floor(world.x / GI_TILE + 0.5);
    if (abs(world.x - edge * GI_TILE) > GI_WALL_REACH) {
      return vec4(0.0);
    }
    own = ivec2(int(edge) - (n.x < 0.0 ? 1 : 0), int(floor(world.z / GI_TILE)));
    face = n.x > 0.0 ? 3 : 1;
    alongTile = ivec2(0, 1);
    along = world.z / GI_TILE - float(own.y) - 0.5;
  } else {
    float edge = floor(world.z / GI_TILE + 0.5);
    if (abs(world.z - edge * GI_TILE) > GI_WALL_REACH) {
      return vec4(0.0);
    }
    own = ivec2(int(floor(world.x / GI_TILE)), int(edge) - (n.z < 0.0 ? 1 : 0));
    face = n.z > 0.0 ? 2 : 0;
    alongTile = ivec2(1, 0);
    along = world.x / GI_TILE - float(own.x) - 0.5;
  }
  if (!giInside(own)) {
    return vec4(0.0);
  }
  int plane = giPlane(texelFetch(giFloors, own, 0).rgb, world.y, GI_WALL_SLACK);
  vec4 e0 = giFace(own, face, plane);
  int kind = (int(e0.g + 0.5) >> 3);
  if (e0.g < 0.5 || diagonal != (kind != 0)) {
    return vec4(0.0);
  }
  if (diagonal) {
    // along the diagonal from tile to tile, and the distance from it; the face looks toward north if it is the north-west
    // side of "/" or the north-east side of "\"
    vec2 local = world.xz - vec2(own) * GI_TILE;
    float perp;
    if (kind == 1) {
      perp = (local.y - local.x) * GI_SQRT_HALF;
      along = (local.x + local.y) / (2.0 * GI_TILE) - 0.5;
      alongTile = ivec2(1, 1);
    } else {
      perp = (local.x + local.y - GI_TILE) * GI_SQRT_HALF;
      along = (local.x - local.y) / (2.0 * GI_TILE);
      alongTile = ivec2(1, -1);
    }
    bool north = (kind == 1) == (face == 4);
    if (abs(perp) > GI_WALL_REACH || (n.z > 0.0) != north) {
      return vec4(0.0);
    }
  }
  ivec2 other = own + (along < 0.0 ? -alongTile : alongTile);
  vec4 e1 = giInside(other) ? giFace(other, face, plane) : vec4(0.0);
  if ((int(e1.g + 0.5) >> 3) != kind) {
    e1 = vec4(0.0);
  }
  vec4 w0 = giBands(e0, world.y);
  vec4 w1 = giBands(e1, world.y);
  float a0 = (1.0 - abs(along)) * w0.a;
  float a1 = abs(along) * w1.a;
  float wsum = a0 + a1;
  if (wsum <= 0.0) {
    return vec4(0.0);
  }
  return vec4((w0.rgb * a0 + w1.rgb * a1) / wsum, clamp(wsum / GI_FULL_WEIGHT, 0.0, 1.0));
}

// Indirect light on a surface at camera-relative p facing n: rgb the patches' light (the light that bounced onto the
// surface, in the units of the composite's ambient), a how much of it to use (0 outside the grid, or where the surface has
// no patches near it).
vec4 giIrradiance(vec3 p, vec3 n) {
  vec3 world = p + giCamera;
  vec2 t = world.xz / GI_TILE;
  vec2 edge = min(t, vec2(float(GI_SIZE)) - t);
  float fade = clamp(min(edge.x, edge.y) / GI_EDGE_FADE, 0.0, 1.0);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  vec4 gi = abs(n.y) < GI_WALL_NORMAL ? giWall(world, n) : vec4(0.0);
  if (gi.a <= 0.0) {
    gi = giFloor(world, world.y + max(n.y, 0.0) * GI_DOWN_BIAS);
  }
  return vec4(gi.rgb, gi.a * fade);
}
