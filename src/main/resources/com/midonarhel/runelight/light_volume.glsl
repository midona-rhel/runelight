// The baked static point lights (LightVolume.java, LightBake.java): the lights that stay put are worked out on the
// CPU, shadowed by the scene's voxels, into a window of cells (32 units) around the player, so a pixel takes their
// light from one lookup instead of a loop over them.
//
// A cell is an ambient cube: the brightness of the light that reaches it for each of the six directions a surface can
// face, which a normal n takes n.x^2 of the face it looks toward on x, and so on, so a surface facing away from a light
// gets none of it. The brightness is one number a face and the colour one for the cell. The 3D texture (RGBA16F) holds
// the cells in two halves of its height, a over b (the second DIM_Y rows):
//   a: +x, -x, +z, -z       b: +y, -y, tint, flicker
// y points down, so +y faces the ground. Tint is the colour's hue * 16 + saturation (7 and 4 bits), flicker the slot
// of the light among those that flicker or pulse that reaches the cell most times 16 plus its share of the cell's
// light in sixteenths; both whole numbers, read from the nearest cell without filtering. The window wraps in x and z
// (the texture repeats) and not in y.
//
// A light is in a block of 16^3 cells when the block was baked after it came (its epoch, lightBlockEpoch) and the old
// path leaves it out for the pixels in that block (lightHeld): so each light reaches each pixel once, whichever way
// it is done.

#include light_volume_config

uniform sampler3D lightVolume;
uniform int lightVolumeOn;          // 1 when there is a volume
uniform vec3 lightVolumeOrigin;     // the window's first cell
// the epoch each block was baked at, 0 for none, by LightVolume.slotOf; and the strength of every flicker slot now
// over when baked
uniform vec4 lightBlockEpoch[LIGHT_VOLUME_EPOCH_VECS];
uniform vec4 lightFlicker[LIGHT_VOLUME_FLICKER_VECS];

// surfaces look the volume up this far off along their normal, a little under a cell, so the lookup is in the air in
// front of them rather than in the wall they are
#define LIGHT_VOLUME_BIAS 20.0
// the share of the light a leaf sheet passes on, looking the way the light goes, averaged over the ways it can
#define LIGHT_VOLUME_FORWARD 0.5
// the sum of the six faces over the light that shines at a point from all around, on average (|x| + |y| + |z| of a
// direction)
#define LIGHT_VOLUME_FACES 1.5

// The epoch the block under a world position was baked at: 0 outside the window, where nothing is baked, or without a
// volume.
float lightBlockEpochAt(vec3 world) {
  if (lightVolumeOn == 0) {
    return 0.0;
  }
  vec3 rel = world * (1.0 / LIGHT_CELL) - lightVolumeOrigin;
  if (any(lessThan(rel, vec3(0.0))) || any(greaterThanEqual(rel, LIGHT_VOLUME_DIM))) {
    return 0.0;
  }
  ivec3 b = ivec3(floor(rel * (1.0 / LIGHT_BLOCK)));
  ivec3 o = ivec3(floor(lightVolumeOrigin * (1.0 / LIGHT_BLOCK) + 0.5));
  int i = (b.y * LIGHT_VOLUME_BLOCKS.z + ((o.z + b.z) & (LIGHT_VOLUME_BLOCKS.z - 1))) * LIGHT_VOLUME_BLOCKS.x
    + ((o.x + b.x) & (LIGHT_VOLUME_BLOCKS.x - 1));
  return lightBlockEpoch[i >> 2][i & 3];
}

// whether the volume has the light that came at this epoch (the third texel of its entry, 0 for none) in the block
// whose epoch is blockEpoch
bool lightHeld(float lightEpoch, float blockEpoch) {
  return lightEpoch > 0.0 && lightEpoch <= blockEpoch;
}

// the colour a tint's code is, its channels averaging 1
vec3 lightVolumeTint(float code) {
  float hue = floor(code * (1.0 / 16.0));
  float saturation = (code - hue * 16.0) * (1.0 / 15.0);
  hue *= 6.0 / 128.0;
  vec3 h = clamp(vec3(abs(hue - 3.0) - 1.0, 2.0 - abs(hue - 2.0), 2.0 - abs(hue - 4.0)), 0.0, 1.0);
  vec3 t = mix(vec3(1.0), h, saturation);
  return t / dot(t, vec3(1.0 / 3.0));
}

// how much a flicker's code swings the cell's light now: 1 for none
float lightVolumeSwing(float code) {
  if (code < 1.0) {
    return 1.0;
  }
  float slot = floor(code * (1.0 / 16.0));
  float share = (code - slot * 16.0) * (1.0 / 15.0);
  int s = int(slot);
  return 1.0 - share + share * lightFlicker[s >> 2][s & 3];
}

// Where in the texture a world position is, for the two halves: x and z repeat, y is the cell's height in the window
// kept inside it, so the filter reads no further than the cells of its half.
void lightVolumeCoords(vec3 world, out vec3 a, out vec3 b, out ivec3 nearest) {
  vec3 c = world * (1.0 / LIGHT_CELL);
  float y = clamp(c.y - lightVolumeOrigin.y, 0.5, LIGHT_VOLUME_DIM.y - 0.5);
  vec3 scale = vec3(1.0 / LIGHT_VOLUME_DIM.x, 0.5 / LIGHT_VOLUME_DIM.y, 1.0 / LIGHT_VOLUME_DIM.z);
  a = vec3(c.x, y, c.z) * scale;
  b = vec3(c.x, y + LIGHT_VOLUME_DIM.y, c.z) * scale;
  nearest = ivec3(int(mod(floor(c.x), LIGHT_VOLUME_DIM.x)), int(y), int(mod(floor(c.z), LIGHT_VOLUME_DIM.z)));
}

// The light of the baked lights on a surface facing n (unit, y down) at a world position in the window, as the
// lights shining on it add up.
vec3 lightVolumeLight(vec3 world, vec3 n) {
  vec3 ca, cb;
  ivec3 cell;
  lightVolumeCoords(world, ca, cb, cell);
  vec4 a = texture(lightVolume, ca);
  vec2 faceY = texture(lightVolume, cb).xy;
  vec2 cellCode = texelFetch(lightVolume, ivec3(cell.x, cell.y + int(LIGHT_VOLUME_DIM.y), cell.z), 0).zw;
  vec3 nn = n * n;
  float lum = nn.x * (n.x >= 0.0 ? a.x : a.y) + nn.y * (n.y >= 0.0 ? faceY.x : faceY.y)
    + nn.z * (n.z >= 0.0 ? a.z : a.w);
  return lightVolumeTint(cellCode.x) * (lum * lightVolumeSwing(cellCode.y));
}

// The light of the baked lights shining at a point from all sides, for what floats in the air (fog, rain, snow).
vec3 lightVolumeOmni(vec3 world) {
  if (lightBlockEpochAt(world) <= 0.0) {
    return vec3(0.0);
  }
  vec3 ca, cb;
  ivec3 cell;
  lightVolumeCoords(world, ca, cb, cell);
  vec4 a = texture(lightVolume, ca);
  vec2 faceY = texture(lightVolume, cb).xy;
  vec2 cellCode = texelFetch(lightVolume, ivec3(cell.x, cell.y + int(LIGHT_VOLUME_DIM.y), cell.z), 0).zw;
  float lum = (a.x + a.y + a.z + a.w + faceY.x + faceY.y) * (1.0 / LIGHT_VOLUME_FACES);
  return lightVolumeTint(cellCode.x) * (lum * lightVolumeSwing(cellCode.y));
}

// The same, with the direction most of the light comes from (unit, to the light; straight up where it comes from all
// around): for what reflects it, the water.
vec3 lightVolumeOmniDir(vec3 world, out vec3 dir) {
  dir = vec3(0.0, -1.0, 0.0);
  if (lightBlockEpochAt(world) <= 0.0) {
    return vec3(0.0);
  }
  vec3 ca, cb;
  ivec3 cell;
  lightVolumeCoords(world, ca, cb, cell);
  vec4 a = texture(lightVolume, ca);
  vec2 faceY = texture(lightVolume, cb).xy;
  vec2 cellCode = texelFetch(lightVolume, ivec3(cell.x, cell.y + int(LIGHT_VOLUME_DIM.y), cell.z), 0).zw;
  // the faces' difference along each axis is the light's direction weighted by its brightness
  vec3 d = vec3(a.x - a.y, faceY.x - faceY.y, a.z - a.w);
  float len = length(d);
  if (len > 1e-5) {
    dir = d / len;
  }
  float lum = (a.x + a.y + a.z + a.w + faceY.x + faceY.y) * (1.0 / LIGHT_VOLUME_FACES);
  return lightVolumeTint(cellCode.x) * (lum * lightVolumeSwing(cellCode.y));
}
