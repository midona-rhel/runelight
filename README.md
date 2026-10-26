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

Plugin config, Weather section: rain or snow with an intensity, puddles / snow cover, rain splashes and a
gradual buildup. Shelter comes from a top-down depth map of all static scenery around the player, drawn with every
roof (also roofs hidden by the game or a plugin), redrawn only after the player walks ~24 tiles or the scenery
changes. Rain also sounds (`WeatherAudio.java`, `AudioMixer.java`, "Weather sounds" volume): a loop that follows how hard
it rains around the player, and a clap of thunder with each lightning flash; both
are low-passed inside building rooms. The CC0 sounds and where they come from are in `sounds/CREDITS.txt`;
`tools/make_weather_sounds.py` makes them.
Tuning constants are `#define`s at the top of `rain_vert.glsl`, `snow_vert.glsl` and
`weather_ground.glsl`; counts and timings are at the top of `Weather.java`. With varying intensity the
rain or snow swells, eases off and gusts around the set intensity (`WeatherVariation.java`). The cloud cover follows
it slowly and grays the sky and softens the sun and moon everywhere through `weatherOvercast` in `sky.glsl` (its
`OVERCAST_*` defines). Drops, flakes and splashes pick up nearby point lights through the light clusters
(`weather_common.glsl`). Snow cover and rain puddles are part of the surfaces: the half resolution AO pass works out
how much cover a pixel gets (`weather_ground_ao.glsl`: the region's ground times how open to the sky it is) into
the shadow target's blue channel, and the composite changes the albedo and normal before
lighting (`weather_ground.glsl`), so they take the AO, shadows and point lights like any other surface. Snow is
blobs of world-space Perlin noise compared with a level that falls as the cover builds, so a blob starts at a peak and
grows outward and melts back the same way; its edge is crisp and two-tone like the water's foam (a shaded outer band
round the bright snow), antialiased to a pixel from the noise's gradient and the surface's footprint on screen. Snow
is big blobs over terrain and floors and a whitening on the tops of scenery; items and actors get none and scenery
with an action (`Interactables.java`) less, by two bits in each face's material (`SurfaceMaterials.java`). Puddles
are separate round blobs, at most one to each 2.25 tile cell of level terrain, most within a tile of a wall (the baked
wall distance of `RoomAmbient.java`, also for outdoor tiles); each is a small water of its own with a damp rim,
reflecting the sky (`waterSkyReflection`) and, with the water's reflections on, the world beside it
(`waterTraceReflection`); outside them the ground is not darkened.

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
scene as it loads, so no zone, shadow, water body, light or voxel ever sees it. Walking into another area loads the
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

## Skylight and light occlusion

Plugin config, Skylight and light occlusion section. The skylight scales the ambient and sky light by how much sky a
spot sees, ray traced through the scene's voxels. When a region loads, the static scene's opaque triangles (captured as
the zones upload, `VoxelCapture.java`) are voxelized into half-tile cubes on a worker thread (`Voxelizer.java`,
`VoxelGrid.java`, `VoxelBuilder.java`; per zone, so zones reused by the next load keep theirs), a voxel being filled
when enough surface passes through it and foliage when swaying plants make up most of that. The water surface joins as a
layer of voxels at its height (`VoxelWater.java`, from the water map) and the sunk bed under it is left out of the
capture. The voxels are built only while the skylight is wanted. The sky visibility is traced
(`SkyGrid.java`, `SkyGridMap.java`) on a worker thread, a few thousand rays a frame nearest the player first: at every
tile corner, three heights above each floor, how much sky is seen toward up and the four sides. Every tile keeps its own
four corner values, shared with its neighbors only where no wall stands between them, so nothing leaks through walls.
The AO pass reads a pixel's tile and weighs the directions by its normal (`skylight_ao.glsl`, in the half resolution
shadow target's alpha), and the composite scales the ambient and sky light by it (`light_occlusion.glsl`).
Debug view "Skylight" shows it.

Walls (`WallMap.java`) come from the collision map's line-of-sight edges plus the diagonal wall objects; rooms split
along them (`BuildingDetector.split`), and every light that stays put gets a quarter-tile visibility grid cast
against them on a worker thread (`LightOcclusion.java`), recast when a door opens or closes, packed after the room
map in the light buffer. Once a light's grid is cast the walls alone decide where it reaches on its own plane; the
rooms keep it off the other planes, except through holes in the floors (`RoomMap.linkStoreys`, from the scene's tile
paints and models): the rooms either side of a hole share their lights there, and a light of another plane shows when
nothing floors it over or in, or its room has a hole (`RoomMap.seenFrom`; 117 HD's plane rule plus the holes). Lights
under a roof don't light surfaces open to the sky above them, facing up (roofs), not the walls around them.

### Light map

Plugin config, Development section, Light map (on by default; off is the path above, the lights in the clusters).
The static lights (those in lights.json and the automatic ones, not the lights that move, follow an animation or the
sky) are baked on background threads into one grid per storey of quarter-tile cells (`LightMap.java`, `LightMapBake.java`).
What stops a light (`LightBlockers.java`): the walls and diagonal walls (`WallMap`), and the static models, rasterized
from the zones' triangles the skylight voxelizes (`ModelBlockers.java`). A cell keeps, per storey, which heights above the
floor a model reaches (16 bins of 15 units) and the box it fills in the cell (to the unit), per quarter of the heights.
A light is only stopped by what stands at its own height, in its bin or one either side: a barrel, crate, table or counter
(70 or less) never shades a torch above it, a pillar, shelf, open furnace door or statue does, and what stops the light is
the model's own box, not its cell. Flat triangles (floors, tabletops, ceilings) are left out. A solid object's tile (the
collision map's sight blocker) follows the same rule when the models are known: it is what the models say stands at the
light's height, and the whole tile only before they are (and on a storey other than the light's, which has no height).
A light is 0.8 tile wide: 16 points over its disc are each swept exactly (a polar depth over 2048 directions, the cost
is the blocker edges plus the cells), every cell looked at in 2 x 2 places, and the share of the looks that see it is its
visibility, falling off by (1 - d / r)^2 as in 117 HD. Light also walks round corners and through doors, as a floor under
the direct light (0.35 of the falloff by path distance, a flood fill over the cells; not through a model that fills its
cell), and a Gaussian blur of 2 cells (half a tile) takes light only from cells it can reach without crossing a wall, a
solid object or a model that fills its cell. A light inside its own object (a brazier, a wall torch, a furnace) is not
shaded by the models it stands in and is cast from the nearest open cell. Onto the storeys above and below the light goes
through the openings: the line to a cell has to cross every floor between where the storey has no floor tile, and the
walls of the storeys it passes through apply (models are not followed there).
A cell keeps the lights' summed colour and how far the brightest light is ahead of the next (RGBA16F), and the lights'
mean place weighted by their luminance, stored premultiplied so that it filters, and the index of the brightest light
(RGBA16F; the index is read as it is, everything else filtered, so nothing in space steps from cell to cell): 2 layers a
storey, 8 in all, 34.7 MB of GPU memory for the largest scene. The composite, the fog (per step), rain and snow (per
particle) and the water read them (`light_map.glsl`); the brightest light's flicker, pulse and fades come from a table in
the light buffer (strength now over strength baked), by its lead over the others so that nothing steps where the
brightest changes, and the mean place gives the direction and height for the shading. Lights at night: night-only
lights and night multipliers are baked at the night as of sixteen steps of it. Walls, doors and objects that change bake
the lights whose reach holds a changed cell again, and a light baked again to the same result sends nothing; a light is
the map's only once all its blocks are on the GPU, until then the clusters light it. Each scene load or teleport is one
line in the log (info): the lights baked, the time until every block was sent, the work and the threads; debug logging
adds each light's bake time.

