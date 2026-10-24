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
// falls off with the height over the highest scenery of the column (the occlusion map), so nothing of it is under a
// roof. Each pixel marches its view ray to the scene: MIST_STEPS samples spread along it and MIST_END_STEPS more over
// the last MIST_END world units, where the ray meets the band over what it lands on.
#define MIST_HEIGHT 60.0       // world units over which the density falls to a third
#define MIST_DENSITY 0.004     // per world unit, at the surface
#define MIST_NEAR 150.0        // none closer to the camera than this
#define MIST_FAR 3000.0        // and it has faded out by here (rays end here when they meet nothing)
#define MIST_MAX 0.65          // at most this opaque
#define MIST_STEPS 20
#define MIST_END_STEPS 8
#define MIST_END 400.0

// the band's density at camera-relative q, 0..1
float mistAt(vec3 q, float t) {
  float top = occluderTop(q);
  if (top > 1e8) {
    return 0.0;
  }
  // y points down: over the surface the point's y is less than the top's
  float h = top - (q.y + cameraPos.y);
  return exp(-max(h, 0.0) / MIST_HEIGHT) * smoothstep(-8.0, 0.0, h) * (1.0 - smoothstep(MIST_FAR * 0.6, MIST_FAR, t));
}

uniform vec2 particleAmount;   // rain at the player, and the most anywhere the particles reach (rain_vert.glsl)

float mistOpacity(vec2 pc) {
  float d = texelFetch(sceneDepth, ivec2(pc), 0).r;
  vec3 end = isSky(d) ? normalize(reconstructPosition(pc, 1.0)) * MIST_FAR : reconstructPosition(pc, d);
  float len = min(length(end), MIST_FAR);
  if (len <= MIST_NEAR) {
    return 0.0;
  }
  vec3 dir = normalize(end);
  float split = max(len - MIST_END, MIST_NEAR);
  float sum = 0.0;
  float step = (split - MIST_NEAR) / float(MIST_STEPS);
  for (int i = 0; i < MIST_STEPS; ++i) {
    float t = MIST_NEAR + (float(i) + 0.5) * step;
    sum += mistAt(dir * t, t) * step;
  }
  float endStep = (len - split) / float(MIST_END_STEPS);
  for (int i = 0; i < MIST_END_STEPS; ++i) {
    float t = split + (float(i) + 0.5) * endStep;
    sum += mistAt(dir * t, t) * endStep;
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
