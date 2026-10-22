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
	 * Two texels per tile axis, representing its actual corners. RG and BA hold each half's light and cover;
	 * G additionally encodes twice the diagonal kind (0, 1, 2). Open edges share corner values; walls keep
	 * separate values, including the two sides of a diagonal ending at a corner. Propagation itself is unchanged.
	 */
	static float[] pack(WallMap walls, int plane, float[] light, boolean[] covered)
	{
		int size = walls.size, width = size * TEXELS_PER_TILE;
		float[] out = new float[width * width * 4];
		int[] bits = new int[4], tiles = new int[4], parent = new int[8], count = new int[8];
		float[] sum = new float[8], coverSum = new float[8];
		for (int cx = 0; cx <= size; ++cx)
		{
			for (int cy = 0; cy <= size; ++cy)
			{
				java.util.Arrays.fill(parent, -1);
				java.util.Arrays.fill(count, 0);
				java.util.Arrays.fill(sum, 0);
				java.util.Arrays.fill(coverSum, 0);
				int present = 0;
				// Quadrants around this corner: SW, SE, NW, NE.
				for (int q = 0; q < 4; ++q)
				{
					int x = cx - 1 + (q & 1), y = cy - 1 + (q >> 1);
					if (x < 0 || y < 0 || x >= size || y >= size) continue;
					present |= 1 << q;
					tiles[q] = x * size + y;
					bits[q] = walls.get(plane, x, y);
					int hx = WallMap.half(bits[q], (q & 1) == 0 ? WallMap.EAST : WallMap.WEST);
					int hy = WallMap.half(bits[q], q < 2 ? WallMap.NORTH : WallMap.SOUTH);
					parent[2 * q + hx] = 2 * q + hx;
					parent[2 * q + hy] = 2 * q + hy;
				}
				joinCorner(walls, plane, cx - 1, cy - 1, present, bits, parent, 0, 1, WallMap.EAST, WallMap.WEST);
				joinCorner(walls, plane, cx - 1, cy, present, bits, parent, 2, 3, WallMap.EAST, WallMap.WEST);
				joinCorner(walls, plane, cx - 1, cy - 1, present, bits, parent, 0, 2, WallMap.NORTH, WallMap.SOUTH);
				joinCorner(walls, plane, cx, cy - 1, present, bits, parent, 1, 3, WallMap.NORTH, WallMap.SOUTH);
				for (int node = 0; node < 8; ++node)
				{
					if (parent[node] < 0) continue;
					int root = cornerRoot(parent, node);
					sum[root] += light[2 * tiles[node >> 1] + (node & 1)];
					coverSum[root] += covered[tiles[node >> 1]] ? 1 : 0;
					count[root]++;
				}
				for (int q = 0; q < 4; ++q)
				{
					if ((present & (1 << q)) == 0) continue;
					int x = cx - 1 + (q & 1), y = cy - 1 + (q >> 1);
					int o = ((2 * y + 1 - (q >> 1)) * width + 2 * x + 1 - (q & 1)) * 4;
					for (int h = 0; h < 2; ++h)
					{
						int node = 2 * q + (WallMap.split(bits[q]) ? h : 0);
						// A triangle's opposite corner is never sampled.
						if (parent[node] < 0) continue;
						int root = cornerRoot(parent, node);
						out[o + 2 * h] = sum[root] / count[root];
						out[o + 2 * h + 1] = coverSum[root] / count[root];
					}
					out[o + 1] += (bits[q] & WallMap.DIAG_SW_NE) != 0 ? 2
						: (bits[q] & WallMap.DIAG_NW_SE) != 0 ? 4 : 0;
				}
			}
		}
		return out;
	}

	private static void joinCorner(WallMap walls, int plane, int x, int y, int present, int[] bits,
		int[] parent, int a, int b, int side, int opposite)
	{
		if ((present & (1 << a)) == 0 || (present & (1 << b)) == 0 || walls.edge(plane, x, y, side)) return;
		int na = 2 * a + WallMap.half(bits[a], side), nb = 2 * b + WallMap.half(bits[b], opposite);
		parent[cornerRoot(parent, na)] = cornerRoot(parent, nb);
	}

	private static int cornerRoot(int[] parent, int node)
	{
		while (parent[node] != node) node = parent[node];
		return node;
	}
}
