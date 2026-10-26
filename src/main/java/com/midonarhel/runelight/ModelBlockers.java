package com.midonarhel.runelight;

import java.util.Arrays;

/**
 * The static models that stand in the way of a light, as cells of the light map (LightMap): a quarter tile each, per
 * storey. They come from the same zone triangles the skylight voxelizes (VoxelCapture), rasterized conservatively: a
 * cell is blocked on a storey when a triangle crosses the cell somewhere between BAND_LOW and BAND_HIGH above that
 * storey's floor.
 *
 * Why that band: lights.json puts most lights at 20 to 50 units above the ground (fires, braziers) and torches and
 * lanterns from 100 to 250. A tabletop, a counter, a fence, a crate or a barrel reaches 70 at most and must not stop
 * light, which passes over them; a pillar, a bookcase, an open furnace door, a statue, a tree trunk or a bush is in
 * the way of a torch at body height. From 80 to 128 units (about two thirds of a tile to a whole one) a model is
 * either tall enough to shade the room or isn't, and the band stays well under the ceilings and canopies (240 and
 * more), so a lamp isn't shut in by its own roof. A triangle only has to touch the band, whatever its height
 * otherwise: a shelf board at 100 units blocks, as does a thin door leaf, because the clip keeps it however narrow.
 *
 * GL-free. Zones are rasterized once and keep their cells (Zone.modelBlockers); the scene's cells are assembled from
 * them.
 */
final class ModelBlockers
{
	// heights above a storey's floor (world units) where a model blocks light
	static final float BAND_LOW = 80, BAND_HIGH = 128;
	static final int PLANES = WallMap.PLANES;
	// cells per zone side (a zone is 8 tiles), and how far a model may overhang its zone
	static final int ZONE_CELLS = 32;
	private static final int MARGIN = 16;
	private static final int SIDE = ZONE_CELLS + 2 * MARGIN;
	private static final float CELL = 32;

	/** One zone's blocked cells: (cx + MARGIN) | (cz + MARGIN) << 7 | storeys' bits << 14, zone-local. */
	static final class Zone
	{
		final int[] keys;

		Zone(int[] keys)
		{
			this.keys = keys;
		}
	}

	/** Per cell, a bit per storey that the cell is blocked on; cells * cells, row (z) by row. */
	final byte[] mask;
	final int cells;

	ModelBlockers(int cells)
	{
		this.cells = cells;
		mask = new byte[cells * cells];
	}

	boolean blocked(int plane, int gx, int gz)
	{
		return gx >= 0 && gz >= 0 && gx < cells && gz < cells && (mask[gz * cells + gx] >> plane & 1) != 0;
	}

	/**
	 * The cells of the zone's triangles that block light.
	 *
	 * @param floors the zone's tiles' floor heights (world y, down), per storey, tile x * 8 + z: PLANES * 64 values
	 */
	static Zone rasterize(VoxelCapture cap, float[] floors)
	{
		byte[] acc = new byte[SIDE * SIDE];
		// the heights where a triangle can touch the band anywhere in the zone, per storey
		float[] top = new float[PLANES], bottom = new float[PLANES];
		for (int p = 0; p < PLANES; ++p)
		{
			float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
			for (int t = 0; t < 64; ++t)
			{
				lo = Math.min(lo, floors[p * 64 + t]);
				hi = Math.max(hi, floors[p * 64 + t]);
			}
			top[p] = lo - BAND_HIGH;
			bottom[p] = hi - BAND_LOW;
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
			if (!near)
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
					int bits = 0;
					for (int p = 0; p < PLANES; ++p)
					{
						float floor = floors[p * 64 + tile];
						if (y1 >= floor - BAND_HIGH && y0 <= floor - BAND_LOW)
						{
							bits |= 1 << p;
						}
					}
					acc[(cz + MARGIN) * SIDE + cx + MARGIN] |= (byte) bits;
				}
			}
		}
		int count = 0;
		for (byte b : acc)
		{
			if (b != 0)
			{
				++count;
			}
		}
		int[] keys = new int[count];
		int k = 0;
		for (int i = 0; i < acc.length; ++i)
		{
			if (acc[i] != 0)
			{
				keys[k++] = i % SIDE | i / SIDE << 7 | (acc[i] & 15) << 14;
			}
		}
		return new Zone(keys);
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
			for (int key : z.keys)
			{
				int gx = ox + (key & 0x7f) - MARGIN, gz = oz + (key >> 7 & 0x7f) - MARGIN;
				if (gx >= 0 && gz >= 0 && gx < cells && gz < cells)
				{
					m.mask[gz * cells + gx] |= (byte) (key >> 14);
				}
			}
		}
		return m;
	}

	/** The rectangle of cells, {x0, z0, x1, z1}, that differ from another scene's, null when none do. */
	int[] diff(ModelBlockers o)
	{
		if (o == null || o.cells != cells)
		{
			return new int[]{0, 0, cells - 1, cells - 1};
		}
		int x0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = -1, z1 = -1;
		for (int z = 0; z < cells; ++z)
		{
			int row = z * cells;
			if (Arrays.equals(mask, row, row + cells, o.mask, row, row + cells))
			{
				continue;
			}
			for (int x = 0; x < cells; ++x)
			{
				if (mask[row + x] != o.mask[row + x])
				{
					x0 = Math.min(x0, x);
					x1 = Math.max(x1, x);
					z0 = Math.min(z0, z);
					z1 = Math.max(z1, z);
				}
			}
		}
		return x1 < 0 ? null : new int[]{x0, z0, x1, z1};
	}
}
