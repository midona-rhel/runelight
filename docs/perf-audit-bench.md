# Performance audit on the bench (2026-11-02, branch claude/perf-audit-5f8819)

Frame and load cost of Lumina measured offline on scene dumps, without the client: what each pass costs at the user's
settings, what was cut, and what is left for other sessions (docs/perf-audit-findings.md).

## The instrument

- `dev/GpuSections`: a GL_TIME_ELAPSED query per section of the frame (scene setup, geometry, alpha resolve, shadows,
  weather occlusion, UI upload, water surface, AO/skylight/shadow mask, sky cache and lighting prep, fog, denoise,
  clouds and water at half resolution, composite, MSAA mark, MSAA edges, weather, upscale, UI draw). Off in the
  client (one static read per mark). `serial` begins each section on an idle GPU (glFinish at each boundary): Apple's
  tiler otherwise runs passes side by side and splits a query's time between them, down to negative times.
- `./gradlew passCost -Pargs="dumps=… hours=12,21 frames=40 [rounds=N variants=A:k=v;B:shaders=DIR] [serial=true]
  [sections=false] [render=DIR] [out=FILE]"` (harness PassCost): per dump and hour, the passes' min and median, the
  CPU steps (CpuTimer), the callbacks' submit and the wall time to glFinish. Variants are switched in one renderer in
  alternating rounds (paired per-round deltas); `shaders=DIR` draws with another tree's shaders, so a shader change is
  compared against its parent in the same process. `-Pjvm="-XX:StartFlightRecording=…"` for a CPU profile.
- `./gradlew imageDiff -Pargs="BEFORE AFTER DIFF"` (harness ImageDiff): two directories of `render=` PNGs, pixel by
  pixel. Renders are deterministic: two runs of the same build differ in 0 pixels.

The user's settings, as every dump holds them: render scale 50 % of a 783×603-point canvas at Retina 2×
(1566×1206 device pixels, the scene drawn at 783×603), MSAA ×4, fog HIGH, AO HIGH, shadows 2048. Dumps: nightref,
snow1, tree1, water1, water2 (first and stall are an old format the bench no longer reads).

Noise: the user's live client and other sessions' tests share the GPU. Medians of the same build swing by 2× between
runs, so the tables take each pass's median in the least-contended of three alternating runs, and changes to shaders
were also measured in-process against their parent (paired rounds). Whole-frame wall time (no timers, pipelined)
stayed within the noise: minimums −0.74 to +0.46 ms, medians lower in 8 of 10 dumps and hours.

## The frame by pass, before → after (GPU ms, serial)

Base 0c79fac9 (the instrument alone) against d491eb8d; per pass the median of the least-contended of 3 runs of 40
frames each, ranked by the mean share before. Hour 12 and 21 (hour 21 with the dump's own lamps: see the findings).

| rank | GPU pass | nightref 12h | nightref 21h | snow1 12h | snow1 21h | tree1 12h | tree1 21h | water1 12h | water1 21h | water2 12h | water2 21h | mean share before |
|---|---|---|---|---|---|---|---|---|---|---|---|---:|
| 1 | AO, skylight, shadow mask | 1.30 → 0.98 | 1.31 → 1.13 | 1.26 → 1.02 | 1.30 → 1.16 | 1.26 → 1.16 | 1.31 → 1.09 | 1.28 → 1.00 | 1.34 → 1.05 | 1.42 → 1.04 | 1.39 → 1.17 | 18 % |
| 2 | geometry | 2.58 → 2.39 | 2.43 → 2.44 | 0.97 → 1.02 | 0.87 → 1.25 | 0.63 → 0.64 | 0.61 → 0.60 | 1.53 → 1.50 | 1.52 → 1.36 | 0.92 → 0.85 | 0.87 → 0.85 | 17 % |
| 3 | composite | 1.01 → 1.01 | 1.04 → 1.10 | 0.88 → 0.92 | 0.87 → 0.97 | 1.15 → 1.19 | 1.14 → 1.17 | 1.29 → 1.21 | 1.25 → 1.25 | 0.96 → 0.95 | 1.00 → 1.01 | 14 % |
| 4 | fog | 0.83 → 0.79 | 0.82 → 0.81 | 0.74 → 0.82 | 0.75 → 0.81 | 1.58 → 1.93 | 1.59 → 1.62 | 1.26 → 1.19 | 1.24 → 1.20 | 0.84 → 0.80 | 0.81 → 0.89 | 14 % |
| 5 | composite edges (MSAA) | 0.95 → 0.78 | 1.00 → 0.68 | 0.71 → 0.44 | 0.71 → 0.44 | 0.71 → 0.41 | 0.71 → 0.37 | 0.86 → 0.50 | 0.89 → 0.51 | 0.83 → 0.41 | 0.77 → 0.44 | 11 % |
| 6 | denoise | 0.59 → 0.55 | 0.56 → 0.57 | 0.50 → 0.53 | 0.51 → 0.63 | 0.51 → 0.65 | 0.52 → 0.55 | 0.55 → 0.49 | 0.53 → 0.49 | 0.54 → 0.53 | 0.55 → 0.68 | 7 % |
| 7 | weather | 0.00 → 0.00 | 0.00 → 0.00 | 0.09 → 0.11 | 0.08 → 0.10 | 0.62 → 0.65 | 0.62 → 0.62 | 0.59 → 0.55 | 0.62 → 0.56 | 0.99 → 0.78 | 0.69 → 0.66 | 6 % |
| 8 | composite mark (MSAA) | 0.34 → 0.00 | 0.38 → 0.00 | 0.29 → 0.00 | 0.31 → 0.00 | 0.30 → 0.00 | 0.30 → 0.00 | 0.34 → 0.00 | 0.32 → 0.00 | 0.42 → 0.00 | 0.35 → 0.00 | 5 % |
| 9 | UI draw | 0.29 → 0.26 | 0.28 → 0.19 | 0.19 → 0.19 | 0.17 → 0.19 | 0.17 → 0.18 | 0.19 → 0.18 | 0.19 → 0.17 | 0.20 → 0.19 | 0.29 → 0.18 | 0.19 → 0.19 | 3 % |
| 10 | upscale | 0.32 → 0.20 | 0.26 → 0.30 | 0.14 → 0.17 | 0.13 → 0.16 | 0.13 → 0.14 | 0.14 → 0.15 | 0.16 → 0.13 | 0.16 → 0.15 | 0.17 → 0.16 | 0.16 → 0.14 | 2 % |
| 11 | shadows (dynamic casters over the static cache's copy) | 0.17 → 0.16 | 0.12 → 0.11 | 0.08 → 0.10 | 0.08 → 0.10 | 0.09 → 0.09 | 0.08 → 0.09 | 0.11 → 0.09 | 0.11 → 0.12 | 0.11 → 0.09 | 0.12 → 0.11 | 1 % |
| 12 | clouds, water shading (half) | 0.13 → 0.12 | 0.13 → 0.11 | 0.07 → 0.09 | 0.07 → 0.15 | 0.06 → 0.08 | 0.06 → 0.07 | 0.16 → 0.15 | 0.17 → 0.14 | 0.10 → 0.10 | 0.10 → 0.09 | 1 % |
| 13 | water surface | 0.06 → 0.08 | 0.05 → 0.01 | 0.00 → 0.00 | 0.00 → 0.01 | 0.00 → 0.00 | 0.00 → 0.00 | 0.00 → 0.00 | 0.03 → 0.00 | 0.00 → 0.00 | 0.00 → 0.04 | 0 % |
| | **sum of the passes** | **8.56 → 7.31** | **8.38 → 7.45** | **5.92 → 5.42** | **5.86 → 5.99** | **7.19 → 7.13** | **7.27 → 6.50** | **8.32 → 6.98** | **8.39 → 7.04** | **7.60 → 5.88** | **6.99 → 6.26** | |

Read the rows the commits touch (AO, composite, mark, edges); the others are the same code on both sides and show the
noise (snow1 21h's geometry, tree1 12h's fog). Below the timers' resolution (0.00): scene setup and skybox, alpha
resolve, weather occlusion, UI upload, sky cache and lighting prep.

What the fog and composite rows hold at night: the lamps (fog's glow up to 1.1 ms in tree1), see the findings.

## The CPU per frame

Bench, no GPU timers (they make the CPU wait), pipelined: the callbacks' submit is 1.3–2.8 ms (min) per frame, of which
the plugin's timed steps (environment, weather, light collection and clusters, shadow submit, UI upload, buffer map,
light upload, voxels, sky capture, occlusion maps, sky grid) are all under 0.1 ms each. A flight recording of 1000
frames (tree1, nightref): ~42 % of the submit is `ModelUploader.uploadSortedModel`'s per-face work (normals, vanilla
shading undone, materials) on the dynamic models with transparency, which the bench replays on one thread and the
client spreads over its 3 render threads; the rest is the driver (`glDrawArrays` waiting in the composite). Nothing
there worth a cut on the client thread.

## The load (from the user's client.log, 2026-11-02 09:24 and 09:36; the bench cannot run a load)

| stage | ms | thread |
|---|---|---|
| zone upload | 361 / 413 | loader + 3 helpers |
| water map (`WaterMap.build`) | 72 / 83 | loader, serial (water's) |
| zone sizes | 66 / 73 | loader, serial (fixed on perf-track's bebb0203, not on lumina) |
| load→swap | 542 / 613 | |
| voxel build: light blockers / voxelize / assemble | 163 / 55 / 12 (09:36) | voxels worker pool (light blockers: lights') |
| skylight field / rooms (CpuCostProbe, skygrid dumps) | 5.4–11.8 / 3.0–3.5 | sky field worker |

## The cuts (each its own commit, pixels proven on all five dumps at 12h and 21h)

1. **32d84830 Composite: the MSAA mark pass folded into the full pass.** The edge pass needs the pixels with a second
   surface; a separate stencil pass ran the same per-sample test over every pixel the full pass had just run. Below
   100 % render scale the upscaler's target has a depth buffer: the full pass writes 1 there where it found a second
   surface, and the edge pass is depth tested GL_LESS against it. Mark + edges 1.00–1.38 → edges 0.37–0.78 ms; the
   full composite +0.00 to +0.10. **About −0.6 ms a frame.** 0 pixels differ.
2. **32a86198 Skylight: one read where lo == hi.** Below the lowest height of its storey (the ground, floors) a point
   reads one height, mixed with itself by 0: the grid's level and surface reads were made twice. AO pass −0.08 to
   −0.22 ms (paired, in-process). 0 pixels differ (final and SKYLIGHT view).
3. **d491eb8d Skylight: the surface's reads with the level's.** `skySurfaceLevel` at the lower and upper heights read
   exactly the texels `skyGridLevel` had just read; one loop gives both. AO pass −0.11 to −0.21 ms (paired,
   in-process): the pass is fetch-bound. 0 pixels differ in the final image; the SKYLIGHT view differs at 4 pixels by
   1 level in water1 (the weights summed in another order).

Together: the AO pass −0.2 to −0.4 ms, the MSAA edges −0.6 ms: **≈ −0.8 to −1.0 ms of a 6–8.5 ms GPU frame**.

## Left on the table (mine, not done)

- The edge pass still shades each second surface with the whole composite; its pixels scatter over most SIMD groups
  in foliage. Compacting them, or a cheaper second-surface shading, is a visual change.
- The edge pass re-fetches the second surface's sample (`sceneSample(px, other)`) it read in its test loop: 3–4
  fetches per edge pixel, small.
- Geometry at nightref (2.4–2.6 ms) is the largest pass there; the bench cannot separate the vertex from the fragment
  share without a render-scale sweep.
