/*
 * Copyright (c) 2026, Midona
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
import java.util.Arrays;
import java.util.BitSet;
import java.util.function.IntPredicate;
import javax.annotation.Nullable;
import net.runelite.api.Client;
import net.runelite.api.Constants;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WallObject;
import static com.midonarhel.lumina.scene.SceneData.HIDDEN_HSL;
import static net.runelite.api.Constants.MAX_Z;

/**
 * How much of the sky a tile sees, baked at scene load for the top-level scene: 1 in the open, falling off smoothly
 * under roofs and upper floors with the walk distance from the nearest open tile, never through a wall.
 * <ol>
 * <li>Cover, per level and extended tile: under a roof (the client's roof ids, or the under-roof tile flag when the
 * scene has no roof ids), or under a floor: a higher level with a visible tile paint or a tile model at least
 * {@link #FLOOR_CLEARANCE} units above it (bridge decks included, since levels are the tiles' render levels).</li>
 * <li>Wall edges: a tile's straight or corner wall object closes its edge(s) unless the object is an opening, a door
 * or gate (an Open/Close action) or a window (by name). Diagonal walls and corner posts close nothing.</li>
 * <li>Propagation: Dial's buckets from the open tiles, cost {@link #STEP} across an open edge and {@link #DIAGONAL}
 * across a corner when both ways round it are open; value = f^(cost/STEP) with f = exp(-1/{@link #REACH_TILES}).</li>
 * <li>Per-tile quadrants: at each tile corner the touching tiles connected through open edges share the mean of
 * their values, so the value is continuous across open edges and steps only across walls.</li>
 * </ol>
 * The result is {@link SceneData#skyVisibility}, sampled bilinearly within a tile; the uploaders write it per vertex.
 */
public final class SkyCover
{
	/** The e-folding distance of the fall-off from an opening, in tiles. */
	public static final float REACH_TILES = 3f;
	/** A floor counts as cover only this far above the tile (units, y down). */
	public static final int FLOOR_CLEARANCE = 64;

	static final int STEP = 10, DIAGONAL = 14;
	// the propagation stops where the value would round to 0 (f^(cost/STEP) * 255 < 0.5)
	static final int MAX_COST = (int) Math.ceil(Math.log(510) * REACH_TILES * STEP);
	private static final float[] VALUE_BY_COST = new float[MAX_COST + 1];

	static
	{
		for (int c = 0; c <= MAX_COST; c++)
		{
			VALUE_BY_COST[c] = (float) Math.exp(-c / (STEP * REACH_TILES));
		}
	}

	// wall edge bits, the wall object orientation values (ObjectOrientation.convertWallObjectOrientation)
	static final int EDGE_W = 1, EDGE_N = 2, EDGE_E = 4, EDGE_S = 8;
	// wall object types (rs117.hd.data.ObjectType) that stand on a tile edge
	private static final int WALL_STRAIGHT = 0, WALL_CORNER = 2;

	private SkyCover()
	{
	}

	/**
	 * The distinct ids of the scene's wall objects, to resolve on the client thread with {@link #openings}.
	 */
	public static int[] wallObjectIds(Scene scene)
	{
		BitSet ids = new BitSet();
		Tile[][][] tiles = scene.getExtendedTiles();
		for (Tile[][] plane : tiles)
		{
			for (Tile[] column : plane)
			{
				for (Tile t : column)
				{
					for (; t != null; t = t.getBridge())
					{
						WallObject w = t.getWallObject();
						if (w != null && w.getId() >= 0)
						{
							ids.set(w.getId());
						}
					}
				}
			}
		}
		return ids.stream().toArray();
	}

	/**
	 * The wall objects among {@code ids} that are openings. Client thread (object definitions).
	 */
	public static BitSet openings(Client client, int[] ids)
	{
		BitSet openings = new BitSet();
		for (int id : ids)
		{
			ObjectComposition def = client.getObjectDefinition(id);
			if (def == null)
			{
				continue;
			}
			boolean opening = isOpening(def);
			if (!opening && def.getImpostorIds() != null)
			{
				ObjectComposition impostor = def.getImpostor();
				opening = impostor != null && isOpening(impostor);
			}
			if (opening)
			{
				openings.set(id);
			}
		}
		return openings;
	}

	/**
	 * A door or gate (an Open or Close action, whatever its state) or a window (by name).
	 */
	public static boolean isOpening(ObjectComposition def)
	{
		String name = def.getName();
		if (name != null && name.toLowerCase().contains("window"))
		{
			return true;
		}
		String[] actions = def.getActions();
		if (actions != null)
		{
			for (String action : actions)
			{
				if (action != null && (action.equalsIgnoreCase("Open") || action.equalsIgnoreCase("Close")))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Bakes {@link SceneData#skyVisibility} of a top-level scene.
	 *
	 * @param isOpening whether a wall object id is an opening (from {@link #openings})
	 */
	public static void bake(SceneData data, IntPredicate isOpening)
	{
		final Scene scene = data.scene;
		final int n = data.sizeX;
		if (n != data.sizeZ)
		{
			return;
		}
		final Tile[][][] tiles = scene.getExtendedTiles();

		// floors by render level (bridge decks are tiles at plane 0 with render level 1), and the wall edges
		boolean[][] floors = new boolean[MAX_Z][n * n];
		byte[][] walls = new byte[MAX_Z][n * n];
		for (int p = 0; p < MAX_Z; p++)
		{
			for (int x = 0; x < n; x++)
			{
				for (int z = 0; z < n; z++)
				{
					for (Tile t = tiles[p][x][z]; t != null; t = t.getBridge())
					{
						int rl = t.getRenderLevel();
						if (rl < 0 || rl >= MAX_Z)
						{
							continue;
						}
						if (hasFloor(t))
						{
							floors[rl][x * n + z] = true;
						}
						WallObject w = t.getWallObject();
						if (w != null)
						{
							int type = w.getConfig() & 0x3f;
							if ((type == WALL_STRAIGHT || type == WALL_CORNER) && !isOpening.test(w.getId()))
							{
								closeEdges(walls[rl], n, x, z, w.getOrientationA() | w.getOrientationB());
							}
						}
					}
				}
			}
		}

		boolean[][] covered = new boolean[MAX_Z][n * n];
		cover(n, scene.getRoofs(), scene.getExtendedTileSettings(), scene.getTileHeights(), floors, covered);

		byte[] out = new byte[MAX_Z * n * n * 4];
		float[] values = new float[n * n];
		for (int level = 0; level < MAX_Z; level++)
		{
			propagate(n, covered[level], walls[level], values);
			quadrants(n, walls[level], values, out, level * n * n * 4);
		}
		data.skyVisibility = out;
	}

	private static boolean hasFloor(Tile t)
	{
		SceneTilePaint paint = t.getSceneTilePaint();
		return (paint != null && paint.getNeColor() != HIDDEN_HSL) || t.getSceneTileModel() != null;
	}

	/**
	 * Closes the given edges of tile (x, z) from both sides.
	 */
	static void closeEdges(byte[] walls, int n, int x, int z, int edges)
	{
		if ((edges & EDGE_W) != 0)
		{
			walls[x * n + z] |= EDGE_W;
			if (x > 0)
			{
				walls[(x - 1) * n + z] |= EDGE_E;
			}
		}
		if ((edges & EDGE_E) != 0)
		{
			walls[x * n + z] |= EDGE_E;
			if (x + 1 < n)
			{
				walls[(x + 1) * n + z] |= EDGE_W;
			}
		}
		if ((edges & EDGE_S) != 0)
		{
			walls[x * n + z] |= EDGE_S;
			if (z > 0)
			{
				walls[x * n + z - 1] |= EDGE_N;
			}
		}
		if ((edges & EDGE_N) != 0)
		{
			walls[x * n + z] |= EDGE_N;
			if (z + 1 < n)
			{
				walls[x * n + z + 1] |= EDGE_S;
			}
		}
	}

	/**
	 * Which tiles are covered: under a roof (roof ids, or the under-roof flag when the scene has none) or under a
	 * floor of a higher level at least {@link #FLOOR_CLEARANCE} above.
	 *
	 * @param roofs    the client's roof ids [level][x][z], or null
	 * @param settings the extended tile settings [level][x][z]
	 * @param heights  the tile heights [level][x][z] (y down)
	 * @param floors   per render level, flat x * n + z: the tile has a visible paint or a tile model
	 * @param covered  out, per level, flat x * n + z
	 */
	static void cover(int n, @Nullable int[][][] roofs, byte[][][] settings, int[][][] heights, boolean[][] floors, boolean[][] covered)
	{
		boolean useRoofs = roofs != null && anyNonZero(roofs);
		for (int x = 0; x < n; x++)
		{
			for (int z = 0; z < n; z++)
			{
				int i = x * n + z;
				for (int level = 0; level < MAX_Z; level++)
				{
					boolean c = useRoofs ? roofs[level][x][z] != 0 : (settings[level][x][z] & Constants.TILE_FLAG_UNDER_ROOF) != 0;
					int limit = heights[level][x][z] - FLOOR_CLEARANCE;
					for (int q = level + 1; q < MAX_Z && !c; q++)
					{
						c = floors[q][i] && heights[q][x][z] < limit;
					}
					covered[level][i] = c;
				}
			}
		}
	}

	private static boolean anyNonZero(int[][][] roofs)
	{
		for (int[][] plane : roofs)
		{
			for (int[] column : plane)
			{
				for (int v : column)
				{
					if (v != 0)
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Dial's buckets from the open tiles (cost 0): {@link #STEP} across an open edge, {@link #DIAGONAL} across a
	 * corner when both ways round it are open. values[i] = f^(cost/STEP), 0 beyond {@link #MAX_COST} or unreachable.
	 */
	static void propagate(int n, boolean[] covered, byte[] walls, float[] values)
	{
		final int count = n * n;
		final int INF = Integer.MAX_VALUE;
		int[] dist = new int[count];
		Arrays.fill(dist, INF);

		// bucket lists: head per cost, entries as parallel (node, next) arrays, grown as needed
		int[] head = new int[MAX_COST + 1];
		Arrays.fill(head, -1);
		int[] qNode = new int[count * 2];
		int[] qNext = new int[count * 2];
		int qLen = 0;

		for (int i = 0; i < count; i++)
		{
			if (!covered[i])
			{
				dist[i] = 0;
				qNode[qLen] = i;
				qNext[qLen] = head[0];
				head[0] = qLen++;
			}
		}

		for (int cost = 0; cost <= MAX_COST; cost++)
		{
			for (int e = head[cost]; e != -1; e = qNext[e])
			{
				int i = qNode[e];
				if (dist[i] != cost)
				{
					continue;
				}
				int x = i / n, z = i % n;
				int w = walls[i];
				boolean openW = (w & EDGE_W) == 0 && x > 0;
				boolean openE = (w & EDGE_E) == 0 && x + 1 < n;
				boolean openS = (w & EDGE_S) == 0 && z > 0;
				boolean openN = (w & EDGE_N) == 0 && z + 1 < n;

				int straight = cost + STEP;
				int diagonal = cost + DIAGONAL;
				for (int dir = 0; dir < 8; dir++)
				{
					int j, nd;
					switch (dir)
					{
						case 0:
							if (!openW)
							{
								continue;
							}
							j = i - n;
							nd = straight;
							break;
						case 1:
							if (!openE)
							{
								continue;
							}
							j = i + n;
							nd = straight;
							break;
						case 2:
							if (!openS)
							{
								continue;
							}
							j = i - 1;
							nd = straight;
							break;
						case 3:
							if (!openN)
							{
								continue;
							}
							j = i + 1;
							nd = straight;
							break;
						case 4: // NE: via E then N, and via N then E
							if (!openE || !openN || (walls[i + n] & EDGE_N) != 0 || (walls[i + 1] & EDGE_E) != 0)
							{
								continue;
							}
							j = i + n + 1;
							nd = diagonal;
							break;
						case 5: // SE
							if (!openE || !openS || (walls[i + n] & EDGE_S) != 0 || (walls[i - 1] & EDGE_E) != 0)
							{
								continue;
							}
							j = i + n - 1;
							nd = diagonal;
							break;
						case 6: // NW
							if (!openW || !openN || (walls[i - n] & EDGE_N) != 0 || (walls[i + 1] & EDGE_W) != 0)
							{
								continue;
							}
							j = i - n + 1;
							nd = diagonal;
							break;
						default: // SW
							if (!openW || !openS || (walls[i - n] & EDGE_S) != 0 || (walls[i - 1] & EDGE_W) != 0)
							{
								continue;
							}
							j = i - n - 1;
							nd = diagonal;
							break;
					}
					if (nd > MAX_COST || nd >= dist[j])
					{
						continue;
					}
					dist[j] = nd;
					if (qLen == qNode.length)
					{
						qNode = Arrays.copyOf(qNode, qLen * 2);
						qNext = Arrays.copyOf(qNext, qLen * 2);
					}
					qNode[qLen] = j;
					qNext[qLen] = head[nd];
					head[nd] = qLen++;
				}
			}
		}

		for (int i = 0; i < count; i++)
		{
			values[i] = dist[i] == INF ? 0 : VALUE_BY_COST[dist[i]];
		}
	}

	/**
	 * The per-tile corner values: at each tile corner, the touching tiles connected through open edges share the
	 * mean of their values. Written to out[offset + (x * n + z) * 4 + corner] as 0-255, corners in
	 * {@link SceneData#SKY_SW} order.
	 */
	static void quadrants(int n, byte[] walls, float[] values, byte[] out, int offset)
	{
		// the four tiles round a corner point (cx, cz), in the order SW, SE, NW, NE
		int[] tile = new int[4];
		int[] group = new int[4];
		float[] sum = new float[4];
		int[] cnt = new int[4];
		for (int cx = 0; cx <= n; cx++)
		{
			for (int cz = 0; cz <= n; cz++)
			{
				boolean hasW = cx > 0, hasE = cx < n, hasS = cz > 0, hasN = cz < n;
				tile[0] = hasW && hasS ? (cx - 1) * n + cz - 1 : -1;
				tile[1] = hasE && hasS ? cx * n + cz - 1 : -1;
				tile[2] = hasW && hasN ? (cx - 1) * n + cz : -1;
				tile[3] = hasE && hasN ? cx * n + cz : -1;
				for (int k = 0; k < 4; k++)
				{
					group[k] = k;
				}
				// SW-SE across SW's east edge, NW-NE across NW's east edge, SW-NW across SW's north edge, SE-NE across SE's
				if (tile[0] >= 0 && tile[1] >= 0 && (walls[tile[0]] & EDGE_E) == 0)
				{
					join(group, 0, 1);
				}
				if (tile[2] >= 0 && tile[3] >= 0 && (walls[tile[2]] & EDGE_E) == 0)
				{
					join(group, 2, 3);
				}
				if (tile[0] >= 0 && tile[2] >= 0 && (walls[tile[0]] & EDGE_N) == 0)
				{
					join(group, 0, 2);
				}
				if (tile[1] >= 0 && tile[3] >= 0 && (walls[tile[1]] & EDGE_N) == 0)
				{
					join(group, 1, 3);
				}
				Arrays.fill(sum, 0);
				Arrays.fill(cnt, 0);
				for (int k = 0; k < 4; k++)
				{
					if (tile[k] >= 0)
					{
						int g = find(group, k);
						sum[g] += values[tile[k]];
						cnt[g]++;
					}
				}
				// the corner point is the SW tile's NE corner, the SE tile's NW, the NW tile's SE and the NE tile's SW
				for (int k = 0; k < 4; k++)
				{
					if (tile[k] >= 0)
					{
						int g = find(group, k);
						int v = Math.round(sum[g] / cnt[g] * 255);
						out[offset + tile[k] * 4 + (3 - k)] = (byte) v;
					}
				}
			}
		}
	}

	private static void join(int[] group, int a, int b)
	{
		int ra = find(group, a), rb = find(group, b);
		if (ra != rb)
		{
			group[rb] = ra;
		}
	}

	private static int find(int[] group, int k)
	{
		while (group[k] != k)
		{
			k = group[k];
		}
		return k;
	}
}
