# Lumina: sky, day cycle and shadows — design (draft for review)

Base: branch `lumina` (RuneLite GPU plugin 1.13.4 copy, package com.midonarhel.lumina) + the 117 HD shading port (normals, unlit colours, areas, model/tile overrides, environments; in progress). GL 3.3 core / macOS 4.1. Sun light is diffuse only (no specular); leaf transmission allowed. Primary source: old RuneLight (branch perf-wip, com.midonarhel.runelight).

## Principles
- One global clock, sun and moon. Areas never move the sun or the shadows.
- Every input is continuous in time and space; anything discrete (cache rebuild, sun step, area change, caster switch) is hidden by a fade or a dead band.
- Modules own their state and talk through two seams: `LightingState` (CPU, filled per frame) -> one std140 UBO the shaders read; and `casterDir/casterKind/casterEpoch` from the day cycle to the shadow module.
- No module draws or computes when its feature is off.

## Packages
- `lumina.sky`: GameClock, Celestial, SkyModel, SkyLight, SkyRenderer, AreaAmbience
- `lumina.shadow`: ShadowFit, ShadowCache, DynamicShadowMap, ShadowTiles, ShadowUniforms
- `lumina.frame`: draw recording/replay (needed by shadows), the frame order, timers

## Sky / day cycle
1. GameClock (port verbatim from RuneLight GameClock + tests): game time = f(unix time) with fixed epoch, default day 24 real minutes, fixed-hour mode. Fix: when day length / fixed hour change, ease the phase over a few seconds along the shortest path (old code jumped).
2. Celestial (from DayCycle): visual sun alt = 70 sin(2πφ)+12, azimuth linear (south at noon); moon opposite; caster = sun or moon with elevation clamped 30–68° (sun) / 30–58° (moon). Handoff dead band: sun light reaches exactly 0 at -2°, moon light starts above 0 only below -4°, caster flips at -3° (both zero there → invisible). Outputs sunDir, moonDir, casterDir, casterKind, casterStrength, casterEpoch (increments on kind change).
3. SkyModel (CPU port of sky.glsl): evaluate(alt, AreaLook, SkyModifiers) -> SkyFrame {zenith, horizon, glow, sunColour, moonColour, ambient, skyFill}. Keyframes and light formulas verbatim; switch per-segment smoothstep to a monotone cubic (no rate stalls at keys). SkyModifiers = weather seam (overcast, snow, haze, warmth, saturation, sun strength), identity when neutral.
4. Brightness target: keep the world bright (gameplay): scale so midday overworld matches 117 HD's OVERWORLD environment (ambient #97baff ×1, sun ×4 after their normalisation) within ~10%, measured on flat ground.
5. AreaAmbience (port 061bcf4a): per-area multipliers (ambient, sun, sky tint; later fog/haze) blended spatially: 16-tile box around the exact focal point over a world-anchored 2-tile grid, cells cached per scene+plane. Cave↔overworld classification change: crossfade ~1 s (old code snapped). Teleport ≥ 41 tiles / scene load: snap. Areas come from the ported 117 HD areas; per-area colour data from 117 HD environments.json mapped onto multipliers (sun angles ignored).
6. SkyLight: L1 spherical harmonics (4 vec3) of the analytic sky gradient + glow, integrated on the CPU (~64 dirs) when alt changes > 0.25° or the look changes; shader adds SH(n) as the sky fill (replaces RuneLight's irradiance cache until clouds exist).
7. SkyRenderer: fullscreen triangle after opaque geometry at depth = far with LEQUAL, no depth write: gradient, sun glow, sun/moon discs, below-horizon void. Stars/clouds are later segments.
8. Shader seam (scene frag): light = ambient·area + SH(n) + caster·max(N·L,0)·(1-shadow) (+ transmission for flagged leaves). Output transform: linear -> sRGB with a soft shoulder so stacked light doesn't clip hues.

## Shadows
1. ShadowFit: light-space square centred on the player's tile height, snapped to chunk steps (1024 units), considered stale at half a chunk; texel snap; fixed depth range from scene height bounds plus margin for the 30° clamp. Camera movement never changes the fit.
2. ShadowCache (static): two depth slots (2048² default, 4096² option). Build draws zones (all levels minLevel..3, empty hidden-roof set → roofs always cast, roof visibility never a trigger), opaque only, amortised over 4 frames into the back slot, then swap. Re-reads ctx.zones each frame (zones can be replaced by rebuild). Triggers: zone rebuild, scene swap (shift focus by base delta, keep showing, rebuild behind), fit cell crossed, sun moved ≥ 1 texel for a 512-unit caster (old criterion), minLevel or caster kind change. First map after load fades in from lit.
3. Crossfade: sample both slots and mix over ~0.3 s for steps > 1 texel (handoff, time jump, zone/minLevel change, swap); sub-texel sun steps swap without fade.
4. Frame order change (prerequisite): zone draws are recorded in drawZoneOpaque/drawZoneAlpha instead of issued, then replayed at PASS_OPAQUE after the shadow passes, so the forward shader samples this frame's dynamic shadows. Also gives contiguous GPU passes for honest timing.
5. DynamicShadowMap: separate 2048² map, same texel density, cleared each frame, dynamic casters (NPCs, players, animated objects, boats) from this frame's VAO ranges recorded before reset. Receiver = min(static, dynamic). No per-frame static copy.
6. Off-screen casters: set ZBUF_ZONE_FRUSTUM_CHECK and implement zoneInFrustum: true for zones in the camera frustum or in the light caster volume; zones that are shadow-only skip scene draws; their dynamic models go to shadow-only VAOs. (Verify in game the client delivers off-screen models for accepted zones.)
7. ShadowTiles: RG32F min/max tile map per static build (max nearer than receiver → fully shadowed; min farther than receiver-bias across the blocker search reach → lit); per-frame min reduction for the dynamic map.
8. Filtering: PCSS, old constants (normal offset 1.5 texels, bias 2 texels, 12 search taps over 160 units, penumbra = gap × 0.08 sun / 0.14 moon clamped [1 texel, 112 units], 16 compare taps, 8 below 2 texels), evaluated forward at full resolution, tap rotation keyed to light-space texel/world cell (stable under camera motion, no crawl, no denoise pass needed). Revisit half-res + denoise only if grain shows, once SSAO brings a depth prepass. Leaves: flip normal and sample (no N·L skip); emissives don't receive.
9. Memory: 2×16 MB + 16 MB at 2048² (+ tiles). GPU estimate: static build 0.5–1 ms spread over 4 frames only on rebuilds; dynamic map + reduction 0.1–0.3 ms; receivers 1–3 fetches typical, 28 in penumbra.

## Revisions after adversarial review (supersede the above where they conflict)
- Frame order (S5): first log one frame's callback order with a boat on screen. Record every draw for every worldview, opaque AND alpha (like 117 HD), replay at the top-level postSceneDraw; move removeTemp after the replay. Per draw capture: Zone ref, zx/zz, min/level/max, hideRoofIds ref, entity projection copy, tint. Split VAOList.draw into unmap / draw(ranges) / reset so shadow and scene passes draw the same ranges. Shadow passes save/restore FBO, viewport, program, depth func, blend (world uses reverse-Z). Pass Zone/VAO a uniform-location struct instead of swapping fields.
- Fit: radius R = drawDistance + 1 chunk, capped at 90 tiles (2048²: ~18.6 texels/tile at R=50, ~10.7 at 90). Centre on the camera focal point; snap the centre at a fixed y (scene-average height) so walking uphill doesn't re-fit.
- Static build zone selection: project each zone's AABB (real minY/maxY) into light space and test against the map rectangle (fixed xz margins miss tall casters).
- Tiles: dilate BOTH min and max by the filter reach (old buildMax did), else hard tile seams in penumbrae.
- Dynamic map: no GPU min-reduction; build its coverage mask on the CPU from each dynamic model's bounding sphere projected into light space; skip clear + sampling when there are no casters. Coverage limited to what the client delivers.
- PCSS tap rotation: rotation cell chosen from fwidth(lightUV), power-of-two cell ≥ one pixel footprint (no distance shimmer). Follow-up if grain remains: precomputed average-blocker-depth map per static build + fixed Vogel pattern.
- Crossfade: sun steps never crossfade (≈17% of penumbra, invisible with PCSS); only content changes (zones, minLevel, caster kind, time jumps). Before PCSS lands, long hard-PCF shadows will step ~1/s (accepted for that interim segment).
- Zone invalidations (doors, trees, rocks): re-render only the changed zone's light-space rectangle (scissor, clear, redraw overlapping zones) instead of a full rebuild + fade. If a zone already drawn into the back slot is replaced mid-build, restart that build.
- Animated scenery comes through drawDynamic: lives in the dynamic map only (shadow follows camera visibility) — known limitation.
- S9 (zoneInFrustum off-screen casters): deferred indefinitely (Lumina would own camera culling; client delivers every model in accepted zones; boats never helped; NPC shadows < 3 tiles at ≥30°).
- Sky pass: reverse-Z → far is 0: test GEQUAL/EQUAL at 0. Fog colour must come from SkyFrame horizon (else a band at the draw-distance edge). Sky yields to scene.getSkybox() and black-sky areas.
- Output shoulder: luminance/max-channel shoulder preserving colour ratios, knee ≥ 0.8. Match brightness vs 117 HD on flat ground AND a vertical wall.
- Day length: night is ~45% of the cycle → default 2 h (24 min as an option) or fixed hour [open: user's call]. Clamp mismatch accepted (azimuth matches; SH glow gives the low-sun cue). Dead band carried by ambient + SH incl. twilight glow.
- SH: keep L1; add a ground-bounce term for downward normals; clamp result ≥ 0.
- Add: texture alpha test in the shadow fragment shader (fences, foliage cutouts); boats' hulls drawn into the dynamic map each frame with the entity projection; exclusion rule for spotanims/projectiles (drawTemp); skip shadow builds where the area's sun multiplier is 0 (caves); measure the dynamic pass in a crowd (GE); settings: shadow distance, resolution, softness, day length, fixed hour, off.

## Segments (each: implement, code review, in-game check, measure, commit)
- S1 shading port (in progress) + exact unlit terrain colour (recompute from floor definitions).
- S2 sky core: GameClock, Celestial, SkyModel, LightingState UBO, scene shader seam (ambient + caster), brightness match.
- S3 sky pass (SkyRenderer) + SkyLight SH.
- S4 AreaAmbience spatial blending.
- S5 frame recording/replay (no visual change; verify identical output + timings).
- S6 shadow fit + static cache + hard PCF.
- S7 crossfade + dynamic map.
- S8 PCSS + tiles.
- S9 off-screen casters (zoneInFrustum).
- Later: stars, clouds + sky capture, weather via SkyModifiers, leaves/emissives material bits.
