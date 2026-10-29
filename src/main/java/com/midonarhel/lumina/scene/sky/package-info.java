/**
 * 117 HD's sky presets and daylight cycle: the sky configuration of a preset or environment (gradients, moon,
 * night colours, sky fog) and the manager that resolves the sun and moon state of the frame from the time, the
 * cycle setting and the environment transition. The classes keep 117 HD's names and BSD headers and the data file
 * is kept as 117 HD's, so upstream data updates stay diffable.
 * <ul>
 * <li>{@code SkyConfiguration.java}, {@code SkyState.java}: 117 HD's src/main/java/rs117/hd/scene/daylight_cycle/</li>
 * <li>{@code SkyManager.java}: 117 HD's src/main/java/rs117/hd/scene/SkyManager.java (the sun and moon state only; no sky drawing)</li>
 * <li>{@code resources/.../scene/sky_presets.json}: 117 HD's src/main/resources/rs117/hd/scene/sky_presets.json</li>
 * </ul>
 * Read by {@code lighting.SkyLighting}.
 */
package com.midonarhel.lumina.scene.sky;
