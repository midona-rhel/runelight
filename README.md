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
