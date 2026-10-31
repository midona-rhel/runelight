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

What the game puts in them, from the client's code (the deobfuscated client as OpenOSRS published it,
`runescape-client/src/main/java/ModelData.java`, and RuneLite's cache library, `cache/.../definitions/
ModelDefinition.computeNormals`, which mirrors it; read 2026-10-31 on GitHub, open-osrs/runelite and runelite/runelite):

- **`ModelData.calculateVertexNormals`** (OpenOSRS ModelData ~1347–1402): one `VertexNormal` per vertex index. Per
  face: `cross(B − A, C − A)` in ints, halved while a component is past ±8192, scaled to length 256
  (`x = x * 256 / length`, length ≥ 1). The model file's **render type** of the face decides where it goes: type 0
  (or no render-type array) → added to the `VertexNormal` of each of its three vertices (`++magnitude`); type 1 →
  stored as that face's own `FaceNormal`, **added to no vertex**; types 2 and 3 → nothing. So a vertex normal is the
  sum of the *unit* normals (not area-weighted) of the **smooth** faces that use that vertex index. Faces share a
  vertex only by sharing its index: at an edge the model file splits (duplicated vertices) nothing is shared; at an
  edge it shares, every smooth face around the vertex is summed **whatever the angle** — there is no crease angle.
- **`ModelData.toModel`** (lighting, ~1490–1614): type 0 → three per-vertex lit colours from the vertex normals (or
  the merged override, below); type 1 → one colour from the face normal and **`faceColors3 = −1`**; untextured
  type 3 → colour 128 and `faceColors3 = −1`; type 2 and textured type 3 → `faceColors3 = −2` (not drawn). So
  `faceColors3 == −1`, 117 HD's flat test, is exactly "the model file marks this face flat".
- **Merging** (`ModelData` static merge, ~1434–1487, used for objects that merge normals with their neighbours): at a
  vertex position two models share, each adds the other's normal into a per-vertex override (`vertexVertices`), which
  type-0 lighting uses; faces whose three vertices all matched become type 2.
- **What `Model.getVertexNormalsX/Y/Z` returns** (RuneLite's `RSModelDataMixin.vertexNormals()`, called from its
  `toModel` replacement): per vertex the override if there is one, else the `VertexNormal`, x/y/z as ints; a vertex
  only flat faces use has a `VertexNormal` with nothing added, so (0, 0, 0). Null arrays when the model has no
  normals.

The OpenOSRS deob predates today's client, and RuneLite's current mixins are not public; the cache library's
`computeNormals` (current) has the same rule. Our tests reproduce this arithmetic (`VertexShadingTest.Mesh.gameNormals`).
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
matrix for boats etc.), interpolated **unnormalized**: the vertex buffer holds the game's ints as they are
(`VertexWriteCache.putStaticVertex` 146, "Unnormalized normals, assumed to be within short max", and
`putDynamicTriangle`), so a corner whose normal sums many faces (length 6 × 256 inside a slope) outweighs one of few
(a ridge corner, whose sum half cancels); `scene_frag.glsl:205–211`: `N = normalize(IN.normal)` (or the derivative normal
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
  shading. Run before the HSL override (dynamic), as 117 HD. Toggle at the time: `config.removeBakedShading()` → `VertexShading.undoVanillaShading`; removed 2026-10-31 (the user: "remove baked shading, according to how HD117 does it"), the undo is now always on.
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
- Interpolation (fixed 2026-10-31, the user's thatched roof at the Ardougne market "blended things coming off" under a
  high sun): the octahedral encoding normalized every corner, so a ridge corner's averaged normal spread across the
  whole slope triangle; 117 HD interpolates the game's unnormalized sums (1.3). Each vertex now carries the normal's
  length beside it (`VertexShading.normalLength`, attribute 3, one int: zone vertices 20 → 24 bytes, dynamic 24 → 28)
  and `vert.glsl` scales the direction by it before interpolation. Normals that are not the game's (terrain, water
  bed, ground fixes) carry length 1, as before. Synthetic gable (`VertexShadingTest`): at the centre of a slope
  triangle touching the ridge, 20.0° off the slope as 117 HD against 23.9° with unit corners.

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

## 5a. Smooth or hard, plainly

Under 117 HD's code a face is shaded **hard** (its own normal on all three corners) when the model file marks it flat
(render type 1 → `faceColors3 == −1`; 117 HD's `preserveVanillaNormals` is off by default), when its 117 HD override
says `flatNormals`, or when the model has no normals; otherwise **smooth**, with the game's vertex normals: at each
corner the unit-weighted sum of every smooth face sharing that vertex index, at any angle. There is no crease angle
anywhere. So:

- **A tree trunk** (an 8-sided prism) is **smooth** if the model marks its sides type 0 and they share their vertices:
  each corner's normal lies between the two sides meeting there, so the trunk shades round (test
  `aSmoothOctagonalTrunkTakesTheGamesVertexNormals`). It is **hard**, eight facets 45° apart, if the model marks the
  sides flat (`aFlatOctagonalTrunkStaysFaceted`). 117 HD changes neither: the ordinary trees carry no `flatNormals`
  override. Which one a given trunk is, is that model's data; this checkout cannot read the cache's models, so the
  Auburnvale tree's own marking is for the client run to show.
- **A canopy dome** the same: smooth-marked → round, every corner leaning out from the dome's middle; flat-marked →
  facets (`aFlatCanopyIsFacetedAndASmoothOneRound`).
- **A crate** is a box only when flat-marked; smooth-marked on shared corners its corners lean along the diagonals and
  it shades round (`aCrateIsABoxOnlyWhenTheGameMarksItFlat`) — 117 HD keeps it so; the game's own data, not a
  crease angle, keeps crates sharp.
- **A two-sided leaf quad** (the quad twice, wound both ways, on shared vertices): smooth-marked, the game's sums
  cancel to zero at every vertex, so 117 HD falls back to each face's own normal; flat-marked, the same normals
  directly. Each side is lit by its own normal (`aTwoSidedLeafQuadLightsEachSideByItsOwnNormal`).
- **A thatched roof** (roof pieces are scene objects, mostly textured: the thatch texture). Two things can show its
  triangles, and 117 HD has exactly one of them:
  - *The colour.* A textured face is drawn as its texture times a lightness. The game bakes that lightness per face
    when it marks the face flat (one value per triangle) and per corner when smooth. 117 HD throws it away and uses
    lightness 90 on every textured face (zone `SceneUploader` 1722–1728), so it never shows triangles through the
    colour. Lumen as it runs today keeps the game's baked lightness on textured faces (`frag.glsl` `fHsl / 127`), so a
    flat-marked roof shows each triangle in its own baked tone — the facets in the user's
    `~/.runelite/lumina/bugs/roof-thatch-facets.png` (lumen + lamps-full, the crease rule, not this branch). This
    branch uses 117 HD's 90 (0deb2859), so that cause is gone here.
  - *The light.* Flat-marked faces are lit by their own normal under 117 HD's rule: triangles in one plane share it
    (a straight slope shows nothing), triangles on a bump or a fold each have their own, and 117 HD shows those as
    separate tones too; smooth-marked faces on shared corners shade with no step
    (`aFlatMarkedRoofShowsItsTrianglesAndASmoothOneDoesNot`). 117 HD's thatch material adds a normal map, which breaks
    the facets up a little; we have none (no texture images).
  - So at the Ardougne market's thatched shops this branch should show a roof in one even tone per plane, with
    triangles visible only where the model's surface bends and the game marks those faces flat — the same places
    117 HD shows them. Which faces the model marks flat is data: `::runelight faces 2` beside the shop writes them
    to `captures/faces.txt`.
- **Tile models** (shaped ground tiles): always smooth in 117 HD (its terrain normals, `ProceduralGenerator` 238–312,
  area-weighted sums over all ground faces); ours are smooth too (central differences, docs/117hd-world-shading.md
  §1.1).

## 5b. Our own smooth normals: welded, creased (the user's experiment, 2026-10-31)

The user, on the thatched roof at the Ardougne market: "since these verts are basically inside each other, why don't
we merge them and recalc the mesh shading based on that? Let's try that." This replaces 117 HD's normal source (the
game's vertex normals, 1.1) for lighting; the vanilla-shading undo keeps the game's normals, which the game lit with.

**Why the game's normals shade the roof unevenly** (dump `faces 6 detail` at 2657,3301, 74 roof objects, analysis in
the shared notes, `roof-faces-analysis.txt`): no roof face is flat-marked, and the pieces agree at all 101 corners they
share (the client merges neighbours' normals), so there are no seams. But a slope's corner sums every smooth face of the
model at that vertex whatever the angle: the timbers, the eave underside, the hip. Of 537 textured roof corners only
70 are within 5 degrees of their own face; plain slope tiles lean 14 degrees at the eave, hip and corner pieces 20-40,
34 corners more than 90. 117 HD lights with these same numbers.

**The mechanism** (`scene/CreaseNormals`): weld a model's vertices by integer position; a smooth face's corner takes
the sum of the welded smooth faces at that corner within the crease angle of its own normal, each weighted by its
angle at the corner; flat-marked (-1) and hidden (-2) faces neither take nor give. Angle, not area, weighting: an
8-sided trunk whose sides each have their own vertices (split, as models often are) leaned 7.7 degrees toward the side
that meets the corner with two triangles under area weighting; with corner angles a quad weighs the same however it
is split. Scene models are done at zone upload; every other model keeps the normals of its first upload, cached by the
client's face index array (an animated model's frames share it), as the game's normals are also one set for all
frames.

**The crease angle, 62 degrees**, from the folds measured (dihedral angles between faces sharing an edge, roof dump,
and the test meshes in `VertexShadingTest`):

| fold | angle | wanted | at 62 |
|---|---|---|---|
| thatch bumps within a slope | 0-30 | round | round |
| 8-sided trunk, 6-sided trunk | 45, 60 | round (the user: the magic tree's trunk is smooth) | round |
| roof hips (corner pieces 1641; 1604/1605) | 46.7, 50.2 | crisp | **round** |
| roof ridge at this pitch (36.9 degrees) | 73.7 | crisp | crisp |
| timbers, gables, crate edges | 90 | crisp | crisp |
| eave to its underside | 125-180 | crisp | crisp |
| a two-sided leaf's sides | 180 | apart | apart |

No single angle keeps the hips crisp and rounds an octagonal trunk (45 against 46.7); 62 picks the trunks. Textured
roof corners within 5 degrees of their face: game normals 70 of 537, crease 62 389, crease 44 483 (the rest at 62 are
the hips' corners, 25-30 degrees). At 44 the hips stay crisp and 8-sided trunks facet.

**Cost**: 77 ns a face, 7 µs per model on the dumped models (199 models, 18,492 faces: 1.4 ms), a 4000-face model
0.26 ms, once. The scene load report logs the load's total (`crease normals ...`).

## 5. What 117 HD's rule means for the tree canopies

Under 117 HD's code a canopy face the game marks flat (`faceColors3 == −1`) is lit with its own face normal — a hard
facet — and its colour is the game's per-face lit colour, undone with that same face normal (which takes out much of
the facet-to-facet colour difference, since vanilla lit it by that normal). Smooth canopy faces are lit with the game's
vertex normals. If the canopies the user saw as faceted are flat-marked faces, 117 HD's rule brings the facets in the
**lighting** back (the crease rule of 00894644 hid them); that is 117 HD's look, by its code
(`SceneUploader.java:1852`), and is the user's call. Whether a given tree's canopy faces are flat-marked is in the
model file, not in 117 HD's data: none of the ordinary trees has a `flatNormals` or `upwardsNormals` override.

