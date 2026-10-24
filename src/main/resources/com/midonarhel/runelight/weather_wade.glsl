// Ripples in puddles where people have just waded (WadeSteps.java). Every step is a vec4: local x and z, its age in
// seconds, and its strength (0 where the array is unused). A step sends out a thin ring that spreads and fades, and a
// second, fainter one a moment behind it. Returns the slope of the water's surface, for weatherPuddleWater to tilt its
// reflection by; it is the same slope as the ring's height profile, so the ring's crests are where it is steepest.

// steps in the array (WadeSteps.MAX_STEPS) and how long one lives, seconds (WadeSteps.LIFE)
#define WADE_MAX 24
#define WADE_LIFE 1.2
// how fast a ring spreads, world units per second, and its width
#define WADE_SPEED 55.0
#define WADE_WIDTH 5.0
// the second ring: seconds behind the first, and how much of its strength it has
#define WADE_SECOND_DELAY 0.2
#define WADE_SECOND 0.6
// the slope of the steepest part of the freshest ring, and how far out a ring has faded to half
#define WADE_SLOPE 0.45
#define WADE_REACH 60.0

uniform vec4 wadeSteps[WADE_MAX];

// One ring of radius r around a step, at distance d from it: slope along the way out, a gaussian ring's derivative
float wadeRing(float d, float r) {
  float x = (d - r) / WADE_WIDTH;
  return -x * exp(-x * x);
}

vec2 wadeRippleSlope(vec2 localXZ) {
  vec2 slope = vec2(0.0);
  for (int i = 0; i < WADE_MAX; i++) {
    vec4 s = wadeSteps[i];
    vec2 away = localXZ - s.xy;
    float d = length(away);
    // beyond the farthest ring there is nothing to add
    if (s.w <= 0.0 || d > WADE_SPEED * s.z + 3.0 * WADE_WIDTH) {
      continue;
    }
    float fade = pow(max(1.0 - s.z / WADE_LIFE, 0.0), 2.0) / (1.0 + d / WADE_REACH);
    float rings = wadeRing(d, WADE_SPEED * s.z)
      + WADE_SECOND * wadeRing(d, WADE_SPEED * max(s.z - WADE_SECOND_DELAY, 0.0)) * step(WADE_SECOND_DELAY, s.z);
    slope += away / max(d, 1e-3) * (rings * fade * s.w * WADE_SLOPE);
  }
  return slope;
}
