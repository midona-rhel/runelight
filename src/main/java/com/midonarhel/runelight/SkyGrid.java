package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * How much of the sky the scene lets through, ray traced through its voxels (GiGrid) for the skylight
 * (skylight_ao.glsl). Values sit at the corners of the base scene's tiles, like the game's terrain heights, at
 * HEIGHTS above the ground of each of FLOORS floors (the game's levels), and are directional: the
 * sky seen toward up and the four sides (+x, -x, +z, -z), each the share of the directions above the horizon, cosine weighted
 * around that axis, whose rays get out (leave the grid, or go MAX_DISTANCE without meeting anything). Under the open
 * sky every one is 1; a wall under eaves sees the sky sideways while the ground under them doesn't see it overhead.
 *
 * Corners lie on tile edges, which is where walls stand, so a corner has no one value: every tile keeps its own four
 * corner values (quadrants), each traced from a point inside that tile near the corner. The quadrants around a corner
 * that reach each other without crossing a wall (WallMap edges; a diagonal wall splits its tile into halves) are
 * traced together, their rays shared out among their points, and get the same value, so the light is continuous
 * wherever no wall is; quadrants a wall separates keep their own. A pixel reads only its own tile's four values.
 * A diagonal tile keeps three actual corners for each half, with independent values on the two sides of the wall.
 * Height samples exist above every tile; actual scene floor flags identify where vertical blending must stop.
 * One additional exterior layer follows the top of the captured geometry, above roofs and foliage.
 *
 * Tracing is amortized: work is a corner at one height (a unit, all its quadrants), units are marked dirty when the
 * voxels or the walls around them change, and trace() works through the dirty ones nearest the player first, up to
 * a budget of rays. The rays of a unit are always the same (a fixed set turned by a per-unit angle, and foliage
 * stopping the same rays), so a unit traced again over unchanged voxels gets the same values: nothing flickers.
 *
 * The 3D RGBA16F texture has four horizontal parts: half 0's up/+x/-x/+z and -z, then the same for half 1.
 * Each tile is a 2x2 block of actual corners. Slice 0 stores three floor heights and packed diagonal kinds in
 * part 0, actual floor flags in part 1, room floor heights in part 2 and protected room bits in part 3.
 * Slice 1 + floor * HEIGHTS.length + height stores visibility; the final slice stores exterior visibility.
 * The -z parts' spare channels store the sample's x/z offset from its corner and its world y (g/b/a = x/y/z).
 *
 * Not thread safe: one thread (SkyGridMap's worker) calls everything; the texels and changes are read between calls.
 * World coordinates are the base scene's local ones, tile 0 at 0, y down.
 */
final class SkyGrid
{
	static final int SIZE = GiGrid.SCENE_TILES;
	static final int CORNERS = SIZE + 1;
	static final int FLOORS = 3;
	// the least two floors' grounds are apart
	static final float FLOOR_GAP = 240;
	// heights of the values above each floor's ground: between a storey's floor and ceiling voxels (a storey is about
	// 240 high, a voxel 64), low enough for the floor, high enough for the tops of walls
	static final float[] HEIGHTS = {40, 112, 184};
	static final int LEVELS = FLOORS * HEIGHTS.length + 1;
	// One exterior sample just above the captured geometry at each corner.
	static final int SURFACE_LEVEL = LEVELS - 1;
	static final float TILE = 128;
	// rays per group of quadrants: an even spread over the sky's half of the sphere, each counting for every lobe it
	// lies in (the one up and up to two sides)
	static final int RAYS = 32;
	static final int ROTATIONS = 16;
	// rays that go this many voxels without a hit see the sky: 16 tiles
	static final float MAX_DISTANCE = 32;
	// tiles around a changed voxel whose rays may have met it
	static final int REACH = (int) Math.ceil(MAX_DISTANCE * GiGrid.VOXEL / TILE);
	static final int LOBES = 5;
	// corners per side of the blocks tracing goes by
	static final int BLOCK = 4;
	static final int TEX_W = 8 * SIZE, TEX_H = 2 * SIZE, TEX_D = 1 + LEVELS;

	private static final short HALF_ONE = GlobalIllumination.toHalf(1);
	// where a quadrant's point may move when its voxel is taken: up and down (world units, y down)
	private static final float[] NUDGE = {0, -24, 24, -48, 48};
	// the least height above the ground a point may be moved down to
	private static final float MIN_HEIGHT = 8;
	// voxels: a blocker up to NEAR_BLOCKER away hides its share of the sky in full; from there to FAR_BLOCKER it hides
	// less and less, down to letting FAR_OPEN of it through
	private static final float NEAR_BLOCKER = 3;
	private static final float FAR_BLOCKER = 24;
	private static final float FAR_OPEN = 0.6f;

	// [rotation][ray * 3 + axis], and each ray's weight in each lobe, [rotation][ray * LOBES + lobe], a lobe's adding to 1
	private static final float[][] DIRECTIONS = new float[ROTATIONS][];
	private static final float[][] WEIGHTS = new float[ROTATIONS][];

	static
	{
		// the upper half of an even spread over the sphere (y down)
		float[] sphere = GiGrid.fibonacci(2 * RAYS);
		float[] base = java.util.Arrays.copyOfRange(sphere, RAYS * 3, RAYS * 6);
		for (int r = 0; r < ROTATIONS; ++r)
		{
			double a = 2 * Math.PI * r / ROTATIONS;
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			float[] d = new float[RAYS * 3];
			float[] w = new float[RAYS * LOBES];
			float[] sum = new float[LOBES];
			for (int i = 0; i < RAYS; ++i)
			{
				float x = c * base[i * 3] - s * base[i * 3 + 2], y = base[i * 3 + 1], z = s * base[i * 3] + c * base[i * 3 + 2];
				d[i * 3] = x;
				d[i * 3 + 1] = y;
				d[i * 3 + 2] = z;
				// up is -y
				float[] lobe = {Math.max(-y, 0), Math.max(x, 0), Math.max(-x, 0), Math.max(z, 0), Math.max(-z, 0)};
				for (int k = 0; k < LOBES; ++k)
				{
					w[i * LOBES + k] = lobe[k];
					sum[k] += lobe[k];
				}
			}
			for (int i = 0; i < RAYS * LOBES; ++i)
			{
				w[i] /= sum[i % LOBES];
			}
			DIRECTIONS[r] = d;
			WEIGHTS[r] = w;
		}
	}

	final int resolution, size, corners, textureWidth, textureHeight, reach;
	final float spacing;

	SkyGrid() { this(1); }

	SkyGrid(int resolution)
	{
		this.resolution = resolution;
		size = SIZE * resolution;
		corners = size + 1;
		spacing = TILE / resolution;
		textureWidth = 8 * size;
		textureHeight = 2 * size;
		reach = REACH * resolution;
		texels = ByteBuffer.allocateDirect(textureWidth * textureHeight * TEX_D * 4 * Short.BYTES)
			.order(ByteOrder.nativeOrder()).asShortBuffer();
		ground = new float[FLOORS * corners * corners];
		floors = new byte[size * size];
		active = new boolean[LEVELS * corners * corners];
		dirty = new boolean[active.length];
	}

	final ShortBuffer texels;

	private GiGrid grid;
	SkyTerrain terrain;
	private int baseX, baseY;
	private boolean instance;
	private WallMap walls;
	// the grounds of the floors at every corner, [floor][z * corners + x], world y
	private final float[] ground;
	// Actual scene floors per tile (z * size + x), one bit per plane; the ground floor always
	private final byte[] floors;
	// units to trace, [(level * corners + z) * corners + x]
	final boolean[] active;
	int nearbyUnits, supportUnits;
	final boolean[] dirty;
	private int dirtyCount;
	// rings of corners around the last trace's center with nothing left to trace
	private int clearRings;
	// texels written since takeChanges: tiles x0..x1 by z0..z1 (none while x1 < x0), slices s0..s1, or everything
	private int changeX0, changeZ0, changeX1 = -1, changeZ1, changeS0, changeS1;
	private boolean changedAll;

	// scratch for a unit
	private final float[] start = new float[8 * 3];
	private final float[] acc = new float[LOBES];
	private final short[] value = new short[LOBES];
	private final int[] group = new int[8];
	private final int[] members = new int[8];
	private final int[] bits = new int[4];

	int baseX()
	{
		return baseX;
	}

	int baseY()
	{
		return baseY;
	}

	boolean instance()
	{
		return instance;
	}

	int dirtyCount()
	{
		return dirtyCount;
	}

	/** Rings of corners around where the last trace() started that are all traced. */
	int clearRings()
	{
		return clearRings;
	}

	/**
	 * The voxels were built again. A new place starts over, every value open sky until traced; the same scene moved
	 * (the player walked into the next region) keeps its values where the two overlap and traces everything again; the
	 * same place traces again around the voxels that changed (a door, a tree cut down).
	 *
	 * @param heights the scene's corner heights, [level][extended x][extended z]
	 * @param sceneFloors actual floor planes per extended tile, from BuildingLights.floorTiles
	 * @param offset extended tiles before local tile 0
	 * @return whether it starts over
	 */
	boolean setGrid(GiGrid g, int[][][] heights, int offset, int bx, int by, boolean inst, byte[] sceneFloors)
	{
		GiGrid old = grid;
		int dx = (bx - baseX)*resolution, dz = (by - baseY)*resolution;
		boolean fresh = old == null || inst != instance || inst && (dx != 0 || dz != 0)
			|| Math.abs(dx) >= size || Math.abs(dz) >= size;
		if (fresh)
		{
			for (int s = 1; s < TEX_D; ++s)
			{
				fillOpen(s, 0, size, 0, size);
			}
		}
		else if (dx != 0 || dz != 0)
		{
			shift(dx, dz);
		}
		grid = g;
		baseX = bx;
		baseY = by;
		instance = inst;
		layout(heights, offset, sceneFloors);
		nearbyUnits = 0; // Geometry/height changes invalidate the proximity selection.
		if (fresh || dx != 0 || dz != 0) java.util.Arrays.fill(active, true);
		if (fresh || dx != 0 || dz != 0)
		{
			markDirty(0, 0, corners, corners);
			changedAll = true;
		}
		else
		{
			markChanged(old, g);
			change(0, 0, size - 1, size - 1, 0, 0);
		}
		return fresh;
	}

	/** The walls changed (a door opened): the corners around the changed tiles are traced again. */
	void setWalls(WallMap w)
	{
		WallMap old = walls;
		walls = w;
		if (grid == null || old == w)
		{
			return;
		}
		int x0 = 0, z0 = 0, x1 = size - 1, z1 = size - 1;
		if (old != null && w != null && old.size == w.size && old.offset == w.offset)
		{
			int[] d = w.diff(old);
			if (d == null)
			{
				return;
			}
			x0 = Math.max(0, (d[0] - w.offset)*resolution);
			z0 = Math.max(0, (d[1] - w.offset)*resolution);
			x1 = Math.min(size - 1, (d[2] - w.offset+1)*resolution-1);
			z1 = Math.min(size - 1, (d[3] - w.offset+1)*resolution-1);
			if (x1 < x0 || z1 < z0)
			{
				return;
			}
		}
		for (int tz = z0; tz <= z1; ++tz)
		{
			for (int tx = x0; tx <= x1; ++tx)
			{
				writeMeta(tx, tz);
			}
		}
		change(x0, z0, x1, z1, 0, 0);
		markDirty(x0, z0, x1 + 2, z1 + 2);
	}

	/**
	 * Trace dirty units, those nearest the given world tile (absolute, as the scene's base) first, until about budget
	 * rays are spent. Returns the rays traced. Units go by blocks of BLOCK x BLOCK corners in rings around the player's,
	 * so what a call writes lies together and uploads as a small box.
	 */
	int trace(int budget, float worldTileX, float worldTileZ)
	{
		if (grid == null || dirtyCount == 0)
		{
			clearRings = corners;
			return 0;
		}
		int bx = Math.max(0, Math.min(size, Math.round((worldTileX - baseX)*resolution))) / BLOCK;
		int bz = Math.max(0, Math.min(size, Math.round((worldTileZ - baseY)*resolution))) / BLOCK;
		int blocks = (corners + BLOCK - 1) / BLOCK;
		int maxRing = Math.max(Math.max(bx, blocks - 1 - bx), Math.max(bz, blocks - 1 - bz));
		int rays = 0;
		for (int r = 0; r <= maxRing && dirtyCount > 0; ++r)
		{
			for (int dz = -r; dz <= r; ++dz)
			{
				// the whole row on the ring's top and bottom, its two ends otherwise
				int step = dz == -r || dz == r ? 1 : 2 * r;
				for (int dx = -r; dx <= r; dx += step)
				{
					int x0 = (bx + dx) * BLOCK, z0 = (bz + dz) * BLOCK;
					for (int cz = Math.max(0, z0); cz < Math.min(corners, z0 + BLOCK); ++cz)
					{
						for (int cx = Math.max(0, x0); cx < Math.min(corners, x0 + BLOCK); ++cx)
						{
							for (int level = 0; level < LEVELS; ++level)
							{
								int u = (level * corners + cz) * corners + cx;
								if (!dirty[u])
								{
									continue;
								}
								if (rays >= budget)
								{
									// every corner of the rings of blocks before this one is traced
									clearRings = Math.max(0, (r - 1) * BLOCK);
									return rays;
								}
								dirty[u] = false;
								dirtyCount--;
								rays += traceUnit(cx, cz, level);
							}
						}
					}
				}
			}
		}
		clearRings = corners;
		return rays;
	}

	/**
	 * The texels written since the last call, as {x0, z0, x1, z1, s0, s1, all} in tiles and slices, null when none;
	 * after a new place or a move all of them, with all 1.
	 */
	int[] takeChanges()
	{
		int[] c = changedAll ? new int[]{0, 0, size - 1, size - 1, 0, TEX_D - 1, 1}
			: changeX1 < changeX0 ? null : new int[]{changeX0, changeZ0, changeX1, changeZ1, changeS0, changeS1, 0};
		changedAll = false;
		changeX1 = -1;
		return c;
	}

	// ---------------------------------------------------------------------------------------------------------

	/** The rotation a unit's rays use: a hash, so the pattern doesn't line up across the scene. */
	private static int rotation(int unit)
	{
		int h = unit * 0x9E3779B1;
		h ^= h >>> 15;
		h *= 0x85EBCA77;
		h ^= h >>> 13;
		return h & (ROTATIONS - 1);
	}

	private void layout(int[][][] heights, int offset, byte[] sceneFloors)
	{
		float[] g = new float[FLOORS];
		for (int cz = 0; cz < corners; ++cz)
		{
			for (int cx = 0; cx < corners; ++cx)
			{
				for (int f = 0; f < FLOORS; ++f)
				{
					// a floor's ground is at least FLOOR_GAP over the one below
					float h = heights != null && f < heights.length ? height(heights[f], (float)cx/resolution + offset, (float)cz/resolution + offset)
						: f > 0 ? g[f - 1] - FLOOR_GAP : 0;
					g[f] = f == 0 ? h : Math.min(h, g[f - 1] - FLOOR_GAP);
					ground[(f * corners + cz) * corners + cx] = g[f];
				}
			}
		}
		for (int tz = 0; tz < size; ++tz)
		{
			for (int tx = 0; tx < size; ++tx)
			{
				int sceneSize = walls != null ? walls.size : SIZE + 2 * offset;
				int i = (tx/resolution + offset) * sceneSize + tz/resolution + offset;
				floors[tz * size + tx] = (byte) (1 | (sceneFloors != null && i < sceneFloors.length ? sceneFloors[i] : 0));
				writeMeta(tx, tz);
			}
		}
	}

	private static float height(int[][] h, float x, float z)
	{
		x = Math.max(0, Math.min(h.length-1, x));
		z = Math.max(0, Math.min(h[(int)x].length-1, z));
		int x0=(int)x, z0=(int)z, x1=Math.min(x0+1,h.length-1), z1=Math.min(z0+1,h[x0].length-1);
		float fx=x-x0, fz=z-z0;
		return (h[x0][z0]*(1-fx)+h[x1][z0]*fx)*(1-fz)+(h[x0][z1]*(1-fx)+h[x1][z1]*fx)*fz;
	}

	private float ground(int f, int cx, int cz)
	{
		return ground[(f * corners + cz) * corners + cx];
	}

	/** The diagonal wall of a tile on a floor: 0 none, 1 "/", 2 "\". */
	private int diagonal(int f, int tx, int tz)
	{
		int b = wallBits(f, tx, tz);
		return (b & WallMap.DIAG_SW_NE) != 0 ? 1 : (b & WallMap.DIAG_NW_SE) != 0 ? 2 : 0;
	}

	int wallBits(int f, int tx, int tz)
	{
		if (walls == null) return 0;
		int b = walls.get(f, tx/resolution+walls.offset, tz/resolution+walls.offset);
		int x=tx%resolution, z=tz%resolution;
		if(x!=0)b&=~WallMap.WEST;
		if(z!=0)b&=~WallMap.SOUTH;
		if(x!=resolution-1)b&=~WallMap.EAST;
		if(z!=resolution-1)b&=~WallMap.NORTH;
		if(x!=z)b&=~WallMap.DIAG_SW_NE;
		if(x+z!=resolution-1)b&=~WallMap.DIAG_NW_SE;
		return b;
	}

	private void writeMeta(int tx, int tz)
	{
		int code = diagonal(0, tx, tz) + 3 * diagonal(1, tx, tz) + 9 * diagonal(2, tx, tz);
		int bits = floors[tz * size + tx];
		for (int j = 0; j < 2; ++j)
		{
			for (int i = 0; i < 2; ++i)
			{
				int t = texel(2 * tx + i, 2 * tz + j, 0);
				for (int f = 0; f < FLOORS; ++f)
				{
					texels.put(t + f, GlobalIllumination.toHalf(ground(f, tx + i, tz + j)));
				}
				texels.put(t + 3, GlobalIllumination.toHalf(code));
				texels.put(texel(2 * tx + i + 2 * size, 2 * tz + j, 0), GlobalIllumination.toHalf(bits));
			}
		}
	}

	/** Use the same per-storey room classification as the existing room lighting. Unknown stays protected. */
	void setRooms(RoomMap rooms)
	{
		for (int z = 0; z < size; ++z)
			for (int x = 0; x < size; ++x)
			{
				int mask = 15;
				int rx = rooms != null ? x/resolution + rooms.offset : 0, rz = rooms != null ? z/resolution + rooms.offset : 0;
				boolean known = rooms != null && rx < rooms.size && rz < rooms.size;
				if (known)
				{
					mask = 0;
					for (int f = 0; f < RoomMap.PLANES; ++f)
					{
						int i = rooms.index(f, rx, rz);
						// covered by the map's under-roof flags: a room with no roof over it (a walled terrace, a
						// tower's top) is out of doors
						if (rooms.roofed[i]) mask |= 1 << f;
					}
				}
				for (int dz = 0; dz < 2; ++dz)
					for (int dx = 0; dx < 2; ++dx)
					{
						int a = texel(2 * x + dx + 4 * size, 2 * z + dz, 0);
						for (int f = 0; f < RoomMap.PLANES; ++f)
							texels.put(a + f, GlobalIllumination.toHalf(known ? rooms.floors[rooms.index(f, rx, rz)] : 0));
						texels.put(texel(2 * x + dx + 6 * size, 2 * z + dz, 0), GlobalIllumination.toHalf(mask));
					}
			}
		selectNearbySamples();
		change(0, 0, size - 1, size - 1, 0, 0);
	}

	/** Select geometry-adjacent samples at either grid resolution. */
	private void selectNearbySamples()
	{
		if (grid != null) selectDenseSamples();
	}

	private void selectDenseSamples()
	{
		// Wall-only door changes alter visibility, not distance to the existing geometry.
		if (nearbyUnits != 0) return;
		nearbyUnits=0; supportUnits=0;
		for(int level=0;level<LEVELS;level++)
		for(int cz=0;cz<corners;cz++)
		for(int cx=0;cx<corners;cx++)
		{
			float y=level==SURFACE_LEVEL?surfaceY(cx,cz):ground(level/3,cx,cz)-HEIGHTS[level%3];
			boolean keep=nearGeometry(cx*spacing,y,cz*spacing,112);
			int u=(level*corners+cz)*corners+cx;
			if(keep)nearbyUnits++;
			if(!keep && dirty[u]){dirty[u]=false;dirtyCount--;}
			if(keep && !active[u] && !dirty[u]){dirty[u]=true;dirtyCount++;}
			active[u]=keep;
			if(!keep) invalidateUnit(cx,cz,level);
		}
	}

	private boolean nearGeometry(float px,float py,float pz,float radius)
	{
		int vx=(int)Math.floor(px/64), vy=(int)Math.floor(py/64)-grid.layer0, vz=(int)Math.floor(pz/64);
		for(int z=Math.max(0,vz-3);z<=Math.min(grid.nz-1,vz+2);z++)
		for(int x=Math.max(0,vx-3);x<=Math.min(grid.nx-1,vx+2);x++)
		for(int y=Math.max(0,vy-3);y<=Math.min(grid.ny-1,vy+2);y++)
		{
			if(!grid.occupied(x,y,z))continue;
			float dx=Math.max(Math.max(x*64-px,px-(x+1)*64),0);
			float dy=Math.max(Math.max((y+grid.layer0)*64-py,py-(y+grid.layer0+1)*64),0);
			float dz=Math.max(Math.max(z*64-pz,pz-(z+1)*64),0);
			if(dx*dx+dy*dy+dz*dz<=radius*radius)return true;
		}
		return false;
	}

	private void invalidateUnit(int cx,int cz,int level)
	{
		for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)
		{
			int tx=cx-dx,tz=cz-dz;
			if(tx<0||tz<0||tx>=size||tz>=size)continue;
			for(int half=0;half<2;half++)
				texels.put(texel(half*4*size+2*tx+dx,2*tz+dz,level+1),GlobalIllumination.toHalf(-1));
			change(tx,tz,tx,tz,level+1,level+1);
		}
	}

	private int texel(int x, int y, int slice)
	{
		return ((slice * textureHeight + y) * textureWidth + x) * 4;
	}

	/** Open sky in both parts of a slice's tiles x0..x1 - 1 by z0..z1 - 1. */
	private void fillOpen(int slice, int x0, int x1, int z0, int z1)
	{
		for (int y = 2 * z0; y < 2 * z1; ++y)
		{
			for (int x = 2 * x0; x < 2 * x1; ++x)
			{
				for (int part = 0; part < 4; ++part)
				{
					int t = texel(x + part * 2 * size, y, slice);
					for (int c = 0; c < 4; ++c) texels.put(t + c, part % 2 == 0 || c == 0 ? HALF_ONE : 0);
				}
			}
		}
	}

	/** Move the values to a base dx, dz tiles away: what lay at tile t + d is now at t; what is new is open sky. */
	private void shift(int dx, int dz)
	{
		short[] slice = new short[textureWidth * textureHeight * 4];
		short[] moved = new short[slice.length];
		int row = textureWidth * 4;
		for (int s = 1; s < TEX_D; ++s)
		{
			texels.position(texel(0, 0, s));
			texels.get(slice);
			java.util.Arrays.fill(moved, (short) 0);
			int xs = Math.max(0, -dx), xe = Math.min(size, size - dx);
			for (int y = 2 * Math.max(0, -dz); y < 2 * Math.min(size, size - dz); ++y)
			{
				int from = (y + 2 * dz) * row, to = y * row;
				for (int part = 0; part < 4; ++part)
				{
					int p = part * 2 * size * 4;
					System.arraycopy(slice, from + p + 2 * (xs + dx) * 4, moved, to + p + 2 * xs * 4, 2 * (xe - xs) * 4);
				}
			}
			texels.position(texel(0, 0, s));
			texels.put(moved);
			texels.position(0);
			// the tiles that came in
			fillOpen(s, 0, size, 0, Math.max(0, -dz));
			fillOpen(s, 0, size, Math.min(size, size - dz), size);
			fillOpen(s, 0, Math.max(0, -dx), 0, size);
			fillOpen(s, Math.min(size, size - dx), size, 0, size);
		}
	}

	/** Mark the units of every level at corners x0..x1 - 1 by z0..z1 - 1 (clamped) for tracing. */
	private void markDirty(int x0, int z0, int x1, int z1)
	{
		x0 = Math.max(0, x0);
		z0 = Math.max(0, z0);
		x1 = Math.min(corners, x1);
		z1 = Math.min(corners, z1);
		for (int level = 0; level < LEVELS; ++level)
		{
			for (int cz = z0; cz < z1; ++cz)
			{
				for (int cx = x0; cx < x1; ++cx)
				{
					int u = (level * corners + cz) * corners + cx;
					if (active[u] && !dirty[u])
					{
						dirty[u] = true;
						dirtyCount++;
					}
				}
			}
		}
	}

	/** Mark the corners within reach of every tile whose voxels differ between two grids of the same place. */
	private void markChanged(GiGrid a, GiGrid b)
	{
		if (a.nx != b.nx || a.ny != b.ny || a.nz != b.nz || a.layer0 != b.layer0)
		{
			markDirty(0, 0, corners, corners);
			return;
		}
		// changed tiles, then spread reach along x and then along z
		boolean[] changed = new boolean[size * size];
		int stride = a.nx * a.nz;
		for (int vz = 0; vz < a.nz; ++vz)
		{
			for (int vx = 0; vx < a.nx; ++vx)
			{
				int t = (vz*resolution/2)*size + vx*resolution/2;
				if (changed[t])
				{
					continue;
				}
				for (int i = a.index(vx, 0, vz); i < a.cells.length; i += stride)
				{
					if (a.cells[i] != b.cells[i])
					{
						changed[t] = true;
						break;
					}
				}
			}
		}
		// [tile z][corner x]
		boolean[] wide = new boolean[size * corners];
		for (int tz = 0; tz < size; ++tz)
		{
			for (int tx = 0; tx < size; ++tx)
			{
				if (changed[tz * size + tx])
				{
					for (int cx = Math.max(0, tx - reach); cx <= Math.min(size, tx + 1 + reach); ++cx)
					{
						wide[tz * corners + cx] = true;
					}
				}
			}
		}
		for (int cx = 0; cx < corners; ++cx)
		{
			for (int tz = 0; tz < size; ++tz)
			{
				if (wide[tz * corners + cx])
				{
					markDirty(cx, tz - reach, cx + 1, tz + 2 + reach);
				}
			}
		}
	}

	private void change(int x0, int z0, int x1, int z1, int s0, int s1)
	{
		if (changeX1 < changeX0)
		{
			changeX0 = x0;
			changeZ0 = z0;
			changeX1 = x1;
			changeZ1 = z1;
			changeS0 = s0;
			changeS1 = s1;
			return;
		}
		changeX0 = Math.min(changeX0, x0);
		changeZ0 = Math.min(changeZ0, z0);
		changeX1 = Math.max(changeX1, x1);
		changeZ1 = Math.max(changeZ1, z1);
		changeS0 = Math.min(changeS0, s0);
		changeS1 = Math.max(changeS1, s1);
	}

	// The quadrants around a corner, 0 south-west to 3 north-east: the tile each lies in, relative to the corner, and
	// which of that tile's corners it is (x 1 east, z 1 north)
	private static final int[] QX = {-1, 0, -1, 0}, QZ = {-1, -1, 0, 0};

	/** Trace the actual corner of each touching triangle, sharing rays only across open edges. */
	private int traceUnit(int cx, int cz, int level)
	{
		boolean surface = level == SURFACE_LEVEL;
		int f = surface ? FLOORS - 1 : level / HEIGHTS.length;
		float height = surface ? HEIGHTS[HEIGHTS.length - 1] : HEIGHTS[level % HEIGHTS.length];
		float y = surface ? surfaceY(cx, cz) : ground(f, cx, cz) - height;
		java.util.Arrays.fill(group, -1);
		int present = 0;
		for (int q = 0; q < 4; ++q)
		{
			int tx = cx + QX[q], tz = cz + QZ[q];
			if (tx < 0 || tz < 0 || tx >= size || tz >= size) continue;
			present |= 1 << q;
			bits[q] = surface ? 0 : wallBits(f, tx, tz);
			int hx = WallMap.half(bits[q], QX[q] < 0 ? WallMap.EAST : WallMap.WEST);
			int hz = WallMap.half(bits[q], QZ[q] < 0 ? WallMap.NORTH : WallMap.SOUTH);
			group[2 * q + hx] = 2 * q + hx;
			group[2 * q + hz] = 2 * q + hz;
			for (int h = 0; h < 2; ++h)
				if (group[2 * q + h] >= 0) startPoint(2 * q + h, cx, cz, bits[q], y, height, false);
		}
		int off = 0;
		if (surface)
		{
			int root = Integer.numberOfTrailingZeros(present) * 2;
			for (int q = 0; q < 4; ++q)
				if ((present & (1 << q)) != 0) group[q * 2] = root;
		}
		else
		{
			join(present, bits, 0, 1, WallMap.EAST, WallMap.WEST, cx - 1 + off, cz - 1 + off, f);
			join(present, bits, 2, 3, WallMap.EAST, WallMap.WEST, cx - 1 + off, cz + off, f);
			join(present, bits, 0, 2, WallMap.NORTH, WallMap.SOUTH, cx - 1 + off, cz - 1 + off, f);
			join(present, bits, 1, 3, WallMap.NORTH, WallMap.SOUTH, cx + off, cz - 1 + off, f);
		}

		int unit = (level * corners + cz) * corners + cx;
		int rot = rotation(unit);
		float[] dirs = DIRECTIONS[rot], weights = WEIGHTS[rot];
		int rays = 0;
		for (int root = 0; root < 8; ++root)
		{
			if (group[root] < 0 || find(root) != root) continue;
			int m = 0;
			for (int node = 0; node < 8; ++node)
				if (group[node] >= 0 && find(node) == root) members[m++] = node;
			// A shared corner touching an interior keeps every ray's original placement.
			boolean followTerrain = level < HEIGHTS.length && terrain != null;
			for (int k = 0; followTerrain && k < m; ++k)
			{
				int q = members[k] / 2, tx = cx + QX[q], tz = cz + QZ[q];
				int mask = (int) GlobalIllumination.fromHalf(texels.get(texel(2 * tx + 6 * size, 2 * tz, 0)));
				if ((mask & 1) != 0) followTerrain = false;
			}
			if (followTerrain)
				for (int k = 0; k < m; ++k)
					startPoint(members[k], cx, cz, bits[members[k] / 2], y, height, true);
			// Keep the position represented by this shared visibility sample, including relocation
			// away from solid geometry. The shader must not treat a point below a roof as above it.
			float px = 0, py = 0, pz = 0;
			for (int k = 0; k < m; ++k)
			{
				int node = members[k];
				px += start[node * 3];
				py += start[node * 3 + 1];
				pz += start[node * 3 + 2];
			}
			short sampleX = GlobalIllumination.toHalf(px * GiGrid.VOXEL / m - cx * spacing);
			short sampleY = GlobalIllumination.toHalf((py / m + grid.layer0) * GiGrid.VOXEL);
			short sampleZ = GlobalIllumination.toHalf(pz * GiGrid.VOXEL / m - cz * spacing);
			boolean keep = nearGeometry(cx*spacing+GlobalIllumination.fromHalf(sampleX),
				GlobalIllumination.fromHalf(sampleY),cz*spacing+GlobalIllumination.fromHalf(sampleZ),64);
			java.util.Arrays.fill(acc, 0);
			for (int r = 0; keep && r < RAYS; ++r)
			{
				int node = members[r % m];
				float ox = start[node * 3], oy = start[node * 3 + 1], oz = start[node * 3 + 2];
				int hit = grid.trace(ox, oy, oz, dirs[r * 3], dirs[r * 3 + 1], dirs[r * 3 + 2], MAX_DISTANCE, true,
					(unit * RAYS + r) & 0x7fffffff);
				// a blocker far off hides less of the sky than one close by: light comes round it and off what is
				// around it. A miss counts whole, a hit by how far away it is.
				float open = 1;
				if (hit >= 0)
				{
					int hx = hit % grid.nx, hz = hit / grid.nx % grid.nz, hy = hit / (grid.nx * grid.nz);
					float d = (float) Math.sqrt(sq(hx + 0.5f - ox) + sq(hy + 0.5f - oy) + sq(hz + 0.5f - oz));
					open = smoothstep(NEAR_BLOCKER, FAR_BLOCKER, d) * FAR_OPEN;
				}
				if (open > 0)
					for (int k = 0; k < LOBES; ++k) acc[k] += weights[r * LOBES + k] * open;
			}
			if (keep) rays += RAYS;
			for (int k = 0; k < LOBES; ++k) value[k] = GlobalIllumination.toHalf(acc[k]);
			if (!keep) value[0] = GlobalIllumination.toHalf(-1);
			for (int k = 0; k < m; ++k)
			{
				int node = members[k], q = node / 2, half = node % 2;
				int tx = cx + QX[q], tz = cz + QZ[q];
				int x = half * 4 * size + 2 * tx + (QX[q] < 0 ? 1 : 0), yy = 2 * tz + (QZ[q] < 0 ? 1 : 0);
				int a = texel(x, yy, 1 + level), b = texel(x + 2 * size, yy, 1 + level);
				if (texels.get(a) == value[0] && texels.get(a + 1) == value[1] && texels.get(a + 2) == value[2]
					&& texels.get(a + 3) == value[3] && texels.get(b) == value[4]
					&& texels.get(b + 1) == sampleX && texels.get(b + 2) == sampleY && texels.get(b + 3) == sampleZ) continue;
				for (int c = 0; c < 4; ++c) texels.put(a + c, value[c]);
				texels.put(b, value[4]);
				texels.put(b + 1, sampleX);
				texels.put(b + 2, sampleY);
				texels.put(b + 3, sampleZ);
				change(tx, tz, tx, tz, 1 + level, 1 + level);
			}
		}
		return rays;
	}

	private void join(int present, int[] bits, int p, int q, int a, int b, int x, int z, int f)
	{
		if ((present >> p & 1) == 0 || (present >> q & 1) == 0 || ((wallBits(f,x,z)&a)!=0 || (wallBits(f,x+(a==WallMap.EAST?1:0),z+(a==WallMap.NORTH?1:0))&b)!=0)) return;
		group[find(2 * p + WallMap.half(bits[p], a))] = find(2 * q + WallMap.half(bits[q], b));
	}

	private int find(int q)
	{
		while (group[q] != q) q = group[q];
		return q;
	}

	/** Start inside the triangle that owns this corner; never relocate through its diagonal wall. */
	private void startPoint(int node, int cx, int cz, int bits, float y, float height, boolean followTerrain)
	{
		int q = node / 2;
		float mx = 0.5f, mz = 0.5f;
		if (WallMap.split(bits))
		{
			boolean slash = (bits & WallMap.DIAG_SW_NE) != 0, north = node % 2 == 0;
			mx = slash == north ? 1 / 3f : 2 / 3f;
			mz = north ? 2 / 3f : 1 / 3f;
		}
		int tx = cx + QX[q], tz = cz + QZ[q];
		float kx = QX[q] < 0 ? 1 : 0, kz = QZ[q] < 0 ? 1 : 0;
		float px = (tx + (kx + mx) / 2) * spacing, pz = (tz + (kz + mz) / 2) * spacing;
		float sx = kx > 0 ? -spacing/2 : spacing/2, sz = kz > 0 ? -spacing/2 : spacing/2;
		int moves = WallMap.split(bits) ? 1 : 4;
		for (int k = 0; k < moves; ++k)
		{
			float x = px + (k == 1 || k == 3 ? sx : 0), z = pz + (k >= 2 ? sz : 0);
			float candidateY = followTerrain ? terrain.height(x, z, y + height) - height : y;
			for (float dy : NUDGE)
			{
				if (height - dy < MIN_HEIGHT || k > 0 && Math.abs(dy) > 24 || solid(x, candidateY + dy, z)) continue;
				setStart(node, x, candidateY + dy, z);
				return;
			}
		}
		setStart(node, px, followTerrain ? terrain.height(px, pz, y + height) - height : y, pz);
	}

	// Highest occupied voxel in the four columns adjacent to this corner. This includes foliage;
	// the extra sample belongs above the canopy, not inside its occupied volume.
	private float surfaceY(int cx, int cz)
	{
		int vx = cx * (int) spacing / GiGrid.VOXEL, vz = cz * (int) spacing / GiGrid.VOXEL;
		for (int y = 0; y < grid.ny; ++y)
			for (int z = Math.max(0, vz - 1); z < Math.min(grid.nz, vz + 1); ++z)
				for (int x = Math.max(0, vx - 1); x < Math.min(grid.nx, vx + 1); ++x)
					if (grid.cells[grid.index(x, y, z)] != 0)
						return (y + grid.layer0) * GiGrid.VOXEL - 8;
		return ground(0, cx, cz) - HEIGHTS[0];
	}

	private void setStart(int q, float x, float y, float z)
	{
		start[q * 3] = x / GiGrid.VOXEL;
		start[q * 3 + 1] = y / GiGrid.VOXEL - grid.layer0;
		start[q * 3 + 2] = z / GiGrid.VOXEL;
	}

	// a voxel that isn't foliage: rays start in foliage (a point under a tree's crown)
	private boolean solid(float x, float y, float z)
	{
		int vx = (int) Math.floor(x / GiGrid.VOXEL), vy = (int) Math.floor(y / GiGrid.VOXEL) - grid.layer0;
		int vz = (int) Math.floor(z / GiGrid.VOXEL);
		if (vx < 0 || vy < 0 || vz < 0 || vx >= grid.nx || vy >= grid.ny || vz >= grid.nz)
		{
			return false;
		}
		int cell = grid.cells[grid.index(vx, vy, vz)];
		return cell != 0 && !GiGrid.foliage(cell);
	}

	private static float sq(float v)
	{
		return v * v;
	}

	private static float smoothstep(float a, float b, float v)
	{
		float t = Math.max(0, Math.min(1, (v - a) / (b - a)));
		return t * t * (3 - 2 * t);
	}
}
