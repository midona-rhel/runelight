// Lightning (Lightning.java): a glow inside the clouds around a strike and, for some strikes, a jagged bolt below
// the clouds. Sky pixels of the composite only, so scenery hides it for free. The flash itself, which lights
// everything else, is weatherOvercast.z in sky.glsl. Needs sky.glsl.

uniform vec4 lightningBolt;  // xy: strike xz relative to the camera, world units; z: bolt visibility 0..1; w: seed

// light added inside the clouds near the strike at a full flash (linear), and how far it spreads, world units
#define LIGHTNING_CLOUD_GLOW 0.6
#define LIGHTNING_GLOW_RADIUS 7000.0
// the bolt runs from the cloud base (clouds.glsl's CLOUD_BOTTOM, above the camera) to this far below the camera
#define BOLT_RISE 3500.0
#define BOLT_DROP 2500.0
// core width in world units (never under about a pixel), and how much wider its glow is
#define BOLT_WIDTH 16.0
#define BOLT_PIXEL 0.0012
#define BOLT_GLOW 10.0
#define BOLT_BRIGHTNESS 3.0
#define BOLT_COLOR vec3(0.85, 0.90, 1.00)

float lightningHash(float x) {
  return fract(sin(x * 127.1 + lightningBolt.w * 311.7) * 43758.5453);
}

// piecewise linear noise in -1..1: straight segments with sharp kinks, like a bolt
float lightningNoise(float x) {
  float i = floor(x);
  return mix(lightningHash(i), lightningHash(i + 1.0), x - i) * 2.0 - 1.0;
}

// sideways offset of the bolt at height fraction h (0 bottom .. 1 cloud base), world units
float boltOffset(float h) {
  float lean = (lightningHash(-7.0) - 0.5) * 900.0 * h;
  return lean + lightningNoise(h * 5.0) * 380.0 + lightningNoise(h * 13.0 + 20.0) * 150.0
    + lightningNoise(h * 37.0 + 40.0) * 50.0;
}

// Light inside the clouds near the strike, linear. rel: the cloud sample's xz relative to the camera.
vec3 lightningCloudGlow(vec2 rel) {
  float f = weatherOvercast.z;
  if (f <= 0.0) {
    return vec3(0.0);
  }
  vec2 d = rel - lightningBolt.xy;
  return LIGHTNING_COLOR * (LIGHTNING_CLOUD_GLOW * f * exp(-dot(d, d) / (LIGHTNING_GLOW_RADIUS * LIGHTNING_GLOW_RADIUS)));
}

// The sky (sRGB) with the bolt in front of it. The bolt lies in a vertical plane through the strike, facing the
// camera; dir: normalized view ray, camPos: world position of the camera.
vec3 lightningOverSky(vec3 sky, vec3 dir, vec3 camPos) {
  float vis = lightningBolt.z;
  if (vis <= 0.0) {
    return sky;
  }
  vec2 b = lightningBolt.xy;
  float dist = max(length(b), 1.0);
  vec2 n = b / dist;
  float facing = dot(dir.xz, n);
  if (facing <= 0.05) {
    return sky;
  }
  float t = dist / facing;
  vec3 p = dir * t;
  float u = dot(p.xz - b, vec2(-n.y, n.x));
  // y points down: altitude is -y
  float camAlt = -camPos.y;
  float bottom = camAlt - BOLT_DROP;
  float h = (camAlt - p.y - bottom) / (BOLT_RISE + BOLT_DROP);
  if (h < 0.0 || h > 1.0) {
    return sky;
  }

  float width = max(BOLT_WIDTH, t * BOLT_PIXEL);
  float d = abs(u - boltOffset(h));
  float core = exp(-d / width);
  float glow = exp(-d / (width * BOLT_GLOW)) * 0.25;

  // one branch forking off partway down and thinning out
  float fork = 0.4 + 0.3 * lightningHash(3.0);
  float reach = 0.3;
  if (h < fork && h > fork - reach) {
    float side = lightningHash(5.0) > 0.5 ? 1.0 : -1.0;
    float drop = fork - h;
    float bu = boltOffset(fork) + side * drop * 1600.0 + lightningNoise(h * 29.0 + 60.0) * 60.0;
    float strength = 0.6 * (1.0 - drop / reach);
    core = max(core, exp(-abs(u - bu) / (width * 0.7)) * strength);
  }

  // rises into the cloud base
  float fade = 1.0 - smoothstep(0.9, 1.0, h);
  vec3 lin = srgbToLinear(max(sky, 0.0)) + BOLT_COLOR * ((core * BOLT_BRIGHTNESS + glow) * vis * fade);
  return linearToSrgb(clamp(lin, 0.0, 1.0));
}
