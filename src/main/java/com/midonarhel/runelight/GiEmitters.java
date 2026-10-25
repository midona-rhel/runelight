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
 * What a pixel reads is kept in three fields of the volume, by how steeply the surfaces that gave it lie: upright (walls),
 * slanted at 45 degrees (roofs, stairs, steep ground) and level (floors, ground). A face gives its colour to the
 * two fields its slope lies between, by the angle (bands), and a pixel reads the fields by how unlike its own slope their
 * slopes are, the sine squared of the angle between (gi.glsl): a floor reads none of the level colour and all the upright,
 * a wall none of the upright and all the level, a roof half of each and none of the slanted, and a slope between a part of
 * each that changes smoothly with it. A surface then never reads the colour it gives itself, and a stair's treads and risers,
 * whose voxels face diagonally, read the same. Which way along the ground a surface faces is not told, so a wall takes no
 * colour from the walls beside it.
 *
 * What a face puts into a voxel is kept as a list per voxel, to be added up on every relight; where the weights of
 * what a voxel is given in a field add up to more than one they are scaled to one, so a corner is not brighter than a
 * wall.
 */
final class GiEmitters
{
	// how far (world units) from a surface its colour reaches, from the full strength at the surface to none; the shape
	// in between is profile
	static final float REACH = 192;
	// the least cosine a voxel's normal has toward a neighbour for that neighbour to be given its colour
	static final float FACING_COS = 0.2f;
	// directions around a face to see the sky in, the least cosine with its normal that counts, and how far they look,
	// in voxels; beyond, the sky
	static final int SKY_SAMPLES = 64;
	static final float SKY_MIN_COS = 0.1f;
	static final float SKY_DISTANCE = 32;
	// surfaces this many degrees from upright, from 45 degrees or from level lie at it exactly
	static final float SLOPE_SNAP = 15;
	// weights below this are left out
	private static final float MIN_WEIGHT = 0.002f;
	// A filled voxel is given a field's colour only if the share of its surface's colour that goes to the field is at least
	// this: a voxel that holds a little of it would read as the air next to it, but darker, to the filter that reads
	// between them (gi.glsl), so a wall a little off true does not dim the colour at its foot.
	private static final float SOLID_SHARE = 0.25f;
	// the voxels around a face's own, across its plane: along the first of the two axes across it, along the second; the
	// weight of each in blurring over them (GiRelight), and 4 for the face itself
	static final int[][] BESIDE = {{-1, -1}, {0, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {0, 1}, {1, 1}};
	static final float[] BESIDE_WEIGHT = {1, 2, 1, 2, 2, 1, 2, 1};
	static final float BESIDE_SELF = 4;
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
	// surface gives at unit luminance (its albedo's); and the share of the directions it faces that see the sky
	int faceCount;
	int[] faceSolid;
	byte[] faceDirection;
	int[] faceCell;
	float[] faceNormal;
	float[] faceChroma;
	float[] faceSky;
	// per face, the share of its colour that goes to each field (bands)
	float[] faceShare;
	// per face, the faces beside it in the plane of its own that look the same way: the eight voxels around its own on
	// its layer (BESIDE), the face of each or -1 for none
	int[] faceBeside;

	// per voxel that is given any colour, cellIndex its index in the grid; the faces that give it some are the entries
	// inStart[i] to inStart[i + 1], each its face and its weight in the three fields (inWeight[entry * 3 + field], the
	// fields in the order upright, slanted, level)
	int cellCount;
	int[] cellIndex;
	int[] inStart;
	int[] inFace;
	float[] inWeight;

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
		faceShare = new float[n * 3];
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
			bands(normal[1], faceShare, f * 3);
			GiRelight.chroma(GiGrid.albedo(cell, 0), GiGrid.albedo(cell, 1), GiGrid.albedo(cell, 2), faceChroma, f * 3);
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

		// the faces beside each, found among the faces in order of their voxel and direction
		int[] key = new int[n];
		for (int f = 0; f < n; ++f)
		{
			key[f] = faceSolid[f] * DIRECTIONS.length + faceDirection[f];
		}
		faceBeside = new int[n * BESIDE.length];
		parallel.run(n, f ->
		{
			int idx = faceSolid[f];
			int[] at = {grid.cellX(idx), grid.cellY(idx), grid.cellZ(idx)};
			int[] size = {grid.nx, grid.ny, grid.nz};
			int across = faceDirection[f] / 2;
			int u = (across + 1) % 3, v = (across + 2) % 3;
			for (int k = 0; k < BESIDE.length; ++k)
			{
				int[] to = at.clone();
				to[u] += BESIDE[k][0];
				to[v] += BESIDE[k][1];
				int found = -1;
				if (to[u] >= 0 && to[u] < size[u] && to[v] >= 0 && to[v] < size[v])
				{
					int i = Arrays.binarySearch(key, grid.index(to[0], to[1], to[2]) * DIRECTIONS.length + faceDirection[f]);
					found = Math.max(i, -1);
				}
				faceBeside[f * BESIDE.length + k] = found;
			}
		});
	}

	/** The list per voxel: where each face puts its colour, and how much. */
	private void spread()
	{
		int cells = grid.cells.length;
		int[] face = new int[4096];
		int[] at = new int[4096];
		float[] along = new float[4096];
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
					along = Arrays.copyOf(along, n * 2);
				}
				face[n] = f;
				at[n] = grid.index(cx, cy, cz);
				along[n] = w;
				n++;
			}
		}

		// the voxels that are given anything, in order of their index
		int[] compact = new int[cells];
		Arrays.fill(compact, -1);
		for (int i = 0; i < n; ++i)
		{
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
		// what each voxel is given in each field in all, to scale it to one at most
		float[] sum = new float[count * 3];
		for (int i = 0; i < n; ++i)
		{
			inStart[compact[at[i]] + 1]++;
			for (int a = 0; a < 3; ++a)
			{
				sum[compact[at[i]] * 3 + a] += given(face[i], a, along[i], grid.cells[at[i]] != 0);
			}
		}
		for (int i = 0; i < count; ++i)
		{
			inStart[i + 1] += inStart[i];
		}
		inFace = new int[n];
		inWeight = new float[n * 3];
		int[] fill = Arrays.copyOf(inStart, count);
		for (int i = 0; i < n; ++i)
		{
			int c = compact[at[i]];
			int o = fill[c]++;
			inFace[o] = face[i];
			for (int a = 0; a < 3; ++a)
			{
				inWeight[o * 3 + a] = given(face[i], a, along[i], grid.cells[at[i]] != 0) / Math.max(1, sum[c * 3 + a]);
			}
		}
	}

	/**
	 * How a surface with normal y component ny divides its colour among the fields (upright, slanted, level) into
	 * out[at..]: a surface lying at 0, 45 or 90 degrees from upright gives it to the field of that slope, and in between
	 * to the two, in proportion to the angle. A surface within SLOPE_SNAP of a field's slope lies at it exactly, so the
	 * normals of the voxels, which are a little off true, give nothing to the others.
	 */
	static void bands(float ny, float[] out, int at)
	{
		float slope = (float) Math.toDegrees(Math.asin(Math.min(1, Math.abs(ny))));
		boolean upper = slope >= 45;
		float snap = SLOPE_SNAP / 45;
		float t = Math.max(0, Math.min(1, ((upper ? slope - 45 : slope) / 45 - snap) / (1 - 2 * snap)));
		out[at] = upper ? 0 : 1 - t;
		out[at + 1] = upper ? 1 - t : t;
		out[at + 2] = upper ? t : 0;
	}

	/** How much of face f's colour goes to field a. */
	float share(int f, int a)
	{
		return faceShare[f * 3 + a];
	}

	// what face f puts into the field of axis a of a voxel (filled or not), where it reaches it with weight w
	private float given(int f, int a, float w, boolean filled)
	{
		float share = share(f, a);
		return filled && share < SOLID_SHARE ? 0 : w * share;
	}
}
