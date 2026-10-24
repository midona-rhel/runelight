// Global illumination: irradiance probes laid over the ground and along the walls (GiProbes.java), relit on the CPU
// from a hit cache whenever the sky or sun changes (GlobalIllumination.java). Looked up at half resolution in the AO
// pass; the composite blends the result with its plain ambient light. Needs frame.glsl.

// RGBA16F, GI_TEXELS texels per probe (GlobalIllumination.TEXELS), GI_ROW probes to a row: probe i at
// (i % GI_ROW * GI_TEXELS + k, i / GI_ROW). Texels 0-2 are its light as cosine-convolved L1 harmonics, 12 floats in
// order: the mean radiance rgb, then the gradient rgb along x, y and z. Texels 3-5 are how far it sees, in voxels:
// the mean distance toward +x, -x, +y, -y, +z, -z, then the six variances. Texel 6 is where it stands: x and z from
// the corner of its GI_CHUNK square, y, and the square (x + z * GI_CHUNKS), world units from the grid's corner.
uniform sampler2D giProbes;
// RGBA16UI, GI_LIST_TEXELS texels per tile, tile (x, z) at (x * GI_LIST_TEXELS + k, z): its list of the probes near it
// (GiProbes.lists), four to a texel, nearest first, GI_NONE after the last.
uniform usampler2D giIndex;
uniform int giEnabled;
uniform vec3 giCamera;       // camera position relative to the probe grid's corner, world y

#define GI_SIZE 104
#define GI_TEXELS 7
#define GI_ROW 256
#define GI_CHUNK 1024.0
#define GI_CHUNKS 16.0
#define GI_LIST_TEXELS 3
#define GI_NONE 65535u
#define GI_TILE 128.0
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
// a probe lights the points this near (GiProbes.RADIUS), and counts like 1 / (1 + distance / GI_NEAR) within
#define GI_RADIUS 192.0
#define GI_NEAR 64.0
// the pixel reads this many probes of its tile's list at most (the nearest ones that are in front of it and in reach),
// which bounds the texture fetches: the list's GI_LIST_TEXELS, one for each of its entries, and 6 more for each probe read
#define GI_MAX_PROBES 7
// the total weight of probes that makes the light count fully; with less it fades back to the plain skylight
#define GI_FULL_WEIGHT 0.12

// How much of the probe at texel t (its first) sees the point dist voxels away along unit dir from it: 1 where the
// point is nearer than the probe's mean distance that way, otherwise Chebyshev's bound on the share of rays that went
// farther (var / (var + (dist - mean)^2)), cubed to cut off sharply. The mean and variance toward dir blend the six
// axis bins by the squares of dir's components, which add up to 1.
float giVisibility(ivec2 t, vec3 dir, float dist) {
  vec4 v0 = texelFetch(giProbes, t + ivec2(3, 0), 0);
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

// Adds the light of probe id to sum and its weight to wsum, for the surface at world (grid) position world facing n,
// whose lookup point is q. A probe counts less the farther it is, the farther behind the surface it lies (the other
// side of a wall, under a roof), and not at all where its rays stop short of the point (visibility). Returns whether
// the probe was in reach and in front, which is what counts toward GI_MAX_PROBES.
bool giGather(uint id, vec3 world, vec3 q, vec3 n, inout vec3 sum, inout float wsum) {
  ivec2 texel = ivec2(int(id % uint(GI_ROW)) * GI_TEXELS, int(id / uint(GI_ROW)));
  vec4 s = texelFetch(giProbes, texel + ivec2(GI_TEXELS - 1, 0), 0);
  float chunkZ = floor(s.w / GI_CHUNKS);
  vec3 probe = vec3((s.w - chunkZ * GI_CHUNKS) * GI_CHUNK + s.x, s.y, chunkZ * GI_CHUNK + s.z);
  vec3 fromProbe = q - probe;
  float dist = length(fromProbe);
  if (dist >= GI_RADIUS) {
    return false;
  }
  vec3 toProbe = probe - world;
  float facing = smoothstep(-GI_FACING_SOFT, GI_FACING_SOFT,
    dot(toProbe, n) * inversesqrt(max(dot(toProbe, toProbe), 1.0)));
  if (facing <= 0.0) {
    return false;
  }
  float reach = 1.0 - dist / GI_RADIUS;
  float w = reach * reach / (1.0 + dist / GI_NEAR) * facing;
  w *= giVisibility(texel, fromProbe / max(dist, 1e-3), dist / GI_VOXEL);
  if (w > 1e-5) {
    sum += giProbeLight(texel, n) * w;
    wsum += w;
  }
  return true;
}

// Indirect light on a surface at camera-relative p facing n: rgb the probes' light, a how much of it to use (0 outside
// the grid or with no usable probe). The point takes the light of the probes in its tile's list, each the light that
// falls on a surface facing n (so a roof reads what is above it, not the roof's own color from below), weighted by
// closeness; where little weight counts, the light fades to the plain skylight.
vec4 giIrradiance(vec3 p, vec3 n) {
  vec3 world = p + giCamera;
  vec3 q = world + n * GI_NORMAL_BIAS;
  vec2 t = q.xz / GI_TILE;
  vec2 edge = min(t, vec2(float(GI_SIZE)) - t);
  float fade = clamp(min(edge.x, edge.y) / GI_EDGE_FADE, 0.0, 1.0);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  ivec2 tile = ivec2(t);
  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  int read = 0;
  bool more = true;
  for (int k = 0; k < GI_LIST_TEXELS && more; ++k) {
    uvec4 ids = texelFetch(giIndex, ivec2(tile.x * GI_LIST_TEXELS + k, tile.y), 0);
    for (int e = 0; e < 4; ++e) {
      if (ids[e] == GI_NONE || read >= GI_MAX_PROBES) {
        more = false;
        break;
      }
      if (giGather(ids[e], world, q, n, sum, wsum)) {
        ++read;
      }
    }
  }
  if (wsum <= 1e-5) {
    return vec4(0.0);
  }
  return vec4(sum / wsum, fade * clamp(wsum / GI_FULL_WEIGHT, 0.0, 1.0));
}
