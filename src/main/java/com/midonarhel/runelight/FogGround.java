package com.midonarhel.runelight;

import net.runelite.api.Constants;
import net.runelite.api.Perspective;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;

/**
 * The height of the ground over the scene, for the volumetric fog to measure its height from (fog_frag.glsl): fog
 * lies on the terrain instead of on one flat plane through the player, so climbing a hill neither thickens the fog
 * below nor thins it above.
 *
 * One value per tile corner (so the texture's bilinear filter gives the terrain's own slopes) for each plane the
 * player can stand on: the floor that plane sees at each spot, which is the highest of the planes up to it with a
 * tile there, a bridge deck over what is under it. Water tiles hold the surface height, so fog lies on lakes.
 */
final class FogGround
{
	static final int PLANES = Constants.MAX_Z;
	static final int CORNERS = WaterMap.CORNERS;
	/** Texture width: the planes' pages side by side. */
	static final int WIDTH = PLANES * CORNERS;
	// corners per side of the blocks that hold the highest ground, for the fog's per-frame bound
	private static final int BLOCK = 8;
	private static final int BLOCKS = (CORNERS + BLOCK - 1) / BLOCK;
	// an overlay's hidden tile (SceneTilePaint colors)
	private static final int HIDDEN_HSL = 12345678;

	// world y of the ground, row by row, the planes side by side
	final float[] texture = new float[WIDTH * CORNERS];
	// per plane and block, the smallest y (the highest ground) in it
	private final float[][] blockTop = new float[PLANES][BLOCKS * BLOCKS];

	static FogGround build(Scene scene)
	{
		FogGround g = new FogGround();
		g.load(scene.getExtendedTiles(), scene.getTileHeights());
		return g;
	}

	private void load(Tile[][][] tiles, int[][][] heights)
	{
		final int size = WaterMap.SIZE;
		// per tile, the render level of its floor, -1 for none
		byte[] floor = new byte[size * size];
		for (int plane = 0; plane < PLANES; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					floor[y * size + x] = (byte) floorLevel(tiles, plane, x, y);
				}
			}

			float[] top = blockTop[plane];
			java.util.Arrays.fill(top, Float.MAX_VALUE);
			for (int cy = 0; cy < CORNERS; ++cy)
			{
				for (int cx = 0; cx < CORNERS; ++cx)
				{
					// the highest of the floors meeting at the corner
					float h = Float.MAX_VALUE;
					for (int ty = Math.max(cy - 1, 0); ty <= Math.min(cy, size - 1); ++ty)
					{
						for (int tx = Math.max(cx - 1, 0); tx <= Math.min(cx, size - 1); ++tx)
						{
							int level = floor[ty * size + tx];
							if (level >= 0)
							{
								h = Math.min(h, heights[level][cx][cy]);
							}
						}
					}
					if (h == Float.MAX_VALUE)
					{
						// nothing loaded here
						h = heights[0][cx][cy];
					}
					texture[cy * WIDTH + plane * CORNERS + cx] = h;
					int b = cy / BLOCK * BLOCKS + cx / BLOCK;
					top[b] = Math.min(top[b], h);
				}
			}
		}
	}

	/**
	 * Render level of the floor a player on {@code plane} has at tile (x, y): the highest plane up to theirs with a
	 * visible floor tile there, or -1.
	 */
	private static int floorLevel(Tile[][][] tiles, int plane, int x, int y)
	{
		for (int z = Math.min(plane, tiles.length - 1); z >= 0; --z)
		{
			Tile t = tiles[z][x][y];
			if (t == null)
			{
				continue;
			}
			Tile bridge = t.getBridge();
			if (bridge != null)
			{
				t = bridge;
			}
			SceneTilePaint paint = t.getSceneTilePaint();
			if (paint != null && paint.getNeColor() != HIDDEN_HSL || t.getSceneTileModel() != null)
			{
				return t.getRenderLevel();
			}
		}
		return -1;
	}

	/**
	 * World y of the highest ground on {@code plane} within the square [minX, maxX] x [minZ, maxZ] (scene-local world
	 * units), to the precision of {@link #BLOCK}-tile blocks: the fog's layer has no use above it.
	 */
	float highest(int plane, float minX, float minZ, float maxX, float maxZ)
	{
		final float tile = Perspective.LOCAL_TILE_SIZE;
		int off = RuneLightPlugin.SCENE_OFFSET;
		int bx0 = block(minX / tile + off), bx1 = block(maxX / tile + off);
		int bz0 = block(minZ / tile + off), bz1 = block(maxZ / tile + off);
		float[] top = blockTop[Math.min(Math.max(plane, 0), PLANES - 1)];
		float best = Float.MAX_VALUE;
		for (int bz = bz0; bz <= bz1; ++bz)
		{
			for (int bx = bx0; bx <= bx1; ++bx)
			{
				best = Math.min(best, top[bz * BLOCKS + bx]);
			}
		}
		return best;
	}

	private static int block(float corner)
	{
		return Math.min(Math.max((int) Math.floor(corner), 0), CORNERS - 1) / BLOCK;
	}
}
