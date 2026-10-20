package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * How much of the sky the scene lets through, ray traced through its voxels (GiGrid) for the skylight
 * (skylight_ao.glsl). Values sit at the corners of the base scene's tiles, like the game's terrain heights, at
 * HEIGHTS above the ground of each of FLOORS floors (the probes' floors, GiProbes), and are directional: the sky seen
 * toward up and the four sides (+x, -x, +z, -z), each the share of the directions above the horizon, cosine weighted
 * around that axis, whose rays get out (leave the grid, or go MAX_DISTANCE without meeting anything). Under the open
 * sky every one is 1; a wall under eaves sees the sky sideways while the ground under them doesn't see it overhead.
 *
 * Corners lie on tile edges, which is where walls stand, so a corner has no one value: every tile keeps its own four
 * corner values (quadrants), each traced from a point inside that tile near the corner. The quadrants around a corner
 * that reach each other without crossing a wall (WallMap edges; a diagonal wall splits its tile into halves) are
 * traced together, their rays shared out among their points, and get the same value, so the light is continuous
 * wherever no wall is; quadrants a wall separates keep their own. A pixel reads only its own tile's four values.
 * On a tile a diagonal wall crosses the north corners' values are the north half's (WallMap.half 0: north-west of
 * "/", north-east of "\"), the south corners' the south half's, each traced from a point in that half; the halves
 * blend their two values along x.
 *
 * Tracing is amortized: work is a corner at one height (a unit, all its quadrants), units are marked dirty when the
 * voxels or the walls around them change, and trace() works through the dirty ones nearest the player first, up to
 * a budget of rays. The rays of a unit are always the same (a fixed set turned by a per-unit angle, and foliage
 * stopping the same rays), so a unit traced again over unchanged voxels gets the same values: nothing flickers.
 *
 * Results go straight into the texels of a 3D RGBA16F texture (SkyGridMap): x is part * 2 * SIZE + 2 * tile x +
 * corner x (part 0: up, +x, -x, +z; part 1: -z), y is 2 * tile z + corner z, so a tile's four corners are a 2 x 2
 * block that linear filtering blends exactly within; slice 0 holds part 0 only, per corner the grounds of the three
 * floors (world y, NO_FLOOR where the tile has no such floor) and the tile's diagonal walls per floor (0 none, 1 "/",
 * 2 "\", times 3 to the floor's power); slice 1 + floor * HEIGHTS.length + height the values.
 *
 * Not thread safe: one thread (SkyGridMap's worker) calls everything; the texels and changes are read between calls.
 * World coordinates are the base scene's local ones, tile 0 at 0, y down.
 */
final class SkyGrid
{
	static final int SIZE = GiProbes.SIZE;
	static final int CORNERS = SIZE + 1;
	static final int FLOORS = GiProbes.FLOORS;
	// heights of the values above each floor's ground: between a storey's floor and ceiling voxels (a storey is about
	// 240 high, a voxel 64), low enough for the floor, high enough for the tops of walls
	static final float[] HEIGHTS = {40, 112, 184};
	static final int LEVELS = FLOORS * HEIGHTS.length;
	// a floor the tile doesn't have: far above anything, so no pixel is ever on it
	static final float NO_FLOOR = -16384;
	static final float TILE = 128;
	// rays per group of quadrants: an even spread over the sky's half of the sphere, each counting for every lobe it
	// lies in (the one up and up to two sides)
	static final int RAYS = 32;
	static final int ROTATIONS = 16;
	// rays that go this many voxels without a hit see the sky: 16 tiles
	static final float MAX_DISTANCE = 32;
	// tiles around a changed voxel whose rays may have met it
	static final int REACH = (int) Math.ceil(MAX_DISTANCE * GiGrid.VOXEL / TILE);
	static final int LOBES = 5;
	// corners per side of the blocks tracing goes by
	static final int BLOCK = 4;
	static final int TEX_W = 4 * SIZE, TEX_H = 2 * SIZE, TEX_D = 1 + LEVELS;

	private static final short HALF_ONE = GlobalIllumination.toHalf(1);
	// where a quadrant's point may move when its voxel is taken: up and down (world units, y down)
	private static final float[] NUDGE = {0, -24, 24, -48, 48};
	// the least height above the ground a point may be moved down to
	private static final float MIN_HEIGHT = 8;

	// [rotation][ray * 3 + axis], and each ray's weight in each lobe, [rotation][ray * LOBES + lobe], a lobe's adding to 1
	private static final float[][] DIRECTIONS = new float[ROTATIONS][];
	private static final float[][] WEIGHTS = new float[ROTATIONS][];

	static
	{
		// the upper half of an even spread over the sphere (y down)
		float[] sphere = GiProbes.fibonacci(2 * RAYS);
		float[] base = java.util.Arrays.copyOfRange(sphere, RAYS * 3, RAYS * 6);
		for (int r = 0; r < ROTATIONS; ++r)
		{
			double a = 2 * Math.PI * r / ROTATIONS;
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			float[] d = new float[RAYS * 3];
			float[] w = new float[RAYS * LOBES];
			float[] sum = new float[LOBES];
			for (int i = 0; i < RAYS; ++i)
			{
				float x = c * base[i * 3] - s * base[i * 3 + 2], y = base[i * 3 + 1], z = s * base[i * 3] + c * base[i * 3 + 2];
				d[i * 3] = x;
				d[i * 3 + 1] = y;
				d[i * 3 + 2] = z;
				// up is -y
				float[] lobe = {Math.max(-y, 0), Math.max(x, 0), Math.max(-x, 0), Math.max(z, 0), Math.max(-z, 0)};
				for (int k = 0; k < LOBES; ++k)
				{
					w[i * LOBES + k] = lobe[k];
					sum[k] += lobe[k];
				}
			}
			for (int i = 0; i < RAYS * LOBES; ++i)
			{
				w[i] /= sum[i % LOBES];
			}
			DIRECTIONS[r] = d;
			WEIGHTS[r] = w;
		}
	}

	final ShortBuffer texels = ByteBuffer.allocateDirect(TEX_W * TEX_H * TEX_D * 4 * Short.BYTES)
		.order(ByteOrder.nativeOrder()).asShortBuffer();

	private GiGrid grid;
	private int baseX, baseY;
	private boolean instance;
	private WallMap walls;
	// the grounds of the floors at every corner, [floor][z * CORNERS + x], world y
	private final float[] ground = new float[FLOORS * CORNERS * CORNERS];
	// per tile (z * SIZE + x), a bit per floor it has; the ground floor always
	private final byte[] floors = new byte[SIZE * SIZE];
	// units to trace, [(level * CORNERS + z) * CORNERS + x]
	private final boolean[] dirty = new boolean[LEVELS * CORNERS * CORNERS];
	private int dirtyCount;
	// rings of corners around the last trace's center with nothing left to trace
	private int clearRings;
	// texels written since takeChanges: tiles x0..x1 by z0..z1 (none while x1 < x0), slices s0..s1, or everything
	private int changeX0, changeZ0, changeX1 = -1, changeZ1, changeS0, changeS1;
	private boolean changedAll;

	// scratch for a unit
	private final float[] start = new float[4 * 3];
	private final float[] acc = new float[LOBES];
	private final short[] value = new short[LOBES];
	private final int[] group = new int[4];
	private final int[] members = new int[4];
	private final int[] bits = new int[4];

	int baseX()
	{
		return baseX;
	}

	int baseY()
	{
		return baseY;
	}

	boolean instance()
	{
		return instance;
	}

	int dirtyCount()
	{
		return dirtyCount;
	}

	/** Rings of corners around where the last trace() started that are all traced. */
	int clearRings()
	{
		return clearRings;
	}

	/**
	 * The voxels were built again. A new place starts over, every value open sky until traced; the same scene moved
	 * (the player walked into the next region) keeps its values where the two overlap and traces everything again; the
	 * same place traces again around the voxels that changed (a door, a tree cut down).
	 *
	 * @param heights the scene's corner heights, [level][extended x][extended z]
	 * @param offset extended tiles before local tile 0
	 * @return whether it starts over
	 */
	boolean setGrid(GiGrid g, int[][][] heights, int offset, int bx, int by, boolean inst)
	{
		GiGrid old = grid;
		int dx = bx - baseX, dz = by - baseY;
		boolean fresh = old == null || inst != instance || inst && (dx != 0 || dz != 0)
			|| Math.abs(dx) >= SIZE || Math.abs(dz) >= SIZE;
		if (fresh)
		{
			for (int s = 1; s < TEX_D; ++s)
			{
				fillOpen(s, 0, SIZE, 0, SIZE);
			}
		}
		else if (dx != 0 || dz != 0)
		{
			shift(dx, dz);
		}
		grid = g;
		baseX = bx;
		baseY = by;
		instance = inst;
		layout(heights, offset);
		if (fresh || dx != 0 || dz != 0)
		{
			markDirty(0, 0, SIZE, SIZE);
			changedAll = true;
		}
		else
		{
			markChanged(old, g);
			change(0, 0, SIZE - 1, SIZE - 1, 0, 0);
		}
		return fresh;
	}

	/** The walls changed (a door opened): the corners around the changed tiles are traced again. */
	void setWalls(WallMap w)
	{
		WallMap old = walls;
		walls = w;
		if (grid == null || old == w)
		{
			return;
		}
		int x0 = 0, z0 = 0, x1 = SIZE - 1, z1 = SIZE - 1;
		if (old != null && w != null && old.size == w.size && old.offset == w.offset)
		{
			int[] d = w.diff(old);
			if (d == null)
			{
				return;
			}
			x0 = Math.max(0, d[0] - w.offset);
			z0 = Math.max(0, d[1] - w.offset);
			x1 = Math.min(SIZE - 1, d[2] - w.offset);
			z1 = Math.min(SIZE - 1, d[3] - w.offset);
			if (x1 < x0 || z1 < z0)
			{
				return;
			}
		}
		for (int tz = z0; tz <= z1; ++tz)
		{
			for (int tx = x0; tx <= x1; ++tx)
			{
				writeMeta(tx, tz);
			}
		}
		change(x0, z0, x1, z1, 0, 0);
		markDirty(x0, z0, x1 + 2, z1 + 2);
	}

	/**
	 * Trace dirty units, those nearest the given world tile (absolute, as the scene's base) first, until about budget
	 * rays are spent. Returns the rays traced. Units go by blocks of BLOCK x BLOCK corners in rings around the player's,
	 * so what a call writes lies together and uploads as a small box.
	 */
	int trace(int budget, float worldTileX, float worldTileZ)
	{
		if (grid == null || dirtyCount == 0)
		{
			clearRings = CORNERS;
			return 0;
		}
		int bx = Math.max(0, Math.min(SIZE, Math.round(worldTileX - baseX))) / BLOCK;
		int bz = Math.max(0, Math.min(SIZE, Math.round(worldTileZ - baseY))) / BLOCK;
		int blocks = (CORNERS + BLOCK - 1) / BLOCK;
		int maxRing = Math.max(Math.max(bx, blocks - 1 - bx), Math.max(bz, blocks - 1 - bz));
		int rays = 0;
		for (int r = 0; r <= maxRing && dirtyCount > 0; ++r)
		{
			for (int dz = -r; dz <= r; ++dz)
			{
				// the whole row on the ring's top and bottom, its two ends otherwise
				int step = dz == -r || dz == r ? 1 : 2 * r;
				for (int dx = -r; dx <= r; dx += step)
				{
					int x0 = (bx + dx) * BLOCK, z0 = (bz + dz) * BLOCK;
					for (int cz = Math.max(0, z0); cz < Math.min(CORNERS, z0 + BLOCK); ++cz)
					{
						for (int cx = Math.max(0, x0); cx < Math.min(CORNERS, x0 + BLOCK); ++cx)
						{
							for (int level = 0; level < LEVELS; ++level)
							{
								int u = (level * CORNERS + cz) * CORNERS + cx;
								if (!dirty[u])
								{
									continue;
								}
								if (rays >= budget)
								{
									// every corner of the rings of blocks before this one is traced
									clearRings = Math.max(0, (r - 1) * BLOCK);
									return rays;
								}
								dirty[u] = false;
								dirtyCount--;
								rays += traceUnit(cx, cz, level);
							}
						}
					}
				}
			}
		}
		clearRings = CORNERS;
		return rays;
	}

	/**
	 * The texels written since the last call, as {x0, z0, x1, z1, s0, s1, all} in tiles and slices, null when none;
	 * after a new place or a move all of them, with all 1.
	 */
	int[] takeChanges()
	{
		int[] c = changedAll ? new int[]{0, 0, SIZE - 1, SIZE - 1, 0, TEX_D - 1, 1}
			: changeX1 < changeX0 ? null : new int[]{changeX0, changeZ0, changeX1, changeZ1, changeS0, changeS1, 0};
		changedAll = false;
		changeX1 = -1;
		return c;
	}

	// ---------------------------------------------------------------------------------------------------------

	private void layout(int[][][] heights, int offset)
	{
		float[] g = new float[FLOORS];
		for (int cz = 0; cz < CORNERS; ++cz)
		{
			for (int cx = 0; cx < CORNERS; ++cx)
			{
				for (int f = 0; f < FLOORS; ++f)
				{
					// as GiProbes: a floor's ground is at least FLOOR_GAP over the one below
					float h = heights != null && f < heights.length ? height(heights[f], cx + offset, cz + offset)
						: f > 0 ? g[f - 1] - GiProbes.FLOOR_GAP : 0;
					g[f] = f == 0 ? h : Math.min(h, g[f - 1] - GiProbes.FLOOR_GAP);
					ground[(f * CORNERS + cz) * CORNERS + cx] = g[f];
				}
			}
		}
		for (int tz = 0; tz < SIZE; ++tz)
		{
			for (int tx = 0; tx < SIZE; ++tx)
			{
				int bits = 1;
				for (int f = 1; f < FLOORS; ++f)
				{
					float center = (ground(f, tx, tz) + ground(f, tx + 1, tz) + ground(f, tx, tz + 1) + ground(f, tx + 1, tz + 1)) / 4;
					if (hasFloor(tx, tz, center))
					{
						bits |= 1 << f;
					}
				}
				floors[tz * SIZE + tx] = (byte) bits;
				writeMeta(tx, tz);
			}
		}
	}

	private static float height(int[][] h, int x, int z)
	{
		x = Math.max(0, Math.min(h.length - 1, x));
		return h[x][Math.max(0, Math.min(h[x].length - 1, z))];
	}

	private float ground(int f, int cx, int cz)
	{
		return ground[(f * CORNERS + cz) * CORNERS + cx];
	}

	/** Whether a tile has an upper floor at height y: some geometry in its voxel columns within a voxel of it. */
	private boolean hasFloor(int tx, int tz, float y)
	{
		int y0 = Math.floorDiv((int) (y - GiGrid.VOXEL), GiGrid.VOXEL) - grid.layer0;
		int y1 = Math.floorDiv((int) (y + GiGrid.VOXEL / 2), GiGrid.VOXEL) - grid.layer0;
		for (int vx = tx * 2; vx < tx * 2 + 2; ++vx)
		{
			for (int vz = tz * 2; vz < tz * 2 + 2; ++vz)
			{
				for (int vy = y0; vy <= y1; ++vy)
				{
					if (grid.occupied(vx, vy, vz))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/** The diagonal wall of a tile on a floor: 0 none, 1 "/", 2 "\". */
	private int diagonal(int f, int tx, int tz)
	{
		int b = walls != null ? walls.get(f, tx + walls.offset, tz + walls.offset) : 0;
		return (b & WallMap.DIAG_SW_NE) != 0 ? 1 : (b & WallMap.DIAG_NW_SE) != 0 ? 2 : 0;
	}

	private int wallBits(int f, int tx, int tz)
	{
		return walls != null ? walls.get(f, tx + walls.offset, tz + walls.offset) : 0;
	}

	private void writeMeta(int tx, int tz)
	{
		int code = diagonal(0, tx, tz) + 3 * diagonal(1, tx, tz) + 9 * diagonal(2, tx, tz);
		int bits = floors[tz * SIZE + tx];
		for (int j = 0; j < 2; ++j)
		{
			for (int i = 0; i < 2; ++i)
			{
				int t = texel(2 * tx + i, 2 * tz + j, 0);
				for (int f = 0; f < FLOORS; ++f)
				{
					texels.put(t + f, GlobalIllumination.toHalf((bits >> f & 1) != 0 ? ground(f, tx + i, tz + j) : NO_FLOOR));
				}
				texels.put(t + 3, GlobalIllumination.toHalf(code));
			}
		}
	}

	private static int texel(int x, int y, int slice)
	{
		return ((slice * TEX_H + y) * TEX_W + x) * 4;
	}

	/** Open sky in both parts of a slice's tiles x0..x1 - 1 by z0..z1 - 1. */
	private void fillOpen(int slice, int x0, int x1, int z0, int z1)
	{
		for (int y = 2 * z0; y < 2 * z1; ++y)
		{
			for (int x = 2 * x0; x < 2 * x1; ++x)
			{
				int a = texel(x, y, slice), b = texel(x + 2 * SIZE, y, slice);
				for (int c = 0; c < 4; ++c)
				{
					texels.put(a + c, HALF_ONE);
					texels.put(b + c, c == 0 ? HALF_ONE : 0);
				}
			}
		}
	}

	/** Move the values to a base dx, dz tiles away: what lay at tile t + d is now at t; what is new is open sky. */
	private void shift(int dx, int dz)
	{
		short[] slice = new short[TEX_W * TEX_H * 4];
		short[] moved = new short[slice.length];
		int row = TEX_W * 4;
		for (int s = 1; s < TEX_D; ++s)
		{
			texels.position(texel(0, 0, s));
			texels.get(slice);
			java.util.Arrays.fill(moved, (short) 0);
			int xs = Math.max(0, -dx), xe = Math.min(SIZE, SIZE - dx);
			for (int y = 2 * Math.max(0, -dz); y < 2 * Math.min(SIZE, SIZE - dz); ++y)
			{
				int from = (y + 2 * dz) * row, to = y * row;
				for (int part = 0; part < 2; ++part)
				{
					int p = part * 2 * SIZE * 4;
					System.arraycopy(slice, from + p + 2 * (xs + dx) * 4, moved, to + p + 2 * xs * 4, 2 * (xe - xs) * 4);
				}
			}
			texels.position(texel(0, 0, s));
			texels.put(moved);
			texels.position(0);
			// the tiles that came in
			fillOpen(s, 0, SIZE, 0, Math.max(0, -dz));
			fillOpen(s, 0, SIZE, Math.min(SIZE, SIZE - dz), SIZE);
			fillOpen(s, 0, Math.max(0, -dx), 0, SIZE);
			fillOpen(s, Math.min(SIZE, SIZE - dx), SIZE, 0, SIZE);
		}
	}

	/** Mark the units of every level at corners x0..x1 - 1 by z0..z1 - 1 (clamped) for tracing. */
	private void markDirty(int x0, int z0, int x1, int z1)
	{
		x0 = Math.max(0, x0);
		z0 = Math.max(0, z0);
		x1 = Math.min(CORNERS, x1);
		z1 = Math.min(CORNERS, z1);
		for (int level = 0; level < LEVELS; ++level)
		{
			for (int cz = z0; cz < z1; ++cz)
			{
				for (int cx = x0; cx < x1; ++cx)
				{
					int u = (level * CORNERS + cz) * CORNERS + cx;
					if (!dirty[u])
					{
						dirty[u] = true;
						dirtyCount++;
					}
				}
			}
		}
	}

	/** Mark the corners within REACH of every tile whose voxels differ between two grids of the same place. */
	private void markChanged(GiGrid a, GiGrid b)
	{
		if (a.nx != b.nx || a.ny != b.ny || a.nz != b.nz || a.layer0 != b.layer0)
		{
			markDirty(0, 0, CORNERS, CORNERS);
			return;
		}
		// changed tiles, then spread REACH along x and then along z
		boolean[] changed = new boolean[SIZE * SIZE];
		int stride = a.nx * a.nz;
		for (int vz = 0; vz < a.nz; ++vz)
		{
			for (int vx = 0; vx < a.nx; ++vx)
			{
				int t = vz / 2 * SIZE + vx / 2;
				if (changed[t])
				{
					continue;
				}
				for (int i = a.index(vx, 0, vz); i < a.cells.length; i += stride)
				{
					if (a.cells[i] != b.cells[i])
					{
						changed[t] = true;
						break;
					}
				}
			}
		}
		// [tile z][corner x]
		boolean[] wide = new boolean[SIZE * CORNERS];
		for (int tz = 0; tz < SIZE; ++tz)
		{
			for (int tx = 0; tx < SIZE; ++tx)
			{
				if (changed[tz * SIZE + tx])
				{
					for (int cx = Math.max(0, tx - REACH); cx <= Math.min(SIZE, tx + 1 + REACH); ++cx)
					{
						wide[tz * CORNERS + cx] = true;
					}
				}
			}
		}
		for (int cx = 0; cx < CORNERS; ++cx)
		{
			for (int tz = 0; tz < SIZE; ++tz)
			{
				if (wide[tz * CORNERS + cx])
				{
					markDirty(cx, tz - REACH, cx + 1, tz + 2 + REACH);
				}
			}
		}
	}

	private void change(int x0, int z0, int x1, int z1, int s0, int s1)
	{
		if (changeX1 < changeX0)
		{
			changeX0 = x0;
			changeZ0 = z0;
			changeX1 = x1;
			changeZ1 = z1;
			changeS0 = s0;
			changeS1 = s1;
			return;
		}
		changeX0 = Math.min(changeX0, x0);
		changeZ0 = Math.min(changeZ0, z0);
		changeX1 = Math.max(changeX1, x1);
		changeZ1 = Math.max(changeZ1, z1);
		changeS0 = Math.min(changeS0, s0);
		changeS1 = Math.max(changeS1, s1);
	}

	// The quadrants around a corner, 0 south-west to 3 north-east: the tile each lies in, relative to the corner, and
	// which of that tile's corners it is (x 1 east, z 1 north)
	private static final int[] QX = {-1, 0, -1, 0}, QZ = {-1, -1, 0, 0};

	/** Which half of its tile a quadrant's value is for: the north half (0) has the north corners. */
	private static int half(int bits, int q)
	{
		return !WallMap.split(bits) || QZ[q] < 0 ? 0 : 1;
	}

	/** Whether a quadrant borders its tile's edge toward side, rather than the diagonal wall cutting it off. */
	private static boolean touches(int bits, int q, int side)
	{
		return !WallMap.split(bits) || WallMap.half(bits, side) == half(bits, q);
	}

	/**
	 * Trace one unit: the quadrants of the tiles around corner (cx, cz) that have the level's floor, grouped by the
	 * walls, each group's RAYS rays shared among its quadrants' points. Returns the rays traced.
	 */
	private int traceUnit(int cx, int cz, int level)
	{
		int f = level / HEIGHTS.length;
		float height = HEIGHTS[level % HEIGHTS.length];
		float y = ground(f, cx, cz) - height;
		int present = 0;
		for (int q = 0; q < 4; ++q)
		{
			int tx = cx + QX[q], tz = cz + QZ[q];
			if (tx < 0 || tz < 0 || tx >= SIZE || tz >= SIZE || (floors[tz * SIZE + tx] >> f & 1) == 0)
			{
				continue;
			}
			present |= 1 << q;
			bits[q] = wallBits(f, tx, tz);
			group[q] = q;
			startPoint(q, cx, cz, bits[q], y, height);
		}
		if (present == 0)
		{
			return 0;
		}
		int off = walls != null ? walls.offset : 0;
		// south-west to south-east, north-west to north-east, south-west to north-west, south-east to north-east
		join(present, bits, 0, 1, WallMap.EAST, WallMap.WEST, cx - 1 + off, cz - 1 + off, f);
		join(present, bits, 2, 3, WallMap.EAST, WallMap.WEST, cx - 1 + off, cz + off, f);
		join(present, bits, 0, 2, WallMap.NORTH, WallMap.SOUTH, cx - 1 + off, cz - 1 + off, f);
		join(present, bits, 1, 3, WallMap.NORTH, WallMap.SOUTH, cx + off, cz - 1 + off, f);
		int unit = (level * CORNERS + cz) * CORNERS + cx;
		int rot = GiProbes.rotation(unit);
		float[] dirs = DIRECTIONS[rot], weights = WEIGHTS[rot];
		int rays = 0;
		for (int root = 0; root < 4; ++root)
		{
			if ((present >> root & 1) == 0 || find(root) != root)
			{
				continue;
			}
			int m = 0;
			for (int q = 0; q < 4; ++q)
			{
				if ((present >> q & 1) != 0 && find(q) == root)
				{
					members[m++] = q;
				}
			}
			java.util.Arrays.fill(acc, 0);
			for (int r = 0; r < RAYS; ++r)
			{
				int q = members[r % m];
				int hit = grid.trace(start[q * 3], start[q * 3 + 1], start[q * 3 + 2], dirs[r * 3], dirs[r * 3 + 1],
					dirs[r * 3 + 2], MAX_DISTANCE, true, (unit * RAYS + r) & 0x7fffffff);
				if (hit < 0)
				{
					for (int k = 0; k < LOBES; ++k)
					{
						acc[k] += weights[r * LOBES + k];
					}
				}
			}
			rays += RAYS;
			for (int k = 0; k < LOBES; ++k)
			{
				value[k] = GlobalIllumination.toHalf(acc[k]);
			}
			for (int k = 0; k < m; ++k)
			{
				int q = members[k];
				int tx = cx + QX[q], tz = cz + QZ[q];
				int x = 2 * tx + (QX[q] < 0 ? 1 : 0), yy = 2 * tz + (QZ[q] < 0 ? 1 : 0);
				int a = texel(x, yy, 1 + level), b = texel(x + 2 * SIZE, yy, 1 + level);
				// traced again over the same voxels the value is the same: nothing to upload
				if (texels.get(a) == value[0] && texels.get(a + 1) == value[1] && texels.get(a + 2) == value[2]
					&& texels.get(a + 3) == value[3] && texels.get(b) == value[4])
				{
					continue;
				}
				for (int c = 0; c < 4; ++c)
				{
					texels.put(a + c, value[c]);
				}
				texels.put(b, value[4]);
				change(tx, tz, tx, tz, 1 + level, 1 + level);
			}
		}
		return rays;
	}

	// union of two quadrants when both are there and nothing walls them apart: the edge between their tiles (the
	// first's toward side a) has no wall, and each touches it rather than being cut off by a diagonal
	private void join(int present, int[] bits, int p, int q, int a, int b, int x, int z, int f)
	{
		if ((present >> p & 1) == 0 || (present >> q & 1) == 0 || !touches(bits[p], p, a) || !touches(bits[q], q, b)
			|| walls != null && walls.edge(f, x, z, a))
		{
			return;
		}
		group[find(p)] = find(q);
	}

	private int find(int q)
	{
		while (group[q] != q)
		{
			q = group[q];
		}
		return q;
	}

	/**
	 * The point quadrant q traces from, into start[q * 3..] in voxel space: halfway from the corner to the middle of
	 * its tile, or of its half of the tile, at world y; moved up or down, then to the tile's other voxels, when that
	 * voxel is solid (a wall standing on the edge fills it). With nowhere free it stays, its own voxel skipped.
	 */
	private void startPoint(int q, int cx, int cz, int bits, float y, float height)
	{
		float mx = 0.5f, mz = 0.5f;
		if (WallMap.split(bits))
		{
			// the middle of the half: the centroid of its triangle
			boolean slash = (bits & WallMap.DIAG_SW_NE) != 0;
			boolean north = half(bits, q) == 0;
			mx = slash == north ? 1 / 3f : 2 / 3f;
			mz = north ? 2 / 3f : 1 / 3f;
		}
		int tx = cx + QX[q], tz = cz + QZ[q];
		// the corner within its tile
		float kx = QX[q] < 0 ? 1 : 0, kz = QZ[q] < 0 ? 1 : 0;
		float px = (tx + (kx + mx) / 2) * TILE, pz = (tz + (kz + mz) / 2) * TILE;
		float sx = kx > 0 ? -GiGrid.VOXEL : GiGrid.VOXEL, sz = kz > 0 ? -GiGrid.VOXEL : GiGrid.VOXEL;
		int moves = WallMap.split(bits) ? 1 : 4;
		for (int k = 0; k < moves; ++k)
		{
			float x = px + (k == 1 || k == 3 ? sx : 0), z = pz + (k >= 2 ? sz : 0);
			for (float dy : NUDGE)
			{
				if (height - dy < MIN_HEIGHT || k > 0 && Math.abs(dy) > 24 || solid(x, y + dy, z))
				{
					continue;
				}
				setStart(q, x, y + dy, z);
				return;
			}
		}
		setStart(q, px, y, pz);
	}

	private void setStart(int q, float x, float y, float z)
	{
		start[q * 3] = x / GiGrid.VOXEL;
		start[q * 3 + 1] = y / GiGrid.VOXEL - grid.layer0;
		start[q * 3 + 2] = z / GiGrid.VOXEL;
	}

	// a voxel that isn't foliage: rays start in foliage (a point under a tree's crown)
	private boolean solid(float x, float y, float z)
	{
		int vx = (int) Math.floor(x / GiGrid.VOXEL), vy = (int) Math.floor(y / GiGrid.VOXEL) - grid.layer0;
		int vz = (int) Math.floor(z / GiGrid.VOXEL);
		if (vx < 0 || vy < 0 || vz < 0 || vx >= grid.nx || vy >= grid.ny || vz >= grid.nz)
		{
			return false;
		}
		int cell = grid.cells[grid.index(vx, vy, vz)];
		return cell != 0 && !GiGrid.foliage(cell);
	}
}
