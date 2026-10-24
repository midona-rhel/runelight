package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The irradiance probe layout over the base scene and its hit cache, built once per region load on the global
 * illumination's worker (GlobalIllumination); relit by GiRelight.
 *
 * A probe holds the light it gathers from around it as directional irradiance (GiRelight: the mean radiance and its
 * gradient, so a surface reads the light that arrives on its own side) and how far it can see in each of the six axis
 * directions (VISIBILITY, below), which tells a pixel whether a wall or roof lies between it and the probe.
 *
 * Probes stand where geometry is, and nowhere else. Every tile of every floor that has one gets a ground probe,
 * GROUND_HEIGHT above the floor at the tile's center. Wherever a wall stands on a tile's edge (WallMap, closed doors
 * included), the tile gets wall probes in front of it, WALL_OFFSET out from the wall at WALL_FIRST above the floor and
 * then every WALL_SPACING as far up as the wall's voxels reach, so the wall's whole face has probes beside it, and the
 * color of the grass at its foot reaches it. A diagonal wall has them on both its sides, and a tile with a solid
 * object (a castle's wall, a tree, a statue) gives the tiles around it a few on that side. Walls that are not in the
 * collision map are covered by the ground probes only. Open air has no probes: one that is not within a voxel of
 * geometry is dropped, and a probe sitting in geometry, or seeing the back of surfaces in more than MAX_BACKFACE of
 * its rays (inside a wall, a table, the ground), is moved a little, and dropped if no candidate spot works. Every
 * probe traces its rays once through the voxel grid; what each ray hits (the sky, the back of something, or a surface
 * voxel) is kept, so relighting only has to light those voxels.
 *
 * The pixel's side of the lookup (gi.glsl) finds its probes through a list per tile: the LIST probes nearest the
 * tile's center, so it reads a handful of probes instead of searching.
 */
final class GiProbes
{
	static final int SIZE = 104;
	static final int FLOORS = 3;
	static final int TILE = 128;
	// the least two floors' grounds are apart
	static final float FLOOR_GAP = 240;
	// a ground probe's height above its floor
	static final float GROUND_HEIGHT = 48;
	// a wall probe's distance from the wall, its first height above the floor, the spacing of the ones above and the
	// height up to which they go at most
	static final float WALL_OFFSET = 48;
	static final float WALL_FIRST = 48;
	static final float WALL_SPACING = 112;
	static final float WALL_MAX_HEIGHT = 640;
	// the heights a solid object's sides get at most; they are many, and the object shades more than it bounces
	static final int SOLID_HEIGHTS = 2;
	// the walls' voxels are looked for at this spacing up the wall, the first this far below the floor, and a wall
	// that has not started by SCAN_START above it is no wall
	static final float SCAN = 32;
	static final float SCAN_START = 48;
	// how far toward its wall a probe may be moved from its nominal spot
	static final float WALL_APPROACH = 16;
	// probes a layout holds at most; the lists use NONE for no probe, so it is not the index of one
	static final int MAX_PROBES = 65535;
	static final int NONE = 65535;
	// entries of a tile's list of probes
	static final int LIST = 12;
	// a probe lights the pixels this near (gi.glsl), so a pixel's tile has the probes within this and half a tile's
	// diagonal of its center
	static final float RADIUS = 192;
	static final float LIST_REACH = RADIUS + TILE * 0.71f;
	// rays reach this many voxels; beyond, the sky
	static final float MAX_DISTANCE = 32;
	static final float MAX_BACKFACE = 0.25f;
	// a ray meeting a one-sided voxel at more than this cosine from behind saw its back
	static final float BACKFACE_COS = 0.2f;
	// The hit voxels' colors are pushed this far from their gray: a voxel averages the colors of everything in it,
	// the texture detail included, which pulls them toward gray, and light that bounces twice keeps the surfaces'
	// colors squared. The bounce is what carries the colors of the ground and the water into the shade.
	static final float ALBEDO_SATURATION = 1.5f;
	// ray results other than a hit voxel
	static final int MISS = -1, BACK = -2;

	// Rays spread evenly over the whole sphere, each counting the same: the ground below a probe is what bounces most
	// of the light (the green of grass onto the walls and people standing on it), so it counts like any other side.
	static final int RAY_SAMPLES = 40;
	// probes rotate their rays about the vertical by one of this many angles, so neighbours sample between each
	// other's directions instead of all missing the same thin gap
	static final int ROTATIONS = 16;
	// the rays that count, [rotation][ray * 3 + axis], and how much each counts (WEIGHT_SUM together)
	static final float[][] DIRECTIONS;
	static final float[] WEIGHT;
	static final int RAYS;
	static final float WEIGHT_SUM;
	// Floats per probe of how far it sees, in voxels, along each of the six axis directions (+x, -x, +y, -y, +z, -z):
	// the six means, then the six variances. A ray counts toward the bins of the sides it points to with the square of
	// its direction's component along that axis (the squares add up to 1, so a pixel blends the bins by the squares of
	// its own direction, as gi.glsl does); misses count as MAX_DISTANCE. A usable probe's means are all above zero.
	static final int VISIBILITY = 12;
	// an even spread over the whole sphere, to measure the sky with
	static final float[] SKY_RAYS = fibonacci(64);

	static
	{
		float[] all = fibonacci(RAY_SAMPLES);
		float[] weights = new float[RAY_SAMPLES];
		int[] kept = new int[RAY_SAMPLES];
		int n = 0;
		float sum = 0;
		for (int i = 0; i < RAY_SAMPLES; ++i)
		{
			weights[n] = 1;
			kept[n++] = i;
			sum += 1;
		}
		RAYS = n;
		WEIGHT = Arrays.copyOf(weights, n);
		WEIGHT_SUM = sum;
		DIRECTIONS = new float[ROTATIONS][n * 3];
		for (int r = 0; r < ROTATIONS; ++r)
		{
			double a = 2 * Math.PI * r / ROTATIONS;
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			for (int i = 0; i < n; ++i)
			{
				float x = all[kept[i] * 3], y = all[kept[i] * 3 + 1], z = all[kept[i] * 3 + 2];
				DIRECTIONS[r][i * 3] = c * x - s * z;
				DIRECTIONS[r][i * 3 + 1] = y;
				DIRECTIONS[r][i * 3 + 2] = s * x + c * z;
			}
		}
	}

	// where a probe may stand: its own spot and the spots around it, world units, y down, nearest first
	private static final float[][] CANDIDATES = {
		{0, 0, 0}, {0, -48, 0}, {40, 0, 0}, {-40, 0, 0}, {0, 0, 40}, {0, 0, -40}, {0, -96, 0},
		{40, -48, 40}, {-40, -48, -40}, {40, -48, -40}, {-40, -48, 40},
		{56, 0, 0}, {-56, 0, 0}, {0, 0, 56}, {0, 0, -56}, {0, -144, 0},
	};
	// a spot counts as much worse than its own as this share of its rays more seeing the back of something; the probe
	// keeps the nearest of the best
	private static final float WORSE_BACKFACE = 0.05f;

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
		// the scene's walls, null for none
		WallMap walls;
	}

	final GiGrid grid;
	// the candidate probes; the layout's floors' grounds are not kept
	int count;
	// per probe: where it stands (world units, y down; its nominal spot until it is traced) and the horizontal unit
	// direction from its wall to it, zero for a ground probe
	float[] position;
	float[] outward;
	boolean[] valid;
	// per probe and ray: a hit (index into the hit arrays), MISS or BACK
	int[] rays;
	// per probe: VISIBILITY floats, all zero for a probe that isn't usable
	float[] visibility;
	// the usable probes by the tile they stand in: tileProbes[tileStart[t]..tileStart[t + 1]) for tile z * SIZE + x
	int[] tileStart, tileProbes;
	// per tile, LIST probes near its center, nearest first, then NONE
	int[] lists;

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

	/** Spherical Fibonacci directions: count unit vectors spread evenly over the sphere, xyz interleaved. */
	static float[] fibonacci(int count)
	{
		float[] d = new float[count * 3];
		double golden = Math.PI * (3 - Math.sqrt(5));
		for (int i = 0; i < count; ++i)
		{
			double y = 1 - (i + 0.5) * 2.0 / count;
			double r = Math.sqrt(Math.max(0, 1 - y * y));
			double a = golden * i;
			d[i * 3] = (float) (Math.cos(a) * r);
			d[i * 3 + 1] = (float) y;
			d[i * 3 + 2] = (float) (Math.sin(a) * r);
		}
		return d;
	}

	/** The rotation a probe uses: a hash, so the pattern doesn't line up across the scene. */
	static int rotation(int probe)
	{
		int h = probe * 0x9E3779B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return h & (ROTATIONS - 1);
	}

	static GiProbes build(GiGrid grid, Input in, ParallelFor parallel)
	{
		GiProbes p = new GiProbes(grid);
		p.layout(in);
		p.trace(parallel);
		p.index();
		p.collectHits();
		return p;
	}

	/** The tile (z * SIZE + x) a world position lies in, clamped to the base scene. */
	static int tileOf(float x, float z)
	{
		int tx = Math.max(0, Math.min(SIZE - 1, (int) Math.floor(x / TILE)));
		int tz = Math.max(0, Math.min(SIZE - 1, (int) Math.floor(z / TILE)));
		return tz * SIZE + tx;
	}

	/** Lay out the candidate spots: ground probes first, so a scene with too many probes loses wall probes first. */
	private void layout(Input in)
	{
		int tiles = SIZE * SIZE;
		// each floor's ground per tile: a floor is at least FLOOR_GAP above the one below
		float[][] floor = new float[FLOORS][tiles];
		for (int c = 0; c < tiles; ++c)
		{
			floor[0][c] = in.ground[0][c];
			for (int f = 1; f < FLOORS; ++f)
			{
				float up = in.ground.length > f ? in.ground[f][c] : floor[f - 1][c] - FLOOR_GAP;
				floor[f][c] = Math.min(up, floor[f - 1][c] - FLOOR_GAP);
			}
		}
		position = new float[MAX_PROBES * 3];
		outward = new float[MAX_PROBES * 2];
		WallMap walls = in.walls;
		int offset = walls != null ? walls.offset : 0;
		for (int f = 0; f < FLOORS; ++f)
		{
			for (int z = 0; z < SIZE; ++z)
			{
				for (int x = 0; x < SIZE; ++x)
				{
					int c = z * SIZE + x;
					// a diagonal wall's tile has its probes on the two sides of the wall
					boolean blocked = walls != null && (walls.solid(f, x + offset, z + offset)
						|| WallMap.split(walls.get(f, x + offset, z + offset)));
					if (!blocked && hasFloor(x, z, floor[f][c]))
					{
						add((x + 0.5f) * TILE, floor[f][c] - GROUND_HEIGHT, (z + 0.5f) * TILE, 0, 0);
					}
				}
			}
		}
		if (walls != null)
		{
			for (int f = 0; f < FLOORS; ++f)
			{
				for (int z = 0; z < SIZE; ++z)
				{
					for (int x = 0; x < SIZE; ++x)
					{
						wallProbes(walls, floor, f, x, z);
					}
				}
			}
		}
		position = Arrays.copyOf(position, count * 3);
		outward = Arrays.copyOf(outward, count * 2);
	}

	private void add(float x, float y, float z, float outX, float outZ)
	{
		if (count < MAX_PROBES)
		{
			position[count * 3] = x;
			position[count * 3 + 1] = y;
			position[count * 3 + 2] = z;
			outward[count * 2] = outX;
			outward[count * 2 + 1] = outZ;
			count++;
		}
	}

	/** The wall probes of tile (x, z) on floor f: in front of each edge with a wall, a solid object or a diagonal wall. */
	private void wallProbes(WallMap walls, float[][] floor, int f, int x, int z)
	{
		int ex = x + walls.offset, ez = z + walls.offset;
		if (walls.solid(f, ex, ez))
		{
			return;
		}
		float ground = floor[f][z * SIZE + x];
		// the walls of a floor end at the next one, if that has them too
		float storey = f + 1 < FLOORS ? ground - floor[f + 1][z * SIZE + x] : WALL_MAX_HEIGHT;
		float cx = (x + 0.5f) * TILE, cz = (z + 0.5f) * TILE;
		for (int side : SIDES)
		{
			// the wall's side is toward the tile, its probes out from it into the tile
			float outX = side == WallMap.EAST ? -1 : side == WallMap.WEST ? 1 : 0;
			float outZ = side == WallMap.NORTH ? -1 : side == WallMap.SOUTH ? 1 : 0;
			int nx = ex - (int) outX, nz = ez - (int) outZ;
			boolean solid = walls.solid(f, nx, nz);
			if (!solid && !walls.edge(f, ex, ez, side))
			{
				continue;
			}
			boolean above = f + 1 < FLOORS && (solid ? walls.solid(f + 1, nx, nz) : walls.edge(f + 1, ex, ez, side));
			// the voxels on both sides of the edge, along the tile
			int edgeX = Math.round((cx - outX * TILE / 2) / GiGrid.VOXEL), edgeZ = Math.round((cz - outZ * TILE / 2) / GiGrid.VOXEL);
			float standing = standing(ground, outX != 0 ? edgeX - 1 : 2 * x, outX != 0 ? edgeX : 2 * x + 1,
				outZ != 0 ? edgeZ - 1 : 2 * z, outZ != 0 ? edgeZ : 2 * z + 1);
			wallColumn(cx + outX * (WALL_OFFSET - TILE / 2), cz + outZ * (WALL_OFFSET - TILE / 2), outX, outZ, ground,
				Math.min(standing, above ? storey : WALL_MAX_HEIGHT), solid ? SOLID_HEIGHTS : Integer.MAX_VALUE);
		}
		int bits = walls.get(f, ex, ez);
		if (WallMap.split(bits))
		{
			boolean above = f + 1 < FLOORS && WallMap.split(walls.get(f + 1, ex, ez));
			float standing = standing(ground, 2 * x, 2 * x + 1, 2 * z, 2 * z + 1);
			// the two sides of the diagonal: northwest and southeast of "/", northeast and southwest of "\"
			float d = 0.70710678f;
			float outX = (bits & WallMap.DIAG_SW_NE) != 0 ? -d : d;
			for (int sign = -1; sign <= 1; sign += 2)
			{
				wallColumn(cx + sign * outX * WALL_OFFSET, cz + sign * d * WALL_OFFSET, sign * outX, sign * d, ground,
					Math.min(standing, above ? storey : WALL_MAX_HEIGHT), Integer.MAX_VALUE);
			}
		}
	}

	private static final int[] SIDES = {WallMap.NORTH, WallMap.EAST, WallMap.SOUTH, WallMap.WEST};

	/** A wall's probes at (x, z), out from the wall by (outX, outZ): from WALL_FIRST every WALL_SPACING up to height. */
	private void wallColumn(float x, float z, float outX, float outZ, float ground, float height, int most)
	{
		int n = 0;
		for (float h = WALL_FIRST; h <= height && n < most; h += WALL_SPACING, ++n)
		{
			add(x, ground - h, z, outX, outZ);
		}
	}

	/**
	 * How high geometry stands above ground (world units; at most WALL_MAX_HEIGHT, a little less) in the voxel columns
	 * x0..x1, z0..z1, going up from the ground while some voxel of the columns is filled; 0 if none is.
	 */
	private float standing(float ground, int x0, int x1, int z0, int z1)
	{
		float top = 0;
		boolean started = false;
		for (float h = -SCAN / 2; h <= WALL_MAX_HEIGHT; h += SCAN)
		{
			int y = (int) Math.floor((ground - h) / GiGrid.VOXEL) - grid.layer0;
			boolean filled = false;
			for (int vx = x0; vx <= x1 && !filled; ++vx)
			{
				for (int vz = z0; vz <= z1 && !filled; ++vz)
				{
					filled = grid.occupied(vx, y, vz);
				}
			}
			if (filled)
			{
				top = h;
				started = true;
			}
			else if (started || h > SCAN_START)
			{
				break;
			}
		}
		return top;
	}

	/**
	 * Whether a tile has a floor at height ground (world y): some geometry in its tile within a voxel of it. Without,
	 * as in the open air of the upper floors, there is nothing to put a probe over.
	 */
	private boolean hasFloor(int x, int z, float ground)
	{
		int y0 = Math.floorDiv((int) (ground - GiGrid.VOXEL), GiGrid.VOXEL) - grid.layer0;
		int y1 = Math.floorDiv((int) (ground + GiGrid.VOXEL / 2), GiGrid.VOXEL) - grid.layer0;
		for (int vx = x * 2; vx < x * 2 + 2; ++vx)
		{
			for (int vz = z * 2; vz < z * 2 + 2; ++vz)
			{
				for (int y = y0; y <= y1; ++y)
				{
					if (grid.occupied(vx, y, vz))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	private void trace(ParallelFor parallel)
	{
		valid = new boolean[count];
		rays = new int[count * RAYS];
		visibility = new float[count * VISIBILITY];
		parallel.run(count, this::traceProbe);
	}

	/**
	 * Find the best spot for probe i and trace its rays; leaves it invalid if none works. Every candidate spot in the
	 * base scene, out of the geometry and near it, not much nearer its wall, is traced, and the probe takes the one
	 * whose rays see the backs of the fewest surfaces (the one most in the open, with the most of the scene in front
	 * of it), the nearest of those within WORSE_BACKFACE of the best.
	 */
	void traceProbe(int i)
	{
		float[] dirs = DIRECTIONS[rotation(i)];
		int base = i * RAYS;
		float bx = position[i * 3], by = position[i * 3 + 1], bz = position[i * 3 + 2];
		float outX = outward[i * 2], outZ = outward[i * 2 + 1];
		int[] candidateRays = new int[RAYS], bestRays = new int[RAYS];
		float[] candidateDistance = new float[RAYS], bestDistance = new float[RAYS];
		int best = -1, bestBack = Integer.MAX_VALUE;
		for (int k = 0; k < CANDIDATES.length; ++k)
		{
			float[] c = CANDIDATES[k];
			float wx = bx + c[0], wz = bz + c[2];
			if (c[0] * outX + c[2] * outZ < -WALL_APPROACH || wx < 0 || wz < 0 || wx >= SIZE * TILE || wz >= SIZE * TILE)
			{
				continue;
			}
			float ox = wx / GiGrid.VOXEL, oy = (by + c[1]) / GiGrid.VOXEL - grid.layer0, oz = wz / GiGrid.VOXEL;
			if (!nearGeometry(ox, oy, oz) || grid.occupied((int) Math.floor(ox), (int) Math.floor(oy), (int) Math.floor(oz)))
			{
				continue;
			}
			int back = 0;
			for (int r = 0; r < RAYS; ++r)
			{
				float dx = dirs[r * 3], dy = dirs[r * 3 + 1], dz = dirs[r * 3 + 2];
				int cell = grid.trace(ox, oy, oz, dx, dy, dz, MAX_DISTANCE, false, base + r);
				int result = cell < 0 ? MISS : backface(grid.cells[cell], dx, dy, dz) ? BACK : cell;
				candidateDistance[r] = cell < 0 ? MAX_DISTANCE : Math.min(MAX_DISTANCE, (float) Math.sqrt(
					sq(grid.cellX(cell) + 0.5f - ox) + sq(grid.cellY(cell) + 0.5f - oy) + sq(grid.cellZ(cell) + 0.5f - oz)));
				if (result == BACK)
				{
					back++;
				}
				candidateRays[r] = result;
			}
			if (back > MAX_BACKFACE * RAYS || back >= bestBack - WORSE_BACKFACE * RAYS)
			{
				continue;
			}
			best = k;
			bestBack = back;
			int[] swapRays = bestRays;
			bestRays = candidateRays;
			candidateRays = swapRays;
			float[] swapDistance = bestDistance;
			bestDistance = candidateDistance;
			candidateDistance = swapDistance;
		}
		if (best < 0)
		{
			Arrays.fill(rays, base, base + RAYS, MISS);
			return;
		}
		float[] c = CANDIDATES[best];
		System.arraycopy(bestRays, 0, rays, base, RAYS);
		valid[i] = true;
		measureVisibility(i, dirs, bestDistance);
		position[i * 3] = bx + c[0];
		position[i * 3 + 1] = by + c[1];
		position[i * 3 + 2] = bz + c[2];
	}

	private static float sq(float v)
	{
		return v * v;
	}

	/** Probe i's visibility bins from the distances its rays travelled (VISIBILITY). */
	private void measureVisibility(int i, float[] dirs, float[] distance)
	{
		int at = i * VISIBILITY;
		float[] weight = new float[6];
		for (int pass = 0; pass < 2; ++pass)
		{
			float[] sum = new float[6];
			for (int r = 0; r < RAYS; ++r)
			{
				for (int axis = 0; axis < 3; ++axis)
				{
					float c = dirs[r * 3 + axis];
					int bin = axis * 2 + (c < 0 ? 1 : 0);
					float w = c * c;
					if (pass == 0)
					{
						weight[bin] += w;
						sum[bin] += w * distance[r];
					}
					else
					{
						sum[bin] += w * sq(distance[r] - visibility[at + bin]);
					}
				}
			}
			for (int bin = 0; bin < 6; ++bin)
			{
				visibility[at + pass * 6 + bin] = sum[bin] / Math.max(weight[bin], 1e-6f);
			}
		}
	}

	/** Whether a filled voxel lies within a voxel of the point (voxel units), which is what keeps probes out of open air. */
	boolean nearGeometry(float x, float y, float z)
	{
		for (int iz = (int) Math.floor(z - 1); iz <= (int) Math.floor(z + 1); ++iz)
		for (int iy = (int) Math.floor(y - 1); iy <= (int) Math.floor(y + 1); ++iy)
		for (int ix = (int) Math.floor(x - 1); ix <= (int) Math.floor(x + 1); ++ix)
		{
			float dx = Math.max(Math.max(ix - x, x - ix - 1), 0);
			float dy = Math.max(Math.max(iy - y, y - iy - 1), 0);
			float dz = Math.max(Math.max(iz - z, z - iz - 1), 0);
			if (dx * dx + dy * dy + dz * dz <= 1 && grid.occupied(ix, iy, iz)) return true;
		}
		return false;
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
			saturate(GiGrid.albedo(cell, 0), GiGrid.albedo(cell, 1), GiGrid.albedo(cell, 2), hitAlbedo, h * 3);
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

	/** Linear color (r, g, b) moved ALBEDO_SATURATION times as far from its luminance, same luminance, into out[at..]. */
	static void saturate(float r, float g, float b, float[] out, int at)
	{
		float l = GiRelight.luminance(r, g, b);
		out[at] = Math.max(0, l + (r - l) * ALBEDO_SATURATION);
		out[at + 1] = Math.max(0, l + (g - l) * ALBEDO_SATURATION);
		out[at + 2] = Math.max(0, l + (b - l) * ALBEDO_SATURATION);
	}

	/** The usable probe nearest a world position, looking in its tile and then the ones around; -1 if none. */
	int nearestProbe(float wx, float wy, float wz)
	{
		int tile = tileOf(wx, wz);
		int cx = tile % SIZE, cz = tile / SIZE;
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
					for (int k = tileStart[z * SIZE + x]; k < tileStart[z * SIZE + x + 1]; ++k)
					{
						int i = tileProbes[k];
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
	 * File the usable probes by tile, and give every tile its list: the LIST probes nearest its center within
	 * LIST_REACH, nearest first (by the distance along the ground, so each floor's probes of the tile come before the
	 * neighbours'). A pixel in the tile finds its probes there.
	 */
	private void index()
	{
		int tiles = SIZE * SIZE;
		tileStart = new int[tiles + 1];
		for (int i = 0; i < count; ++i)
		{
			if (valid[i])
			{
				tileStart[tileOf(position[i * 3], position[i * 3 + 2]) + 1]++;
			}
		}
		for (int t = 0; t < tiles; ++t)
		{
			tileStart[t + 1] += tileStart[t];
		}
		tileProbes = new int[tileStart[tiles]];
		int[] fill = Arrays.copyOf(tileStart, tiles);
		for (int i = 0; i < count; ++i)
		{
			if (valid[i])
			{
				tileProbes[fill[tileOf(position[i * 3], position[i * 3 + 2])]++] = i;
			}
		}
		lists = new int[tiles * LIST];
		Arrays.fill(lists, NONE);
		float[] near = new float[LIST];
		int reach = (int) Math.ceil(LIST_REACH / TILE);
		for (int z = 0; z < SIZE; ++z)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				float cx = (x + 0.5f) * TILE, cz = (z + 0.5f) * TILE;
				int at = (z * SIZE + x) * LIST;
				int n = 0;
				for (int tz = Math.max(0, z - reach); tz <= Math.min(SIZE - 1, z + reach); ++tz)
				{
					for (int tx = Math.max(0, x - reach); tx <= Math.min(SIZE - 1, x + reach); ++tx)
					{
						for (int k = tileStart[tz * SIZE + tx]; k < tileStart[tz * SIZE + tx + 1]; ++k)
						{
							int i = tileProbes[k];
							float d = sq(position[i * 3] - cx) + sq(position[i * 3 + 2] - cz);
							if (d > LIST_REACH * LIST_REACH || n == LIST && d >= near[LIST - 1])
							{
								continue;
							}
							int e = Math.min(n, LIST - 1);
							while (e > 0 && near[e - 1] > d)
							{
								near[e] = near[e - 1];
								lists[at + e] = lists[at + e - 1];
								e--;
							}
							near[e] = d;
							lists[at + e] = i;
							n = Math.min(n + 1, LIST);
						}
					}
				}
			}
		}
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
}
