package com.midonarhel.runelight;

import java.util.Arrays;

/**
 * How much of a static light a cell of the light map (LightMap) sees, per height layer in it: the share of the
 * light that is not stopped on its way to the layer's height above the cell, 0 to 1, with no falloff in it: the shader
 * has the light's position and works the falloff out for every pixel (what a flat, per-cell falloff could not show was
 * the shape of what it lit). HEIGHT_LAYERS layers cover a storey's height, each worked out for the height of its
 * middle, so what the models and the floors above stop is stopped at the heights it stands. Cells farther than the
 * light's radius are 0. GL-free; runs on the baking threads.
 *
 * A cell is a quarter tile. What stops light is in LightBlockers: wall edges (and diagonal walls), the tiles of solid
 * objects, and the boxes of the models (ModelBlockers.runs, a box for each run of heights, as big as the model is in
 * the cell). On the light's own storey:
 *
 * - Soft line of sight. A light isn't a point: SAMPLES points are spread over a disc LIGHT_WIDTH_TILES wide, each swept
 *   exactly. A sweep from a point finds, for each of BINS directions around it, the distance to the nearest blocker
 *   (every blocker edge in reach is walked once, over the directions it covers), then every cell is seen when it is
 *   nearer than that in its direction, looked at in SUBS x SUBS places of the cell so that an edge falling inside a
 *   cell gives it a part. The cost is the edges plus the cells. The share of the looks that see a cell is its
 *   visibility. (One pass of neighbour
 *   propagation was tried instead and a light between two nearby blockers got trapped.) Walls stop the light at every
 *   height; a model stops it at the heights it stands: its box stops the line from the light to a layer where that line
 *   passes it within the heights of the model. A model that stands at the light's height but not at the layer's
 *   (a shelf above a floor layer) shades from farther on, where the line from the light to the layer has dropped past it:
 *   the box scaled up from the light by how much farther. The sweeps are worked out once, for what stops light at every
 *   layer, and again only for the directions and the layers where a model's box differs.
 * - Wrap. Light walks around corners and through doors and stands in for bounce light: a flood fill over the cells,
 *   blocked by the same blockers, gives each the path distance from the light, and WRAP times the falloff by that
 *   distance, as a share of the falloff straight from the light, is a floor under the direct light's visibility. It is the same flood for every layer.
 * - Blur. A Gaussian of BLUR_SIGMA_CELLS that never takes a cell's light from across a wall, a solid object or a model
 *   that fills its cell (DENSE_BOX), so shadows have soft edges and no cell shows. The floor under a model's box takes its
 *   neighbours' light, and wrap does not go through a model that fills its cell.
 * - A light inside the footprint of its own object (a brazier, a caged lantern, a wall torch) is not shaded by the
 *   boxes it stands in or next to (FIXTURE), and casts from the nearest open cell when it is in a solid tile, a little
 *   way from the walls of that cell, so a fixture never traps its own light. The light's real position still gives
 *   the distance.
 *
 * The other storeys get the light that passes through the openings between them: the line from the light to a point
 * of the cell, at the height of each layer above its floor, has to cross every floor between them where the storey has
 * no floor or roof tile, from the light to the first opening as visible as it is on its own storey (at its top layer),
 * and past the walls of the storeys it goes through. (Models are not followed onto the other storeys: no one height of
 * theirs is the light's.)
 */
final class LightMapBake
{
	static final float CELL = 32;
	static final int CELLS_PER_TILE = LightBlockers.CELLS_PER_TILE;
	/** How wide a light is, in tiles: its samples are spread over a disc this wide. */
	static final float LIGHT_WIDTH_TILES = 0.8f;
	static final int SAMPLES = 16;
	/** The share of the falloff by path distance that stays where the light only gets around corners (of the falloff straight from the light, at most this much). */
	static final float WRAP = 0.35f;
	/** The blur's Gaussian, in cells (a quarter tile): about half a tile. */
	static final float BLUR_SIGMA_CELLS = 2;
	/** Places looked at along each side of a cell, for its visibility. */
	static final int SUBS = 2;
	/** Models closer than this (world units) to a light are its own fixture and do not shade it. */
	static final float FIXTURE = 10;
	/** A model that covers less than this share of its cell across a line from the light barely shades it; all of it, shades it fully. */
	static final float COVER_KNEE = 0.25f;
	/** The height (above the floor, world units) a see-through wall reaches: a storey's. */
	static final float SEE_THROUGH_HEIGHT = ModelBlockers.HEIGHT_BINS * ModelBlockers.BIN_HEIGHT;
	/** The share of the light a see-through wall (bars, a fence, a railing) stops: the rest goes through. */
	static final float SEE_THROUGH_OPACITY = 0.15f;
	/** A model whose box fills this much of a cell (the share of its area) is a solid for the wrap of light. */
	static final float DENSE_BOX = 0.5f;
	/** The thinnest a model's box is taken to be (world units): a leaf of a door stops light across its length. */
	static final float MIN_BOX = 4;
	/** Lights that reach further than this (world units) are not baked: the bake would end them at its edge. */
	static final float MAX_RADIUS = 3200;
	/** Directions a sweep tells apart: at the far end of a big light still a few world units a direction. */
	static final int BINS = 2048;
	/** Bins to a block of the sweep's depths, whose nearest and farthest blocker tell which cells need no closer look. */
	private static final int BLOCK_SHIFT = 4, BLOCK = 1 << BLOCK_SHIFT, BLOCKS = BINS / BLOCK, BLOCK_LEVELS = 8;
	/** Cells to a side of the blocks of cells that a sweep settles together, how far (cells) a look in a cell or a block lies from its middle (with room for float rounding), and how near a block can be to be told apart. */
	private static final int CELL_BLOCK = 4;
	private static final float CELL_REACH = 0.37f, BLOCK_REACH = (CELL_BLOCK / 2f - 0.25f) * 1.4143f + 0.02f, BLOCK_NEAR = 2.1f * BLOCK_REACH;
	/** A light is cast this far (world units) off the walls of its cell. */
	static final float SOURCE_CLEARANCE = 8;
	/** How far (cells) from a blocked cell the nearest open one is looked for. */
	private static final int RELOCATE_REACH = 16;
	/** Layers in a cell, over the height of a storey that the models' bins reach: each is baked for its middle. */
	static final int HEIGHT_LAYERS = 4;
	static final float LAYER_HEIGHT = ModelBlockers.HEIGHT_BINS * ModelBlockers.BIN_HEIGHT / HEIGHT_LAYERS;
	/** A model that shades a layer from farther than this many times its distance does not: the line to the layer is too near the light's height. */
	private static final float MAX_SHADOW_SCALE = 16;

	/** The height above its floor a layer is baked for: its middle. */
	static float layerHeight(int layer)
	{
		return (layer + 0.5f) * LAYER_HEIGHT;
	}

	private static final int JOIN_E = 1, JOIN_W = 2, JOIN_N = 4, JOIN_S = 8;
	private static final int BLUR_REACH = (int) Math.ceil(2.5f * BLUR_SIGMA_CELLS);
	private static final float[] BLUR = new float[BLUR_REACH + 1];
	// the weights of a run of b cells before a cell and a after it, and the cell, added in the blur's order
	private static final float[][] BLUR_WEIGHTS = new float[BLUR_REACH + 1][BLUR_REACH + 1];
	private static final float GOLDEN_ANGLE = 2.3999632f;
	// the steps of the sequences that spread the samples a box is counted in, over the samples and over the boxes
	private static final double GOLDEN = 0.6180339887498949, PLASTIC = 0.7548776662466927;
	private static final float SQRT2 = 1.4142135f;
	private static final float[] BIN_DX = new float[BINS], BIN_DZ = new float[BINS];

	static
	{
		for (int d = 0; d <= BLUR_REACH; ++d)
		{
			BLUR[d] = (float) Math.exp(-d * d / (2.0 * BLUR_SIGMA_CELLS * BLUR_SIGMA_CELLS));
		}
		for (int b = 0; b <= BLUR_REACH; ++b)
		{
			for (int a = 0; a <= BLUR_REACH; ++a)
			{
				float weight = 1;
				for (int d = 1; d <= b; ++d)
				{
					weight += BLUR[d];
				}
				for (int d = 1; d <= a; ++d)
				{
					weight += BLUR[d];
				}
				BLUR_WEIGHTS[b][a] = weight;
			}
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
		final int plane, owner;
		final float x, y, z, radius;

		/** @param owner the scene object the light is on (VoxelCapture.ownerOf), whose own geometry never shades it; 0 for a world light */
		Light(int plane, float x, float y, float z, float radius, int owner)
		{
			this.owner = owner;
			this.plane = plane;
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
		}
	}

	/**
	 * A storey's visibility of a light per cell and layer, a rectangle of the scene's cells: c[((z - z0) * w + x - x0) *
	 * HEIGHT_LAYERS + layer]. The rectangle holds a ring of cells without light round the lit ones.
	 */
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
		// the models' boxes (the light's own storey), as x0, z0, x1, z1 in cells of the layer, one for each run of heights a
		// model has in a cell; per box its bottom and top (above the floor), and whether the light's fixture took it
		float[] boxes = new float[64];
		float[] runs = new float[32];
		// per box, how much of the cell it covers seen along z and along x (0 to 1)
		float[] cover = new float[32];
		// per box, the share of the light it stops whatever the line, or -1 to tell it from the cover
		float[] stopped = new float[16];
		boolean[] dropped = new boolean[16];
		int boxCount;
		// the boxes at the light's height, in the cell's: per cell the box + 1 (0: none), and the cells that have one
		int[] boxOf = new int[0], boxCells = new int[16];
		int boxCellCount;
		// the cell's middle is inside its box
		boolean[] covered = new boolean[0];
		// the box fills most of the cell: wrap does not go through
		boolean[] dense = new boolean[0];
		// per cell, a bit for each of the four steps (JOIN_E, JOIN_W, JOIN_N, JOIN_S) that joined() lets light make
		byte[] join = new byte[0];

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
				join = new byte[w * h];
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

		/** Fill join from the walls and the open cells: once for a layer, for the flood and the blur to share. */
		void computeJoin()
		{
			for (int j = 0; j < h; ++j)
			{
				for (int i = 0; i < w; ++i)
				{
					int k = j * w + i;
					join[k] = (byte) ((i + 1 >= w || free[k + 1] && east[k] ? JOIN_E : 0) | (i == 0 || free[k - 1] && east[k - 1] ? JOIN_W : 0)
						| (j + 1 >= h || free[k + w] && north[k] ? JOIN_N : 0) | (j == 0 || free[k - w] && north[k - w] ? JOIN_S : 0));
				}
			}
		}

		/** @param near the box is at the light's height: the cell's floor is lit round it, and the light's fixture may be it */
		void addBox(int cell, float x0, float z0, float x1, float z1, float low, float high, float coverX, float coverZ, float stop, boolean near)
		{
			if (boxCount * 4 == boxes.length)
			{
				boxes = Arrays.copyOf(boxes, boxes.length * 2);
				runs = Arrays.copyOf(runs, runs.length * 2);
				cover = Arrays.copyOf(cover, cover.length * 2);
				stopped = Arrays.copyOf(stopped, stopped.length * 2);
				dropped = Arrays.copyOf(dropped, dropped.length * 2);
			}
			int o = boxCount * 4;
			boxes[o] = x0;
			boxes[o + 1] = z0;
			boxes[o + 2] = x1;
			boxes[o + 3] = z1;
			runs[boxCount * 2] = low;
			runs[boxCount * 2 + 1] = high;
			cover[boxCount * 2] = coverX;
			cover[boxCount * 2 + 1] = coverZ;
			stopped[boxCount] = stop;
			dropped[boxCount] = false;
			++boxCount;
			if (near)
			{
				if (boxCellCount == boxCells.length)
				{
					boxCells = Arrays.copyOf(boxCells, boxCellCount * 2);
				}
				boxOf[cell] = boxCount;
				boxCells[boxCellCount++] = cell;
			}
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

	/** Line segments, x0, z0, x1, z1 each, in cells of the layer. */
	private static final class Segments
	{
		float[] v = new float[256];
		int count;

		void add(float x0, float z0, float x1, float z1)
		{
			if (count * 4 == v.length)
			{
				v = Arrays.copyOf(v, v.length * 2);
			}
			int o = count++ * 4;
			v[o] = x0;
			v[o + 1] = z0;
			v[o + 2] = x1;
			v[o + 3] = z1;
		}

		/** The four sides of a box. */
		void box(float x0, float z0, float x1, float z1)
		{
			add(x0, z0, x1, z0);
			add(x1, z0, x1, z1);
			add(x1, z1, x0, z1);
			add(x0, z1, x0, z0);
		}
	}

	/** What a baking thread keeps between lights. */
	static final class Scratch
	{
		private final Layer own = new Layer(), other = new Layer();
		// what stops light at every layer: walls, diagonals and the boxes of the models that stand at all of them
		private final Segments segments = new Segments();
		// the boxes that stop it at some layers, by the number of the box in the layer, and per layer how far each is scaled
		// from the light (1: where it stands, 0: not at all)
		private int[] partials = new int[16];
		// the boxes that stop it at every layer but only partly (a model that does not fill its cell), and the ones of them
		// that a sample's sweep counts: stochastically, by how much they stop (stops)
		private int[] softBoxes = new int[16];
		private int softCount;
		private final Segments chosen = new Segments();
		private int partialCount;
		private float[] partialScale = new float[16 * HEIGHT_LAYERS];
		// per layer, whether any box differs from the common ones
		private final boolean[] layerDiffers = new boolean[HEIGHT_LAYERS];
		// one layer's own segments of a sweep, its depths, and the bins where they differ from the common ones, counted
		private final Segments extra = new Segments();
		private boolean anyDiffer;
		private float reach;
		private final float[] depth = new float[BINS], layerDepth = new float[BINS];
		private final int[] changed = new int[BINS + 1];
		private final float[] layerBlockMin = new float[BLOCK_LEVELS * (BINS / BLOCK)], layerBlockMax = new float[BLOCK_LEVELS * (BINS / BLOCK)];
		// the sweep's nearest and farthest blocker over each block of BLOCK bins, and the candidates' cell columns and rows
		private final float[] blockMin = new float[BLOCK_LEVELS * (BINS / BLOCK)], blockMax = new float[BLOCK_LEVELS * (BINS / BLOCK)];
		private int[] candX = new int[0], candZ = new int[0], order = new int[0], blockStart = new int[0], blockAdd = new int[0];
		private int blockCols, blockRows;
		private int[] candidates = new int[0];
		private int candidateCount;
		private short[] hits = new short[0];
		// what the sweep in hand sees: per cell the looks that see it, per block of cells 0 none, 1 cell by cell, 2 all
		private byte[] sampleSeen = new byte[0], sampleBlock = new byte[0];
		// per layer that differs, per cell: how many looks more or fewer than the common sweeps see
		private final short[][] delta = new short[HEIGHT_LAYERS][];
		private byte[] runBefore = new byte[0], runAfter = new byte[0];
		// the visibility of the top layer, per cell; the layers' light per cell, HEIGHT_LAYERS to a cell
		private float[] vis = new float[0], value = new float[0], value2 = new float[0], mix = new float[0], dist = new float[0];
		// the flood's two queues: cells reached by an axis step and by a diagonal step, each in order of distance
		private float[] axisKey = new float[0], diagKey = new float[0];
		private int[] axisCell = new int[0], diagCell = new int[0];
		// the storeys the line from the light to a cell on another storey crosses, and each one's height from the light
		private final int[] crossStorey = new int[WallMap.PLANES];
		private final float[] crossNum = new float[WallMap.PLANES];
		// whether a storey has walls anywhere in the light's rectangle
		private final boolean[] storeyWalls = new boolean[WallMap.PLANES];
		// per cell of the light's rectangle, for all the other storeys: its distance from the light, and its tile's place in the floors
		private float[] cellDistance = new float[0];
		private int[] cellTile = new int[0];
		private boolean cellsKnown;
		private long visits;

		private void size(int cells)
		{
			if (vis.length < cells)
			{
				runBefore = new byte[cells];
				runAfter = new byte[cells];
				candidates = new int[cells];
				hits = new short[cells];
				sampleSeen = new byte[cells];
				for (int l = 0; l < HEIGHT_LAYERS; ++l)
				{
					delta[l] = new short[cells];
				}
				vis = new float[cells];
				value = new float[cells * HEIGHT_LAYERS];
				value2 = new float[cells * HEIGHT_LAYERS];
				mix = new float[cells * HEIGHT_LAYERS];
				dist = new float[cells];
			}
		}
	}

	private LightMapBake()
	{
	}

	/**
	 * The light's share per cell and layer on every storey it reaches.
	 */
	static Result bake(Light light, LightBlockers b, Scratch s)
	{
		s.visits = 0;
		s.cellsKnown = false;
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
		// how high the light is above its storey's floor
		float height = b.floor(light.plane, (int) Math.floor(light.x / 128) + b.offset,
			(int) Math.floor(light.z / 128) + b.offset) - light.y;
		load(own, b, light.plane, x0, z0, w, h, ModelBlockers.bin(height), light.owner);
		float sx = lx - x0, sz = lz - z0;
		float[] source = new float[2];
		if (!source(own, sx, sz, source))
		{
			return new Result(new Grid[0], 0);
		}
		if (light.owner == 0)
		{
			// a light on an object is not shaded by it: that object's own geometry was left out. A world light only has FIXTURE
			dropFixture(own, sx, sz);
			dropFixture(own, source[0], source[1]);
		}
		collectSegments(s, own, height);
		gatherCandidates(s, own, sx, sz, rc);
		s.reach = rc + 1;
		float[] value = s.value;
		Arrays.fill(value, 0, w * h * HEIGHT_LAYERS, 0);
		int samples = visibility(s, own, source[0], source[1]);
		float inv = 1f / (samples * SUBS * SUBS);
		float invRadius = 1 / radius;
		// the light's height above each layer's middle (negative: below)
		float[] up = new float[HEIGHT_LAYERS];
		for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
		{
			up[layer] = layerHeight(layer) - height;
		}
		for (int n = 0; n < s.candidateCount; ++n)
		{
			int k = s.candidates[n];
			float flat = hypot(k % w + 0.5f - sx, k / w + 0.5f - sz) * CELL;
			int seen = s.hits[k] + s.blockAdd[(s.candZ[n] / CELL_BLOCK) * s.blockCols + s.candX[n] / CELL_BLOCK];
			for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
			{
				float vis = (seen + (s.layerDiffers[layer] ? s.delta[layer][k] : 0)) * inv;
				value[k * HEIGHT_LAYERS + layer] = vis;
			}
			s.vis[k] = (seen + (s.layerDiffers[HEIGHT_LAYERS - 1] ? s.delta[HEIGHT_LAYERS - 1][k] : 0)) * inv;
		}
		own.computeJoin();
		flood(s, own, source[0], source[1], sx, sz, rc);
		for (int n = 0; n < s.candidateCount; ++n)
		{
			int k = s.candidates[n];
			float path = s.dist[k] * CELL;
			for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
			{
				int i = k * HEIGHT_LAYERS + layer;
				float flat = hypot(k % w + 0.5f - sx, k / w + 0.5f - sz) * CELL;
				float direct = falloff(hypot(flat, up[layer]) * invRadius);
				if (direct > 0)
				{
					// the light that wraps round, as a share of the light that would arrive straight
					float wrapped = Math.min(1, WRAP * falloff(hypot(path, up[layer]) * invRadius) / direct);
					value[i] = Math.max(value[i], wrapped);
				}
			}
		}
		fillCovered(own, value);
		blur(s, own, value);
		dilate(own, value);
		// the blur spread the light past its radius: there is none there
		for (int k = 0; k < w * h; ++k)
		{
			float flat = hypot(k % w + 0.5f - sx, k / w + 0.5f - sz) * CELL;
			for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
			{
				if (hypot(flat, up[layer]) >= radius)
				{
					value[k * HEIGHT_LAYERS + layer] = 0;
				}
			}
		}

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

	/** Math.hypot as a float, without its cost: the squares of floats are exact in a double. */
	private static float hypot(float a, float b)
	{
		return (float) Math.sqrt((double) a * a + (double) b * b);
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
	private static void load(Layer l, LightBlockers b, int plane, int x0, int z0, int w, int h, int bin, int owner)
	{
		l.reset(w, h);
		float[] box = new float[ModelBlockers.MAX_RUNS * ModelBlockers.RUN];
		float thin = MIN_BOX / CELL;
		for (int j = 0; j < h; ++j)
		{
			int lastTile = Integer.MIN_VALUE;
			boolean open = true, see = false;
			for (int i = 0; i < w; ++i)
			{
				// a solid object fills its tile: asked once for the cells of a tile that follow one another
				int tile = Math.floorDiv(x0 + i, CELLS_PER_TILE);
				if (tile != lastTile)
				{
					lastTile = tile;
					open = !b.solid(plane, x0 + i, z0 + j, bin >= 0);
					// the bars and fences of the tile are the wall map's, not the models': the wall objects' boxes are left out
					see = bin >= 0 && b.walls.anySeeThrough(plane, tile, Math.floorDiv(z0 + j, CELLS_PER_TILE));
				}
				l.free[j * w + i] = open;
				int runs = bin >= 0 && b.models != null ? b.models.runs(plane, x0 + i, z0 + j, bin, owner, see, box) : 0;
				for (int r = 0; r < runs; ++r)
				{
					int o = r * ModelBlockers.RUN;
					// as thin as MIN_BOX at the least, inside the cell
					for (int a = 0; a < 2; ++a)
					{
						if (box[o + a + 2] - box[o + a] < thin)
						{
							float mid = (box[o + a] + box[o + a + 2]) * 0.5f;
							box[o + a] = Math.max(0, Math.min(1 - thin, mid - thin * 0.5f));
							box[o + a + 2] = box[o + a] + thin;
						}
					}
					boolean near = box[o + 6] != 0;
					l.addBox(j * w + i, i + box[o], j + box[o + 1], i + box[o + 2], j + box[o + 3], box[o + 4], box[o + 5], box[o + 7], box[o + 8], -1, near);
					if (near)
					{
						l.covered[j * w + i] = box[o] <= 0.5f && box[o + 2] >= 0.5f && box[o + 1] <= 0.5f && box[o + 3] >= 0.5f;
						l.dense[j * w + i] = (box[o + 2] - box[o]) * (box[o + 3] - box[o + 1]) >= DENSE_BOX;
					}
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
				if (bin >= 0)
				{
					// bars, fences and railings stop a little of the light, on the edge, whatever its height
					boolean sn = walls.seeThrough(plane, tx, ty, WallMap.NORTH), se = walls.seeThrough(plane, tx, ty, WallMap.EAST);
					boolean ss = ty == ty0 && walls.seeThrough(plane, tx, ty, WallMap.SOUTH);
					boolean sw = tx == tx0 && walls.seeThrough(plane, tx, ty, WallMap.WEST);
					for (int k = 0; (sn | se | ss | sw) && k < CELLS_PER_TILE; ++k)
					{
						seeThroughEdge(l, sn, ax + k, az + CELLS_PER_TILE, true);
						seeThroughEdge(l, se, ax + CELLS_PER_TILE, az + k, false);
						seeThroughEdge(l, ss, ax + k, az, true);
						seeThroughEdge(l, sw, ax, az + k, false);
					}
				}
			}
		}
	}

	/** A see-through wall along a cell's side at (i, j), the cell's corner (cells): a thin box that stops SEE_THROUGH_OPACITY of the light. */
	private static void seeThroughEdge(Layer l, boolean wall, int i, int j, boolean alongX)
	{
		if (!wall || i < 0 || j < 0 || i > l.w || j > l.h)
		{
			return;
		}
		float thin = MIN_BOX / CELL * 0.5f;
		if (alongX)
		{
			l.addBox(0, i, j - thin, i + 1, j + thin, 0, SEE_THROUGH_HEIGHT, 0.5f, 0.5f, SEE_THROUGH_OPACITY, false);
		}
		else
		{
			l.addBox(0, i - thin, j, i + thin, j + 1, 0, SEE_THROUGH_HEIGHT, 0.5f, 0.5f, SEE_THROUGH_OPACITY, false);
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
					l.dropped[l.boxOf[j * l.w + i] - 1] = true;
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

	/**
	 * How far from the light a model whose bins reach from low to high (heights above the floor) starts to stop the line from
	 * the light (at height light) to the middle of a layer (at height layer), as a multiple of its own distance: 1 where the
	 * layer is among the model's heights, so that the line is among them wherever it passes the model; and over it or
	 * under it, where the line has not yet risen past its top (or dropped past its bottom): the model stops the line to
	 * the layer from the distance at which the line leaves the model's heights. 0: the line never gets among them (the
	 * layer is short of the model's heights, or on the light's side of them), or leaves them too close to the light to
	 * be told from there.
	 */
	private static float shadowScale(float light, float low, float high, float layer)
	{
		if (layer >= low && layer <= high)
		{
			return 1;
		}
		float edge = layer < low ? low : high;
		float rise = layer - light, gap = edge - light;
		if (rise * gap <= 0 || Math.abs(rise) < Math.abs(gap) || Math.abs(gap) < 1e-3f)
		{
			return 0;
		}
		float scale = rise / gap;
		return scale > MAX_SHADOW_SCALE ? 0 : scale;
	}

	/**
	 * The blocker edges of the layer, merged into runs, and its diagonal walls; the edges of the boxes that stop light at
	 * every layer. The others are kept to be scaled for each layer's sweeps (sweep).
	 *
	 * @param lightHeight the light's height above its floor
	 */
	private static void collectSegments(Scratch s, Layer l, float lightHeight)
	{
		s.segments.count = 0;
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
					s.segments.add(run, j + 1, i, j + 1);
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
					s.segments.add(i + 1, run, i + 1, j);
					run = -1;
				}
			}
		}
		for (int d = 0; d < l.diagonalCount; ++d)
		{
			s.segments.add(l.diagonals[d * 4], l.diagonals[d * 4 + 1], l.diagonals[d * 4 + 2], l.diagonals[d * 4 + 3]);
		}
		s.partialCount = 0;
		s.softCount = 0;
		s.anyDiffer = false;
		Arrays.fill(s.layerDiffers, false);
		for (int b = 0; b < l.boxCount; ++b)
		{
			if (l.dropped[b])
			{
				continue;
			}
			float low = l.runs[b * 2], high = l.runs[b * 2 + 1];
			boolean everywhere = true;
			if (s.partialScale.length < (s.partialCount + 1) * HEIGHT_LAYERS)
			{
				s.partialScale = Arrays.copyOf(s.partialScale, s.partialScale.length * 2);
				s.partials = Arrays.copyOf(s.partials, s.partials.length * 2);
			}
			for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
			{
				float scale = shadowScale(lightHeight, low, high, layerHeight(layer));
				s.partialScale[s.partialCount * HEIGHT_LAYERS + layer] = scale;
				everywhere &= scale == 1;
			}
			if (!everywhere)
			{
				s.partials[s.partialCount++] = b;
				for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
				{
					if (s.partialScale[(s.partialCount - 1) * HEIGHT_LAYERS + layer] != 0)
					{
						s.layerDiffers[layer] = true;
						s.anyDiffer = true;
					}
				}
				continue;
			}
			if (l.cover[b * 2] >= 1 && l.cover[b * 2 + 1] >= 1)
			{
				// it fills its cell all the way across
				s.segments.box(l.boxes[b * 4], l.boxes[b * 4 + 1], l.boxes[b * 4 + 2], l.boxes[b * 4 + 3]);
				continue;
			}
			if (s.softCount == s.softBoxes.length)
			{
				s.softBoxes = Arrays.copyOf(s.softBoxes, s.softCount * 2);
			}
			s.softBoxes[s.softCount++] = b;
		}
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
	 * The looks of the light's samples that see each candidate cell, in s.hits and s.blockAdd (and in s.delta, per layer
	 * that has models of its own to see by). The samples are spread over the light's width around its source point
	 * (px, pz); those that can't be reached from the source, or stand in a blocked cell, are left out.
	 *
	 * @return the samples swept
	 */
	private static int visibility(Scratch s, Layer l, float px, float pz)
	{
		int cells = l.w * l.h;
		Arrays.fill(s.hits, 0, cells, (short) 0);
		Arrays.fill(s.vis, 0, cells, 0);
		for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
		{
			if (s.layerDiffers[layer])
			{
				Arrays.fill(s.delta[layer], 0, cells, (short) 0);
			}
		}
		if (s.candX.length < s.candidateCount)
		{
			s.candX = new int[s.candidates.length];
			s.candZ = new int[s.candidates.length];
		}
		for (int n = 0; n < s.candidateCount; ++n)
		{
			int k = s.candidates[n], j = k / l.w;
			s.candZ[n] = j;
			s.candX[n] = k - j * l.w;
		}
		groupCandidates(s, l);
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
			sweep(s, l, x, z, k);
			++samples;
		}
		if (samples == 0)
		{
			// nothing of the disc can be reached from the source: the source alone
			sweep(s, l, px, pz, 0);
			samples = 1;
		}
		return samples;
	}

	/** The candidates grouped by block of CELL_BLOCK x CELL_BLOCK cells: s.order lists them block after block. */
	private static void groupCandidates(Scratch s, Layer l)
	{
		int cols = (l.w + CELL_BLOCK - 1) / CELL_BLOCK, rows = (l.h + CELL_BLOCK - 1) / CELL_BLOCK;
		s.blockCols = cols;
		s.blockRows = rows;
		if (s.blockStart.length < cols * rows + 1)
		{
			s.blockStart = new int[cols * rows + 1];
			s.blockAdd = new int[cols * rows + 1];
			s.sampleBlock = new byte[cols * rows + 1];
			s.sampleBlock = new byte[cols * rows + 1];
		}
		if (s.order.length < s.candidateCount)
		{
			s.order = new int[s.candidates.length];
		}
		int[] start = s.blockStart;
		Arrays.fill(start, 0, cols * rows + 1, 0);
		Arrays.fill(s.blockAdd, 0, cols * rows + 1, 0);
		for (int n = 0; n < s.candidateCount; ++n)
		{
			++start[(s.candZ[n] / CELL_BLOCK) * cols + s.candX[n] / CELL_BLOCK + 1];
		}
		for (int b = 0; b < cols * rows; ++b)
		{
			start[b + 1] += start[b];
		}
		// start[b] is the next free place of block b while filling, and the first one of block b + 1 after
		for (int n = 0; n < s.candidateCount; ++n)
		{
			s.order[start[(s.candZ[n] / CELL_BLOCK) * cols + s.candX[n] / CELL_BLOCK]++] = n;
		}
		// shift back to the first place of each block
		for (int b = cols * rows; b > 0; --b)
		{
			start[b] = start[b - 1];
		}
		start[0] = 0;
	}

	private static boolean crossesAny(Scratch s, float ax, float az, float bx, float bz)
	{
		float[] g = s.segments.v;
		for (int o = 0; o < s.segments.count * 4; o += 4)
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
	 * Whether a sweep sees every look at a patch of cells (1), none of them (-1), or some (0): the patch's looks all
	 * lie within reach (cells) of its middle at (cx, cz) from the sweep's point, d away, so they fall in the bins
	 * around the middle's, and the nearest and the farthest blocker over those bins bound the depths they are
	 * compared with. Only for patches farther than their reach twice over.
	 */
	private static int classify(float[] blockMin, float[] blockMax, float cx, float cz, float d, float reach)
	{
		int mid = Math.min(BINS - 1, (int) (pseudoAngle(cx, cz) * (BINS / 4f)));
		int bins = binsAround(d, reach);
		int b0 = (mid - bins) >> BLOCK_SHIFT, b1 = (mid + bins) >> BLOCK_SHIFT;
		int len = b1 - b0 + 1;
		float lo, hi;
		if (len >= BLOCKS)
		{
			lo = blockMin[(BLOCK_LEVELS - 1) * BLOCKS];
			hi = blockMax[(BLOCK_LEVELS - 1) * BLOCKS];
		}
		else
		{
			int k = 31 - Integer.numberOfLeadingZeros(len), o = k * BLOCKS;
			int u = b0 & (BLOCKS - 1), v = (b1 - (1 << k) + 1) & (BLOCKS - 1);
			lo = Math.min(blockMin[o + u], blockMin[o + v]);
			hi = Math.max(blockMax[o + u], blockMax[o + v]);
		}
		if (d + reach < lo)
		{
			return 1;
		}
		return d - reach > hi ? -1 : 0;
	}

	/** The bins either side of a direction that the looks of a patch within reach (cells) of a point d away fall in: at most 512 bins to a radian of angle, and the arc of a chord of the reach is a few percent over its sine. */
	private static int binsAround(float d, float reach)
	{
		return (int) (538f * reach / d) + 3;
	}

	/** Whether the patch's looks (as classify's) fall in a bin where the layer's depths differ from the common ones. */
	private static boolean anyChanged(int[] changed, float cx, float cz, float d, float reach)
	{
		int mid = Math.min(BINS - 1, (int) (pseudoAngle(cx, cz) * (BINS / 4f)));
		int bins = binsAround(d, reach);
		int a = mid - bins, b = mid + bins;
		if (bins * 2 + 1 >= BINS)
		{
			return changed[BINS] > 0;
		}
		if (a < 0)
		{
			return changed[b + 1] > 0 || changed[BINS] - changed[a + BINS] > 0;
		}
		if (b >= BINS)
		{
			return changed[BINS] - changed[a] > 0 || changed[b + 1 - BINS] > 0;
		}
		return changed[b + 1] - changed[a] > 0;
	}

	/**
	 * How much of the light a model's box stops on the line from (px, pz) to it (0 to 1). A model does not fill its box: what
	 * stops a line is the part of the cell the model covers across the line (the box's cover, along z for a line that runs
	 * along z, along x for one along x, between for a slant), and a model that covers under COVER_KNEE of the cell across
	 * stops nothing to speak of, one that covers all of it everything, with a smooth turn between.
	 */
	private static float opacity(Layer l, int box, float px, float pz)
	{
		if (l.stopped[box] >= 0)
		{
			return l.stopped[box];
		}
		float coverX = l.cover[box * 2], coverZ = l.cover[box * 2 + 1];
		float dx = (l.boxes[box * 4] + l.boxes[box * 4 + 2]) * 0.5f - px, dz = (l.boxes[box * 4 + 1] + l.boxes[box * 4 + 3]) * 0.5f - pz;
		float len = hypot(dx, dz);
		float across = len < 1e-3f ? (coverX + coverZ) * 0.5f : (Math.abs(dz) * coverX + Math.abs(dx) * coverZ) / len;
		float t = Math.max(0, Math.min(1, (across - COVER_KNEE) / (1 - COVER_KNEE)));
		return t * t * (3 - 2 * t);
	}

	/**
	 * Whether a sample's sweep counts a box as a blocker. The samples are the light's width, so a box that stops some of the light
	 * is counted by that share of them, spread evenly over the samples and differently for each box: the lines through
	 * several boxes keep the share that gets past each, and the shade is soft.
	 */
	private static boolean stops(Layer l, int box, float px, float pz, int sample)
	{
		double u = (box * PLASTIC + sample * GOLDEN) % 1;
		return u < opacity(l, box, px, pz);
	}

	/**
	 * One sweep from (px, pz): the nearest blocker in each direction, then every candidate cell nearer than it counts a
	 * hit. The layers whose models differ from the common ones are swept again where they do.
	 */
	private static void sweep(Scratch s, Layer l, float px, float pz, int sample)
	{
		Arrays.fill(s.depth, Float.POSITIVE_INFINITY);
		nearest(s.depth, s.segments, px, pz);
		if (s.softCount > 0)
		{
			s.chosen.count = 0;
			for (int n = 0; n < s.softCount; ++n)
			{
				int b = s.softBoxes[n];
				if (stops(l, b, px, pz, sample))
				{
					s.chosen.box(l.boxes[b * 4], l.boxes[b * 4 + 1], l.boxes[b * 4 + 2], l.boxes[b * 4 + 3]);
				}
			}
			nearest(s.depth, s.chosen, px, pz);
		}
		tables(s.depth, s.blockMin, s.blockMax);
		settle(s, l, px, pz);
		for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
		{
			if (s.layerDiffers[layer])
			{
				differ(s, l, px, pz, layer, sample);
			}
		}
		s.visits += s.candidateCount * SUBS * SUBS;
	}

	/** The nearest blocker in each direction from (px, pz), among the depths in hand and the segments. */
	private static void nearest(float[] depth, Segments segments, float px, float pz)
	{
		float[] g = segments.v;
		for (int o = 0; o < segments.count * 4; o += 4)
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
	}

	/** The nearest and the farthest blocker over blocks of bins, and over runs of 2^k blocks on from each, round the turn. */
	private static void tables(float[] depth, float[] blockMin, float[] blockMax)
	{
		for (int b = 0; b < BLOCKS; ++b)
		{
			float lo = Float.POSITIVE_INFINITY, hi = 0;
			for (int bin = b * BLOCK; bin < (b + 1) * BLOCK; ++bin)
			{
				float d = depth[bin];
				lo = d < lo ? d : lo;
				hi = d > hi ? d : hi;
			}
			blockMin[b] = lo;
			blockMax[b] = hi;
		}
		// level k: the nearest and the farthest over 2^k blocks on from each, round the turn
		for (int k = 1; k < BLOCK_LEVELS; ++k)
		{
			int o = k * BLOCKS, p = o - BLOCKS, half = 1 << (k - 1);
			for (int b = 0; b < BLOCKS; ++b)
			{
				int c = p + ((b + half) & (BLOCKS - 1));
				blockMin[o + b] = Math.min(blockMin[p + b], blockMin[c]);
				blockMax[o + b] = Math.max(blockMax[p + b], blockMax[c]);
			}
		}
	}

	/**
	 * Blocks of cells that the sweep sees wholly or not at all are settled in one look; the rest cell by cell. What it
	 * sees is kept for the layers that differ.
	 */
	private static void settle(Scratch s, Layer l, float px, float pz)
	{
		int[] order = s.order, start = s.blockStart, add = s.blockAdd;
		boolean keep = s.anyDiffer;
		for (int bj = 0, blk = 0; bj < s.blockRows; ++bj)
		{
			for (int bi = 0; bi < s.blockCols; ++bi, ++blk)
			{
				if (start[blk] == start[blk + 1])
				{
					continue;
				}
				// the middle of the block's cells; every look in them is within BLOCK_REACH of it
				float cx = (bi + 0.5f) * CELL_BLOCK - px, cz = (bj + 0.5f) * CELL_BLOCK - pz;
				float c2 = cx * cx + cz * cz;
				if (c2 >= BLOCK_NEAR * BLOCK_NEAR)
				{
					int verdict = classify(s.blockMin, s.blockMax, cx, cz, (float) Math.sqrt(c2), BLOCK_REACH);
					if (verdict > 0)
					{
						add[blk] += SUBS * SUBS;
						s.sampleBlock[blk] = 2;
						continue;
					}
					if (verdict < 0)
					{
						s.sampleBlock[blk] = 0;
						continue;
					}
				}
				s.sampleBlock[blk] = 1;
				for (int m = start[blk]; m < start[blk + 1]; ++m)
				{
					int n = order[m], i = s.candX[n], j = s.candZ[n], k = j * l.w + i;
					int seen = cellSeen(s.blockMin, s.blockMax, s.depth, i, j, px, pz);
					s.hits[k] += seen;
					if (keep)
					{
						s.sampleSeen[k] = (byte) seen;
					}
				}
			}
		}
	}

	/** How many of the SUBS x SUBS looks at a cell the sweep from (px, pz), with these depths, sees. */
	private static int cellSeen(float[] blockMin, float[] blockMax, float[] depth, int i, int j, float px, float pz)
	{
		float ccx = i + 0.5f - px, ccz = j + 0.5f - pz, cc2 = ccx * ccx + ccz * ccz;
		if (cc2 >= 2.25f)
		{
			int verdict = classify(blockMin, blockMax, ccx, ccz, (float) Math.sqrt(cc2), CELL_REACH);
			if (verdict > 0)
			{
				return SUBS * SUBS;
			}
			if (verdict < 0)
			{
				return 0;
			}
		}
		int seen = 0;
		for (int sj = 0; sj < SUBS; ++sj)
		{
			for (int si = 0; si < SUBS; ++si)
			{
				float dx = i + (si + 0.5f) / SUBS - px, dz = j + (sj + 0.5f) / SUBS - pz;
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
		return seen;
	}

	/**
	 * The sweep from (px, pz) for a layer whose models differ from the common ones: the boxes that stop its line from the
	 * light, each where it stands or scaled up from (px, pz), are blockers too. What the layer sees differently from the
	 * common sweep is added to its delta; the cells that look the same way as they did are left as they were.
	 */
	private static void differ(Scratch s, Layer l, float px, float pz, int layer, int sample)
	{
		Segments extra = s.extra;
		extra.count = 0;
		for (int p = 0; p < s.partialCount; ++p)
		{
			float scale = s.partialScale[p * HEIGHT_LAYERS + layer];
			if (scale == 0)
			{
				continue;
			}
			int b = s.partials[p];
			if (!stops(l, b, px, pz, sample))
			{
				continue;
			}
			float x0 = l.boxes[b * 4], z0 = l.boxes[b * 4 + 1], x1 = l.boxes[b * 4 + 2], z1 = l.boxes[b * 4 + 3];
			if (scale != 1)
			{
				// from the light, the box as far off as the line takes to drop past it; out of the light's reach it stops nothing
				float near = hypot(Math.max(0, Math.max(x0 - px, px - x1)), Math.max(0, Math.max(z0 - pz, pz - z1)));
				if (near * scale > s.reach)
				{
					continue;
				}
				x0 = px + (x0 - px) * scale;
				z0 = pz + (z0 - pz) * scale;
				x1 = px + (x1 - px) * scale;
				z1 = pz + (z1 - pz) * scale;
			}
			extra.box(x0, z0, x1, z1);
		}
		if (extra.count == 0)
		{
			return;
		}
		float[] depth = s.layerDepth;
		System.arraycopy(s.depth, 0, depth, 0, BINS);
		nearest(depth, extra, px, pz);
		int[] changed = s.changed;
		changed[0] = 0;
		for (int bin = 0; bin < BINS; ++bin)
		{
			changed[bin + 1] = changed[bin] + (depth[bin] < s.depth[bin] ? 1 : 0);
		}
		if (changed[BINS] == 0)
		{
			return;
		}
		tables(depth, s.layerBlockMin, s.layerBlockMax);
		short[] delta = s.delta[layer];
		int[] order = s.order, start = s.blockStart;
		for (int bj = 0, blk = 0; bj < s.blockRows; ++bj)
		{
			for (int bi = 0; bi < s.blockCols; ++bi, ++blk)
			{
				// a block the common sweep sees nothing of, the layer sees nothing of
				if (start[blk] == start[blk + 1] || s.sampleBlock[blk] == 0)
				{
					continue;
				}
				float cx = (bi + 0.5f) * CELL_BLOCK - px, cz = (bj + 0.5f) * CELL_BLOCK - pz;
				float c2 = cx * cx + cz * cz;
				if (c2 >= BLOCK_NEAR * BLOCK_NEAR && !anyChanged(changed, cx, cz, (float) Math.sqrt(c2), BLOCK_REACH))
				{
					continue;
				}
				boolean all = s.sampleBlock[blk] == 2;
				for (int m = start[blk]; m < start[blk + 1]; ++m)
				{
					int n = order[m], i = s.candX[n], j = s.candZ[n], k = j * l.w + i;
					int before = all ? SUBS * SUBS : s.sampleSeen[k];
					if (before == 0)
					{
						continue;
					}
					float ccx = i + 0.5f - px, ccz = j + 0.5f - pz, cc2 = ccx * ccx + ccz * ccz;
					if (cc2 >= 2.25f && !anyChanged(changed, ccx, ccz, (float) Math.sqrt(cc2), CELL_REACH))
					{
						continue;
					}
					delta[k] += (short) (cellSeen(s.layerBlockMin, s.layerBlockMax, depth, i, j, px, pz) - before);
				}
			}
		}
	}

	// ---------------------------------------------------------------------------------------------------------
	// Wrap

	/**
	 * The path distance (cells) from the light to each candidate cell around the blockers, in s.dist; infinity where
	 * it isn't reached within the radius.
	 */
	private static void flood(Scratch s, Layer l, float px, float pz, float sx, float sz, float rc)
	{
		int w = l.w, h = l.h;
		float[] dist = s.dist;
		Arrays.fill(dist, 0, w * h, Float.POSITIVE_INFINITY);
		boolean[] dense = l.dense;
		byte[] join = l.join;
		int src = (int) Math.floor(pz) * w + (int) Math.floor(px);
		// the way from the light to the source cell's middle
		float start = (float) Math.hypot(sx - ((int) Math.floor(px) + 0.5f), sz - ((int) Math.floor(pz) + 0.5f));
		// Steps cost 1 or the root of 2, so cells are reached in order of distance by two queues, one for each cost: each
		// is in order because the cells are taken in order, and the nearer front is the next cell: Dijkstra's order
		// with no heap. A cell is put in a queue once for every shorter way found to it, at most four of each.
		int cap = w * h * 4 + 1;
		if (s.axisKey.length < cap)
		{
			s.axisKey = new float[cap];
			s.diagKey = new float[cap];
			s.axisCell = new int[cap];
			s.diagCell = new int[cap];
		}
		float[] ak = s.axisKey, dk = s.diagKey;
		int[] ac = s.axisCell, dc = s.diagCell;
		int ah = 0, at = 0, dh = 0, dt = 0;
		float limit = rc + 1;
		dist[src] = start;
		ak[at] = start;
		ac[at++] = src;
		int visits = 0;
		while (ah < at || dh < dt)
		{
			int k;
			float d;
			if (dh >= dt || (ah < at && ak[ah] <= dk[dh]))
			{
				d = ak[ah];
				k = ac[ah++];
			}
			else
			{
				d = dk[dh];
				k = dc[dh++];
			}
			if (d > dist[k])
			{
				continue;
			}
			visits++;
			int i = k % w, j = k / w;
			int jm = join[k];
			float nAxis = d + 1, nDiag = d + SQRT2;
			if (nAxis < limit)
			{
				// east, west, north, south
				if ((jm & JOIN_E) != 0 && i + 1 < w)
				{
					int nk = k + 1;
					if (nAxis < dist[nk] && !dense[nk])
					{
						dist[nk] = nAxis;
						ak[at] = nAxis;
						ac[at++] = nk;
					}
				}
				if ((jm & JOIN_W) != 0 && i > 0)
				{
					int nk = k - 1;
					if (nAxis < dist[nk] && !dense[nk])
					{
						dist[nk] = nAxis;
						ak[at] = nAxis;
						ac[at++] = nk;
					}
				}
				if ((jm & JOIN_N) != 0 && j + 1 < h)
				{
					int nk = k + w;
					if (nAxis < dist[nk] && !dense[nk])
					{
						dist[nk] = nAxis;
						ak[at] = nAxis;
						ac[at++] = nk;
					}
				}
				if ((jm & JOIN_S) != 0 && j > 0)
				{
					int nk = k - w;
					if (nAxis < dist[nk] && !dense[nk])
					{
						dist[nk] = nAxis;
						ak[at] = nAxis;
						ac[at++] = nk;
					}
				}
			}
			if (nDiag < limit)
			{
				// a corner is turned when both ways round it are open, from both sides
				for (int dj = -1; dj <= 1; dj += 2)
				{
					int nj = j + dj;
					if (nj < 0 || nj >= h)
					{
						continue;
					}
					int vert = dj > 0 ? JOIN_N : JOIN_S, vertBack = dj > 0 ? JOIN_S : JOIN_N;
					if ((jm & vert) == 0)
					{
						continue;
					}
					for (int di = -1; di <= 1; di += 2)
					{
						int ni = i + di;
						if (ni < 0 || ni >= w)
						{
							continue;
						}
						int nk = k + dj * w + di;
						if (!(nDiag < dist[nk]) || dense[nk])
						{
							continue;
						}
						int horiz = di > 0 ? JOIN_E : JOIN_W, horizBack = di > 0 ? JOIN_W : JOIN_E;
						int nm = join[nk];
						if ((jm & horiz) != 0 && (nm & horizBack) != 0 && (nm & vertBack) != 0)
						{
							dist[nk] = nDiag;
							dk[dt] = nDiag;
							dc[dt++] = nk;
						}
					}
				}
			}
		}
		s.visits += visits;
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
		blur(s, l, value, 0, 0, l.w - 1, l.h - 1);
	}

	/**
	 * The blur of the cells i0..i1, j0..j1 (inclusive). The layers of a cell are blurred together: the cells they are
	 * mixed with are the same. The layer outside the rectangle must have no light in it, and be no nearer than the blur's
	 * reach to the light there is, or what it would give is left out.
	 */
	private static void blur(Scratch s, Layer l, float[] value, int i0, int j0, int i1, int j1)
	{
		int w = l.w, h = l.h;
		float[] tmp = s.mix;
		byte[] before = s.runBefore, after = s.runAfter;
		boolean[] open = l.free, dense = l.dense;
		byte[] join = l.join;
		// the second pass reads the first's cells up to the reach beyond the rectangle, where there is nothing
		for (int j = Math.max(0, j0 - BLUR_REACH); j <= Math.min(h - 1, j1 + BLUR_REACH); ++j)
		{
			Arrays.fill(tmp, (j * w + i0) * HEIGHT_LAYERS, (j * w + i1 + 1) * HEIGHT_LAYERS, 0f);
		}
		for (int pass = 0; pass < 2; ++pass)
		{
			boolean x = pass == 0;
			float[] from = x ? value : tmp, to = x ? tmp : value;
			int stride = x ? 1 : w;
			int step = stride * HEIGHT_LAYERS;
			int stepBefore = x ? JOIN_W : JOIN_S, stepAfter = x ? JOIN_E : JOIN_N;
			// how many open steps lead on from each cell, either way along the axis (up to the blur's reach); counted
			// from the reach before the rectangle, which is as far back as it matters
			int a0 = x ? Math.max(0, i0 - BLUR_REACH) : i0, a1 = x ? Math.min(w - 1, i1 + BLUR_REACH) : i1;
			int b0 = x ? j0 : Math.max(0, j0 - BLUR_REACH), b1 = x ? j1 : Math.min(h - 1, j1 + BLUR_REACH);
			for (int j = b0; j <= b1; ++j)
			{
				for (int i = a0; i <= a1; ++i)
				{
					int k = j * w + i;
					boolean first = x ? i == a0 : j == b0;
					int p = k - stride;
					before[k] = (byte) (!first && open[k] && !dense[k] && (join[k] & stepBefore) != 0 && !dense[p]
						? Math.min(BLUR_REACH, before[p] + 1) : 0);
				}
			}
			for (int j = b1; j >= b0; --j)
			{
				for (int i = a1; i >= a0; --i)
				{
					int k = j * w + i;
					boolean last = x ? i == a1 : j == b1;
					int n = k + stride;
					after[k] = (byte) (!last && open[k] && !dense[k] && (join[k] & stepAfter) != 0 && !dense[n]
						? Math.min(BLUR_REACH, after[n] + 1) : 0);
				}
			}
			for (int j = j0; j <= j1; ++j)
			{
				for (int k = j * w + i0; k <= j * w + i1; ++k)
				{
					if (!open[k] || dense[k])
					{
						continue;
					}
					int nb = before[k], na = after[k];
					float weights = BLUR_WEIGHTS[nb][na];
					for (int c = k * HEIGHT_LAYERS, end = c + HEIGHT_LAYERS; c < end; ++c)
					{
						float sum = from[c];
						if (BLUR_REACH == 5 && nb == 5 && na == 5)
						{
							// the common case, open ground all round: the same sums, in the same order, without the loops
							sum += BLUR[1] * from[c - step];
							sum += BLUR[2] * from[c - 2 * step];
							sum += BLUR[3] * from[c - 3 * step];
							sum += BLUR[4] * from[c - 4 * step];
							sum += BLUR[5] * from[c - 5 * step];
							sum += BLUR[1] * from[c + step];
							sum += BLUR[2] * from[c + 2 * step];
							sum += BLUR[3] * from[c + 3 * step];
							sum += BLUR[4] * from[c + 4 * step];
							sum += BLUR[5] * from[c + 5 * step];
						}
						else
						{
							for (int d = 1; d <= nb; ++d)
							{
								sum += BLUR[d] * from[c - d * step];
							}
							for (int d = 1; d <= na; ++d)
							{
								sum += BLUR[d] * from[c + d * step];
							}
						}
						to[c] = sum / weights;
					}
				}
			}
		}
		s.visits += (i1 - i0 + 1) * (j1 - j0 + 1);
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
			for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
			{
				float best = 0;
				for (int d = 0; d < 4; ++d)
				{
					int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
					if (ni >= 0 && nj >= 0 && ni < w && nj < h && l.free[nj * w + ni] && !l.covered[nj * w + ni]
						&& edgeOpen(l, ni, nj, i - ni, j - nj))
					{
						best = Math.max(best, value[(nj * w + ni) * HEIGHT_LAYERS + layer]);
					}
				}
				if (best > value[k * HEIGHT_LAYERS + layer])
				{
					value[k * HEIGHT_LAYERS + layer] = best;
				}
			}
		}
	}

	/**
	 * Blocked cells take the best light of the open cells next to them: the floor beside a thin wall or a pillar is in
	 * such a cell, and is lit like its neighbours.
	 */
	private static void dilate(Layer l, float[] value)
	{
		dilate(l, value, 0, 0, l.w - 1, l.h - 1);
	}

	/** The cells i0..i1, j0..j1 (inclusive) only: no blocked cell outside takes light from the layer. */
	private static void dilate(Layer l, float[] value, int i0, int j0, int i1, int j1)
	{
		int w = l.w, h = l.h;
		for (int j = j0; j <= j1; ++j)
		{
			for (int i = i0; i <= i1; ++i)
			{
				int k = j * w + i;
				if (l.free[k])
				{
					continue;
				}
				for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
				{
					float best = 0;
					for (int d = 0; d < 4; ++d)
					{
						int ni = i + (d == 0 ? 1 : d == 1 ? -1 : 0), nj = j + (d == 2 ? 1 : d == 3 ? -1 : 0);
						if (ni >= 0 && nj >= 0 && ni < w && nj < h && l.free[nj * w + ni]
							&& edgeOpen(l, ni, nj, i - ni, j - nj))
						{
							best = Math.max(best, value[(nj * w + ni) * HEIGHT_LAYERS + layer]);
						}
					}
					value[k * HEIGHT_LAYERS + layer] = best;
				}
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
		return crop(plane, x0, z0, w, h, value, 0, 0, w - 1, h - 1);
	}

	/** The cells i0..i1, j0..j1 (inclusive) are the only ones with light; a ring of cells round them is kept. */
	private static Grid crop(int plane, int x0, int z0, int w, int h, float[] value, int i0, int j0, int i1, int j1)
	{
		int minX = w, minZ = h, maxX = -1, maxZ = -1;
		for (int j = j0; j <= j1; ++j)
		{
			for (int i = i0; i <= i1; ++i)
			{
				for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
				{
					if (value[(j * w + i) * HEIGHT_LAYERS + layer] > 0)
					{
						minX = Math.min(minX, i);
						maxX = Math.max(maxX, i);
						minZ = Math.min(minZ, j);
						maxZ = Math.max(maxZ, j);
						break;
					}
				}
			}
		}
		if (maxX < 0)
		{
			return null;
		}
		// the ring carries where the light is to the cells the light's colour reaches only by filtering
		minX = Math.max(0, minX - 1);
		minZ = Math.max(0, minZ - 1);
		maxX = Math.min(w - 1, maxX + 1);
		maxZ = Math.min(h - 1, maxZ + 1);
		int gw = maxX - minX + 1, gh = maxZ - minZ + 1;
		float[] c = new float[gw * gh * HEIGHT_LAYERS];
		for (int j = 0; j < gh; ++j)
		{
			System.arraycopy(value, ((minZ + j) * w + minX) * HEIGHT_LAYERS, c, j * gw * HEIGHT_LAYERS, gw * HEIGHT_LAYERS);
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
		load(l, b, plane, x0, z0, w, h, -1, 0);
		float[] value = s.value2;
		Arrays.fill(value, 0, w * h * HEIGHT_LAYERS, 0);
		// the floors between the light and the storey, and how far each is from the light
		boolean up = plane > light.plane;
		int crossings = 0;
		for (int k = up ? light.plane + 1 : light.plane; up ? k <= plane : k > plane; k += up ? 1 : -1)
		{
			s.crossStorey[crossings] = k;
			s.crossNum[crossings++] = b.floor(k, ltx, lty) - light.y;
		}
		double reach = (double) radius * radius;
		// the storeys in the light's way, and the target, that have a wall anywhere in the rectangle
		Arrays.fill(s.storeyWalls, false);
		s.storeyWalls[plane] = b.anyWalls(plane, x0, z0, x0 + w - 1, z0 + h - 1);
		for (int n = 0; n < crossings; ++n)
		{
			int k = s.crossStorey[n];
			s.storeyWalls[k] = b.anyWalls(k, x0, z0, x0 + w - 1, z0 + h - 1);
			s.storeyWalls[k - 1] = b.anyWalls(k - 1, x0, z0, x0 + w - 1, z0 + h - 1);
		}
		if (!s.cellsKnown)
		{
			if (s.cellDistance.length < w * h)
			{
				s.cellDistance = new float[w * h];
				s.cellTile = new int[w * h];
			}
			for (int j = 0; j < h; ++j)
			{
				for (int i = 0; i < w; ++i)
				{
					float cx = (x0 + i + 0.5f - off) * CELL, cz = (z0 + j + 0.5f - off) * CELL;
					float d2 = hypot(cx - light.x, cz - light.z);
					s.cellDistance[j * w + i] = d2;
					if (d2 < radius)
					{
						s.cellTile[j * w + i] = b.tileIndex((int) Math.floor(cx / 128) + tOff, (int) Math.floor(cz / 128) + tOff);
					}
				}
			}
			s.cellsKnown = true;
		}
		// the rectangle the light lands in
		int minI = w, minJ = h, maxI = -1, maxJ = -1;
		for (int j = 0; j < h; ++j)
		{
			for (int i = 0; i < w; ++i)
			{
				float d2 = s.cellDistance[j * w + i];
				if (!l.free[j * w + i] || d2 >= radius)
				{
					continue;
				}
				float cx = (x0 + i + 0.5f - off) * CELL, cz = (z0 + j + 0.5f - off) * CELL;
				s.visits++;
				float floorY = b.floorAt(plane, s.cellTile[j * w + i]);
				boolean lit = false;
				for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
				{
					float dy = floorY - layerHeight(layer) - light.y;
					float v = 0;
					if ((double) d2 * d2 + (double) dy * dy < reach)
					{
						v = through(light, b, s, plane, crossings, ltx, lty, cx, cz, dy, x0, z0, off);
					}
					value[(j * w + i) * HEIGHT_LAYERS + layer] = v;
					lit |= v > 0;
				}
				if (lit)
				{
					minI = Math.min(minI, i);
					maxI = Math.max(maxI, i);
					minJ = Math.min(minJ, j);
					maxJ = Math.max(maxJ, j);
				}
			}
		}
		if (maxI < 0)
		{
			return null;
		}
		l.computeJoin();
		// the light spreads no further than the blur's reach, and a blocked cell takes from its neighbours one step more
		int i0 = Math.max(0, minI - BLUR_REACH), i1 = Math.min(w - 1, maxI + BLUR_REACH);
		int j0 = Math.max(0, minJ - BLUR_REACH), j1 = Math.min(h - 1, maxJ + BLUR_REACH);
		blur(s, l, value, i0, j0, i1, j1);
		i0 = Math.max(0, i0 - 1);
		i1 = Math.min(w - 1, i1 + 1);
		j0 = Math.max(0, j0 - 1);
		j1 = Math.min(h - 1, j1 + 1);
		dilate(l, value, i0, j0, i1, j1);
		// the blur spread the light past its radius: there is none there
		for (int j = j0; j <= j1; ++j)
		{
			for (int i = i0; i <= i1; ++i)
			{
				float cx = (x0 + i + 0.5f - off) * CELL, cz = (z0 + j + 0.5f - off) * CELL;
				float flat = hypot(cx - light.x, cz - light.z);
				float floorY = b.floorAt(plane, b.tileIndex((int) Math.floor(cx / 128) + tOff, (int) Math.floor(cz / 128) + tOff));
				for (int layer = 0; layer < HEIGHT_LAYERS; ++layer)
				{
					if (hypot(flat, floorY - layerHeight(layer) - light.y) >= radius)
					{
						value[(j * w + i) * HEIGHT_LAYERS + layer] = 0;
					}
				}
			}
		}
		return crop(plane, x0, z0, w, h, value, i0, j0, i1, j1);
	}

	/**
	 * How much of the light gets from its place to the point (cx, ry, cz) on another storey, ry being dy from the light:
	 * 0 when a floor with a tile in the way, a wall of the storeys it passes through, or the light's own storey (the
	 * share that sees the first opening) stops it. It goes by the crossings floors of s.crossStorey, nearest the light first.
	 */
	private static float through(Light light, LightBlockers b, Scratch s, int plane, int crossings, int ltx, int lty,
		float cx, float cz, float dy, int x0, int z0, int off)
	{
		int tOff = b.offset;
		if (Math.abs(dy) < 1e-3f)
		{
			return 0;
		}
		boolean up = plane > light.plane;
		float px = light.x, pz = light.z;
		int storey = light.plane;
		float seen = -1;
		for (int n = 0; n < crossings; ++n)
		{
			int k = s.crossStorey[n];
			float t = s.crossNum[n] / dy;
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
			else if (s.storeyWalls[storey] && b.blocked(storey, px, pz, qx, qz))
			{
				return 0;
			}
			px = qx;
			pz = qz;
			storey = up ? k : k - 1;
		}
		if (seen < 0 || s.storeyWalls[plane] && b.blocked(plane, px, pz, cx, cz))
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
}
