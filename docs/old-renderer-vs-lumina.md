# The old RuneLight renderer (perf-wip) against Lumina (2026-10-30)

Read-only comparison of two trees in this repository. OLD = branch `perf-wip` at `~/Code/runelight-perfwip`
(package `com.midonarhel.lumina`, shaders in `src/main/resources/com/midonarhel/lumina/`, 42.7k lines of Java,
last commit 5fb9e4e0 "Terrain waterfalls"). NEW = branch `lumina` at `~/Code/runelight` (package
`com.midonarhel.lumina`, 27k lines, working tree with the uncommitted `frame/ZoneCulling`). Every claim below names
the file and line or function it was read from; nothing was run. Line numbers are of the files as they are today.

Contents: 1 shading model, 2 sky and fog, 3 architecture and cost, 4 verdict table, 5 proposed order.

## 1. Shading and lighting model

### 1.1 OLD: how a face was lit

**Where.** OLD was a deferred renderer. The scene pass wrote four targets: unlit colour, world normal, a lit
transparency layer and a material id (`frag.glsl:60-63`, `SceneTargets.java:6-9`). Opaque pixels were lit once per
pixel in the composite (`composite_frag.glsl` `shadeScene`, lines 348-546). Transparent faces were lit forward in the
scene pass with the same formula but no shadows (`frag.glsl:151-169`, comment at 65-66).

**Colours in.** The client's *lit* face colours, with 117 HD's capped undo of the model bake applied on the CPU
(`VertexShading.baseColor`, lines 201-224: `BASE_LIGHTEN 10`, `IGNORE_LOW_LIGHTNESS 3`, `LIGHTNESS_MULTIPLIER 3`,
light direction (1,1,1), `maxBrightness(s)` table 127/61/59/57/56/56/55; setting `removeBakedShading` default true,
`RuneLightConfig.java:359-369`). Terrain had its own undo, `GroundBlender.undoShading` (lines 345-361): lighten the
corners the vanilla light left dark (`LIGHTEN_MULTIPLIER 1.5`, `LIGHTEN_BASE 15`), darken the ones it lit
(`DARKEN_MULTIPLIER 0.5`), then **`GAIN 1.25` on every ground vertex**, capped at `MAX_LIGHTNESS 55`; called from
`SceneUploader.groundColor` (760-772). That gain is the "terrain matching": models lose their bake and gain lightness
through the capped undo, so flat ground was lifted by a quarter to keep up. Flat-shaded faces of round low-poly
shapes took the model's **unlit** face colour and smooth normals (`SceneUploader.java:1323-1327`,
`ModelUploader.java:279-282`, gate `VertexShading.flatFaceNormals` with `SMOOTH_COS = cos 50°`, lines 73-126).
Textured faces carried only their 7-bit lightness and were drawn as texture × lightness/127 (`frag.glsl:110-113`).
The game's brightness setting was applied as a curve to every colour: `pow(rgb, brightness)` in
`hsl_to_rgb.glsl:84` and on textures at `frag.glsl:108`.

**Normals.** Computed on the CPU from the model's own geometry, area-weighted face normals summed per vertex
(`VertexShading.computeNormals`, 145-172; the comment says the client's normals are not always there and "117 HD
found [them] inaccurate"), octahedral 16-bit in the spare texture short (`vert.glsl:94-107`). In the fragment shader
the triangle's own normal from screen derivatives, faced to the camera, orients the smooth one and stands in for
flat faces (`frag.glsl:137-146`). Terrain normals came from neighbouring corner heights (`SceneUploader.terrainNormal`
736).

**The light** (`composite_frag.glsl:426-454`, linear):

```
indirect = (ambientLight + skyFill(n) * up) * envAmbient * ao          // skyFill: zenith colour on up-facing, or the sky cache
light    = indirect + caster * envSun * max(N.L, 0) * shadow * ao
         + caster * envSun * transmission(N.L, view.toward.light, transmissive) * shadow   // leaves only
lit      = shadeLinear(srgbColour, light)  // srgbToLinear(c) * light, soft shoulder above 0.75 (sky.glsl:391-399)
```

Units (`sky.glsl:228-238, 249-270`): `SUN_INTENSITY 2.9`, sun colour warm (1, .42, .16) to noon (1, .90, .76) over
2-28°, strength `smoothstep(-4, 10, alt)`; ambient day (0.30, 0.38, 0.58), night (0.10, 0.14, 0.30) ×
`NIGHT_LIGHT_BRIGHTNESS 1.5`, × `CLEAR_AMBIENT 0.80` under a clear high sun (a dip of 0.08 more in "harsh" weather),
× (1.10, 0.80, 0.80) at dusk; `SKY_LIGHT 0.45` × linear zenith on up-facing surfaces only; `MOON_INTENSITY 0.675` ×
(0.42, 0.55, 1.0), strength `smoothstep(-2, -14, alt)`. Caster elevation clamped to 30-68° (sun) / 30-58° (moon),
`DayCycle.java:19-22`. Area multipliers `envAmbient`, `envSun` from environments.json (`environment.glsl:5-6`,
`EnvironmentManager.java:53-54`, neutral 1).

Worked numbers (luma of linear light, flat open ground at noon, caster at 68°): ambient 0.80 × 0.378 = 0.30, sky
0.45 × 0.206 = 0.09, sun 2.9 × 0.911 × sin 68° = 2.45; **ground ≈ 2.85**. A vertical wall facing the sun:
0.30 + 2.64 × cos 68° = **1.29**; a wall facing away: **0.30** (ambient only, the sky term needs `up > 0`). So lit
wall : shaded wall ≈ 4.3 : 1, ground : shaded wall ≈ 9.5 : 1. `RoomLight.java:27-28` states the same calibration
("outdoors at 45 degrees of sun about 2.7: the sun's 2.64 times its height, plus the ambient and sky light 0.8").

**Minimum light.** None in the open: a shaded face got the ambient, whatever it was. Three local floors existed:
`SKYLIGHT_FLOOR 0.4` keeps 40% of the ambient where the traced sky visibility is 0 (`skylight_ambient.glsl:5-15`);
`RoomLight` lifts building rooms to `TARGET_NIGHT 0.40` at night and `DAY_SHARE 0.42` of the outdoor light by day
(`RoomLight.java:27-37`, applied at `composite_frag.glsl:434-447`); and under cloud `overcastSkyBoost`
(`sky.glsl:289-298`) raises the sky light so a sunlit level surface keeps `OVERCAST_TARGET_BRIGHTNESS 0.9` of its
clear-sky light. That last one is an energy-balance compensation, the only place OLD had the "ambient rises when the
direct light goes" idea, and only for weather.

**Night.** Midnight flat ground: ambient (0.10, 0.14, 0.30) × 1.5 → 0.21, moon 0.675 × 0.555 × sin 58° = 0.21, total
**≈ 0.43, 15% of noon**; faces turned from the moon **0.21, 7%**. The palette's sky got the same ×1.5
(`NIGHT_SKY_BRIGHTNESS`, `sky.glsl:32-37`), the stars hash-cell or `night_sky.glsl`, the moon disc in the composite.

**Underground.** `caveLight`: 0.50 ambient + 0.40 key from a fixed direction (`sky.glsl:383-389`), times the area.

**Also in the light:** SSAO at half resolution (`ao_frag.glsl`), applied to ambient and (AO_DIRECT 1.0) to the sun;
point lights by clusters plus a baked light map (`composite_frag.glsl:212-270`); emissive materials
(`materials.glsl:28-30`); the sky cache's directional fill so walls take the colour of the sky they face
(`atmosphere.glsl:22-25, 53-69`, `SKY_CACHE_SIDES 0.5`).

### 1.2 NEW: how a face is lit

**Where.** Forward, one scene pass into the scene FBO (`frag.glsl:204-448`), the sky pass after the opaque geometry
(`SkyRenderer.java:34-40`), no composite.

**Colours in.** Two modes sharing one light path (`DiffuseColors.java:33-53`, `frag.glsl:84-90`). Vanilla (default):
the client's lit colours with 117 HD's capped undo (`FaceShading.undoVanillaShading`, 481-511, the same constants and
table as OLD's `baseColor`), converted without the game brightness curve (`frag.glsl:86-88`; HANDOVER:82-83 measured
the curve as the whole of a 1.30x gap), textured faces at lightness/127 × texture (`frag.glsl:268-273`). Terrain:
the client's lit tile colours as they are, **no terrain undo and no gain** (`DiffuseColors.terrainColor`, 154-157;
HANDOVER:86 lists OLD's "terrain undo (flat ground × 1.25 lightness)" as deliberately not taken). 117 HD mode adds its
colour/tile/model overrides. Never any colour blending across tiles (HANDOVER:96).

**Normals.** 117 HD's: the client's vertex normals where present, the face normal where a face is flat-shaded,
`flatNormals` is set, or the normals are zero (`FaceShading.java:330-357`, header 48-49), rotated with the model;
terrain normals summed per vertex (`TerrainGenerator.java:51, 172`). A switch to OLD's computed normals is an open
decision pending a measurement (HANDOVER:105).

**The light** (`frag.glsl:401-432`, linear):

```
skyFill        = max(SH0 + SH1.n + bounce * max(n.y, 0), 0) * fSkyVis          // sky.SkyLight, scene.terrain.SkyCover
compositeLight = ambient + skyFill + (caster * max(N.L, 0) + transmission) * shadowLit
compositeLight = smoothMax(compositeLight, lightFloor)                        // 428-432
colour         = linearToSrgb(colour * compositeLight)                        // no shoulder, clips
```

Units (`SkyModel.java:74-123`): `SUN_INTENSITY 2.2` × `BRIGHTNESS 1.264` (the scale that matched 117 HD's noon,
line 103-107), `SUN_NOON (1, .96, .90)`; **ambient = `AMBIENT_GAIN 2.64` × mean(zenith, horizon) in linear** after
the area's tint and haze (lines 226-233), × `CLEAR_AMBIENT 0.90` under a high sun; `MOON_INTENSITY 2.7` × 1.264 ×
(0.42, 0.55, 1.0); sky fill `SKY_LIGHT 0.45 / π × 1.264` as L1 SH of the gradient plus glow, integrated on the CPU
over 256 directions (`SkyLight.java:27-39`), with a 5% ground bounce onto undersides; `CAVE_AMBIENT (0.80, 0.84,
0.90)`. The floor: `lightFloor` 0.45 at night and underground, 0.8 by day, smooth between -12° and +6°
(`SkyModel.lightFloor`, 288-292); per frame `liftAmbient` (300-312) raises the ambient's luma, hue kept, so
ambient + sky + caster on level ground is never under it; the shader floors every face at the floor colour, 30% sky
hue (`floorColour`, 319-333). Caster clamp 30-68° / 30-58° as OLD (`Celestial.java:60-62`).

Numbers by arithmetic from HANDOVER:141 (overworld, `LightFloorTest`): **noon open ground 3.64, shaded ground 1.15
(3.2 : 1), a wall facing away 1.08**; 18:00 open 2.67, shaded wall 1.23; 20:00 open 0.82; **midnight moonlit open
1.11 (30% of noon), shade at the floor 0.45 (12%)**. Same source: relit noon walls are 0.77x the ground facing the
sun and 0.65x on average, where the game's own bake gives 1.5x and 0.76x (`NoonReferenceTest`).

**Transmission.** The same function on both sides: OLD `materials.glsl:22-26` (`TRANSMISSION 1.5`, only for
`incidence < 0`, × `max(viewTowardLight, 0)`, × `transmissionStrength` setting), NEW `frag.glsl:125-128`
(117 HD's `thinSheetTransmission`, subsurface from the material). Both multiply it by the shadow term
(`composite_frag.glsl:453`, `frag.glsl:426-427`). OLD's transmissive set came from its materials.json; NEW gives
perf-wip's leaf texture list subsurface 1 in Vanilla mode (HANDOVER:52, 84-85).

### 1.3 The differences, and which model today's rules are nearer

| | OLD (perf-wip) | NEW (Lumina) |
|---|---|---|
| Pipeline | deferred: G-buffer + per-pixel composite; alpha faces lit forward without shadows | forward, one pass; alpha faces take the same light and shadows |
| Vanilla colours | lit colours + 117 HD capped undo (`VertexShading.baseColor`) | the same (`FaceShading.undoVanillaShading`), Vanilla mode |
| Game brightness curve | applied, `pow(c, brightness)` (`hsl_to_rgb.glsl:84`) | dropped (1.30x at the user's 0.8, HANDOVER:83) |
| Terrain bake | undone, then × 1.25, cap 55 (`GroundBlender.undoShading`) | left as the client lit it (`DiffuseColors.terrainColor`) |
| Flat faces | smooth normals + unlit colour when the fold < 50° (`flatFaceNormals`) | 117 HD's flat flags, face normal |
| Normals | computed from geometry, 16-bit octahedral | the client's vertex normals (117 HD), switch pending |
| Sun | 2.9 × (1, .90, .76) | 2.2 × 1.264 = 2.78 × (1, .96, .90) |
| Ambient | fixed day/night colours, luma 0.30 at noon | the sky's own colour × 2.64, luma ~0.9 at noon, hue follows dusk/area |
| Sky fill | 0.45 × zenith on up-facing only (or the sky cache's irradiance, walls too) | L1 SH of gradient + glow on every normal, + 5% ground bounce |
| Shadow term | PCSS at half res + SSAO on everything | PCF 3x3 or PCSS per fragment, no AO |
| Noon ground : shaded wall | ≈ 9.5 : 1 (2.85 : 0.30) | ≈ 3.4 : 1 (3.64 : 1.08) |
| Minimum light | none outdoors; 0.4 of ambient where no sky; rooms to 0.40 / 42% | floor 0.45 night, 0.8 day, every face, smooth max |
| Night ground | ≈ 0.43 (15% of noon), shade 0.21 (7%) | 1.11 (30%), shade 0.45 (12%) |
| Moon | 0.675 | 2.7 × 1.264 |
| Highlight | soft shoulder above 0.75 (`shadeLinear`) | hard clip |
| Underground | 0.5 + 0.4 key light | (0.80, 0.84, 0.90) ambient, floor 0.45 |
| Point lights / AO / emissive | yes (clusters, light map, SSAO) | none |

Against the design doc's §3/§3a rules:

- *Vanilla answers light as OLD did.* NEW's Vanilla mode is OLD's formula (lit colours, the same capped undo, the
  same transmission) minus three deliberate omissions: the game brightness curve, the terrain undo with its 1.25
  gain, and the 0.75 shoulder. Of these only the **terrain gain** changes how a lit face and a shaded face compare
  to the ground (`NoonReferenceTest`'s 0.77x against the game's 1.5x is exactly the gap that gain closed in OLD).
  The curve is a display transform and the shoulder a clip softener; neither moves the lit/shaded ratio.
- *A minimum light the ambient compensates toward, never a cutoff.* This is NEW's `lightFloor`/`liftAmbient`
  exactly. OLD had no such thing in the open; its closest idea is `overcastSkyBoost`'s energy balance (weather
  only) and the `RoomLight` fill (rooms only). Nothing to take from OLD here.
- *Nights brighter.* NEW's night is already about twice OLD's on moonlit ground and the floor holds the shade at
  12% of noon where OLD fell to 7%. OLD's night is the darker model.

So the rules are nearer NEW's present model. What NEW is missing from OLD, for rule one, is the terrain-to-object
match: either OLD's terrain undo + gain, or an equivalent that makes relit walls stand to the ground as the game's
own bake does.

## 2. Sky and fog

### 2.1 OLD

**Palette and gradient.** `sky.glsl:5-28` zenith/horizon keyframes by sun altitude; `skyGradient` mixes horizon to
zenith by `pow(elev, 0.45)` above the horizon and darkens the horizon × 0.65 below it (414-421); `sunGlow`
(424-435) adds `glowColour × (mu^10 × 0.30 + mu^120 × 0.50)` toward the sun, visible from -6°; `VOID_BELOW (0.35,
0.7)` (463-466). NEW's `SkyModel.java:45-67` holds the identical keyframes, `lumina_sky_gradient.glsl` the identical
gradient, `lumina_glow.glsl` the identical glow shape; NEW moved the per-frame evaluation to the CPU where OLD did it
once per frame in `composite_vert.glsl` (`SKY_FRAME_CONSTANTS`, `sky.glsl:352-380`).

**Weather on the sky.** Overcast, haze, warmth, saturation in `sky.glsl:67-226`, ported verbatim as NEW's
`SkyModifiers` (SkyModel header 36-40); lightning's sky flash and the cloud light were not ported.

**Clouds.** `clouds.glsl`: one 2.5D slab 3500-6000 units above an eye fixed over the focal point, 10-16 steps
(`CLOUD_STEPS`, `CLOUD_MIN_STEPS`, 50-52), one light sample toward the sun with Beer/powder and two HG lobes
(113-121, 294-334), weather field by position, drawn at half resolution in a composite pass scissored to the rows
that can hold cloud (`composite_frag.glsl:300-328`, `Clouds.skyRows`), filtered into the full-res sky
(`atmosphere.glsl:107-121`). Cloud shadows on the ground from the same noise (`clouds.glsl:356-370`). The noise
texture is generated by `Clouds.java`, not an image.

**Sky cache.** `SkyCache.java`: the sky with clouds captured to 128×64 every few game minutes, blended, and a 32×16
cosine-weighted irradiance map feeding `skyFill` so the ground follows the clouds and walls take the colour of the sky
they face (`atmosphere.glsl:22-25, 53-69`).

**Night sky.** `night_sky.glsl`: three layers of hashed-cell stars with twinkle and glints, a sines-only nebula and
Milky Way band, a two-curtain aurora, shooting stars; all procedural, turned by `NightSky.java`'s frame. NEW instead
took 117 HD's star field, nebula bake and aurora (HANDOVER:59-62).

**Horizon handling — the apparent horizon.** `cloudSkyDir` (`clouds.glsl:143-158`): with `cloudEye` = (focal point
minus camera xz, radius of the drawn world, camera height above the focal point) the view ray's elevation is remeasured
from the edge of the drawn world: `far` = distance along the ray's azimuth to the edge circle, `below = h /
sqrt(h² + far²)`, `s = (-dir.y + below) / (1 + below)`; the zenith stays. The whole sky dome is looked up along it,
clouds and gradient alike (`atmosphere.glsl:110` `dir = cloudSkyDir(dir)` before `skyGradient`). Setting
`skyHorizonAtEdge`, default true (`RuneLightConfig.java:526-536`), `Clouds.setEye` (77-87) with `eyeHeight = focalY -
cameraY`. This is precisely the design doc §1's `skyHorizonDrop`, already built, with one refinement the design lacks:
the drop depends on the azimuth because the edge circle is centred on the focal point, not the camera ("nearer behind
the camera than in front", `clouds.glsl:144-145`).

**Fog stages.**

| Stage | What | Where it reaches | Colour |
|---|---|---|---|
| `envFogAmount` (`environment.glsl:43-53`) | 117 HD's exponential distance fog per area (`envFogDepth` from environments.json), vertical distance halved | from a start that moves in as depth grows, over the draw distance (≤ 90 tiles) | `envFog`: the sky horizon, or the area's colour scaled by daylight with 35% horizon mixed in (`environment.glsl:29-39`) |
| `capFogCover` (`fog_cover.glsl`) | a cap shared by the area fog and the volumetric fog: at most 0.3 cover within 25 tiles of the player, opening over the next 20 | around the player | — |
| `edgeFadeAmount` (`edge_fade.glsl`, `EdgeFade.java`) | the world ends at a circle of the draw distance around the **focal point**, not the client's square around the camera; geometry fades into `skyBehind` (the full sky in that direction, clouds included) over 12 tiles in clear air, up to 60% of the radius in fog (`EdgeFade.java:35-37, targetWidth`); alpha faces lose alpha (`frag.glsl:168`) | the last 12+ tiles | the sky behind the pixel |
| `fog_frag.glsl` volumetric | half-res march, 6/8/12 steps (`VolumetricFog.java:221`), a height layer 3 tiles thick over the terrain (`FogGround`), lit by the horizon (0.32) + ambient (0.14) + the caster through the shadow map (shafts) + point lights, noise and banks, denoised, upsampled depth-aware (`fog_apply.glsl`) | the player's surroundings, faded by `fogFade` | horizon-tinted, sun-coloured shafts |
| `fog_apply.glsl` | the upsample and the blend in the composite (`composite_frag.glsl:544`) | — | — |

The client's own square edge was hidden by EdgeFade giving the client a larger draw distance (`clientDrawDistance`:
+ offset + 10 tiles) and skipping zones outside the circle (`EdgeFade.java:8-13`), which costs uploads and culling on
the CPU.

**Sunrise/sunset colour.** The HORIZON palette is orange at -2..4° all around the compass ((0.627, .267, .180),
(0.890, .443, .227), (0.949, .631, .369)); `sunGlow` adds more toward the sun. The same in both renderers, so OLD had
the design §5 problem too. The one OLD mechanism that made *land* toward the sun warmer than land away from it was the
sky cache's directional irradiance; NEW's SH fill does the same analytically.

### 2.2 NEW

Sky pass after the opaque geometry at the far depth (`SkyRenderer.java:34-40`, `lumina_sky_frag.glsl:73-119`):
gradient + glow, nebulae from the baked cube map, aurora march, discs, `VOID_BELOW` identical, the area fog colour
where there is no sky. The gradient's zero is the **true horizontal** (`lumina_sky_gradient.glsl:5` `elev = -dir.y`);
nothing remaps it to the ground edge, which is the §1 symptom. Fog (`fog.glsl:46-81`, `frag.glsl:168-201`): 117 HD's
edge fog over the last five tiles of the client's square, Lumina's aerial perspective `1 - exp(-(d / ref)² × depth)`
with `ref = min(drawDistance, 90) tiles` and depth 2 (caves 4), 117 HD's ground fog; the fog colour is
`skyGradient(dir) + glow` in the view direction (`frag.glsl:188-198`), so fog and sky are one function. No cover cap
(the square law keeps the near ground clear: 0.39 at half the draw distance), no volumetric fog, no clouds, no sky
cache.

### 2.3 What NEW lacks from OLD that the design doc's issues need

- **§1, the sky lifts when panning down:** OLD's `cloudSkyDir`. Port it as the elevation remap inside
  `skyGradient` (shared by the sky pass and `applyFog`), with `cloudEye`'s four numbers (focal offset xz, edge radius,
  camera height above the focal point) instead of the design's single `skyHorizonDrop`; the azimuth dependence is
  free and right. The "ground tone between the apparent horizon and the void" the design asks for is what OLD's
  below-horizon × 0.65 band becomes once the zero is moved. OLD's circular EdgeFade is the stronger fix for the ground
  edge itself but needs the enlarged client draw distance; not for this symptom.
- **§5, orange dawn all around:** nothing in OLD; both share the palette. The fix is the design's: cool the -2..4°
  horizon keys in `SkyModel.HORIZON_COLOR` and widen `luminaSunGlow` with low altitude (a per-frame exponent from
  `SkyModel`), and the SH fill carries it to the land.
- **§2, one area tint:** OLD's `environment.glsl` is where NEW's `skyTint`/`fogColor`/`fogWeight`/`haze` came from
  (`SkyModel.areaSky`, 253-282); the design's collapse to one tint is a simplification of OLD, not a port.

## 3. Architecture and performance

### 3.1 OLD's frame

GPU passes in order (`GpuTimer.NAMES`, `RuneLightPlugin.postDrawToplevel` 2040-2085): scene G-buffer (MRT 4, MSAA
optional, unresolved) → alpha layer resolve → shadow map (static cache in two textures copied each frame, dynamic
models over it, `shadowMax` tiles; `ShadowMap.java:26-31, 54-57`) → weather occlusion map → half-res AO + PCSS
shadows + sky visibility + wet ground (`ao_frag.glsl`) → sky cache capture/blend/irradiance (every few game minutes)
→ half-res volumetric fog (6-12 steps, each a shadow fetch and the cluster's lights) → denoise, 21 taps on two
targets (`denoise_frag.glsl:3-12`) → half-res clouds and/or water (`COMPOSITE_CLOUDS/WATER`) → full-res composite
(per pixel: 2×2 depth/normal-aware upsample of AO, shadow, fog; sky with clouds, stars; lighting; clusters and the
light map; rooms; water; puddles and snow; distance fog; edge fade) → MSAA mark + edges pass (a second lighting of
the pixels with two surfaces) → weather particles → upscale (Catmull-Rom) → UI. Six composite programs
(`RuneLightPlugin.java:241-285`).

CPU per frame (`CpuTimer`): environment, weather field, lights, clusters (32×18×24 grid, bit masks,
`LightClusters.java:11-19`, CPU because macOS has no compute), shadow submit, buffer maps, light upload, sky cache
schedule; workers: TempModels (normals, sort, pack of every dynamic model, `TempModels.java:17-25`), the sky grid's
voxel ray tracing (`SkyGrid`, `Voxelizer`, `SkyGridMap`), light map bakes (`LightMapBake`), weather audio mixing;
per scene: `BuildingDetector`, `RoomMap`, `WallMap`, `ModelBlockers`, `GroundBlender`. A watchdog thread samples
the client thread's stack on hitches (`HitchMonitor.java:16-18`).

Cost shape: everything after the scene pass is per pixel at full resolution plus three half-res passes, at any scene
content; the comments record the fights: the sky palette per pixel "cost over a millisecond" (`sky.glsl:352-354`),
a 4×4 upsample footprint "over a millisecond for no visible difference" (`composite_frag.glsl:119-120`), the MSAA
edge pass inlined twice made the composite "nine times as long" (562-563). Texture units ran to
`COMPOSITE_TEXTURE_UNIT + 28` with sharing tricks (`RuneLightPlugin.java:393-400`). 69 settings
(`RuneLightConfig`, `keyName` count).

Heavy or fragile: the G-buffer at MSAA; the six-way composite compilation and recompiles on debug view; the sky
grid/voxel/light-map machinery (thousands of lines of CPU code for interiors and lamp visibility); the enlarged
client draw distance for the circular edge; the cache keys and schedules (sky cache, static shadow map, light map
blocks) each with its own staleness rules; GL 4.5 `glClipControl` branches everywhere (`frame.glsl:12, 17-24`).

### 3.2 NEW's frame

Record/replay (`DrawList.java` header, `FrameRenderer.java:80-84`): the client's callbacks record zone and model
draws (no GL), the shadow passes run on the recorded frame (static map built over 4/16 frames into a back slot,
`StaticShadowMap` header; dynamic map each frame; PCSS min/max tiles, `ShadowTiles`), then one replay into the scene
FBO at `renderScale` (`FrameRenderer.java:735-760`): clear, opaque zones and dynamic models, Lumina's sky triangle
and the star sprites, alpha zones; blit; UI. CPU: alpha sorting per frame (`ZoneDrawer` header), dynamic uploads on
the client's render threads, SH re-integration when the sun moves 0.25° (`SkyLight.java:35-39`), `ZoneCulling`'s
frustum test (uncommitted). 26 settings (`LuminaConfig`).

Measured (perf-audit §2, M5 Pro, small canvas, uncapped): frame 2.6-3.7 ms, `client_scene` 0.4-0.8, `client_ui`
1.1-1.7, GPU scene 1.5-2.3 ms (idle gaps included); PCSS adds +0.32 ms to the scene pass at that canvas
(HANDOVER:145).

### 3.3 Implications

Per pixel, OLD paid the composite's lighting once (no overdraw) but then paid every half-res pass, the upsample and
the clusters whether or not anything was lit by them; NEW pays its lighting per fragment with overdraw but it is a
dozen multiplies and 9 or 28 shadow taps. At 4K the difference is OLD's roughly 1.5-2.5 ms of composite + 1-2 ms of
half-res passes (by its own comments and pass list) against NEW's one pass. The CPU side is the larger gap: OLD kept
five background workers and a per-scene building analysis alive; NEW has none of that, and its remaining CPU cost is
the client's own traversal, the alpha sort and zone rebuilds on the client thread (HANDOVER, "Next segments" item 5).

## 4. Verdict table

Rules applied: performance first; few settings; the vanilla look with 117 HD's shading tricks; no texture images; no
terrain colour blending.

| Feature | Verdict | Reason |
|---|---|---|
| Forward single pass, record/replay | Keep as Lumina has it | One pass beats OLD's G-buffer + composite on every axis the rules name. |
| Vanilla colours = lit colours + 117 HD capped undo | Keep as Lumina has it | Already OLD's treatment (`baseColor` = `undoVanillaShading`). |
| Terrain bake undo + 1.25 gain (`GroundBlender.undoShading`) | Take from OLD (the undo and gain, not the blending) | The one missing piece of "Vanilla answers light as OLD did": walls now stand 0.77x to the ground where the game has 1.5x. |
| Ground colour blending (`GroundBlender` vertex sharing, `frag.glsl:118-126` border mix) | Strip (OLD) | The rule: never blend terrain colours. |
| Game brightness curve on colours | Keep as Lumina has it (dropped) | A display curve; it put Vanilla 1.30x over 117 HD mode. |
| Highlight shoulder 0.75 (`shadeLinear`) | Rethink | Cheap and it stops stacked light clipping hues; decide from a capture at noon, both modes alike. |
| Computed smooth normals (`VertexShading.computeNormals`) | Rethink (the planned measurement) | Client normals cost nothing; OLD's claim that the client's are missing or wrong needs a count, not a feeling. |
| Flat faces smoothed with unlit colours under 50° | Rethink, after the normals decision | Rounds trunks and rocks; conflicts with "flat-lit objects = 117 HD's flags". |
| Sun 2.2 white, ambient = sky colour, moon 2.7 | Keep as Lumina has it | Today's design; OLD's 9.5:1 noon contrast and 15% nights are what the user moved away from. |
| Light floor with ambient lift | Keep as Lumina has it | OLD had none; this is the user's rule. |
| OLD `overcastSkyBoost` energy balance | Take from OLD, later, into `SkyModifiers` | The floor already covers the open case; this keeps overcast days from going dim when weather lands. |
| Sky fill: SH L1 + bounce | Keep as Lumina has it | Analytic, tens of microseconds, directional on every normal; OLD's cache needed three passes and a schedule. |
| Leaf transmission | Keep as Lumina has it | Same formula both sides; the texture list is in. |
| SSAO + denoise | Strip (OLD) | Needs a depth prepass and a half-res chain; not in the rules' look. |
| Point lights: clusters, light map, rooms, blockers | Rethink, much later | ~8k lines of OLD for lamps; the rules allow a Lights section but not this machinery first. |
| Sky palette, gradient, glow, void | Keep as Lumina has it | Identical to OLD's already. |
| Apparent horizon (`cloudSkyDir`) | Take from OLD | Shader-only, four floats, fixes design §1 with the azimuth term the design lacks. |
| Dawn/dusk warm toward the sun only | Rethink (neither has it) | Palette keys and glow width in `SkyModel` + `lumina_glow`; design §5. |
| Edge: circle around the focal point + fade to sky behind | Rethink | Right idea, but it enlarges the client draw distance (CPU); first see what `ZoneCulling` leaves of the symptom. |
| Distance fog: aerial perspective (d/ref)² | Keep as Lumina has it | Clear near ground without OLD's cover-cap machinery. |
| Area fog per environment (`envFogDepth`, `envFog`) | Strip (OLD) / Keep NEW's one tint | Design §2: one tint, one depth. |
| Volumetric fog + FogGround + fog noise | Strip (OLD) | Half-res march with shadow and light fetches every frame; the weather can tint and deepen the aerial fog instead. |
| Clouds (`clouds.glsl` slab march) | Take from OLD, later, sky pixels only | Procedural (no image), scissored to sky rows, 10-16 steps; the only OLD feature that reads as weather. |
| Cloud shadows | Rethink with the clouds | Cheap (one noise fetch) once the clouds exist. |
| Sky cache + irradiance | Strip (OLD) | Replaced by SH; re-integrate the SH with cloud cover when clouds land. |
| Night sky | Keep as Lumina has it (117 HD's) | Already done; the nebula strength check is pending in-game. |
| Static shadow cache + dynamic map + PCSS | Keep as Lumina has it | Same idea on both sides; NEW's is per fragment with tiles, OLD's needed the half-res chain. |
| Sky visibility: OLD voxel ray trace vs NEW `SkyCover` bake | Keep as Lumina has it | One bake at upload against a worker thread, a 3D texture and a voxel grid. |
| Weather field, forecast, climates | Keep as Lumina has it (data), wire it | Already ported as `weather/`; OLD's `Weather.java` showers/gusts come after. |
| Lightning, weather audio, rain/snow particles, wet ground, snow cover | Strip (OLD) | Settings and passes the rules do not want; audio is outside a renderer. |
| Water (`water*.glsl`, `WaterMap`, waterfalls, `water_caustics.jpg`) | Strip (OLD), rethink water later | 5k+ lines, a caustics image (forbidden), its own passes. |
| Wind sway (`sway.glsl`, `WindSway`) | Rethink | Vertex-only and cheap, but needs the per-vertex bend code in the vertex format; an open decision in HANDOVER. |
| MSAA deferred edge pass | Strip (OLD) | Forward MSAA is free of it. |
| Render scale + upscaler | Keep as Lumina has it | NEW has the FBO scale; OLD's Catmull-Rom sharpen is a setting too many. |
| HitchMonitor, GPU/CPU timers panel | Keep NEW's `snapshot`; strip OLD's panel | HANDOVER:28-30 found the GPU timers misleading on GL-on-Metal. |
| Settings surface | Keep as Lumina has it | 26 against 69. |
| TempModels worker upload | Rethink, if `client_scene` grows | OLD moved dynamic model packing off the client thread; NEW uses the client's render threads already. |

## 5. Proposed order of next changes

Shortest path first; each tied to a row above.

1. **Apparent horizon** (row "Apparent horizon"): add a `vec4 skyEye` uniform (focal offset xz, edge radius =
   `min(drawDistance, 90) × 128`, camera height above the ground under it) to `Lighting`/`LightingUniforms`, and
   remap `elev` in `lumina_sky_gradient.glsl` with OLD's `cloudSkyDir` arithmetic. The sky pass and `applyFog` share
   the function, so one change. Check: three pitches, the horizon row of pixels.
2. **Terrain matching** (row "Terrain bake undo"): port the undo half of `GroundBlender.undoShading` (slope dot,
   lighten/darken, `GAIN 1.25`, cap 55) into `DiffuseColors.terrainColor`'s Vanilla path, nothing of its vertex
   sharing; verify with `NoonReferenceTest` that a relit wall facing the sun returns toward the game's 1.5x of the
   ground. One capture at noon in both modes.
3. **Dawn and dusk toward the sun only** (row "Dawn/dusk"): cool the -2, 0, 4° entries of `SkyModel.HORIZON_COLOR`
   and give `luminaSunGlow` a width from altitude (`SkyModel` writes an exponent and a strength into `Lighting`,
   `lumina_glow.glsl` reads them). The SH fill and the fog follow without more work. Check: `time 6.5` at four yaws.
4. **Shoulder decision** (row "Highlight shoulder"): one capture at noon with `shadeLinear`'s shoulder added to
   `frag.glsl:440` behind a compile-time define, both modes; keep or delete the same day.
5. **Normals measurement** (rows "Computed smooth normals", "Flat faces"): count zero and degenerate client normals
   per scene with `debug unlit`, then port `VertexShading.computeNormals` behind the planned switch only if the count
   says so; decide the 50° flat-face smoothing with it.
6. **Overcast energy balance** (row "`overcastSkyBoost`"): when the weather hook-up (HANDOVER next segment 2) lands,
   port `overcastSkyBoost` into `SkyModel.evaluate` as the sky fill's gain under cover, before any cloud is drawn.
7. **Clouds** (rows "Clouds", "Cloud shadows", "Sky cache"): `clouds.glsl`'s slab march in the sky pass only,
   `Clouds.java`'s generated noise, SH re-integrated with the cover; cloud shadows from the same noise in the scene
   shader. Measured with `snapshot` on sky-heavy and ground-heavy views before it stays.
8. **Edge circle** (row "Edge"): only if, after `ZoneCulling` is in and the horizon is fixed, the square draw edge
   still shows when turning: EdgeFade's circle and client margin, measured against `client_scene`.

## 6. Measured baseline, 2026-10-30 evening: the old renderer against Lumina, same client, same views

Same machine (M5 Pro, GL 4.1 Metal), same session, same canvas (783×603, stretched 1175×905), Auburnvale
(1388, 3280), camera yaw 0 and 1024 at pitch 719 / scale 355, FPS uncapped (`snapshot 10 uncapped` in both builds:
Lumina's, and the reference build's `::runelight snapshot`, commit 18b6402a on `ref-perfwip-tools`), 10 s each.
Old renderer with its defaults (AO HIGH, shadows, volumetric fog, clouds, lights); Lumina at `33485774`+ with
PCSS, the ported SSAO on smooth normals (uncommitted), aerial fog, zone culling.

| | old renderer (perf-wip) | Lumina |
|---|---|---|
| median frame, yaw 0 | **6.96 ms** (144 FPS) | **2.89 ms** (337 FPS) |
| median frame, yaw 1024 | 6.98 ms | 3.00 ms |
| p5 / p95 | 6.27 / 7.39 | 2.60 / 3.63 |
| max | 14.7 / 16.4 | 11.4 / 9.5 |
| frames > 1.5× median per 10 s | 3 / 1 | 14 / 16 (1.5× of a 2.9 ms median is 4.4 ms) |

Reading: at this canvas the old renderer costs 2.4× Lumina's frame with everything it does switched on, and its
frame time is flat (p5–p95 within 1.1 ms: a fixed per-pixel chain, as §3.3 predicted), while Lumina's varies with
content. Neither hitches in the user's sense here (worst frames 9–16 ms, none over a 60 Hz interval); the old
renderer's hitches of record were scene loads and bakes, which a 10 s standing snapshot does not exercise. At a 4K
window both scale with pixels: the old's chain roughly 4× (≈ 25 ms, below 60 Hz), Lumina's forward pass less than
linearly (3.3 ms measured today maximised). This is the number the "start from perf-wip" decision was waiting for.
