// Water surfaces, drawn by the composite over the lowered water bed (WaterMap.java, Water.java).
//
// The bed and anything else under the water are ordinary pixels of the scene buffers. For those, this finds where
// the view ray crosses the water surface (heights from the water data, plus the swell), then shades what is seen
// through it (refracted, absorbed and tinted by depth, caustics on the bed) and puts the surface on top: sky and
// rough world reflections, sun and lamp glints, ripples that move with the flow map, shoreline foam, rain rings.
// Included by composite_frag.glsl after its lighting helpers (shadows, point light clusters, ign).

uniform int waterEnabled;
uniform sampler2D waterData;   // see WaterMap.texture: page 0 (height, flow x, flow z, type), page 1 (shore, energy)
uniform sampler2D waterNoise;  // tiling: rg ripple slope, b ripple height, a foam cells
uniform vec2 waterGrid;        // x: extended tile of local tile 0, y: corners per side
uniform float waterTime;       // seconds, wraps
uniform vec3 waterWind;        // xy: direction the wind blows toward (x, z), z: strength 0-1
uniform float waterRain;       // 0-1
uniform int waterReflections;  // screen-space world reflections
uniform float waterClarity;    // multiplier on how far light gets through
uniform float waterWaves;      // multiplier on wave and ripple height
uniform float waterSpeed;      // multiplier on flow
uniform int waterCaustics;
uniform int waterDebug;

#define WATER_TILE 128.0
// waterTime wraps after this many seconds (Water.java); every motion below repeats a whole number of times in it
#define WATER_TIME_WRAP 3840.0

// Water types (WaterMap.java): none, fresh, ocean, dark ocean, pale ocean, swamp, cave
#define WATER_TYPES 7
// extinction per tile of water the light passes, linear rgb: red goes first, murky water swallows everything
const vec3 WATER_EXTINCTION[WATER_TYPES] = vec3[](
  vec3(1.0),
  vec3(0.36, 0.15, 0.11),
  vec3(0.50, 0.19, 0.13),
  vec3(0.60, 0.32, 0.26),
  vec3(0.30, 0.12, 0.10),
  vec3(2.40, 1.80, 2.80),
  vec3(1.50, 1.00, 0.90));
// the color deep water takes on (sRGB), lit by the light falling on the water
const vec3 WATER_COLOR[WATER_TYPES] = vec3[](
  vec3(0.0),
  vec3(0.11, 0.33, 0.36),
  vec3(0.07, 0.29, 0.40),
  vec3(0.10, 0.20, 0.26),
  vec3(0.28, 0.50, 0.58),
  vec3(0.17, 0.21, 0.09),
  vec3(0.06, 0.15, 0.16));
// reflectivity, ripple strength, flow speed, foam
const vec4 WATER_SURFACE[WATER_TYPES] = vec4[](
  vec4(0.0),
  vec4(1.00, 0.80, 1.00, 0.70),
  vec4(1.00, 1.00, 1.00, 1.00),
  vec4(0.95, 1.00, 1.00, 0.90),
  vec4(1.00, 0.80, 1.00, 0.90),
  vec4(0.45, 0.25, 0.40, 0.30),
  vec4(0.55, 0.35, 0.50, 0.35));
// specular gloss, caustics
const vec2 WATER_SHINE[WATER_TYPES] = vec2[](
  vec2(1.0, 0.0),
  vec2(500.0, 1.0),
  vec2(600.0, 0.8),
  vec2(400.0, 0.4),
  vec2(500.0, 1.0),
  vec2(120.0, 0.0),
  vec2(160.0, 0.0));
// sRGB foam color before lighting, murky water has dirty foam
const vec3 WATER_FOAM_COLOR[WATER_TYPES] = vec3[](
  vec3(0.0),
  vec3(0.86, 0.90, 0.90),
  vec3(0.90, 0.94, 0.95),
  vec3(0.80, 0.84, 0.86),
  vec3(0.92, 0.95, 0.97),
  vec3(0.45, 0.46, 0.36),
  vec3(0.55, 0.60, 0.60));

// swell: height in world units of open sea in a calm wind, and how much the wind adds
#define SWELL_HEIGHT 7.0
#define SWELL_WIND 1.0
// ripple normal tilt at full strength
#define RIPPLE_STRENGTH 0.45
// world units per repeat of the two ripple layers
#define RIPPLE_SCALE_A 448.0
#define RIPPLE_SCALE_B 150.0
// seconds per flow map phase; ripples are dragged at most this long before fading to a fresh copy
#define FLOW_CYCLE 2.0
// how far still water drifts with the wind, world units per second (calm, full wind)
#define DRIFT_CALM 6.0
#define DRIFT_WIND 22.0
// screen offset of the refracted bed per unit of ripple tilt, world units, for water at least a tile deep
#define REFRACT 96.0
// Fresnel at normal incidence
#define WATER_F0 0.02
// glints: sun and moon, point lights
#define SPECULAR 1.0
#define LAMP_SPECULAR 0.6
// vertical depth over which the water fades in at the shore, and the shoreline foam reaches
#define SOFT_EDGE 6.0
#define FOAM_DEPTH 26.0
// breaking wave bands on sea and lake shores: tiles between bands, seconds between them reaching the shore
#define BAND_TILES 2.2
#define BAND_PERIOD 6.0
#define FOAM_SCALE 220.0
// rain rings: cell size (world units), rings per cell per second
#define RAIN_CELL 40.0
#define RAIN_RATE 1.3
// screen-space reflection march
#define SSR_STEPS 12
#define SSR_FIRST_STEP 32.0
#define SSR_GROWTH 1.4
#define CAUSTICS 0.9
// how much darker exposed bed right at the waterline looks
#define WET_SAND 0.78

vec2 waterCell(vec2 worldXZ) {
  return clamp(worldXZ / WATER_TILE + waterGrid.x, vec2(0.0), vec2(waterGrid.y - 1.0));
}

vec4 waterPage0(vec2 c) {
  return texture(waterData, (c + 0.5) / vec2(2.0 * waterGrid.y, waterGrid.y));
}

vec4 waterPage1(vec2 c) {
  return texture(waterData, (c + vec2(waterGrid.y, 0.0) + 0.5) / vec2(2.0 * waterGrid.y, waterGrid.y));
}

int waterTypeAt(vec2 c) {
  ivec2 t = clamp(ivec2(floor(c)), ivec2(0), ivec2(int(waterGrid.y) - 2));
  return clamp(int(texelFetch(waterData, t, 0).a + 0.5), 0, WATER_TYPES - 1);
}

// Long waves along the wind: height (up positive, world units) and its x, z gradient
vec3 waterSwell(vec2 xz, float amp) {
  vec3 sum = vec3(0.0);
  if (amp <= 0.01)
    return sum;
  // wavelength (world units), turn from the wind (radians), share of the height; and the period in seconds,
  // longer waves travelling faster like deep water waves
  const vec3 SWELL[3] = vec3[](vec3(704.0, 0.0, 1.0), vec3(416.0, 0.5, 0.55), vec3(232.0, -0.65, 0.3));
  const float PERIOD[3] = float[](12.0, 8.0, 6.0);
  for (int i = 0; i < 3; ++i) {
    float c = cos(SWELL[i].y), s = sin(SWELL[i].y);
    vec2 d = vec2(waterWind.x * c - waterWind.y * s, waterWind.x * s + waterWind.y * c);
    float k = 6.28318530718 / SWELL[i].x;
    float ph = dot(d, xz) * k - fract(waterTime / PERIOD[i]) * 6.28318530718 + float(i) * 1.7;
    float a = amp * SWELL[i].z;
    sum.x += a * sin(ph);
    sum.yz += d * (a * k * cos(ph));
  }
  return sum;
}

vec2 waterSlopeSample(vec2 uv, float lod) {
  return textureLod(waterNoise, uv, lod).rg * 2.0 - 1.0;
}

// Mip level for noise repeating every `repeat` world units, seen with `footprint` world units per pixel. Picked by
// hand: the water is drawn in branches, where texture() can't take derivatives from its neighbors.
float waterLod(float footprint, float repeat) {
  return log2(max(footprint * 256.0 / repeat, 1.0));
}

// Ripple slope moved along the flow map: two copies dragged with the flow half a cycle apart, each fading out
// before it has been dragged too far, so the ripples move without ever stretching.
vec2 waterRipples(vec2 xz, vec2 flow, float jitter, float footprint, out float w0, out vec2 o0, out vec2 o1) {
  float ph0 = fract(waterTime / FLOW_CYCLE + jitter);
  float ph1 = fract(ph0 + 0.5);
  w0 = 1.0 - abs(1.0 - 2.0 * ph0);
  o0 = flow * (ph0 * FLOW_CYCLE);
  o1 = flow * (ph1 * FLOW_CYCLE);
  const mat2 ROT = mat2(0.8, 0.6, -0.6, 0.8);
  float la = waterLod(footprint, RIPPLE_SCALE_A), lb = waterLod(footprint, RIPPLE_SCALE_B);
  vec2 a = waterSlopeSample((xz - o0) / RIPPLE_SCALE_A, la) * w0
    + waterSlopeSample((xz - o1) / RIPPLE_SCALE_A + 0.5, la) * (1.0 - w0);
  vec2 b = waterSlopeSample(ROT * (xz - o0) / RIPPLE_SCALE_B, lb) * w0
    + waterSlopeSample(ROT * (xz - o1) / RIPPLE_SCALE_B + 0.37, lb) * (1.0 - w0);
  // the small layer is rotated: turn its slope back
  b = b * ROT;
  return a * 0.65 + b * 0.35;
}

// Rings from raindrops: one ring at a time per cell, in two offset layers, as many cells active as it rains
vec2 waterRainRings(vec2 xz) {
  vec2 sum = vec2(0.0);
  for (int layer = 0; layer < 2; ++layer) {
    vec2 g = xz / RAIN_CELL + float(layer) * vec2(0.5, 0.31);
    vec2 cell = floor(g);
    float h = hash13(vec3(cell, float(layer) * 7.0));
    float t = waterTime * RAIN_RATE + h * 7.0;
    float drop = floor(t);
    float phase = fract(t);
    // a new spot and a new chance to fall every drop
    float h2 = hash13(vec3(cell + drop * 0.123, float(layer) + 3.0));
    if (h2 > waterRain)
      continue;
    vec2 center = vec2(hash13(vec3(cell, drop)), hash13(vec3(cell.yx, drop + 1.0))) * 0.5 + 0.25;
    vec2 dv = fract(g) - center;
    float dist = length(dv);
    float ring = (dist - phase * 0.3) / 0.035;
    float wave = sin(ring * 3.14159) * exp(-ring * ring * 0.5) * (1.0 - phase) * (1.0 - phase);
    sum += dv / max(dist, 1e-3) * wave;
  }
  return sum * 0.35;
}

// Bright vein network on the bed: where two drifting copies of the ripple height agree
float waterCausticPattern(vec2 xz, float footprint) {
  vec2 uv = xz / 180.0;
  float t = waterTime;
  float lod = waterLod(footprint, 180.0 / 1.37);
  float a = textureLod(waterNoise, uv + vec2(50.0, 27.0) * (t / WATER_TIME_WRAP), lod).b;
  float b = textureLod(waterNoise, uv * 1.37 + vec2(-35.0, 42.0) * (t / WATER_TIME_WRAP) + 0.21, lod).b;
  float v = 1.0 - min(abs(a - b) * 4.0, 1.0);
  return v * v * v * v;
}

// soft shadow of the sun or moon on the surface, four taps
float waterShadow(vec3 s) {
  if (shadowsEnabled == 0)
    return 1.0;
  vec4 lc = lightProjRel * vec4(s, 1.0);
  vec2 uv = lc.xy * 0.5 + 0.5;
  float z = depthZeroToOne == 1 ? lc.z : lc.z * 0.5 + 0.5;
  if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0))) || z >= 1.0)
    return 1.0;
  float texel = 1.0 / shadowMapSize;
  float bias = 4.0 * (2.0 * shadowRadius / shadowMapSize) / (2.0 * shadowDepthRange);
  float lit = 0.0;
  lit += texture(shadowCompare, vec3(uv + vec2(-1.5, -0.5) * texel, z - bias));
  lit += texture(shadowCompare, vec3(uv + vec2(0.5, -1.5) * texel, z - bias));
  lit += texture(shadowCompare, vec3(uv + vec2(1.5, 0.5) * texel, z - bias));
  lit += texture(shadowCompare, vec3(uv + vec2(-0.5, 1.5) * texel, z - bias));
  return lit * 0.25;
}

// The world as seen in the reflection: march along the reflected ray against the depth buffer. The hit takes the
// surface's own color under a rough guess of its light. a: how much to trust it.
vec4 waterTraceReflection(vec3 s, vec3 r, float jitter, vec3 approxLight) {
  float stepLen = SSR_FIRST_STEP;
  float t = stepLen * (0.5 + jitter);
  for (int i = 0; i < SSR_STEPS; ++i) {
    vec3 q = s + r * t;
    vec4 clip = viewProj * vec4(q, 1.0);
    if (clip.w <= NEAR)
      break;
    vec2 ndc = clip.xy / clip.w;
    if (any(greaterThan(abs(ndc), vec2(1.0))))
      break;
    vec2 qpx = (ndc * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy);
    ivec2 ip = ivec2(qpx);
    float dq = texelFetch(sceneDepth, ip, 0).r;
    if (!isSky(dq)) {
      float sceneZ = viewDepth(dq);
      float behind = clip.w - sceneZ;
      if (behind > 0.0 && behind < max(96.0, stepLen * 2.0)) {
        // water reflecting water shows the sky instead
        if (texelFetch(sceneMaterial, ip, 0).b > 0.75)
          break;
        vec2 edge = smoothstep(vec2(1.0), vec2(0.8), abs(ndc));
        float fade = edge.x * edge.y * (1.0 - float(i) / float(SSR_STEPS));
        vec3 c = srgbToLinear(texelFetch(sceneColor, ip, 0).rgb) * approxLight;
        return vec4(c, fade);
      }
    }
    stepLen *= SSR_GROWTH;
    t += stepLen;
  }
  return vec4(0.0);
}

vec3 waterShoulder(vec3 c) {
  return mix(c, 1.0 - (1.0 - SHOULDER) * exp(-(c - SHOULDER) / (1.0 - SHOULDER)), step(SHOULDER, c));
}

// p: camera-relative position of this pixel's scene surface (the bed, or something else under the water)
// n, albedo (sRGB), light (linear), shadow: its normal, color, light and sun shadow from the composite
// lit: its lit color (sRGB); mask: the material target's water channel (frag.glsl)
// Returns the color with the water over it, or lit unchanged; moves p to the water surface for the fog.
vec3 waterOver(ivec2 px, inout vec3 p, vec3 n, vec3 albedo, vec3 light, float shadow, vec3 lit, float mask,
  vec3 fogColor) {
  if (waterEnabled == 0 || mask < 0.25)
    return lit;
  bool bed = mask > 0.75;
  vec3 world = p + cameraPos;
  vec2 cBed = waterCell(world.xz);
  int type = waterTypeAt(cBed);
  if (type == 0 && !bed)
    return lit;

  // where the view ray meets the surface: the surface height under the pixel first, then a few refinements at
  // the crossing so slopes and the swell line up
  float dist = length(p);
  vec3 v = p / dist;
  if (v.y < 1e-3)
    return lit;
  vec4 page1 = waterPage1(cBed);
  vec4 surf0 = waterPage0(cBed);
  if (cameraPos.y >= surf0.r)
    return lit;  // under the surface
  int t0 = type;
  vec4 shape = WATER_SURFACE[max(type, 1)];
  float wind = waterWind.z;
  float swellAmp = SWELL_HEIGHT * (1.0 + SWELL_WIND * wind) * page1.g * page1.g * shape.y * waterWaves;
  float t = (surf0.r - cameraPos.y) / v.y;
  vec3 swell = vec3(0.0);
  vec2 c = cBed;
  for (int i = 0; i < 3; ++i) {
    vec2 xz = cameraPos.xz + v.xz * t;
    c = waterCell(xz);
    surf0 = waterPage0(c);
    swell = waterSwell(xz, swellAmp);
    t = (surf0.r - swell.x - cameraPos.y) / v.y;
  }
  float path = dist - t;
  // vertical depth of this pixel below the surface
  float depth = path * v.y;
  if (depth <= 0.0) {
    // above the water: the bed right at the waterline is wet where the waves just left it
    return bed ? lit * mix(WET_SAND, 1.0, smoothstep(0.0, 14.0, -depth)) : lit;
  }

  type = waterTypeAt(c);
  if (type == 0)
    type = t0 != 0 ? t0 : 1;
  shape = WATER_SURFACE[type];
  vec2 shine = WATER_SHINE[type];
  vec3 s = v * t;  // camera-relative surface point
  vec3 sw = s + cameraPos;
  page1 = waterPage1(c);
  float shore = page1.r;
  float energy = page1.g;
  // the wind drags the ripples along too, so still water never freezes; it goes through the flow map like the
  // flow itself, so nothing scrolls without bound
  vec2 flow = (surf0.gb * WATER_TILE + waterWind.xy * mix(DRIFT_CALM, DRIFT_WIND, wind)) * (waterSpeed * shape.z);
  float noise = ign(gl_FragCoord.xy);
  // world units one pixel covers on the surface, stretched where it is seen at a slant
  float viewZ = (viewProj * vec4(s, 1.0)).w;
  float pixelNear = length(reconstructPosition(vec2(px) + vec2(1.5, 0.5), 1.0) - reconstructPosition(vec2(px) + 0.5, 1.0));
  float footprint = pixelNear * viewZ / viewDepth(1.0) * inversesqrt(max(v.y, 0.05));

  // ---- surface normal: swell, flowing ripples, rain rings
  float jitter = textureLod(waterNoise, sw.xz / 4096.0, 0.0).a;
  float w0;
  vec2 o0, o1;
  vec2 slope = waterRipples(sw.xz, flow, jitter, footprint, w0, o0, o1)
    * (RIPPLE_STRENGTH * shape.y * waterWaves * (0.55 + 0.45 * energy) * (0.7 + 0.6 * wind));
  slope += swell.yz;
  if (waterRain > 0.01 && outdoors == 1)
    slope += waterRainRings(sw.xz) * shape.y;
  vec3 nrm = normalize(vec3(-slope.x, -1.0, -slope.y));
  vec3 toCamera = -v;

  // ---- light falling on the water
  bool lightOn = lightingEnabled == 1;
  vec3 caster = vec3(0.0);
  float surfShadow = 1.0;
  vec3 waterLight;
  if (outdoors == 1 && lightOn) {
    caster = (sunIsCaster == 1 ? sunLight(sunAltitude) : moonLight(sunAltitude)) * envSun;
    surfShadow = overcastShadow(waterShadow(s) * cloudShadow(sw, casterDir));
    waterLight = (ambientLight(sunAltitude) + skyLight(sunAltitude)) * envAmbient
      + caster * max(-casterDir.y, 0.0) * 0.6 * surfShadow;
  } else if (lightOn) {
    waterLight = envCaveLight(vec3(0.0, -1.0, 0.0));
  } else {
    waterLight = vec3(1.0);
  }

  vec3 lampSpec = vec3(0.0);
  if (pointLightsEnabled == 1 && lightOn) {
    int base = clusterBase(px, viewZ);
    int count = clusterLightCount(base);
    for (int i = 0; i < count; ++i) {
      int li = int(texelFetch(clusterData, base + 1 + i).r);
      vec4 posRadius = texelFetch(lightData, li * 2);
      vec3 color = texelFetch(lightData, li * 2 + 1).rgb;
      vec3 l = posRadius.xyz - s;
      float d2 = dot(l, l);
      float r2 = posRadius.w * posRadius.w;
      if (d2 >= r2)
        continue;
      float att = 1.0 - sqrt(d2 / r2);
      att *= att;
      vec3 ld = l * inversesqrt(max(d2, 1.0));
      waterLight += color * att * max(-ld.y, 0.2) * 0.6;
      float g = shine.x * 0.25;
      lampSpec += color * att * pow(max(dot(nrm, normalize(ld + toCamera)), 0.0), g) * (g + 8.0) / 25.0;
    }
  }

  // ---- what is seen through the surface: refracted, with caustics, absorbed and tinted on the way up
  vec3 under;
  {
    float reach = clamp(path / WATER_TILE, 0.15, 1.0);
    vec3 pr = p + vec3(slope.x, 0.0, slope.y) * (REFRACT * reach);
    vec4 clip = viewProj * vec4(pr, 1.0);
    vec3 seen = albedo;
    vec3 seenLight = light;
    if (clip.w > NEAR) {
      ivec2 rp = ivec2((clip.xy / clip.w * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy));
      if (inViewport(rp)) {
        float dr = texelFetch(sceneDepth, rp, 0).r;
        // only take the offset pixel if it is under this water too, or things above it would show in it
        if (!isSky(dr) && texelFetch(sceneMaterial, rp, 0).b >= 0.25
          && reconstructPosition(vec2(rp) + 0.5, dr).y > s.y + 2.0)
          seen = texelFetch(sceneColor, rp, 0).rgb;
      }
    }
    if (waterCaustics == 1 && shine.y > 0.0 && outdoors == 1 && lightOn) {
      float fade = exp(-depth / 220.0) * smoothstep(4.0, 24.0, depth);
      seenLight += caster * (max(-n.y, 0.0) * shadow * waterCausticPattern(world.xz + slope * 40.0, footprint * dist / t)
        * CAUSTICS * shine.y * fade);
    }
    vec3 below = srgbToLinear(seen) * seenLight;
    vec3 trans = exp(-WATER_EXTINCTION[type] / waterClarity * (path / WATER_TILE));
    under = below * trans + srgbToLinear(WATER_COLOR[type]) * waterLight * (1.0 - trans);
  }

  // ---- reflection: the sky (or the gloom underground), and roughly the world
  float cosV = max(dot(nrm, toCamera), 0.0);
  float fresnel = (WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - cosV, 5.0)) * shape.x;
  vec3 r = reflect(v, nrm);
  r.y = min(r.y, -0.03);
  r = normalize(r);
  vec3 sky;
  if (outdoors == 1 && proceduralSky == 1) {
    float elev = -r.y;
    vec3 sc = mix(skyHorizon(sunAltitude), skyZenith(sunAltitude), pow(clamp(elev, 0.0, 1.0), 0.45));
    sky = srgbToLinear(envSky(sc, fogColor, elev));
  } else {
    sky = srgbToLinear(fogColor) * (outdoors == 1 ? 1.0 : 0.6);
  }
  vec3 reflection = sky;
  if (waterReflections == 1) {
    vec3 approx = outdoors == 1
      ? (ambientLight(sunAltitude) + skyLight(sunAltitude) * 0.5) * envAmbient + caster * 0.5
      : envCaveLight(vec3(0.0, -1.0, 0.0));
    // calmer normal for the march: the reflection is rough anyway, and this keeps it from breaking up
    vec3 rr = reflect(v, normalize(mix(vec3(0.0, -1.0, 0.0), nrm, 0.5)));
    rr.y = min(rr.y, -0.03);
    vec4 hit = waterTraceReflection(s, normalize(rr), noise, approx);
    reflection = mix(sky, hit.rgb, hit.a);
  }

  vec3 color = mix(under, reflection, fresnel);

  // ---- glints
  if (outdoors == 1 && lightOn) {
    vec3 h = normalize(casterDir + toCamera);
    float spec = pow(max(dot(nrm, h), 0.0), shine.x) * (shine.x + 8.0) / 25.0;
    float fh = WATER_F0 + (1.0 - WATER_F0) * pow(1.0 - max(dot(h, toCamera), 0.0), 5.0);
    color += caster * spec * fh * surfShadow * SPECULAR * shape.x * 4.0;
  }
  color += lampSpec * LAMP_SPECULAR * shape.x;

  // ---- foam along the shore, in bands where the sea and lakes break, carried by the flow
  float foam = 1.0 - smoothstep(0.0, FOAM_DEPTH * (0.6 + 0.6 * energy), depth);
  float band = sin((shore / BAND_TILES + fract(waterTime / BAND_PERIOD)) * 6.28318530718);
  foam = max(foam, smoothstep(0.55, 1.0, band) * (1.0 - smoothstep(1.0, 3.2, shore)) * smoothstep(0.35, 0.6, energy));
  foam *= shape.w;
  if (foam > 0.01) {
    vec2 fxz = sw.xz / FOAM_SCALE;
    float lf = waterLod(footprint, FOAM_SCALE);
    float cells = textureLod(waterNoise, fxz - o0 / FOAM_SCALE, lf).a * w0
      + textureLod(waterNoise, fxz - o1 / FOAM_SCALE + 0.5, lf).a * (1.0 - w0);
    float f = smoothstep(1.0 - foam, 1.0 - foam + 0.18, cells) * min(foam * 2.0, 1.0);
    color = mix(color, srgbToLinear(WATER_FOAM_COLOR[type]) * waterLight, f * 0.85);
  }

  // ---- fade in from the waterline
  vec3 result = mix(srgbToLinear(bed ? lit * WET_SAND : lit), color, smoothstep(0.0, SOFT_EDGE, depth));
  result = linearToSrgb(clamp(waterShoulder(result), 0.0, 1.0));

  if (waterDebug == 1) {
    // type color, flow as stripes that move with it
    const vec3 DEBUG_TYPE[WATER_TYPES] = vec3[](vec3(0.0), vec3(0.2, 0.6, 1.0), vec3(0.0, 0.2, 0.9),
      vec3(0.3, 0.3, 0.6), vec3(0.6, 0.9, 1.0), vec3(0.4, 0.6, 0.1), vec3(0.4, 0.3, 0.3));
    float speed = length(surf0.gb);
    vec2 dir = speed > 1e-3 ? surf0.gb / speed : vec2(0.0);
    float stripe = speed > 1e-3 ? step(0.5, fract(dot(sw.xz / 64.0, dir) - waterTime * speed * 2.0)) : 0.5;
    result = mix(DEBUG_TYPE[type], vec3(stripe), 0.35) * mix(0.6, 1.0, page1.g);
  }

  p = s;
  return result;
}
