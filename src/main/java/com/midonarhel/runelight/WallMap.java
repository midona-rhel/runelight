package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.Collection;

/**
 * The walls of a scene per plane: for every tile, which of its four edges hold a wall and whether a diagonal wall
 * crosses it. Walls are the edges the collision map says block line of sight (closed doors and windows included,
 * fences and railings not; an open door is no wall), plus the diagonal walls, which the collision map only shows
 * as a fully blocked tile, from their objects. Rooms split along these walls (BuildingDetector.split) and the
 * static lights' visibility is cast against them (LightOcclusion).
 *
 * GL-free and immutable once built, so the light occlusion worker can read it. Arrays are [plane][x][y] in
 * extended scene tiles, like RoomMap; x is east, y north.
 */
final class WallMap
{
	static final int PLANES = 4;

	// per tile bits
	static final int NORTH = 1, EAST = 2, SOUTH = 4, WEST = 8;
	// diagonal from the south-west corner to the north-east one ("/"), and from the north-west to the south-east ("\")
	static final int DIAG_SW_NE = 16, DIAG_NW_SE = 32;

	// CollisionDataFlag: line of sight blocked toward a side, and the whole tile
	static final int LOS_NORTH = 0x400, LOS_EAST = 0x1000, LOS_SOUTH = 0x4000, LOS_WEST = 0x10000;
	static final int LOS_FULL = 0x20000;

	// object config: type in the low 5 bits, rotation in bits 6-7; type 9 is a diagonal wall
	static final int TYPE_DIAGONAL_WALL = 9;

	final int size;
	// extended tiles before local tile 0
	final int offset;
	// scenes and rebuilds of the same scene: a different scene means everything cast before is stale
	final int scene;
	private final byte[] bits;

	/** A diagonal wall object on a tile: kind is DIAG_SW_NE or DIAG_NW_SE. Extended tile coordinates. */
	static final class Diagonal
	{
		final int plane, x, y, kind;

		Diagonal(int plane, int x, int y, int kind)
		{
			this.plane = plane;
			this.x = x;
			this.y = y;
			this.kind = kind;
		}

		@Override
		public boolean equals(Object o)
		{
			if (!(o instanceof Diagonal))
			{
				return false;
			}
			Diagonal d = (Diagonal) o;
			return d.plane == plane && d.x == x && d.y == y && d.kind == kind;
		}

		@Override
		public int hashCode()
		{
			return ((plane * 31 + x) * 31 + y) * 31 + kind;
		}
	}

	private WallMap(int size, int offset, int scene)
	{
		this.size = size;
		this.offset = offset;
		this.scene = scene;
		bits = new byte[PLANES * size * size];
	}

	/** A map without walls. */
	static WallMap empty(int size, int offset, int scene)
	{
		return new WallMap(size, offset, scene);
	}

	/**
	 * @param flags collision flags per plane, [plane][x][y] in local scene tiles; null entries have no walls
	 * @param diagonals diagonal wall objects; only those on tiles the collision map blocks for sight count, unless
	 * there are no flags for the plane
	 * @param size extended tiles per side
	 * @param offset extended tiles before local tile 0
	 */
	static WallMap build(int[][][] flags, Collection<Diagonal> diagonals, int size, int offset, int scene)
	{
		WallMap m = new WallMap(size, offset, scene);
		for (int plane = 0; plane < PLANES; ++plane)
		{
			int[][] f = flags != null && plane < flags.length ? flags[plane] : null;
			if (f == null)
			{
				continue;
			}
			for (int lx = 0; lx < f.length; ++lx)
			{
				for (int ly = 0; ly < f[lx].length; ++ly)
				{
					int v = f[lx][ly];
					int b = ((v & LOS_NORTH) != 0 ? NORTH : 0) | ((v & LOS_EAST) != 0 ? EAST : 0)
						| ((v & LOS_SOUTH) != 0 ? SOUTH : 0) | ((v & LOS_WEST) != 0 ? WEST : 0);
					int x = lx + offset, y = ly + offset;
					if (b != 0 && x < size && y < size)
					{
						m.bits[m.index(plane, x, y)] |= (byte) b;
					}
				}
			}
		}
		if (diagonals != null)
		{
			for (Diagonal d : diagonals)
			{
				if (d.plane < 0 || d.plane >= PLANES || d.x < 0 || d.y < 0 || d.x >= size || d.y >= size)
				{
					continue;
				}
				int[][] f = flags != null && d.plane < flags.length ? flags[d.plane] : null;
				int lx = d.x - offset, ly = d.y - offset;
				if (f != null && (lx < 0 || ly < 0 || lx >= f.length || ly >= f[lx].length || (f[lx][ly] & LOS_FULL) == 0))
				{
					// see-through (a diagonal fence) or outside the collision map
					continue;
				}
				m.bits[m.index(d.plane, d.x, d.y)] |= (byte) d.kind;
			}
		}
		return m;
	}

	int index(int plane, int x, int y)
	{
		return (plane * size + x) * size + y;
	}

	/** The tile's bits, 0 outside the map. */
	int get(int plane, int x, int y)
	{
		if (plane < 0 || plane >= PLANES || x < 0 || y < 0 || x >= size || y >= size)
		{
			return 0;
		}
		return bits[index(plane, x, y)] & 0xFF;
	}

	/** Whether a wall stands on the edge between tile (x, y) and its neighbor toward side (NORTH, EAST, ...). */
	boolean edge(int plane, int x, int y, int side)
	{
		switch (side)
		{
			case NORTH:
				return ((get(plane, x, y) & NORTH) | (get(plane, x, y + 1) & SOUTH)) != 0;
			case SOUTH:
				return ((get(plane, x, y) & SOUTH) | (get(plane, x, y - 1) & NORTH)) != 0;
			case EAST:
				return ((get(plane, x, y) & EAST) | (get(plane, x + 1, y) & WEST)) != 0;
			default:
				return ((get(plane, x, y) & WEST) | (get(plane, x - 1, y) & EAST)) != 0;
		}
	}

	/** Whether any wall lies in the rectangle of extended tiles (inclusive). */
	boolean anyIn(int plane, int x0, int y0, int x1, int y1)
	{
		if (plane < 0 || plane >= PLANES)
		{
			return false;
		}
		x0 = Math.max(x0 - 1, 0);
		y0 = Math.max(y0 - 1, 0);
		x1 = Math.min(x1 + 1, size - 1);
		y1 = Math.min(y1 + 1, size - 1);
		for (int x = x0; x <= x1; ++x)
		{
			int i = index(plane, x, y0);
			for (int y = y0; y <= y1; ++y, ++i)
			{
				if (bits[i] != 0)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Same walls (and size) as another map. */
	boolean sameWalls(WallMap o)
	{
		return o != null && o.size == size && o.offset == offset && Arrays.equals(o.bits, bits);
	}

	/**
	 * The rectangle of tiles whose walls differ from another map of the same size, over all planes, as
	 * {x0, y0, x1, y1}; null when they are the same.
	 */
	int[] diff(WallMap o)
	{
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = -1, y1 = -1;
		for (int plane = 0; plane < PLANES; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				int i = index(plane, x, 0);
				for (int y = 0; y < size; ++y, ++i)
				{
					if (bits[i] != o.bits[i])
					{
						x0 = Math.min(x0, x);
						y0 = Math.min(y0, y);
						x1 = Math.max(x1, x);
						y1 = Math.max(y1, y);
					}
				}
			}
		}
		return x1 < 0 ? null : new int[]{x0, y0, x1, y1};
	}

	/**
	 * Which diagonal a diagonal wall's model runs along, from its vertices (model space, x east and z north, before
	 * the object's model orientation, which is applied here the way SceneUploader does): the sign of the x-z
	 * covariance. DIAG_SW_NE when x and z grow together; the rotation's parity when the model says nothing.
	 *
	 * @param orientation model orientation, 2048 per turn
	 * @param rotation the object's rotation from its config, 0-3
	 */
	static int diagonalOf(float[] vx, float[] vz, int count, int orientation, int rotation)
	{
		if (vx != null && vz != null && count > 1)
		{
			double s = orientation * Math.PI * 2 / 2048;
			double sin = Math.sin(s), cos = Math.cos(s);
			double mx = 0, mz = 0;
			int n = Math.min(count, Math.min(vx.length, vz.length));
			double[] rx = new double[n], rz = new double[n];
			for (int i = 0; i < n; ++i)
			{
				rx[i] = vz[i] * sin + vx[i] * cos;
				rz[i] = vz[i] * cos - vx[i] * sin;
				mx += rx[i];
				mz += rz[i];
			}
			mx /= n;
			mz /= n;
			double cov = 0, vxx = 0, vzz = 0;
			for (int i = 0; i < n; ++i)
			{
				cov += (rx[i] - mx) * (rz[i] - mz);
				vxx += (rx[i] - mx) * (rx[i] - mx);
				vzz += (rz[i] - mz) * (rz[i] - mz);
			}
			// clearly along a diagonal: the correlation is well away from 0
			if (vxx > 0 && vzz > 0 && Math.abs(cov) > 0.3 * Math.sqrt(vxx * vzz))
			{
				return cov > 0 ? DIAG_SW_NE : DIAG_NW_SE;
			}
		}
		return (rotation & 1) == 0 ? DIAG_SW_NE : DIAG_NW_SE;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Line of sight

	/**
	 * Whether a wall stands between two points on a plane, local world units (128 per tile, x east, z north).
	 * Crossings within {@code ignore} world units of a are left out: a light hung right on a wall lights both of its
	 * faces' sides rather than neither. Ends exactly on a wall don't count as crossing it.
	 */
	boolean blocked(int plane, float ax, float az, float bx, float bz, float ignore)
	{
		if (plane < 0 || plane >= PLANES)
		{
			return false;
		}
		double x0 = ax / 128.0 + offset, z0 = az / 128.0 + offset;
		double x1 = bx / 128.0 + offset, z1 = bz / 128.0 + offset;
		double dx = x1 - x0, dz = z1 - z0;
		double len = Math.sqrt(dx * dx + dz * dz) * 128;
		double tIgnore = len > 0 ? ignore / len : 1;
		int tx = (int) Math.floor(x0), tz = (int) Math.floor(z0);
		int ex = (int) Math.floor(x1), ez = (int) Math.floor(z1);
		int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
		int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
		double tDeltaX = sx != 0 ? 1 / Math.abs(dx) : Double.POSITIVE_INFINITY;
		double tDeltaZ = sz != 0 ? 1 / Math.abs(dz) : Double.POSITIVE_INFINITY;
		double tMaxX = sx > 0 ? (tx + 1 - x0) * tDeltaX : sx < 0 ? (x0 - tx) * tDeltaX : Double.POSITIVE_INFINITY;
		double tMaxZ = sz > 0 ? (tz + 1 - z0) * tDeltaZ : sz < 0 ? (z0 - tz) * tDeltaZ : Double.POSITIVE_INFINITY;
		double tEnter = 0;
		// a straight walk can't take more steps than this
		int steps = Math.abs(ex - tx) + Math.abs(ez - tz) + 2;
		while (true)
		{
			double tExit = Math.min(Math.min(tMaxX, tMaxZ), 1);
			if (crossesDiagonal(get(plane, tx, tz), tx, tz, x0, z0, dx, dz, Math.max(tEnter, tIgnore), tExit))
			{
				return true;
			}
			if ((tx == ex && tz == ez) || --steps < 0 || tExit >= 1)
			{
				return false;
			}
			boolean ignored = tExit <= tIgnore;
			if (Math.abs(tMaxX - tMaxZ) < 1e-9)
			{
				// through a corner: blocked when both ways around it are
				int xSide = sx > 0 ? EAST : WEST, zSide = sz > 0 ? NORTH : SOUTH;
				boolean viaX = edge(plane, tx, tz, xSide) || edge(plane, tx + sx, tz, zSide);
				boolean viaZ = edge(plane, tx, tz, zSide) || edge(plane, tx, tz + sz, xSide);
				if (!ignored && viaX && viaZ)
				{
					return true;
				}
				tx += sx;
				tz += sz;
				tEnter = tMaxX;
				tMaxX += tDeltaX;
				tMaxZ += tDeltaZ;
			}
			else if (tMaxX < tMaxZ)
			{
				if (!ignored && edge(plane, tx, tz, sx > 0 ? EAST : WEST))
				{
					return true;
				}
				tx += sx;
				tEnter = tMaxX;
				tMaxX += tDeltaX;
			}
			else
			{
				if (!ignored && edge(plane, tx, tz, sz > 0 ? NORTH : SOUTH))
				{
					return true;
				}
				tz += sz;
				tEnter = tMaxZ;
				tMaxZ += tDeltaZ;
			}
		}
	}

	/** Whether the segment, between parameters t0 and t1, strictly crosses a diagonal of tile (tx, tz). */
	private static boolean crossesDiagonal(int b, int tx, int tz, double x0, double z0, double dx, double dz,
		double t0, double t1)
	{
		if ((b & (DIAG_SW_NE | DIAG_NW_SE)) == 0 || t1 <= t0)
		{
			return false;
		}
		double u0 = x0 + dx * t0 - tx, v0 = z0 + dz * t0 - tz;
		double u1 = x0 + dx * t1 - tx, v1 = z0 + dz * t1 - tz;
		if ((b & DIAG_SW_NE) != 0 && (v0 - u0) * (v1 - u1) < 0)
		{
			return true;
		}
		return (b & DIAG_NW_SE) != 0 && (u0 + v0 - 1) * (u1 + v1 - 1) < 0;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Tile halves, for splitting rooms: a tile crossed by a diagonal wall is two halves

	/** Which half of a tile with these bits an edge (side) belongs to: 0 or 1; always 0 without a diagonal. */
	static int half(int b, int side)
	{
		if ((b & DIAG_SW_NE) != 0)
		{
			// the north-west half has the north and west edges
			return side == NORTH || side == WEST ? 0 : 1;
		}
		if ((b & DIAG_NW_SE) != 0)
		{
			// the north-east half has the north and east edges
			return side == NORTH || side == EAST ? 0 : 1;
		}
		return 0;
	}

	static boolean split(int b)
	{
		return (b & (DIAG_SW_NE | DIAG_NW_SE)) != 0;
	}
}
