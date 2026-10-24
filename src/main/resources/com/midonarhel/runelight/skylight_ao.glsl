// Skylight, the AO pass's half: how much of the sky a pixel sees, from the sky visibility ray traced through the
// scene's voxels at the centres of the tiles (SkyGrid.java), two heights above each storey's floor and one above the
// scenery. Walls stand on tile edges, so a tile's centre is on one side of every wall: a pixel blends the (up to) four
// nearest centres, leaving out the ones a wall separates it from, and weighs the directions by its normal's cosine
// (first order spherical harmonics): under the open sky it is 1 whichever way it faces, a wall under eaves sees the sky
// sideways, the ground under them doesn't see it overhead. Written to the shadow target's alpha at half resolution;
// the composite upsamples it with the AO and scales the sky and ambient light by it (skylight_lighting.glsl).
// Needs frame.glsl.

uniform int skylightOn;
// RGBA16F, see SkyGrid.java: one texel per probe, a tile's two halves side by side (a diagonal wall splits it)
uniform sampler3D skyGrid;
uniform vec3 skyGridCamera;  // the camera in the grid's coordinates: tile 0 at 0
// Room-fill corner values (SkyField.java): rg/ba light and cover per half; g also holds 2 * diagonal kind.
// Texture coordinates are
// camera-relative world xz * skyFieldMap.xy + skyFieldMap.zw
uniform sampler2D skyField;
uniform int skyFieldOn;
uniform vec4 skyFieldMap;

// as SkyGrid.java
#define SKY_GRID_TILES 104.0
#define SKY_GRID_TILE 128.0
#define SKY_GRID_LINKS 1
#define SKY_GRID_PROBES 5
#define SKY_GRID_SURFACE 6
#define SKY_GRID_LOW 48.0
#define SKY_GRID_HIGH 176.0
// A point is read a little off its surface, out along the normal: a wall's outer face stands within a few units of its
// tile's edge, which could be either side of it, and 32 puts it in the tile it faces whichever; the inner face is far
// from the edge its normal points to. Up and down only enough to pick the side of a floor or a roof.
#define SKY_OFFSET_SIDE 32.0
#define SKY_OFFSET_UP 8.0
// A surface facing up is at the top of the scenery over its tile when its point lies no further than SKY_TOP_NEAR below
// the SURFACE probe's height (that is a voxel's worth: the probe is above the top voxel, the surface somewhere in it),
// and not at all from SKY_TOP_FAR.
#define SKY_TOP_NEAR 64.0
#define SKY_TOP_FAR 96.0
// The SURFACE probes of neighbouring tiles blend only while their scenery is about as high: not a roof's with the
// ground's beside it.
#define SKY_TOP_STEP_NEAR 48.0
#define SKY_TOP_STEP_FAR 96.0

// The probes of a slice around a point, blended by weights: its own tile, the neighbour along x, the one along z and
// the diagonal one, in the directions of toward; halves is the half of each tile to read. A weight of 0 reads nothing.
vec4 skyGridBlend(int slice, ivec2 tile, ivec2 toward, ivec4 halves, vec4 weights) {
  vec4 sh = vec4(0.0);
  float total = 0.0;
  for (int k = 0; k < 4; ++k) {
    if (weights[k] <= 0.0) continue;
    ivec2 at = tile + ivec2(k & 1, k >> 1) * toward;
    sh += weights[k] * texelFetch(skyGrid, ivec3(2 * at.x + halves[k], at.y, slice), 0);
    total += weights[k];
  }
  return sh / total;
}

// What a surface facing n sees of the sky, from the harmonics (a, b) of a probe's visibility: the cosine weighted share
// of the directions that get out, a + 2 b.n over the same for the open sky, 1 + the upward share of n. A normal facing
// down sees what the horizon does.
float skyGridShade(vec4 sh, vec3 n) {
  vec3 m = vec3(n.x, min(n.y, 0.0), n.z);
  return clamp((sh.x + 2.0 * dot(m, sh.yzw)) / (1.0 - m.y), 0.0, 1.0);
}

// w: the point in grid coordinates, n its surface's normal
float skyGridVisibility(vec3 w, vec3 n) {
  vec2 t = w.xz / SKY_GRID_TILE;
  if (any(lessThan(t, vec2(0.0))) || any(greaterThanEqual(t, vec2(SKY_GRID_TILES)))) return 1.0;
  ivec2 tile = ivec2(t);
  vec2 f = t - vec2(tile);
  // the three neighbours that share the quadrant of the tile the point is in, and the bilinear weights of the four
  ivec2 toward = ivec2(f.x < 0.5 ? -1 : 1, f.y < 0.5 ? -1 : 1);
  vec2 d = abs(f - 0.5);
  vec4 bilinear = vec4((1.0 - d.x) * (1.0 - d.y), d.x * (1.0 - d.y), (1.0 - d.x) * d.y, d.x * d.y);
  // the floors' heights of the tile, and the SURFACE probe's
  vec4 heights = texelFetch(skyGrid, ivec3(2 * tile.x, tile.y, 0), 0);

  float top = smoothstep(0.0, 0.5, -n.y) * (1.0 - smoothstep(SKY_TOP_NEAR, SKY_TOP_FAR, w.y - heights.w));
  vec4 sh = vec4(0.0);
  if (top > 0.0) {
    vec4 weights = bilinear;
    for (int k = 1; k < 4; ++k) {
      ivec2 at = tile + ivec2(k & 1, k >> 1) * toward;
      if (any(lessThan(at, ivec2(0))) || any(greaterThanEqual(at, ivec2(SKY_GRID_TILES)))) {
        weights[k] = 0.0;
        continue;
      }
      float apart = abs(texelFetch(skyGrid, ivec3(2 * at.x, at.y, 0), 0).w - heights.w);
      weights[k] *= 1.0 - smoothstep(SKY_TOP_STEP_NEAR, SKY_TOP_STEP_FAR, apart);
    }
    sh += top * skyGridBlend(SKY_GRID_PROBES + SKY_GRID_SURFACE, tile, toward, ivec4(0), weights);
  }
  if (top < 1.0) {
    // the storey: the highest floor the point is above. Its probes are not blended across a floor.
    int storey = w.y < heights.z ? 2 : w.y < heights.y ? 1 : 0;
    // what the point may blend with in this storey, and the half of a tile a diagonal wall leaves it in
    int quadrant = (toward.x > 0 ? 1 : 0) + (toward.y > 0 ? 2 : 0);
    vec4 link = texelFetch(skyGrid, ivec3(2 * tile.x, tile.y, SKY_GRID_LINKS + quadrant), 0);
    int kind = int(link.w + 0.5) / (storey == 0 ? 1 : storey == 1 ? 3 : 9) % 3;
    int part = kind == 0 ? 0 : kind == 1 ? (f.y > f.x ? 0 : 1) : (f.x + f.y > 1.0 ? 0 : 1);
    if (part == 1) link = texelFetch(skyGrid, ivec3(2 * tile.x + 1, tile.y, SKY_GRID_LINKS + quadrant), 0);
    int bits = int(link[storey] + 0.5);
    vec4 weights = bilinear * vec4(1.0, float(bits & 1), float((bits >> 1) & 1), float((bits >> 2) & 1));
    ivec4 halves = ivec4(part, (bits >> 3) & 1, (bits >> 4) & 1, (bits >> 5) & 1);
    // between the storey's low and high probes; beyond them the nearest one
    float high = clamp((heights[storey] - SKY_GRID_LOW - w.y) / (SKY_GRID_HIGH - SKY_GRID_LOW), 0.0, 1.0);
    int slice = SKY_GRID_PROBES + 2 * storey;
    vec4 inStorey = vec4(0.0);
    if (high < 1.0) inStorey += (1.0 - high) * skyGridBlend(slice, tile, toward, halves, weights);
    if (high > 0.0) inStorey += high * skyGridBlend(slice + 1, tile, toward, halves, weights);
    sh += (1.0 - top) * inStorey;
  }
  return skyGridShade(sh, n);
}

// Reconstruct the field from shared corners across open edges. Each tile owns copies of its corners, so
// sampling never pulls a texel through a wall. Diagonal halves use separate channels and triangle weights.
vec2 skyFieldVisibility(vec3 p) {
  ivec2 dims = textureSize(skyField, 0);
  vec2 t = clamp((p.xz * skyFieldMap.xy + skyFieldMap.zw) * (vec2(dims) * 0.5),
    vec2(0.0), vec2(dims) * 0.5 - 0.0001);
  ivec2 base = ivec2(floor(t)) * 2;
  vec2 f = fract(t);
  vec4 sw = texelFetch(skyField, base, 0);
  vec4 se = texelFetch(skyField, base + ivec2(1, 0), 0);
  vec4 nw = texelFetch(skyField, base + ivec2(0, 1), 0);
  vec4 ne = texelFetch(skyField, base + ivec2(1, 1), 0);
  int diagonal = int(floor(sw.g * 0.5));
  float code = 2.0 * float(diagonal);
  sw.g -= code; se.g -= code; nw.g -= code; ne.g -= code;
  vec2 field = mix(mix(sw.rg, se.rg, f.x), mix(nw.rg, ne.rg, f.x), f.y);
  if (diagonal == 1) {
    field = f.y > f.x
      ? sw.rg * (1.0 - f.y) + nw.rg * (f.y - f.x) + ne.rg * f.x
      : sw.ba * (1.0 - f.x) + se.ba * (f.x - f.y) + ne.ba * f.y;
  } else if (diagonal == 2) {
    field = f.x + f.y > 1.0
      ? nw.rg * (1.0 - f.x) + ne.rg * (f.x + f.y - 1.0) + se.rg * (1.0 - f.y)
      : sw.ba * (1.0 - f.x - f.y) + se.ba * f.x + nw.ba * f.y;
  }
  return field;
}

// 1 under the open sky. Under cover (the reconstructed field's green channel) the light flowing in through
// the openings counts too: it stands for the light that gets deep into a room by bouncing off the walls, which the
// traced sky doesn't see; the trace stands for what the sky lights directly (a pavilion's open sides, a window), so
// neither darkens what the other lights.
float skylightVisibility(vec3 p, vec3 n) {
  if (skylightOn == 0) {
    return 1.0;
  }
  vec3 o = p + n * vec3(SKY_OFFSET_SIDE, SKY_OFFSET_UP, SKY_OFFSET_SIDE);
  vec2 field = skyFieldOn == 1 ? skyFieldVisibility(o) : vec2(0.0);
  float v = skyGridVisibility(o + skyGridCamera, n);
  if (skyFieldOn == 1) {
    v = mix(v, max(v, field.r), field.g);
  }
  return v;
}
