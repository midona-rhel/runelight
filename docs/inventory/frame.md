# Inventory — the frame: passes, render targets, composite, upscale, AA, timers

Read-only inventory of branch `lumen` (2026-10-30), judged against HANDOVER.md's rules: performance first (≤ 8 ms at
4K), one mechanism per quantity, few settings, GL 3.3–4.1 on Windows/macOS/Linux (no compute). Paths are relative to
`src/main/java/com/midonarhel/lumina/` (Java) and `src/main/resources/com/midonarhel/lumina/` (GLSL).
Numbers are HANDOVER B1/B2/B3's; nothing here was run or built. "By reading" marks a conclusion from the code alone.

Lighting, sky/weather/water, shadows and scene upload are other readers' areas; they appear here only where the frame
flow binds, times or duplicates them.

## 0. The frame, in order (where everything below hangs)

`RuneLightPlugin` implements `DrawCallbacks`. One frame, top level only:

1. `preSceneDraw` → `preSceneDrawToplevel` (RuneLightPlugin.java:1813–2060): maps the model buffers, captures the
   frame state (`captureFrameState` :2152), updates the world's edge (`updateEdge` :2208), writes the camera UBO
   (:1869–1883), recreates the FBOs on a size/AA/render-scale change (:1902–1928), sets anisotropy and LOD bias
   (:1934–1948), the scene viewport (:1976–1983), the scene program's uniforms (:1985–2042), the projection
   (:2029–2038), depth `GL_GREATER` reverse-z (:2052), starts the `SCENE` GPU timer and clears the targets via
   `drawSkybox` (:2055–2056).
2. The client draws zones and models through `drawZoneOpaque`/`drawZoneAlpha`/`drawPass`/`drawDynamic`/`drawTemp`
   (:3571–3795; scene upload, other reader). `drawPass` switches the transparency layer on and off (:3651–3673).
3. `postSceneDraw` → `postDrawToplevel` (:2088–2146): resolves the alpha layer (MSAA), draws the shadow map and the
   weather occlusion map, rebinds the window framebuffer, marks `sceneFboValid`.
4. The client draws its interface into the canvas (timed as `CLIENT_UI`, CpuTimer.java:23).
5. `draw` (:3965–4098): shader hot-reload poll, lazy texture array, starts the interface copy (`prepareInterfaceTexture`
   :3928), `compositeScene` (:3270–3568), `finishInterfaceUpload` (:3949), `drawUi` (:4100), `swapBuffers` + `paceFrame`
   (:4041–4042), then the timers' `endFrame`s, the hitch monitor, the snapshot and `processDrawComplete` (:4071–4093).

`compositeScene` in order: water surface pass → voxel/sky-grid upkeep → **AO pass** (half res; AO + shadows + ground
cover + sky visibility + packed geometry) → clouds setup and **sky cache** → `prepareLighting` (light clusters/light
map upload, other reader) → **fog pass** (half res) → **denoise** (half res) → **half-res clouds/water pass** (a
composite variant) → **full composite** (+ MSAA mark + edge passes) → weather → **upscale** → unbind everything.

Measured (B1, canvas 783×603 ≈ 3.4 Mpx device, render scale possibly 50 %, see HANDOVER's caveat): per-pixel chain
(fog + composite + AO + filter + clouds) ≈ **4.8 ms of 6.96**; projected to 4K ≈ 19 ms. At render scale 25 % the frame
is 3.95 ms and the chain nearly vanishes (fog 0.32, composite 0.40, AO 0.14, filter 0.03): the chain is pixel-bound.

---

## 1. Scene render targets (G-buffer)

**Purpose (code's words):** "The scene render targets: color, world-space normal, depth and material, as textures
later passes can sample." (SceneTargets.java:5–10)

**Files:** SceneTargets.java (`init` :96–157, `clear` :434–446, `setAlphaPass` :451–454, `bindTextures` :481–489,
`texture` :501–520); formats :13–17; RuneLightPlugin.java `initFbo` :1779–1804, `preSceneDrawToplevel` :1902–1931;
`drawSkybox` :2062–2085 does the clear.

**How:** One FBO with four color attachments and a depth: color RGBA8 (alpha 1 = geometry covered the sample), normal
RGB10_A2 (smooth normal, n·0.5+0.5), depth D32F reverse-z, material RGBA8 (see §4), plus the transparency layer on
attachment 2 (§2). Draw buffers switch between `OPAQUE_BUFFERS` {0,1,—,3} and `ALPHA_BUFFERS` {—,—,2,—} (:60–61). All
five are cleared each frame (`clear`, five `glClearBufferfv`). Sized at window × backing scale × render factor
(`initFbo` :1784–1785). The half-resolution family (§7) is allocated alongside: `texAo` RG32F, `texShadowRaw`/`texShadow`
RGBA16F, denoise FBO, fog raw/smoothed RGBA16F (lazily, :325–346), clouds RGBA16F + water-shaded RGBA32UI (lazily,
:244–300), and two debug "part" texture arrays RGBA16F×3 and ×1 (`drawPart` :23–48).

**Settings:** `renderScale` (:58), `antiAliasingMode` (:86) — both force a rebuild (RuneLightPlugin.java:1903–1906).

**Cost:** per frame fixed (clears, binds) + memory proportional to pixels: at 4K without MSAA roughly 4+4+4+4+4 bytes/px
for the scene set, +8+8+8 for the half set at quarter area. With MSAA ×N the five scene attachments are ×N. Rebuild is
per event (resize / setting). The color target's alpha doubles as a coverage mask (:430–432), read by the composite's
`skyShowsAt` (composite_frag.glsl:291–298) and sky replacement (:371).

**Duplicates / variants / dead:** The two "part" texture arrays and their FBOs (`drawPart`, `partTextures`) exist only
for the `runelight.compositeCombined=false` diagnostic path (§11); allocated lazily, never in normal play, but the
code and the `compositeParts` sampler binding stay in every composite program (RuneLightPlugin.java:1439). Two clear
paths: `clear(sky color)` with no skybox, `clear(0)` + a drawn skybox model (:2064–2083). `check()` throws a
RuntimeException on an incomplete FBO (:523–530), which `draw` catches and logs per frame (:4014–4021).

**Verdict:** **Restructure.** Keep the four-target layout (it is what every pass reads), but drop the `drawPart`
arrays and `compositeParts` with the legacy path, and consider RGBA8 → RGB10_A2 or packing coverage into the material
target so the color alpha is not a fifth quantity. Memory and clears are the only per-pixel costs here; they are
fine.

## 2. Transparency layer (the alpha pass)

**Purpose:** "Transparent faces go to their own layer, blended as premultiplied 'over' so the composite can put the
layer on top of the lit opaque scene." (RuneLightPlugin.java:2702–2705)

**Files:** `setAlphaPass` RuneLightPlugin.java:2706–2720, `drawPass` :3651–3673, SceneTargets.java:55–58, 451–454,
`resolveAlpha` :416–427; frag.glsl:65–73, 151–169 (lit as drawn); composite_frag.glsl `overTransparent` :275–277,
reads at :381, :543, :646.

**How:** `PRE_PASS_ALPHA` routes draws to attachment 2 with `GL_SRC_ALPHA, ONE_MINUS_SRC_ALPHA / ONE,
ONE_MINUS_SRC_ALPHA` blending and `alphaPass=1`; frag.glsl then lights the fragment itself (sun/moon or cave light,
transmission, emissive, distance fog, edge fade) and writes `FragAlpha`. With MSAA the layer is drawn multisampled
and blitted to a plain texture (`resolveAlpha`). The composite reads it per pixel and composites "over" the lit
opaque result, then fogs the sum.

**Settings:** none of its own (inherits `sunLighting`, `emissiveStrength`, `transmissionStrength`, `areaEnvironments`
through the shared includes).

**Cost:** per transparent fragment (full lighting in the scene shader, overdraw-dependent) + one blit per frame with
MSAA + one fetch per pixel in the composite.

**Duplicates / overlaps:** This is a **second lighting path**: frag.glsl:153–166 re-implements the composite's sun/
sky/cave/emissive/transmission/distance-fog chain "same lighting as the composite, without shadows" (frag.glsl:66), and
without AO, point lights, the light map, cloud shadow or volumetric fog. Anything tuned in composite_frag must be
tuned here too; it already differs (no point lights on transparent faces). The edge fade is applied twice to a
transparent pixel: here (:168) and again by the composite's sky-replacement of the opaque behind.

**Verdict:** **Keep the layer, merge the shading.** Keep the separate premultiplied layer (the only sane way to light
unsorted transparency in a deferred frame), but reduce frag.glsl's lighting to one shared function with the
composite's (one `shadeSurface(color, n, p, material, shadow, ao)` include) so there is one mechanism for direct
light, and document that transparency gets no AO/shadow by design.

## 3. Scene shaders (vert.glsl / frag.glsl)

**Purpose:** The stock RuneLite GPU plugin's model shaders, extended to write the G-buffer. (headers frag.glsl:25–63,
vert.glsl:25–77)

**Files:** vert.glsl `main` :83–136 (HSL → RGB :88–90, tint :89, octahedral normal decode :94–107, wind sway :108,
depth bias :114, border/inner color :121–123, texture id/material unpack :125–128, texture animation :129–132);
frag.glsl `main` :94–189 (texture path :97–113 with a second `textureLod(…, 0)` fetch for the alpha test :101–104,
HSL/smooth-banding/ground-blending :115–127, colorblind :130–132, face normal from derivatives :139–146, material
pack :149, alpha-pass lighting :151–169).

**How:** Classic forward vertex pipeline; the fragment writes color (unlit), normal (smooth if present else the
derivative face normal, oriented toward the camera), material (packed, §4) and, in the alpha pass, a lit color.

**Settings:** `smoothBanding` (RuneLightConfig.java:798), `brightTextures` (:810), `colorBlindMode` (:834, compile-time),
`colorBlindIntensity` (:848), `emissiveStrength`/`transmissionStrength` (:242/:257 via `sceneMaterials.set`),
`anisotropicFilteringLevel` (:99), `drawDistance` (:112, uniform set but unused in the shader), `windSway*` (other
reader).

**Cost:** per scene fragment, overdraw-dependent; B1 "scene" 0.46 / 0.91 ms (yaw 1024 / yaw 0).

**Duplicates / dead (by reading):**
- vert.glsl:38–41 `FOG_SCENE_EDGE_MIN/MAX`, `FOG_CORNER_ROUNDING(_SQUARED)` — defined, never used (vanilla fog
  remnants). vert.glsl:59–60 `drawDistance`, `expandedMapLoadingChunks` uniforms — declared and set from Java
  (RuneLightPlugin.java:2011–2012) but unused in the shader. vert.glsl:81 `#include "edge_fade.glsl"` — its
  functions are not called in the vertex shader. frag.glsl:35 `fogColor` is used only in the alpha pass (:163).
- Debug switches: `//#define FRAG_UVS`, `//#define ZBUF_DEBUG` (frag.glsl:27–28, 48–50, 86–92, 171–188),
  `//#define ZBUF_DEBUG`, `//#define BIAS_DEBUG` (vert.glsl:27–28, 75–77, 111–118) — compile-time dead code.
- The colorblind matrix is applied here per fragment (frag.glsl:130) and again per UI pixel (fragui.glsl:79) — two
  places for one quantity, and `inverse(rgb2lms)` is computed per pixel (colorblind.glsl:38).
- The face normal is computed here by derivatives (frag.glsl:139) and the smooth normal is decoded per vertex; the
  material target carries the face normal so the composite can choose between them (composite_frag.glsl:204–209).

**Verdict:** **Keep, prune.** Remove the dead defines/uniforms/includes; hoist the colorblind correction to one place
(the final blend or the UI pass, not both); keep the two-normal write — it is what the point-light normal rule and
the MSAA surface test rely on.

## 4. Material target (`material_target.glsl`, `materials.glsl`)

**Purpose:** "The scene's material target, written by frag.glsl, read by the composite and the passes before it: r the
surface's material … g, b the triangle's own normal, octahedral … a [fake water end distance]." (material_target.glsl:1–11)

**Files:** material_target.glsl `materialPack` :25–36, `materialUnpack` :40–47, `materialFaceNormal` :60–62,
`materialEnd` :55–57; materials.glsl `transmission` :22–26, `emissiveLight` :28–30; SceneTargets.java:16–17;
SurfaceMaterials (other reader) feeds the bits via `tex.x >> 9` (vert.glsl:127).

**How:** Seven bits in r: emissive level (2), transmissive (1), water class (2), snow class (2); the face normal in gb
(octahedral, 8 bits each); a = fake-water distance-to-end. Unpacked to a vec4 (emissive, transmissive, water 0/0.5/1/1.5,
snow) by the composite, AO and MSAA surface test.

**Settings:** `emissiveStrength`, `transmissionStrength` (set on the scene program and all six composite programs).

**Cost:** one RGBA8 write per scene fragment, one fetch per pixel in AO/composite (+ one per sample in the MSAA edge
tests).

**Duplicates:** `materialUnpack` is called twice per pixel in ao_frag.glsl (:61 and :73) for the same texel. The
"water" class overlaps the water readers' own data texture (`waterAoSurface`, `WATER_DATA_UNIT`): two sources for "is
this pixel water" (other reader's area, noted here because the composite tests both, composite_frag.glsl:418, 521).

**Verdict:** **Keep as is.** One compact mechanism; only tidy the double unpack.

## 5. MSAA (multisample targets, two-surface composite, mark + edge passes)

**Purpose:** "With MSAA they are multisample textures that stay unresolved: the composite lights each sample of the
pixels where the surface changes … and every other pass reads sample 0." (SceneTargets.java:7–9)

**Files:** `sceneSamples` RuneLightPlugin.java:1195–1205; `shaderSamples` + recompile on change :1188–1189,
1910–1920; `initFbo` `glEnable(GL_MULTISAMPLE)` :1787–1794; SceneTargets.java `texture` :505–509 (fixed sample
locations), `resolveAlpha` :416–427; composite programs `COMPOSITE_EDGES`/`COMPOSITE_MARK` :291–331; the stencil
decision and the two extra draws :3480–3511; `hasWindowStencil` :2723–2733; `configurePixelFormat(0,0,8)` :783;
Upscaler.java:18–19, 51–55 (stencil renderbuffer); scene_samplers.glsl:1–13; composite_frag.glsl:548–624 (`sameSurface`,
`twoSurfaces`, `shadeSamples` ×2), `skyShowsAt` :291–298.

**How:** The sample count is the setting's, or the driver's forced count, clamped to `GL_MAX_*_SAMPLES` (1195–1205);
if it differs from what the shaders were compiled for, every program is recompiled (`MSAA_SAMPLES` define → `sampler2DMS`).
The scene draws multisampled into unresolved textures. The composite's full pass shades sample 0's surface, averaging
the colors of samples that agree in depth/normal/material (tolerances :552–554); a `MARK` pass writes stencil 1 where a
pixel has a second surface, and an `EDGES` pass shades that second surface (first disagreeing sample's geometry, mean
of its colors) and blends it over by its sample share. Without a stencil (macOS window at 100 %: "it draws into an
IOSurface's framebuffer, color alone", :332–335) the edge pass runs over every pixel and discards itself. Only the alpha
layer is resolved (blit). AO, denoise, fog, clouds and water read sample 0 only.

**Settings:** `antiAliasingMode` (DISABLED/2/4/8/16; default **MSAA_2**, RuneLightConfig.java:86–96).

**Cost:** per pixel ×N for scene bandwidth and clears; per pixel in the composite: the sameSurface loop (N−1 depth +
normal + material fetches, :564–571) in **all three** of FULL, MARK and EDGES — the FULL pass does it to average
colors even for one-surface pixels; MARK does it again; EDGES does it a third time for marked pixels. Plus a stencil
clear and two extra fullscreen draws per frame. B1 was taken at the default MSAA_2 (not recorded explicitly; by
reading of the default). The comment at :560–563 records that one combined program was 9× slower — the split is the
mitigation, not a cure.

**Duplicates / overlaps / dead:**
- Interaction with render scale (the user's "weird scaling for the MSAA", HANDOVER B8): below 100 % the scene is
  multisampled at the reduced size, the composite resolves at that size into the upscaler's target, then Catmull-Rom
  upscales — so MSAA is spent on pixels that are then resampled anyway, and the stencil lives in a
  `GL_DEPTH24_STENCIL8` renderbuffer whose depth half is never used (Upscaler.java:53). Above 100 % (render scale 200
  in B1's method) MSAA and supersampling stack. **Two AA mechanisms for one quantity.**
- The half-res passes (AO/denoise/fog/clouds/water) see only sample 0, so an MSAA edge gets AO/shadow/fog from one side
  only; the edge pass then upsamples with the second surface's depth/normal, giving a slightly different weight set —
  a third source of edge difference.
- `skyShowsAt` loops all samples per tap × 4 taps per cloud texel (:316–319).
- `SceneTargets.OPAQUE_BUFFERS`'s "fixed sample locations" comment and the `GL_MAX_DEPTH_TEXTURE_SAMPLES` clamp are
  the only platform guards; forced driver AA (`GL_SAMPLES` on the default FBO, :1199) silently overrides the setting.

**Verdict:** **Restructure — one AA path.** Under "performance first at 4K" and "one mechanism per quantity", MSAA on a
deferred G-buffer with a per-sample surface split is the most expensive way to antialias this scene (N× bandwidth on
five targets, three surface tests per pixel). Replace it with a single path chosen for the render-scale design of A5:
either render scale ≥ 100 % as the only supersampling and a cheap post filter (FXAA/SMAA-1x on the composite output,
GL 3.3, no compute), or keep MSAA_2 only for the scene and resolve *before* the composite (losing per-surface lighting
at edges, which the current code shows is only visible at silhouettes). Default must not be MSAA_2 while the chain
is 19 ms at 4K. Removing it also removes `COMPOSITE_EDGES`, `COMPOSITE_MARK`, `windowStencil`, the pixel-format
stencil request, the upscaler's stencil renderbuffer, `resolveAlpha`, `scene_samplers.glsl` and `shaderSamples`
recompiles — the single largest simplification available in this area.

## 6. Render scale and the upscaler

**Purpose:** "Render scale below 100%: the composite and the weather draw into this target at the scene's reduced size
… and draw() scales it up to the window (upscale_frag.glsl). The interface is drawn afterwards at the window's full
resolution." (Upscaler.java:5–10)

**Files:** config/RenderScale.java:3–36 (FULL/75/50/25/UNSCALED, `factor(backingScale)`); `renderFactor`
RuneLightPlugin.java:4768–4771; `initFbo` :1779–1804 (`halfRatio = max(1, 2·renderFactor)` :1796; upscaler init only
when ≠ 1 :1797–1800); LOD bias :1942–1948; `glDpiAwareViewport(…, renderFactor)` :4757–4766; `frameViewport` :1977–1983;
`compositeScene` target selection :3472–3483, upscale draw :3537–3545; Upscaler.java `init` :38–63, `draw` :90–100;
upscale_frag.glsl:15–54; halfmap.glsl:5–15 (`halfRatio`).

**How:** The scene targets are allocated at window × backing × factor; the viewport is scaled to match; textures get a
negative LOD bias of log2(factor) so mips follow the window, not the reduced scene. Below 100 % the composite and weather
render into the upscaler's RGBA8 target at scene size, then `upscale_frag` blends bilinear → five-tap Catmull-Rom by
`sharpness`, clamped to the 2×2 neighbourhood to avoid ringing. The half-res passes stay at half the *window*
(`halfRatio` 2 at 100 %, 1 at ≤ 50 %, SceneTargets.java:75–78) so their grain is not stretched by the upscale; every
half-res consumer uses `halfToFull`/`fullToHalf` with the ratio. The interface is drawn after, at full resolution.
`UNSCALED` cancels the Retina backing scale (RenderScale.java:9–10, 24–27).

**Settings:** `renderScale` (:58), `upscaleSharpness` (:74, default 40 %).

**Cost:** per window pixel, one pass: 5 `texture` + 4 `texelFetch` (upscale_frag.glsl:35–49). B1 did not list
"upscale" separately (render scale 25 % frame 3.95 ms is the end-to-end result). Allocation per event.

**Duplicates / overlaps / dead:**
- Three resolution concepts: window (`width/height` via `getScaledValue`), scene (`sceneTargets.getWidth()`), half
  (`aoWidth`), with `halfRatio` as a float that is 2 at 100 %, 1.5 at 75 %, 1 at ≤ 50 % — at 75 % the half texel grid
  maps to scene pixels non-integrally (`floor(h·1.5)`, halfmap.glsl:8–10), which is where "weird scaling" would show.
- The dither (`ign`, §13) is static in *half-res texel* space; at 25–50 % on Windows that is 2–4 window pixels per
  grain (HANDOVER A5's concern).
- The upscaler's FBO carries a `DEPTH24_STENCIL8` renderbuffer only for the MSAA edge stencil (§5).
- `UNSCALED` is a second way to express "50 % on Retina" — a fifth enum value for a platform special case.
- The UI has its own, unrelated scaler (§15) with six modes.

**Verdict:** **Keep, simplify with §5.** Catmull-Rom with the neighbourhood clamp is the right GL 3.3 upscaler; keep
it as the one supersampling/undersampling mechanism. Fold `upscaleSharpness` into a constant (40 % is already the
default; a slider is one more setting the user asked to shed) or keep it as the only knob. Restrict `halfRatio` to
{1, 2} (drop 75 % or make 75 % use ratio 2) so the half grid always divides the scene grid. Drop the stencil
renderbuffer when §5 goes. Verify the dither at 25–50 % on Windows before any of this lands (A5).

## 7. Half-resolution geometry and the AO pass as the half-res hub

**Purpose:** "Half resolution geometry, written by the AO pass for the top-left pixel of every 2x2 block: r NEAR /
view depth (0 for sky), g the octahedral normal, two 12 bit numbers in one float … The composite's depth- and
normal-aware upsamples of AO and fog read their taps' geometry from here with one small fetch." (halfres.glsl:1–6)

**Files:** halfres.glsl:16–37 (`halfViewDepth`, `halfPack/Unpack`, `halfPackGeometry`, `halfNormal`); halfmap.glsl;
octahedral.glsl; ao_frag.glsl:38–54 (writes `FragAo` geometry and the default `FragShadow` for every texel, before any
early-out); SceneTargets.java:69–78, 129–151; `compositeScene` gating `halfPass = ao || frameFog || shadows || ground
|| sky` RuneLightPlugin.java:3315, pass :3316–3362.

**How:** One fullscreen triangle at half size reads depth + normal at `halfToFull(px)` and packs them into RG32F;
the same pass writes the four half-res quantities the composite needs (r sun/moon shadow, g AO, b snow/puddle cover,
a sky visibility) into `texShadowRaw`. The fog pass (other reader) renders at the same size and reads this geometry;
the denoise (§9) and the composite's upsample (§10) read it for their weights. `aoSamples = 0` runs the pass for its
geometry alone when AO is off but fog/shadows are on (ao_frag.glsl:17, 67–69).

**Settings:** indirectly all of `ambientOcclusion`, `shadows`, `volumetricFog`, weather, skylight (the gate).

**Cost:** per half-res texel; B1 "AO + skylight" 1.04 / 0.91 ms (includes the PCSS shadow lookup, the skylight trace
and the weather ground cover, which are all in this one pass). At 25 % scale 0.14 ms.

**Duplicates / overlaps:**
- The AO pass is five mechanisms in one program: SSAO, PCSS shadow filtering (`shadowFactor`), skylight visibility
  (`skylightVisibility`), weather ground cover (`weatherGroundAo`), water depth test. All share one `ign` noise and
  one early-out structure, so none can be skipped per texel without the others.
- Geometry is read twice at full res by the AO pass (depth, normal, material), once more by the fog pass (frame.glsl
  samplers) and again by the composite; the half-res copy is a third representation of depth+normal.

**Verdict:** **Keep the hub, rename and restructure.** Writing packed half-res geometry once and reading it from all
upsamples is right for GL 3.3 (no compute, no shared memory). But the pass should be named for what it is (the
half-res pass), the shadow lookup should move to B6's shadow mechanism when it lands, and the `halfPass` gate should
be per-quantity so that "AO off, fog on" does not still pay the shadow and skylight code paths.

## 8. Screen-space ambient occlusion

**Purpose:** "Screen-space ambient occlusion at half resolution. Normal-oriented hemisphere samples in world space,
tested against the depth buffer. Noisy by design; the denoise blurs it depth-aware before the composite upsamples it."
(ao_frag.glsl:3–4)

**Files:** ao_frag.glsl:28–36 constants, :80–132 loop; uniforms set RuneLightPlugin.java:3325–3326; composite use
composite_frag.glsl:405–406, 414, 430, 450, 468 (`AO_DIRECT 1.0` :49).

**How:** `aoSamples` (6/8/12) cosine-weighted directions on a golden-angle spiral rotated by `ign`, lengths biased to
the surface; each sample is projected, its depth fetched, range- and thickness-weighted and angle-biased (:113–129).
Output `1 − occlusion/samples`. The composite applies `aoStrength` and multiplies *both* indirect and direct light
(`AO_DIRECT 1.0`, physically 0 per its own comment :48).

**Settings:** `ambientOcclusion` (:307, default on), `aoStrength` (:321, 100 %), `aoRadius` (:335, 75 % tile),
`aoQuality` (:347, HIGH = 12 samples).

**Cost:** per half-res texel × samples: 12 depth fetches + 12 `reconstructPosition` (a mat4 multiply each, :126) at
HIGH. Included in B1's 1.04 / 0.91 ms.

**Duplicates / overlaps / dead:**
- HANDOVER B4 notes the AO early-out problem: "open ground 0.94–0.97 defeats a ≥ 0.995 test" — by reading, the only
  early-outs are sky (:41) and fully under water (:75); there is **no** open-ground early-out, and the composite's
  gate (:403–404) is true whenever any of five features is on, so the upsample always runs.
- AO darkens direct light too (`AO_DIRECT`), which overlaps the shadow mechanism for the same quantity (sun
  occlusion); the skylight visibility (a, same target) is a second ambient-occlusion quantity from a different source
  (traced voxels), and `roomAmbientFill`/`enclosed` a third modulation of ambient (composite_frag.glsl:430–447; other
  reader, but it multiplies `ao` four times in that block).
- Three AO-related settings for one effect.

**Verdict:** **Keep, cut settings, fix the early-out.** Keep SSAO as the one screen-space ambient occlusion; fix
`aoQuality` and `aoRadius` to constants (MEDIUM 8 samples at half res is the usual sweet spot; measure), keep
`ambientOcclusion` as the one toggle, set `AO_DIRECT` to 0 (shadows own direct occlusion). Add a true skip: when the
denoised AO is ≥ 0.98 *and* flat normal, the composite's upsample can take the bilinear weight alone.

## 9. Denoise (the half-res filter)

**Purpose:** "Smooths the half resolution passes' dither in one pass: the AO pass's AO and sun shadow … and, when the
sun shafts jitter it, the volumetric fog, all in one depth-aware blur so the composite's small 2x2 upsample is left
with an even, fine grain at most." (denoise_frag.glsl:3–10)

**Files:** denoise_frag.glsl:22–70; SceneTargets.java `denoise` :365–382, `fboDenoise` :79–83, 151–157, 342–344;
RuneLightPlugin.java:3383, 3406–3413.

**How:** For each half texel: 21 taps (5×5 minus corners), weights fitted to `ign`, times a depth weight; the shadow
target (r shadow, g AO, b ground, a sky) also gets a normal weight (`nd⁴`); the fog gets depth only. Sky texels skip
unless fog is on. Writes `texShadow` and, when `denoiseFog`, `texFog`. Runs whenever the half pass ran (`denoise =
halfPass && program != 0`).

**Settings:** none directly.

**Cost:** per half-res texel: 21 × (geometry fetch + shadow fetch [+ fog fetch]) ≈ 42–63 fetches. B1 "AO/fog filtering"
0.59 / 0.52 ms; 0.03 at 25 %.

**Duplicates / overlaps:**
- Smoothing happens **twice**: the 21-tap denoise here, then the composite's 2×2 depth/normal-aware upsample (§10) —
  the comment acknowledges it replaces "the two passes of a 5 tap blur" but a 4×4 upsample footprint was rejected as
  too slow (composite_frag.glsl:119–120). Both are spatial filters of the same dither.
- It filters four quantities with one weight set (shadow, AO, ground cover, sky visibility) — the normal weight that
  keeps contact shadows crisp also sharpens the snow cover, which has no business being normal-weighted.
- The fog's denoise is conditional on `hasShafts` (RuneLightPlugin.java:3393) — a second path (`fogFbo(denoised)`
  SceneTargets.java:325–346) with its own FBO and draw-buffer state toggle (:370–374).

**Verdict:** **Keep one filter, merge the other.** Keep this pass as the single spatial filter of the half-res
quantities (it is cheap at half res and GL 3.3-friendly), and make the composite's upsample a plain bilinear-by-
geometry 2×2 with no extra normal power (drop the second denoise). Alternatively replace both with a temporal
accumulation — but that needs motion vectors this frame does not have; not for now. Reduce to 13 taps if the grain at
25–50 % on Windows turns out to be set by the upscale, not the filter (A5 captures decide).

## 10. Composite upsample of the half-res quantities (two implementations)

**Purpose:** "Ambient occlusion and shadows: 2x2 depth- and normal-aware upsample of the half resolution pass … The
volumetric fog's upsample uses the same taps, so it is done in the same loop when the fog is on." (composite_frag.glsl:106–108)

**Files:** composite_frag.glsl `upsampleAoFog` :110–154 (sets the global `skyUpsampled`, skylight_ambient.glsl:9);
fog_apply.glsl `upsampleFog` :18–34, `applyVolumetricFog` :52–57; callers composite_frag.glsl:383 (sky pixels), :405
(surfaces), :544 (fallback when `fogDone` is false).

**How:** Bilinear weights of the four half texels around `fullToHalf(px)`, each scaled by a depth weight (fog: half-at-
3 % relative depth; shadows: 1 % with a `nd⁸` normal term). Fallback to the nearest texel if weights vanish.

**Settings:** none.

**Cost:** per pixel: 4 geometry + 4 shadow [+ 4 fog] fetches, ~12 fetches; part of B1's composite 1.38 / 1.45 ms.

**Duplicates:** **Two upsamplers of the fog**: `upsampleAoFog` (with AO) and `upsampleFog` (fog_apply.glsl), with
*different* depth weights (`z·0.01+1` vs `z·0.03+1`), selected by whether the pixel took the half-pass branch. Sky
pixels take `applyVolumetricFog` (second implementation) always.

**Verdict:** **Merge into one** `upsampleHalf(px, z, n)` returning a struct; one depth weight. Delete `upsampleFog`.

## 11. The full composite (six programs, uniforms, early-outs, debug views, legacy "parts" path)

**Purpose:** "Runs once per pixel over the finished scene: sky, lighting, shadows, AO and fog. Compiled once for each
of its passes, so that each program holds only its own path." (composite_frag.glsl:3–8)

**Files:** RuneLightPlugin.java `CompositeProgram` :287–331, `compileComposite` :1257–1261, `locateComposite`
:1392–1448, `useComposite` :2896–2960, the draw :3450–3512; composite_vert.glsl:1–27 (fullscreen triangle + 11
`flat` sky constants per vertex); composite_frag.glsl `shadeScene` :348–546, `main` :626–653; `createTemplate`
`composite_mode`/`debug_view` :1231–1243.

**How:** Six compiled variants of one source: FULL, CLOUDS, WATER, CLOUDS_WATER, EDGES, MARK (`COMPOSITE_PASS` define).
`useComposite` pushes ~60 uniforms (frame, sky cache, night sky, AO, camera, day cycle, weather, environment, shadows,
rooms, edge, ground, fog, clouds, lightning, materials, water) on each program it uses — up to three times per frame
(half pass, full, edges). `shadeScene` per pixel: sky branch (procedural sky or fog colour + transparency + fog +
triangular dither) or surface branch (upsample → wet surface → sun/sky/room/skylight ambient → point lights/light
map → emissive → debug views → shade → puddles/water/fake water → distance fog → edge fade → transparency → fog).
`compositePart` is a `const int 0` in normal builds (`composite_mode`), so all `compositePart` branches fold away;
`debugView` is likewise a const from the setting (recompile on change).

**Settings:** `debugView` (:908, compile-time), plus everything the sub-mechanisms take (`ambientOcclusion`,
`aoStrength`, `shadows`, `pointLights`, `proceduralSky`, `volumetricFog`, `starBrightness`, `nebulaStrength`,
`emissiveStrength`, `transmissionStrength`, `water*`, `clouds*`…) — the composite is where most of the 69 settings land.

**Cost:** per pixel, B1 "sky/fog + final blend" 1.38 / 1.45 ms (0.40 at 25 %). The only true early-outs are the
sky branch (:371) and `linearLit` (:511–538: requires fog on, no wet, no transparency, not under water, no distance
fog, no edge) — HANDOVER B4 asks which early-outs fire; by reading, `linearLit` is the only surface one and it saves a
sRGB round trip, not a lighting evaluation. The half-pass gate (:403–404) is effectively always true.

**Duplicates / dead / variants:**
- `lightingEnabled` is a uniform that is always set to 1 (RuneLightPlugin.java:2910) and tested per pixel here (:415,
  :455) and five times in water.glsl — dead switch.
- `runelight.compositeCombined` system property (:1234, :3457): when false, the composite runs **five extra fullscreen
  passes** into the "parts" texture arrays (ambient, sun, points, surfaces, atmosphere) and reads them back
  (:462–478, :525–529) — a diagnostic the timers' `AMBIENT..SURFACES` stages exist for (GpuTimer.java:35–38). Legacy;
  the normal build compiles it out but the Java path, the FBOs, the sampler binding and the GpuTimer stages remain.
- Eight debug views as compile-time branches inside the hot path (:359–367, 482–506), each recompiling all programs on
  change (:1105–1114).
- `skyBehind` is evaluated for sky pixels (:376) and again for edge-faded surface pixels (:541) — the atmosphere is
  computed twice for a pixel at the world's edge; `edgeFadeAmount` is also computed in `skyShowsAt` for every cloud
  texel's four taps (:297).
- The sky's triangular dither (:385–387) re-implements `ign` inline with the same constants (§13).
- `composite_vert.glsl` computes 11 sky constants per vertex of every fullscreen triangle, including for the AO,
  denoise, upscale, shadow-copy and shadow-max programs that share the vertex shader and ignore them (its own
  comment :3–5) — harmless (3 vertices) but it drags `sky.glsl` into every program's compile.
- Uniforms are located by string 40+ times per program × 6 programs on every reload (`locateComposite`), and the
  whole set is set per use rather than through a UBO (the one UBO holds five floats, RuneLightPlugin.java:137, 1869–1883).

**Verdict:** **Restructure.** (a) Delete the `compositeCombined` parts path, its FBOs, sampler and timer stages, and the
`lightingEnabled` uniform. (b) Move the per-frame uniform set into one UBO written once per frame (GL 3.3 has UBOs)
so the six-program split stops costing CPU and the "set the same 60 uniforms three times" goes away. (c) Replace the
debug-view compile-time branches with a separate tiny debug program that reads the targets (no recompile of the
production programs on a debug toggle). (d) Keep the half/full split (§12) but not the MSAA split (§5). The composite's
per-pixel work is the B4 critical path; the structure above is what stands between measuring it and cutting it.

## 12. Half-resolution clouds / water-shading pass (composite variants)

**Purpose:** "The clouds, which the composite's sky filters, and the water's shading at half resolution, with
everything bound for the composite: in one pass when both are drawn." (RuneLightPlugin.java:3424–3426)

**Files:** RuneLightPlugin.java:3426–3448, `scissorHalf` :3179–3204; SceneTargets.java `bindHalf` :244–300;
composite_frag.glsl `cloudsHalf` :302–328, `skyShowsAt` :291–298, `main` :635–642; `CLOUD_HALF_UNIT` :370.

**How:** Picks one of three programs by what is on (clouds, water, both), binds an FBO whose two attachments are the
clouds (RGBA16F, cleared to clear sky) and the water's packed shading (RGBA32UI, cleared to none), scissors to the
union of the water's rect and the cloud rows, draws one triangle. The full composite then filters the clouds from
`cloudHalf` and reads the water shading from `WATER_NOISE_UNIT` (reused unit, :3445–3446).

**Settings:** `clouds`, `water` (other readers); `proceduralSky`.

**Cost:** per half-res texel inside the scissor; B1 clouds 0.16 / 0.18, water 0.07 / 0.11 ms.

**Duplicates / overlaps:** This is the second half-res pass (after §7) with its own FBO, clears and size logic;
`skyShowsAt` per cloud texel repeats the composite's own sky test (:371) four times per texel. The water-shaded
target hitches a ride on the water *noise* unit (:3433–3446) — an alias that works only because the composite never
samples noise and shading in the same program.

**Verdict:** **Keep as is for now**; cheap. When §7's hub is restructured, fold the cloud rows and water rect into the
same half-res pass's draw buffers (one half-res FBO, one scissor) so the frame has one half-res stage, not two.

## 13. Dither (`ign.glsl`) and its copies

**Purpose:** "Interleaved gradient noise, 0..1, static in screen space. The half resolution passes rotate and jitter
their samples by it; the denoise then averages it away." (ign.glsl:1–3)

**Files:** ign.glsl:4–6; users ao_frag.glsl:58, fog_frag.glsl:138, skylight_ao.glsl, water_common.glsl (grep); inline
copy in composite_frag.glsl:385–387 (sky banding dither, two evaluations with swapped constants); the clouds use
`cloudJitter` (clouds.glsl, other reader) — a separate jitter function.

**How:** `fract(52.98·fract(dot(px, (0.0671, 0.0058))))`, static per screen position. HANDOVER A5 flags it: at render
scale 25–50 % on Windows each grain is 2–4 window pixels and does not move, so the "even fine grain" the denoise
leaves becomes a visible static pattern.

**Settings:** none.

**Cost:** negligible per texel.

**Duplicates:** four dither sources for "break up a pattern": `ign` (AO/shadow/fog/skylight/water), the inline sky
dither, `cloudJitter`, and the upscaler's lack of one. One quantity, four mechanisms.

**Verdict:** **Restructure.** One `dither(px, frame)` include with a temporal term (frame index rotates the IGN, GL 3.3
needs nothing new), used by all half-res passes, the sky dither and the clouds; then judge at 25–50 % on Windows per
A5. Blue-noise texture is the alternative if the temporal term flickers without TAA.

## 14. Edge of the world: circle, fade and zone frustum culling (`EdgeFade`)

**Purpose:** "The edge of the drawn world, and what is drawn inside it … the world ends at a circle of the draw
distance around the camera's focal point (the player), which turning does not move … everything fades into the sky
behind it toward the circle … Zones are also tested against the view frustum with generous bounds." (EdgeFade.java:5–17)

**Files:** EdgeFade.java (`clientDrawDistance` :63–66, `radius` :74–77, `fogginess` :91–96, `targetWidth` :99–103,
`update` :117–132, `setFrustum` :143–159, `inFrustum` :162–183, `zoneVisible` :196–205, `modelVisible` :208–217,
`Uniforms` :220–235); RuneLightPlugin.java `updateEdge` :2208–2220, `zoneInFrustum` :2223–2228, `setupGpuFlags`
`ZBUF_ZONE_FRUSTUM_CHECK` :900–902, `edgeFade.setFrustum` :2035; edge_fade.glsl:9–18; users frag.glsl:168,
composite_frag.glsl:297, 532, 540–542.

**How:** The client is told a draw distance large enough that its camera-centred square covers the player-centred
circle (+10 tiles margin); zones outside the circle or the five-plane frustum (padded 4 tiles sideways, 10 up, 2 down)
are skipped via the `zoneInFrustum` callback; models outside radius+6 tiles are skipped (`modelVisible`, called from
the draw callbacks, other reader). The fade width eases over ~2 s between 12 tiles (clear) and 60 % of the radius
(fog), driven by weather fog and the area's 117 HD fog depth. The shader fades surfaces into `skyBehind` toward the
circle and the loaded scene's bounds.

**Settings:** `drawDistance` (:112), `expandedMapLoadingChunks` (:125), `areaEnvironments` (fog depth input).

**Cost:** CPU per zone per frame (a few multiplies, ~500 zones); GPU per pixel: one `length` + smoothstep, and for
pixels inside the fade a second atmosphere evaluation (§11). HANDOVER B7 lists "zone frustum culling (Lumina's
implementation)" as a segment — this renderer already has a frustum + circle test; what Lumina adds should be
measured against this one, not assumed missing.

**Duplicates / overlaps:**
- **Two "world's end" mechanisms**: the edge fade (sky-behind blend toward the circle) and the distance fog
  (`envFogAmount`, environment.glsl, other reader; composite_frag.glsl:530, 539; frag.glsl:165–166) both blend the
  surface toward a fog/sky colour by distance; the edge fade's width itself is driven by the fog settings. Rule 2 says
  one of these owns "fade with distance".
- The client's own square + the circle + the frustum are three culls; the client's cull is unavoidable, the other two
  are ours and are evaluated in the same call (`zoneVisible`), which is fine.
- Dead in vert.glsl: the `edge_fade.glsl` include and the vanilla `FOG_SCENE_EDGE_*` defines (§3).

**Verdict:** **Keep the culling as is** (it is the right shape and cheap); **merge the fade with the distance fog**
into one "distance → sky" quantity in the composite (edge fade becomes the far clamp of the fog curve), applied once,
and drop the duplicate from frag.glsl's alpha path when §2's shading is unified.

## 15. Interface texture, upload and UI scaling

**Purpose:** "Uploads the client's interface canvas to its texture through a pixel buffer … the copy runs on worker
threads, split in bands, while the client thread submits the composite; the texture is filled from the buffer after."
(InterfaceUpload.java:12–18)

**Files:** InterfaceUpload.java (`start` :38–69, `await` :74–94, `finish` :99–118, `THREADED_MIN` :24, `THREADS` :25);
RuneLightPlugin.java `initInterfaceTexture` :1757–1769, `prepareInterfaceTexture` :3928–3944, `finishInterfaceUpload`
:3949–3962, `drawUi` :4100–4155, `initVao` :1666–1697 (the UI quad VAO, also the VAO every fullscreen triangle binds);
fragui.glsl:27–84, vertui.glsl:27–56, scale/bicubic.glsl, scale/xbr_lv2_*.glsl, scale/hybrid.glsl, colorblind.glsl;
config/UIScalingMode.java.

**How:** Each frame the PBO is orphaned (`glBufferData` STREAM), mapped, and the canvas ints copied into it by two
worker threads in bands (or inline below 512×512); the client thread meanwhile submits the composite; `finish`
unmaps and `glTexSubImage2D`s into the RGBA texture. `drawUi` blends the texture over the window with premultiplied
`ONE, ONE_MINUS_SRC_ALPHA`, the `alphaOverlay` tint, and the selected resampling (nearest/linear via texture filter;
Mitchell/Catmull-Rom/xBR/hybrid in the shader) when stretched mode resizes it.

**Settings:** `uiScalingMode` (:137, default HYBRID; compile-time, recompiles every program on change :1105–1114),
`colorBlindMode`/`colorBlindIntensity` (applied here too).

**Cost:** CPU per frame: the copy (~0.5 ms at 1440p on one thread per its comment, split across two workers) and
`UI_UPLOAD` wait; GPU per window pixel: one textured quad (plus xBR's ~25 taps when selected). B1 CPU "client UI 1.4
(not ours)" is the client drawing the canvas; our part is `UI upload`, not separately listed.

**Duplicates / dead:**
- Second scaler in the frame after the upscaler (§6): six UI modes with three shader files (xBR alone 315 lines) for
  the RuneLite "stretched mode" case. The scene upscaler and the UI scaler solve the same problem (resample to the
  window) with different filters and a different setting.
- Colorblind correction is applied to the scene in frag.glsl *and* to the UI here — two sites (§3).
- `interfaceFilter` toggling via `glTexParameteri` on the texture every mode change (:4137–4143) is the comment's own
  "probably better done with sampler objects".

**Verdict:** **Keep the upload** (the banded worker copy is a real win and platform-neutral); **cut the UI scaler to
one mode** (HYBRID, the default, or Catmull-Rom shared with §6's shader), removing `uiScalingMode`, the xBR/bicubic
files and the recompile-on-change. Apply colorblind once, here, over the final frame — then it leaves frag.glsl.

## 16. Frame pacing and sync (`paceFrame`, `setupSyncMode`)

**Purpose:** "Frame pacing (Lumina's): with FPS unlocked and vsync off the client's own limiter sleeps whole
milliseconds and lands late every sixth frame (a 14/21 ms saw-tooth at a 60 target), so the frame is paced here
instead: sleep to within 1.5 ms of a deadline on a fixed grid, then spin; the client gets no target." (RuneLightPlugin.java:201–204)

**Files:** RuneLightPlugin.java:201–241 (`paceFrame`), `setupSyncMode` :1143–1186, call after swap :4041–4042,
`pace on|off` command :4857–4862, config-change hook :1047–1053.

**How:** `setupSyncMode` sets the client's unlocked-FPS flag, the swap interval (0/1/−1 adaptive) through the AWT
context, and, when the swap interval is 0 and pacing is "here", a nanosecond frame target; the client's own target is
set to 0. After `swapBuffers`, `paceFrame` sleeps 1 ms at a time until 1.5 ms before the deadline, then spins; a frame
more than one period late restarts the grid.

**Settings:** `unlockFps` (:149), `vsyncMode` (:161), `fpsTarget` (:174). Without `unlockFps` the client runs its 20 ms
timer and sync is forced OFF (:1148–1151).

**Cost:** per frame fixed; B2: at a 60 target p5/p50/p95/max 16.66/16.67/16.67/16.70 ms, stdev 0.00, vs the client's
15.0/16.4/19.5/23.2. The spin burns one core for up to 1.5 ms per frame.

**Duplicates:** two limiters for one quantity (the client's and ours), chosen by `paceHere` (a debug toggle, default
true, no setting). With vsync ON both are off and the swap blocks (`SWAP` CPU stage, CpuTimer.java:18–19).

**Verdict:** **Keep as is**; remove the `pace off` A/B path once B2 is accepted (it is). Three settings is already the
minimum RuneLite users expect here.

## 17. GPU timers (`GpuTimer`)

**Purpose:** "Non-blocking GPU timing per frame pass. Each frame gets a slot of GL_TIME_ELAPSED queries; a slot is only
read back when it comes around again several frames later, and only if the driver says the results are ready, so
measuring never stalls the pipeline. Uses elapsed-time queries rather than timestamps: Apple's OpenGL driver returns
0 for GL_TIMESTAMP." (GpuTimer.java:5–13)

**Files:** GpuTimer.java (`begin` :124–140, `end` :142–150, `endFrame` :155–211, `setEnabled` :109–118, stages :17–47);
RuneLightPlugin.java `gpuTimer.setEnabled(false)` :1862, `gpuTimer.endFrame()` :4071, `timers` command :4863–4892,
`begin` sites throughout `postDrawToplevel`/`compositeScene`.

**How:** Four slots × 40 queries; `begin(stage)` ends the running query and starts a new one tagged with the stage;
`endFrame` advances the slot and reads the slot filled three frames ago if its last query is available, summing
pieces per stage and keeping "the last valid execution" for stages absent this frame (:195–200). 22 named stages.

**Settings:** none (the "Time GPU passes" setting the comments refer to, :59–61, and HitchMonitor.java:133, is gone).

**Cost:** per frame fixed, a few queries; the comment notes that on Apple's driver ending a query stalls the CPU
(:59–61) — which is why it is off by default.

**Duplicates / dead / a defect (by reading, not run):**
- **`preSceneDrawToplevel` calls `gpuTimer.setEnabled(false)` every frame (:1862).** The `timers` command enables it
  (:4867–4871) and tells the user to run it again in a few seconds; the next frame disables it again and
  `setEnabled(false)` drops the in-flight slots (:115–116). As written, `timers` can never report GPU values, and the
  hitch monitor's "GPU times not read / timings are off" branch always fires. B1's GPU numbers predate the comment at
  :1861 ("the timings panel is gone, 2026-10-30"). This needs a one-line fix before any B4 measurement.
- Stages `AMBIENT`, `SUN`, `POINT_LIGHTS`, `SURFACES` (GpuTimer.java:35–38) are only reached through the legacy parts
  path (§11). `RESOLVE` is begun but covers only a blit. `SKY_GRID` and `LIGHT_MAP` time uploads, not passes.
- CPU side: `getMs` is the last valid value, not an average, while `CpuTimer` is an EMA — the two "timers" smooth
  differently and the `timers` line prints both as if alike (:4872–4891).

**Verdict:** **Keep, fix the enable, prune stages.** It is the right design for macOS (elapsed queries, deferred
reads). Make the `timers` command (or a developer config) the owner of `enabled`, delete the per-frame disable, drop
the four legacy stages, and give both timers the same smoothing.

## 18. CPU timers (`CpuTimer`)

**Purpose:** "CPU time of the plugin's own per-frame steps and of the parts of the frame it doesn't control,
averaged." (CpuTimer.java:3–7)

**Files:** CpuTimer.java (`endFrame` EMA 0.95/0.05 :65–73, `untrackedMs` :87–98, 17 stages :10–42); call sites
RuneLightPlugin.java (CLIENT_SCENE :2059/2107, CLIENT_UI :2145/3967, SWAP :4038/4070, OUTSIDE :4092/1834,
BUFFER_MAP :1837–1843, UI_UPLOAD :3956–3958/4001–4003, and the lighting/weather/sky stages inside `compositeScene`
and `prepareLighting`). `cpuFrameMs` (:4072–4078) is a separate EMA 0.9/0.1 of scene-start → swap.

**How:** `System.nanoTime` pairs accumulated per frame per stage, then folded into the average; `last[]` kept for the
hitch monitor.

**Settings:** none. **Cost:** negligible.

**Duplicates:** three CPU frame figures: `cpuFrameMs` (EMA 0.9), `swapIntervalMs` (EMA 0.95, :4079–4087),
`CpuTimer.untrackedMs` — plus `FrameSnapshot.frame()` recording raw wall times (:4088). B3's "untimed 172 ms / 34–39
ms" came from exactly the gap `untrackedMs` names.

**Verdict:** **Keep as is**; one EMA constant shared with GpuTimer; the snapshot stays the ground truth.

## 19. Hitch monitor and watchdog (`HitchMonitor`)

**Purpose:** "Finds the cause of occasional long frames from the log. A frame that took more than twice the running
median (and more than MIN_MS) is logged with the plugin's CPU steps and GPU passes that were large in it, the garbage
collections that ran during it, and the periodic jobs that were flagged on it … a watchdog thread looks every couple of
milliseconds, and once a frame has run past the hitch threshold takes the client thread's stack a few times." (HitchMonitor.java:11–18)

**Files:** HitchMonitor.java (`endFrame` :91–158, `watch` :171–203, `text` :241–304, `gpuText` :306–329, thresholds
:38–53); job flags set at RuneLightPlugin.java:2561 (static shadows), 3260 (voxel build), 3372 (sky capture), 3849
(area hiding), 3917 (zone rebuild), 3977 (shader reload), 4652 (scene swap); `hitchMonitor.endFrame` :4090.

**How:** Rolling 64-frame median (re-sorted every 8 frames); after a 240-frame warm-up a frame > max(12 ms, 2×median)
produces a line with the five largest CPU stages ≥ 2 ms, the untimed remainder, GC deltas, the flagged jobs and the
watchdog's up-to-three stack samples (8 frames deep, 4 ms apart); the line waits up to 6 frames for the GPU slot.
Rate-limited to one per second.

**Settings:** none; always on (:4089 comment).

**Cost:** per frame a few array reads; a daemon thread sleeping 2 ms; stack sampling only during a hitch. B3's
attribution (RoomMap texels / LightMap glTexSubImage3D on the swap frame; 186 ms client scene build) came from this
tool.

**Duplicates / dead:** The GPU half of every line is dead while §17's per-frame disable stands ("GPU timings are off
(show timings turns them on)", :133 — the setting no longer exists). `LookMonitor` (debug-log-only, RuneLightPlugin.java
:3451–3455) and `FrameSnapshot` overlap as "what happened this frame" loggers.

**Verdict:** **Keep as is**; fix the text at :133 with §17. This is the instrument B3 depends on; the job list is the
scheduler's future work queue in disguise ("one heavy job per frame", HANDOVER B3).

## 20. Timings overlay and development tools (`RuneLightOverlay`, `FrameSnapshot`, `FrameCapture`, commands)

**Purpose (as it stands):** The timings panel is gone ("the timings panel is gone, 2026-10-30", RuneLightPlugin.java:1861).
`RuneLightOverlay.java` does not exist in the tree (verified by `find`). Its replacements are the `::runelight`
commands (`runCommand` :4825–4902: `cam`, `capture`, `snapshot [s] [uncapped]`, `pace`, `timers`, `set`) and the
command file (`CommandFile`, :196–198), FrameSnapshot.java (per-frame wall times → CSV + summary, :15–19),
FrameCapture.java (screenshot captures), `LookMonitor` (debug-log environment summary).

**Settings:** `weatherFieldMap` (RuneLightConfig.java:956–965) — "A small map of the weather around you in the timings
overlay" — **dead**: no reference outside the config (grep). `glErrorChecks` (:896), `debugView` (:908), `waterDebugView`,
`testLight`, `strikeLightning`, `showBuildings`, `dumpScene` form the Development section (seven settings).

**Cost:** per event; `screenshot` (:4162–4195) is a synchronous `glReadPixels` of the whole window per
`processDrawComplete` consumer — per event, but it stalls the pipeline when a consumer exists.

**Verdict:** **Remove `weatherFieldMap`**; keep the commands (they are the measurement protocol of HANDOVER rule 1);
`debugView` becomes the separate debug program of §11 so it stops recompiling production shaders.

## 21. Shader programs: template, compile, reload, hot reload

**Purpose:** "Recompile every program. On failure the previous programs stay in use, so a typo while editing a shader
shows an error instead of taking down the renderer." (RuneLightPlugin.java:1505–1508)

**Files:** `createTemplate` :1207–1254 (defines: `TEXTURE_COUNT`, `SAMPLING_MODE`, cluster/light-map constants,
`composite_mode`, `MSAA_SAMPLES`, `COLORBLIND_MODE`, `debugView`), `initProgram` :1263–1292 (16 programs: scene, UI,
6 composite, AO, denoise, shadow, shadow copy, shadow max, fog, water surface, upscale + weather's and sky cache's),
`initUniforms` :1294–1390, `reloadShaders` :1509–1611, `ShaderWatcher` (developer mode, :722–729, polled at :3974–3979),
config triggers :1105–1114 (`uiScalingMode`, `colorBlindMode`, `debugView`, `lightMap` → full reload) and :1910–1920
(sample count change → full reload).

**How:** One template with a key→define callback; every program is compiled from it; on reload all are compiled first
and swapped atomically, else deleted and the error chatted. The hot-reload watcher triggers a reload on file change
(flagged as a hitch job).

**Settings:** the five compile-time ones above.

**Cost:** per event; a full reload compiles ~20 programs (including six composites that each pull sky, clouds,
atmosphere, water, weather ground, light clusters, rooms, light map includes) — by reading, this is the "shader
reload" hitch job; no number in HANDOVER.

**Duplicates / dead:** `SHADOW_CACHE` is a `static final boolean = true` (:352–354) — a retired setting left as a
constant. `composite_mode`'s `uniform int compositePart` variant (§11). Uniform locations are looked up by string for
~300 names at every reload.

**Verdict:** **Keep, shrink the matrix.** Each compile-time setting removed (§5 MSAA, §15 UI mode, §11 debug view,
§3 colorblind site) removes a reason to recompile at runtime; what is left (texture count, cluster constants) is
build-time. Keep the atomic swap and the watcher.

## 22. Reverse-Z depth and clip control

**Purpose:** reverse-z depth (`GL_GREATER`, cleared to 0) with `glClipControl(GL_ZERO_TO_ONE)` where GL 4.5 exists,
else the [−1,1] convention (frame.glsl:12, 17–24, 33).

**Files:** RuneLightPlugin.java:839–842 (`glClipControl` under `OpenGL45`), :2052 (`GL_GREATER`), SceneTargets.java:66,
445 (`FAR_DEPTH = 0`); `depthZeroToOne` uniform set in `setFrameUniforms` :2740 and passed to fog/water/weather/sky
(e.g. :3396, :3525); frame.glsl `isSky`, `viewDepth`, `reconstructPosition` branch on it per call; vert.glsl:114 depth
bias in clip z.

**How:** On Windows/Linux with GL 4.5 the depth range is [0,1] and precision is maximal; on macOS (GL 4.1) it is
[−1,1] mapped to [0,1] by the driver, and `d − 0.5` maths recovers the view depth. Every full-screen shader branches on
the uniform per sample.

**Settings:** none. **Cost:** a uniform branch per depth read (coherent, ~free); macOS loses depth precision far from
the camera (the near-50 projection with no far plane).

**Duplicates:** two depth conventions in every reconstruction function; `linear_depth` in frag.glsl's dead
`ZBUF_DEBUG` assumes a third (vanilla 100/z).

**Verdict:** **Keep as is** — it is the correct GL 3.3–4.5 spread; just delete the dead debug variant.

## 23. GL error checking

**Purpose:** "Call glGetError after each draw step and log failures. Slow; leave off when measuring." (RuneLightConfig.java:896–905)

**Files:** `checkGLErrors` RuneLightPlugin.java:4778–4819; cached flag :250–251; called per zone draw (:3588, 3623,
3675) and at each stage end.

**How:** Loops `glGetError`, builds an exception for the caller's stack, rate-limits by error+caller.

**Settings:** `glErrorChecks` (default off). **Cost:** per draw call when on (a sync point each); a boolean test when off.

**Verdict:** **Keep as is**; developer-only.

## 24. Camera uniform buffer

**Purpose:** the stock plugin's five-float UBO (yaw, pitch, camera xyz) bound at binding 0 (RuneLightPlugin.java:137,
1869–1883; vert.glsl:47–53, frag.glsl:52–58).

**Cost:** per frame fixed. **Duplicates:** the camera position is also a plain uniform on every composite program
(`cameraPos`) and inside `FrameUniforms`' matrices; §11's UBO proposal would make this the one place.

**Verdict:** **Merge into the per-frame UBO** of §11.

---

## Ranked: the five heaviest or messiest things in this area

1. **MSAA on the deferred frame (§5)** — N× bandwidth on five targets, three per-sample surface tests per pixel
   (FULL, MARK, EDGES), a stencil that macOS cannot provide at 100 %, a stencil renderbuffer in the upscaler, and a
   second AA mechanism stacked on render scale. Default MSAA_2. Removing it is the biggest single cut in code and
   pixel cost available before B4 touches the lighting itself.
2. **The composite's structure (§11, §10)** — 1.38–1.45 ms (likely at 50 % scale, ~4× at 4K): six programs each
   given ~60 uniforms per use, a gate that is always true, one real early-out that saves only a colour-space round trip,
   the fog upsampled by two different functions, `skyBehind` evaluated twice at the world's edge, eight compile-time
   debug views and a legacy five-pass "parts" path still wired through Java, FBOs and timer stages.
3. **Two filters of the same dither (§9 + §10), and four dithers for one job (§13)** — 21-tap denoise then a
   normal-weighted 2×2 upsample, over a static IGN pattern that A5 says will read as a grid at 25–50 % on Windows; the
   sky, the clouds and the half-res passes each jitter differently.
4. **The half-res hub doing five jobs in one program (§7, §8)** — SSAO, PCSS, skylight, ground cover and water depth in
   `ao_frag`, gated together: "AO off" still runs the pass, and the AO early-out HANDOVER asks for does not exist. 1.04 /
   0.91 ms. Also where B6's shadow filtering currently lives, so it changes again when shadows are ported.
5. **The GPU timer is disabled every frame (§17)** — `gpuTimer.setEnabled(false)` in `preSceneDrawToplevel` means the
   `timers` command and the hitch monitor's GPU column cannot work as the code stands; B1's "re-measure at FULL before
   B4" is blocked by one line. Alongside: the dead `weatherFieldMap` setting, `lightingEnabled` always-1 uniform,
   `SHADOW_CACHE` constant, vert.glsl's vanilla fog defines and unused uniforms, the UI's six-mode scaler (§15).

Honourable mention: the transparency layer's second lighting path (§2) and the edge fade + distance fog pair (§14) are
each two mechanisms for one quantity under rule 2, but cheap.

## Settings touched by this area

23 of the 69: `renderScale`, `upscaleSharpness`, `antiAliasingMode`, `anisotropicFilteringLevel`, `drawDistance`,
`expandedMapLoadingChunks`, `uiScalingMode`, `unlockFps`, `vsyncMode`, `fpsTarget`, `ambientOcclusion`, `aoStrength`,
`aoRadius`, `aoQuality`, `smoothBanding`, `brightTextures`, `colorBlindMode`, `colorBlindIntensity`, `glErrorChecks`,
`debugView`, `weatherFieldMap`, `emissiveStrength`, `transmissionStrength`.

Proposed to go, by the verdicts above: `antiAliasingMode` (one AA path), `upscaleSharpness` (constant), `uiScalingMode`
(one mode), `aoRadius`, `aoQuality` (constants), `weatherFieldMap` (dead) — six fewer; `debugView` stays but moves off
the production shaders. Not verified by running anything; every per-pixel claim above needs the B1 timers (after the
§17 fix) at an explicit `renderScale=FULL` and at 25–50 % on a Windows GPU, per HANDOVER rule 4.
