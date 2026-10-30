# Inventory: point lights and what feeds them (branch `lumen`, 2026-10-30)

Read-only inventory of the old RuneLight renderer's lamp pipeline, for Track A3 and rule 2 of HANDOVER.md (one
mechanism per quantity). Paths are under `src/main/java/com/midonarhel/runelight/` (Java) and
`src/main/resources/com/midonarhel/runelight/` (GLSL) unless given in full. Line numbers are of the working tree at
commit 7193e68a. "Unverified" marks what was not confirmed by reading (no build or run was made).

Conventions used below: *client thread* is the RuneLite client thread the plugin draws on; *worker* is a thread the
plugin owns. "Per pixel" is per composite pixel at render scale (the composite runs full-res; fog at quarter/half res).

---

## 1. Light definitions (`lights.json`, `LightDefinition`, `tools/import_117hd_lights.py`)

**Purpose (code's words):** "One entry of lights.json, which is converted from 117 HD's light definitions by
tools/import_117hd_lights.py. Field names match the JSON." (`LightDefinition.java:3-6`).

**Files / functions:** `LightDefinition.java` — fields :9-93, `Alignment` :100-121, `prepare()` :135-151 (sRGB → linear,
kind, nightOnly), `allowedAt()` :156-160 (area boxes), `matchesAnimation()` :187-198, `locate()` :219-238 (117 HD's
placement by offset or alignment). `tools/import_117hd_lights.py:1-25` (resolves gamevals to ids, areas to boxes;
"Every definition is imported"). `lights.json` (575 KB, one line).

**How it works:** a flat JSON array; each entry attaches to object ids, NPC ids, projectile ids, graphics-object ids, or
a fixed world tile. Counted from the file: 2,892 definitions — 1,182 world, 1,078 object, 253 NPC, 133 projectile,
249 graphics; by type 557 STATIC, 877 FLICKER, 1,458 PULSE; 34 `outdoorLighting`, 19 `schedule: NIGHT`.
Strength/colour are 117 HD's and are "tuned for lighting in linear space" (`LightDefinition.java:141`).

**Settings:** none.

**Cost shape:** per plugin start: Gson parse of 575 KB on the client thread (`SceneLights.load`, `SceneLights.java:230`,
called from `RuneLightPlugin.java:736`). No per-frame cost.

**Duplicates:** `autolights.json` presets are `LightDefinition`s too (§2). No other definition format.

**Verdict:** keep.

---

## 2. Automatic lights (`AutoLightRules`, `AutoLightPlacement`, `autolights.json`)

**Purpose (code's words):** "Which things that burn or glow get a light that 117 HD does not give them
(autolights.json): the rules look at the name of a game object ... The first rule that fits wins." (`AutoLightRules.java:15-20`);
"Where an automatic light goes on an object, from its model: the middle of the faces colored like a flame, or where
there are none the middle of the upper part of its bounds." (`AutoLightPlacement.java:5-8`).

**Files / functions:** `AutoLightRules.match()` :78-100 (word patterns, exclusion words); `AutoLightPlacement.isFlame()`
:39-44 (HSL hue 3–11, sat ≥ 5, light ≥ 56), `locate()` :49-56, `flames()` :64-89, `bounds()` :98-117,
`awayFromWall()` :139-145; `autolights.json:1-24` (6 presets, 6 rules, 33 exclusion words). Consumer:
`SceneLights.autoLight()` :575-591, `placeAuto()` :593-660, `nearWorldLight()` :662-676 (skips tiles within 1 of a
117 HD world light).

**How it works:** on scene rebuild and object spawn, an object with no `lights.json` entry is matched by its
`ObjectComposition` name; its model (`ModelData.wrap`) is scanned for flame-coloured faces to place the light, else
the upper quarter of its bounds; a wall decoration without a flame is pushed 16 units off the wall. Auto lights are
emitted after the others each frame so a full budget (1024) drops them first (`SceneLights.java:1136-1137`,
`appendNearest` :1236-1247 sorts them by distance when over budget).

**Settings:** none (no toggle).

**Cost shape:** per scene load and per spawn event on the client thread: a model scan per matched object (O(faces)).
Per frame: the auto list sort only when candidates exceed the remaining budget.

**Duplicates:** the "near a world light" rule duplicates by proximity what `lights.json` already covers by id; both
produce `Placed` lights that then go down one path. Not a quantity duplicate.

**Verdict:** keep (data + a placement rule; no setting); fold the log tallies (`autoMatched`, `autoTally` :86-91)
into the one funnel log of §3 or drop them.

---

## 3. Scene lights: placement, tracking, life and the per-frame list (`SceneLights`, `LightLife`, `OutdoorLight`, `PointLight`)

**Purpose (code's words):** "Places the lights from lights.json in the scene: on objects (tracked through spawn events,
including objects that change appearance through impostors), on NPCs, projectiles and spell effects (followed every
frame), and at fixed world positions. Everything runs on the client thread." (`SceneLights.java:58-62`).

**Files / functions:** `SceneLights.java` — `Placed` :122-155 (creates the `LightMap.Source` for bakeable lights at
:150-154: not `outdoorLighting`, not animation-specific, not `fixedDespawnTime`, reach ≤ `LightMapBake.MAX_RADIUS`),
`rebuild()` :330-403 (scans every extended tile; logs two summaries), `add()` :420-444, `remove()` :446-464,
`placeObjectLights()` :487-536, `placeAuto()` :593-660, spawn/despawn subscribers :679-740, `onGameTick()` :744 (impostor
re-resolution), `attachNew()` :852-923 (NPCs, projectiles, graphics objects each frame), `follow()` :938-1013,
`hiddenByActor()` :1015, `collect()` :1057-1144 (the per-frame entry), `emitFixed()` :1255-1273, `emit()` :1298-1408
(flicker :1330-1340 — six cosines per light per frame; pulse :1341-1348; night :1350-1354; `lightMap.setLevel` for
mapped lights :1370-1374; `OutdoorLight.apply` :1376-1382), `appendNearest()` :1236-1247, funnel log :1146.
`LightLife.java:9-175` (spawn/despawn/fade/hide state machine; `HIDING_FADE` 64 ms :12). `OutdoorLight.apply()` :29-43
(sky-following colour and strength from sun altitude). `PointLight.java:7-35` (the per-frame record: position,
radius, linear colour, intensity, plane). Caller: `RuneLightPlugin.collectLights()` :2965-2996, under
`CpuTimer.LIGHTS` (:2769-2779).

**How it works:** static lights are `Placed` once (object or world) and kept in `objects`/`worldLights`; followers
(`Live`) are re-attached each frame from the world view's NPC/projectile/graphics lists and moved to their parent.
`collect()` runs every `Placed` and `Live` through `LightLife.update`, culls by distance from the edge-fade focus
(`reach = maxDistance + radius*1.5`, :1316-1328), applies flicker/pulse/night/fade, and either (a) hands the light to
the clusters as a `PointLight`, or (b) if the light map carries it (`LightMap.isLive`), only updates its table entry
(`setLevel`) and emits nothing. Lights on another plane are dropped on the CPU unless `visibleFromOtherPlanes`
(:1305-1313) — but a mapped light bypasses that rule ("The map knows what the floors let through").

**Settings:** `pointLights` (`RuneLightConfig.java:228-237`) gates `collectLights` (`RuneLightPlugin.java:2770-2778`);
`testLight` (:932-940) adds one warm light at the player (`RuneLightPlugin.java:2983-2994`).

**Cost shape:** per frame, client thread: O(placed + live) with trig for every FLICKER light within reach (877 of the
2,892 definitions flicker; each flicker evaluates 6 `Math.cos` :1333-1336). `attachNew` walks all NPCs, projectiles and
graphics objects per frame (:852-923). Per scene load: full tile scan + model scan per auto candidate. HANDOVER B1:
"light upload 0.25" ms CPU covers §4's upload, not this step; `CpuTimer.LIGHTS` is not quoted in HANDOVER (unverified
number).

**Duplicates / overlaps:** plane gating exists twice — here on the CPU (`visibleFromOtherPlanes`, :1305) and in the
shader for cluster lights (`lightOnStorey`, `light_map.glsl:33-36`, fed by `LightClusters.storeyCode` :539-543); the
light map has a third answer (cross-storey bake, §6). Night fade exists as `night` from `collectLights`
(`RuneLightPlugin.java:2968-2970`, smoothstep over sun −2°…−10°) and as `BuildingLights.nightFactor` (+6°…−6°,
`BuildingLights.java:171-176`) used by `RoomLight.target` (:290) — two night curves for lamp-related quantities.

**Verdict:** keep; restructure the plane rule into one place (the occlusion structure of §6's replacement) and the two
night curves into one.

---

## 4. Clustered culling and the light buffer (`LightClusters`, `light_clusters.glsl`)

**Purpose (code's words):** "Clustered light culling. The view is split into a grid of CLUSTERS_X * CLUSTERS_Y screen
tiles and CLUSTERS_Z exponential depth slices; every light is listed only in the clusters its sphere overlaps ...
Built on the CPU because macOS OpenGL 4.1 has no compute shaders" (`LightClusters.java:10-19`).

**Files / functions:** `LightClusters.java` — constants :23-37 (32×18×24 clusters, 31 lights per cluster, 1024 lights,
128 lights per RGBA32UI texel, NEAR 50, FAR 25600; buffer layout: lights at 0, light-map table at 2048,
room fill at 2048+4096, room map at `ROOM_BASE` after that), fog columns :40-48, triple buffering `SETS=3` :50-55,
`init()` :114-144, `cull()` :245-321 (sphere → screen rect + depth slices; `storeyCode`; fog candidates), `fillClusters()`
:323-372 (sphere-vs-box per cluster; `overflows`), `fillFogColumns()` :391-436, `upload()` :449-525 (room map
:458-481 — now from `RoomMap.packAsync` with the old copy kept until ready; room fill :482-489; lights :490-493;
light-map table :494-501; header :503-505; masks :507-524), `storeyCode()` :539-543, `computeBounds()` :586-676
(only when the view-projection changes), `columnTouches()` :679-690, `screenBounds()` :708-741.
`light_clusters.glsl` — `clusterEntry()` :7-13, `columnEntry()` :16-20, `LightMask`/`nextLight()` :23-53 (bit walk,
float-exponent trick for findLSB on GLSL 3.30), `pointLightFalloff()` :63-66 (117 HD's `(1-sqrt(d²/r²))²`).
Callers: `RuneLightPlugin.prepareLighting()` :2748-2808 (`setRooms`, `setLightMap`, `setOpenRooms`, `setFog`, `cull`
under `CpuTimer.CLUSTERS` :2792-2798, `upload` under `CpuTimer.LIGHT_UPLOAD` :2799-2801, `bind` :2802).

**How it works:** per frame the CPU projects each `PointLight`'s sphere, finds its cluster range, tests it against each
cluster's camera-relative box and sets its bit. The whole mask table is rebuilt only when a sphere, the count or the
view changed (:250, :279-286, :312-319). Two buffer textures go up: `lightData` (RGBA32F: lights, then the light-map
table, the room fill table and the room map) and `clusterData` (RGBA32UI masks). The room map is only re-sent to a
set when its version changed; the lights every frame.

**Settings:** `pointLights` only (the clusters also run without lights when rooms exist, for the room map:
`frameLightClusters = points || rooms != null`, `RuneLightPlugin.java:2763`).

**Cost shape:** per frame, client thread: `cull` O(lights × clusters touched); `upload` ≈ **0.25 ms CPU** (HANDOVER
B1 "light upload 0.25"). Per scene load: the room map re-upload (`glBufferData` + `glBufferSubData` of
`(2048·2 + 4096 + 4095 + 1 + 5·size²)·16` bytes ≈ 2.9 MB at size 184) — this was half of B3's 34–39 ms swap-frame
stall (`RoomMap.texels`/`RoomAmbient.pack`/`RoomMap.pool` inside `LightClusters.upload`), moved to a worker in
7193e68a (`RoomMap.java:36-84`). Per pixel: see §5.

**Platform risk:** `upload()` drops the room map entirely when `ROOM_BASE + texelCount > GL_MAX_TEXTURE_BUFFER_SIZE`
(:452-456; "GL 3.3 only promises 65536 texels"); at size 184 the map alone needs 169,281 texels, so on a
minimum-spec driver rooms, room fill and the light map's plane lookup all silently turn off. Unverified which of the
three platforms' drivers report less than ~180k.

**Duplicates / overlaps:** the fog columns (:40-48, :391-436) are a second light list per screen tile for the same
lights (needed because the fog integrates once per pixel, not per step — `fog_frag.glsl:192-196`). `lightData` is a
carrier for four unrelated tables (lights, map table, room fill, room map): one buffer, four owners, one version
scheme each (`uploadedRooms/uploadedVersion/uploadedFill`, :59-63).

**Verdict:** keep as the one per-frame light list (no compute on GL 4.1 makes a CPU cluster build the right call);
restructure the room map and room fill out of `lightData` into their own 2D texture (removes the 65536-texel risk and
the mixed versioning).

---

## 5. Point-light shading in the composite and its other readers

**Purpose (code's words):** "returns the light on the front; light through thin foliage from behind goes to throughOut"
(`composite_frag.glsl:211`); "The normal a point light's N.L takes" (:195-203).

**Files / functions:** `composite_frag.glsl` — uniforms :91-93, includes :95-98 (`light_clusters`, `rooms`,
`skylight_ambient`, `light_map`), `clusterAt()` :182-184, `clusterLightCount()` :186-193 (debug), `pointLightNormal()`
:204-209 (`POINT_SMOOTH_FLAT 0.55`, `POINT_SMOOTH_FULL 0.85` :202-203), `pointLights()` :212-272 (light-map lights
:216-237, cluster lights :239-269, `POINT_LIGHT_SCALE 1.0` :180), call site :465-471 (`* mix(1.0, ao, AO_DIRECT)`,
`AO_DIRECT 1.0` :49 — point light fully multiplied by SSAO), debug view :482-486. The same per-light code is copied
into `fog_frag.glsl` (`pointGlow()` :196-230 analytic glow per column light; baked-light glow per step :358, :419-432,
`FOG_MAP_LIGHT_SCALE` :436), `weather_common.glsl` (`weatherPointLights()` :141-200: particles and wet ground; indoor
lights skipped by `colorIndoor.w > 0.5` :185; roof test `WEATHER_ROOF_BIAS` :131), and `water.glsl` (`waterLamps()`
:272-315: `LAMP_SURFACE`, glints). `materials.glsl:22` `transmission()`.

**How it works (per pixel):** `clusterAt(px, viewDepth)` → `LightMask` bit walk; for each cluster light two
`texelFetch` on `lightData` (position/radius, colour/storey code), radius reject, N·L reject, `lightOnStorey`, falloff.
Before that, if the light map is on: `lightMapPlane` (2 fetches), `lightMapSlots` (2 index fetches, 1 floor fetch,
`lightMapTaps` 1–4 wall fetches, then either one `textureLod` on the 3D texture or up to 8 `texelFetch`), then for up
to 4 slots `lightMapLight` (2 fetches each). Everything is summed into `light` after sun/ambient; emissive replaces
it (:480). The normal: ground (`material.b < 0.25`) uses the G-buffer's smooth `n`; everything else blends from the
face normal (`sm.faceNormal`, derivative-based, `frag.glsl:139-141`, packed by `material_target.glsl:25-35`) to the
smooth normal by how upright the face is (:204-209) — the stated reason is that smooth normals on walls "draw those
faces' diagonals into the glow" because vertices are shared with the top and ends (:195-201).

**Settings:** `pointLights`; `transmissionStrength` (`RuneLightConfig.java:257-266`) scales the through-leaf term
(indirectly, via `transmissive`); `emissiveStrength` (:242-252) replaces lamp light on emissive surfaces.

**Cost shape:** per pixel in the composite (1.38/1.45 ms total at the B1 canvas, HANDOVER; the lamp share is not
isolated — `GpuTimer.POINT_LIGHTS` exists (`GpuTimer.java:37`) but is **never begun** anywhere: unused). Per fog pixel
(quarter/half res): `pointGlow` once per column light plus, when the map is on, a full `lightMapSlots` **per march
step** (`fog_frag.glsl:419-432`). Per weather vertex (six per particle, :130-134 note) and per water pixel: the whole
light loop again.

**Duplicates / overlaps — "how much lamp light reaches a surface":**
1. cluster light: `colour × falloff × N·L` (`composite_frag.glsl:239-269`);
2. light-map light: the same `colour × falloff × N·L` times a baked visibility (:216-237; `light_map.glsl:207-225`);
3. the room fill from lamp luminance on floors (§11): `interiorTint × roomFillLevel × wall shaping` (:447);
4. the room ambient shaping `roomAmbientFill` 0.6–1 by wall distance (§10) multiplying the room daylight floor (:435-447);
5. the room daylight floor `max(indirect, roomDaylight × 0.3 × …)` (:440-446);
6. fog glow: analytic per column (`pointGlow`) **and** baked per step (`scatterMap`) — two integrals of the same lamps
   with a separate scale (`FOG_MAP_LIGHT_SCALE`);
7. weather and water re-implement the loop with their own scales (`WEATHER_POINT_LIGHT_SCALE`, `LAMP_SURFACE`).
Items 1 and 2 are the same formula differing only in visibility; 3–5 are three interior-ambient mechanisms that all
depend on the room id and overlap in what they lift. The code itself says 2 shares 1's formula "worked out as the
clusters do theirs" (`light_map.glsl:11`, :204-206).

**Normals:** two normal rules for one surface — the sun uses `n` (`:391`, `:415`), lamps use `pointLightNormal(n,
faceNormal)` (:468). The user's wish (A3) is lamps shading with `VertexShading.computeNormals`' output as the sun does;
the per-light blend is a shader-side patch for an upload-side decision (§13).

**Verdict:** keep the loop as the one analytic lamp evaluation; **merge** 1+2 into one path whose visibility comes
from one occlusion structure (render-plan §3.6a) so the map branch and `lightOnStorey` go; remove `pointLightNormal`
once the upload-side flat/smooth rule (§13) is right for walls; collapse the fog's two glow paths into one.

---

## 6. The light map (`LightMap`, `LightMapBake`, `LightMapTexture`, `LightBlockers`, `light_map.glsl`)

**Purpose (code's words):** "The static point lights' visibility, baked into a light map: per storey, a grid of
quarter-tile cells that holds, for each baked light that reaches the cell, how much of the light is not stopped on
its way there ... Nothing else about a light is in the map" (`LightMap.java:17-22`); "How much of a static light a
cell of the light map sees, per height layer in it ... GL-free; runs on the baking threads." (`LightMapBake.java:5-11`).

**Files / functions:**
- `LightMap.java` (1563 lines): class doc :17-48; `BLOCK 32` :55, `SLOTS 4` :59, `TABLE_IDS 2048` :63,
  `EVICT_MARGIN 2560` :71, `MODEL_WAIT_NANOS 1 s` :76; `Source` :88-144; `Block`/`Sink` :147-180; threads and
  lock-guarded state :200-235; `defaultThreads()` :285-288 (1–3), `burstThreads()` :291-294 (up to 6);
  `setBlockers()` :419-454 (waits up to 1 s for models); `applyBlockers()` :456-549 (diffs walls/models per cell,
  rebakes the lights that touch changed cells); `add()` :632-656; `remove()` :664-698; `setLevel()` :753-768 (the
  per-frame table write); `rebake()` :850-881 (priority = distance² from focus); `bake()` :884-951 (worker);
  `hold()` :953 (slot assignment, "the weakest light is the one dropped"); `endBatch()` :1181-1218 (the per-load log);
  `put()`/`build()` :1331-1446 (block assembly on workers, including the floor luminance for §11 :1434-1440);
  `update()` :1465-1544 (render thread: send nearest blocks within a budget; mark lights live).
- `LightMapBake.java` (2092 lines): doc :5-45; `CELL 32` :47, `LIGHT_WIDTH_TILES 0.8` :50, `SAMPLES 16` :51,
  `WRAP 0.35` :53, `BLUR_SIGMA_CELLS 2` :55, `SUBS 2` :57, `FIXTURE 10` :59, `SEE_THROUGH_OPACITY 0.15` :66,
  `MAX_RADIUS 3200` :72, `BINS 2048` :74, `HEIGHT_LAYERS 4` :85; `bake()` :429-543 (soft LOS sweeps, flood for wrap,
  blur, dilate, crop; then `otherStorey()` :1903 for each other plane).
- `LightMapTexture.java` (170 lines): doc :9-14 ("736 x 736 for the largest scene, 52 MB all told"); `SLICES` :22
  = (4 layers + 2 index) × 4 storeys = 24; `ensure()` :37-69 (allocates and **zeroes slice by slice** with 24
  `glTexSubImage3D` :62-66); `block()`/`send()` :72-99 (two `glTexSubImage3D` per block).
- `LightBlockers.java` (161 lines): doc :6-11; the immutable snapshot (walls, models, floors, filled) with wall prefix
  sums :27-51; `blocked()` :91-118; `solid()` :126-130; `hasFloor()` :153-160.
- `light_map.glsl` (225 lines): doc :1-14; `LIGHT_MAP_NORMAL_BIAS 20` :20; `lightMapOn()` :26-28 (header == 3);
  `lightOnStorey()` :33-36; `lightMapPlane()` :39-53; `lightMapTaps()` :95-159 (wall-aware bilinear; diagonal walls
  cell by cell); `lightMapSlots()` :163-202; `lightMapLight()` :207-225.
- Plugin wiring: `RuneLightPlugin.java:375-397` (`LIGHT_MAP_BLOCKS_PER_FRAME 2`, `SEND_BUDGET 0.4 ms`, `MAX 96`, the
  sink), `updateLightMap()` :2836-2890 (builds `LightBlockers` when any input object changed :2862-2868; `ensure`;
  `update` under `GpuTimer.LIGHT_MAP`/`CpuTimer.LIGHT_MAP`), `collectLights()` :2972 (map first, then lights).

**How it works:** static `Placed` lights within reach are `add`ed as `Source`s; a worker bakes each light alone into
per-storey grids of visibility (0–1 per cell per height layer) against `LightBlockers`; blocks of 32×32 cells are
assembled from all lights' grids (4 slots per cell; overflow drops the weakest) on workers; the render thread sends
≤ 2 blocks per frame (more within 0.4 ms, ≤ 96) into one RGBA8 3D texture; a light becomes `live` when all its
blocks are sent, and from then the clusters stop carrying it and only its table entry (position, radius, colour ×
strength now) is written per frame. Doors (`WallMap` change), spawned models (`ModelBlockers` change) or a plane
rebuild trigger re-bakes for the lights whose cells changed.

**Settings:** none of its own — on with `pointLights` whenever walls, rooms and floors exist (`RuneLightPlugin.java:2842-2856`).
(History: a "Light map" Development toggle existed at a2118af1; it is gone.)

**Cost shape:**
- Per scene load: bakes on 1–3 steady / up to 6 burst workers; per-load log line (`endBatch`). The texture upload
  burst was 30+ ms per region load before 7193e68a (`RuneLightPlugin.java:376-377`); HANDOVER B3 attributes
  `LightMap.update → LightMapTexture.send → glTexSubImage3D` to the swap-frame stall; now sliced. `ensure()` still
  zeroes the whole 52 MB texture on the client thread in 24 calls whenever the scene size changes (:62-66) — not
  budgeted (unverified whether it hitches in practice; it runs only when `cells` changes).
- Per door/spawn: `applyBlockers` diff on the client thread (inside the lock), then worker bakes.
- Per frame, client thread: `setBlockers`/`setNight`/`setFocus`, `update` (≤ 2–96 block sends), `setLevel` per live
  light (from §3), `tableStaging` copy of `tableUsed × 32` bytes (§4 :494-501).
- Per pixel: §5's map branch (≈ 7–15 fetches + 1 filtered 3D read) in the composite, water and weather; per fog step
  in the fog.
- Memory: 52 MB GPU for the texture (comment, `LightMapTexture.java:12`); CPU `holder` arrays `4 storeys × 4 slots ×
  cells²` shorts (≈ 17 MB at 736²) (`LightMap.java:233`, :478).

**Duplicates / overlaps — "what blocks a lamp":**
1. `WallMap` LOS edges and diagonals (§7) — walls at every height;
2. `WallMap.solid` tiles (`LightBlockers.solid` :126-130) — a whole tile, only when models are unknown or on another storey;
3. `ModelBlockers` boxes by height band (§8) — models at the light's height;
4. floors / `filled` bits (`LightBlockers.hasFloor`) — cross-storey openings (`LightMapBake.otherStorey`);
5. storey gating for unbaked lights: `storeyCode`/`lightOnStorey` (§4/§5) — a plane, not an occluder;
6. CPU plane gating `visibleFromOtherPlanes` (§3);
7. fog: indoor lights excluded from columns unless the room's roof is hidden (`OpenRooms`, §9), and `FogGround`'s own
   room factor (§12);
8. weather: `colorIndoor.w > 0.5` and the roof-height test (`weather_common.glsl:185`).
Only 1–4 are geometric occlusion and they apply to **static lights only**; moving lights (NPCs, projectiles, spells,
the test light) are never occluded by anything but 5–8. Render-plan §3.6a names the one replacement: a voxel grid
with face bits, built per zone on a worker, read by a short march per light in the shader, occluding dynamic lights
too, and also serving the skylight (`docs/render-plan.md:177-204`). HANDOVER A3 says the same ("later the voxel-grid
occlusion ... replaces blockers/rooms/light map as one mechanism").

**Verdict:** remove when the §3.6a grid lands (it is the heaviest and most intricate mechanism in the area: ~3,800
lines, 52 MB, a second shading path and a second storey rule); until then keep, do not extend; measure its per-pixel
share with a real GPU timer first (none exists today).

---

## 7. Wall map (`WallMap`)

**Purpose (code's words):** "The walls of a scene per plane: for every tile, which of its four edges hold a wall and
whether a diagonal wall crosses it. Walls are the edges the collision map says block line of sight ... Edges that
stop movement but not sight (bars, fences, railings, low walls, gates) are kept apart (seeThrough)" (`WallMap.java:8-14`).

**Files / functions:** constants :21-35; `build()` :117-176 (from `CollisionData.getFlags()` + diagonal wall objects);
`solid()` :194; `edge()` :205; `seeThrough()` :224; `sameWalls()` :285; `diff()` :295, :322 (cell changes for §6);
`diagonalOf()` :355 (which diagonal, from the model's vertices); `blocked()` :396-485 (DDA walk, used by
`LightBlockers.blocked`); `half()`/`halfAt()` :487-514. Built by `BuildingLights.rebuild()` :116-117 and **every game
tick** by `BuildingLights.onGameTick()` :289-290, compared with `sameWalls`, and then (if changed) the room map is
rebuilt (:297-305).

**How it works:** one byte per tile per plane (N/E/S/W + two diagonals), a `full` tile flag and `see` bits. Consumers:
§6 (bake blockers, wall-aware filter via the room texels), §9 (`BuildingDetector.split`), §10 (`RoomAmbient.pack`),
§11 (`RoomLight.skyShares`), the skylight (`skyField`, `skyGrid`: `RuneLightPlugin.java:2257`, :3300).

**Settings:** none.

**Cost shape:** per game tick on the client thread: a full pass over `4 × 184²` collision flags plus diagonals
(`build`), then `sameWalls` compare. HANDOVER B3: watchdog stacks "in our `WallMap.diff`" on the 65 ms frame. Per
scene load: `rebuild` (logged in ms, `BuildingLights.java:132-134`).

**Duplicates:** the dead comment at :14 says "the point-light blocking reads the same edges in
`point_light_blocking.glsl`" — that file does not exist (removed in 20963fa8); wall bits are instead carried into
`lightData`'s room texels (`RoomMap.texelsInto` :327-342) and read by `rooms.glsl`/`light_map.glsl`. So walls live in
three places: `WallMap` (CPU), the room texels (GPU, per tile, with edge symmetrisation :330-336), and `FogGround`'s
texels (§12).

**Verdict:** keep as the source of wall faces (the §3.6a grid's "blocked face between two voxels" is exactly this
data); restructure the per-tick rebuild into a door-event-driven diff on a worker; fix the comment.

---

## 8. Model blockers (`ModelBlockers`, via `VoxelBuilder`)

**Purpose (code's words):** "The static models that stand in the way of a light, per cell of the light map ... and per
storey, from the same zone triangles the skylight voxelizes (VoxelCapture)" (`ModelBlockers.java:7-9`); "Triangles that
lie flat ... are left out: they stop no light going along the ground, and a ceiling would shut every lamp in." (:25-26).

**Files / functions:** constants :33-50 (16 height bins × 15 units = 240 units; 4 bands; 8 columns); `set()` :87;
`runs()` :195-201 (the bake's query); `rasterize()` :296-432 (per zone from `VoxelCapture`, on the voxel workers);
`assemble()` :505; `markCells()`/`diff()` :573-618. `VoxelBuilder.java:38`, :50-62 (`modelsAwaited`), :221-247 (built
in `parallel()` before the voxels, "the light map waits for them"). `Zone.java:89-90` (cached per zone).
`VoxelCapture.java:23-48` (per-triangle owner id so a lamp ignores its own object).

**How it works:** each zone's captured triangles are clipped into quarter-tile cells and height bins; per cell and
object a mask of bins and a per-band box with coverage columns. The bake reads the runs at the light's height.

**Settings:** none (built when `frameSkylightWanted || frameLightMapWanted`, `RuneLightPlugin.java:3257`).

**Cost shape:** per scene load / zone change on the voxel worker pool; HANDOVER B3 lists "voxel build" among the four
jobs on the swap frame (`hitchMonitor.job(VOXEL_BUILD)` :3260) — unverified how much of that is `rasterize` vs the
voxelizer. Per door: nothing (walls are §7's). Per frame: a reference compare in `updateLightMap` (:2862).

**Duplicates:** the same triangles are rasterised twice — into `SceneVoxels` for the skylight and into
`ModelBlockers` for lamps (`VoxelBuilder.java:224-247`). Render-plan §3.6a: "Skylight and lamp occlusion are one
quantity under rule 8" (`docs/render-plan.md:178`).

**Verdict:** merge into the one voxel grid (§3.6a) with the skylight's voxelisation; remove with §6.

---

## 9. Rooms: detection, map, walls bookkeeping, open roofs, and `rooms.glsl` (`BuildingDetector`, `RoomMap`, `BuildingLights`, `OpenRooms`, `BuildingOverlay`)

**Purpose (code's words):** "Finds building interiors in a scene from the game's own tile data" (`BuildingDetector.java:7`);
"Which building room every tile of the scene belongs to, for the volumetric fog (it stays out of interiors), the room
fill (RoomLight), puddles and the weather" (`RoomMap.java:6-8`); "The scene's buildings: their rooms ... and the walls
... which follow doors as they open and close; and the soft fill the composite spreads over each room that lacks
light" (`BuildingLights.java:24-27`); "The building rooms whose roof the client has hidden this frame ... so their lamps
shine up over the walls into the fog" (`OpenRooms.java:6-8`).

**Files / functions:** `BuildingDetector.detect()` :62 (under-roof flags + roof ids → rooms; `MIN_TILES 3`,
`MAX_TILES 2000` :26-28), `split()` :119 (along `WallMap`). `RoomMap.build()` :113-184 (ids ≤ 4094, floor heights,
roofed), `setWalls()` :186, `setFloors()` :200, `roomOfLight()` :234-243, `roomAt()` :253-283 (CPU twin of the
shader's), `texelCount()` :285-288 (1 + 5 per tile), `texels()`/`texelsInto()` :298-350 (rooms, floors, walls + diagonal
halves + floor bit 19, `RoomAmbient.pack`, puddle pools), `pools()` :370 (puddle placement — not a light quantity but
packed here), `packAsync()` :61-84 (B3 worker). `BuildingLights.rebuild()` :91-135, `floorTiles()` :141-166,
`onGameTick()` :275-311. `OpenRooms.update()` :37-64. `rooms.glsl` — header doc :1-10, `roomHeader` :12,
`ROOM_TEXELS 5` :14, `ROOM_PLANE_MARGIN 2` :17, `ROOM_STOREY 420` :19, `ROOM_NORMAL_BIAS 40` :22, `roomPlaneAt()`
:34-71, `tileWallDistance()` :75-83, `roomFillLevel()` :87-89, `roomAmbientFill()` :93-106, `wallDistanceAt()`
:109-128, `puddlePoolAt()` :132-161. `BuildingOverlay.java:19-26`, :46-90 (dev tint of rooms within 32 tiles).
Plugin: `prepareLighting` :2756-2790 (rooms into clusters, fog, open rooms), `getBuildingLights()` :3145.

**How it works:** rooms are connected under-roof tiles per plane, split along walls; each tile gets a 12-bit room id.
Five RGBA32F texels per tile ride in `lightData` after the lights (§4). Shaders find a point's storey by comparing its
y with the four planes' floor heights (`roomPlaneAt`, `lightMapPlane`, `wallDistanceAt`, `puddlePoolAt` — four copies
of the same loop: `rooms.glsl:51-56`, :120-126, :144-149, `light_map.glsl:45-51`).

**Settings:** `interiorBrightness` (keyName `buildingLightBrightness`, `RuneLightConfig.java:214-224`) gates
`updateRoomLight(rooms)` (`RuneLightPlugin.java:2781`); `showBuildings` (:968-978) for the overlay.

**Cost shape:** per scene load, client thread: `detect` + `WallMap.build` + `split` + `RoomMap.build` (logged in ms,
`BuildingLights.java:132`) and the texel pack — the latter was the other half of B3's swap-frame stall
(`RoomMap.texels`/`RoomAmbient.pack`/`RoomMap.pool`), now on the `RuneLight room texels` thread (`RoomMap.java:39-50`).
Per door: `RoomMap.build` again (:297-305) → version bump → re-pack and re-upload of the whole map (2.9 MB). Per frame:
`OpenRooms.update` (cheap, cached). Per pixel: `roomPlaneAt` is run **three times** per lit pixel — in
`skylight_ambient.glsl:22`, in `roomAmbientFill` (`composite_frag.glsl:435`) and as `lightMapPlane` in `pointLights`
(:221), each with its own normal bias (40 for rooms, 20 for the map).

**Duplicates / overlaps:** the room data is uploaded twice — into `lightData` (§4) and into the fog's ground texture
(`FogGround.texels(RoomMap, OpenRooms, …)`, `FogGround.java:124-134`; `VolumetricFog.java:67-75`, :254-261) — and the
room/storey decision is implemented three times (CPU `RoomMap.roomAt`, GLSL `roomPlaneAt`, GLSL `lightMapPlane`).
`BuildingLights.FILL_TINT` (:37) is the old constant fill's colour, still sent as `interiorTint`
(`RuneLightPlugin.java:2942`) for §11. The config description for `showBuildings` promises light markers and floor-hole
outlines; the overlay code has neither (:46-90) — stale text (unverified: nothing in the file references lights).

**Verdict:** keep rooms as one quantity ("which room/storey is this point in") with **one** texture of its own, one
GLSL lookup used by skylight, fill and lamps, one CPU twin; merge `FogGround`'s room channels into it; `OpenRooms`
becomes a bit of that texture. `BuildingOverlay`: keep as a dev aid, fix the description.

---

## 10. Wall-distance ambient shaping (`RoomAmbient`, `roomAmbientFill`)

**Purpose (code's words):** "Baked wall distance at tile corners, outdoors too (rain puddles gather by walls). Six
bits per corner fit exactly in a float texture value." (`RoomAmbient.java:3-6`); "The shaping, 0.6 to 1, of how far the
point is from the walls" (`rooms.glsl:91-92`).

**Files / functions:** `RoomAmbient.pack()` :11-34 (per tile corner, min distance to any wall segment in the 2×2
tiles around it); packed in `RoomMap.texelsInto` :343-347; read by `tileWallDistance()` (`rooms.glsl:75-83`),
`roomAmbientFill()` :93-106 (`mix(0.6, 1.0, smoothstep(distance))`), `wallDistanceAt()` :109-128 (puddles);
consumed at `composite_frag.glsl:435-447`: multiplies the room daylight floor and shapes the §11 fill with
`INTERIOR_WALL 0.35` (:159, :447).

**How it works:** a per-tile-corner distance field to walls, interpolated per pixel, giving rooms a darker rim at the
walls. It is a hand-made "ambient occlusion by walls" for interiors.

**Settings:** none (on with rooms and `interiorBrightness > 0`).

**Cost shape:** per scene load and per door: `pack` for every tile × 4 planes × 4 corners × up to 4 tiles × 6 wall
tests on the client thread (now the packer worker) — B3 named `RoomAmbient.pack` explicitly. Per pixel: 2 fetches in
`roomAmbientFill`.

**Duplicates:** it overlaps the SSAO (`ao`) and the skylight (`skylightAmbient`, `skyUpsampled`) which already darken
toward walls and under overhangs; it is also the puddle placement's input (not a light quantity). Three interior
ambient terms stack at `composite_frag.glsl:435-447` (room daylight × shaping, room fill × shaping, skylight × plain
ambient).

**Verdict:** remove as a lighting term (let SSAO + skylight/§3.6a grid darken wall feet); keep the distance field only
if the puddles need it, in the rooms texture, not in the light buffer.

---

## 11. Adaptive room fill (`RoomLight`)

**Purpose (code's words):** "How much light each building room gets, and the soft fill that makes up what it lacks, so
that no real room is left black, nor lit more than it needs." (`RoomLight.java:10-11`).

**Files / functions:** constants :43-69 (`TARGET_NIGHT 0.40`, `DAY_SHARE 0.42`, `KNEE 0.25`, `FLOOR_NDL 0.5`,
`CELL_CAP 1.5`, `SKY_SHARE 0.22`, `SUN_LUMINANCE 2.64`, `EASE_SECONDS 0.4`); `floor()` :189-240 (per block, per cell
score from the light map's floor luminance, summed per room); `score()` :254; `target()` :288-297 (uses
`BuildingLights.nightFactor` :290); `fill()` :314; `skyShares()` :328-376 (sky reach per room via `SkyField` falloff);
`followWalls()` :389-429 (one low-priority worker `RuneLight room sky`); `update()` :456-500 (eased fills → `table`,
version); `table()` :545. Fed by `LightMap.build()`'s `floor[]` (`LightMap.java:1434-1440`) through
`lightMapSink.floor` (`RuneLightPlugin.java:393-396`); uploaded by `LightClusters.upload` :482-489; read by
`roomFillLevel()` (`rooms.glsl:87-89`) at `composite_frag.glsl:447` tinted by `interiorTint` and shaped by §10.

**How it works:** each room's score = mean lamp luminance on its floor (from the baked map, with the night's strengths
quantised to `NIGHT_STEPS 16`) + its sky share × daylight; the fill is the shortfall to a target (night 0.40, day
42 % of outdoor light) with a soft knee, eased over 0.4 s, scaled by `interiorBrightness`. A summary log after 8 s.

**Settings:** `interiorBrightness` (0 turns it off).

**Cost shape:** per block send: `floor()` O(1024 cells) on the client thread (inside `LightMap.update`). Per wall
change: `skyShares` on the worker. Per frame: `update` O(rooms) + a table upload when changed. Per pixel: 1 fetch.

**Duplicates:** this is the one *adaptive* interior fill, but it stacks on two others at
`composite_frag.glsl:440-447` (the room daylight floor and the §10 shaping) and it depends on §6's floor luminance,
so removing the light map breaks its score. Its night curve differs from §3's.

**Verdict:** keep as the single interior fill; restructure its lamp score to come from the CPU light list (positions,
radii, strengths are all known in `SceneLights`; a per-room analytic sum is O(lights × rooms) once per change) so it
survives §6's removal; drop the room daylight floor and §10 shaping so one fill remains; one night curve.

---

## 12. The fog's own room factor (`FogGround`, `fogRoomFactor`) — overlap note

**Purpose (code's words):** "inside building rooms (RoomMap.java, in fogGround) the fog's density is multiplied by
this; 1 leaves interiors" (`fog_frag.glsl:39-40`); "The building rooms come with it (texels), so the fog's march has
them in the same fetch as the ground." (`FogGround.java:18`).

**Files / functions:** `FogGround.texels(RoomMap, OpenRooms, float[])` :134; `VolumetricFog.java:67-75`, :254-261;
`fog_frag.glsl` `fogRoomFactor()` :150, used per step :349-382.

**Cost shape:** per scene load / door / roof change: a second pack and upload of room membership and storey tops.

**Verdict:** merge into §9's one rooms texture (the fog then reads the same lookup); this is outside the lights area
proper and is listed only because it is a third carrier of the room data.

---

## 13. Smooth normals (`VertexShading`, the uploaders, `frag.glsl`) and where the lights read them

**Purpose (code's words):** "Per-vertex shading data for the uploaders: smooth normals and the base (unshaded) face
colors. Normals are octahedral-encoded into 16 bits ... 0 means 'no vertex normal': the face is flat shaded"
(`VertexShading.java:3-8`); "Smooth vertex normals computed from the model's own geometry: each vertex gets the
area-weighted average of the normals of the visible faces around it." (:139-143).

**Files / functions:** `VertexShading.java` — `encodeNormal()` :23-40, `vertexNormal()` :61-71 (rotated by orientation),
`SMOOTH_COS = cos 50°` :75, `flatFaceNormals()` :84-126 (a game-flat face gets the smooth normals only if **all three**
corner normals are within 50° of the face normal, else the face normal), `computeNormals()` :145-172 (unnormalised
area-weighted cross products; faces with `colors3 == -2` skipped), `baseColor()` :201-224 (undo vanilla's baked
light, 117 HD's constants). Callers: `ModelUploader.java:122-128` (`computeNormals` for every uploaded model, the
game's normals never used: `hasNormals = true`), smooth/flat decision :254-284 (`flat = color3 == -1`; non-flat faces
always smooth; flat faces through `flatFaceNormals`); the second upload path :610-700 is the same; `SceneUploader.java:1197-1205`
for scene models, and terrain normals from height slopes (`encodeNormal(slope, -1, slope)` :745, :940, :1145, :1155).
GPU: `vert.glsl:70`, :94 (`fNormal`, `fNormalValid`); `frag.glsl:137-149` — face normal from `dFdx/dFdy`, flipped
toward the camera; smooth normal used when valid and aligned with the face normal; written to `FragNormal`
(:147) and the face normal packed into `FragMaterial` (:149, `material_target.glsl:25-35`). Read back in the composite
as `n = decodeNormal(sm.normal)` (`composite_frag.glsl:391`) for the sun and `sm.faceNormal` for `pointLightNormal`
(:468). `removeBakedShading` → `VertexShading.undoVanillaShading` (`RuneLightPlugin.java:732`, :1065-1070).

**How it works:** normals are computed on the CPU per model upload (static scene at load, dynamic models per frame
in `TempModels`/`ModelUploader`), encoded into a spare vertex short, decoded in the vertex shader, oriented per
fragment, and stored in the G-buffer. The lights read them in the composite, after the fact. The 50° rule decides
per flat face at upload whether it is round; the lamp pass then re-decides per pixel with its own 0.55–0.85 uprightness
blend (§5).

**Settings:** `removeBakedShading` (`RuneLightConfig.java:359-362`) — colours, not normals.

**Cost shape:** per upload (client thread, and the `TempModels` workers — unverified which uploads run where):
O(faces + vertices) per model. Per pixel: free (G-buffer read). HANDOVER A7: "the smooth-normal path is not reaching
trees (or the 50° rule excludes them)" — from the code, a tree's canopy faces that the game marks flat (`color3 == -1`)
fail `flatFaceNormals` whenever any one corner's smoothed normal folds more than 50° (:114-119), which leaf cards do.

**Duplicates:** two normal rules for one surface (upload-side 50° rule vs shader-side `pointLightNormal`); the face
normal is computed twice (CPU in `flatFaceNormals` for the encode, GPU by derivatives for the G-buffer).

**Verdict:** keep `computeNormals` as the one normal source; restructure so one upload-side rule (the 50° test,
retuned per A7) produces the normal both sun and lamps use, and `pointLightNormal` goes — this is the user's A3 wish.

---

## 14. Smaller pieces in the area

- **Emissive materials** (`emissiveStrength`, `RuneLightConfig.java:242-252`; `emissiveLight()` at
  `composite_frag.glsl:480`): a material flag, not a light; replaces lamp light on glowing surfaces. Keep.
- **Test light** (`testLight`, `RuneLightPlugin.java:2983-2994`): one warm `PointLight` at the player; the only dynamic
  light a developer can place; it is never occluded (§6). Keep as a dev setting.
- **Scene dump** (`dumpScene`, `RuneLightPlugin.java:3012-3070`; `LightBlockers.write` :54-67; `SceneLights.write`
  :1164): offline fixture for the light map tests (`LightMapLoadTest`, `LightMapShadingTest`). Dies with §6.
- **Debug view** `DEBUG_LIGHT_CLUSTERS` (`composite_frag.glsl:482-486`): lights per pixel heat map. Keep.
- **Funnel log** (`SceneLights.Funnel` :97-107, `logFunnel` :1146): where each definition's light went, 6 s after a
  load. Keep (it is the only accounting of lights that reach the screen).
- **Tests**: 22 light-related test files in `src/test/java/com/midonarhel/runelight/` (`LightClustersTest`,
  `LightMapTest`, `LightMapBakeTest`, `LightMapTimingTest`, `RoomMapTest`, `WallMapTest`, `ModelBlockersTest`,
  `SceneLightsBudgetTest`, …). Not run here.
- **`Lightning.java`** matches the file glob but is weather (the strike's `strikeCaster`), not a point light; out of area.

**Dead or stale paths found:**
- `GpuTimer.POINT_LIGHTS` (`GpuTimer.java:37`) is declared and never used — no GPU number exists for the lamp pass.
- `WallMap.java:14` refers to `point_light_blocking.glsl`, which does not exist (removed in 20963fa8).
- `BuildingLights.FADE_START_ALTITUDE/FADE_END_ALTITUDE/nightFactor` (:38-40, :171-176) survive only for
  `RoomLight.target`; `FILL_TINT` (:37) is the pre-RoomLight constant fill's colour kept as `interiorTint`.
- `LightClusters.update()` (:236-240) is a convenience the plugin does not call (it calls `cull` and `upload` apart);
  presumably tests (unverified).
- `RoomMap.roofed` (:29) is filled (:179-181) — no reader found in the files read (unverified beyond grep of this area).
- `showBuildings` description (`RuneLightConfig.java:970-973`) describes light markers and floor-hole outlines the
  overlay does not draw.
- `PointLight.plane = -1` "unknown" is set by every emitter (`onPlane(lightPlane)` :1408), so the "every storey"
  branch of `storeyCode` (:541) is reached only by the test light.

---

## Data flow: from a definition to a lit pixel

```
lights.json (2,892 defs) + autolights.json (6 presets)      [tools/import_117hd_lights.py]
  └─ SceneLights.load (Gson, start)                          [client]
      ├─ rebuild / spawn events → Placed (objects, world) ── LightMap.Source if bakeable
      ├─ per frame attachNew/follow → Live (NPC, projectile, graphics)
      └─ collect(): LightLife → flicker/pulse/night/outdoor → either
            (a) PointLight list  ─────────────────────────────────────────────┐
            (b) mapped: LightMap.setLevel (table only) ─────────────────┐      │
BuildingLights (client, load + every tick)                              │      │
  ├─ BuildingDetector.detect/split  → RoomMap (ids, floors, pools, wall distances via RoomAmbient)
  ├─ WallMap.build (collision flags + diagonals)                         │      │
  └─ floorTiles                                                          │      │
VoxelBuilder (workers): VoxelCapture → ModelBlockers.rasterize/assemble  │      │
LightBlockers(walls, models, floors, filled)  ──► LightMap.setBlockers   │      │
  LightMap workers: LightMapBake.bake per light → grids → put/build blocks (vis, index, floor luminance)
  LightMap.update (client, ≤2 blocks/0.4 ms): LightMapTexture.block (3D RGBA8, 52 MB) + RoomLight.floor
RoomLight.update (client): per-room fill → table                         │      │
LightClusters (client): cull(PointLights) → masks; upload():             │      │
   lightData = [lights(a)] [map table(b)] [room fill] [RoomMap.texels (packed on worker)]; clusterData = masks
   roomHeader uniform (0 / 2 rooms / 3 rooms+map)
GPU, per pixel (composite_frag.glsl; copies in fog_frag, weather_common, water):
   n = G-buffer smooth normal (VertexShading.computeNormals → vert/frag.glsl); faceNormal from material target
   roomPlaneAt (skylight_ambient) · roomAmbientFill (+ roomFillLevel × interiorTint) · lightMapPlane
   pointLights(): light-map slots (lightMapSlots/lightMapTaps → lightMapLight × vis)
                + cluster bits (nextLight → lightData × 2 → lightOnStorey → falloff × N·L with pointLightNormal)
   × mix(1, ao, AO_DIRECT) + through-leaf → light → emissiveLight → fog
```

## The five heaviest or messiest things, ranked

1. **The light map** (§6): ~3,800 lines across five files and a 225-line shader, a 52 MB 3D texture zeroed on the
   client thread, 1–6 worker threads, a second lamp-shading branch per pixel (and per fog step), a second storey rule,
   and the only occlusion in the renderer — static lights only. The direct cause of half of B3's swap-frame hitch.
2. **The room data's three carriers and three per-pixel lookups** (§9, §12): `lightData` texels (with the 65,536-texel
   GL 3.3 floor that can silently drop it), `FogGround` texels, and the CPU twin; `roomPlaneAt` run three times per
   lit pixel with two different normal biases; `RoomAmbient.pack` and `RoomMap.pool` in the swap-frame stall (B3).
3. **Three stacked interior ambient terms** (§10, §11, `composite_frag.glsl:435-447`): room daylight floor × wall
   shaping, adaptive fill × wall shaping × `interiorTint`, skylight ambient — plus two night curves.
4. **`WallMap.build` every game tick on the client thread** (§7) with a whole-map `RoomMap` rebuild, re-pack and
   2.9 MB re-upload on every door, and a `WallMap.diff` seen in B3's watchdog stacks.
5. **Two normal rules and four copies of the lamp loop** (§5, §13): `pointLightNormal`'s per-pixel blend on top of the
   upload-side 50° rule, and the per-light code repeated in composite, fog (twice: analytic + baked), weather and water
   with four independent scale constants.

## Settings in this area

`RuneLightConfig.java` has **67** `keyName` entries by count (HANDOVER says 69 — unverified which is right; the count
here is `grep -c keyName`). Of them, this area reads **8**: `pointLights`, `buildingLightBrightness`
(`interiorBrightness()`), `emissiveStrength`, `transmissionStrength`, `removeBakedShading`, and the development
toggles `testLight`, `showBuildings`, `dumpScene`. The light map, clusters, rooms, wall map, model blockers,
room ambient and the fog/weather/water lamp paths have **no** settings of their own; the quantity count that should
come down is in mechanisms (§5–§12), not in settings.

## Unverified in this inventory

- Any runtime number not in HANDOVER (the lamp pass's GPU share, `CpuTimer.LIGHTS`/`CLUSTERS`, bake times per load,
  `ensure()`'s zeroing cost, `rasterize`'s share of the voxel build).
- Whether `GL_MAX_TEXTURE_BUFFER_SIZE` on any of the three target platforms is below the ~180k texels the room map
  needs (`LightClusters.java:452-456`).
- Which uploads calling `VertexShading.computeNormals` run on the `TempModels` workers vs the client thread.
- `RoomMap.roofed` readers outside the files read; `LightClusters.update()` callers (tests assumed).
- The HANDOVER's 69 vs the counted 67 settings.
