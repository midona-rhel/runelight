// Skylight, the AO pass's half (SkyOcclusion.java): how much of the sky a pixel sees, from the top-down depth of the
// scenery around it, in the spirit of Community Shaders' Skylighting. Around the point, in a ring of directions,
// the scenery's tops at two distances give the horizon's slope that way; the sky above that horizon, cosine weighted,
// is what the point sees, 1 / (1 + slope^2). The column right above the point counts too, so anything under a roof is
// dark and the top of every column is open. Walls only look out over their own side. Written to the shadow target's
// alpha at half resolution; the composite upsamples it with the AO and scales the sky and ambient light by it
// (light_occlusion.glsl). Needs frame.glsl.

uniform int skylightOn;
uniform sampler2D skyOcclusion;     // top-down window depth of the scenery, raw
uniform mat4 skyOcclusionProjRel;   // camera-relative world -> occlusion clip
uniform float skyOcclusionDepthRange;

#define SKY_DIRECTIONS 8
// world units out to the two horizon taps in each direction
#define SKY_RADIUS_NEAR 56.0
#define SKY_RADIUS_FAR 256.0
// the point moves this far off its surface along the normal, so a surface doesn't hide its own sky
#define SKY_NORMAL_OFFSET 24.0
// world units scenery may rise above the point before it hides any sky: the surface's own bumps
#define SKY_HEIGHT_BIAS 24.0
// how far the scenery right above may be before the point counts as fully covered (world units above the bias)
#define SKY_COVER_RAMP 48.0
#define SKY_TAU 6.28318530718

// How high the scenery's top is above camera-relative point q (world units, up positive); a large negative number
// past the map, where nothing is known to cover anything
float skyOccluderAbove(vec3 q) {
  vec4 c = skyOcclusionProjRel * vec4(q, 1.0);
  vec2 uv = c.xy * 0.5 + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) {
    return -1e9;
  }
  float depth = depthZeroToOne == 1 ? c.z : c.z * 0.5 + 0.5;
  // smaller depth is higher up; cleared texels (nothing drawn) are 1, far below
  return (depth - textureLod(skyOcclusion, uv, 0.0).r) * 2.0 * skyOcclusionDepthRange;
}

// 1 under the open sky, 0 under a roof
float skylightVisibility(vec3 p, vec3 n, float noise) {
  if (skylightOn == 0) {
    return 1.0;
  }
  vec3 o = p + n * SKY_NORMAL_OFFSET;
  float above = skyOccluderAbove(o);
  if (above < -1e8) {
    return 1.0;
  }
  float open = 1.0 - smoothstep(SKY_HEIGHT_BIAS, SKY_HEIGHT_BIAS + SKY_COVER_RAMP, above);
  if (open <= 0.0) {
    return 0.0;
  }
  float side = length(n.xz);
  vec2 facing = side > 1e-3 ? n.xz / side : vec2(0.0);
  float sum = 0.0;
  float wsum = 0.0;
  for (int i = 0; i < SKY_DIRECTIONS; ++i) {
    float a = (float(i) + noise) * (SKY_TAU / float(SKY_DIRECTIONS));
    vec2 d = vec2(cos(a), sin(a));
    float slope = 0.0;
    slope = max(slope, (skyOccluderAbove(o + vec3(d.x, 0.0, d.y) * SKY_RADIUS_NEAR) - SKY_HEIGHT_BIAS) / SKY_RADIUS_NEAR);
    slope = max(slope, (skyOccluderAbove(o + vec3(d.x, 0.0, d.y) * SKY_RADIUS_FAR) - SKY_HEIGHT_BIAS) / SKY_RADIUS_FAR);
    // a wall sees the sky on its own side
    float w = mix(1.0, max(dot(d, facing), 0.0) * 2.0, side);
    sum += w / (1.0 + slope * slope);
    wsum += w;
  }
  return open * (wsum > 1e-4 ? sum / wsum : 1.0);
}
