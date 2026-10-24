package com.midonarhel.runelight;

import java.util.Arrays;
import java.util.function.IntConsumer;

/**
 * The global illumination's patches and the links between them, built once per region load on the global
 * illumination's worker (GlobalIllumination); lit by GiRelight.
 *
 * A patch is a piece of surface the size of a tile that holds one color of light: where it stands, which way it
 * faces, its area and its albedo (the average of the voxels it covers). Light is bounced between patches (radiosity),
 * and a pixel reads the patches of the surface it is on (gi.glsl), never the space around it, so what is behind a wall,
 * under a roof or on another floor can't leak into it.
 *
 * There are patches for three kinds of surface, and for no other: trees and objects have none, and read the ground
 * under them.
 *
 * - Floor patches, facing up: one per tile of every floor that has ground there, at the tile's center, at the height
 *   of the ground (the average of its corners). A floor has no patch under a solid object or where a diagonal wall cuts
 *   the tile. They have fixed slots, the first FLOOR_SLOTS ids: floor f's tile (x, z) is patch f * TILES + z * SIZE + x,
 *   whether it has one or not (area 0), which is how the shader finds it.
 * - Roof patches: one per tile that has a roof (see addRoof), in the slots after the floors', facing as the roof's
 *   voxels do and standing at their height; a sloped roof has sloped patches. They get light from the walls and roofs
 *   that face them and give it back like any patch.
 * - Wall patches, horizontal, for every wall of the collision map (WallMap; closed doors included): each tile whose
 *   edge holds one has a face on that edge looking into the tile, so a wall has two faces, one for each side, and a
 *   diagonal wall has one on each side of the tile's diagonal. A face is cut into bands about BAND high, from the floor
 *   up to the wall's real top (found from the voxels), the walls of a floor ending at the next one.
 *
 * Patches are linked once: patch i gets a list of the patches it receives light from, each with the point-to-area form
 * factor times the share of four rays through the voxels, between points spread over the two patches, that are clear,
 * for the patches within REACH that face it and that it faces and can see. Patches in one plane never link, since they face neither one the other; walls facing each other across
 * a gap do. A patch also keeps which of the SKY_SAMPLES directions around it are open to the sky, so its light from the
 * sky can be told at any time without tracing.
 */
final class GiPatches
{
	static final int SIZE = 104;
	static final int FLOORS = 3;
	static final int TILE = 128;
	static final int TILES = SIZE * SIZE;
	// the least two floors' grounds are apart
	static final float FLOOR_GAP = 240;
	// the first ids are the floors' slots (see above), then the roofs': tile (x, z)'s roof is patch FLOOR_SLOTS + z * SIZE + x
	static final int FLOOR_SLOTS = FLOORS * TILES;
	static final int FIXED_SLOTS = FLOOR_SLOTS + TILES;
	// patches a layout holds at most, the slots included; the walls that don't fit are left out
	static final int MAX_PATCHES = 131072;
	// A tile's wall faces, in the order of the wall index texels: the four sides (WallMap.NORTH, EAST, SOUTH, WEST), then
	// the two sides of a diagonal wall, the one whose normal points west (-x) first
	static final int FACES = 6;
	static final int DIAGONAL_WEST = 4, DIAGONAL_EAST = 5;
	// what a wall index texel's second value adds to its number of bands, for a diagonal wall of this kind
	static final int KIND = 8;
	static final int KIND_SW_NE = 1, KIND_NW_SE = 2;
	// A roof is the topmost surface in a tile's voxel columns that faces up (a thin one is taken to), at least this far
	// above the tile's highest floor, and where a wall of the collision map stands within a tile of it, which leaves out
	// trees and the objects on the ground. The least cosine a voxel faces up by to be one, and what the shader is told
	// of a tile that has no roof (a height that nothing is at).
	static final float ROOF_MIN = 160;
	static final float ROOF_COS = 0.25f;
	static final float NO_ROOF = 1e6f;
	// wall faces are cut into bands about this high, MAX_BANDS at most, which is as many as a wall of its greatest height
	// takes
	static final float BAND = 128;
	static final int MAX_BANDS = 5;
	static final float WALL_MAX_HEIGHT = 640;
	// a wall lower than this has no patches
	static final float MIN_WALL = 48;
	// the walls' voxels are looked for at this spacing up the wall, the first this far below the floor, and a wall that
	// has not started by SCAN_START above it is no wall
	static final float SCAN = 32;
	static final float SCAN_START = 48;

	// Whether two patches see each other is told by rays between points spread over them, this far (world units) from their
	// centers along their two directions, so a link's weight goes from none to all as a thing moves across the line
	// between the patches, not at once. The points of a patch pair up with another patch's in these four ways.
	static final float SPREAD = 32;
	private static final float[][] SPREAD_A = {{1, 1}, {-1, -1}, {1, -1}, {-1, 1}};
	private static final float[][] SPREAD_B = {{-1, 1}, {1, -1}, {1, 1}, {-1, -1}};
	// patches link to the others this near, centers
	static final float REACH = 3 * TILE;
	// ... which lie in the tiles within this many of theirs (a patch stands up to half a tile off its tile's center)
	static final int WINDOW = 4;
	// the least cosine a patch faces another by, which keeps patches in one plane, and the ones that graze, from linking
	static final float MIN_COS = 0.02f;
	// The rays between patches, and those that look for the sky, leave a patch from this far off its surface: a floor's
	// is a voxel and a bit up, which puts them above the voxel layer the ground is in, so they don't run along the ground's
	// voxels; a wall's is half a voxel, which puts them in front of it
	static final float FLOOR_LIFT = 72;
	static final float WALL_LIFT = 24;
	// directions around a patch to see the sky in (a bit per direction, in a long), the least cosine with the patch's
	// normal that counts, and how far they look, in voxels; beyond, the sky
	static final int SKY_SAMPLES = 64;
	static final float SKY_MIN_COS = 0.1f;
	static final float SKY_DISTANCE = 32;
	// The voxels' colors are pushed this far from their gray: a voxel averages the colors of everything in it, the
	// texture detail included, which pulls them toward gray.
	static final float ALBEDO_SATURATION = 1.5f;
	// a patch with no voxel to take its color from is this gray
	static final float DEFAULT_ALBEDO = 0.2f;
	// a voxel is ground when its normal points at least this much (cosine) up
	static final float FLOOR_COS = 0.3f;
	// a voxel counts toward a patch's albedo when its normal is at least this much (cosine) toward the patch's
	static final float FACING_COS = 0.2f;
	// an even spread over the whole sphere, to measure the sky with
	static final float[] SKY_RAYS = fibonacci(SKY_SAMPLES);

	private static final int[] SIDES = {WallMap.NORTH, WallMap.EAST, WallMap.SOUTH, WallMap.WEST};

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

	/** Scene data the layout needs, copied on the client thread. */
	static final class Input
	{
		// ground height at each base tile's center per map level, [level][z * SIZE + x], world y
		float[][] ground;
		// the scene's walls, null for none
		WallMap walls;
	}

	/** The links of one patch while they are collected. */
	private static final class Links
	{
		int[] id = new int[16];
		float[] weight = new float[16];
		int n;

		void add(int j, float w)
		{
			if (n == id.length)
			{
				id = Arrays.copyOf(id, n * 2);
				weight = Arrays.copyOf(weight, n * 2);
			}
			id[n] = j;
			weight[n++] = w;
		}
	}

	final GiGrid grid;
	// the patches, floor slots first; a slot without a floor has area 0
	int count;
	// per patch: where it stands (world units, y down), the unit normal, the area, the linear albedo
	float[] position;
	float[] normal;
	float[] area;
	float[] albedo;
	// per patch: its tile (floor * TILES + z * SIZE + x)
	int[] home;
	// per patch: the SKY_SAMPLES directions (SKY_RAYS) that see the sky, a bit each
	long[] sky;
	// per tile (floor * TILES + z * SIZE + x): its wall patches are wallStart[tile] until wallStart[tile + 1]
	int[] wallStart;
	// the patches that light patch i are linkTarget[linkStart[i]..linkStart[i + 1]), each with its form factor
	int[] linkStart, linkTarget;
	float[] linkWeight;
	// What the shader looks patches up by (gi.glsl). RGBA32F texels. floorTexels: one per tile, [z * SIZE + x], the world
	// y of the ground of floors 0, 1 and 2 in rgb (whether they have a patch or not), and of its roof patch in a (NO_ROOF
	// for none). wallTexels: one per tile and wall
	// face, [(floor * TILES + z * SIZE + x) * FACES + face], with the first patch id of the face (its bands follow one
	// another), its number of bands plus KIND times the diagonal kind (0 for a face on a tile's edge), the world y of its
	// base and its bands' height; 0 for a face that has no patches.
	float[] floorTexels, wallTexels;

	private GiPatches(GiGrid grid)
	{
		this.grid = grid;
	}

	/** Spherical Fibonacci directions: count unit vectors spread evenly over the sphere, xyz interleaved. */
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

	static GiPatches build(GiGrid grid, Input in, ParallelFor parallel)
	{
		GiPatches p = new GiPatches(grid);
		p.layout(in);
		p.findSky(parallel);
		p.link(parallel);
		return p;
	}

	/** Whether patch i exists: the floors' empty slots don't. */
	boolean exists(int i)
	{
		return area[i] > 0;
	}

	/** Where rays leave patch i, voxel space (see FLOOR_LIFT). */
	void origin(int i, float[] out)
	{
		origin(i, 0, 0, out);
	}

	/**
	 * Where rays leave the point of patch i that is s1 * SPREAD along its first direction (horizontal for a wall) and
	 * s2 * SPREAD along its second.
	 */
	private void origin(int i, float s1, float s2, float[] out)
	{
		float lift = i < FIXED_SLOTS ? FLOOR_LIFT : WALL_LIFT;
		float nx = normal[i * 3], ny = normal[i * 3 + 1], nz = normal[i * 3 + 2];
		// the first direction is the normal crossed with up for a wall, with z for the rest, the second the normal crossed
		// with the first
		float ux = 0, uy = 0, uz = 0;
		if (i >= FIXED_SLOTS)
		{
			ux = -nz;
			uz = nx;
		}
		else
		{
			ux = -ny;
			uy = nx;
		}
		float ul = (float) Math.sqrt(ux * ux + uy * uy + uz * uz);
		ux /= ul;
		uy /= ul;
		uz /= ul;
		float vx = ny * uz - nz * uy, vy = nz * ux - nx * uz, vz = nx * uy - ny * ux;
		out[0] = (position[i * 3] + nx * lift + (ux * s1 + vx * s2) * SPREAD) / GiGrid.VOXEL;
		out[1] = (position[i * 3 + 1] + ny * lift + (uy * s1 + vy * s2) * SPREAD) / GiGrid.VOXEL - grid.layer0;
		out[2] = (position[i * 3 + 2] + nz * lift + (uz * s1 + vz * s2) * SPREAD) / GiGrid.VOXEL;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Layout

	/** Make the patches: the floors' slots first, so a scene with too many walls loses wall patches first. */
	private void layout(Input in)
	{
		// each floor's ground per tile: a floor is at least FLOOR_GAP above the one below
		float[][] floor = new float[FLOORS][TILES];
		floorTexels = new float[TILES * 4];
		for (int c = 0; c < TILES; ++c)
		{
			floor[0][c] = in.ground[0][c];
			for (int f = 1; f < FLOORS; ++f)
			{
				float up = in.ground.length > f ? in.ground[f][c] : floor[f - 1][c] - FLOOR_GAP;
				floor[f][c] = Math.min(up, floor[f - 1][c] - FLOOR_GAP);
			}
			for (int f = 0; f < FLOORS; ++f)
			{
				floorTexels[c * 4 + f] = floor[f][c];
			}
		}
		position = new float[MAX_PATCHES * 3];
		normal = new float[MAX_PATCHES * 3];
		area = new float[MAX_PATCHES];
		albedo = new float[MAX_PATCHES * 3];
		home = new int[MAX_PATCHES];
		wallTexels = new float[FLOOR_SLOTS * FACES * 4];
		wallStart = new int[FLOOR_SLOTS + 1];
		count = FIXED_SLOTS;
		WallMap walls = in.walls;
		int offset = walls != null ? walls.offset : 0;
		for (int f = 0; f < FLOORS; ++f)
		{
			for (int z = 0; z < SIZE; ++z)
			{
				for (int x = 0; x < SIZE; ++x)
				{
					int c = z * SIZE + x;
					// a diagonal wall's tile has no floor patch: it is two halves, with a wall between
					boolean blocked = walls != null && (walls.solid(f, x + offset, z + offset)
						|| WallMap.split(walls.get(f, x + offset, z + offset)));
					if (!blocked && hasFloor(x, z, floor[f][c]))
					{
						addFloor(f * TILES + c, x, z, floor[f][c]);
					}
				}
			}
		}
		for (int z = 0; z < SIZE; ++z)
		{
			for (int x = 0; x < SIZE; ++x)
			{
				floorTexels[(z * SIZE + x) * 4 + 3] = NO_ROOF;
				if (walls != null && nearWall(walls, x, z))
				{
					addRoof(x, z, floor);
				}
			}
		}
		for (int f = 0; f < FLOORS; ++f)
		{
			for (int z = 0; z < SIZE; ++z)
			{
				for (int x = 0; x < SIZE; ++x)
				{
					wallStart[f * TILES + z * SIZE + x] = count;
					if (walls != null)
					{
						faces(walls, floor, f, x, z);
					}
				}
			}
		}
		wallStart[FLOOR_SLOTS] = count;
		position = Arrays.copyOf(position, count * 3);
		normal = Arrays.copyOf(normal, count * 3);
		area = Arrays.copyOf(area, count);
		albedo = Arrays.copyOf(albedo, count * 3);
		home = Arrays.copyOf(home, count);
	}

	private void addFloor(int id, int x, int z, float ground)
	{
		position[id * 3] = (x + 0.5f) * TILE;
		position[id * 3 + 1] = ground;
		position[id * 3 + 2] = (z + 0.5f) * TILE;
		normal[id * 3 + 1] = -1;
		area[id] = TILE * TILE;
		home[id] = id;
		int y0 = Math.floorDiv((int) (ground - GiGrid.VOXEL), GiGrid.VOXEL) - grid.layer0;
		int y1 = Math.floorDiv((int) (ground + GiGrid.VOXEL / 2), GiGrid.VOXEL) - grid.layer0;
		averageAlbedo(id, x * 2, x * 2 + 1, y0, y1, z * 2, z * 2 + 1, 0, -1, 0);
	}

	/** Whether a wall, a solid object or a diagonal wall of any floor stands in the tile or one next to it. */
	private static boolean nearWall(WallMap walls, int x, int z)
	{
		for (int f = 0; f < FLOORS; ++f)
		{
			for (int dz = -1; dz <= 1; ++dz)
			{
				for (int dx = -1; dx <= 1; ++dx)
				{
					int ex = x + dx + walls.offset, ez = z + dz + walls.offset;
					if (walls.get(f, ex, ez) != 0 || walls.solid(f, ex, ez))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Tile (x, z)'s roof patch, if it has a roof: at the tile's center, at the height of the topmost voxel surface facing
	 * up in its columns (the average of the voxels of that layer and the one below), at least ROOF_MIN above the ground,
	 * facing the way they do, so a sloped roof's patch is sloped. Its tile's height for the shader goes in floorTexels'
	 * fourth value. It takes the place of the floor patch of an upper floor that this surface is at the height of.
	 */
	private void addRoof(int x, int z, float[][] floor)
	{
		int c = z * SIZE + x;
		float ground = floor[0][c];
		int last = Math.min(grid.ny - 1, Math.floorDiv((int) (ground - ROOF_MIN), GiGrid.VOXEL) - grid.layer0);
		for (int y = 0; y <= last; ++y)
		{
			float[] n = new float[3];
			float up = 0, height = 0;
			int found = 0;
			for (int layer = y; layer <= y + 1; ++layer)
			{
				for (int vx = x * 2; vx < x * 2 + 2; ++vx)
				{
					for (int vz = z * 2; vz < z * 2 + 2; ++vz)
					{
						if (!grid.occupied(vx, layer, vz))
						{
							continue;
						}
						int cell = grid.cells[grid.index(vx, layer, vz)];
						float[] cn = GiGrid.normal(cell);
						if (GiGrid.twoSided(cell))
						{
							cn = new float[]{0, -1, 0};
						}
						else if (cn[1] >= -ROOF_COS)
						{
							continue;
						}
						for (int k = 0; k < 3; ++k)
						{
							n[k] += cn[k];
						}
						height += (layer + grid.layer0 + 0.5f) * GiGrid.VOXEL;
						found++;
					}
				}
			}
			if (found == 0)
			{
				continue;
			}
			float length = (float) Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
			if (length < 1e-3f || n[1] / length >= -ROOF_COS)
			{
				continue;
			}
			int id = FLOOR_SLOTS + c;
			for (int k = 0; k < 3; ++k)
			{
				normal[id * 3 + k] = n[k] / length;
			}
			position[id * 3] = (x + 0.5f) * TILE;
			position[id * 3 + 1] = height / found;
			position[id * 3 + 2] = (z + 0.5f) * TILE;
			area[id] = TILE * TILE / -normal[id * 3 + 1];
			home[id] = c;
			averageAlbedo(id, x * 2, x * 2 + 1, y, y + 1, z * 2, z * 2 + 1, normal[id * 3], normal[id * 3 + 1],
				normal[id * 3 + 2]);
			floorTexels[c * 4 + 3] = height / found;
			// a surface that is a floor of the floors above, by its height, is this roof (the upper floors are where the
			// voxels have a surface facing up at their height, so a roof at about that height has one too)
			for (int f = 1; f < FLOORS; ++f)
			{
				if (Math.abs(position[id * 3 + 1] - floor[f][c]) <= GiGrid.VOXEL)
				{
					area[f * TILES + c] = 0;
				}
			}
			return;
		}
	}

	/** The wall faces of tile (x, z) on floor f: one on each edge with a wall, and two when a diagonal wall crosses it. */
	private void faces(WallMap walls, float[][] floor, int f, int x, int z)
	{
		int ex = x + walls.offset, ez = z + walls.offset;
		int tile = f * TILES + z * SIZE + x;
		if (walls.solid(f, ex, ez))
		{
			return;
		}
		float ground = floor[f][z * SIZE + x];
		// the walls of a floor end at the next one, which the lookup takes anything above to be on
		float storey = f + 1 < FLOORS ? ground - floor[f + 1][z * SIZE + x] : WALL_MAX_HEIGHT;
		float cx = (x + 0.5f) * TILE, cz = (z + 0.5f) * TILE;
		for (int face = 0; face < SIDES.length; ++face)
		{
			int side = SIDES[face];
			if (!walls.edge(f, ex, ez, side))
			{
				continue;
			}
			// the face looks into the tile, away from its wall
			float outX = side == WallMap.EAST ? -1 : side == WallMap.WEST ? 1 : 0;
			float outZ = side == WallMap.NORTH ? -1 : side == WallMap.SOUTH ? 1 : 0;
			// the voxels on both sides of the edge, along the tile
			int edgeX = Math.round((cx - outX * TILE / 2) / GiGrid.VOXEL), edgeZ = Math.round((cz - outZ * TILE / 2) / GiGrid.VOXEL);
			int x0 = outX != 0 ? edgeX - 1 : 2 * x, x1 = outX != 0 ? edgeX : 2 * x + 1;
			int z0 = outZ != 0 ? edgeZ - 1 : 2 * z, z1 = outZ != 0 ? edgeZ : 2 * z + 1;
			float top = Math.min(standing(ground, x0, x1, z0, z1), storey);
			addFace(tile, face, 0, cx - outX * TILE / 2, cz - outZ * TILE / 2, outX, outZ, TILE, ground, top, x0, x1, z0, z1);
		}
		int bits = walls.get(f, ex, ez);
		if (WallMap.split(bits))
		{
			float top = Math.min(standing(ground, 2 * x, 2 * x + 1, 2 * z, 2 * z + 1), storey);
			// the two sides of the diagonal: northwest and southeast of "/", southwest and northeast of "\"
			float d = 0.70710678f;
			boolean slash = (bits & WallMap.DIAG_SW_NE) != 0;
			int kind = slash ? KIND_SW_NE : KIND_NW_SE;
			addFace(tile, DIAGONAL_WEST, kind, cx, cz, -d, slash ? d : -d, TILE * 1.41421356f, ground, top, 2 * x, 2 * x + 1,
				2 * z, 2 * z + 1);
			addFace(tile, DIAGONAL_EAST, kind, cx, cz, d, slash ? -d : d, TILE * 1.41421356f, ground, top, 2 * x, 2 * x + 1,
				2 * z, 2 * z + 1);
		}
	}

	/**
	 * Add the bands of a wall face: at (px, pz), facing (nx, nz), length wide, up from ground to ground - top, the
	 * albedo from the voxels in columns x0..x1, z0..z1.
	 */
	private void addFace(int tile, int face, int kind, float px, float pz, float nx, float nz, float length, float ground,
		float top, int x0, int x1, int z0, int z1)
	{
		if (top < MIN_WALL)
		{
			return;
		}
		int bands = Math.max(1, Math.min(MAX_BANDS, Math.round(top / BAND)));
		if (count + bands > MAX_PATCHES)
		{
			return;
		}
		float height = top / bands;
		int first = count;
		for (int k = 0; k < bands; ++k)
		{
			int id = count++;
			position[id * 3] = px;
			position[id * 3 + 1] = ground - (k + 0.5f) * height;
			position[id * 3 + 2] = pz;
			normal[id * 3] = nx;
			normal[id * 3 + 2] = nz;
			area[id] = length * height;
			home[id] = tile;
			int y0 = (int) Math.floor((ground - (k + 1) * height) / GiGrid.VOXEL) - grid.layer0;
			int y1 = (int) Math.floor((ground - k * height - 1) / GiGrid.VOXEL) - grid.layer0;
			averageAlbedo(id, x0, x1, y0, y1, z0, z1, nx, 0, nz);
		}
		int at = (tile * FACES + face) * 4;
		wallTexels[at] = first;
		wallTexels[at + 1] = bands + kind * KIND;
		wallTexels[at + 2] = ground;
		wallTexels[at + 3] = height;
	}

	/**
	 * Set patch id's albedo to the average of the voxels in the block of columns x0..x1, z0..z1 and layers y0..y1 whose
	 * normals face (nx, ny, nz) or are two-sided (a thin wall), or of all of them if none does; DEFAULT_ALBEDO if the
	 * block is empty.
	 */
	private void averageAlbedo(int id, int x0, int x1, int y0, int y1, int z0, int z1, float nx, float ny, float nz)
	{
		float[] sum = new float[3];
		int n = 0;
		for (int pass = 0; pass < 2 && n == 0; ++pass)
		{
			for (int y = y0; y <= y1; ++y)
			{
				for (int z = z0; z <= z1; ++z)
				{
					for (int x = x0; x <= x1; ++x)
					{
						if (!grid.occupied(x, y, z))
						{
							continue;
						}
						int cell = grid.cells[grid.index(x, y, z)];
						if (pass == 0 && !GiGrid.twoSided(cell))
						{
							float[] c = GiGrid.normal(cell);
							if (c[0] * nx + c[1] * ny + c[2] * nz < FACING_COS)
							{
								continue;
							}
						}
						for (int k = 0; k < 3; ++k)
						{
							sum[k] += GiGrid.albedo(cell, k);
						}
						n++;
					}
				}
			}
		}
		if (n == 0)
		{
			Arrays.fill(albedo, id * 3, id * 3 + 3, DEFAULT_ALBEDO);
			return;
		}
		saturate(sum[0] / n, sum[1] / n, sum[2] / n, albedo, id * 3);
	}

	/** Linear color (r, g, b) moved ALBEDO_SATURATION times as far from its luminance, same luminance, into out[at..]. */
	static void saturate(float r, float g, float b, float[] out, int at)
	{
		float l = GiRelight.luminance(r, g, b);
		out[at] = Math.max(0, l + (r - l) * ALBEDO_SATURATION);
		out[at + 1] = Math.max(0, l + (g - l) * ALBEDO_SATURATION);
		out[at + 2] = Math.max(0, l + (b - l) * ALBEDO_SATURATION);
	}

	/**
	 * How high geometry stands above ground (world units; at most WALL_MAX_HEIGHT, a little less) in the voxel columns
	 * x0..x1, z0..z1, going up from the ground while some voxel of the columns is filled; 0 if none is.
	 */
	private float standing(float ground, int x0, int x1, int z0, int z1)
	{
		float top = 0;
		boolean started = false;
		for (float h = -SCAN / 2; h <= WALL_MAX_HEIGHT; h += SCAN)
		{
			int y = (int) Math.floor((ground - h) / GiGrid.VOXEL) - grid.layer0;
			boolean filled = false;
			for (int vx = x0; vx <= x1 && !filled; ++vx)
			{
				for (int vz = z0; vz <= z1 && !filled; ++vz)
				{
					filled = grid.occupied(vx, y, vz);
				}
			}
			if (filled)
			{
				top = h;
				started = true;
			}
			else if (started || h > SCAN_START)
			{
				break;
			}
		}
		return top;
	}

	/**
	 * Whether a tile has a floor at height ground (world y): some voxel facing up in its tile within a voxel of it (a
	 * wall's, thin or facing sideways, is not ground). Without, as in the open air of the upper floors, there is nothing
	 * to put a patch on.
	 */
	private boolean hasFloor(int x, int z, float ground)
	{
		int y0 = Math.floorDiv((int) (ground - GiGrid.VOXEL), GiGrid.VOXEL) - grid.layer0;
		int y1 = Math.floorDiv((int) (ground + GiGrid.VOXEL / 2), GiGrid.VOXEL) - grid.layer0;
		for (int vx = x * 2; vx < x * 2 + 2; ++vx)
		{
			for (int vz = z * 2; vz < z * 2 + 2; ++vz)
			{
				for (int y = y0; y <= y1; ++y)
				{
					if (grid.occupied(vx, y, vz) && facesUp(grid.cells[grid.index(vx, y, vz)]))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	private static boolean facesUp(int cell)
	{
		return !GiGrid.twoSided(cell) && GiGrid.normal(cell)[1] < -FLOOR_COS;
	}

	// ---------------------------------------------------------------------------------------------------------
	// Sky and links

	/** Find the directions around each patch that see the sky. */
	private void findSky(ParallelFor parallel)
	{
		sky = new long[count];
		parallel.run(count, i ->
		{
			if (!exists(i))
			{
				return;
			}
			float[] o = new float[3];
			origin(i, o);
			long open = 0;
			for (int r = 0; r < SKY_SAMPLES; ++r)
			{
				float dx = SKY_RAYS[r * 3], dy = SKY_RAYS[r * 3 + 1], dz = SKY_RAYS[r * 3 + 2];
				if (dx * normal[i * 3] + dy * normal[i * 3 + 1] + dz * normal[i * 3 + 2] < SKY_MIN_COS)
				{
					continue;
				}
				if (grid.trace(o[0], o[1], o[2], dx, dy, dz, SKY_DISTANCE, true, i * SKY_SAMPLES + r) < 0)
				{
					open |= 1L << r;
				}
			}
			sky[i] = open;
		});
	}

	/** Find what lights each patch: the patches around it that face it and see it, with their form factors. */
	private void link(ParallelFor parallel)
	{
		Links[] links = new Links[count];
		parallel.run(count, i ->
		{
			if (!exists(i))
			{
				return;
			}
			Links out = new Links();
			int tile = home[i] % TILES;
			int tx = tile % SIZE, tz = tile / SIZE;
			for (int f = 0; f < FLOORS; ++f)
			{
				for (int z = Math.max(0, tz - WINDOW); z <= Math.min(SIZE - 1, tz + WINDOW); ++z)
				{
					for (int x = Math.max(0, tx - WINDOW); x <= Math.min(SIZE - 1, tx + WINDOW); ++x)
					{
						int slot = f * TILES + z * SIZE + x;
						if (f == 0)
						{
							connect(i, FLOOR_SLOTS + z * SIZE + x, out);
						}
						connect(i, slot, out);
						for (int j = wallStart[slot]; j < wallStart[slot + 1]; ++j)
						{
							connect(i, j, out);
						}
					}
				}
			}
			links[i] = out;
		});
		linkStart = new int[count + 1];
		for (int i = 0; i < count; ++i)
		{
			linkStart[i + 1] = linkStart[i] + (links[i] != null ? links[i].n : 0);
		}
		linkTarget = new int[linkStart[count]];
		linkWeight = new float[linkStart[count]];
		for (int i = 0; i < count; ++i)
		{
			if (links[i] != null)
			{
				System.arraycopy(links[i].id, 0, linkTarget, linkStart[i], links[i].n);
				System.arraycopy(links[i].weight, 0, linkWeight, linkStart[i], links[i].n);
			}
		}
	}

	/**
	 * Link patch j to patch i if they face each other, are within REACH and see each other. The weight is the
	 * point-to-area form factor of j seen from i, cos_i * cos_j * A_j / (pi r^2 + A_j): the added area keeps pairs
	 * that are close, relative to their size, from weighing more than the light they can send.
	 */
	private void connect(int i, int j, Links out)
	{
		if (i == j || !exists(j))
		{
			return;
		}
		float dx = position[j * 3] - position[i * 3], dy = position[j * 3 + 1] - position[i * 3 + 1];
		float dz = position[j * 3 + 2] - position[i * 3 + 2];
		float r2 = dx * dx + dy * dy + dz * dz;
		if (r2 > REACH * REACH || r2 < 1)
		{
			return;
		}
		float r = (float) Math.sqrt(r2);
		float cosI = (normal[i * 3] * dx + normal[i * 3 + 1] * dy + normal[i * 3 + 2] * dz) / r;
		float cosJ = -(normal[j * 3] * dx + normal[j * 3 + 1] * dy + normal[j * 3 + 2] * dz) / r;
		if (cosI <= MIN_COS || cosJ <= MIN_COS)
		{
			return;
		}
		float seen = seen(Math.min(i, j), Math.max(i, j));
		if (seen > 0)
		{
			out.add(j, seen * cosI * cosJ * area[j] / ((float) Math.PI * r2 + area[j]));
		}
	}

	/**
	 * The share (0 to 1) of the rays between points spread over patches a and b that reach one from the other. The voxels
	 * next to the one b's point is in don't stop a ray (they may be the ones the wall b is on fills), nor does the one it
	 * starts in. The pair is always traced from the lower id, so both of them agree.
	 */
	private float seen(int a, int b)
	{
		float[] oa = new float[3], ob = new float[3];
		int clear = 0;
		for (int k = 0; k < SPREAD_A.length; ++k)
		{
			origin(a, SPREAD_A[k][0], SPREAD_A[k][1], oa);
			origin(b, SPREAD_B[k][0], SPREAD_B[k][1], ob);
			float dx = ob[0] - oa[0], dy = ob[1] - oa[1], dz = ob[2] - oa[2];
			float length = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
			if (length < 1e-4f)
			{
				clear++;
				continue;
			}
			int hit = grid.trace(oa[0], oa[1], oa[2], dx / length, dy / length, dz / length, length, true,
				((a * 0x9E3779B1 + b) * 4 + k) & 0x7fffffff);
			if (hit < 0 || touches(hit, ob))
			{
				clear++;
			}
		}
		return (float) clear / SPREAD_A.length;
	}

	/** Whether voxel index hit is the voxel at voxel space point o or one next to it and filled. */
	private boolean touches(int hit, float[] o)
	{
		int ex = (int) Math.floor(o[0]), ey = (int) Math.floor(o[1]), ez = (int) Math.floor(o[2]);
		for (int y = ey - 1; y <= ey + 1; ++y)
		{
			for (int z = ez - 1; z <= ez + 1; ++z)
			{
				for (int x = ex - 1; x <= ex + 1; ++x)
				{
					if (grid.occupied(x, y, z) && hit == grid.index(x, y, z))
					{
						return true;
					}
				}
			}
		}
		return false;
	}

	/**
	 * Ground height at each base tile's center, [level][z * SIZE + x], from the scene's corner heights
	 * ([level][extended x][extended z]).
	 */
	static float[][] groundHeights(int[][][] heights, int offset)
	{
		int levels = heights.length;
		float[][] out = new float[levels][SIZE * SIZE];
		for (int l = 0; l < levels; ++l)
		{
			int[][] h = heights[l];
			for (int z = 0; z < SIZE; ++z)
			{
				for (int x = 0; x < SIZE; ++x)
				{
					int ex = x + offset, ez = z + offset;
					if (ex + 1 < h.length && ez + 1 < h[ex].length)
					{
						out[l][z * SIZE + x] = (h[ex][ez] + h[ex + 1][ez] + h[ex][ez + 1] + h[ex + 1][ez + 1]) / 4f;
					}
				}
			}
		}
		return out;
	}
}
