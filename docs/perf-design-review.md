# Design review: is each pass built the cheapest way? (2026-11-02)

The audit so far cut what could be cut with no pixel change (docs/perf-audit-bench.md). This review asks the next
question for each costly pass: is the design itself the cheapest way to get this picture? Each answer comes with an
**upper bound measured on the bench**: the expensive part stubbed out in a scratch copy of the shaders, compared in
the same renderer in 10 alternating rounds of 8 frames (PassCost `variants=…:shaders=DIR`), serial timers, the
pass's minimum and the paired per-round change. A redesign can win at most that, less what its replacement costs.
None of the prototypes is meant to ship; they only size the prize. Load average 3.4–6.

## Ranked

| # | pass and design today | what it costs | upper bound measured | better design | expected gain | pixels | whose files |
|---|---|---|---|---|---|---|---|
| 1 | **Lamps**: every lit pixel walks each lamp's walls tile by tile (lampOpen → lampWallsShareIn, up to LAMP_WALL_STEPS fetches); the fog's glow does it 4 times per lamp per ray | night: tree1 1.92 ms, water1 1.26 ms of the frame (lamps off) | walls stubbed (lampOpen = 1): tree1 −1.62 (fog −0.70, composite −0.22, edges −0.12), water1 −0.74 (fog −0.54, composite −0.30); nightref ±noise | the walls don't move: bake, per lamp, which tiles its light reaches (and the wall-top heights for the air) into a small texture when the lamps or walls change, on the CPU; a pixel reads one texel instead of walking | **0.6–1.5 ms at night** in lamp-heavy views | small: the bake's tile resolution against today's exact line walk; needs proof at doorways | lights |
| 2 | **Fog**: a 12-step march per half-resolution pixel, every frame, each step reading ground, weather field, 3D noise and the shadow map | 0.38–0.55 ms (lamps off) | 1 step instead of 12: −0.40 to −0.57 (the march is nearly all of it) | a froxel grid (a small 3D volume over the view, e.g. 160×90×64, lit once a frame and integrated front to back), looked up per pixel; or the same march at quarter resolution with the depth-aware upsample it already has | **0.3–0.45 ms**, and it stops growing with resolution | changes: soft by nature, to be proven | fog (idle); the glow inside it is the lights' |
| 3 | **Skylight** (in the AO pass): ~30 3D texture reads a pixel to rebuild values from the grid's corners, levels and roofs every frame | 0.24–0.45 ms of the AO pass | skylight stubbed (visibility = 1): −0.24 tree1, −0.24 water1, −0.45 nightref | bake per floor, when the grid changes, the reconstructed visibility into a 2D map at the corners' resolution (and the cover/field terms), so a pixel reads 1–2 texels; the normal-direction weighting stays per pixel | **0.15–0.35 ms** | small: the bake's resolution; to be proven on the doorway and diagonal dumps | skylight (idle) |
| 4 | **G-buffer under MSAA**: color, normal, material and depth at 4 samples, all written and read back (the composite reads ~16 multisample texels a pixel) | geometry's per-pixel share and the composite's reads; MSAA ×4 costs 1.5–2.5 ms over none | not stubbable without the redesign | pack normal (oct, 2×10 bits) and material into one RGBA target: three color targets become two | estimate 0.1–0.3 ms | none if packed losslessly | touches every shader that reads sceneMaterial (water, weather) |
| 5 | **Shadow map**: each frame copies the 2048² static cache into the front map, then draws the moving models | shadows section 0.08–0.17 ms | — | moving casters in their own small map, both sampled | ≤ 0.1 ms | none | shadows (pcss sessions) |
| 6 | **CPU, models**: dynamic models redo their per-face work (normals, vanilla shading undone, materials) every frame; the load repeats it for every copy of a model | ~1 ms of a bench frame on one thread (3 threads in the client); load: upload 360–413 ms | not measurable offline (the dumps hold no models) | cache per model and orientation what doesn't change between frames and copies | load time and client-thread headroom; not GPU | none | scene (free) |

Not worth a redesign: the upscale (0.1–0.2 ms), the UI draw (0.15–0.2), clouds (below noise), the water surface
(≤ 0.15), the denoise (0.35–0.5: already one pass of 21 taps that replaced two; fewer taps is a visual trade).

## Recommendation

1. **Lamps first** (biggest, at night, where the user plays): needs the lights session or its OK to work in its files.
2. **Skylight bake** and **fog grid** next: both in idle sessions' files, ~0.3–0.8 ms together, both visual changes to
   prove with crops on all dumps.
3. G-buffer packing after the water and weather sessions settle, since it touches their shaders.
4. Model caching when load time matters more than frame time.
