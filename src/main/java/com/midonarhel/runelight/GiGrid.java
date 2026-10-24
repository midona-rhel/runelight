package com.midonarhel.runelight;

/**
 * Dense voxel grid over the base scene for the global illumination's rays: 208 x 208 half-tile columns, up to
 * MAX_LAYERS half-tile layers from the highest geometry down. A cell is 0 when empty, else packed: bit 0 set,
 * bit 1 two-sided (its faces point both ways, as a thin wall's do), albedo sqrt-encoded 5 bits per channel from
 * bit 2, octahedral normal 7 bits per axis from bit 17, bit 31 foliage.
 *
 * Voxel space: x = world x / VOXEL, y = world y / VOXEL - layer0, z = world z / VOXEL; world is the client's local
 * coordinates of the scene (base tile 0 at 0), y down.
 */
final class GiGrid
{
	static final int VOXEL = 64;
	static final int SIZE = 208;
	static final int MAX_LAYERS = 96;
	// normals shorter than this (averaged over the voxel) mean faces pointing both ways
	static final float TWO_SIDED = 0.35f;
	// bit 31: mostly swaying foliage, which rays pass with this chance
	static final int FOLIAGE = 1 << 31;
	static final float FOLIAGE_PASS = 0.4f;
	// square world units of surface touching a voxel below which it doesn't count as occupied: a quarter of a face
	static final float THIN_AREA = VOXEL * VOXEL / 4f;

	final int nx, ny, nz;
	final int layer0;
	final int[] cells;

	GiGrid(int nx, int ny, int nz, int layer0)
	{
		this.nx = nx;
		this.ny = ny;
		this.nz = nz;
		this.layer0 = layer0;
		cells = new int[nx * ny * nz];
	}

	int index(int x, int y, int z)
	{
		return (y * nz + z) * nx + x;
	}

	int cellX(int index)
	{
		return index % nx;
	}

	int cellZ(int index)
	{
		return index / nx % nz;
	}

	int cellY(int index)
	{
		return index / (nx * nz);
	}

	boolean occupied(int x, int y, int z)
	{
		return x >= 0 && y >= 0 && z >= 0 && x < nx && y < ny && z < nz && cells[index(x, y, z)] != 0;
	}

	/**
	 * Assemble the grid from voxelized zones. zones[i] lies at extended zone (zoneX[i], zoneZ[i]); the base scene
	 * starts at extended zone baseZone.
	 */
	static GiGrid assemble(GiVoxels.Zone[] zones, int[] zoneX, int[] zoneZ, int baseZone)
	{
		return assemble(zones, zoneX, zoneZ, baseZone, null);
	}

	/** As above, with the water's surface (GiWater, may be null) as one more layer of voxels. */
	static GiGrid assemble(GiVoxels.Zone[] zones, int[] zoneX, int[] zoneZ, int baseZone, GiWater water)
	{
		int perZone = 8 * 128 / VOXEL;
		int lo = Integer.MAX_VALUE, hi = Integer.MIN_VALUE;
		for (int i = 0; i < zones.length; ++i)
		{
			GiVoxels.Zone z = zones[i];
			if (z == null)
			{
				continue;
			}
			int ox = (zoneX[i] - baseZone) * perZone, oz = (zoneZ[i] - baseZone) * perZone;
			for (int k = 0; k < z.count; ++k)
			{
				int key = z.keys[k];
				int x = ox + GiVoxels.Zone.lx(key), zz = oz + GiVoxels.Zone.lz(key);
				if (x < 0 || zz < 0 || x >= SIZE || zz >= SIZE)
				{
					continue;
				}
				int l = GiVoxels.Zone.layer(key);
				lo = Math.min(lo, l);
				hi = Math.max(hi, l);
			}
		}
		for (int i = 0; water != null && i < water.type.length; ++i)
		{
			if (water.type[i] != WaterMap.NONE)
			{
				lo = Math.min(lo, water.layer(i));
				hi = Math.max(hi, water.layer(i));
			}
		}
		if (lo > hi)
		{
			return new GiGrid(SIZE, 1, SIZE, 0);
		}
		// with more layers than fit, keep those down to the lowest geometry: the ground and what stands on it
		lo = Math.max(lo, hi - MAX_LAYERS + 1);
		GiGrid g = new GiGrid(SIZE, hi - lo + 1, SIZE, lo);
		for (int i = 0; i < zones.length; ++i)
		{
			GiVoxels.Zone z = zones[i];
			if (z == null)
			{
				continue;
			}
			int ox = (zoneX[i] - baseZone) * perZone, oz = (zoneZ[i] - baseZone) * perZone;
			for (int k = 0; k < z.count; ++k)
			{
				int key = z.keys[k];
				int x = ox + GiVoxels.Zone.lx(key), zz = oz + GiVoxels.Zone.lz(key);
				int y = GiVoxels.Zone.layer(key) - lo;
				if (x < 0 || zz < 0 || x >= SIZE || zz >= SIZE || y < 0 || y >= g.ny)
				{
					continue;
				}
				int o = k * GiVoxels.STRIDE;
				float[] d = z.data;
				// a voxel holding only a little surface (a lamp on a wall, a sign, a fence's rail) would block as much
				// as a solid wall, half a tile of it: it is left empty
				if (d[o] < THIN_AREA)
				{
					continue;
				}
				int idx = g.index(x, y, zz);
				int cell = pack(d[o], d[o + 1], d[o + 2], d[o + 3], d[o + 4], d[o + 5], d[o + 6]);
				if (d[o + 7] > d[o] * 0.5f)
				{
					cell |= FOLIAGE;
				}
				// zones overlap only where models overhang their zone: average the two
				g.cells[idx] = g.cells[idx] == 0 ? cell : merge(g.cells[idx], cell);
			}
		}
		if (water != null)
		{
			addWater(g, water);
		}
		return g;
	}

	// The surface faces up (y down), over the voxel columns of every water tile; what already fills a voxel (a pier,
	// a post, the bank) stays.
	private static void addWater(GiGrid g, GiWater water)
	{
		int perTile = 128 / VOXEL;
		float[] rgb = new float[3];
		int[] cellOfType = new int[7];
		for (int t = 1; t < cellOfType.length; ++t)
		{
			GiWater.albedo(t, rgb);
			cellOfType[t] = pack(1, rgb[0], rgb[1], rgb[2], 0, -1, 0);
		}
		for (int i = 0; i < water.type.length; ++i)
		{
			int type = water.type[i];
			int y = water.layer(i) - g.layer0;
			if (type == WaterMap.NONE || y < 0 || y >= g.ny)
			{
				continue;
			}
			int tx = i % GiPatches.SIZE, tz = i / GiPatches.SIZE;
			for (int dz = 0; dz < perTile; ++dz)
			{
				for (int dx = 0; dx < perTile; ++dx)
				{
					int idx = g.index(tx * perTile + dx, y, tz * perTile + dz);
					if (g.cells[idx] == 0)
					{
						g.cells[idx] = cellOfType[type];
					}
				}
			}
		}
	}

	static int pack(float w, float r, float gr, float b, float nx, float ny, float nz)
	{
		float inv = w > 0 ? 1 / w : 0;
		float lx = nx * inv, ly = ny * inv, lz = nz * inv;
		float len = (float) Math.sqrt(lx * lx + ly * ly + lz * lz);
		boolean twoSided = len < TWO_SIDED;
		int cell = 1 | (twoSided ? 2 : 0);
		cell |= q5(r * inv) << 2 | q5(gr * inv) << 7 | q5(b * inv) << 12;
		if (!twoSided)
		{
			cell |= octEncode(lx / len, ly / len, lz / len) << 17;
		}
		return cell;
	}

	private static int merge(int a, int b)
	{
		float[] na = normal(a), nb = normal(b);
		float sx = na[0] + nb[0], sy = na[1] + nb[1], sz = na[2] + nb[2];
		return pack(2, albedo(a, 0) + albedo(b, 0), albedo(a, 1) + albedo(b, 1), albedo(a, 2) + albedo(b, 2), sx, sy, sz)
			| a & b & FOLIAGE;
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

	private static int q5(float v)
	{
		return Math.max(0, Math.min(31, Math.round((float) Math.sqrt(Math.max(v, 0)) * 31)));
	}

	static boolean twoSided(int cell)
	{
		return (cell & 2) != 0;
	}

	/** Linear albedo channel c of a cell. */
	static float albedo(int cell, int c)
	{
		float v = (cell >>> (2 + c * 5) & 31) / 31f;
		return v * v;
	}

	/** Unit normal of a one-sided cell, zero for a two-sided one. */
	static float[] normal(int cell)
	{
		if (twoSided(cell))
		{
			return new float[3];
		}
		return octDecode(cell >>> 17 & 0x3fff);
	}

	static int octEncode(float x, float y, float z)
	{
		float l1 = Math.abs(x) + Math.abs(y) + Math.abs(z);
		float u = x / l1, v = y / l1;
		if (z < 0)
		{
			float ou = u;
			u = (1 - Math.abs(v)) * (ou >= 0 ? 1 : -1);
			v = (1 - Math.abs(ou)) * (v >= 0 ? 1 : -1);
		}
		int qu = Math.round((u * 0.5f + 0.5f) * 127);
		int qv = Math.round((v * 0.5f + 0.5f) * 127);
		return qu | qv << 7;
	}

	static float[] octDecode(int e)
	{
		float u = (e & 127) / 127f * 2 - 1, v = (e >> 7 & 127) / 127f * 2 - 1;
		float z = 1 - Math.abs(u) - Math.abs(v);
		if (z < 0)
		{
			float ou = u;
			u = (1 - Math.abs(v)) * (ou >= 0 ? 1 : -1);
			v = (1 - Math.abs(ou)) * (v >= 0 ? 1 : -1);
		}
		float len = (float) Math.sqrt(u * u + v * v + z * z);
		return new float[]{u / len, v / len, z / len};
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

	private static int clamp(int v, int n)
	{
		return Math.max(0, Math.min(n - 1, v));
	}
}
