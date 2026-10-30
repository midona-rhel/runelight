# Inventory synthesis — the renderer, its quantities, its packaging, what goes, in what order

Written 2026-10-30 from the four inventories in this directory (`frame.md`, `lights.md`, `sky-weather-water.md`,
`scene-shadows.md`), HANDOVER.md and render-plan.md §2 / §3.6a. Nothing here was run or built; every number is
HANDOVER's B1/B2/B3 unless marked *est.* (an inventory's estimate) or *unmeasured*. Citations are `inventory §` and
`file:line` as the inventories give them; `≈` marks RuneLightPlugin.java lines that moved while the inventories were
written. The user's rules apply throughout: one mechanism per quantity (render-plan §2 rule 8); the look of the
shading, sky palette, fog stack, clouds and aurora gating is kept; the water waves go; three platforms, GL 3.3–4.1
floor; settings go down; the user's statement about a thing is tested before any diagnosis.

Paths: Java under `src/main/java/com/midonarhel/runelight/`, GLSL under `src/main/resources/com/midonarhel/runelight/`.

---

## 1. The renderer in one page

B1 was taken uncapped on a 783×603 canvas (≈ 3.4 Mpx device), MSAA_2 by default, **render scale possibly 50 %**
(HANDOVER's caveat — re-measure at explicit FULL first). Frame median **6.96 ms**; the per-pixel chain (fog +
composite + AO + filter + clouds) ≈ **4.8 ms**; at render scale 25 % the frame is 3.95 ms (fog 0.32, composite 0.40,
AO 0.14, filter 0.03) so the chain is pixel-bound and the rest (~3.7 ms) is CPU floor + scene + shadow map.
Projected to 4K the chain alone is ≈ 19 ms (or ~4× worse if B1 was at 50 %).

### The frame, pass by pass (GPU ms, yaw 1024 / yaw 0)

| # | Pass | Where | Cost |
|---|---|---|---|
| 1 | `preSceneDraw`: map every render thread's 4 MiB `VAO`s, camera UBO, FBO rebuild on change, clear five targets via `drawSkybox` | frame §0, §1; RuneLightPlugin.java:1813–2060 | fixed; `BUFFER_MAP` CPU stage (unmeasured; `glMapBufferRange` can stall) |
| 2 | Scene: zone opaque multi-draws, static alpha CPU face-sort + `glDrawElements`, temp/dynamic models | scene §2–§4 | **0.46 / 0.91** GPU; CPU `client scene` 0.7–0.9 (client traversal + our callbacks) |
| 3 | `postSceneDraw`: alpha-layer MSAA resolve blit; shadow map = static-copy pass + sway zones + dynamic casters (+ static build frames, + `buildMax` ×3 when a build finishes) | frame §5; scene §9 | **0.64 / 1.80** (view-dependent) |
| 4 | Weather occlusion map redraw (all zones, all levels, 1024² depth) — per event, same frame as the swap | scene §10; Weather.java:847 | *unmeasured* (`OCCLUSION` timer exists, not in B1) |
| 5 | Client draws its interface into the canvas | frame §0 | CPU `client UI` 1.4 (not ours) |
| 6 | Water surface pass (geometry → RGBA16UI target; includes the 20 Hz wave bake over 1,152² maps) | sky G4–G5 | **0.07 / 0.11** at this view; *unmeasured* at a sea view |
| 7 | Voxel / sky-grid upkeep: `glTexSubImage3D` of changed boxes every 6 frames (up to 15 MB after a move) | sky D2 | `SKY_GRID` timer, *unmeasured* |
| 8 | **Half-res hub ("AO pass")**: SSAO + PCSS shadow + skylight trace + weather ground cover + water depth, writes packed half geometry | frame §7–§8; scene §11 | **1.04 / 0.91** |
| 9 | Clouds setup + sky cache capture/blend/irradiance | sky C3, C5 | **0.36 / 0.05** (0.36 = a capture frame) |
| 10 | `prepareLighting`: light clusters cull + `lightData`/`clusterData` upload, room map, light-map block sends (≤ 2 / 0.4 ms) | lights §4, §6 | CPU `light upload` **0.25**; `LIGHT_MAP` GPU *unmeasured* |
| 11 | **Volumetric fog** (half res, 6–12 steps: shadow compare + sky colour + cluster lights + light-map slots per step + 3D noise ×2) | sky F1 | **1.62 / 1.47** — the heaviest pass |
| 12 | Denoise: 21 taps over shadow/AO/ground/sky (+ fog when shafts) | frame §9 | **0.59 / 0.52** |
| 13 | Half-res clouds / water-shade composite variant (scissored) | frame §12 | **0.16 / 0.18** (clouds) |
| 14 | **Full composite** (FULL; + MARK + EDGES with MSAA): sky or surface branch, upsample, sun/ambient/rooms/lamps/emissive, water, distance fog, edge fade, transparency over, fog | frame §11 | **1.38 / 1.45** |
| 15 | Weather particles: rain layers → mist (4 stages at 1/4 res) → impacts → wade → snow layers | sky E6–E8 | `WEATHER` timer, *unmeasured* (B1 in clear weather) |
| 16 | Upscale (5-tap Catmull-Rom) when render scale < 100 % | frame §6 | *unmeasured* separately |
| 17 | UI: banded worker copy of the canvas → PBO → texture; textured quad (xBR ~25 taps when selected) | frame §15 | CPU `UI upload` wait (unmeasured); GPU one quad |
| 18 | `swapBuffers` → `paceFrame`; timers `endFrame`; hitch monitor; snapshot | frame §16–§19 | B2: 16.66/16.67/16.67/16.70 at 60 |

### CPU per frame, by thread

- **Client thread** (all GL): the client's own traversal and UI (2.1–2.3 ms, not ours); `drawTemp`'s `ModelData.copy`
  per dynamic model (scene §4); static alpha sort per zone (scene §3); `SceneLights.collect` with six `Math.cos` per
  flickering light in reach (lights §3, `LIGHTS` *unmeasured*); `LightClusters.cull` + `upload` 0.25; `LightMap.update`
  block sends ≤ 0.4 ms; `WallMap.build` **every game tick** over 4×184² flags (lights §7); `WeatherField.update` every
  0.1 s (~0.5 ms *est.*, sky E4); `EnvironmentManager.update` (81 lookups on a focal-tile change, sky B1);
  `SkyGridMap.upload` every 6 frames; `shadow submit` 0.14; `outside` 0.4; `InterfaceUpload.finish`.
- **Client render threads** (`RENDER_THREADS`): `drawDynamic` straight into `rts[id+1]` buffers (scene §4).
- **Temp workers** ("RuneLight temp models", 64-job ring, 200 µs linger): normals + sort + pack per dynamic model, every
  frame (scene §4).
- **Interface upload workers** (2): canvas bands into the PBO (frame §15).
- **Watchdog** (2 ms sleep): stack samples during a hitch (frame §19). **Weather audio** thread (sky E11).

### Per scene load, by thread

- **Loading thread** (the client's): `AreaHiding.prepare`, `RegionManager.prepare`, `WaterMap.build` (EDT over 209²,
  bodies, flow, falls — *unmeasured*), `FogGround.build`, `zoneSize` per new zone, `uploadZone` per new zone (scene §1,
  sky G3).
- **Client thread under a latch** while the loader waits: `Interactables.resolve` + ~380 `glGenBuffers`/`glBufferData`/
  `glMapBufferRange` (1–3 ms *est.*, scene §1 :4449–4493).
- **Swap frame (client thread)**: `swapScene` **3.5–4.5 ms** (6.6–9.1 on a teleport: zone free 3.8–6.4 for 529 zones,
  unmap 2.1–2.4) → then, on the same frame: a **one-frame** full static shadow build + `buildMax` ×3 (scene §9 :2568),
  the occlusion redraw (scene §10), the voxel/`ModelBlockers` handover, the sky capture after `STALE_MILLIS`, the
  `LightMapTexture.ensure` 52 MB zeroing when the scene size changed (lights §6, *unmeasured*). Load frame measured
  49–53 ms → **37 ms** after moving room texels and fog ground to workers (B3 fix 1–2); teleports 77–95 ms.
- **Workers**: voxel build ("RuneLight voxels" + ForkJoin ≤ 4, also `ModelBlockers.rasterize`), light-map bakes (1–3,
  burst 6), sky grid tracer (MIN_PRIORITY), sky field executor, "RuneLight room texels", "RuneLight room sky".
- **Area crossing**: `hideAreas` → `setGameState(LOADING)` → the client's own full scene build, **100–170 ms** (scene §13;
  B3 first run's 186 ms frame).

---

## 2. Quantities and their mechanisms

"Today" counts the mechanisms the inventories found for the quantity; "the one" is the mechanism proposed to remain,
chosen so the look is kept and the cost is the lowest of the candidates on GL 3.3.

| Quantity | Mechanisms today | The one that remains |
|---|---|---|
| **Sun / moon light (direct)** | 4: `sky.glsl` caster colour with overcast/haze/flash (sky C1 :249–283), its Java copy `DayCycle.directStrength` (sky A2), the alpha pass re-lighting in `frag.glsl:151–169` "same lighting as the composite, without shadows" (frame §2), `envSun` area multiplier (sky B2) | one `shadeSurface()` include used by the composite **and** the transparency layer; `sky.glsl` colours × shadow × `envSun`; the Java constants injected as `#define`s (sky A2) |
| **Ambient** | 10 (sky I.2) + 3 interior terms stacked at `composite_frag.glsl:435–447` (lights §10–§11): palette `ambientCover`, analytic `skyLightAt`×`overcastSkyBoost` (≤ 200×), sky-cache irradiance, haze/warmth lift, flash dip, `SKYLIGHT_FLOOR`, `daylightFill`, room daylight floor, `roomAmbientFill`, `RoomLight` fill, `envAmbient`, cave light | `ambient(n) = skyIrradiance(n)` from the **sky cache** (it already holds palette, haze, warmth, clouds) `× skyVisibility × envAmbient × ao` + the one interior fill (`RoomLight`, score from the CPU light list); analytic path = first-frame fallback only; flash dip stays a two-line modifier (sky I.2, D4; lights §11) |
| **Sky visibility (skylight)** | 5 + 1 debug (sky I.3): `SkyGrid` voxel trace (15 MB 3D), `SkyField` wall flood, weather occlusion map (1024² depth, full extra scene draw), under-roof `sheltered`, `VoxelGrid.top` (unused as texture) | the **voxel grid** (render-plan §3.6a) with a flood pass folded in and its `top` column uploaded as the occluder height — one producer, one upload budget (sky I.3, D1–D3, E6) |
| **Lamp light** | 7 (lights §5): cluster loop, light-map loop (same formula × baked visibility), room fill, `roomAmbientFill`, room daylight floor, fog glow twice (analytic `pointGlow` + baked `scatterMap`), weather/water copies with own scales | the **cluster loop** (`light_clusters.glsl`, CPU-built, right for GL 4.1) as one include shared by composite, fog, weather, water; `POINT_LIGHT_SCALE`/`FOG_MAP_LIGHT_SCALE`/`WEATHER_POINT_LIGHT_SCALE`/`LAMP_SURFACE` → one |
| **Lamp occlusion** | 8 (lights §6): `WallMap` edges, `solid` tiles, `ModelBlockers`, floors/`filled`, `storeyCode`/`lightOnStorey`, CPU `visibleFromOtherPlanes`, fog `OpenRooms`/`FogGround` room factor, weather `colorIndoor` — only 1–4 geometric, **static lights only** | the **voxel-grid face-bit march** per light (render-plan §3.6a: 6–8 steps, dynamic lights occluded too, ≈ 0.1–0.2 ms); plane gating becomes a property of the grid, not three rules |
| **Shadow** | 12 passes/consumers (scene §9): static build, `buildMax` ×3, copy, sway, dynamic, PCSS in AO, denoise, upsample, fog shafts, occlusion map, held bolt map, dead `SHADOW_CACHE=false` | **B6**: Lumina's static map spread over frames **including the first after a swap**, one dynamic map, receiver at full res in the lighting pass with min/max tiles; lightning re-plugged (sky C6) |
| **AO (contact)** | 4 (frame §8; lights §10): SSAO multiplying indirect **and** direct (`AO_DIRECT 1.0`, physically 0 per its own comment :48), `SKYLIGHT_FLOOR`, `roomAmbientFill` wall shaping, `RoomAmbient` distance field | **SSAO at half res, indirect only** (`AO_DIRECT 0`), 8 samples constant; wall feet darken by SSAO + skylight, not a hand-made wall field |
| **Fog amount** | 11 (sky I.1): config density, weather type fields, `FogClimate`, field spatial delta, player multipliers + cave, area `fogDepth` into volumetric, area `envFogAmount` (117 HD exponential — a **second renderer**), `envHaze`, room/water/noise/cap shaping, `EdgeFade.fogginess`, rain mist | **one extinction field σ(x)** = config × field FOG (folds weather + climate) × (1 + area depth), marched by `fog_frag` near and continued analytically to the draw distance; `capFogCover` applied once; `EdgeFade` and the sky haze read σ (sky I.1, F1, B2–B4) |
| **Fog colour** | 2: fog pass sky/sun/lamp-lit samples × `fogTint`; `envFog` = area colour 35 % toward the horizon (sky B2 :29, `composite_frag.glsl:356`) | the **sky behind the pixel** (`atmosphereSky`/`skyBehind`) tinted — one function for sky, aerial tail and volume (render-plan §2 table) |
| **Sky colour** | 4: `atmosphereSky` drawn per sky pixel; `skyBehind` recomputed for edge-faded surface pixels (frame §11 :376, :541) and ×4 per cloud texel in `skyShowsAt` (:297); `envSky` haze mix; sky-cache capture of the same (sky C2–C3) | `atmosphereSky` **once per pixel** (sky and edge share one evaluation); the sky cache is its capture, not a second model |
| **Water surface** | 6 (sky I.4): surface geometry pass + wave bake, `water.glsl` half shade + full blend, `waterFake`, puddles `weatherPuddleWater`, `VoxelWater`, fog water stop | the per-tile plane from the data texture (page 0 heights) intersected **in the composite**, shaded by `water.glsl` with an up normal + rain rings; puddles accepted as their own cheap shading (sky G4, G6, E9) |
| **Vertex colour** | 8 (scene §6): `baseColor` undo, `GroundBlender.undoShading` (second formula, **GAIN 1.25**), `unlitColors` swap, blended terrain colour, bank-averaged bed colours, HSL override + `entityTint`, `smoothBanding`/border mix, texture `brightness`/`textureLightMode` | one `undoBake(colour, normal)` with 117 HD's constants and the terrain gain as its single terrain branch; `entityTint`/HSL override kept (game semantics); **no blending between tiles** (user's rule); bed colour = the tile's own |
| **Vertex normal** | 5 (scene §5; lights §13): `computeNormals`+`vertexNormal`, `flatFaceNormals` 50° all-three-corners gate, `terrainNormal`/`modelNormal` two height sources, `frag.glsl` `dFdx` face normal into the material target, `pointLightNormal` 0.55–0.85 blend in the composite | **`VertexShading.computeNormals` for every face**; flat only for 117 HD's flat-lit object list (A2, to import); face normal from vertex data; `pointLightNormal` goes so sun and lamps shade the same normal (A3/A7) |
| **Culling / visibility** | 10 (scene §12): `zoneVisible` circle + frustum, `modelVisible`, the client's square + roof/level hiding, `shadowZones`, `renderOcclusion` reach, `RegionManager`, `AreaHiding`, callback manager, per-face backface/near reject, `renderAlpha` filters | `EdgeFade.zoneVisible` (circle + 5-plane frustum, pads 4/10/2) as **the** zone cull, feeding one "zones in reach" list that shadows and occlusion iterate (scene §2); B7 measures the enlarged client square against a plain frustum before the circle stays |
| **Frame pacing / timing** | 2 limiters (client's and `paceFrame`, frame §16); 3 CPU frame figures (`cpuFrameMs` EMA 0.9, `swapIntervalMs` EMA 0.95, `untrackedMs`) + `GpuTimer` "last valid" vs `CpuTimer` EMA (frame §17–§18) | `paceFrame` alone (B2 accepted; `pace off` A/B path removed); one EMA constant for both timers; `FrameSnapshot` is the ground truth |

---

## 3. Packaging proposal

Java moves into packages by quantity; GLSL mirrors them under the same names. `config/` stays for the enums the
settings need. A new `visibility/` holds the one static-visibility source (render-plan §3.6a) and the room/wall data
it is built from, because lamps, skylight and weather all read it. `dev/` holds everything that only measures or
shows. Moves are the **last** segment (§7, S23): files move once each mechanism is one.

**Proposed layout**

```
frame/       the frame order, targets, composite, half-res hub, denoise, upscale, UI blit, pacing, frame UBO
scene/       zone/model upload (one face pipeline), draw, culling, materials, textures, vertex shading, area hiding, sway codes
light/       definitions, placement, life, clusters, room fill, the shared surface-shading include
visibility/  voxel grid (capture → voxelize → grid → 3D texture), walls, rooms, sky visibility trace
sky/         clock, sun/moon, area environments, sky palette & atmosphere, sky cache, night sky, clouds, lightning
fog/         the one volumetric fog (density field, ground, march, apply)
water/       overrides, flow data, water map/bodies, surface, shading, caustics
weather/     data, forecast, climate, field, wind, driver, rain/snow, ground cover, audio
shadow/      shadow map (B6's one mechanism), caster shaders, receiver
dev/         timers, hitch monitor, snapshot/capture, commands, shader watcher, overlays, debug program
common/      (GLSL) frame.glsl, octahedral, halfres/halfmap, dither
```

**File → home → action** (every file the inventories name; "remove" rows are justified in §4)

| Current | Proposed home | Action |
|---|---|---|
| `RuneLightPlugin.java` (≈ 4,900 lines; `SceneContext` :614, `RenderThread` :604 nested) | `frame/RuneLightPlugin.java` | **split**: keep `DrawCallbacks` + frame order; `compositeScene` → `frame/Composite.java`; `paceFrame`/`setupSyncMode` → `frame/FramePacer.java`; `loadScene`/`loadSubScene`/`rebuild`/`swapScene`/`SceneContext` → `scene/SceneLoader.java` (one load routine, scene §1); `renderShadowMap`/`renderStaticShadows`/`shadowZones` → `shadow/`; `prepareLighting`/`collectLights`/`updateLightMap`/`updateRoomLight` → `light/LightFrame.java`; `runCommand`/`screenshot` → `dev/Commands.java`; `createTemplate`/`initProgram`/`reloadShaders` → `frame/Programs.java` |
| `SceneTargets.java` | `frame/` | keep; remove `drawPart`/`partTextures` (frame §1), `resolveAlpha` and the MS variants (frame §5); consider packing coverage into the material target |
| `Upscaler.java`, `upscale_frag.glsl`, `config/RenderScale.java` | `frame/`, `config/` | keep; drop the `DEPTH24_STENCIL8` renderbuffer (frame §6); `halfRatio` ∈ {1, 2} only |
| `InterfaceUpload.java`, `fragui.glsl`, `vertui.glsl`, `colorblind.glsl` | `frame/` | keep; UI scaler one mode; colorblind applied here only (frame §15, §3) |
| `scale/bicubic.glsl`, `scale/xbr_lv2_*.glsl`, `scale/hybrid.glsl`, `config/UIScalingMode.java` | — | **remove** (keep `hybrid` only if it is the one mode; else Catmull-Rom shared with the upscaler) |
| `composite_frag.glsl`, `composite_vert.glsl` | `frame/` | **split**: `shadeScene` surface lighting → `light/shade_surface.glsl` (shared with the alpha pass); remove `COMPOSITE_EDGES`/`COMPOSITE_MARK`, `compositePart`, debug-view branches, `lightingEnabled`, `upsampleFog` duplicate (frame §5, §10, §11) |
| `ao_frag.glsl`, `halfres.glsl`, `halfmap.glsl` | `frame/halfres_frag.glsl`, `common/` | keep as the half-res hub, renamed; shadow lookup out to `shadow/`; per-quantity gate (frame §7) |
| `denoise_frag.glsl` | `frame/` | keep (the one spatial filter); composite upsample becomes plain geometry-weighted 2×2 (frame §9) |
| `fog_apply.glsl` | — | **merge into** `frame/composite_frag.glsl` (`upsampleHalf`) and `fog/fog_apply.glsl` (apply only) |
| `ign.glsl` (+ inline sky dither, `cloudJitter`) | `common/dither.glsl` | **merge** into one `dither(px, frame)` with a temporal term (frame §13) |
| `octahedral.glsl`, `frame.glsl` | `common/` | keep (frame §22: delete the dead `ZBUF_DEBUG` depth variant) |
| `scene_samplers.glsl` | — | **remove** with MSAA |
| `edge_fade.glsl`, `EdgeFade.java` | `fog/` (fade), `scene/Culling.java` (the zone/model tests) | **split**: culling stays as is; the fade becomes the far clamp of the one fog (frame §14) |
| `vert.glsl`, `frag.glsl`, `material_target.glsl`, `materials.glsl` | `scene/` | keep; prune `FOG_SCENE_EDGE_*`, unused uniforms, `edge_fade` include, debug defines (frame §3); alpha-pass lighting → `shade_surface.glsl` |
| `GpuTimer.java`, `CpuTimer.java`, `HitchMonitor.java`, `FrameSnapshot.java`, `FrameCapture.java`, `CommandFile`, `ShaderWatcher` | `dev/` | keep; fix the per-frame disable, drop `AMBIENT/SUN/POINT_LIGHTS/SURFACES` stages, one EMA (frame §17–§19) |
| `LookMonitor.java` | `dev/` | **remove** once B3/A1 are done (scene §16) |
| `SceneUploader.java` | `scene/SceneUploader.java` + `scene/FaceUpload.java` + `water/WaterBed.java` | **split**: the per-face pipeline (:1274–1398) becomes the shared `uploadFaces`; bed/shore/standing-object code (:912–1146, :1411) → water (scene §4, §8) |
| `ModelUploader.java` | `scene/FaceUpload.java` | **merge**: `uploadSortedModel` :249–335 and `uploadTempModel` :664–775 are the same ~40 lines (scene §4) |
| `TempModels.java`, `ModelData.java`, `VAO.java`/`VAOList`, `VBO.java`, `GpuIntBuffer.java`, `Zone.java` (+ `AlphaModel`) | `scene/` | keep; B5 decides the worker ring; zone buffers pooled, free/unmap sliced (scene §1, §4) |
| `VertexShading.java` | `scene/` | keep `computeNormals`/`encodeNormal`; remove `flatFaceNormals` 50° gate and the `unlitColors` swap; add `undoBake` (scene §5–§6) |
| `GroundBlender.java` | — | **remove**; `undoShading` + GAIN 1.25 move into `VertexShading.undoBake` (scene §7) |
| `AreaHiding.java`, `area_hiding.json`, `RegionManager.java`, `regions/Regions.java`, `regions/Region.java`, `regions/regions.txt` | `scene/AreaHiding.java` | **merge** into one list, one setting, applied as a per-zone draw mask (no reload) (scene §13) |
| `SurfaceMaterials.java`, `materials.json`, `Interactables.java`, `TextureManager.java`, `tools/import_117hd_materials.py` | `scene/` | keep; fix `Rule.matches` per-face allocation; extend the JSON with 117 HD flat-lit / inherit-tile-colour flags (scene §14) |
| `WindSway.java`, `sway.glsl`, `wind.json`, `tools/import_117hd_wind.py` | `scene/` (codes and the vertex rule) | keep; one setting (scene §15, sky E10) |
| `LightDefinition.java`, `lights.json`, `tools/import_117hd_lights.py`, `AutoLightRules.java`, `AutoLightPlacement.java`, `autolights.json` | `light/` | keep (lights §1–§2) |
| `SceneLights.java`, `LightLife.java`, `OutdoorLight.java`, `PointLight.java` | `light/` | keep; plane rule and night curve to one place (lights §3) |
| `LightClusters.java`, `light_clusters.glsl` | `light/` | keep as the one light list; room map / room fill **out of `lightData`** (lights §4) |
| `RoomLight.java` | `light/` | keep as the one interior fill; score from the CPU light list, not the light map (lights §11) |
| `BuildingLights.java` | `visibility/Rooms.java` | **merge** (walls/rooms bookkeeping; `FILL_TINT`, `nightFactor` die) (lights §9, §14) |
| `LightMap.java`, `LightMapBake.java`, `LightMapTexture.java`, `LightBlockers.java`, `light_map.glsl`, `ModelBlockers.java`, scene dump (`dumpScene`), `LightMap*Test`, `ModelBlockersTest` | — | **remove** when the §3.6a grid lands (lights §6, §8, §14) |
| `WallMap.java` | `visibility/` | keep as the source of blocked faces; door-event diff on a worker, not per tick (lights §7) |
| `BuildingDetector.java`, `RoomMap.java`, `OpenRooms.java`, `rooms.glsl` | `visibility/Rooms.java`, `visibility/rooms.glsl` | **merge**: one rooms texture, one GLSL lookup, one CPU twin; `OpenRooms` a bit of it (lights §9) |
| `RoomAmbient.java` | `weather/` (puddle placement) or inside the rooms texture | **remove as a lighting term**; keep the distance field only if puddles need it (lights §10) |
| `BuildingOverlay.java`, `SkyProbeOverlay.java` | `dev/` | keep; fix the `showBuildings` description (lights §9) |
| `VoxelCapture.java`, `Voxelizer.java`, `VoxelGrid.java`, `VoxelWater.java`, `SceneVoxels.java`, `SkyTerrain.java`, `VoxelBuilder.java` | `visibility/` | keep → **restructure** into the one §3.6a grid (face bits, R8 3D texture, per-zone slices); capture moves to the worker that consumes it (sky D1, scene §1) |
| `SkyGrid.java`, `SkyGridMap.java`, `skylight_ao.glsl` | `visibility/SkyVisibility.java`, `visibility/sky_visibility.glsl` | keep the quantity; byte-budget the upload; drop `resolution`/`supportUnits`/`selectNearbySamples` (sky D2) |
| `SkyField.java`, `SkyFieldMap.java` | — | **merge into** the grid as a flood pass (sky D3) |
| `skylight_ambient.glsl` | `light/shade_surface.glsl` | **merge**; remove `doorwayDaylight` (dead) and `daylightFill` (sky D4) |
| `GameClock.java`, `DayCycle.java` | `sky/` | keep; `directStrength` constants shared as `#define`s (sky A1–A2) |
| `EnvironmentManager.java`, `EnvironmentUniforms.java`, `environments.json`, `tools/import_environments.py` | `sky/` | keep; outputs one fog quantity (sky B1) |
| `environment.glsl`, `fog_cover.glsl` | `sky/environment.glsl` (ambient/sun/tint/cave), `fog/` (cap, once) | **split**; `envFog`/`envFogAmount`/`envSky` → removed into the one fog (sky B2–B3) |
| `sky.glsl`, `atmosphere.glsl` | `sky/` | keep (the look) |
| `SkyCache.java`, `sky_cache_frag.glsl`, `sky_cache_blend_frag.glsl`, `sky_irradiance_frag.glsl` | `sky/` | keep as **the** sky-light source; capture off the swap frame (sky C3) |
| `NightSky.java`, `night_sky.glsl` | `sky/` | keep; one `nightSky` setting (sky C4) |
| `Clouds.java`, `clouds.glsl` | `sky/` | keep; `cloudJitter` → `common/dither`; `cloudSpeed`/`skyHorizonAtEdge` constants (sky C5) |
| `Lightning.java`, `lightning.glsl` | `sky/` | keep; re-plug on B6's shadow mechanism (sky C6) |
| `VolumetricFog.java`, `fog_frag.glsl` | `fog/` | keep → **the** fog; σ field, analytic tail, one lamp include, `fogQuality` constant (sky F1) |
| `FogGround.java` | `fog/` | keep terrain heights; room channels **merge into** the rooms texture (lights §12) |
| `FogClimate.java` | `weather/` | keep (sky E3) |
| `WaterOverrides.java`, `water_overrides.json`, `tools/import_117hd_water.py` | `water/` | keep (sky G1) |
| `WaterFlow.java`, `water_flow.dat`, `tools/water/*` | `water/` | keep pending the flow-map discussion; v2 branch + `WaterBodies.shapeFlow` removable (sky G2) |
| `WaterMap.java`, `WaterBodies.java` | `water/` | keep bed/type/height/shore/flow; **remove** energies, ridges, openSea, falls, contacts (§K) (sky G3) |
| `WaterSurface.java`, `water_vert.glsl`, `water_frag.glsl` | `water/` | keep minus §K now; later **fold into** the composite (sky G4–G5) |
| `Water.java`, `water.glsl`, `water_common.glsl`, `water_caustics.jpg` | `water/` | keep minus §K (noise layers 0 and 2, drift, contact); caustics stay (sky G6–G8) |
| `config/WaterDebug.java` | `dev/` (into `debugView`) | **merge**; `WAVES` entry removed |
| `WeatherData.java`, `WeatherType.java`, `WeatherClimate.java`, `WeatherRegions.java`, `weather_types.json`, `weather_climate.json`, `weather_regions.json`, `WeatherForecast.java`, `Climate.java` | `weather/` | keep (sky E1–E3) |
| `WeatherField.java` | `weather/` | keep the 6 GPU channels as the field; ease the other 30 at the player; drop its own WET/SNOW easing (sky E3–E4) |
| `WeatherVariation.java`, `Wind.java`, `wind.glsl` | `weather/` | keep; drop the constant `varying` parameter (sky E5) |
| `Weather.java` | `weather/` | keep the driver; **remove** `renderOcclusion` (→ `VoxelGrid.top`), `getWaterWind` (sky E6, §K) |
| `rain_vert.glsl`, `rain_frag.glsl`, `snow_vert.glsl`, `snow_frag.glsl`, `weather_common.glsl` | `weather/` | keep; lamp loop → the shared include (sky E7–E8) |
| `WeatherGround.java`, `weather_ground_ao.glsl`, `weather_ground.glsl`, `weather_wade.glsl`, `WadeSteps.java` | `weather/` | keep; exposure from the voxel top (sky E9) |
| `WeatherAudio.java`, `AudioMixer.java`, `sounds/*.wav`, `tools/make_weather_sounds.py` | `weather/` | keep (sky E11) |
| `ShadowMap.java`, `shadow_vert.glsl`, `shadow_frag.glsl` | `shadow/` | **restructure** (B6): static over frames incl. the first, one dynamic map; held bolt map and `SHADOW_CACHE=false` out (scene §9) |
| `shadow_copy_frag.glsl`, `shadow_max_frag.glsl` | — | **remove** with B6 (copy pass and `buildMax` ×3) |
| `shadow_pcss.glsl` | `shadow/shadow_receiver.glsl` | **restructure**: receiver at full res in the lighting pass (scene §11) |
| `config/ShadowResolution.java` (+ `shadowDistance`) | `config/ShadowQuality.java` | **merge** into one quality enum (scene settings) |
| *(new)* `dev/debug_frag.glsl` | `dev/` | the eight debug views + water/skylight debug as one tiny program reading the targets (frame §11c) |
| *(new)* `frame/FrameUbo.java` (absorbs the 5-float camera UBO and `FrameUniforms`) | `frame/` | per-frame uniforms written once (frame §11b, §24) |

---

## 4. Remove list

Each line: what, why, where the inventory says so.

**A. The water wave system — one unit** (sky G5, §K; user's decision). Not in the unit: the flow map (`WaterFlow`,
page 0 flow, `waterFlowPhases`, `BANK_SLOW`), caustics (G7), rain rings (G8), the surface pass (G4) and `water.glsl`
(G6), which simplify but stay.
- `WaterSurface.java`: `WAVE_MAP_TILES`, `WAVE_MAP_REACH`, `WAVE_MAP_TEXELS`, `STEPS_PER_SECOND` (:87–90), `WAVE_VIEW_SLACK`
  (:102); `uniBake`, `uniWaveBlend` (:80–82), `waveTex`, `waveFbo` (:91), `builtStep`, `shownStep`, `halvesDone`,
  `lastTime` (:94–96), `waveBaked`, `waveMotion`, `motion` (:103–105), `NEEDED`/`WANTED`/`PICKED` (:107), `waveUnitA/B`
  (:110); `bakeWaves` :568, `markWaveChunks` :616, `startStep` :648, `bakeStep` :660, `catchUp` :682, `pick` :700,
  `bindMap` :708, `bakePicked` :719, `mipmap` :728, `setMotion` :736, `ensureWaveMaps` :742; in `draw` :522–531 and
  :551–554; in `free` :822–828; the wave-unit parameters of `setProgram`/`upload` (verify callers in `Water.setSurfaceProgram`,
  `RuneLightPlugin.java:≈1282, ≈1589`); `WAVE_MARGIN` (:33) stays only if rain rings need a bounds margin.
- `Water.java`: `DRIFT_CALM`, `DRIFT_WIND`, `DRIFT_WRAP`, `STRENGTH_EASE` (:36–41); `Frame.windX/windZ/strength/driftX/driftZ`
  (:48); `lastNanos`, `driftX`, `driftZ`, `strength` (:61–62); `advance` :403, `drift` :413; noise **layer 0** (`createNoise`
  :454 / `noise` :490, `SLOPE_RANGE` :22) and **layer 2** (`CELL_SEED`, `CELLS`, `CELL_RADIUS` :29–31, `cells` :555) — keep
  layer 1 (caustics) and `aberrate` :616; `texContact` upload :247–268 and `WaterMap.CONTACT_SIZE`.
- `WaterMap.java`: `findContacts` :739, `spread`/`spreadCell`/`spreadLine` :784–932, `terrainFalls`, `dirAt`, `waterAt`,
  `edge`, `edgeHeight`, `markFall`, `pullToLines` :932–1136; in `buildTexture` :1380 page 1 g (wave energy) and a
  (turbulence), page 2 g (open-sea share), b/a (fall frame) (:80–82); `SHORE_FIELD_REACH` :37 comment.
- `WaterBodies.java`: `energies` :744, `ridges` :640, `openSea` :735, `OPEN_SEA_RIDGE` :41, `SHAPED_MIDDLE` :61, `STILL` :63,
  the `turbulence`/`energy` arrays (fields near :68–130, verify names), `drop` :714 if only energy uses it.
- `Weather.java`: `getWaterWind` :435. `RuneLightPlugin.java`: the `waterWind` computation and `water.advance(...)`
  feeding `drawSurface` (:≈3287, verify), the wave texture units passed to `setSurfaceProgram`. `config/WaterDebug.java`:
  `WAVES` (and `waterDebug == 2`).
- GLSL `water_frag.glsl`: uniforms `waterWind` :17, `waterDrift` :18, `waterBake` :29, `waterWavesA` :30, `waterWavesB` :31,
  `waterWaveBlend` :32, `waterFocus` :33, `waterContact` :16; output `WaveOut` :43; `crestWaves` :133, `ripples` :163,
  `shoreFoam` :231, `waterFoamStill` :269, `waterFoamHeight` :276, `contactFoam` :285, `fallFrame` :296, `fallLaid` :302,
  `fallFoamHeight` :308, `waterWaveField` :313, `hardFoam` :386; `main` bake branch :396–401, wave fetch/blend :426–432
  (keep `RAIN_FOAM` :449 if foam stays rings-only), foam coverage packing :496–498, `waterDebug == 2` :475–479.
  `water_vert.glsl`: `waterBake` :15 and the bake-space path (:23+, verify). `water_common.glsl`: `waveNoise` :240,
  `waveNoiseD` :249, `seaOctaveGrad` :263, `waterFoamRim` :201, `waterFoamCells` :207, `waterFoamEdges` :224, layer-0
  `waterNoise` use (`waterFlowPhases` :108 stays — caustics). `water.glsl`: foam in `waterOver` :501–598, `waterGlitterNormal`
  :440 / `waterNormalAt` :445 → up normal + rings, `waterShoreOf` :135.
- Settings: none are wave-specific. 24 MB VRAM and the per-frame bake + 1,152² mipmap chain go with it.

**B. The timers panel** — already gone (`RuneLightOverlay.java` absent; RuneLightPlugin.java:1861 comment; frame §20).
Left behind and to remove: `weatherFieldMap` (RuneLightConfig.java:956–965, no reader — frame §20, sky J), the
HitchMonitor text "show timings turns them on" (:133, frame §19), the GpuTimer comments about the setting (:59–61).

**C. MSAA on the G-buffer** (frame §5): `antiAliasingMode`, `sceneSamples`/`shaderSamples` and the recompile on change
(RuneLightPlugin.java:1188–1205, 1910–1920), `glEnable(GL_MULTISAMPLE)` :1787–1794, `COMPOSITE_EDGES`/`COMPOSITE_MARK`
programs :291–331 and their draws :3480–3511, `hasWindowStencil` :2723–2733, `configurePixelFormat(0,0,8)` :783,
Upscaler's stencil renderbuffer :51–55, `SceneTargets.resolveAlpha` :416–427, `scene_samplers.glsl`, `sameSurface`/
`twoSurfaces`/`shadeSamples` composite_frag.glsl:548–624, the per-sample loop in `skyShowsAt` :316–319. Reason: N×
bandwidth on five targets, three surface tests per pixel, a second AA mechanism stacked on render scale ("weird
scaling for the MSAA"), a stencil macOS cannot provide at 100 %.

**D. The legacy composite "parts" path** (frame §1, §11, §17): `runelight.compositeCombined` (:1234, :3457), the five
extra fullscreen passes and read-back (composite_frag.glsl:462–478, 525–529), `drawPart`/`partTextures` (SceneTargets.java:23–48),
the `compositeParts` sampler binding (:1439), `composite_mode`/`compositePart`, `GpuTimer` stages `AMBIENT`, `SUN`,
`POINT_LIGHTS`, `SURFACES` (GpuTimer.java:35–38; `POINT_LIGHTS` is never begun — lights §14).

**E. Dead settings, dead code, retired variants**
- `lightingEnabled` uniform always 1, tested per pixel at composite_frag.glsl:415, :455 and five times in water.glsl (frame §11).
- `SHADOW_CACHE = true` constant and the non-cache branch :2418–2427, :2472–2476 (frame §21, scene §9).
- vert.glsl:38–41 `FOG_SCENE_EDGE_MIN/MAX`, `FOG_CORNER_ROUNDING*`; :59–60 `drawDistance`, `expandedMapLoadingChunks`
  uniforms (set at RuneLightPlugin.java:2011–2012, unused); :81 `edge_fade.glsl` include; `FRAG_UVS`/`ZBUF_DEBUG`/`BIAS_DEBUG`
  blocks (frame §3).
- `doorwayDaylight` (skylight_ambient.glsl:21, no callers); `skylight_ao.glsl:6` comment to a non-existent file (sky D4, J).
- `SkyGrid` `resolution ≠ 1` path, `supportUnits` "legacy support corners", `selectNearbySamples` wrapper (SkyGrid.java:503–523,
  SkyGridMap.java:35, :131) (sky D2).
- `WaterFlow` v2 branch + `WaterBodies.shapeFlow` (:596) — shipped file is v3 (sky G2).
- `WeatherField` own WET/SNOW_COVER easing (:322–338) overwritten in AUTOMATIC (sky E3); `Weather.update(... varying=true)` (sky E5).
- `WallMap.java:14` comment to `point_light_blocking.glsl` (removed in 20963fa8); `LightClusters.update()` :236–240 uncalled;
  `RoomMap.roofed` :29 with no reader found (unverified); `BuildingLights.FILL_TINT`/`FADE_*`/`nightFactor` (:37–40, :171–176);
  `PointLight.plane = -1` "every storey" branch reached only by the test light (lights §14).
- `pace off` A/B path (RuneLightPlugin.java:4857–4862) once B2 is accepted — it is (frame §16).
- `Zone.readOpaque` :337 and `dumpScene` (scene §1, lights §14) — the offline light-map fixture dies with the map.
- `LookMonitor` (scene §16) after B3/A1.
- `showBuildings` description promising markers the overlay does not draw (RuneLightConfig.java:970–973).

**F. Duplicate fog / ambient / normal / colour paths (one mechanism per quantity)**
- Fog: `envFogAmount`/`envFog`/`envSky` as separate renderer and colour (environment.glsl:29, :43, :56; composite_frag.glsl:356,
  :530–539); `fog_apply.glsl upsampleFog` second upsampler with a different depth weight (frame §10); the second fog
  denoise FBO path (`fogFbo(denoised)`, SceneTargets.java:325–346) once the one filter owns it; `EdgeFade.fogginess`'s own
  smoothsteps; `capFogCover` applied twice (sky B3, I.1).
- Ambient: analytic `skyLightAt`×`overcastSkyBoost` as a live term (fallback only); `daylightFill` (composite_frag.glsl:432–433);
  room daylight floor (:440–446); `roomAmbientFill`/`RoomAmbient` (lights §10); `SKYLIGHT_FLOOR` folds into the visibility
  value; the second night curve (`BuildingLights.nightFactor` vs `collectLights`' smoothstep, lights §3).
- Normal: `pointLightNormal` (composite_frag.glsl:204–209); the 50° `flatFaceNormals` gate (VertexShading.java:84–126) once
  the flat-lit list exists; the `dFdx` face normal as the lamp normal (frag.glsl:139–146 stays only as the degenerate
  fallback) (scene §5, lights §13).
- Colour: `GroundBlender` blending and its second `undoShading` formula (scene §7); the `unlitColors` swap (:1324–1327);
  bank-averaged bed colours (`bedCornerColor` :975) (scene §6, §8).
- Lamp: the light-map branch of `pointLights` (:216–237), `lightOnStorey`, the fog's baked `scatterMap` glow (second of two),
  the four scale constants (lights §5).
- Shadow: the per-frame copy pass, `buildMax` ×3, the held bolt map, PCSS inside the AO pass (scene §9, §11).
- Sky visibility: `SkyField` as its own producer, the weather occlusion map (`Weather.renderOcclusion` :847), `sheltered`
  as a fourth shelter notion (sky I.3).
- Dither: the inline sky dither (composite_frag.glsl:385–387), `cloudJitter` (frame §13).
- Colorblind at two sites (frag.glsl:130, fragui.glsl:79) and `inverse(rgb2lms)` per pixel (colorblind.glsl:38) (frame §3).
- Area hiding: `hideUnrelatedMaps` + `RegionManager` beside `AreaHiding` (scene §13).
- UI scaler: six modes, three shader files (xBR 315 lines) for one resample (frame §15).
- `WeatherField`'s 30 of 36 channels eased per cell but read only at the player (sky E4).
- Three copies of the loading sequence (`loadScene`, `loadSubScene`, `rebuild`) and three copies of the per-face upload
  pipeline (scene §1, §4).

---

## 5. Performance ranking — the ten heaviest or most hitch-prone things

| # | What | Number | Fix the inventories propose |
|---|---|---|---|
| 1 | **The swap-frame bundle**: `swapScene` free/unmap of ~190–529 zones + a *one-frame* full static shadow build (`front == null → frames = 1`, RuneLightPlugin.java:2568) + `buildMax` ×3 + occlusion redraw + voxel handover + sky capture + 52 MB light-map zeroing | load frame 49–53 → **37 ms** after fixes 1–2; teleports **77–95 ms**; `swapScene` 3.5–4.5 / 6.6–9.1 ms; `Zone.free` → `glDeleteBuffers` in the stacks | one scene-derived-data pipeline with a scheduler ("one heavy job per frame", HANDOVER B3); static map over frames including the first (first frames: stale map or none); zone buffers pooled / freed over frames; captures and uploads deferred (scene §1, §9, §10; sky C3, D2; lights §6) |
| 2 | **Area-boundary crossing** → `setGameState(LOADING)` → the client's own full scene build; zones not reusable | **100–170 ms** (B3's 186 ms frame was the client's build) | a per-zone visibility mask at draw time instead of a reload; one hide list, one setting (scene §13) |
| 3 | **Volumetric fog pass** (+ its filter share and composite upsample) | **1.62 / 1.47 ms**; 0.32 at 25 % — pixel-bound | one σ field, steps/res decided in B4 (`fogQuality` → constant), one upsampler, light-map slots per step gone with the map, denoise only with shafts (sky F1, I.1; frame §9–§10) |
| 4 | **The full composite** | **1.38 / 1.45 ms** (×4 at 4K if B1 was at 50 %) | UBO for the ~60 uniforms set up to 3× per frame; parts path, MSAA split and `lightingEnabled` out; one `upsampleHalf`; `skyBehind` once per pixel; debug views in a separate program (frame §10–§11) |
| 5 | **MSAA_2 default on five G-buffer targets** + three per-sample surface tests per pixel + stencil passes | *unmeasured separately* (B1 taken at the default); the one-program variant was 9× slower per the code's own comment (:560–563) | remove; render scale + Catmull-Rom is the one AA/supersampling path; judge dither at 25–50 % on Windows (frame §5–§6, A5) |
| 6 | **The half-res hub** (SSAO 12 samples + PCSS 12+16 taps + skylight 3D fetches + ground cover 9 taps + water test), gated as one | **1.04 / 0.91 ms**; "AO off" still runs the pass; no open-ground early-out exists (HANDOVER's ≥ 0.995 premise is not in the code) | per-quantity gate; `AO_DIRECT 0`; 8 samples constant; shadow receiver moves to B6; true skip when denoised AO ≥ 0.98 and flat (frame §7–§8; scene §11) |
| 7 | **Shadow map per frame** (copy pass + sway zones + dynamic casters; static build frames) | **0.64 / 1.80 ms** (yaw-dependent); every zone `rebuild` restarts a full 3-frame static redraw | B6: Lumina's cached static map over frames, one dynamic map, receiver at full res; redraw only the rebuilt zone's region (scene §9, §17) |
| 8 | **The light map**: 3,800 lines, 52 MB 3D texture zeroed on the client thread in 24 calls, bake workers, a second lamp branch per pixel and per fog step | per-pixel share *unmeasured* (`POINT_LIGHTS` never begun); the `glTexSubImage3D` bursts were 30+ ms per load before throttling | measure with a real timer first; remove with the §3.6a grid (lights §6; sky D1) |
| 9 | **Dynamic model upload**: `ModelData.copy` (18 arrays) per model per frame on the client thread, then `computeNormals` + `flatFaceNormals` + sort on workers; 4 MiB `VAO` map/unmap per render thread per frame | `client scene` 0.7–0.9 ms CPU; TempModels' own comment: "most of a millisecond a frame in busy areas"; `BUFFER_MAP` *unmeasured* | B5: one `uploadFaces`, a per-model cache keyed by identity/animation frame so normals are computed once, worker ring kept only if measured (scene §4–§5) |
| 10 | **The untimed per-event work**: weather occlusion redraw (all levels, all roofs, 4 MB) on every zone change; `WallMap.build` every game tick + whole `RoomMap` re-pack and 2.9 MB re-upload per door; `SkyGridMap` upload up to 15 MB in one frame (`glEndQuery` stall) | all *unmeasured* in B1 (`WEATHER`/`OCCLUSION` stages exist but were not in the row; B3 saw `WallMap.diff` and the voxel upload in stacks) | occlusion from `VoxelGrid.top`; `WallMap` door-event diff on a worker; every upload under a per-frame byte budget from the one scheduler (sky E6, D2; lights §7, §9) |

Also on the ledger but cheaper: the denoise's second filter (0.59 / 0.52, two filters of one dither — frame §9–§10);
the `WaterMap` CPU build and `FogGround` upload on the load path (*unmeasured*, sky G3, F1); the water at a wide
sea view (*unmeasured*, sky G5).

---

## 6. Defects found by reading

Not run; each is what the code says as the inventories read it.

1. **`gpuTimer.setEnabled(false)` every frame** (RuneLightPlugin.java:1862, frame §17): the `timers` command enables it
   (:4867–4871); the next frame disables it and drops the in-flight slots (GpuTimer.java:115–116). As written `timers`
   cannot report GPU values and the hitch monitor's GPU column is always "off". One line, before any B4 measurement.
2. **`lightData` silently drops the room map on a 65,536-texel driver** (LightClusters.java:452–456, lights §4): at scene
   size 184 the room map needs 169,281 texels; rooms, room fill and the light map's plane lookup all turn off with a log
   line at most. Unverified which of the three platforms' drivers report less than ~180k.
3. **The 50° flat-face rule on trees** (VertexShading.java:84–126, scene §5; lights §13): a game-flat face takes smooth
   normals only if **all three** corner normals are within 50° of the face normal — a canopy fails per face by
   construction; double-sided leaf cards cancel to `l < 1e-6` → face normal; passing faces get one `unlitColors`
   colour (SceneUploader.java:1324–1327). To be tested on one tree first (A7), as the user stated it.
4. **The swap-frame bundle** (scene §9 :2568; HANDOVER B3): with no front map after a swap the whole static shadow map
   is drawn in one frame, with `buildMax` ×3, in the frame that already frees ~190–529 zones — plus the occlusion redraw,
   voxel handover and sky capture flagged on the same frame.
5. **Zone size counting vs upload disagree for some model** (HANDOVER crash 19:26; scene §1): `zoneSize` :102 /
   `standingSize` :517 and `uploadStaticModel` :1174's per-face routing are two functions; a `BufferOverflowException`
   in `VoxelCapture.Buffer.put22224` followed. Now guarded and logged with coordinates; the real cause is open.
6. **`GpuTimer.POINT_LIGHTS` is never begun** (GpuTimer.java:37, lights §14); `WEATHER` and `OCCLUSION` exist but are not
   in B1's row (sky E6) — the ledger has holes where three mechanisms live.
7. **`LightMapTexture.ensure` zeroes the 52 MB texture on the client thread in 24 `glTexSubImage3D`s** whenever `cells`
   changes (:62–66, lights §6) — unbudgeted; unverified whether it hitches in practice.
8. **`halfRatio` is 1.5 at render scale 75 %** (SceneTargets.java:75–78; halfmap.glsl:8–10, frame §6): the half grid
   maps to scene pixels non-integrally (`floor(h·1.5)`) — the likely visible "weird scaling".
9. **Forced driver AA silently overrides the setting** (`GL_SAMPLES` on the default FBO, RuneLightPlugin.java:1199, frame §5).
10. **`AO_DIRECT 1.0`** darkens direct light with SSAO against its own comment "physically 0" (composite_frag.glsl:48–49,
    frame §8) — occlusion counted twice with the shadow.
11. **`groundBlending` off changes terrain brightness only on blended tiles**: `GroundBlender.undoShading`'s GAIN 1.25 is
    skipped for blended vertices (:305) but still applied to unblended ones via `groundColor` (:761–772) (scene §6; unverified visually).
12. **`SurfaceMaterials.Rule.matches` allocates `int[] v` per face** on the upload hot path (:191, scene §14) — render-plan
    rule 4 (no garbage in the frame loop) for dynamic models.
13. **Edge fade applied twice to a transparent pixel**: frag.glsl:168 and again by the composite's sky replacement of the
    opaque behind (frame §2).
14. **The water-shaded target rides on `WATER_NOISE_UNIT`** (RuneLightPlugin.java:3433–3446, frame §12) — works only
    because no program samples noise and shading together.
15. **`SceneTargets.check()` throws per frame on an incomplete FBO** and `draw` catches and logs it every frame (:523–530,
    :4014–4021, frame §1).
16. **Two `capFogCover` caps on two fog amounts don't add up to one cap** when both fogs are present (sky B3).
17. **`inverse(rgb2lms)` computed per pixel** (colorblind.glsl:38) at two sites (frame §3, §15).
18. **The teleport "one frame really sharp" pop** (HANDOVER B3 fix 2; user 19:22): every pass ran identically in frames
    1–5, so it is a pass's *content*; cause unknown; `::runelight swapcapture` is set to diff it. The pipeline must swap
    derived data atomically.
19. **The sky-cache capture after a pause (`STALE_MILLIS` 1 s) lands on the frame after a load** (SkyCache.java:203, sky C3).
20. **`screenshot` is a synchronous `glReadPixels` of the whole window** per `processDrawComplete` consumer (RuneLightPlugin.java:4162–4195,
    frame §20) — per event, but it stalls the pipeline (render-plan rule 3).
21. **`LookMonitor.frame` does not early-out** when not at DEBUG (scene §16; unverified whether the caller gates it).
22. Stale text: HitchMonitor.java:133 names a setting that no longer exists; WallMap.java:14 names a shader that no longer
    exists; skylight_ao.glsl:6 names `skylight_lighting.glsl`; `showBuildings`' description (RuneLightConfig.java:970–973).
23. **Settings count**: HANDOVER says 69; `grep -c keyName RuneLightConfig.java` gives **67** (all three inventories and
    this count agree) — HANDOVER's figure is the stale one.

---

## 7. Segment order

Each segment is one mechanism, under a day, with `::runelight snapshot 10 uncapped` at the two fixed views (and a
40 s snapshot across a region change for the hitch segments) before and after, captures for anything that touches the
look, and a HANDOVER row — or it is reverted (rule 1). Hitches first, then cost, then structure. The user's statement is
tested first wherever one exists (S4, S16). Rendering decisions are judged at 25–50 % on a Windows GPU as well as the
Mac (rule 4). Settings only go down, and only with the user (rule 5; S24).

**Hitches**

- **S0 — The ledger.** Delete the per-frame `setEnabled(false)` (§6.1); begin `POINT_LIGHTS`, `WEATHER`, `OCCLUSION`,
  `UPSCALE`, `LIGHT_MAP`, `SKY_GRID` where they run; one EMA for both timers. Re-measure B1 at an explicit `renderScale=FULL`
  and at 50 (and once at 200). Half a day. Everything below is measured against this.
- **S1 — One scheduler for scene-derived work.** The jobs the hitch monitor already names (static shadow build, voxel
  handover and `ModelBlockers`, sky capture, occlusion redraw, light-map `ensure`, room texels, fog ground, sky-grid
  upload) become budgeted jobs of one scheduler: one heavy job per frame, ≤ 1 ms client-thread time (render-plan rule 2);
  the swap frame does the client's swap and nothing else; first frames draw with the stale map / defaults. Measure the
  load frame (37 ms today).
- **S2 — Zone buffers.** `Zone.free`/`unmap` over following frames or a grow-only pool instead of delete/create
  (`glDeleteBuffers` in the B3 stacks); one load routine shared by `loadScene`/`loadSubScene`/`rebuild`. Measure a teleport
  (77–95 ms today).
- **S3 — The uploads under a byte budget.** `SkyGridMap.upload`, `LightMapTexture.ensure`, `LightClusters` room map,
  `FogGround.sendGround` sliced from S1's scheduler; `WallMap.build` → a door-event diff on a worker (every game tick today).
  Measure door open/close and a walk across a scene.
- **S4 — The overflow crash.** Find the model from the logged coordinates (test the log's statement first), make zone
  size counting and upload **one function** (§6.5). Measured by the log staying silent across the three teleports.
- **S5 — The sharp-frame pop.** `swapcapture` pixel diff of frames 1–4; make the swap of derived data atomic (no frame
  with a layer missing). Capture before/after.
- **S6 — Area crossing without a reload.** Merge `AreaHiding` + `RegionManager` into one list and one setting; apply as
  a per-zone draw mask so zones are reusable and `setGameState(LOADING)` is not ours (§5.2). Measure a boundary crossing.
- **S7 — Zone rebuild per frame.** At most one zone per frame (or GL part sliced); redraw only that zone's region of the
  static shadow map, not the whole map (scene §17). Measure a farming-patch / door tick.

**Cost (the per-pixel chain — B4's critical path)**

- **S8 — One AA path.** Remove MSAA (§4.C); render scale + Catmull-Rom is the supersampling; `halfRatio` ∈ {1, 2}.
  Measure the chain at FULL and 50; captures at 25–50 % on Windows for the dither (A5).
- **S9 — Composite structure.** Parts path and `lightingEnabled` out; per-frame UBO (camera UBO merged); one `upsampleHalf`;
  `skyBehind` once per pixel; debug views → `dev/debug_frag.glsl`, no production recompile. Measure composite.
- **S10 — The half-res hub.** Per-quantity gate; `AO_DIRECT 0`; `aoQuality`/`aoRadius` → constants (8 samples, measure);
  one `dither(px, frame)` with a temporal term for AO/shadow/fog/sky/clouds, judged at 25–50 % on Windows before it stays;
  denoise 13 taps if the captures say the grain is set by the upscale. Measure AO + filter.
- **S11 — One fog.** σ(x) field; `envFogAmount`/`envFog`/`envSky` removed; analytic tail **fitted to today's `envFogAmount`
  curve** so the look is kept (captures at both views, dawn and noon); `capFogCover` once; `EdgeFade.fogginess` reads σ;
  `fogQuality` → constant. Measure fog.
- **S12 — Shadows as one mechanism (B6).** Lumina's static cache spread over frames (including the first after a swap),
  one dynamic map, receiver at full res in the lighting pass with min/max tiles; copy pass, `buildMax`, held bolt map,
  PCSS-in-AO, `SHADOW_CACHE` variant gone; lightning re-plugged with a one-frame re-render budget. Measure shadow + AO.
- **S13 — Waves out.** The §K unit as one commit (§4.A); captures of a river and the sea before/after; measure `WATER`
  at a sea view (unmeasured today). Flow map untouched pending the discussion.
- **S14 — Model upload (B5).** One `uploadFaces` for static/dynamic/sorted; per-model normal cache; fix the `Rule.matches`
  allocation; measure `client scene`, `BUFFER_MAP` and `draw` CPU before deciding the worker ring's fate.
- **S15 — Weather cost.** Occlusion map → `VoxelGrid.top`; the 30 player-only field channels eased once at the player;
  the field's own WET/SNOW easing out. Measure `WEATHER`/`OCCLUSION` in rain.

**Structure and look (Track A, one mechanism each)**

- **S16 — Trees (A7, then A3 part 1).** Test the user's statement first: capture one tree, count which of its faces fail
  the 50° gate and why (§6.3). Then one normal rule: `computeNormals` for every face, flat only for 117 HD's flat-lit list
  (import into `materials.json`); `pointLightNormal` removed so lamps shade with the same normal as the sun. Captures of
  the same tree, day and under a lamp.
- **S17 — One vertex-colour rule.** `undoBake(colour, normal)` with 117 HD's constants + the terrain gain (1.25, cap 55)
  as its one terrain branch; `GroundBlender` blending removed (never blend between tiles); `unlitColors` swap gone; bed
  colour = the tile's. Captures of terrain seams and a shore.
- **S18 — One ambient line.** `skyIrradiance(n) × skyVisibility × envAmbient × ao` + `RoomLight`; analytic sky light as
  fallback only; `daylightFill`, room daylight floor, `RoomAmbient` shaping and the second night curve out; `RoomLight`'s
  score from the CPU light list. Captures of an interior, a doorway, open ground at dusk — look kept.
- **S19 — Rooms as one texture.** Room/storey/walls/open-roof out of `lightData` and `FogGround` into one 2D texture with
  one GLSL lookup (`roomPlaneAt` three times → once) and one CPU twin; removes the 65,536-texel risk (§6.2).
- **S20 — The voxel grid prototype (render-plan §3.6a).** Face bits per voxel, R8 3D texture per zone slice on the voxel
  worker; lamp occlusion by a 6–8 step march in `shadeSurface`; skylight by flood over the grid into the same texture.
  Gate: per-zone build time and the leak check at a lamp-lit interior, the palisade and a lamp on a post. If it leaks,
  the BVH alternative replaces it as a whole.
- **S21 — Remove the light map family.** `LightMap*`, `LightBlockers`, `ModelBlockers`, `light_map.glsl`, `SkyField*`,
  the scene dump and their tests (§4.F); fog glow one path; one lamp include for composite/fog/weather/water. Measure the
  lamp share (S0's timer) before and after.
- **S22 — Small ones.** UI scaler one mode; colorblind once over the final frame; `upscaleSharpness` constant; `pace off`
  path out; dead code of §4.E; stale text of §6.22.
- **S23 — Packaging moves.** The §3 table as pure moves and renames, no behaviour change; `ShaderCompileCheck`-style
  compile of every program as the test.
- **S24 — Settings.** The §8 list, with the user. Then HANDOVER's A2 (117 HD terrain/object rules), A4 (fog at 4K / 50 %
  on Windows), A6 (dawn/dusk warmth) in their order.

---

## 8. Settings

`RuneLightConfig.java` has **67** `keyName`s (counted; HANDOVER's 69 is stale). Proposed after the merges: **37** (35 kept
+ 2 merged replacements, `shadowQuality` and `nightSky`, which need the user's yes under rule 5 since they are new keys
replacing four). Development keys are listed apart.

**Kept (35):** `renderScale`, `drawDistance`, `expandedMapLoadingChunks`, `unlockFps`, `vsyncMode`, `fpsTarget`,
`sunLighting`, `buildingLightBrightness` (`interiorBrightness()`), `pointLights`, `shadows`, `ambientOcclusion`,
`proceduralSky`, `dayCycleMinutes`, `fixedHour`, `clouds`, `cloudShadows`, `aurora`, `weatherMode`, `weatherIntensity`,
`weatherSounds`, `water`, `volumetricFog`, `volumetricFogDensity`, `areaEnvironments`, `windSway`, `numThreads`,
`removeVertexSnapping`, `hideOtherAreas` (now the one hide list), `colorBlindMode`, `colorBlindIntensity`; development:
`glErrorChecks`, `debugView` (absorbs `waterDebugView` and the skylight/light-cluster views), `testLight`, `strikeLightning`,
`showBuildings`.

**Merged (4 → 2):** `shadowDistance` + `shadowResolution` → `shadowQuality` (scene settings: "fold into one quality");
`starBrightness` + `nebulaStrength` → `nightSky` (sky C4).

**Removed (32), with the reason:**

| Key | Reason | Inventory |
|---|---|---|
| `antiAliasingMode` | one AA path (render scale + upscaler); MSAA removed | frame §5 |
| `upscaleSharpness` | constant (40 % is the default) | frame §6 |
| `uiScalingMode` | one UI resample mode; removes three shader files and a recompile | frame §15 |
| `anisotropicFilteringLevel` | fixed | scene settings |
| `emissiveStrength`, `transmissionStrength` | fixed material constants | scene settings; frame §4 |
| `shadowDistance`, `shadowResolution` | → `shadowQuality` | scene settings |
| `aoStrength`, `aoRadius`, `aoQuality` | constants; `ambientOcclusion` is the one toggle | frame §8 |
| `removeBakedShading` | always on, as 117 HD | scene §6 |
| `groundBlending` | blending removed (never blend terrain colours between tiles) | scene §7 |
| `smoothBanding`, `brightTextures` | two settings, one look | scene §6 |
| `clearWeatherClouds`, `cloudSpeed`, `skyHorizonAtEdge` | constants / variant dropped | sky C5, J |
| `starBrightness`, `nebulaStrength` | → `nightSky` | sky C4 |
| `weatherBuildup`, `weatherLightning` | constants (weather is the one setting) | sky L |
| `waterReflections`, `waterCaustics` | constant on (or off, by measurement); one `water` setting | sky G6–G7 |
| `fogQuality` | steps decided by B4, constant | sky F1 |
| `windSwayStrength`, `windSwayShadows`, `windSwayFps` | one `windSway` toggle; shadows belong to B6, fps to pacing | sky E10; scene §15 |
| `hideUnrelatedMaps` | merged into the one hide list | scene §13 |
| `waterDebugView` | → `debugView` | sky J |
| `weatherFieldMap` | dead: no reader | frame §20; sky J |
| `dumpScene` | a command, and its fixture dies with the light map | scene settings; lights §14 |

Not touched by any inventory and kept as is: `sunLighting`, `numThreads`, `removeVertexSnapping`, `colorBlind*`.
