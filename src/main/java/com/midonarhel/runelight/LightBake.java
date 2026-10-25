package com.midonarhel.runelight;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Bakes static point lights into cells of the light volume (LightVolume), shadowed by the scene's voxels (VoxelGrid).
 * Pure CPU and GL free; one call bakes a box of cells for the lights that reach it.
 *
 * A cell holds the light that reaches its centre as an ambient cube: the light's brightness (the mean of its colour,
 * after 117 HD's falloff and the share of its shadow rays that get through) for each of the six directions a surface
 * can face, a surface with the normal n taking n.x^2 of the face it looks toward on x, and so on, so one that faces
 * away from a light gets none of it. Brightness is per face, but colour is one per cell (its hue and saturation, the
 * colour of the cell's total), which with the two other things a cell tells fits eight half floats:
 *
 * <pre>
 * a: +x, -x, +z, -z
 * b: +y, -y, tint, flicker
 * </pre>
 *
 * y points down in the world, so +y faces the ground. The tint packs hue (7 bits) and saturation (4 bits) into the
 * exact integer hue * 16 + saturation. The flicker packs which light among those that flicker or pulse reaches the
 * cell most (its slot, 1 to 127, 0 for none) and its share of the cell's light in sixteenths, as slot * 16 + share,
 * so the shader can swing that share with the light's current strength (light_volume.glsl). Both are read without
 * filtering: they are whole numbers.
 *
 * Shadows: shadow rays go from the cell's centre to the light, a few jittered points on the small sphere (LIGHT_SIZE)
 * the light is, through the voxels. The voxel the ray starts in doesn't block it, as the cell may be anywhere in the
 * voxel its surface is in. A light inside geometry (a torch flame in its sconce's voxel, a light in a brazier) is
 * moved out of the voxel it is in into the nearest empty one for the rays to end in (see {@link #targets}), so it
 * doesn't shadow itself (of the voxels beside it, the nearest in its own tile, the side of the wall it was put on),
 * while a wall it hangs on still stands between it and the other side. Thin geometry is
 * voxels, as in the skylight: a wall is a voxel or two thick. Foliage lets some rays through.
 */
final class LightBake
{
	// world units a cell is
	static final int CELL = 32;
	// shadow rays per cell and light, to the corners of a tetrahedron around the light
	static final int SAMPLES = 4;
	// the radius of the sphere the light is, world units: the soft edge of a shadow
	static final float LIGHT_SIZE = 12;
	// a light adding less than this to the brightest channel of a cell is left out of it
	static final float MIN_LIGHT = 1f / 1024;
	// nearer to a light than this the direction its light comes from is too coarse to tell which faces it reaches
	// (the cell's centre may lie beyond the light from a wall it hangs on): every face gets some, fading to the
	// light's own direction by FAR_DIRECTION
	static final float NEAR_DIRECTION = CELL * 0.5f;
	static final float FAR_DIRECTION = CELL * 2;
	// the share of a light every face gets at the least, at NEAR_DIRECTION
	static final float NEAR_FACE = 0.5f;
	// flicker slots: 1 to SLOTS - 1, and the sixteenths of a share
	static final int SLOTS = 128;
	static final int SHARE_LEVELS = 15;
	static final int HUE_STEPS = 128;
	static final int SATURATION_LEVELS = 15;

	// world units a tile is, and the distance (squared, in voxels) that puts a voxel of another tile after all in the tile
	private static final int TILE = 128;
	private static final float OTHER_TILE = 1e4f;

	static final int PX = 0, NX = 1, PY = 2, NY = 3, PZ = 4, NZ = 5;

	private static final float[] TETRAHEDRON = {1, 1, 1, 1, -1, -1, -1, 1, -1, -1, -1, 1};
	private static final float INV_SQRT3 = (float) (1 / Math.sqrt(3));

	private LightBake()
	{
	}

	/**
	 * A light as baked: where it is, its radius, its colour times strength (linear), and its flicker slot (0: steady).
	 * Immutable, shared by the bakes of the blocks it reaches; they add up how long it took them.
	 */
	static final class Light
	{
		final float x, y, z, radius, r, g, b;
		final int slot;
		// the cells its sphere reaches, inclusive
		final int minX, minY, minZ, maxX, maxY, maxZ;
		final float brightest, mean;
		private final AtomicLong nanos = new AtomicLong();
		private final AtomicLong cells = new AtomicLong();

		Light(float x, float y, float z, float radius, float r, float g, float b, int slot)
		{
			this.x = x;
			this.y = y;
			this.z = z;
			this.radius = radius;
			this.r = r;
			this.g = g;
			this.b = b;
			this.slot = slot;
			minX = cell(x - radius);
			minY = cell(y - radius);
			minZ = cell(z - radius);
			maxX = cell(x + radius);
			maxY = cell(y + radius);
			maxZ = cell(z + radius);
			brightest = Math.max(r, Math.max(g, b));
			mean = (r + g + b) / 3;
		}

		/** Nanoseconds spent baking it, over all the blocks. */
		long nanos()
		{
			return nanos.get();
		}

		/** Cells it was traced into. */
		long cells()
		{
			return cells.get();
		}
	}

	/** The cell a world coordinate is in. */
	static int cell(float world)
	{
		return (int) Math.floor(world / CELL);
	}

	/**
	 * Bake a box of cells.
	 *
	 * @param lights the first n lights that may reach it
	 * @param x0, y0, z0 the box's first cell, and its size in cells
	 * @param a, b the cells' texels, 4 shorts each, x fastest then y then z (see the class comment)
	 */
	static void bake(VoxelGrid grid, Light[] lights, int n, int x0, int y0, int z0, int sx, int sy, int sz, short[] a,
		short[] b)
	{
		int count = sx * sy * sz;
		float[] faces = new float[count * 6];
		float[] rgb = new float[count * 3];
		float[] total = new float[count];
		float[] best = new float[count];
		int[] bestSlot = new int[count];
		float[] target = new float[SAMPLES * 3];

		for (int li = 0; li < n; ++li)
		{
			Light l = lights[li];
			int ax = Math.max(l.minX, x0), bx = Math.min(l.maxX, x0 + sx - 1);
			int ay = Math.max(l.minY, y0), by = Math.min(l.maxY, y0 + sy - 1);
			int az = Math.max(l.minZ, z0), bz = Math.min(l.maxZ, z0 + sz - 1);
			if (ax > bx || ay > by || az > bz)
			{
				continue;
			}
			long start = System.nanoTime();
			targets(grid, l, target);
			float r2 = l.radius * l.radius;
			long traced = 0;
			for (int cz = az; cz <= bz; ++cz)
			{
				float dz = l.z - (cz + 0.5f) * CELL;
				for (int cy = ay; cy <= by; ++cy)
				{
					float dy = l.y - (cy + 0.5f) * CELL;
					int row = ((cz - z0) * sy + (cy - y0)) * sx - x0;
					for (int cx = ax; cx <= bx; ++cx)
					{
						float dx = l.x - (cx + 0.5f) * CELL;
						float d2 = dx * dx + dy * dy + dz * dz;
						if (d2 >= r2)
						{
							continue;
						}
						float d = (float) Math.sqrt(d2);
						float att = 1 - d / l.radius;
						att *= att;
						if (att * l.brightest < MIN_LIGHT)
						{
							continue;
						}
						++traced;
						float seen = visibility(grid, (cx + 0.5f) * CELL, (cy + 0.5f) * CELL, (cz + 0.5f) * CELL, target,
							(cx * 73856093 ^ cy * 19349663 ^ cz * 83492791) & 0x1fffffff);
						if (seen <= 0)
						{
							continue;
						}
						// the direction to the light, and how much of the light each face takes
						float inv = 1 / Math.max(d, 1);
						float ux = dx * inv, uy = dy * inv, uz = dz * inv;
						float near = NEAR_FACE * (1 - smoothstep(NEAR_DIRECTION, FAR_DIRECTION, d));
						int i = row + cx;
						int f = i * 6;
						float lum = l.mean * att * seen;
						faces[f + PX] += lum * Math.max(Math.max(ux, 0), near);
						faces[f + NX] += lum * Math.max(Math.max(-ux, 0), near);
						faces[f + PY] += lum * Math.max(Math.max(uy, 0), near);
						faces[f + NY] += lum * Math.max(Math.max(-uy, 0), near);
						faces[f + PZ] += lum * Math.max(Math.max(uz, 0), near);
						faces[f + NZ] += lum * Math.max(Math.max(-uz, 0), near);
						// the colour of what the faces add up to: each face has the light's, weighted like its share
						float weight = att * seen * (Math.max(Math.max(ux, 0), near) + Math.max(Math.max(-ux, 0), near)
							+ Math.max(Math.max(uy, 0), near) + Math.max(Math.max(-uy, 0), near)
							+ Math.max(Math.max(uz, 0), near) + Math.max(Math.max(-uz, 0), near));
						rgb[i * 3] += l.r * weight;
						rgb[i * 3 + 1] += l.g * weight;
						rgb[i * 3 + 2] += l.b * weight;
						float m = l.mean * weight;
						total[i] += m;
						if (l.slot > 0 && m > best[i])
						{
							best[i] = m;
							bestSlot[i] = l.slot;
						}
					}
				}
			}
			l.nanos.addAndGet(System.nanoTime() - start);
			l.cells.addAndGet(traced);
		}

		for (int i = 0; i < count; ++i)
		{
			int f = i * 6, t = i * 4;
			a[t] = Half.toHalf(faces[f + PX]);
			a[t + 1] = Half.toHalf(faces[f + NX]);
			a[t + 2] = Half.toHalf(faces[f + PZ]);
			a[t + 3] = Half.toHalf(faces[f + NZ]);
			b[t] = Half.toHalf(faces[f + PY]);
			b[t + 1] = Half.toHalf(faces[f + NY]);
			b[t + 2] = Half.toHalf(tint(rgb[i * 3], rgb[i * 3 + 1], rgb[i * 3 + 2]));
			b[t + 3] = Half.toHalf(flicker(bestSlot[i], total[i] > 0 ? best[i] / total[i] : 0));
		}
	}

	private static float smoothstep(float lo, float hi, float v)
	{
		float t = Math.max(0, Math.min(1, (v - lo) / (hi - lo)));
		return t * t * (3 - 2 * t);
	}

	/**
	 * The share of the shadow rays from a point to the light that get through, 0 to 1.
	 */
	private static float visibility(VoxelGrid grid, float x, float y, float z, float[] target, int seed)
	{
		int seen = 0;
		for (int s = 0; s < SAMPLES; ++s)
		{
			if (clear(grid, x, y, z, target[s * 3], target[s * 3 + 1], target[s * 3 + 2], seed << 2 | s))
			{
				++seen;
			}
		}
		return seen / (float) SAMPLES;
	}

	/**
	 * Whether nothing but the voxel it starts in and the one it ends in stands in the way between two world points.
	 */
	static boolean clear(VoxelGrid grid, float x, float y, float z, float tx, float ty, float tz, int seed)
	{
		float inv = 1f / VoxelGrid.VOXEL;
		float dx = (tx - x) * inv, dy = (ty - y) * inv, dz = (tz - z) * inv;
		float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (length < 1e-4f)
		{
			return true;
		}
		float ox = x * inv, oy = y * inv - grid.layer0, oz = z * inv;
		int hit = grid.trace(ox, oy, oz, dx / length, dy / length, dz / length, length - 1e-3f, true, seed);
		if (hit < 0)
		{
			return true;
		}
		// it ran into the voxel the light is in: that is the light's own
		int vx = (int) Math.floor(tx * inv), vy = (int) Math.floor(ty * inv) - grid.layer0, vz = (int) Math.floor(tz * inv);
		return vx >= 0 && vy >= 0 && vz >= 0 && vx < grid.nx && vy < grid.ny && vz < grid.nz
			&& hit == grid.index(vx, vy, vz);
	}

	/**
	 * Where the shadow rays to a light end, SAMPLES points (x, y, z each) around the light: where it is, unless that is
	 * in an occupied voxel, then moved into the empty voxel nearest to it (to just inside that voxel). The points are
	 * the corners of a small tetrahedron around that; one that would lie in another occupied voxel is the centre.
	 */
	static void targets(VoxelGrid grid, Light l, float[] out)
	{
		float qx = l.x, qy = l.y, qz = l.z;
		float inv = 1f / VoxelGrid.VOXEL;
		int vx = (int) Math.floor(qx * inv), vy = (int) Math.floor(qy * inv) - grid.layer0, vz = (int) Math.floor(qz * inv);
		if (grid.occupied(vx, vy, vz))
		{
			float bestDistance = Float.MAX_VALUE;
			int bx = 0, by = 0, bz = 0;
			int perTile = TILE / VoxelGrid.VOXEL;
			int tileX = Math.floorDiv(vx, perTile), tileZ = Math.floorDiv(vz, perTile);
			for (int dz = -1; dz <= 1; ++dz)
			{
				for (int dy = -1; dy <= 1; ++dy)
				{
					for (int dx = -1; dx <= 1; ++dx)
					{
						if ((dx | dy | dz) == 0 || grid.occupied(vx + dx, vy + dy, vz + dz))
						{
							continue;
						}
						// the distance to the neighbour's box
						float ex = outside(qx * inv, vx + dx), ey = outside(qy * inv - grid.layer0, vy + dy),
							ez = outside(qz * inv, vz + dz);
						float distance = ex * ex + ey * ey + ez * ez;
						// a light belongs to the tile it is in, the side of a wall it was put on: a voxel of another tile
						// is the last place for it
						if (Math.floorDiv(vx + dx, perTile) != tileX || Math.floorDiv(vz + dz, perTile) != tileZ)
						{
							distance += OTHER_TILE;
						}
						if (distance < bestDistance)
						{
							bestDistance = distance;
							bx = dx;
							by = dy;
							bz = dz;
						}
					}
				}
			}
			if (bestDistance < Float.MAX_VALUE)
			{
				// to just inside the box on each axis it lies beside
				if (bx != 0)
				{
					qx = ((bx > 0 ? vx + 1 : vx) + (bx > 0 ? 0.01f : -0.01f)) * VoxelGrid.VOXEL;
				}
				if (by != 0)
				{
					qy = ((by > 0 ? vy + 1 : vy) + grid.layer0 + (by > 0 ? 0.01f : -0.01f)) * VoxelGrid.VOXEL;
				}
				if (bz != 0)
				{
					qz = ((bz > 0 ? vz + 1 : vz) + (bz > 0 ? 0.01f : -0.01f)) * VoxelGrid.VOXEL;
				}
				vx = (int) Math.floor(qx * inv);
				vy = (int) Math.floor(qy * inv) - grid.layer0;
				vz = (int) Math.floor(qz * inv);
			}
		}
		for (int s = 0; s < SAMPLES; ++s)
		{
			float px = qx + TETRAHEDRON[s * 3] * INV_SQRT3 * LIGHT_SIZE;
			float py = qy + TETRAHEDRON[s * 3 + 1] * INV_SQRT3 * LIGHT_SIZE;
			float pz = qz + TETRAHEDRON[s * 3 + 2] * INV_SQRT3 * LIGHT_SIZE;
			int wx = (int) Math.floor(px * inv), wy = (int) Math.floor(py * inv) - grid.layer0,
				wz = (int) Math.floor(pz * inv);
			if ((wx != vx || wy != vy || wz != vz) && grid.occupied(wx, wy, wz))
			{
				px = qx;
				py = qy;
				pz = qz;
			}
			out[s * 3] = px;
			out[s * 3 + 1] = py;
			out[s * 3 + 2] = pz;
		}
	}

	// how far a coordinate in voxels is from the voxel v, 0 inside it, in voxels
	private static float outside(float c, int v)
	{
		return c < v ? v - c : c > v + 1 ? c - v - 1 : 0;
	}

	// ---------------------------------------------------------------------------------------------------------
	// What a cell tells

	/**
	 * The packed tint of a colour: the colour's hue and saturation (HSV's, with the value left out), hue * 16 +
	 * saturation, a whole number below 2048. Grey is 0.
	 */
	static float tint(float r, float g, float b)
	{
		float max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
		if (max <= 0 || max == min)
		{
			return 0;
		}
		float hue;
		if (max == r)
		{
			hue = (g - b) / (max - min);
		}
		else if (max == g)
		{
			hue = 2 + (b - r) / (max - min);
		}
		else
		{
			hue = 4 + (r - g) / (max - min);
		}
		int h = Math.round((hue < 0 ? hue + 6 : hue) / 6 * HUE_STEPS) % HUE_STEPS;
		int s = Math.round((max - min) / max * SATURATION_LEVELS);
		return h * 16 + s;
	}

	/**
	 * The colour a packed tint is, its three channels averaging 1 (what light_volume.glsl does).
	 */
	static void untint(float packed, float[] out)
	{
		int p = Math.round(packed);
		float hue = (p >> 4) * 6f / HUE_STEPS, saturation = (p & 15) / (float) SATURATION_LEVELS;
		float r = Math.max(0, Math.min(1, Math.abs(hue - 3) - 1));
		float g = Math.max(0, Math.min(1, 2 - Math.abs(hue - 2)));
		float b = Math.max(0, Math.min(1, 2 - Math.abs(hue - 4)));
		r = 1 - saturation + saturation * r;
		g = 1 - saturation + saturation * g;
		b = 1 - saturation + saturation * b;
		float mean = (r + g + b) / 3;
		out[0] = r / mean;
		out[1] = g / mean;
		out[2] = b / mean;
	}

	/** The packed flicker of a slot and its share of the cell's light (0 to 1), a whole number below 2048. */
	static float flicker(int slot, float share)
	{
		int level = Math.round(Math.max(0, Math.min(1, share)) * SHARE_LEVELS);
		return slot > 0 && level > 0 ? slot * 16 + level : 0;
	}

	/**
	 * What the shader makes of a cell, in Java: the colour of the light that faces the normal n (unit, y down) get from
	 * the cell at texel i of a and b, with the flicker slots' strengths in mods (a slot's strength now over its strength
	 * when baked).
	 */
	static void irradiance(short[] a, short[] b, int i, float nx, float ny, float nz, float[] mods, float[] out)
	{
		int t = i * 4;
		float fx = Half.fromHalf(nx >= 0 ? a[t] : a[t + 1]), fz = Half.fromHalf(nz >= 0 ? a[t + 2] : a[t + 3]),
			fy = Half.fromHalf(ny >= 0 ? b[t] : b[t + 1]);
		float lum = nx * nx * fx + ny * ny * fy + nz * nz * fz;
		float[] k = new float[3];
		untint(Half.fromHalf(b[t + 2]), k);
		int packed = Math.round(Half.fromHalf(b[t + 3]));
		float share = (packed & 15) / (float) SHARE_LEVELS;
		float swing = packed == 0 ? 1 : 1 - share + share * mods[packed >> 4];
		out[0] = lum * k[0] * swing;
		out[1] = lum * k[1] * swing;
		out[2] = lum * k[2] * swing;
	}
}
