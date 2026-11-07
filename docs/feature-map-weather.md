# Weather, sky and atmosphere: current behavior map

Code audit of the working checkout, 2026-11-07. This describes executable paths and current resource data, not historical design proposals. It does **not** establish visual correctness, measured GPU cost, or Windows/Mac parity. World distances below use 128 world units per tile. Times are real seconds unless explicitly called game time.

## How the parts connect

`FrameState` selects environment/time, then `WeatherFrame` updates the climate and spatial weather field. That field drives clouds, precipitation, surface wetness/snow, atmospheric fog, wind and rain audio. `SkyFrame` updates the visible sky, night sky and cached sky lighting. The composite combines surface weather with the normal lighting pipeline; fog is another pass. These are overlapping systems, not a single weather shader.

Sources: [FrameState.java](../src/main/java/com/midonarhel/lumina/frame/FrameState.java), [WeatherFrame.java](../src/main/java/com/midonarhel/lumina/weather/WeatherFrame.java), [SkyFrame.java](../src/main/java/com/midonarhel/lumina/sky/SkyFrame.java).

The weather focus is normally the player's position; camera coordinates are a fallback. Area environment selection uses the camera focal point. This distinction matters with a detached camera or at an area boundary: “the biome I see” and “the rain/audio at my character” need not be identical.

## Settings you can actually change

Defaults are declarations in [LuminaConfig.java](../src/main/java/com/midonarhel/lumina/LuminaConfig.java); saved user values may differ. Unspecified minimums use the config annotation's default rather than a separately declared minimum.

| Setting/key | Default and declared range | Actual effect / interaction |
|---|---|---|
| `proceduralSky` | On | Enables the procedural sky path. Existing game skyboxes also have their own rendering path. |
| `dayLength` | 24 minutes; Fixed / 24 minutes / 1 hour / Real world | Controls visible day progression, not the weather clock. Real world uses local timezone, including DST. |
| `fixedHour` | 12; 0–23 | Holds visible solar time when Fixed is selected. Weather, climate and mist continue. |
| `clouds` | On | Cloud visibility toggle. Weather still has cloud-cover/overcast state that affects other calculations. |
| `clearWeatherClouds` (`cloudCoverage()`) | 8%; maximum 100% | Clear-sky base coverage; final coverage is `base + (1-base)*weatherCover`. It is not an extra fixed percentage added to every weather. |
| `cloudShadows` | On | Separate cloud-shadow flag supplied to shaders. |
| `starBrightness` | 100%; maximum 200% | Night-sky star strength. |
| `nebulaStrength` | 100%; maximum 200% | Night-sky nebula strength. |
| `aurora` | On | Enables normal regional/night scheduling; a developer hold can override it. |
| `skyHorizonAtEdge` | On | Horizon positioning relative to the drawn world. Its current tooltip incorrectly describes weather mode. |
| `weatherMode` | Automatic | Automatic forecast, Off/Clear, or a forced catalog type. Off resolves to clear weather; it is not a master switch for all atmospheric effects. |
| `weatherIntensity` | 60%; 1–100% | Precipitation is scaled by `(setting/100)/0.6`, in both automatic and forced weather. 60% means the catalog amount; 100% means 1.667 times it before other variation/caps. There is no zero on this slider. |
| `weatherBuildup` | On | Controls temporal transitions and gradual ground changes. Off does not remove spatial smoothing between weather cells. |
| `weatherLightning` | On | Permits scheduled storm flashes. |
| `weatherSounds` | 50%; maximum 100% | Multiplied by the game's sound-effect volume. Its current tooltip incorrectly describes water. |
| `volumetricFog` | On | Enables the volume pass when logged in with a local player and a prepared ground field. Does not control all distance fog or sky haze. |
| `fogQuality` | High | Low/Medium/High use 6/8/12 main raymarch steps. |
| `volumetricFogDensity` | 15; maximum 15 | Base extinction = setting × `0.00001` per world unit, multiplied by weather and area factors. This is not a documented percentage control. |
| `areaEnvironments` | On | Uses the environment/area definitions for biome and atmosphere selection. Turning it off changes weather-region/environment context as well as area colors/fog. |
| `windSway` | On | Scene foliage/object sway toggle; weather/cloud movement has its own wind usage. |
| `strikeLightning` | Off, developer action | Requests a test strike; separate from waiting for a forecast storm. |
| `weatherFieldMap` | Off, developer overlay | Shows the spatial weather field for inspection. |

## Two clocks, several kinds of transition

[GameClock.java](../src/main/java/com/midonarhel/lumina/sky/GameClock.java) derives time from absolute UTC relative to 2024-01-01. Weather does not start from clear whenever the plugin restarts. This also makes the same time/place reproducible across clients rather than maintaining a separate saved forecast per login.

| System | Schedule / smoothing | Consequence |
|---|---|---|
| Visible sun/day | Selected day length; Fixed holds an hour | Can be disconnected from the weather's time of day. |
| Weather clock | Always 24 real minutes per game day | Changing visible day speed does not change forecast or seasons. |
| Forecast | 3 slots/day, 8 game hours/slot = 8 real minutes | A slot is a chance to change type, not a promise of different weather. |
| Season | 7 weather days = 2 h 48 min real time | Four seasons take 11 h 12 min; this is a code constant, not a current UI slider. |
| Forecast seasonal odds | Blend during final quarter of season | Seasonal probability changes are smoothed. |
| Field update | Approximately 10 Hz; elapsed step capped at 0.25 s | Local transition state does not instantly catch up after a long frame stall; absolute climate time still advances. |
| New target weather | 40-second smoothstep, followed by channel easing | “Build up over a minute” is an approximation, not one uniform timer. |
| Channel easing | Clouds/fog typically 15 s, precipitation 8 s, storm 10 s | Different visible elements can lead or lag each other. Rain is gated until clouds are roughly 80% built, fully allowed around 97%. |
| Automatic ground display | Usually 20-second easing toward simulated history | Arrival can show preexisting snow/wet ground; it need not begin accumulating from zero. |

Sources: [DayLength.java](../src/main/java/com/midonarhel/lumina/config/DayLength.java), [WeatherForecast.java](../src/main/java/com/midonarhel/lumina/weather/WeatherForecast.java), [Climate.java](../src/main/java/com/midonarhel/lumina/weather/Climate.java), [WeatherField.java](../src/main/java/com/midonarhel/lumina/weather/WeatherField.java).

The forecast uses deterministic, biome-specific weighted transitions. Similar weather is favored; ordinary catalog entries have persistence 3, storms/sandstorms/ash less. Its bounded reconstruction restarts the forecast chain every 12 slots (four game days). This is a computational boundary, not evidence of a visible glitch.

## Catalog: what each weather really requests

These are raw catalog inputs, **before** intensity, climate conversion, local showers, field blur and transitions. Fog is a multiplier input, not meters of visibility. Positive darkness darkens clouds; negative values brighten them. “Snow channel” is also the renderer for dust/ash, so a variable named `snow` does not always mean white accumulation.

| Type | Cover | Falling material / amount | Fog | Haze | Wind offset | Other |
|---|---:|---|---:|---:|---:|---|
| Clear | 0 | None | 0.15 | 0 | 0 | Default base values |
| Overcast | 0.8 | None | 0.35 | 0.1 | 0 | — |
| Rain | 0.85 | Rain 0.55 | 0.9 | 0 | +0.1 | — |
| Storm | 1 | Rain 1 | 1.1 | 0 | +0.35 | Darkness 0.4; lightning 1; persistence 1.2 |
| Snow | 0.8 | Snow 0.5 | 0.8 | 0 | 0 | Actual white ground accumulation |
| Fog | 0.45 | None | 2.2 | 0.5 | −0.2 | — |
| Sandstorm | 0.25 | Sand 0.45 | 1.6 | 0.8 | +0.4 | Darkness −0.5; persistence 1.6; does not wet or whiten ground |
| Ash fall | 0.75 | Ash 0.35 | 1 | 0.65 | 0 | Darkness 0.6; persistence 1.6; does not wet or whiten ground |
| Hazy | 0.15 | None | 0.35 | 0.4 | −0.1 | Wispy cloud amount 0.6; darkness −0.4 |

Sources: [weather_types.json](../src/main/resources/com/midonarhel/lumina/weather/weather_types.json), [WeatherType.java](../src/main/java/com/midonarhel/lumina/weather/WeatherType.java), [weather_regions.json](../src/main/resources/com/midonarhel/lumina/weather/weather_regions.json).

Several visual parameters are derived rather than independent editable knobs: haze reduces sun by `1 - 0.45*haze`; sky saturation shifts by `-0.5*haze - 0.1*cover`; fog/haze also derive distance-fog scale, area-fog weight and morning-mist allowance. Wind is `max(0, biomeWind + typeOffset)`; despite a “0..1” description, that calculation does not clamp its upper end. Sand and ash reuse flakes but override size, speed, drift and tint, and suppress snow/wet accumulation. Editing one JSON field can therefore change multiple effects.

## Local weather and region boundaries

The active field has 33×33 cells, each 16 tiles wide. Cell state is keyed to world location, so recentering preserves overlapping cells. Scene/base/plane/area-mode changes can rebuild layout. Instance locations are mapped to known world coordinates when available, otherwise use the focus biome.

Weather channels are spatially blurred (Gaussian radius 5 cells, sigma 2), ground uses a radius-2 weighted kernel, and sky weather a wider radius-6/sigma-3 kernel. Consequently the system deliberately produces broad boundary mixtures rather than weather changing at a sharp region line. Disabling buildup bypasses temporal easing, not these spatial kernels.

First-use cells can snap to their current target. Forced rain/snow without buildup can immediately initialize fully wet/white ground. This behavior differs from automatic mode's reconstructed ground history. Weather texture channels are stored in 8-bit fields; the ground exposure result used by the composite is a separate half-resolution floating-point buffer.

Sources: [WeatherField.java](../src/main/java/com/midonarhel/lumina/weather/WeatherField.java), [Weather.java](../src/main/java/com/midonarhel/lumina/weather/Weather.java).

## Temperature, snow and wetness

Automatic mode simulates a regional history in hourly weather-clock steps. It reconstructs two seasons of history, bounded to 4–30 game days, rather than storing actual footprints of every past shower. Temperature combines seasonal means, a daily swing (coldest near 03:00, warmest near 15:00), cloud damping, precipitation cooling and slow variation. Frozen regions enforce a cold ceiling and at least 30 cm snow.

Precipitation type is temperature-dependent: the snow-share curve runs from all snow at −0.5°C to all rain at 2.5°C, but `withTemperature` selects the **whole rain or snow weather type** at share 0.5. It is not a continuously mixed sleet renderer. Biomes marked never-snow stay rainy. A freezing storm can become the Snow catalog entry, changing more than just the particle texture.

Ground rules include 5 cm snow per game hour per unit snowfall, a 60 cm cap, and full visual cover at 10 cm. Above freezing, snow melts with temperature, rain and sunlight; excess deep snow also settles. Wetness builds from rain and dries faster with sun/warmer air. **Snowmelt intentionally does not add wetness.** Manual-mode fallback accumulation uses separate timescales: approximately 25 s wet buildup, 240 s snow buildup, 120 s drying and 900 s melting, rather than the automatic historical climate model.

The automatic historical simulation uses catalog precipitation and shower share, not the UI intensity multiplier. The visible local field uses UI-scaled precipitation and gates growth toward historical cover when rain/snow is locally present. Therefore intensity is not a single physically consistent control over both visible snowfall and long-term snow depth.

Sources: [Climate.java](../src/main/java/com/midonarhel/lumina/weather/Climate.java), [WeatherClimate.java](../src/main/java/com/midonarhel/lumina/weather/WeatherClimate.java), [WeatherField.java](../src/main/java/com/midonarhel/lumina/weather/WeatherField.java).

## What roofs actually shelter

There are several distinct meanings of “indoors”:

| Consumer | Source of shelter | Behavior |
|---|---|---|
| Falling rain/snow and ground exposure | Dedicated top-down weather depth map | Includes roofs even when the game hides them. Uses opaque scenery with foliage-opening handling; this is not restricted to the point-light wall/roof blocker set. |
| Ground wet/snow mask | Nine weighted comparisons against that map | Softens shelter edges over roughly 1.5 tiles; 40-unit depth bias and 12-unit normal offset avoid self-covering. Outside map bounds counts as exposed. |
| Rain sound | Sky-grid upward visibility at the player's head, falling back to room membership | Smooth muffling: fully indoors at ≤0.15 visibility, fully outside at ≥0.6. Can partially muffle under a canopy/gateway. |
| Volumetric fog | Room/cutaway information | Indoor density multiplier 0.03, or 0.25 for the open/cutaway indoor case. This is neither the particle collision map nor the point-light map. |
| Global weather rendering | Area open-sky flag and login state | Ordinary rooms in an outdoor area still have outside weather; this flag is not simply “a roof over the player.” |

The weather map is 1024², covers a 64-tile radius and a 4096-unit vertical range. It is refreshed when needed, including substantial recentering (24 tiles), plane changes, large height movement and scene invalidation. It renders levels 0–3 with the hidden-roof set deliberately empty. Wet/snow surfaces can keep it needed after particles stop.

Ground wet/snow state is multiplied by exposure and written during the AO pass, then applied before lighting in the composite. Snow takes precedence over puddles; puddle water is completely suppressed once normalized snow cover reaches 0.125. Snow changes both material color and normal, and noise breaks its boundaries into patches. Puddles use surface/room-map information, darkening, flattened normals and reflections. Emissive, water-bed and transparent material eligibility suppresses these effects; they are not a universal white/blue overlay on every mesh.

Sources: [Weather.java](../src/main/java/com/midonarhel/lumina/weather/Weather.java), [WeatherGround.java](../src/main/java/com/midonarhel/lumina/weather/WeatherGround.java), [weather_ground_ao.glsl](../src/main/resources/com/midonarhel/lumina/weather_ground_ao.glsl), [weather_ground.glsl](../src/main/resources/com/midonarhel/lumina/weather_ground.glsl), [WeatherAudio.java](../src/main/java/com/midonarhel/lumina/weather/WeatherAudio.java), [VolumetricFog.java](../src/main/java/com/midonarhel/lumina/fog/VolumetricFog.java).

## Particles, wind and sound

Rain/snow positions are procedural and world-anchored, rather than individually persistent simulated particles. The renderer uses distance layers: nearest-layer budgets are 24,000 rain instances and 12,000 snow instances at full amount, with up to six layers and separate impact rendering (maximum 4,400). These are configured drawing budgets, **not measured simultaneous visible counts or GPU costs**. Layers change size/coverage with distance, and transitions hide boundaries.

Wind displacement is integrated to keep gust changes from teleporting particles. Rain/snow falling and drift coordinates wrap on a 600-second interval. Cloud drift also uses the shared `Wind` driver. Particle drift has a base plus wind/intensity scaling; catalog sand/ash change flake motion independently. Turning off scene sway does not mean the whole world has zero wind.

Rain audio samples local rain and rings at 1,200/2,800/4,800 world units in eight directions. Rain can therefore be audible while approaching a wet region, before particles reach the player. Loudness is multiplied by weather volume and game sound-effect volume, then sheltered/muffled. Thunder events are scheduled separately; the lightning/rumble visual state can proceed even when the user's volume is zero.

Sources: [Weather.java](../src/main/java/com/midonarhel/lumina/weather/Weather.java), [Wind.java](../src/main/java/com/midonarhel/lumina/weather/Wind.java), [Clouds.java](../src/main/java/com/midonarhel/lumina/sky/Clouds.java), [WeatherFrame.java](../src/main/java/com/midonarhel/lumina/weather/WeatherFrame.java), [WeatherAudio.java](../src/main/java/com/midonarhel/lumina/weather/WeatherAudio.java).

## Fog and haze are several effects

The volume pass works at half resolution, then filters/upsamples. Its base extinction is `configDensity * 1e-5`; the frame multiplies that by weather fog and an area-fog factor derived from 117 HD's fog depth (the area-depth ratio is capped at 2). Fog height is normally 3 tiles times the weather height multiplier, minimum 16 units. It falls off vertically, fades near the camera over the first 7 tiles and at its outer edge over 7 tiles; coverage radius is at most 40 tiles or the configured draw distance. Ground-height data is prepared by a worker and blended when changed.

Morning/evening mist is another weather calculation, not simply the Fog catalog type. Humidity drives dawn mist, with smaller dusk/night contributions; dry climates can gain midday haze. Dawn builds around 03:00–06:00 and clears by about 10:30 **weather-clock time**. Weather turbulence/cloud state modifies the allowance. The fog multiplier uses a saturation knee/cap to prevent unbounded addition.

Distance fog, area environment fog, sky haze/desaturation and the volumetric pass have distinct controls and consumers. Disabling volumetric fog does not imply a perfectly clear horizon; choosing Clear does not eliminate humid dawn mist. Cave/closed-sky behavior also has its own weather fog fallback.

Sources: [FogClimate.java](../src/main/java/com/midonarhel/lumina/weather/FogClimate.java), [VolumetricFog.java](../src/main/java/com/midonarhel/lumina/fog/VolumetricFog.java), [FrameState.java](../src/main/java/com/midonarhel/lumina/frame/FrameState.java), [SkyFrame.java](../src/main/java/com/midonarhel/lumina/sky/SkyFrame.java).

## Day, moon, cloud lighting and lightning

The sun follows a stylized altitude curve (`70*sin(phase*2π) + 12` degrees), not a geographic astronomy model. The moon is opposite. The directional caster changes from sun to moon when solar altitude falls below −3°. Shadow-casting elevation is clamped to 30–68° for sun and 30–58° for moon to prevent extremely long horizon shadows. The visible celestial body and effective shadow direction therefore intentionally differ near the horizon.

Direct strength fades across twilight and is reduced by overcast. At full overcast the multiplier retains 30% for rain-like cover or 42% for snowy cover. Moon strength is weaker than daytime strength. This confirms a code path for moon directional lighting; it does **not** prove that shadows are visibly correct in the live scene. See the lighting feature map for shadow-map scheduling, occlusion and indoor lighting.

Lightning is a temporary directional caster, not a point light placed in the world. Storm strength must exceed 0.6. Scheduling uses five-second real-time windows with 0.5 chance; a strike can wait up to a second for its shadow map before being dropped. Main flash rise/hold/fall are 0.05/0.4/0.4 seconds, with secondary flare and thunder/rumble modulation. Its direction is elevated 30–40°, at a nominal distance of 3,000–9,000 units. These conditions explain why enabling lightning or choosing a storm need not yield an immediate visible flash.

Sources: [DayCycle.java](../src/main/java/com/midonarhel/lumina/sky/DayCycle.java), [Lightning.java](../src/main/java/com/midonarhel/lumina/sky/Lightning.java).

## Night sky and cached sky lighting

Stars rotate once per visible day around a pole tilted 35° from vertical. Meteors are scheduled in 12-second real-time slots with 45% chance, lasting 0.7–1.2 seconds and beginning near the camera's view. Aurora is normally limited to TUNDRA/ARCTIC; 30% of nights have none and other nights have varying strength. Strength transitions over about six seconds and is multiplied by a fixed 0.396 visibility factor. Its night schedule uses visible game time, not the separate fixed-speed weather clock. Developer `auroraHold` and `DayCycle.heldHour` can override normal behavior and should be checked before judging settings.

Sky lighting is cached separately from drawing the sky. `SkyFrame` includes quantized weather/settings/time in cache inputs and retains old cached sky across loading, easing back toward analytic lighting if captures stop. Consequently a visual sky change, a cache update and the light falling on objects are related but not necessarily simultaneous operations.

Sources: [NightSky.java](../src/main/java/com/midonarhel/lumina/sky/NightSky.java), [SkyFrame.java](../src/main/java/com/midonarhel/lumina/sky/SkyFrame.java).

## Confirmed parameter issues versus things still to verify

**Confirmed from code:**

- Two config descriptions are attached to the wrong concepts (`skyHorizonAtEdge`, `weatherSounds`). The volume-fog implementation comment also mentions a 25% default, while current configuration declares 15 with maximum 15.
- The intensity tooltip emphasizes automatic weather, but the same `/0.6` scaling applies to forced precipitation. Ground history is not scaled by that slider in automatic mode.
- Fixed visual time does not freeze climate or morning mist. This may be intentional, but it must be visible in the product's explanation.
- “Off” and “Clear” resolve to clear weather rather than disabling the whole atmosphere stack. “Buildup off” removes temporal smoothing, not boundary blending.
- Indoor shelter, audio muffling, fog density and point-light blockers use different geometry/data and thresholds. One subsystem's success cannot verify the others.
- Snow/rain conversion swaps catalog types rather than continuously mixing the precipitation; sand/ash share the snow rendering channel but do not accumulate snow.

**Not established by this audit:** whether any transition actually flickers, whether fog is visually excessive on the user's saved settings, whether the 600-second coordinate wrap is visible, whether hidden-roof exposure is correct for every model, or which effect dominates measured frame time. Those need live scene comparison and scoped timing; none follows merely from the number of code paths or configured particle counts.

Useful player review cases are: one doorway with visible/hidden roof; forced rain then clear to inspect residual puddles; forced snow then thaw; fixed noon observed across weather-clock dawn; a dry region against a humid one; the same night with clear/overcast and sun/moon shadows enabled. These are proposed acceptance checks, **not tests already performed**.

## Simplification opportunities supported by this map

1. Expose the two clock meanings and correct the tooltips before changing rendering behavior. This makes current controls reviewable without silently changing appearance.
2. Give each weather parameter one documented owner, unit, range and consumer list. Derived fog, haze, mist, area fog and overcast currently overlap, so a control name alone is insufficient to predict the result.
3. Group the shelter contracts explicitly (falling precipitation, surface exposure, audio, fog, point lights). Reuse data only where the blocker rules really match; hidden roofs and foliage show why blindly sharing one map would change behavior.
4. Separate automatic climate-ground history from forced-weather demonstration behavior in the UI/documentation. Today they have different accumulation semantics under the same buildup/intensity controls.
5. Use the weather field overlay and scene-specific timing to decide what can be removed or merged. This audit does not justify deleting a pass or claiming a performance win.
