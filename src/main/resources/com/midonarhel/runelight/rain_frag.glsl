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

// The rain's mist: where it beats down, the air just over everything it hits fills with spray, a soft bright band
// over the ground and the tops of roofs and walls that spills up over their edges into a halo against what is behind
// them. Worked out at 1 / MIST_DOWN of the scene's resolution (Weather.drawMist), in stages:
// 1, each texel's own mist: its surface's share of open sky (the occlusion map) times how far the view ray runs
//    through a band MIST_HEIGHT thick over it, more at a slant;
// 2, blurred across;
// 3, blurred up the screen, leaning upward, so it rises off edges;
// 4, laid over the scene, filtered up to its pixels.
// No step depends on depth edges, so it never cuts off at a wall, and it is smooth by construction.
#define MIST_DOWN 4            // scene pixels per mist texel, each way (Weather.MIST_DOWN)
#define MIST_HEIGHT 70.0       // world units: the band's thickness
#define MIST_DENSITY 0.004     // per world unit through it
#define MIST_SLANT 0.35        // the flattest view of the band counted: past it the path through it stops growing,
                               // so at a grazing angle over the ground it stays a veil, not a wall
#define MIST_WALLS 0.35        // what a face that isn't level keeps of its mist
#define MIST_NEAR 150.0        // none closer to the camera than this
#define MIST_FAR 3000.0        // and it has faded out by here
#define MIST_MAX 0.65          // at most this opaque
#define MIST_BLUR_ACROSS 4     // taps either side, low-res texels
#define MIST_BLUR_DOWN 3       // up the screen: this many taps below a texel and MIST_BLUR_UP above, centred
#define MIST_BLUR_UP 9         // MIST_RISE texels below it, so the mist climbs over what it lies on
#define MIST_RISE 2.5

uniform int mistStage;         // 0 particles; 1 to 4 the mist's stages
uniform sampler2D mistTex;     // the previous stage's mist (Weather.drawMist)
uniform vec2 particleAmount;   // rain at the player, and the most anywhere the particles reach (rain_vert.glsl)

// stage 1: the mist at one low-res texel
float mistSource(ivec2 t) {
  ivec2 px = sceneViewport.xy + t * MIST_DOWN + MIST_DOWN / 2;
  if (any(greaterThanEqual(px, sceneViewport.xy + sceneViewport.zw))) {
    return 0.0;
  }
  float d = texelFetch(sceneDepth, px, 0).r;
  if (isSky(d)) {
    return 0.0;
  }
  vec3 p = reconstructPosition(vec2(px) + 0.5, d);
  float dist = length(p);
  vec3 n = decodeNormal(texelFetch(sceneNormal, px, 0).rgb);
  // open to the sky: none under a roof, none on a floor whose roof the game hides
  vec2 uv;
  float depth;
  float open = 1.0;
  if (occlusionLookup(p + n * 12.0, uv, depth)) {
    open = texture(occlusionCompare, vec3(uv, depth - 40.0 / (2.0 * occlusionDepthRange)));
  }
  float path = MIST_HEIGHT / max(abs(p.y) / dist, MIST_SLANT);
  float m = (1.0 - exp(-MIST_DENSITY * path)) * open * mix(MIST_WALLS, 1.0, max(-n.y, 0.0))
    * smoothstep(MIST_NEAR, MIST_NEAR * 2.0, dist) * (1.0 - smoothstep(MIST_FAR * 0.6, MIST_FAR, dist));
  // as much as it rains there (the player's rain plus the weather field's difference)
  vec2 xz = p.xz + cameraPos.xz;
  return m * clamp((particleAmount.x + weatherFieldDelta(xz).g) / max(particleAmount.y, 1e-3), 0.0, 1.0);
}

float mistBlur(ivec2 t, bool across) {
  ivec2 size = textureSize(mistTex, 0) - 1;
  float sum = 0.0, wsum = 0.0;
  if (across) {
    for (int k = -MIST_BLUR_ACROSS; k <= MIST_BLUR_ACROSS; ++k) {
      float x = float(k) / (float(MIST_BLUR_ACROSS) * 0.6);
      float w = exp(-x * x);
      sum += w * texelFetch(mistTex, clamp(t + ivec2(k, 0), ivec2(0), size), 0).r;
      wsum += w;
    }
  } else {
    // y is up the screen: the taps below a texel count most, so what lies under it rises into it
    for (int k = -MIST_BLUR_UP; k <= MIST_BLUR_DOWN; ++k) {
      float x = (float(k) + MIST_RISE) / 3.5;
      float w = exp(-x * x);
      sum += w * texelFetch(mistTex, clamp(t - ivec2(0, -k), ivec2(0), size), 0).r;
      wsum += w;
    }
  }
  return sum / wsum;
}

vec4 mistStageColor() {
  if (mistStage == 4) {
    vec2 uv = (gl_FragCoord.xy - vec2(sceneViewport.xy)) / (vec2(textureSize(mistTex, 0)) * float(MIST_DOWN));
    return vec4(fColor, MIST_MAX * fAlpha * texture(mistTex, uv).r);
  }
  ivec2 t = ivec2(gl_FragCoord.xy);
  float m = mistStage == 1 ? mistSource(t) : mistBlur(t, mistStage == 2);
  return vec4(m, 0.0, 0.0, 1.0);
}

void main() {
  if (mistStage != 0) {
    FragColor = mistStageColor();
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
