/**
 * Lumina's own sky and day cycle, the first {@code lighting.LightingSource} after 117 HD's. One global clock, sun
 * and moon; areas never move them.
 * <ul>
 * <li>{@link com.midonarhel.lumina.sky.GameClock}: the game's time of day as a function of the real time (the old
 * renderer's, with the day length and fixed hour eased over a few seconds when they change)</li>
 * <li>{@link com.midonarhel.lumina.sky.Celestial}: the visual sun and moon, and the light that casts shadows (the
 * caster: sun or moon, elevation clamped, with the handoff dead band)</li>
 * <li>{@link com.midonarhel.lumina.sky.SkyModel}: the palette and light colours by sun altitude (the old renderer's
 * sky.glsl on the CPU), per area through {@link com.midonarhel.lumina.sky.AreaLook} and for the weather later through
 * {@link com.midonarhel.lumina.sky.SkyModifiers}; the result is a {@link com.midonarhel.lumina.sky.SkyFrame}</li>
 * <li>{@link com.midonarhel.lumina.sky.SkyLight}: the sky's light on surfaces as L1 spherical harmonics of the sky
 * frame, integrated on the CPU</li>
 * <li>{@link com.midonarhel.lumina.sky.SkySource}: fills {@code lighting.Lighting} from the above each frame</li>
 * </ul>
 * The sky background itself is drawn by {@code frame.SkyRenderer} from the same {@code Lighting} values. The shadow
 * module (later) reads {@code Celestial}'s caster direction, kind and epoch.
 */
package com.midonarhel.lumina.sky;
