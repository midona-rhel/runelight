/**
 * The per-frame render path and the GL resources it owns. {@link com.midonarhel.lumina.frame.FrameRenderer} runs a
 * frame: the scene FBO with MSAA, the frame's uniforms (camera, projection, the {@code lighting} values, output
 * adjustments), the sky pass, the blit, the interface texture and the upscaled UI pass, the swap and the timers.
 * {@link com.midonarhel.lumina.frame.ZoneDrawer} records the zone draws the client asks for during its scene traversal
 * into the frame's {@link com.midonarhel.lumina.frame.DrawList} and uploads the dynamic and temporary models into the
 * {@link com.midonarhel.lumina.frame.RenderThreads}' VAO lists; the FrameRenderer replays the list at the top-level
 * post scene callback (the shadow passes run just before). {@link com.midonarhel.lumina.frame.SceneProgram} compiles
 * the shaders and holds their uniform locations; a {@link com.midonarhel.lumina.frame.DrawUniforms} per program
 * carries the per-draw ones.
 * <p>
 * Seams: the scene is read through {@code scene.SceneLoader} and {@code scene.Zone}; the light comes from one
 * {@code lighting.LightingSource}; the defines of the shaders come from the settings and {@code shading.DiffuseColors}.
 */
package com.midonarhel.lumina.frame;
