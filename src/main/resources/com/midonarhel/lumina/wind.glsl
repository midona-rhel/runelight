// The wind (Wind.java): for anything that sways, drifts or slants with it. Set on a program by Wind.apply.
// World positions are scene local units (128 per tile), x east, z north.

uniform vec2 windDir;          // direction the wind blows toward, normalized (x, z)
uniform float windStrength;    // steady strength without gusts: 0 calm, 1 gale, above 1 stronger
uniform float windGust;        // gust at the player, 0..1; windGustAt gives it anywhere
uniform float windTime;        // accumulated phase, seconds-like: advances faster in stronger wind, never jumps
uniform float windFront;       // world units the gust fronts have travelled along windDir
uniform float windGustiness;   // 0 steady .. 1 squally: the strongest gust there can be

// Must match Wind.java
#define WIND_GUST_LENGTH 2400.0
#define WIND_GUST_WIDTH 7000.0
#define WIND_GUST_PERIOD 256.0
#define WIND_GUST_LOW 0.5
#define WIND_GUST_HIGH 0.85

float windHash(float p) {
  p = fract(p * 0.1031);
  p *= p + 33.33;
  p *= p + p;
  return fract(p);
}

// periodic 1D value noise, quintic fade
float windNoise(float x, float period) {
  float i = floor(x);
  float f = x - i;
  float u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);
  return mix(windHash(mod(i, period)), windHash(mod(i + 1.0, period)), u);
}

// Gust 0..1 at a world xz: bands across the wind that travel with it, broken up along their length, so a gust
// sweeps over the land instead of everything gusting at once
float windGustAt(vec2 worldXZ) {
  if (windGustiness <= 0.0) {
    return 0.0;
  }
  float along = (dot(worldXZ, windDir) - windFront) / WIND_GUST_LENGTH;
  float across = (worldXZ.y * windDir.x - worldXZ.x * windDir.y) / WIND_GUST_WIDTH;
  float n = windNoise(along, WIND_GUST_PERIOD);
  float m = windNoise(across + 37.0, 1048576.0);
  return windGustiness * smoothstep(WIND_GUST_LOW, WIND_GUST_HIGH, n) * (0.5 + 0.5 * m);
}

// Total wind at a world xz: the steady strength plus the gust passing there
float windAt(vec2 worldXZ) {
  return windStrength * (1.0 + windGustAt(worldXZ));
}
