/**
 * The scene light the shaders read. {@link com.midonarhel.lumina.lighting.Lighting} is the frame's light, fog and
 * sky values, filled once per frame by the one {@link com.midonarhel.lumina.lighting.LightingSource} the frame
 * holds and uploaded unchanged by {@code frame.LightingUniforms}. {@link com.midonarhel.lumina.lighting.SkyLighting}
 * is today's source: 117 HD's light from the environment, the sky preset and the daylight cycle (its
 * src/main/java/rs117/hd/renderer/SkyRenderer.java and the per-frame part of renderer/zone/ZoneRenderer.java).
 * <p>
 * This is the seam for later sources (Lumina's own sky or day cycle): implement {@code LightingSource}, hand it to
 * the frame, and nothing downstream changes.
 */
package com.midonarhel.lumina.lighting;
