# Performance audit on the bench: findings for other sessions' areas (2026-11-02, branch claude/perf-audit-5f8819)

Measured offline with `./gradlew passCost` (harness PassCost: per-pass GL_TIME_ELAPSED timers, dev/GpuSections; serial
mode begins each pass on an idle GPU) on the dumps nightref, snow1, tree1, water1, water2 at the user's settings
(render scale 50 % of a 1566×1206 Retina canvas, MSAA ×4, fog HIGH, AO HIGH, shadows 2048), hours 12 and 21. The GPU
is shared with the user's live client and other sessions' tests, so medians swing by 2×; the numbers here are
minimums over many frames and runs, and switches (a setting off) are compared in the same process, in alternating
rounds.

The lamps: the bench replays the lamps the dump collected, and every dump was taken at night (hour 20), so the lamp
numbers are the night's cost, whatever hour the bench holds.

## Lights (light/, lamp.glsl, the fog's lamp glow)

Lamps switched off in the same renderer (pointLights=false, 8 alternating rounds × 10 frames, serial; the fog pass's
minimum, and the paired per-round change of each pass):

| dump, hour | fog pass min, lamps on → off | fog Δ | composite Δ | MSAA edges Δ |
|---|---|---|---|---|
| tree1 21 | 1.542 → 0.459 | −0.98 | −0.51 | −0.12 |
| tree1 12 | 1.457 → 0.509 | −1.27 | −0.74 | −0.38 |
| water1 21 | 1.139 → 0.314 | −0.59 | −0.51 | +0.04 |
| water1 12 | 1.042 → 0.559 | −0.98 | −0.58 | −0.35 |
| snow1 21 | 0.599 → 0.372 | −0.27 | −0.19 | −0.58 |
| nightref 21 | 0.622 → 0.600 | −0.03 | −0.13 | +0.08 |
| water2 21 | 0.727 → 0.278 | ±0 | −0.13 | −0.05 |

**The fog's lamp glow (`lampGlow`, fog_frag.glsl, with lamp.glsl's wall walk) is up to two thirds of the fog pass**
(tree1: ~1.0–1.1 ms of 1.5), the largest single cost on the bench that is not the base shading. With the composite's
lamp loop the lamps cost 1.5–3 ms of tree1's and water1's GPU frame at night.

Light blockers (`ModelBlockers.rasterize`, the voxel build's first step, on its worker pool): **163 ms** on the
user's cold load at 09:36:27 (voxelize 55, assemble 12; total 230 ms), from client.log; not reproducible on the
bench (dumps carry no voxel captures).

Suggestions:
- `lampGlow`: each of the 4 samples per lamp per ray walks the lamp's walls tile by tile (`lampOpen` →
  `lampWallsShareIn`, up to LAMP_WALL_STEPS texel fetches each). Take the wall share at 2 points (the ends of the
  in-reach segment, or its nearest point and one jittered) and interpolate the falloff between them, or walk the
  walls once per lamp per ray from the segment's middle; or draw the glow at quarter resolution (it is soft and
  denoised anyway). Any of these is a visual change to prove on nightref/tree1 at 21.
- Composite and MSAA edges: the per-pixel lamp loop costs ≈0.5 + 0.35 ms at night in tree1. The edge pass shades
  each second surface with the full lamp loop; second surfaces cover ~8 % of tree1's pixels but scatter over most
  SIMD groups.
- Light blockers: 225 zones, 163 ms in the pool. Skip triangles of no object (owner 0: the ground) if the light map
  doesn't need them, reject a triangle's cells by its bounding box against the storeys' bins before clipping, and
  reuse a zone's blockers across loads (it already keeps `z.modelBlockers` per zone; check they survive reuse).

## Weather and clouds (weather/, clouds.glsl, weather_*.glsl)

| what | measured |
|---|---|
| weather pass (`Weather.draw`: the precipitation layers) | **0.55–0.65 ms** min on tree1 and water1 (both hours, the dump's weather restored); 0 on nightref |
| clouds (half-res march + the composite's cloud shadow) | clouds=false changes no pass beyond noise (≤ 0.2 ms either way): cheap |
| weather occlusion map (CPU) | ≤ 0.001 ms a frame when not redrawn |

## Water (water shaders, WaterMap)

| what | measured |
|---|---|
| water surface pass | ≤ 0.15 ms (often below the timers' resolution) |
| water's half-res shading (in the clouds' pass) | water=false: −0.10 to −0.26 ms on every dump |
| water map at load (`WaterMap.build`, loader thread, serial before the zone sizes and upload) | **72–83 ms** per cold load (user's client 09:24, 09:36), ~13 % of load→swap |

Suggestion: the water map is on the load's critical path, single-threaded, and both the sizes and the upload need it
(SceneLoader: "needed to size and upload the zones"). Split `WaterMap.build` by region chunk over the loader and the
three upload threads (the zones' own pattern), or let the sizes of zones with no water start while it builds.

## Scene load (not mine either: perf-track's)

- Zone sizes: 65–73 ms serial per cold load in today's client. perf-track's **bebb0203** (sizes on the upload
  threads) fixes exactly this and is not on lumina yet.
