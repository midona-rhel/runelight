// Global illumination: a volume of tint (GiEmitters.java, GiRelight.java), relit on the CPU whenever the sky or sun
// changes (GlobalIllumination.java). Every surface of the base scene gives the air in front of it its colour, as much as it
// is lit, thinning out with the distance from it; this looks that colour up a little in front of a pixel, in the AO
// pass at half resolution. A wall beside sunlit grass is so greened at its foot, and less higher up where the grass'
// colour is farther, and a floor takes the colour of the walls beside it. The colour went through air only, so it does
// not leak through a wall, and a pixel takes less of the colour of surfaces that lie the way it does (a wall of its
// walls', a floor of its floors', a roof of its roofs'): it already has it. The composite tints its light with the
// result. Needs frame.glsl.

// RGBA8, the voxels of the grid with x across, z down and the layers (y, down) in depth, three times: the first ny layers
// hold the colour that upright surfaces (walls) give, the next ny that slanted ones (45 degrees: roofs, stairs) give,
// the last ny that level ones (floors); a surface between gives to the two. rgb is the square root of the sum of colour
// a voxel is given, over GI_RANGE; a is 1 where the field is given to a voxel, every empty one and the filled ones
// a surface of the field's slope looks away from not, so reading between voxels (filtered) divides by it, which takes
// the average of the ones that are given. Two of them, for the volume shown and the one blending into it.
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
// A pixel reads the volume this far (world units) in front of itself: past the half voxel a surface can be off the center
// of its own voxel, and short of the next, so the point is in front of the surface, between the first voxels of air and
// never behind a wall one voxel thick, and the colour is read close to where it is wanted.
#define GI_LOOKUP 40.0
// tiles over which the tint fades back to nothing toward the grid's edges
#define GI_EDGE_FADE 3.0
// a point is given none of a field when the share of the filter's weight that is, is below the first, and in full when it
// is the second; the share can be a good deal under one in front of a surface, where the filter reaches into the filled
// voxel behind it
#define GI_GIVEN_MIN 0.02
#define GI_GIVEN_FULL 0.1
// A pixel whose slope is within 15 degrees of a field's (the sine of it squared, GiEmitters.SLOPE_SNAP) counts as lying
// exactly so, so the field of its own kind is left unread there, not read at a hair's weight: gentle ground, roofs
// of a shallower pitch than 45 degrees, and models that are a little off true.
#define GI_SLOPE_SLACK 0.067

// The colour of one field (0 upright, 1 slanted, 2 level) of one volume at cell, in voxels, x and z from the grid's corner, y from its first
// layer: rgb the sum of colour, times a, how far the point is given any of the field's, 0 to 1.
vec4 giFetch(sampler3D volume, vec3 cell, float field) {
  // the layers of a field are clamped to its own, not to the texture's, or the first layers of one would blend with the
  // last of the other
  float layer = field * giGrid.y + clamp(cell.y, 0.5, giGrid.y - 0.5);
  vec4 t = texture(volume, vec3(cell.x, cell.z, layer) / vec3(giGrid.x, giGrid.z, 3.0 * giGrid.y));
  if (t.a < GI_GIVEN_MIN) {
    return vec4(0.0);
  }
  vec3 u = t.rgb / t.a;
  float given = smoothstep(GI_GIVEN_MIN, GI_GIVEN_FULL, t.a);
  return vec4(GI_RANGE * u * u * given, given);
}

vec4 giField(vec3 cell, float field) {
  vec4 to = giFetch(giVolumeTo, cell, field);
  if (giBlend >= 1.0) {
    return to;
  }
  return mix(giFetch(giVolume, cell, field), to, giBlend);
}

// The tint on a surface at camera-relative p facing n: rgb the volume's tint (the colour of the surfaces around it, as light
// of luminance 0 to 1), a how much of it to use (0 outside the grid, or inside a wall).
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
  // A pixel takes each field by how unlike its own slope that of the field is, the sine squared of the angle between
  // the two: none of the colour floors give for a floor, none of the walls' for a wall, and for a slope a part of each,
  // smoothly. Upright is 0 degrees from the vertical, slanted 45 and level 90; y is the sine of the pixel's.
  float y = abs(n.y);
  vec3 across = max(vec3(y * y, 0.5 - y * sqrt(max(1.0 - y * y, 0.0)), 1.0 - y * y) - GI_SLOPE_SLACK, 0.0)
    / (1.0 - GI_SLOPE_SLACK);
  vec3 sum = vec3(0.0);
  float given = 0.0;
  if (across.x > 0.0) {
    vec4 f = giField(cell, 0.0);
    sum += across.x * f.rgb;
    given = max(given, f.a);
  }
  if (across.y > 0.0) {
    vec4 f = giField(cell, 1.0);
    sum += across.y * f.rgb;
    given = max(given, f.a);
  }
  if (across.z > 0.0) {
    vec4 f = giField(cell, 2.0);
    sum += across.z * f.rgb;
    given = max(given, f.a);
  }
  // the colours add up; the point is a place the volume knows where any field does
  return vec4(sum, given * fade);
}
