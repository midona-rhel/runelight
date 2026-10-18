// The composite's half of the skylight and the light occlusion.
//
// Skylight (skylight_ao.glsl): the sky visibility upsampled with the AO scales the ambient and sky light, so
// interiors, overhangs and the shade under trees darken without the probes; with the probes on too
// (skylightMode 2) they add only what they find beyond it: light bounced off sunlit surfaces.
//
// Point lights: a static light's occlusion grid (LightOcclusion.java) dims it behind walls, and a light under a roof
// leaves the tops of things open to the sky above it dark: roofs aren't lit from inside. The per-light texel after
// the lights (LightOcclusion.texel) carries both. Needs lightData, cluster_config and cameraPos.

uniform int skylightMode;    // 0 probes only, 1 skylight, 2 skylight and the probes' bounce
uniform int skylightOn;      // 1 when the AO pass worked the sky visibility out this frame
uniform float skylightFloor; // share of the ambient light kept where no sky is seen

// sky visibility of this pixel, from upsampleAoFog; 1 when unknown
float skyUpsampled = 1.0;

// how far below a sky-open surface (world units) a covered light must be before it stops lighting it
#define LIGHT_ROOF_MARGIN 32.0
#define LIGHT_ROOF_RAMP 64.0
// the occlusion grid is looked up this far off the surface along its normal, as the rooms are: a wall's inner face
// samples inside the room, its outer face outside
#define LIGHT_VIS_NORMAL_BIAS 40.0
// sky visibility above which a surface counts as open to the sky
#define LIGHT_OPEN_LOW 0.35
#define LIGHT_OPEN_HIGH 0.65

// plain: the ambient plus sky light a surface would get under the open sky; gi and giW the probes' light and weight
vec3 skylightAmbient(vec3 plain, vec3 gi, float giW) {
  float v = skylightOn == 1 ? skyUpsampled : 1.0;
  vec3 s = plain * mix(skylightFloor, 1.0, v);
  if (skylightMode == 2 && giW > 0.0) {
    s += max(gi - plain * v, 0.0) * giW;
  }
  return s;
}

// one cell of a grid starting at texel base, n cells per side: 0..1, 1 outside it
float lightVisCell(int base, int n, ivec2 c) {
  if (any(lessThan(c, ivec2(0))) || any(greaterThanEqual(c, ivec2(n)))) {
    return 1.0;
  }
  int k = c.y * n + c.x;
  vec4 t = texelFetch(lightData, base + (k >> 4));
  int comp = (k >> 2) & 3;
  int v = int(comp == 0 ? t.x : comp == 1 ? t.y : comp == 2 ? t.z : t.w);
  return float((v >> ((k & 3) * 4)) & 15) / 15.0;
}

// How much of light li (camera-relative position lightPos) reaches camera-relative point p with normal n
float lightOcclusion(int li, vec3 lightPos, vec3 p, vec3 n) {
  vec4 e = texelFetch(lightData, LIGHT_EXTRA_BASE + li);
  int code = int(e.w);
  float v = 1.0;
  if ((code & 2) != 0 && skylightOn == 1) {
    // y points down: positive when the light is below the point
    float below = lightPos.y - p.y;
    v = 1.0 - smoothstep(LIGHT_OPEN_LOW, LIGHT_OPEN_HIGH, skyUpsampled)
      * smoothstep(LIGHT_ROOF_MARGIN, LIGHT_ROOF_MARGIN + LIGHT_ROOF_RAMP, below);
  }
  if (e.x >= 0.0 && v > 0.0) {
    int n4 = code >> 2;
    float cell = (code & 1) != 0 ? 64.0 : 32.0;
    vec3 w = p + cameraPos + n * LIGHT_VIS_NORMAL_BIAS;
    vec2 c = (w.xz - e.yz) / cell - 0.5;
    vec2 b = floor(c);
    vec2 f = c - b;
    ivec2 i = ivec2(b);
    int base = int(e.x);
    float v00 = lightVisCell(base, n4, i);
    float v10 = lightVisCell(base, n4, i + ivec2(1, 0));
    float v01 = lightVisCell(base, n4, i + ivec2(0, 1));
    float v11 = lightVisCell(base, n4, i + ivec2(1, 1));
    v *= mix(mix(v00, v10, f.x), mix(v01, v11, f.x), f.y);
  }
  return v;
}
