// The night sky, added to the sky as the composite draws it (atmosphere.glsl's celestial): layered stars of different
// sizes and colors that twinkle, a painted nebula and Milky Way band, shooting stars and, in the far north, an aurora
// (117 HD's).
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
// Aurora: 117 HD's (utils/aurora.glsl), ported as it is. Volumetric curtains raymarched through an elevated slab of
// sky. Each curtain is a thin vertical sheet: a folded ribbon in the horizontal plane, extruded upward and sheared as
// it rises. Marching the view ray through the slab, rather than intersecting a handful of flat planes, is what gives
// the curtains height: near parts of a sheet sweep past faster than far parts as the camera turns, the rays stay
// standing in the volume, and the colour ramps from green along the base to magenta at the tips. A function of the
// view direction and the time only: the slab's altitudes are above the camera, wherever it is.

// The slab the curtains live in: altitudes above the camera, in world units
#define AURORA_BASE 900.0
#define AURORA_TOP 4500.0
// samples taken per ray between base and top: the main quality/cost knob
#define AURORA_STEPS 20
// world units per unit of curtain coordinate: one fold spans roughly 7000
#define AURORA_SCALE 0.0005
// horizontal distance at which curtains are lost to haze, in world units
#define AURORA_VIEW_DISTANCE 24000.0
#define AURORA_INTENSITY 0.7
// Lumina's, for speed, with the same result: where along the curtain's cross coordinate (v, north) a sheet can be at
// all. auroraCurtain lies in -0.83..1.23 and the lean with height (auroraSheet) adds -0.11..0.21, and a sheet adds
// nothing (density below 1e-3, at most 0.861 exp(-d^2 / 2 sigma^2)) further than 3.68 sigma from its centre, so a step
// further than that from the band is skipped without working out either sheet: most steps of most rays, all of those
// looking north low down (the curtains hang overhead, and come down to the horizon toward the east and west).
#define AURORA_BAND vec2(-0.94, 1.44)
#define AURORA_BAND_SIGMAS 3.7

// 117 HD's hash12 (utils/hash.glsl), for its noise
float auHash12(vec2 p) {
  vec3 p3 = fract(vec3(p.xyx) * 0.1031);
  p3 += dot(p3, p3.yzx + 33.33);
  return fract((p3.x + p3.y) * p3.z);
}

float auNoise(vec2 p) {
  vec2 i = floor(p);
  vec2 f = fract(p);
  f = f * f * (3.0 - 2.0 * f);
  return mix(
    mix(auHash12(i), auHash12(i + vec2(1.0, 0.0)), f.x),
    mix(auHash12(i + vec2(0.0, 1.0)), auHash12(i + vec2(1.0, 1.0)), f.x),
    f.y);
}

// the curtain's ridge at a horizontal position
float auroraCurtain(float x, float time) {
  float wave = sin(x * 1.8 - time * 0.4) * 0.5;
  wave += sin(x * 3.1 + time * 0.25) * 0.25;
  wave += sin(x * 7.0 - time * 0.6) * 0.08;
  wave += auNoise(vec2(x * 0.8 + time * 0.05, time * 0.03)) * 0.4;
  return wave;
}

// Emissive colour of one curtain sheet at a point in the slab.
//   h          height fraction through the slab, 0 at the base
//   sigmaFloor minimum sheet half-width, set from the march footprint so that distant curtains prefilter instead of
//              sparkling
//   detail     how much fine ray structure survives at that footprint
vec3 auroraSheet(vec3 p, float h, float sigmaFloor, float detail, float time, float seed) {
  float u = p.x * AURORA_SCALE + seed * 3.7;
  float v = p.z * AURORA_SCALE;

  // Lumina's, for speed, with the same result: the sheet's centre is the curtain plus a lean of -0.11..0.21 (below), so
  // a point further than AURORA_BAND_SIGMAS of the widest sheet from that has no sheet, whatever its reach
  float curtain = auroraCurtain(u, time + seed * 5.0);
  float dc = v - curtain;
  float reject = AURORA_BAND_SIGMAS * max(0.075, sigmaFloor);
  if (dc < -0.11 - reject || dc > 0.21 + reject) {
    return vec3(0.0);
  }

  // Curtains do not all reach the same height. Varying the reach along the ribbon is most of what sells them as
  // separate sheets hanging in the sky.
  float reach = mix(0.45, 1.3, auNoise(vec2(u * 1.1 + seed * 7.0, time * 0.03 + seed)));
  float hn = h / reach; // 0 at the base, 1 at this curtain's tip
  if (hn >= 1.0) {
    return vec3(0.0);
  }

  // sharp lower border, long fade toward the tips
  float vProfile = smoothstep(0.0, 0.10, hn) * exp(-hn * 1.5) * (1.0 - smoothstep(0.7, 1.0, hn));

  // Sheets thicken and diffuse as they rise. The peak scales with 1/sigma so a widened (prefiltered) sheet emits as
  // much light as a crisp one rather than getting brighter.
  float sigma = max(mix(0.030, 0.075, hn), sigmaFloor);
  // the distance from the centre of the sheet (117 HD's auroraRibbon, with the curtain worked out above). The shear
  // with height keeps the sheet from being a straight vertical extrusion, so it still reads as a surface in depth when
  // seen close to edge-on.
  float lean = 0.05 + 0.16 * sin(u * 2.3 - time * 0.18 + seed);
  float d = dc - lean * hn;
  float sheet = exp(-d * d / (2.0 * sigma * sigma)) * (0.030 / sigma);

  float density = sheet * vProfile;
  if (density < 1e-3) {
    return vec3(0.0);
  }

  // Vertical rays. Constant along a curtain's height, so they read as structure standing in the volume instead of
  // texture on a plane.
  float rays = auNoise(vec2(u * 9.0 + seed * 10.0, time * 0.1));
  rays = mix(0.45, 1.0, smoothstep(0.15, 0.85, rays));
  float fine = auNoise(vec2(u * 30.0 - time * 0.25, seed * 3.0 + hn * 0.35));
  rays *= mix(1.0, mix(0.6, 1.2, fine), detail);

  float brightness = 0.4 + 0.6 * auNoise(vec2(u * 1.7 - time * 0.02, seed));

  // Slowly vary the emissive green base between teal and yellow-green, and ramp to magenta at the tips the way oxygen
  // gives way to nitrogen.
  float hueNoise = auNoise(vec2(u * 1.3 + seed * 4.0, time * 0.04 + seed));
  vec3 tealGreen = vec3(0.0, 1.5, 0.9);   // cool cyan-green
  vec3 warmGreen = vec3(0.35, 1.7, 0.25); // warm yellow-green
  vec3 green = mix(tealGreen, warmGreen, smoothstep(0.25, 0.75, hueNoise));
  vec3 magenta = vec3(1.0, 0.15, 1.25);
  vec3 col = mix(green, magenta, smoothstep(0.3, 0.95, hn));

  return col * density * rays * brightness;
}

// Additive aurora colour along viewDir (y down, z north), linear.
vec3 proceduralAurora(vec3 viewDir, float time) {
  float upAmount = -viewDir.y;
  if (upAmount < 0.01) {
    return vec3(0.0);
  }

  // north-facing gate: aurora in the northern sky (+z)
  float northFacing = viewDir.z;
  if (northFacing < -0.2) {
    return vec3(0.0);
  }
  float northBlend = smoothstep(-0.2, 0.3, northFacing);

  float horizonFade = smoothstep(0.01, 0.06, upAmount);

  vec3 rd = normalize(viewDir);
  float up = max(-rd.y, 1e-3);

  // enter the slab at its base and step up through it in equal heights
  float tBase = AURORA_BASE / up;
  float dt = (AURORA_TOP - AURORA_BASE) / (up * float(AURORA_STEPS));

  // Horizontal footprint of one step, along the curtain coordinate. Sheets and ray detail are filtered against it, so
  // the long steps a grazing ray takes blur the curtains rather than aliasing them into sparkle.
  float stepU = dt * length(rd.xz) * AURORA_SCALE;
  float sigmaFloor = stepU * 0.5;
  float detail = 1.0 - smoothstep(0.03, 0.10, stepU);

  // offset the march per pixel so a short march does not band
  float jitter = auHash12(gl_FragCoord.xy + time);

  // the band the sheets can be in, widened by the widest a sheet gets
  vec2 band = AURORA_BAND + vec2(-1.0, 1.0) * (AURORA_BAND_SIGMAS * max(0.075, sigmaFloor));

  vec3 aurora = vec3(0.0);
  for (int i = 0; i < AURORA_STEPS; i++) {
    vec3 p = rd * (tBase + (float(i) + jitter) * dt);
    float v = p.z * AURORA_SCALE;
    if (v < band.x || v > band.y) {
      // v only moves one way along the ray: past the band it never comes back
      if (v > band.y == rd.z > 0.0) {
        break;
      }
      continue;
    }
    float h = (-p.y - AURORA_BASE) / (AURORA_TOP - AURORA_BASE);

    // Haze swallows the far ends of the curtains, leaving the bright lower border arcing across the northern sky.
    float r = length(p.xz) / AURORA_VIEW_DISTANCE;
    float haze = exp(-r * r);
    if (haze < 0.01) {
      break; // only ever gets further away
    }

    aurora += auroraSheet(p, h, sigmaFloor, detail, time, 1.0) * haze;
    aurora += auroraSheet(p, h, sigmaFloor, detail, time, 2.6) * haze * 0.7;
  }

  // Weight by the path length each step covers, capped so curtains brighten toward the horizon without running away
  // as the ray goes flat.
  aurora *= min(1.0 / up, 6.0) / float(AURORA_STEPS);

  return aurora * northBlend * horizonFade * AURORA_INTENSITY;
}

// 117 HD dims the aurora against a bright sky, 1 / (1 + 1200 Y) of the sky behind it (sky_sampling.glsl): auroras
// lose contrast much sooner than the moon. Lumina's clear night sky is about 16 times brighter (zenith Y 0.004 against
// 117 HD's 0.00024), so the constant is 16 times smaller: Lumina's clear night dims it as 117 HD's does (by about a
// quarter), and dusk, moonlight and haze take it away as they do there.
#define AURORA_CONTRAST 72.0

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
// (overcast, haze); T: the clouds' transmittance; moonDir: toward the moon; skyY: the luminance of the sky behind
// (linear, without the night sky).
vec3 nightSky(vec3 dir, vec3 moonDir, float alt, float time, float pixelAngle, float visible, float T, float skyY) {
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
  // 117 HD's: its darkness (sun 6 to 18 degrees down), the contrast against the sky, and in front of the moon but
  // behind the haze (sky fog there) and clouds
  float aur = auroraStrength * smoothstep(-6.0, -18.0, alt) * visible * T / (1.0 + skyY * AURORA_CONTRAST);
  if (aur > 0.001) {
    c += proceduralAurora(dir, time) * aur;
  }
  return c;
}
