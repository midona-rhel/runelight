# How 117 HD shades the world, against Lumina — by code

Audit for the shading-parity segment, 2026-10-31, on the user's ask: "it's not the tree shading, it's the general how
we shade the world … how 117 HD shades the world, how it shades foliage, terrain, etc. We technically have a better
terrain solver … but there's also some issues still." 117 HD = `~/Code/rlhd-ref` @ 7cadf3bd, default zone renderer.
Ours = branch `claude/shading-parity-b43a20`. Line numbers on our side are at 334228ec (before this doc's terrain
commit) unless a row says otherwise. Every claim was read in code; nothing was run in a client. Each row ends in a
recommendation: **COPY** (with the code), **KEEP OURS** (with the reason), or **OPEN** (the user decides).

Binding constraints applied: HANDOVER rules (one mechanism per quantity, no new settings, A1 "keep the shading, sky
palette, fog stack" — a parity change that alters what A1 keeps is OPEN, not COPY), "match by code" for anything
taken from 117 HD, and the user's "never blend terrain colours between tiles".

## Summary

| Area | Verdict |
|---|---|
| Model normals and the vanilla undo | now 117 HD's (docs/117hd-model-shading.md; commits 79d5c01d, e2721038, 0deb2859, 9ad03695) |
| Terrain normals | **KEEP OURS — the user's decision, 2026-10-31**: ours shade the back side of cliffs from the real slope, where RuneLite's GPU plugin and 117 HD "aren't able to suss out the correct shading of the back" (evidence: `~/.runelite/lumina/bugs/terrain-cliff-back-ours-correct.png`, a boulder cliff seen from above and behind). Mechanism: height-field central differences across tile borders, symmetric and seamless where tile models meet tile paints (§1.1); do not move it toward 117 HD |
| Terrain colours | **fixed (8eb04057)**: one undo rule with 117 HD's exact arithmetic for every untextured tile, no blending between tiles, 117 HD's set of raw tiles; GroundBlender's land blending, border flags and the `groundBlending` setting gone |
| Fixing the world | 117 HD has gap fillers, double-sided upper floors and 1,146 per-area tile overrides; we have none (OPEN / COPY in their own segment) |
| Foliage materials | 117 HD textures, normal-maps and glosses trees and grass (BARK, LEAF_VEINS, GRASS_BLADE …); we have none — this, not the normals, is what hides the facets there (OPEN, a feature) |
| Lighting equation | same structure (linear light, N·L sun, constant ambient, up-facing sky light); different levels and four extra mechanisms of ours: the brightness `pow` on albedo, the 0.7 daylight fill in shadows, SSAO on direct light, the 30–68° caster clamp (§3) |
| Wind | 117 HD's zone renderer has none and no 117 HD wind tilts a normal; ours tilts it (OPEN) |

## Open questions — state

Settled 2026-10-31 by the user's standing rules (relayed by the session that started this one) and landed, one
commit each:

| # | question | decision | commit |
|---|---|---|---|
| 5 | AO on direct light | **reverted by the user** (bf7801fb): SSAO darkens sun and lamps again | 24d6ea8a, bf7801fb |
| 6 | ambient as one mechanism | (ambient + sky light) × sky visibility × area × AO; the room daylight floor gone; the room fill flat and directionless, darkened by AO (the user) | 676bf249, 5a6794ad |
| 7 | foliage materials (textures) | no: never load texture images | — |
| 8 | specular | none | — |
| 9 | fog haze | keep ours (the fog segment's) | — |
| 11 | water bed colours | **reverted by the user** ("how we did it before"): 2×2 mean and bank grading back, `GroundBlender` with its land colour map | 79f916d3, 2c80ab50 |
| 12 | double-sided upper floors | copied, counted in `zoneSize` | e0ccb260 |
| 12 | tile geometry overrides (21 `heightOffset` / `doubleSidedFaces` entries) | copied: matched through the water importer's tile-override list (117 HD's precedence), replacements at 117 HD's defaults | 5396df40 |
| 12 | gap fillers | **the user: keep ours** (none): 117 HD's far-plane black does not carry over to our deferred composite, and the simple version (black at the hole surface) would hide what is in the hole | — |
| 13 | wind normal tilt | deleted | 84370197 |
| 14 | shadows on transparent faces | copied (last frame's map with its own matrix, 4-tap PCF) | 9b9f8650 |
| 15 | interpolation space | keep ours | — |
| 16 | `inheritTileColorType` | copied (static uploads) | 4986bba9 |
| 16 | `doubleSidedFaces` + double-sided materials (every leaf texture) | copied for static models; the zone count in one function, which also closes S4 (translucent faces that become fake water) | 9d42eae1 |

**The user, on the look questions**: 1 daylight fill and 2 brightness curve stay as the branch has them; 3 (a brighter,
whiter sun), 4 (117 HD's sun angle) and 10 (clipping highlights) no. The original wording of all sixteen follows.

### The sixteen as first asked

1. **Daylight fill** (`composite_frag.glsl:375-376`): ours puts back 0.7 × the sun inside shadows wherever the sky is
   open; 117 HD has nothing like it. Open-ground shadow contrast 1.35:1 here vs 4.7:1 in 117 HD — the largest single
   look difference. Parity = delete the two lines.
2. **Brightness curve on albedo** (`hsl_to_rgb.glsl:84`, `frag.glsl:140`): `pow(rgb, brightness)` makes our linear
   albedo 1.35–1.87× brighter (darks most) than 117 HD's, which has none (the `lumina` branch dropped it). Dropping it
   alone darkens the scene ~1.3× in lightness, so it goes with question 3.
3. **Light levels**: at noon our sun is 0.69× 117 HD's and yellow (2.9 × (1, .90, .76) vs white × 4), ambient 0.63×
   and less blue ((.30,.38,.58)×0.8 vs linear #97baff), sky light on up faces 0.29× (0.45 × zenith vs 0.5 × the
   horizon colour). Copy 117 HD's levels with question 2, or keep A1's palette.
4. **Sun direction for shading**: ours clamps the caster to 30–68° (noon 68°; a sun-facing wall gets 0.37 of the
   light), 117 HD shades with the real sun (noon 26–73° by simulated date, 49° at an equinox) or 52°/235° fixed.
5. **AO on direct light** (`AO_DIRECT 1.0`, `composite_frag.glsl:48`): our SSAO darkens sun and lamps too; 117 HD has
   no SSAO (material AO maps on ambient only). Parity minimum: `AO_DIRECT 0.0`, the value the line's own comment calls
   physical.
6. **Ambient as one mechanism** (rule 2): ours = constant × sky visibility (floor 0.4) × SSAO + room daylight + room
   fill; 117 HD = one constant.
7. **Foliage materials** (textures + normal maps + specular on untextured faces of trees, grass, rocks): a feature
   (117 HD's textures are CC0/BSD/with permission per `scene/textures/000_licenses.txt`).
8. **Specular** (Phong per material, gloss 30 on translucent faces): none in ours outside water.
9. **Fog**: 117 HD hazes the overworld (fogDepth 6.5, sky colour per view direction); our overworld has none besides
   the volumetric fog and the edge fade (A1/A4).
10. **Tone**: our shoulder above 0.75 vs 117 HD's clip at 1; emissive 1.2 vs 1.0. Only with question 3.
11. **Water bed colours** still blend across tiles (`WaterMap.bedColor` 2×2 average, bank grading) — does the
    no-blending rule cover the bed (inventory S17 says "bed colour = the tile's")?
12. **Tile overrides, gap fillers, double-sided upper floors** (§1.5): which to port. Double-sided upper floors is a
    straight copy (`ZSU:1131, 1505`) but adds triangles the zone size count (`SceneUploader.zoneSize`) and
    VoxelCapture must count — its own segment.
13. **Wind normal tilt** (`sway.glsl:70-73`): 117 HD tilts no normal; delete the 4 lines for parity, or keep ours
    (the lit normal follows the sheared geometry).
14. **Transparent faces get no shadow** in ours (the alpha path, `frag.glsl:181-188`); 117 HD shadows them.
15. **Interpolation space**: 117 HD blends vertex colours in linear, ours in sRGB (small; KEEP unless the albedo
    target changes).
16. **`doubleSidedFaces`, `inheritTileColorType`** model overrides (§2): grass and ground clutter taking the tile's
    colour (HANDOVER A2 "ground-matched foliage"), back faces of jungle/palm leaves.

## 1. Terrain

**State after 8eb04057** (this branch): issues 1–5 and 10–11 below are fixed — every untextured tile is undone
with 117 HD's exact arithmetic whether or not anything blends; no colours are shared between tiles on land; the
raw set is 117 HD's default one ({7, 13, 19, 27, 28, 37, 46, 128, 156, 163, 199}); the border flags and the shader's
border mix are gone with the sharing; the `groundBlending` setting is removed; `GroundBlenderTest` checks the undo
against 117 HD's transcribed code. The water bed (issue 5's bed half) is untouched: open question 11.

Read-only, nothing run. 117 HD = `~/Code/rlhd-ref` @ 7cadf3bd (paths below are relative to
`src/main/java/rs117/hd/` and `src/main/resources/rs117/hd/`). Ours = worktree
`~/Code/runelight/.claude/worktrees/shading-parity-b43a20` @ 334228ec (paths relative to
`src/main/java/com/midonarhel/lumina/` and `src/main/resources/com/midonarhel/lumina/`).
Abbreviations: **PG** = 117 HD `scene/ProceduralGenerator.java`, **ZSU** = 117 HD `renderer/zone/SceneUploader.java`,
**SU** = our `scene/SceneUploader.java`, **GB** = our `scene/GroundBlender.java`.

### 0. Up front: the user's "no blending between tiles" rule and GroundBlender

**GroundBlender blends colours between tiles, and it is on by default.**

- Default: `LuminaConfig.java:379-390` `groundBlending` default `true` ("Blend ground colors smoothly across tile
  borders and give paths soft edges, like 117 HD. Also removes the light the game bakes into the ground."). Copied
  into `GroundBlender.enabled` at `LuminaPlugin.java:728` and on change at `:1049-1058` (scene reload).
  `GB:26` `public static boolean enabled = true`.
- What it does (`GB:96-133` build, `:219-345` add, `:139-155` lookup), per zone at upload (`SU:160`):
  1. Walks the zone's tiles plus a one-tile border, all 4 levels, plus bridges (`GB:110-131`).
  2. Every untextured, non-hidden ground vertex goes into a hash keyed by (x, z in quarter tiles, height & 0xfff)
     (`GB:367-370`, the same key as 117 HD's `tileVertexHash`). Textured tile paints are skipped whole (`GB:227`),
     and so are textured faces of tile models (`GB:265`).
  3. Each colour is first un-lit (`undoShading`, `GB:307-310`). Then **one colour per vertex** is kept, by 117 HD's
     priority rules (`GB:332-340`): an overlay replaces what is there, an underlay only fills an empty vertex, a
     near-black colour (packed HSL ≤ 2) never replaces a proper one. One rule is our own: a proper underlay also
     replaces a near-black one (`GB:334-335`).
  4. At upload every corner of an untextured tile paint (`SU:655-676`) and every vertex of an untextured tile-model
     face (`SU:878-893`) takes that shared colour (`groundColor`, `SU:770-781`) instead of its own. Since neighbouring
     tiles now share one colour per vertex, the colour is **interpolated across tile borders**.
  5. Exceptions: 15 overlay ids are never blended (`GB:66`). Overlay faces of a tile model that has any texture
     keep their own colours (`GB:258`, `SU:812`).
  6. A vertex touched by both overlay and underlay ground gets `BORDER` (`GB:151`). That writes −1 into the
     vertex's w (`SU:759-764`). The fragment shader then sharpens the change between the two groups
     (`frag.glsl:150-158`, `vert.glsl:120-122`). Section 3 covers this.
- **With the setting off** (`enabled = false`): the tile paint and tile model paths skip the lookup completely
  (`SU:655`, `SU:808/880`), so each tile keeps the client's own corner colours. **The terrain undo goes as well**
  (see Issue 2). But GroundBlender is **still built** (`SU:159-160`, "built with blending off too"), and it still
  feeds the water bed. With the setting off, `blend = !enabled || …` is true for every tile (`GB:233, 270`), so the
  overlay-wins shared colour is computed anyway. `shoreColor` (`SU:1082-1093`) reads it for the bed.
- **The water bed blends between tiles whatever the setting is** (`WaterMap.bedColor` averages the 2×2 water tiles
  around a corner, `water/WaterMap.java:715-750`). `bedCornerColor` mixes in the bank's colours from up to 2
  corners away (`SU:1014-1051`, `BANK_SHARE {1, .7, .35}`). The shore fan's middle and side-middle colours are
  `lerpHsl` of the corners (`SU:947, 966`). This is under the water, but it is still colour blended across tiles.
- The project's own docs already say "never blend terrain colours": `docs/render-plan.md:160-166` ("Never the vertex
  colour blending"), `:209` (listed under "not in the plan"), and `docs/old-renderer-vs-lumina.md:321` ("Ground colour
  blending … Strip"). Those docs describe the `lumina` branch (`DiffuseColors.terrainColor`), not this worktree. In
  this worktree the blending is live and on by default.

### 1. Tile vertex normals

**117 HD** (`PG:166-312`, TerrainNormalGenerator; it runs after the underwater generator, `PG:144-149`):
- Every tile on every level contributes. Bridges contribute twice: the bridge tile itself without the underwater sink
  (`PG:202-205`, `isBridge`). A tile paint contributes 2 triangles split on the nw–se diagonal: (ne, nw, se) and
  (sw, se, nw) (`PG:257-272`). A tile model contributes all of its faces, hidden and water faces included (`PG:240-255`).
- Before the cross product, the face's vertices are lowered by the underwater depth keyed at each vertex (`PG:279-283`).
- The face normal is `cross(B − A, C − A)`, **not normalized** (`utils/HDUtils.java:80-84`), so each face counts by
  its area. It is summed into the vertex's entry (same key as the colours: x, z, height; **no level** in the key).
  It is normalized once at the end (`PG:210-224`).
- A vertex is shared across tiles, tile paints and tile models, and zone borders (the whole scene is processed at once).
  One side effect: a tile-model vertex in the middle of an edge, next to a plain tile, sums only the tile model's own
  faces. The plain neighbour has no vertex there.
- Use: `getVertexNormalOrDefault(key, …, UP_NORMAL)`, with `UP_NORMAL {0, -1, 0}` (`ZSU:69, 967-970, 1321-1323`).
  Water-surface tiles get UP_NORMAL (`ZSU:966`). There is no flat/rough distinction for terrain: every tile is
  smooth-shaded by these sums.

**Ours**:
- Tile paint: `terrainNormal(surface, corner)` (`SU:745-756`). These are central differences of the corner heights,
  `dhdx = (h[x+1] − h[x−1]) / 256` and the same for z, giving the normal `(dhdx, −1, dhdz)` (clamped at the grid
  edge). `surface` is the level's heights **with the water bed sunk in** (`surfaceHeights`, `SU:739-743`). Normals
  are encoded octahedrally (`VertexShading.encodeNormal`, `scene/VertexShading.java:30`).
- Tile model: `modelNormal` (`SU:1191-1197`) interpolates bilinearly between the four corner slopes of the sunk
  surface (`surfaceSlopes`, `SU:810`), at the vertex's position within the tile. It does not use the face geometry
  at all. The comment at `SU:805-806` says why: "Its heights are bilinear in the tile's corner heights".
  **I did not verify that claim** against the client.
- Water bed: the same central differences on the bed heights (`SU:933-936`). The shore fan's middle and
  side-middles interpolate the corner slopes (`SU:948-950, 967-968`).
- Every terrain vertex carries a normal, so the shader never falls back to the face normal (`frag.glsl:176`).

**Net**:
- On a smooth height field the two give nearly the same normal: the six triangles round a corner sum to about the
  central difference.
- Ours is symmetric. 117 HD's is slightly anisotropic along the nw–se diagonal.
- Ours is seamless where a tile model meets a tile paint: at a mid-edge vertex, both sides interpolate the same two
  corners. 117 HD's mid-edge vertex is one-sided.
- 117 HD follows a tile model's real face geometry. Ours assumes the tile model is bilinear.

### 2. Tile vertex colours

**117 HD**:
- Colour pass: TerrainDataGenerator (`PG:579-780`), which runs after the normals.
  - Water tile paints are skipped (`PG:645-648`). Water faces of tile models are skipped (`PG:697-698`).
  - Scene-edge fix: within 2 tiles of the extended scene's far edge, the corners copy the sw/se/nw colours
    (`PG:657-668`).
  - Per vertex, skip a negative colour or a HIDDEN one (12345678) unless the override is `forced` (`PG:720-722`).
    This is checked per vertex, so a hidden tile paint (ne = HIDDEN) still adds its other three corners.
  - Low priority means `color <= 2` (`PG:726`).
- Undo of the vanilla bake (`PG:728-756`):
  - `dot = (n.x + n.z) / sqrt(2·|n|²)` over the vertex's **117 HD normal**, which includes the underwater sink.
  - `adjusted = (max(l − 3, 0) · 1.5 + 15)`, then `l = (int) mix(l, adjusted, max(dot, 0))`.
  - `darkened = (int)(l · 0.5)`, then `l = (int)(1.25 · mix(l, darkened, −min(dot, 0)))`.
  - `l = min(l, 55)`.
- Then `override.modifyColor(color)` (`PG:758`; `scene/tile_overrides/TileOverride.java:250-264`: shift then clamp
  hue, saturation and lightness).
- Overlay flag (`PG:761-765`):
  - Tile paint: `override.queriedAsOverlay`, which is true only if a tile override matched the **overlay** id
    (`scene/TileOverrideManager.java:255-271`). It is false for NONE.
  - Tile-model face: the `TILE_OVERLAY_TRIS` table (`PG:65-79`).
  - Either is XOR'd with `blendedAsOpposite`.
  - Both are set on the vertex **before** the default-colour check, so textured or unblended tiles still mark a
    border.
- Write rules (`PG:767-778`): overlay puts, underlay only fills an empty vertex, low priority never overwrites high
  priority. A vertex with a "default colour" is never written. Default colour comes from `useDefaultColor`
  (`PG:315-325`): any textured tile paint or tile model, or an override with `blended: false`.
- Upload (`ZSU:886-1205` uploadTilePaint, `ZSU:1208-1530` uploadTileModel):
  - Blending is allowed when the config's ground blending is on, the face is untextured, and it is not a
    default-colour tile (for tile models: not an overlay face of one) (`ZSU:982-987, 1337-1342`).
  - Where blending is allowed: the shared colour, `getOrDefault(key, own)` (`ZSU:1005-1010, 1358-1362`).
  - Otherwise: the **raw vanilla colour**, passed through `modifyColor` if an override exists (`ZSU:990-1000,
    1344-1354`). **No undo and no 1.25 / 55 cap on unblended tiles. With 117 HD's blending OFF there is no terrain
    undo at all.**
- Default config: `GroundBlending.ON` (colours and textures) (`HdPluginConfig.java:971-977`, `config/GroundBlending.java`).
- Data:
  - `scene/tile_overrides.json` has 1146 entries; 441 change colour, and 419 have `blended: false`. Nearly all are
    limited to an area.
  - The 31 `area: ALL` entries are mostly ground materials (textures). The colour-only ones among them:
    `LAVA` (19, set to white ×HD_LAVA), `VANILLA_BLACK_TILE` (37, maxLightness 0), `OVERLAY_SNOW` (maxLightness 90),
    `OVERLAY_CLEAN_WOOD_PLANKS` (−4).
  - 19 entries have `heightOffset`.

**Ours**:
- `GB.undoShading` (`GB:347-363`) has **exactly 117 HD's constants and integer truncations** (1.5, 15, 3, 0.5, 1.25,
  cap 55). The difference is the normal it uses: `(dhdx, −1, dhdz)` from the **unsunk vanilla heights**, by central
  differences (`GB:175-184`, `tileSlopes`/`interpolateSlope` for tile models, `SU:809, 1196`). The bed-sunk normal
  (which is what is drawn) is kept separate on purpose (`SU:89-91`).
- Applied:
  - to blended colours inside the hash (`GB:307-310`, only when `undoVanillaShading && enabled`);
  - to unblended tiles at upload (`SU:776-780`), **including the cap 55**.
  - Textured tiles are never undone.
- With `groundBlending` off, terrain is not undone at all (`SU:655, 880`).
- No `modifyColor`, no tile overrides, no ground materials (`tile_override` appears nowhere in `src/`).
- The never-blended overlays are a hard-coded list of 15 (`GB:66`).
- The overlay flag for tile paints is `overlayIds != 0` (`GB:231`). For tile models it uses 117 HD's table
  (`GB:31-44`).

### 3. Overlay/underlay boundary in the shader

**117 HD** (`scene_frag.glsl:290-355`): the per-vertex `MATERIAL_FLAG_IS_OVERLAY` means "this vertex is on an
overlay/underlay border" (it is set only when a vertex is both overlay and underlay, `ZSU:1026-1033, 1433-1438`).
- Barycentric weights are renormalized within the border group and within the non-border group.
- The two group colours are mixed by `clamp(2·w − 1, 0, 1)`. Here `w` is the summed weight of the group with fewer
  vertices: the border vertex when there is one, otherwise 1 minus the weight of the non-border vertex.
- Colours are converted to **linear** first (`:262-264`), and multiplied by the material texture before the mix
  (`:323-335`).

**Ours** (`vert.glsl:120-122`, `frag.glsl:150-158`): the vertex shader outputs `fBorderColor = rgb·b, b` and
`fInnerColor = rgb·(1 − b)`, where `b` = 1 on a BORDER vertex. The fragment shader computes
`mix(inner/(1 − w), border/w, clamp(2w − 1, 0, 1))`.
- **The same function as 117 HD's** for 1 or 2 border vertices out of 3.
- Differences: colours are interpolated in gamma-space `hslToRgb` output, with `pow(…, brightness)` already applied
  (`hsl_to_rgb.glsl:84`), not linear. The mix is active only when "Remove color banding" is on (`frag.glsl:154`;
  uniform = `config.smoothBanding() ? 0 : 1`, `LuminaPlugin.java:1581`; default on). There is no texture term.

### 4. Underwater, depth, height

**117 HD** (`PG:782-1112`):
- Every corner starts at depth level 1. Land and missing tiles reset their corners to 0. An upper-plane water tile
  over lower-plane water is skipped (TILE_SKIP_FLAG, `PG:869-893, 908-941`).
- Then 11 erosion passes: a corner goes up one level when its 4 neighbours are all at least its level (`PG:988-1015`).
  Corners on the scene edge are forced to 0, a "wall" (`PG:999-1004`).
- Depth = `DEPTH_LEVEL_SLOPE[level − 1] · 0.55` (`PG:61, 1105-1111`), up to 1380 · 0.55 = 759.
- Off-corner tile-model vertices are interpolated bilinearly unless the vertex is land (`PG:1062-1081`).
- The bed's colour is `UNDERWATER_HSL` = 6676 (h 6, s 4, l 20) everywhere, with the `UNDERWATER_GENERIC` ground
  material (`ZSU:1051-1060, 1400-1422`). The vertex terrain data carries the depth.
- Shader (`utils/water.glsl:318-353`): multiply by `waterType.depthColor` over depths 0-150, fade to black over
  150-500, black below 500, plus caustics.

**Ours** (`water/WaterMap.java`, SU `uploadBed`/`putBedVertex`):
- Depth level is a 4-connected BFS from land corners (`WaterMap.java:521-570`), capped at `MAX_LEVEL 8`.
- `DEPTH {82, 165, 258, 335, 385, 412, 451, 506}` (`:62-63`) are 117 HD's first 8 values · 0.55, truncated. Past
  level 8 ours stays at 506; 117 HD goes on to 759.
- "Off the grid is not land, so the sea deepens toward the scene edge" (`:518-519`), which is the opposite of
  117 HD's wall.
- Extra rules: `MIN_DEPTH 56` sinks the middle of shore tiles and their shared sides (`SU:938-996`,
  `WaterMap.sideOffset`). A water tile one tile wide holds water this way; in 117 HD every corner of such a tile is
  at depth 0.
- Bed colour by water type: `SAND` 8/2/40, `PALE_SAND`, `MUD`, `STONE` (`WaterMap.java:580-597`). It is averaged
  over the 2×2 tiles, gets ±4 jitter, and loses 2 lightness per depth level (`:715-750`). Bank colours grade into
  it over 2 corners (`SU:1014-1051`). Near-black bank colours are refused (`SU:1090-1092`, `MIN_SHORE_LIGHTNESS 3`).
- Shader: `waterDepthTint` is "117 HD's fade" (`water.glsl:156-162`). The depth used is smoothed by shore distance
  (`water.glsl:541-555`).
- Bank-side terrain normals lean into the bed (sunk surface) in both renderers. The undo slope does not in ours
  (section 2).

### 5. "Fixing the world"

| Rule | 117 HD | Ours |
|---|---|---|
| Hidden tiles | paint skipped if ne == HIDDEN (`ZSU:907`); model face skipped if colour A == HIDDEN (`ZSU:1267`); the colour pass still adds a hidden paint's other three corners (`PG:720`) | same skips (`SU:644, 842`); the blender skips the whole hidden paint (`GB:227`) |
| Gap fillers (holes round trapdoors and ladders) | config `fillGapsInTerrain` default true, area `MAINLAND` only (`ZSU:207-213`). A level-0 tile with no drawable paint and no model gets a black quad at its heights; a tile model's HIDDEN faces are drawn black (`ZSU:2446-2571`). Drawn with depth range just above the far plane, so they only show where nothing else drew (`renderer/zone/ZoneRenderer.java:992-996`) | **not found** |
| Upper floors seen from below | tiles not vis-below on level > 0 are uploaded double-sided, back faces with negated normals (`ZSU:1131-1201, 1505-1525`) | not found; the scene pass culls back faces (`LuminaPlugin.java:1606`) |
| Area colour/height fixes | 441 colour overrides, 19 `heightOffset` (`ZSU:1076-1079, 1440-1442`), `blendedAsOpposite` | none |
| Scene edge black | corner colours copied within 2 tiles of the far edge (`PG:657-668`) | not needed: EdgeFade ends the world at the draw-distance circle (`scene/EdgeFade.java`) |
| Black under walls | low priority ≤ 2 never replaces a proper colour; a proper underlay never replaces a black one | same, but a proper underlay **does** replace black (`GB:332-340`) |
| Tile paint vs tile model consistency | both read the shared colour and normal maps by key | colour by the shared key; normal by bilinear corner slopes (`SU:1191`), so paint and model agree at shared edges |
| Water | above | bed sink + MIN_DEPTH, shore fan, bank colour grading, ripples (SceneUploader / WaterMap) |

### 6. Textured ground (as far as colour and normal go)
- **117 HD**: ground materials (`scene/GroundMaterialManager.java`, `scene/ground_materials.json`, 744 lines) choose
  a texture per vertex position by hash (`ground_materials/GroundMaterial.java:44-52`). These textures are also
  blended across tiles ("textures" half of ground blending, `ZSU:1012-1023`).
  - In the shader the texture multiplies the vertex colour (`scene_frag.glsl:323-328`), and normal maps change the
    normal.
  - A vanilla-textured tile keeps its raw colour (no blending, no undo), and the vanilla texture's material
    multiplies it.
- **Ours**: no ground materials ("no texture images", `docs/render-plan.md:209`). A vanilla-textured tile is drawn as
  texture × (lightness/127) (`frag.glsl:142-145`), without the undo (`SU:655` requires texture == −1).
  - Colour-only effects of 117 HD's materials and overrides are therefore absent: LAVA turned white × lava texture,
    VANILLA_BLACK_TILE forced to lightness 0, KHARID hue shifts and the like.

### Difference table

| Quantity | 117 HD | Ours | Difference | Recommendation |
|---|---|---|---|---|
| Terrain normal | area-weighted face sums per vertex key, sunk positions, diagonal split (`PG:238-312`, `HDUtils.java:80`) | central differences of the sunk corner heights (`SU:745-756`), bilinear inside tile models (`SU:1191-1197`) | ours is symmetric and seamless at tile-model mid-edges; 117 HD follows a tile model's real faces and is one-sided at its mid-edges | **KEEP OURS, decided by the user (2026-10-31)**. Ours: the normal at every terrain vertex from central differences of the (sunk) height field across tile borders, `(dh/dx, −1, dh/dz)`, bilinear inside tile models. 117 HD: area-weighted sums of the faces around each vertex key. Vanilla / RuneLite's GPU plugin: the game's own per-tile shading baked into the colours. The user's evidence that ours is the better one: the back side of a boulder cliff seen from above and behind is shaded from its real slope here and not by 117 HD or the stock GPU plugin (`~/.runelite/lumina/bugs/terrain-cliff-back-ours-correct.png`). Do not move this toward 117 HD. Open check, unchanged: whether the client's tile-model vertex heights really are bilinear (`SU:805-806` assumes so) |
| Normal for the undo | the same sunk 117 HD normal (`PG:734-743`) | the unsunk vanilla slope (`GB:175-184`, `SU:657-669, 809`) | differs only beside water and at tile-model mid-edges | **KEEP OURS**. It is the surface vanilla lit; the bed is ours. (Whether the client lights by the same central differences is not verifiable in these two trees.) |
| Undo constants | 1.5 / 15 / 3 / 0.5 / ×1.25 / cap 55 (`PG:728-755`) | identical (`GB:48-53, 347-363`) | none | KEEP |
| Which tiles are undone | only the colours that go into the blend map; unblended / default-colour / textured tiles raw + `modifyColor`; nothing when blending is OFF (`ZSU:982-1010`) | every untextured tile while `groundBlending` is on, unblended ones included and capped at 55 (`SU:770-781`); nothing when it is off | ours darkens bright unblended floors to 55; ours cannot undo without blending | **OPEN QUESTION** (Issues 2 and 3): decouple the undo from blending; then decide whether unblended tiles are undone and capped **Done (8eb04057)**: undo decoupled from blending; 117 HD's raw set and its cap 55, since in 117 HD every tile it does not leave raw goes through its blend map and so through the undo with the cap. |
| Colour shared across tiles | on by default (`GroundBlending.ON`) | on by default (`LuminaConfig.java:379-390`) | same mechanism and priorities, plus our underlay-beats-black rule | **OPEN QUESTION for the user**. The user's rule and `docs/render-plan.md` say none: set `groundBlending` default false, or remove GroundBlender's colour sharing (keep its undo, see Issue 2) **Done (8eb04057)**: removed on land, the setting with it. |
| Never-blended set | `blended: false` per override and area (419) | 15 overlay ids (`GB:66`) | ours includes 117/137/171/179, which 117 HD blends at its default; ours lacks the ~400 area entries | moot if blending goes; else **COPY** the area ALL set exactly: {7, 13, 19, 27, 28, 37, 46, 128, 156, 163} + OVERWORLD {199} (`tile_overrides.json`, names OVERLAY_SWAMP_WATER, OVERLAY_CARPET, LAVA, OVERLAY_BRICK_BROWN, OVERLAY_28, VANILLA_BLACK_TILE, "Savannah Church - Stone tile pathway") **Done (8eb04057)**: the raw set is now exactly that. |
| Overlay classification (tile paint) | override matched by overlay id (`queriedAsOverlay`) | overlayId != 0 (`GB:231`) | ours marks more borders | **KEEP OURS** (data-true, no override table) |
| Border marks from textured tiles | yes (`PG:765` before the default check) | no (`GB:227, 265`) | no soft edge where a path meets a textured tile | minor; COPY only if blending stays: in `GB.addTile`, still OR `TOUCHES_*` for textured paints and faces, with `blend = false` Moot after 8eb04057 (no border marks). |
| Border mix (shader) | linear colours, renormalized groups, `clamp(2w−1)` (`scene_frag.glsl:290-355`) | same function, gamma-space colours, only with "Remove color banding" on (`frag.glsl:150-158`) | colour space | goes with the blending decision. If kept: KEEP (same math) Removed in 8eb04057 with the sharing. |
| Tile overrides (`modifyColor`, `heightOffset`) | 1146 entries (`TileOverrideManager`) | none | per-area colour/height fixes missing | **OPEN QUESTION** (A2 "117 HD's terrain rules"). The area-ALL colour-only rules are small: LAVA (setLightness 127 via shift, hue/sat 0), VANILLA_BLACK_TILE (maxLightness 0), OVERLAY_SNOW (maxLightness 90), OVERLAY_CLEAN_WOOD_PLANKS (shiftLightness −4). They would go in `SU.groundColor` after the undo, as `modifyColor` (`TileOverride.java:250-264`) |
| Gap fillers | black at the far plane under holes, MAINLAND (`ZSU:2399-2593`, `ZoneRenderer.java:992-996`) | not found | holes show what the composite puts where nothing drew | **COPY** candidate (or OPEN, since ours has no areas): per zone, for level-0 tiles with no non-hidden paint and no model, two triangles at the tile heights, colour 0 (or the cave colour), drawn in a pass with `glDepthRange` just above far. Same for a tile model's HIDDEN faces (`ZSU:2446-2506`) |
| Double-sided upper tiles | `!isTileVisBelow && tileZ != 0` (`ZSU:1131, 1505`) | single-sided, culled | upper floors and bridges vanish from below | **COPY**: in `SU.upload` (paint, after `SU:720`) and the model loop (after `SU:907`) also emit the reversed triangles with negated normals when `(settings[tileZ][x][y] & TILE_FLAG_VIS_BELOW) == 0 && tileZ != 0`; the zone size estimate (`SU:319-334`) must count them |
| Bed depth | 12 levels ·0.55, edge = wall (`PG:61, 988-1015`) | 8 levels, edge deepens, MIN_DEPTH middles (`WaterMap.java:62-65, 518-570`) | deep sea capped at 506 vs 759; edge rule opposite | KEEP OURS (water session's design); depth cap is an OPEN detail |
| Bed colour | UNDERWATER_HSL 6676 flat + material (`ZSU:1051`) | per-type sand/mud/stone, 2×2 averaged, bank-graded (`WaterMap.java:715-750`, `SU:1014-1051`) | ours blends colour across tiles under water | **OPEN QUESTION**: does "no blending between tiles" cover the bed? |
| Scene-edge colour copy | `PG:657-668` | none | — | KEEP OURS (EdgeFade hides the edge) |
| Hidden tile paint in the blend map | three non-hidden corners still added (`PG:720`) | whole tile skipped (`GB:227`) | — | KEEP OURS |

### Issues ours still has (from the code)

1. **Blending on by default against the user's rule.** `LuminaConfig.java:387-390` returns true. `GB:26` defaults
   true. `LuminaPlugin.java:728`.
2. **The terrain undo is tied to blending.** The tile paint undo runs only inside `if (GroundBlender.enabled &&
   tile.getTexture() == -1)` (`SU:655`). The tile model undo runs only inside `if (blending && tex == 0)`
   (`SU:808, 880`). The blend-map undo requires `enabled` (`GB:307`). Turning blending off (the rule) also brings
   vanilla's baked terrain light back, and `removeBakedShading` cannot stop that. The setting's description says
   so itself (`LuminaConfig.java:383-384`).
   - Minimal fix: guard on `VertexShading.undoVanillaShading && texture == -1`, and call `groundColor(own, -1, false)`
     when blending is off, so `SU:776-780` undoes each tile's own colour.
3. **Unblended tiles are undone and capped at 55, where 117 HD leaves them raw.** `SU:776-780` calls
   `GB.undoShading`, which clamps to `MAX_LIGHTNESS 55` (`GB:361`). 117 HD's unblended tiles get no undo and no
   cap (`ZSU:990-1000`). Bright crisp floors (white church tiles 199, marble, tiled floors) are darkened. Once
   Issue 2 is fixed this applies to every tile, so the cap needs a decision then.
4. **The never-blended list is not what its comment says.** `GB:64-66`: "Overlays 117 HD never blends anywhere" is
   wrong for 117, 137, 171 and 179. 117 HD blends them at its default (`OVERWORLD - COBBLESTONE_PATHS` and
   `OVERLAY_N119` switch to `blended: false` only when `blendingTexturesOnly`, `utils/HDVariables.java:57-58`).
   7, 128 and 156 are swamp water (they become bed in ours anyway).
5. **The water bed blends colours across tiles whatever `groundBlending` is set to.** `WaterMap.java:715-750`,
   `SU:1014-1051, 947, 966, 1178-1181`. GroundBlender is built and shared with blending off (`SU:159-160`;
   `GB:233, 270` `!enabled ||`).
6. **Textured tiles do not mark overlay/underlay borders** (`GB:227, 265`). 117 HD does (`PG:765`).
7. **No gap fillers.** Nothing under holes (trapdoors, ladders, hidden tile-model faces). Not found in `src/`.
8. **Upper-level terrain is single-sided.** It is culled from below (`LuminaPlugin.java:1606`). 117 HD double-sides
   it (`ZSU:1131`).
9. **Tile-model normals ignore the model's faces.** `SU:1191-1197` assumes bilinear heights (comment `SU:805-806`,
   `GB:206-207`). This is unverified. If a shaped tile's vertex heights differ from bilinear, its shading and its
   geometry disagree.
10. **The border mix is gated on "Remove color banding"** (`frag.glsl:154`). With that off, paths lose their
    defined edge but keep the shared colours. It also interpolates in gamma space, not linear like 117 HD
    (`scene_frag.glsl:262-264`).
11. **No tests cover GroundBlender or `undoShading`.** Nothing in `src/test` references either.
12. **The vertex key carries no level**, so colours can merge across levels: a level-1 floor corner at the same
    x, z and height as a level-0 one shares its colour (`GB:367-370`). 117 HD has the same key and the same
    behaviour. The normals do not merge: ours are per level (`SU:739-743`), 117 HD's keyed sums do merge.

### Bottom line for the doc
- Normals: ours is at least as good. It is symmetric, seamless between tile paint and tile model, and its undo uses
  the surface vanilla lit. 117 HD's only edge is following a tile model's real faces.
- Colours: the undo arithmetic is 117 HD's exactly. But ours (a) blends between tiles by default, against the
  user's rule; (b) can only undo while blending; (c) undoes and caps tiles 117 HD leaves raw.
- What 117 HD has for "fixing the world" that we lack: gap fillers, double-sided upper tiles, and per-area tile
  overrides (colour, height, blend flags).
- What we have that 117 HD lacks: the bed sink with MIN_DEPTH and a shore fan, bank-graded bed colours, and an
  edge fade instead of the edge-colour hack.

## 2. Foliage and objects; wind


Done in this branch and audited in docs/117hd-model-shading.md (normals, flat rule, shader use, undo, textured 90,
flatNormals/upwardsNormals/undoVanillaShading flags). What remains different:

| quantity | 117 HD | ours | difference | recommendation |
|---|---|---|---|---|
| material on untextured faces | override `baseMaterial` (trees `BARK`, `LEAF_VEINS`, grass `GRASS_BLADE`, rocks `ROCK_1`…) puts a colour texture on untextured faces with generated UVs (`uvType` BOX etc.), multiplied with the vertex colour (`scene_frag.glsl:281–328`), plus its normal map (`utils/normals.glsl` `sampleNormalMap`, e.g. `BARK_N`, `LEAF_VEINS_N`) and specular (`BARK` 0.3/30, `LEAF_VEINS` 0.15/20; `scene_frag.glsl:394–417, 463–465`); `brightness` scales the texture (`LIGHT_BARK` 1.277) | none: untextured faces are their vertex colour; no normal maps, no specular on models | 117 HD's trees are textured, normal-mapped and glossy; the normal maps break up each facet's flat lighting, which is what hides the facets of flat-marked canopies there | OPEN: a feature, not a copy — 117 HD's textures (scene/textures, CC0/BSD/permission per 000_licenses.txt), a texture array of them, the UV generation (`ModelOverride.fillUvsForFace`, `UvType`), normal mapping (TBN from `cotangent_frame`) and specular |
| inheritTileColorType | grass, ground clutter, Vampyrium/crystal trees take the ground tile's colour: UNDERLAY 59 overrides (short grass 149 + 158 objects, hunter grass, jungle grass, vines with grass), OVERLAY 12 (dirt clumps, grunge, rocks); keepShading (no undo) (`SceneUploader.java:1733–1793`): the tile paint's four corners averaged, or the tile model's first underlay (overlay) face colour A, through the tile override; static uploads only | none (HANDOVER A2 "ground-matched foliage" is the wanted item) | our grass keeps the model's own green; 117 HD's matches the tile under it | OPEN (question 16): a recolour, not a shading rule; it reads the tile's *raw* colour (117 HD 1739-1745), which after 8eb04057 is what ours would read too; needs the tile at `uploadZoneRenderable` |
| doubleSidedFaces | reversed triangle with negated normals (`SceneUploader.java:1926–1951`), 36 overrides incl. jungle trees, palms, Gnome Stronghold leaves, redwood tops | not drawn: back faces are culled | the back of those leaves is invisible in ours | OPEN: extra triangles change the zone size count (the 2026-10-30 overflow crash came from that count) |
| transmission (leaves lit from behind) | `subsurface` only on glass and sail cloth materials | `transmissive` on 1,932 foliage objects and 35 leaf textures (materials.json, import_117hd_materials.py) | ours lights leaves through from behind | KEEP OURS (our addition, its own mechanism; 117 HD has none to copy) or OPEN if the user wants strict parity |
| hideVanillaShadows | drops the game's baked shadow faces (transparency > 100, flat at ground level; `HDUtils.isBakedGroundShading`, `SceneUploader.java:1730-1731`) on 22 overrides | not found: the game's fake shadow faces are drawn | a dark translucent patch under those objects in ours, on top of our real shadow | OPEN (a face-hiding rule, its own segment; the flag is not in model_shading.json) |

### Wind and normals

117 HD's default zone renderer has **no wind displacement**: `scene_vert.glsl` (zone branch, 64–131) passes the
position through; wind exists only in the legacy compute path (`priority_render.glsl:225–331`
`applyWindDisplacement`), which moves positions and only rotates normals by the orientation (378–380): **no wind
tilts a normal in 117 HD**. JIGGLE uses the normal only to pick the skew direction (276–290).

Ours (`sway.glsl:37–75`, `vert.glsl:109`): our own sway model ("after 117 HD's"), on static swaying objects; it
tilts the normal with the bend (`WIND_NORMAL_TILT 1.0`, 70–73), for faces that carry a normal — after this branch,
every model face (flat faces now carry their face normal), so flat faces of swaying foliage tilt too.

| quantity | 117 HD | ours | recommendation |
|---|---|---|---|
| wind displacement | none (zone); legacy compute only | ours, static swaying objects | KEEP OURS (the user's wind; not a shading quantity) |
| normal tilt with the bend | none | `normal.y += dot(slope, normal.xz)` | OPEN: 117 HD's code has no tilt (copy = delete 4 lines); ours keeps the lit normal consistent with the sheared geometry, at slopes of a few hundredths |

## 3. The surface lighting equation

Read-only audit. 117 HD = `~/Code/rlhd-ref` at 7cadf3bd (branch `ref-117hd-tools`; this build has the daylight
cycle / `SkyRenderer` / `SkyManager`). Lumina = worktree `shading-parity-b43a20` at 334228ec. Paths below are short:
`HD/` = `~/Code/rlhd-ref/src/main/`, `L/` = `src/main/` of the worktree (shaders in
`L/resources/com/midonarhel/lumina/`, abbreviated to the file name). Nothing was run in a client; numbers are
arithmetic from the constants (script: scratchpad `noon.py`).

### 0. The two equations

**117 HD** (`HD/resources/rs117/hd/scene_frag.glsl`, opaque and transparent faces alike, one pass):

```
albedo   = srgbToLinear(hslToSrgb(convertHsl(hsl)))  [per vertex, blended in linear]  * texture(linear) * material.brightness   (246-266, 281-355)
light    = ambientColor*ambientStrength * materialAO                                     (423-435)
         + max(N.L,0) * lightColor*lightStrength * (1 - shadow)                          (438, 458-461)
         + Phong specular (sun) + point lights (diffuse + specular + transmission)       (464-472, utils/lights.glsl)
         + 0.5 * fogColor * max(dot((0,-1,0), N), 0)        "sky light"                  (475-478)
         + 0.25 * lightningBrightness * max(dot((0,-1,0), N), 0)                          (482-485)
         + underglowColor*underglowStrength * max(N.y, 0)                                 (494)
         + transmission (subsurface materials only)                                       (488-491)
         + 0  (fresnel: surfaceColor = vec3(0), dead)                                      (498-502)
colour   = linearToSrgb(albedo * mix(light, 1, unlit))                                    (529-530)
colour   = fog mixes (distance fog in sRGB, ground fog in linear)                         (553-601)
out      = clamp → saturation/contrast (identity at defaults) → pow(gamma = 100/brightness = 1) → ±0.5/255 dither  (604-605, utils/output_transform.glsl)
```

**Lumina** (deferred: albedo in an RGBA8 target from `frag.glsl`, lit per pixel in `composite_frag.glsl` `shadeScene` 291-489):

```
albedo   = pow(hslToRgb(hsl), brightness)  [per vertex, interpolated in sRGB]  (vert.glsl:90, hsl_to_rgb.glsl:84)
           textured: pow(tex, brightness) * (lightness/127)  in sRGB         (frag.glsl:140-145)
indirect = (ambientLight + skyFill(n)) * mix(0.4, 1, skyVis) * envAmbient * ao                       (372-373)
         + sun * envSun * max(N.L,0) * (1 - shadow) * 0.7 * skyVis * ao      "daylight fill"         (375-376)
         [rooms: max(indirect, roomDaylight*0.3*enclosed*ao) + interiorTint*roomFill]               (377-389)
light    = indirect + sun * envSun * max(N.L,0) * shadow * ao   (AO_DIRECT 1.0)                      (391-392, 48)
         + transmission (leaf faces) * shadow                                                       (393-396)
         + lamps (wrap, wall/floor occlusion) * ao + lamp transmission                              (408-413)
light    = mix(light, 1.2*emissiveStrength, emissive)                                               (422)
colour   = shoulder(srgbToLinear(albedo) * light) → linearToSrgb                                    (456-460, sky.glsl:392-399)
colour   = mix(colour, fogColor, distanceFog) in sRGB; edge fade to sky; volumetric fog              (473-487)
out      = written as is: no gamma/contrast/saturation, no dither on surfaces
```

### 1. The points to settle

#### 1.1 Colour space each side lights in
Both light in **linear** and store/display sRGB.
- 117 HD: colours go to linear before anything is multiplied (`scene_frag.glsl:264-266`, `srgbToLinear(hslToSrgb(...))`);
  textures are `GL_SRGB8_ALPHA8` so sampling returns linear (`HD/java/rs117/hd/scene/MaterialManager.java:360`);
  light colours are linear (`Environment.java` fields carry `@JsonAdapter(SrgbToLinearAdapter.class)`); back to sRGB
  at `scene_frag.glsl:530`.
- Lumina: albedo is computed and interpolated in sRGB and stored in an RGBA8 target (`SceneTargets.java:15`);
  `shadeLinear` converts it to linear and multiplies by the linear light (`sky.glsl:395-399`); back to sRGB at
  `composite_frag.glsl:460/480` or in `applyFogLinear` (`fog_apply.glsl:39-41`). Same sRGB curves on both sides
  (piecewise, 0.04045 / 0.0031308): `color_utils.glsl:116-128` = `sky.glsl:240-246`.
- Difference: only *where* vertex colours are interpolated. 117 HD converts each of the three vertex colours to
  linear and blends them in linear (`fAlphaBiasHsl` is flat, blended by `fTexBlend`, 259-266, 334-355). Lumina
  interpolates sRGB (`fColor`, vert.glsl:118; frag.glsl:149 with smoothBanding default on = `fColor`), then
  linearises per pixel. Effect: across a triangle whose corners differ strongly in lightness the middle is slightly
  darker in Lumina (sRGB average linearised is below the linear average); invisible on flat colour, small on
  terrain gradients.

#### 1.2 HSL → RGB
- 117 HD: `convertHsl` (H/64+1/128, S/8+1/16, L/128; `color_utils.glsl:244-250`) then `hslToSrgb`
  (`color_utils.glsl:186-195`), then `srgbToLinear`. No brightness curve.
- Lumina: `hslToRgb` (`hsl_to_rgb.glsl:26-84`, the GPU plugin's Jagex port) **then `pow(rgb, brightness)`** (line 84)
  with `brightness = textureProvider.getBrightness()` (`LuminaPlugin.java:1580`); textures likewise
  `pow(texture, brightness)` (`frag.glsl:140`).
- The HSL maths is identical: checked exhaustively over all 65,536 packed HSL values, max difference 7e-16.
- The difference is the brightness curve. At the user's 0.8 (docs/old-renderer-vs-lumina.md:137) it multiplies the
  linear albedo by 1.87x at sRGB 0.2, 1.59x at 0.33, 1.35x at 0.5: dark colours are lifted most, so Lumina's
  albedo is brighter and flatter (less contrast between dark and light materials) than 117 HD's. The lumina branch
  already dropped it (docs/old-renderer-vs-lumina.md:137 and :322 "Keep as Lumina has it (dropped)"); this branch,
  being perf-wip's shading, still has it.
- Textured faces: 117 HD multiplies the linear texel by `material.brightness` and by colour 90 converted to linear =
  (0.480, 0.429, 0.426) — a faint warm grey, since S has the 1/16 offset. Lumina multiplies `pow(texel, 0.8)` by
  90/127 = 0.709 in sRGB, neutral grey. For the same texel Lumina is 1.89x (sRGB 0.3) / 1.50x (0.5) / 1.28x (0.7)
  brighter in linear; without the pow it would be 1.18 / 1.12 / 1.10x (the 90/127-in-sRGB vs 90/128-to-linear gap).
  117 HD also swaps many vanilla textures for its own materials (not compared here).

#### 1.3 Sun direction at noon
- 117 HD: `uboGlobal.lightDir` = the shadow camera's forward (`ZoneRenderer.java:674`), whose orientation is
  `{PI - azimuth, altitude}` of `state.shadowAngles` (`SkyManager.java:537-549`). With the default
  `daylightCycle = DEFAULT` (`HdPluginConfig.java:670-671`) the cycle is active in overworld environments
  (`SkyManager.java:304`) and `shadowAngles = sunAngles` (329), the real sun from `AstronomyUtils` at New York
  (40.71 N, `SkyManager.java:43`) on a clock running 24x real time (`SkyManager.java:381`, one day per real hour,
  so the date runs too). Noon altitude is therefore 90 − 40.7 ± 23.4 = **26° to 73° depending on the simulated date
  (49° at an equinox)**, azimuth south (180°, measured from north through east, `SkyManager.java:422-428`). Only
  below 2° is the altitude bent (asymptote, `getShadowAltitude` 527-535); no upper clamp. Shading and shadows use
  the same direction. With the cycle off, or a fixed preset, the environment's `shadowAngles`: default and OVERWORLD
  **52° altitude, 235° azimuth (south-west)** (`Environment.java:25`, `environments.json:3266-3269`); the `DAY`
  preset is the same 52°/235° (`sky_presets.json`).
- Lumina: `DayCycle.update` (`DayCycle.java:82-104`): visual altitude = 70 sin(2π phase) + 12, peak 82° at 12:40,
  **81° at 12:00**, azimuth south (10° east of south at 12:00). Shading uses **`casterDir`**
  (`composite_frag.glsl:359`, `frag.glsl:185`), the caster elevation **clamped to 30–68°** (`DayCycle.java:21-23, 98`),
  so at noon N.L is taken from **68° elevation**; the visual `sunDir` (81°) is used only for the sky.
- Difference: at noon Lumina's light is steeper than 117 HD's DAY preset (68° vs 52°): flat ground gets sin 68 =
  0.93 of the sun vs 0.79; a wall facing the sun gets cos 68 = 0.37 vs 0.62. Walls facing the sun are ~40 % darker
  relative to the ground than in 117 HD's DAY capture. Lumina never goes below 30° for shading (long low-sun
  raking light on walls at dawn/dusk never happens; 117 HD's does), and never above 68°.

#### 1.4 Ambient vs directional at noon (luminance of linear light, overworld, open sky, no AO/shadow)

| | 117 HD DAY 52° | 117 HD equinox noon 49° | Lumina 12:00 (N.L at 68°) |
|---|---|---|---|
| sun (directional) | 3.81 (white × 4, atmosphere 0.95) | 3.78 | 2.64 (2.9 × (1, .90, .76)) |
| ambient | 0.477 (#97baff linear × 1, × scattering) | 0.475 | 0.302 ((.30,.38,.58) × 0.80) |
| sky light on an up face | 0.328 (0.5 × #B9D6FF linear) | 0.328 | 0.094 analytic (0.45 × linear zenith); ~0.1 from the cache (estimate) |
| sun / ambient | **8.0** | 8.0 | **8.75** |
| flat ground, lit | 3.81 | 3.67 | 2.85 |
| flat ground, in shadow | 0.81 (ratio 4.7:1) | 0.80 | 0.40 without the fill; **2.11 with the daylight fill** at sky visibility 1 (ratio 1.35:1) |
| wall facing the sun | 2.82 | 2.94 | 1.29 |
| wall facing away | 0.48 | 0.48 | 0.30 (+ ~0.03 sky cache sides, estimate) |

Sources: 117 HD `SkyRenderer.java:164-191, 283-318, 320-358, 371-450, 486-500`, `Environment.java:140-150`,
`ColorUtils.java:30-36` (optical depths 0.131 / 0.181 / 0.321); Lumina `sky.glsl:231-238, 249-270, 315-323`,
`atmosphere.glsl:21-25, 53-70`, `composite_frag.glsl:369-392`.
117 HD's sky light colour is `uboGlobal.fogColor` = the sky gradient's horizon (`SkyRenderer.java:358`), which at
altitudes ≥ 40° is fully the default sky colour "117 HD Blue" (185, 214, 255) → linear (0.485, 0.672, 1.0)
(`SkyConfiguration.java:80, 248-252` takeover 40°; `DefaultSkyColor.java:36, 53-66`; `EnvironmentManager.java:386-390`).
Lumina's overall light is ~0.7x 117 HD's at noon (sun 0.69x, ambient 0.63x, sky 0.29x), which the brightness pow on
albedo (1.35–1.87x) partly compensates — two mechanisms that only agree for mid colours.

#### 1.5 Is the normal used for ambient?
- 117 HD: **no**. `ambientLightOut = ambientColor * ambientStrength * aoMap` (423-435) has no N. The normal enters the
  sky light as `max(dot(downDir, N), 0)` with `downDir = (0,-1,0)` (103, 374, 477-478) — in RS coordinates y points
  down, so `downDir` is *up* and this is the up-facing share (1 on the ground, 0 on walls, 0 on undersides);
  lightning the same (484); underglow `max(N.y, 0)` = the down-facing share (494).
- Lumina: **no** for `ambientLight` (a constant per frame, `sky.glsl:261-270`). The sky fill takes `up = max(-n.y, 0)`
  (`composite_frag.glsl:370`) = same shape as 117 HD's sky light for the analytic path (`atmosphere.glsl:54`); the
  sky cache path (default outdoors: `LuminaPlugin.java:2212` returns `MODE_LIGHT`) looks the irradiance up by the
  full normal and gives sideways and downward faces `0.25 ×` (`mix(up, 0.5 + 0.5·up, SKY_CACHE_SIDES 0.5)`,
  `atmosphere.glsl:63`). The skylight visibility (`skylight_ao.glsl:233`) also offsets by the normal.

### 2. Term by term

**Albedo** — see 1.1–1.2. 117 HD per-vertex linear blend, overlay/underlay mix in linear (299-355); Lumina the same
overlay rule in sRGB (`frag.glsl:150-158`). Brightness curve: Lumina only.

**Normal** — 117 HD `N = normalize(IN.normal)` (209), normal maps per material (357-371), `upwardsNormals` flag →
(0,-1,0) (359-360). Lumina: the uploaded vertex normal, derivative normal only where 0 (`frag.glsl:173-176`), now the
game's normals / face normals and the 117 HD flags on the CPU (HANDOVER row 112). No normal maps. Effect: HD
materials with normal maps (ground, rocks) have per-texel relief in 117 HD only.

**Sun / moon (directional)** — 117 HD: `lightColor * lightStrength` = environment `directionalColor` (#ffffff) ×
`directionalStrength` (OVERWORLD 4, default 0.25 elsewhere: `Environment.java:67`, environments.json:3265),
attenuated by air mass per channel (`applyAtmosphere`, 486-500: `exp(-depth·(airMass − 1))`, visible from 0° to 0.5°),
handed over to the moon below the horizon (`SkyRenderer.java:336-357`, moon strength 0.0008 × moonDiskStrength 6.2 ×
size² × illumination, `SkyConfiguration.java:109-111`), night exposure on the light values (`SkyRenderer.java:416-430`).
`lightOut = max(N.L, 0) × colour × (1 − shadow)` (461). Lumina: `sunLightCover` 2.9 × warm (1, .42, .16) → noon
(1, .90, .76) over 2–28°, × smoothstep(−4, 10) (`sky.glsl:249-254`); moon 0.675 × (.42, .55, 1.0) × smoothstep(−2, −14)
(256-258); caster switches at −3° (`DayCycle.java:27, 95`); × area `envSun` (`environment.glsl:6`).
Difference: 117 HD's sun is near white and 1.44x stronger at noon; Lumina's is yellow (b/r 0.76) all day. Effect:
Lumina's lit faces are warmer and dimmer; 117 HD's whiter with more punch.

**Ambient** — 117 HD: `#97baff` → linear (0.309, 0.491, 1.0) × 1 (OVERWORLD; blue/red 3.2), × atmospheric scattering
(≈1 at high sun, falls at twilight), + night airglow 0.005 × #94B8FF and moon ambient, + 0.25 × the directional light
the sun/moon handoff suppresses (`SkyRenderer.java:355-357`). One constant per frame, × material AO map only.
Lumina: (0.30, 0.38, 0.58) × 0.80 clear-sun dip (`sky.glsl:235, 261-270`; blue/red 1.9) × 1.5 at night × dusk tint,
then × `mix(0.4, 1, skyVis)` (`skylight_ambient.glsl:6-15`) × `envAmbient` × SSAO, plus the daylight fill, plus room
daylight/fill (`composite_frag.glsl:372-389`). Difference: 117 HD's ambient is 1.6x brighter and much bluer; Lumina's
is cut to 40 % indoors/under cover by the sky visibility and by SSAO. Effect: faces away from the sun are ~37 %
darker in Lumina (0.30 vs 0.48) and less blue; interiors and overhangs much darker than 117 HD's (which has no
occlusion of ambient at all).

**Sky light** — 117 HD: `0.5 × fogColor × max(up, 0)` (475-478); fogColor = horizon colour, at noon the 117 HD Blue
(lum 0.66 → 0.33). Lumina: analytic `0.45 × linear(zenith)` (`sky.glsl:238, 315-323`; at noon (0.018, 0.093, 0.320),
lum 0.094, very blue) or the cache's cosine-weighted sky × 0.40 (`atmosphere.glsl:21, 53-70`), blended by
`skyCacheWeight`. Difference: 117 HD's is 3.5x brighter on up faces and paler; Lumina's cache also lights walls
(0.25x) where 117 HD gives walls none. Effect: ground and roofs in shade are much lighter and bluer-white in 117 HD.

**Daylight fill (Lumina only)** — `indirect += sun × envSun × max(N.L, 0) × (1 − shadow) × 0.7 × skyVis × ao`
(`composite_frag.glsl:375-376`, from 0b786df2 "retaining skylight and GI improvements"); on whenever outdoors
(`LuminaPlugin.java:1805-1806`, no setting). 117 HD: not found (no bounce term). Effect: a shadow on open ground
(sky visibility ~1) removes only 30 % of the sun: shadowed ground 2.11 vs lit 2.85 (1.35:1), where 117 HD has
0.81 vs 3.81 (4.7:1). This is the largest single contrast difference; shadows read faint and grey in Lumina except
where the sky is blocked (under canopies, against walls).

**Underglow** — 117 HD: `underglowColor × max(N.y, 0) × underglowStrength` (494); strength 0 by default
(`Environment.java:78`), non-zero only in THE_INFERNO and TRUE_BLOOD_ALTAR. Lumina: not found
(`tools/import_environments.py:24` lists underglow as not imported). Effect: only in those two areas.

**AO** — 117 HD: material AO maps only (`ambientOcclusionMap`, 425-435), on the ambient only. No SSAO (not found in
the zone renderer's scene shader). Lumina: SSAO at half res (`ao_frag.glsl`, radius 75 % of a tile =
`LuminaConfig.java:349-351`, strength 100 %, `LuminaConfig.java:335-337`), applied to ambient, sky, **direct sun and
lamps** (`AO_DIRECT 1.0`, `composite_frag.glsl:48`, comment "physically 0"), plus the voxel sky visibility on
ambient+sky (floor 0.4). Effect: contact darkening and darkened sunlit creases that 117 HD does not have.

**Shadow** — both: directional × (1 − shadow), shadow ∈ [0, 1] (117 HD `shadows.glsl:62-145`, PCF/PCSS, distance
fade, terrain shadow map; `scene_frag.glsl:381-386, 458`). Lumina: half-res PCSS in the AO pass
(`ao_frag.glsl`, `shadow_pcss.glsl`), × cloud shadow (366), softened by overcast/haze (`sky.glsl:209-213`); but see
the daylight fill. Transparent faces: 117 HD receives shadows (same shader); Lumina's alpha path has none
(`frag.glsl:181-188`, comment line 66). Effect: windows/water-like alpha faces stay sunlit inside shadows in Lumina.

**Point lights** — 117 HD: `color × strength` (linear, lights.json) × `(1 − sqrt(d²/r²))²` × `max(N.L, 0)` + Phong
specular + thin-sheet transmission; no occlusion, no AO (`utils/lights.glsl:16-41`, `ZoneRenderer.java:823-835`).
Lumina: same colours (linear, `LightDefinition.java:140-143`) and falloff (`light/lamp.glsl:74-78`), but
`lampWrap` (a ball of 8 % of the reach wraps N.L past the horizon, 91-94), wall/floor occlusion, × AO
(`composite_frag.glsl:410`), no specular. Effect: lamps light past a face's horizon a little and stop at walls in
Lumina; no lamp glints.

**Emissive / unlit** — 117 HD: `mix(compositeLight, vec3(1), unlit)` (515-529): exactly the albedo. Lumina:
`mix(light, 1.2 × emissiveStrength, emissive)` (`materials.glsl:8, 28-30`), emissive in thirds. Effect: Lumina's
emissive faces are 1.2x (then shouldered) — slightly brighter.

**Transmission / subsurface** — same formula and constant: `-N.L × subsurface × 1.5 × max(dot(−L, V), 0)` × directional
(with shadow) (117 HD `lights.glsl:9-13`, `scene_frag.glsl:488-491`; Lumina `materials.glsl:10, 22-26`,
`composite_frag.glsl:393-396`). Differs in which faces: 117 HD materials with `subsurface` (3 entries in
materials.json), Lumina leaf faces (SurfaceMaterials) × `transmissionStrength`. Effect: Lumina's foliage glows from
behind; 117 HD's does not.

**Specular** — 117 HD: Phong `pow(clamp(dot(V, R)), gloss) × strength` per material (`specular.glsl:27-31`,
143 materials with `specularStrength`), roughness maps, and every semi-transparent face gets gloss 30, strength
`(1 − alpha) × 2` (407-416), for the sun (464-465) and for each lamp. Lumina: not found outside water. Effect: no sun
or lamp glints on HD materials or windows in Lumina.

**Fresnel** — 117 HD computes it but `surfaceColor = vec3(0)` so `surfaceColorOut = 0` (497-502): no effect.
Lumina: none. Nothing to port.

**Lightning** — 117 HD: `0.25 × lightningBrightness × up` (482-485) plus the fog colour flash. Lumina: the bolt
takes over the caster direction and colour (`sky.glsl:106-113`, `DayCycle.applyStrike`), ambient dips 35 %
(`sky.glsl:100-104`). Different mechanisms; both brief.

**Fog** — 117 HD: `calculateFogAmount` (`utils/fog.glsl:38-88`): max of a linear fog over the last 15 % of the draw
distance, an exponential fog (density fogDepth/100, start 0.3 → 0 of the distance as fogDepth grows, vertical
distance halved) and a 5-tile edge fog; OVERWORLD fogDepth 6.5 (DYNAMIC mode, `HdPluginConfig.java:861-863`) ×
min(drawDistance, 90)/10 (`ZoneRenderer.java:690-704`); colour = the sky seen in the view direction
(`foggedSkyColor`, `utils/sky.glsl:120`) with the cycle on, mixed in sRGB (594-595); ground fog in linear (opacity 0
in OVERWORLD). Lumina: the exponential part only (`environment.glsl:43-53`, its comment cites 117 HD), density from
the area (OVERWORLD fallback 0, biomes 0, caves 20-35), colour = the horizon of Lumina's palette or the area's fog
colour (`environment.glsl:29-39`), mixed in sRGB (482); the world's edge fades to the sky behind (`edge_fade.glsl`);
plus volumetric fog (default on, `LuminaConfig.java:680-708`). Effect: 117 HD hazes the overworld's distance toward
the sky colour of each direction; Lumina's overworld has no distance fog, only its volumetric fog and the edge fade.

**Exposure / tone map** — 117 HD: none on surfaces (tone_mapping.glsl is included only by `utils/sky_sampling.glsl`);
values above 1 clip at `applyColorAdjustments`' clamp; night exposure is applied to the light values on the CPU
(`SkyRenderer.java:416-430`). Lumina: soft shoulder above 0.75 linear, `1 − 0.25·exp(−(c − 0.75)/0.25)`
(`sky.glsl:391-399`), clamp; night is `NIGHT_LIGHT_BRIGHTNESS 1.5` on the ambient (`sky.glsl:33, 264`). Effect: bright
sunlit surfaces roll off in Lumina and clip to white in 117 HD (117 HD's sunlit ground at 3.81 clips any albedo
above linear 0.26 ≈ sRGB 0.55).

**Output** — 117 HD: clamp, saturation/contrast (defaults 1.0: `Saturation.java:36`, `Contrast.java:36`, identity),
colour-blind, `pow(srgb, 100/brightness)` (default brightness 100 → 1, `HdPlugin.java:2161-2165`), ±0.5/255 hash dither
(`output_transform.glsl:11-37`). Lumina: none of these on surfaces; colour-blind on the albedo (`frag.glsl:162-164`);
triangular dither on sky pixels only (`composite_frag.glsl:327-331`). Effect at defaults: only the dither (slight
banding in dark surface gradients in Lumina).

### 3. Difference table

Rules applied: one mechanism per quantity (HANDOVER rule 2), no new settings (rule 5), A1 "keep the shading, sky
palette, fog stack" (binding look rule of this branch) — wherever parity would change the look A1 keeps, the row
is an OPEN QUESTION.

| Term | 117 HD | Lumina | Difference | Recommendation |
|---|---|---|---|---|
| Colour space | linear lighting, sRGB out | same | interpolation: 117 HD blends vertex colours in linear, Lumina in sRGB | KEEP OURS: small, only on strong gradients; changing it means a linear albedo target |
| HSL → RGB | `hslToSrgb(convertHsl)` → linear | same maths (equal to 7e-16) **+ `pow(rgb, brightness)`** | Lumina's albedo 1.35–1.87x brighter in linear, darks lifted most | **COPY**: drop the pow at `hsl_to_rgb.glsl:84` and `frag.glsl:140` (as the lumina branch did, docs/old-renderer-vs-lumina.md:322). Land it with the light-level row below or the scene goes ~1.3x darker in lightness |
| Textured base | linear(tex) × material.brightness × linear(colour 90) = (0.480, 0.429, 0.426) | pow(tex, b) × 90/127 in sRGB | 1.28–1.89x brighter in Lumina; 1.10–1.18x after the pow is dropped | COPY with the row above; the remaining 10–18 % (90/127 multiplied in sRGB vs converted to linear) is OPEN (a linear multiply in the composite would need the lightness carried separately) |
| Sun direction for shading | true sun (cycle, NYC, 24x clock; noon 26–73° by date) or 52°/235° fixed | caster elevation clamped 30–68°, noon 68° south | walls facing the sun 0.37 vs 0.62 of the light; no low raking light | OPEN QUESTION: the clamp is the shadow-length rule of DayCycle; parity means shading with the unclamped sun (then shadows too: one direction per light) |
| Sun colour/strength | white × 4 × air-mass attenuation (noon lum 3.81) | 2.9 × (1, .9, .76) (lum 2.64) | Lumina 0.69x and yellow | OPEN QUESTION (A1 palette). Exact 117 HD: `directionalColor #ffffff`, `directionalStrength 4`, `applyAtmosphere` (SkyRenderer.java:486-500) |
| Ambient | linear #97baff × 1 × scattering, constant per frame | (.30,.38,.58) × 0.8, × sky visibility floor 0.4, × SSAO, + room daylight max + room fill | Lumina 0.63x, less blue; four mechanisms vs one | OPEN QUESTION (the ambient/sky decision already open in vanilla-colour-brightness.md). Rule 2 says one mechanism; 117 HD's is the constant |
| Sky light | 0.5 × horizon colour × up (noon lum 0.33) | 0.45 × linear zenith × up (0.094), or cache × 0.40 incl. 0.25 on walls | 117 HD 3.5x brighter on up faces; Lumina lights walls a little | OPEN QUESTION (A1). Parity constants: `skyLightStrength 0.5`, colour = `uboGlobal.fogColor` (scene_frag.glsl:475-478) |
| Daylight fill | not found | + 0.7 × sun × N.L × (1 − shadow) × skyVis (composite_frag.glsl:375-376) | open-ground shadows 1.35:1 vs 4.7:1 | OPEN QUESTION, parity = remove the two lines; largest single contrast difference |
| Underglow | env colour × max(N.y,0) × strength; 2 areas | not found | only Inferno / True Blood Altar | KEEP OURS (importer skips it on purpose; 2 areas) |
| AO | material AO maps, ambient only | SSAO on ambient, sky, sun and lamps (AO_DIRECT 1.0) + sky visibility | contact/crease darkening incl. sunlit faces | OPEN QUESTION; for parity at least `AO_DIRECT 0.0` (composite_frag.glsl:48), the value its own comment calls physical |
| Shadow | directional × (1 − shadow); alpha faces shadowed | same, + clouds/overcast; alpha faces unshadowed | alpha faces stay sunlit in shade | KEEP OURS for opaque; the alpha path's missing shadow is OPEN (needs the shadow map in the scene pass) |
| Point lights | 117 HD falloff, N.L, spec, transmission, no occlusion | same falloff/colours; wrap, walls/floors occlude, × AO, no spec | | KEEP OURS (A3, decided by the user); `× AO` on lamps goes with the AO row |
| Emissive/unlit | light = 1 | light = 1.2 × emissiveStrength | 1.2x brighter | OPEN QUESTION (small): parity = `EMISSIVE_LIGHT 1.0` (materials.glsl:8) |
| Transmission | same formula, 1.5; subsurface materials only | same formula; leaves × setting | which faces | KEEP OURS (formula already 117 HD's) |
| Specular | Phong per material; gloss 30 on translucent faces; sun and lamps | not found | no glints | OPEN QUESTION (a new mechanism, no setting needed); the translucent-face rule (scene_frag.glsl:409-416) is the cheapest part |
| Fresnel | dead code (surfaceColor = 0) | none | none | KEEP OURS |
| Lightning | 0.25 × brightness on up faces | bolt as caster, ambient dip | different | KEEP OURS |
| Fog | linear-edge + exponential + edge fog, fogDepth 6.5 overworld, colour = sky in view dir | exponential only, overworld 0, horizon/area colour, edge fade, volumetric | no overworld distance haze in Lumina | OPEN QUESTION (A1 fog stack, A4) |
| Tone map | none (clip at 1) | shoulder above 0.75 | highlights roll off vs clip | OPEN QUESTION; parity = remove the shoulder (sky.glsl:391-399), only sensible together with the light-level rows |
| Night | moon + exposure adaptation on light values | moon 0.675, ambient × 1.5 | different models | OPEN QUESTION (not compared numerically here) |
| Output | clamp, sat/contrast/gamma identity at defaults, ±0.5/255 dither | none; dither on sky only | dither on surfaces | KEEP OURS (no new settings); a surface dither is optional, not parity-critical |
| Area multipliers | absolute per-area light | sqrt of ratios to 117 HD's OVERWORLD, clamped (import_environments.py:16-19) | relative, compressed | KEEP OURS until the light-level rows are decided |

### 4. Not found / not checked
- 117 HD SSAO, bounce/fill light, underglow in Lumina, specular on non-water surfaces in Lumina: not found.
- Night-time numbers for 117 HD (moon + exposure) were not computed.
- The sky-cache irradiance values (Lumina) were estimated, not computed: they depend on the captured sky.
- Which vanilla textures 117 HD replaces with its own materials (affects the textured-face row) was not listed.
- `textureProvider.getBrightness()` default: not read from the client; 0.8 is the user's value per
  docs/old-renderer-vs-lumina.md:137.
