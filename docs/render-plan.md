# Lumina render plan — the old look, without the old cost (2026-10-30)

What the user asked for: a renderer that looks like the old Lumina (`perf-wip`) — SSAO, fog, point lights with
occlusion, skylight, 117 HD's terrain and normal fixes — and is performant and never hitches. GI is dropped. This
document is the plan: the frame budget, the rules that keep hitches out, how each feature is built inside those
rules, and the order. Companion documents: `docs/old-renderer-vs-lumina.md` (what the old renderer did and what it
cost), `docs/perf-audit-117hd.md` (where 117 HD keeps work off the client thread),
`docs/sky-horizon-and-light-design.md` (horizon, tint, light floor, dawn glow).

## 0. Architecture: forward shading with a thin G-buffer

Forward or deferred is not the choice; how much is deferred is. The old renderer deferred everything: a four-target
G-buffer, all lighting in six composite programs, three half-res passes and a denoiser, so every pixel paid the whole
chain whatever the scene held. Lumina lights in the forward pass (a dozen multiplies and the shadow taps per
fragment, overdraw included) and defers only what needs the screen:

- **Depth** comes free from the forward pass, and it is the whole G-buffer: GTAO (§3.2) and the volumetric fog
  (§3.8) work from depth, with normals reconstructed from it, so the scene pass writes nothing extra.
- **Screen-space passes** read the depth: GTAO at half resolution, the fog march at half resolution, then one
  full-resolution composite in place of the blit that applies both.
- **Everything else stays per fragment**: the light floor, sky fill, fog, the point-light loop over a tile list.

This is what is usually called forward+: the lighting cost follows the geometry drawn, the screen-space cost is
fixed and small, and no pass exists that lights the whole screen a second time.

## 1. Targets

Machine of record: Apple M5 Pro, macOS GL 4.1 on Metal, the client maximised (~4K device pixels). Measured today at
a small canvas: 2.6–3.7 ms a frame uncapped; GPU scene pass 1.5–2.3 ms; PCSS +0.3 ms; Lumina's own client-thread
work ~1.3 ms (draw 0.3, record 0.3, replay 0.5, alpha sort inside it); the client's UI 1.1–1.5 ms, not ours.

| Target | Number | Why |
|---|---|---|
| Frame, uncapped, maximised, Auburnvale | **≤ 8 ms** with everything in this plan on | 60 FPS with 2× headroom; 120 FPS reachable at 1440p |
| Frame, uncapped, Grand Exchange crowd | ≤ 10 ms | the dynamic-model worst case |
| Lumina's client-thread work per frame | **≤ 1.5 ms** steady, ≤ 2.5 ms on a scene-change frame | the client's own 2–3 ms sits on the same thread |
| Hitch | **none**: no frame > 1.5× the pacing interval caused by Lumina (25 ms at 60) over a 10 s snapshot | the user's definition of done |
| Memory | shadow maps ≤ 50 MB at 4K, every other target ≤ 8 MB, no steady-state growth | laptop GPU, shared memory |
| Settings | Ambient occlusion on/off, Volumetric fog on/off, Lights on/off, Shadow quality, Shadow resolution, Render scale, Sky, Colours | "few settings" |

GPU budget per frame at 4K, with every feature on (ms): scene pass 2.5 (incl. PCSS and the lights loop) · dynamic
shadow map 0.3 · static map build share 0.5 average (spread) · sky + stars + aurora 0.4 · GTAO half-res 0.7 ·
volumetric fog half-res 1.0 · composite 0.3 · UI 0.3 → **≈ 6 ms**, 2 ms under the target for scene variance; water
(§3.9) gets its own budget when it is designed.

## 2. The no-hitch rules (binding for every feature below)

1. **The client thread does submission only.** Per-frame work that scales with scene content (sorting, packing,
   uploads, bakes) runs on workers or is time-sliced. Today's violations to fix first: zone uploads and rebuilds at
   scene change and the `SkyCover` bake run on the client thread (perf-audit §8.4 items 4–5).
2. **Every bake is sliced with a per-frame budget**, like the static shadow map already is (4 frames at 2K, 16 at
   4K): ≤ 1 ms of client-thread time per frame, the rest of the bake on a worker where GL is not needed; a bake
   that is not finished shows its previous result, never a hole.
3. **No synchronous GPU reads in the frame**: timer queries read a frame late or not at all; no `glFinish`, no
   `glReadPixels`, no `glGetTexImage`, no mapped-buffer waits. Buffers are grow-only pools; no reallocation in the
   steady state; textures and FBOs are created at start or at a size change, never per frame.
4. **No garbage in the frame loop**: no per-frame `new` of arrays or objects on the hot path (the GC pause is the
   hitch nobody sees in a profiler); scratch arrays live on the owner.
5. **Shaders compile at start**, every variant (`ShaderCompileCheck` proves they compile); a debug view is a uniform,
   never a recompile.
6. **Per-pixel passes run at half resolution** unless the effect needs full (the scene pass and the composite); each
   has a fixed step count, never a content-dependent loop without a cap.
7. **Measure before it stays.** Every feature lands with `snapshot 10 uncapped` at two fixed camera positions
   (Auburnvale, the Grand Exchange) at the maximised window, before and after, plus the hitch count (frames over
   1.5× the median) and `set renderScale 50` once to tell GPU-bound from CPU-bound. The numbers go in the HANDOVER
   state table; a feature over budget is cut down or taken out, not explained.

8. **One mechanism per quantity.** Every quantity a pixel is made of — its ambient light, its direct light, its
   lamp light, its occlusion, its fog, its shadow — has exactly one source in the code, with one knob, and a new
   feature *replaces* the old mechanism for that quantity, never sits on top of it. The old renderer layered five
   ambients (palette fill, sky cache, `RoomLight`, `SKYLIGHT_FLOOR`, `overcastSkyBoost`), four fogs (per-area depth,
   aerial, volumetric + `FogGround`, edge fade) and three lamp systems (clusters, light map, rooms/blockers), and
   no one could say which layer made a pixel what it was. A debug view per quantity (`debug light|ao|fog|...`) must
   show that one mechanism alone.

   | Quantity | The one mechanism | Replaces (removed when it lands) |
   |---|---|---|
   | Sky colour | `SkyModel` palette + area tint + haze, SH L1 fill | `skyTint`/`fogColor`/`fogWeight` multipliers of the area look |
   | Direct light | sun or moon (one caster at a time), × shadow map | — |
   | Ambient | sky colour × gain, with the altitude floor | `AMBIENT_DAY/NIGHT/DUSK`, the night gain constants, `CLEAR/HARSH_AMBIENT` collapse into the one floor + gain |
   | Skylight (sky visibility) | one bake with two inputs (walls/roofs flood, height-field horizon), one value per vertex | — |
   | Occlusion (contact) | GTAO, one strength | — |
   | Lamp light | 117 HD light data → tile list → one loop in the scene shader | — |
   | Lamp occlusion | **baked depth cubes, alone** (no contact shadows, no reach masks, no rooms) | — |
   | Fog colour | the sky behind the pixel (`skyGradient` + glow), one function for the sky pass, the aerial fog and the volumetric fog | — |
   | Fog, distance | aerial perspective, one depth from `SkyModel` | — |
   | Fog, volume | the volumetric pass: one density = weather global + ground layer | 117 HD's `groundFog` env data and the `nightFogBlend` term in `frag.glsl`, the ground-fog setting |
   | Shadow | static cache + dynamic map, PCF or PCSS | — |
   | Edge of the world | fog to the apparent horizon | the old `EdgeFade` circle (not taken) |

## 3. The features, inside the rules

### 3.1 Fog — have it; finish the horizon
Aerial perspective in the scene shader, colour = the sky behind the pixel (done). Add the apparent horizon
(`cloudSkyDir` from the old renderer, four floats, shader only) so the ground edge and the haze band move together.
Cost: zero per frame beyond today. No volumetrics, no per-area depths: the weather module later tints and deepens
this one fog. Clouds (procedural, sky pixels only, ≤ 16 steps) are the one weather visual to add, after the rest.

### 3.2 Ambient occlusion — GTAO from depth, half resolution, applied to the whole pixel
The user's intent: contact darkening that makes objects stand out from the terrain, not a physically separate
ambient term, so the occlusion multiplies the whole colour. Horizon-based AO (GTAO: a few slice directions, the
horizon angle found by marching the depth buffer, cosine-weighted) gives a cleaner, more stable result than
crytek-style SSAO for the same taps, and needs no normal attachment: normals are reconstructed from depth.
- Half-res pass from the depth buffer: 2 slices × 8 steps per pixel per frame, the slice angle and step offset
  rotated by a per-pixel hash that changes per frame, radius ≈ 1 tile in world units with a screen-space cap.
- Noise control without a 21-tap denoiser: **temporal accumulation** against the previous frame's AO, reprojected
  with the previous view-projection (the camera is the only thing that moves the depth of static scenery; dynamic
  models fall back to the spatial result by a depth test), plus a 4-tap depth-aware blur. Two half-res R8 history
  targets.
- Applied in the composite (§0): `colour × mix(1, ao, strength)`, faded with the fog amount so distant AO vanishes
  with the haze. Fixed cost ≈ 0.6–0.8 ms at 4K.
- MSAA: the depth is resolved by `glBlitFramebuffer` before the AO pass; if that costs more than it saves, AO runs
  with Render scale as the AA.
- Setting: Ambient occlusion on/off. Debug: `debug ao`.

### 3.3 Skylight — a better bake, still no per-frame cost
Today: an L1 spherical-harmonic sky fill on every normal × `SkyCover` (a walk-distance bake: roofs and walls). The old
renderer traced a voxel grid on workers. The plan keeps the per-frame cost at zero and improves the bake:
- **Height-field sky occlusion**: at scene load, per tile corner, march 16 directions × 8 steps across a 2D height
  field made of the terrain plus the static objects' bounding heights (trees, buildings), accumulating the horizon
  angle; sky visibility = the cosine-weighted open fraction. Trees and buildings then darken the ground under and
  beside them softly; roofs and walls keep the walk-distance rule for interiors (the two bakes multiply).
- Sliced: the march runs on a worker (pure arithmetic, no GL), the per-vertex write happens in the zone upload that
  already exists; a zone shows the old value until its new one is in.
- Optional later: a **bent normal** (mean open direction) per vertex so the SH fill is sampled toward the open sky,
  not straight up. Measure first whether the visibility alone reads as skylight.
- No sky cache, no irradiance texture, no 3D texture: the SH is re-integrated on the CPU when the sun moves 0.25°,
  tens of microseconds.

### 3.4 Point lights with occlusion — data from 117 HD, culled on the CPU, occlusion by a baked cube or a reach mask
The old renderer spent ~8k lines (clusters, light map bakes, rooms, blockers). The performant version:
- **Lights**: 117 HD's light definitions (positions, colours, radii, flicker; data, not images), as the Lights
  section's source. Cap: the 64 nearest lights in the draw distance, sorted by intensity at the camera.
- **Culling**: a CPU tile grid over the view (32×18 tiles, ≤ 8 lights per tile by contribution), uploaded as one small
  texture per frame (≤ 20 KB); the scene shader loops over its tile's lights — a fixed, capped loop, ≈ 0.3 ms at 4K
  in the forward pass. No clusters in depth (the scene is shallow); no compute.
- **Occlusion** — the open design question; the honest options, each with its cost:
  1. *Wall-reach bake*: per static light, the tiles it can light without crossing a wall, by the same flood
     `SkyCover` uses. Zero per-frame cost, walls block lamps, rooms stay lit from inside; no shadows from furniture,
     trees or players, and a lamp on a post lights the ground through the post.
  2. *Baked per-light depth cubes*: at scene load, one small depth cube (128² × 6, 16-bit) per static light within
     reach, from the static scene only, sliced with the zone bakes (≤ 1 ms per frame); the forward lights loop
     samples the cube like a shadow map. Real occlusion from every static object, soft with a 2×2 PCF; nothing
     per frame but the sample; 64 lights × 6 × 128² × 2 bytes = 12 MB. Players and NPCs do not block lamps.
  3. *Screen-space contact shadows*: a short depth-buffer ray march toward each light (8 steps) in the forward pass,
     catching blockers that are on screen — dynamic ones included — and missing those off screen. ≈ 0.2 ms per
     light-covered pixel; view-dependent.
  4. *Per-frame per-light shadow maps*: the full answer and the one that does not fit the budget (N lights × a
     cube pass each frame); not planned.
  Chosen under rule 8 (one mechanism): **2 alone** — baked depth cubes for static lights, real occlusion from every
  static object, zero per-frame rebuild, one texture fetch per light per fragment. Players and NPCs do not block
  lamps, accepted. Dynamic lights (spells, projectiles) have no occlusion at all, accepted. No contact shadows, no
  reach masks, no rooms on top. If the cube bake or memory is over budget at the Grand Exchange, the cube count or
  size shrinks; 1 replaces 2 only as a whole, never beside it.
- Budget: occlusion bakes sliced with the zone bake (≤ 1 ms per frame at scene load); per frame ≤ 0.1 ms CPU for
  the tile grid at 64 lights, ≤ 0.3 ms GPU for the lights loop and its cube samples.
- Setting: Lights on/off (later a count or distance if needed). Debug: `debug lights` (tile occupancy).

### 3.5 117 HD's terrain and normal fixes
- **Terrain brightness** (from the old renderer, not 117 HD): the terrain bake undo + 1.25 gain, cap 55, in the
  Vanilla path of `DiffuseColors.terrainColor` — the one piece that puts a sunlit wall back at the game's 1.5× of the
  ground (comparison §1.3). Never the vertex colour blending.
- **Terrain normals**: 117 HD derives terrain normals from the height field across tile borders, so slopes shade as
  surfaces and tile seams vanish; Lumina's current terrain normals come from each tile's own geometry. Take the
  height-field normal at upload time (zero per-frame cost); keep "never blend terrain colours" — this is normals,
  not colours.
- **Model normals**: measure first with `debug unlit` how many faces have zero or degenerate normals per scene
  (comparison §5 item 5); then port smooth-normal computation behind the planned switch only where the count says
  so; 117 HD's flat-lit object flags stay the rule for which objects are flat.
- **Geometry fixes**: 117 HD's model and tile overrides are already Lumina's (rule 2 of the feature decisions).

### 3.6 Shadows — have them; keep the cost shape
Static cache spread over frames, dynamic map each frame, PCF or PCSS (done, measured). The static build share and
the PCSS receiver are the two GPU items to watch when the window is maximised; the PCSS session's maximised
measurement is still owed.

### 3.6a Static visibility: a voxel grid as a 3D texture (user's criterion: least load time, draw at once)
Skylight and lamp occlusion are one quantity under rule 8 — visibility through the static scene — and neither is
part of loading: **the first frame draws with defaults (sky visibility 1, no lamp occlusion) and the bakes refine
zone by zone over the following frames.** Of the two structures that can answer visibility, the grid wins on the
user's criterion:
- **Voxel grid** (chosen): the static geometry rasterised into a byte grid, half a tile horizontally and 64 units
  vertically (≈ 208×208×32 = 1.4 MB per scene, one or two channels), per zone as the zone is uploaded, on a worker,
  tens of milliseconds a zone, incremental on a changed zone. **Each voxel is a node with six face bits** (user's
  proposal): bit 0 occupancy, bits 1–6 "light may cross this face" — 7 bits, one byte per voxel. Thin walls, which
  would fall between voxels of any size, are exactly a blocked face between two voxels (`SkyCover`'s wall-edge
  classification — doors, gates and windows open, posts and diagonals close nothing — moves here; floors and roofs
  block the vertical faces), so the grid does not leak through walls whatever its resolution. Uploaded as a 3D
  texture (R8; GL 3.3 has them), a few KB per zone slice.
  - *Lamp occlusion on the GPU*: in the forward pass, 6–8 texture steps from the fragment toward each light of its
    tile list, each step testing the face bit it crosses; no per-light bake at all, dynamic lights occluded for free,
    ≈ 0.1–0.2 ms over the lit pixels. Replaces the depth cubes of §3.4 entirely. Lamps stay analytic per fragment:
    a baked light volume (summed lamp irradiance per voxel, one fetch) was considered and set aside — blobby at half
    a tile, static-only so dynamic lights would need a second path (rule 8), and local GI by another name.
  - *Skylight on the CPU*: per vertex, either 16–32 short voxel marches toward the sky (cosine-weighted) or a
    Dial's-buckets flood from the open sky across open faces (`SkyCover`'s flood in 3D, which also wraps softly
    through doorways) → sky visibility and a bent normal, on the worker right after the zone's slice is built;
    replaces the height-field march of §3.3 and `SkyCover`'s own 2D flood (one mechanism).
  - *Known failure*: coarse rounding of small free-standing geometry (a post, a fence rail) — walls no longer leak
    thanks to the face bits; the prototype checks a lamp-lit interior, the palisade and a lamp on a post before it
    stays.
- **BVH** (the exact alternative): a sorted build over every triangle (0.3–1 s per scene on one core, 20–40 MB),
  CPU rays only (no compute on GL 4.1), exact under overhangs and at corners. Kept only if the face-flag grid still
  leaks unacceptably at the prototype spots; then it replaces the grid as a whole (rule 8), at the load-time price.
- **Gate**: the grid's per-zone build time and the leak check at two spots; both measured in step 1.

### 3.7 Not in the plan
GI (dropped by the user). Per-frame per-light shadow maps, full deferred shading, the sky cache, the caustics image
(no texture images), lightning/audio/particles, the MSAA edge pass, ground colour blending.

### 3.8 Volumetric fog — wanted; a capped half-res post pass
The user wants it: the old renderer's volumetric fog looked right. It is a screen-space pass (§0), budgeted:
- Half-res march along the view ray from the camera to the depth buffer: **8 steps**, fixed; density = a global
  term from the weather (clear days near zero) + a height layer over the terrain (the old `FogGround`'s 3-tile
  layer, density falling with height above the ground under the pixel); each step samples the static shadow map
  once (sun shafts) and adds ambient × density; the phase function favours the sun direction.
- Noise control as for GTAO: a per-pixel, per-frame step jitter, temporal accumulation on two half-res RGBA16F
  history targets, a 4-tap depth-aware blur — not the old 21-tap denoiser.
- Applied in the composite before the AO multiply: `colour × transmittance + inscatter`, and its colour is the same
  sky-behind-the-pixel fog colour the aerial fog uses, so the two fogs agree.
- Point lights in the march only when the Lights section is in, capped to the tile's list, and only if the measured
  cost allows (the old renderer paid for every light at every step).
- Budget ≈ 1.0 ms at 4K; setting Volumetric fog on/off (the aerial fog stays on regardless).
- Rule 8: when it lands, 117 HD's ground fog (`groundFogStart/End/Opacity`, `nightFogBlend`, the Ground fog setting)
  is removed, not left under it; the volumetric pass's ground layer is the one height fog.

### 3.9 Water — a later phase
Water comes after the above, as its own designed segment: the old renderer's 5k lines, its passes and its caustics
image are not the model; the direction is vanilla water textures kept (rule 5), with reflections or depth tint
decided when it is planned. Not budgeted here.

## 4. Order of work (each step measured per rule 7 before the next starts)

1. **Hitch foundation**: zone upload/rebuild and the `SkyCover` bake off the client thread or sliced (rules 1–2);
   with it, the **static-visibility gate** (§3.6a): build the voxel grid on a worker, time it per zone, and check
   for leaks at a lamp-lit interior and the palisade; the first frame must not wait for it;
   `snapshot 10 uncapped` at both fixed views as the baseline, hitch count recorded. Nothing visual changes; this is
   what makes every later bake safe.
2. **Apparent horizon** (§3.1), then **terrain undo/gain** (§3.5): small, shader/upload only, both already designed.
3. **Ambient occlusion, GTAO** (§3.2): the composite, the depth resolve and the temporal history are the
   foundation the fog pass reuses.
3b. **Volumetric fog** (§3.8): on the same composite and history machinery; measured on a dawn view with shafts.
4. **Skylight bake upgrade** (§3.3): height-field occlusion on a worker, sliced through the zone upload from step 1.
5. **Terrain normals from the height field** (§3.5), with the normals count and the smooth-normal switch decision.
6. **Point lights** (§3.4): data in, tile grid, forward loop; then the reach-mask occlusion bake, sliced.
7. **Dawn toward the sun only**, **overcast balance**, **clouds** (sky-pixel march) — the sky polish, measured on
   sky-heavy views.
8. Re-baseline: both fixed views, maximised, everything on, against the §1 targets; cut what is over.
9. **Water** (§3.9), designed and budgeted on its own after the re-baseline.
