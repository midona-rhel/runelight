package com.midonarhel.runelight;

/**
 * The sky's light flowing in under cover, per tile of a plane (the skylight's horizontal half; skylight_ao.glsl):
 * tiles with the open sky above are 1, and the light spreads from them to the tiles around through every edge the
 * walls leave open (WallMap: closed doors, windows and walls block it, open doors and doorways don't; the two halves
 * of a tile with a diagonal wall are separate), losing a share per tile. So a gate arch fades in from both of its
 * ends, a house with its door open has a pool of light by the door, and a closed door keeps it out.
 *
 * Covered tiles are the ones the map flags as under a roof or with a floor above them. The light is the nearest
 * way in, by a shortest-path search over the tile grid (Dial's buckets: cheap enough to rerun whenever a door
 * opens), 10 per step across an edge, 14 across a corner. GL-free; arrays are [x * size + y] in extended tiles, y
 * north, like WallMap.
 */
final class SkyField
{
	static final int TEXELS_PER_TILE = 2;
	// path cost of a step across an edge and across a corner
	private static final int STRAIGHT = 10, DIAGONAL = 14;
	// the light isn't followed once it is below this
	private static final float MIN_LIGHT = 1 / 255f;
	private static final int[] DX = {0, 1, 0, -1}, DY = {1, 0, -1, 0};
	private static final int[] SIDE = {WallMap.NORTH, WallMap.EAST, WallMap.SOUTH, WallMap.WEST};
	private static final int[] OPPOSITE = {WallMap.SOUTH, WallMap.WEST, WallMap.NORTH, WallMap.EAST};

	private SkyField()
	{
	}

	/** The share of the light that survives a tile, for light that reaches this many tiles in (one e-fold). */
	static float falloffOf(float reachTiles)
	{
		return (float) Math.exp(-1.0 / Math.max(reachTiles, 0.25));
	}

	/**
	 * Tiles of a plane that have a roof over them or a floor above: from the rooms' flags and the floors (bit per
	 * plane, as BuildingLights.floorTiles); everything else is open to the sky.
	 */
	static boolean[] covered(RoomMap rooms, byte[] floors, int plane)
	{
		int n = rooms.size * rooms.size;
		boolean[] out = new boolean[n];
		// the planes above this one
		int above = (0xFF << (plane + 1)) & 0xFF;
		for (int i = 0; i < n; ++i)
		{
			out[i] = rooms.roofed[rooms.index(plane, 0, 0) + i] || floors != null && (floors[i] & above) != 0;
		}
		return out;
	}

	/**
	 * The sky light per tile half, 0..1, at tile * 2 + half (WallMap.half): the two halves of a tile a diagonal wall
	 * crosses each have their own, the halves of any other tile the same.
	 *
	 * @param covered tiles without open sky above
	 * @param falloff the share left after a tile's step
	 */
	static float[] propagate(WallMap walls, boolean[] covered, int plane, float falloff)
	{
		int size = walls.size;
		int n = size * size;
		float[] light = new float[n * 2];
		int maxCost = (int) Math.ceil(Math.log(MIN_LIGHT) / Math.log(falloff) * STRAIGHT);
		float[] table = new float[maxCost + 1];
		for (int c = 0; c <= maxCost; ++c)
		{
			table[c] = (float) Math.pow(falloff, c / (double) STRAIGHT);
		}
		// a node is a tile's half: tile * 2 + half
		int[] cost = new int[n * 2];
		java.util.Arrays.fill(cost, Integer.MAX_VALUE);
		int[][] bucket = new int[maxCost + 1][];
		int[] fill = new int[maxCost + 1];
		for (int x = 0; x < size; ++x)
		{
			for (int y = 0; y < size; ++y)
			{
				int i = x * size + y;
				if (covered[i])
				{
					continue;
				}
				light[2 * i] = light[2 * i + 1] = 1;
				cost[2 * i] = cost[2 * i + 1] = 0;
				// only the open tiles by covered ones have anything to give
				if (touchesCovered(covered, size, x, y))
				{
					push(bucket, fill, 0, 2 * i);
					push(bucket, fill, 0, 2 * i + 1);
				}
			}
		}
		for (int c = 0; c <= maxCost; ++c)
		{
			for (int k = 0; k < fill[c]; ++k)
			{
				int node = bucket[c][k];
				if (cost[node] != c)
				{
					continue;
				}
				int i = node >> 1, half = node & 1;
				int x = i / size, y = i % size;
				int b = walls.get(plane, x, y);
				// a solid object takes the light but doesn't pass it on
				if (c > 0 && walls.solid(plane, x, y))
				{
					continue;
				}
				for (int d = 0; d < 4; ++d)
				{
					if (WallMap.half(b, SIDE[d]) != half)
					{
						continue;
					}
					int nx = x + DX[d], ny = y + DY[d];
					if (nx < 0 || ny < 0 || nx >= size || ny >= size)
					{
						continue;
					}
					int nb = walls.get(plane, nx, ny);
					if ((b & SIDE[d]) != 0 || (nb & OPPOSITE[d]) != 0)
					{
						continue;
					}
					relax(cost, bucket, fill, maxCost, (nx * size + ny) * 2 + WallMap.half(nb, OPPOSITE[d]), c + STRAIGHT);
				}
				if (WallMap.split(b))
				{
					continue;
				}
				// across a corner: only where both ways round it are open
				for (int d = 0; d < 4; ++d)
				{
					int e = (d + 1) & 3;
					int ax = x + DX[d], ay = y + DY[d], bx = x + DX[e], by = y + DY[e];
					int dx = ax + DX[e], dy = ay + DY[e];
					if (dx < 0 || dy < 0 || dx >= size || dy >= size || (b & (SIDE[d] | SIDE[e])) != 0)
					{
						continue;
					}
					int ab = walls.get(plane, ax, ay), bb = walls.get(plane, bx, by), db = walls.get(plane, dx, dy);
					if (WallMap.split(ab) || WallMap.split(bb) || WallMap.split(db)
						|| (ab & (OPPOSITE[d] | SIDE[e])) != 0 || (bb & (OPPOSITE[e] | SIDE[d])) != 0
						|| (db & (OPPOSITE[d] | OPPOSITE[e])) != 0)
					{
						continue;
					}
					relax(cost, bucket, fill, maxCost, (dx * size + dy) * 2, c + DIAGONAL);
				}
			}
		}
		for (int i = 0; i < n; ++i)
		{
			if (!covered[i])
			{
				continue;
			}
			boolean split = WallMap.split(walls.get(plane, i / size, i % size));
			for (int h = 0; h < 2; ++h)
			{
				int c = split ? cost[2 * i + h] : Math.min(cost[2 * i], cost[2 * i + 1]);
				light[2 * i + h] = c <= maxCost ? table[c] : 0;
			}
		}
		return light;
	}

	private static boolean touchesCovered(boolean[] covered, int size, int x, int y)
	{
		for (int dx = -1; dx <= 1; ++dx)
		{
			for (int dy = -1; dy <= 1; ++dy)
			{
				int nx = x + dx, ny = y + dy;
				if (nx >= 0 && ny >= 0 && nx < size && ny < size && covered[nx * size + ny])
				{
					return true;
				}
			}
		}
		return false;
	}

	private static void relax(int[] cost, int[][] bucket, int[] fill, int maxCost, int node, int c)
	{
		if (c > maxCost || c >= cost[node])
		{
			return;
		}
		cost[node] = c;
		push(bucket, fill, c, node);
	}

	private static void push(int[][] bucket, int[] fill, int c, int node)
	{
		if (bucket[c] == null)
		{
			bucket[c] = new int[64];
		}
		else if (fill[c] == bucket[c].length)
		{
			bucket[c] = java.util.Arrays.copyOf(bucket[c], fill[c] * 2);
		}
		bucket[c][fill[c]++] = node;
	}

	/**
	 * The texture: two texels a side per tile, red the light and green 1 under cover, rows by y. A texel is the tile's
	 * light eased a quarter toward the tile on its side, over open edges only, so that bilinear sampling gives smooth
	 * gradients that stop at the walls; the cover eases the same way over any edge. On a tile a diagonal wall crosses
	 * the two texels either side of it take their halves' light, the two on it the darker one's, unblended.
	 *
	 * @param light per tile half, as {@link #propagate} gives it
	 */
	static byte[] pack(WallMap walls, int plane, float[] light, boolean[] covered)
	{
		int size = walls.size;
		int w = size * TEXELS_PER_TILE;
		byte[] out = new byte[w * w * 2];
		for (int x = 0; x < size; ++x)
		{
			for (int y = 0; y < size; ++y)
			{
				for (int i = 0; i < 2; ++i)
				{
					for (int j = 0; j < 2; ++j)
					{
						int sx = i == 0 ? -1 : 1, sy = j == 0 ? -1 : 1;
						float r = lightTexel(walls, plane, light, size, x, y, sx, sy);
						float g = coverTexel(covered, size, x, y, sx, sy);
						int o = ((y * 2 + j) * w + x * 2 + i) * 2;
						out[o] = (byte) Math.round(r * 255);
						out[o + 1] = (byte) Math.round(g * 255);
					}
				}
			}
		}
		return out;
	}

	private static float lightTexel(WallMap walls, int plane, float[] light, int size, int x, int y, int sx, int sy)
	{
		int b = walls.get(plane, x, y);
		int i = x * size + y;
		if (WallMap.split(b))
		{
			// the quadrant's half: north-west or south-east of "/", north-east or south-west of ""
			boolean slash = (b & WallMap.DIAG_SW_NE) != 0;
			if (slash ? sx != sy : sx == sy)
			{
				return light[2 * i + WallMap.half(b, sy > 0 ? WallMap.NORTH : WallMap.SOUTH)];
			}
			return Math.min(light[2 * i], light[2 * i + 1]);
		}
		int sideX = sx > 0 ? WallMap.WEST : WallMap.EAST, sideY = sy > 0 ? WallMap.SOUTH : WallMap.NORTH;
		boolean openX = openAcross(walls, plane, size, x, y, sx, 0);
		boolean openY = openAcross(walls, plane, size, x, y, 0, sy);
		float a = light[2 * i];
		float row = openX ? 0.75f * a + 0.25f * facing(walls, plane, light, size, x + sx, y, sideX) : a;
		if (!openY)
		{
			return row;
		}
		int y2 = y + sy;
		float c = facing(walls, plane, light, size, x, y2, sideY);
		float rowY = openAcross(walls, plane, size, x, y2, sx, 0)
			? 0.75f * c + 0.25f * facing(walls, plane, light, size, x + sx, y2, sideX) : c;
		return 0.75f * row + 0.25f * rowY;
	}

	/** The light of tile (x, y)'s half with the given edge. */
	private static float facing(WallMap walls, int plane, float[] light, int size, int x, int y, int side)
	{
		return light[2 * (x * size + y) + WallMap.half(walls.get(plane, x, y), side)];
	}

	/** Whether tile (x, y) is open toward the tile one step (dx, dy) away, which is in the map. */
	private static boolean openAcross(WallMap walls, int plane, int size, int x, int y, int dx, int dy)
	{
		int nx = x + dx, ny = y + dy;
		if (nx < 0 || ny < 0 || nx >= size || ny >= size)
		{
			return false;
		}
		int side = dx > 0 ? WallMap.EAST : dx < 0 ? WallMap.WEST : dy > 0 ? WallMap.NORTH : WallMap.SOUTH;
		return !walls.edge(plane, x, y, side);
	}

	private static float coverTexel(boolean[] covered, int size, int x, int y, int sx, int sy)
	{
		int x2 = Math.max(0, Math.min(size - 1, x + sx)), y2 = Math.max(0, Math.min(size - 1, y + sy));
		float a = covered[x * size + y] ? 1 : 0, b = covered[x2 * size + y] ? 1 : 0;
		float c = covered[x * size + y2] ? 1 : 0, d = covered[x2 * size + y2] ? 1 : 0;
		return 0.75f * (0.75f * a + 0.25f * b) + 0.25f * (0.75f * c + 0.25f * d);
	}
}
