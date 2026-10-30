# The scene-load pipeline (S1–S3, 2026-10-30)

One load routine, one scheduler, pooled zone buffers. Code: `SceneLoader`, `SceneJobs`, `ZonePool`, `ZoneUploads`,
`SceneLoadReport` (in the root package for now — S23's packaging moves them to `scene/` with the rest, as pure moves).

## How a load runs

| When | Thread | What |
|---|---|---|
| `loadScene` | map loader | `SceneLoader.load`: reuse old zones where possible (unchanged rules), water map, zone sizes; the fog ground and the water's surface mesh beside them on an upload thread |
| during the upload | map loader + up to 3 "RuneLight zone upload" threads | each zone written into its mapped buffer, one `SceneUploader` per thread |
| alongside | client thread, ≤ 1 ms a frame (`SceneJobs`) | `ZoneUploads`: buffers taken from `ZonePool` and mapped ahead of the writers, unmapped behind them |
| end of the load | map loader | `SceneDerived` record built; the fog ground's texels packed (without rooms); the voxel build of the new scene started (`VoxelBuilder.buildNext`); its static shadow map and the fog ground's upload queued |
| between the load and the swap | client thread, scheduler (LOAD jobs get 4 ms a frame while the loading box is up) | the new scene's static shadow map drawn (all of it in one frame), held; the fog ground uploaded into the fog's second texture; the water's surface tiles, data and contact into their second buffer and textures |
| `swapScene` | client thread | `SceneLoader.swap`: old zones' buffers back to the pool (no GL call), new zones in, the record's parts handed over: fog ground (texture flip), static shadow map (index flip; if not drawn yet, the swap draws what is left), water (buffer and texture flip), voxels (if built, else when built) |
| after | worker "RuneLight buildings" | rooms and walls (`BuildingLights`, was 3–18 ms on the swap frame); doors: a compare of the collision flags each tick, the rebuild on the worker only on a change |
| after | scheduler | zone buffers the pool keeps beyond the scene in use (≤ 256 MiB) deleted, a few a frame |

Every GL job a load leaves runs from the one queue (`SceneJobs.run`, after the frame is presented), in priority order
(the load's, then what the next frame shows, then upkeep), within 1 ms of client-thread time a frame; its time is
counted in the next frame by the load report and flagged "scene jobs" by the hitch monitor.

## What arrives after a swap, and how it eases in (`LayerEase`, 0.3 s smoothstep, ≤ 8 % of a change a frame at 60 FPS)

- **Skylight grid**: traced from the new voxels after the swap (its rings are centred on the player, who is at the old
  place until the swap); a new grid eases in over the one before it, or over open sky where that does not cover the
  scene (a teleport: open sky until the new place is traced).
- **Lamps**: a light the light map takes over eases from the clusters' light into the map's; a light the clusters hid
  fades in. (The lights session replaces the light map; the rule stays.)
- **Rooms**: built on the "RuneLight buildings" worker after the swap (they need the collision maps; whether those are
  the new scene's at `loadScene` time is untested); the fog's room part eases in. As before, rooms are cleared while
  the client loads.
- **Water**: uploaded before the swap and flipped with it (97160de6); nothing of it arrives later.

## Measuring (the same on Windows and macOS)

The client must run with developer mode (the command file is read only then). Commands go into
`~/.runelite/lumina/command-perfwip.txt` (Windows: `%USERPROFILE%\.runelite\lumina\command-perfwip.txt`), one per
write, or typed in chat as `::runelight <command>`.

1. Log in, stand anywhere, wait 10 s.
2. Type `::runelight swapcapture 30` in chat (30 frames span the eases; the grabs disturb that load's timings).
3. Teleport (a spell or a jewellery teleport to a different region). Wait 5 s.
4. Teleport twice more to other regions, ~10 s apart.
5. Send the log file (and say whether the game window was in front the whole time: macOS, and possibly Windows,
   throttle an unshown window's presents to about once a second, which shows as ~1 s frames of "swap wait"): `~/.runelite/logs/client.log` (Windows: `%USERPROFILE%\.runelite\logs\client.log`), and the eight
   `swap-k*.png` files from `~/.runelite/lumina/captures/`.

What to read in the log, one line per load (`Scene load report:`):

- `load→swap X = our loadScene Y (stages) + the client's until its swap Z` — what a teleport costs, split into ours
  (by stage: reuse, water map, sizes, upload, …) and the client's.
- `pre-swap client work … in N steps` — our client-thread GL work for the upload, spread over N frames.
- `swap frame X ms, ours Y` — the frame the swap ran in and our part of it (the rest is the client's own scene build
  and other plugins).
- `GL buffers +a -b, vertex arrays +c -d (pool hits …, respecified …, created …, trimmed …)` — GL objects made and
  deleted from the load's start to 2 s after the swap, and how the zone pool served the load.
- `upload threads: … wait … write …; where the writers were: …` — each upload thread's time waiting for mapped buffers
  and writing, and the stack sampler's most frequent places.
- `next 2 s: … K over 25 ms (+frame:ms/ours …)` — the slow frames after the swap and our time in each.

The pixel check: `uv run --with pillow --with numpy tools/swap_diff.py <captures dir>` prints, per captured frame,
the share of pixels that differ by more than 8 levels from the frame before and from the steady state. A pop is a frame
after the swap (`-s1`, `-s2`, …) that differs from the steady state where the scene itself did not change.

## Numbers

See HANDOVER's S1–S3 row. On Windows the questions are: is `upload` (writing into mapped GL buffers) the bulk of
`load→swap` there too, how many pool `created` per teleport once warm, and whether `swap frame … ours` stays under
~10 ms.
