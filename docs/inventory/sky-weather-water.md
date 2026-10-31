# Inventory — sky, day cycle, environment, weather, fog, clouds, water, wind

Read-only inventory of branch `lumen` (`perf-wip` base), written 2026-10-30 for HANDOVER A1 ("document each quantity's
mechanism before touching it"). Scope: `DayCycle`, `GameClock`, `Climate*`, `Weather*`, `Wind*`, `EnvironmentManager`,
`SkyCache`, `SkyGrid*`, `SkyField*`, `Voxel*`, `Clouds`, `VolumetricFog`, `FogGround`, `Water*`, `Lightning`, `NightSky`,
`WeatherAudio`/`AudioMixer`, `WadeSteps`, and the shaders `sky`, `atmosphere`, `night_sky`, `clouds`, `environment`,
`fog_*`, `skylight_*`, `sky_cache_*`, `sky_irradiance_frag`, `water*`, `weather_*`, `rain_*`, `snow_*`, `lightning`,
`sway`, `wind`, plus the data files and `tools/import_*.py`, `tools/water/*`, `tools/make_weather_sounds.py`.

Size: 44 Java files, 16,226 lines; 28 GLSL files, 5,121 lines; data 1.5 MB (`water_flow.dat` 591 kB, `lights.json`
excluded as not ours). Paths below are relative to `src/main/java/com/midonarhel/lumina/` (Java) and
`src/main/resources/com/midonarhel/lumina/` (GLSL/data). `file:line` refers to the files as read; **`RuneLightPlugin.java`
was being edited while this was written (its line numbers moved by +12 and then +3 between reads), so its references are
marked ≈.** Costs quote HANDOVER B1 (uncapped, 783×603 canvas, possibly at render scale 50 %, see the B1 caveat); anything
not in B1 is marked *untimed*.

Judging rule used for every verdict: performance first (≤ 8 ms at 4K, no hitches), one mechanism per quantity, few
settings, GL 3.3–4.1 on three platforms, and the user's wishes: the look of the shading, sky, fog and tuning is kept; the
water **waves** go for now (flow maps to be discussed later); volumetric fog and clouds stay.

---

## A. Clock and sun

### A1. GameClock — the game's clock as a pure function of real time
- **Purpose (comment):** "a pure function of the real (Unix) time, counted from a fixed epoch, so the time of day, the
  season and the weather are the same whenever and wherever the client runs" (`GameClock.java:3-10`).
- **Files:** `GameClock.java` (78 lines): `gameMillis` :30, `hourOfDay` :50, `days` :62, `realSeconds` :74.
- **How:** epoch 2024-01-01 (:14); game runs `1440 / dayMinutes` × real; `dayMinutes ≤ 0` (fixed hour) still runs the
  default 24-minute day so weather and seasons go on (:27-35).
- **Settings:** `dayCycleMinutes` (0 = held), `fixedHour`.
- **Cost:** a few long divisions per frame, client thread.
- **Overlaps:** none; `Water.java:56-61` keeps its own `System.nanoTime` clock for the waves (Windows 15.6 ms tick
  argument), `Weather.java:993` wraps a particle clock every 600 s off `EPOCH_MILLIS`, `Clouds.java:236` and
  `WeatherField`/`Weather` use `currentTimeMillis` deltas. Four time bases; harmless but could be one `FrameClock`.
- **Verdict:** keep.

### A2. DayCycle — sun, moon and shadow-caster directions
- **Purpose:** "Sun and moon positions over a repeating day… The shadow casting direction uses the same azimuth but a
  clamped elevation, keeping shadows short. At night the moon, opposite the sun, casts instead" (`DayCycle.java:3-12`).
- **Files:** `DayCycle.java`: `update` :80 (altitude = 70·sin + 12 lift, caster clamped 30–68° sun / 30–58° moon),
  `directStrength` :43 (mirrors `sky.glsl` `sunLightCover`/`moonLightCover` numbers in Java for `Lightning`),
  `applyStrike` :66 (bolt takes the caster).
- **Settings:** `dayCycleMinutes`, `fixedHour`.
- **Cost:** per frame, trivial, client thread.
- **Overlaps:** `directStrength` duplicates `sky.glsl:249-258` constants (SUN_INTENSITY, OVERCAST_DIRECT_*) in Java —
  two copies of the same numbers, kept in sync by hand (comment :45, :49). `NightSky.starFrame` also derives from
  `phase`. The "visual sun vs caster" split is a deliberate look rule (keep).
- **Verdict:** keep; move the duplicated constants into one shared table (Java constants injected as shader
  `#define`s via `Template`) when a segment touches lighting.

---

## B. Environment (per-area ambience)

### B1. EnvironmentManager — which area the camera is in and its blended ambience
- **Purpose:** "Where the camera is: which area (environment) it is in, whether that is under the open sky or
  underground, and the ambience that area asks for, blended over a few seconds" (`EnvironmentManager.java:16-27`).
- **Files:** `EnvironmentManager.java` (673): `load` :180 (environments.json → region-indexed boxes), `update` :264
  (per frame; re-looks-up only when the focal tile/plane/scene/skyBlack changes :280), `lookup` :311, `blendAround`
  :336 (9×9 samples 6 tiles apart out to ±24 tiles, every lookup), `environmentAt` :435 (box → biome look → cave
  biome → height fallback), `select` :481 (3 s smoothstep, instant on jumps ≥ 41 tiles), `advance` :520 (plus
  `sheltered` 0.5 s ease from under-roof flag), `toWorld` :550 (instance template chunks).
  Data: `environments.json` (231 environments, 2,228 boxes, 3 cave biome regions; from `tools/import_environments.py`,
  442 lines — 117 HD environments + 3D Weather cave regions, stored relative to RuneLight's own look).
- **Settings:** `areaEnvironments` (off → classification by client sky colour only, `RuneLightPlugin.java:≈2362`).
- **Cost:** per frame, client thread (`CpuTimer.ENVIRONMENT`); 81 `environmentAt` calls on each focal-tile change
  (each a region-box scan + `WeatherRegions.biomeAt`): a few µs, not measured.
- **Overlaps:** produces *ambient*, *sun*, *fogColor/weight*, *fogDepth*, *haze*, *skyTint* — all consumed by B2 and by
  the fog stack (F1) and edge fade (B4). `isUnderRoof` (:534) is a fourth "sheltered" producer beside the weather
  occlusion map, `RoomMap` and the skylight. `weatherBiomeAt` (:398) also serves the weather field.
- **Dead/variant:** `regionBiome` route (cave biome regions) has 3 entries; `NEUTRAL_CAVE` path when no area data.
- **Verdict:** keep (it is the look's area data); restructure so it outputs exactly one fog quantity (see §I.1).

### B2. EnvironmentUniforms + environment.glsl — the area's multipliers in every lit program
- **Purpose:** "Per-area ambience… areas tint the light, add distance fog in their own color and haze over the sky"
  (`environment.glsl:1-3`); "Every program that includes environment.glsl must get these set each frame"
  (`EnvironmentUniforms.java:5-8`).
- **Files:** `EnvironmentUniforms.java` (74): `set` :36 — `envFogDepth = fogDepth × fogStrength × min(dd,90)/10`
  (:65, 117 HD scaling), `envHaze = haze × min(fogStrength,1)` (:66). `environment.glsl` (65): `envFog` :29 (area colour
  mixed 35 % with the horizon, day-scaled), `envFogAmount` :43 (117 HD exponential distance fog, vertical halved,
  then `capFogCover`), `envSky` :56 (haze mixes sky toward fog colour), `envCaveLight` :63. Set from
  `RuneLightPlugin.setEnvironmentUniforms` :≈2375 with `fogStrength = weather.distanceFogScale()` (0 over a skybox).
  Consumers: scene program (:≈2009) and composite (:≈2919); `composite_frag.glsl:356` (fog colour), :430/:443
  (`envAmbient`), :450/:453 (`envSun`), :458 (cave), :530-539 (distance fog).
- **Settings:** `areaEnvironments`.
- **Cost:** per pixel in the composite: `envFogAmount` (one `exp`, one `length`, `capFogCover` with another `exp` and a
  `smoothstep`) for every lit pixel, and the `mix` at :539; untimed inside `COMPOSITE` 1.4 ms.
- **Overlaps:** this is the **second fog renderer** beside the volumetric fog (F1): a separate amount (`envFogAmount`),
  a separate colour (`envFog` vs the fog pass's sky-lit samples), a separate cap sharing `fog_cover.glsl`. The area's
  `fogDepth` is *also* fed into the volumetric density (`RuneLightPlugin.java:≈2192-2194`). `envHaze` is a third
  fog-like blend (sky → fog colour).
- **Verdict:** merge into F1 as the analytic far tail of one fog extinction (see §I.1); keep `envAmbient`/`envSun`/
  `envSkyTint`/`envCaveLight` as the area multipliers they are.

### B3. fog_cover.glsl — the limit on what fog may hide near the player
- **Purpose:** "around the player the cover is limited to a maximum (a soft knee)… beyond the play area that limit opens
  up" (`fog_cover.glsl:1-4`); FOG_MAX_COVER 0.3, 25 tiles full, 20 tiles ramp (:7-11).
- **Files:** `fog_cover.glsl:16 capFogCover`; included by `environment.glsl:15` and `fog_frag.glsl:15`.
- **Cost:** per pixel (composite) and per fog texel; trivial.
- **Overlaps:** applied twice to two different fog amounts; the volumetric fog's version applies to a transmittance,
  the distance fog's to an sRGB mix amount — the two caps don't add up to one cap when both fogs are present.
- **Verdict:** keep the rule; apply it once when the fogs become one.

### B4. EdgeFade fogginess — fog widens the world's edge fade
- **Purpose:** "Fade width, world units: CLEAR_TILES in clear air, up to FOGGY_SHARE of the radius in fog"
  (`EdgeFade.java:98`); `fogginess(weatherFog, areaFog)` :91 combines the weather's fog multiplier and the area's
  `fogDepth × distanceFogScale` (`RuneLightPlugin.java:≈2216-2219`).
- **Cost:** per frame; the fade itself is per pixel in the composite (`edgeFadeAmount`, :532-541 — outside this area).
- **Overlaps:** a third consumer of the fog amount with its own smoothsteps (`WEATHER_FOG_CLEAR/THICK`,
  `AREA_FOG_CLEAR/THICK`) — a fourth place where "how foggy" is re-derived.
- **Verdict:** keep the behaviour; read the single fog extinction once it exists.

---

## C. Sky

### C1. sky.glsl — the palette and the light model (sun, moon, ambient, sky light, overcast, haze, warmth, flash)
- **Purpose:** "Sky and light colors, all driven by the visual sun altitude… Stylized palette: sunrise and sunset go
  red-orange at the horizon with purple above it, nights are cool blue" (`sky.glsl:1-4`). **This is the look the user
  wants kept.**
- **Files:** `sky.glsl` (466): zenith/horizon keyframes :5-28; `weatherOvercast`/`weatherLight`/`strikeLight` uniforms
  :73-109; overcast model :121-216 (`overcastSky`, `overcastDirect`, `overcastAmbient`, `overcastShadow`); lights
  :249-283 (`sunLightCover`, `moonLightCover`, `ambientCover`); `overcastSkyBoost` :289 (energy balance, clamp
  1..200); `skyLightAt` :315; `cloudLightAt` :342; `skyFrame[11]` constants :352-379 computed once per frame in
  `composite_vert.glsl:16-23` ("cost over a millisecond" per pixel otherwise, :354). Uniforms set by
  `Weather.setOvercastUniform/setLightUniform` (`Weather.java:563-575`) on scene, composite, fog, rain/snow programs.
- **Settings:** none directly (`sunLighting` gates outdoors).
- **Cost:** per frame in the vertex shader (flat varyings) for composite/fog; per pixel for the rain/snow/sky-cache
  programs that don't get `skyFrame` (they call `*At` directly — `#else` branch :369-379; which programs define
  `SKY_FRAME_CONSTANTS` is not verified).
- **Overlaps (ambient producers, see §I.2):** `ambientCover` (base ambient + dusk tint + `nightGain`), `skyLightAt`
  with `overcastSkyBoost`, `weatherLight` haze lift (`HAZE_AMBIENT` :85) and warmth (`WARMTH_FILL` :90), `flashDip`
  :102, `skyLightningAt` :332. Java copy of parts in `DayCycle.directStrength`.
- **Verdict:** keep as the look; the *analytic* `skyLightAt`+`overcastSkyBoost` branch becomes a fallback only once the
  sky cache is the one sky-light source (see C3).

### C2. atmosphere.glsl — the drawn sky and the sky-fill light
- **Purpose:** "The procedural sky as the composite draws it (sky colors, clouds, the sun, moon and stars through
  them) and the sky cache… and the light it sheds on surfaces facing each way" (`atmosphere.glsl:1-4`).
- **Files:** `skyCacheUv` :37 (lat-long with √sin(elev) rows), `skyFill` :53 (mix of analytic `skyLight×up` and cache
  irradiance by `skyCacheWeight`, `SKY_CACHE_SIDES` 0.5, NaN guards), `celestial` :72 (sun/moon discs, halo, night
  sky; stars fade by `max(skyHaze, envHaze)` :91), `atmosphereSky` :107 (sky gradient + sun glow, half-res clouds
  fetched from `cloudHalf`, `celestial`). Used by `composite_frag.glsl:167` and `skyBehind` (:376, :541).
- **Cost:** per *sky* pixel only (composite early branch :371-389), plus `skyFill` for every lit pixel (one
  `textureLod` + a few mixes).
- **Overlaps:** see C1/C3; `envSky`'s haze (B2) and `skyHaze` (Weather→stars) are two haze notions.
- **Verdict:** keep.

### C3. SkyCache — captured sky radiance and irradiance
- **Purpose:** "Cached sky radiance: the procedural sky and its clouds captured into a small lat-long texture… every few
  in-game minutes… A tiny irradiance map… holds the light the sky sheds on a surface facing each way, so the ground
  follows the clouds and the sky's colors" (`SkyCache.java:6-20`).
- **Files:** `SkyCache.java` (613): `Schedule` :52-154 (capture every 5 game minutes `CAPTURE_MINUTES` :205, ×0.35 at
  twilight, early on cover/snow/altitude/drift steps, ≥ every 30 real s; blend 2–8 s), `update` :367 (capture →
  mipmaps → 3-texture roles :456 → blend → irradiance redraw when blend moved 0.02 or every 100 ms :442),
  `easeComposite` :316 (`skyCacheWeight` eases over 1.5 s :184), `capture` :489 (128×64, 12 cloud steps :25-27),
  `irradiance` :542 (32×16, 32 hemisphere taps each). Shaders: `sky_cache_frag.glsl` (37; captureSteps 24 when drawn
  from the cache, 12 for light :19), `sky_cache_blend_frag.glsl` (14), `sky_irradiance_frag.glsl` (55; adds the sun
  glow live :6-7). Plugin: `skyCache.update` :≈3243, `GpuTimer.SKY` :≈3365, `MODE_LIGHT` always :≈3248.
- **Settings:** none of its own (`proceduralSky`, `clouds`, `starBrightness` passed through).
- **Cost (B1):** sky cache 0.36 / 0.05 ms (the 0.36 is a frame with a capture + blend + irradiance; otherwise only the
  100 ms irradiance redraws). Three programs, 3 + 2 small textures, mipmap generation per capture. Client/GL thread.
  A capture after a pause (`STALE_MILLIS` 1 s :203) lands on the frame after a load — one of B3's "sky capture" jobs.
- **Overlaps:** **two sky-light mechanisms run at once**: the analytic `skyLight(alt)×overcastSkyBoost` and the cache
  irradiance, mixed by `skyCacheWeight` in `skyFill` (`atmosphere.glsl:53-68`). The cache leaves out lightning, sun
  glow, stars, which are added back separately (`sky_irradiance_frag.glsl:6`, `atmosphere.glsl:62`).
- **Verdict:** keep as **the** sky-light source (it already carries the clouds' colour); make the analytic path the
  start-up fallback only, and move the capture off the swap frame (B3) — it is already cheap.

### C4. NightSky + night_sky.glsl — stars, nebula, Milky Way, shooting stars, aurora
- **Purpose:** "The moving parts of the night sky… the turn of the stars, the shooting stars and the aurora. All of it
  is worked out on the CPU from the clocks, a few numbers a frame" (`NightSky.java:7-12`); shader "layered stars…
  twinkle, a painted nebula and Milky Way band, shooting stars and, in the far north, an aurora" (`night_sky.glsl:1-5`).
- **Files:** `NightSky.java` (243): `starFrame` :58, `auroraNight` :91 (deterministic per game day, 30 % of nights
  none :35), `update` :110 (aurora target only in TUNDRA/ARCTIC biomes :33, 6 s fade), meteors :123-203;
  `night_sky.glsl` (226): `starLayer` :50, `nebulaColor` :101, `aurora` :160, `shootingStar` :177, `nightSky` :191.
  Plugin `updateNightSky` :≈2265 (aurora gated by `config.aurora()` and `environmentManager.getBiome()`).
- **Settings:** `starBrightness`, `nebulaStrength`, `aurora` (3).
- **Cost:** per sky pixel at night (several star layers, nebula noise, aurora curtains): untimed, inside `COMPOSITE`.
  Not in the sky cache (by design :5).
- **Overlaps:** none; "aurora gating" is the biome rule the HANDOVER says to keep.
- **Verdict:** keep; fold `starBrightness`+`nebulaStrength` into one "night sky" percentage (two settings → one).

### C5. Clouds + clouds.glsl — the half-resolution cloud slab and cloud shadows
- **Purpose:** "Volumetric clouds… one slab of cloud between two altitudes over the whole world, drawn in the
  composite's sky branch only, so they cost nothing where scenery covers the sky… 2.5D clouds in the spirit of
  Horizon Zero Dawn's height gradients" (`clouds.glsl:1-19`); Java owns the noise and per-frame uniforms
  (`Clouds.java:8-13`).
- **Files:** `Clouds.java` (503): `update` :232 (wind drift from `Wind.getMean`, coverage = base + (1−base)×cover
  :249), `skyRows` :108 (scissor the half-res cloud pass to rows that can hold cloud), `apply` :261, noise
  generation :336-499 (128², Worley + value). `clouds.glsl` (370): `cloudSkyDir` :146 (horizon at the drawn world's
  edge), `cloudSkyWeather` :161 (weather field sky texture by position), `cloudLayer` :244 (march + one light tap),
  `cloudsOverSky` :341, `cloudShadow` :356. Drawn by the composite's `COMPOSITE_CLOUDS` pass into `cloudHalf`
  (`composite_frag.glsl:10-12`, :326; plugin :≈3426-3440), optionally in one pass with the water's half pass.
- **Settings:** `clouds`, `clearWeatherClouds`, `cloudSpeed`, `cloudShadows`, `skyHorizonAtEdge` (5).
- **Cost (B1):** clouds 0.16 / 0.18 ms (half-res, scissored); `cloudShadow` adds coverage lookups per lit pixel in the
  composite (:423) and in the fog. Client thread for the wind drift.
- **Overlaps:** `cloudShadow` is a fifth occluder of direct light (shadow map, overcast factor, `overcastShadow`, haze
  `HAZE_SHADOW`, cloud shadow). The sky cache re-marches the same clouds at 12/24 steps per capture (fine: 128×64).
- **Dead/variant:** none found; `skyHorizonAtEdge=false` keeps a level horizon (variant worth dropping as a setting).
- **Verdict:** keep (user: clouds stay); drop `skyHorizonAtEdge` and `cloudSpeed` as settings (constants).

### C6. Lightning + lightning.glsl — strikes that take over the shadow caster
- **Purpose:** "Lightning in storms: now and then a strike that lights the sky a little, shows a bolt below the clouds…
  While the bolt's light is brighter than the sun's or moon's, the bolt is the light that casts shadows… The shadow map
  for the bolt's direction is drawn before the flash begins (PREPARE, a few frames), beside the sun's, and both stay
  drawn: taking over and handing back are each a swap of the two (ShadowMap.swapFront)" (`Lightning.java:8-26`).
- **Files:** `Lightning.java` (382): `update` :105 (OFF → PREPARE → pulses; drop if no bolt map after 1 s :41),
  `strike` :203, `aim` :273, `takeThunder` :287, `rumble` :304 (thunder loudness feeds the light), `getStrike` :357
  (`strikeLight` uniform), `bind` :378 (bolt position for the composite). `lightning.glsl` (92): glow in clouds + jagged
  bolt, sky pixels only. Plugin: bolt shadow map drawn/swapped :≈2548-2617, `takeThunder → audio.thunder` :≈2348.
  `sky.glsl` `strikeCaster`/`strikeSun`/`flashDip`/`LIGHTNING_SKY` :94-119.
- **Settings:** `weatherLightning`, dev `strikeLightning` (2).
- **Cost:** per event: an extra shadow-map render for the bolt direction (a full static+models shadow pass — B6's
  cost, 0.64–1.8 ms in B1 — once per strike, during PREPARE); per frame trivial. Thread: client/GL.
- **Overlaps:** a second shadow-caster direction interleaved with the sun's (ties into B6's one-mechanism shadow
  port); `Rumble` couples audio to light.
- **Debug-only:** `log()`/`isDebugEnabled` traces :155-181, :229, :329, :345; `strikeNow` via `strikeLightning`.
- **Verdict:** keep; re-plug onto Lumina's shadow mechanism in B6 (the swap-two-maps trick must survive or be replaced
  by a one-frame re-render budget).

---

## D. Skylight (sky visibility)

### D1. Voxel build — VoxelCapture → Voxelizer → VoxelGrid (+VoxelWater, SceneVoxels, SkyTerrain) → VoxelBuilder
- **Purpose:** "Voxelizes the base scene for the skylight, which traces the voxels (SkyGrid). Everything runs on a
  worker thread: after a region load the zones' triangles (captured as they upload, VoxelCapture) are voxelized, per
  zone so reused zones keep theirs" (`VoxelBuilder.java:15-19`). Grid: "208 × 208 half-tile columns, up to 96 layers…
  A cell is 0 when empty, else 1, with bit 31 set for foliage… each column keeps the world y of the highest surface"
  (`VoxelGrid.java:3-9`).
- **Files:** `VoxelCapture.java` (145; triangles kept per zone as `SceneUploader` writes them, `ownerOf` :33),
  `Voxelizer.java` (307; conservative tri-box rasterization :78-172, per-voxel area/foliage/highest), `VoxelGrid.java`
  (321; `assemble` :60, `addWater` :141, `trace` :187 DDA with foliage pass chance 0.4 :25, `fibonacci` :301),
  `VoxelWater.java` (53; water surface as a voxel layer from `WaterMap`), `SceneVoxels.java` (24), `SkyTerrain.java`
  (78; ground triangles for probe placement), `VoxelBuilder.java` (270; `prepare` :100 — rebuild 2 s after zones
  change, ≥ 10 s apart :27-28; `submitBuild` :165 copies heights/settings/terrain on the client thread, builds on
  "RuneLight voxels" thread + a ForkJoinPool of ≤ 4 :131; also builds `ModelBlockers` for the lights).
- **Settings:** none (wanted = `frameSkylightWanted || frameLightMapWanted`, i.e. outdoors+sun or point lights :≈3257).
- **Cost:** per scene load / zone change, worker threads; the result is handed over on the client thread and the
  sky-grid texture is uploaded in slices (D2). HANDOVER B3 lists "voxel build" among the four jobs landing on the swap
  frame (the handover on the client thread, not the build itself). The capture (`VoxelCapture.add`) runs inside the
  scene upload on the client thread (untimed).
- **Overlaps:** the voxel grid is also what render-plan §3.6a wants for point-light occlusion (replacing
  blockers/rooms/light map); `VoxelGrid.top` per column (:32-33) is the same quantity as the weather **occlusion map**
  (E6) at 2 × coarser resolution and without a GPU pass.
- **Verdict:** keep; restructure as the one occluder source (skylight + light occlusion + weather shelter).

### D2. SkyGrid + SkyGridMap — ray-traced sky visibility per tile corner, as a 3D texture
- **Purpose:** "How much of the sky the scene lets through, ray traced through its voxels… directional: the sky seen
  toward up and the four sides… Tracing is amortized… nearest the player first, up to a budget of rays"
  (`SkyGrid.java:7-39`); `SkyGridMap`: "A worker thread traces in time-bounded chunks… what changed is uploaded every
  UPLOAD_FRAMES frames" (`SkyGridMap.java:12-17`).
- **Files:** `SkyGrid.java` (1,011): layout 3 floors × 3 heights + 1 surface level = 10 levels :44-52, 32 rays × 16
  rotations :56-57, `MAX_DISTANCE` 32 voxels :59, texture 832×208×11 RGBA16F (`TEX_W/H/D` :65 → ≈15 MB), `setGrid`
  :205, `setWalls` :243 (quadrants joined across open edges), `trace` :284 (rings of 4-corner blocks from the player,
  budget), `traceUnit` :812, `selectDenseSamples` :508 (keeps only corners within 112 units of geometry). `SkyGridMap.java`
  (324): `update` :68 (one job at a time, ≤ 8 ms per job :141, `RAY_BUDGET` 4096 :24, upload every 6 frames :25,
  `READY_RINGS` 16 :27), `upload` :192 (`glTexSubImage3D` of changed boxes), `bindAo` :302. Shader half:
  `skylight_ao.glsl` (270): `skyGridLevel` :56, `skyGridVisibility` :125, `skylightVisibility` :227 (writes the shadow
  target's alpha at half res).
- **Settings:** none (gated by `ambientOcclusion`'s half pass + outdoors).
- **Cost (B1):** AO+skylight 1.04 / 0.91 ms (the skylight share is not split out: several 3D-texture fetches per AO
  texel, wall-edge nudging logic :30-37). Worker "RuneLight sky grid" at MIN_PRIORITY; upload on the client thread
  (`glTexSubImage3D`, up to the whole 15 MB after a move — B3's "voxel upload in one frame" stall at `glEndQuery`).
- **Overlaps:** second producer of sky visibility beside D3 and E6; the result darkens ambient *and* a `daylightFill`
  term (C/D4). Debug: `debugProbes` :164, `SkyProbeOverlay.java` (124, draws probe positions), `DebugView.SKYLIGHT`.
- **Dead/variant:** `resolution` parameter always 1 (`SkyGridMap():35 → this(1)`); `supportUnits` never incremented
  but logged as "legacy support corners" (`SkyGrid.java:512-523`, `SkyGridMap.java:131`); `selectNearbySamples` is a
  one-line wrapper :503; the unspaced code style of :508-556 marks a late patch. `skylight_ao.glsl:6` refers to a
  non-existent `skylight_lighting.glsl` (now `skylight_ambient.glsl`).
- **Verdict:** keep the quantity, restructure: slice the upload to a per-frame byte budget (B3), drop the dead
  resolution/support code, and merge D3 into the same texture (one skylight producer).

### D3. SkyField + SkyFieldMap — daylight flowing in under cover through open doors
- **Purpose:** "The sky's light flowing in under cover, per tile of a plane… the light spreads from them to the tiles
  around through every edge the walls leave open (WallMap…)… by a shortest-path search over the tile grid (Dial's
  buckets: cheap enough to rerun whenever a door opens)" (`SkyField.java:3-13`); worker + texture in `SkyFieldMap`
  (:9-12), "Until the first result the skylight keeps to the traced sky visibility alone".
- **Files:** `SkyField.java` (290): `covered` :40, `propagate` :60, `pack` :215 (2 texels per tile). `SkyFieldMap.java`
  (163): `update` :52 (job per wall/room/plane/reach change on its own single-thread executor), `upload` :88,
  `bindAo` :140. Shader: `skylight_ao.glsl:197 skyFieldVisibility`, combined in `skylightVisibility`. Reach constant
  `SKYLIGHT_REACH = 3` tiles (`RuneLightPlugin.java:≈135`).
- **Settings:** none.
- **Cost:** per door/room change, worker; one small 2D upload. Per AO texel one extra 2D fetch.
- **Overlaps:** third sky-visibility producer; `composite_frag.glsl:432-433` adds a separate `daylightFill` from
  `skyUpsampled` and `skylight_ambient.glsl:21 doorwayDaylight` (dead) expresses the same idea a third way.
- **Verdict:** merge into D2 (a flood pass over the voxel grid's covered columns, written into the same texture) so one
  texture answers "how much sky here".

### D4. skylight_ambient.glsl + composite ambient terms — the composite's half of the skylight
- **Purpose:** "the sky visibility upsampled with the AO scales the ambient and sky light, so interiors, overhangs and
  the shade under trees darken" (`skylight_ambient.glsl:1-2`); `SKYLIGHT_FLOOR` 0.4 (:6).
- **Files:** `skylight_ambient.glsl` (26): `skylightAmbient` :12, `doorwayDaylight` :21 (**no callers anywhere — dead**).
  `composite_frag.glsl:427-447`: `plain = ambientLight + skyFill` :429, `indirect = skylightAmbient(plain) × envAmbient ×
  ao` :430, `daylightFill = 0.7 × skyUpsampled × caster·ndl·(1−shadow)` :432-433 ("Retain the later skylight daylight
  fill" — a second doorway term), room floor `max(indirect, roomDaylight × envAmbient × roomFill × enclosed × 0.3 × ao)`
  :441-443, `interiorTint × roomFillLevel` :446 (RoomLight, lights area).
- **Cost:** per lit pixel in the composite; small individually, but this block is where every ambient mechanism meets.
- **Verdict:** restructure to one line: `ambient = skyIrradiance(n) × skyVisibility × envAmbient × ao` (+ the lights
  area's room fill); remove `doorwayDaylight` (dead) and fold `daylightFill` into the sky-field quantity.

---

## E. Weather

### E1. Weather data — WeatherData, WeatherType, WeatherClimate, WeatherRegions
- **Purpose:** catalogue "One kind of weather: a row of weather_types.json. Everything that follows the weather…
  eases toward these values" (`WeatherType.java:8-15`); climate "Which weather each biome gets in each season"
  (`WeatherClimate.java:7-12`); regions "The climate map of the world… the one place that decides where it snows,
  rains or sands" (`WeatherRegions.java:9-18`); loader with dev hot reload (`WeatherData.java:16-19`).
- **Files/data:** `weather_types.json` 25 types (CLEAR … GALE; ~30 numeric fields each: cover, cloudWisp/Dark, rain,
  snow, showers, drop/flake style, skyWhite, wind, gustiness, fog, areaFog, distanceFog, morningMist, fogTint, sun,
  haze, warmth, skySaturation, lightning, temperature, frost, wetRate, snowRate, persistence); `weather_climate.json`
  15 biomes × 4 seasons odds + temperatures + fog profiles; `weather_regions.json` 26 layers + 5 "looks" aliases.
  `WeatherRegions.biomeAt` :81, `WeatherClimate.odds` :266, `WeatherData.reloadIfChanged` :79 (1 s poll, dev only).
- **Settings:** `weatherMode` (AUTOMATIC/OFF/25 forced types).
- **Cost:** load once; tables rebuilt on hot reload (`WeatherType.version()` guards in `Climate`, `WeatherForecast`).
- **Overlaps:** `WeatherRegions.looks` duplicates `EnvironmentManager.looks` (biome → lighting preset) — the
  "look" of a biome is decided in two files (`weather_regions.json` aliases → `environments.json` presets).
- **Verdict:** keep the data; the `WeatherMode` enum of 27 entries is a dev convenience — keep as one setting.

### E2. WeatherForecast — the Markov chain of 8-hour slots
- **Purpose:** "every third of a game day (8 in-game hours) each biome gets a new weather… Everything is a pure function
  of the slot number and the biome, so it needs no saved state… Consecutive slots form a Markov chain… restarted…
  every EPOCH slots" (`WeatherForecast.java:8-20`).
- **Files:** `affinity` :45 (feature-distance transition weights), `odds` :113 (season blend 25 %), `at` :148 (≤ 12
  steps), `uniform` :237, `slot` :248.
- **Cost:** per biome per 0.1 s via `Climate` (≤ 12 draws × 25 types); client thread; trivial.
- **Verdict:** keep.

### E3. Climate + FogClimate — temperature, ground history, mist by hour
- **Purpose:** "Temperature, snow on the ground and wet ground per region (biome), as pure functions of the game clock…
  each region runs a small simulation in steps of one game hour… starts a couple of seasons' worth of days back"
  (`Climate.java:8-24`); FogClimate: "How much fog a region makes by itself over the day: mist at dawn… dusk… night…
  almost none in deserts, which get a faint midday heat haze" (`FogClimate.java:3-10`).
- **Files:** `Climate.java` (393): `temperature` :83, `withTemperature` :157 (rain/snow split, cached variants),
  `Region.update` :241 (hourly steps, history 4–30 game days :66-67 → up to 720 steps on first use per biome),
  `step` :280, `snowCover` :327. `FogClimate.java` (154): `dawn/dusk/night/midday` :67-88, `density` :103, `height`
  :110, `total` :121 (knee 2.2, cap 3.2, max 3.0), `haze` :139 (stars).
- **Settings:** `weatherMode` (AUTOMATIC uses it), `SEASON_DAYS` constant 7.
- **Cost:** per biome per 0.1 s (`WeatherField.update`), client thread; first touch of a biome replays its history
  (hundreds of steps, each a forecast lookup) — a small one-off CPU spike on region entry (untimed).
- **Overlaps:** `WeatherField.step` (:296-339) *also* eases WET/SNOW_COVER with its own build/dry/melt constants
  (`Weather.java:110-114`), then AUTOMATIC overwrites them with `Climate.ground` (`WeatherField.java:231-238`): two
  ground simulations; the field's is only live for forced modes. FogClimate feeds the fog *amount* (F1 via the field)
  and the fog *height*.
- **Verdict:** keep Climate; remove the field's own WET/SNOW_COVER easing (use Climate for forced modes too, with the
  forced type as the forecast).

### E4. WeatherField — the eased, blurred weather over the world around the player
- **Purpose:** "The weather over the world around the player… every cell (16x16 tiles) takes the forecast for its
  place's biome, eases toward it, and builds up its own wet ground and snow cover. This is the only place the weather
  is decided… uploaded as three tiny textures" (`WeatherField.java:10-21`).
- **Files:** 36 channels :35-42, `layout` :116 (33×33 cells, 528 tiles, `FIELD_RADIUS` 16 in `Weather.java:789`),
  `update` :179 (per 0.1 s: targets per biome :249, `step` per cell :296, `blur` :347 — two separable 5-tap passes ×
  36 channels × 1,089 cells ≈ 400k MACs), textures RGBA8/RG8/RGB8 33² :655-657 uploaded when bytes change :664,
  `sample`/`max`/`focus` :428-540 (CPU bilinear reads), `apply`/`setRect`/`setSkyRect` :737-757.
- **Settings:** `weatherIntensity` (scale), `weatherBuildup` (gradual).
- **Cost:** CPU ≈ every 0.1 s, client thread (`CpuTimer.WEATHER`): ~0.5 ms-class per update (estimate, untimed);
  the blur quantizes to 8 bits so CPU and GPU agree. GPU: bilinear fetches in rain/snow/fog/clouds/AO.
- **Overlaps:** 30 of the 36 channels are only ever read at the focus (`Weather.takeField` :649-674) — they are eased
  per cell but never sampled spatially; a per-biome ease at the player would do. Ground channels duplicate Climate (E3).
- **Verdict:** restructure: keep the 6 GPU channels as the spatial field; ease the other 30 once at the player.

### E5. WeatherVariation + Wind + wind.glsl — showers, swells, gusts, the wind
- **Purpose:** variation "slow swells and lulls over a minute or two, and now and then a short heavier burst… smooth
  deterministic noise of the time" (`WeatherVariation.java:3-7`); wind "a mean strength and gustiness that ease toward
  the weather's, a direction that wanders slowly, and gust fronts that travel across the world with it… It moves the
  clouds, slants the rain, carries the snow and ruffles the water" (`Wind.java:5-12`).
- **Files:** `WeatherVariation.java` (82); `Wind.java` (248): `update` :75, `gustAt` :107 (same function as
  `wind.glsl:35 windGustAt`), `apply` :235 (six uniforms :44). `Weather.showers` :695. Consumers: `sway.glsl`,
  `rain_vert`, `snow_vert`, `Clouds.update` (mean), `Water.advance` (`Weather.getWaterWind` :435), `WindSway`.
- **Settings:** none (`varying` is hard-wired `true` at `RuneLightPlugin.java:≈2320`).
- **Cost:** per frame trivial; `windGustAt` per vertex/particle in shaders.
- **Overlaps:** Java `Wind.gustAt` and GLSL `windGustAt` are the same noise kept in sync by comment (:16-22, "Must
  match wind.glsl"). Fine.
- **Dead:** the `varying` parameter of `Weather.update` is constant.
- **Verdict:** keep; drop the `varying` parameter.

### E6. Weather — the driver: field → player values, occlusion map, particle layers, mist, draw
- **Purpose:** "Weather: how cloudy, foggy, windy and stormy it is, and rain and snow as falling particles and rain
  splashes, each drawn in its own small pass over the composited scene… Shelter comes from an 'occlusion map': a
  top-down orthographic depth map of all static scenery around the player, drawn with every roof and every level…
  only redrawn when the player walks away from its center, the scene loads or a zone changes" (`Weather.java:9-35`).
- **Files:** `Weather.java` (1,221): `update` :587 (dt, field → `takeField` :649, variation, wind, lightning, occlusion
  dirtiness), `updateField` :716 (10 Hz, relayout on scene/plane/biome change :728-739), `renderOcclusion` :847 (1024²
  depth over ±64 tiles, 4 MB :39-41; redraws every zone with the shadow program when dirty — on scene load and every
  zone change, `GpuTimer.OCCLUSION` :≈2125), `draw` :973 (rain layers → mist (4 stages, 1/4 res, `drawMist` :351) →
  impacts → wade droplets → snow layers; `lastLayer` :1110 up to 6 layers each ×2 in area), `drawLayers` :1072,
  `fieldPeak` :1124, `groundActive`/`bindGround` :1134-1158 (AO pass), `rainLoudness` :939 (24 field samples).
  Counts: 24,000 drops/layer, 4,400 impacts, 12,000 flakes/layer at 100 % :76-78.
- **Settings:** `weatherMode`, `weatherIntensity`, `weatherBuildup`, `weatherLightning`, `weatherSounds`; dev
  `weatherFieldMap` (**no readers anywhere but its own getter — dead setting**).
- **Cost:** GPU `GpuTimer.WEATHER` and `OCCLUSION` exist but **neither is in the B1 row** (untimed; B1 was presumably
  measured in clear weather). The occlusion redraw is a full extra scene draw (all levels, all roofs) on every zone
  change — a hitch candidate for B3's list that is not on it. Particles: up to ~6 instanced draws × 2 kinds per frame,
  each quad reading depth + occlusion + light clusters (`weather_common.glsl:141 weatherPointLights`).
- **Overlaps:** the occlusion map is a third sky-visibility producer (D1/D2) at 8× the voxel grid's resolution;
  `EnvironmentManager.getSheltered` is a fourth shelter notion; `fog` multipliers (`fogMultiplier`, `fogHeightMultiplier`,
  `areaFogWeight`, `distanceFogScale`, `getFogTint`, `FOG_CAVE`) are five fog knobs exported from here (§I.1).
- **Verdict:** keep the look; restructure: replace the occlusion map with `VoxelGrid.top` (one occluder source), time
  WEATHER/OCCLUSION in B1's table before B4, and remove the `weatherFieldMap` setting.

### E7. Rain pass — rain_vert.glsl / rain_frag.glsl (drops, impacts, mist, wade droplets)
- **Purpose:** "Rain: one instanced quad per drop or splash, no vertex buffer… Drops end on the highest scenery in
  their column (occlusion map), so they stop on roofs" (`rain_vert.glsl:3-6`); mist "where it beats down, the air just
  over everything it hits fills with spray… Worked out at 1 / MIST_DOWN of the scene's resolution… in stages"
  (`rain_frag.glsl:19-27`).
- **Files:** `rain_vert.glsl` (324): wind field :39, `drop` :129, `wadeDroplet` :216, `impact` :260; `rain_frag.glsl`
  (140): `mistSource` :47, `mistBlur` :74, `mistStageColor` :96. Reads `sceneMaterial`, `waterSurface` (splashes land
  on the water pass's pixels), light clusters, light map, `wind.glsl`, `weather_wade.glsl`.
- **Settings:** via E6.
- **Cost:** per frame while raining: instanced draws + 4 fullscreen passes at 1/4 res (mist) — untimed.
- **Overlaps:** the mist is a second "fog-like veil" (haze over wet ground) beside the volumetric fog; the wade
  droplets are a third particle kind.
- **Verdict:** keep; consider the mist a candidate to express through the volumetric fog's density (one veil) later.

### E8. Snow pass — snow_vert.glsl / snow_frag.glsl
- **Purpose:** "one instanced camera-facing sprite per flake… Flakes below the highest scenery in their column
  (occlusion map) are hidden" (`snow_vert.glsl:3-5`).
- **Files:** `snow_vert.glsl` (95), `snow_frag.glsl` (32). Shares `weather_common.glsl` with E7 (layers, occlusion,
  field deltas, point lights).
- **Cost:** per frame while snowing (or dust/ash: `flakeColor`); untimed.
- **Verdict:** keep.

### E9. WeatherGround + weather_ground_ao.glsl + weather_ground.glsl + weather_wade.glsl/WadeSteps — snow cover and puddles
- **Purpose:** "Snow and rain puddles as part of the surfaces: the half resolution AO pass works out how much cover a
  pixel gets… into the shadow target's blue channel, and the composite lays it out as blobs of noise before lighting"
  (`WeatherGround.java:5-10`); puddles "drawn as a water of their own reflecting the sky" (`weather_ground.glsl:5-6`);
  wade "Ripples in puddles where people have just waded" (`weather_wade.glsl:1`).
- **Files:** `WeatherGround.java` (86), `weather_ground_ao.glsl` (65; `groundExposure` 3×3 compare taps :26-28),
  `weather_ground.glsl` (187; `weatherGroundSurface` :125, `weatherPuddleWater` :173), `weather_wade.glsl` (43),
  `WadeSteps.java`. Composite: :411, :519.
- **Settings:** via E6.
- **Cost:** per AO texel when active (9 shadow-compare taps + field fetch), per composite pixel (Perlin + gradients);
  inside `AO` and `COMPOSITE` timers, untimed separately.
- **Overlaps:** puddles are a **second water shading** (sky reflection, ripples) beside `water.glsl`; the exposure is a
  fourth sky-visibility consumer of the occlusion map.
- **Verdict:** keep; after the occluder merge (D1) read exposure from the voxel top; keep puddles' own shading (it is
  cheap and the water surface pass isn't there).

### E10. WindSway + sway.glsl + wind.json — plants swaying
- **Purpose:** "Plants that sway in the wind, after 117 HD's wind displacement (wind.json, from
  tools/import_117hd_wind.py). Each vertex of a swaying object carries a code in the fourth position short…
  The codes are always baked; the config only changes uniforms" (`WindSway.java:16-29`).
- **Files:** `WindSway.java` (235): `forObject` :121, `vertexCode` :159; `sway.glsl` (75): `windDisplacement` :37;
  `wind.json` (object id → mode/modifier, 14 kB); `tools/import_117hd_wind.py` (106).
- **Settings:** `windSway`, `windSwayStrength`, `windSwayShadows`, `windSwayFps` (4).
- **Cost:** per vertex in the scene vertex shader (and the shadow pass when `windSwayShadows`: "redrawing those plants
  into the shadow map every frame", config :753); untimed. The upload-time baking of codes is in the uploader (not this
  area).
- **Verdict:** keep; collapse to one setting (`windSway` on/off; strength, shadows, fps become constants — fps belongs to
  the pacing design, shadows to B6).

### E11. WeatherAudio + AudioMixer — rain loops and thunder
- **Purpose:** "rain, a light loop at first and a heavy one as it builds… and a rumble of thunder with every lightning
  strike… both are muffled inside buildings… One thread mixes (AudioMixer) into one SourceDataLine, opened when there
  is something to hear" (`WeatherAudio.java:15-22`).
- **Files:** `WeatherAudio.java` (341): `update` :134, `thunder` :157, `run` :206 (thread "RuneLight weather audio"
  :200), `indoorMix` :332 (uses `RoomMap`); `AudioMixer.java` (231): `render` :120 (per-sample smoothing, low-pass,
  soft clip); `sounds/*.wav` 1.6 MB from `tools/make_weather_sounds.py` (199).
- **Settings:** `weatherSounds` (volume).
- **Cost:** own thread; client thread sets targets per frame. Not a render cost.
- **Verdict:** keep.

---

## F. Fog

### F1. VolumetricFog + fog_frag.glsl + fog_apply.glsl + FogGround — the half-resolution height-fog march
- **Purpose:** "Lightweight volumetric fog. A half resolution pass (fog_frag.glsl) marches each view ray a fixed number
  of steps through a height fog layer around the player, its height measured from the terrain (FogGround)… the denoise
  smooths its sun shafts' grain with the AO and shadows… and the composite upsamples it depth-aware"
  (`VolumetricFog.java:10-15`); "All fog parameters are set in updateParameters" (:17-18). FogGround: "fog lies on the
  terrain instead of on one flat plane through the player… Water tiles hold the surface height… The building rooms
  come with it" (`FogGround.java:9-18`).
- **Files:** `VolumetricFog.java` (603): `updateParameters` :207 (density = `volumetricFogDensity × 1e-5 × areaDensity`,
  height 3 tiles × areaHeight, fade 40 tiles/edge 7, steps 6/8/12 by `fogQuality` :221), `sendGround` :257 (RGBA32F
  4×209 × 209 re-upload when rooms/open roofs change), `render` :367 (≈30 uniforms, weather-field delta :426-437,
  water stop :441), `getMinGlow` :331 (light culling threshold), 3D noise 64³ :26. `fog_frag.glsl` (453): `weatherFogFactor`
  :75, `fogGroundAt` :142, `fogRoomFactor` :150 (indoor 0.03 / open-roof 0.25 :35-37), `waterSurfaceDistance` :165,
  `pointGlow` :196 (analytic point-light glow per light per ray), `main` :234 (sky + sun-through-shadow-map +
  point lights per step, IGN jitter). `fog_apply.glsl` (57): `upsampleFog` :18 (depth-aware 2×2), `applyVolumetricFog`
  :52; composite :383 (sky), :544 (scene). `FogGround.java` (286): `build` :41 (per scene, from `loadScene`, "any
  thread" :225), `texels` :134 (rooms + open rooms into the same texture), `highest` :264 (per-frame bound).
  Plugin: inputs assembled at :≈2186-2201: `areaDensity = weather.fogMultiplier × (1 + weather.areaFogWeight ×
  min(areaFogDepth / AREA_FOG_DEPTH, 2))`, `areaHeight = weather.fogHeightMultiplier`, `areaTint = weather.getFogTint`,
  `weatherFog`, field; `GpuTimer.FOG` :≈3388; denoise only with shafts (`hasShafts` :358).
- **Settings:** `volumetricFog`, `fogQuality`, `volumetricFogDensity` (3).
- **Cost (B1):** **volumetric fog 1.62 / 1.47 ms — the single heaviest pass**, plus its share of filtering 0.59 / 0.52
  and of the composite's upsample (composite 1.38 / 1.45). At render scale 25 %: fog 0.32 (pixel-bound). Per-texel
  work: 6–12 steps × (shadow compare + sky colour + cluster light loop + 3D noise ×2 + ground fetch + field fetch).
  Per scene load: `FogGround.build` on the load path; `sendGround` RGBA32F upload on the client thread when rooms
  change (≈0.7 MB; a candidate for the B3 job list, untimed).
- **Overlaps (fog amount — the full stack):** `config.volumetricFogDensity` × `Weather.fogMultiplier` (= field FOG =
  `FogClimate.total(type.fog × density(profile, hour, calm, cover))`, eased 15 s, `FOG_CAVE` 0.6 underground) ×
  `(1 + areaFogWeight × area.fogDepth/AREA_FOG_DEPTH)` × field spatial delta (`weatherFogFactor`) × `fogRoomFactor` ×
  noise/banks × height from `FogClimate.height` × `capFogCover`; **and separately** the distance fog `envFogAmount`
  (B2) from the same `area.fogDepth × weather.distanceFogScale`, with its own cap and colour; **and** `envHaze` on the
  sky; **and** `EdgeFade.fogginess`; **and** the rain mist (E7). Fog colour: fog pass = sky/sun/lamp-lit samples ×
  `fogTint`; distance fog = `envFog(horizon or client sky)`; two colours for one medium.
- **Dead/variant:** `fogQuality` enum 6/8/12 is a quality knob of the kind rule 5 wants gone; `hasShafts` is trivially
  `outdoors && shadows != null`.
- **Verdict:** keep (user: volumetric fog stays) and make it **the** fog: one extinction field σ(x) (weather × climate ×
  area), marched near, continued analytically beyond `fadeEnd` to the draw distance with the same colour, feeding
  `EdgeFade` and the sky haze; remove `envFogAmount`/`envFog` as separate mechanisms; B4 decides steps/res (drop
  `fogQuality`).

---

## G. Water

### G1. WaterOverrides — 117 HD's water type overrides and hidden ripples
- **Purpose:** "117 HD's water definitions (water_overrides.json, converted by tools/import_117hd_water.py), matched as
  117 HD matches them" (`WaterOverrides.java:14-17`).
- **Files:** `WaterOverrides.java` (293): `tileType` :144, `hidesFace` :207; `water_overrides.json` 104 kB;
  `tools/import_117hd_water.py` (218).
- **Cost:** per scene load (tile matching inside `WaterMap.load`), per face at upload (`hidesFace`, uploader side).
- **Verdict:** keep (data).

### G2. WaterFlow + tools/water — the world flow map
- **Purpose:** "Which way the water flows across the whole world, worked out offline from the world map
  (tools/water/build_flow.py) and shipped as water_flow.dat: per 64x64 region with water, flow vectors on a grid of
  2 x 2 samples per tile (version 3…)" (`WaterFlow.java:14-24`).
- **Files:** `WaterFlow.java` (263): `read` :97, `region` :130 (inflate on demand, LRU 32), `flow`/`corner` :243-254;
  `water_flow.dat` 591 kB (header read: `RLWF`, version 3 big-endian); `tools/water/build_flow.py` (516),
  `channel_flow.py` (262, potential-flow solve), `crop.py`, `worldmap.py`, `annotations.json`.
- **Cost:** per scene load (a few region inflates); memory ≈ 550 kB deflated.
- **Dead/variant:** version-2 file path (one sample per tile, unshaped) and `WaterBodies.shapeFlow` for it
  (`WaterBodies.java:596`) — the shipped file is v3, so the v2 branch is legacy.
- **Verdict:** **flow maps: to be discussed later (user)** — keep the data and loader as they are; note the v2 path as
  removable.

### G3. WaterMap + WaterBodies — the scene's water map (CPU, at load)
- **Purpose:** "The water of one top-level scene, worked out on the CPU when the scene loads. Water tiles are the ones
  with the game's water textures (117 HD's list). Their terrain is uploaded lowered into a bed… the surface is not
  geometry at all but drawn by the composite pass (water.glsl) over the bed, from the surface heights and the per-tile
  data in texture: flow, water type, distance to shore, wave energy" (`WaterMap.java:17-23`); bodies "Connected bodies
  of water… what kind each is, and how its water flows… fastest in the middle of a channel and slow at its banks"
  (`WaterBodies.java:5-19`).
- **Files:** `WaterMap.java` (1,648): `build` :198-206 (called from `RuneLightPlugin.loadScene` :≈4423, i.e. the
  scene-load path; `Water.sceneLoaded` then `swap` on the GL thread), `load` :210, `depthLevels` :509 (bed depth by
  shore distance, 117 HD slopes :53-58), `findContacts` :739 (things standing in water → contact texture),
  `spreadLine`/`terrainFalls`/`markFall`/`pullToLines` :832-1136 (waterfall lips/feet → "fall frame"), `buildTexture`
  :1380 (3 pages RGBA32F 209² : page 0 height/flow/type, page 1 shore distance/**wave energy**/share/**turbulence**,
  page 2 land distance/**open-sea share**/**fall frame** :77-82), `distanceToLand` :1529 (EDT). `WaterBodies.java`
  (782): `solve` :132 (label → shore distance → classify → flow → smooth → shape → ridges → openSea → energies).
- **Settings:** `water`.
- **Cost:** per scene load, CPU, on whichever thread runs `loadScene` (EDT over 209², body labelling, flow BFS, falls):
  ms-class, untimed — the B3 ledger should list it. Upload: one RGBA32F 627×209 texture (1 MB) + contact R8 on swap.
- **Overlaps / wave-only parts:** `WaterBodies.energies` :744, `ridges` :640, `openSea` :735, `WaterMap.findContacts`,
  `terrainFalls` and the fall frame, page 1 g/a and page 2 g/b/a exist only to drive **waves and foam** (G5).
  `VoxelWater` (D1) and `fog_frag` water stop (F1) read page 0 heights — those stay.
- **Verdict:** keep the bed/type/height/shore part; remove the wave-energy, turbulence, open-sea, falls and contact
  computations with the waves (G5); flow stays pending the flow-map discussion.

### G4. WaterSurface — the surface pass into its own target
- **Purpose:** "The water surface as geometry, drawn before the composite into its own target: per pixel how far away
  the surface is and its normal, type, foam and speed… one RGBA16UI texture (64 bits)… The mesh: one instance per
  tile… Each tile is one flat quad at the water map's corner heights; the waves are all in its normal"
  (`WaterSurface.java:10-24`).
- **Files:** `WaterSurface.java` (841): `build` :125 (instances by 8-tile chunks), `anyVisible` :228 (rect + chunk
  culling), `ensureTarget` :431 (RGBA16UI + depth32F, sized in 256-px steps), `draw` :485, `drawChunks` :783;
  `water_vert.glsl` (48), `water_frag.glsl` (503; packs normal/slot/foam/depth/distance/caustic/foam coverage
  :486-502). Plugin `GpuTimer.WATER` :≈3286.
- **Settings:** `water`.
- **Cost (B1):** water 0.07 / 0.11 ms at the small canvas (includes the wave bake below). Per frame when water is in
  view; the target is cleared whole each frame (:113-115 comment).
- **Overlaps:** exists because "the composite… has no sampler to spare" (:16-17). Without waves the surface is a
  per-tile plane whose heights are already in the data texture (page 0): the composite could intersect the view ray
  with that plane analytically and drop this pass and its 64-bit packing.
- **Verdict:** keep for now (cheap); restructure later into the composite once the waves are gone (one water pass).

### G5. The wave system — wave maps baked at 20 Hz, blended in the surface pass  **(TO REMOVE)**
- **Purpose:** "The normal: a choppy sea of sine octaves carried along the flow map in two phases (still water runs
  slower), worked out with the foam a few steps a second into world-space wave maps (waterBake, WaterSurface.bake) and
  blended here" (`water_frag.glsl:7-9`); "The wave maps: the waves and foam worked out at STEPS_PER_SECOND into
  world-space maps of WAVE_MAP_TILES tiles a side (wrapping, WAVE_MAP_TEXELS texels a tile), each over two frames, half
  the chunks each; the surface pass blends the last two finished ones. Three, so one is being worked out while two are
  shown" (`WaterSurface.java:84-86`).
- **How:** 3 × RGBA16F maps of (96 × 12)² = 1,152² texels (≈ 8 MB each, mipmapped, anisotropic) wrap around the focal
  point; every frame half of the wanted chunks of the next step are drawn with `waterBake = 1` (`water_frag.glsl:396`
  → `waterWaveField` :313 → `WaveOut` slope xy + foam), `glGenerateMipmap` when a map completes (:728), chunks that
  came into view are caught up (:682); the on-screen pass fetches `waterWavesA/B` and mixes by `waterWaveBlend`
  (:429). Drift/wind come from `Water.advance` (`Water.java:403`) driven by `Weather.getWaterWind`.
- **Cost:** per frame: bake draws over a 1,152² target (fixed, canvas-independent) + a mipmap chain of 1,152² every
  1/20 s; inside `WATER` 0.07–0.11 ms in B1 (so small at that view; **untimed in a wide river/sea view**). 24 MB VRAM.
- **Verdict:** **remove as a unit** (user). The exact unit is listed in §K.

### G6. water.glsl — the composite's water shading (half-res shade pass + full-res blend)
- **Purpose:** "Looking down the water is clear over the shallows and colored by its depth… at a slant the surface
  turns into the sky, clouds and all, by Fresnel, with sharp reflections of the world. Sharp glints from the sun and
  moon, lamps' streaks, foam. Two passes of the composite. The first (COMPOSITE_WATER…) runs at half resolution over
  the water only… The second… only blends" (`water.glsl:7-15`).
- **Files:** `water.glsl` (741): `waterShadeHalf` :321 (sky reflection :251, screen-space world reflection trace :208
  when `waterReflections`, lamps :272, packed to RGBA32UI), `waterOver` :501 (depth tint :156, surface blend, glitter
  :476, sun glint :457), `waterFake` :650 (water off: flat type colour, uses `waterTime`). `water_common.glsl` (276):
  types/palette :18-40 (117 HD colours), pages :91-101, `waterFlowPhases` :108, foam cells :201-224, `waveNoise*`
  :240-263. Plugin: half pass shares a draw with the clouds (:≈3426-3440); `Water.setComposite` :358; AO-pass water
  uniforms `Water.locateAo/bindAo` :126-153 (the AO skips water pixels — a water-related path in the AO).
- **Settings:** `water`, `waterReflections`, `waterCaustics` (3) + dev `waterDebugView`.
- **Cost:** half-res pass scissored to the water rect (inside `CLOUDS`/`WATER` timer), per full-res water pixel in
  the composite; untimed separately.
- **Overlaps:** puddles (E9) shade water a second way; `waterFake` is a third (water-off) look; reflections here and
  `waterSkyReflection` duplicate the sky evaluation already done for sky pixels.
- **Verdict:** keep the look; after the wave removal the surface normal is up (+ rain rings), so `waterGlitterNormal`/
  `waterNormalAt` (:440-456) simplify; `waterReflections` → constant on (or off, by measurement), one `water` setting.

### G7. Caustics — 117 HD's caustics map carried by the flow
- **Purpose:** "The caustics are 117 HD's, carried along by the same flow and drawn out along fast water"
  (`water_frag.glsl:9`); "117 HD's caustics map (layer 1 of the noise texture), its red, green and blue taken this many
  texels apart diagonally… colored fringes for one tap per copy" (`Water.java:23-26`).
- **Files:** `Water.caustics`/`aberrate` :523-690 (builds layer 1 of the 256² `sampler2DArray` from
  `water_caustics.jpg`), `water_frag.glsl:181 causticAt`, :189 `causticMoved`, :451-471 (refraction toward the
  caster, packed 8 bits), applied on the bed in `water.glsl` (`waterOver`).
- **Settings:** `waterCaustics`.
- **Cost:** 1–2 texture taps per water pixel in the surface pass.
- **Overlaps:** depends on the flow phases (`m0/m1/w0`) which are flow-map territory, and on `look.x < 0.7` type test.
- **Verdict:** keep (117 HD data, cheap); make the setting a constant.

### G8. Rain rings on water and foam — weather and shore effects inside the wave field
- **Purpose:** "and rain rings" (`water_frag.glsl:9`, `rainRings` :199, foam from rings `RAIN_FOAM` :449); foam:
  "The hard edged foam at xz: the shore waves' and the foam around things standing in the water, and on terrain falls"
  (:383-385), soft rapids/bank foam :369-379.
- **Files:** `water_frag.glsl`: `shoreFoam` :231, `waterFoamStill` :269, `waterFoamHeight` :276, `contactFoam` :285,
  `fallFrame/fallLaid/fallFoamHeight` :296-308, `hardFoam` :386; `water_common.glsl` `waterFoamRim/Cells/Edges`
  :201-224; `WaterMap.contact` texture (`Water.java:247-268`).
- **Cost:** per water pixel / per bake texel.
- **Verdict:** foam goes with the waves (it is the wave field's z channel and reads wave energy/turbulence/open-sea/fall
  frame — §K); **rain rings** are rain-on-water, not waves: keep them as the one dynamic normal term (they are computed
  on screen, not baked: :442-445).

### G9. Water in other systems (cross-references)
- `VoxelWater` (D1) — surface as a voxel layer for the skylight. Keep.
- `fog_frag.glsl:155-185` — fog rays stop at the surface (`fogWaterLevel`, `waterSurfaceDistance`). Keep.
- `rain_vert.glsl:21-22` — splashes land by the water pass's pixels (`waterSurface`, `waterRect`); `weather_wade.glsl` —
  puddle ripples. Keep (after G4 is restructured, read the data texture instead).
- `Weather.getWaterWind` :435 and `Water.advance` :403 — the waves' drift; part of §K (keep only if rain rings need
  wind, which they don't).
- `Water.locateAo/bindAo` :126-153 — the AO pass's water rect. Keep with G4; disappears with it.
- Debug: `WaterDebug` enum (MAP, WAVES), `waterDebug` uniforms in `water_frag.glsl:21`/`water.glsl:27`.

---

## H. Data files and tools (summary)
| Data | Size | Producer | Consumer | Verdict |
|---|---|---|---|---|
| `environments.json` | 99 kB (231 envs, 2,228 boxes) | `tools/import_environments.py` (442) | `EnvironmentManager.load` | keep |
| `weather_types.json` / `weather_climate.json` / `weather_regions.json` | 6 / 14 / 7 kB | hand-written (3D-Weather-inspired) | `WeatherData` | keep |
| `wind.json` | 14 kB | `tools/import_117hd_wind.py` (106) | `WindSway.load` | keep |
| `water_overrides.json` | 104 kB | `tools/import_117hd_water.py` (218) | `WaterOverrides.load` | keep |
| `water_flow.dat` | 591 kB (v3) | `tools/water/build_flow.py` + `channel_flow.py` + `worldmap.py` + `annotations.json` | `WaterFlow` | keep pending the flow-map discussion |
| `water_caustics.jpg` | 9 kB | 117 HD | `Water.caustics` | keep |
| `sounds/*.wav` + `CREDITS.txt` | 1.6 MB | `tools/make_weather_sounds.py` (199) | `WeatherAudio` | keep |

---

## I. Cross-cutting: every mechanism per quantity

### I.1 Fog colour and amount (11 places)
| # | Mechanism | Where | Sets |
|---|---|---|---|
| 1 | Config density | `VolumetricFog.updateParameters:211` | base extinction |
| 2 | Weather type `fog`, `areaFog`, `distanceFog`, `fogTint`, `morningMist` | `WeatherType.java:52-60` → `WeatherField.targets:259-277` | amount multipliers, tint |
| 3 | FogClimate mist/haze by hour, humidity, height | `FogClimate.density/height/total:103-131` | amount, height |
| 4 | Field spatial delta (FOG channel, blurred) | `WeatherField` → `fog_frag.glsl:75 weatherFogFactor` | amount by position |
| 5 | Player multipliers + cave 0.6 | `Weather.fogMultiplier/fogHeightMultiplier/areaFogWeight/distanceFogScale/getFogTint:476-531` | amount, height, tint |
| 6 | Area fogDepth into the volumetric density | `RuneLightPlugin.java:≈2192-2194` (`AREA_FOG_DEPTH`) | amount |
| 7 | Area distance fog (117 HD exponential) | `EnvironmentUniforms.set:63-66`, `environment.glsl:43 envFogAmount`, `composite_frag.glsl:530,539` | a second amount |
| 8 | Area fog colour mixed with horizon | `environment.glsl:29 envFog`, `composite_frag.glsl:356` | a second colour |
| 9 | Area haze over the sky | `environment.glsl:56 envSky`, `EnvironmentUniforms:66` | sky → fog colour |
| 10 | Indoor / open-roof density, water stop, noise & banks, near/edge fades, cover cap | `fog_frag.glsl:150,159-185; VolumetricFog:35-39,216-219; fog_cover.glsl:16` | amount shaping |
| 11 | Edge-fade width from fogginess; rain mist veil | `EdgeFade.fogginess:91`; `rain_frag.glsl:19-27` | amount-like |

**Could be one:** a single extinction field σ(x) = config × (weather field FOG, which already folds 2+3) × (1 + area
depth) with one colour (sky-lit, tinted, mixed toward the area colour by its weight), marched by F1 near and continued
analytically to the draw distance; 7, 8, 9 and 11's fogginess read σ instead of recomputing. The look (the fog stack's
tuning) is preserved by fitting the analytic tail to today's `envFogAmount` curve.

### I.2 Ambient light (10 places)
| # | Mechanism | Where |
|---|---|---|
| 1 | Palette ambient by altitude, dusk tint, night gain, overcast | `sky.glsl:261 ambientCover`, `:201 overcastAmbient` |
| 2 | Sky light on up-facing surfaces + overcast energy boost (≤ 200×) | `sky.glsl:315 skyLightAt`, `:289 overcastSkyBoost` |
| 3 | Sky-cache irradiance, eased against 2 | `atmosphere.glsl:53 skyFill`, `SkyCache.easeComposite:316` |
| 4 | Weather haze lift and warmth tint of ambient | `sky.glsl:85 HAZE_AMBIENT`, `:90 WARMTH_FILL` (via `weatherLight`) |
| 5 | Lightning flash dip and sky-flash add | `sky.glsl:102 flashDip`, `:332 skyLightningAt` |
| 6 | Sky visibility floor 0.4 | `skylight_ambient.glsl:12 skylightAmbient` |
| 7 | `daylightFill` 0.7 × skyUpsampled × direct | `composite_frag.glsl:432-433` |
| 8 | Room daylight floor and RoomLight fill (lights area) | `composite_frag.glsl:435-447`, `rooms.glsl:87-93` |
| 9 | Area multiplier (+ blendAround spatial blend) | `environment.glsl:5 envAmbient`, `EnvironmentManager.blendAround:336` |
| 10 | Cave key light | `environment.glsl:63 envCaveLight`, `composite_frag.glsl:458` |
| — | dead: `doorwayDaylight` | `skylight_ambient.glsl:21` (no callers) |

**Could be one:** ambient(n) = skyIrradiance(n) [3, which already contains 1's palette, 4's haze/warmth via the capture,
and the clouds] × skyVisibility [6, with 7 folded into the sky-field value] × envAmbient [9] × ao; 2 only as the
first-frame fallback; 5 stays a two-line modifier; 8 and 10 belong to the lights/cave areas.

### I.3 Sky visibility / skylight producers (5 + 1 debug)
| # | Producer | Resolution / storage | Thread | Consumers |
|---|---|---|---|---|
| 1 | Voxel grid trace (`SkyGrid`) | 3D RGBA16F 832×208×11 ≈ 15 MB | worker + client upload | AO pass → composite ambient |
| 2 | Wall flood fill (`SkyField`) | 2D 2 texels/tile | worker | AO pass (same shader) |
| 3 | Weather occlusion map (top-down depth) | 1024² depth, 4 MB, GPU redraw of all levels per zone change | GL | rain/snow/mist, snow & puddle exposure |
| 4 | Under-roof flag → `sheltered` | scalar | client | weather at the player, audio |
| 5 | `VoxelGrid.top` per column (unused as a texture) | 208² floats | worker | roof-top test inside 1 |
| d | `SkyProbeOverlay`, `debugProbes`, `DebugView.SKYLIGHT` | — | — | debug |

**Could be one:** the voxel grid (1) extended with a flood pass (2) and its `top` column uploaded as the occluder height
(3, 4) — one producer, one upload budget per frame.

### I.4 Water-related passes (13)
| # | Pass | Per | Verdict |
|---|---|---|---|
| 1 | `WaterMap`/`WaterBodies` CPU build (bed, types, shore EDT, flow, **energy, turbulence, open sea, falls, contacts**) | scene load | keep minus the bold wave-only parts |
| 2 | Data texture + contact texture upload | swap | keep (contact goes with waves) |
| 3 | Bed lowering (uploader, reads `bedOffset`) | upload | keep (outside area) |
| 4 | `VoxelWater` layer | voxel build | keep |
| 5 | Fog water stop | fog texel | keep |
| 6 | Surface pass (`WaterSurface.draw`, RGBA16UI + depth) | frame | keep now; fold into composite later |
| 7 | **Wave bake** (3 × 1,152² RGBA16F, 20 Hz, mipmaps) | frame | **remove** |
| 8 | Caustics (surface pass, bed) | water pixel | keep |
| 9 | Rain rings | water pixel | keep |
| 10 | Foam (shore/contact/fall/bank/rapids) | bake/water pixel | remove with 7 |
| 11 | Composite half-res shade (`waterShadeHalf`: sky/world reflections, lamps) | water pixel/4 | keep; reflections constant |
| 12 | Composite blend (`waterOver`, glints, glitter) + `waterFake` | water pixel | keep; simplify normal |
| 13 | AO-pass water rect, rain splashes by water pixels, puddle water (`weatherPuddleWater`) | AO texel / particle / pixel | keep; puddles are a second water shading (accept) |

---

## J. Dead paths, variants, debug-only code
- `weatherFieldMap` config (`RuneLightConfig.java:956-962`): no reader anywhere → dead setting.
- `doorwayDaylight` (`skylight_ambient.glsl:21`): defined, never called → dead.
- `SkyGrid` `resolution` ≠ 1 path, `supportUnits`/"legacy support corners" counter (`SkyGrid.java:512-523`,
  `SkyGridMap.java:35,131`), `selectNearbySamples` wrapper (:503) → dead/vestigial.
- `skylight_ao.glsl:6` comment → non-existent `skylight_lighting.glsl`.
- `WaterFlow` version-2 branch + `WaterBodies.shapeFlow` for v2 (`WaterBodies.java:596`, `WaterFlow.java:19-21`): file
  is v3 → legacy.
- `WeatherField` own WET/SNOW_COVER easing (`:322-338`) overwritten by Climate in AUTOMATIC → half-dead duplicate.
- `Weather.update(... varying=true ...)` constant parameter (`RuneLightPlugin.java:≈2320`).
- Debug-only: `SkyProbeOverlay`, `SkyGridMap.debugProbes`, `DebugView.SKYLIGHT`, `WaterDebug` (MAP/WAVES) + `waterDebug`
  uniforms, `strikeLightning`, `Lightning.log`, `LookMonitor` (reads `EnvironmentUniforms.values`), `WeatherData`
  hot reload (dev client), `SkyGridMap` timing logs (:149), `System.getProperty("runelight.compositeCombined")` parts
  (composite, outside area).
- Variants worth collapsing to constants: `fogQuality`, `cloudSpeed`, `skyHorizonAtEdge`, `windSwayStrength`,
  `windSwayShadows`, `windSwayFps`, `waterReflections`, `waterCaustics`, `starBrightness`+`nebulaStrength` (→ one),
  `clearWeatherClouds`.

---

## K. The wave system — exact removal unit
Remove together (everything that exists only for the baked wave normal and its foam). Not in the unit: the flow map
(`WaterFlow`, `WaterMap` page 0 flow, `waterFlowPhases`, `BANK_SLOW` in `water_vert.glsl:32-34`) — to be discussed;
caustics (G7); rain rings (G8); the surface pass itself (G4) and the composite shading (G6), which simplify but stay.

**Java**
- `WaterSurface.java`: constants `WAVE_MAP_TILES`, `WAVE_MAP_REACH`, `WAVE_MAP_TEXELS`, `STEPS_PER_SECOND` (:87-90),
  `WAVE_VIEW_SLACK` (:102); fields `uniBake`, `uniWaveBlend` (:80-82), `waveTex`, `waveFbo` (:91), `builtStep`,
  `shownStep`, `halvesDone`, `lastTime` (:94-96), `waveBaked`, `waveMotion`, `motion` (:103-105), chunk flags `NEEDED`,
  `WANTED`, `PICKED` (:107), `waveUnitA`, `waveUnitB` (:110); methods `bakeWaves` :568, `markWaveChunks` :616,
  `startStep` :648, `bakeStep` :660, `catchUp` :682, `pick` :700, `bindMap` :708, `bakePicked` :719, `mipmap` :728,
  `setMotion` :736, `ensureWaveMaps` :742; in `draw` the lines :522-531 (bake call, `uniBake=0`, `uniWaveBlend`,
  binding `waveTex[...]` on `waveUnitA/B`) and :551-554 (unbind); in `free` :822-828 (delete wave FBOs/textures); the
  wave-unit parameters of `setProgram`/`upload` that pass `waveUnitA/B` (signature at :368 region — verify callers in
  `Water.setSurfaceProgram` and `RuneLightPlugin.java:≈1282,1589`). `WAVE_MARGIN` (:33) stays if the surface still
  needs a bounds margin for the rain rings; otherwise goes.
- `Water.java`: `DRIFT_CALM`, `DRIFT_WIND`, `DRIFT_WRAP`, `STRENGTH_EASE` (:36-41); `Frame.windX/windZ/strength/driftX/
  driftZ` (:48); fields `lastNanos`, `driftX`, `driftZ`, `strength` (:61-62); `advance` :403, `drift` :413; noise
  **layer 0** (wave noise: `createNoise` :454 / `noise` :490, `SLOPE_RANGE` :22) and **layer 2** (ripple cells: `CELL_SEED`,
  `CELLS`, `CELL_RADIUS` :29-31, `cells` :555) — keep layer 1 (caustics) and `aberrate` :616; the `texContact` upload
  :247-268 (contact foam) and `WaterMap.CONTACT_SIZE`.
- `WaterMap.java`: `findContacts` :739, `spread`/`spreadCell`/`spreadLine` :784-932 (contact field), `terrainFalls`,
  `dirAt`, `waterAt`, `edge`, `edgeHeight`, `markFall`, `pullToLines` :932-1136 (fall frame); in `buildTexture` :1380 the
  page 1 g (wave energy) and a (turbulence) channels and page 2 g (open-sea share), b/a (fall frame) (:80-82);
  `SHORE_FIELD_REACH` :37 comment ("the waves are all within it" — the shore distance itself stays for depth tint).
- `WaterBodies.java`: `energies` :744, `ridges` :640, `openSea` :735, `OPEN_SEA_RIDGE` :41, `SHAPED_MIDDLE` :61,
  `STILL` :63 (turbulence threshold), `turbulence`/`energy` arrays (fields near :68-130, verify names), and `drop` :714
  if only energy uses it.
- `Weather.java`: `getWaterWind` :435 (only consumer is the drift) — remove or keep for rain rings' wind (they don't use
  it).
- `RuneLightPlugin.java`: the `waterWind` computation and `water.advance(...)` call feeding `drawSurface`
  (:≈3287; verify), the wave texture units passed to `setSurfaceProgram`.
- `config/WaterDebug.java`: `WAVES` entry (and `waterDebug == 2` branch).

**GLSL**
- `water_frag.glsl`: uniforms `waterWind` :17, `waterDrift` :18, `waterBake` :29, `waterWavesA` :30, `waterWavesB` :31,
  `waterWaveBlend` :32, `waterFocus` :33; output `WaveOut` :43; functions `crestWaves` :133, `ripples` :163,
  `shoreFoam` :231, `waterFoamStill` :269, `waterFoamHeight` :276, `contactFoam` :285, `fallFrame` :296, `fallLaid` :302,
  `fallFoamHeight` :308, `waterWaveField` :313, `hardFoam` :386; in `main`: the bake branch :396-401, the wave fetch
  and blend :426-432, foam from waves (`foam = wave.z`, :432; keep `RAIN_FOAM` from rings :449 if foam stays as a
  rings-only term), the foam coverage packing (:496-498 "the shore waves' rim and core coverage"), `waterDebug == 2`
  branch :475-479; uniform `waterContact` :16 (contact foam).
- `water_vert.glsl`: `waterBake` uniform :15 and the bake-space position path in `main` :23+ (verify lines).
- `water_common.glsl`: `waveNoise` :240, `waveNoiseD` :249, `seaOctaveGrad` :263, `waterFoamRim` :201, `waterFoamCells`
  :207, `waterFoamEdges` :224 and their constants; `waterNoise` layer 0 usage; `waterFlowPhases` :108 only if the flow
  map goes too (it also feeds caustics — keep for now).
- `water.glsl`: foam use in `waterOver` (:501-598, the "two tones" foam from the packed coverage), `waterGlitterNormal`
  :440 and `waterNormalAt` :445 simplify to the up normal (+ rain rings); `waterShoreOf` :135 (shore waves' rim/core).
- `rain_vert.glsl:11` includes `water_common.glsl` for `waterSurfaceAt` — unaffected.

**Settings:** none are wave-specific (nothing to remove from `RuneLightConfig`).

---

## L. Ranking, settings, summary

### The five heaviest or messiest things
1. **The fog stack** — the heaviest pass (volumetric fog 1.6 ms + filter share + upsample) and the messiest quantity:
   11 places set fog amount/colour, two renderers (volumetric + 117 HD distance fog), two colours, two caps (§I.1).
2. **The skylight stack** — ~3,000 Java lines across 9 classes + `skylight_ao.glsl`, a 15 MB 3D texture uploaded on the
   client thread (B3's stall), three sky-visibility producers plus the weather occlusion map's full extra scene draw per
   zone change (untimed), and 10 ambient terms meeting in `composite_frag.glsl:427-447` (§I.2, §I.3).
3. **The water** — 5,800 Java + 1,570 GLSL lines; a CPU scene-load build on the load path (untimed), the 20 Hz wave bake
   with 24 MB of maps, two composite passes and a surface pass with 64-bit packing; the wave unit (§K) is a third of it.
4. **The weather driver** — `Weather` 1,221 + `WeatherField` 763 + 10 helpers; a 36-channel field eased and blurred per
   cell when 30 channels are only read at the player; two ground simulations; WEATHER and OCCLUSION GPU passes absent
   from the B1 ledger; one dead setting.
5. **Two sky-light sources** — the analytic `skyLightAt`+`overcastSkyBoost` (≤ 200×) and the `SkyCache` irradiance,
   mixed per pixel by an eased weight; cheap (0.05–0.36 ms) but two mechanisms for one light, and the cache's capture
   is one of the four swap-frame jobs.

### Settings
- `RuneLightConfig` has **67** `keyName` entries as read (HANDOVER says 69 — recount when a segment touches config).
  **30** of them belong to this area: sky 11 (`proceduralSky`, `dayCycleMinutes`, `fixedHour`, `clouds`,
  `clearWeatherClouds`, `cloudSpeed`, `starBrightness`, `nebulaStrength`, `aurora`, `cloudShadows`, `skyHorizonAtEdge`),
  weather 5 (`weatherMode`, `weatherIntensity`, `weatherBuildup`, `weatherLightning`, `weatherSounds`), water 3
  (`water`, `waterReflections`, `waterCaustics`), fog 3 (`volumetricFog`, `fogQuality`, `volumetricFogDensity`),
  environment 5 (`areaEnvironments`, `windSway`, `windSwayStrength`, `windSwayShadows`, `windSwayFps`), dev 3
  (`waterDebugView`, `strikeLightning`, `weatherFieldMap` — dead).
- Proposed after the merges (rule 5, user to decide): **12** — `proceduralSky`, `dayCycleMinutes` (0 = fixed at
  `fixedHour`, keep both = 2), `clouds`, `cloudShadows`, `nightSky` (stars+nebula), `aurora`, `weatherMode`,
  `weatherIntensity`, `weatherSounds`, `water`, `volumetricFog` (+ `volumetricFogDensity` = 2), `areaEnvironments`,
  `windSway`; dev keeps `strikeLightning`. Removed: `fogQuality`, `cloudSpeed`, `skyHorizonAtEdge`,
  `clearWeatherClouds`, `weatherBuildup`, `weatherLightning`, `waterReflections`, `waterCaustics`, `windSwayStrength`,
  `windSwayShadows`, `windSwayFps`, `weatherFieldMap`, `waterDebugView` (→ `debugView`).

### Unverified / to measure before cutting
- WEATHER, OCCLUSION, water-at-a-sea-view, WaterMap build, FogGround upload, sky-grid upload per frame: none in B1.
- Whether `SKY_FRAME_CONSTANTS` is defined for the rain/snow/sky-cache programs (per-pixel palette walk otherwise).
- `RuneLightPlugin.java` line numbers (file changed during this read); `WaterSurface.setProgram`/`upload` signatures
  and `WaterBodies` field names in §K marked "verify".
