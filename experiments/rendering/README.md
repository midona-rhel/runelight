# Unfinished rendering experiments

Checkpoint only. These are NOT approved visual results and are not loaded by normal client launches.

- `lava/`: rejected prototype, runtime tagging disabled by default. Latest feedback: hot yellow patches are too large; rock pieces overlap and are too angular. Need smaller hot regions and smaller coherent irregular rock shapes, without the first prototype's cereal-like repetition. Fog and point-light colors remain unfinished. This snapshot predates the latest moon/PCSS changes; do not overwrite current source wholesale.
- `water-glitter/water.glsl`: historical shimmer-filter candidate. It predates full-resolution water. Port only the glitter helpers if revisited; never replace current water.glsl wholesale. No visual acceptance.
- `point-light-boundary/lamp.glsl`: tested receiver-end tolerance candidate, not deployed. Default synthetic zigzag visibility regression remains unresolved without this candidate.
- `room-boundary/rooms.glsl`: opt-in diagonal boundary candidate matching Java `runelight.openRoomBoundaries=true`; actual doorway type still requires verification.

Live code and status are documented in ../../docs/active-bugs.md.
