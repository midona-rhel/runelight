/**
 * Sun and moon shadows for Lumina's lighting: orthographic depth maps from the caster the sky module picks
 * ({@code sky.Celestial}), sampled by the scene shader's Lumina branch ({@code shadow.glsl}) with hardware PCF.
 * Nothing here runs under 117 HD's lighting source.
 * <ul>
 * <li>{@link com.midonarhel.lumina.shadow.ShadowFit}: the world-anchored light-space square a map covers (centre
 * snapped to chunk steps at a fixed height, radius from the draw distance, fixed depth range from the scene's
 * heights) and when a fit has gone stale; pure math</li>
 * <li>{@link com.midonarhel.lumina.shadow.ZoneBounds}: every zone's local bounding box from the scene's tile heights,
 * projected into a fit for the zone selection and the partial redraw rectangles; pure math</li>
 * <li>{@link com.midonarhel.lumina.shadow.StaticShadowMap}: two depth slots; the zones overlapping the fit are drawn
 * into the back slot over four frames and swapped in; a zone rebuilt under the front map is redrawn in place within
 * its light-space rectangle</li>
 * <li>{@link com.midonarhel.lumina.shadow.DynamicShadowMap}: the frame's dynamic models (players, NPCs, animated
 * objects) drawn every frame into their own map at the static map's texel density; receivers take the darker of the
 * two</li>
 * <li>{@link com.midonarhel.lumina.shadow.ShadowProgram}: the depth-only program ({@code shadow_vert.glsl}), one for
 * both vertex formats</li>
 * <li>{@link com.midonarhel.lumina.shadow.ShadowRenderer}: runs the passes at {@code frame.FrameRenderer}'s shadow
 * hook, before the frame's recorded draws are replayed, and fills the shadow fields of {@code lighting.Lighting}</li>
 * <li>{@link com.midonarhel.lumina.shadow.ShadowSettings}: the shadow settings, read by key</li>
 * </ul>
 */
package com.midonarhel.lumina.shadow;
