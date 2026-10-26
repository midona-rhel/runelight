// The light masks (LightClusters.java): for every cluster (a screen column's depth slices next to each other), then
// every fog column, a bit per light in view, in RGBA32UI texels of 128 lights: light i is bit i & 31 of word i >> 5,
// word w component w & 3 of texel w >> 2. An entry has as many texels as the lights in view need, and the buffer
// holds exactly CLUSTER_ENTRIES entries, so its size tells how many. Needs cluster_config and clusterData.

// the entry of the cluster at rel, the point's position across the scene viewport (0 to 1), and view depth z
int clusterEntry(vec2 rel, float z) {
  int cx = clamp(int(rel.x * float(CLUSTERS_X)), 0, CLUSTERS_X - 1);
  int cy = clamp(int(rel.y * float(CLUSTERS_Y)), 0, CLUSTERS_Y - 1);
  int cz = z <= CLUSTER_NEAR ? 0
    : clamp(int(floor(log(z / CLUSTER_NEAR) / log(CLUSTER_FAR / CLUSTER_NEAR) * float(CLUSTERS_Z))), 0, CLUSTERS_Z - 1);
  return (cy * CLUSTERS_X + cx) * CLUSTERS_Z + cz;
}

// the entry of the fog column at rel, after the clusters
int columnEntry(vec2 rel) {
  int cx = clamp(int(rel.x * float(CLUSTERS_X)), 0, CLUSTERS_X - 1);
  int cy = clamp(int(rel.y * float(CLUSTERS_Y)), 0, CLUSTERS_Y - 1);
  return CLUSTERS_X * CLUSTERS_Y * CLUSTERS_Z + cy * CLUSTERS_X + cx;
}

// A walk over an entry's lights in index order: LightMask m = clusterLights(entry); int li; while (nextLight(m, li))
struct LightMask {
  int base;   // the entry's first texel
  int words;
  int word;   // the word bits came from
  uvec4 mask; // the texel holding it
  uint bits;  // its lights not visited yet
};

LightMask clusterLights(int entry) {
  int texels = textureSize(clusterData) / CLUSTER_ENTRIES;
  return LightMask(entry * texels, texels * 4, -1, uvec4(0u), 0u);
}

bool nextLight(inout LightMask m, out int light) {
  while (m.bits == 0u) {
    if (++m.word >= m.words) {
      light = -1;
      return false;
    }
    if ((m.word & 3) == 0) {
      m.mask = texelFetch(clusterData, m.base + (m.word >> 2));
    }
    m.bits = m.mask[m.word & 3];
  }
  // GLSL 3.30 has no findLSB. The lowest bit alone is a power of two, which a float holds exactly (2^31 too), so the
  // float's exponent is the bit's index.
  uint low = m.bits & (~m.bits + 1u);
  light = m.word * 32 + int(floatBitsToUint(float(low)) >> 23) - 127;
  m.bits ^= low;
  return true;
}

bool clusterHasLights(int entry) {
  LightMask m = clusterLights(entry);
  int li;
  return nextLight(m, li);
}

// 117 HD's falloff of a point light, from the squared distance to it and its squared radius (below 1 inside it): the clusters'
// lights and the light map's lights are worked out with it alike (light_map.glsl)
float pointLightFalloff(float d2, float r2) {
  float att = 1.0 - sqrt(d2 / r2);
  return att * att;
}
