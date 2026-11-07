# RuneLight / Lumina: current feature map

**Source audit: 7 November 2026, current working tree.** This is an explanation of what the code currently does,
not a statement that every feature looks correct or performs well. Changes to the renderer remain reviewable;
this mapping pass does not change rendering behavior or your settings.

## Start here

- [Browse the searchable guide](feature-map.html): all three audits and all 59 declared settings in one place.
- [Lighting, indoors, shadows and occlusion](feature-map-lighting.md)
- [Sky, time, weather, fog and ground response](feature-map-weather.md)
- [Materials, water, rendering, scene loading and diagnostics](feature-map-rendering.md)
- [Exact settings registry](feature-map-settings.json): declarations and defaults, **not your live saved values**.
- [Point-light measurements and their limits](point-light-occlusion-performance.md)

## Find the behavior you are trying to understand

| Question | Start with | Also matters |
|---|---|---|
| Why is this room bright even without a nearby lamp? | Lighting: indoor calculation and room fill | Area classification, skylight, AO, time of day |
| Why does a cave respond differently to Interior Brightness? | Lighting: open-sky buildings versus caves | Environment lighting and the Sun Lighting switch |
| Where do lamps come from, and what blocks them? | Lighting: imported 117 HD sources and structural occlusion | Source lifetime, moving attachments, clustered culling, hidden roofs |
| Why does a roof cutaway change rain or fog differently from lamps? | Weather: shelter and fog | Each system has its own geometry and roof policy |
| Why does disabling Sun Lighting change more than sunlight? | Lighting: branch selection | Cave shading, room fill, night-only lights |
| Why can weather change while the sun stays fixed? | Weather: separate day and weather clocks | Manual weather mode, climate and ground history |
| Why does increasing rain not immediately make the ground match? | Weather: intensity, wetness and history | Drying, snow/melt, shelter and material eligibility |
| Why does lower 3D resolution not reduce all GPU work? | Rendering: target sizes and frame stages | Half-resolution intermediates, draw distance, MSAA, upscaling |
| How do water, reflections and caustics actually work? | Rendering: water surface and composition | Weather, scene depth, material classification and transparency |
| Why do changes or scene loads cause hitches/rebuilds? | Rendering: invalidation, jobs and loading | Lighting source lifecycle and geometry caches |
| What is genuinely different between Mac and PC? | Rendering: platform and GL behavior | Shader validation, window pixel format, reverse depth and capability limits |

## Read the evidence correctly

| Label | What it supports | What it does not support |
|---|---|---|
| Code behavior | A branch, formula, default, data source or invalidation rule exists in the current source | Correct appearance in every game location |
| Code-derived concern | A mismatch or interaction can be explained from source | A reproduced user-visible bug unless specifically recorded |
| GPU correctness test | Specified synthetic rays match expected blocking, including holes and slopes | Full game behavior, visual quality, source placement or overall performance |
| Synthetic timing | A particular offscreen workload took the recorded GPU time under recorded conditions | The cost of your actual game frame |
| In-game observation | Something was actually visible or logged in the running client | A controlled A/B or acceptance of unrelated features |
| User acceptance | You have tested and accepted the appearance/behavior | Anything not covered by that feedback |

The ~6.9 ms figure is the complete lighting pass of one synthetic 16-light fixture. It is **not** the measured
point-light cost of your current game scene. GPU power state was observed changing; its contribution to the
variation was not isolated. Earlier confidence about the cause and practical adequacy was not justified.

## Interactions to review first

1. **Indoor ambient is one warm wall-faded fill.** Detected opaque rooms also retain skylight through openings,
   directional light, point lights and SSAO. Outdoor ambient floor and artificial daylight fill are excluded there.
   Caves take a different branch. This implementation still needs matched visual acceptance.
2. **The lighting master switch is not semantically narrow.** The current Sun Lighting switch affects the
   branch used for cave lighting, room-fill targeting and night-only lamp weighting.
3. **There is more than one definition of shelter.** Point lights now use structural placement classes,
   which can include fences: this is not yet a guarantee of strictly walls and roofs only.
   weather shelter uses a separate opaque-geometry representation; room IDs and fog density use other data.
   A shared word such as roof, inside or hidden does not mean a shared calculation.
4. **The day clock and weather clock are independent.** Freezing the sun does not freeze every weather phase.
5. **Several labels/comments are stale.** The detailed guides distinguish declarations, implementations and
   suspected intent. Tooltips are not treated as authoritative documentation.
6. **Legacy paths still exist.** Presence of a class or shader does not mean it participates in the live frame.
   The audits flag remaining old light-map/cube code separately from the active structural query.

The optional player debug light is restored; the 117 HD-only restriction concerns static world lights. Water surface lighting now uses half the selected 3D scene width and height, rounded up, while surface geometry uses full 3D scene resolution. These source changes do not close the open doorway-light, tent-shading, rock-sorting or water-crawl reports.

## Play and review without changing everything at once

The client is left running for your own testing. No further restarts, scene changes, time changes or benchmark
runs are part of this documentation pass. Useful feedback is the location, the visible problem, the relevant
setting and what you expected. You do not need to collect a formal test report.

For an indoor-light review, keep the camera and time fixed yourself if useful, compare a window/doorway,
a room with a lamp and a room without one, and note whether the location is a building or a cave. The guides
explain which settings should affect each term; unexpected differences remain findings to investigate,
not evidence to explain away with the harness.

## Coverage and maintenance

The guide covers declared settings plus shader/CPU mechanisms without exposed controls. Each domain guide
includes source references, parameters, interactions and limitations. The registry is checked against every
`keyName` in `LuminaConfig.java` so an omitted public setting is detectable. It does not claim to enumerate
every numeric literal or every third-party RuneLite plugin.

Historical `docs/inventory/` files mix older implementations, measurements and proposed replacements. They
remain useful history, but this guide audits current code instead. Source references and declarations will
age as code changes; regenerate the browsable guide with `python tools/build-feature-map.py` after updating
the domain documents. No performance acceptance or further simplification is implied by this map.
