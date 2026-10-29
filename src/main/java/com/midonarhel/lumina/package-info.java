/**
 * Lumina: RuneLite's GPU plugin with 117 HD's shading on top. {@link com.midonarhel.lumina.LuminaPlugin} is the
 * plugin lifecycle only: it creates the GL context, wires the parts below together, applies setting changes and
 * forwards the client's draw callbacks. The shader files live in this package's resources.
 * <ul>
 * <li>{@code frame}: the per-frame render path and its GL resources</li>
 * <li>{@code scene}: scene loading, zone upload and the 117 HD data the uploads read</li>
 * <li>{@code shading}: where a vertex colour or normal is decided</li>
 * <li>{@code lighting}: the scene light the shaders read, and what decides it</li>
 * <li>{@code config}, {@code dev}, {@code util}: settings, developer tools, shared helpers</li>
 * </ul>
 */
package com.midonarhel.lumina;
