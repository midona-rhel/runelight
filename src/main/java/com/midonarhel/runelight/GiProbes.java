package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The irradiance probe layout over the base scene and its hit cache, built once per region load on the global
 * illumination's worker (GlobalIllumination); relit by GiRelight.
 *
 * One probe column per tile, three layers in each: LAYER0_HEIGHT above the ground, one above the first floor (at
 * least LAYER_GAP above the first layer) and a high outdoor one. A probe sitting in geometry, or seeing the back of
 * surfaces in more than MAX_BACKFACE of its rays (inside a wall, a table, the ground), is moved a little; if no
 * candidate spot works it is disabled. Every probe traces its rays once through the voxel grid; what each ray hits
 * (the sky, the back of something, or a surface voxel) is kept, so relighting only has to light those voxels.
 *
 * Probes are indoor or outdoor: indoor when their column belongs to a building (BuildingDetector rooms) and they
 * are below its roof, the top of the geometry over the column. Pixels use only probes of their own class (gi.glsl),
 * so walls don't leak daylight inside or darkness outside. Light through a door still gets in: indoor probes'
 * rays leave through the doorway and see the sky and the sunlit ground there.
 */
final class GiProbes
{
	static final int SIZE = 104;
	static final int LAYERS = 3;
	static final int PROBES = SIZE * SIZE * LAYERS;
	static final int TILE = 128;
	static final float LAYER0_HEIGHT = 96;
	static final float LAYER_GAP = 192;
	static final float HIGH_HEIGHT = 512;
	// rays reach this many voxels; beyond, the sky
	static final float MAX_DISTANCE = 64;
	static final float MAX_BACKFACE = 0.25f;
	// a ray meeting a one-sided voxel at more than this cosine from behind saw its back
	static final float BACKFACE_COS = 0.2f;
	// roofs are looked for up to this high above the first layer
	static final float ROOF_SEARCH = 16 * TILE;
	// meta's roof for columns that aren't indoors: below everything (y down)
	static final float NO_ROOF = 131072;
	// ray results other than a hit voxel
	static final int MISS = -1, BACK = -2;

	// where a probe may move when its spot is bad: world units, y down
	private static final float[][] CANDIDATES = {
		{0, 0, 0}, {0, -48, 0}, {40, 0, 0}, {-40, 0, 0}, {0, 0, 40}, {0, 0, -40}, {0, -96, 0},
		{40, -48, 40}, {-40, -48, -40}, {40, -48, -40}, {-40, -48, 40},
	};

	/** Runs body(0..n-1), possibly in parallel. */
	interface ParallelFor
	{
		void run(int n, IntConsumer body);

		ParallelFor SEQUENTIAL = (n, body) ->
		{
			for (int i = 0; i < n; ++i)
			{
				body.accept(i);
			}
		};
	}

	/** Scene data the layout needs, copied on the client thread. */
	static final class Input
	{
		// ground height at each base tile's center per map level, [level][z * SIZE + x], world y
		float[][] ground;
		// base tiles that belong to a building
		boolean[] indoor;
	}

	final GiGrid grid;
	// per column: layer heights (world y) and roof + validity bits / 16 (gi.glsl)
	final float[] meta = new float[SIZE * SIZE * 4];
	final float[] position = new float[PROBES * 3];
	final boolean[] valid = new boolean[PROBES];
	final boolean[] indoor = new boolean[PROBES];
	// per probe and ray: a hit (index into the hit arrays), MISS or BACK
	final int[] rays = new int[PROBES * GiSh.RAYS];
	final float[][] directions = GiSh.rotatedRays();

	// the voxels hit by any ray
	int hits;
	int[] hitCell;
	float[] hitAlbedo, hitNormal, hitPos;
	boolean[] hitTwoSided;
	// probe whose light a hit voxel reflects for the next bounce, -1 for none
	int[] hitBounce;

	private GiProbes(GiGrid grid)
	{
		this.grid = grid;
	}

	static int probe(int x, int z, int layer)
	{
		return (layer * SIZE + z) * SIZE + x;
	}

	static GiProbes build(GiGrid grid, Input in, ParallelFor parallel)
	{
		GiProbes p = new GiProbes(grid);
		p.layout(in);
		p.trace(parallel);
		p.collectHits();
		return p;
	}

	private void layout(Input in)
	{
		for (int z = 0; z < SIZE; ++z)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				int c = z * SIZE + x;
				float g0 = in.ground[0][c];
				float g1 = in.ground.length > 1 ? in.ground[1][c] : g0;
				float h0 = g0 - LAYER0_HEIGHT;
				float h1 = Math.min(g1 - LAYER0_HEIGHT, h0 - LAYER_GAP);
				float h2 = Math.min(h1 - LAYER_GAP, h0 - HIGH_HEIGHT);
				float roof = in.indoor != null && in.indoor[c] ? roofTop(x, z, h0) : NO_ROOF;
				meta[c * 4] = h0;
				meta[c * 4 + 1] = h1;
				meta[c * 4 + 2] = h2;
				meta[c * 4 + 3] = roof;
				float[] h = {h0, h1, h2};
				for (int l = 0; l < LAYERS; ++l)
				{
					int i = probe(x, z, l);
					position[i * 3] = (x + 0.5f) * TILE;
					position[i * 3 + 1] = h[l];
					position[i * 3 + 2] = (z + 0.5f) * TILE;
					indoor[i] = h[l] > roof;
				}
			}
		}
	}

	/**
	 * The roof line of a building column, world y: the bottom of the topmost voxel over it, searched from the first
	 * layer up (the roof's outer surface lies within that voxel); NO_ROOF when nothing is there.
	 */
	float roofTop(int x, int z, float from)
	{
		int top = Integer.MAX_VALUE;
		int yStart = (int) Math.floor(from / GiGrid.VOXEL) - grid.layer0;
		int yEnd = (int) Math.floor((from - ROOF_SEARCH) / GiGrid.VOXEL) - grid.layer0;
		for (int vx = x * 2; vx <= x * 2 + 1; ++vx)
		{
			for (int vz = z * 2; vz <= z * 2 + 1; ++vz)
			{
				for (int y = Math.max(0, yEnd); y <= Math.min(grid.ny - 1, yStart); ++y)
				{
					if (grid.occupied(vx, y, vz))
					{
						top = Math.min(top, y);
						break;
					}
				}
			}
		}
		return top == Integer.MAX_VALUE ? NO_ROOF : (top + 1 + grid.layer0) * (float) GiGrid.VOXEL;
	}

	private void trace(ParallelFor parallel)
	{
		parallel.run(SIZE * LAYERS, row ->
		{
			for (int x = 0; x < SIZE; ++x)
			{
				int i = row * SIZE + x;
				traceProbe(i);
			}
		});
		// validity bits into the meta, after the roof: bits / 16 in its fraction (the roof is a whole number)
		for (int c = 0; c < SIZE * SIZE; ++c)
		{
			int bits = 0;
			for (int l = 0; l < LAYERS; ++l)
			{
				if (valid[l * SIZE * SIZE + c])
				{
					bits |= 1 << l;
				}
			}
			meta[c * 4 + 3] += bits / 16f;
		}
	}

	/** Find a spot for probe i and trace its rays; leaves it invalid if none works. */
	void traceProbe(int i)
	{
		float[] dirs = directions[GiSh.rotation(i)];
		int base = i * GiSh.RAYS;
		float bx = position[i * 3], by = position[i * 3 + 1], bz = position[i * 3 + 2];
		for (float[] c : CANDIDATES)
		{
			float ox = (bx + c[0]) / GiGrid.VOXEL, oy = (by + c[1]) / GiGrid.VOXEL - grid.layer0, oz = (bz + c[2]) / GiGrid.VOXEL;
			if (grid.occupied((int) Math.floor(ox), (int) Math.floor(oy), (int) Math.floor(oz)))
			{
				continue;
			}
			int back = 0;
			for (int r = 0; r < GiSh.RAYS && back <= MAX_BACKFACE * GiSh.RAYS; ++r)
			{
				float dx = dirs[r * 3], dy = dirs[r * 3 + 1], dz = dirs[r * 3 + 2];
				int cell = grid.trace(ox, oy, oz, dx, dy, dz, MAX_DISTANCE, false, base + r);
				int result = cell < 0 ? MISS : backface(grid.cells[cell], dx, dy, dz) ? BACK : cell;
				if (result == BACK)
				{
					back++;
				}
				rays[base + r] = result;
			}
			if (back <= MAX_BACKFACE * GiSh.RAYS)
			{
				valid[i] = true;
				position[i * 3] = bx + c[0];
				position[i * 3 + 1] = by + c[1];
				position[i * 3 + 2] = bz + c[2];
				return;
			}
		}
		Arrays.fill(rays, base, base + GiSh.RAYS, MISS);
	}

	static boolean backface(int cell, float dx, float dy, float dz)
	{
		if (GiGrid.twoSided(cell))
		{
			return false;
		}
		float[] n = GiGrid.normal(cell);
		return n[0] * dx + n[1] * dy + n[2] * dz > BACKFACE_COS;
	}

	/** Number the voxels the rays hit and gather what lighting them needs. */
	private void collectHits()
	{
		int[] cellToHit = new int[grid.cells.length];
		Arrays.fill(cellToHit, -1);
		int n = 0;
		for (int i = 0; i < rays.length; ++i)
		{
			int c = rays[i];
			if (c < 0)
			{
				continue;
			}
			if (cellToHit[c] < 0)
			{
				cellToHit[c] = n++;
			}
			rays[i] = cellToHit[c];
		}
		hits = n;
		hitCell = new int[n];
		hitAlbedo = new float[n * 3];
		hitNormal = new float[n * 3];
		hitPos = new float[n * 3];
		hitTwoSided = new boolean[n];
		hitBounce = new int[n];
		for (int c = 0; c < cellToHit.length; ++c)
		{
			int h = cellToHit[c];
			if (h < 0)
			{
				continue;
			}
			int cell = grid.cells[c];
			hitCell[h] = c;
			for (int k = 0; k < 3; ++k)
			{
				hitAlbedo[h * 3 + k] = GiGrid.albedo(cell, k);
			}
			float[] nn = GiGrid.normal(cell);
			System.arraycopy(nn, 0, hitNormal, h * 3, 3);
			hitTwoSided[h] = GiGrid.twoSided(cell);
			hitPos[h * 3] = grid.cellX(c) + 0.5f;
			hitPos[h * 3 + 1] = grid.cellY(c) + 0.5f;
			hitPos[h * 3 + 2] = grid.cellZ(c) + 0.5f;
			// the probe in front of the surface
			float wx = hitPos[h * 3] * GiGrid.VOXEL + nn[0] * 48;
			float wy = (hitPos[h * 3 + 1] + grid.layer0) * GiGrid.VOXEL + nn[1] * 48;
			float wz = hitPos[h * 3 + 2] * GiGrid.VOXEL + nn[2] * 48;
			hitBounce[h] = nearestProbe(wx, wy, wz);
		}
	}

	/** The valid probe nearest a world position, looking in its column and then the ones around; -1 if none. */
	int nearestProbe(float wx, float wy, float wz)
	{
		int cx = Math.max(0, Math.min(SIZE - 1, (int) Math.floor(wx / TILE)));
		int cz = Math.max(0, Math.min(SIZE - 1, (int) Math.floor(wz / TILE)));
		for (int ring = 0; ring <= 2; ++ring)
		{
			int best = -1;
			float bestD = Float.MAX_VALUE;
			for (int z = cz - ring; z <= cz + ring; ++z)
			{
				for (int x = cx - ring; x <= cx + ring; ++x)
				{
					if (x < 0 || z < 0 || x >= SIZE || z >= SIZE || Math.max(Math.abs(x - cx), Math.abs(z - cz)) != ring)
					{
						continue;
					}
					for (int l = 0; l < LAYERS; ++l)
					{
						int i = probe(x, z, l);
						if (!valid[i])
						{
							continue;
						}
						float dx = position[i * 3] - wx, dy = position[i * 3 + 1] - wy, dz = position[i * 3 + 2] - wz;
						float d = dx * dx + dy * dy + dz * dz;
						if (d < bestD)
						{
							bestD = d;
							best = i;
						}
					}
				}
			}
			if (best >= 0)
			{
				return best;
			}
		}
		return -1;
	}

	/**
	 * Ground height at each base tile's center, [level][z * SIZE + x], from the scene's corner heights
	 * ([level][extended x][extended z]).
	 */
	static float[][] groundHeights(int[][][] heights, int offset)
	{
		int levels = heights.length;
		float[][] out = new float[levels][SIZE * SIZE];
		for (int l = 0; l < levels; ++l)
		{
			int[][] h = heights[l];
			for (int z = 0; z < SIZE; ++z)
			{
				for (int x = 0; x < SIZE; ++x)
				{
					int ex = x + offset, ez = z + offset;
					if (ex + 1 < h.length && ez + 1 < h[ex].length)
					{
						out[l][z * SIZE + x] = (h[ex][ez] + h[ex + 1][ez] + h[ex][ez + 1] + h[ex + 1][ez + 1]) / 4f;
					}
				}
			}
		}
		return out;
	}

	/** Base tiles in any detected building room. */
	static boolean[] indoorColumns(BuildingDetector.Result rooms, int offset)
	{
		boolean[] out = new boolean[SIZE * SIZE];
		for (BuildingDetector.Room room : rooms.rooms)
		{
			for (int t : room.tiles)
			{
				int x = (t >>> 16) - offset, z = (t & 0xffff) - offset;
				if (x >= 0 && z >= 0 && x < SIZE && z < SIZE)
				{
					out[z * SIZE + x] = true;
				}
			}
		}
		return out;
	}
}
