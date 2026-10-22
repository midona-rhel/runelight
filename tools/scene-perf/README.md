# Scene performance capture

Use a developer client with **Show GPU timings enabled**. The tool attaches to that JVM; it does not modify settings or renderer code. Requires a JDK with attach support and Python with NumPy and Pillow.

```sh
tools/scene-perf/capture.sh JAVA_PID /tmp/runelight-scene
python3 tools/scene-perf/analyze.py /tmp/runelight-scene
```

Find the dev client's PID with `jps -lv` (or `ps ax` if it is absent). Use a new output directory for each capture. Capture takes about eight seconds, followed by one synchronous GPU readback that may hitch. Leave the camera steady for a comparable workload snapshot; the report counts camera changes instead of silently assuming stability. Timings are polled every 20 ms, not recorded on every rendered frame; duplicate frame IDs are removed. GPU timings arrive several frames later and carry their own frame IDs. GPU/frame profiling must be enabled; otherwise the report is not a timing benchmark.

Outputs:

- `scene.json`: dimensions, viewport, camera matrices, settings, light counts.
- `depth/normal/material/color.f32`: full-resolution rendered buffers, little-endian floats, bottom-up rows. Color includes coverage. This captures visible geometry, **not** all world meshes and is not a replay renderer.
- `lights.f32`, `light-extra.f32`, `clusters.u16`: actual uploaded light and cluster tables.
- `timings.csv`: raw GPU results (blank = no query, NaN = invalid), raw CPU stages and raw sampled frame intervals; no retained overlay averages.
- `revision.txt`, `working-tree.patch`: source provenance.
- `report.md`, `analysis.json`, `lights.csv`: statistics and per-light work ranking.
- Three PNG heatmaps: cluster candidates, candidates inside radius and facing the light, and radius-rejected candidates. Heatmaps do not measure spatial GPU execution time.

The analyzer reconstructs positions using the shader's inverse projection, accounts for coverage when decoding normals, and reproduces the current cluster/radius/back-face checks. Default `--stride 1` examines every pixel; larger strides estimate counts. Room rejection and visibility lookups are not simulated. Fog candidate iterations are an upper bound before ray/cylinder/depth early exits. Inspect the report's limitations before converting work counts into cost claims: neither instruction counts nor physical bandwidth can be inferred from texture-fetch counts alone.

Compare captures only at matching settings/viewpoints. A/B feature toggles are separate experiments; a snapshot cannot assign a causal millisecond cost to each expression inside a shader. The tool never adds CPU and GPU elapsed times together.
