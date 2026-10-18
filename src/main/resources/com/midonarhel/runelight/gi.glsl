// Global illumination: irradiance probes over the base scene, one column per tile with three layers (GiProbes.java),
// relit on the CPU from a hit cache whenever the sky or the sun changes (GlobalIllumination.java). Looked up at half
// resolution in the AO pass; the composite blends the result with its plain ambient light. Needs frame.glsl.

uniform sampler2D giProbes;  // RGBA16F: probe (x, z, layer), channel c's irradiance e0 + dot(e.xyz, n) at (x * 3 + c, layer * 104 + z)
uniform sampler2D giMeta;    // RGBA32F per column: the three layers' heights (world y, down), roof + probe validity bits / 16
uniform int giEnabled;
uniform vec3 giCamera;       // camera position relative to the probe grid's corner, world y

#define GI_SIZE 104
#define GI_TILE 128.0
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

vec3 giProbe(ivec2 col, int layer, vec3 n) {
  int y = layer * GI_SIZE + col.y;
  vec4 nn = vec4(1.0, n);
  return max(vec3(dot(texelFetch(giProbes, ivec2(col.x * 3, y), 0), nn),
    dot(texelFetch(giProbes, ivec2(col.x * 3 + 1, y), 0), nn),
    dot(texelFetch(giProbes, ivec2(col.x * 3 + 2, y), 0), nn)), 0.0);
}

// Indirect light on a surface at camera-relative p facing n: rgb in the composite's light units, a how much of
// it to use (0 outside the grid or with no usable probe). Probes interpolate between the four nearest columns and
// the two layers around the point; only probes of the pixel's own class (indoor or outdoor) count.
vec4 giIrradiance(vec3 p, vec3 n) {
  vec3 world = p + giCamera;
  vec3 q = world + n * GI_NORMAL_BIAS - normalize(p) * GI_VIEW_BIAS;
  vec2 t = q.xz / GI_TILE;
  vec2 edge = min(t, vec2(float(GI_SIZE)) - t);
  float fade = clamp(min(edge.x, edge.y) / GI_EDGE_FADE, 0.0, 1.0);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  vec2 c = t - 0.5;
  ivec2 b = ivec2(floor(c));
  vec2 f = c - vec2(b);
  vec3 qc = world + n * GI_CLASS_BIAS;
  float ownRoof = floor(texelFetch(giMeta, clamp(ivec2(floor(qc.xz / GI_TILE)), 0, GI_SIZE - 1), 0).w);
  bool pixelIndoor = qc.y > ownRoof;

  vec3 sum = vec3(0.0);
  float wsum = 0.0;
  for (int i = 0; i < 4; ++i) {
    ivec2 o = ivec2(i & 1, i >> 1);
    ivec2 col = clamp(b + o, 0, GI_SIZE - 1);
    vec4 m = texelFetch(giMeta, col, 0);
    float roof = floor(m.w);
    int bits = int((m.w - roof) * 16.0 + 0.5);
    float wxz = (o.x == 1 ? f.x : 1.0 - f.x) * (o.y == 1 ? f.y : 1.0 - f.y);
    // layers go up (y down): between the two around the point, the lowest below it, the highest above it
    int lo = 0, hi = 0;
    float tv = 0.0;
    if (q.y < m.x && q.y >= m.y) {
      hi = 1;
      tv = (m.x - q.y) / max(m.x - m.y, 1.0);
    } else if (q.y < m.y) {
      lo = 1;
      hi = 2;
      tv = clamp((m.y - q.y) / max(m.y - m.z, 1.0), 0.0, 1.0);
    }
    vec2 center = (vec2(col) + 0.5) * GI_TILE;
    for (int k = 0; k < 2; ++k) {
      int layer = k == 0 ? lo : hi;
      float w = wxz * (k == 0 ? 1.0 - tv : tv);
      if (w <= 0.0 || ((bits >> layer) & 1) == 0) {
        continue;
      }
      float h = layer == 0 ? m.x : layer == 1 ? m.y : m.z;
      if ((h > roof) != pixelIndoor) {
        continue;
      }
      vec3 toProbe = vec3(center.x, h, center.y) - q;
      float facing = (dot(toProbe, n) * inversesqrt(max(dot(toProbe, toProbe), 1.0)) + 1.0) * 0.5;
      w *= facing * facing + GI_BEHIND;
      sum += giProbe(col, layer, n) * w;
      wsum += w;
    }
  }
  if (wsum <= 1e-5) {
    return vec4(0.0);
  }
  return vec4(sum / wsum, fade);
}
