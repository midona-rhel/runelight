# The offline renderer

A frame of the live client, dumped whole, drawn again with no client and no window by the plugin's own code — to
look at it, change a setting or the hour, turn on a debug view, and check it automatically, as often as needed.

Nothing of the renderer is reimplemented. The offline renderer makes a `LuminaPlugin`, hands it the dump's client as
fakes, restores the dump's GPU data through the plugin's own classes and plays the frame's draw callbacks through it.
The frame is the plugin's frame: the same upload code, the same shaders, the same passes.

## Taking a dump (in the client)

```
::runelight scenedump <name>
```

(or the line `scenedump <name>` in `~/.runelite/lumina/command-perfwip.txt` in developer mode). The next frame is
recorded and, after it is presented, written to `~/.runelite/lumina/dumps/<name>/`:

| file | what | how |
|---|---|---|
| `manifest.json` | the renderer's clock at the frame (`common/Clock`: millis, nanos), the plugin's start, the game clock, the camera (yaw, pitch, scale, focal point, position), canvas, viewport, stretch, DPI scale, sky colour, expanded map loading, game state and cycle, texture brightness, plane, base, instance, the player's local and world position, sun/moon/caster direction, every setting by key, the world views, the frame state's numbers, what the plain data left out | `SceneDump.describe` |
| `zones.bin.gz` | every drawn zone of every world view: its vertices read back from its buffers (`glGetBufferSubData`), level offsets, roof ranges, sway ranges, static transparent models with their packed faces | `scene/ZoneSnapshot`, streamed zone by zone |
| `dynamic.bin.gz` | the frame tape: every draw callback in order (`preSceneDraw`, the zones drawn, the passes, `postSceneDraw`, `draw`) with its arguments, and every dynamic and temporary model past its filters as `ModelData` with its material, placement and projection | `dev/FrameTape` |
| `derived.bin.gz` | what the scene's loads and workers derived, as plain data: the voxels (`SceneVoxels`), the water map, the fog ground, the buildings (result, coarse, rooms, walls, floors), the frame's lamps and the steady lamps; the scene's and world views' own arrays (every no-argument getter that gives primitives); the frame state a warm-up can't settle (`SceneDump.STATE`) | `dev/PlainData` (reflective, shared references and cycles kept) |
| `textures.bin.gz` | the game's textures at brightness 1 as the texture array is made from them, their animations, the brightness | `SceneDump.textures` |
| `ui.bin.gz` | the interface's pixels that frame | `SceneDump.ui` |
| `reference.png`, `.txt` | the frame as presented | `FrameCapture` |

A dump of a full scene is tens to hundreds of MB; the zones are most of it. Taking one is a hitch of a second or two
(the zones are read back and written on the client thread, the rest on a worker).

## Drawing a dump (headless)

```
./gradlew renderDump -Pdump=<name>                       # a name under ~/.runelite/lumina/dumps, or a directory
./gradlew renderDump -Pdump=<name> -Pargs="hour=21 views=all"
./gradlew renderDump -Pdump=<name> -Pargs="yaw=1024 pitch=300 shadows=false fogQuality=HIGH"
./gradlew renderDump -Pdump=synthetic                    # the dump made in code (no client needed)
```

Writes to `<dump>/offline/` (or `out=dir`): `final.png`, `view-<name>.png` for the debug views (default NORMALS,
SKYLIGHT, LIGHTING, SHADOWS, AMBIENT_OCCLUSION, DEPTH; `views=all`, `views=none`, or a list), and `diff.png` with the
numbers against `reference.png`. Options: `hour=h` (holds the hour: dayLength FIXED, fixedHour h), `yaw=`, `pitch=`,
`scale=` (the camera; with yaw or pitch changed the whole scene's zones are drawn, as the client only drew those in its
frustum; dynamic models are the client's), `warmup=s` and `step=s` (seconds of frames before the dump's time, default 6
and 0.05), `time=n` (draws the dump's frame n more times and prints the GPU time per frame, GL_TIME_ELAPSED
around the whole frame, and the CPU time of its draw calls: median and least), `snowCover=c` (the weather field's snow
on the ground set to c everywhere; with `weatherMode=OVERCAST` or another weather that neither snows nor takes the
climate's ground, so it stays), `weatherState=off` (the dump's weather state is not put back: a weather given by
`weatherMode` draws as itself), and any setting as `key=value`.

### How it works (`src/test/java/com/midonarhel/lumina/harness/`)

1. **GL**: `HeadlessGl` makes a CGL context of the 4.1 core profile — the client's own on the Mac — with no window;
   the plugin draws into a framebuffer of the renderer's (`LuminaPlugin.offscreenFramebuffer`, read by
   `windowFramebuffer()` wherever the frame used the window's) at the dump's DPI scale (`offscreenTransform`,
   read by `dpiTransform()`). Only macOS: Windows and Linux would need a hidden GLFW window (not on the classpath).
2. **The client**: `DumpWorld` fakes `Client`, `WorldView`, `Scene`, `Player`, `TextureProvider`, `BufferProvider`,
   `Preferences` and `LuminaConfig` with `java.lang.reflect.Proxy` (`Fakes`), answering from the manifest and the
   recorded getters. A method not answered throws, naming itself.
3. **The plugin**: `initState()` and `initGlObjects()` — `startUp`'s parts that need neither the client nor its
   canvas — then the restore: zones into buffers of the loader's own pool (`ZoneSnapshot.restore`), the top level
   through `SceneLoader.restore` and `swap` (the water and fog ground jobs, the static shadows, as a load), sub world
   views through `restoreSub`; `VoxelBuilder.restore`, `BuildingLights.restore`, `SceneLights.replay` (the lamps as the
   client had them; there are no game objects to place them on).
4. **Time**: `common/Clock` is the renderer's time for what it draws. The renderer sets it frame by frame from
   `warmup` seconds before the dump's time to the dump's time, playing the tape each frame, so what eases (layers, the
   area environment, the room light, the sky cache, the shadow cache, the sky grid's tracing) settles as it had in the
   client. What a few seconds can't settle — the clouds' and the water's drift, the weather's wetness, snow, eased
   cover, fog and light, the wind's phase, the weather field — is put back from the dump (`SceneDump.STATE`) before
   the warm-up and again before the last frame. Lightning's random is seeded.
5. **The frame**: the tape played through `preSceneDraw`, `drawZoneOpaque/Alpha`, `drawPass`,
   `SceneDraw.drawDynamicModel/drawTempModel`, `postSceneDraw`, `draw` — the composite, the interface, the jobs.

### Not reproduced

- Lamps placed after the dump's frame, flicker beyond the frame (the lamps are the frame's).
- Ripples of walking actors in the wet (`WadeSteps`: no actors in the dump).
- A lightning strike in progress at the dump (strikes follow the real time, Lightning.due, and are not restored).
- Partial eases: a layer still easing in at the dump's frame is drawn eased in fully.
- Zone voxels for rebuilds (the scene's `SceneVoxels` is restored; zones carry no voxel capture).
- The interface is the dump's: with the camera overridden, the client's overlays (tile markers, names) stay where they
  were.

The lamps: `lamps.collected` is what `SceneLights.collect` gave, without the test light (`testLight` setting), which
`LampFrame.collectLights` adds after it in the replay as in the client. The first dumps (`lampsWithoutTestLight` false)
held it at the end of the list; `SceneDump.load` takes it off — it was drawn twice, a warm pool round the player.

The camera's units are read off the dump (`DumpRenderer.unitsPerTurn`): this client gives 16384 a turn.

## The first live dump (2026-11-01, `first`, 0d56ae85; 36 MB, 0.7 s to collect)

- Against `reference.png` (2350 × 1810): mean 0.80, 4.80 % of pixels off by more than 8 with the test light twice;
  **mean 0.06, max 35, 0.00 % (107 pixels) off by more than 8** without. What is left is single pixels at edges.
- Camera orbit and units: the client's.
- Lamps behind walls: 299,868 pixels behind walls, 1 lit (rise max 7); 288,405 of 776,969 in the open lit.
- Skylight steps: 6,071 in 5,417,066 open ground pairs (0.112 %), **4,694 of them on tile edges** — under the test's 0.2 %,
  but most are where tiles meet (`offline-test/skylight-steps.png`).

## Checks (`harness/Checks`)

| check | what it measures |
|---|---|
| `diff(a, b)` | mean and max channel difference, share of pixels off by more than 8; `within(mean, over8)` |
| `flatness(normals, region)` | mean and max angle of a NORMALS view region's normals from their mean |
| `lampLeak(on, off, probe, lamps, walls, plane, threshold)` | with the lamps on against off, the pixels where every lamp in reach is cut off by `WallMap.blocked` from its line origin (as `lamp.glsl`): how many rose; and that the lamps light the open |
| `skylightSteps(skylight, normals, probe, step)` | neighbouring open-ground pixels (normal up, worked out, near each other in the world) whose sky visibility differs by more than `step`; how many lie on tile edges |

`FrameProbe` reads the scene's depth back and gives each pixel's world position (as `frame.glsl`'s
`reconstructPosition`); it needs one sample (`antiAliasingMode=DISABLED`).

## Tests

- `OfflineRenderSyntheticTest`: the synthetic dump (ground zones in a checkerboard, a box) draws, the same every time,
  the same from files.
- `SceneDumpRoundTripTest`: the client's writer driven by an offline frame: the dump it writes draws that frame again
  and its reference is that frame.
- `OfflineChecksSyntheticTest`: flat ground; a wall and a lamp — the lamp lights its side and nothing behind the wall.
- `ChecksTest`: each check on images made in code.
- `OfflineRenderDumpTest` (opt-in): a live dump against its reference and the checks;
  `./gradlew test --tests '*OfflineRenderDumpTest*' -Dlumina.dump=<name>` (tolerances `-Dlumina.mean=3
  -Dlumina.over8=0.05`). Writes `<dump>/offline-test/`.
- `PlainDataTest`, `PlainDataRoomsTest`, `FrameTapeTest`, `ZoneSnapshotTest`, `SceneDumpSettingsTest`: the formats.

## Changes in the plugin for it

All behaviour-neutral in the client: `common/Clock` in place of `System.currentTimeMillis/nanoTime` where time is
drawn (budgets and timers keep the system's); `windowFramebuffer()` and `dpiTransform()`; `initState()` and
`initGlObjects()` out of `startUp`; `SceneDraw.drawDynamicModel/drawTempModel` (the draws past their filters);
`SceneLoader.restore/restoreSub/jobsPending` and the load's water and fog ground jobs as `queueDerived`;
`VoxelBuilder.restore`, `BuildingLights.restore/getCoarse`, `SceneLights.replay`; `ModelData.copyOf/write/read`; the
swap skipped and the screenshot read from the offscreen framebuffer when there is one; the frame tape's hooks in the
draw callbacks (a null check each when no dump is armed).
