// The one lamp (light/LightClusters.java, light/LampWalls.java): what a point light gives a point. Everything lit by
// lamps (the composite, the transparent faces, the fog, the weather, the water) takes their light from here: the same
// falloff, colour, size and occlusion.
//
// A lamp lights a point unless a wall or a floor stands between them. Walls: those of the lamp's storey (WallMap: the
// edges the collision map says block sight, so doors open and close as the game has them, and diagonal walls), as tall
// as the storey, walked tile by tile along the line from the point to the lamp (lampWallsShareIn): a wall the line
// passes over doesn't stop it. Floors: where the line leaves the
// lamp's storey through its ceiling or its floor, the tile it goes through must be open there (a ladder's hole, a
// courtyard), else it stops. Nothing else stops a lamp. The lamp is a ball of lampSize: its light wraps a little past
// a surface's horizon, and a wall's end throws a soft edge as wide as the ball seen past it.
//
// When the walls change (a door) the new ones ease in over 0.3 s (LayerEase): the layers of the walls before are kept,
// and both are walked.
//
// Needs cluster_config and cameraPos (world position of the camera); positions are camera-relative.

// per lamp three texels: (position, radius), (colour times strength, the storey whose walls stop it or -1), (the
// point its lines start from: x, z, its storey's floor y, ceiling y); then the rooms' fill; then from LAMP_WALLS_BASE
// four layers of LAMP_WALL_LAYER_TEXELS: the walls now and before, the ceilings now and before (LampWalls.java)
uniform samplerBuffer lightData;
uniform usamplerBuffer clusterData;  // per cluster: a bit per lamp in view (light_clusters.glsl)
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
// only where it is open
#define LAMP_FLOOR_MARGIN 16.0
// how tall walls are where the storey above isn't known (world units): a storey's height
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
};

Lamp lampAt(int li) {
  vec4 a = texelFetch(lightData, li * 3);
  vec4 b = texelFetch(lightData, li * 3 + 1);
  vec4 c = texelFetch(lightData, li * 3 + 2);
  return Lamp(a.xyz, a.w, b.rgb, int(b.w), c.xy, c.z, c.w);
}

// 117 HD's falloff, from the squared distance and the squared radius (below 1 inside it)
float lampFalloff(float d2, float r2) {
  float att = 1.0 - sqrt(d2 / r2);
  return att * att;
}

float lampSize(Lamp lamp) {
  return clamp(lamp.radius * LAMP_SIZE_SHARE, LAMP_SIZE_MIN, LAMP_SIZE_MAX);
}

// the top of the walls of the lamp's storey (camera-relative y, down): its ceiling, or a storey's height over its
// floor where no ceiling is known; with no floor known either, no top
float lampWallTop(Lamp lamp) {
  if (lamp.floorY > 1e8) {
    return -1e9;
  }
  return lamp.floorY - lamp.ceilY > 120.0 && lamp.ceilY > -1e8 ? lamp.ceilY : lamp.floorY - LAMP_WALL_HEIGHT;
}

// How much of a ball lamp at distance d a surface takes at cosine ndl to its middle: the cosine, wrapped past the
// horizon by the ball's size seen from there, so the wall a torch hangs on is lit up to its top
float lampWrap(Lamp lamp, float ndl, float d) {
  float w = min(lampSize(lamp) / max(d, 1.0), 1.0);
  return max((ndl + w) / (1.0 + w), 0.0);
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

// The share of a ball lamp seen past one tile edge the line crosses at fraction f (0 to 1) along the edge, h the
// ball's half width there (tiles): the edge is a wall (wall) or open, and the edges on either side of it along the
// same line are walls or open (below, above). A wall's open end, or an opening's walled end, within h of the crossing
// gives a soft edge: half the ball at the corner itself.
float lampEdgeShare(bool wall, bool below, bool above, float f, float h) {
  if (wall) {
    // the distance into the wall from its nearer open end
    float e = min(below ? 2.0 : f, above ? 2.0 : 1.0 - f);
    return h > 0.0 ? smoothstep(0.0, 1.0, 0.5 - e / (2.0 * h)) : 0.0;
  }
  // the distance out of the opening from its nearer walled end
  float e = min(below ? f : 2.0, above ? 1.0 - f : 2.0);
  return h > 0.0 ? smoothstep(0.0, 1.0, 0.5 + e / (2.0 * h)) : 1.0;
}

// How much of the lamp the walls of a layer let through to camera-relative point from (0 to 1): the line on the ground
// from it to the lamp's origin, walked tile by tile as WallMap.blocked does on the CPU. The ends' own tiles' edges
// don't count past the line's ends.
float lampWallsShareIn(vec3 from, Lamp lamp, float size, int layer) {
  int tiles = int(layer == 0 ? lampWallsHeader.x : lampWallsHeader.z);
  if (tiles == 0) {
    return 1.0;
  }
  float offset = layer == 0 ? lampWallsHeader.y : lampWallsHeader.w;
  int storey = lamp.walls;
  // the line's height at a crossing is between the point's and the lamp's; walls reach up to top
  float top = lampWallTop(lamp);
  vec2 a = (from.xz + cameraPos.xz) / 128.0 + offset;
  vec2 b = (lamp.origin + cameraPos.xz) / 128.0 + offset;
  vec2 d = b - a;
  // the ball's half width in tiles at the lamp; at a crossing it is that times how far along the line it is
  float ball = size / 128.0;
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
  float share = 1.0;
  for (int i = 0; i <= LAMP_WALL_STEPS; ++i) {
    uint bits = lampWallBits(t, storey, layer, tiles);
    float tExit = min(min(tMax.x, tMax.y), 1.0);
    if (lampCrossesDiagonal(bits, t, a, d, tEnter, tExit)) {
      // stopped as far as the wall reaches up where the line crosses it
      float y = mix(from.y, lamp.pos.y, 0.5 * (tEnter + tExit));
      float hy = max(size * 0.5 * (tEnter + tExit), 4.0);
      share *= 1.0 - smoothstep(top - hy, top + hy, y);
      if (share <= 0.004) {
        return 0.0;
      }
    }
    if (i >= steps) {
      return share;
    }
    bool alongX = tMax.x < tMax.y;
    float tc = alongX ? tMax.x : tMax.y;
    bool wall = (bits & (alongX ? xSide : ySide)) != 0u;
    // where along the edge the line crosses it, and the ball's half width there
    float c = alongX ? a.y + d.y * tc : a.x + d.x * tc;
    float f = c - floor(c);
    float h = ball * tc;
    // how much of the ball the walls here would stop by their height: all of it below their top, none over it
    float y = mix(from.y, lamp.pos.y, tc);
    float hy = max(size * tc, 4.0);
    float low = smoothstep(top - hy, top + hy, y);
    if (wall && f > h && f < 1.0 - h) {
      share *= 1.0 - low;
    } else if (low > 0.0 && (wall || f < h || f > 1.0 - h)) {
      // near a corner: the edges either side of this one along the same line
      ivec2 side = alongX ? ivec2(0, 1) : ivec2(1, 0);
      uint edge = alongX ? xSide : ySide;
      bool below = (lampWallBits(t - side, storey, layer, tiles) & edge) != 0u;
      bool above = (lampWallBits(t + side, storey, layer, tiles) & edge) != 0u;
      share *= 1.0 - (1.0 - lampEdgeShare(wall, below, above, f, h)) * low;
    }
    if (share <= 0.004) {
      return 0.0;
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

// How much of the lamp reaches camera-relative point p past the walls and floors, the walls looked for from wallFrom
// (p, or p moved off its surface by LAMP_WALL_BIAS): 0 to 1, soft at walls' ends, and while new walls ease in, between
// the walls before and now
float lampOpen(Lamp lamp, vec3 p, vec3 wallFrom) {
  if (lamp.walls < 0) {
    return 1.0;
  }
  float size = lampSize(lamp);
  float now = lampFloorsOpenIn(p, lamp, 2) ? lampWallsShareIn(wallFrom, lamp, size, 0) : 0.0;
  if (lampWallsEase >= 1.0) {
    return now;
  }
  float before = lampFloorsOpenIn(p, lamp, 3) ? lampWallsShareIn(wallFrom, lamp, size, 1) : 0.0;
  return mix(before, now, lampWallsEase);
}

// The light the lamp gives camera-relative point p: its colour times its falloff, 0 outside its radius or behind a
// wall or floor (lampOpen, the walls looked for from wallFrom). ld: the unit direction from p to the lamp, d the
// distance. Before any N.L: the caller says how the point takes it (lampWrap for a surface).
vec3 lampLight(Lamp lamp, vec3 p, vec3 wallFrom, out vec3 ld, out float d) {
  vec3 l = lamp.pos - p;
  float d2 = dot(l, l);
  float r2 = lamp.radius * lamp.radius;
  d = sqrt(max(d2, 1.0));
  ld = l / d;
  if (d2 >= r2) {
    return vec3(0.0);
  }
  float open = lampOpen(lamp, p, wallFrom);
  return open > 0.0 ? lamp.color * (lampFalloff(d2, r2) * open) : vec3(0.0);
}

// Whether the lamp is within reach of a surface at p and in front of it: its smooth normal n (wrapped by the lamp's
// size) and its face's normal faceN (a face turned away from the lamp is lit only through, with backToo)
bool lampReaches(Lamp lamp, vec3 p, vec3 n, vec3 faceN, bool backToo) {
  vec3 l = lamp.pos - p;
  float d2 = dot(l, l);
  return d2 < lamp.radius * lamp.radius
    && (backToo || dot(n, l) > -lampSize(lamp) && dot(faceN, l) > -LAMP_WALL_BIAS);
}
