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

// Read one height without crossing a tile's wall. Diagonal halves have their own three actual corners.
float skyGridLevel(sampler3D grid, float scale, vec2 tile, vec2 f, int level, int diagonal, vec3 n) {
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
      validWeight += weights[k];
      a += sampleValue * weights[k];
      minusZ += textureLod(grid, vec3(uv + vec2(0.25, 0.0), z), 0.0).r * weights[k];
    }
  }
    {
      if (validWeight <= 0.00001) return -1.0;
      a /= validWeight; minusZ /= validWeight;
    }
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
    if (level == 9)
      weight *= 1.0-smoothstep(80.0,160.0,abs(w.y-samplePosition.y));
    vec3 nn=n*n;
    float value=nn.y*(n.y<0.0?a.x:(a.y+a.z+a.w+b.r)*0.25)
      +nn.x*(n.x>0.0?a.y:a.z)+nn.z*(n.z>0.0?a.w:b.r);
    sum+=vec2(value,1.0)*weight;
  }
  return sum;
}

float skyGridVisibility(sampler3D grid, float scale, vec3 w, vec3 n) {
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
  bool exterior = (roomMask & (1 << roomPlane)) == 0;
  float lower = skyGridLevel(grid, scale, tile, f, lo, loDiagonal, n);
  float original;
  {
    float upper = skyGridLevel(grid, scale, tile, f, hi, hiDiagonal, n);
    original = lower < 0.0 ? upper : upper < 0.0 ? lower : mix(lower, upper, blend);
    if (original < 0.0) {
      float bestDistance = 1e30;
      for (int k = first; k <= last; ++k) {
        int diagonal = code / (k/3 == 0 ? 1 : k/3 == 1 ? 3 : 9) % 3;
        float value = skyGridLevel(grid, scale, tile, f, k, diagonal, n);
        float distance = abs(w.y-(meta[k/3]-SKY_GRID_LOW-float(k%3)*SKY_GRID_STEP));
        if (value >= 0.0 && distance < bestDistance) { original=value; bestDistance=distance; }
      }
      if (original < 0.0) original = max(0.0, skyGridLevel(grid, scale, tile, f, 9, 0, n));
    }
  }
  if (exterior && n.y < 0.0) {
    vec2 a = skySurfaceLevel(grid, scale, tile, f, lo, loDiagonal, w, n);
    vec2 b = skySurfaceLevel(grid, scale, tile, f, hi, hiDiagonal, w, n);
    vec2 total = mix(a, b, blend);
    float surfaceWeight = (1.0 - total.y);
    total += skySurfaceLevel(grid, scale, tile, f, 9, 0, w, n) * surfaceWeight;
    if (total.y > 0.0001) return mix(original, total.x / total.y, smoothstep(0.0, 0.5, -n.y));
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
  float tileSize = 128.0 / scale;
  vec3 g = o + skyGridCamera;
  for (int axis = 0; axis < 2; ++axis) {
    float d = axis == 0 ? n.x : n.z;
    if (abs(d) > 0.5) {
      float x = axis == 0 ? g.x : g.z;
      float f = fract(x / tileSize) * tileSize;
      // world units to the edge the face looks across
      float toEdge = d > 0.0 ? tileSize - f : f;
      if (toEdge < SKY_EDGE_REACH) {
        float step = (toEdge + 1.0) * sign(d);
        if (axis == 0) {
          o.x += step;
        } else {
          o.z += step;
        }
      }
    }
  }
  vec2 field = skyFieldOn == 1 ? skyFieldVisibility(o) : vec2(0.0);
  float v = skyGridVisibility(skyGrid, scale, o + skyGridCamera, n);
  if (skyFieldOn == 1) {
    v = mix(v, max(v, field.r), field.g);
  }
  return v;
}
