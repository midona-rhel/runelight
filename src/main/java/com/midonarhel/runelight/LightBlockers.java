package com.midonarhel.runelight;

/**
 * What stops a static light, as one scene's snapshot for the baking threads (LightMapBake): the walls and the tiles
 * a solid object fills (WallMap), the models at light height (ModelBlockers), and the floors of the storeys, for
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

	LightBlockers(WallMap walls, ModelBlockers models, float[] floors, byte[] filled)
	{
		this.walls = walls;
		this.models = models;
		this.floors = floors;
		this.filled = filled;
		size = walls.size;
		offset = walls.offset;
		cells = size * CELLS_PER_TILE;
	}

	/** Whether the cell stops light: a tile filled by a solid object, or a model at light height. */
	boolean blocked(int plane, int gx, int gz)
	{
		return walls.solid(plane, Math.floorDiv(gx, CELLS_PER_TILE), Math.floorDiv(gz, CELLS_PER_TILE))
			|| models != null && models.blocked(plane, gx, gz);
	}

	/** The height (world y, down) of the storey's floor on a tile. */
	float floor(int plane, int tx, int ty)
	{
		tx = Math.max(0, Math.min(size - 1, tx));
		ty = Math.max(0, Math.min(size - 1, ty));
		return floors[(plane * size + tx) * size + ty];
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
