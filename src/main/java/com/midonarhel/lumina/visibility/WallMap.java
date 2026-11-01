package com.midonarhel.lumina.visibility;

import com.midonarhel.lumina.*;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;

/**
 * The walls of a scene per plane: for every tile, which of its four edges hold a wall and whether a diagonal wall
 * crosses it. Walls are the edges the collision map says block line of sight (closed doors and windows included,
 * fences and railings not; an open door is no wall) where a wall the game draws stands (an invisible wall, a wall
 * object with no model such as the ones boxing in a market stall, is none), plus the diagonal walls, which the
 * collision map only shows as a fully blocked tile, from their objects. Edges that stop movement but not sight (bars, fences, railings,
 * low walls, gates) are kept apart (seeThrough): they let light through. Rooms split along these walls
 * (BuildingDetector.split), and they are the one thing that stops a lamp (LampWalls, light/lamp.glsl; on the CPU
 * {@link #blocked}).
 *
 * GL-free and immutable once built, so workers can read it. Arrays are [plane][x][y] in extended scene tiles, like
 * RoomMap; x is east, y north.
 */
public final class WallMap
{
	public static final int PLANES = 4;

	// per tile bits
	public static final int NORTH = 1, EAST = 2, SOUTH = 4, WEST = 8;
	// diagonal from the south-west corner to the north-east one ("/"), and from the north-west to the south-east ("\")
	public static final int DIAG_SW_NE = 16, DIAG_NW_SE = 32;

	// CollisionDataFlag: line of sight blocked toward a side, and the whole tile
	public static final int LOS_NORTH = 0x400, LOS_EAST = 0x1000, LOS_SOUTH = 0x4000, LOS_WEST = 0x10000;
	public static final int LOS_FULL = 0x20000;
	// CollisionDataFlag: movement blocked toward a side
	public static final int MOVE_NORTH = 0x2, MOVE_EAST = 0x8, MOVE_SOUTH = 0x20, MOVE_WEST = 0x80;

	// object config: type in the low 5 bits, rotation in bits 6-7; type 9 is a diagonal wall
	static final int TYPE_DIAGONAL_WALL = 9;

	public final int size;
	// extended tiles before local tile 0
	public final int offset;
	// scenes and rebuilds of the same scene: a different scene means everything cast before is stale
	public final int scene;
	private final byte[] bits;
	// tiles the collision map blocks sight across entirely (a solid object); a diagonal wall tile is one too, but
	// has its bit
	private final boolean[] full;
	// per tile, the edges (NORTH...) that stop movement and not sight
	private final byte[] see;

	/** A diagonal wall object on a tile: kind is DIAG_SW_NE or DIAG_NW_SE. Extended tile coordinates. */
	public static final class Diagonal
	{
		final int plane, x, y, kind;

		public Diagonal(int plane, int x, int y, int kind)
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
		full = new boolean[PLANES * size * size];
		see = new byte[PLANES * size * size];
	}

	/** The map as the scene dump has it: its size, offset and scene, the wall bits and the solid tiles. */
	void write(DataOutputStream out) throws IOException
	{
		out.writeInt(size);
		out.writeInt(offset);
		out.writeInt(scene);
		out.write(bits);
		for (boolean f : full)
		{
			out.writeBoolean(f);
		}
		out.write(see);
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
	public static WallMap build(int[][][] flags, Collection<Diagonal> diagonals, int size, int offset, int scene)
	{
		return build(flags, diagonals, null, size, offset, scene);
	}

	/**
	 * @param drawn per extended tile, (plane * size + x) * size + y: whether a wall object with a model stands there; an
	 * edge the collision map blocks is a wall only where the tile on either side of it has one. null: every edge counts
	 */
	public static WallMap build(int[][][] flags, Collection<Diagonal> diagonals, boolean[] drawn, int size, int offset,
		int scene)
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
					// The client's collision-map border is initialized to 0xFFFFFF, not actual wall flags.
					if ((v & 0xFFFFFF) == 0xFFFFFF)
					{
						continue;
					}
					int b = ((v & LOS_NORTH) != 0 ? NORTH : 0) | ((v & LOS_EAST) != 0 ? EAST : 0)
						| ((v & LOS_SOUTH) != 0 ? SOUTH : 0) | ((v & LOS_WEST) != 0 ? WEST : 0);
					int x = lx + offset, y = ly + offset;
					if (b != 0 && x < size && y < size)
					{
						m.bits[m.index(plane, x, y)] |= (byte) b;
					}
					int s = ((v & MOVE_NORTH) != 0 ? NORTH : 0) | ((v & MOVE_EAST) != 0 ? EAST : 0)
						| ((v & MOVE_SOUTH) != 0 ? SOUTH : 0) | ((v & MOVE_WEST) != 0 ? WEST : 0);
					if ((s & ~b) != 0 && x < size && y < size)
					{
						m.see[m.index(plane, x, y)] |= (byte) (s & ~b);
					}
					if ((v & LOS_FULL) != 0 && x < size && y < size)
					{
						m.full[m.index(plane, x, y)] = true;
					}
				}
			}
		}
		if (drawn != null && drawn.length == PLANES * size * size)
		{
			m.keepDrawn(drawn);
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

	/** Drop the edges with no drawn wall on either side: a wall nobody sees stops no light. */
	private void keepDrawn(boolean[] drawn)
	{
		byte[] kept = bits.clone();
		for (int plane = 0; plane < PLANES; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				for (int y = 0; y < size; ++y)
				{
					int i = index(plane, x, y);
					int b = bits[i];
					if (b == 0 || drawn[i])
					{
						continue;
					}
					if ((b & NORTH) != 0 && !(y + 1 < size && drawn[index(plane, x, y + 1)])) b &= ~NORTH;
					if ((b & SOUTH) != 0 && !(y > 0 && drawn[index(plane, x, y - 1)])) b &= ~SOUTH;
					if ((b & EAST) != 0 && !(x + 1 < size && drawn[index(plane, x + 1, y)])) b &= ~EAST;
					if ((b & WEST) != 0 && !(x > 0 && drawn[index(plane, x - 1, y)])) b &= ~WEST;
					kept[i] = (byte) b;
				}
			}
		}
		System.arraycopy(kept, 0, bits, 0, bits.length);
	}

	int index(int plane, int x, int y)
	{
		return (plane * size + x) * size + y;
	}

	/** The tile's bits, 0 outside the map. */
	public int get(int plane, int x, int y)
	{
		if (plane < 0 || plane >= PLANES || x < 0 || y < 0 || x >= size || y >= size)
		{
			return 0;
		}
		return bits[index(plane, x, y)] & 0xFF;
	}

	/** Whether a solid object fills the tile: it blocks sight across all of it, and no diagonal wall is why. */
	public boolean solid(int plane, int x, int y)
	{
		if (plane < 0 || plane >= PLANES || x < 0 || y < 0 || x >= size || y >= size)
		{
			return false;
		}
		int i = index(plane, x, y);
		return full[i] && (bits[i] & (DIAG_SW_NE | DIAG_NW_SE)) == 0;
	}

	/** Whether a wall stands on the edge between tile (x, y) and its neighbor toward side (NORTH, EAST, ...). */
	public boolean edge(int plane, int x, int y, int side)
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

	/**
	 * Whether the edge between tile (x, y) and its neighbor toward side holds a wall that stops movement and not sight
	 * (bars, a fence, a railing, a low wall, a closed gate), and no wall that stops sight.
	 */
	public boolean seeThrough(int plane, int x, int y, int side)
	{
		if (plane < 0 || plane >= PLANES || edge(plane, x, y, side))
		{
			return false;
		}
		switch (side)
		{
			case NORTH:
				return ((seeBits(plane, x, y) & NORTH) | (seeBits(plane, x, y + 1) & SOUTH)) != 0;
			case SOUTH:
				return ((seeBits(plane, x, y) & SOUTH) | (seeBits(plane, x, y - 1) & NORTH)) != 0;
			case EAST:
				return ((seeBits(plane, x, y) & EAST) | (seeBits(plane, x + 1, y) & WEST)) != 0;
			default:
				return ((seeBits(plane, x, y) & WEST) | (seeBits(plane, x - 1, y) & EAST)) != 0;
		}
	}

	/** Whether any edge of the tile is seeThrough. */
	public boolean anySeeThrough(int plane, int x, int y)
	{
		return seeThrough(plane, x, y, NORTH) || seeThrough(plane, x, y, EAST) || seeThrough(plane, x, y, SOUTH)
			|| seeThrough(plane, x, y, WEST);
	}

	private int seeBits(int plane, int x, int y)
	{
		if (x < 0 || y < 0 || x >= size || y >= size)
		{
			return 0;
		}
		return see[index(plane, x, y)];
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

	/**
	 * Mark the light map's cells (cellsPerTile to a tile's side) of every tile whose walls or solid objects differ from
	 * another map of the same size, and the cell around them: a wall stands on its tile's edge (LightMap: the lights
	 * near them are baked again).
	 */
	public void diff(WallMap o, CellChanges changes, int cellsPerTile)
	{
		int n = cellsPerTile;
		for (int plane = 0; plane < PLANES; ++plane)
		{
			for (int x = 0; x < size; ++x)
			{
				int i = index(plane, x, 0);
				for (int y = 0; y < size; ++y, ++i)
				{
					if (bits[i] != o.bits[i] || full[i] != o.full[i] || see[i] != o.see[i])
					{
						for (int dz = 0; dz < n; ++dz)
						{
							for (int dx = 0; dx < n; ++dx)
							{
								changes.mark(x * n + dx, y * n + dz, 1);
							}
						}
					}
				}
			}
		}
	}

	/** Same walls (and size) as another map. */
	boolean sameWalls(WallMap o)
	{
		return o != null && o.size == size && o.offset == offset && Arrays.equals(o.bits, bits) && Arrays.equals(o.full, full)
			&& Arrays.equals(o.see, see);
	}

	/**
	 * The rectangle of tiles whose walls or solid objects differ from another map of the same size, over all planes,
	 * as {x0, y0, x1, y1}; null when they are the same.
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
					if (bits[i] != o.bits[i] || full[i] != o.full[i] || see[i] != o.see[i])
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
	 * Whether a wall stands between two points on a plane, local world units (128 per tile, x east, z north). Ends
	 * exactly on a wall don't count as crossing it.
	 */
	public boolean blocked(int plane, float ax, float az, float bx, float bz)
	{
		if (plane < 0 || plane >= PLANES)
		{
			return false;
		}
		double x0 = ax / 128.0 + offset, z0 = az / 128.0 + offset;
		double x1 = bx / 128.0 + offset, z1 = bz / 128.0 + offset;
		double dx = x1 - x0, dz = z1 - z0;
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
			if (crossesDiagonal(get(plane, tx, tz), tx, tz, x0, z0, dx, dz, tEnter, tExit))
			{
				return true;
			}
			if ((tx == ex && tz == ez) || --steps < 0 || tExit >= 1)
			{
				return false;
			}
			if (Math.abs(tMaxX - tMaxZ) < 1e-9)
			{
				// through a corner: blocked when walls leave it on both sides of the line
				if (cornerBlocks(plane, tx + (sx > 0 ? 1 : 0), tz + (sz > 0 ? 1 : 0), sx, sz))
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
				if (edge(plane, tx, tz, sx > 0 ? EAST : WEST))
				{
					return true;
				}
				tx += sx;
				tEnter = tMaxX;
				tMaxX += tDeltaX;
			}
			else
			{
				if (edge(plane, tx, tz, sz > 0 ? NORTH : SOUTH))
				{
					return true;
				}
				tz += sz;
				tEnter = tMaxZ;
				tMaxZ += tDeltaZ;
			}
		}
	}

	/**
	 * Whether a line through tile corner (cx, cz) going (sx, sz) (a diagonal direction) squeezes between walls that
	 * meet there: some wall leaves the corner on each side of it. The walls leaving a corner are the four edges along
	 * it and the diagonals of the four tiles round it that end there; one alone is a wall's end, which the line passes.
	 * Diagonals count as edges do: two diagonal walls meeting end to end (a zigzag front's joint) let nothing through,
	 * where only the edges were looked at (light came through every joint of Ardougne's zigzag fronts, 2026-11-01).
	 */
	private boolean cornerBlocks(int plane, int cx, int cz, int sx, int sz)
	{
		// the walls leaving the corner by compass direction: 0 east, 1 north-east ... 7 south-east
		int sw = get(plane, cx - 1, cz - 1), se = get(plane, cx, cz - 1), nw = get(plane, cx - 1, cz), ne = get(plane, cx, cz);
		boolean[] ray = {
			(se & NORTH) != 0 || (ne & SOUTH) != 0,
			(ne & DIAG_SW_NE) != 0,
			(nw & EAST) != 0 || (ne & WEST) != 0,
			(nw & DIAG_NW_SE) != 0,
			(sw & NORTH) != 0 || (nw & SOUTH) != 0,
			(sw & DIAG_SW_NE) != 0,
			(sw & EAST) != 0 || (se & WEST) != 0,
			(se & DIAG_NW_SE) != 0,
		};
		int t = sx > 0 ? (sz > 0 ? 1 : 7) : (sz > 0 ? 3 : 5);
		boolean left = false, right = false;
		for (int k = 1; k <= 3; ++k)
		{
			left |= ray[(t + k) & 7];
			right |= ray[(t - k) & 7];
		}
		return left && right;
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
	public static int half(int b, int side)
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

	/** Which half of a tile with these bits a point in it is in, fx east and fy north within the tile (0..1). */
	static int halfAt(int b, float fx, float fy)
	{
		if ((b & DIAG_SW_NE) != 0)
		{
			return fy > fx ? 0 : 1;
		}
		if ((b & DIAG_NW_SE) != 0)
		{
			return fx + fy > 1 ? 0 : 1;
		}
		return 0;
	}

	static boolean split(int b)
	{
		return (b & (DIAG_SW_NE | DIAG_NW_SE)) != 0;
	}
}
