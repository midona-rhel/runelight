# Lumina on Windows

Double-click `play-lumina.bat` in the checkout, or run `./gradlew.bat run` from PowerShell.
Java must be on PATH (this setup uses Temurin 21). The first build downloads Gradle and dependencies.
The development client reads your existing RuneLite profiles and saved credentials. Disable 117 HD
and the built-in GPU plugin before enabling Lumina. Only run one game client at a time.

Shaders are watched under `src/main/resources/com/midonarhel/lumina`. Java changes require a client restart.

## First Windows session, 2026-11-07

Base: `origin/occ/lumina-live` at `ad7e3a7f`, checked after fetching origin. The local branch is
`codex/windows-lumina`. This is the latest handover branch; `origin/lumina` is a separate history
with 17 commits not yet reconciled into it.

The initial build passed 479 tests, with 25 skipped. The offline GL harness currently uses macOS CGL;
a passing Windows suite does not validate rendering. The live GPU is an NVIDIA RTX 4080.

Enabling Lumina after 117 HD failed with `unable to set pixel format (2000)`. Lumina requested
an 8-bit window stencil while RuneLite's GPU plugin requests none. Windows now uses that same
pixel format; the existing composite fallback handles a window without stencil. The startup
failure also invoked shutdown both on the client thread and through PluginManager on the EDT,
causing a race and a null buildingLights exception. Cleanup now belongs to PluginManager alone.
Live verification: after a clean restart, Lumina rendered Fossil Island on the RTX 4080 with rain,
clouds, fog and lighting visible (about 120 FPS in the observed view, not a benchmark).
The initial driver shader compilation took about 35 seconds. Toggling off/on during that wait
exposed a separate lifecycle race: queued startup/shutdown collided, causing voxel-worker and
terminated-executor errors. A further clean restart and single enable succeeded. This race is
still open; do not toggle repeatedly while shaders compile.

NVIDIA also reports GL debug warning 0x20096: a comparison sampler is bound with non-depth
textures (observed texture IDs 30 and 33), including use by a non-shadow sampler. Investigate
texture/sampler binding lifetime before treating the missing moon shadows as a lighting-tuning
problem. These warnings remain unresolved; rendering successfully is not visual acceptance.

Next: reproduce the reported missing moon shadows and scene-load lamp resets in the live client.
The moon already supplies casterDir and participates in the shadow/composite paths; do not treat
that as evidence that its shadows look correct. HANDOVER.md records the outstanding lamp reset
and weather issues. Windows shader replay support remains a separate portability task.
