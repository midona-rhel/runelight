/**
 * The per-scene terrain data generated on the map-load thread before a scene's zones are uploaded: the tile override
 * of every tile, the smoothed terrain normals and the overlay/underlay marks of terrain vertices (117 HD's
 * ProceduralGenerator without water, underwater terrain and ground materials). Written into {@code scene.SceneData},
 * read by {@code scene.SceneUploader}.
 * <ul>
 * <li>{@code TerrainGenerator.java}, {@code TerrainVertices.java}: split from 117 HD's src/main/java/rs117/hd/scene/ProceduralGenerator.java</li>
 * <li>{@code SkyCover.java}: Lumina's own; the sky visibility of every tile corner (roofs, upper floors and walls
 * from the map data, propagated from the open tiles), baked by {@code scene.SceneLoader} once the wall openings
 * are resolved on the client thread</li>
 * </ul>
 */
package com.midonarhel.lumina.scene.terrain;
