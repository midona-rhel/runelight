// Global illumination: radiosity between tile sized patches of floor, wall and roof (GiPatches.java), relit on the CPU
// whenever the sky or sun changes (GlobalIllumination.java). Looked up at half resolution in the AO pass by the surface
// the pixel is on, never by the space around it, so nothing leaks through a wall, a roof or from another floor: a wall
// pixel reads the patches of its own wall face, a pixel facing up the roof patches around it, and anything else (floors,
// trees, objects, people) the floor patches under it, less the higher it is above them. Every lookup blends the patches
// around the pixel by how close they are, and the lookups fade into one another by how much of each there is, so the
// light varies smoothly along a surface and jumps only where the surface itself does (a wall's corner, where it meets
// the floor). The composite blends the result with its plain ambient light. Needs frame.glsl.

// RGBA16F, patch i at (i & GI_PATCH_MASK, i >> GI_PATCH_BITS): rgb the light the patch gathers by bouncing, in the units of
// the composite's ambient light, a 1 for a patch and 0 for none. The first patches are the floors', one slot per floor
// and tile: floor f's tile (x, z) is patch f * GI_TILES + z * GI_SIZE + x; then the roofs', one per tile: patch
// GI_ROOFS + z * GI_SIZE + x.
uniform sampler2D giPatches;
// RGBA32F, a texel per tile (x, z): rgb the world y of the ground of floors 0, 1 and 2 (y is down), a the world y of the
// tile's roof patch, or a height beyond GI_NO_ROOF where it has none.
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
#define GI_ROOFS 32448
#define GI_NO_ROOF 100000.0
#define GI_FACES 6
#define GI_PATCH_BITS 9
#define GI_PATCH_MASK 511
#define GI_TILE 128.0
#define GI_SQRT_HALF 0.70710678
// tiles over which the light fades back to the plain ambient toward the grid's edges
#define GI_EDGE_FADE 3.0
// a surface facing no more up or down than the first (the y of its normal) is a wall, more than the second is not, and
// between them it is both
#define GI_WALL_NORMAL_MIN 0.35
#define GI_WALL_NORMAL_MAX 0.65
// a surface facing up this much (the y of its normal) is wholly a roof if there is one, less than it, partly
#define GI_ROOF_NORMAL 0.3
// a horizontal normal is along an axis when its smaller component is less than this share of its greater one (tan 22.5)
#define GI_AXIS_RATIO 0.41421356
// a wall pixel lies this near the wall's line (the tile's edge, or its diagonal), fully within half of it
#define GI_WALL_REACH 48.0
// the light fades out over this much (bands) above the top of the wall
#define GI_WALL_TOP 0.5
// A floor is under a pixel when it is at most this far (world units) below it; on a wall, which ends where the floor
// above begins, only just. A pixel is wholly on the highest floor under it when it is that far or more above its
// slack, the floor's patches count for less the nearer it is to being under it, and the floor below counts for the rest.
#define GI_FLOOR_SLACK 64.0
#define GI_WALL_SLACK 24.0
#define GI_FLOOR_BLEND 48.0
#define GI_WALL_BLEND 24.0
// a neighbouring tile's ground counts when it is within this of the ground under the pixel, and not at all beyond twice
// that; the same for roofs, which slope, and a pixel is on a roof within the reach of it, and not at all beyond twice that
#define GI_FLOOR_TOLERANCE 96.0
#define GI_ROOF_TOLERANCE 128.0
#define GI_ROOF_REACH 96.0
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

// The floor a pixel at world height y is over, given a tile's three: the highest one that is not above it, within slack.
int giPlane(vec3 floors, float y, float slack) {
  return floors.b >= y - slack ? 2 : floors.g >= y - slack ? 1 : 0;
}

// Patch id: its light (rgb) and whether there is one (a).
vec4 giPatch(float id) {
  int i = int(id + 0.5);
  return texelFetch(giPatches, ivec2(i & GI_PATCH_MASK, i >> GI_PATCH_BITS), 0);
}

// Layer on top of what is there: acc has the light times how much of it there is in rgb and the sum of those in a; layer
// has the light in rgb and how much of it there is in a, and only fills what acc does not.
vec4 giOver(vec4 acc, vec4 layer) {
  float t = layer.a * (1.0 - acc.a);
  return vec4(acc.rgb + layer.rgb * t, acc.a + t);
}

// The floor patches of the 2x2 tiles at t0 (their spatial weights sw, their giFloors texels tap) for floor plane, for a point
// at height y: blended by sw, and a patch with none or whose ground is not about the height of the point's drops out by
// degrees, not at once. The heights are compared to the ground interpolated under the point, so which tile it is in
// makes no difference. rgb the light, a how much of it there is, less the higher the point is above the ground.
vec4 giFloor(ivec2 t0, vec4 sw, vec4 tap[4], int plane, float y) {
  vec4 fh = vec4(tap[0][plane], tap[1][plane], tap[2][plane], tap[3][plane]);
  float ssum = sw.x + sw.y + sw.z + sw.w;
  float ground = dot(sw, fh) / ssum;
  float fade = 1.0 - smoothstep(GI_OBJECT_FADE_START, GI_OBJECT_FADE_END, ground - y);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int k = 0; k < 4; ++k) {
    float w = sw[k] * (1.0 - smoothstep(GI_FLOOR_TOLERANCE, 2.0 * GI_FLOOR_TOLERANCE, abs(fh[k] - ground)));
    if (w <= 0.0) {
      continue;
    }
    ivec2 t = t0 + ivec2(k & 1, k >> 1);
    vec4 patch = giPatch(float(plane * GI_TILES + t.y * GI_SIZE + t.x));
    if (patch.a > 0.5) {
      sum += patch.rgb * w;
      wsum += w;
    }
  }
  return wsum > 0.0 ? vec4(sum / wsum, clamp(wsum / GI_FULL_WEIGHT, 0.0, 1.0) * fade) : vec4(0.0);
}

// The light under and above a point at world position world, the roof patches' where it is on a roof (one that faces up:
// facingUp 0..1) and the floor patches' under it. y is its height for finding its floor. Both blend the patches of the
// 2x2 tiles around the point by how close their centers are; the roof's are left out by degrees like the floor's. rgb the
// light, a how much of it there is.
vec4 giGround(vec3 world, float y, float facingUp) {
  ivec2 own = ivec2(floor(world.xz / GI_TILE));
  if (!giInside(own)) {
    return vec4(0.0);
  }
  vec2 u = world.xz / GI_TILE - 0.5;
  ivec2 t0 = ivec2(floor(u));
  vec2 f = u - vec2(t0);
  vec4 sw = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
  vec4 tap[4];
  for (int k = 0; k < 4; ++k) {
    ivec2 t = t0 + ivec2(k & 1, k >> 1);
    if (giInside(t)) {
      tap[k] = texelFetch(giFloors, t, 0);
    } else {
      tap[k] = vec4(0.0);
      sw[k] = 0.0;
    }
  }
  vec4 acc = vec4(0.0);

  // roofs
  if (facingUp > 0.0) {
    vec4 rw = sw;
    vec4 rh = vec4(tap[0].a, tap[1].a, tap[2].a, tap[3].a);
    for (int k = 0; k < 4; ++k) {
      if (rh[k] >= GI_NO_ROOF) {
        rw[k] = 0.0;
      }
    }
    float rsum = rw.x + rw.y + rw.z + rw.w;
    if (rsum > 0.0) {
      float roof = dot(rw, rh) / rsum;
      float reach = 1.0 - smoothstep(GI_ROOF_REACH, 2.0 * GI_ROOF_REACH, abs(world.y - roof));
      if (reach > 0.0) {
        vec3 sum = vec3(0.0);
        float wsum = 0.0;
        for (int k = 0; k < 4; ++k) {
          float w = rw[k] * (1.0 - smoothstep(GI_ROOF_TOLERANCE, 2.0 * GI_ROOF_TOLERANCE, abs(rh[k] - roof)));
          if (w <= 0.0) {
            continue;
          }
          ivec2 t = t0 + ivec2(k & 1, k >> 1);
          vec4 patch = giPatch(float(GI_ROOFS + t.y * GI_SIZE + t.x));
          if (patch.a > 0.5) {
            sum += patch.rgb * w;
            wsum += w;
          }
        }
        if (wsum > 0.0) {
          acc = giOver(acc, vec4(sum / wsum, clamp(wsum / GI_FULL_WEIGHT, 0.0, 1.0) * reach * facingUp));
        }
      }
    }
  }

  // floors: the highest one under the point, by degrees, and the one below it where that has no light
  if (acc.a < 1.0) {
    int ko = (f.x >= 0.5 ? 1 : 0) + (f.y >= 0.5 ? 2 : 0);
    int plane = giPlane(tap[ko].rgb, y, GI_FLOOR_SLACK);
    vec4 layer = giFloor(t0, sw, tap, plane, y);
    if (plane > 0) {
      layer.a *= smoothstep(0.0, GI_FLOOR_BLEND, tap[ko].rgb[plane] + GI_FLOOR_SLACK - y);
    }
    acc = giOver(acc, layer);
    if (plane > 0 && acc.a < 1.0) {
      acc = giOver(acc, giFloor(t0, sw, tap, plane - 1, y));
    }
  }
  return acc.a > 0.0 ? vec4(acc.rgb / acc.a, acc.a) : vec4(0.0);
}

vec4 giFace(ivec2 tile, int face, int plane) {
  return texelFetch(giWalls, ivec2(tile.x * GI_FACES + face, plane * GI_SIZE + tile.y), 0);
}

// The bands of the wall face e (a giWalls texel) around world height y, blended between the two whose centers are nearest.
// rgb the light, a whether the face has patches there: 0 above the wall, fading out just over its top.
vec4 giBands(vec4 e, float y) {
  int bands = int(e.g + 0.5) & 7;
  if (bands == 0) {
    return vec4(0.0);
  }
  float b = (e.b - y) / e.a;
  float top = 1.0 - smoothstep(float(bands), float(bands) + GI_WALL_TOP, b);
  if (top <= 0.0) {
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
  return w > 0.0 ? vec4((p0.rgb * w0 + p1.rgb * w1) / w, top) : vec4(0.0);
}

// The light on a pixel on a wall face of floor plane: the face, tile own's (the tile the normal points into) or the next one
// along the wall, found by the pixel's position along the wall (along: 0 at the tile's middle, 0.5 at its border, toward
// alongTile), blended along the wall and between its bands; rgb the light, a how much of it there is. kind is 0 for a face
// on a tile's edge and 1 or 2 for the side of a diagonal wall (see giWalls), and the face has to be of it.
vec4 giWallFace(float y, ivec2 own, int face, int kind, ivec2 alongTile, float along, int plane) {
  vec4 e0 = giFace(own, face, plane);
  if (e0.g < 0.5 || (int(e0.g + 0.5) >> 3) != kind) {
    return vec4(0.0);
  }
  ivec2 other = own + (along < 0.0 ? -alongTile : alongTile);
  vec4 e1 = giInside(other) ? giFace(other, face, plane) : vec4(0.0);
  if ((int(e1.g + 0.5) >> 3) != kind) {
    e1 = vec4(0.0);
  }
  vec4 w0 = giBands(e0, y);
  vec4 w1 = giBands(e1, y);
  float a0 = (1.0 - abs(along)) * w0.a;
  float a1 = abs(along) * w1.a;
  float wsum = a0 + a1;
  if (wsum <= 0.0) {
    return vec4(0.0);
  }
  return vec4((w0.rgb * a0 + w1.rgb * a1) / wsum, clamp(wsum / GI_FULL_WEIGHT, 0.0, 1.0));
}

// The light on a pixel at world position world on the wall face that its normal n (mostly horizontal) points away from:
// the face of the tile it looks into, found from the pixel's position and the way it faces (an edge between tiles for a
// normal along an axis, a tile's diagonal for one between them), of the floor the pixel is on and, where that has none,
// the one below. rgb the light, a how much of it there is, less the farther the pixel is from the wall's line; 0 if the
// pixel is not on a wall that has patches.
vec4 giWall(vec3 world, vec3 n) {
  vec2 a = abs(n.xz);
  bool diagonal = min(a.x, a.y) >= GI_AXIS_RATIO * max(a.x, a.y);
  ivec2 own;
  ivec2 alongTile = ivec2(0);
  float along = 0.0;
  float dist = 0.0;
  int face;
  int kind = 0;
  if (diagonal) {
    own = ivec2(floor(world.xz / GI_TILE));
    face = n.x > 0.0 ? 5 : 4;
    vec2 local = world.xz - vec2(own) * GI_TILE;
    // along the diagonal from tile to tile, and the distance from it; a "/" wall's sides face northwest and southeast, a
    // "\" wall's southwest and northeast
    float perp;
    kind = n.x * n.z < 0.0 ? 1 : 2;
    if (kind == 1) {
      perp = (local.y - local.x) * GI_SQRT_HALF;
      along = (local.x + local.y) / (2.0 * GI_TILE) - 0.5;
      alongTile = ivec2(1, 1);
    } else {
      perp = (local.x + local.y - GI_TILE) * GI_SQRT_HALF;
      along = (local.x - local.y) / (2.0 * GI_TILE);
      alongTile = ivec2(1, -1);
    }
    dist = abs(perp);
  } else if (a.x >= a.y) {
    // the edge between two tiles in x: the tile the normal points into has the wall on its west side if it points east
    float edge = floor(world.x / GI_TILE + 0.5);
    dist = abs(world.x - edge * GI_TILE);
    own = ivec2(int(edge) - (n.x < 0.0 ? 1 : 0), int(floor(world.z / GI_TILE)));
    face = n.x > 0.0 ? 3 : 1;
    alongTile = ivec2(0, 1);
    along = world.z / GI_TILE - float(own.y) - 0.5;
  } else {
    float edge = floor(world.z / GI_TILE + 0.5);
    dist = abs(world.z - edge * GI_TILE);
    own = ivec2(int(floor(world.x / GI_TILE)), int(edge) - (n.z < 0.0 ? 1 : 0));
    face = n.z > 0.0 ? 2 : 0;
    alongTile = ivec2(1, 0);
    along = world.x / GI_TILE - float(own.x) - 0.5;
  }
  float reach = 1.0 - smoothstep(0.5 * GI_WALL_REACH, GI_WALL_REACH, dist);
  if (reach <= 0.0 || !giInside(own)) {
    return vec4(0.0);
  }
  vec3 floors = texelFetch(giFloors, own, 0).rgb;
  int plane = giPlane(floors, world.y, GI_WALL_SLACK);
  vec4 layer = giWallFace(world.y, own, face, kind, alongTile, along, plane);
  if (plane > 0) {
    layer.a *= smoothstep(0.0, GI_WALL_BLEND, floors[plane] + GI_WALL_SLACK - world.y);
  }
  if (plane > 0 && layer.a < 1.0) {
    layer = giOver(vec4(layer.rgb * layer.a, layer.a), giWallFace(world.y, own, face, kind, alongTile, along, plane - 1));
    layer.rgb /= max(layer.a, 1e-4);
  }
  return vec4(layer.rgb, layer.a * reach);
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
  vec4 acc = vec4(0.0);
  float wall = 1.0 - smoothstep(GI_WALL_NORMAL_MIN, GI_WALL_NORMAL_MAX, abs(n.y));
  if (wall > 0.0) {
    vec4 w = giWall(world, n);
    acc = giOver(acc, vec4(w.rgb, w.a * wall));
  }
  if (acc.a < 1.0) {
    vec4 g = giGround(world, world.y + max(n.y, 0.0) * GI_DOWN_BIAS, smoothstep(0.0, GI_ROOF_NORMAL, -n.y));
    acc = giOver(acc, g);
  }
  return acc.a > 0.0 ? vec4(acc.rgb / acc.a, acc.a * fade) : vec4(0.0);
}
