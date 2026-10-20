# RuneLight

Lighting-focused GPU renderer for RuneLite. OpenGL 3.3/4.1 core so it runs on macOS, Windows and Linux.

Currently a faithful fork of RuneLite's GPU renderer (1.13.2) used as the baseline.

## Run

```
./gradlew run
```

or run `RuneLightPluginTest` (src/test) from IntelliJ with the project root as working directory.
Turn off the built-in GPU plugin; RuneLight refuses to start while it is enabled.

Logging in with a Jagex account: run the RuneLite launcher once with `--insecure-write-credentials`.

## Shader hot reload

In developer mode, shaders are read straight from `src/main/resources/com/midonarhel/runelight/` and recompiled
whenever a `.glsl` file is saved. A broken shader keeps the last working one running and reports the error,
with the original file and line, in game chat and the log.

Override the directory with `-Drunelight.shaderDir=/path`.

## Development settings

Plugin config, Development section:

- Show GPU timings: GPU ms for the scene and for UI + present, measured with non-blocking timestamp queries.
- Check GL errors: glGetError after each draw step. Slow, keep off when measuring.
- Debug view: show the depth or normal buffer instead of the final image.

## Lights

Point light placements come from 117 HD's light definitions, converted with `tools/import_117hd_lights.py`
(see the script for usage). Rerun it against a newer 117 HD checkout to pick up their additions.

## Weather

Plugin config, Weather section: rain or snow with an intensity, wet ground / snow cover, rain splashes and a
gradual buildup. Shelter comes from a top-down depth map of all static scenery around the player, drawn with every
roof (also roofs hidden by the game or a plugin), redrawn only after the player walks ~24 tiles or the scenery
changes. Tuning constants are `#define`s at the top of `rain_vert.glsl`, `snow_vert.glsl` and
`weather_ground.glsl`; counts and timings are at the top of `Weather.java`. With varying intensity the
rain or snow swells, eases off and gusts around the set intensity (`WeatherVariation.java`). The cloud cover follows
it slowly and grays the sky and softens the sun and moon everywhere through `weatherOvercast` in `sky.glsl` (its
`OVERCAST_*` defines). Drops, flakes and splashes pick up nearby point lights through the light clusters
(`weather_common.glsl`). Wet ground and snow cover are part of the surfaces: the half resolution AO pass works out
how much of each a pixel gets (`weather_ground_ao.glsl`: the region's ground times how open to the sky it is) into
the shadow target's spare channels, and the composite changes the albedo and normal before lighting
(`weather_ground.glsl`), so snow takes the AO, shadows, GI and point lights like any other surface. Snow lies in
patches: a fixed world-space noise thresholded against the depth, so patches grow from their cores as it deepens and
melt back the same way, upward faces first and sheltered spots last.

Time (`GameClock.java`) is a function of the real clock from a fixed epoch: with the default 24 minute day one game
hour per real minute, seasons of "Days per season" game days. The time of day, the season, the forecast and the
wind's wander are the same whenever and wherever the client runs. Each region has a temperature (`Climate.java`:
the biome's per-season mean and day/night swing from `weather_climate.json`'s `temperature`, damped by cloud, cooled
by whatever falls, the type's own `temperature` offset, and a slow wander; arctic and mountain regions never thaw).
Rain and snow follow it: a forecast rain falls as snow in a frost and as sleet around zero. Snow depth and wet ground
run as an hourly simulation per region from a couple of seasons back, so they are the same for everyone at the same
moment: snow piles up while it snows and only melts above zero (faster when warmer, in rain or sun), wet ground dries
with warmth and sun. The overlay shows the temperature, snow depth and wetness.

Automatic weather (the default mode) picks a new weather per biome every third of a game day (8 game hours, 4
minutes at the default day length) from about 25 types: clear, sunny, harsh sun, hazy, high thin clouds, partly
cloudy, overcast, drizzle, rain, spring showers, summer rain, autumn rain, thunderstorm, tropical downpour, mist,
fog, freezing fog, sleet, light snow, snowfall, heavy snow, blizzard, sandstorm, ash fall and gale. Each type is a
row of `weather_types.json` (cloud cover and look, rain/snow amounts and drop/flake style, showers, wind and
gustiness, fog and its tint, sun strength, haze, warmth and sky saturation, lightning, buildup rates, frost;
fields and defaults in `WeatherType.java`) that `Weather.java` eases toward, so slot changes and walking into
another biome show its weather as it is. The odds per biome and season are in `weather_climate.json`; seasons
(spring, summer, autumn, winter) last "Days per season" game days each and blend into the next over the last
quarter. Consecutive slots form a Markov chain whose transition weights come from how alike two types look
(`WeatherForecast.java`), so clear skies cloud over before it storms. In the dev client both JSON files are read
from `src/main/resources` and reloaded on save. The light changes go through `weatherLight` in `sky.glsl`
(`HAZE_*`, `WARM_TINT`, `HARSH_SHADOW`). Storms strike lightning (`Lightning.java`): the flash goes through
`weatherOvercast.z` (`LIGHTNING_*` in `sky.glsl`), the bolt and cloud glow are in `lightning.glsl`. "Force
weather" holds any one type for testing. With GPU timings shown, the overlay lists the weather, season, wind,
region and the next weather.

The wind (`Wind.java`, `wind.glsl`) eases toward the weather's mean and gustiness, wanders slowly in direction, and
sends gust fronts across the world with it, so a gust visibly passes over: drops slant harder and flakes swirl
where one is. It drives the rain's slant, the snow's drift, the clouds and the water. Shaders that sway with it
include `wind.glsl` (uniforms `windDir`, `windStrength`, `windGust`, `windTime`, `windFront`, `windGustiness`,
and `windGustAt(worldXZ)`); `Wind.apply` sets them, on the scene and shadow programs every frame.

Weather by region (`WeatherField.java`): every chunk of the loaded scene, expanded map loading included, takes
the forecast for its own region's biome (as the temperature lets it fall), eases toward it when it changes and takes
its region's ground from the climate. The
chunks are blurred across region borders (`BLUR`) and sampled by world position as differences from the weather
at the player, so where you stand nothing changes and elsewhere the clouds close up, the fog thickens and the
ground gets wet or white by region. The rain and snow particles fill everything the camera sees out to the draw
distance wherever it falls, in layers of world-anchored boxes that double in size outward, each owning a band of
distance from the camera with fewer, larger particles (`LAYER_*` in `Weather.java`), so the weather thins gradually
into the distance and a change shows over the whole view at once. "Show weather map" puts a small map of the field
in the overlay (green clear, gray cloud, blue rain, white snow, pale fog, player in red).

## Areas

Which area the camera is in, whether it is under the open sky or underground, and each area's light tint, fog and
sky haze come from `environments.json`, built by `tools/import_environments.py` from 117 HD's areas and
environments with 3D Weather's biome regions as fallback (see the script for usage and what it leaves out).
Rerun it against newer checkouts to pick up their additions. The biome looks (desert, swamp, snow, ...) are set in
the script's `BIOMES` table.

Plugin config, Hide other areas section: while the player is in one of 117 HD's areas that hide the rest of the map
(caves, instances, cutscene spots; the mainland's surface too), `AreaHiding.java` takes every other tile out of the
scene as it loads, so no zone, shadow, water body, light or GI probe ever sees it. Walking into another area loads the
scene again. The areas come from `area_hiding.json`, built by `tools/import_117hd_areas.py`.

## Clouds

Plugin config, Clouds section: one slab of clouds over the procedural sky, marched in 6 (looking up) to 16 (at
the horizon) steps per sky pixel in the composite (nothing is spent where scenery covers the sky), with optional
cloud shadows on the ground. Rain and snow close the sky up through `Weather.cloudCover()` and, by region, the
weather field. Tuning constants are `#define`s at the top of `clouds.glsl`; the noise texture and wind are in
`Clouds.java`. A detail noise warps the shapes a little and gently erodes their edges (Cloud roughness), so they
are soft but not perfectly round; some stretches of sky turn wispy. The sun and moon show
through thin cloud and hide behind thick cloud, with silver linings, and the stars (`atmosphere.glsl`) are faint
dots that fade toward the horizon, in haze and behind clouds.

The sky cache (`SkyCache.java`, `sky_cache_frag.glsl`) captures the sky and clouds into a small lat-long texture
every few in-game minutes (more often at sunrise and sunset, and at once when the weather or the clouds overhead
change) and blends each capture in over the game clock. The light from the sky on the ground comes from it
(`sky_irradiance_frag.glsl`), so it follows the cloud cover and the sky's colors; optionally the sky itself is drawn
from it, which is cheaper than marching the clouds for every sky pixel but softer.

## Fog

Volumetric fog (`VolumetricFog.java`, `fog_frag.glsl`) is the configured density times the weather's fog times the
region's climate (`FogClimate.java`): mist at dawn that burns off by late morning and lies low, more of it and again
at dusk and night in damp regions (swamps, forests, the tropics), almost none in deserts, which get a faint midday
haze instead. Patches drift and change shape, and larger banks of fog drift over the ground (Fog banks).

## Water

Plugin config, Water section. When a scene loads, `WaterMap.java` finds the water tiles (the game's water
textures, as in 117 HD), groups them into bodies and classifies each (`WaterBodies.java`). Which way the water flows
comes from a world flow map (`WaterFlow.java`, resource `water_flow.dat`) computed offline from the world map
image: rivers run from their sources to the sea or a lake, straits and ponds are still. Where the map knows nothing
(new areas, water drawn in colors other than blue on the map) the scene is analysed on its own as a fallback. The sea
pushes toward its shores. How fast it runs is shaped by the scene's channel (`WaterBodies.shapeFlow`): fastest in
the middle and slow at the banks, faster through narrows and over drops (a smaller cross-section, the bed being
shallower near the shore), slower where a river widens into a lake. Swamps and caves (from the area, `EnvironmentManager.waterMurk`) make water murky. Water
terrain is uploaded as a lowered bed, deeper away from the shore. The surface is geometry (`WaterSurface.java`,
`water_vert.glsl`, `water_frag.glsl`): a grid over the water tiles and the land around them, built at scene load,
fine near the focal point and moved there by a Gerstner swell and flow-carried chop ("Moving surface"), drawn after
the MSAA resolve into its own target (distance, normal, type, foam). The composite shades it in `water.glsl` after
117 HD's water: the bed refracted and lit by 117 HD's caustics (carried by the flow), fading into the type's depth
color over a short depth (by the shore distance, smooth across tiles), the surface's own color and the sky and its
clouds reflected in soft round ripple cells that the current carries along, soft highlights and a mild glitter path,
lamp glints in their rooms, a few taps of screen-space reflection. Objects in the water are covered by the real surface, so the waterline
moves with the waves. Per-type looks are the tables in `water_common.glsl`, the rest are `#define`s in the three
water shaders. "Show water map" tints water by type and shows its flow as moving stripes.

The flow map: `tools/water/build_flow.py <cache dir> src/main/resources/com/midonarhel/runelight/water_flow.dat`
downloads the map image (Explv's osrs_map_tiles, rendered from the game cache by RuneLite's map dumper) into the
cache dir, finds the water, and computes the flow; `tools/water/annotations.json` fixes rivers the automatic pass
gets wrong (sources, outlets, still canals), and `tools/water/crop.py` renders map crops with a tile grid and the
flow to check them. Needs numpy, scipy, scikit-image and Pillow.

## Global illumination

Plugin config, Global illumination section. When a region loads, the static scene's opaque triangles (captured as
the zones upload, `GiCapture.java`) are voxelized into half-tile cubes on a worker thread (`GiVoxels.java`,
`GiGrid.java`; per zone, so zones reused by the next load keep theirs). A probe grid covers the base scene, one
column per tile with three layers (`GiProbes.java`); each probe traces 64 rays once and keeps what they hit. Probes
in buildings (BuildingDetector rooms, below the roof line) are indoor and light only indoor pixels, so walls don't
leak, while their rays still see out through doors. Relighting (`GiRelight.java`) takes the sky from the sky cache
(`gi_sky_frag.glsl`, read back without stalling) plus the sun or moon on the hit voxels and the last light for
further bounces, a few tens of milliseconds on the worker whenever the sky or the sun changes enough; new light
blends in over a few seconds. The AO pass looks the probes up at half resolution (`gi.glsl`) and the composite
uses the result instead of its plain ambient and sky light, keeping a floor of the latter (Shade floor). Debug view
"Global illumination" shows the indirect light alone. Outside the base scene and underground the plain ambient
light stays.

## Skylight and light occlusion

Plugin config, Skylight and light occlusion section. Lighting model Skylight (default) replaces the probes with a
top-down depth map of the scenery around the player (`SkyOcclusion.java`, the weather's occlusion map kept while
needed): the AO pass reads it in a ring of directions around each pixel and turns the horizon's slope into how much
sky the pixel sees (`skylight_ao.glsl`, in the half resolution shadow target's alpha), and the composite scales the
ambient and sky light by it (`light_occlusion.glsl`). "Skylight + GI bounce" adds what the probes find beyond it.
Debug view "Skylight" shows it.

Walls (`WallMap.java`) come from the collision map's line-of-sight edges plus the diagonal wall objects; rooms split
along them (`BuildingDetector.split`), and every light that stays put gets a quarter-tile visibility grid cast
against them on a worker thread (`LightOcclusion.java`), recast when a door opens or closes, packed after the room
map in the light buffer. Lights under a roof don't light surfaces open to the sky above them.
