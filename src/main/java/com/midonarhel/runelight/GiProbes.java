package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The irradiance probe layout over the base scene and its hit cache, built once per region load on the global
 * illumination's worker (GlobalIllumination); relit by GiRelight.
 *
 * A probe holds one color: the light it gathers from around it, averaged, with no direction kept. Columns of probes
 * stand every PITCH tiles, and each of a column's FLOORS floors (the scene's planes) has two: LOW and HIGH above
 * that floor's ground. A probe sitting in geometry, or seeing the back of surfaces in more than MAX_BACKFACE of its
 * rays (inside a wall, a table, the ground), is moved a little; if no candidate spot works it is disabled, as are
 * the probes of floors the column has no floor on. Every probe traces its rays once through the voxel grid; what
 * each ray hits (the sky, the back of something, or a surface voxel) is kept, so relighting only has to light those
 * voxels.
 *
 * Probes are indoor or outdoor: indoor when their column belongs to a building (BuildingDetector rooms) and they
 * are below its roof, the top of the geometry over the column. Pixels use only probes of their own class (gi.glsl),
 * so walls don't leak daylight inside or darkness outside. Light through a door still gets in: indoor probes'
 * rays leave through the doorway and see the sky and the sunlit ground there.
 */
final class GiProbes
{
	static final int SIZE = 104;
	static final int PITCH = 1;
	static final int COLUMNS = SIZE / PITCH;
	static final int FLOORS = 3;
	static final int LEVELS = FLOORS * 2;
	static final int PROBES = COLUMNS * COLUMNS * LEVELS;
	static final int TILE = 128;
	// the two probes of a floor, above its ground: a quarter and three quarters of the storey
	static final float LOW = 60;
	static final float HIGH = 180;
	// the least two floors' grounds are apart, so the probes stay in order
	static final float FLOOR_GAP = 240;
	// rays reach this many voxels; beyond, the sky
	static final float MAX_DISTANCE = 32;
	static final float MAX_BACKFACE = 0.25f;
	// a ray meeting a one-sided voxel at more than this cosine from behind saw its back
	static final float BACKFACE_COS = 0.2f;
	// roofs are looked for up to this high above the first probe
	static final float ROOF_SEARCH = 16 * TILE;
	// meta's roof for columns that aren't indoors: below everything (y down)
	static final float NO_ROOF = 131072;
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
	// per tile: the grounds of the floors (world y, FLOOR_GAP apart at least) and the roof line (gi.glsl)
	final float[] meta = new float[SIZE * SIZE * 4];
	final float[] position = new float[PROBES * 3];
	final boolean[] valid = new boolean[PROBES];
	final boolean[] indoor = new boolean[PROBES];
	// per probe and ray: a hit (index into the hit arrays), MISS or BACK
	final int[] rays = new int[PROBES * RAYS];

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

	/** Probe of column (cx, cz) at a level: the low probe of floor f is level 2 * f, its high one 2 * f + 1. */
	static int probe(int cx, int cz, int level)
	{
		return (level * COLUMNS + cz) * COLUMNS + cx;
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

	/** The rotation a probe uses: a hash, so the pattern doesn't line up across the grid. */
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
		p.collectHits();
		return p;
	}

	private void layout(Input in)
	{
		float[] g = new float[FLOORS];
		for (int z = 0; z < SIZE; ++z)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				int c = z * SIZE + x;
				g[0] = in.ground[0][c];
				for (int f = 1; f < FLOORS; ++f)
				{
					float up = in.ground.length > f ? in.ground[f][c] : g[f - 1] - FLOOR_GAP;
					g[f] = Math.min(up, g[f - 1] - FLOOR_GAP);
				}
				meta[c * 4] = g[0];
				meta[c * 4 + 1] = g[1];
				meta[c * 4 + 2] = g[2];
				meta[c * 4 + 3] = in.indoor != null && in.indoor[c] ? roofTop(x, z, g[0] - LOW) : NO_ROOF;
			}
		}
		for (int cz = 0; cz < COLUMNS; ++cz)
		{
			for (int cx = 0; cx < COLUMNS; ++cx)
			{
				int c = cz * PITCH * SIZE + cx * PITCH;
				for (int level = 0; level < LEVELS; ++level)
				{
					int i = probe(cx, cz, level);
					float h = meta[c * 4 + level / 2] - (level % 2 == 0 ? LOW : HIGH);
					position[i * 3] = (cx * PITCH + 0.5f) * TILE;
					position[i * 3 + 1] = h;
					position[i * 3 + 2] = (cz * PITCH + 0.5f) * TILE;
					indoor[i] = h > meta[c * 4 + 3];
				}
			}
		}
	}

	/**
	 * The roof line of a building column, world y: the bottom of the topmost voxel over it, searched from the given
	 * height up (the roof's outer surface lies within that voxel); NO_ROOF when nothing is there.
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

	/**
	 * Whether a column has a floor at height ground (world y): some geometry in its tiles within a voxel of it. The
	 * ground floor always has one; the others are mostly open air, and probes there would only see the sky.
	 */
	private boolean hasFloor(int cx, int cz, float ground, int floor)
	{
		if (floor == 0)
		{
			return true;
		}
		int y0 = Math.floorDiv((int) (ground - GiGrid.VOXEL), GiGrid.VOXEL) - grid.layer0;
		int y1 = Math.floorDiv((int) (ground + GiGrid.VOXEL / 2), GiGrid.VOXEL) - grid.layer0;
		int span = PITCH * 2;
		for (int vx = cx * span; vx < (cx + 1) * span; ++vx)
		{
			for (int vz = cz * span; vz < (cz + 1) * span; ++vz)
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
		parallel.run(COLUMNS * LEVELS, row ->
		{
			int level = row / COLUMNS, cz = row % COLUMNS;
			for (int cx = 0; cx < COLUMNS; ++cx)
			{
				int i = row * COLUMNS + cx;
				float ground = meta[(cz * PITCH * SIZE + cx * PITCH) * 4 + level / 2];
				if (hasFloor(cx, cz, ground, level / 2))
				{
					traceProbe(i);
				}
				else
				{
					Arrays.fill(rays, i * RAYS, (i + 1) * RAYS, MISS);
				}
			}
		});
	}

	/** Find a spot for probe i and trace its rays; leaves it invalid if none works. */
	void traceProbe(int i)
	{
		float[] dirs = DIRECTIONS[rotation(i)];
		int base = i * RAYS;
		float bx = position[i * 3], by = position[i * 3 + 1], bz = position[i * 3 + 2];
		for (float[] c : CANDIDATES)
		{
			float ox = (bx + c[0]) / GiGrid.VOXEL, oy = (by + c[1]) / GiGrid.VOXEL - grid.layer0, oz = (bz + c[2]) / GiGrid.VOXEL;
			if (!nearGeometry(ox, oy, oz) || grid.occupied((int) Math.floor(ox), (int) Math.floor(oy), (int) Math.floor(oz)))
			{
				continue;
			}
			int back = 0;
			for (int r = 0; r < RAYS && back <= MAX_BACKFACE * RAYS; ++r)
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
			if (back <= MAX_BACKFACE * RAYS)
			{
				valid[i] = true;
				position[i * 3] = bx + c[0];
				position[i * 3 + 1] = by + c[1];
				position[i * 3 + 2] = bz + c[2];
				return;
			}
		}
		Arrays.fill(rays, base, base + RAYS, MISS);
	}

	// Keep probes within one tile of captured scenery voxels, including the relocated origin.
	private boolean nearGeometry(float x, float y, float z)
	{
		for (int iz = (int) Math.floor(z - 2); iz <= (int) Math.floor(z + 2); ++iz)
		for (int iy = (int) Math.floor(y - 2); iy <= (int) Math.floor(y + 2); ++iy)
		for (int ix = (int) Math.floor(x - 2); ix <= (int) Math.floor(x + 2); ++ix)
		{
			float dx = Math.max(Math.max(ix - x, x - ix - 1), 0);
			float dy = Math.max(Math.max(iy - y, y - iy - 1), 0);
			float dz = Math.max(Math.max(iz - z, z - iz - 1), 0);
			if (dx * dx + dy * dy + dz * dz <= 4 && grid.occupied(ix, iy, iz)) return true;
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

	/**
	 * Each valid probe's light averaged with its valid neighbours' of the same level and class (indoor or outdoor), the
	 * probe itself counting twice: a probe's few dozen rays see a noisy sample of what is around it, and one lucky ray
	 * on a bright wall would otherwise show as a lit patch two tiles wide.
	 */
	static float[] smooth(GiProbes p, float[] light)
	{
		float[] out = light.clone();
		for (int level = 0; level < LEVELS; ++level)
		{
			for (int cz = 0; cz < COLUMNS; ++cz)
			{
				for (int cx = 0; cx < COLUMNS; ++cx)
				{
					int i = probe(cx, cz, level);
					if (!p.valid[i])
					{
						continue;
					}
					float r = 2 * light[i * 3], g = 2 * light[i * 3 + 1], b = 2 * light[i * 3 + 2], w = 2;
					for (int dz = -1; dz <= 1; ++dz)
					{
						for (int dx = -1; dx <= 1; ++dx)
						{
							int x = cx + dx, z = cz + dz;
							if ((dx | dz) == 0 || x < 0 || z < 0 || x >= COLUMNS || z >= COLUMNS)
							{
								continue;
							}
							int j = probe(x, z, level);
							if (!p.valid[j] || p.indoor[j] != p.indoor[i])
							{
								continue;
							}
							r += light[j * 3];
							g += light[j * 3 + 1];
							b += light[j * 3 + 2];
							w += 1;
						}
					}
					out[i * 3] = r / w;
					out[i * 3 + 1] = g / w;
					out[i * 3 + 2] = b / w;
				}
			}
		}
		return out;
	}

	/** Linear color (r, g, b) moved ALBEDO_SATURATION times as far from its luminance, same luminance, into out[at..]. */
	static void saturate(float r, float g, float b, float[] out, int at)
	{
		float l = GiRelight.luminance(r, g, b);
		out[at] = Math.max(0, l + (r - l) * ALBEDO_SATURATION);
		out[at + 1] = Math.max(0, l + (g - l) * ALBEDO_SATURATION);
		out[at + 2] = Math.max(0, l + (b - l) * ALBEDO_SATURATION);
	}

	/** The valid probe nearest a world position, looking in its column and then the ones around; -1 if none. */
	int nearestProbe(float wx, float wy, float wz)
	{
		int cx = Math.max(0, Math.min(COLUMNS - 1, Math.round((wx / TILE - 0.5f) / PITCH)));
		int cz = Math.max(0, Math.min(COLUMNS - 1, Math.round((wz / TILE - 0.5f) / PITCH)));
		for (int ring = 0; ring <= 2; ++ring)
		{
			int best = -1;
			float bestD = Float.MAX_VALUE;
			for (int z = cz - ring; z <= cz + ring; ++z)
			{
				for (int x = cx - ring; x <= cx + ring; ++x)
				{
					if (x < 0 || z < 0 || x >= COLUMNS || z >= COLUMNS || Math.max(Math.abs(x - cx), Math.abs(z - cz)) != ring)
					{
						continue;
					}
					for (int l = 0; l < LEVELS; ++l)
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
