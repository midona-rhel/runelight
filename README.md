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
the noise texture and wind are in `Clouds.java`.
