package com.midonarhel.runelight;

/**
 * Dense voxel grid over the base scene for the skylight's rays (SkyGrid): 208 x 208 half-tile columns, up to
 * MAX_LAYERS half-tile layers from the highest geometry down, and HEADROOM empty ones above it. A cell is 0 when
 * empty, else 1, with bit 31 set for foliage.
 *
 * Besides the cells, each column keeps the world y of the highest surface found in it (top), which is how high a
 * roof stands, finer than a voxel.
 *
 * Voxel space: x = world x / VOXEL, y = world y / VOXEL - layer0, z = world z / VOXEL; world is the client's local
 * coordinates of the scene (base tile 0 at 0), y down.
 */
final class VoxelGrid
{
	static final int VOXEL = 64;
	// the scene is this many tiles square, SIZE voxels
	static final int SCENE_TILES = 104;
	static final int SIZE = 208;
	static final int MAX_LAYERS = 96;
	// empty layers over the highest geometry, so the rays that start above roofs and canopies start inside the grid
	static final int HEADROOM = 3;
	// bit 31: mostly swaying foliage, which rays pass with this chance
	static final int FOLIAGE = 1 << 31;
	static final float FOLIAGE_PASS = 0.4f;
	// square world units of surface touching a voxel below which it doesn't count as occupied: a quarter of a face
	static final float THIN_AREA = VOXEL * VOXEL / 4f;

	final int nx, ny, nz;
	final int layer0;
	final int[] cells;
	// per column (z * nx + x): the world y (down) of the highest surface in an occupied voxel, +infinity where none
	final float[] top;

	VoxelGrid(int nx, int ny, int nz, int layer0)
	{
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		this.layer0 = layer0;
		cells = new int[nx * ny * nz];
		top = new float[nx * nz];
		java.util.Arrays.fill(top, Float.POSITIVE_INFINITY);
	}

	int index(int x, int y, int z)
	{
		return (y * nz + z) * nx + x;
	}

	boolean occupied(int x, int y, int z)
	{
		return x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz && cells[index(x, y, z)] != 0;
	}

	/**
	 * Assemble the grid from voxelized zones, with the water's surface (VoxelWater, may be null) as one more layer of
	 * voxels. zones[i] lies at extended zone (zoneX[i], zoneZ[i]); the base scene starts at extended zone baseZone.
	 */
	static VoxelGrid assemble(Voxelizer.Zone[] zones, int[] zoneX, int[] zoneZ, int baseZone, VoxelWater water)
	{
		int perZone = 8 * 128 / VOXEL;
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int i = 0; i < zones.length; ++i)
		{
			Voxelizer.Zone z = zones[i];
			if (z == null)
			{
				continue;
			}
			int ox = (zoneX[i] - baseZone) * perZone, oz = (zoneZ[i] - baseZone) * perZone;
			for (int k = 0; k < z.count; ++k)
			{
				int key = z.keys[k];
				int x = ox + Voxelizer.Zone.lx(key), zz = oz + Voxelizer.Zone.lz(key);
				if (x < 0 || zz < 0 || x >= SIZE || zz >= SIZE)
				{
					continue;
				}
				int l = Voxelizer.Zone.layer(key);
				lo = Math.min(lo, l);
				hi = Math.max(hi, l);
			}
		}
		for (int i = 0; water != null && i < water.wet.length; ++i)
		{
			if (water.wet[i])
			{
				lo = Math.min(lo, water.layer(i));
				hi = Math.max(hi, water.layer(i));
			}
		}
		if (lo > hi)
		{
			return new VoxelGrid(SIZE, 1, SIZE, 0);
		}
		// with more layers than fit, keep those down to the lowest geometry: the ground and what stands on it
		lo = Math.max(lo, hi - MAX_LAYERS + 1) - HEADROOM;
		VoxelGrid g = new VoxelGrid(SIZE, hi - lo + 1, SIZE, lo);
		for (int i = 0; i < zones.length; ++i)
		{
			Voxelizer.Zone z = zones[i];
			if (z == null)
			{
				continue;
			}
			int ox = (zoneX[i] - baseZone) * perZone, oz = (zoneZ[i] - baseZone) * perZone;
			for (int k = 0; k < z.count; ++k)
			{
				int key = z.keys[k];
				int x = ox + Voxelizer.Zone.lx(key), zz = oz + Voxelizer.Zone.lz(key);
				int y = Voxelizer.Zone.layer(key) - lo;
				if (x < 0 || zz < 0 || x >= SIZE || zz >= SIZE || y < 0 || y >= g.ny)
				{
					continue;
				}
				int o = k * Voxelizer.STRIDE;
				float[] d = z.data;
				// a voxel holding only a little surface (a lamp on a wall, a sign, a fence's rail) would block as much
				// as a solid wall, half a tile of it: it is left empty
				if (d[o] < THIN_AREA)
				{
					continue;
				}
				int cell = d[o + 1] > d[o] * 0.5f ? 1 | FOLIAGE : 1;
				g.top[zz * g.nx + x] = Math.min(g.top[zz * g.nx + x], d[o + 2]);
				// zones overlap only where models overhang their zone: a voxel is foliage if it is in both
				int idx = g.index(x, y, zz);
				g.cells[idx] = g.cells[idx] == 0 ? cell : cell & g.cells[idx] | 1;
			}
		}
		if (water != null)
		{
			addWater(g, water);
		}
		return g;
	}

	// Every voxel over the voxel columns of every water tile, at the water's height; what already fills a voxel (a pier,
	// a post, the bank) stays.
	private static void addWater(VoxelGrid g, VoxelWater water)
	{
		int perTile = 128 / VOXEL;
		for (int i = 0; i < water.wet.length; ++i)
		{
			int y = water.layer(i) - g.layer0;
			if (!water.wet[i] || y < 0 || y >= g.ny)
			{
				continue;
			}
			int tx = i % SCENE_TILES, tz = i / SCENE_TILES;
			for (int dz = 0; dz < perTile; ++dz)
			{
				for (int dx = 0; dx < perTile; ++dx)
				{
					int idx = g.index(tx * perTile + dx, y, tz * perTile + dz);
					if (g.cells[idx] == 0)
					{
						g.cells[idx] = 1;
					}
				}
			}
		}
	}

	static boolean foliage(int cell)
	{
		return cell < 0;
	}

	/** Whether ray seed passes through foliage cell idx: the same ray and cell always agree. */
	static boolean passes(int idx, int seed)
	{
		int h = idx * 0x9E3779B1 ^ seed * 0x85EBCA77;
		h ^= h >>> 15;
		h *= 0x2C1B3C6D;
		h ^= h >>> 12;
		return (h & 0xffff) < FOLIAGE_PASS * 0x10000;
	}

	/**
	 * March a ray through the grid (Amanatides and Woo) from (ox, oy, oz) along the unit direction d, voxel
	 * space, up to maxT voxels. Returns the index of the first occupied cell, or -1 when the ray leaves the grid
	 * or runs out. skipStart ignores the cell the ray starts in. Foliage stops some rays and lets others through,
	 * picked by seed; a negative seed stops at foliage too.
	 */
	int trace(float ox, float oy, float oz, float dx, float dy, float dz, float maxT, boolean skipStart, int seed)
	{
		// clip to the grid's box
		float t0 = 0, t1 = maxT;
		if (Math.abs(dx) < 1e-9f)
		{
			if (ox < 0 || ox >= nx)
			{
				return -1;
			}
		}
		else
		{
			float ta = -ox / dx, tb = (nx - ox) / dx;
			t0 = Math.max(t0, Math.min(ta, tb));
			t1 = Math.min(t1, Math.max(ta, tb));
		}
		if (Math.abs(dy) < 1e-9f)
		{
			if (oy < 0 || oy >= ny)
			{
				return -1;
			}
		}
		else
		{
			float ta = -oy / dy, tb = (ny - oy) / dy;
			t0 = Math.max(t0, Math.min(ta, tb));
			t1 = Math.min(t1, Math.max(ta, tb));
		}
		if (Math.abs(dz) < 1e-9f)
		{
			if (oz < 0 || oz >= nz)
			{
				return -1;
			}
		}
		else
		{
			float ta = -oz / dz, tb = (nz - oz) / dz;
			t0 = Math.max(t0, Math.min(ta, tb));
			t1 = Math.min(t1, Math.max(ta, tb));
		}
		if (t0 > t1)
		{
			return -1;
		}
		boolean inside = t0 == 0;
		float px = ox + dx * t0, py = oy + dy * t0, pz = oz + dz * t0;
		int x = clamp((int) Math.floor(px), nx), y = clamp((int) Math.floor(py), ny), z = clamp((int) Math.floor(pz), nz);
		int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
		float ddx = Math.abs(dx) < 1e-9f ? Float.MAX_VALUE : Math.abs(1 / dx);
		float ddy = Math.abs(dy) < 1e-9f ? Float.MAX_VALUE : Math.abs(1 / dy);
		float ddz = Math.abs(dz) < 1e-9f ? Float.MAX_VALUE : Math.abs(1 / dz);
		float tx = ddx == Float.MAX_VALUE ? Float.MAX_VALUE : t0 + (dx > 0 ? x + 1 - px : px - x) * ddx;
		float ty = ddy == Float.MAX_VALUE ? Float.MAX_VALUE : t0 + (dy > 0 ? y + 1 - py : py - y) * ddy;
		float tz = ddz == Float.MAX_VALUE ? Float.MAX_VALUE : t0 + (dz > 0 ? z + 1 - pz : pz - z) * ddz;
		boolean skip = skipStart && inside;
		int stride = nx * nz;
		int idx = index(x, y, z);
		while (true)
		{
			int cell = cells[idx];
			if (!skip && cell != 0 && (cell > 0 || seed < 0 || !passes(idx, seed)))
			{
				return idx;
			}
			skip = false;
			if (tx <= ty && tx <= tz)
			{
				if (tx > t1)
				{
					return -1;
				}
				x += sx;
				if (x < 0 || x >= nx)
				{
					return -1;
				}
				idx += sx;
				tx += ddx;
			}
			else if (ty <= tz)
			{
				if (ty > t1)
				{
					return -1;
				}
				y += sy;
				if (y < 0 || y >= ny)
				{
					return -1;
				}
				idx += sy * stride;
				ty += ddy;
			}
			else
			{
				if (tz > t1)
				{
					return -1;
				}
				z += sz;
				if (z < 0 || z >= nz)
				{
					return -1;
				}
				idx += sz * nx;
				tz += ddz;
			}
		}
	}

	/** An even spread of count unit directions over the whole sphere. */
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

	private static int clamp(int v, int n)
	{
		return Math.max(0, Math.min(n - 1, v));
	}
}
