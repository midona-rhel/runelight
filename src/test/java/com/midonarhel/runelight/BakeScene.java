package com.midonarhel.runelight;

/**
 * A small scene for the light map tests: walls and solid tiles by local tile, thin models by cell, floors for the
 * storeys. Tile coordinates are extended-scene tiles (the walls' own), light positions are made from them.
 */
final class BakeScene
{
	static final int SIZE = 48, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;
	static final float STOREY = 240;

	final int[][][] flags = new int[4][LOCAL][LOCAL];
	final float[] floors = new float[4 * SIZE * SIZE];
	final byte[] filled = new byte[SIZE * SIZE];
	ModelBlockers models;
	private boolean explicitFloors;

	BakeScene()
	{
		for (int p = 0; p < 4; ++p)
		{
			for (int i = 0; i < SIZE * SIZE; ++i)
			{
				floors[p * SIZE * SIZE + i] = -p * STOREY;
			}
		}
		// every tile has a ground floor unless a test says otherwise
		java.util.Arrays.fill(filled, (byte) 1);
	}

	/** The wall on the west edge of tile (x, y), flagged on both sides as the game does. */
	BakeScene westWall(int plane, int x, int y)
	{
		flags[plane][x - OFFSET][y - OFFSET] |= WallMap.LOS_WEST;
		flags[plane][x - 1 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST;
		return this;
	}

	BakeScene southWall(int plane, int x, int y)
	{
		flags[plane][x - OFFSET][y - OFFSET] |= WallMap.LOS_SOUTH;
		flags[plane][x - OFFSET][y - 1 - OFFSET] |= WallMap.LOS_NORTH;
		return this;
	}

	/** A wall along x = x (the west edge of column x) from tile y0 to y1 inclusive, doors being the rows left open. */
	BakeScene verticalWall(int plane, int x, int y0, int y1, int... doors)
	{
		for (int y = y0; y <= y1; ++y)
		{
			boolean door = false;
			for (int d : doors)
			{
				door |= d == y;
			}
			if (!door)
			{
				westWall(plane, x, y);
			}
		}
		return this;
	}

	BakeScene solid(int plane, int x0, int y0, int x1, int y1)
	{
		for (int x = x0; x <= x1; ++x)
		{
			for (int y = y0; y <= y1; ++y)
			{
				flags[plane][x - OFFSET][y - OFFSET] |= WallMap.LOS_FULL;
			}
		}
		return this;
	}

	/** Cells (not tiles) filled by a model at every height, whole, on a storey, inclusive. */
	BakeScene modelCells(int plane, int gx0, int gz0, int gx1, int gz1)
	{
		for (int z = gz0; z <= gz1; ++z)
		{
			for (int x = gx0; x <= gx1; ++x)
			{
				modelBox(plane, x, z, 0, 0, 32, 32, 0, 239);
			}
		}
		return this;
	}

	/** A model in a cell: its box in the cell (units, 0 to 32) and the heights above the floor it reaches. */
	BakeScene modelBox(int plane, int gx, int gz, int x0, int z0, int x1, int z1, float low, float high)
	{
		if (models == null)
		{
			models = new ModelBlockers(SIZE * LightBlockers.CELLS_PER_TILE);
		}
		int mask = 0;
		for (int bin = ModelBlockers.bin(low); bin <= ModelBlockers.bin(high); ++bin)
		{
			mask |= 1 << bin;
		}
		byte[] box = new byte[16];
		for (int band = 0; band < 4; ++band)
		{
			box[band * 4] = (byte) x0;
			box[band * 4 + 1] = (byte) z0;
			box[band * 4 + 2] = (byte) x1;
			box[band * 4 + 3] = (byte) z1;
		}
		models.set(plane, gx, gz, (short) mask, box, 0);
		return this;
	}

	/** Whether a storey has a floor on each tile of the rectangle (inclusive). */
	BakeScene floor(int plane, int x0, int y0, int x1, int y1, boolean has)
	{
		for (int x = x0; x <= x1; ++x)
		{
			for (int y = y0; y <= y1; ++y)
			{
				if (has)
				{
					filled[x * SIZE + y] |= (byte) (1 << plane);
				}
				else
				{
					filled[x * SIZE + y] &= (byte) ~(1 << plane);
				}
			}
		}
		return this;
	}

	LightBlockers blockers()
	{
		return blockers(1);
	}

	LightBlockers blockers(int scene)
	{
		WallMap walls = WallMap.build(flags, null, SIZE, OFFSET, scene);
		return new LightBlockers(walls, models, floors, filled);
	}

	/** World position (local units) of a tile coordinate with a fraction. */
	static float world(float tile)
	{
		return (tile - OFFSET) * 128;
	}

	/** A light on a storey at a tile position, this high above the storey's floor. */
	static LightMapBake.Light light(int plane, float tileX, float tileY, float height, float radiusTiles)
	{
		return new LightMapBake.Light(plane, world(tileX), -plane * STOREY - height, world(tileY), radiusTiles * 128);
	}

	/** The share at a cell, scene cell coordinates (a tile is 4), the best of the height layers, 0 where the bake left it out. */
	static float at(LightMapBake.Result r, int plane, int gx, int gz)
	{
		float best = 0;
		for (int layer = 0; layer < LightMapBake.HEIGHT_LAYERS; ++layer)
		{
			best = Math.max(best, at(r, plane, layer, gx, gz));
		}
		return best;
	}

	/** The share at a cell for one height layer. */
	static float at(LightMapBake.Result r, int plane, int layer, int gx, int gz)
	{
		for (LightMapBake.Grid g : r.grids)
		{
			if (g.plane == plane && gx >= g.x0 && gz >= g.z0 && gx < g.x0 + g.w && gz < g.z0 + g.h)
			{
				return g.c[((gz - g.z0) * g.w + gx - g.x0) * LightMapBake.HEIGHT_LAYERS + layer];
			}
		}
		return 0;
	}

	/** The share at the middle of a tile at a fraction, as a cell. */
	static float atTile(LightMapBake.Result r, int plane, float tileX, float tileY)
	{
		return at(r, plane, (int) Math.floor(tileX * 4), (int) Math.floor(tileY * 4));
	}

	static boolean reaches(LightMapBake.Result r, int plane)
	{
		for (LightMapBake.Grid g : r.grids)
		{
			if (g.plane == plane)
			{
				for (float c : g.c)
				{
					if (c > 0)
					{
						return true;
					}
				}
			}
		}
		return false;
	}
}
