// Skylight, the AO pass's half: how much of the sky a pixel sees, from the sky probes (SkyProbes.java). They hold the
// sky's visibility over the upper hemisphere as L1 spherical harmonics, half a tile apart and a few layers high above
// the ground (sky_probe_layout.glsl). A pixel looks them up a half spacing off its surface, trilinearly, and evaluates
// the harmonics for its normal as a cosine weighted irradiance, scaled so that under the open sky it is 1 whichever way
// it faces; a wall under eaves sees the sky sideways, the ground under them doesn't see it overhead. Written to the
// shadow target's alpha at half resolution; the composite upsamples it with the AO and scales the sky and ambient
// light by it (skylight_lighting.glsl).
// Needs frame.glsl.

#include "sky_probe_layout.glsl"

uniform int skylightOn;
// RGBA16F, SKY_LAYERS layers of harmonics (constant, x, up, z), then the columns' ground and top (SkyProbes.java)
uniform sampler3D skyProbes;
uniform ivec2 skyOrigin;  // the probe window's lowest probe, in probe steps
uniform vec3 skyCamera;   // the camera in world coordinates, to turn camera-relative positions into them
// Room-fill corner values (SkyField.java): rg/ba light and cover per half; g also holds 2 * diagonal kind.
// Texture coordinates are
// camera-relative world xz * skyFieldMap.xy + skyFieldMap.zw
uniform sampler2D skyField;
uniform int skyFieldOn;
uniform vec4 skyFieldMap;

// Probes are looked up this far off the surface: a probe's own distance to a wall beside it, so a wall reads the probe
// in front of it
#define SKY_PROBE_OFFSET (0.5 * SKY_PROBE_SPACING)
// the window's last stretch fades to open sky: its outer probes were worked out last, or not yet
#define SKY_WINDOW_FADE 1024.0
// world units a point may be below the top of the scenery over a column and not count as covered by it: roofs and
// treetops slope within a probe spacing
#define SKY_COVER_SLACK 24.0
// weight of the plain trilinear lookup in the mix with the one that keeps to probes on the point's side of the
// scenery over them: where that has no probe at all, the plain one takes over
#define SKY_PLAIN_WEIGHT 0.01
// the irradiance of the harmonics' constant term and of the linear ones: the cosine lobe's, pi and 2 pi / 3, times
// the harmonics' own factors
#define SKY_IRRADIANCE_0 0.886227
#define SKY_IRRADIANCE_1 1.023327

// Nudge off the surface to choose its facing side at a tile boundary, for the room-fill field. A larger offset can
// cross a nearby wall and read outdoor fill instead of this surface's room.
#define SKY_NORMAL_OFFSET 1.0
// The room-fill field is per tile corner, and a wall's outer face stands at or just short of its tile's edge, so which
// tile a point of it falls in depends on how far its brick pattern is inset: the mortar read the room behind the wall
// and the bricks the outdoors. A face within SKY_EDGE_REACH world units of the tile edge it looks across reads the tile
// beyond that edge, the one it faces.
#define SKY_EDGE_REACH 32.0
#define SKY_TILE 128.0

// Sky visibility at world position w for a surface facing n (0 to 1, 1 with the whole hemisphere open)
//
// The four columns around the point each interpolate their two layers around its height, over their own ground. A
// probe is covered when it lies below the top of the scenery over its column (inside a roof, a wall, a treetop's
// shade); the point is covered by the share of the columns that have scenery above it. A probe counts for as much of
// its weight as the point is on its side: under a roof only the covered probes count, in the open only the others, in
// between both, so a probe inside a roof's thickness never darkens its top, and a room doesn't read the yard beside
// it through its wall.
float skyProbeVisibility(vec3 w, vec3 n) {
  vec3 o = w + n * SKY_PROBE_OFFSET;
  vec2 center = (vec2(skyOrigin) + 0.5 * float(SKY_PROBES)) * SKY_PROBE_SPACING;
  float inside = smoothstep(0.0, SKY_WINDOW_FADE,
    0.5 * float(SKY_PROBES) * SKY_PROBE_SPACING - max(abs(o.x - center.x), abs(o.z - center.y)));
  if (inside <= 0.0) {
    return 1.0;
  }
  // probe i lies at (i + 0.5) spacings
  vec2 g = o.xz / SKY_PROBE_SPACING - 0.5;
  ivec2 first = ivec2(floor(g));
  vec2 f = g - vec2(first);
  ivec2 slot[4];
  vec2 column[4];  // ground and top
  float across[4];
  float covered = 0.0;
  for (int c = 0; c < 4; ++c) {
    ivec2 d = ivec2(c & 1, c >> 1);
    slot[c] = ivec2(skyProbeSlot(first.x + d.x), skyProbeSlot(first.y + d.y));
    column[c] = texelFetch(skyProbes, ivec3(slot[c], SKY_LAYERS), 0).xy;
    across[c] = (d.x == 1 ? f.x : 1.0 - f.x) * (d.y == 1 ? f.y : 1.0 - f.y);
    if (o.y > column[c].y + SKY_COVER_SLACK) {
      covered += across[c];
    }
  }
  vec4 plain = vec4(0.0);
  vec4 sided = vec4(0.0);
  float sidedWeight = 0.0;
  for (int c = 0; c < 4; ++c) {
    float layer = clamp((column[c].x - o.y - SKY_LAYER_BASE) / SKY_LAYER_STEP, 0.0, float(SKY_LAYERS - 1));
    int below = int(floor(layer));
    for (int k = 0; k < 2; ++k) {
      int probeLayer = min(below + k, SKY_LAYERS - 1);
      float weight = across[c] * (k == 0 ? 1.0 - (layer - float(below)) : layer - float(below));
      vec4 sh = texelFetch(skyProbes, ivec3(slot[c], probeLayer), 0);
      float probeY = column[c].x - SKY_LAYER_BASE - float(probeLayer) * SKY_LAYER_STEP;
      float onSide = weight * (probeY > column[c].y ? covered : 1.0 - covered);
      plain += sh * weight;
      sided += sh * onSide;
      sidedWeight += onSide;
    }
  }
  vec4 sh = (sided + plain * SKY_PLAIN_WEIGHT) / (sidedWeight + SKY_PLAIN_WEIGHT);
  // Irradiance of the harmonics for the normal, over what it would be if the upper hemisphere were open. Facing
  // down it keeps to the horizontal part of the normal: the underside of eaves sees the sky sideways.
  vec3 m = vec3(n.x, max(-n.y, 0.0), n.z);
  float seen = SKY_IRRADIANCE_0 * sh.x + SKY_IRRADIANCE_1 * dot(sh.yzw, m);
  float open = 1.5707963 * (1.0 + m.y);
  return mix(1.0, clamp(seen / open, 0.0, 1.0), inside);
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

// The point the room-fill field is read at: the surface's, moved across the tile edge a wall's face looks across when it
// is near it
vec3 skyFieldPoint(vec3 p, vec3 n) {
  vec3 o = p + n * SKY_NORMAL_OFFSET;
  if (n.y >= 0.1) {
    return o;
  }
  vec3 g = o + skyCamera;
  vec2 f = fract(g.xz / SKY_TILE) * SKY_TILE;
  // world units to the edge each axis' face looks across
  vec2 toEdge = vec2(n.x > 0.0 ? SKY_TILE - f.x : f.x, n.z > 0.0 ? SKY_TILE - f.y : f.y);
  if (abs(n.x) > 0.5 && toEdge.x < SKY_EDGE_REACH) {
    o.x += (toEdge.x + 1.0) * sign(n.x);
  }
  if (abs(n.z) > 0.5 && toEdge.y < SKY_EDGE_REACH) {
    o.z += (toEdge.y + 1.0) * sign(n.z);
  }
  return o;
}

// 1 under the open sky. Under cover (the reconstructed field's green channel) the light flowing in through
// the openings counts too: it stands for the light that gets deep into a room by bouncing off the walls, which the
// probes don't see (they see only what the sky lights directly, from above: a roof covers everything under it); the
// probes stand for what the sky lights directly (the open yard, the top of a wall), so neither darkens what the other
// lights. The field masks the interiors: it mixes by its cover, which is 0 outside the rooms, so the probes' outdoor
// values are never lifted indoors, and a room's walls need no special care in the probes' lookup.
float skylightVisibility(vec3 p, vec3 n) {
  if (skylightOn == 0) {
    return 1.0;
  }
  float v = skyProbeVisibility(p + skyCamera, n);
  if (skyFieldOn == 1) {
    vec2 field = skyFieldVisibility(skyFieldPoint(p, n));
    v = mix(v, max(v, field.r), field.g);
  }
  return v;
}
