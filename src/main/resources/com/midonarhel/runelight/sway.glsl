// Plants swaying in the wind, after 117 HD's wind displacement. Swaying model vertices carry a code in the fourth
// position short (see WindSway.java): a bend weight (world units the vertex moves per unit of bend: 0 at the base,
// growing up the object along its bend curve), the object's anchor tile modulo 8, and a jiggle flag for ivy.
// Everything else reads 0, -1 (ground) or 1 (dynamic models) there, which all mean still.
//
// Each object moves as one body: its phase, gust and the rolling wave all come from its anchor tile, never from the
// vertex, so every vertex of an object bends the same way at the same time, only by its own weight. Vertices shared
// between faces have the same code and position, so they stay together.

#include "wind.glsl"

uniform float windSway;     // amplitude multiplier from the config, 0 holds still

#define WIND_MAX_HEIGHT 2048.0   // WindSway.MAX_HEIGHT
#define WIND_ANCHOR_MOD 8.0      // WindSway.ANCHOR_MOD
// Bend, as a slope (world units sideways per unit of bend weight):
#define WIND_LEAN 0.035          // steady lean downwind at full strength (grows with strength squared)
#define WIND_GUST_LEAN 0.04      // extra lean in a full gust
#define WIND_SWAY_CALM 0.004     // rocking in still air: barely there
#define WIND_SWAY 0.02           // rocking added at full strength
#define WIND_SWAY_GUST 0.018     // and in a full gust
#define WIND_SIDE 0.3            // sideways rocking relative to downwind rocking
#define WIND_SWAY_FREQ 1.2       // radians per unit of windTime: one slow rock every few seconds
#define WIND_WAVE 0.0008         // radians per world unit: the sway rolls across a field downwind
#define WIND_PHASE 0.6           // how far each object's own phase offsets it from the rolling wave (radians / 2pi)
// leaves: a quicker, small rock of the whole object on top of the slow one (it follows the bend weight, so the top
// moves and the base doesn't)
#define WIND_LEAF 0.12           // relative to the rocking
#define WIND_LEAF_FREQ 2.7
// ivy on walls: a small rustle up and down, which stays along any wall (relative to the rocking)
#define WIND_JIGGLE 0.1
// How much a bend tilts the normal (1 follows a straight shear)
#define WIND_NORMAL_TILT 1.0

// Displacement in world units (y down) for a vertex at worldPos with the given code. Tilts normal (world space,
// left alone when hasNormal is false) with the bend.
vec3 windDisplacement(float code, vec3 worldPos, inout vec3 normal, bool hasNormal) {
  int c = int(code);
  // below 128 the bend is 0: still, which also covers the ground's -1 and dynamic models' 1
  if (c < 128 || windSway <= 0.0)
    return vec3(0.0);
  float b = float(c >> 7 & 255) / 255.0;
  float w = b * b * WIND_MAX_HEIGHT;
  bool jiggle = (c & 64) != 0;

  // the object's anchor tile: the one congruent to the stored residue nearest this vertex (good within 4 tiles)
  vec2 residue = vec2(float(c & 7), float(c >> 3 & 7));
  vec2 k = worldPos.xz / 128.0 - 0.5;
  vec2 anchor = residue + WIND_ANCHOR_MOD * floor((k - residue) / WIND_ANCHOR_MOD + 0.5);
  vec2 anchorXZ = (anchor + 0.5) * 128.0;
  float phase = windHash(dot(anchor, vec2(1.0, 113.0)) + 17.0) * 6.2831853;

  float s = min(windStrength, 1.5);
  float gust = windGustAt(anchorXZ);
  float a = windTime * WIND_SWAY_FREQ - dot(anchorXZ, windDir) * WIND_WAVE + phase * WIND_PHASE;
  float amp = (WIND_SWAY_CALM + WIND_SWAY * s + WIND_SWAY_GUST * gust) * windSway;

  if (jiggle) {
    return vec3(0.0, sin(WIND_LEAF_FREQ * a + phase) * amp * WIND_JIGGLE * w, 0.0);
  }

  float rock = sin(a) + 0.3 * sin(1.7 * a + phase) + WIND_LEAF * sin(WIND_LEAF_FREQ * a + 2.0 * phase);
  float lean = (WIND_LEAN * s * s + WIND_GUST_LEAN * gust) * windSway;
  vec2 side = vec2(-windDir.y, windDir.x);
  vec2 slope = windDir * (lean + amp * rock) + side * (amp * WIND_SIDE * sin(0.61 * a + phase));
  vec3 d;
  d.xz = slope * w;
  // a bent stem keeps its length, so its top comes down a little
  d.y = 0.5 * dot(slope, slope) * w;
  if (hasNormal) {
    normal.y += dot(slope, normal.xz) * WIND_NORMAL_TILT;
    normal = normalize(normal);
  }
  return d;
}
