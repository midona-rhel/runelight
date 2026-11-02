# Inventory: scene data to GPU, shadows, culling, vertex shading

Branch `lumen` (the old RuneLight renderer), read-only survey of 2026-10-30. Paths are under
`src/main/java/com/midonarhel/lumina/` (Java) and `src/main/resources/com/midonarhel/lumina/` (GLSL) unless
given in full. Judged by HANDOVER.md's rules: performance first (≤ 8 ms at 4K, no hitches on scene load), one
mechanism per quantity, few settings, GL 3.3–4.1 on three platforms; and the user's wishes: smooth mesh shading
everywhere except 117 HD's flat-lit objects, ground-matched foliage and 117 HD's terrain colour rules, never blend
terrain colours between tiles.

Files named in the brief that do not exist here: `SceneContext*.java` and `RenderThread.java` are nested classes of
`RuneLightPlugin.java` (`SceneContext` :614, `RenderThread` :604); there is no `ModelOverride*`, `Material*` or
`Texture*` family — the material system is `SurfaceMaterials.java` + `materials.json`, textures are
`TextureManager.java`; `VBO*` is the single `VBO.java`; `Regions*` is `regions/Regions.java`, `regions/Region.java`
and `RegionManager.java`. 117 HD's model overrides (geometry, flat-lit flags, inherit-tile-colour) are **not
imported**: `grep modelOverride` only hits the import tools' docstrings and the comparison docs.

Threads, as used below: *client thread* (the game's; all GL calls, `swapScene`, `rebuild`, `drawTemp`,
`drawDynamic` for `renderThreadId == -1`); *client render threads* (the client's own `RENDER_THREADS(n)` workers that
call `drawDynamic` with `renderThreadId ≥ 0`, `rts[id + 1]`); *scene-loading thread* (the thread the client calls
`loadScene` on — not the client thread: the code waits on `clientThread.invoke` latches, `RuneLightPlugin.java:4449-4493`);
*temp workers* (`TempModels.Workers`, "RuneLight temp models" daemon threads).

---

## 1. Zone geometry upload (static scene → zone VBOs)

**Purpose (comments):** "Upload the swaying objects held back ... in one run" / zone vertex format "index 0: short
vec3(x, y, z), index 1: int abhsl, index 2: short vec4(id, u, v, 0)" (`Zone.java:53-57`).

**Files / functions:** `SceneUploader.zoneSize` :102, `uploadZone` :123, `uploadZoneLevel` :190,
`uploadZoneLevelRoof` :216, `uploadZoneTile` :372, `uploadZoneRenderable` :566, `uploadStaticModel` :1174, terrain
`upload(SceneTilePaint)` :619, `upload(SceneTileModel)` :774; `Zone.init/setupVao` :95/:168, `Zone.unmap` :146,
`Zone.free` :115; `VBO.java` (map with `GL_MAP_WRITE_BIT` only for `GL_STATIC_DRAW`, :73); plugin `loadScene`
:4220-4513, `loadSubScene` :4545, `rebuild` :3865, `swapScene` :4644.

**How it works:** Per zone (8×8 tiles, 4 levels), `zoneSize` counts opaque and alpha faces (plus standing-in-water
extrusions, `standingSize` :517), then one `VBO` each for opaque (`vboO`) and alpha (`vboA`) is created, mapped, and
`uploadZone` writes 20-byte vertices (5 ints: `put22224` + `put2222`, `GpuIntBuffer.java:48-59`). Levels are uploaded
in order with the roofs of each level first (`roofStart/roofEnd` ranges) so `renderOpaque` can skip hidden roofs by
draw range; level 0 also uploads the vis-below tiles of levels 1-3 (`:161-167`). Swaying objects are deferred and
flushed as one contiguous range per level/roof (`flushSway` :276, `Zone.addSwayRange` :232) so the shadow pass can
draw or skip them. A `VoxelCapture.Buffer` wraps the opaque buffer on the top level to keep a copy of every opaque
triangle for the skylight voxels (`:129-131`, `VoxelCapture.java:91-121`).

**Settings:** `drawDistance`, `expandedMapLoadingChunks` (scene size), `water` (bed baked into terrain, forces
reload :1089), `hideOtherAreas`, `removeBakedShading`, `groundBlending` (all force `GameState.LOADING`, :1065-1098).

**Cost shape:** Per scene load, on the scene-loading thread except the GL block: `WaterMap.build`, `zoneSize` for every
new zone, then **on the client thread under a latch** `Interactables.resolve` + for each new zone up to two
`glGenBuffers`/`glBufferData`/`glMapBufferRange` (:4449-4493; ~190 new zones per load per HANDOVER B3 → ~380 buffers),
then `uploadZone` for every new zone back on the loading thread, then `swapScene` on the client thread: `free` of
~190 culled zones (2 `glDeleteBuffers` + 2 `glDeleteVertexArrays` each) **2.5–3.3 ms**, `unmap` of ~190 zones
0.6–0.8 ms, modules 0.5 ms, total **3.5–4.5 ms** (HANDOVER B3, and the timing log at :4713-4717). One `VBO` + one
VAO per zone per transparency class → up to 4 GL objects per zone, 24×24 = 576 zones (`NUM_ZONES`, extended scene)
→ up to ~2300 GL objects live. Each zone VBO is sized exactly (`sizeO * 60` bytes) and never reused: a zone change
(`rebuild`) frees and reallocates. `GroundBlender.build` runs once per zone with a one-tile border (:151), i.e. every
border tile is visited by up to 4 zones. Zone reuse across loads (`canReuse` :4515) needs all 9 neighbours
initialised and non-empty, is disabled for `dirty` zones and when area hiding changed (:4298-4301).

**Duplicates / overlaps:** three copies of the loading sequence (`loadScene` :4419-4509, `loadSubScene` :4562-4622,
`rebuild` :3883-3914). The `VoxelCapture.Buffer` override doubles every vertex write on the top level (out-of-area
consumer, in-area cost). `Zone.readOpaque` :337 (`dumpScene`) is debug-only. `AlphaModel.packedFaces` is a second
copy of the alpha faces' centroids kept per model for the per-frame CPU sort (§3).

**Verdict:** Restructure — one zone build routine, zone buffers freed/unmapped and allocated in budgeted slices
(or from a pooled arena) off the swap frame, and the voxel capture moved to the worker that consumes it; the format
and roof/level ranges can stay.

---

## 2. Zone draw: opaque ranges, roofs, levels

**Purpose (comments):** "Fill the draw ranges (in ints) with what the levels show: whole levels, and above the
current one the roofs not hidden" (`Zone.java:281`).

**Files / functions:** `Zone.renderOpaque` :251/:260, `pushLevels` :282, `filterSway` :369, `convertForDraw` :207,
`pushRange` :422; plugin `drawZoneOpaque` :3571.

**How it works:** Per zone per frame the client calls `drawZoneOpaque`; `pushLevels` builds up to 1024 (offset,
length) pairs (`NUM_DRAW_RANGES` :203) from the level offsets and roof ranges, optionally split around the sway
ranges, then one `glMultiDrawArrays` per zone with `uniBase` set to the zone origin (:275-277). The same routine
serves the shadow pass (every level, every roof, `Collections.emptySet()`, `RuneLightPlugin.java:2699`) and the rain
occlusion map (`Weather.java:913`).

**Settings:** none directly.

**Cost shape:** Per frame per visible zone, client thread: one uniform + one VAO bind + one multi-draw; the range
arrays are static shared `IntBuffer`s. "draw ranges exhausted" is only logged at debug (:438). HANDOVER B1: `scene`
GPU 0.46 / 0.91 ms, `client scene` CPU 0.7–0.9 ms (includes the client's traversal).

**Duplicates / overlaps:** the per-zone draw is issued three times a frame when shadows and weather are on (main,
shadow, occlusion — §9, §10). `renderOpaque(…)` 6-arg overload only forwards to the 7-arg one.

**Verdict:** Keep; it is the cheap part. Merge the three callers' zone iteration into one "zones in reach" list
(§9 already collects one).

---

## 3. Static alpha models: per-frame CPU face sort

**Purpose (comments):** "only set for static geometry as they require sorting" (`Zone.java:459`).

**Files / functions:** `Zone.addAlphaModel` :474 (packs face centroids, radius), `alphaSort` :666, `multizoneLocs`
:848, `renderAlpha` :677, `flush` :807; plugin `drawZoneAlpha` :3594 (`ALPHA_ZSORT_CLOSE = 2048` :3591).

**How it works:** At upload each alpha-faced static model gets an `AlphaModel` with its faces' centroids packed into
ints (11/10/11 bits, :562-564). Each frame, for level 0 of each zone, the models are sorted by distance to the camera
(`alphaSort`), models spanning several zones are re-homed to the nearest zone (`multizoneLocs`, allocating
`AlphaModel` copies from a cache). For zones within 2048 units the faces of every alpha model are bucket-sorted by
view depth on the CPU (`renderAlpha` :740-801) into a stream element buffer (`glBufferData(GL_ELEMENT_ARRAY_BUFFER,
… GL_STREAM_DRAW)` :827) and drawn with `glDrawElements`; farther zones draw the unsorted range (`STATIC_UNSORTED`).
Temp alpha models (`addTempAlphaModel` :578, from `drawTemp`/`drawDynamic`) are already sorted and multi-drawn.

**Settings:** none.

**Cost shape:** Per frame per alpha zone, client thread: a `List.sort` over the zone's alpha models, the per-face
bucket sort for close zones (uses `ModelUploader.zsort*` scratch of `rts[0]`), one `glBufferData` per flush.
`removeTemp` :604 walks every zone's list once per frame (`drawPass PASS_ALPHA` :3654-3661). Memory: `packedFaces`
per static alpha model.

**Duplicates / overlaps:** the same bucket sort exists in `ModelUploader.uploadSortedModel` :198-235 (projected),
and the element-buffer path exists only for static alpha models while temp ones are pre-sorted into vertex order.

**Verdict:** Keep for now (it is what RuneLite's GPU plugin does); measure `drawZoneAlpha` CPU before B5 decides
whether an order-independent or coarser sort replaces it.

---

## 4. Dynamic model upload (actors, animated objects, projectiles)

**Purpose (comments):** "Uploads the models drawTemp is given … on worker threads. Computing their normals and
sorting and packing their faces took the client thread most of a millisecond a frame in busy areas"
(`TempModels.java:18-20`).

**Files / functions:** plugin `drawTemp` :3757, `drawDynamic` :3679; `TempModels.uploadTemp/uploadSorted` :147/:159,
`Job.pack` :89, `flush` :223, `emit` :280, `Workers` :334; `ModelUploader.uploadSortedModel` :98,
`uploadTempModel` :581, `computeFaceUvs` :818; `ModelData.copy` :85; `VAO.java`/`VAOList` (`VAO_SIZE = 4 MiB` :243,
`VERT_SIZE = 24` :45), `VBO.map` with `GL_MAP_INVALIDATE_BUFFER_BIT | GL_MAP_FLUSH_EXPLICIT_BIT` for dynamic
buffers :73.

**How it works:** `drawTemp` (client thread) copies the model (`ModelData.copy`, 18 arrays) into a ring of 64 jobs
(`MAX_JOBS`), queued to the temp workers; a worker (or the client thread when it catches up, `complete` :241) packs
the faces — normals, colours, UVs, projected depth sort — into the job's int array; `flush` copies jobs in call order
into the frame's mapped `VAO` buffers (`emit`) and registers an `AlphaModel` for the alpha half. `drawDynamic`
(client render threads) uploads straight into `rts[id+1]` buffers without workers. Every frame the `VAOList`s of all
render threads are mapped in `preSceneDraw` (:1838-1843, timed as `BUFFER_MAP`) and unmapped/drawn in `drawPass`.
Opaque depth-writing ranges are recorded as `ShadowDraw`s for the shadow pass (`VAO.recordShadowDraw` :167).

**Settings:** `numThreads` (client render threads and temp workers, `renderThreads()` :889), `removeVertexSnapping`
(client flag :899).

**Cost shape:** Per model per frame: a full `ModelData.copy` on the client thread (vertices + 15 face arrays), then
`VertexShading.computeNormals` (every face, every frame), per face `baseColor` ×3, `flatFaceNormals` (two sqrt),
`computeFaceUvs`, `SurfaceMaterials.face`, projection of every vertex (sorted path) — on a worker or the client
thread. Per frame: map/unmap of every `VAO` of every render thread (4 MiB each, grown on demand, never shrunk;
`glMapBufferRange` can stall — HANDOVER B1 notes `BUFFER_MAP` is timed apart). The workers linger 200 µs spinning
(`LINGER_NANOS` :40). HANDOVER B5 wants `client_scene` and `draw` CPU measured before caching replaces this.

**Duplicates / overlaps:** the colour/normal/material per-face pipeline is written out **three times**:
`SceneUploader.uploadStaticModel` :1274-1398, `ModelUploader.uploadSortedModel` :249-335, `uploadTempModel`
:664-775 (the same ~40 lines each). Two `Model`→`ModelData` wrappers (`wrap`/`copy`). `drawDynamic` is a fourth
upload entry that bypasses `TempModels`. `drawSkybox` :2062 uses `uploadTempModel` as a fifth caller. Animated
`DynamicObject`s are static-uploaded via `getModelZbuf` in zones (`SceneUploader.java:587`) **and** redrawn as temp
models by the client — which one wins per object is the client's business (unverified here).

**Verdict:** Restructure (B5) — one `uploadFaces(ModelData, …)` shared by static and dynamic paths, a per-model cache
keyed by model identity/animation frame so normals are computed once per model not per frame, and the worker ring
kept only if the measurement still needs it.

---

## 5. Vertex normals (`VertexShading`) and the 50° flat-face rule

**Purpose (comments):** "Smooth vertex normals computed from the model's own geometry: each vertex gets the
area-weighted average of the normals of the visible faces around it" (`VertexShading.java:140-141`); "Flat-shaded
faces whose vertex normals are within this angle of the face normal are shaded smoothly, so low-poly round shapes
(tree trunks, rocks) read as round while real corners (crates, walls) stay sharp" (:73-75).

**Files / functions:** `VertexShading.computeNormals` :145, `vertexNormal` :61, `flatFaceNormals` :84
(`SMOOTH_COS = 0.643` :75), `rotatedNormal` :128, `encodeNormal` :23 (octahedral 8+8 bits, 0 = none); callers
`SceneUploader.java:1199-1328`, `ModelUploader.java:124-283, 612-710`; terrain normals `SceneUploader.terrainNormal`
:736, `modelNormal` :1152, `GroundBlender.cornerSlope/tileSlopes/interpolateSlope` :173/:187/:207; shader decode
`vert.glsl:94-107`, orientation and face normal `frag.glsl:137-147`, sway tilt `sway.glsl:70-73`.

**How it works (models):** per upload, `computeNormals` sums unnormalised face cross products at the three vertices
(sign from winding; the fragment shader flips the smooth normal to face the camera, `frag.glsl:143-146`). A face
with `color3 != -1` (gouraud in the game's data) gets the three rotated vertex normals. A face with `color3 == -1`
(the game's flat face) goes through `flatFaceNormals`: **all three** vertex normals must be within 50° of the face
normal, else the whole face gets the face normal (constant across the face → faceted); if they are, the face takes
the smooth normals **and** its three colours are replaced by `unlitColors[face]` (one colour over the face,
`SceneUploader.java:1324-1327`). Degenerate faces get 0 → the shader uses `dFdx/dFdy`.

**How it works (terrain):** paint tiles take central-difference height-field normals at the corners across tile
borders (`terrainNormal`); tile models interpolate the four corner slopes bilinearly (`modelNormal`), computed twice
per vertex — once on the bed-sunk surface for the normal and once on vanilla heights for the shading undo
(`:1152-1158`, `:800-801`).

**Settings:** none (always on). `removeBakedShading` changes colours, not normals.

**Cost shape:** per model per upload; for dynamic models that is every frame (§4). Terrain: per vertex at zone
upload, two slope interpolations.

**Why trees shade hard-edged (what the code says, to be tested on one tree per HANDOVER A7):**
1. OSRS tree trunks and many canopies are flat faces (`color3 == -1`). For them smoothing is all-or-nothing per face
   under the 50° test (:109-120). Any corner where a leaf-cluster plane meets another at > 50° — the normal case in a
   canopy — fails the whole face, which then gets a constant face normal: hard edges by construction.
2. Even where a flat face passes, its colour becomes the single `unlitColors[face]` (:1326): lighting is smooth but
   the colour steps per face.
3. Double-sided foliage (two faces of opposite winding on one plane, common in OSRS leaves) cancels in the
   area-weighted sum at shared vertices: `l < 1e-6` → face normal (:114). Unverified count; `computeNormals`' comment
   assumes winding is "consistent within a model".
4. Textured leaf faces skip `baseColor` and the unlit-colour swap but still go through the same normal test; the
   alpha pass lights them in `frag.glsl:151-169` without shadows or AO, so canopies and trunks are lit by different
   paths.
5. Transmissive back-lit pixels flip `n` for the shadow lookup only (`ao_frag.glsl:64`); not an edge source.
The second mechanism in the frame — `composite_frag.glsl:204-209 pointLightNormal` — mixes the geometric face
normal (from the material target) back in for upright faces under point lights, so lamps flatten what the sun smooths.

**Duplicates / overlaps:** normals are decided in 5 places: `computeNormals`+`vertexNormal` (gouraud),
`flatFaceNormals` (flat, 50° rule), `terrainNormal`/`modelNormal` (two height sources), `frag.glsl` derivative
normal (fallback + orientation + material target), `pointLightNormal` (composite). The game's own normals are never
read. 117 HD's flat-lit object flags (the user's rule for what stays flat) do not exist here; the 50° heuristic
stands in for them.

**Verdict:** Restructure (A7, one rule) — smooth normals for every face (gouraud and flat alike, no 50° gate),
flat only for objects on 117 HD's flat-lit list (to import), face normal from the vertex data not `dFdx`; keep the
octahedral encoding; drop `pointLightNormal`'s second mix once lamps use the same normal (A3).

---

## 6. Vertex colours: vanilla bake undo, unlit colours, overrides, tint

**Purpose (comments):** "Vanilla bakes lighting from a fixed direction into vertex colors. Like 117 HD's default
shading mode, keep the vanilla colors (and their gradients) but brighten what that light darkened"
(`VertexShading.java:194-197`).

**Files / functions:** `VertexShading.baseColor` :201 (117 HD's `BASE_LIGHTEN 10`, `IGNORE_LOW_LIGHTNESS 3`,
`LIGHTNESS_MULTIPLIER 3`, `maxBrightness` per saturation :226); `unlitColors` swap `SceneUploader.java:1324-1327`,
`ModelUploader.java:279-282, 706-709`; HSL override `ModelUploader.interpolateHSL` :794 (dynamic models only);
scene tint `entityTint` uniform, `vert.glsl:88-89`, `VAO.Range h/s/l/a` :87; banding `frag.glsl:116-117`
(`smoothBanding`); textured lightness and `textureLightMode` `frag.glsl:110-113`; `brightness` pow on textures
`frag.glsl:108`.

**How it works:** For untextured faces each colour is brightened by `dot(normal, (1,1,1)/√3) × adjust`, capped per
saturation; for flat faces only vertex A's normal is used and copied to B and C (`SceneUploader.java:1301-1311`).
Flat faces that pass the 50° test are then overwritten with the game's unlit colour (§5). Dynamic models then apply
the game's HSL override (NPC recolours); every vertex goes through the scene's `entityTint` in the vertex shader;
the fragment shader picks interpolated RGB or interpolated HSL (`smoothBanding`), applies the overlay/underlay
border mix for ground (§7), and for textures multiplies the texture by the 7-bit lightness or by the colour
(`brightTextures`).

**Settings:** `removeBakedShading` (reload), `smoothBanding`, `brightTextures`.

**Cost shape:** per face per upload (per frame for dynamic models); shader cost negligible.

**Duplicates / overlaps — every path that decides a vertex's colour:**
1. `baseColor` (models, 117 HD constants) — undo of the bake.
2. `GroundBlender.undoShading` (terrain, different constants: lighten ×1.5 + 15 − 3, darken ×0.5, **GAIN 1.25**,
   cap 55, `GroundBlender.java:44-51, 345-361`) — a second undo formula for the same quantity, applied from two call
   sites: inside the blend table (`add` :305-308) and for unblended vertices (`SceneUploader.groundColor` :761-772).
3. `unlitColors[face]` swap (flat faces under 50°).
4. Blended vertex colour from `GroundBlender.lookup` (§7).
5. Water-bed colours: `bedCornerColor`, `shoreColor`, `lerpHsl`, `mixHsl`, `landColor` (`SceneUploader.java:912-1146`)
   — averages of neighbouring bank colours across up to 2 tiles.
6. `interpolateHSL` (game override, dynamic only) and `entityTint` (scene override, all).
7. `smoothBanding` RGB-vs-HSL interpolation and the border mix in `frag.glsl`.
8. `brightness` and `textureLightMode` for textured faces.
The "terrain gain" the user wants (render-plan §3.5 "terrain bake undo + 1.25 gain, cap 55, never the vertex colour
blending") lives only inside `GroundBlender.undoShading`, i.e. inside the blending class, and is switched off with
`groundBlending` for blended vertices (`:305`) but **not** for unblended ones (`groundColor` applies it whenever
`undoVanillaShading`): toggling `groundBlending` changes terrain brightness only on the tiles that blend
(unverified visually).

**Verdict:** Merge into one `undoBake(colour, normal)` with 117 HD's constants plus the terrain gain as the single
terrain branch; keep `entityTint`/HSL override (game semantics); drop the unlit-colour swap with the 50° rule;
`smoothBanding` and `brightTextures` are candidates for removal (two settings, one look).

---

## 7. Ground blender (terrain colour blending across tiles)

**Purpose (comments):** "Ground color blending, the color half of 117 HD's ground blending … every untextured ground
vertex gets one color, shared by all tiles and tile-model faces that touch it, so colors interpolate across tile
borders" (`GroundBlender.java:9-17`).

**Files / functions:** `GroundBlender.build` :94, `addTile` :217, `add` :290 (117 HD priority rules :330-338),
`lookup` :137, `unblended` :158 (`UNBLENDED_OVERLAYS` :64), `isOverlayFace` :163 (117 HD's table :29);
`SceneUploader.upload(SceneTilePaint)` :645-666, `upload(SceneTileModel)` :871-888, `borderFlag` :752 → `vertf.w = -1`
→ `frag.glsl:118-126` border mix.

**How it works:** per zone, an open-addressing table (16 Ki entries, stamped per build) of blended colours keyed by
117 HD's quarter-tile key (`:363-368`) over the zone plus a one-tile border; overlays win, underlays fill, near-black
loses. Vertices where overlay and underlay meet get the border flag; the fragment shader interpolates border and
inner colours separately and switches over the middle half (117 HD's overlay mix). The table is also built with
blending off because the water bed takes bank colours from it (`:150`, `:231`).

**Settings:** `groundBlending` (reload).

**Cost shape:** per zone upload: every tile and tile-model vertex of 10×10 tiles × 4 levels hashed twice (build and
lookup); fixed 3 × 64 KiB arrays. Shader: two extra varyings (`fBorderColor`, `fInnerColor`) on every vertex of every
pass.

**Duplicates / overlaps:** the only place the terrain gain lives (§6); the bed colour code depends on it even when
off; the border flag shares the fourth position short with the wind code (`vert.glsl:43`) — a ground vertex can
never sway and a swaying vertex can never be a border, fine, but the shader tests `vertf.w < -0.5` and `c >= 128`
on the same float.

**Verdict:** Remove the blending (the user's rule: never blend terrain colours between tiles); move
`undoShading`+gain to §6's single routine; replace the bed's bank-colour sampling with the tile's own colour or
drop it with the water rework; frees two varyings and one setting.

---

## 8. Terrain upload details: water bed, shore tiles, standing objects

**Purpose (comments):** "A water tile as the bed under the water: lowered, no texture, bed colored, flagged for the
composite" (`SceneUploader.java:909-911`); "Static objects standing in the water … faces with an edge within
WATERLINE … are extruded down to the bed" (:65-69).

**Files / functions:** `uploadBed` :912, `bedFan` :1081, `bedCornerColor` :975, `putBedVertex` :1117,
`markLandVertices` :1092, `extrude` :1411, `surfaceHeights` :730; `WaterMap.LAND_FLAG`/`BED_FLAG` material bits
(`SurfaceMaterials.java:30`), `WaterOverrides.hidesFace` ripples (:1233-1297).

**How it works:** water tiles are replaced by a sunk bed (fan of 4 triangles on shore tiles), coloured from the
nearest bank colours, flagged in the material bits; shore tile models get their water faces lowered and their
land faces first so bank vertices keep land colours; opaque faces of objects standing in water with an edge at the
waterline are extruded to the bed with affine-projected UVs; 117 HD's ripple faces are skipped and
`Zone.addAlphaModel` is told to skip them too (`rippleFace`).

**Settings:** `water` (reload).

**Cost shape:** per zone upload; `bedCornerColor` samples a 5×5 corner neighbourhood per bed corner (25 table lookups
× 4 per tile, × 4 again per bed vertex of shore models); `standingSize`/`atWaterLevel` scan every vertex of every
static model when water is on (:1240-1267).

**Duplicates / overlaps:** colours decided by path 5 of §6; normals from `surfaceHeights` (bed) vs vanilla heights
— two slope sets per vertex (§5). All of this is water-area logic living inside the generic uploader.

**Verdict:** Move out of `SceneUploader` into the water module's own pass over the terrain, and decide its colours by
the tile's own colour (no bank averaging); the extrusion can stay as a per-face upload rule.

---

## 9. Shadow map: static cache, per-frame copy, dynamic casters

**Purpose (comments):** "Orthographic shadow map for the single directional caster (sun or moon)" (`ShadowMap.java:5`);
"Static scenery cache: the zones drawn into a map of their own … Each frame that map is copied into the one sampled
and only the moving models are drawn over it. A new static map is drawn into the other texture, over a few frames"
(:27-30).

**Files / functions:** plugin `renderShadowMap` :2387, `renderStaticShadows` :2525 (`SHADOW_CACHE_SNAP 256`,
`SHADOW_BUILD_FRAMES 3`, `SHADOW_CACHE_TEXELS 1.0` :2507-2513), `shadowStale` :2618, `shadowZones` :2645,
`shadowZone` :2691, `SHADOW_CACHE = true` :354; `ShadowMap.startBuild` :247, `finishBuild` :278, `holdBuild` :285,
`swapFront` :302, `buildMax` :315, `useFront` :380, `project` :424; shaders `shadow_vert.glsl`, `shadow_frag.glsl`
(alpha-tested holes, emissive/transmissive discards), `shadow_copy_frag.glsl`, `shadow_max_frag.glsl`;
`VAO.ShadowDraw` :146 (dynamic casters); invalidation `shadowStaticDirty = true` at `swapScene` :4685 and
`refreshCache()` at `rebuild` :3919.

**How it works:** Each frame, if outdoors and `shadows`: (a) `renderStaticShadows` — if the focus left its 256-unit
cell, the light turned more than ~1 texel for a 512-high caster, a zone was rebuilt (`stale`), or the scene swapped
(`shadowStaticDirty` → `invalidateCache` → **front = -1**), a new static map is started into the other of two
`size²` depth textures and `shadowBuildZones` (all initialised zones within `radius + 2048` in a square) are drawn
over 3 frames — **or in one frame when there is no front map** (:2568: "the first map is needed at once"), which is
the case after every scene swap; on finish, `buildMax` runs 3 fullscreen passes to compute the per-tile maximum
depth for the PCSS early-out; a second "held" map serves lightning bolts. (b) `useFront` copies the front static map
into the sampled map with a fullscreen `gl_FragDepth` triangle (not a blit — Apple GL). (c) swaying zones
(`SWAY_ONLY`) are redrawn when `windSwayShadows`. (d) every recorded opaque dynamic draw is redrawn from the light.

**Settings:** `shadows`, `shadowDistance`, `shadowResolution`, `windSwayShadows`, `emissiveStrength` (emissive casters
only when glow is off, `shadow_frag.glsl:17`).

**Cost shape:** GPU per frame: copy pass (size² texels) + dynamic casters + optional sway zones; HANDOVER B1 shadow
map **0.64 / 1.80 ms** (yaw-dependent). Static build: all zones in reach ÷ 3 frames, but **the whole static map plus
`buildMax` in the swap frame** (HANDOVER B3: "a GPU stall: the whole static shadow map and the voxel upload in one
frame", `glEndQuery` stacks); every `rebuild` (an object appearing) restarts a 3-frame full redraw. Memory: 3 depth
textures of `size²×4 B` (sampled + 2 static) + 2 small max textures; 4096² → 64 MiB each (`ShadowResolution`
values unverified). CPU per frame: `shadowZones` square scan over 24×24 zones (cheap), `shadowStale` maths.

**Duplicates / overlaps — every shadow-related pass:** (1) static cache build (zones), (2) `buildMax` ×3 passes,
(3) `useFront` copy pass each frame, (4) sway zones each frame, (5) dynamic casters each frame, (6) PCSS receiver in
`ao_frag.glsl` at half res (§11), (7) denoise + upsample of that (B4, out of area), (8) volumetric fog sampling the
map for shafts (`RuneLightPlugin.java:3392`), (9) the rain occlusion map drawn with the same shadow program from
straight up (§10), (10) cloud shadows (`clouds.glsl`, `cloudShadows` setting, out of area), (11) the non-cache path
(`SHADOW_CACHE = false`, a compile-time dead variant :2418-2427, :2472-2476), (12) the `held` bolt map (lightning).
Transparent faces get no shadows at all (`frag.glsl:65-66`).

**Verdict:** Restructure (B6) into one mechanism: Lumina's static map spread over frames **including the first one
after a swap** (first frames draw with no shadow or the stale map), one dynamic map, receiver in the forward/composite
pass with min/max tiles; drop the copy pass (render static once, sample two maps or composite them in the receiver),
drop the held bolt map, the `SHADOW_CACHE=false` variant, and make the occlusion map a slice of the same scheduler.

---

## 10. Rain occlusion map (shadow program reused)

**Purpose (comments):** "Redraw the occlusion map if needed, with the shadow program: same vertex formats, and it
keeps alpha tested holes (leaves, grates) open" (`Weather.java:843-845`).

**Files / functions:** `Weather.renderOcclusion` :847 (`OCCLUSION_SIZE 1024`, `OCCLUSION_RADIUS 64 tiles`,
`OCCLUSION_DEPTH_RANGE 4096` :39-43), plugin call :2126, `invalidateOcclusion` at `rebuild` :3918 and `swapScene`
:4684; `shadow_frag.glsl:19-20` (`foliageOpen`).

**How it works:** a second `ShadowMap` instance pointed straight down over ±64 tiles, redrawn when the player moves
far, the plane changes, a zone rebuilds or the scene swaps, drawing every level/roof of every zone in a square reach
(`:895-916`); the weather shaders read it to keep rain off covered ground.

**Settings:** `weatherMode` (active) — out of area.

**Cost shape:** per event, GPU: all zones within 65 tiles into a 4 MiB depth texture, **in the swap frame** together
with §9's full static build. CPU `OCCLUSION` timer.

**Duplicates / overlaps:** a third full-scene depth render from a fixed direction (static shadow, voxel skylight,
occlusion) each rebuilt on the same events.

**Verdict:** Merge into §9's scheduler as one more budgeted job (or derive cover from the voxel grid when A3's
voxel occlusion lands); never in the swap frame.

---

## 11. PCSS receiver (in the AO pass)

**Purpose (comments):** "Sun and moon shadows: PCSS. Blocker search for the average occluder depth, then a PCF kernel
sized by the distance between occluder and receiver … Drawn at half resolution in the AO pass" (`shadow_pcss.glsl:1-4`).

**Files / functions:** `shadow_pcss.glsl shadowFactor` :42 (12 search taps, 8/16 filter taps, `MAX_PENUMBRA_WORLD
112`, tile-max early-out :57-62), `ao_frag.glsl:60-66`, plugin AO uniforms :3326-3338, `ShadowMap.bindMax` :364,
`bindForSampling` :488 (two samplers on one texture), `unbind` :504 (1×1 empty depth for NVIDIA).

**How it works:** per half-res pixel with shadows on and a non-emissive material: normal-offset the position, test
the per-tile static maximum (skip when fully inside shadow), 17 raw-depth taps for blockers (the point's own texel and
16 rotated by IGN, packed toward the centre), of which only those whose penumbra cone reaches the point are averaged,
then 8 compare taps unrotated (a kernel under two texels: no dither) or 16 rotated by IGN; result goes into
`FragShadow.r` with the dithered kernel's reach on screen (`shadow_reach.glsl`), which the denoise uses to smooth the
lit share only as far as each texel's penumbra reaches (2026-11-02, pcss-sharpen: contact hardening; before, every
shadow edge took the full 5×5 denoise and the blocker average took in occluders whose penumbra never reached).

**Settings:** `shadows` (and the AO chain's `aoQuality`/`ambientOcclusion`, out of area, share the pass).

**Cost shape:** GPU per frame per half-res pixel: up to 33 texture taps; HANDOVER B1 "AO+skylight 1.04 / 0.91" and
"AO/fog filtering 0.59 / 0.52" include it; at 4K the chain is B4's critical path.

**Duplicates / overlaps:** the receiver lives in the AO pass so shadows cost a denoise and an upsample even with AO
off (`aoSamples <= 0` still runs the shadow, :60-69); the empty-texture `unbind` exists because the composite program
always declares the sampler.

**Verdict:** Merge into B6's single shadow mechanism: receiver at full res in the lighting pass (PCF with min/max
tiles), no denoise dependency.

---

## 12. Zone culling and the edge of the world (`EdgeFade`)

**Purpose (comments):** "the world ends at a circle of the draw distance around the camera's focal point … the
client is given a larger square that covers the circle wherever the camera is, zones and models outside the circle
are skipped … Zones are also tested against the view frustum with generous bounds (117 HD does the same)"
(`EdgeFade.java:8-16`).

**Files / functions:** `EdgeFade.zoneVisible` :196 (`PAD_XZ 4`, `PAD_UP 10`, `PAD_DOWN 2` tiles), `inFrustum` :162,
`inCircle` :186, `modelVisible` :208 (`MODEL_PAD 6` tiles), `clientDrawDistance` :63 (`CLIENT_MARGIN_TILES 10`),
`setFrustum` :143, `update` :117; plugin `zoneInFrustum` :2223 (client callback, flag `ZBUF_ZONE_FRUSTUM_CHECK` :902),
`updateEdge` :2208, `drawDynamic`/`drawTemp` :3692/:3769; shader `edge_fade.glsl` (fade to sky) used in
`frag.glsl:168`, composite, water (out of area).

**How it works:** per frame the client is told a draw distance of `drawDistance + camera offset + 10` tiles; the
client asks `zoneInFrustum` per top-level zone, answered by circle test then 5-plane AABB test with pads; dynamic
models are circle-tested by position; everything fades to sky toward the circle in the shaders.

**Settings:** `drawDistance`; fade width follows weather fog (no setting).

**Cost shape:** per zone per frame: ~20 multiplies; per model per frame: 2 multiplies. Hidden cost: the client
traverses a square up to `(d + offset/128 + 10)²` tiles instead of `d²` (HANDOVER/old-renderer-vs-lumina: "it
enlarges the client draw distance (CPU)"); HANDOVER B1's `client scene` 0.7–0.9 ms includes this.

**Duplicates / overlaps — every culling mechanism:** (1) `zoneVisible` circle+frustum for the main pass; (2)
`modelVisible` circle for temps; (3) the client's own square and roof/level hiding (`hideRoofIds`, `pushLevels`); (4)
`shadowZones` square reach (no frustum, correct for shadows); (5) `renderOcclusion` square reach; (6)
`RegionManager.prepare` removes other regions' tiles at load (`hideUnrelatedMaps`, `RegionManager.java:59-86`); (7)
`AreaHiding.removeOutside` at load (§13); (8) `renderCallbackManager.drawTile/drawObject` (other plugins); (9)
`uploadSortedModel`'s per-face backface test by projected winding (`ModelUploader.java:218`) and near reject
(`p[2] < 50` :181); (10) `Zone.renderAlpha` level/roof/SKIP filters. HANDOVER B7 wants Lumina's
`ZBUF_ZONE_FRUSTUM_CHECK` implementation — this is already one, with the circle on top.

**Verdict:** Keep the frustum test and the circle as a culling rule (one mechanism), but measure the enlarged client
square against a plain frustum cull before keeping the "circle around the focal point" (B7); keep the fade as the
one edge treatment.

---

## 13. Area hiding and region hiding (tiles removed at load)

**Purpose (comments):** "117 HD's areas that hide the rest of the map … The tiles are taken out of the scene as it
loads, before anything reads it" (`AreaHiding.java:13-20`); `RegionManager.prepare` "remove tiles from the scene that
are outside the current region" (`RegionManager.java:57`).

**Files / functions:** `AreaHiding.prepare` :166, `removeOutside` :181, `changed` :219, `sameAsShown` :203; plugin
`hideAreas` :3839 (sets `GameState.LOADING`), `loadScene` :4276, `swapScene` :4655; `RegionManager.prepare` :59,
`removeZone` :88, `regions/regions.txt` (465 lines); data `area_hiding.json` (328 areas, 36 KB).

**How it works:** at load, before sizing, every tile outside the player's area (and its unhide boxes) or outside the
central region id is `scene.removeTile`d; walking into another area triggers a **full client scene reload**
(`setGameState(LOADING)` :3850), and zones cannot be reused across such a load.

**Settings:** `hideOtherAreas`, `hideUnrelatedMaps`.

**Cost shape:** per load on the loading thread: `removeOutside` visits every extended tile × 4 levels with a
`toWorld` per tile (~104²×4 ≈ 43k; cheap); per event: a complete reload (the heaviest thing in the renderer, the
client's own 100+ ms build, HANDOVER B3) whenever the area boundary is crossed. `locate` is linear over 328 areas per
tile.

**Duplicates / overlaps:** two tile-removal mechanisms with two settings and two data files (117 HD areas, RuneLite
regions) for one quantity ("hide the map that should not be seen").

**Verdict:** Merge into one list and one setting (117 HD's areas, which include the region cases or can), and
replace the reload-on-crossing with a per-zone visibility mask applied at draw time (no reload, zones reusable).

---

## 14. Surface materials (emissive, transmissive, snow, fake water) and textures

**Purpose (comments):** "Per-surface material behaviour, from materials.json … Every face carries 7 material bits in
the spare high bits of its texture id short" (`SurfaceMaterials.java:23-39`).

**Files / functions:** `SurfaceMaterials.load` :232, `forObject` :402, `forRenderable` :429, `face` :467,
`fakeSpan` :522, `Group.level`/`Rule.matches` :144/:189; `Interactables` (snow-reduced objects, client-thread
resolution); shaders `materials.glsl`, `material_target.glsl` (material + face normal G-buffer channel),
`frag.glsl:125-127`; `TextureManager.initTextureArray` :46 (128×128 RGBA8 array, 8 mips, `TEXTURE_COUNT 256`),
`setAnisotropicFilteringLevel` :92, `setLodBias` :124, `computeTextureAnimations` :237; plugin lazy init :3982-3996,
aniso/LOD :1934-1948; `tools/import_117hd_materials.py` (derives transmissive/emissive/fake-water from 117 HD's
overrides, lights and textures — see its docstring :1-45); `materials.json` (1932 transmissive objects, 242 emissive
NPCs, 332 spotanims, 16 emissive groups, 23 fake-water groups).

**How it works:** per object/NPC/spotanim id a flag int (emissive level, transmissive, fire, snow class, emissive
group, fake-water group) is looked up at upload and resolved per face by texture, colour rules and opacity into 7
bits above the 9-bit texture id; the fragment shader forwards them to the material target; the composite reads them.
Textures are the game's, uploaded once when all are loaded, with mip bias following render scale.

**Settings:** `emissiveStrength`, `transmissionStrength` (uniforms only, no reload), `anisotropicFilteringLevel`.

**Cost shape:** per face per upload: a table lookup + for group models a rule evaluation allocating `int[] v` per
face (`Rule.matches` :191 — allocation on hot path); per frame two uniforms. Textures: one-time 8 MiB array
(256×128×128×4 with mips ~10.7 MiB).

**Duplicates / overlaps:** `isBark`/`isHot`/rule colours are a second colour-based classification beside 117 HD's
overrides (which are not imported); the face bits share the texture short, the sway code shares the position short,
the normal shares the last texture short — the vertex format is full. `forObject` is called twice per static
renderable (`SceneUploader.java:568` and via `fakeSpan`), and `face()` is evaluated once in `fakeSpan` and again per
face.

**Verdict:** Keep (data and per-face upload rules are A2's direction); fix the per-face allocation; extend the same
JSON with 117 HD's flat-lit and inherit-tile-colour flags rather than adding a second override system.

---

## 15. Wind sway on vertices

**Purpose (comments):** "Plants that sway in the wind, after 117 HD's wind displacement … Each vertex of a swaying
object carries a code in the fourth position short of the static vertex format" (`WindSway.java:15-19`).

**Files / functions:** `WindSway.vertexCode` :159, `forObject` :121, `anchor` :146; `SceneUploader.java:1218-1231`
(codes), `flushSway`/`addSwayRange` (ranges); `sway.glsl windDisplacement` :37 (in `vert.glsl:108` and
`shadow_vert.glsl:23`); plugin sway tick :2001-2008, 2447-2448; data `wind.json` (1378 objects).

**How it works:** per vertex a bend weight (sqrt-coded), anchor tile residue and jiggle flag; the vertex shader finds
the anchor, derives phase/gust/wave from it and displaces along a bend curve, tilting the normal; the wind uniforms
are updated at a limited rate (`windSwayFps`); swaying zones are a separate draw range so the shadow pass can redraw
only them.

**Settings:** `windSway`, `windSwayStrength`, `windSwayShadows`, `windSwayFps` — four settings for one effect.

**Cost shape:** vertex shader: ~30 ALU + a hash per swaying vertex (all vertices pay the `c < 128` test); zone upload:
one pass over vertices for height, one for codes; shadow pass: sway zones redrawn per frame when enabled.

**Duplicates / overlaps:** dynamic models (animated objects) never sway by design (`:26-27`); the position short is
shared with the ground border flag (§7).

**Verdict:** Keep the mechanism; collapse the four settings to one (on/off, strength fixed, tick rate fixed to the
game's).

---

## 16. `LookMonitor` (debug)

**Purpose (comments):** "Debug aid for finding what makes the lighting snap while walking: logs every scene load and
swap … and every frame in which a value that feeds the lit look jumps" (`LookMonitor.java:7-11`). Client thread,
only when the logger is at DEBUG (`:43`, :61-97). Called at `loadScene` :4269, `swapScene` :4680 and once per frame.

**Cost shape:** per frame a 32-float copy and compare; nothing when not at DEBUG beyond the array copies (the
`frame` method does not early-out — unverified whether the caller gates it).

**Verdict:** Remove once B3/A1 are done, or gate `frame` behind `isDebugEnabled` like `scene`.

---

## 17. Zone rebuild on object change (`invalidateZone` → `rebuild`)

**Files / functions:** plugin `invalidateZone` :3795, `rebuild` :3865 (in `onPostClientTick` :3830).

**How it works:** when the client changes a zone (door, farming patch, spawn), the zone is freed and rebuilt
synchronously on the client thread in the tick: `zoneSize`, `Interactables.resolve`, two VBO allocs + maps, `uploadZone`,
`unmap`; then `weather.invalidateOcclusion()`, `shadowMap.refreshCache()` (full 3-frame static redraw),
`voxelBuilder.zonesChanged()`.

**Cost shape:** per event, client thread, unbounded (all invalidated zones in one tick); HANDOVER's hitch list names
"zone rebuild" and B3 asks for a per-frame budget.

**Verdict:** Restructure — rebuild at most one zone per frame (or on a worker with the GL part sliced), and redraw only
that zone's region of the static shadow map instead of the whole map.

---

## The five heaviest or messiest things, ranked

1. **The swap frame's bundle** (§1, §9, §10): `swapScene` frees/unmaps ~190 zones (3.5–4.5 ms) and the same frame
   starts a *one-frame* full static shadow build (`front == null → frames = 1`, `RuneLightPlugin.java:2568`) plus
   `buildMax`, plus the 4 MiB rain occlusion redraw, plus the out-of-area voxel/light-map jobs — HANDOVER B3's 49–53 ms
   frames. Every later zone rebuild restarts a full 3-frame static redraw.
2. **Three copies of the per-face upload pipeline and per-frame normal recomputation for dynamic models** (§4, §5):
   `uploadStaticModel`, `uploadSortedModel`, `uploadTempModel` each re-implement normals → undo → unlit swap → material
   bits → pack; dynamic models pay `computeNormals` + `flatFaceNormals` + `ModelData.copy` every frame, hidden behind
   a 64-job worker ring and 4 MiB mapped buffers per render thread.
3. **Vertex colour decided in eight places with two bake-undo formulas** (§6, §7, §8): `baseColor` vs
   `GroundBlender.undoShading`, the unlit-colour swap tied to the 50° rule, blended terrain colours (forbidden by the
   rules), bank-averaged bed colours, plus HSL override, scene tint, banding mix and border mix in the shaders.
4. **Shadows as twelve passes and consumers** (§9–§11): static build, max ×3, copy, sway, dynamic, PCSS in AO, denoise,
   upsample, fog shafts, occlusion map, held bolt map, dead non-cache variant; 0.64–1.80 ms GPU before the receiver.
5. **Normals decided in five places with the 50° gate** (§5): the gate is the likely cause of the hard-edged trees
   (all-three-corners test, per-face constant normal on failure, double-sided leaf cancellation), and the composite
   re-flattens upright faces for point lights.

## What runs on the client thread at a scene load (estimates where HANDOVER has no number)

| Step | Where | Thread note | Estimate |
|---|---|---|---|
| VBO alloc + map for every new zone (`glGenBuffers`, `glBufferData`, `glMapBufferRange` ×2 per zone), `Interactables.resolve` | `loadScene` :4449-4493 | `clientThread.invoke` under a latch while the loading thread waits | ~380 buffer creations; est. 1–3 ms (unmeasured) |
| `swapScene`: `zone.free` ×~190, `updateRoofs`, module swaps, `zone.unmap` ×~190 | :4644-4719 | client thread, swap frame | **3.5–4.5 ms** (HANDOVER B3: free/roofs 2.5–3.3, unmap 0.6–0.8, modules 0.5) |
| Static shadow map: all zones in reach drawn into a fresh map in one frame + `buildMax` ×3 | `renderStaticShadows` :2555-2594 | client thread submits; GPU stall (`glEndQuery`) | GPU: a full shadow pass (0.6–1.8 ms steady-state equivalent for the per-frame part; the full static draw is several times that — unmeasured); HANDOVER: "the whole static shadow map … in one frame" |
| Rain occlusion map redraw (when weather active) | `Weather.renderOcclusion` :847 | client thread, same frame | GPU: all zones within 65 tiles into 1024²; est. 0.5–1.5 ms (unmeasured) |
| `LookMonitor.scene`, `regionManager.prepare`, `areaHiding.prepare` | :4268-4277 | loading thread (not client) | negligible |
| `hideAreas` → `setGameState(LOADING)` on area crossing | :3847-3850 | client thread; triggers the client's own full scene build | the client's 100–170 ms build (HANDOVER B3 first run) |
| Out of area, same frame (for the ledger): `RoomMap.texels`/`LightClusters.upload`, `LightMap.update` → `glTexSubImage3D`, voxel upload, sky capture | HANDOVER B3 | client thread | **34–39 ms** untimed remainder (HANDOVER) |

## Settings count

`RuneLightConfig.java` has **67** `keyName`s (HANDOVER says 69; the two may be hidden items or an older count —
unverified). **21** belong to this area: `drawDistance`, `expandedMapLoadingChunks`, `anisotropicFilteringLevel`,
`emissiveStrength`, `transmissionStrength`, `shadows`, `shadowDistance`, `shadowResolution`, `removeBakedShading`,
`groundBlending`, `windSway`, `windSwayStrength`, `windSwayShadows`, `windSwayFps`, `numThreads`, `smoothBanding`,
`brightTextures`, `removeVertexSnapping`, `hideUnrelatedMaps`, `hideOtherAreas`, `dumpScene` (debug). Proposed by the
verdicts above: keep `drawDistance`, `expandedMapLoadingChunks`, `shadows` (+ one quality), `windSway`,
`numThreads`, `removeVertexSnapping`, one hide-areas toggle → **8**; remove `groundBlending`, `removeBakedShading`
(always on, as 117 HD), `windSwayStrength`, `windSwayShadows`, `windSwayFps`, `smoothBanding`, `brightTextures`,
`shadowDistance`/`shadowResolution` (fold into one quality), `hideUnrelatedMaps` (merge), `anisotropicFilteringLevel`
(fixed), `emissiveStrength`/`transmissionStrength` (fixed), `dumpScene` (command).
