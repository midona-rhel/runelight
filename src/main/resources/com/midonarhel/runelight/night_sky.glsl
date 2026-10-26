// The night sky, added to the sky as the composite draws it (atmosphere.glsl's celestial): layered stars of different
// sizes and colors that twinkle, a painted nebula and Milky Way band, shooting stars and, in the far north, an aurora.
// Everything is a function of the view direction (and the time, for the twinkle), so nothing shimmers when the camera
// moves, and the stars and nebula turn slowly with the night (NightSky.java). Linear colors. Needs sky.glsl.
// None of it goes through the sky cache: that only feeds the light, and these move.

uniform mat3 starFrame;       // view direction -> the fixed stars' frame, which turns with the night
uniform float starBrightness; // the setting, 0..2
uniform float nebulaStrength; // the setting, 0..2: the nebula and the Milky Way
uniform float auroraStrength; // 0..1: how strong tonight's aurora is where the player is (0 almost everywhere)
uniform vec4 meteorHead;      // the shooting star in flight: direction of its head, and how bright (0: none)
uniform vec3 meteorTail;      // direction of its tail
uniform float starPixel;      // the angle one pixel covers, radians, in the middle of the screen

#define NIGHT_TAU 6.28318530718

// Stars: layers of one star at most per cell of a grid over the sky. Each is a soft dot at least about a pixel wide
// (so it holds still as the camera moves, at any render scale) whose brightness falls off as a power: most are faint.
// cells: grid size, chance: per cell, size: radius in radians, peak: brightest, color: share of the pastel hues, glint:
// share of the cross of light on the brightest, swell: depth of the twinkle
#define STAR_PIXELS 0.9
// a cell of a layer's grid is at least this many pixels across: a star has to fit in its cell, so the grid gets
// coarser (and the stars in it as many) at low render scales rather than clipping them
#define STAR_CELL_PIXELS 6.0
#define STAR_FAINT 2.0
#define STAR_HORIZON 0.2
// stars are this much more common (times 1 + this) in the Milky Way's core
#define STAR_BAND_DENSITY 5.0
#define STAR_MOON_WASH 0.6

// the Milky Way: a band around this pole (in the stars' frame), its width (sine) and the nebula's brightness
#define MILKY_POLE normalize(vec3(0.35, -0.55, 0.62))
#define MILKY_WIDTH 0.2
#define NEBULA_BRIGHTNESS 0.065
#define MILKY_BRIGHTNESS 0.05
#define NEBULA_MOON_WASH 0.65

float milkyDensity(vec3 d) {
  float b = dot(d, MILKY_POLE) / MILKY_WIDTH;
  return exp(-b * b);
}

vec3 starTint(vec3 cell, float color) {
  float h = hash13(cell + 9.1);
  vec3 plain = mix(vec3(0.78, 0.85, 1.0), vec3(1.0, 0.92, 0.80), h);
  vec3 hue = 0.5 + 0.5 * cos(NIGHT_TAU * (hash13(cell + 11.3) + vec3(0.0, 0.33, 0.67)));
  return mix(plain, mix(vec3(1.0), hue, 0.7), color);
}

vec3 starLayer(vec3 d, float cells, float chance, float size, float peak, float color, float glint, float swell,
  float pixelAngle, float time, float wash) {
  float grid = min(cells, exp2(floor(log2(1.0 / (starPixel * STAR_CELL_PIXELS)))));
  chance = min(chance * (cells * cells) / (grid * grid), 1.0);
  cells = grid;
  vec3 q = d * cells;
  vec3 cell = floor(q);
  float h = hash13(cell);
  // thicker in the Milky Way's core, by the density at the cell's middle so the stars of a cell never come and go
  float band = milkyDensity(normalize(cell + 0.5));
  chance *= 1.0 + STAR_BAND_DENSITY * band * min(nebulaStrength, 1.0);
  if (h < 1.0 - chance) {
    return vec3(0.0);
  }
  vec3 f = q - cell;
  vec3 e = min(f, 1.0 - f);
  // the star is only drawn in its own cell: fade it out toward the cell's faces instead of cutting it off
  float inside = smoothstep(0.0, 0.2, min(e.x, min(e.y, e.z)));
  vec3 center = cell + 0.3 + 0.4 * vec3(hash13(cell + 1.7), hash13(cell + 3.1), hash13(cell + 5.9));
  vec3 off = q - center;
  off -= d * dot(off, d);
  float sigma = max(pixelAngle * STAR_PIXELS, size) * cells;
  float u = (h - (1.0 - chance)) / chance;
  float bright = 0.25 + 0.75 * pow(u, STAR_FAINT);
  float t = hash13(cell + 7.3);
  // a slow swell and, now and then, a quick flicker
  float twinkle = 1.0 + swell * (0.6 * sin(time * (0.9 + 2.4 * t) + t * 40.0)
    + 0.4 * sin(time * (6.0 + 5.0 * t) + t * 57.0) * max(sin(time * (0.5 + t) + t * 13.0), 0.0));
  float r2 = dot(off, off) / (sigma * sigma);
  float shape = exp(-r2);
  if (glint > 0.0) {
    // a soft halo and a cross of light on the brightest stars
    vec3 a = normalize(cross(d, vec3(0.31, 0.93, 0.19)));
    vec3 b = cross(d, a);
    vec2 p = vec2(dot(off, a), dot(off, b)) / sigma;
    float cross4 = exp(-abs(p.x) * 0.5 - p.y * p.y * 3.0) + exp(-abs(p.y) * 0.5 - p.x * p.x * 3.0);
    shape += (0.12 * exp(-r2 * 0.08) + 0.35 * cross4 * glint * smoothstep(0.5, 1.0, u)) * smoothstep(0.0, 0.4, u);
  }
  return starTint(cell, color) * (bright * twinkle * shape * inside * peak * (1.0 - wash * (1.0 - bright)));
}

// ---------------------------------------------------------------------------------------------------------------
// Nebula and Milky Way: smooth fields from a few sines folded into each other, which reads as paint and costs a
// handful of sines rather than a lattice of hashes

float swirl(vec3 p) {
  p += 0.9 * sin(p.zxy * 1.37 + vec3(1.7, 4.1, 2.3));
  return 0.5 + 0.5 * sin(p.x * 1.6 + 1.3 * sin(p.y * 1.9 + 0.7 * sin(p.z * 2.3)));
}

// The nebula and the Milky Way. The parts that most of the sky has none of come after cheap tests for them.
vec3 nebulaColor(vec3 d, float amount) {
  vec3 col = vec3(0.0);
  // broad clouds of it, soft at the edges
  float region = swirl(d * 1.9 + vec3(3.0));
  // the finer swirl moves the edge by at most 0.15: past that there is none
  if (region > 0.22) {
    region = smoothstep(0.38, 0.95, region + 0.3 * (swirl(d * 4.1 + vec3(9.0)) - 0.5));
  } else {
    region = 0.0;
  }
  if (region > 0.0) {
    // filaments: the ridges of a field, where it crosses its middle
    float ridge = 1.0 - abs(2.0 * swirl(d * 6.7 + vec3(60.0)) - 1.0);
    float wisps = ridge * ridge * ridge;
    float tone = swirl(d * 3.1 + vec3(80.0));
    vec3 teal = vec3(0.10, 0.62, 0.70);
    vec3 violet = vec3(0.45, 0.20, 0.85);
    vec3 rose = vec3(0.90, 0.22, 0.55);
    vec3 hue = mix(mix(teal, violet, smoothstep(0.25, 0.55, tone)), rose, smoothstep(0.55, 0.85, tone));
    col += hue * (region * (0.3 + 0.9 * wisps) * NEBULA_BRIGHTNESS);
  }
  // the Milky Way: a band of mottled light with a dark lane through it
  float band = milkyDensity(d);
  if (band > 0.02) {
    float mottle = swirl(d * 5.3 + vec3(20.0));
    float lane = smoothstep(0.55, 0.85, swirl(d * 8.7 + vec3(40.0))) * band;
    float milky = band * (0.3 + 0.9 * mottle) * (1.0 - 0.7 * lane);
    col += mix(vec3(0.38, 0.50, 1.0), vec3(1.0, 0.72, 0.85), band * band) * (milky * MILKY_BRIGHTNESS);
  }
  return col * amount;
}

// ---------------------------------------------------------------------------------------------------------------
// Aurora: two rippling curtains standing on the northern horizon (+z), green at the foot and violet at the tips, with
// rays that sway. A function of azimuth and elevation, so it costs a few sines a pixel.

#define AURORA_BRIGHTNESS 0.12

vec3 auroraCurtain(float az, float elev, float time, float seed) {
  float t = time * 0.06;
  // the foot of the curtain ripples along the horizon
  float foot = 0.10 + 0.05 * seed + 0.035 * sin(az * 2.3 + t * 1.3 + seed * 5.0) + 0.02 * sin(az * 6.1 - t * 2.1 + seed)
    + 0.012 * sin(az * 13.0 + t * 3.0);
  float h = (elev - foot) / 0.26;
  if (h < -0.15) {
    return vec3(0.0);
  }
  float edge = smoothstep(-0.15, 0.04, h);
  // rays stand up from the foot and thin out as they climb; the foot itself is an even glow
  float r1 = 0.5 + 0.5 * sin(az * 30.0 + 2.0 * sin(az * 3.7 + t * 3.0 + seed) + t * 4.0 + seed * 9.0);
  float r2 = 0.5 + 0.5 * sin(az * 67.0 + 3.0 * sin(az * 5.3 - t * 2.0) + seed * 4.0);
  float rays = r1 * r1 * (0.55 + 0.45 * r2) * 1.7;
  float body = edge * exp(-max(h, 0.0) * 1.8) * mix(0.9, rays, smoothstep(0.0, 0.7, h));
  float swell = 0.7 + 0.3 * sin(az * 3.1 - t * 4.0 + seed * 3.0);
  vec3 col = mix(vec3(0.12, 1.0, 0.45), vec3(0.10, 0.75, 0.95), smoothstep(0.0, 0.5, h));
  col = mix(col, vec3(0.70, 0.25, 0.95), smoothstep(0.4, 1.2, h));
  return col * (body * swell);
}

vec3 aurora(vec3 d, float time) {
  float elev = -d.y;
  if (elev > 0.7) {
    return vec3(0.0);
  }
  float az = atan(d.x, d.z);
  float north = smoothstep(2.4, 0.9, abs(az));
  if (north <= 0.0) {
    return vec3(0.0);
  }
  vec3 col = auroraCurtain(az, elev, time, 0.0) + 0.6 * auroraCurtain(az + 1.7, elev, time, 1.0);
  return col * (north * smoothstep(0.0, 0.08, elev) * AURORA_BRIGHTNESS);
}

// ---------------------------------------------------------------------------------------------------------------
// Shooting star: the streak between the tail and the head, brightest at the head

vec3 shootingStar(vec3 d, float pixelAngle) {
  vec3 seg = meteorHead.xyz - meteorTail;
  float len2 = max(dot(seg, seg), 1e-8);
  float along = clamp(dot(d - meteorTail, seg) / len2, 0.0, 1.0);
  vec3 off = d - (meteorTail + seg * along);
  float width = max(pixelAngle * 1.2, 0.0012);
  float line = exp(-dot(off, off) / (width * width));
  return vec3(1.0, 0.89, 0.62) * (line * along * along * meteorHead.w * 4.0);
}

// ---------------------------------------------------------------------------------------------------------------

// The night sky along dir (a view ray, y down), linear. alt: sun altitude, degrees; visible: how much of the sky shows
// (overcast, haze); T: the clouds' transmittance; moonDir: toward the moon.
vec3 nightSky(vec3 dir, vec3 moonDir, float alt, float time, float pixelAngle, float visible, float T) {
  float elev = -dir.y;
  float dark = smoothstep(-8.0, -18.0, alt) * visible * T;
  vec3 c = vec3(0.0);
  if (dark <= 0.0 || elev <= 0.0) {
    return c;
  }
  float horizon = smoothstep(0.0, STAR_HORIZON, elev);
  // the moon washes out the faint, near it and in general
  float moonUp = smoothstep(0.0, 0.5, -moonDir.y);
  float mm = max(dot(dir, moonDir), 0.0);
  float moonNear = 1.0 - 0.9 * smoothstep(0.9, 0.998, mm);
  vec3 d = starFrame * dir;

  float stars = starBrightness * dark * horizon * moonNear;
  if (stars > 0.0) {
    float wash = STAR_MOON_WASH * moonUp;
    c += starLayer(d, 320.0, 0.0011, 0.0004, 0.9, 0.0, 0.0, 0.25, pixelAngle, time, wash) * stars;
    c += starLayer(d, 110.0, 0.0045, 0.0009, 1.1, 0.5, 0.0, 0.30, pixelAngle, time, wash) * stars;
    c += starLayer(d, 36.0, 0.012, 0.0016, 1.8, 0.75, 1.0, 0.35, pixelAngle, time, wash) * stars;
    if (meteorHead.w > 0.0) {
      c += shootingStar(dir, pixelAngle) * (min(starBrightness, 1.0) * dark * horizon);
    }
  }

  float nebula = nebulaStrength * smoothstep(-11.0, -22.0, alt) * visible * T * horizon * moonNear
    * (1.0 - NEBULA_MOON_WASH * moonUp);
  if (nebula > 0.001) {
    c += nebulaColor(d, nebula);
  }
  float aur = auroraStrength * smoothstep(-6.0, -16.0, alt) * visible * T;
  if (aur > 0.0) {
    c += aurora(dir, time) * aur;
  }
  return c;
}
