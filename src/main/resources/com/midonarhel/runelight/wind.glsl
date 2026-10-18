// Plants swaying in the wind, after 117 HD's wind displacement. Swaying model vertices carry a code in the fourth
// position short (see Wind.java): a bend weight (world units of height above the part that stays put), a flutter
// level, the object's phase, and a jiggle flag for ivy. Everything else reads 0, -1 (ground) or 1 (dynamic models)
// there, which all mean still.

uniform vec2 windDir;       // direction the wind blows toward, x and z, normalized
uniform float windStrength; // 0 calm to 1 storm
uniform float windGust;     // 0 to 1
uniform float windTime;     // accumulated phase: runs faster in stronger wind
uniform float windSway;     // amplitude multiplier from the config, 0 holds still

#define WIND_MAX_HEIGHT 4096.0   // Wind.MAX_HEIGHT
// Bend, as a slope (world units sideways per unit of height):
#define WIND_LEAN 0.035          // steady lean downwind at full strength (grows with strength squared)
#define WIND_GUST_LEAN 0.03      // extra lean in a full gust
#define WIND_SWAY_CALM 0.004     // rocking in still air
#define WIND_SWAY 0.022          // rocking added at full strength
#define WIND_SWAY_GUST 0.016     // and in a full gust
#define WIND_SIDE 0.35           // sideways rocking relative to downwind rocking
#define WIND_SWAY_FREQ 1.6       // radians per unit of windTime
#define WIND_WAVE 0.0012         // radians per world unit: sway and gusts roll across a field downwind
#define WIND_PHASE 0.3           // how much each object's own phase offsets it from the rolling wave
// Small plants bend further for their size: below this height (world units) the bend grows with its square root.
#define WIND_SMALL 256.0
// Flutter, as world units per unit of (bend-adjusted) height at flutter level 3:
#define WIND_FLUTTER_CALM 0.004
#define WIND_FLUTTER 0.014
#define WIND_FLUTTER_GUST 0.01
#define WIND_FLUTTER_FREQ 7.0
// How much a bend tilts the normal (1 follows the sheared surface exactly)
#define WIND_NORMAL_TILT 1.0

// Displacement in world units (y down) for a vertex at worldPos with the given code. Tilts normal (world space,
// left alone when hasNormal is false) with the bend.
vec3 windDisplacement(float code, vec3 worldPos, inout vec3 normal, bool hasNormal) {
  int c = int(code);
  // below 512 the bend is 0: still, which also covers the ground's -1
  if (c < 512 || windSway <= 0.0)
    return vec3(0.0);
  int b = c >> 9 & 63;

  float h = float(b) / 63.0;
  h = h * h * WIND_MAX_HEIGHT;
  float hk = h < WIND_SMALL ? sqrt(WIND_SMALL * h) : h;
  float phase = float(c & 15) * (6.2831853 / 16.0);
  float flutter = float(c >> 4 & 3) / 3.0;
  bool jiggle = (c & 64) != 0;

  float s = windStrength;
  float travel = dot(worldPos.xz, windDir) * WIND_WAVE;
  float gust = windGust * (0.6 + 0.4 * sin(windTime * 0.45 - travel * 0.5 + 1.3));
  vec2 side = vec2(-windDir.y, windDir.x);
  vec3 d = vec3(0.0);

  if (!jiggle) {
    float a = windTime * WIND_SWAY_FREQ - travel + phase * WIND_PHASE;
    float rock = sin(a) + 0.35 * sin(2.1 * a + phase);
    float lean = WIND_LEAN * s * s + WIND_GUST_LEAN * gust;
    float amp = WIND_SWAY_CALM + WIND_SWAY * s + WIND_SWAY_GUST * gust;
    vec2 slope = (windDir * (lean + amp * rock) + side * (amp * WIND_SIDE * sin(0.73 * a + phase))) * windSway;
    d.xz = slope * hk;
    // a bent stem keeps its length, so its top comes down a little
    d.y = 0.5 * dot(slope, slope) * hk;
    if (hasNormal) {
      normal.y += dot(slope, normal.xz) * WIND_NORMAL_TILT;
      normal = normalize(normal);
    }
  }

  if (flutter > 0.0) {
    // the phase follows the position, so vertices shared between faces stay together
    float fp = dot(worldPos, vec3(0.071, 0.053, 0.089));
    float fa = windTime * WIND_FLUTTER_FREQ + fp;
    float amp = (WIND_FLUTTER_CALM + WIND_FLUTTER * s + WIND_FLUTTER_GUST * gust) * flutter * hk * windSway;
    vec3 dir = vec3(windDir.x, 0.0, windDir.y);
    if (jiggle && hasNormal && dot(normal.xz, normal.xz) > 0.01)
      dir = normalize(vec3(-normal.z, 0.0, normal.x)); // along the wall, not into it
    d += (dir * sin(fa) + vec3(0.0, 0.4 * sin(1.37 * fa + 1.7 * fp), 0.0)) * amp;
  }
  return d;
}
