// The composite's half of the skylight and the light occlusion.
//
// Skylight (skylight_ao.glsl): the sky visibility upsampled with the AO scales the ambient and sky light, so
// interiors, overhangs and the shade under trees darken without the global illumination; with its volume on too
// (skylightMode 2) the light takes the colors of the surfaces around it.
//
// Point lights: a static light's occlusion grid (LightOcclusion.java) dims it behind walls, and a light under a roof
// leaves the tops of things open to the sky above it dark: roofs aren't lit from inside. The per-light texel after
// the lights (LightOcclusion.texel) carries both, and whether the walls already decide where the light reaches on
// its own storey. Needs lightData, cluster_config, cameraPos and rooms.glsl.

uniform int skylightMode;    // 1 skylight, 2 skylight and the volume's tint
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
// how far up a surface must face (-normal.y) to count as a roof, rather than a wall of the room the light is in
#define LIGHT_ROOF_FACING_LOW 0.05
#define LIGHT_ROOF_FACING_HIGH 0.35

// The global illumination is a colour transfer, not light (GiRelight): what the volume holds in front of a surface is the
// colour of the surfaces near it, as a tint whose luminance, 0 to 1, is how lit they are and how near, fading with the
// distance from them (gi.glsl). The surface's light is multiplied by mix(white, that colour at luminance 1, how much of it
// there is), so the grass' green stands on the foot of the wall beside it, however dim the grass is, and the light's
// brightness stays as it was. The sun's light is tinted by this share of it, so the colour still shows on a sunlit wall.
#define GI_SUN_TINT 0.6
// The tint's saturation is raised by this much from the surfaces' own colours: a voxel averages everything in it, a
// texture's detail included, which pulls its colour toward gray. Little, so the tint stays the colour the surfaces
// have on screen.
#define GI_SATURATION 1.2

// plain: the ambient plus sky light a surface would get under the open sky
vec3 skylightAmbient(vec3 plain) {
  float v = skylightOn == 1 ? skyUpsampled : 1.0;
  return plain * mix(skylightFloor, 1.0, v);
}

// The tint in mode 2, gi the volume's tint and giW how much of it there is (the strength setting too): rgb the colour at
// luminance 1, a how much of it to apply.
vec4 giTint(vec3 gi, float giW) {
  float l = overcastLuma(gi);
  if (skylightMode != 2 || l < 1e-4) {
    return vec4(1.0, 1.0, 1.0, 0.0);
  }
  // a square root, so a surface that sees a little lit colour still shows it
  vec3 tint = max(mix(vec3(1.0), gi / l, GI_SATURATION), vec3(0.0));
  return vec4(tint / max(overcastLuma(tint), 1e-4), sqrt(clamp(l, 0.0, 1.0)) * giW);
}

// What the tint multiplies the light by, share of it: never negative, however strong the setting.
vec3 giFactor(vec4 tint, float share) {
  return max(mix(vec3(1.0), tint.rgb, tint.a * share), vec3(0.0));
}

// Inside a building the skylight is the light flowed in through its openings (SkyField), and what comes in through a
// door is mostly the sunlit ground outside, not just the sky: covered surfaces get this many times the sunlight on open
// ground, as much as the sky's light does (the sky's own part is in skylightAmbient). sun: the caster's light.
#define DOORWAY_DAYLIGHT 1.5
vec3 doorwayDaylight(vec3 world, vec3 n, vec3 sun) {
  if (skylightOn == 0 || roomPlaneAt(world + n * ROOM_NORMAL_BIAS).x <= 0) {
    return vec3(0.0);
  }
  return sun * (skyUpsampled * DOORWAY_DAYLIGHT * max(-casterDir.y, 0.0));
}

// The static lights' occlusion grids (LightOcclusion.Atlas): a byte per cell, each grid inside a border of fully
// visible texels, so one hardware filtered fetch is the bilinear lookup and reads 1 past the grid's edge
uniform sampler2D lightVis;
uniform vec2 lightVisSize;  // the atlas' size in texels

// How much of a light with room code roomCode and per-light texel `extra` reaches a point in room r (lightRoomPlaneAt).
// The rooms decide it, except for a light whose occlusion grid is cast: its walls decide on its own storey, so the
// rooms (which can only cut at tile edges, and not where a roof ends but no wall) only keep it off the other storeys:
// an open door is a portal, between rooms and out of doors. The other lights (moving ones, those not cast yet) go by
// the rooms, which let them spill through openings (RoomMap).
float lightRoomShare(float roomCode, vec4 extra, ivec3 r) {
  int code = int(extra.w);
  if ((code & 4) != 0 && ((code >> 3) & 3) == r.z) {
    return 1.0;
  }
  return roomShare(roomCode, r.xy);
}

// How much of the light (per-light texel `extra`, camera-relative position lightPos) reaches camera-relative point p
// with normal n
float lightOcclusion(vec4 extra, vec3 lightPos, vec3 p, vec3 n) {
  int code = int(extra.w);
  float v = 1.0;
  if ((code & 2) != 0 && skylightOn == 1) {
    // y points down: positive when the light is below the point. Only what faces up is a roof: walls and the like
    // around a light in a room open to the sky keep their light
    float below = lightPos.y - p.y;
    v = 1.0 - smoothstep(LIGHT_OPEN_LOW, LIGHT_OPEN_HIGH, skyUpsampled)
      * smoothstep(LIGHT_ROOF_MARGIN, LIGHT_ROOF_MARGIN + LIGHT_ROOF_RAMP, below)
      * smoothstep(LIGHT_ROOF_FACING_LOW, LIGHT_ROOF_FACING_HIGH, -n.y);
  }
  if (extra.x >= 0.0 && v > 0.0) {
    int n4 = code >> 5;
    float cell = (code & 1) != 0 ? 64.0 : 32.0;
    vec3 w = p + cameraPos + n * LIGHT_VIS_NORMAL_BIAS;
    // cell coordinates; past the border every tap is 1, as the border is
    vec2 c = clamp((w.xz - extra.yz) / cell - 0.5, vec2(-1.0), vec2(float(n4)));
    // the grid's cell (0, 0) in the atlas, x + y * 4096 (LightOcclusion.ATLAS_BASE_ROW)
    vec2 at = vec2(mod(extra.x, 4096.0), floor(extra.x / 4096.0));
    v *= textureLod(lightVis, (at + c + 0.5) / lightVisSize, 0.0).r;
  }
  return v;
}
