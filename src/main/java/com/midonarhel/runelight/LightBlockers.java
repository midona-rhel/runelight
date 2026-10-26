package com.midonarhel.runelight;

import java.io.DataOutputStream;
import java.io.IOException;

/**
 * What stops a static light, as one scene's snapshot for the baking threads (LightMapBake): the walls and the tiles
 * a solid object fills (WallMap), the models at the light's height (ModelBlockers), and the floors of the storeys, for
 * the light that passes between them. Immutable once made; arrays are [plane][x][y] in extended scene tiles, like
 * RoomMap's, and cells are the light map's, a quarter tile each (CELLS_PER_TILE).
 */
final class LightBlockers
{
	static final int CELLS_PER_TILE = 4;

	final WallMap walls;
	// null without any
	final ModelBlockers models;
	final int size, offset, cells;
	// the tiles' floor heights (world y, down), RoomMap.floors' layout
	private final float[] floors;
	// per tile (x * size + y), a bit per storey that has a floor there: a roof is the floor of the storey over it
	private final byte[] filled;
	// per storey, the number of tiles with wall bits in the rectangle from tile (0, 0) to (x, y) exclusive: (size + 1)^2
	private final int[][] wallSums;

	LightBlockers(WallMap walls, ModelBlockers models, float[] floors, byte[] filled)
	{
		this.walls = walls;
		this.models = models;
		this.floors = floors;
		this.filled = filled;
		size = walls.size;
		offset = walls.offset;
		cells = size * CELLS_PER_TILE;
		wallSums = new int[WallMap.PLANES][];
		for (int plane = 0; plane < WallMap.PLANES; ++plane)
		{
			int[] sum = new int[(size + 1) * (size + 1)];
			for (int x = 0; x < size; ++x)
			{
				int row = 0;
				for (int y = 0; y < size; ++y)
				{
					row += walls.get(plane, x, y) != 0 ? 1 : 0;
					sum[(x + 1) * (size + 1) + y + 1] = sum[x * (size + 1) + y + 1] + row;
				}
			}
			wallSums[plane] = sum;
		}
	}

	/** The walls, the models, the floors and the storeys that have one, as the scene dump has them. */
	void write(DataOutputStream out) throws IOException
	{
		walls.write(out);
		out.writeBoolean(models != null);
		if (models != null)
		{
			models.write(out);
		}
		for (float f : floors)
		{
			out.writeFloat(f);
		}
		out.write(filled);
	}

	/** Whether a storey has a wall on a tile within the cell rectangle (inclusive, scene cells), or three tiles of it. */
	boolean anyWalls(int plane, int cx0, int cz0, int cx1, int cz1)
	{
		if (plane < 0 || plane >= WallMap.PLANES)
		{
			return false;
		}
		int x0 = Math.max(0, Math.floorDiv(cx0, CELLS_PER_TILE) - 3), x1 = Math.min(size - 1, Math.floorDiv(cx1, CELLS_PER_TILE) + 3);
		int z0 = Math.max(0, Math.floorDiv(cz0, CELLS_PER_TILE) - 3), z1 = Math.min(size - 1, Math.floorDiv(cz1, CELLS_PER_TILE) + 3);
		if (x0 > x1 || z0 > z1)
		{
			return false;
		}
		int[] sum = wallSums[plane];
		int n = size + 1;
		return sum[(x1 + 1) * n + z1 + 1] - sum[x0 * n + z1 + 1] - sum[(x1 + 1) * n + z0] + sum[x0 * n + z0] != 0;
	}

	/**
	 * WallMap.blocked, with the walk skipped when no tile it could read has a wall: the same answer, quicker over open
	 * ground (the walk reads the tiles on its way and the next one over, so the rectangle is padded by two).
	 */
	boolean blocked(int plane, float ax, float az, float bx, float bz)
	{
		if (plane < 0 || plane >= WallMap.PLANES)
		{
			return false;
		}
		int x0 = (int) Math.floor(Math.min(ax, bx) / 128.0 + offset) - 2, x1 = (int) Math.floor(Math.max(ax, bx) / 128.0 + offset) + 2;
		int z0 = (int) Math.floor(Math.min(az, bz) / 128.0 + offset) - 2, z1 = (int) Math.floor(Math.max(az, bz) / 128.0 + offset) + 2;
		x0 = Math.max(x0, 0);
		z0 = Math.max(z0, 0);
		x1 = Math.min(x1, size - 1);
		z1 = Math.min(z1, size - 1);
		if (x0 <= x1 && z0 <= z1)
		{
			int[] sum = wallSums[plane];
			int n = size + 1;
			if (sum[(x1 + 1) * n + z1 + 1] - sum[x0 * n + z1 + 1] - sum[(x1 + 1) * n + z0] + sum[x0 * n + z0] == 0)
			{
				return false;
			}
		}
		else
		{
			// wholly off the map: nothing to read but zeros
			return false;
		}
		return walls.blocked(plane, ax, az, bx, bz);
	}

	/**
	 * Whether a solid object stops the light on the cell, the whole of it. With the models known the object is what
	 * they say stands at the light's height (ModelBlockers.runs), not its whole tile: a crate that fills its tile to
	 * the knee does not shade a torch above it. So only without models, or for a storey other than the light's own
	 * (where no one height is the light's), it is the tile.
	 */
	boolean solid(int plane, int gx, int gz, boolean heightKnown)
	{
		return walls.solid(plane, Math.floorDiv(gx, CELLS_PER_TILE), Math.floorDiv(gz, CELLS_PER_TILE))
			&& !(heightKnown && models != null);
	}

	/** The height (world y, down) of the storey's floor on a tile. */
	float floor(int plane, int tx, int ty)
	{
		tx = Math.max(0, Math.min(size - 1, tx));
		ty = Math.max(0, Math.min(size - 1, ty));
		return floors[(plane * size + tx) * size + ty];
	}

	/** A tile's place in the floors of a storey, as floorAt takes it: the tile is held to the map. */
	int tileIndex(int tx, int ty)
	{
		return Math.max(0, Math.min(size - 1, tx)) * size + Math.max(0, Math.min(size - 1, ty));
	}

	/** floor() by a tile's tileIndex. */
	float floorAt(int plane, int tile)
	{
		return floors[plane * size * size + tile];
	}

	/** Whether the storey has a floor on the tile; one that is not known has. */
	boolean hasFloor(int plane, int tx, int ty)
	{
		if (filled == null || tx < 0 || ty < 0 || tx >= size || ty >= size)
		{
			return true;
		}
		return (filled[tx * size + ty] >> plane & 1) != 0;
	}
}
