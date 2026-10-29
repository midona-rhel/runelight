/**
 * The scene light the shaders read. {@link com.midonarhel.lumina.lighting.Lighting} is the frame's light, fog and
 * sky values, filled once per frame by the {@link com.midonarhel.lumina.lighting.LightingSource} the "Lighting
 * source" setting picks and uploaded unchanged by {@code frame.LightingUniforms}. The sources:
 * {@link com.midonarhel.lumina.lighting.SkyLighting}, 117 HD's light from the environment, the sky preset and the
 * daylight cycle (its src/main/java/rs117/hd/renderer/SkyRenderer.java and the per-frame part of
 * renderer/zone/ZoneRenderer.java), and {@code sky.SkySource}, Lumina's own sun, moon and sky.
 * <p>
 * A source says through {@code Lighting.luminaLighting} which lighting seam the scene shader composites with and
 * which sky pass the frame draws; everything else downstream is the same for both.
 */
package com.midonarhel.lumina.lighting;
