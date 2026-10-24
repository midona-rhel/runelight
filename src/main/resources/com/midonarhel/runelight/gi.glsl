// Global illumination: tile-centered irradiance probes (GiProbes.java), up to two heights per floor
// within one tile of scenery, relit on the CPU from a hit cache whenever the sky or sun changes
// (GlobalIllumination.java). Looked up at half resolution in the AO pass; the composite blends the result with its
// plain ambient light. Needs frame.glsl.

// RGBA16F, GI_TEXELS texels per probe (GlobalIllumination.TEXELS): probe (column x, level, column z) at
// (x * GI_TEXELS + k, level * columns + z). Texels 0-2 are its light as cosine-convolved L1 harmonics, 12 floats in
// order: the mean radiance rgb, then the gradient rgb along x, y and z. Texels 3-5 are how far it sees, in voxels:
// the mean distance toward +x, -x, +y, -y, +z, -z, then the six variances. All zero for no probe.
uniform sampler2D giProbes;
uniform sampler2D giMeta;    // RGBA32F per tile: the grounds of the three floors (world y, down), the roof line
uniform int giEnabled;
uniform vec3 giCamera;       // camera position relative to the probe grid's corner, world y

#define GI_SIZE 104
#define GI_TEXELS 6
#define GI_COLUMNS (textureSize(giProbes, 0).x / GI_TEXELS)
#define GI_PITCH (GI_SIZE / GI_COLUMNS)
#define GI_LEVELS 6
#define GI_TILE 128.0
// a floor's two probes stand this high above its ground (GiProbes.LOW, HIGH)
#define GI_LOW 60.0
#define GI_HIGH 180.0
// the lookup moves a quarter of a tile off the surface along its normal, so a pixel takes its light from the
// space in front of it rather than from inside the wall or floor it lies on
#define GI_NORMAL_BIAS 32.0
// the probes' distances are in voxels (GiGrid.VOXEL world units)
#define GI_VOXEL 64.0
// the least variance (voxels squared) the visibility test assumes, so a probe that sees a flat wall exactly still
// lets a pixel just in front of it through
#define GI_MIN_VARIANCE 0.25
// tiles over which the light fades back to the plain ambient toward the grid's edges
#define GI_EDGE_FADE 3.0
// probes count fully once this far (cosine) in front of the surface, none as far behind it
#define GI_FACING_SOFT 0.2
// the share of the surrounding probes' weight that counts as full coverage
#define GI_COVER 2.0

// How much of the probe at texel t (its first) sees the point dist voxels away along unit dir from it: 1 where the
// point is nearer than the probe's mean distance that way, otherwise Chebyshev's bound on the share of rays that went
// farther (var / (var + (dist - mean)^2)), cubed to cut off sharply. The mean and variance toward dir blend the six
// axis bins by the squares of dir's components, which add up to 1. Negative for no probe.
float giVisibility(ivec2 t, vec3 dir, float dist) {
  vec4 v0 = texelFetch(giProbes, t + ivec2(3, 0), 0);
  if (v0.x <= 0.0) {
    return -1.0;
  }
  vec4 v1 = texelFetch(giProbes, t + ivec2(4, 0), 0);
  vec4 v2 = texelFetch(giProbes, t + ivec2(5, 0), 0);
  float mean[6] = float[6](v0.x, v0.y, v0.z, v0.w, v1.x, v1.y);
  float variance[6] = float[6](v1.z, v1.w, v2.x, v2.y, v2.z, v2.w);
  ivec3 bin = ivec3(0, 2, 4) + ivec3(lessThan(dir, vec3(0.0)));
  vec3 w = dir * dir;
  float m = w.x * mean[bin.x] + w.y * mean[bin.y] + w.z * mean[bin.z];
  float excess = dist - m;
  if (excess <= 0.0) {
    return 1.0;
  }
  float v = max(w.x * variance[bin.x] + w.y * variance[bin.y] + w.z * variance[bin.z], GI_MIN_VARIANCE);
  float c = v / (v + excess * excess);
  return c * c * c;
}

// The light of the probe at texel t (its first) that falls on a surface facing n: the mean plus the gradient along n.
vec3 giProbeLight(ivec2 t, vec3 n) {
  vec4 a = texelFetch(giProbes, t, 0);
  vec4 b = texelFetch(giProbes, t + ivec2(1, 0), 0);
  vec4 c = texelFetch(giProbes, t + ivec2(2, 0), 0);
  vec3 gx = vec3(a.w, b.xy), gy = vec3(b.zw, c.x), gz = c.yzw;
  return max(a.rgb + gx * n.x + gy * n.y + gz * n.z, 0.0);
}

// Indirect light on a surface at camera-relative p facing n: rgb the probes' light, a how much of it to use (0 outside
// the grid or with no usable probe). The point blends linearly between the four nearest columns and, in each, the two
// probes around its height. Each probe gives the light that falls on a surface facing n (so a roof reads what is above
// it, not the roof's own color from below), counts less the farther behind the surface it lies (inside the wall's
// building, under the roof), and not at all where its rays stop short of the point (the other side of a wall or
// roof); where few count the light fades to the plain skylight.
vec4 giIrradiance(vec3 p, vec3 n) {
  vec3 world = p + giCamera;
  vec3 q = world + n * GI_NORMAL_BIAS;
  vec2 t = q.xz / GI_TILE;
  vec2 edge = min(t, vec2(float(GI_SIZE)) - t);
  float fade = clamp(min(edge.x, edge.y) / GI_EDGE_FADE, 0.0, 1.0);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  // columns stand at tile centers; derive spacing from the uploaded texture during live reloads
  vec2 c = (t - 0.5) / float(GI_PITCH);
  ivec2 b = ivec2(floor(c));
  vec2 f = c - vec2(b);

  vec3 sum = vec3(0.0);
  float wsum = 0.0, total = 0.0;
  for (int i = 0; i < 4; ++i) {
    ivec2 o = ivec2(i & 1, i >> 1);
    ivec2 col = clamp(b + o, 0, GI_COLUMNS - 1);
    vec4 m = texelFetch(giMeta, col * GI_PITCH, 0);
    float wxz = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
    float hs[GI_LEVELS] = float[GI_LEVELS](m.x - GI_LOW, m.x - GI_HIGH, m.y - GI_LOW, m.y - GI_HIGH, m.z - GI_LOW,
      m.z - GI_HIGH);
    // levels go up (y down): j is the highest one below the point, the next one is above it
    int j = -1;
    for (int k = 0; k < GI_LEVELS; ++k) {
      if (hs[k] >= q.y) {
        j = k;
      }
    }
    int lo = max(j, 0), hi = min(j + 1, GI_LEVELS - 1);
    float tv = lo == hi ? 0.0 : clamp((hs[lo] - q.y) / (hs[lo] - hs[hi]), 0.0, 1.0);
    vec2 center = (vec2(col * GI_PITCH) + 0.5) * GI_TILE;
    for (int k = 0; k < 2; ++k) {
      int level = k == 0 ? lo : hi;
      float wt = wxz * (k == 0 ? 1.0 - tv : tv);
      if (wt <= 0.0) {
        continue;
      }
      ivec2 texel = ivec2(col.x * GI_TEXELS, level * GI_COLUMNS + col.y);
      vec3 probe = vec3(center.x, hs[level], center.y);
      vec3 fromProbe = q - probe;
      float dist = length(fromProbe);
      float vis = giVisibility(texel, fromProbe / max(dist, 1e-3), dist / GI_VOXEL);
      if (vis < 0.0) {
        // No sample at this level; distance weighting below limits the remaining samples' reach.
        continue;
      }
      total += wt;
      // a probe behind the surface (the other side of a wall, under a roof) lights nothing in front of it
      vec3 toProbe = probe - world;
      float facing = smoothstep(-GI_FACING_SOFT, GI_FACING_SOFT,
        dot(toProbe, n) * inversesqrt(max(dot(toProbe, toProbe), 1.0)));
      // Fade beyond the highest/lowest supported height instead of extending that color indefinitely.
      float heightWeight = 1.0 - smoothstep(GI_HIGH - GI_LOW, GI_TILE * 2.0, abs(toProbe.y));
      float w = wt * facing * heightWeight * vis;
      if (w <= 1e-5) {
        continue;
      }
      sum += giProbeLight(texel, n) * w;
      wsum += w;
    }
  }
  if (wsum <= 1e-5) {
    return vec4(0.0);
  }
  // where only some of the surrounding probes light this side, the rest of the way is the plain skylight
  return vec4(sum / wsum,
    fade * clamp(wsum / max(total, 1e-5) * GI_COVER, 0.0, 1.0));
}
