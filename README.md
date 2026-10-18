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
`weather_surface_frag.glsl`; counts and timings are at the top of `Weather.java`. With varying intensity the
rain or snow swells, eases off and gusts around the set intensity (`WeatherVariation.java`). The cloud cover follows
it slowly and grays the sky and softens the sun and moon everywhere through `weatherOvercast` in `sky.glsl` (its
`OVERCAST_*` defines). Drops, flakes, splashes and snow cover pick up nearby point lights through the light clusters
(`weather_common.glsl`).

Automatic weather (the default mode) picks a new weather per biome every third of a game day (8 game hours, 4
minutes at the default day length): clear, partly cloudy, cloudy, fog, rain, storm, snow or blizzard, from each
biome's odds as a Markov chain so consecutive weathers connect (`WeatherForecast.java`, odds and affinities at
the top). Each weather is a set of targets in `WeatherType.java` (cover, rain, snow, wind, fog, area fog, distance
fog, morning mist, storm) that `Weather.java` eases toward, so slot changes and walking into another biome blend
over about a minute. Storms strike lightning (`Lightning.java`): the flash goes through `weatherOvercast.z`
(`LIGHTNING_*` in `sky.glsl`), the bolt and cloud glow are in `lightning.glsl`. With GPU timings shown, the
overlay lists the weather, region, next weather and minutes to it.

## Areas

Which area the camera is in, whether it is under the open sky or underground, and each area's light tint, fog and
sky haze come from `environments.json`, built by `tools/import_environments.py` from 117 HD's areas and
environments with 3D Weather's biome regions as fallback (see the script for usage and what it leaves out).
Rerun it against newer checkouts to pick up their additions. The biome looks (desert, swamp, snow, ...) are set in
the script's `BIOMES` table.

## Clouds

Plugin config, Clouds section: one slab of clouds over the procedural sky, marched in 12 steps per sky pixel in
the composite (nothing is spent where scenery covers the sky), with optional cloud shadows on the ground. Rain and
snow close the sky up through `Weather.cloudCover()`. Tuning constants are `#define`s at the top of `clouds.glsl`;
the noise texture and wind are in `Clouds.java`. A detail noise warps the shapes and, with a fine noise, erodes
ragged edges and mottles the insides (Cloud roughness); some stretches of sky turn wispy. The sun and moon show
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
pushes toward its shores. Swamps and caves (from the area, `EnvironmentManager.waterMurk`) make water murky. Water
terrain is uploaded as a lowered bed, deeper away from the shore. The surface is not geometry: the composite finds
where each view ray crosses it and shades it in `water.glsl` (refraction, depth absorption into a dark depth
color, caustics, sky and a few taps of screen-space reflection, glints and sparkle, two octaves of flow-mapped wave
noise and a swell, rain rings), fading in from the waterline without a hard edge. Per-type looks are the tables at
the top of `water.glsl`, the rest are its `#define`s. "Show water map" tints water by type and shows its flow as
moving stripes.

The flow map: `tools/water/build_flow.py <cache dir> src/main/resources/com/midonarhel/runelight/water_flow.dat`
downloads the map image (Explv's osrs_map_tiles, rendered from the game cache by RuneLite's map dumper) into the
cache dir, finds the water, and computes the flow; `tools/water/annotations.json` fixes rivers the automatic pass
gets wrong (sources, outlets, still canals), and `tools/water/crop.py` renders map crops with a tile grid and the
flow to check them. Needs numpy, scipy, scikit-image and Pillow.
