package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.*;

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
public final class SkyField
{
	static final int TEXELS_PER_TILE = 2;
	// path cost of a step across an edge and across a corner
	private static final int STRAIGHT = 10, DIAGONAL = 14;
	// the light isn't followed once it is below this
	private static final float MIN_LIGHT = 1 / 255f;
	// how much further on (tiles) cornerLight counts a corner than its distance from the open sky: the flood counts from
	// the open tiles' centres and the corners averaged it, which put its light 0.59 tiles further on (sd 0.45, over
	// 8,000 covered corners in each of four Ardougne dumps); kept, so rooms keep their brightness
	private static final float CORNER_LAG = 0.6f;
	private static final int[] DX = {0, 1, 0, -1}, DY = {1, 0, -1, 0};
	private static final int[] SIDE = {WallMap.NORTH, WallMap.EAST, WallMap.SOUTH, WallMap.WEST};
	private static final int[] OPPOSITE = {WallMap.SOUTH, WallMap.WEST, WallMap.NORTH, WallMap.EAST};

	private SkyField()
	{
	}

	/** The share of the light that survives a tile, for light that reaches this many tiles in (one e-fold). */
	public static float falloffOf(float reachTiles)
	{
		return (float) Math.exp(-1.0 / Math.max(reachTiles, 0.25));
	}

	/**
	 * Tiles of a plane that have a roof over them or a floor above: from the rooms' flags and the floors (bit per
	 * plane, as BuildingLights.floorTiles); everything else is open to the sky.
	 */
	public static boolean[] covered(RoomMap rooms, byte[] floors, int plane)
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
	public static float[] propagate(WallMap walls, boolean[] covered, int plane, float falloff)
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
	 *
	 * Below those rows, as many again: each corner copy's gradient, per tile, of the light (RG: east, north) and of the
	 * cover (BA), shared like the values: the mean over the tiles joined at the corner of their slopes there, so a
	 * centred difference across open edges and a one-sided one at a wall. The shader blends a tile no diagonal splits
	 * from its corners' values and gradients (cubic Hermite, skylight_ao.glsl's skyFieldVisibility), so the light
	 * flowing into a room keeps its slope across tile edges: rebuilt bilinearly, it bent at every edge, the stepped
	 * falloff from a doorway the user saw (2026-11-01). A tile a diagonal splits has its halves' light gradients
	 * instead (RG half 0, BA half 1; its cover stays linear): the shader blends each half the same way, the corner
	 * missing from its triangle carried on from the triangle's plane, so a half meets its neighbours as smoothly.
	 */
	static float[] pack(WallMap walls, int plane, float[] light, boolean[] covered)
	{
		return pack(walls, plane, light, covered, null);
	}

	/**
	 * As pack, with each corner copy's light the given one (cornerLight: by the straight-line distance from the open
	 * sky) instead of the mean of its tiles' flood values; null: the mean.
	 */
	static float[] pack(WallMap walls, int plane, float[] light, boolean[] covered, float[] corners)
	{
		return pack(walls, plane, light, covered, corners, cornerRoots(walls, plane));
	}

	/** As pack, with the corners' groups worked out once (cornerRoots), as cornerLight takes them. */
	static float[] pack(WallMap walls, int plane, float[] light, boolean[] covered, float[] corners, int[] rootOf)
	{
		int size = walls.size, width = size * TEXELS_PER_TILE;
		float[] out = new float[2 * width * width * 4];
		int[] bits = new int[4], tiles = new int[4], parent = new int[8], count = new int[8];
		float[] sum = new float[8], coverSum = new float[8];
		for (int cx = 0; cx <= size; ++cx)
		{
			for (int cy = 0; cy <= size; ++cy)
			{
				java.util.Arrays.fill(count, 0);
				java.util.Arrays.fill(sum, 0);
				java.util.Arrays.fill(coverSum, 0);
				int present = corner(walls, plane, size, cx, cy, rootOf, bits, tiles, parent);
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
						out[o + 2 * h] = corners != null ? corners[(cx * (size + 1) + cy) * 8 + root] : sum[root] / count[root];
						out[o + 2 * h + 1] = coverSum[root] / count[root];
					}
				}
			}
		}
		// the gradients, from the values; then the diagonal kinds go into G. Per root: the mean of its members' slopes
		// (light east, north, cover east, north)
		float[] slope = new float[8 * 4];
		for (int cx = 0; cx <= size; ++cx)
		{
			for (int cy = 0; cy <= size; ++cy)
			{
				// out in the open (the tiles whose corners the slopes here read all uncovered): light 1, cover 0, no gradient
				boolean open = true;
				for (int x = Math.max(0, cx - 2); open && x <= Math.min(size - 1, cx + 1); ++x)
					for (int y = Math.max(0, cy - 2); open && y <= Math.min(size - 1, cy + 1); ++y)
						open = !covered[x * size + y];
				if (open) continue;
				java.util.Arrays.fill(count, 0);
				java.util.Arrays.fill(slope, 0);
				int present = corner(walls, plane, size, cx, cy, rootOf, bits, tiles, parent);
				for (int node = 0; node < 8; ++node)
				{
					if (parent[node] < 0) continue;
					int q = node >> 1, root = cornerRoot(parent, node);
					tileSlope(out, width, cx - 1 + (q & 1), cy - 1 + (q >> 1), 1 - (q & 1), 1 - (q >> 1), bits[q], node & 1, slope, root * 4);
					count[root]++;
				}
				for (int q = 0; q < 4; ++q)
				{
					if ((present & (1 << q)) == 0) continue;
					int x = cx - 1 + (q & 1), y = cy - 1 + (q >> 1);
					int o = (width * width + (2 * y + 1 - (q >> 1)) * width + 2 * x + 1 - (q & 1)) * 4;
					if (!WallMap.split(bits[q]))
					{
						int root = cornerRoot(parent, 2 * q);
						for (int k = 0; k < 4; ++k) out[o + k] = slope[root * 4 + k] / count[root];
						continue;
					}
					// each half's light gradient, where the half touches the corner
					for (int h = 0; h < 2; ++h)
					{
						if (parent[2 * q + h] < 0) continue;
						int root = cornerRoot(parent, 2 * q + h);
						out[o + 2 * h] = slope[root * 4] / count[root];
						out[o + 2 * h + 1] = slope[root * 4 + 1] / count[root];
					}
				}
			}
		}
		for (int x = 0; x < size; ++x)
		{
			for (int y = 0; y < size; ++y)
			{
				int b = walls.get(plane, x, y);
				float code = (b & WallMap.DIAG_SW_NE) != 0 ? 2 : (b & WallMap.DIAG_NW_SE) != 0 ? 4 : 0;
				for (int j = 0; j < 2; ++j)
					for (int i = 0; i < 2; ++i)
						out[((2 * y + j) * width + 2 * x + i) * 4 + 1] += code;
			}
		}
		return out;
	}

	/**
	 * The sky's light at every corner copy, by the straight-line distance (tiles) from the open sky through what the
	 * walls leave open, as falloff^distance: an any-angle shortest path (Theta*) over the corner copies, which steps
	 * across the tiles a copy belongs to and goes straight from a node's parent where nothing stands between them
	 * (WallMap.blocked). The tile flood (propagate) goes by edges and corners with a cost of 10 and 14, so its light
	 * fell off in octagons, and averaging it at the corners dented them by the walls: the steps in the light a doorway
	 * lets into a room (2026-11-01). Here the light falls off in circles round the opening's ends. A source corner
	 * gives light from the halves of the open edges at it (an open tile on one side, a covered one on the other, no
	 * wall), so the distance is to the opening's line, not to the corners on it: points one tile apart creased the
	 * light halfway between them.
	 *
	 * Returns [(cx * (size + 1) + cy) * 8 + root]: a corner's group roots (groupCorner) hold the light, 1 where the
	 * group has an open tile, 0 where the light doesn't reach.
	 */
	static float[] cornerLight(WallMap walls, int plane, boolean[] covered, float falloff)
	{
		return cornerLight(walls, plane, covered, falloff, cornerRoots(walls, plane), null);
	}

	/**
	 * As cornerLight, with the corners' groups (cornerRoots); paths, when given, gets {distance, parent node, x, z} per
	 * node, for tests. Lazy Theta*: a node is reached through its predecessor's parent on trust, and the line of sight
	 * between them checked once, when it is taken from the queue (if blocked, it takes the best of its settled
	 * neighbours instead), so WallMap.blocked runs once a node, not once an edge.
	 */
	static float[] cornerLight(WallMap walls, int plane, boolean[] covered, float falloff, int[] rootOf, float[][] paths)
	{
		int size = walls.size, corners = size + 1, nodes = rootOf.length;
		// the roots' positions (extended tiles), nudged into their tiles
		float[] px = new float[nodes], pz = new float[nodes];
		float[] dist = new float[nodes];
		int[] from = new int[nodes];
		java.util.Arrays.fill(dist, Float.POSITIVE_INFINITY);
		java.util.Arrays.fill(from, -1);
		boolean[] open = new boolean[nodes], settled = new boolean[nodes];
		// per source node: the open edges at its corner it gives light from, a bit per side (east 1, north 2, west 4,
		// south 8), each the half of the edge next to the corner
		byte[] edges = new byte[nodes];
		float reach = (float) (Math.log(MIN_LIGHT) / Math.log(falloff));
		Heap heap = new Heap(1024);
		float[] sx = new float[8], sz = new float[8];
		int[] n = new int[8], tiles = new int[4], bits = new int[4];
		boolean[] anyOpen = new boolean[8], anyCovered = new boolean[8];
		for (int cx = 0; cx < corners; ++cx)
		{
			for (int cy = 0; cy < corners; ++cy)
			{
				int c = (cx * corners + cy) * 8, present = 0;
				java.util.Arrays.fill(sx, 0);
				java.util.Arrays.fill(sz, 0);
				java.util.Arrays.fill(n, 0);
				java.util.Arrays.fill(anyOpen, false);
				java.util.Arrays.fill(anyCovered, false);
				for (int q = 0; q < 4; ++q)
				{
					int x = cx - 1 + (q & 1), y = cy - 1 + (q >> 1);
					if (x < 0 || y < 0 || x >= size || y >= size) continue;
					present |= 1 << q;
					tiles[q] = x * size + y;
					bits[q] = walls.get(plane, x, y);
				}
				for (int node = 0; node < 8; ++node)
				{
					int root = rootOf[c + node];
					if (root < 0) continue;
					int q = node >> 1, x = cx - 1 + (q & 1), y = cy - 1 + (q >> 1);
					halfCentre(bits[q], x, y, node & 1, sx, sz, root);
					n[root]++;
					boolean cov = covered[tiles[q]];
					anyOpen[root] |= !cov;
					anyCovered[root] |= cov;
				}
				for (int root = 0; root < 8; ++root)
				{
					if (n[root] == 0) continue;
					int id = c + root;
					px[id] = cx + 0.02f * (sx[root] / n[root] - cx);
					pz[id] = cy + 0.02f * (sz[root] / n[root] - cy);
					if (anyOpen[root])
					{
						open[id] = true;
						// only the open corners by covered tiles have anything to give
						boolean touches = anyCovered[root];
						for (int q = 0; !touches && q < 4; ++q) touches = (present & (1 << q)) != 0 && covered[tiles[q]];
						if (touches)
						{
							edges[id] = (byte) openEdges(walls, plane, size, covered, cx, cy);
							dist[id] = 0;
							from[id] = id;
							heap.push(id, 0);
						}
					}
				}
			}
		}
		int[] around = new int[24];
		while (heap.size > 0)
		{
			float key = heap.topKey();
			int u = heap.pop();
			if (settled[u] || key > dist[u]) continue;
			int p = from[u];
			if (p != u && walls.blocked(plane, local(px[p], walls), local(pz[p], walls), local(px[u], walls), local(pz[u], walls)))
			{
				// the parent taken on trust can't see it: the best of its settled neighbours
				float best = Float.POSITIVE_INFINITY;
				int via = -1;
				for (int k = 0, m = neighbours(walls, plane, size, corners, rootOf, dist, u, around); k < m; ++k)
				{
					int w = around[k];
					if (!settled[w]) continue;
					float d = dist[w] + reachFrom(w, edges[w], corners, px[u], pz[u], px[w], pz[w]);
					if (d < best)
					{
						best = d;
						via = w;
					}
				}
				if (via < 0 || best > reach)
				{
					dist[u] = Float.POSITIVE_INFINITY;
					continue;
				}
				dist[u] = best;
				from[u] = via;
			}
			settled[u] = true;
			int pu = from[u];
			for (int k = 0, m = neighbours(walls, plane, size, corners, rootOf, dist, u, around); k < m; ++k)
			{
				int v = around[k];
				if (open[v] || settled[v]) continue;
				// through u's parent, on trust (checked when v is taken)
				int via = pu != u ? pu : u;
				float d = dist[via] + reachFrom(via, edges[via], corners, px[v], pz[v], px[via], pz[via]);
				if (d < dist[v] && d <= reach)
				{
					dist[v] = d;
					from[v] = via;
					heap.push(v, d);
				}
			}
		}
		if (paths != null)
		{
			paths[0] = dist;
			paths[1] = new float[from.length];
			for (int i = 0; i < from.length; ++i) paths[1][i] = from[i];
			paths[2] = px;
			paths[3] = pz;
		}
		float[] out = new float[nodes];
		for (int id = 0; id < nodes; ++id)
		{
			out[id] = open[id] ? 1 : dist[id] <= reach ? (float) Math.pow(falloff, dist[id] + CORNER_LAG) : 0;
		}
		return out;
	}

	/**
	 * The nodes node u reaches across the tiles of its group (their other corners' groups holding the same tile half),
	 * into out; returns how many. A solid tile passes nothing on.
	 */
	private static int neighbours(WallMap walls, int plane, int size, int corners, int[] rootOf, float[] dist, int u, int[] out)
	{
		int ucx = u / 8 / corners, ucy = u / 8 % corners, uc = (ucx * corners + ucy) * 8, m = 0;
		for (int node = 0; node < 8; ++node)
		{
			if (rootOf[uc + node] != u - uc) continue;
			int q = node >> 1, h = node & 1, tx = ucx - 1 + (q & 1), ty = ucy - 1 + (q >> 1);
			if (tx < 0 || ty < 0 || tx >= size || ty >= size || walls.solid(plane, tx, ty) && dist[u] > 0) continue;
			boolean split = WallMap.split(walls.get(plane, tx, ty));
			for (int k = 0; k < 4; ++k)
			{
				int vcx = tx + (k & 1), vcy = ty + (k >> 1);
				if (vcx == ucx && vcy == ucy) continue;
				int q2 = (tx - vcx + 1) + 2 * (ty - vcy + 1);
				int vc = (vcx * corners + vcy) * 8, vroot = rootOf[vc + 2 * q2 + (split ? h : 0)];
				if (vroot >= 0) out[m++] = vc + vroot;
			}
		}
		return m;
	}

	/**
	 * A corner's quadrants as groupCorner gives them, from cornerRoots: bits and tiles of the quadrants inside the map,
	 * parent each node's root (a root its own parent), -1 where none. Returns the quadrants inside the map.
	 */
	private static int corner(WallMap walls, int plane, int size, int cx, int cy, int[] rootOf, int[] bits, int[] tiles, int[] parent)
	{
		int c = (cx * (size + 1) + cy) * 8, present = 0;
		for (int q = 0; q < 4; ++q)
		{
			int x = cx - 1 + (q & 1), y = cy - 1 + (q >> 1);
			if (x < 0 || y < 0 || x >= size || y >= size) continue;
			present |= 1 << q;
			tiles[q] = x * size + y;
			bits[q] = walls.get(plane, x, y);
		}
		for (int node = 0; node < 8; ++node) parent[node] = rootOf[c + node];
		return present;
	}

	/** Each corner's groups (groupCorner) once: [(cx * (size + 1) + cy) * 8 + node] the node's root, -1 where none. */
	static int[] cornerRoots(WallMap walls, int plane)
	{
		int size = walls.size, corners = size + 1;
		int[] bits = new int[4], tiles = new int[4], parent = new int[8];
		int[] rootOf = new int[corners * corners * 8];
		for (int cx = 0; cx < corners; ++cx)
		{
			for (int cy = 0; cy < corners; ++cy)
			{
				int c = (cx * corners + cy) * 8;
				groupCorner(walls, plane, size, cx, cy, bits, tiles, parent);
				for (int node = 0; node < 8; ++node) rootOf[c + node] = parent[node] < 0 ? -1 : cornerRoot(parent, node);
			}
		}
		return rootOf;
	}

	/** A binary min-heap of nodes by distance (repeats allowed; the stale ones are skipped when taken). */
	private static final class Heap
	{
		int[] ids;
		float[] keys;
		int size;

		Heap(int capacity)
		{
			ids = new int[capacity];
			keys = new float[capacity];
		}

		void push(int id, float key)
		{
			if (size == ids.length)
			{
				ids = java.util.Arrays.copyOf(ids, size * 2);
				keys = java.util.Arrays.copyOf(keys, size * 2);
			}
			int i = size++;
			while (i > 0)
			{
				int up = (i - 1) >> 1;
				if (keys[up] <= key) break;
				ids[i] = ids[up];
				keys[i] = keys[up];
				i = up;
			}
			ids[i] = id;
			keys[i] = key;
		}

		float topKey()
		{
			return keys[0];
		}

		int pop()
		{
			int top = ids[0];
			int id = ids[--size];
			float key = keys[size];
			int i = 0;
			while (true)
			{
				int c = 2 * i + 1;
				if (c >= size) break;
				if (c + 1 < size && keys[c + 1] < keys[c]) c++;
				if (keys[c] >= key) break;
				ids[i] = ids[c];
				keys[i] = keys[c];
				i = c;
			}
			ids[i] = id;
			keys[i] = key;
			return top;
		}
	}

	/**
	 * The distance from node s (a corner's group) to (x, z): from the open edges it gives light from where it has
	 * some, else from its position (sx, sz).
	 */
	private static float reachFrom(int s, int sides, int corners, float x, float z, float sx, float sz)
	{
		if (sides == 0)
		{
			return (float) Math.hypot(x - sx, z - sz);
		}
		int cx = s / 8 / corners, cz = s / 8 % corners;
		float best = Float.POSITIVE_INFINITY;
		for (int k = 0; k < 4; ++k)
		{
			if ((sides & (1 << k)) == 0) continue;
			// the half edge from the corner a half tile toward the side
			float ex = k == 0 ? 0.5f : k == 2 ? -0.5f : 0, ez = k == 1 ? 0.5f : k == 3 ? -0.5f : 0;
			float t = Math.max(0, Math.min(1, ((x - cx) * ex + (z - cz) * ez) / 0.25f));
			best = Math.min(best, (float) Math.hypot(x - cx - t * ex, z - cz - t * ez));
		}
		return best;
	}

	/**
	 * The open edges at corner (cx, cz), a bit per side (east 1, north 2, west 4, south 8): an edge from the corner with
	 * an open tile on one side and a covered one on the other and no wall on it.
	 */
	private static int openEdges(WallMap walls, int plane, int size, boolean[] covered, int cx, int cz)
	{
		int out = 0;
		// per side: the two tiles either side of the edge from the corner, and the side of the first the edge is on
		int[][] t = {{cx, cz - 1, cx, cz, WallMap.NORTH}, {cx - 1, cz, cx, cz, WallMap.EAST},
			{cx - 1, cz - 1, cx - 1, cz, WallMap.NORTH}, {cx - 1, cz - 1, cx, cz - 1, WallMap.EAST}};
		for (int k = 0; k < 4; ++k)
		{
			int ax = t[k][0], az = t[k][1], bx = t[k][2], bz = t[k][3];
			if (ax < 0 || az < 0 || bx < 0 || bz < 0 || ax >= size || az >= size || bx >= size || bz >= size) continue;
			if (covered[ax * size + az] == covered[bx * size + bz] || walls.edge(plane, ax, az, t[k][4])) continue;
			out |= 1 << k;
		}
		return out;
	}

	/** Extended tiles to the local world units WallMap.blocked takes. */
	private static float local(float t, WallMap walls)
	{
		return (t - walls.offset) * 128;
	}

	/** Add the centre of a tile's half (halfCentre) to sx[at], sz[at]. */
	private static void halfCentre(int bits, int x, int y, int half, float[] sx, float[] sz, int at)
	{
		float[] c = halfCentre(bits, x, y, half);
		sx[at] += c[0];
		sz[at] += c[1];
	}

	/** The centre of a tile's half (its triangle's centroid where a diagonal splits it), extended tiles. */
	private static float[] halfCentre(int bits, int x, int y, int half)
	{
		if ((bits & WallMap.DIAG_SW_NE) != 0)
		{
			return half == 0 ? new float[]{x + 1 / 3f, y + 2 / 3f} : new float[]{x + 2 / 3f, y + 1 / 3f};
		}
		if ((bits & WallMap.DIAG_NW_SE) != 0)
		{
			return half == 0 ? new float[]{x + 2 / 3f, y + 2 / 3f} : new float[]{x + 1 / 3f, y + 1 / 3f};
		}
		return new float[]{x + 0.5f, y + 0.5f};
	}

	/**
	 * The quadrants around corner (cx, cy) (SW, SE, NW, NE) that reach each other without crossing a wall: parent holds
	 * the groups of their halves' nodes (2 * quadrant + half), -1 where a half doesn't touch the corner. Returns the
	 * quadrants inside the map.
	 */
	private static int groupCorner(WallMap walls, int plane, int size, int cx, int cy, int[] bits, int[] tiles, int[] parent)
	{
		java.util.Arrays.fill(parent, -1);
		int present = 0;
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
		return present;
	}

	/**
	 * Add a tile half's slopes, per tile, at its corner (kx, ky) (1 east, north) to slope[at..at + 3]: light east,
	 * north, cover east, north. A whole tile's are its edges' through the corner; a half's, its triangle's.
	 */
	private static void tileSlope(float[] out, int width, int x, int y, int kx, int ky, int bits, int half, float[] slope, int at)
	{
		for (int c = 0; c < 2; ++c)
		{
			int ch = 2 * half + c;
			float sw = out[((2 * y) * width + 2 * x) * 4 + ch], se = out[((2 * y) * width + 2 * x + 1) * 4 + ch];
			float nw = out[((2 * y + 1) * width + 2 * x) * 4 + ch], ne = out[((2 * y + 1) * width + 2 * x + 1) * 4 + ch];
			float east, north;
			if ((bits & WallMap.DIAG_SW_NE) != 0)
			{
				// half 0 the north-west triangle (SW, NW, NE), half 1 the south-east one (SW, SE, NE)
				east = half == 0 ? ne - nw : se - sw;
				north = half == 0 ? nw - sw : ne - se;
			}
			else if ((bits & WallMap.DIAG_NW_SE) != 0)
			{
				// half 0 the north-east triangle (NW, NE, SE), half 1 the south-west one (SW, NW, SE)
				east = half == 0 ? ne - nw : se - sw;
				north = half == 0 ? ne - se : nw - sw;
			}
			else
			{
				east = ky == 0 ? se - sw : ne - nw;
				north = kx == 0 ? nw - sw : ne - se;
			}
			slope[at + 2 * c] += east;
			slope[at + 2 * c + 1] += north;
		}
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
