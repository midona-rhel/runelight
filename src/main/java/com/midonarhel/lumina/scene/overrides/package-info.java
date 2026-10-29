/**
 * 117 HD's model and tile overrides: per model (by NPC, object, projectile or graphics object id, optionally per
 * area) the hidden faces, normals, colour and alpha changes, materials and UVs; per tile (by area and overlay or
 * underlay id) the colour change, blending and water type, with replacement expressions. The classes keep 117 HD's
 * names and BSD headers and the data files are kept as 117 HD's, so upstream data updates stay diffable.
 * <ul>
 * <li>{@code ModelOverride.java}, {@code InheritTileColorType.java}, {@code UvType.java}: 117 HD's src/main/java/rs117/hd/scene/model_overrides/</li>
 * <li>{@code ModelOverrideManager.java}: 117 HD's src/main/java/rs117/hd/scene/ModelOverrideManager.java</li>
 * <li>{@code TileOverride.java}, {@code TileOverrideVariables.java}: 117 HD's src/main/java/rs117/hd/scene/tile_overrides/</li>
 * <li>{@code TileOverrideManager.java}: 117 HD's src/main/java/rs117/hd/scene/TileOverrideManager.java</li>
 * <li>{@code resources/.../scene/model_overrides.json}, {@code tile_overrides.json}: 117 HD's src/main/resources/rs117/hd/scene/</li>
 * </ul>
 */
package com.midonarhel.lumina.scene.overrides;
