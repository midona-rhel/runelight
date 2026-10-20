// How much fog may hide of the playable view. Fog is for lights and sun to shine through, not for covering the
// ground you play on: around the player the cover is limited to a maximum (a soft knee, so thin fog is untouched),
// and beyond the play area that limit opens up so the far land still fades into the sky. Shared by the volumetric
// fog (fog_frag.glsl) and the area's distance fog (environment.glsl).

// tiles around the player where the limit holds in full, and the tiles over which it then opens to no limit
#define FOG_COVER_PLAY_TILES 25.0
#define FOG_COVER_RAMP_TILES 20.0
// share of the maximum below which cover is left as it is
#define FOG_COVER_KNEE 0.5

// cover 0..1: how much of the scene behind the fog is hidden; dist: horizontal distance from the player, world units
float capFogCover(float cover, float maxCover, float dist) {
  if (maxCover >= 1.0) {
    return cover;
  }
  float knee = maxCover * FOG_COVER_KNEE;
  float soft = cover <= knee ? cover : knee + (maxCover - knee) * (1.0 - exp(-(cover - knee) / (maxCover - knee)));
  float open = smoothstep(FOG_COVER_PLAY_TILES * 128.0, (FOG_COVER_PLAY_TILES + FOG_COVER_RAMP_TILES) * 128.0, dist);
  return mix(soft, cover, open);
}
