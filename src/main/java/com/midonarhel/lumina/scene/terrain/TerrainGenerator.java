/*
 * Copyright (c) 2021, 117 <https://twitter.com/117scape>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.midonarhel.lumina.scene.terrain;

import com.midonarhel.lumina.scene.SceneData;
import com.midonarhel.lumina.scene.overrides.TileOverride;
import com.midonarhel.lumina.scene.overrides.TileOverrideManager;
import com.midonarhel.lumina.shading.DiffuseColors;
import com.midonarhel.lumina.util.IntIntMap;
import java.util.Arrays;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import static com.midonarhel.lumina.scene.SceneData.HIDDEN_HSL;
import static com.midonarhel.lumina.scene.SceneData.TILE_OVERRIDE_COUNT;
import static com.midonarhel.lumina.scene.SceneData.TILE_OVERRIDE_MAIN;
import static com.midonarhel.lumina.scene.SceneData.TILE_OVERRIDE_OVERLAY;
import static com.midonarhel.lumina.scene.SceneData.TILE_OVERRIDE_UNDERLAY;
import static com.midonarhel.lumina.scene.overrides.TileOverride.OVERLAY_FLAG;
import static net.runelite.api.Constants.MAX_Z;

/**
 * Generates a scene's shading data on the map-load thread, before its zones are uploaded (117 HD's
 * ProceduralGenerator, without water, underwater terrain or ground materials):
 * <ol>
 * <li>the tile override of every tile (main, underlay and overlay); none in the Vanilla colour mode,</li>
 * <li>smoothed terrain normals: the sum of the face normals around each terrain vertex,</li>
 * <li>the overlay/underlay marks of terrain vertices (TerrainDataGenerator's setVertexIsOverlay), which the scene
 * shader's overlay/underlay blend reads. 117 HD's shared (blended) vertex colours are not generated: with ground
 * blending off, which Lumina always is, 117 HD does not use them.</li>
 * </ol>
 */
@Slf4j
public final class TerrainGenerator
{
	private static final int VERTICES_PER_FACE = 3;

	private final TileOverrideManager tileOverrideManager;

	private final int[][] vertices = new int[4][3];
	private final int[] keys = new int[4];
	private final int[] worldPos = new int[3];
	private final TileOverride[] overrides = new TileOverride[TILE_OVERRIDE_COUNT];
	private final short[] normal = new short[3];
	private final int[] surfaceNormal = new int[3];

	private int[][][] faceVertices = new int[2][VERTICES_PER_FACE][3];
	private int[][] faceVertexKeys = new int[2][VERTICES_PER_FACE];
	private int[] vertexNormals;
	private int vertexNormalsPos;

	private int[] vertexKeys = new int[6];
	private int[] vertexColors = new int[6];
	private TileOverride[] vertexOverrides = new TileOverride[6];
	private boolean[] vertexIsOverlay = new boolean[6];
	private boolean[] vertexDefaultColor = new boolean[6];

	public TerrainGenerator(TileOverrideManager tileOverrideManager)
	{
		this.tileOverrideManager = tileOverrideManager;
	}

	public void generate(SceneData data)
	{
		long start = System.nanoTime();
		generateTileOverrides(data);
		long overridesDone = System.nanoTime();
		generateNormals(data);
		long normalsDone = System.nanoTime();
		generateOverlayFlags(data);
		long end = System.nanoTime();
		log.debug("Terrain data: overrides {} ms, normals {} ms, overlay flags {} ms",
			(overridesDone - start) / 1_000_000, (normalsDone - overridesDone) / 1_000_000, (end - normalsDone) / 1_000_000);
	}

	/**
	 * Whether a tile keeps its own colour instead of blending with its neighbours (117 HD's useDefaultColor).
	 */
	public static boolean useDefaultColor(Tile tile, TileOverride override)
	{
		if ((tile.getSceneTilePaint() != null && tile.getSceneTilePaint().getTexture() >= 0) ||
			(tile.getSceneTileModel() != null && tile.getSceneTileModel().getTriangleTextureId() != null))
		{
			return true; // skip tiles with textures provided by default
		}

		if (override == TileOverride.NONE)
		{
			return false;
		}

		return !override.blended;
	}

	private void generateTileOverrides(SceneData data)
	{
		data.tileOverrides = tileOverrideManager.getOverrides();
		if (DiffuseColors.useUnlitTerrainColors())
		{
			// Vanilla colour mode: no tile overrides (every tile keeps TileOverride.NONE)
			return;
		}

		final Scene scene = data.scene;
		final Tile[][][] tiles = scene.getExtendedTiles();
		final short[][][] overlayIds = scene.getOverlayIds();
		final short[][][] underlayIds = scene.getUnderlayIds();

		for (int z = 0; z < MAX_Z; ++z)
		{
			for (int x = 0; x < data.sizeX; ++x)
			{
				for (int y = 0; y < data.sizeZ; ++y)
				{
					final Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}

					calculateTileOverride(data, tile, x, y, overlayIds, underlayIds);

					final Tile bridge = tile.getBridge();
					if (bridge != null)
					{
						calculateTileOverride(data, bridge, x, y, overlayIds, underlayIds);
					}
				}
			}
		}
	}

	private void calculateTileOverride(SceneData data, Tile tile, int tileExX, int tileExY, short[][][] overlayIds, short[][][] underlayIds)
	{
		int tileZ = tile.getRenderLevel();
		int overlayId = OVERLAY_FLAG | overlayIds[tileZ][tileExX][tileExY];
		int underlayId = underlayIds[tileZ][tileExX][tileExY];

		data.extendedSceneToWorld(tileExX, tileExY, tileZ, worldPos);

		overrides[TILE_OVERRIDE_MAIN] = tileOverrideManager.getOverride(tile, worldPos, overlayId, underlayId);
		overrides[TILE_OVERRIDE_UNDERLAY] = tileOverrideManager.getOverride(tile, worldPos, underlayId);
		overrides[TILE_OVERRIDE_OVERLAY] = tileOverrideManager.getOverride(tile, worldPos, overlayId);

		data.setTileOverride(tileZ, tileExX, tileExY, overrides);
	}

	private void generateNormals(SceneData data)
	{
		final Tile[][][] tiles = data.scene.getExtendedTiles();

		data.vertexTerrainNormalIndices = new IntIntMap(1 << 16);
		vertexNormals = new int[3 * (1 << 16)];
		vertexNormalsPos = 0;

		for (int z = 0; z < MAX_Z; z++)
		{
			for (int x = 0; x < data.sizeX; x++)
			{
				for (int y = 0; y < data.sizeZ; y++)
				{
					final Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}

					if (tile.getBridge() != null)
					{
						calculateNormalsForTile(data, tile.getBridge());
					}
					calculateNormalsForTile(data, tile);
				}
			}
		}

		short[] normals = new short[vertexNormalsPos];
		for (int offset = 0; offset < vertexNormalsPos; offset += 3)
		{
			final float x = vertexNormals[offset];
			final float y = vertexNormals[offset + 1];
			final float z = vertexNormals[offset + 2];

			final float len = x * x + y * y + z * z;
			if (len == 0)
			{
				continue;
			}

			final float invLen = 1 / (float) Math.sqrt(len);
			normals[offset] = normShort(x * invLen);
			normals[offset + 1] = normShort(y * invLen);
			normals[offset + 2] = normShort(z * invLen);
		}
		data.vertexTerrainNormals = normals;
		vertexNormals = null;
	}

	private static short normShort(float v)
	{
		return (short) Math.round(Math.max(-1, Math.min(1, v)) * Short.MAX_VALUE);
	}

	/**
	 * Accumulates the face normals of a tile into its vertices.
	 */
	private void calculateNormalsForTile(SceneData data, Tile tile)
	{
		int faceCount = 2;
		final SceneTileModel tileModel = tile.getSceneTileModel();
		if (tileModel != null)
		{
			faceCount = tileModel.getFaceX().length;
			if (faceVertices.length < faceCount)
			{
				faceVertices = new int[faceCount][VERTICES_PER_FACE][3];
				faceVertexKeys = new int[faceCount][VERTICES_PER_FACE];
			}

			for (int face = 0; face < faceCount; face++)
			{
				TerrainVertices.faceVertexKeys(tile, face, vertices, keys);
				// Winding order: 0, 1, 2
				for (int v = 0; v < 3; v++)
				{
					System.arraycopy(vertices[v], 0, faceVertices[face][v], 0, 3);
					faceVertexKeys[face][v] = keys[v];
				}
			}
		}
		else
		{
			TerrainVertices.tileVertexKeys(data, tile, vertices, keys);

			// Winding order: ne, nw, se => 3, 2, 1
			System.arraycopy(vertices[3], 0, faceVertices[0][0], 0, 3);
			System.arraycopy(vertices[2], 0, faceVertices[0][1], 0, 3);
			System.arraycopy(vertices[1], 0, faceVertices[0][2], 0, 3);

			// Winding order: sw, se, nw => 0, 1, 2
			System.arraycopy(vertices[0], 0, faceVertices[1][0], 0, 3);
			System.arraycopy(vertices[1], 0, faceVertices[1][1], 0, 3);
			System.arraycopy(vertices[2], 0, faceVertices[1][2], 0, 3);

			// Each face adds its normal to all three of its vertices, so only the set of keys matters here
			faceVertexKeys[0][0] = keys[3];
			faceVertexKeys[0][1] = keys[1];
			faceVertexKeys[0][2] = keys[2];
			faceVertexKeys[1][0] = keys[0];
			faceVertexKeys[1][1] = keys[2];
			faceVertexKeys[1][2] = keys[1];
		}

		for (int face = 0; face < faceCount; face++)
		{
			int[] a = faceVertices[face][0];
			int[] b = faceVertices[face][1];
			int[] c = faceVertices[face][2];
			// cross(b - a, c - a)
			int e0x = b[0] - a[0], e0y = b[1] - a[1], e0z = b[2] - a[2];
			int e1x = c[0] - a[0], e1y = c[1] - a[1], e1z = c[2] - a[2];
			surfaceNormal[0] = e0y * e1z - e0z * e1y;
			surfaceNormal[1] = e0z * e1x - e0x * e1z;
			surfaceNormal[2] = e0x * e1y - e0y * e1x;

			for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
			{
				final int key = faceVertexKeys[face][vertex];
				final int index = data.vertexTerrainNormalIndices.getOrDefault(key, -1);
				if (index == -1)
				{
					data.vertexTerrainNormalIndices.put(key, vertexNormalsPos / 3);
					if (vertexNormalsPos + 3 > vertexNormals.length)
					{
						vertexNormals = Arrays.copyOf(vertexNormals, vertexNormals.length * 2);
					}
					vertexNormals[vertexNormalsPos++] = surfaceNormal[0];
					vertexNormals[vertexNormalsPos++] = surfaceNormal[1];
					vertexNormals[vertexNormalsPos++] = surfaceNormal[2];
				}
				else
				{
					final int offset = index * 3;
					vertexNormals[offset] += surfaceNormal[0];
					vertexNormals[offset + 1] += surfaceNormal[1];
					vertexNormals[offset + 2] += surfaceNormal[2];
				}
			}
		}
	}

	private void generateOverlayFlags(SceneData data)
	{
		data.vertexTerrainFlags = new IntIntMap(1 << 16);

		final Tile[][][] tiles = data.scene.getExtendedTiles();
		for (int z = 0; z < MAX_Z; ++z)
		{
			for (int x = 0; x < data.sizeX; ++x)
			{
				for (int y = 0; y < data.sizeZ; ++y)
				{
					final Tile tile = tiles[z][x][y];
					if (tile == null)
					{
						continue;
					}

					generateOverlayFlagsForTile(data, tile, x, y);

					final Tile bridge = tile.getBridge();
					if (bridge != null)
					{
						generateOverlayFlagsForTile(data, bridge, x, y);
					}
				}
			}
		}
	}

	private void ensureCapacity(int size)
	{
		if (vertexKeys.length < size)
		{
			vertexKeys = new int[size];
			vertexColors = new int[size];
			vertexOverrides = new TileOverride[size];
			vertexIsOverlay = new boolean[size];
			vertexDefaultColor = new boolean[size];
		}
	}

	/**
	 * Marks each vertex of a tile as overlay or underlay, as 117 HD's TerrainDataGenerator does (the same vertices it
	 * would give a colour; the colour itself is unused with ground blending off and not computed).
	 */
	private void generateOverlayFlagsForTile(SceneData data, Tile tile, int tileExX, int tileExY)
	{
		int faceCount;
		final SceneTilePaint tilePaint = tile.getSceneTilePaint();
		final SceneTileModel tileModel = tilePaint == null ? tile.getSceneTileModel() : null;
		if (tilePaint != null)
		{
			faceCount = 2;
		}
		else if (tileModel != null)
		{
			faceCount = tileModel.getFaceX().length;
		}
		else
		{
			return;
		}

		final int tileZ = tile.getRenderLevel();
		final int vertexCount = faceCount * VERTICES_PER_FACE;
		ensureCapacity(vertexCount);
		Arrays.fill(vertexKeys, 0, vertexCount, 0);
		Arrays.fill(vertexColors, 0, vertexCount, 0);
		Arrays.fill(vertexOverrides, 0, vertexCount, null);
		Arrays.fill(vertexIsOverlay, 0, vertexCount, false);
		Arrays.fill(vertexDefaultColor, 0, vertexCount, false);

		if (tilePaint != null)
		{
			var override = data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_MAIN);
			if (override.isWater())
			{
				return; // skip water tiles
			}

			int swColor = tilePaint.getSwColor();
			int seColor = tilePaint.getSeColor();
			int nwColor = tilePaint.getNwColor();
			int neColor = tilePaint.getNeColor();

			TerrainVertices.tileVertexKeys(data, tile, vertices, vertexKeys);

			vertexColors[0] = swColor;
			vertexColors[1] = seColor;
			vertexColors[2] = nwColor;
			vertexColors[3] = neColor;

			final boolean defaultColor = useDefaultColor(tile, override);
			for (int i = 0; i < 4; i++)
			{
				vertexOverrides[i] = override;
				vertexIsOverlay[i] = override.queriedAsOverlay;
				vertexDefaultColor[i] = defaultColor;
			}
		}
		else
		{
			final int[] faceColorsA = tileModel.getTriangleColorA();
			final int[] faceColorsB = tileModel.getTriangleColorB();
			final int[] faceColorsC = tileModel.getTriangleColorC();

			var overlayOverride = data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_OVERLAY);
			var underlayOverride = data.getTileOverride(tileZ, tileExX, tileExY, TILE_OVERRIDE_UNDERLAY);

			for (int face = 0; face < faceCount; face++)
			{
				TerrainVertices.faceVertexKeys(tile, face, vertices, keys);
				final boolean isOverlay = TerrainVertices.isOverlayFace(tile, face);
				final var override = isOverlay ? overlayOverride : underlayOverride;
				if (override.isWater())
				{
					continue; // skip water faces
				}

				for (int vertex = 0; vertex < VERTICES_PER_FACE; vertex++)
				{
					final int base = face * VERTICES_PER_FACE + vertex;
					vertexKeys[base] = keys[vertex];
					vertexColors[base] = (vertex == 0 ? faceColorsA : vertex == 1 ? faceColorsB : faceColorsC)[face];
					vertexOverrides[base] = override;
					vertexIsOverlay[base] = isOverlay;
					vertexDefaultColor[base] = isOverlay && useDefaultColor(tile, override);
				}
			}
		}

		for (int vertex = 0; vertex < vertexCount; vertex++)
		{
			if (vertexKeys[vertex] == 0)
			{
				continue;
			}

			int color = vertexColors[vertex];
			var override = vertexOverrides[vertex];
			if (color < 0 || color == HIDDEN_HSL && !override.forced)
			{
				continue;
			}

			// mark the vertex as either an overlay or underlay.
			// this is used to determine how to blend between vertex colors
			final boolean isOverlay = vertexIsOverlay[vertex] != override.blendedAsOpposite;
			data.setVertexIsOverlay(vertexKeys[vertex], isOverlay);
		}
	}
}
