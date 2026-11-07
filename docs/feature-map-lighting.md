# Lighting: current behavior map

Audited 7 November 2026 against the current working tree, including uncommitted structural point-light changes. This is a code audit, not visual acceptance or a measurement of the running game. Older inventory documents may describe different implementations or proposals. Numbers below describe equations/defaults, not measured screen brightness or GPU cost.

The indoor ambient cleanup and restored player light are implemented/deployed, but matched visual acceptance remains pending. Implementation described below is not evidence that doorway transitions or tent shading are correct.

## The separate lighting systems

| System | What it actually does | What controls obstruction |
| --- | --- | --- |
| Scene classification | Selects the outdoor sun/sky formula or the cave formula for the frame | Area environment classification, or client sky color fallback |
| Building rooms | Labels surfaces with a room ID and supplies one warm fill shaped by distance to walls | Roof flags, roof IDs, wall/collision topology and floor heights |
| Skylight | Reduces outdoor ambient according to exposure to the sky; smooths light near openings | Its own voxel field, directional samples and room/wall data |
| Sun/moon | One directional light, with an independently chosen shadow direction | A directional depth map containing general scene geometry |
| Point lights | Imported 117 HD lights, with radius falloff, color and animation | New binary ray test against captured structural wall/roof geometry |
| Ambient occlusion (AO) | Darkens surfaces close to visible depth discontinuities | Screen depth, including furniture and actors |
| Emissive materials | Makes authored material surfaces glow | Material rules; this is not the imported point-light list |

These are not one indoor/outdoor model. A building in an overworld scene receives skylight through openings, direct sun/moon and one wall-faded room fill; its opaque shading excludes the outdoor ambient floor and artificial daylight fill. A cave uses a different formula. A prop can darken a surface through AO or block the sun without being a point-light blocker.

## A concrete indoor walkthrough

For an opaque wall inside a detected overworld room, the renderer approximately adds:

1. Skylight through openings, multiplied by area ambient tint and AO; no outdoor ambient floor or artificial daylight fill inside detected rooms.
2. Direct sun or moon light, multiplied by its surface angle, directional visibility, area sun tint and AO.
3. One warm room-fill term, shaped by wall distance and multiplied by AO.
4. Visible imported point lights and the optional player debug light, each with its own distance falloff and surface angle, also multiplied by AO.
5. Material emission and additional material-specific effects.

The actual code is in [composite_frag.glsl](../src/main/resources/com/midonarhel/lumina/composite_frag.glsl), especially the outdoor/cave branch and `lampsOnSurface`. Fog, wet surfaces, water and final color processing can alter the resulting appearance further.

**A closed room at night with no torch is deliberately still lit.** At the default Interior brightness setting, each detected room receives one warm fill with target luminance 0.40, spatially scaled from 35% at walls to full strength one tile away. The outdoor ambient floor is excluded inside these rooms; opening skylight is separately weighted by exposure. The room does not need a lamp, an opening or a window to receive that fill. AO can darken it locally. Moonlight only adds where the directional calculation permits it.

**Adding a torch adds another term.** It does not subtract from the room's artificial fill, and the room target does not react to the number of torches. This can make an already readable room very bright when several authored lamps overlap.

**Walking through a doorway combines different transitions.** Skylight has spatial smoothing near openings. Room fill is selected by the surface's room ID and shaped by the room wall-distance field, independently of sky exposure; this is not a separate doorway/skylight fade. Fill levels ease over time when their target changes, which is different from spatial fading at a doorway. The room lookup offsets a surface 40 world units along its face normal to select the appropriate side of a wall.

**An actual cave takes another path.** It receives a fixed ambient contribution of 0.50 plus a directional key of 0.40, modulated by area tints and AO. That key is not the moon. Room fill is targeted to zero and is not added by the cave branch. Imported point lights and material emission can still contribute. Interior brightness is consequently not a general cave-brightness control.

**Turning Sun and moon lighting off also chooses that cave-style lighting path.** It does not simply remove the sun and leave the same outdoor ambient model in place. It disables the room-fill target and sets the night-only lamp multiplier to its nighttime value. That coupling is implemented behavior, whether or not it matches the setting's intended meaning.

## How a room is detected and assigned

[BuildingDetector](../src/main/java/com/midonarhel/lumina/visibility/BuildingDetector.java) begins from tiles marked under a roof. It groups connected tiles by plane and roof identity, then splits along wall and diagonal-wall boundaries. Closed doors can split rooms; open doors do not. Fences, gates and low walls are excluded from this room-wall model. Candidate groups smaller than three tiles or larger than 2,000 are rejected; additional open/eave rejection removes some marginal groups. If roof IDs are insufficiently consistent, detection falls back away from relying on them.

[BuildingLights](../src/main/java/com/midonarhel/lumina/visibility/BuildingLights.java) captures input on the client thread and builds the wall/roof/room result on a worker. [RoomMap](../src/main/java/com/midonarhel/lumina/visibility/RoomMap.java) supports four planes, floor heights, diagonal tile halves and room IDs up to 4094. Texture packing is also asynchronous; consumers can briefly use the previously uploaded map. This is intentional lifecycle complexity, not evidence that every room update is instantaneous.

[rooms.glsl](../src/main/resources/com/midonarhel/lumina/rooms.glsl) selects the highest floor below the sampled position with a two-world-unit margin. A room ends 420 units above its floor, approximately 3.28 tiles. The normal offset is 40 units, approximately 0.31 tiles. These fixed distances matter around thin walls, low ceilings, diagonals and stacked floors. Roof hiding for the player's camera is not the same thing as declaring the space outdoors.

### Room-fill calculation

[RoomLight](../src/main/java/com/midonarhel/lumina/light/RoomLight.java) computes one target for all rooms, using sun altitude `a` in degrees and the setting `b = Interior brightness / 100`:

```text
clearOutdoor = 2.64 * smoothstep(-4, 10, a) * max(sin(a), 0)
             + 0.80 * smoothstep(-10, 15, a)
dayTarget    = max(0.40, 0.42 * clearOutdoor)
nightShare   = smooth transition from 0 at +6° to 1 at -6°
target       = mix(dayTarget, 0.40, nightShare) * max(b, 0)
```

At a 45° sun altitude, default brightness gives approximately 1.12 target luminance. At deep night it gives 0.40. At 200% both targets double. These are linear lighting terms; 200% does not imply double the final displayed pixel value. The warm tint is normalized to unit luminance and derives from linear RGB `(1, .552, .231)`.

Changes use exponential easing with a 0.4-second time constant, not a fixed 0.4-second completion time. Frame delta is capped at 0.5 seconds. A fresh scene initializes to target. Same-scene room topology changes try to inherit the dominant overlapping old room's level; rooms without a predecessor can start at zero and ease up.

The target uses a clear-sky approximation, not current cloud cover, current skylight exposure, lamp count or measured room brightness. Its tint is also not multiplied by the area's ambient tint in the composite. This can make room fill respond differently from nearby outdoor light during weather or area transitions.

## Point lights: source, animation and visibility

[LampFrame.collectLights](../src/main/java/com/midonarhel/lumina/light/LampFrame.java) collects [SceneLights](../src/main/java/com/midonarhel/lumina/light/SceneLights.java), whose definitions come from the imported 117 HD light data. It also appends the optional developer player light: 250 world units above ground, radius five tiles, RGB (1, 0.72, 0.42), strength 6. This light is independent of night-only imported-light fading. The 117 HD-only restriction applies to static world lights, not this developer tool. There is no second automatic room-lamp generator in this active path. “117 HD only” does not mean stationary lamps only: definitions can attach to world coordinates, object IDs, NPCs, projectiles and graphics objects. This audit does not establish that the checked-in import matches the latest upstream revision.

[LightDefinition](../src/main/java/com/midonarhel/lumina/light/LightDefinition.java) supports location/area exclusions, parent animation filters, height and rotated offsets, alignment, color, radius, strength, flicker/pulse, schedules, spawn/despawn timing and parent lifetime rules. An object without a matching imported definition does not become a lamp merely because it visually resembles a torch.

| Authored parameter | Current interpretation / important interaction |
| --- | --- |
| Radius | World units; one tile is 128 units. Definition default is 300 units, about 2.34 tiles. Flicker lights expand effective radius to 1.5 times authored radius. |
| Strength | Unitless linear-light amplitude; default 5. It is not watts or displayed pixel brightness. |
| Color | Authored sRGB 0–255 values converted to linear RGB. |
| Type | Static by default. Flicker varies intensity using time and a per-light seed. Pulse varies intensity and radius. |
| Duration / range | Pulse duration in milliseconds; modulation range in percent. |
| Night schedule | Outdoor-classified scenes fade night-only lights from sun altitude −2° to −10°. This differs from the room-fill twilight interval of +6° to −6°. Cave-style frames use full night multiplier. |
| Night multiplier | Further scales brightness according to night weight; default 1. |
| Fade in/out | Defaults 50 milliseconds; spawn/despawn delays and parent-lifetime flags also affect apparent continuity. |
| Outdoor lighting | Separately changes color/strength with sun altitude; applies to a definition carrying this flag, rather than being a geometric indoor test. |

[OutdoorLight](../src/main/java/com/midonarhel/lumina/light/OutdoorLight.java) uses horizon and midday blends and a blue moon contribution with at most 0.12 strength. This is separate from the main directional moon term.

Lights are gathered, distance-culled and assigned to screen/depth clusters. [LightClusters](../src/main/java/com/midonarhel/lumina/light/LightClusters.java) considers at most the first 1024 input lights, then processes those by importance and caps a cluster at 127 lights. Later, lower-priority candidates lose a slot when that cluster is full. Authored strength times radius influences importance. Large radii and overlapping lights can therefore affect both work and which lights survive a cap; changing these parameters is not purely aesthetic.

### Current wall/roof occlusion

[lamp.glsl](../src/main/resources/com/midonarhel/lumina/light/lamp.glsl) performs a binary segment query from the shaded surface toward a lamp origin. If a captured blocker intersects it, that lamp contributes zero. Otherwise the contribution falls off as `(1 - distance / radius)^2` inside the radius and is zero outside. Opaque diffuse response also uses the smooth surface normal. There is no soft point-shadow filter or per-light cubemap in this active path.

[LampBlockers](../src/main/java/com/midonarhel/lumina/light/LampBlockers.java) indexes captured structural geometry in a stackless bounding-volume tree. [StructuralRectangles](../src/main/java/com/midonarhel/lumina/light/StructuralRectangles.java) merges suitable coplanar triangles into rectangles while retaining geometric holes. Intersection uses precomputed plane and coordinate rows. Geometry is cached until the relevant uploaded scene geometry changes; visibility is still queried for contributing light/sample pairs.

The capture rule selects wall objects and game-object placement types 9 and 12–21. It does not capture general furniture, actors, terrain or floor surfaces as point blockers. Roof cutaways remain blockers even when hidden for viewing. That is the implemented approximation to “walls and roofs only”; it is not an audited semantic classification of every game asset.

Important limits and interactions:

- A fence represented by a selected wall object can be a point blocker, even though the room detector excludes fences. The two wall definitions differ.
- The capture uses opaque faces. Transparent structural faces and models omitted before capture need inspection in real scenes.
- The ray test is double-sided, has one world unit of endpoint slack and offsets the surface by two units. These tolerances trade self-blocking against small leaks near seams.
- Wall-mounted lamp origins still use legacy wall-placement helpers. The visibility origin can differ from the authored visible light center.
- Door/model changes depend on scene geometry invalidation and upload. The code path alone does not prove every animated door updates its blocker at the right moment.
- Props and actors can still darken point-lit surfaces through AO. That is not their geometry entering the point-light ray test.
- Shared lamp queries serve opaque surfaces, transparent surfaces, water, fog and weather, but those consumers shade/integrate the returned light differently.

## Sun, moon and directional shadows

[DayCycle](../src/main/java/com/midonarhel/lumina/sky/DayCycle.java) advances the visible sun altitude along a `70*sin(phase)+12` degree curve. The moon is opposite the sun's azimuth with negated altitude. The sun is the directional caster above −3° sun altitude; below that, the moon is the caster. Lightning can temporarily replace the caster direction and light.

The shadow direction deliberately differs from the visible celestial disc: its elevation is clamped to at least 30°, at most 68° for the sun and 58° for the moon. This avoids very long low-angle shadows but means the visible moon's location is not a literal prediction of shadow geometry.

[FrameState](../src/main/java/com/midonarhel/lumina/frame/FrameState.java) enables directional shadows only when the scene is outdoor-classified, Sun and moon lighting is enabled, Shadows is enabled, and the client is logged in. [ShadowPass](../src/main/java/com/midonarhel/lumina/shadow/ShadowPass.java) additionally needs initialized resources and scene state. Both sides of geometry cast; general props/actors can cast these directional shadows. The wall/roof-only requirement currently concerns point lights.

The default 32-tile shadow distance defines the covered area. At a fixed map resolution, increasing it spreads texels over a larger area and changes the rendered geometry set. Resolution choices are 1024, 2048 and 4096. Static caching and dynamic rendering reduce some repeated work, but this is not a universal fixed-cost pass. Leaf shadow sway is currently hardcoded off even when visible foliage moves. The emissive-strength setting also changes whether emissive surfaces cast in this pass.

The code contains a moon-shadow path. That does **not** establish that the user's reported absent nighttime shadows are fixed or visually acceptable. A useful live check must confirm scene classification, caster selection, shadow-map validity, receiver lighting and the actual resulting shadow. Warm fill, sky ambient, AO and overlapping lamps can hide directional contrast even when a map exists. These are hypotheses to distinguish, not a diagnosis from this audit.

[sky.glsl](../src/main/resources/com/midonarhel/lumina/sky.glsl) uses nominal sun intensity 2.9 and moon intensity 0.675, with different color and altitude fades. Moon color is blue `(0.42, 0.55, 1)`. There are reversed-edge `smoothstep` calls in celestial fading; they deserve a deliberate PC/Mac portability review rather than an assumption that the visual result is identical on both drivers.

## Skylight and AO

[VisibilityFrame](../src/main/java/com/midonarhel/lumina/visibility/VisibilityFrame.java) prepares skylight for the top-level world view when outdoor lighting is active and the client is logged in. It is distinct from point-light blockers. Its voxel model may include props/vegetation that should not block point lights.

[skylight_ao.glsl](../src/main/resources/com/midonarhel/lumina/skylight_ao.glsl) combines directional sky visibility with wall-aware interpolation and an opening field. The field reaches three tiles from openings. The sampled grid has 12 height layers, a first sampled height of 40 units and 72-unit spacing. Directional components are weighted by the surface normal; diagonal wall halves and walls limit interpolation across partitions. Previous/current data is blended during updates. [skylight_ambient.glsl](../src/main/resources/com/midonarhel/lumina/skylight_ambient.glsl) keeps a 0.40 floor for the outdoor ambient path and defaults to open visibility there when skylight is unavailable. Detected opaque rooms bypass that floor: their opening-skylight contribution becomes zero when skylight is disabled, while their warm fill remains separate.

[ao_frag.glsl](../src/main/resources/com/midonarhel/lumina/ao_frag.glsl) computes AO from screen depth at half resolution with 6, 8 or 12 samples. It cannot see hidden/off-screen geometry, so this is a view-dependent proximity approximation. Default radius 75% of a tile equals 96 world units. Its thickness and bias scale with radius, so radius changes the character of occlusion as well as its reach.

AO strength applies `clamp(1 - (1 - ao) * strength, 0, 1)`. The current direct-light AO coefficient is 1: AO fully multiplies direct sun/moon diffuse, point diffuse, sky ambient and room fill. Leaf transmission is treated separately. Setting AO off does not necessarily remove the whole half-resolution pass, because directional shadow and skylight values also travel through it.

## Transparent surfaces do not receive identical lighting

[frag.glsl](../src/main/resources/com/midonarhel/lumina/frag.glsl) shades the alpha pass with its own outdoor ambient/sky/direct formula and shared lamp visibility. That formula does not add the opaque pass's room fill, voxel skylight or SSAO. It samples the previous frame's directional shadow map/matrix. Cave lighting is also a separate branch there.

Consequently an indoor transparent object can have a different apparent brightness from an adjacent opaque object even with identical imported lamps. Water has further specialized lighting. These are actual pipeline differences, not proof of a point-occlusion defect.

## Main user settings

Defaults and declared limits come from [LuminaConfig](../src/main/java/com/midonarhel/lumina/LuminaConfig.java). A listed maximum-only range uses the config framework's minimum rather than an explicit custom lower bound in this file.

| UI setting | Default / declared range | Behavioral caution |
| --- | --- | --- |
| Sun and moon lighting | On | Also selects outdoor/cave shading, room-fill eligibility and night-only lamp state. |
| Interior brightness | 100%; maximum 300% | Persisted key remains `buildingLightBrightness`; shared overworld room-fill target with wall-distance shaping, not a lamp or cave multiplier. |
| Point lights | On | Controls point lighting, including the separately toggled developer player light; not emission, room fill, skylight or AO. |
| Glowing materials | 100%; maximum 300% | Material emission; also affects directional caster treatment. |
| Leaf transmission | On | Allows separate backlit foliage response. |
| Shadows | On | Directional geometry map; does not disable point occlusion or independently configured cloud shading. |
| Shadow distance | 32 tiles; 8–96 | Coverage/detail/work tradeoff. |
| Shadow resolution | 2048; 1024/2048/4096 | Directional map resolution only. |
| Ambient occlusion | On | Screen-depth effect; shared pass may still execute when off. |
| AO strength | 100%; maximum 200% | Applied to direct lighting too. |
| AO radius | 75% of a tile; 10–400% | 96 world units by default, up to four tiles. |
| AO quality | High | High 12, Medium 8, Low 6 samples. |
| Procedural sky | On | Sky drawing/cache choice; not a master switch for all lighting. |
| Day length | 24 minutes | Fixed, 24 minutes, one hour, or local real-world time. Weather uses a separate shared clock. |
| Fixed hour | 12; maximum 23 | Used in Fixed mode; developer-held hour can override. |
| Cloud shadows | On | Sun/moon cloud attenuation is separate from geometry shadows. |

Area environments affect scene classification and light/fog tints. With them disabled, the open-sky fallback tests whether the client's sky RGB sum exceeds 24. With them enabled, the environment manager supplies overworld classification using scene/location/plane information. This is a frame classification, not a per-pixel roof test. See [SkyFrame](../src/main/java/com/midonarhel/lumina/sky/SkyFrame.java).

## Reloads, remaining complexity and performance uncertainty

SceneLights clears state on loading/login transitions and subsequently recollects definitions against scene entities. Lamp lifetime/fade rules, building workers, room-texture packing, skylight rebuilds and structural geometry upload are independent update paths. Replacing point visibility does not automatically eliminate visible light repopulation or all asynchronous transitions.

Active point visibility now uses `LampBlockers`. `LampVolume` is test-only; `LampFrame.lightMapOff` is a deprecated replay option and runtime light-map baking is no longer called. `LightMap`, `LampCubes`, old cluster upload hooks and some bake metadata still exist as production leftovers. They should not be documented as active point-shadow techniques merely because classes remain.

One concrete leftover worth reviewing: `VisibilityFrame.prepareVoxels` still requests voxel preparation when Point lights is enabled, even though the new point blocker query no longer uses that general voxel representation. This is a code dependency observation, **not** a measured explanation for a millisecond result.

The new method's likely work drivers are visible sample count, overlapping lights per sample, how soon a ray is rejected, traversed tree nodes/primitives, structural upload/rebuild frequency, and which passes request lamp light. There is no per-light visibility bake amortizing all of those queries. A synthetic full-screen overlapping-light timing cannot by itself establish typical game cost, the cause of a slowdown, or acceptable playability. No new timing was performed for this document.

## Confirmed mismatches and open checks

| Status | Finding | What would resolve it |
| --- | --- | --- |
| Confirmed text/code mismatch | Point Lights tooltip says lights do not cast shadows; active point lighting has structural binary occlusion. | Update wording after intended terminology is agreed. |
| Confirmed text/code mismatch | RoomLight comments describe doorway fading; the current composite uses wall-distance shaping independent of sky exposure. | Correct the comment to describe the implemented wall fade; do not infer an additional doorway fade. |
| Confirmed text/code mismatch | Some room/lamp comments say walls alone block; roofs now also block point light. | Remove obsolete descriptions. |
| Confirmed coupling | Sun lighting off selects cave shading and full night-only lamps, while removing room fill. | Decide whether this is intended before simplifying it. |
| Confirmed pipeline difference | Transparent surfaces lack opaque room-fill/skylight/AO behavior. | Inspect an actual indoor alpha material and choose desired consistency. |
| Confirmed classification risk | Structural placement classes are not a semantic wall/roof asset list. | Check fences, railings, glass, unusual roofs and dynamic doors in game. |
| Unresolved user-visible issue | Nighttime directional shadows reportedly absent. | Live nighttime observation plus frame/caster/map state; no claim of resolution from code alone. |
| Unresolved performance issue | Reported synthetic timing is not a causal in-game diagnosis. | Matched capped-FPS game scenes, separately timed passes, resolution/light/geometry variants and explicit variance. |
| Candidate simplification | Legacy point-light classes/hooks and unnecessary voxel requests remain. | Trace callers and remove only after current shared consumers are accounted for. |

The most useful player inspection is a single building visited by day and night: stand outside, cross the doorway, turn around, inspect both faces of a wall, open/close its door, hide/show its roof, and compare a nearby transparent object. Repeat with Interior brightness at zero and with AO off to distinguish artificial fill and screen occlusion from the point-light blocker rule. These are proposed checks, not checks claimed completed here.
