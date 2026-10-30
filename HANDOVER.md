# Lumina handover (2026-10-30)

Lumina is a RuneLite GPU renderer plugin: RuneLite's stock GPU plugin (1.13.4, pinned in build.gradle) with 117 HD's shading
data on top and Lumina's own sky, fog, interiors and shadows. Branch `lumina`. Old code: `fork-117hd` (a stripped 117 HD
fork, reference only), `perf-wip` / `hd-baseline` (the old RuneLight renderer, a parts bin).

## Run and compare
- `./gradlew run` starts a dev client with the plugin (developer mode). Turn off the GPU and 117 HD plugins in the profile:
  `~/.runelite/profiles2/<profile>.properties` keys `runelite.gpuplugin`, `runelite.hdplugin`, `runelite.luminaplugin`.
- Upstream 117 HD reference for pixel comparisons: a worktree of `upstream-117hd/master` (on the Windows PC at
  `C:\Users\Midona\source\rlhd-ref`; on the Mac: `git worktree add ../rlhd-ref upstream-117hd/master`) with the same
  `cam`/`capture`/`dump` commands patched into `rs117.hd.utils.DeveloperTools` (uncommitted there; see the
  Windows worktree or redo: FrameCapture + CommandFile copied from `dev/`). Settings must match the user's 117 HD:
  `tools/mirror_profile.py <profile> <keys...>` copies `hd.<key>` to `lumina.<key>`.
- Captures go to `~/.runelite/lumina/captures/<name>.png` (+ .txt with camera and location). `tools/compare_captures.py a.png b.png out.jpg`
  prints the per-region difference and writes a side-by-side with a diff image. Moving creatures and chat text always differ.
- Developer commands (developer mode only), typed as `::lumina <cmd>` or written one per line to `~/.runelite/lumina/command.txt`
  (polled every game tick, so a tool can drive the client without typing in it):
  `cam <yaw> <pitch> <zoom>`, `capture <name>`, `debug albedo|light|normal|skyvis|shadow|off`, `dump` (every lighting value,
  both lighting sources, the area look), `time <hour|run>`, `source lumina|hd117`, `climate` (forecast under the player),
  `snapshot [seconds]` (per-frame CPU/GPU timings to `~/.runelite/lumina/snapshots/*.csv`).
- Unit tests: `./gradlew test` (sky, area looks, weather rules, sky cover, unlit terrain, face shading, draw list, materials).

## Structure (package com.midonarhel.lumina; each package has a package-info.java)
- `LuminaPlugin`: lifecycle, config wiring, DrawCallbacks delegation only.
- `frame/`: the render path. `DrawList` records every scene draw during the client's callbacks and `FrameRenderer` replays
  them at the top-level post-scene callback (`// ===== SHADOW PASS HOOK =====` runs before the replay). Timers, GL resources,
  render threads, the sky pass.
- `scene/`: scene loading and zone upload. `scene/{areas,environment,materials,overrides,sky,terrain}` hold 117 HD's data
  classes and JSON verbatim (diffable against upstream `rs117.hd`); `scene/terrain/SkyCover` bakes interior sky visibility;
  `scene/terrain/UnlitTerrainColors` recomputes the game's unlit tile colours.
- `shading/`: `DiffuseColors` + `FaceShading`: the one place a colour or normal is decided. Settings: Shading mode
  (117 HD's four) and Colours (117 HD / Vanilla), Ground-matched foliage.
- `lighting/`: `Lighting` is what the shaders read; `LightingSource` fills it: `SkyLighting` (117 HD's values) or
  `sky/SkySource` (Lumina's). Setting "Lighting source".
- `sky/`: GameClock, Celestial (sun/moon, caster clamp 30-68°, dead-band handoff), SkyModel (old RuneLight palette on the
  CPU), SkyLight (L1 SH sky fill + ground bounce), AreaLook (`sky/area_looks.json`), SkySource.
- `weather/`: climates, patterns, climate regions and the deterministic Forecast (not yet wired into rendering).
- `shadow/`: in progress (see below).
- `dev/`: the developer commands, capture, snapshot, command file. `config/`, `util/`.

## Rules settled with the user (see also the memory notes in the Windows session)
- Two looks: Colours "117 HD" (117 HD's colours and geometry overrides) and "Vanilla" (the game's colours and geometry,
  baked shading removed exactly). Both lit by Lumina with smooth normals. Ground-matched foliage is independent.
- Never smooth/blend terrain tile colours. Match the user's 117 HD settings (hd.* keys in their profile), never hardcode
  a 117 HD config value: port the setting.
- Sky owns the colour; fog = the sky horizon in the view direction; 117 HD's fog data is dropped. Only the Morytania
  group tints the sky (`sky/area_looks.json`). Caves: no sky, own dark fog, sun 0. Weather supplies the atmosphere.
- Day: 24-minute default; sun up 04:00-20:00, dusk/dawn the hour before 20:00/04:00; night to -35°.
- Weather: real-world analogues (docs/climate-map.md); fewer patterns; Wilderness never clear, never snow; deserts >= 80% clear;
  Lumbridge never snows; ash fall in volcanic regions.
- No 117 HD images (only lava_flow_map.png). Sun light is diffuse only (no specular); leaf transmission allowed.
- GL 3.3 core / macOS 4.1; Windows, macOS, Linux. Every step: implement, code review, in-game check, commit.

## State of each piece
| Piece | Commit | In-game check |
|---|---|---|
| 117 HD shading match (pixel-identical at Auburnvale) | 2f046d6c | done |
| Restructure | 59291190 | done (identical captures) |
| Sky and day cycle | 48723401, 9138aba3 | done at Auburnvale (hours 12/19/20/0/3/4) |
| Fog = sky horizon, area looks, Morytania tint, cave fog | 294fdf1f | dawn/noon/sunset at Auburnvale; Morytania and caves NOT seen |
| Interior sky visibility (+ transposition fix) | d9510127, 5bc8f650 | open ground only; buildings NOT checked (Lumbridge castle, a house, under a bridge) |
| Texture fallbacks, 117 HD images dropped | 1d5d7769 | NOT checked (webs, Fortis roads, infernal cape) |
| Weather data + forecast | 0e92f0e3 | n/a (not wired) |
| Frame record/replay | 353880dd | identical capture verified; boats/instances NOT checked |
| Vanilla colour mode, ground-matched foliage | 5bc8f650 | NOT checked (Lumbridge vs the stock GPU plugin; the self-check log line) |
| Shadows (fit, cached static map, dynamic map, PCF) | in progress | - |

## Next segments, in order
1. Shadows: finish S6 (static cache + dynamic map + PCF), check in game, then PCSS with min/max tiles (docs/sky-and-shadows-design.md).
2. Weather hook-up: WeatherField + WeatherState feeding SkyModifiers and the fog (docs/weather-design.md steps 3-8).
3. Area ambience spatial blending (old commit 061bcf4a), stars, clouds.
4. Point lights with wall occlusion (old LightClusters/LightMap ideas), SSAO (needs a depth prepass), water.
5. Open: Lumina walls are 0.61x 117 HD's by design of the 68° clamp; GPU-mode textured base 127 vs 117 HD's 90 is the
   brightness knob for textured floors; zone rebuilds still on the client thread.
