// Global illumination: a volume of tint (GiEmitters.java, GiRelight.java), relit on the CPU whenever the sky or sun
// changes (GlobalIllumination.java). Every surface of the base scene gives the air in front of it its colour, as much as it
// is lit, thinning out with the distance from it; this looks that colour up a little in front of a pixel, in the AO
// pass at half resolution. A wall beside sunlit grass is so greened at its foot, and less higher up where the grass'
// colour is farther, and a floor takes the colour of the walls beside it. The colour went through air only, so it does
// not leak through a wall, and a pixel never reads the colour of its own kind of surface (a wall its walls', a floor its
// floors'): it already has it. The composite tints its light with the result. Needs frame.glsl.

// RGBA8, the voxels of the grid with x across, z down and the layers (y, down) in depth, twice: the first ny layers hold
// the colour that surfaces that lie flat (floors, roofs) give, the next ny the colour that standing ones (walls) give.
// rgb is the square root of the sum of colour a voxel is given, over GI_RANGE; a is 1 where the field gives that voxel any
// and 0 where not, so reading between voxels (filtered) divides by it, which takes the average of those that are given
// and none of the filled voxels or the empty ones beyond the colour's reach. Two of them, for the volume shown and the
// one blending into it.
uniform sampler3D giVolume;
uniform sampler3D giVolumeTo;
uniform float giBlend;       // how far giVolumeTo is blended into giVolume, 0 to 1
uniform int giEnabled;
uniform vec3 giCamera;       // camera position relative to the grid's corner, world y
uniform vec3 giGrid;         // the grid's size in voxels: x, layers, z
uniform float giLayer0;      // the grid's first layer: voxel y = world y / GI_VOXEL - giLayer0

#define GI_VOXEL 64.0
#define GI_TILE 128.0
#define GI_SIZE 104
// the largest sum of colour a texel holds (GiRelight.TEXEL_RANGE)
#define GI_RANGE 4.0
// A pixel reads the volume this far (world units) in front of itself: a voxel and a half, which puts the point
// between the first voxels in the air whatever part of its voxel a surface is at (it is a half voxel off one at worst), and
// in front of the one the pixel is on, never behind a wall one voxel thick.
#define GI_LOOKUP 96.0
// tiles over which the tint fades back to nothing toward the grid's edges
#define GI_EDGE_FADE 3.0
// a surface facing no more up or down than the first (the y of its normal) stands, more than the second lies flat, and
// between them it is both (GiEmitters)
#define GI_FLAT_MIN 0.5
#define GI_FLAT_MAX 0.7
// a point is given none of a field when the share of the filter's weight that is, is below the first, and in full when it
// is the second
#define GI_GIVEN_MIN 0.02
#define GI_GIVEN_FULL 1.0

// The colour and how far the point is given any of one field (0 flat, 1 standing) of one volume: rgb the sum of colour,
// a 0 to 1. cell is in voxels, x and z from the grid's corner, y from its first layer.
vec4 giFetch(sampler3D volume, vec3 cell, float field) {
  // the layers of a field are clamped to its own, not to the texture's, or the first layers of one would blend with the
  // last of the other
  float layer = field * giGrid.y + clamp(cell.y, 0.5, giGrid.y - 0.5);
  vec4 t = texture(volume, vec3(cell.x, cell.z, layer) / vec3(giGrid.x, giGrid.z, 2.0 * giGrid.y));
  if (t.a < GI_GIVEN_MIN) {
    return vec4(0.0);
  }
  vec3 u = t.rgb / t.a;
  return vec4(GI_RANGE * u * u, smoothstep(GI_GIVEN_MIN, GI_GIVEN_FULL, t.a));
}

vec4 giField(vec3 cell, float field) {
  vec4 to = giFetch(giVolumeTo, cell, field);
  if (giBlend >= 1.0) {
    return to;
  }
  return mix(giFetch(giVolume, cell, field), to, giBlend);
}

// The tint on a surface at camera-relative p facing n: rgb the volume's tint (the colour of the surfaces around it, as light
// of luminance 0 to 1), a how much of it to use (0 outside the grid, or where no surface is in reach).
vec4 giIrradiance(vec3 p, vec3 n) {
  vec3 world = p + giCamera;
  vec2 t = world.xz / GI_TILE;
  vec2 edge = min(t, vec2(float(GI_SIZE)) - t);
  float fade = clamp(min(edge.x, edge.y) / GI_EDGE_FADE, 0.0, 1.0);
  if (fade <= 0.0) {
    return vec4(0.0);
  }
  vec3 cell = (world + n * GI_LOOKUP) / GI_VOXEL;
  cell.y -= giLayer0;
  // a pixel that stands reads what surfaces that lie flat give, and one that lies flat what the ones that stand give
  float flatness = smoothstep(GI_FLAT_MIN, GI_FLAT_MAX, abs(n.y));
  vec4 acc = vec4(0.0);
  if (flatness < 1.0) {
    acc += (1.0 - flatness) * giField(cell, 0.0);
  }
  if (flatness > 0.0) {
    acc += flatness * giField(cell, 1.0);
  }
  return vec4(acc.rgb, acc.a * fade);
}
