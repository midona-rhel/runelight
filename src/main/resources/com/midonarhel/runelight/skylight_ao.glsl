// Skylight, the AO pass's half: how much of the sky a pixel sees, from the sky visibility ray traced through the
// scene's voxels at the tiles' corners (SkyGrid.java), a few heights above each floor, toward up and the four sides.
// A pixel reads its own tile's four corners only, so nothing blends through a wall, and weighs the directions by its
// normal (an ambient cube): under the open sky it is 1 whichever way it faces, a wall under eaves sees the sky
// sideways, the ground under them doesn't see it overhead. Written to the shadow target's alpha at half resolution;
// the composite upsamples it with the AO and scales the sky and ambient light by it (skylight_lighting.glsl).
// Needs frame.glsl.

uniform int skylightOn;
// RGBA16F: four horizontal parts, two per triangle half (up/+x/-x/+z, then -z).
// Slice 0: floor heights/diagonals, floor flags, room floor heights, protected room bits.
// The final slice samples above captured geometry. Visibility slices store sample x/y/z in the -z part's g/b/a.
uniform sampler3D skyGrid;
uniform float skyGridScale;
uniform vec3 skyGridCamera;  // the camera in the grid's coordinates: tile 0 at 0
// Room-fill corner values (SkyField.java): rg/ba light and cover per half; g also holds 2 * diagonal kind.
// Texture coordinates are
// camera-relative world xz * skyFieldMap.xy + skyFieldMap.zw
uniform sampler2D skyField;
uniform int skyFieldOn;
uniform vec4 skyFieldMap;

// as SkyGrid.java
#define SKY_GRID_TILES 104.0
#define SKY_GRID_W (8.0 * SKY_GRID_TILES)
#define SKY_GRID_H (2.0 * SKY_GRID_TILES)
#define SKY_GRID_D 11.0
#define SKY_GRID_LOW 40.0
#define SKY_GRID_STEP 72.0
// Nudge off the surface to choose its facing side at a tile boundary. A larger offset can cross
// a nearby wall and read outdoor visibility/fill instead of this surface's room.
#define SKY_NORMAL_OFFSET 1.0
// A wall's outer face stands at or just short of its tile's edge, so which tile a point of it falls in depends on how
// far its brick pattern is inset: the mortar read the room behind the wall and the bricks the outdoors. A face within
// SKY_EDGE_REACH world units of the tile edge it looks across reads the tile beyond that edge, the one it faces.
#define SKY_EDGE_REACH 32.0
// how square to an axis a face must be (its normal's component along it) to count as standing on a tile edge
#define SKY_SQUARE 0.9
// Faces that aren't walls and face up or down (the ground at a wall's foot, the ledges of an inset brick pattern) only
// ever stray past a wall's outer face by a few units: within SKY_FOOT_REACH of the edge of a room tile, where the tile
// across that edge is out of doors, they are out of doors too. Facing down they read where they are (an eave's
// underside sees no sky). Kept to the width of an inset: where a wall stands in the tile outside, the room's own
// floor runs up to the edge, and a wider reach lights a line along the wall's foot inside.
#define SKY_FOOT_REACH 3.0
// how much of the traced darkening is kept out of doors
#define SKY_OUTDOOR_STRENGTH 0.35
// what a corner behind the surface still counts for, against 1 for one well in front: only enough that a pixel with
// every corner behind it still has a value
#define SKY_BEHIND 0.02
// An upward face at the top of the scenery over its tile (a roof, a wall's top, a flat roof inside its parapet: within
// SKY_TOP_SLACK of the highest traced point, fading out over as much again) is out of doors, whatever storey's room
// lies under it. A storey's ceiling is higher than twice this over its floor.
#define SKY_TOP_SLACK 96.0
// world units above a storey's floor over which a face goes from the room to the roof over it (a storey's walls are
// about 240 high)
#define SKY_ROOF_ABOVE vec2(190.0, 240.0)

// Read one height without crossing a tile's wall. Diagonal halves have their own three actual corners. A corner counts
// by how far its traced point lies in front of the surface (w, grid coordinates, along n): one behind the surface or
// pressed into it (the far side of a post, the foot of a wall) sees what the surface can't, and drops out while any
// other corner is in front.
float skyGridLevel(sampler3D grid, float scale, vec2 tile, vec2 f, int level, int diagonal, vec3 n, vec3 w) {
  bool north = diagonal == 1 ? f.y > f.x : f.x + f.y > 1.0;
  float halfOffset = diagonal != 0 && !north ? 0.5 : 0.0;
  vec2 c = 2.0 * tile + 0.5;
  vec4 a = vec4(0.0);
  float minusZ = 0.0;
  float validWeight = 0.0;
  float z = (1.5 + float(level)) / SKY_GRID_D;
  {
    // Weights in SW, SE, NW, NE order; the opposite corner of each triangle has zero weight.
    vec4 weights = diagonal == 0 ? vec4((1.0-f.x)*(1.0-f.y), f.x*(1.0-f.y), (1.0-f.x)*f.y, f.x*f.y) : diagonal == 1
      ? (north ? vec4(1.0 - f.y, 0.0, f.y - f.x, f.x) : vec4(1.0 - f.x, f.x - f.y, 0.0, f.y))
      : (north ? vec4(0.0, 1.0 - f.y, 1.0 - f.x, f.x + f.y - 1.0) : vec4(1.0 - f.x - f.y, f.x, f.y, 0.0));
    for (int k = 0; k < 4; ++k) {
      if (weights[k] <= 0.0) continue;
      vec2 uv = (c + vec2(k & 1, k >> 1)) / (vec2(SKY_GRID_W, SKY_GRID_H)*scale) + vec2(halfOffset, 0.0);
      vec4 sampleValue = textureLod(grid, vec3(uv, z), 0.0);
      if (sampleValue.x < 0.0) continue;
      vec4 b = textureLod(grid, vec3(uv + vec2(0.25, 0.0), z), 0.0);
      vec3 samplePosition = vec3((tile.x + float(k & 1)) * (128.0 / scale) + b.g, b.b,
        (tile.y + float(k >> 1)) * (128.0 / scale) + b.a);
      float weight = weights[k] * (SKY_BEHIND + smoothstep(-16.0, 32.0, dot(samplePosition - w, n)));
      validWeight += weight;
      a += sampleValue * weight;
      minusZ += b.r * weight;
    }
  }
  if (validWeight <= 0.00001) return -1.0;
  a /= validWeight;
  minusZ /= validWeight;
  vec3 nn = n * n;
  float down = (a.y + a.z + a.w + minusZ) * 0.25;
  return nn.y * (n.y < 0.0 ? a.x : down) + nn.x * (n.x > 0.0 ? a.y : a.z) + nn.z * (n.z > 0.0 ? a.w : minusZ);
}

// Reject outdoor probes behind the rendered surface instead of blending through the roof/canopy.
// The tile and triangle selection is identical to the wall-safe indoor reconstruction.
vec2 skySurfaceLevel(sampler3D grid, float scale, vec2 tile, vec2 f, int level, int diagonal, vec3 w, vec3 n) {
  bool north = diagonal == 1 ? f.y > f.x : f.x + f.y > 1.0;
  float halfOffset = diagonal != 0 && !north ? 0.5 : 0.0;
  vec4 weights = vec4((1.0-f.x)*(1.0-f.y), f.x*(1.0-f.y), (1.0-f.x)*f.y, f.x*f.y);
  if (diagonal == 1) weights = north ? vec4(1.0-f.y,0.0,f.y-f.x,f.x) : vec4(1.0-f.x,f.x-f.y,0.0,f.y);
  if (diagonal == 2) weights = north ? vec4(0.0,1.0-f.y,1.0-f.x,f.x+f.y-1.0) : vec4(1.0-f.x-f.y,f.x,f.y,0.0);
  vec2 sum = vec2(0.0);
  for (int k=0; k<4; ++k) {
    if (weights[k] <= 0.0) continue;
    vec2 corner = vec2(k & 1, k >> 1);
    vec2 uv = (2.0*tile+0.5+corner)/(vec2(SKY_GRID_W,SKY_GRID_H)*scale)+vec2(halfOffset,0.0);
    float z = (1.5+float(level))/SKY_GRID_D;
    vec4 a = textureLod(grid,vec3(uv,z),0.0);
    if (a.x < 0.0) continue;
    vec4 b = textureLod(grid,vec3(uv+vec2(0.25,0.0),z),0.0);
    vec3 samplePosition = vec3((tile.x+corner.x)*(128.0/scale)+b.g,b.b,(tile.y+corner.y)*(128.0/scale)+b.a);
    float weight = weights[k]*smoothstep(-16.0,32.0,dot(samplePosition-w,n));
    // an outdoor face reads what is at its height or above it: a probe well below it (at the foot of the wall under
    // a roof's eave, down in the alley beside it) sees a sky the face doesn't, and the one above the scenery stands
    // for the open sky over it (y down: a larger y is lower)
    weight *= 1.0 - smoothstep(48.0, 128.0, samplePosition.y - w.y);
    vec3 nn=n*n;
    float value=nn.y*(n.y<0.0?a.x:(a.y+a.z+a.w+b.r)*0.25)
      +nn.x*(n.x>0.0?a.y:a.z)+nn.z*(n.z>0.0?a.w:b.r);
    sum+=vec2(value,1.0)*weight;
  }
  return sum;
}

// how far out of doors the last skyGridVisibility found its face (a roof, a terrace, open ground: 1)
float skyExterior;

float skyGridVisibility(sampler3D grid, float scale, vec3 w, vec3 n) {
  skyExterior = 1.0;
  vec2 t = w.xz * scale / 128.0;
  if (any(lessThan(t, vec2(0.0))) || any(greaterThanEqual(t, vec2(SKY_GRID_TILES*scale)))) return 1.0;
  vec2 tile = floor(t), f = fract(t), c = 2.0 * tile + 0.5;
  vec2 uv = (c + f) / (vec2(SKY_GRID_W, SKY_GRID_H)*scale);
  vec4 meta = textureLod(grid, vec3(uv, 0.5 / SKY_GRID_D), 0.0);
  int floorMask = int(textureLod(grid, vec3(uv + vec2(0.25, 0.0), 0.5 / SKY_GRID_D), 0.0).r + 0.5);
  // Every height exists outdoors too. Only an actual scene floor separates the samples above and below it.
  int first = 0, last = 8;
  for (int floor = 1; floor < 3; ++floor) {
    if ((floorMask & (1 << floor)) == 0) continue;
    if (w.y < meta[floor]) first = floor * 3;
    else last = min(last, floor * 3 - 1);
  }
  int lo = first, hi = first;
  float loY = meta[first / 3] - SKY_GRID_LOW;
  float hiY = loY;
  for (int k = first + 1; k <= last; ++k) {
    if (w.y >= hiY) break;
    lo = hi; loY = hiY;
    hi = k;
    hiY = meta[k / 3] - SKY_GRID_LOW - float(k % 3) * SKY_GRID_STEP;
  }
  float blend = lo == hi ? 0.0 : clamp((loY - w.y) / max(loY - hiY, 1.0), 0.0, 1.0);
  int code = int(meta.w + 0.5);
  int loDiagonal = code / (lo / 3 == 0 ? 1 : lo / 3 == 1 ? 3 : 9) % 3;
  int hiDiagonal = code / (hi / 3 == 0 ? 1 : hi / 3 == 1 ? 3 : 9) % 3;
  // The extra sample is above captured geometry. Enclosed/roofed tiles on this storey
  // retain the original reconstruction, including when their roof is hidden by the renderer.
  vec4 roomFloors = textureLod(grid, vec3(uv + vec2(0.5, 0.0), 0.5 / SKY_GRID_D), 0.0);
  int roomMask = int(textureLod(grid, vec3(uv + vec2(0.75, 0.0), 0.5 / SKY_GRID_D), 0.0).r + 0.5);
  int roomPlane = 0;
  // Upward-facing roof/floor surfaces belong to the storey at their height; the one-unit
  // normal offset already selects the exposed side. Do not move a roof lip into the room below.
  for (int k = 1; k < 4; ++k) if (w.y <= roomFloors[k]) roomPlane = k;
  float exterior = (roomMask & (1 << roomPlane)) == 0 ? 1.0 : 0.0;
  // A face turned up, higher above its storey's floor than a storey's walls stand, is on the roof over that storey:
  // out of doors, however low it slopes down. (A wall's upper part indoors faces sideways and stays in.)
  if (exterior < 1.0 && n.y < -0.3) {
    exterior = smoothstep(SKY_ROOF_ABOVE.x, SKY_ROOF_ABOVE.y, roomFloors[roomPlane] - w.y);
  }
  if (exterior < 1.0 && n.y < -0.3) {
    // an upward face near the top of the scenery is out of doors, whatever room lies under it (a roof, a wall's
    // top). The top is the highest traced point over the tile, blended between its corners like the values, and
    // the face goes out of doors gradually over SKY_TOP_SLACK below it, so a sloping roof has no step.
    float surfaceZ = (1.5 + 9.0) / SKY_GRID_D;
    vec4 cornerWeights = vec4((1.0 - f.x) * (1.0 - f.y), f.x * (1.0 - f.y), (1.0 - f.x) * f.y, f.x * f.y);
    float top = 0.0, topWeight = 0.0;
    for (int k = 0; k < 4; ++k) {
      vec2 cuv = (2.0 * tile + 0.5 + vec2(k & 1, k >> 1)) / (vec2(SKY_GRID_W, SKY_GRID_H) * scale);
      if (textureLod(grid, vec3(cuv, surfaceZ), 0.0).x >= 0.0) {
        top += cornerWeights[k] * textureLod(grid, vec3(cuv + vec2(0.25, 0.0), surfaceZ), 0.0).b;
        topWeight += cornerWeights[k];
      }
    }
    if (topWeight > 0.0001) {
      top /= topWeight;
      exterior = max(exterior, (1.0 - smoothstep(top + SKY_TOP_SLACK, top + 2.0 * SKY_TOP_SLACK, w.y))
        * smoothstep(0.3, 0.7, -n.y));
    }
  }
  float lower = skyGridLevel(grid, scale, tile, f, lo, loDiagonal, n, w);
  float original;
  {
    float upper = skyGridLevel(grid, scale, tile, f, hi, hiDiagonal, n, w);
    original = lower < 0.0 ? upper : upper < 0.0 ? lower : mix(lower, upper, blend);
    if (original < 0.0) {
      float bestDistance = 1e30;
      for (int k = first; k <= last; ++k) {
        int diagonal = code / (k/3 == 0 ? 1 : k/3 == 1 ? 3 : 9) % 3;
        float value = skyGridLevel(grid, scale, tile, f, k, diagonal, n, w);
        float distance = abs(w.y-(meta[k/3]-SKY_GRID_LOW-float(k%3)*SKY_GRID_STEP));
        if (value >= 0.0 && distance < bestDistance) { original=value; bestDistance=distance; }
      }
      if (original < 0.0) original = max(0.0, skyGridLevel(grid, scale, tile, f, 9, 0, n, w));
    }
  }
  skyExterior = exterior;
  if (exterior > 0.0) {
    vec2 a = skySurfaceLevel(grid, scale, tile, f, lo, loDiagonal, w, n);
    vec2 b = skySurfaceLevel(grid, scale, tile, f, hi, hiDiagonal, w, n);
    vec2 total = mix(a, b, blend);
    float surfaceWeight = (1.0 - total.y);
    total += skySurfaceLevel(grid, scale, tile, f, 9, 0, w, n) * surfaceWeight;
    if (total.y > 0.0001) return mix(original, total.x / total.y, smoothstep(0.0, 0.5, -n.y) * exterior);
  }
  return original;
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

// Whether grid point w (grid coordinates) is in a room on the storey at its height
bool skyInRoom(vec3 w, float scale) {
  vec2 t = w.xz * scale / 128.0;
  if (any(lessThan(t, vec2(0.0))) || any(greaterThanEqual(t, vec2(SKY_GRID_TILES * scale)))) return false;
  vec2 uv = (2.0 * floor(t) + 0.5 + fract(t)) / (vec2(SKY_GRID_W, SKY_GRID_H) * scale);
  vec4 roomFloors = textureLod(skyGrid, vec3(uv + vec2(0.5, 0.0), 0.5 / SKY_GRID_D), 0.0);
  int roomMask = int(textureLod(skyGrid, vec3(uv + vec2(0.75, 0.0), 0.5 / SKY_GRID_D), 0.0).r + 0.5);
  int roomPlane = 0;
  for (int k = 1; k < 4; ++k) if (w.y <= roomFloors[k]) roomPlane = k;
  return (roomMask & (1 << roomPlane)) != 0;
}

// 1 under the open sky. Under cover (the reconstructed field's green channel) the light flowing in through
// the openings counts too: it stands for the light that gets deep into a room by bouncing off the walls, which the
// traced sky doesn't see; the trace stands for what the sky lights directly (a pavilion's open sides, a window), so
// neither darkens what the other lights.
float skylightVisibility(vec3 p, vec3 n) {
  if (skylightOn == 0) {
    return 1.0;
  }
  vec3 o = p + n * SKY_NORMAL_OFFSET;
  float scale = max(skyGridScale, 1.0);
  // walls stand on the tiles' edges, whatever the grid's resolution
  float tileSize = 128.0;
  vec3 g = o + skyGridCamera;
  bool wall = abs(n.x) > 0.5 || abs(n.z) > 0.5;
  for (int axis = 0; axis < 2; ++axis) {
    float d = axis == 0 ? n.x : n.z;
    float x = axis == 0 ? g.x : g.z;
    float f = fract(x / tileSize) * tileSize;
    float step = 0.0;
    // only a face square to the axis stands on a tile edge: a diagonal wall's faces cross the middle of their tiles,
    // and pushing them across an edge read the next tile in stripes along the wall
    if (abs(d) > SKY_SQUARE && n.y < 0.1) {
      // world units to the edge the face looks across
      float toEdge = d > 0.0 ? tileSize - f : f;
      if (toEdge < SKY_EDGE_REACH) {
        step = (toEdge + 1.0) * sign(d);
      }
    } else if (!wall && n.y < 0.1 && (f < SKY_FOOT_REACH || tileSize - f < SKY_FOOT_REACH)) {
      step = f < SKY_FOOT_REACH ? -(f + 1.0) : tileSize - f + 1.0;
      vec3 across = g;
      if (axis == 0) {
        across.x += step;
      } else {
        across.z += step;
      }
      if (!skyInRoom(g, scale) || skyInRoom(across, scale)) {
        step = 0.0;
      }
    }
    if (axis == 0) {
      o.x += step;
    } else {
      o.z += step;
    }
  }
  vec2 field = skyFieldOn == 1 ? skyFieldVisibility(o) : vec2(0.0);
  float v = skyGridVisibility(skyGrid, scale, o + skyGridCamera, n);
  // out of doors the trace only shades gently: the ambient occlusion draws the contact where things meet, and the
  // grid is too coarse to draw more than a soft darkening near buildings and trees. Under cover (the field's green)
  // it keeps its full range.
  // The field's cover is the map's under-roof flags, which a roof itself is flagged with too: a face the grid finds
  // out of doors (a roof top, a terrace) is not under cover, whatever the flags say.
  float cover = skyFieldOn == 1 ? field.g * (1.0 - skyExterior) : 0.0;
  float outdoors = 1.0 - cover;
  v = mix(v, 1.0 - (1.0 - v) * SKY_OUTDOOR_STRENGTH, outdoors);
  if (skyFieldOn == 1) {
    v = mix(v, max(v, field.r), cover);
  }
  return v;
}
