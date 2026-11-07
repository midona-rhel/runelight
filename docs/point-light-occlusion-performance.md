# Structural point-light performance — 2026-11-07

> **Evidence status, updated after user review:** These are diagnostic synthetic offscreen timings, not an
> accepted in-game performance result. The often-quoted ~6.9 ms is the complete synthetic lighting pass for
> one 16-light fixture, not a measured increment in a real RuneLight frame. Before/after client activity and
> GPU clock conditions were not matched. A low-power state was observed, but no controlled test established
> how much of the variation it caused. Correctness tests do not establish visual acceptance. Real-scene
> cost, source of timing variability, and user acceptance remain open.


The initial direct per-pixel triangle implementation was unsuitable for dense scenes. The optimized implementation and follow-up results are below.
Even the simple fixture takes 6.35 ms for four overlapping lights at 1440p; 16 take 26.01 ms.
Detailed geometry sharply increases the cost. These results reject this implementation as a general
performance solution; they do not invalidate limiting blockers to walls and roofs.

## Method

Run `./gradlew test --tests com.midonarhel.lumina.light.LampBlockersPerfTest -Dlumina.blockerPerf=true`
(quote the `-D...` argument in PowerShell).

Hidden Windows OpenGL context on the RTX 4080, rendering the actual production `lamp.glsl` visibility
query to an RGBA16F offscreen surface. Submission is capped at 60 frames per second using CPU pacing
after the GPU timer result. GPU elapsed queries exclude that pacing. Work that exceeds 16.67 ms is
allowed to finish; a cap cannot make an over-budget draw take less time.

A synthetic 2048-by-2048-unit floor is covered by candidate lights with radius 2200. Their distance
falloff and Lambert lighting are evaluated with occlusion alternately disabled/enabled, reversing
order each iteration. There are 12 warmup draws and 40 measured draws per mode and case. Reported
medians and p95s are GPU milliseconds, not reciprocal FPS. The fixture has three finite partition
walls with doorways and a roof; the simple version has 200 triangles, the subdivided version 8264.
The latter tests expensive unsuccessful intersections with roof triangles above the rays.

This isolates the light/shadow query cost. It is not a full-game frame benchmark, an old-volume A/B,
or a captured live interior. Real cluster culling and screen coverage change how often lights reach
pixels. The game and desktop remained running, so other GPU work and scheduling can affect timings.
CPU pack timings measure only `LampBlockers.pack`, not complete scene capture or upload.

## Raw results

```text
GPU: NVIDIA GeForce RTX 4080/PCIe/SSE2; driver: 3.3.0 NVIDIA 610.88
Synthetic 2048-unit interior; actual lamp.glsl; RGBA16F target; GPU elapsed queries; 60 FPS submission cap; GPU timers exclude pacing; 12 warmups, 40 interleaved samples per mode. Other desktop/game GPU work remains active.
geometry=simple triangles=200 bufferKiB=58.8 packMeanMs=0.364
1920x1080 lights=1 baselineMedian=0.018 blockedMedian=0.602 added=0.584 blockedP95=0.723 ms
1920x1080 lights=4 baselineMedian=0.022 blockedMedian=4.222 added=4.200 blockedP95=6.005 ms
1920x1080 lights=16 baselineMedian=0.062 blockedMedian=13.566 added=13.503 blockedP95=14.553 ms
1920x1080 lights=32 baselineMedian=0.120 blockedMedian=25.428 added=25.308 blockedP95=26.666 ms
2560x1440 lights=1 baselineMedian=0.031 blockedMedian=1.298 added=1.268 blockedP95=2.001 ms
2560x1440 lights=4 baselineMedian=0.032 blockedMedian=6.351 added=6.319 blockedP95=8.288 ms
2560x1440 lights=16 baselineMedian=0.109 blockedMedian=26.010 added=25.901 blockedP95=28.317 ms
2560x1440 lights=32 baselineMedian=0.210 blockedMedian=49.608 added=49.398 blockedP95=52.517 ms
geometry=detailed triangles=8264 bufferKiB=583.8 packMeanMs=1.190
1920x1080 lights=1 baselineMedian=0.017 blockedMedian=8.949 added=8.931 blockedP95=9.944 ms
1920x1080 lights=4 baselineMedian=0.019 blockedMedian=34.361 added=34.342 blockedP95=36.841 ms
1920x1080 lights=16 baselineMedian=0.062 blockedMedian=112.415 added=112.352 blockedP95=113.315 ms
1920x1080 lights=32 baselineMedian=0.120 blockedMedian=215.038 added=214.918 blockedP95=216.350 ms
2560x1440 lights=1 baselineMedian=0.030 blockedMedian=17.316 added=17.286 blockedP95=19.554 ms
2560x1440 lights=4 baselineMedian=0.032 blockedMedian=66.792 added=66.761 blockedP95=68.674 ms
2560x1440 lights=16 baselineMedian=0.109 blockedMedian=216.627 added=216.519 blockedP95=218.902 ms
2560x1440 lights=32 baselineMedian=0.210 blockedMedian=405.563 added=405.353 blockedP95=408.461 ms
```

## Next implementation constraint

Keep one wall/roof-only visibility rule, but remove triangle walks from every shaded pixel.
Use a bounded-cost structural representation or a visibility cache keyed to geometry changes and
lamp position/radius. Colour and flicker must not cause occlusion rebuilds. Retain the exact query
as a correctness reference for tests, and rerun the same capped benchmark before deploying its
replacement. Measure a captured dense live scene as well before making whole-game claims.

## Implemented fix and follow-up (2026-11-07)

The runtime now merges adjacent coplanar pairs into planar rectangles, including sloped roofs, without
filling doorways or roof holes. Unrecognized shapes retain their original triangles. A stackless bounds
hierarchy rejects whole subtrees that the light segment cannot intersect. Each remaining primitive has
its plane and barycentric rows precomputed on the CPU, removing per-pixel cross products. Unchanged
structural geometry no longer rebuilds when an unrelated object causes a zone upload. No light bake,
per-lamp handoff, shadow-map sampling, extra lights, or floor/terrain blockers were introduced.

The simple, detailed-flat, and detailed-sloped fixtures all reduce to about 1 KiB of structural data;
the original detailed flat fixture occupied 584 KiB in the duplicated tile lists. The detailed-sloped
fixture was added to ensure the improvement was not limited to horizontal roofs.

The client was stopped for the final run; the desktop remained active. The 60 FPS submission cap and
GPU timing method stayed unchanged. During investigation NVIDIA was observed in P8 at 645 MHz core
and 405 MHz memory: pacing permits clock changes, and medians/p95s vary across cases. The original run
had the client active. Therefore these are useful before/after fixture measurements, not a locked-clock
speedup claim or full-game FPS prediction. All reported times include only the synthetic light pass.

```text
GPU: NVIDIA GeForce RTX 4080/PCIe/SSE2; driver: 3.3.0 NVIDIA 610.88
Synthetic 2048-unit interior; actual lamp.glsl; RGBA16F target; GPU elapsed queries; 60 FPS submission cap; GPU timers exclude pacing; 12 warmups, 40 interleaved samples per mode. External GPU activity is not controlled by this harness; record whether the client was stopped.
geometry=simple triangles=200 bufferKiB=1.0 packMeanMs=1.334
1920x1080 lights=1 baselineMedian=0.018 blockedMedian=0.059 added=0.041 blockedP95=0.181 ms
1920x1080 lights=4 baselineMedian=0.057 blockedMedian=0.754 added=0.696 blockedP95=0.768 ms
1920x1080 lights=16 baselineMedian=0.197 blockedMedian=3.213 added=3.017 blockedP95=8.406 ms
1920x1080 lights=32 baselineMedian=0.384 blockedMedian=7.884 added=7.500 blockedP95=9.321 ms
2560x1440 lights=1 baselineMedian=0.096 blockedMedian=0.311 added=0.215 blockedP95=0.369 ms
2560x1440 lights=4 baselineMedian=0.072 blockedMedian=0.956 added=0.885 blockedP95=2.145 ms
2560x1440 lights=16 baselineMedian=0.345 blockedMedian=6.554 added=6.209 blockedP95=8.186 ms
2560x1440 lights=32 baselineMedian=0.670 blockedMedian=13.481 added=12.811 blockedP95=15.487 ms
geometry=detailed triangles=8264 bufferKiB=1.0 packMeanMs=8.163
1920x1080 lights=1 baselineMedian=0.056 blockedMedian=0.182 added=0.126 blockedP95=0.184 ms
1920x1080 lights=4 baselineMedian=0.040 blockedMedian=0.502 added=0.462 blockedP95=3.558 ms
1920x1080 lights=16 baselineMedian=0.197 blockedMedian=3.216 added=3.020 blockedP95=5.756 ms
1920x1080 lights=32 baselineMedian=0.288 blockedMedian=6.254 added=5.966 blockedP95=8.789 ms
2560x1440 lights=1 baselineMedian=0.030 blockedMedian=0.098 added=0.069 blockedP95=0.305 ms
2560x1440 lights=4 baselineMedian=0.097 blockedMedian=1.306 added=1.208 blockedP95=2.364 ms
2560x1440 lights=16 baselineMedian=0.343 blockedMedian=6.912 added=6.569 blockedP95=8.068 ms
2560x1440 lights=32 baselineMedian=0.674 blockedMedian=13.228 added=12.554 blockedP95=17.274 ms
geometry=detailed-sloped triangles=8264 bufferKiB=1.0 packMeanMs=6.874
1920x1080 lights=1 baselineMedian=0.055 blockedMedian=0.178 added=0.123 blockedP95=0.182 ms
1920x1080 lights=4 baselineMedian=0.018 blockedMedian=0.233 added=0.215 blockedP95=0.750 ms
1920x1080 lights=16 baselineMedian=0.195 blockedMedian=3.192 added=2.997 blockedP95=4.521 ms
1920x1080 lights=32 baselineMedian=0.382 blockedMedian=7.934 added=7.552 blockedP95=9.485 ms
2560x1440 lights=1 baselineMedian=0.030 blockedMedian=0.097 added=0.068 blockedP95=0.098 ms
2560x1440 lights=4 baselineMedian=0.097 blockedMedian=1.304 added=1.206 blockedP95=2.119 ms
2560x1440 lights=16 baselineMedian=0.343 blockedMedian=5.608 added=5.265 blockedP95=7.854 ms
2560x1440 lights=32 baselineMedian=0.674 blockedMedian=13.284 added=12.611 blockedP95=15.202 ms
```

Correctness: the GPU query was compared with an independent plane/edge CPU oracle against unsimplified
geometry for 256 deterministic random segments, plus explicit door, finite-height, sloped-roof,
vertical-ray, outside-origin, and roof-hole cases. All five affected shader stages compile with
GLSL 3.30 validation. This retains exact structural visibility apart from the existing endpoint and
floating-point tolerances. Dense overlap still costs time: 16 full-screen lights at 1440p measured
about 5.6–6.9 ms in the detailed fixtures, and 32 about 13 ms in this capped run. A captured live dense
scene remains the next measurement before making any whole-game performance claim.
