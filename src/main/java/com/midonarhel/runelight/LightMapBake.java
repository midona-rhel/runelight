package com.midonarhel.runelight;

import java.util.Arrays;

/**
 * How far one static light reaches, per cell of the light map (LightMap): the share of the light that arrives, 0 to
 * 1, with 117 HD's falloff (1 - d / r)^2 in it. GL-free; runs on the baking threads.
 *
 * A cell is a quarter tile. What stops light is in LightBlockers: wall edges (and diagonal walls), the tiles of solid
 * objects, and the boxes of the models that stand at the light's height (ModelBlockers.box, as big as the model is in
 * the cell). On the light's own storey:
 *
 * - Soft line of sight. A light isn't a point: SAMPLES points are spread over a disc LIGHT_WIDTH_TILES wide, each swept
 *   exactly. A sweep from a point finds, for each of BINS directions around it, the distance to the nearest blocker
 *   (every blocker edge in reach is walked once, over the directions it covers), then every cell is seen when it is
 *   nearer than that in its direction, looked at in SUBS x SUBS places of the cell so that an edge falling inside a
 *   cell gives it a part. The cost is the edges plus the cells. The share of the looks that see a cell is its
 *   visibility, and the falloff by the straight distance to the light goes with it. (One pass of neighbour
 *   propagation was tried instead and a light between two nearby blockers got trapped.)
 * - Wrap. Light walks around corners and through doors and stands in for bounce light: a flood fill over the cells,
 *   blocked by the same blockers, gives each the path distance from the light, and WRAP times the falloff by that
 *   distance is a floor under the direct light.
 * - Blur. A Gaussian of BLUR_SIGMA_CELLS that never takes a cell's light from across a wall, a solid object or a model
 *   that fills its cell (DENSE_BOX), so shadows have soft edges and no cell shows. The floor under a model's box takes its
 *   neighbours' light, and wrap does not go through a model that fills its cell.
 * - A light inside the footprint of its own object (a brazier, a caged lantern, a wall torch) is not shaded by the
 *   boxes it stands in or next to (FIXTURE), and casts from the nearest open cell when it is in a solid tile, a little
 *   way from the walls of that cell, so a fixture never traps its own light. The light's real position still gives
 *   the distance.
 *
 * The other storeys get the light that passes through the openings between them: the line from the light to a point
 * of the cell, at a few heights above its floor, has to cross every floor between them where the storey has no floor
 * or roof tile, from the light to the first opening as visible as it is on its own storey, and past the walls of the
 * storeys it goes through. (Models are not followed onto the other storeys: no one height of theirs is the light's.)
 */
final class LightMapBake
{
	static final float CELL = 32;
	static final int CELLS_PER_TILE = LightBlockers.CELLS_PER_TILE;
	/** How wide a light is, in tiles: its samples are spread over a disc this wide. */
	static final float LIGHT_WIDTH_TILES = 0.8f;
	static final int SAMPLES = 16;
	/** The share of the falloff by path distance that stays where the light only gets around corners. */
	static final float WRAP = 0.35f;
	/** The blur's Gaussian, in cells (a quarter tile): about half a tile. */
	static final float BLUR_SIGMA_CELLS = 2;
	/** Places looked at along each side of a cell, for its visibility. */
	static final int SUBS = 2;
	/** Models closer than this (world units) to a light are its own fixture and do not shade it. */
	static final float FIXTURE = 10;
	/** A model whose box fills this much of a cell (the share of its area) is a solid for the wrap of light. */
	static final float DENSE_BOX = 0.5f;
	/** The thinnest a model's box is taken to be (world units): a leaf of a door stops light across its length. */
	static final float MIN_BOX = 4;
	/** Lights that reach further than this (world units) are not baked: the bake would end them at its edge. */
	static final float MAX_RADIUS = 3200;
	/** Directions a sweep tells apart: at the far end of a big light still a few world units a direction. */
	static final int BINS = 2048;
	/** A light is cast this far (world units) off the walls of its cell. */
	static final float SOURCE_CLEARANCE = 8;
	/** How far (cells) from a blocked cell the nearest open one is looked for. */
	private static final int RELOCATE_REACH = 16;
	/** The heights above a storey's floor the light is followed to, on the storeys it shines onto through openings. */
	static final float[] CROSS_HEIGHTS = {32, 96, 160};

	private static final int BLUR_REACH = (int) Math.ceil(2.5f * BLUR_SIGMA_CELLS);
	private static final float[] BLUR = new float[BLUR_REACH + 1];
	private static final float GOLDEN_ANGLE = 2.3999632f;
	private static final float SQRT2 = 1.4142135f;
	private static final float[] BIN_DX = new float[BINS], BIN_DZ = new float[BINS];

	static
	{
		for (int d = 0; d <= BLUR_REACH; ++d)
		{
			BLUR[d] = (float) Math.exp(-d * d / (2.0 * BLUR_SIGMA_CELLS * BLUR_SIGMA_CELLS));
		}
		for (int k = 0; k < BINS; ++k)
		{
			// the pseudo angle at the bin's middle, as a direction
			float pa = (k + 0.5f) * 4 / BINS;
			float p = pa <= 2 ? 1 - pa : pa - 3;
			float x = p, z = (1 - Math.abs(p)) * (pa <= 2 ? 1 : -1);
			float len = (float) Math.sqrt(x * x + z * z);
			BIN_DX[k] = x / len;
			BIN_DZ[k] = z / len;
		}
	}

	/** A light as the bake sees it. */
	static final class Light
	{
		final int plane;
		final float x, y, z, radius;

		Light(int plane, float x, float y, float z, float radius)
		{
			this.plane = plane;
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
		}
	}

	/** A storey's share of a light per cell, a rectangle of the scene's cells: c[(z - z0) * w + x - x0]. */
	static final class Grid
	{
		final int plane, x0, z0, w, h;
		final float[] c;

		Grid(int plane, int x0, int z0, int w, int h, float[] c)
		{
			this.plane = plane;
			this.x0 = x0;
			this.z0 = z0;
			this.w = w;
			this.h = h;
			this.c = c;
		}
	}

	static final class Result
	{
		final Grid[] grids;
		// cells looked at by the sweeps, the flood and the other storeys
		final long cellVisits;

		Result(Grid[] grids, long cellVisits)
		{
			this.grids = grids;
			this.cellVisits = cellVisits;
		}
	}

	/** One storey's blockers over the light's rectangle, local cells. */
	private static final class Layer
	{
		int w, h;
		boolean[] free = new boolean[0], east = new boolean[0], north = new boolean[0];
		// diagonal walls as segments: x0, z0, x1, z1
		float[] diagonals = new float[16];
		int diagonalCount;
		// the cells with a model's box (the light's own storey): per cell the box + 1 (0: none), the boxes as x0, z0,
		// x1, z1 in cells of the layer, and the cells that have one
		int[] boxOf = new int[0], boxCells = new int[16];
		float[] boxes = new float[64];
		int boxCount, boxCellCount;
		// the cell's middle is inside its box
		boolean[] covered = new boolean[0];
		// the box fills most of the cell: wrap does not go through
		boolean[] dense = new boolean[0];

		void reset(int w, int h)
		{
			this.w = w;
			this.h = h;
			if (free.length < w * h)
			{
				free = new boolean[w * h];
				east = new boolean[w * h];
				north = new boolean[w * h];
				boxOf = new int[w * h];
				covered = new boolean[w * h];
				dense = new boolean[w * h];
			}
			Arrays.fill(east, 0, w * h, true);
			Arrays.fill(north, 0, w * h, true);
			Arrays.fill(boxOf, 0, w * h, 0);
			Arrays.fill(covered, 0, w * h, false);
			Arrays.fill(dense, 0, w * h, false);
			diagonalCount = 0;
			boxCount = 0;
			boxCellCount = 0;
		}

		void addBox(int cell, float x0, float z0, float x1, float z1)
		{
			if (boxCount * 4 == boxes.length)
			{
				boxes = Arrays.copyOf(boxes, boxes.length * 2);
			}
			if (boxCellCount == boxCells.length)
			{
				boxCells = Arrays.copyOf(boxCells, boxCellCount * 2);
			}
			int o = boxCount * 4;
			boxes[o] = x0;
			boxes[o + 1] = z0;
			boxes[o + 2] = x1;
			boxes[o + 3] = z1;
			boxOf[cell] = ++boxCount;
			boxCells[boxCellCount++] = cell;
		}

		/** Whether a point is inside a model's box. */
		boolean inBox(float x, float z)
		{
			int i = (int) Math.floor(x), j = (int) Math.floor(z);
			if (i < 0 || j < 0 || i >= w || j >= h || boxOf[j * w + i] == 0)
			{
				return false;
			}
			int o = (boxOf[j * w + i] - 1) * 4;
			return x >= boxes[o] && x <= boxes[o + 2] && z >= boxes[o + 1] && z <= boxes[o + 3];
		}

		void addDiagonal(float x0, float z0, float x1, float z1)
		{
			if (diagonalCount * 4 == diagonals.length)
			{
				diagonals = Arrays.copyOf(diagonals, diagonals.length * 2);
			}
			int o = diagonalCount++ * 4;
			diagonals[o] = x0;
			diagonals[o + 1] = z0;
			diagonals[o + 2] = x1;
			diagonals[o + 3] = z1;
		}
	}

	/** What a baking thread keeps between lights. */
	static final class Scratch
	{
		private final Layer own = new Layer(), other = new Layer();
		private float[] segments = new float[256];
		private int segmentCount;
		private final float[] depth = new float[BINS];
		private int[] candidates = new int[0];
		private int candidateCount;
		private short[] hits = new short[0];
		private byte[] runBefore = new byte[0], runAfter = new byte[0];
		private float[] vis = new float[0], value = new float[0], value2 = new float[0], mix = new float[0], dist = new float[0];
		private final Heap heap = new Heap();
		private long visits;

		private void size(int cells)
		{
			if (vis.length < cells)
			{
				runBefore = new byte[cells];
				runAfter = new byte[cells];
				candidates = new int[cells];
				hits = new short[cells];
				vis = new float[cells];
				value = new float[cells];
				value2 = new float[cells];
				mix = new float[cells];
				dist = new float[cells];
			}
		}
	}

	private LightMapBake()
	{
	}

	/**
	 * The light's share per cell on every storey it reaches.
	 */
	static Result bake(Light light, LightBlockers b, Scratch s)
	{
		s.visits = 0;
		float radius = light.radius;
		int off = b.offset * CELLS_PER_TILE;
		float lx = light.x / CELL + off, lz = light.z / CELL + off;
		float rc = radius / CELL;
		int x0 = Math.max(0, (int) Math.floor(lx - rc) - 1), x1 = Math.min(b.cells - 1, (int) Math.floor(lx + rc) + 1);
		int z0 = Math.max(0, (int) Math.floor(lz - rc) - 1), z1 = Math.min(b.cells - 1, (int) Math.floor(lz + rc) + 1);
		if (x1 < x0 || z1 < z0 || lx < 0 || lz < 0 || lx >= b.cells || lz >= b.cells)
		{
			return new Result(new Grid[0], 0);
		}
		int w = x1 - x0 + 1, h = z1 - z0 + 1;
		s.size(w * h);
		Layer own = s.own;
		int lightBin = ModelBlockers.bin(b.floor(light.plane, (int) Math.floor(light.x / 128) + b.offset,
			(int) Math.floor(light.z / 128) + b.offset) - light.y);
		load(own, b, light.plane, x0, z0, w, h, lightBin);
		float sx = lx - x0, sz = lz - z0;
		float[] source = new float[2];
		if (!source(own, sx, sz, source))
		{
			return new Result(new Grid[0], 0);
		}
		dropFixture(own, sx, sz);
		dropFixture(own, source[0], source[1]);
		collectSegments(s, own);
		gatherCandidates(s, own, sx, sz, rc);
		float[] value = s.value;
		Arrays.fill(value, 0, w * h, 0);
		visibility(s, own, source[0], source[1]);
		float invRadius = 1 / radius;
		for (int n = 0; n < s.candidateCount; ++n)
		{
			int k = s.candidates[n];
			float d = (float) Math.hypot(k % w + 0.5f - sx, k / w + 0.5f - sz) * CELL * invRadius;
			value[k] = s.vis[k] * falloff(d);
		}
		flood(s, own, source[0], source[1], sx, sz, rc);
		for (int n = 0; n < s.candidateCount; ++n)
		{
			int k = s.candidates[n];
			float d = s.dist[k] * CELL * invRadius;
			value[k] = Math.max(value[k], WRAP * falloff(d));
		}
		fillCovered(own, value);
		blur(s, own, value);
		dilate(own, value);

		Grid[] grids = new Grid[WallMap.PLANES];
		int count = 0;
		Grid ownGrid = crop(light.plane, x0, z0, w, h, value);
		if (ownGrid != null)
		{
			grids[count++] = ownGrid;
		}
		for (int plane = 0; plane < WallMap.PLANES; ++plane)
		{
			if (plane != light.plane)
			{
				Grid g = otherStorey(light, b, s, plane, radius, x0, z0, w, h, off);
				if (g != null)
				{
					grids[count++] = g;
				}
			}
		}
		return new Result(Arrays.copyOf(grids, count), s.visits);
	}

	/** 117 HD's falloff at a distance in radii. */
	static float falloff(float d)
	{
		if (d >= 1)
		{
			return 0;
		}
		float a = 1 - d;
		return a * a;
	}

	// ---------------------------------------------------------------------------------------------------------
	// A storey's blockers

	/**
	 * @param bin the light's height bin when this is the light's own storey, where models are followed; -1 on any other
	 */
	private static void load(Layer l, LightBlockers b, int plane, int x0, int z0, int w, int h, int bin)
	{
		l.reset(w, h);
		float[] box = new float[4];
		float thin = MIN_BOX / CELL;
		for (int j = 0; j < h; ++j)
		{
			for (int i = 0; i < w; ++i)
			{
				l.free[j * w + i] = !b.solid(plane, x0 + i, z0 + j, bin >= 0);
				if (bin >= 0 && b.models != null && b.models.box(plane, x0 + i, z0 + j, bin, box))
				{
					// as thin as MIN_BOX at the least, inside the cell
					for (int a = 0; a < 2; ++a)
					{
						if (box[a + 2] - box[a] < thin)
						{
							float mid = (box[a] + box[a + 2]) * 0.5f;
							box[a] = Math.max(0, Math.min(1 - thin, mid - thin * 0.5f));
							box[a + 2] = box[a] + thin;
						}
					}
					l.addBox(j * w + i, i + box[0], j + box[1], i + box[2], j + box[3]);
					l.covered[j * w + i] = box[0] <= 0.5f && box[2] >= 0.5f && box[1] <= 0.5f && box[3] >= 0.5f;
					l.dense[j * w + i] = (box[2] - box[0]) * (box[3] - box[1]) >= DENSE_BOX;
				}
			}
		}
		WallMap walls = b.walls;
		int tx0 = Math.floorDiv(x0, CELLS_PER_TILE), tx1 = Math.floorDiv(x0 + w - 1, CELLS_PER_TILE);
		int ty0 = Math.floorDiv(z0, CELLS_PER_TILE), ty1 = Math.floorDiv(z0 + h - 1, CELLS_PER_TILE);
		for (int ty = ty0; ty <= ty1; ++ty)
		{
			for (int tx = tx0; tx <= tx1; ++tx)
			{
				int bits = walls.get(plane, tx, ty);
				int ax = tx * CELLS_PER_TILE - x0, az = ty * CELLS_PER_TILE - z0;
				boolean n = walls.edge(plane, tx, ty, WallMap.NORTH), so = walls.edge(plane, tx, ty, WallMap.SOUTH);
				boolean e = walls.edge(plane, tx, ty, WallMap.EAST), we = walls.edge(plane, tx, ty, WallMap.WEST);
				for (int k = 0; k < CELLS_PER_TILE && (n | so | e | we); ++k)
				{
					if (n)
					{
						closeNorth(l, ax + k, az + CELLS_PER_TILE - 1);
					}
					if (so)
					{
						closeNorth(l, ax + k, az - 1);
					}
					if (e)
					{
						closeEast(l, ax + CELLS_PER_TILE - 1, az + k);
					}
					if (we)
					{
						closeEast(l, ax - 1, az + k);
					}
				}
				if ((bits & (WallMap.DIAG_SW_NE | WallMap.DIAG_NW_SE)) != 0)
				{
					diagonal(l, bits, ax, az);
				}
			}
		}
	}

	private static void closeNorth(Layer l, int i, int j)
	{
		if (i >= 0 && j >= 0 && i < l.w && j < l.h)
		{
			l.north[j * l.w + i] = false;
		}
	}

	private static void closeEast(Layer l, int i, int j)
	{
		if (i >= 0 && j >= 0 && i < l.w && j < l.h)
		{
			l.east[j * l.w + i] = false;
		}
	}

	/**
	 * A tile with diagonal walls (its corner at ax, az in local cells): the cells the wall runs through (their centres
	 * are on it) are blocked, and no cell is joined to a neighbour across it.
	 */
	private static void diagonal(Layer l, int bits, int ax, int az)
	{
		for (int kind = 0; kind < 2; ++kind)
		{
			boolean slash = kind == 0;
			if ((bits & (slash ? WallMap.DIAG_SW_NE : WallMap.DIAG_NW_SE)) == 0)
			{
				continue;
			}
			int n = CELLS_PER_TILE;
			if (slash)
			{
				l.addDiagonal(ax, az, ax + n, az + n);
			}
			else
			{
				l.addDiagonal(ax, az + n, ax + n, az);
			}
			for (int k = 0; k < n; ++k)
			{
				int i = ax + k, j = slash ? az + k : az + n - 1 - k;
				if (i >= 0 && j >= 0 && i < l.w && j < l.h)
				{
					l.free[j * l.w + i] = false;
				}
			}
			for (int j = az - 1; j <= az + n; ++j)
			{
				for (int i = ax - 1; i <= ax + n; ++i)
				{
					if (i < 0 || j < 0 || i >= l.w || j >= l.h)
					{
						continue;
					}
					float u = i + 0.5f - ax, v = j + 0.5f - az;
					if (crosses(u, v, u + 1, v, slash, n))
					{
						l.east[j * l.w + i] = false;
					}
					if (crosses(u, v, u, v + 1, slash, n))
					{
						l.north[j * l.w + i] = false;
					}
				}
			}
		}
	}

	/** Whether the segment between two points of a tile n cells wide crosses its diagonal, "/" or "\". */
	private static boolean crosses(float u0, float v0, float u1, float v1, boolean slash, int n)
	{
		float fa = slash ? v0 - u0 : u0 + v0 - n, fb = slash ? v1 - u1 : u1 + v1 - n;
		if (fa * fb >= 0)
		{
			return false;
		}
		float t = fa / (fa - fb);
		float u = u0 + (u1 - u0) * t, v = v0 + (v1 - v0) * t;
		return u >= 0 && u <= n && v >= 0 && v <= n;
	}

	/**
	 * The light's own fixture does not shade it: the boxes of the cell the point is in and the cells around it, that the
	 * point is inside or within FIXTURE of, are left out.
	 */
	private static void dropFixture(Layer l, float px, float pz)
	{
		float m = FIXTURE / CELL;
		int ci = (int) Math.floor(px), cj = (int) Math.floor(pz);
		for (int j = cj - 1; j <= cj + 1; ++j)
		{
			for (int i = ci - 1; i <= ci + 1; ++i)
			{
				if (i < 0 || j < 0 || i >= l.w || j >= l.h || l.boxOf[j * l.w + i] == 0)
				{
					continue;
				}
				int o = (l.boxOf[j * l.w + i] - 1) * 4;
				if (px >= l.boxes[o] - m && px <= l.boxes[o + 2] + m && pz >= l.boxes[o + 1] - m && pz <= l.boxes[o + 3] + m)
				{
					l.boxOf[j * l.w + i] = 0;
					l.covered[j * l.w + i] = false;
					l.dense[j * l.w + i] = false;
				}
			}
		}
	}

	/**
	 * Where the light is cast from, local cells, in out: its own place in an open cell, kept SOURCE_CLEARANCE off the
	 * cell's walls; from a blocked cell the nearest point of the nearest open one. False when there is none.
	 */
	private static boolean source(Layer l, float sx, float sz, float[] out)
	{
		int ci = (int) Math.floor(sx), cj = (int) Math.floor(sz);
		float clear = SOURCE_CLEARANCE / CELL;
		if (ci >= 0 && cj >= 0 && ci < l.w && cj < l.h && l.free[cj * l.w + ci])
		{
			float px = sx, pz = sz;
			if (!joined(l, ci, cj, -1, 0) && px < ci + clear)
			{
				px = ci + clear;
			}
			else if (!joined(l, ci, cj, 1, 0) && px > ci + 1 - clear)
			{
				px = ci + 1 - clear;
			}
			if (!joined(l, ci, cj, 0, -1) && pz < cj + clear)
			{
				pz = cj + clear;
			}
			else if (!joined(l, ci, cj, 0, 1) && pz > cj + 1 - clear)
			{
				pz = cj + 1 - clear;
			}
			out[0] = px;
			out[1] = pz;
			return true;
		}
		float best = Float.POSITIVE_INFINITY;
		for (int j = Math.max(0, cj - RELOCATE_REACH); j <= Math.min(l.h - 1, cj + RELOCATE_REACH); ++j)
		{
			for (int i = Math.max(0, ci - RELOCATE_REACH); i <= Math.min(l.w - 1, ci + RELOCATE_REACH); ++i)
			{
				if (!l.free[j * l.w + i])
				{
					continue;
				}
				// the nearest point of the cell, a little inside it
				float px = Math.max(i + clear, Math.min(i + 1 - clear, sx)), pz = Math.max(j + clear, Math.min(j + 1 - clear, sz));
				float d = (px - sx) * (px - sx) + (pz - sz) * (pz - sz);
				if (d < best)
				{
					best = d;
					out[0] = px;
					out[1] = pz;
				}
			}
		}
		return best < Float.POSITIVE_INFINITY;
	}

	/** Whether light passes from cell (i, j) to its neighbour (i + di, j + dj), one step along an axis. */
	private static boolean joined(Layer l, int i, int j, int di, int dj)
	{
		int ni = i + di, nj = j + dj;
		if (ni < 0 || nj < 0 || ni >= l.w || nj >= l.h)
		{
			return true;
		}
		if (!l.free[nj * l.w + ni])
		{
			return false;
		}
		if (di != 0)
		{
			return l.east[j * l.w + Math.min(i, ni)];
		}
		return l.north[Math.min(j, nj) * l.w + i];
	}

	// ---------------------------------------------------------------------------------------------------------
	// Soft line of sight

	/** The blocker edges of the layer, merged into runs, and its diagonal walls. */
	private static void collectSegments(Scratch s, Layer l)
	{
		s.segmentCount = 0;
		for (int j = 0; j + 1 < l.h; ++j)
		{
			int run = -1;
			for (int i = 0; i <= l.w; ++i)
			{
				boolean blocked = i < l.w && (!l.north[j * l.w + i] || l.free[j * l.w + i] != l.free[(j + 1) * l.w + i]);
				if (blocked && run < 0)
				{
					run = i;
				}
				else if (!blocked && run >= 0)
				{
					addSegment(s, run, j + 1, i, j + 1);
					run = -1;
				}
			}
		}
		for (int i = 0; i + 1 < l.w; ++i)
		{
			int run = -1;
			for (int j = 0; j <= l.h; ++j)
			{
				boolean blocked = j < l.h && (!l.east[j * l.w + i] || l.free[j * l.w + i] != l.free[j * l.w + i + 1]);
				if (blocked && run < 0)
				{
					run = j;
				}
				else if (!blocked && run >= 0)
				{
					addSegment(s, i + 1, run, i + 1, j);
					run = -1;
				}
			}
		}
		for (int d = 0; d < l.diagonalCount; ++d)
		{
			addSegment(s, l.diagonals[d * 4], l.diagonals[d * 4 + 1], l.diagonals[d * 4 + 2], l.diagonals[d * 4 + 3]);
		}
		for (int n = 0; n < l.boxCellCount; ++n)
		{
			int b = l.boxOf[l.boxCells[n]];
			if (b == 0)
			{
				continue;
			}
			float x0 = l.boxes[(b - 1) * 4], z0 = l.boxes[(b - 1) * 4 + 1], x1 = l.boxes[(b - 1) * 4 + 2], z1 = l.boxes[(b - 1) * 4 + 3];
			addSegment(s, x0, z0, x1, z0);
			addSegment(s, x1, z0, x1, z1);
			addSegment(s, x1, z1, x0, z1);
			addSegment(s, x0, z1, x0, z0);
		}
	}

	private static void addSegment(Scratch s, float x0, float z0, float x1, float z1)
	{
		if (s.segmentCount * 4 == s.segments.length)
		{
			s.segments = Arrays.copyOf(s.segments, s.segments.length * 2);
		}
		int o = s.segmentCount++ * 4;
		s.segments[o] = x0;
		s.segments[o + 1] = z0;
		s.segments[o + 2] = x1;
		s.segments[o + 3] = z1;
	}

	/** The open cells within a radius (cells) of the light, in s.candidates. */
	private static void gatherCandidates(Scratch s, Layer l, float sx, float sz, float rc)
	{
		s.candidateCount = 0;
		float r2 = (rc + 1) * (rc + 1);
		for (int j = 0; j < l.h; ++j)
		{
			for (int i = 0; i < l.w; ++i)
			{
				float dx = i + 0.5f - sx, dz = j + 0.5f - sz;
				if (l.free[j * l.w + i] && dx * dx + dz * dz <= r2)
				{
					s.candidates[s.candidateCount++] = j * l.w + i;
				}
			}
		}
	}

	/**
	 * The share of the light's samples that see each candidate cell, in s.vis. The samples are spread over the light's
	 * width around its source point (px, pz); those that can't be reached from the source, or stand in a blocked cell,
	 * are left out.
	 */
	private static void visibility(Scratch s, Layer l, float px, float pz)
	{
		int cells = l.w * l.h;
		Arrays.fill(s.hits, 0, cells, (short) 0);
		Arrays.fill(s.vis, 0, cells, 0);
		float half = LIGHT_WIDTH_TILES * CELLS_PER_TILE * 0.5f;
		int samples = 0;
		for (int k = 0; k < SAMPLES; ++k)
		{
			float r = half * (float) Math.sqrt((k + 0.5f) / SAMPLES), a = k * GOLDEN_ANGLE;
			float x = px + r * (float) Math.cos(a), z = pz + r * (float) Math.sin(a);
			int ci = (int) Math.floor(x), cj = (int) Math.floor(z);
			if (x < 0 || z < 0 || ci >= l.w || cj >= l.h || !l.free[cj * l.w + ci] || l.inBox(x, z) || crossesAny(s, px, pz, x, z))
			{
				continue;
			}
			sweep(s, l, x, z);
			++samples;
		}
		if (samples == 0)
		{
			// nothing of the disc can be reached from the source: the source alone
			sweep(s, l, px, pz);
			samples = 1;
		}
		float inv = 1f / (samples * SUBS * SUBS);
		for (int n = 0; n < s.candidateCount; ++n)
		{
			int c = s.candidates[n];
			s.vis[c] = s.hits[c] * inv;
		}
	}

	private static boolean crossesAny(Scratch s, float ax, float az, float bx, float bz)
	{
		float[] g = s.segments;
		for (int o = 0; o < s.segmentCount * 4; o += 4)
		{
			float o1 = orient(ax, az, bx, bz, g[o], g[o + 1]), o2 = orient(ax, az, bx, bz, g[o + 2], g[o + 3]);
			float o3 = orient(g[o], g[o + 1], g[o + 2], g[o + 3], ax, az), o4 = orient(g[o], g[o + 1], g[o + 2], g[o + 3], bx, bz);
			if (o1 * o2 < 0 && o3 * o4 < 0)
			{
				return true;
			}
		}
		return false;
	}

	private static float orient(float ax, float az, float bx, float bz, float cx, float cz)
	{
		return (bx - ax) * (cz - az) - (bz - az) * (cx - ax);
	}

	/** A direction's place in a turn, 0 to 4, in order around the point: the angle without the trigonometry. */
	private static float pseudoAngle(float dx, float dz)
	{
		float p = dx / (Math.abs(dx) + Math.abs(dz));
		return dz < 0 ? 3 + p : 1 - p;
	}

	/**
	 * One sweep from (px, pz): the nearest blocker in each direction, then every candidate cell nearer than it counts a
	 * hit.
	 */
	private static void sweep(Scratch s, Layer l, float px, float pz)
	{
		float[] depth = s.depth;
		Arrays.fill(depth, Float.POSITIVE_INFINITY);
		float[] g = s.segments;
		for (int o = 0; o < s.segmentCount * 4; o += 4)
		{
			float ax = g[o] - px, az = g[o + 1] - pz, bx = g[o + 2] - px, bz = g[o + 3] - pz;
			float cross = ax * bz - az * bx;
			// a blocker edge on, or edge-on to, the point covers no direction
			if (Math.abs(cross) < 1e-6f)
			{
				continue;
			}
			if (cross < 0)
			{
				float tx = ax, tz = az;
				ax = bx;
				az = bz;
				bx = tx;
				bz = tz;
			}
			int first = Math.min(BINS - 1, (int) (pseudoAngle(ax, az) * (BINS / 4f)));
			int last = Math.min(BINS - 1, (int) (pseudoAngle(bx, bz) * (BINS / 4f)));
			int count = (last - first + BINS) % BINS + 1;
			float ex = bx - ax, ez = bz - az;
			float len2 = ex * ex + ez * ez;
			float nearA = (float) Math.sqrt(ax * ax + az * az), nearB = (float) Math.sqrt(bx * bx + bz * bz);
			float ae = ax * ez - az * ex;
			for (int n = 0, bin = first; n < count; ++n, bin = bin + 1 == BINS ? 0 : bin + 1)
			{
				float ux = BIN_DX[bin], uz = BIN_DZ[bin];
				float den = ux * ez - uz * ex;
				float t;
				if (Math.abs(den) < 1e-9f)
				{
					t = Math.min(nearA, nearB);
				}
				else
				{
					t = ae / den;
					// where the middle of the bin's ray meets the line of the edge: past an end of the edge, the bin
					// is only partly covered, and the end's distance is the edge's along the covered part
					float along = ((ux * t - ax) * ex + (uz * t - az) * ez) / len2;
					if (t <= 0 || along < 0)
					{
						t = nearA;
					}
					else if (along > 1)
					{
						t = nearB;
					}
				}
				if (t < depth[bin])
				{
					depth[bin] = t;
				}
			}
		}
		int w = l.w;
		for (int n = 0; n < s.candidateCount; ++n)
		{
			int k = s.candidates[n];
			int seen = 0;
			for (int sj = 0; sj < SUBS; ++sj)
			{
				for (int si = 0; si < SUBS; ++si)
				{
					float dx = k % w + (si + 0.5f) / SUBS - px, dz = k / w + (sj + 0.5f) / SUBS - pz;
					float d2 = dx * dx + dz * dz;
					if (d2 < 1e-6f)
					{
						seen++;
						continue;
					}
					int bin = Math.min(BINS - 1, (int) (pseudoAngle(dx, dz) * (BINS / 4f)));
					float limit = depth[bin];
					if (limit == Float.POSITIVE_INFINITY || d2 < limit * limit)
					{
						seen++;
					}
				}
			}
			s.hits[k] += seen;
		}
		s.visits += s.candidateCount * SUBS * SUBS;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Wrap

	/**
	 * The path distance (cells) from the light to each candidate cell around the blockers, in s.dist; infinity where
	 * it isn't reached within the radius.
	 */
	private static void flood(Scratch s, Layer l, float px, float pz, float sx, float sz, float rc)
	{
		int w = l.w;
		Arrays.fill(s.dist, 0, w * l.h, Float.POSITIVE_INFINITY);
		int src = (int) Math.floor(pz) * w + (int) Math.floor(px);
		// the way from the light to the source cell's middle
		float start = (float) Math.hypot(sx - ((int) Math.floor(px) + 0.5f), sz - ((int) Math.floor(pz) + 0.5f));
		Heap heap = s.heap;
		heap.clear();
		s.dist[src] = start;
		heap.push(start, src);
		while (heap.size > 0)
		{
			int k = heap.pop();
			float d = heap.popped;
			if (d > s.dist[k])
			{
				continue;
			}
			s.visits++;
			int i = k % w, j = k / w;
			for (int dj = -1; dj <= 1; ++dj)
			{
				for (int di = -1; di <= 1; ++di)
				{
					if ((di == 0 && dj == 0) || !step(l, i, j, di, dj))
					{
						continue;
					}
					int nk = k + dj * w + di;
					float nd = d + (di != 0 && dj != 0 ? SQRT2 : 1);
					if (nd < rc + 1 && nd < s.dist[nk] && !l.dense[nk])
					{
						s.dist[nk] = nd;
						heap.push(nd, nk);
					}
				}
			}
		}
	}

	/** Whether light passes from cell (i, j) to the neighbour (di, dj) away, a corner too when both ways round it are open. */
	private static boolean step(Layer l, int i, int j, int di, int dj)
	{
		if (di == 0 || dj == 0)
		{
			return joined(l, i, j, di, dj);
		}
		int ni = i + di, nj = j + dj;
		if (ni < 0 || nj < 0 || ni >= l.w || nj >= l.h)
		{
			return false;
		}
		return joined(l, i, j, di, 0) && joined(l, i, j, 0, dj) && joined(l, ni, nj, -di, 0) && joined(l, ni, nj, 0, -dj);
	}

	// ---------------------------------------------------------------------------------------------------------
	// Smoothing

	/**
	 * Smooth the open cells with a Gaussian of BLUR_SIGMA_CELLS along each axis in turn, a cell mixing in only the cells
	 * it is joined to by open steps, and taking the mean of those: the blur never takes light across a wall or a solid
	 * object, and does not darken what stands beside one.
	 */
	private static void blur(Scratch s, Layer l, float[] value)
	{
		int w = l.w, h = l.h;
		float[] tmp = s.mix;
		byte[] before = s.runBefore, after = s.runAfter;
		for (int pass = 0; pass < 2; ++pass)
		{
			boolean x = pass == 0;
			float[] from = x ? value : tmp, to = x ? tmp : value;
			int stride = x ? 1 : w;
			// how many open steps lead on from each cell, either way along the axis (up to the blur's reach)
			for (int j = 0; j < h; ++j)
			{
				for (int i = 0; i < w; ++i)
				{
					int k = j * w + i;
					int pi = x ? i - 1 : i, pj = x ? j : j - 1;
					before[k] = (byte) (pi >= 0 && pj >= 0 && l.free[k] && !l.dense[k] && openStep(l, i, j, pi - i, pj - j)
						? Math.min(BLUR_REACH, before[k - stride] + 1) : 0);
				}
			}
			for (int j = h - 1; j >= 0; --j)
			{
				for (int i = w - 1; i >= 0; --i)
				{
					int k = j * w + i;
					int ni = x ? i + 1 : i, nj = x ? j : j + 1;
					after[k] = (byte) (ni < w && nj < h && l.free[k] && !l.dense[k] && openStep(l, i, j, ni - i, nj - j)
						? Math.min(BLUR_REACH, after[k + stride] + 1) : 0);
				}
			}
			for (int k = 0; k < w * h; ++k)
			{
				if (!l.free[k] || l.dense[k])
				{
					continue;
				}
				float sum = from[k], weight = 1;
				for (int d = 1; d <= before[k]; ++d)
				{
					sum += BLUR[d] * from[k - d * stride];
					weight += BLUR[d];
				}
				for (int d = 1; d <= after[k]; ++d)
				{
					sum += BLUR[d] * from[k + d * stride];
					weight += BLUR[d];
				}
				to[k] = sum / weight;
			}
		}
		s.visits += w * h;
	}

	/** Whether the blur passes from cell (i, j) to the neighbour one step away: open, and not a model that fills its cell. */
	private static boolean openStep(Layer l, int i, int j, int di, int dj)
	{
		return joined(l, i, j, di, dj) && !l.dense[(j + dj) * l.w + i + di];
	}

	/** The floor in the middle of a model's box is lit like the open cells beside it. */
	private static void fillCovered(Layer l, float[] value)
	{
		int w = l.w, h = l.h;
		for (int n = 0; n < l.boxCellCount; ++n)
		{
			int k = l.boxCells[n];
			if (!l.covered[k])
			{
				continue;
			}
			int i = k % w, j = k / w;
			float best = 0;
			for (int d = 0; d < 4; ++d)
			{
				int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
				if (ni >= 0 && nj >= 0 && ni < w && nj < h && l.free[nj * w + ni] && !l.covered[nj * w + ni]
					&& edgeOpen(l, ni, nj, i - ni, j - nj))
				{
					best = Math.max(best, value[nj * w + ni]);
				}
			}
			if (best > value[k])
			{
				value[k] = best;
			}
		}
	}

	/**
	 * Blocked cells take the best light of the open cells next to them: the floor beside a thin wall or a pillar is in
	 * such a cell, and is lit like its neighbours.
	 */
	private static void dilate(Layer l, float[] value)
	{
		int w = l.w, h = l.h;
		for (int j = 0; j < h; ++j)
		{
			for (int i = 0; i < w; ++i)
			{
				int k = j * w + i;
				if (l.free[k])
				{
					continue;
				}
				float best = 0;
				for (int d = 0; d < 4; ++d)
				{
					int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
					if (ni >= 0 && nj >= 0 && ni < w && nj < h && l.free[nj * w + ni]
						&& edgeOpen(l, ni, nj, i - ni, j - nj))
					{
						best = Math.max(best, value[nj * w + ni]);
					}
				}
				value[k] = best;
			}
		}
	}

	/** Whether no wall stands on the edge between a cell and the neighbour one step away, whatever the neighbour is. */
	private static boolean edgeOpen(Layer l, int i, int j, int di, int dj)
	{
		int ni = i + di, nj = j + dj;
		if (di != 0)
		{
			return l.east[j * l.w + Math.min(i, ni)];
		}
		return l.north[Math.min(j, nj) * l.w + i];
	}

	private static Grid crop(int plane, int x0, int z0, int w, int h, float[] value)
	{
		int minX = w, minZ = h, maxX = -1, maxZ = -1;
		for (int j = 0; j < h; ++j)
		{
			for (int i = 0; i < w; ++i)
			{
				if (value[j * w + i] > 0)
				{
					minX = Math.min(minX, i);
					maxX = Math.max(maxX, i);
					minZ = Math.min(minZ, j);
					maxZ = Math.max(maxZ, j);
				}
			}
		}
		if (maxX < 0)
		{
			return null;
		}
		int gw = maxX - minX + 1, gh = maxZ - minZ + 1;
		float[] c = new float[gw * gh];
		for (int j = 0; j < gh; ++j)
		{
			System.arraycopy(value, (minZ + j) * w + minX, c, j * gw, gw);
		}
		return new Grid(plane, x0 + minX, z0 + minZ, gw, gh, c);
	}

	// ---------------------------------------------------------------------------------------------------------
	// The other storeys

	private static Grid otherStorey(Light light, LightBlockers b, Scratch s, int plane, float radius, int x0, int z0,
		int w, int h, int off)
	{
		int tOff = b.offset;
		int ltx = (int) Math.floor(light.x / 128) + tOff, lty = (int) Math.floor(light.z / 128) + tOff;
		// no cell of it can be within the radius of the light
		if (Math.abs(b.floor(plane, ltx, lty) - light.y) - 200 > radius)
		{
			return null;
		}
		Layer l = s.other;
		load(l, b, plane, x0, z0, w, h, -1);
		float[] value = s.value2;
		Arrays.fill(value, 0, w * h, 0);
		boolean any = false;
		for (int k = 0; k < w * h; ++k)
		{
			if (!l.free[k])
			{
				continue;
			}
			float cx = (x0 + k % w + 0.5f - off) * CELL, cz = (z0 + k / w + 0.5f - off) * CELL;
			float d2 = (float) Math.hypot(cx - light.x, cz - light.z);
			if (d2 >= radius)
			{
				continue;
			}
			s.visits++;
			int ctx = (int) Math.floor(cx / 128) + tOff, cty = (int) Math.floor(cz / 128) + tOff;
			float seen = 0;
			for (float ref : CROSS_HEIGHTS)
			{
				float ry = b.floor(plane, ctx, cty) - ref;
				if (Math.hypot(d2, ry - light.y) < radius)
				{
					seen += through(light, b, s, plane, cx, cz, ry, x0, z0, off);
				}
			}
			float v = seen / CROSS_HEIGHTS.length * falloff(d2 / radius);
			value[k] = v;
			any |= v > 0;
		}
		if (!any)
		{
			return null;
		}
		blur(s, l, value);
		dilate(l, value);
		return crop(plane, x0, z0, w, h, value);
	}

	/**
	 * How much of the light gets from its place to the point (cx, ry, cz) on another storey: 0 when a floor with a tile
	 * in the way, a wall of the storeys it passes through, or the light's own storey (the share that sees the first
	 * opening) stops it.
	 */
	private static float through(Light light, LightBlockers b, Scratch s, int plane, float cx, float cz, float ry,
		int x0, int z0, int off)
	{
		int tOff = b.offset;
		int ltx = (int) Math.floor(light.x / 128) + tOff, lty = (int) Math.floor(light.z / 128) + tOff;
		float dy = ry - light.y;
		if (Math.abs(dy) < 1e-3f)
		{
			return 0;
		}
		int from = light.plane;
		boolean up = plane > from;
		float px = light.x, pz = light.z;
		int storey = from;
		float seen = -1;
		for (int k = up ? from + 1 : from; up ? k <= plane : k > plane; k += up ? 1 : -1)
		{
			float t = (b.floor(k, ltx, lty) - light.y) / dy;
			if (!(t > 0 && t < 1))
			{
				continue;
			}
			float qx = light.x + (cx - light.x) * t, qz = light.z + (cz - light.z) * t;
			if (b.hasFloor(k, (int) Math.floor(qx / 128) + tOff, (int) Math.floor(qz / 128) + tOff))
			{
				return 0;
			}
			if (seen < 0)
			{
				seen = visibleAt(s, qx, qz, x0, z0, off);
				if (seen <= 0)
				{
					return 0;
				}
			}
			else if (b.walls.blocked(storey, px, pz, qx, qz))
			{
				return 0;
			}
			px = qx;
			pz = qz;
			storey = up ? k : k - 1;
		}
		if (seen < 0 || b.walls.blocked(plane, px, pz, cx, cz))
		{
			return 0;
		}
		return seen;
	}

	/** The light's own storey's visibility at a world position, 1 outside the cells swept. */
	private static float visibleAt(Scratch s, float x, float z, int x0, int z0, int off)
	{
		Layer l = s.own;
		int i = (int) Math.floor(x / CELL) + off - x0, j = (int) Math.floor(z / CELL) + off - z0;
		if (i < 0 || j < 0 || i >= l.w || j >= l.h)
		{
			return 1;
		}
		return l.free[j * l.w + i] ? s.vis[j * l.w + i] : 0;
	}

	// ---------------------------------------------------------------------------------------------------------

	/** A binary min-heap of cells by distance. */
	private static final class Heap
	{
		private float[] key = new float[256];
		private int[] item = new int[256];
		int size;
		float popped;

		void clear()
		{
			size = 0;
		}

		void push(float k, int v)
		{
			if (size == key.length)
			{
				key = Arrays.copyOf(key, size * 2);
				item = Arrays.copyOf(item, size * 2);
			}
			int i = size++;
			while (i > 0)
			{
				int parent = (i - 1) >> 1;
				if (key[parent] <= k)
				{
					break;
				}
				key[i] = key[parent];
				item[i] = item[parent];
				i = parent;
			}
			key[i] = k;
			item[i] = v;
		}

		int pop()
		{
			int top = item[0];
			popped = key[0];
			float k = key[--size];
			int v = item[size];
			int i = 0;
			while (true)
			{
				int child = i * 2 + 1;
				if (child >= size)
				{
					break;
				}
				if (child + 1 < size && key[child + 1] < key[child])
				{
					child++;
				}
				if (key[child] >= k)
				{
					break;
				}
				key[i] = key[child];
				item[i] = item[child];
				i = child;
			}
			key[i] = k;
			item[i] = v;
			return top;
		}
	}
}
