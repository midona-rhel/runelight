// The snow's pattern, shared by the scene pass (frag.glsl, for scenery at rest) and the composite (weather_ground.glsl,
// for the ground): the water foam's cells (water_frag.glsl, Water.cells) at ground scale. A jittered grid of points,
// each the top of a round dome reaching SNOW_REACH of the grid spacing, the domes added up into soft rounded cells.
// Snow settles where the pattern is low, in the gaps between cells, and spreads over the crowns as the cover builds.

// the grid's spacing in world units, and how far a dome reaches, in spacings
#define SNOW_CELL 150.0
#define SNOW_REACH 0.85

uvec2 groundHash(vec2 cell) {
  uvec2 v = uvec2(ivec2(cell)) * 1664525u + 1013904223u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  v.x += v.y * 1664525u;
  v.y += v.x * 1664525u;
  v ^= v >> 16u;
  return v;
}

// x the height of the cells at world xz, about 0 in the gaps and 1 on a crown, yz its change per world unit
vec3 snowPattern(vec2 xz) {
  vec2 q = xz / SNOW_CELL;
  vec2 base = floor(q);
  float h = 0.0;
  vec2 slope = vec2(0.0);
  for (int k = 0; k < 9; ++k) {
    vec2 id = base + vec2(k % 3 - 1, k / 3 - 1);
    uvec2 r = groundHash(id);
    vec2 d = (q - (id + 0.2 + 0.6 * vec2(r.x & 65535u, r.x >> 16u) / 65535.0)) / SNOW_REACH;
    float t = 1.0 - dot(d, d);
    if (t > 0.0) {
      h += t * t * t;
      slope -= 6.0 * t * t * d / SNOW_REACH;
    }
  }
  return vec3(h, slope / SNOW_CELL);
}
