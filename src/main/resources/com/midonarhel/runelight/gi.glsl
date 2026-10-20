// Global illumination: irradiance probes over the base scene, one color each (GiProbes.java), in columns every two
// tiles with two probes per floor, relit on the CPU from a hit cache whenever the sky or the sun changes
// (GlobalIllumination.java). Looked up at half resolution in the AO pass; the composite blends the result with its
// plain ambient light. Needs frame.glsl.

uniform sampler2D giProbes;  // RGBA16F: probe (column x, level, column z) at (x, level * 52 + z); rgb its light, a 1 if usable
uniform sampler2D giMeta;    // RGBA32F per tile: the grounds of the three floors (world y, down), the roof line
uniform int giEnabled;
uniform vec3 giCamera;       // camera position relative to the probe grid's corner, world y

#define GI_SIZE 104
#define GI_PITCH 2
#define GI_COLUMNS 52
#define GI_LEVELS 6
#define GI_TILE 128.0
// a floor's two probes stand this high above its ground (GiProbes.LOW, HIGH)
#define GI_LOW 60.0
#define GI_HIGH 180.0
// the lookup moves off the surface along its normal and toward the camera, so a pixel takes its light from the
// space in front of it rather than from inside the wall or floor it lies on
#define GI_NORMAL_BIAS 40.0
#define GI_VIEW_BIAS 20.0
// tiles over which the light fades back to the plain ambient toward the grid's edges
#define GI_EDGE_FADE 3.0
// Pixels are indoor when this far off their surface along the normal they are in a building column and below its
// roof line (the bottom of the voxel holding the roof's top): more than a voxel, so a roof's top and a wall's outer
// face count as outdoors and the ceiling and inner faces as indoors.
#define GI_CLASS_BIAS 72.0
// weight left to a probe behind the surface
#define GI_BEHIND 0.05

// Indirect light on a surface at camera-relative p facing n: rgb the probes' light, a how much of it to use (0 outside
// the grid or with no usable probe). The light has no direction: the point blends linearly between the four nearest
// columns and, in each, the two probes around its height; only probes of the pixel's own class (indoor or outdoor)
// count, and those behind the surface count for little.
vec4 giIrradiance(vec3 p, vec3 n) {
  vec3 world = p + giCamera;
  vec3 q = world + n * GI_NORMAL_BIAS - normalize(p) * GI_VIEW_BIAS;
  vec2 t = q.xz / GI_TILE;
  vec2 edge = min(t, vec2(float(GI_SIZE)) - t);
  float fade = clamp(min(edge.x, edge.y) / GI_EDGE_FADE, 0.0, 1.0);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  // columns stand at the centers of every second tile
  vec2 c = (t - 0.5) / float(GI_PITCH);
  ivec2 b = ivec2(floor(c));
  vec2 f = c - vec2(b);
  vec3 qc = world + n * GI_CLASS_BIAS;
  float ownRoof = texelFetch(giMeta, clamp(ivec2(floor(qc.xz / GI_TILE)), 0, GI_SIZE - 1), 0).w;
  bool pixelIndoor = qc.y > ownRoof;

  vec3 sum = vec3(0.0);
  float wsum = 0.0;
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
      float w = wxz * (k == 0 ? 1.0 - tv : tv);
      if (w <= 0.0 || (hs[level] > m.w) != pixelIndoor) {
        continue;
      }
      vec4 pr = texelFetch(giProbes, ivec2(col.x, level * GI_COLUMNS + col.y), 0);
      vec3 toProbe = vec3(center.x, hs[level], center.y) - q;
      float facing = (dot(toProbe, n) * inversesqrt(max(dot(toProbe, toProbe), 1.0)) + 1.0) * 0.5;
      w *= pr.a * (facing * facing + GI_BEHIND);
      sum += pr.rgb * w;
      wsum += w;
    }
  }
  if (wsum <= 1e-5) {
    return vec4(0.0);
  }
  return vec4(sum / wsum, fade);
}
