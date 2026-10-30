# Lumen — the old RuneLight renderer as the base (branch `lumen`, from `perf-wip` + the reference tools)

Decided 2026-10-30, evening. The look of `perf-wip` (its shading, sky, fog, tuning) is the base and is kept; the
work is to make it perform, not hitch, and run well on Windows, macOS and Linux, and to add what it lacks from the
Lumina work and from 117 HD, feature by feature. `lumina` stays as a parts bin (its shadows, pacing, culling,
tools, docs). Measured 2026-10-30, same client, same views, uncapped, small canvas: this renderer 6.96 ms median
(flat, p5–p95 6.3–7.4), Lumina 2.89 ms; worst frames 16 vs 11 ms (docs/old-renderer-vs-lumina.md §6).

## Rules (binding)
1. **Two tracks, one segment at a time.** Track A (look: keep, fix, add) and Track B (performance and hitches)
   alternate; a segment is one mechanism, under a day, with a before/after measurement (`::runelight snapshot 10
   uncapped` at the two fixed views; captures with `::runelight capture`) and a HANDOVER row, or it is reverted —
   not patched over.
2. **One mechanism per quantity** (render-plan.md §2 rule 8). This renderer stacks several for ambient, fog and
   lamps; a segment that touches a quantity leaves exactly one.
3. **The user's statements about what a thing is are tested first**, before any diagnosis of our own (the black
   rock faces, 2026-10-30: model faces the game colours near black — the user said so; a session spent half an
   hour re-deriving it and misread an overlay on the way).
4. **Three platforms.** Every per-pixel choice (dither, upscaler, AO noise, fog jitter) is judged at render scale
   25–50 % on a Windows GPU as well as on the Mac; macOS GL 4.1 is the floor (no compute, no GL 4.5). Nothing lands
   that looks good only dithered at Retina density.
5. **No new settings** without the user; the 69 here come down, not up.
6. Never push; commit per segment; the client is the user's (restarts allowed, camera moves only for approved
   capture passes, clock released after use).

## Track A — look
- A1 **Keep** the shading, sky palette, fog stack, clouds, aurora gating, flat-face rule, edge circle as they are.
  Document each quantity's mechanism in a table before touching it.
- A2 **117 HD's terrain and object rules**: ground-matched foliage; flat-lit object flags; the geometry overrides
  the user likes. Data and per-face upload rules, not shading changes.
- A3 **Point lights**: already here (clusters, light map, rooms, blockers). Make them shade with the smooth mesh
  normals (`VertexShading.computeNormals` output, as the sun does), confirm the occlusion path works and where it
  leaks; later the voxel-grid occlusion of render-plan.md §3.6a replaces blockers/rooms/light map as one mechanism.
- A4 **Volumetric fog**: already here (`fog_frag.glsl`, `FogGround`). Verify it is on, how it reads at 4K and at
  render scale 50 on Windows; cost in B4.
- A5 **Render scale and the upscaler**: the setting exists; the dither (`ign.glsl`, 4 shaders) and the Catmull-Rom
  upscale must look right at 25–50 % on Windows — blue-noise or temporal dither, depth-aware upscale of the
  half-res passes; measured by captures on both platforms.
- A6 Dawn/dusk warm toward the sun only (render-plan §5 of the light design); later.

## Track B — performance and hitches
- B1 **Where the 7 ms go**: per-pass GPU timers (`GpuTimer`, `RuneLightOverlay`) read at the two fixed views and at
  render scale 200 (≈ 1.6× 4K pixels); CPU per step (`CpuTimer`); the hitch monitor's job list (static shadows,
  zone rebuild, scene swap, shader reload, area hiding, voxel build, sky capture) timed at a scene load. Written
  down before anything is cut.
- B2 **Frame pacing**: Lumina's `paceFrame` (sleep then spin on a fixed grid; the client gets no target).
- B3 **Hitches**: each hitch-monitor job sliced to a per-frame budget or moved to a worker (scene swap, zone
  rebuild, static shadow rebuild, voxel build, sky capture); first frame draws with defaults, bakes refine.
- B4 **The per-pixel chain**: composite early-outs that fire, half-res passes that stay half-res end to end, the
  denoise and upsample costs, AO early-out (open ground 0.94–0.97 defeats a ≥ 0.995 test); target the chain under
  Lumina's forward cost at 4K or know exactly why not.
- B5 **Model caching and uploads**: RuneLite/117 HD-style zone VAOs and cached dynamic models against this
  renderer's `TempModels` workers; measured by `client_scene` and `draw` CPU, not assumed.
- B6 **Shadows**: Lumina's cached static map spread over frames, dynamic map, PCSS with min/max tiles, cutout and
  alpha casters — port as one mechanism replacing the current static-copy + PCSS-in-AO path.
- B7 **Zone frustum culling** (Lumina's `ZBUF_ZONE_FRUSTUM_CHECK` implementation).

## The user's list (2026-10-30, 19:05), as segments
- **Map load is super heavy and things behave weirdly after a load** → B3, measured first: a `snapshot` spanning a
  region change with the hitch monitor's job names and durations in the CSV, so each hitch is attributed (static
  shadow rebuild, zone rebuild, scene swap, voxel build, sky capture, light map, area hiding, GC).
- **Render scale must work** (and look right at 25–50 % on Windows) → A5, also the MSAA: "weird scaling for the
  MSAA" → B8: how MSAA interacts with render scale and the half-res passes; one AA path, not two.
- **Hard-edged shading on trees** → A7: the smooth-normal path (`VertexShading.computeNormals`) is not reaching
  trees (or the 50° rule excludes them); find which, fix as one rule, before/after capture of one tree.
- **Inefficiencies all over** → B4 (the chain), B5 (uploads/caching), with B1's numbers as the ledger.

## Order
B1 → B2 → A1 (the mechanism table) → B3 → A3 → B4 → A5 → B6 → B5 → B7 → A2 → A4 → A6. Each segment's row goes
in the table below with its numbers; a segment may be reordered when a measurement says so, never skipped.

## State
| Segment | Commit | Checked |
|---|---|---|
| B1 where the 7 ms go (`::runelight timers`, uncapped, canvas 783×603 ≈ 3.4 Mpx device, gpuPassTimings on) | 860fdd14 | GPU per pass, smoothed, yaw 1024 / yaw 0: volumetric fog 1.62 / 1.47 · sky+fog+final blend (composite) 1.38 / 1.45 · AO+skylight 1.04 / 0.91 · AO/fog filtering 0.59 / 0.52 · shadow map 0.64 / **1.80** · scene 0.46 / 0.91 · sky cache 0.36 / 0.05 · clouds 0.16 / 0.18 · water 0.07 / 0.11 → per-pixel chain (fog + composite + AO + filter + clouds) ≈ **4.8 ms of the 6.96**. CPU per frame: client scene 0.7–0.9, client UI 1.4 (not ours), light upload 0.25, shadow submit 0.14, outside 0.4. At render scale 25 %: frame 3.95 ms median (248 FPS): fog 0.32, composite 0.40, AO 0.14, filter 0.03 — the chain is pixel-bound; what remains (~3.7 ms) is the CPU floor + scene + shadow map. Projection to 4K (4× pixels): chain ≈ 19 ms → B4 is the critical path; the shadow map's 1.8 ms at yaw 0 (static copy + models, view-dependent) is B6's. **Caveat**: the render scale in force during the 18:43 baseline and the first two timers lines is not recorded; the profile held `renderScale=PERCENT_50` afterwards, so those numbers may be at half scale (then the chain at 4K is ~4× worse than projected). The snapshot header and the timers line now record it; re-measure at an explicit FULL before B4 |
| B2 frame pacing (Lumina's pacer; `::runelight pace on|off` to A/B) | f3b26e6b | capped at 60, same view, 10 s each: client limiter p5/p50/p95/max 15.0/16.4/19.5/23.2 ms, 69 frames over 18.5 ms, stdev 1.35; pacer 16.66/16.67/16.67/16.70, 0 over 18.5, stdev 0.00 |
| B3 measured: map load (40 s snapshot at 60 FPS, the user walked into a new area; hitch monitor on) | 4cf18904 | 6 frames over 25 ms: **186 ms** (frame 636), then 71, 31, 40 ms in the next second; 65 + 29 ms at a second boundary. The 186 ms frame: 172 ms untimed on the client thread, jobs that frame = static shadow rebuild + scene swap + voxel build + sky capture, GC 7 ms; watchdog stacks at 41/46/51 ms all inside the game client's own scene load (`client.iz/ip`, `ei.gx` Arrays.fill, EventBus.post) — the client's swap itself is most of it, and all four of our jobs land on the same frame. The 65 ms frame: static shadow rebuild + scene swap + voxel build; stacks in our `WallMap.diff` and in `glEndQuery` (a GPU stall: the whole static shadow map and the voxel upload in one frame). Fix plan: one heavy job per frame with a budget (a scheduler, not four jobs on the swap frame); static shadow map built over frames (Lumina's); voxel/sky-grid upload sliced; sky capture deferred; WallMap/BuildingDetector/RoomMap on a worker; the client's own swap stays |
| B3 attributed (second run, three region loads in 40 s, swapScene timed) | 25dec660 | each load: one frame of 49–53 ms (no 186 this time: the first run's giant frame was the client's own full scene build). `swapScene` itself 3.5–4.5 ms (zone free/roofs 2.5–3.3 ms for ~190 freed zones, unmap 0.6–0.8 ms, modules 0.5). The 34–39 ms 'untimed' remainder, by watchdog stacks at 35/40/45 ms on every load: **`RoomMap.texels` / `RoomAmbient.pack` / `RoomMap.pool` inside `LightClusters.upload` (prepareLighting)** and **`LightMap.update` → `LightMapTexture.send` → `glTexSubImage3D` (collectLights)** — the room-light texel packing and the light-map 3D texture upload, on the client thread, on the swap frame. Jobs flagged the same frame: static shadow rebuild start, voxel build, zone rebuild. Fix targets, in order: room texels and the light-map upload off the swap frame (worker / sliced blocks); then one heavy job per frame |
| B3 fix 1 measured (room texels on a worker, light-map sends throttled) | 7193e68a | two region loads in 40 s: the load frame **49–53 → 37 ms** (36.8 / 36.9), nothing else over 20 ms; swapScene 3.3–3.7 ms, buildings analysis 2–4 ms. The remaining ~20 ms untimed, by stacks: load 1 `RoomLight.followWalls` (prepareLighting → updateRoomLight), load 2 `FogGround.texels` (VolumetricFog.sendGround). **Pattern**: every subsystem repacks its per-scene texture synchronously on the swap frame (room texels, light map, room light, fog ground, voxels, static shadows). Restructure: ONE scene-derived-data pipeline — snapshot the scene's arrays on the client thread, build every derived texture on a worker, upload in budgeted slices from one scheduler; the swap frame does the client's swap and nothing else |
| B3 fix 2 measured (fog ground on a worker); three TELEPORTS in 40 s (529 zones freed, 0 reused each) | 36965f32 | load frames 84 / 95 / 77 ms; swapScene 6.6–9.1 ms (zone free 3.8–6.4 ms for 529 zones, unmap 2.1–2.4). No RoomMap/FogGround/LightMap in any stack any more. Stacks: the client's own scene build (`client.iz/ip/rb`, EventBus, Formatter — not ours) and **`Zone.free` → glDeleteBuffers / glDeleteVertexArrays** (ours, sampled twice). Next: free zones over following frames or pool their buffers instead of delete/create; the client's own build is the floor we cannot move. **Bug (user, 19:22)**: on every teleport one frame renders normally, one 'really sharp', then back — cause not yet known (the swap code swaps pending fog ground and water in, it does not null them); the post-swap trace (7b27ffa1) shows every pass ran identically in frames 1–5 after a teleport (ao, fog, shadows, sky, half-res, fog ground, voxels, sky cache mode all constant), so the pop is in a pass's content, not a missing pass; `::runelight swapcapture` now saves frames 1–4 after the next swap for a pixel diff; the pipeline restructure must swap derived data atomically, never a frame with a layer missing |
| Crash 19:26 on a teleport: `java.nio.BufferOverflowException` in `SceneUploader.uploadStaticModel` → `VoxelCapture.Buffer.put22224` (a zone's opaque VBO smaller than what its upload emitted), then `swapScene` NPE on `ctx.zones` (no loaded scene) → client down | 70279977 + next | Guarded: swapScene keeps the previous zones when the load failed; a zone whose upload overflows loses its tail and is logged with its coordinates. **Real cause open**: the zone size counting pass (`SceneUploader` ~488–520: per-face opaque/alpha + `standingSize`) and the upload's per-face routing disagree for some model in that scene — find the model from the log line and make counting and upload one function |
| Teleport flash (the user's shadow-map call, 19:3x): eight frames captured around a swap (`::runelight swapcapture`) showed frame +1 dim/soft (luma 103 vs 116), frame **+2 drawn black** (0.9), frame +3 over-bright (117.5), steady from +4; the trace put the static shadow front swap (finishBuild → buildMax) and the fog ground upload on frame +2 | 8efc8dd3 | buildMax bound textures on unit 0 and left it unbound; it and the fog ground upload now restore the active unit and its binding. Re-captured after: lumas 57.2 / 60.7 / 59.7 / 59.7… — no black frame, frame +2 changes only where the new shadow map and fog ground differ (12 % of pixels). One run; the user's eye is the second check |
| Teleport flash, layer 2: the skylight grid dropped out for three frames after new voxels (sky=false; captures luma 80 → 71 → 80) | d1f34495 | SkyGridMap traces the new place into a back texture while the shown grid stays in use, swaps when its rings are ready. Re-captured on a different-area teleport: pre-swap 88.7, then 120.6 / 119.4 / 124.5 / 124.1 steady — no black frame, no dropout; what remains is a +5 luma settle at frame +3 when the new voxels and fog ground land (the old place's data stands in for two frames). The atomic answer is the scene-derived-data pipeline (swap all derived data with the scene); the load frame itself was 154 ms this time (client scene build + 529 zone frees) |
| Tools: `::runelight cam/capture/set/snapshot`, command file `~/.runelite/lumina/command-perfwip.txt` | 18b6402a | snapshots at yaw 0 / 1024: 6.96 / 6.98 ms median uncapped, 144 FPS, 1–3 frames > 1.5× median per 10 s |
