package com.midonarhel.runelight;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

/**
 * The static models that stand in the way of a light, per cell of the light map (LightMap, a quarter tile) and per
 * storey, from the same zone triangles the skylight voxelizes (VoxelCapture). A cell remembers where in height the
 * models are, and where in the cell:
 *
 * - a mask of HEIGHT_BINS bins of BIN_HEIGHT units above the storey's floor, set for the bins a model reaches;
 * - for each BAND (a quarter of the bins), the box in the cell (to the unit) that the models of the band fill, and how
 *   much of the cell they cover: the columns of the cell (COLUMNS of them, along x and along z) that a model's
 *   silhouette reaches seen along the other axis, which is the share of the lines across the cell that meet the model;
 * - per scene object (VoxelCapture.owner): what a light on an object leaves out of what stands in its way is the
 *   object's own geometry, so a cell keeps a record for each object that stands in it.
 *
 * A light is only stopped by what stands where its line goes (runs): the runs of bins joined to one another, each with its
 * box and its heights. A barrel, a crate, a table or a counter never shades a torch that hangs above it, a pillar, a
 * bookcase, an open furnace door or a statue does shade a lamp at their height, an awning or a roof shades what is
 * behind it at its height and above, and a box is only as big as the model is in the cell, so what stops the light is
 * the model's own size and not a cell's. Walls are the wall map's.
 *
 * Triangles that lie flat (floors, tabletops, ceilings, stair treads) are left out: they stop no light going along the
 * ground, and a ceiling would shut every lamp in. The bins reach to 16 * 15 = 240 units, where a storey's ceiling is.
 *
 * GL-free. Zones are rasterized once and keep their cells (Zone.modelBlockers); the scene's cells are assembled from
 * them.
 */
final class ModelBlockers
{
	static final int PLANES = WallMap.PLANES;
	static final int HEIGHT_BINS = 16;
	static final float BIN_HEIGHT = 15;
	static final int BANDS = 4;
	private static final int BINS_PER_BAND = HEIGHT_BINS / BANDS;
	/** Columns a cell's width is told apart in, for how much of it a model covers (4 units each). */
	static final int COLUMNS = 8;
	/** Numbers per cell and band in a box: x0, z0, x1, z1 in units of the cell (0 to 32), the columns along x and along z. */
	private static final int BOX = 6;
	private static final int RECORD = BANDS * BOX;
	// flatter than this (cosine of the angle to the horizontal) and a triangle is a floor
	private static final float FLAT = 0.95f;
	// cells per zone side (a zone is 8 tiles), and how far a model may overhang its zone
	static final int ZONE_CELLS = 32;
	private static final int MARGIN = 16;
	private static final int SIDE = ZONE_CELLS + 2 * MARGIN;
	private static final float CELL = 32;
	private static final int BLOCK = LightMap.BLOCK;


	/** One zone's cells with models, a record for each object in a cell: key (cx + MARGIN) | (cz + MARGIN) << 7 | storey << 14, the object, the bins' mask, the boxes. */
	static final class Zone
	{
		final int[] keys, owners;
		final short[] masks;
		final byte[] boxes;

		Zone(int[] keys, int[] owners, short[] masks, byte[] boxes)
		{
			this.keys = keys;
			this.owners = owners;
			this.masks = masks;
			this.boxes = boxes;
		}
	}

	final int cells;
	private final int perSide;
	// per storey and block of BLOCK * BLOCK cells, the first record of each cell plus one (0: none); null for a block without
	private final int[][] index;
	// per record: the object, and the next record of the cell plus one (0: the last)
	private int[] owners = new int[64], next = new int[64];
	private short[] masks = new short[64];
	private byte[] boxes = new byte[64 * RECORD];
	private int records;

	ModelBlockers(int cells)
	{
		this.cells = cells;
		perSide = (cells + BLOCK - 1) / BLOCK;
		index = new int[PLANES * perSide * perSide][];
	}

	/** Put an object's record in a cell: its mask and RECORD box numbers from boxes at offset, merged with what the object has there. */
	void set(int plane, int gx, int gz, int owner, short mask, byte[] box, int offset)
	{
		if (gx < 0 || gz < 0 || gx >= cells || gz >= cells || mask == 0)
		{
			return;
		}
		int b = (plane * perSide + gz / BLOCK) * perSide + gx / BLOCK;
		if (index[b] == null)
		{
			index[b] = new int[BLOCK * BLOCK];
		}
		int cell = gz % BLOCK * BLOCK + gx % BLOCK;
		int r = index[b][cell] - 1;
		while (r >= 0 && owners[r] != owner)
		{
			r = next[r] - 1;
		}
		if (r < 0)
		{
			if (records == masks.length)
			{
				masks = Arrays.copyOf(masks, records * 2);
				owners = Arrays.copyOf(owners, records * 2);
				next = Arrays.copyOf(next, records * 2);
				boxes = Arrays.copyOf(boxes, records * 2 * RECORD);
			}
			r = records++;
			owners[r] = owner;
			next[r] = index[b][cell];
			index[b][cell] = r + 1;
			masks[r] = 0;
			for (int i = 0; i < RECORD; i += BOX)
			{
				boxes[r * RECORD + i] = (byte) CELL;
				boxes[r * RECORD + i + 1] = (byte) CELL;
				boxes[r * RECORD + i + 2] = 0;
				boxes[r * RECORD + i + 3] = 0;
				boxes[r * RECORD + i + 4] = 0;
				boxes[r * RECORD + i + 5] = 0;
			}
		}
		merge(r, mask, box, offset);
	}

	/** Every record as the scene dump has it: storey, cell x and z, object, mask, the boxes; then a storey of -1. */
	void write(DataOutputStream out) throws IOException
	{
		out.writeInt(cells);
		for (int b = 0; b < index.length; ++b)
		{
			if (index[b] == null)
			{
				continue;
			}
			int plane = b / (perSide * perSide), bz = b / perSide % perSide, bx = b % perSide;
			for (int cell = 0; cell < index[b].length; ++cell)
			{
				for (int r = index[b][cell] - 1; r >= 0; r = next[r] - 1)
				{
					out.writeInt(plane);
					out.writeInt(bx * BLOCK + cell % BLOCK);
					out.writeInt(bz * BLOCK + cell / BLOCK);
					out.writeInt(owners[r]);
					out.writeShort(masks[r]);
					out.write(boxes, r * RECORD, RECORD);
				}
			}
		}
		out.writeInt(-1);
	}

	/** The first record of a cell, -1 for none. */
	private int record(int plane, int gx, int gz)
	{
		if (gx < 0 || gz < 0 || gx >= cells || gz >= cells)
		{
			return -1;
		}
		int[] block = index[(plane * perSide + gz / BLOCK) * perSide + gx / BLOCK];
		return block == null ? -1 : block[gz % BLOCK * BLOCK + gx % BLOCK] - 1;
	}

	/** The bins a model reaches in the cell, a bit each; 0 without any. */
	int heights(int plane, int gx, int gz)
	{
		int mask = 0;
		for (int r = record(plane, gx, gz); r >= 0; r = next[r] - 1)
		{
			mask |= masks[r] & 0xffff;
		}
		return mask;
	}

	/** The numbers of a run in runs(): its box, its bottom and top, whether it is at the light's height, and its cover along x and z. */
	static final int RUN = 9;
	/** The most runs a cell has: alternate bins. */
	static final int MAX_RUNS = HEIGHT_BINS / 2;

	/**
	 * The runs of what stands in the cell: each set of bins joined to one another, with the box (x0, z0, x1, z1, in units of
	 * the cell, 0 to 1) of the bands it reaches, the heights (units above the floor) it reaches, whether it is at a
	 * light's height (it has a bin in the light's bin or the one either side of it), and how much of the cell it covers
	 * seen along z and seen along x (0 to 1): the mean over its bands of the share of columns the models reach.
	 *
	 * @param exclude the object whose records are left out, 0 for none: the one a light is on
	 * @param out RUN numbers for each run, lowest first
	 * @return the number of runs, 0 when nothing stands in the cell
	 */
	int runs(int plane, int gx, int gz, int bin, int exclude, float[] out)
	{
		int first = record(plane, gx, gz);
		if (first < 0)
		{
			return 0;
		}
		int all = 0;
		// per band: the box, and the columns along x and along z (bakes run on several threads: nothing is shared)
		float[] x0 = new float[BANDS], z0 = new float[BANDS], x1 = new float[BANDS], z1 = new float[BANDS];
		int[] xs = new int[BANDS], zs = new int[BANDS];
		Arrays.fill(x0, CELL);
		Arrays.fill(z0, CELL);
		for (int r = first; r >= 0; r = next[r] - 1)
		{
			if (exclude != 0 && owners[r] == exclude)
			{
				continue;
			}
			all |= masks[r] & 0xffff;
			for (int band = 0; band < BANDS; ++band)
			{
				int o = r * RECORD + band * BOX;
				if (boxes[o + 2] < boxes[o] || boxes[o + 3] < boxes[o + 1])
				{
					continue;
				}
				x0[band] = Math.min(x0[band], boxes[o]);
				z0[band] = Math.min(z0[band], boxes[o + 1]);
				x1[band] = Math.max(x1[band], boxes[o + 2]);
				z1[band] = Math.max(z1[band], boxes[o + 3]);
				xs[band] |= boxes[o + 4] & 0xff;
				zs[band] |= boxes[o + 5] & 0xff;
			}
		}
		int window = ((1 << Math.min(HEIGHT_BINS, bin + 2)) - 1) & ~((1 << Math.max(0, bin - 1)) - 1);
		int count = 0;
		for (int lo = 0; lo < HEIGHT_BINS; ++lo)
		{
			if ((all >> lo & 1) == 0)
			{
				continue;
			}
			int hi = lo;
			while (hi + 1 < HEIGHT_BINS && (all >> hi + 1 & 1) != 0)
			{
				++hi;
			}
			float bx0 = CELL, bz0 = CELL, bx1 = 0, bz1 = 0, coverX = 0, coverZ = 0;
			int bands = 0;
			for (int band = lo / BINS_PER_BAND; band <= hi / BINS_PER_BAND; ++band)
			{
				if (x1[band] < x0[band] || z1[band] < z0[band])
				{
					continue;
				}
				bx0 = Math.min(bx0, x0[band]);
				bz0 = Math.min(bz0, z0[band]);
				bx1 = Math.max(bx1, x1[band]);
				bz1 = Math.max(bz1, z1[band]);
				coverX += Integer.bitCount(xs[band]) / (float) COLUMNS;
				coverZ += Integer.bitCount(zs[band]) / (float) COLUMNS;
				++bands;
			}
			if (bands == 0)
			{
				lo = hi;
				continue;
			}
			int o = count++ * RUN;
			out[o] = bx0 / CELL;
			out[o + 1] = bz0 / CELL;
			out[o + 2] = bx1 / CELL;
			out[o + 3] = bz1 / CELL;
			out[o + 4] = lo * BIN_HEIGHT;
			out[o + 5] = (hi + 1) * BIN_HEIGHT;
			out[o + 6] = (window >> lo & ((1 << hi - lo + 1) - 1)) != 0 ? 1 : 0;
			out[o + 7] = coverX / bands;
			out[o + 8] = coverZ / bands;
			lo = hi + 1;
		}
		return count;
	}

	/** The bin a height above the floor (world units) is in. */
	static int bin(float height)
	{
		return Math.max(0, Math.min(HEIGHT_BINS - 1, (int) Math.floor(height / BIN_HEIGHT)));
	}

	/**
	 * The cells of the zone's triangles.
	 *
	 * @param floors the zone's tiles' floor heights (world y, down), per storey, tile x * 8 + z: PLANES * 64 values
	 */
	static Zone rasterize(VoxelCapture cap, float[] floors)
	{
		// per cell and storey, the slot of its first record + 1; a slot for each object in the cell, the next one + 1 in nextSlot
		int[] slot = new int[SIDE * SIDE * PLANES];
		int slots = 0;
		int[] keys = new int[64], owners = new int[64], nextSlot = new int[64];
		short[] masks = new short[64];
		byte[] boxes = new byte[64 * RECORD];
		// the heights where a triangle can reach the bins anywhere in the zone, per storey
		float[] top = new float[PLANES], bottom = new float[PLANES];
		for (int p = 0; p < PLANES; ++p)
		{
			float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
			for (int t = 0; t < 64; ++t)
			{
				lo = Math.min(lo, floors[p * 64 + t]);
				hi = Math.max(hi, floors[p * 64 + t]);
			}
			top[p] = lo - HEIGHT_BINS * BIN_HEIGHT;
			bottom[p] = hi;
		}
		float[] poly = new float[8 * 3], tmp = new float[8 * 3];
		short[] pos = cap.pos;
		for (int t = 0; t < cap.count; ++t)
		{
			int o = t * 9;
			float minY = Math.min(pos[o + 1], Math.min(pos[o + 4], pos[o + 7]));
			float maxY = Math.max(pos[o + 1], Math.max(pos[o + 4], pos[o + 7]));
			boolean near = false;
			for (int p = 0; p < PLANES && !near; ++p)
			{
				near = maxY >= top[p] && minY <= bottom[p];
			}
			if (!near || flat(pos, o))
			{
				continue;
			}
			float minX = Math.min(pos[o], Math.min(pos[o + 3], pos[o + 6]));
			float maxX = Math.max(pos[o], Math.max(pos[o + 3], pos[o + 6]));
			float minZ = Math.min(pos[o + 2], Math.min(pos[o + 5], pos[o + 8]));
			float maxZ = Math.max(pos[o + 2], Math.max(pos[o + 5], pos[o + 8]));
			int cx0 = Math.max(-MARGIN, (int) Math.floor(minX / CELL)), cx1 = Math.min(ZONE_CELLS + MARGIN - 1, (int) Math.floor(maxX / CELL));
			int cz0 = Math.max(-MARGIN, (int) Math.floor(minZ / CELL)), cz1 = Math.min(ZONE_CELLS + MARGIN - 1, (int) Math.floor(maxZ / CELL));
			for (int cz = cz0; cz <= cz1; ++cz)
			{
				for (int cx = cx0; cx <= cx1; ++cx)
				{
					int n = clip(pos, o, cx * CELL, cz * CELL, poly, tmp);
					if (n == 0)
					{
						continue;
					}
					float y0 = Float.POSITIVE_INFINITY, y1 = Float.NEGATIVE_INFINITY;
					float px0 = Float.POSITIVE_INFINITY, px1 = Float.NEGATIVE_INFINITY;
					float pz0 = Float.POSITIVE_INFINITY, pz1 = Float.NEGATIVE_INFINITY;
					for (int i = 0; i < n; ++i)
					{
						y0 = Math.min(y0, poly[i * 3 + 1]);
						y1 = Math.max(y1, poly[i * 3 + 1]);
						px0 = Math.min(px0, poly[i * 3]);
						px1 = Math.max(px1, poly[i * 3]);
						pz0 = Math.min(pz0, poly[i * 3 + 2]);
						pz1 = Math.max(pz1, poly[i * 3 + 2]);
					}
					// what only touches the cell is the next one's: a point, or a line on its far edge
					if (px1 == px0 && pz1 == pz0 || px0 >= (cx + 1) * CELL || pz0 >= (cz + 1) * CELL)
					{
						continue;
					}
					// the floor of the tile the cell is in
					int tile = Math.max(0, Math.min(7, cx >> 2)) * 8 + Math.max(0, Math.min(7, cz >> 2));
					int bx0 = (int) Math.floor(px0 - cx * CELL), bx1 = (int) Math.ceil(px1 - cx * CELL);
					int bz0 = (int) Math.floor(pz0 - cz * CELL), bz1 = (int) Math.ceil(pz1 - cz * CELL);
					// the columns its silhouette reaches along each axis: a line (a wall seen edge on) is a column
					int xCols = columns(bx0, bx1), zCols = columns(bz0, bz1);
					for (int p = 0; p < PLANES; ++p)
					{
						// heights above the floor: y is down
						float lo = floors[p * 64 + tile] - y1, hi = floors[p * 64 + tile] - y0;
						if (hi < 0 || lo >= HEIGHT_BINS * BIN_HEIGHT)
						{
							continue;
						}
						int b0 = bin(lo), b1 = bin(hi);
						int cell = ((cz + MARGIN) * SIDE + cx + MARGIN) * PLANES + p;
						int s = slot[cell] - 1;
						while (s >= 0 && owners[s] != cap.owner[t])
						{
							s = nextSlot[s] - 1;
						}
						if (s < 0)
						{
							if (slots == masks.length)
							{
								keys = Arrays.copyOf(keys, slots * 2);
								owners = Arrays.copyOf(owners, slots * 2);
								nextSlot = Arrays.copyOf(nextSlot, slots * 2);
								masks = Arrays.copyOf(masks, slots * 2);
								boxes = Arrays.copyOf(boxes, slots * 2 * RECORD);
							}
							s = slots++;
							nextSlot[s] = slot[cell];
							slot[cell] = s + 1;
							keys[s] = cx + MARGIN | cz + MARGIN << 7 | p << 14;
							owners[s] = cap.owner[t];
							masks[s] = 0;
							for (int i = 0; i < RECORD; i += BOX)
							{
								boxes[s * RECORD + i] = (byte) CELL;
								boxes[s * RECORD + i + 1] = (byte) CELL;
								boxes[s * RECORD + i + 2] = 0;
								boxes[s * RECORD + i + 3] = 0;
								boxes[s * RECORD + i + 4] = 0;
								boxes[s * RECORD + i + 5] = 0;
							}
						}
						for (int bin = b0; bin <= b1; ++bin)
						{
							masks[s] |= (short) (1 << bin);
						}
						for (int band = b0 / BINS_PER_BAND; band <= b1 / BINS_PER_BAND; ++band)
						{
							int q = s * RECORD + band * BOX;
							boxes[q] = (byte) Math.min(boxes[q], bx0);
							boxes[q + 1] = (byte) Math.min(boxes[q + 1], bz0);
							boxes[q + 2] = (byte) Math.max(boxes[q + 2], bx1);
							boxes[q + 3] = (byte) Math.max(boxes[q + 3], bz1);
							boxes[q + 4] |= (byte) xCols;
							boxes[q + 5] |= (byte) zCols;
						}
					}
				}
			}
		}
		return new Zone(Arrays.copyOf(keys, slots), Arrays.copyOf(owners, slots), Arrays.copyOf(masks, slots), Arrays.copyOf(boxes, slots * RECORD));
	}

	/** The bits of the columns of the cell that the span from a to b (units into the cell) reaches; a span without width reaches one. */
	private static int columns(int a, int b)
	{
		int unit = (int) CELL / COLUMNS;
		int first = Math.min(COLUMNS - 1, a / unit), last = Math.min(COLUMNS - 1, Math.max(b - 1, a) / unit);
		return (1 << last + 1) - (1 << first);
	}

	/** Whether the triangle at o lies flat, or has no area. */
	private static boolean flat(short[] pos, int o)
	{
		float ux = pos[o + 3] - pos[o], uy = pos[o + 4] - pos[o + 1], uz = pos[o + 5] - pos[o + 2];
		float vx = pos[o + 6] - pos[o], vy = pos[o + 7] - pos[o + 1], vz = pos[o + 8] - pos[o + 2];
		float nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
		float len2 = nx * nx + ny * ny + nz * nz;
		return len2 == 0 || ny * ny >= FLAT * FLAT * len2;
	}

	/**
	 * The triangle at o in pos clipped to the square cell at (x, z), 32 units a side: its corners in out (x, y, z),
	 * the count; 0 when it doesn't reach the square. A triangle seen edge on (a wall) stays as a line.
	 */
	private static int clip(short[] pos, int o, float x, float z, float[] out, float[] tmp)
	{
		for (int i = 0; i < 3; ++i)
		{
			out[i * 3] = pos[o + i * 3];
			out[i * 3 + 1] = pos[o + i * 3 + 1];
			out[i * 3 + 2] = pos[o + i * 3 + 2];
		}
		int n = 3;
		for (int edge = 0; edge < 4 && n > 0; ++edge)
		{
			n = clipEdge(out, n, tmp, edge, edge == 0 ? x : edge == 1 ? x + CELL : edge == 2 ? z : z + CELL);
			System.arraycopy(tmp, 0, out, 0, n * 3);
		}
		return n;
	}

	// Sutherland-Hodgman against one side of the square: edge 0 keeps x >= v, 1 keeps x <= v, 2 keeps z >= v, 3 z <= v
	private static int clipEdge(float[] in, int n, float[] out, int edge, float v)
	{
		int m = 0;
		int axis = edge < 2 ? 0 : 2;
		float sign = (edge & 1) == 0 ? 1 : -1;
		for (int i = 0; i < n; ++i)
		{
			int j = (i + 1) % n;
			float di = (in[i * 3 + axis] - v) * sign, dj = (in[j * 3 + axis] - v) * sign;
			if (di >= 0)
			{
				out[m * 3] = in[i * 3];
				out[m * 3 + 1] = in[i * 3 + 1];
				out[m * 3 + 2] = in[i * 3 + 2];
				++m;
			}
			if (di >= 0 != dj >= 0)
			{
				float t = di / (di - dj);
				out[m * 3] = in[i * 3] + (in[j * 3] - in[i * 3]) * t;
				out[m * 3 + 1] = in[i * 3 + 1] + (in[j * 3 + 1] - in[i * 3 + 1]) * t;
				out[m * 3 + 2] = in[i * 3 + 2] + (in[j * 3 + 2] - in[i * 3 + 2]) * t;
				++m;
			}
		}
		return m;
	}

	/**
	 * The scene's cells from its zones. zones[i] lies at extended zone (zoneX[i], zoneZ[i]); cells is the scene's side
	 * in cells.
	 */
	static ModelBlockers assemble(Zone[] zones, int[] zoneX, int[] zoneZ, int count, int cells)
	{
		ModelBlockers m = new ModelBlockers(cells);
		for (int i = 0; i < count; ++i)
		{
			Zone z = zones[i];
			if (z == null)
			{
				continue;
			}
			int ox = zoneX[i] * ZONE_CELLS, oz = zoneZ[i] * ZONE_CELLS;
			for (int k = 0; k < z.keys.length; ++k)
			{
				int key = z.keys[k];
				int gx = ox + (key & 0x7f) - MARGIN, gz = oz + (key >> 7 & 0x7f) - MARGIN;
				int plane = key >> 14 & 3;
				// zones overlap where models overhang: the boxes and heights of both
				m.set(plane, gx, gz, z.owners[k], z.masks[k], z.boxes, k * RECORD);
			}
		}
		return m;
	}

	private void merge(int r, short mask, byte[] box, int offset)
	{
		masks[r] |= mask;
		for (int i = 0; i < RECORD; i += BOX)
		{
			int q = r * RECORD + i;
			if (box[offset + i + 2] < box[offset + i] || box[offset + i + 3] < box[offset + i + 1])
			{
				continue;
			}
			boxes[q] = (byte) Math.min(boxes[q], box[offset + i]);
			boxes[q + 1] = (byte) Math.min(boxes[q + 1], box[offset + i + 1]);
			boxes[q + 2] = (byte) Math.max(boxes[q + 2], box[offset + i + 2]);
			boxes[q + 3] = (byte) Math.max(boxes[q + 3], box[offset + i + 3]);
			boxes[q + 4] |= box[offset + i + 4];
			boxes[q + 5] |= box[offset + i + 5];
		}
	}

	/** Whether the cell's records, from the first r of this and q of o, are the same: each object's is. */
	private boolean same(int r, ModelBlockers o, int q)
	{
		int mine = 0, theirs = 0;
		for (int a = r; a >= 0; a = next[a] - 1)
		{
			++mine;
			int b = q;
			while (b >= 0 && o.owners[b] != owners[a])
			{
				b = o.next[b] - 1;
			}
			if (b < 0 || masks[a] != o.masks[b]
				|| !Arrays.equals(boxes, a * RECORD, (a + 1) * RECORD, o.boxes, b * RECORD, (b + 1) * RECORD))
			{
				return false;
			}
		}
		for (int b = q; b >= 0; b = o.next[b] - 1)
		{
			++theirs;
		}
		return mine == theirs;
	}

	/** Mark the cells (of every storey) that have a model. */
	void markCells(CellChanges changes)
	{
		for (int b = 0; b < index.length; ++b)
		{
			int[] mine = index[b];
			if (mine == null)
			{
				continue;
			}
			int bx = b % perSide, bz = b / perSide % perSide;
			for (int c = 0; c < BLOCK * BLOCK; ++c)
			{
				if (mine[c] != 0)
				{
					changes.mark(bx * BLOCK + c % BLOCK, bz * BLOCK + c / BLOCK, 0);
				}
			}
		}
	}

	/** Mark the cells (of every storey) that differ from another scene's. */
	void diff(ModelBlockers o, CellChanges changes)
	{
		if (o == null || o.cells != cells)
		{
			changes.markAll();
			return;
		}
		for (int b = 0; b < index.length; ++b)
		{
			int[] mine = index[b], theirs = o.index[b];
			if (mine == null && theirs == null)
			{
				continue;
			}
			int bx = b % perSide, bz = b / perSide % perSide;
			for (int c = 0; c < BLOCK * BLOCK; ++c)
			{
				int r = mine == null ? 0 : mine[c], q = theirs == null ? 0 : theirs[c];
				if (r == 0 && q == 0 || r != 0 && q != 0 && same(r - 1, o, q - 1))
				{
					continue;
				}
				changes.mark(bx * BLOCK + c % BLOCK, bz * BLOCK + c / BLOCK, 0);
			}
		}
	}
}
