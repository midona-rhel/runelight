# Model normals and vanilla-shading cleanup: 117 HD against Lumina

Audit for the shading-parity segment (2026-10-31). The user's instruction: trees and other models shade the way 117 HD
shades them, "match by code, not by visuals". 117 HD = the reference checkout `~/Code/rlhd-ref` (upstream 117 HD plus
the comparison tools, commit 7cadf3bd). Its default renderer is the zone renderer (`HdPluginConfig.legacyRenderer()`
default false, `HdPlugin.java:594`); the legacy renderer is cited only where it differs. Paths below are relative to
`src/main/java/rs117/hd/` and `src/main/resources/rs117/hd/` for 117 HD, and to `src/main/java/com/midonarhel/lumina/`
and `src/main/resources/com/midonarhel/lumina/` for ours. Line numbers are the state before this segment
(lumen 4f6b7987).

## 1. What 117 HD does, from the game's model to the shaded pixel

Two upload paths, both in `renderer/zone/SceneUploader.java`:

- **static** — scenery, trees, walls, decorations, uploaded once per zone: `uploadStaticModel` (1533–1952), called
  from `uploadZoneRenderable` (817).
- **dynamic** — players, NPCs, projectiles, animated objects, uploaded per frame: `preprocessTempModel` (1954–2210,
  vertices rotated into `modelVertices`) then `uploadTempModel` (2211–2390), called from
  `ModelStreamingManager` (367, 394, 420) for `ZoneRenderer.drawDynamic`/`drawTemp`.

### 1.1 Where the per-vertex normals come from

The game's own: `model.getVertexNormalsX/Y/Z()` (static 1567–1570, dynamic 2234–2250; the async copy caches the same
arrays, `AsyncCachedModel.java:152–158, 301–303`). 117 HD never computes per-vertex normals for models; the only
normal it computes is a face's own (1.2). `modelHasNormals` is false when any of the three arrays is null, and then
every face of the model is flat (`flatNormals = !modelHasNormals || faceOverride.flatNormals`, 1847 / 2320).

What the game puts in them is the client's model lighting (`ModelData.light` → its vertex normals; RuneLite's
`Model.getVertexNormalsX` returns them, its API has no separate `calculateVertexNormals`): per vertex the sum, over the
faces the model file marks smooth (render type 0), of each face's normal scaled to length 256 — not area-weighted, and
flat faces (render type 1, `faceColors3 == -1` after lighting) do not contribute. Objects the scene merges with their
neighbours (`mergeNormals`) get the merged sums. The client's implementation is in the obfuscated client and could not
be read locally (the injected-client jar in the Gradle cache carries no `getVertexNormalsX`); none of this changes what
we do, because after this segment we read the same arrays 117 HD reads.

The arrays are in **model space**: 117 HD rotates them by the object's orientation itself (`rotateNormals`, 2837–2851,
integer `SINE`/`COSINE` `>> 16`) after using them unrotated for the shading undo (1.4).

### 1.2 The flat-face rule

Per face, static 1847–1876 and dynamic 2320–2350:

```
flatNormals = !modelHasNormals || faceOverride.flatNormals
if (flatNormals || !configPreserveVanillaNormals && color3s[face] == -1)   // preserveVanillaNormals default false (HdPluginConfig 1401)
    face normal for all three corners, no rotation
else
    the three vertex normals; if all nine components are 0, the face normal instead
    rotate by the orientation (static: always when orientation != 0; dynamic: only if not replaced by the face normal)
```

- The face normal is `calculateFaceNormal` (2608–2635): `cross(B − A, C − A)` of the **already rotated** positions
  (static: `modelVertices` ints, 1594–1631; dynamic: rotated floats, `preprocessTempModel` 2000–2040), normalised and
  stored as `(int) (n * 2048)`. Same normal on all three corners — a hard facet.
- A smooth face with only one or two zero vertex normals keeps the zeros for those corners.
- Static quirk, copied as code: when a smooth face's vertex normals are all zero, the face normal (already in rotated
  space) is rotated once more (`shouldRotateNormals` stays true, 1856 then 1883). The dynamic path guards this
  (`shouldRotateNormals && !shouldCalculateFaceNormal`, 2357). Only reachable when the game gives a smooth face three
  zero normals.
- **No crease angle, no neighbour averaging, no smoothing of flat faces anywhere.** 117 HD leaves a face the game
  marks flat as flat. Its only per-model ways to change this are the override flags (1.6).

The legacy renderer differs only in where the face normal is made: it uploads zeros for flat faces
(`LegacyModelPusher.getNormalDataForFace`, 401–435) and its geometry shader substitutes the triangle's normal
(`scene_geom.glsl:91, 103`: `length(normal) == 0 ? N : normalize(normal)`, `N = normalize(cross(p1 − p0, p2 − p0))`).

### 1.3 How the shaders use the normal

Zone renderer, no geometry shader: `scene_vert.glsl:99–123` passes the vertex normal through (times the world view's
matrix for boats etc.), interpolated; `scene_frag.glsl:205–211`: `N = normalize(IN.normal)` (or the derivative normal
when the user turns on the "Flat shading" setting, default off, `HdPluginConfig 1149–1157`). **No zero check and no
facing flip in the zone renderer**: zeros never reach it whole (1.2), and the normal is used as uploaded, with back
faces culled (`ZoneRenderer.scenePass`, `GL_CULL_FACE` on, 985–986). The lighting normal is `N` through the normal
map's TBN (`scene_frag.glsl:357–371`) unless the material data carries `MATERIAL_FLAG_UPWARDS_NORMALS`, which makes it
`(0, −1, 0)` (straight up). The derivative normal `flatNormal` (111) is used only for shadow-map bias and parallax.

### 1.4 How vanilla's baked shading is undone or kept

`ShadingMode` (`config/ShadingMode.java:32–37`), default `DEFAULT("117 HD", unlitFaceColors false, undoVanillaShading
true, directionalShading true)`. So by default the game's **lit** face colours (`getFaceColors1/2/3`) are used, not
`getUnlitFaceColors` (that is only the NONE/UNLIT modes, 1562 / 2229), and the undo runs.

Per face, before the undo:

- `color3 == −1` (flat): `color2 = color3 = color1` (1653–1654, 2280–2281).
- **Textured faces** (`faceTextures[face] != −1`): `color1 = color2 = color3 = 90` and `keepShading = true` — no
  undo (1722–1728; the comment says the vanilla shading stays visible otherwise, Ardougne roofs). Same for any face
  whose material is a vanilla texture (1810–1815). Dynamic: `textureId != −1 || material.vanillaTextureIndex != −1` →
  90 (2289–2290), undo skipped when `material.vanillaTextureIndex != −1` (2351). The 90 is HSL lightness 90 with hue
  and saturation 0, which the fragment shader multiplies the texture by (`scene_frag.glsl:259–266, 323–328`).
- `inheritTileColorType` (static only, 1733–1793): rocks and the like take the ground tile's colour, `keepShading =
  true`. 71 overrides use it.
- `tzHaarRecolorType` (static, 1796–1808, only with the legacy TzHaar reskin setting).
- `hideVanillaShadows` (static 1730–1731, dynamic 2134): drops the game's fake shadow faces (`HDUtils.isBakedGroundShading`); hiding, not
  shading.

The undo (`undoVanillaShading`, 2751–2793), per corner with that corner's normal from 1.2:

```
s = color >> 7 & 7, l = color & 127
adjust = BASE_LIGHTEN − l + (l < IGNORE_LOW_LIGHTNESS ? 0 : (l − IGNORE_LOW_LIGHTNESS) * LIGHTNESS_MULTIPLIER)   // 10, 3, 3 (80–85)
len = nx² + ny² + nz² (int), dot = (nx + ny + nz) * 0.57735026 / sqrt(len)
if len > 0 && dot > 0: l += (int) (dot * adjust)
l = min(l, legacyGreyColors ? 55 : getMaxBrightness(s))       // legacyGreyColors default false; 127, 61, 59, 57, 56, 56, 55, 55
```

Which normal it gets: a **smooth** face's three vertex normals, **unrotated** (model space, the comment at 2780); a
**flat** face (or a smooth one whose normals are all zero) the face normal from 1.2, which is **in rotated space**
because it is made from rotated positions. Called when `configUndoVanillaShading && faceOverride.undoVanillaShading &&
!keepShading` (1848, 1877–1881; dynamic 2321, 2351–2355). Dynamic models then get the model's HSL override
(`interpolateHSL`) on untextured faces (2362–2366), after the undo.

### 1.5 Static and dynamic, side by side

| | static (`uploadStaticModel`) | dynamic (`uploadTempModel`) |
|---|---|---|
| normals | `getVertexNormals*` | same (cached copy) |
| flat rule | `flatNormals \|\| color3 == −1` → face normal | same |
| all-zero smooth face | face normal, then rotated (again) | face normal, not rotated |
| textured colour | 90, keep shading | 90; undo skipped for vanilla-texture materials |
| undo condition | setting ∧ override ∧ ¬keepShading | setting ∧ override ∧ ¬vanilla-texture material |
| HSL override | — | after the undo, untextured faces |
| double-sided | `doubleSidedFaces` (or the material's own flag) emits the reversed triangle with negated normals (1926–1951) | — (not in `uploadTempModel`) |

### 1.6 Per-model override flags that change normals or shading

`scene/model_overrides/ModelOverride.java:75–86` and `model_overrides.json` (106,461 lines). Counts over every override
including the per-material and per-colour sub-overrides (`materialOverrides`, `colorOverrides`):

| flag | effect | where | uses |
|---|---|---|---|
| `flatNormals` | all faces flat (face normal) | CPU, 1847 | 361 top level + 55 sub |
| `upwardsNormals` | lighting normal straight up | shader, `scene_frag.glsl:359`; `Material.packMaterialData` 196 | 89 top level + 137 sub |
| `undoVanillaShading: false` | keep the vanilla shading | CPU, 1848 | 110 top level + 85 sub |
| `doubleSidedFaces` | also draw the reversed triangle, normals negated | CPU, 1926 | 28 top level + 8 sub |
| `inheritTileColorType` | ground colour, keep shading | CPU, 1733 | 71 |
| `invertDisplacementStrength`, `windDisplacementMode` | wind sway only | shader | 4, 326 |

On foliage: the ordinary trees (normal, oak, willow, maple, yew… and the Auburnvale area, whose overrides are furniture,
walls and logs) carry **no** normal or shading flag — only a material (`BARK`, `LEAF_VEINS`…) and a wind mode. The
flagged foliage is specific: `upwardsNormals` on grass (149 short-grass objects, hunter grass, river grass, Vampyrium
grass) and on some tree sub-parts (Tirannwn leafless trees, tropical tree bottoms, Prifddinas crystal and spirit trees,
Vampyrium trees), `doubleSidedFaces` on Gnome Stronghold leaves, jungle trees, palms and the Woodcutting Guild
redwood tops, `undoVanillaShading: false` on "Tree - Lighter", "Oak Tree - Lighter", Vampyrium trees and some plants,
`flatNormals` on loose leaves (Icyene graveyard) and river grass. The full list is produced by
`tools/import_117hd_shading.py` (§4).

## 2. What we did (lumen 4f6b7987)

- **Normals**: computed by us, never read from the game. `VertexShading.computeNormals` (169–227): per vertex the
  **area-weighted** sum of the cross products of **all** visible faces around it, flat ones included
  (`colors3 == −2` skipped only). Called by `SceneUploader.uploadStaticModel` (1235–1242), `ModelUploader.
  uploadSortedModel` (124–131) and `uploadTempModel` (612–619); `hasNormals = true` always. Its javadoc: "117 HD found
  [the game's] to be inaccurate anyway" — that is the preserve-vanilla-normals comment, which is about flat faces only;
  117 HD does use the game's normals for smooth faces.
- **Flat rule**: `color3 == −1` faces go through `VertexShading.flatFaceNormals` (85–143, commit 00894644): at each
  corner the area-weighted average of this face and the neighbours within 50° (`SMOOTH_COS = 0.643`), a same-plane
  opposite-winding twin flipped; when any corner rounds, the face's colour is replaced by the game's **unlit** colour
  (`getUnlitFaceColors`) on all three corners (SceneUploader 1356–1366, ModelUploader 274–285, 701–712). Otherwise 0
  (no normal). 117 HD has no equivalent of either.
- **Shader**: `vert.glsl:95–108` decodes the octahedral normal (0 = none); `frag.glsl:170–179`: the derivative
  normal, flipped to face the camera, for a 0 normal, and **the vertex normal flipped to the derivative normal's side**
  when they disagree. 117 HD flips nothing. Back faces are culled for the whole scene pass (`LuminaPlugin` 1606–1671).
- **Undo**: `VertexShading.baseColor` (240–268) has 117 HD's constants and formula, but: run with **our** smooth
  normal at each corner for every face, flat faces included (a flat face is undone with corner A's smooth normal
  instead of its face normal), in model space for every face (117 HD: rotated space for face normals); **textured faces
  keep the game's lit lightness** (117 HD: 90) — `frag.glsl:139–142` multiplies the texture by `fHsl / 127`, the baked
  shading. Run before the HSL override (dynamic), as 117 HD. Toggle: `config.removeBakedShading()` →
  `VertexShading.undoVanillaShading`.
- **Overrides**: none of the six flags; `materials.json` carries transmissive/emissive/fake water only.

## 3. The differences, and what this segment does with each

| # | difference | change | commit |
|---|---|---|---|
| D1 | normal source: ours area-weighted over all faces vs the game's `getVertexNormals*` | read the game's arrays (static `Model`, dynamic `ModelData` gains the three arrays) | 79d5c01d |
| D2 | flat faces: crease-angle smoothing + unlit colour vs face normal | 117 HD's rule: `!hasNormals ∨ flatNormals ∨ color3 == −1` → CPU face normal from rotated positions; `computeNormals`, `flatFaceNormals`, `SMOOTH_COS`, `COPLANAR_COS`, the unlit-colour swap go | 79d5c01d |
| D3 | all-zero smooth face | face normal, static re-rotates (copied), dynamic doesn't | 79d5c01d |
| D4 | shader flips the vertex normal to the camera side | used as uploaded; the derivative normal stays only for a 0 normal (terrain and others that send none) | e2721038 |
| D5 | undo normal: our smooth normal at every corner vs vertex normals (smooth, model space) / face normal (flat, rotated space) | as 117 HD | 0deb2859 |
| D6 | textured faces: game lightness vs 90 | 90, not undone | 0deb2859 |
| D7 | override flags | `flatNormals`, `upwardsNormals`, `undoVanillaShading: false` imported into `scene/model_shading.json` and applied per face in all three upload paths (§4); `doubleSidedFaces` and `inheritTileColorType` not (below) | 9ad03695 |

Not ported, and why:

- `doubleSidedFaces` emits extra triangles; our zone sizes are counted before the upload (`SceneUploader` size pass)
  and the overflow crash of 2026-10-30 came from that count and the upload disagreeing. A separate segment with the
  count changed in the same commit; the user decides.
- `inheritTileColorType`, `tzHaarRecolorType`, `hideVanillaShadows`: colour replacement and face hiding, not normals or
  the undo; separate segments (A2 "ground-matched foliage" is the first).
- 117 HD's normal maps, parallax and the "Flat shading" setting: not in our pipeline.
- Quantisation: 117 HD uploads `int(n * 2048)` per axis; we keep our 8+8-bit octahedral normal in the texture
  attribute's last short (≈0.5° steps), which carries any direction 117 HD's does.

## 4. Override import

`tools/import_117hd_shading.py <RLHD>/src/main/resources/rs117/hd/scene src/main/resources/com/midonarhel/lumina/scene/model_shading.json`
writes `shadingGroups` (its own file, so `import_117hd_materials.py` rewriting `materials.json` leaves it alone): groups
in the same form as `emissiveGroups`/`fakeWater` (objects, npcs, the textured faces' flags, the untextured faces'
default and colour rules), a level being the flags 1 flat normals, 2 upwards normals, 4 keep vanilla shading. Per id the
last override without areas wins, as `ModelOverrideManager.addEntry` (132–218). At import (2026-10-31): 245 groups,
4,179 objects, 21 NPCs. Skipped, and listed by the importer: 48 overrides limited to areas or seasons (Chinchompa
hunting grass, Lumbridge/Edgeville walls, farmyard hay roofs, …) and the projectile/graphics ones (4 with flags).
Approximation: two flat-normal overrides (the water and fire obelisks) have a `materialOverrides` entry that 117 HD
uses for their water/lava-textured faces without the flag; ours flat-normals those faces too.

At upload (`SurfaceMaterials.shadingForObject`/`shadingForRenderable` → `SurfaceMaterials.shading` per face, by the
face's game colour and alpha as the emissive groups are): `FLAT_NORMALS` → the face normal as for a game-flat face
(colours stay per corner, as 117 HD's); `KEEP_SHADING` → no undo; `UPWARDS_NORMALS` → after the undo and rotation,
`(0, −2048, 0)` on every corner — 117 HD swaps the lighting normal in its fragment shader instead, after the undo with
the real normal; ours also tilts it with the wind, as any normal.

## 5. What 117 HD's rule means for the tree canopies

Under 117 HD's code a canopy face the game marks flat (`faceColors3 == −1`) is lit with its own face normal — a hard
facet — and its colour is the game's per-face lit colour, undone with that same face normal (which takes out much of
the facet-to-facet colour difference, since vanilla lit it by that normal). Smooth canopy faces are lit with the game's
vertex normals. If the canopies the user saw as faceted are flat-marked faces, 117 HD's rule brings the facets in the
**lighting** back (the crease rule of 00894644 hid them); that is 117 HD's look, by its code
(`SceneUploader.java:1852`), and is the user's call. Whether a given tree's canopy faces are flat-marked is in the
model file, not in 117 HD's data: none of the ordinary trees has a `flatNormals` or `upwardsNormals` override.

