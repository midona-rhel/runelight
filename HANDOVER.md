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

## Order
B1 → B2 → A1 (the mechanism table) → B3 → A3 → B4 → A5 → B6 → B5 → B7 → A2 → A4 → A6. Each segment's row goes
in the table below with its numbers; a segment may be reordered when a measurement says so, never skipped.

## State
| Segment | Commit | Checked |
|---|---|---|
| Tools: `::runelight cam/capture/set/snapshot`, command file `~/.runelite/lumina/command-perfwip.txt` | 18b6402a | snapshots at yaw 0 / 1024: 6.96 / 6.98 ms median uncapped, 144 FPS, 1–3 frames > 1.5× median per 10 s |
