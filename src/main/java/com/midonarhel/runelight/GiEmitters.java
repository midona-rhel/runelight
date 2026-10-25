package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * Where the global illumination's colour comes from and where it goes, found once per region load on the global
 * illumination's worker (GlobalIllumination) from the voxels; lit by GiRelight.
 *
 * The global illumination is a volume of tint: every voxel of the base scene that has air beside it gives that air the
 * colour of its surface, as much as the surface is lit (GiRelight), and the colour thins out with the distance from the
 * surface, so the grass' green stands in the air above the grass, strong near it and gone REACH away. A pixel
 * looks the volume up half a voxel and more in front of itself (gi.glsl) and takes that colour as its tint, so a wall
 * beside the grass is greened at its foot and less and less higher up, and no more where the grass is out of reach. The
 * colour goes through air only: a voxel that is filled gives none to what is behind it.
 *
 * A face is a filled voxel and one of its six neighbours that is empty, for a voxel whose faces point that way (a
 * one-sided voxel's normal has at least FACING_COS toward the neighbour; a two-sided one's, a thin wall's, point all
 * ways). From each face the colour is put into the empty voxels in a straight line from it, as long as they are empty
 * and within REACH of the surface itself. How far a voxel's center is from the surface is known to a fraction of the
 * voxel (GiGrid.plane): the surface is not at the voxels' boundaries, and the colour at the foot of a wall would otherwise
 * be whatever the grid happened to cut the ground at. The filled voxel gets its share too, which is what a pixel
 * between it and the first empty voxel reads, and no other filled voxel gets any.
 *
 * Two fields are kept, the colour surfaces that lie flat give (floors, roofs) and the colour the ones that stand give
 * (walls): a pixel never reads its own kind, whose colour it already has, and a wall shows the floors', a floor the
 * walls'. A sloped surface is partly of each kind (HORIZONTAL_MIN, HORIZONTAL_MAX), as is what it reads.
 *
 * What a face puts into a voxel is kept as a list per voxel, to be added up on every relight; where the weights of
 * what a voxel is given add up to more than one they are scaled to one, so a corner is not brighter than a wall.
 */
final class GiEmitters
{
	// how far (world units) from a surface its colour reaches, from the full strength at the surface to none; the shape
	// in between is profile
	static final float REACH = 192;
	// the least cosine a voxel's normal has toward a neighbour for that neighbour to be given its colour
	static final float FACING_COS = 0.2f;
	// A surface facing no more up or down than the first (the y of its normal) stands, more than the second lies flat,
	// and between them it is both.
	static final float HORIZONTAL_MIN = 0.5f, HORIZONTAL_MAX = 0.7f;
	// directions around a face to see the sky in, the least cosine with its normal that counts, and how far they look,
	// in voxels; beyond, the sky
	static final int SKY_SAMPLES = 64;
	static final float SKY_MIN_COS = 0.1f;
	static final float SKY_DISTANCE = 32;
	// The voxels' colors are pushed this far from their gray: a voxel averages the colors of everything in it, the
	// texture detail included, which pulls them toward gray.
	static final float ALBEDO_SATURATION = 1.5f;
	// weights below this are left out
	private static final float MIN_WEIGHT = 0.002f;
	private static final int[][] DIRECTIONS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
	// an even spread over the whole sphere, to measure the sky with
	static final float[] SKY_RAYS = GiGrid.fibonacci(SKY_SAMPLES);

	/** Runs body(0..n-1), possibly in parallel. */
	interface ParallelFor
	{
		void run(int n, IntConsumer body);

		ParallelFor SEQUENTIAL = (n, body) ->
		{
			for (int i = 0; i < n; ++i)
			{
				body.accept(i);
			}
		};
	}

	final GiGrid grid;

	// per face: the filled voxel and the direction it looks, an index of DIRECTIONS; the empty voxel it looks into (where
	// its light is measured from); the direction it faces (the normal of a one-sided voxel, else the axis); the colour its
	// surface gives at unit luminance; and the share of the directions it faces that see the sky
	int faceCount;
	int[] faceSolid;
	byte[] faceDirection;
	int[] faceCell;
	float[] faceNormal;
	float[] faceChroma;
	float[] faceSky;

	// per voxel that is given any colour, cellIndex its index in the grid; the faces that give it some are the entries
	// inStart[i] to inStart[i + 1], each its face and its weight in the field of flat surfaces (inFlat) and in the one
	// of standing ones (inStanding)
	int cellCount;
	int[] cellIndex;
	int[] inStart;
	int[] inFace;
	float[] inFlat;
	float[] inStanding;

	private GiEmitters(GiGrid grid)
	{
		this.grid = grid;
	}

	/** The share of the colour at the surface that a voxel the given distance from it gets, 0 to 1. */
	static float profile(float distance)
	{
		float t = Math.max(0, Math.min(1, distance / REACH));
		float s = t * t * (3 - 2 * t);
		return (1 - s) * (1 - s);
	}

	/** How much a surface with normal y component ny lies flat rather than stands, 0 to 1. */
	static float flat(float ny)
	{
		float t = Math.max(0, Math.min(1, (Math.abs(ny) - HORIZONTAL_MIN) / (HORIZONTAL_MAX - HORIZONTAL_MIN)));
		return t * t * (3 - 2 * t);
	}

	/** Linear color (r, g, b) moved ALBEDO_SATURATION times as far from its luminance, same luminance, into out[at..]. */
	static void saturate(float r, float g, float b, float[] out, int at)
	{
		float l = GiRelight.luminance(r, g, b);
		out[at] = Math.max(0, l + (r - l) * ALBEDO_SATURATION);
		out[at + 1] = Math.max(0, l + (g - l) * ALBEDO_SATURATION);
		out[at + 2] = Math.max(0, l + (b - l) * ALBEDO_SATURATION);
	}

	static GiEmitters build(GiGrid grid, ParallelFor parallel)
	{
		GiEmitters e = new GiEmitters(grid);
		e.findFaces(parallel);
		e.spread();
		return e;
	}

	/** The faces: the filled voxels and the empty ones next to them that they look at. */
	private void findFaces(ParallelFor parallel)
	{
		int[] solid = new int[1024];
		byte[] dir = new byte[1024];
		int n = 0;
		for (int y = 0; y < grid.ny; ++y)
		{
			for (int z = 0; z < grid.nz; ++z)
			{
				for (int x = 0; x < grid.nx; ++x)
				{
					int idx = grid.index(x, y, z);
					int cell = grid.cells[idx];
					if (cell == 0)
					{
						continue;
					}
					float[] normal = GiGrid.twoSided(cell) ? null : GiGrid.normal(cell);
					for (int d = 0; d < DIRECTIONS.length; ++d)
					{
						int[] v = DIRECTIONS[d];
						int nx = x + v[0], ny = y + v[1], nz = z + v[2];
						if (nx < 0 || ny < 0 || nz < 0 || nx >= grid.nx || ny >= grid.ny || nz >= grid.nz
							|| grid.cells[grid.index(nx, ny, nz)] != 0
							|| normal != null && normal[0] * v[0] + normal[1] * v[1] + normal[2] * v[2] < FACING_COS)
						{
							continue;
						}
						if (n == solid.length)
						{
							solid = Arrays.copyOf(solid, n * 2);
							dir = Arrays.copyOf(dir, n * 2);
						}
						solid[n] = idx;
						dir[n] = (byte) d;
						n++;
					}
				}
			}
		}
		faceCount = n;
		faceCell = new int[n];
		faceNormal = new float[n * 3];
		faceChroma = new float[n * 3];
		faceSky = new float[n];
		faceSolid = Arrays.copyOf(solid, n);
		faceDirection = Arrays.copyOf(dir, n);
		parallel.run(n, f ->
		{
			int idx = faceSolid[f];
			int[] v = DIRECTIONS[faceDirection[f]];
			int cell = grid.cells[idx];
			int x = grid.cellX(idx), y = grid.cellY(idx), z = grid.cellZ(idx);
			faceCell[f] = grid.index(x + v[0], y + v[1], z + v[2]);
			float[] normal = GiGrid.twoSided(cell) ? new float[]{v[0], v[1], v[2]} : GiGrid.normal(cell);
			System.arraycopy(normal, 0, faceNormal, f * 3, 3);
			float[] rgb = new float[3];
			saturate(GiGrid.albedo(cell, 0), GiGrid.albedo(cell, 1), GiGrid.albedo(cell, 2), rgb, 0);
			GiRelight.chroma(rgb[0], rgb[1], rgb[2], faceChroma, f * 3);
			// the share of the directions it faces that see the sky
			float ox = x + v[0] + 0.5f, oy = y + v[1] + 0.5f, oz = z + v[2] + 0.5f;
			int facing = 0, open = 0;
			for (int r = 0; r < SKY_SAMPLES; ++r)
			{
				float dx = SKY_RAYS[r * 3], dy = SKY_RAYS[r * 3 + 1], dz = SKY_RAYS[r * 3 + 2];
				if (dx * normal[0] + dy * normal[1] + dz * normal[2] < SKY_MIN_COS)
				{
					continue;
				}
				facing++;
				if (grid.trace(ox, oy, oz, dx, dy, dz, SKY_DISTANCE, true, f * SKY_SAMPLES + r) < 0)
				{
					open++;
				}
			}
			faceSky[f] = facing > 0 ? (float) open / facing : 0;
		});
	}

	/** The list per voxel: where each face puts its colour, and how much. */
	private void spread()
	{
		int cells = grid.cells.length;
		int[] face = new int[4096];
		int[] at = new int[4096];
		float[] byFlat = new float[4096];
		float[] byStanding = new float[4096];
		int n = 0;
		for (int f = 0; f < faceCount; ++f)
		{
			int idx = faceSolid[f];
			int[] v = DIRECTIONS[faceDirection[f]];
			int cell = grid.cells[idx];
			boolean twoSided = GiGrid.twoSided(cell);
			// how much the face looks along the direction, and how far in front of the surface the filled voxel's center is
			float facing = twoSided ? 1 : faceNormal[f * 3] * v[0] + faceNormal[f * 3 + 1] * v[1] + faceNormal[f * 3 + 2] * v[2];
			float distance = twoSided ? 0 : grid.planeDistance(idx);
			float lies = flat(faceNormal[f * 3 + 1]);
			int x = grid.cellX(idx), y = grid.cellY(idx), z = grid.cellZ(idx);
			for (int k = twoSided ? 1 : 0;; ++k)
			{
				float d = distance + facing * GiGrid.VOXEL * k;
				if (d >= REACH)
				{
					break;
				}
				int cx = x + v[0] * k, cy = y + v[1] * k, cz = z + v[2] * k;
				if (k > 0 && (cx < 0 || cy < 0 || cz < 0 || cx >= grid.nx || cy >= grid.ny || cz >= grid.nz
					|| grid.cells[grid.index(cx, cy, cz)] != 0))
				{
					break;
				}
				float w = facing * profile(Math.max(d, 0));
				if (w < MIN_WEIGHT)
				{
					break;
				}
				if (n == face.length)
				{
					face = Arrays.copyOf(face, n * 2);
					at = Arrays.copyOf(at, n * 2);
					byFlat = Arrays.copyOf(byFlat, n * 2);
					byStanding = Arrays.copyOf(byStanding, n * 2);
				}
				face[n] = f;
				at[n] = grid.index(cx, cy, cz);
				byFlat[n] = w * lies;
				byStanding[n] = w * (1 - lies);
				n++;
			}
		}

		// what each voxel is given in all, to scale it to one at most, and the voxels in order of their index
		float[] sumFlat = new float[cells], sumStanding = new float[cells];
		int[] compact = new int[cells];
		Arrays.fill(compact, -1);
		for (int i = 0; i < n; ++i)
		{
			sumFlat[at[i]] += byFlat[i];
			sumStanding[at[i]] += byStanding[i];
			compact[at[i]] = 0;
		}
		int count = 0;
		for (int i = 0; i < cells; ++i)
		{
			if (compact[i] == 0)
			{
				compact[i] = count++;
			}
		}
		cellCount = count;
		cellIndex = new int[count];
		inStart = new int[count + 1];
		for (int i = 0; i < cells; ++i)
		{
			if (compact[i] >= 0)
			{
				cellIndex[compact[i]] = i;
			}
		}
		for (int i = 0; i < n; ++i)
		{
			inStart[compact[at[i]] + 1]++;
		}
		for (int i = 0; i < count; ++i)
		{
			inStart[i + 1] += inStart[i];
		}
		inFace = new int[n];
		inFlat = new float[n];
		inStanding = new float[n];
		int[] fill = Arrays.copyOf(inStart, count);
		for (int i = 0; i < n; ++i)
		{
			int c = at[i];
			int o = fill[compact[c]]++;
			inFace[o] = face[i];
			inFlat[o] = byFlat[i] / Math.max(1, sumFlat[c]);
			inStanding[o] = byStanding[i] / Math.max(1, sumStanding[c]);
		}
	}
}
