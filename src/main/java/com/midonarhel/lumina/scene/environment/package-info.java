/**
 * 117 HD's environments: the ambient and directional light, fog, underglow and sky of each area, and the manager
 * that picks the environment at the camera focal point and transitions between them. The classes keep 117 HD's
 * names and BSD headers and the data file is kept as 117 HD's, so upstream data updates stay diffable.
 * <ul>
 * <li>{@code Environment.java}: 117 HD's src/main/java/rs117/hd/scene/environments/Environment.java</li>
 * <li>{@code EnvironmentManager.java}: 117 HD's src/main/java/rs117/hd/scene/EnvironmentManager.java</li>
 * <li>{@code resources/.../scene/environments.json}: 117 HD's src/main/resources/rs117/hd/scene/environments.json</li>
 * </ul>
 * Read by {@code lighting.SkyLighting}, which turns the current environment into the frame's light.
 */
package com.midonarhel.lumina.scene.environment;
