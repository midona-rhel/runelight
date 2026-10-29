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
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Point;
import net.runelite.api.SceneTileModel;
import net.runelite.api.Tile;
import static net.runelite.api.Constants.EXTENDED_SCENE_SIZE;
import static net.runelite.api.Constants.SCENE_SIZE;
import static net.runelite.api.Perspective.LOCAL_TILE_SIZE;

/**
 * Terrain vertex positions and the keys shared terrain data is stored under (117 HD's ProceduralGenerator helpers).
 * Vertices are in scene-local coordinates (not extended), Y down; a vertex key identifies a corner position, so
 * neighbouring tiles share data through it.
 */
@Slf4j
public final class TerrainVertices
{
	private static final int EXTENDED_SCENE_OFFSET = (EXTENDED_SCENE_SIZE - SCENE_SIZE) / 2;

	private static final boolean[][] TILE_OVERLAY_TRIS = new boolean[][]
		{
			/*  0 */ {true, true, true, true}, // Used by tile models of varying tri counts?
			/*  1 */ {false, true},
			/*  2 */ {false, false, true},
			/*  3 */ {false, false, true},
			/*  4 */ {false, true, true},
			/*  5 */ {false, true, true},
			/*  6 */ {false, false, true, true},
			/*  7 */ {false, false, false, true},
			/*  8 */ {false, true, true, true},
			/*  9 */ {false, false, false, true, true, true},
			/* 10 */ {true, true, true, false, false, false},
			/* 11 */ {true, true, false, false, false, false},
		};

	private TerrainVertices()
	{
	}

	public static int vertexKey(int[] vertex)
	{
		// Tile X and Z coordinates are always multiples of 32, so 10 bits is sufficient for 184 tiles.
		// The tile height does not strictly fit in 12 bits, so we allow collisions beyond 4096 units.
		return
			(vertex[1] & 0xFFF) << 20 |
			(vertex[2] + EXTENDED_SCENE_OFFSET * LOCAL_TILE_SIZE >> 5) << 10 |
			(vertex[0] + EXTENDED_SCENE_OFFSET * LOCAL_TILE_SIZE >> 5);
	}

	public static boolean isOverlayFace(Tile tile, int face)
	{
		int tileShapeIndex = tile.getSceneTileModel().getShape() - 1;
		if (tileShapeIndex < 0 || tileShapeIndex >= TILE_OVERLAY_TRIS.length)
		{
			return false;
		}
		boolean[] tris = TILE_OVERLAY_TRIS[tileShapeIndex];
		return face < tris.length && tris[face];
	}

	/**
	 * The four corners of a tile paint tile, in the order SW, SE, NW, NE.
	 */
	public static void tileVertices(SceneData data, Tile tile, int[][] vertices)
	{
		final Point tileLocation = tile.getSceneLocation();
		final int tileX = tileLocation.getX();
		final int tileY = tileLocation.getY();
		final int tileExX = tileX + data.sceneOffset;
		final int tileExY = tileY + data.sceneOffset;
		final int[][] tileHeights = data.scene.getTileHeights()[tile.getRenderLevel()];

		set(vertices[0], tileX * LOCAL_TILE_SIZE, tileHeights[tileExX][tileExY], tileY * LOCAL_TILE_SIZE);
		set(vertices[1], (tileX + 1) * LOCAL_TILE_SIZE, tileHeights[tileExX + 1][tileExY], tileY * LOCAL_TILE_SIZE);
		set(vertices[2], tileX * LOCAL_TILE_SIZE, tileHeights[tileExX][tileExY + 1], (tileY + 1) * LOCAL_TILE_SIZE);
		set(vertices[3], (tileX + 1) * LOCAL_TILE_SIZE, tileHeights[tileExX + 1][tileExY + 1], (tileY + 1) * LOCAL_TILE_SIZE);
	}

	/**
	 * Keys of the four corners of a tile paint tile, in the order SW, SE, NW, NE.
	 */
	public static void tileVertexKeys(SceneData data, Tile tile, int[][] vertices, int[] keys)
	{
		tileVertices(data, tile, vertices);
		keys[0] = vertexKey(vertices[0]);
		keys[1] = vertexKey(vertices[1]);
		keys[2] = vertexKey(vertices[2]);
		keys[3] = vertexKey(vertices[3]);
	}

	public static void faceVertices(Tile tile, int face, int[][] vertices)
	{
		SceneTileModel model = tile.getSceneTileModel();
		final int[] vertexX = model.getVertexX();
		final int[] vertexY = model.getVertexY();
		final int[] vertexZ = model.getVertexZ();
		final int a = model.getFaceX()[face];
		final int b = model.getFaceY()[face];
		final int c = model.getFaceZ()[face];
		set(vertices[0], vertexX[a], vertexY[a], vertexZ[a]);
		set(vertices[1], vertexX[b], vertexY[b], vertexZ[b]);
		set(vertices[2], vertexX[c], vertexY[c], vertexZ[c]);
	}

	public static void faceVertexKeys(Tile tile, int face, int[][] vertices, int[] keys)
	{
		faceVertices(tile, face, vertices);
		keys[0] = vertexKey(vertices[0]);
		keys[1] = vertexKey(vertices[1]);
		keys[2] = vertexKey(vertices[2]);
	}

	/**
	 * The lit colour of the south-western-most underlay corner of a tile, which tile override replacement
	 * expressions ({@code h}, {@code s}, {@code l}) test.
	 */
	public static int southWesternMostTileColor(Tile tile)
	{
		var paint = tile.getSceneTilePaint();
		var model = tile.getSceneTileModel();
		int hsl = 0;
		if (paint != null)
		{
			hsl = paint.getSwColor();
		}
		else if (model != null)
		{
			int faceCount = model.getFaceX().length;
			final int[] faceColorsA = model.getTriangleColorA();
			final int[] faceColorsB = model.getTriangleColorB();
			final int[] faceColorsC = model.getTriangleColorC();

			int baseX = tile.getSceneLocation().getX() * LOCAL_TILE_SIZE;
			int baseY = tile.getSceneLocation().getY() * LOCAL_TILE_SIZE;

			for (int face = 0; face < faceCount; face++)
			{
				if (isOverlayFace(tile, face))
				{
					continue;
				}

				final int a = model.getFaceX()[face];
				hsl = faceColorsA[face];
				if (model.getVertexX()[a] - baseX != LOCAL_TILE_SIZE && model.getVertexZ()[a] - baseY != LOCAL_TILE_SIZE)
				{
					break;
				}

				final int b = model.getFaceY()[face];
				hsl = faceColorsB[face];
				if (model.getVertexX()[b] - baseX != LOCAL_TILE_SIZE && model.getVertexZ()[b] - baseY != LOCAL_TILE_SIZE)
				{
					break;
				}

				final int c = model.getFaceZ()[face];
				hsl = faceColorsC[face];
				if (model.getVertexX()[c] - baseX != LOCAL_TILE_SIZE && model.getVertexZ()[c] - baseY != LOCAL_TILE_SIZE)
				{
					break;
				}
			}
		}
		return hsl;
	}

	private static void set(int[] v, int x, int y, int z)
	{
		v[0] = x;
		v[1] = y;
		v[2] = z;
	}
}
