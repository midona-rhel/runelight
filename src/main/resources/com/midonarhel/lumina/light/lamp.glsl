// The one lamp (light/LightClusters.java, light/LampWalls.java): what a point light gives a point. Everything lit by
// lamps (the composite, the transparent faces, the fog, the weather, the water) takes their light from here: the same
// falloff, colour, size and occlusion.
//
// A lamp lights a point unless a wall or a floor stands between them, and then none of it reaches the point. Walls:
// those of the lamp's storey (WallMap: the edges the collision map says block sight, so doors open and close as the
// game has them, and diagonal walls), as tall as the world, walked tile by tile along the line from the point to the
// lamp (lampWallsShareIn). Floors: where the line leaves the
// lamp's storey through its ceiling or its floor, the tile it goes through must be open there (a ladder's hole, a
// courtyard), else it stops. Roofs, as drawn: where the line passes through a roofed tile's roof height (the lowest
// point of its roof objects, eaves included), it stops; a roof the client hides over the player stops nothing. Nothing
// else stops a lamp. A surface takes a lamp's light by the plain cosine to it, its
// smooth normal's, as it takes the sun's. The lamp's size (lampSize) only softens the tops of walls in the air.
//
// When the walls change (a door) the new ones ease in over 0.3 s (LayerEase): the layers of the walls before are kept,
// and both are walked.
//
// A lamp that stands still doesn't walk: its walls are baked on the CPU into its reach map (light/LampReach.java), the
// walls the rays round it meet, which a point reads instead (lampReachShare); the walk is for a lamp whose map isn't
// baked yet and while walls ease in. Roofs are walked only for a line that could pass through one.
//
// The fixed lamps are baked into the light map besides (light/lamp_map.glsl): how much of the lamp the line of sight
// from its disc, the light wrapping round corners, the blur, the models and the other storeys' openings let reach the
// point. A lamp the map carries lights a point by the stricter of the map and the walls (lampOpen: the least of the two);
// one it has not baked yet, or that moves, by the walls alone.
//
// Needs cluster_config and cameraPos (world position of the camera); positions are camera-relative.

// per lamp four texels: (position, radius), (colour times strength, the storey whose walls stop it or -1), (the
// point its lines start from: x, z, its storey's floor y, ceiling y), (its id in the light map, 0: none, the map's
// share as it eases in, its reach map's first texel, 0: none, the lowest drawn roof in its reach, as the roofs' layer
// holds it); then the rooms' fill; then from LAMP_WALLS_BASE five layers of LAMP_WALL_LAYER_TEXELS: the walls now and
// before, the ceilings now and before, the drawn roofs (LampWalls.java); then the lamps' reach maps (LampReach.java);
// then the light map (LightMapBuffer.java)
uniform samplerBuffer lightData;
uniform vec4 lampWallsHeader;        // now and before: tiles a side (0: no walls), extended tiles before local tile 0
uniform float lampWallsEase;         // the weight of the walls now against those before, 0 to 1

#include "light/light_clusters.glsl"

// wall bits of a tile (LampWalls.java), six a storey: a wall on its edge toward that side, either tile's; the diagonals
// from the south-west corner to the north-east one and from the north-west corner to the south-east one
#define LAMP_WALL_NORTH 1u
#define LAMP_WALL_EAST 2u
#define LAMP_WALL_SOUTH 4u
#define LAMP_WALL_WEST 8u
#define LAMP_WALL_SW_NE 16u
#define LAMP_WALL_NW_SE 32u
// tile edges a line can cross at most: a lamp reaches 65 tiles at most (lights.json's 5500 units, flickering 1.5 times
// that), and a line crosses at most twice as many edges as tiles
#define LAMP_WALL_STEPS 130
// A surface's walls are looked at from this far off it, along its normal (world units): a wall's lit face lies on its
// edge of the tile or inside the tile the wall belongs to, and a reconstructed position is a little off its surface.
#define LAMP_WALL_BIAS 16.0
// a point this far (world units) past the lamp's ceiling or floor is on another storey: the floor lets it through
// only where it is open. Only rounding's slack: the foot of an upper storey's walls is on that storey.
#define LAMP_FLOOR_MARGIN 4.0
// how tall walls are, in the fog, where the storey above isn't known (world units): a storey's height
#define LAMP_WALL_HEIGHT 240.0
// The size of the ball a lamp is, a share of its reach, and its least and most (world units): a torch of reach 525
// about 40, a furnace's 1050 about 85
#define LAMP_SIZE_SHARE 0.08
#define LAMP_SIZE_MIN 16.0
#define LAMP_SIZE_MAX 96.0

struct Lamp {
  vec3 pos;      // camera-relative
  float radius;
  vec3 color;    // linear, times its strength now
  int walls;     // the storey whose walls and floors stop it, -1 when none is in its reach
  vec2 origin;   // camera-relative x, z its lines start from: in its own tile, off the walls it hangs on
  float floorY;  // its storey's floor and ceiling (camera-relative y, down)
  float ceilY;
  int mapId;     // its table id in the light map, 0 for a lamp the map doesn't carry
  float mapEase; // the map's share of its visibility, 0 to 1 as it eases in (the walls' alone the rest)
  int reach;     // its reach map's first texel in lightData, 0: none (its lines are walked)
  float roofLow; // the lowest drawn roof in its reach, world y plus LAMP_ROOF_BIAS; 0: none
  int cube;      // its shadow cube's slot (LampCubes, light/lamp_cube.glsl), -1: none, or not drawn yet
};

// the second texel's w: the walls code plus 16 times the cube's slot + 1 (LightClusters)
int lampWallsCode(float w) {
  return ((int(w) + 1) & 15) - 1;
}

int lampCubeCode(float w) {
  return ((int(w) + 1) >> 4) - 1;
}

// A lamp's first texel: its camera-relative position and radius, all that says whether it reaches a point; the other
// three are read (lampWith) only for a lamp that does, so the lamps of a cluster that miss a pixel cost one read each.
// The radius is negative for a lamp the light map carries in full (lampOnMap): a surface takes such a lamp's light
// through the map's read, which holds the at most four lamps that reach its cell (lampOfMapId), not from the cluster.
vec4 lampSphere(int li) {
  return texelFetch(lightData, li * 4);
}

bool lampOnMap(vec4 sphere) {
  return sphere.w < 0.0;
}

Lamp lampWith(int li, vec4 a) {
  vec4 b = texelFetch(lightData, li * 4 + 1);
  vec4 c = texelFetch(lightData, li * 4 + 2);
  vec4 d = texelFetch(lightData, li * 4 + 3);
  return Lamp(a.xyz, abs(a.w), b.rgb, lampWallsCode(b.w), c.xy, c.z, c.w, int(d.x + 0.5), d.y, int(d.z + 0.5), d.w,
    lampCubeCode(b.w));
}

// The colour times strength of lamp li (its second texel), for choosing the lamps that give a point the most light
vec3 lampColorOf(int li) {
  return texelFetch(lightData, li * 4 + 1).rgb;
}

float lampLuminance(vec3 c) {
  return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

// The lamps that give a point the most light, at most LAMP_BUDGET (the light map holds four a cell): while the
// candidates come, the brightest four by their estimate (est: the light they would give, before walls) in e, brightest
// first, with what goes with each (who), and the most any left out would have given (left). The fourth fades out as the
// best left out nears it (lampBudgetFade), so where two swap places the light is the same either way: no seam.
#define LAMP_BUDGET 4

struct LampBudget {
  vec4 e;
  ivec4 who;
  vec4 open; // per kept lamp: how much of it the light map lets through, -1 where the walls must say (lampOpen)
  float left; // the most any lamp left out would have given
};

LampBudget lampBudgetEmpty() {
  return LampBudget(vec4(0.0), ivec4(-1), vec4(-1.0), 0.0);
}

void lampBudgetAdd(inout LampBudget b, float est, int who, float open) {
  if (est <= b.e.w) {
    b.left = max(b.left, est);
    return;
  }
  b.left = max(b.left, b.e.w);
  b.e.w = est;
  b.who.w = who;
  b.open.w = open;
  if (b.e.w > b.e.z) {
    b.e.zw = b.e.wz;
    b.who.zw = b.who.wz;
    b.open.zw = b.open.wz;
  }
  if (b.e.z > b.e.y) {
    b.e.yz = b.e.zy;
    b.who.yz = b.who.zy;
    b.open.yz = b.open.zy;
  }
  if (b.e.y > b.e.x) {
    b.e.xy = b.e.yx;
    b.who.xy = b.who.yx;
    b.open.xy = b.open.yx;
  }
}

// how much of kept lamp k (0 to 3) the budget lets through: all of the first three; the fourth less as the best left
// out comes near it, none where they are equal
float lampBudgetFade(LampBudget b, int k) {
  return k < 3 || b.left <= 0.0 ? 1.0 : clamp(1.0 - b.left / max(b.e.w, 1e-9), 0.0, 1.0);
}

// The lamp in this frame's list the light map's table id stands for, -1 when none (out of view, or not in full on it)
int lampOfMapId(int id) {
  return int(texelFetch(lightData, MAP_LAMPS_BASE + (id >> 2))[id & 3] + 0.5) - 1;
}

Lamp lampAt(int li) {
  return lampWith(li, lampSphere(li));
}

// 117 HD's falloff, from the squared distance and the squared radius (below 1 inside it)
float lampFalloff(float d2, float r2) {
  float att = 1.0 - sqrt(d2 / r2);
  return att * att;
}

float lampSize(Lamp lamp) {
  return clamp(lamp.radius * LAMP_SIZE_SHARE, LAMP_SIZE_MIN, LAMP_SIZE_MAX);
}

// a tile's bits in a layer: the walls (layer 0 now, 1 before) or the ceilings (2 now, 3 before: bit storey set where
// the storey's ceiling covers the tile)
uint lampTileBits(ivec2 t, int layer, int size) {
  if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, ivec2(size)))) {
    return 0u;
  }
  int i = t.y * size + t.x;
  return uint(texelFetch(lightData, LAMP_WALLS_BASE + layer * LAMP_WALL_LAYER_TEXELS + (i >> 2))[i & 3]);
}

// the drawn roofs' layer: a tile's lowest roof point, world y plus this; 0 where no drawn roof is (RoofMap.BIAS)
#define LAMP_ROOF_BIAS 65536.0

// the lowest point of the drawn roof on tile t (camera-relative y, down); none: far below everything
float lampRoofBottom(ivec2 t, int size) {
  if (any(lessThan(t, ivec2(0))) || any(greaterThanEqual(t, ivec2(size)))) {
    return 1e9;
  }
  int i = t.y * size + t.x;
  float v = texelFetch(lightData, LAMP_WALLS_BASE + 4 * LAMP_WALL_LAYER_TEXELS + (i >> 2))[i & 3];
  return v > 0.0 ? v - LAMP_ROOF_BIAS - cameraPos.y : 1e9;
}

uint lampWallBits(ivec2 t, int storey, int layer, int size) {
  return (lampTileBits(t, layer, size) >> uint(6 * storey)) & 63u;
}

// Whether the line between a and b (tiles, extended) strictly crosses a diagonal wall of tile t between its
// parameters t0 and t1
bool lampCrossesDiagonal(uint bits, ivec2 t, vec2 a, vec2 d, float t0, float t1) {
  if ((bits & (LAMP_WALL_SW_NE | LAMP_WALL_NW_SE)) == 0u || t1 <= t0) {
    return false;
  }
  vec2 u0 = a + d * t0 - vec2(t), u1 = a + d * t1 - vec2(t);
  if ((bits & LAMP_WALL_SW_NE) != 0u && (u0.y - u0.x) * (u1.y - u1.x) < 0.0) {
    return true;
  }
  return (bits & LAMP_WALL_NW_SE) != 0u && (u0.x + u0.y - 1.0) * (u1.x + u1.y - 1.0) < 0.0;
}

// the top of the walls of the lamp's storey (camera-relative y, down): its ceiling, or a storey's height over its
// floor where no ceiling is known; with no floor known either, no top
float lampWallTop(Lamp lamp) {
  if (lamp.floorY > 1e8) {
    return -1e9;
  }
  return lamp.floorY - lamp.ceilY > 120.0 && lamp.ceilY > -1e8 ? lamp.ceilY : lamp.floorY - LAMP_WALL_HEIGHT;
}

// Whether no drawn roof stops the line between camera-relative point from and the lamp: where it passes through a
// roofed tile's roof height it stops, walked tile by tile as lampWallsShareIn walks it, and only where it could (an end
// of it above the lowest roof in the lamp's reach)
bool lampRoofsOpen(vec3 from, Lamp lamp) {
  int tiles = int(lampWallsHeader.x);
  if (tiles == 0 || lamp.roofLow <= 0.0) {
    return true;
  }
  float low = lamp.roofLow - LAMP_ROOF_BIAS - cameraPos.y;
  if (from.y > low && lamp.pos.y > low) {
    return true;
  }
  float offset = lampWallsHeader.y;
  vec2 a = (from.xz + cameraPos.xz) / 128.0 + offset;
  vec2 b = (lamp.origin + cameraPos.xz) / 128.0 + offset;
  vec2 d = b - a;
  ivec2 t = ivec2(floor(a));
  ivec2 e = ivec2(floor(b));
  ivec2 s = ivec2(sign(d));
  vec2 delta = vec2(s.x != 0 ? 1.0 / abs(d.x) : 1e30, s.y != 0 ? 1.0 / abs(d.y) : 1e30);
  vec2 tMax = vec2(s.x > 0 ? (float(t.x + 1) - a.x) * delta.x : s.x < 0 ? (a.x - float(t.x)) * delta.x : 1e30,
    s.y > 0 ? (float(t.y + 1) - a.y) * delta.y : s.y < 0 ? (a.y - float(t.y)) * delta.y : 1e30);
  int steps = min(abs(e.x - t.x) + abs(e.y - t.y), LAMP_WALL_STEPS);
  float tEnter = 0.0;
  for (int i = 0; i <= LAMP_WALL_STEPS; ++i) {
    float tExit = min(min(tMax.x, tMax.y), 1.0);
    float roof = lampRoofBottom(t, tiles);
    if (roof < 1e8 && (mix(from.y, lamp.pos.y, tEnter) - roof) * (mix(from.y, lamp.pos.y, tExit) - roof) < 0.0) {
      return false;
    }
    if (i >= steps) {
      return true;
    }
    if (tMax.x < tMax.y) {
      t.x += s.x;
      tEnter = tMax.x;
      tMax.x += delta.x;
    } else {
      t.y += s.y;
      tEnter = tMax.y;
      tMax.y += delta.y;
    }
  }
  return true;
}

// a ray's lines in a lamp's reach map (LampReach): its first and second wall's
vec2 lampReachLines(int base, int ray) {
  vec4 t = texelFetch(lightData, base + (ray >> 1));
  return (ray & 1) == 0 ? t.xy : t.zw;
}

// Where along o + v * t (extended tiles) the line with id crosses it, as t; 2 where it doesn't, ahead of o
float lampReachCross(float id, vec2 o, vec2 v) {
  if (id < 0.5) {
    return 2.0;
  }
  int k = int(id + 0.5) - 1;
  int kind = k & 3;
  float c = float((k >> 2) - LAMP_REACH_C_OFF);
  float num = kind == 0 ? c - o.x : kind == 1 ? c - o.y : kind == 2 ? c - (o.x - o.y) : c - (o.x + o.y);
  float den = kind == 0 ? v.x : kind == 1 ? v.y : kind == 2 ? v.x - v.y : v.x + v.y;
  if (abs(den) < 1e-6) {
    return 2.0;
  }
  float t = num / den;
  return t > 0.0 ? t : 2.0;
}

// lampWallsShareIn for the walls now, read from the lamp's reach map: the walls of the two rays either side of the
// line from the lamp's origin to camera-relative point from, as lines. On a surface (toTop false) 0 where the first of
// either stands between them, else the roofs' say (lampRoofsOpen); in the air each wall of both rays the line crosses
// stops it below the walls' top, softly over the lamp's size, as the walk has it.
float lampReachShare(vec3 from, Lamp lamp, bool toTop) {
  vec2 o = (lamp.origin + cameraPos.xz) / 128.0 + lampWallsHeader.y;
  vec2 v = (from.xz - lamp.origin) / 128.0;
  if (dot(v, v) < 1e-8) {
    return 1.0;
  }
  float f = atan(v.y, v.x) * (float(LAMP_REACH_BINS) / 6.28318531) - 0.5;
  int r0 = int(floor(f));
  r0 = r0 < 0 ? r0 + LAMP_REACH_BINS : r0;
  int r1 = r0 + 1 < LAMP_REACH_BINS ? r0 + 1 : 0;
  vec2 a = lampReachLines(lamp.reach, r0), b = lampReachLines(lamp.reach, r1);
  if (!toTop) {
    if (min(lampReachCross(a.x, o, v), lampReachCross(b.x, o, v)) < 1.0) {
      return 0.0;
    }
    return lampRoofsOpen(from, lamp) ? 1.0 : 0.0;
  }
  float top = lampWallTop(lamp);
  float size = lampSize(lamp);
  float share = 1.0;
  vec4 ids = vec4(a, b);
  for (int k = 0; k < 4; ++k) {
    float id = ids[k];
    // each line once
    if (id < 0.5 || (k > 0 && id == ids[0]) || (k > 1 && id == ids[1]) || (k > 2 && id == ids[2])) {
      continue;
    }
    float t = lampReachCross(id, o, v);
    if (t < 1.0) {
      // the line's height there is between the lamp's and the point's; the wall stops it below its top
      share *= 1.0 - smoothstep(top - size, top + size, mix(lamp.pos.y, from.y, t));
    }
  }
  return share <= 0.004 ? 0.0 : share;
}

// How much of the lamp the walls of the lamp's storey, in a layer of the walls, let through to camera-relative point
// from: the line on the ground between them and the lamp's origin, walked tile by tile as WallMap.blocked does on the
// CPU; the ends' own tiles' edges don't count past the line's ends. For a surface (toTop false) a wall is as tall as
// the world: whatever its height, a lamp on one side lights nothing on the other, 0 or 1; and a drawn roof stops the
// line where it passes through the roof's height on a roofed tile. For the fog (toTop) a wall
// reaches its top (lampWallTop) and no higher, with a soft edge of the lamp's size over it: the light in the air
// above a wall spreads on, where a wall as tall as the world drew its plane up into the sky.
float lampWallsShareIn(vec3 from, Lamp lamp, int layer, bool toTop) {
  int tiles = int(layer == 0 ? lampWallsHeader.x : lampWallsHeader.z);
  if (tiles == 0) {
    return 1.0;
  }
  if (layer == 0 && lamp.reach > 0) {
    return lampReachShare(from, lamp, toTop);
  }
  float top = lampWallTop(lamp);
  float size = lampSize(lamp);
  float share = 1.0;
  float offset = layer == 0 ? lampWallsHeader.y : lampWallsHeader.w;
  int storey = lamp.walls;
  vec2 a = (from.xz + cameraPos.xz) / 128.0 + offset;
  vec2 b = (lamp.origin + cameraPos.xz) / 128.0 + offset;
  vec2 d = b - a;
  ivec2 t = ivec2(floor(a));
  ivec2 e = ivec2(floor(b));
  ivec2 s = ivec2(sign(d));
  vec2 delta = vec2(s.x != 0 ? 1.0 / abs(d.x) : 1e30, s.y != 0 ? 1.0 / abs(d.y) : 1e30);
  vec2 tMax = vec2(s.x > 0 ? (float(t.x + 1) - a.x) * delta.x : s.x < 0 ? (a.x - float(t.x)) * delta.x : 1e30,
    s.y > 0 ? (float(t.y + 1) - a.y) * delta.y : s.y < 0 ? (a.y - float(t.y)) * delta.y : 1e30);
  int steps = min(abs(e.x - t.x) + abs(e.y - t.y), LAMP_WALL_STEPS);
  float tEnter = 0.0;
  uint xSide = s.x > 0 ? LAMP_WALL_EAST : LAMP_WALL_WEST;
  uint ySide = s.y > 0 ? LAMP_WALL_NORTH : LAMP_WALL_SOUTH;
  // the roofs are the shown walls' tiles
  int roofTiles = int(lampWallsHeader.x);
  vec2 roofT = vec2(offset - lampWallsHeader.y);
  for (int i = 0; i <= LAMP_WALL_STEPS; ++i) {
    uint bits = lampWallBits(t, storey, layer, tiles);
    float tExit = min(min(tMax.x, tMax.y), 1.0);
    if (!toTop && roofTiles > 0) {
      float roof = lampRoofBottom(t - ivec2(roofT), roofTiles);
      // the line's height over the tile is between its heights where it enters and leaves it
      if (roof < 1e8 && (mix(from.y, lamp.pos.y, tEnter) - roof) * (mix(from.y, lamp.pos.y, tExit) - roof) < 0.0) {
        return 0.0;
      }
    }
    bool alongX = tMax.x < tMax.y;
    // a wall crossed, diagonal or on the edge being stepped over, and where along the line
    float tw = -1.0;
    if (lampCrossesDiagonal(bits, t, a, d, tEnter, tExit)) {
      tw = 0.5 * (tEnter + tExit);
    } else if (i < steps && (bits & (alongX ? xSide : ySide)) != 0u) {
      tw = alongX ? tMax.x : tMax.y;
    }
    if (tw >= 0.0) {
      if (!toTop) {
        return 0.0;
      }
      // the line's height there is between the point's and the lamp's; the wall stops it below its top
      float y = mix(from.y, lamp.pos.y, tw);
      share *= 1.0 - smoothstep(top - size, top + size, y);
      if (share <= 0.004) {
        return 0.0;
      }
    }
    if (i >= steps) {
      return share;
    }
    if (alongX) {
      t.x += s.x;
      tEnter = tMax.x;
      tMax.x += delta.x;
    } else {
      t.y += s.y;
      tEnter = tMax.y;
      tMax.y += delta.y;
    }
  }
  return share;
}

// Whether the storey's floors let the lamp's line to camera-relative point p through, in a layer of the ceilings:
// where it leaves the lamp's storey through its ceiling (or, going down, through its floor), the tile it goes through
// must be open
bool lampFloorsOpenIn(vec3 p, Lamp lamp, int layer) {
  int tiles = int(layer == 2 ? lampWallsHeader.x : lampWallsHeader.z);
  if (tiles == 0) {
    return true;
  }
  float through;
  int ceiling;
  if (p.y < lamp.ceilY - LAMP_FLOOR_MARGIN && lamp.pos.y > lamp.ceilY) {
    through = lamp.ceilY;
    ceiling = lamp.walls;
  } else if (p.y > lamp.floorY + LAMP_FLOOR_MARGIN && lamp.pos.y < lamp.floorY && lamp.walls > 0) {
    through = lamp.floorY;
    ceiling = lamp.walls - 1;
  } else {
    return true;
  }
  float offset = layer == 2 ? lampWallsHeader.y : lampWallsHeader.w;
  vec3 x = mix(lamp.pos, p, (through - lamp.pos.y) / (p.y - lamp.pos.y));
  ivec2 t = ivec2(floor((x.xz + cameraPos.xz) / 128.0 + offset));
  return ((lampTileBits(t, layer, tiles) >> uint(ceiling)) & 1u) == 0u;
}

#include "light/lamp_map.glsl"

// How much of the lamp the walls let reach camera-relative point p, the walls looked for from wallFrom (p, or p moved
// off its surface by LAMP_WALL_BIAS). On a surface: past the walls, as tall as the world, and the floors and ceilings, 1
// or 0. In the air (inAir: the fog's glow, raindrops): past the walls up to their tops, soft over the lamp's size, and no
// floor or ceiling: the light in the air spreads on smoothly where it no longer meets anything drawn, and a roof the
// client hides drew its plane into the glow (the user's "phantom roof"). While new walls ease in (a door), between
// the walls before and now.
float lampWallsOpen(Lamp lamp, vec3 p, vec3 wallFrom, bool inAir) {
  if (lamp.walls < 0) {
    return 1.0;
  }
  float now = inAir || lampFloorsOpenIn(p, lamp, 2) ? lampWallsShareIn(wallFrom, lamp, 0, inAir) : 0.0;
  if (lampWallsEase >= 1.0) {
    return now;
  }
  float before = inAir || lampFloorsOpenIn(p, lamp, 3) ? lampWallsShareIn(wallFrom, lamp, 1, inAir) : 0.0;
  return mix(before, now, lampWallsEase);
}

// A lamp the light map carries, once its map has eased in, stopped by the map alone, without the walls (lampWallsOpen):
// off. The map's soft bake lets a little light over a wall's line: fps1's gold rail on the floor behind a castle wall
// lit orange by the torch on the wall's other face (perf-audit, 2026-11-02). The walls stay, as the stricter of the two.
#define LAMP_MAP_ALONE 0

// How much of a lamp the light map carries in full reaches surface point p (wallFrom: p off its surface), given what
// the map lets through (vis): no more than its walls let through (lampWallsOpen: the line from its origin in its reach
// map, exact at the walls' lines, so a jamb's shadow on the floor has a clean edge, where the map's cells alone gave it
// steps; the floors; the roofs as drawn). Only the at most four lamps a point takes from the map look for their walls.
float lampMapLampOpen(Lamp lamp, float vis, vec3 p, vec3 wallFrom) {
  return vis <= 0.0 ? 0.0 : min(vis, lampWallsOpen(lamp, p, wallFrom, false));
}

// How much of the lamp reaches camera-relative point p: for a lamp the map carries in full, the map's (lampMapVisibility);
// else what the walls let through (lampWallsOpen), and for a lamp the light map is easing in on, no more than the map
// lets through: the stricter of the two.
float lampOpen(Lamp lamp, vec3 p, vec3 wallFrom, bool inAir) {
#if LAMP_MAP_ALONE
  if (lamp.mapId > 0 && lamp.mapEase >= 1.0) {
    return lampMapVisibility(lamp.mapId, p, wallFrom);
  }
#endif
  float open = lampWallsOpen(lamp, p, wallFrom, inAir);
  if (lamp.mapId > 0 && open > 0.0) {
    open = min(open, mix(1.0, lampMapVisibility(lamp.mapId, p, wallFrom), lamp.mapEase));
  }
  return open;
}

// The light the lamp gives camera-relative point p: its colour times its falloff, 0 outside its radius or behind a
// wall or floor (lampOpen, the walls looked for from wallFrom; a point in the air, inAir, as lampOpen has it). ld: the
// unit direction from p to the lamp, d the distance. Before any N.L: the caller says how the point takes it (the
// cosine for a surface).
vec3 lampLight(Lamp lamp, vec3 p, vec3 wallFrom, bool inAir, out vec3 ld, out float d) {
  vec3 l = lamp.pos - p;
  float d2 = dot(l, l);
  float r2 = lamp.radius * lamp.radius;
  d = sqrt(max(d2, 1.0));
  ld = l / d;
  if (d2 >= r2) {
    return vec3(0.0);
  }
  float open = lampOpen(lamp, p, wallFrom, inAir);
  return open > 0.0 ? lamp.color * (lampFalloff(d2, r2) * open) : vec3(0.0);
}

bool lampSphereReaches(vec4 sphere, vec3 p, vec3 n, bool backToo) {
  vec3 l = sphere.xyz - p;
  return dot(l, l) < sphere.w * sphere.w && (backToo || dot(n, l) > 0.0);
}

// Whether the lamp is within reach of a surface at p and in front of it by its smooth normal n, as the sun's light is
// by its normal (a face's own normal cut the light triangle by triangle across a round mesh); a surface turned away is
// lit only through, with backToo. No wrap past the horizon: it lit a roof's slope from the lanterns under its eave.
bool lampReaches(Lamp lamp, vec3 p, vec3 n, bool backToo) {
  return lampSphereReaches(vec4(lamp.pos, lamp.radius), p, n, backToo);
}
