# Performance audit: 117 HD's techniques against Lumina (2026-10-30)

Read-only audit, no code changed. 117 HD = upstream master at `ref-117hd-tools` (7cadf3bd, worktree `~/Code/rlhd-ref`);
Lumina = branch `lumina` (361b6ad3). Paths: `rs117/hd/...` is 117 HD, `frame/`, `scene/`, `shadow/` are under
`com/midonarhel/lumina/`. Evidence tags: **[code]** read in the source, **[log]** 117 HD's own startup log on this Mac
(`rlhd-ref/117hd.log`), **[snap]** Lumina `snapshot` CSVs in `~/.runelite/lumina/snapshots` (M5 Pro, 2026-10-30).
Nothing here was run: every number below is read from those files, and anything unmeasured says so.

## 1. Read this first: 117 HD has two renderers, and the compute one is not the current one

- 117 HD's **default is the zone renderer** (`renderer/zone/ZoneRenderer`, `legacyRenderer()` returns `false`
  [code HdPluginConfig:1216]). It has **no compute or OpenCL step at all**: CPU face sorting (`FacePrioritySorter`,
  `StaticAlphaSortingJob`) on worker threads, zone VBOs drawn with `glMultiDrawArrays`. This is the same architecture as
  Lumina's (stock GPU plugin 1.13.4 zones). 117 HD's own option text says the new renderer "generally performs better than
  the legacy renderer" and is required for sailing.
- The **compute path is `renderer/legacy/LegacyRenderer`**, behind a closed-by-default "Legacy" section. It is real and
  it does run on macOS: `computeMode = APPLE ? OPENCL : OPENGL` [code LegacyRenderer:152], OpenCL with
  `APPLEGLSharing`/CGL share group (`opengl/compute/OpenCLManager`), kernels in `resources/.../opengl/compute/*.cl`
  (`priority_render.cl` 648 lines). Platform is not the reason to skip it. Section 5 judges it on its merits.
- 117 HD on this Mac, zone renderer, logged **[log]**: `Indirect draw: false`, `Multi indirect draw: false`,
  `Storage buffers: false`, `unsupported swap interval 1, got 0`. So even upstream 117 HD runs the plain
  `glMultiDrawArrays` + `glMapBufferRange` path here, as Lumina does. Indirect draw defaults to off on Apple by config
  (`indirectDraw().get(NVIDIA_GPU && !APPLE)`, HdPlugin:613); multi-indirect needs GL 4.3 and persistent mapping needs
  `ARB_buffer_storage` (GL 4.4), neither in Apple's GL 4.1 (this last one is my inference from `false`, the code also has
  a config gate).

## 2. Where Lumina's frame goes today (bounds every "value" below)

Uncapped runs **[snap]**, CPU stages in ms, canvas 764x742 to 1131x722 (stretched to about 933x905 to 1697x1084),
1 to 3 players, no crowd, AA off, draw distance 50:

| run | frame | client_scene | client_ui | record | replay | draw | swap | gpu_scene |
|---|---|---|---|---|---|---|---|---|
| 14-03-08 | 2.62 | 0.43 | 1.11 | 0.28 | 0.47 | 0.26 | 0.14 | 1.47 |
| 13-45-54 | 3.27 | 0.73 | 1.61 | 0.45 | 0.41 | 0.23 | 0.09 | 2.26 |
| 13-43-32 | 3.42 | 0.84 | 1.53 | 0.54 | 0.40 | 0.26 | n/a | 1.77 |
| 13-56-36 | 3.67 | 0.83 | 1.73 | 0.52 | 0.46 | 0.28 | n/a | 1.99 |

(`record` and `replay` are inside `client_scene`, so they overlap it, not add. p99 frame 3.7 to 4.7 ms, worst 5.9 to
7.4 ms in all four. `gpu_scene` is a GL-on-Metal timer query that includes idle gaps, relative use only.)

What this says:
1. The frame is **CPU-bound on the client thread**: the stage times (scene + UI + draw + swap + outside) add up to
   about the frame time, while `gpu_scene` is below the frame time and runs concurrently.
2. The biggest single stage, `client_ui` (1.1 to 1.7 ms), is the client drawing its interface and RuneLite overlays.
   It is not plugin code and no 117 HD technique touches it.
3. What the plugin controls on the CPU is `record` + `replay` + `draw`: about 0.9 to 1.2 ms of a 2.6 to 3.7 ms frame.
   **No CPU-side technique can win more than that in these scenes** (about a third of the frame at the very most), and no
   GPU-side technique shows up at all until the GPU is the limit.
4. Not measured, so unknown: a crowded area (every snapshot has 1 to 3 players), the real canvas of a ~4K window (all
   snapshots are under 1200 px wide), a scene load or a zone rebuild (no snapshot spans one), the share of
   `record`/`replay` that is sorting (no timer splits it; `alpha unsorted` was not snapshotted).
5. Measurement caveat: Lumina's `checkGLErrors()` (`frame/FrameRenderer:1323`) loops on `glGetError` at about eight call
   sites per frame when the logger is at DEBUG. 117 HD's dev-run log is at DEBUG [log], and Lumina's `./gradlew run` is
   probably the same (not checked). 117 HD turns its checks off whenever its frame-timings overlay is on
   (HdPlugin:2172); Lumina does not. Worth confirming the snapshots were not taken with it on.

## 3. The table

Status: **Have** = same technique in Lumina; **Different** = same goal another way; **Absent** = Lumina lacks it;
**N/A** = not applicable to Lumina today. "Value" is for Lumina on this machine and is bounded by section 2.

### 3a. Sorting and culling

| # | 117 HD technique (where) | Lumina equivalent | Status | Value for Lumina |
|---|---|---|---|---|
| 1 | GPU/compute face sorting and tile/model passes (legacy renderer, OpenCL on macOS, `comp.glsl`/`priority_render.cl`) | none; CPU sorting as in 117 HD's own default renderer | Absent, by design | See section 5. Not worth doing. |
| 2 | Static alpha face sort: z-bucket sort of packed face centroids per alpha model, on worker threads queued in `preSceneDraw` (`StaticAlphaSortingJob`, `FacePrioritySorter.sortStaticModelFacesByDistance`); the client thread steals unfinished models; result written into a mapped EBO by `EboAlphaWriterJob` | `scene/Zone.renderAlpha`: the same bucket sort (same packed faces), on the client thread at the replay, one `glBufferData(GL_ELEMENT_ARRAY_BUFFER)` + `glDrawElements` per flush | Have (algorithm); Absent (threading, mapped EBO) | Small now. Lumina only sorts zones within `ALPHA_ZSORT_CLOSE` = 2048 units (2 zones) per frame (`frame/ZoneDrawer:63`) and draws farther alpha unsorted; 117 HD sorts every in-frustum zone. The whole replay stage is 0.40 to 0.47 ms including all GL submission, so the sort is a fraction of that. Offloading it saves at most that fraction. |
| 3 | Sort only what needs it: a temp model is sorted if it has face transparency, an override that may be transparent, a Player, a non-default render mode, or a texture with real vanilla transparency (`ModelStreamingManager.isAlphaModel`, `shouldSort`) | `frame/ZoneDrawer.drawDynamic/uploadTemp` send **any model with `getFaceTextures() != null`** through `ModelUploader.uploadSortedModel` (per-vertex projection + z/priority sort, faces split into opaque/alpha buffers) | Different (Lumina's condition is broader) | Possibly real, unmeasured. Lumina draws textured faces opaque with a cutout discard (`Material.hasTransparency` is false for every vanilla fallback, `MaterialManager:271,287`; handover: "face transparency only"), so for a textured model with no face transparency the sorted path writes the same faces to the opaque buffer and the alpha buffer stays empty. Bounded by the `record` stage (0.28 to 0.54 ms here), scales with textured NPCs and objects on screen. Cheap to test: count models taking the sorted path in a busy scene. |
| 4 | Per-face frustum culling of partly visible temp models; culled faces go only to the shadow VAO (`SceneUploader.preprocessTempModel`) | none (the client's object culling only) | Absent | Negligible. Saves vertex writes on large models only. |
| 5 | Skip off-screen temp models unless they can cast into the view (sphere vs scene frustum, light frustum, caster volume) (`ModelStreamingManager.drawTemp`) | the client's own culling; Lumina's dynamic shadow map takes this frame's drawn models only | Different | Nothing to gain: Lumina has no off-screen caster case. |
| 6 | Zone frustum culling by the plugin: `ZBUF_ZONE_FRUSTUM_CHECK` + `zoneInFrustum` with per-zone AABB (min/max Y, water depth, 4-tile padding) and a separate `inShadowFrustum` (`ZoneRenderer:1016`) | not set (`LuminaPlugin:398` flags are GPU, ZBUF, NORMALS, UNLIT, RENDER_THREADS); the client decides which zones call `drawZoneOpaque/Alpha`; `AreaHiding.isZoneHidden` hides other areas | Different (Absent for the exact AABB test) | Unknown. The stock plugin 1.13.4 also relies on the client; the client's test is closed source, so I cannot say how many zones behind or beside the camera it still submits. `ZoneBounds` (shadow/) already has zone boxes. Check first: the timings panel's "draw commands" at a fixed camera, against 117 HD's count. |
| 7 | Hide unrelated areas: zones outside the player's area are skipped (`zoneInFrustum` area test, `Scene` area hiding) | `scene/AreaHiding` (`isZoneHidden`, `isOutsideArea`), checked at record time | Have | Done. |
| 8 | Detail draw distance: animated dynamic objects beyond N tiles are not drawn, except a blacklist (`detailDistance`, default 70, `ModelOverrideManager.allowDetailCulling`) | none | Absent | Nothing at the default draw distance 50 (the cut is at 70). Matters only above 70 (Lumina allows up to 184) with many animated objects. |
| 9 | Draw distance setting (default 50, max 184), `scene.setDrawDistance` | `FrameRenderer.getDrawDistance()`, range 0 to 184 (`LuminaConfig.MAX_DISTANCE`), `scene.setDrawDistance` in `preSceneDrawToplevel`; Lumina's fog distance uses at most 90 | Have | Done. |

### 3b. Geometry caching and reuse; static versus dynamic

| # | 117 HD technique | Lumina equivalent | Status | Value |
|---|---|---|---|---|
| 10 | Static scenery/terrain in per-zone VBOs, built once per scene load, drawn per level with roof ranges coalesced (`Zone.renderOpaque`, `pushRange`) | `scene/Zone` (same code lineage): `levelOffsets`, roof `rids/roofStart/roofEnd`, `pushRange` merging, `glMultiDrawArrays` | Have | Done. |
| 11 | Reuse zones across scene loads (skip re-uploading unchanged zones; edge zones and dirty zones rebuilt) (`SceneManager.loadScene`) | `SceneLoader.loadScene`: `canReuse` needs the 8 neighbours valid; roof id remap; reuse only inside the same hidden area | Have | Done. |
| 12 | Zone upload on a job system (17 workers on this Mac [log]), one job per zone, buffers mapped on the client thread then filled by workers (`ZoneUploadJob`, `utils/jobs/JobSystem`); scene data (`ProceduralGenerator.generateSceneData`) is a parallel task too | `SceneLoader.loadScene` on the map-load thread, **zones sized and uploaded in a serial loop** (`mapUploader.uploadZone`), scene data, sky cover and unlit terrain computed serially before it | Absent (parallelism); Have (off the client thread) | Affects how long a new area takes to appear, not steady FPS. The client thread only pays the buffer-allocation callback and `swapScene`. Not measured: the debug lines "Scene upload time" / "Scene size time" (`SceneLoader:470,559`) exist but I found no log of a Lumina run. |
| 13 | Zone streaming: new zones revealed in distance order, staggered (`revealAfterTimestampMs`, `ZONE_DEFER_DIST_START`, `zoneStreaming` setting), the old zone stays until its replacement is ready | none: the whole new scene swaps at once | Absent | Smooths the swap hitch if one exists. Unmeasured (no snapshot crosses a scene load). |
| 14 | Zone invalidation (a door, a tree, a rock) rebuilt by a job while the old zone keeps drawing (`SceneManager.invalidateZone`, `WorldViewContext.processZoneSwaps`) | `SceneLoader.rebuildInvalidated`: `free` + new zone + size + map + upload **on the client thread**, once per tick | Absent | Handover lists it as known. A hitch, not an average cost; size unmeasured. |
| 15 | Dynamic models (NPCs, players, projectiles, animated objects) written per frame into per-world-view dynamic VAOs with reserved ranges so several threads can write at once (`DynamicModelVAO`, `WorldViewContext.beginDraw`) | `RenderThreads` / `VAOList`: one VAO list per render thread, `glMapBufferRange` invalidate + explicit flush (`frame/VBO`) | Have (for `drawDynamic`, which RuneLite calls from `RENDER_THREADS`, default 3) | Done. |
| 16 | Parallel temp-model processing: `drawTemp` (actors, projectiles, graphics objects) is copied into a pooled `AsyncCachedModel` and sorted/uploaded on the job system; the client thread blocks only at `ensureAsyncUploadsComplete` (`ModelStreamingManager`, `renderer/zone/AsyncCachedModel`) | `ZoneDrawer.drawTemp` uploads **synchronously on the client thread** (`rt = renderThreads.get(0)`); only `drawDynamic` is multithreaded | Absent | The only dynamic-model work Lumina does serially. Lives in `record` (0.28 to 0.54 ms with 1 to 3 players), grows with visible NPCs. Unmeasured in a crowd. 117 HD pays a model copy to hand the work over. |
| 17 | Pooled arrays and objects so the frame allocates nothing (`PooledArrayType`, `ConcurrentPool`) | `DrawList` documents zero allocation after warm-up (parallel primitive arrays, `float[16]` pool); `ModelUploader` reuses fixed arrays | Have | Done. |
| 18 | `ModelCache`/`ModelHasher`: skip re-uploading unchanged models | not used in 117 HD's zone renderer either (only `renderer/legacy`) | N/A | None. |

### 3c. Buffers, uploads, draw submission

| # | 117 HD technique | Lumina equivalent | Status | Value |
|---|---|---|---|---|
| 19 | Persistent mapped streaming buffers (`GLMappedBuffer`, `STORAGE_PERSISTENT`, needs `ARB_buffer_storage`); staging buffer + GPU copy for the dynamic VAO | `glMapBufferRange` with invalidate + `glFlushMappedBufferRange` | Absent, not available on the GL 4.1 target | Not available on the M5 (117 HD logs `Storage buffers: false` here). Windows/Linux only, and 117 HD gates it behind a setting. Skip. |
| 20 | Indirect and multi-indirect draw with a command buffer (`CommandBuffer.DrawArraysIndirect`, `MultiDrawArraysIndirect`, `SUPPORTS_INDIRECT_DRAW`) | `glMultiDrawArrays` per zone | Absent, off by default on Apple even upstream | 117 HD itself defaults it off on Apple [log]; multi-indirect needs GL 4.3. Skip on the Mac target. |
| 21 | Recorded `CommandBuffer` + `RenderState` that skips redundant GL state (`utils/RenderState`, `GLState`) | `frame/DrawList` records and replays, GL calls direct; per-zone `glUniform3i(base)` + `glBindVertexArray` + multidraw | Different | Small. The replay is 0.40 to 0.47 ms in total and each zone needs its own bind and offset anyway. |
| 22 | Per-zone metadata VBO (world view index, scene offset) as instanced attributes instead of a uniform per zone (`Zone.vboM`) | `glUniform3i(u.base)` per zone | Different | Negligible. |
| 23 | Vertex format: 28 byte zone vertex + a 36 byte per-face texel buffer (`textureFaces`, so about 40 bytes per vertex) | 36 byte vertex carrying the face's three alpha/bias/HSL triples (`scene/Zone.VERT_SIZE`), no texel buffer; 40 byte dynamic vertex | Different (Lumina's static data is smaller and has no texel fetch) | None to gain. |
| 24 | UI texture upload through three PBOs, the pixel copy done on a worker (`AsyncUICopy`, `pboUi[frame % 3]`), overlapping the scene submit | `FrameRenderer.prepareInterfaceTexture`: orphan + `glMapBuffer` + synchronous `put` on the client thread | Absent | Low. `draw` is 0.23 to 0.28 ms in total here (copy + blit + UI draw). 117 HD can overlap the copy because it submits the scene inside `draw()`, after kicking the copy; Lumina replays at the post-scene callback on purpose, so the GPU works while the client draws its UI, and there is nothing left to overlap the copy with. Grows with canvas pixels (not measured at 4K). |
| 25 | Frame fence, 3 frames in flight (`FRAMES_IN_FLIGHT`, `glFenceSync`, `waitForFrameFence` with timeout and `glFinish` fallback) | none; Lumina's own `paceFrame` sleeps/spins to a fixed deadline grid when uncapped | Absent | Latency control, not throughput. On GL-on-Metal `swapBuffers` already blocks on the drawable. Skip. |
| 26 | GL error checks off unless debug (`SKIP_GL_ERROR_CHECKS`, off when the frame-timings overlay is on) | `checkGLErrors()` returns unless DEBUG logging | Have (see the caveat in 2.5) | Confirm the dev client's log level when measuring. |

### 3d. Textures, uniforms, shaders, framebuffers

| # | 117 HD technique | Lumina equivalent | Status | Value |
|---|---|---|---|---|
| 27 | One texture array with mip chain via `glTexStorage3D`, anisotropic filtering setting (`scene/TextureManager`) | `frame/TextureManager`: `glTexStorage3D`, `glGenerateMipmap`, `setAnisotropicFilteringLevel`; 128 px vanilla layers only | Have | Done. Smaller than 117 HD's 256 px / 358 layers array. |
| 28 | Uniform buffers (global, lights, materials, world views, water, compute) with dirty-range upload (`opengl/uniforms/UniformBuffer`) | a main UBO + materials UBO, uploaded when material slots change, the rest as ~25 `glUniform*` per frame (`FrameRenderer:780-840`, `LightingUniforms`) | Different | Negligible. |
| 29 | Shader features compiled out with `#define`s (shadow mode, tiled lighting, normal/parallax mapping, wind, cycle...) (`HdPlugin:974-1015`) | few defines; per-fragment uniform branches on `debugView`, `vanillaColors`, `skyEnabled` (`frag.glsl`) | Different | Small. Uniform branches are cheap, and Lumina has already dropped most of 117 HD's branches (no normal/parallax/specular/water). The debug view could be a define. |
| 30 | No geometry shader in the zone path (the legacy path needs one) | none | Have | Done. |
| 31 | MSAA scene FBO + resolve; scene render-resolution scale ("Game resolution") with a filtered upscale (`updateSceneFbo`, `sceneResolutionScale`) | `initFbo` with MSAA; no resolution scale, but the render size follows RuneLite's stretched mode (`stretched` canvas in the snapshots) | Different | GPU-side only. The frame is CPU-bound at these canvases (section 2), so no gain until a 4K canvas makes `gpu_scene` the limit. Not measured at 4K. |
| 32 | Reverse-Z depth (`GL_GEQUAL`, far = 0) | same (`FrameRenderer:849`, clear depth 0) | Have | Done. |
| 33 | Alpha drawn as colour pass without depth writes, then a depth-only pass for later opaque geometry | `Zone.renderAlpha(..., depthOnly)` via `SceneExecutor.zoneAlpha` | Have | Done. The extra pass costs draw calls; Lumina's `alpha nodepth` switch exists to test it. |

### 3e. Shadows (cost controls)

| # | 117 HD technique | Lumina equivalent | Status | Value |
|---|---|---|---|---|
| 34 | **Redraws the whole shadow map every frame** (`directionalShadowPass`), cost bounded by culling | **Static map cached** over 4 frames per 2048 build, two slots, redrawn in place per rebuilt zone, plus a per-frame dynamic map (`shadow/StaticShadowMap`, `DynamicShadowMap`) | Different, and better in Lumina | Lumina is ahead: its measured shadow cost is 0.00 to 0.07 ms mean per frame [snap `gpu_shadow_*`]. Nothing to import. |
| 35 | Only zones that can cast into the view are drawn to the shadow map: light-frustum + `ShadowCasterVolume` test, per-zone `inShadowFrustum`, "conservative culling" setting (`utils/ShadowCasterVolume`) | `ZoneBounds` box-vs-fit test when building (`shadow/`) | Have (different form) | Done. |
| 36 | Fit the light frustum to the receivers inside the draw distance, texel snapping, ignore size changes under 5 % (no shimmer, no reallocation) (`ZoneRenderer:597-666`) | `ShadowFit` (world-anchored square, rebuild by texel criterion) | Have | Done. |
| 37 | Shadow resolution 1024 to 8192, shadow distance capped at the draw distance (max 90) | 2K / 4K (`ShadowMapSize`), build spread over frames in proportion to area | Have | Done. |
| 38 | Fast vs detailed shadow program (detailed = alpha-tested casters), roof-shadow toggle, separate terrain-only shadow map | alpha casters with cutout in the one shadow program; roofs always cast | Different | Not a saving (cost is already ~0). |
| 39 | Tiled (clustered) light culling in a fullscreen pass, light count cap (default 25), lights culled to the frustum (`TiledLightingShaderProgram`, `LightManager.update`) | none; Lumina has no point lights yet | N/A today | Design input for handover segment 4 (point lights): without a cap and a tile cull, per-fragment light loops are the GPU cost that 117 HD itself calls "very taxing". |

### 3f. Frame-time safeguards and threading

| # | 117 HD technique | Lumina equivalent | Status | Value |
|---|---|---|---|---|
| 40 | Own `JobSystem` (workers = cores, `cpuUsageLimit` setting), jobs with priorities and a client thread that steals work | RuneLite's render threads only (`numThreads`, default 3, `RENDER_THREADS`); map-load thread | Different | The enabling piece for rows 12 to 16. |
| 41 | Power saving: fewer threads, no async UI copy after 15 s unfocused | none | Absent | Background CPU only; no effect on frame time. |
| 42 | Low memory mode (buffer growth 1.33x instead of 2x) | none | Absent | Memory, not speed. |
| 43 | Frame timer overlay with per-pass CPU/GPU timers | `dev/TimingsOverlay`, `FrameTiming`, `snapshot` CSVs | Have | Done, and it is how the numbers here exist. |
| 44 | Plugin stops cleanly on any render exception (`requestPluginStop`) | plugin `stopPlugin` callbacks | Have | Robustness, not speed. |
| 45 | Frame pacing | `FrameRenderer.paceFrame` (own, deadline grid) | Lumina only | 117 HD relies on the client's limiter. |

## 4. Techniques Lumina has that 117 HD does not

Cached static shadow map with in-place partial redraw (row 34); `ALPHA_ZSORT_CLOSE` limit on static alpha sorting
(row 2); frame record/replay so the GPU starts on the scene while the client draws its UI; own frame pacing; the draw
list with zero per-frame allocation. 117 HD's `directionalShadowPass` renders everything it culls in every frame.

## 5. Verdict on the compute/OpenCL sorting and culling path

Judged as asked: cost of the CPU sort Lumina does now against the compute path, driver risk, maintenance.

- **What Lumina pays now.** `record` 0.28 to 0.54 ms and `replay` 0.40 to 0.47 ms, both inside a 0.43 to 0.84 ms
  `client_scene`, of a 2.6 to 3.7 ms frame [snap]. Sorting is a fraction of those two (the alpha sort is limited to
  2 zones; dynamic sorting is in `record`, where most of it is `drawTemp` and textured-model sorting, rows 3 and 16). So
  the CPU sort cost is well under 1 ms in the scenes measured and the ceiling for any sorting offload is that fraction.
- **What the compute path would cost.** The legacy design pushes every model's input to staging buffers every frame
  and rewrites a full output vertex buffer every frame (`postDrawScene`: three staging uploads, three
  `ensureCapacity` output buffers, `renderBufferOffset`), then waits for it (`clManager.finish()` in `draw`, a
  `glMemoryBarrier` in the GL variant). Static zones, already uploaded once in Lumina, would either be re-emitted each
  frame (a regression in bandwidth) or the compute would be limited to dynamic and alpha models, where the saving is
  the sort share of `record` above, and the input still has to be copied by the CPU.
- **Driver risk.** OpenCL needs the CGL share group of a Metal-backed GL context
  (`APPLEGLSharing`, `OpenCLManager:173`) and Apple has deprecated both OpenCL and OpenGL; I did not run it on this
  Mac or this macOS, and 117 HD's own default no longer uses it. On Windows/Linux the GL compute variant needs GL 4.3
  while Lumina targets 3.3 core, so it would be a second code path with capability tiers.
- **Maintenance.** Two sort implementations (CPU for 3.3 and as fallback, compute for the rest) in a renderer whose
  face shading, material routing and shadow passes all read the same uploaded vertex format.
- **Verdict.** Not worth doing. 117 HD's authors made the same call for their default renderer. Reopen only if a
  crowded-scene snapshot shows sorting plus temp-model upload above roughly a quarter of the frame, and then the
  first answers are rows 3 and 16, not compute.

## 6. Absent techniques worth doing, ranked

Ranked by value over effort and risk, and gated by evidence. Step 0 comes first because most of the value is unmeasured.

0. **Take the missing baselines (no code).** `snapshot 10 uncapped` in a crowd (Varrock or Grand Exchange), while
   crossing a chunk boundary, and while opening a door or chopping a tree; with `log` level above DEBUG so
   `glGetError` is out of the numbers. Every item below is gated on one of these. Evidence: section 2.4, every current
   snapshot has 1 to 3 players and none spans a scene load.
1. **Narrow the sorted-path condition (row 3).** Lowest effort (a condition in `ZoneDrawer.drawDynamic/uploadTemp`),
   same stage as the largest controllable CPU cost (`record`), and plausible because Lumina's textured faces never
   blend. Evidence: `ZoneDrawer:212` and `:309` sort on `getFaceTextures() != null`; `MaterialManager:271,287` and
   `FaceShading:260` leave textured faces opaque; 117 HD sorts only real alpha (`isAlphaModel`). Gain is bounded by
   `record` (0.28 to 0.54 ms); count the models taking the path first. Check the shadow VAO and the double-sided
   reversed-face copies still get what they need.
2. **Zone rebuilds and scene loads off the client thread (rows 12 to 14).** Largest worst-frame item and the
   handover already lists it. Evidence: `SceneLoader.rebuildInvalidated` runs `free` + size + map + upload per
   invalidated zone on the client thread; 117 HD swaps in a job's result after it is ready. No steady-state gain;
   gate on the door/tree baseline from step 0. Parallel zone upload (row 12) is the same job system and makes the
   scene-load latency shorter; do the rebuild first (smaller, and it has a user-visible hitch).
3. **Parallel `drawTemp` (row 16).** The one serial piece of dynamic work, and the one that scales with crowds.
   Evidence: `drawTemp` uses `renderThreads.get(0)` on the client thread while `drawDynamic` already runs on RuneLite's
   render threads; 117 HD's `AsyncCachedModel` is the answer. Highest effort and risk (model data is copied because the
   client reuses it, ranges must keep draw order, the dynamic shadow map reads the same ranges), so only after step 0
   shows `record` growing with NPC count. Do row 1 first: it may remove part of the same cost.
4. **Exact zone frustum test (`zoneInFrustum`, row 6).** Possible CPU and GPU saving by dropping zones the client still
   submits, reuse of `ZoneBounds`. No evidence either way yet: compare the draw-command count at a fixed camera with
   117 HD's before writing it.
5. **Detail distance for animated objects (row 8).** Cheap, but at the default draw distance 50 the cut (70) never
   triggers. Only worth it if a draw distance above 70 (allowed up to 184) is used with many animated objects.
6. **Light cap + tiled culling (row 39), when point lights land.** Not a current cost; a design requirement for
   handover segment 4, from 117 HD's own warning that dynamic lights are "very taxing" and its cap of 25.

Not worth doing, with the reason: compute/OpenCL sorting (section 5); indirect/multi-indirect draw and persistent mapped
buffers (not available on GL 4.1, and 117 HD turns them off on this Mac, rows 19 and 20); async UI copy (row 24,
nothing to overlap with); frame fences (row 25); scene resolution scale (row 31, GPU is not the limit at measured
canvases); shader define permutations (row 29, negligible); low memory and power saving (rows 41 and 42).

Beyond 117 HD, one more with evidence behind it: the static alpha sort result depends only on camera yaw and pitch, so
caching the sorted index list per alpha model while those are unchanged would skip the per-frame sort and
`glBufferData` for the 2-zone ring. Neither renderer does it. Value is bounded by `replay` (0.40 to 0.47 ms) and not
measured; worth a look only if step 0 shows the alpha sort is a visible share of it.

## 7. What I did not verify

- 117 HD's frame times on this Mac; only its startup log was available.
- How many zones the client submits without `zoneInFrustum` (closed-source client).
- That OpenCL still works on macOS 27 with an M5 GPU. The judgement in section 5 does not depend on it.
- Any Lumina number at a 4K canvas, in a crowd, or across a scene load.

## 8. Addendum: how 117 HD's zone renderer keeps work off the client thread, set against Lumina

Asked by the parent session (2026-10-30): zone renderer only (`renderer/zone/`, the default per `HdPlugin:594`), in detail,
and what Lumina should move. Lumina's measured CPU per frame, uncapped (parent session's figures): client_scene 0.4 to
0.6, draw 0.3, record 0.3, replay 0.4 to 0.6, swap 0.15 ms, plus the client's own UI 1.1 to 1.5 ms, GPU about 1.5 ms.
Everything below is **[code]** unless tagged; sizes of gains are reasoning, not measurement.

### 8.1 The mechanism

**Job system** (`utils/jobs/JobSystem`, `Job`, `JobHandle`, `JobGroup`, `Worker`). Workers = `max(1, ceil((cores - 1) *
cpuUsageLimit.threadRatio))`, 17 on this Mac [log], priority NORM+1. One global deque of handles (high-priority jobs
`addFirst`), per-worker local queues and work stealing between workers. Jobs can have dependencies (a dependant is
queued when its dependency completes) and belong to a `JobGroup` (`sceneLoadGroup` and `invalidationGroup` high
priority, `streamingGroup` low) so the client can `complete()` or `cancel()` a whole batch. A job that needs the
client thread (GL calls) blocks on a semaphore through `invokeClientCallback`; the client runs those callbacks in
`processPendingClientCallbacks()`, which `ZoneRenderer` calls at the top of `preSceneDraw`, in `postSceneDraw` and in
`draw`. So a worker waits up to the next such point, one callback per frame at worst. The client thread can also run
queued work itself (`forceProcessModelClient`, `processModel`): it never idles while a job it needs has not started.

**What runs where, per frame** (zone renderer):

| Stage | Client thread | Workers | Join point |
|---|---|---|---|
| `preSceneDraw` | fence wait; environment, sky, `SceneManager.update` (area hiding, `processZoneSwaps` over every zone of the extended scene, `EXTENDED_SCENE_SIZE >> 3` per side); camera, shadow fit, UBO upload; `multizoneLocs` over every zone; `sortStaticAlphaModels` (zones with alpha in the frustum, quicksorted by distance, one `StaticAlphaSortingJob` queued per zone); `ctx.map()` (this frame's dynamic VAOs) | start sorting static alpha models, zone by zone, far zones first | none yet |
| scene traversal | `zoneInFrustum` AABB tests; `drawZoneOpaque` records into command buffers; `drawZoneAlpha`: zone model list quicksort, then per alpha model: take the sorted result, or sort it itself if no worker started it (`forceProcessModelClient`), else wait; reserve a region of the mapped alpha EBO | per temp model: project, frustum-cull faces, sort (`FacePrioritySorter`), shade and write (`SceneUploader.uploadTempModel` through `VertexWriteCache`); per zone: copy the sorted index lists into the EBO (`EboAlphaWriterJob`) | the zone's own `pendingModelJobs` before its alpha is drawn |
| `drawTemp` / `drawDynamic` | `AsyncCachedModel.setup` + `queue`: borrow arrays, copy the model's arrays (up to 21 fields), create the zone's alpha placeholder, return | the upload above | `ensureAsyncUploadsComplete` at `PASS_ALPHA` (client drains the inflight list, running jobs not yet started) |
| `drawPass(PASS_ALPHA)` | unmap dynamic VAOs (flush writers; staging copy when storage buffers exist), build the command lists | none | before any draw |
| `postSceneDraw` | UBO world views, flush alpha EBO writer, indirect commands, lights | | |
| `draw` | wait frame fence; map UI PBO and queue the pixel copy; **execute the recorded commands (all GL submission: tiled lights, shadows, scene)**; blit; UI draw (waits for the copy); fence; swap | UI pixel copy (`AsyncUICopy`) | UI draw |
| scene load / zone rebuild | map buffers when a worker asks (callback), swap in finished zones each frame | `ZoneUploadJob` per zone (size, map via callback, upload, unmap via callback), `generateSceneData`, `loadSceneLights`, `calculateRoofChanges` | `completeInvalidation`, `processZoneSwaps` |

What 117 HD does **not** move: all GL submission and buffer map/unmap, the per-zone callbacks and their bookkeeping loops
(`multizoneLocs`, `sortStaticAlphaModels`, `processZoneSwaps`, `postAlphaPass`, each over every zone every frame),
environment/sky/light updates, and the model snapshot copy. Its client thread is not light; it is shorter only when there is
a lot of model and alpha work to hand over.

### 8.2 `AsyncCachedModel`, `ModelStreamingManager`, `VertexWriteCache`: what is cached, keyed how, evicted when

- **`AsyncCachedModel` is a snapshot, not a cache.** It implements `Model` over pooled copies of the client's arrays
  (vertices x/y/z, face indices, colours, textures, priorities, transparencies, bias, texture faces, normals, unlit
  colours: 21 fields), because the client keeps mutating the shared `Model` after the callback returns. There is no
  key and no reuse between frames or between calls: every `drawTemp`/`drawDynamic` makes a fresh snapshot and
  releases it when the upload finishes (`processModel` `finally`: every field `reset()` back to its pool, the object
  recycled). Pool: a `ConcurrentPool` of at most 32 `AsyncCachedModel`s; the 33rd concurrent model gets `null` and is
  uploaded synchronously on the calling thread. `setup` also refuses when the heap cannot hold the arrays
  (`freeMemory` check) and falls back the same way. The copy is pipelined: the job is queued first, the fields are copied
  after in the order the upload reads them, and the worker starts once vertices, face indices and `faceColors3` are
  cached (`canStart`); reading a field that is not yet copied parks until it is.
- **`PooledArrayType`** is the backing store of those arrays (and of the sorter scratch): one pool per primitive type,
  buckets by power-of-two size, 8 stripes chosen by thread hash, total capped at 5 % of the max heap (at least
  10 MB), idle buckets shrunk after 60 s from a moving average of demand (alpha 0.1), `forceCleanup(false)` on every scene
  load. This is what keeps the frame allocation-free.
- **`VertexWriteCache`** is not a model cache either: a 32 KiB heap `int[]` per output stream (opaque, alpha, opaque
  texture faces, alpha texture faces) that `SceneUploader` fills a face at a time and bulk-`put`s into the mapped buffer when
  full. It avoids many small writes into mapped GPU memory and keeps the per-face texel index (`textureFaceIdx`)
  computed from the output position.
- **`ModelStreamingManager`** decides, per call: area-hide, detail-distance cull, override lookup, off-screen skip (with
  the shadow-caster exception), whether the model has alpha (`isAlphaModel`), whether it needs sorting (`shouldSort`), and
  whether to hand it to a worker (`obtainAvailableAsyncCachedModel`, only with render threads on and the pool not
  exhausted). RuneLite's two render threads (`RL_RENDER_THREADS = 2`) are only suppliers: they snapshot and queue.
- **`DynamicModelVAO`** (3 sets, one per frame in flight, pooled across world views): a growing buffer (starts at 64 KiB)
  plus a texel buffer, from which `beginDraw` reserves a vertex range and a texel range under a lock; many threads then
  write their own reserved ranges at once. Ranges are merged at `unmap`. The model-keyed cache (`ModelCache`, keyed by a
  model hash, off-heap, oldest entry evicted) exists only in the legacy renderer.

### 8.3 Lumina against it

| 117 HD | Lumina | Difference that matters |
|---|---|---|
| `StaticAlphaSortingJob` + `FacePrioritySorter.sortStaticModelFacesByDistance` | the STATIC branch of `Zone.renderAlpha`, using the render thread 0 uploader's `zsortHead/Tail/Next` | Same bucket sort of the same packed centroids. Lumina does it at the replay, on the client thread, for zones inside 2048 units only. 117 HD sorts every alpha zone in the frustum, from `preSceneDraw`, so it overlaps the whole traversal. |
| `EboAlphaWriterJob` + mapped alpha EBO | `alphaElements` buffer + `glBufferData(GL_ELEMENT_ARRAY_BUFFER, GL_STREAM_DRAW)` + `glDrawElements` per flush | 117 HD copies the lists into the EBO on a worker (a second pass after the sort into a pooled `int[]`) and issues one `glDrawElements` per model run; Lumina's upload is a driver copy on the client thread. |
| `ZoneUploadJob`, job groups, zone swap | `SceneLoader.loadScene` serial on the map-load thread; `rebuildInvalidated` on the client thread | Rows 12 to 14 above. |
| `FacePrioritySorter.sortModelFaces` split from the upload (`preprocessTempModel` projects and culls first; the sorter sorts; `uploadTempModel` shades and writes visible faces) | `ModelUploader.uploadSortedModel` does project, shade, sort and write in one pass | Lumina's single pass shades every face, including faces 117 HD would have culled; and, per row 3 above, it runs for any textured model. |
| `ModelStreamingManager` + `AsyncCachedModel` | `ZoneDrawer.drawDynamic` (runs directly on RuneLite's render threads, `numThreads` default 3, each with its own `VAOList` and `ModelUploader` scratch, so no locks and no model copy) and `ZoneDrawer.drawTemp` (client thread, `renderThreads.get(0)`) | Lumina parallelises dynamic objects without copying, because the render threads call back while the `Model` is valid. `drawTemp` has no such thread, which is the whole gap. |
| `DynamicModelVAO` (growing, locked reservation, 3 in flight) | `VAOList` / `VAO` / `VBO`: fixed 4 MiB per VAO (`VAO_SIZE`), one list per thread per kind, all mapped with invalidate every frame (`RenderThreads.map`), unmapped with a flush of the written part | Per-thread lists need no lock but make one draw range per VAO per thread; and 4 threads x 2 kinds x 4 MiB is mapped and invalidated each frame whether or not a model was written. Unmeasured on GL-on-Metal. |
| `VertexWriteCache` (32 KiB heap staging per stream) | `ModelUploader.vertexBuffer` (one face at a time) bulk-`put` into the mapped `IntBuffer`; static `SceneUploader` writes straight into the mapped buffer | Finer staging in Lumina for temp models. Whether unified-memory mapped writes on Apple are slow enough to matter is unknown. |
| `PooledArrayType`, `ConcurrentPool` | fixed per-thread scratch arrays; `DrawList` primitive arrays | Lumina needs no pool because it never copies models. |

### 8.4 Ranked by what would cut Lumina's numbers

First, a caution the data forces. With one to three players and no crowd, the temp-model and alpha-sort work 117 HD
offloads is small, while most of what remains in `record` (0.3) and `replay` (0.4 to 0.6) is per-frame and per-zone
fixed cost that 117 HD **also keeps on its client thread**: mapping and unmapping buffers, the per-zone callbacks, and
the GL submission (its `draw()`). On this Mac 117 HD cannot batch that submission (no indirect, no multi-indirect [log]).
So copying 117 HD's threading will cut little from the idle-scene numbers; it pays in crowds. I cannot rank by measured
size because `record` and `replay` are not split. The ranking below is by probable share times how cleanly it can be
removed, with the split as step 0.

0. **Split `record` and `replay` with sub-timers** (a few `CpuTimer` stages: `renderThreads.map()`, `renderThreads.unmap()`,
   `drawTemp` upload, zone alpha sort + `glBufferData`, GL submission, shadow submission). No code in this audit; this is
   the measurement everything else waits on. A crowd snapshot (Varrock, Grand Exchange) with the same stages shows what
   scales.
1. **Make the dynamic buffer map/unmap cost proportional to use (new, not a 117 HD technique, suggested by the
   comparison).** 117 HD's dynamic buffers start at 64 KiB and grow; Lumina maps and invalidates 8 VAOs of 4 MiB each
   frame (`VAOList.map`, `VBO.map` with `MAP_INVALIDATE_BUFFER`). If GL-on-Metal reallocates on invalidate, this is a
   fixed per-frame cost inside `record` and `replay` that no job system touches. Test: the sub-timers above, then a
   smaller `VAO_SIZE` or mapping only lists used in the frame. Hypothesis, not a finding.
2. **Do not re-sort static alpha when the camera has not turned (new, simpler than a job).** The sort depends only on
   yaw and pitch (`StaticAlphaSortingJob` takes only those). While they are unchanged the previous frame's index
   lists for each close alpha model stay valid, so both the sort and, with a persistent per-zone index buffer, the
   `glBufferData` go away. This removes the work instead of moving it, costs no threads, and RuneScape's camera is
   still most of the time. When yaw or pitch change, run the sort as 117 HD does (rank 4).
3. **Async `drawTemp` (the `AsyncCachedModel` pattern), crowds only.** The one serial dynamic-model path, and the only
   117 HD technique that scales with NPC count. Lumina's record/replay makes it easier than in 117 HD: nothing is drawn
   until the replay, so a single join at the start of the replay replaces 117 HD's per-zone `pendingModelJobs` waits
   and the `PASS_ALPHA` drain. What must be copied from 117 HD: the snapshot of the model (the client reuses it), the
   alpha placeholder created on the client thread before the job so `Zone.alphaSort` (which needs only x, y, z) can
   run during the traversal, and the pool-exhausted/low-heap fallback to the synchronous path. What Lumina needs that
   117 HD solved with a lock: single-writer buffers for the workers, i.e. give each worker its own `RenderThread`
   (`VAOList` + `ModelUploader`), as the render threads already have. Expect a loss in scenes like the snapshots
   (snapshot copy plus handoff for two players) and a gain in crowds; gate it on the crowd baseline.
4. **Static alpha sort + element upload on workers, started in `preSceneDraw` (`StaticAlphaSortingJob`,
   `EboAlphaWriterJob`).** Cleanly separable (the packed faces are immutable after the zone is built, the output is a
   pure function of yaw/pitch, so it can be unit-tested like the other pure parts), but Lumina sorts only zones within 2048
   units, so the work moved is small. Do it only if rank 2 does not make the sort negligible, or if the close radius is
   raised.
5. **Zone upload and rebuild jobs (`ZoneUploadJob`, rows 12 to 14).** Not in the per-frame numbers; a hitch fix. Needs
   the job system with client callbacks (the one subtle part: workers wait for the client thread to map and unmap), so it
   shares its infrastructure with rank 3 and 4: if more than one of 3, 4 and 5 is wanted, build the job system once.
6. **Async UI copy.** Bounded by `draw` (0.3 ms) and, as in row 24, nothing in Lumina's order of work overlaps it.

Not to copy: the per-zone bookkeeping loops over every zone (`multizoneLocs`, `postAlphaPass`, `processZoneSwaps`,
`sortStaticAlphaModels`); they are cheap for 117 HD's design and Lumina's record/replay does not need them in that form.

### 8.5 Legacy renderer, one row

| 117 HD (`renderer/legacy`) | Lumina | Status |
|---|---|---|
| Compute/OpenCL path: every model and tile pushed to staging buffers each frame, sorted/transformed on the GPU into a rewritten output buffer, `ModelCache` keyed by model hash (off-heap, size-bounded, oldest evicted) to skip re-pushing, `clFinish` before drawing | none | Not applicable to the zone architecture and not worth porting (section 5). |
