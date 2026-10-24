#version 330

#include "frame.glsl"
#include "sky.glsl"
#include "weather_common.glsl"

in vec2 fQuad;
flat in int fSplash;
flat in float fSoft;
in float fViewZ;
in float fAlpha;
flat in vec3 fColor;

out vec4 FragColor;

// soft particles: drops fade over this many world units in front of the scene
#define SOFT_DEPTH 60.0

// The rain's mist: where it beats down, the air just over everything it hits fills with spray, a bright band hugging
// the ground and the tops of roofs, walls and trees, that rims their edges against what is behind them. Its density
// falls off with the height over the highest scenery of the column (the occlusion map, smoothed over MIST_BLUR so
// wall tops and roof edges don't cut it off in steps), so none of it is under a roof. Each pixel marches its view
// ray to the scene in MIST_STEPS segments, short near the camera and longer away from it, and integrates the density
// over each one exactly as the height changes along it, so the steps never show as bands.
#define MIST_HEIGHT 70.0       // world units over which the density falls to a third
#define MIST_DENSITY 0.004     // per world unit, at the surface
#define MIST_NEAR 150.0        // none closer to the camera than this
#define MIST_FAR 3000.0        // and it has faded out by here
#define MIST_SKY_REACH 1600.0  // rays that meet nothing end here: the band only lies over scenery near the camera
#define MIST_MAX 0.65          // at most this opaque
#define MIST_STEPS 16
#define MIST_BLUR 48.0         // world units between the taps that smooth the scenery's height
// a roof the game hides (the player under it) is in the occlusion map but not on screen: the band over a surface
// shows only where the camera sees that surface, or something nearer, at its foot (this share of the distance plus
// MIST_FOOT_MARGIN world units of slack)
#define MIST_FOOT_TOLERANCE 0.05
#define MIST_FOOT_MARGIN 40.0

uniform vec2 particleAmount;   // rain at the player, and the most anywhere the particles reach (rain_vert.glsl)

// the top of the scenery under camera-relative q, smoothed; +big where there is none
float mistTop(vec3 q) {
  float sum = 0.0, n = 0.0;
  for (int i = 0; i < 4; ++i) {
    vec2 o = vec2(i == 0 ? -1.0 : i == 1 ? 1.0 : 0.0, i == 2 ? -1.0 : i == 3 ? 1.0 : 0.0) * MIST_BLUR;
    float t = occluderTop(q + vec3(o.x, 0.0, o.y));
    if (t < 1e8) {
      sum += t;
      n += 1.0;
    }
  }
  return n > 0.0 ? sum / n : 1e9;
}

// The height of camera-relative q over the scenery under it (negative under it, +big over nothing), and how much of
// the surface there the camera sees, 0..1 (1 off the screen, where it can't be checked)
float mistHeight(vec3 q, out float seen) {
  seen = 0.0;
  float top = mistTop(q);
  if (top > 1e8) {
    return 1e9;
  }
  vec3 foot = vec3(q.x, top - cameraPos.y, q.z);
  vec4 c = viewProj * vec4(foot, 1.0);
  vec2 ndc = c.xy / max(c.w, 1e-3);
  if (c.w < NEAR || any(greaterThan(abs(ndc), vec2(1.0)))) {
    seen = 1.0;
  } else {
    vec2 px = (ndc * 0.5 + 0.5) * vec2(sceneViewport.zw) + vec2(sceneViewport.xy);
    float tol = c.w * MIST_FOOT_TOLERANCE + MIST_FOOT_MARGIN;
    seen = 1.0 - smoothstep(tol, tol * 2.0, sceneViewDepth(px) - c.w);
  }
  // y points down: over the surface the point's y is less than the top's
  return top - (q.y + cameraPos.y);
}

// the band's density at height h over the scenery
float mistDensity(float h) {
  return h > 1e8 ? 0.0 : exp(-max(h, 0.0) / MIST_HEIGHT) * smoothstep(-16.0, 0.0, h);
}

float mistOpacity(vec2 pc) {
  float d = texelFetch(sceneDepth, ivec2(pc), 0).r;
  bool sky = isSky(d);
  vec3 end = sky ? normalize(reconstructPosition(pc, 1.0)) * MIST_SKY_REACH : reconstructPosition(pc, d);
  float len = min(length(end), MIST_FAR);
  if (len <= MIST_NEAR) {
    return 0.0;
  }
  vec3 dir = normalize(end);
  float sum = 0.0;
  float seen0;
  float t0 = MIST_NEAR;
  float h0 = mistHeight(dir * t0, seen0);
  for (int i = 1; i <= MIST_STEPS; ++i) {
    // segments growing with the distance, the square of an even split
    float u = float(i) / float(MIST_STEPS);
    float t1 = MIST_NEAR + (len - MIST_NEAR) * u * u;
    float seen1;
    float h1 = mistHeight(dir * t1, seen1);
    float far = 1.0 - smoothstep(MIST_FAR * 0.6, MIST_FAR, 0.5 * (t0 + t1));
    if (h0 < 1e8 && h1 < 1e8) {
      // the density integrated exactly over the segment with the height changing evenly along it (both over the
      // surface); the soft foot under it by the mean
      float a = max(h0, 0.0), b = max(h1, 0.0);
      float k = abs(b - a) > 0.5 ? MIST_HEIGHT * (exp(-a / MIST_HEIGHT) - exp(-b / MIST_HEIGHT)) / (b - a)
        : exp(-0.5 * (a + b) / MIST_HEIGHT);
      sum += k * smoothstep(-16.0, 0.0, 0.5 * (h0 + h1)) * (t1 - t0) * 0.5 * (seen0 + seen1) * far;
    } else {
      sum += 0.5 * (mistDensity(h0) * seen0 + mistDensity(h1) * seen1) * (t1 - t0) * far;
    }
    t0 = t1;
    h0 = h1;
    seen0 = seen1;
  }
  // as much as it rains where the ray ends (the player's rain plus the weather field's difference)
  vec2 xz = (dir * len).xz + cameraPos.xz;
  float share = clamp((particleAmount.x + weatherFieldDelta(xz).g) / max(particleAmount.y, 1e-3), 0.0, 1.0);
  return MIST_MAX * (1.0 - exp(-sum * MIST_DENSITY)) * share;
}

void main() {
  if (fSplash == 2) {
    float m = fAlpha * mistOpacity(gl_FragCoord.xy);
    if (m < 0.003) {
      discard;
    }
    FragColor = vec4(fColor, m);
    return;
  }
  float scene = sceneViewDepth(gl_FragCoord.xy);
  float a = fAlpha;
  if (fSplash != 0) {
    // behind something nearer than its foot, it is hidden, over a short distance so it doesn't end in an edge
    float behind = scene - (fViewZ * (1.0 - SPLASH_SURFACE_TOLERANCE) - 8.0);
    a *= smoothstep(-fSoft, 0.0, behind);
    // a droplet, shaded like a snowflake: gaussian-like, fullest in the middle and fading smoothly to nothing at its edge
    vec2 q = vec2(fQuad.x, fQuad.y * 2.0 - 1.0);
    float r2 = dot(q, q);
    a *= exp(-3.5 * r2) * max(1.0 - r2, 0.0);
  } else {
    if (fViewZ > scene) {
      discard;
    }
    a *= clamp((scene - fViewZ) / SOFT_DEPTH, 0.0, 1.0);
    // round the streak across and taper its ends
    a *= 1.0 - fQuad.x * fQuad.x;
    a *= smoothstep(0.0, 0.3, fQuad.y) * (1.0 - smoothstep(0.85, 1.0, fQuad.y));
  }
  if (a < 0.003) {
    discard;
  }
  FragColor = vec4(fColor, a);
}
