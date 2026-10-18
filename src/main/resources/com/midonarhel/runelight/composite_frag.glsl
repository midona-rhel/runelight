#version 330

// Runs once per pixel over the finished scene. Whole-frame effects (fog, AO, grading) go here.

#define DEBUG_FINAL 0
#define DEBUG_DEPTH 1
#define DEBUG_NORMALS 2

// near plane of the client projection (Mat4.projection)
#define NEAR 50.0

uniform sampler2D sceneColor;
uniform sampler2D sceneNormal;  // world space, n * 0.5 + 0.5. OSRS y points down
uniform sampler2D sceneDepth;   // reverse-z, 0 = infinitely far
uniform int debugView;
uniform int depthZeroToOne;     // 1 with glClipControl (GL 4.5), 0 on macOS

out vec4 FragColor;

// Distance from the camera along the view axis, in game units (128 per tile). Returns -1 for sky.
float viewDepth(float d) {
  if (depthZeroToOne == 1)
    return d <= 0.0 ? -1.0 : 2.0 * NEAR / d;
  return d <= 0.5 ? -1.0 : NEAR / (d - 0.5);
}

void main() {
  ivec2 px = ivec2(gl_FragCoord.xy);
  vec4 color = texelFetch(sceneColor, px, 0);

  if (debugView == DEBUG_DEPTH) {
    float z = viewDepth(texelFetch(sceneDepth, px, 0).r);
    // log scale from the near plane out to ~100 tiles
    float v = z < 0.0 ? 1.0 : clamp(log(z / NEAR) / log(12800.0 / NEAR), 0.0, 1.0);
    FragColor = vec4(vec3(v), 1.0);
    return;
  }

  if (debugView == DEBUG_NORMALS) {
    FragColor = vec4(texelFetch(sceneNormal, px, 0).rgb, 1.0);
    return;
  }

  FragColor = color;
}
