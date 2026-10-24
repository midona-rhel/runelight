package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * How much of the sky the scene lets through, ray traced through its voxels (GiGrid) for the skylight
 * (skylight_ao.glsl). Probes sit at the centres of the base scene's tiles. Walls stand on tile edges (WallMap), so a
 * centre is always clearly on one side of every wall. A tile that a diagonal wall splits has one probe for each half, at
 * the half's centroid.
 *
 * Each storey (the scene's planes, FLOORS of them; a tile has the upper ones only where it has an actual floor on that
 * plane) has two probes in a column, LOW and HIGH above its floor, which are never blended across a floor. One more probe, the
 * SURFACE one, sits just above the highest scenery of the tile's column (a roof, a wall's top, the crown of a tree), for
 * the surfaces that face the open sky at the top of the scenery.
 *
 * A probe stores the sky visibility as first order spherical harmonics, one RGBA16F texel (a, bx, by, bz): a is the
 * share of the sky's directions (an even spread over the upper half of the sphere) whose rays get out, leave the grid or
 * go MAX_DISTANCE without meeting anything, and b is the sum of those directions over the same count, in world axes
 * (y down). The shader weighs them by a normal's cosine; the directions are corrected so that under the open sky
 * (a, b) = (1, 0, -0.5, 0) exactly, whatever turn the probe's rays have, and every normal sees 1. Probes far from any
 * geometry are not traced and hold that open sky.
 *
 * Tracing is amortized: work is a unit, one slot (LOW and HIGH of each storey, then SURFACE) of one tile, with both
 * halves of a diagonal one. Units are marked dirty when the voxels or the walls around them change, and trace() works
 * through the dirty ones nearest the player first, up to a budget of rays. The rays of a probe are always the same (a
 * fixed set turned by a per-probe angle, and foliage stopping the same rays), so a probe traced again over unchanged
 * voxels gets the same values: nothing flickers.
 *
 * The 3D RGBA16F texture is TEX_W x TEX_H = 2 * SIZE x SIZE texels, a tile's halves side by side (x = 2 * tile x +
 * half, y = tile z; a tile without a diagonal uses half 0 only), and TEX_D slices:
 * <ul>
 * <li>Slice 0 (HEIGHT_SLICE), half 0: the floor height of each storey at the tile's centre (g, b, a = storeys 0, 1, 2),
 * NO_FLOOR where the tile has no floor on it, and the surface probe's height in a.</li>
 * <li>Slices 1 to 4 (LINK_SLICE + q), for the quadrant q of the tile a point is in (bit 0 east, bit 1 north): what the
 * point may blend with, a number per storey in r, g, b (see link()), and the tiles' diagonal kinds in a, 0 none,
 * 1 "/" and 2 "\", of storey 0 + 3 * storey 1 + 9 * storey 2.</li>
 * <li>Slices PROBE_SLICE + slot: the probes, slot 2 * storey for LOW, 2 * storey + 1 for HIGH, SURFACE last.</li>
 * </ul>
 * That is 2 MB. A pixel reads up to four tile-centre probes (its own and the three nearest neighbours), the ones a wall
 * does not separate from it, in two or three slices.
 *
 * Not thread safe: one thread (SkyGridMap's worker) calls everything; the texels and changes are read between calls.
 * World coordinates are the base scene's local ones, tile 0 at 0, y down.
 */
final class SkyGrid
{
	static final int SIZE = GiProbes.SIZE;
	static final int FLOORS = GiProbes.FLOORS;
	// heights of the LOW and HIGH probes above their storey's floor: between a storey's floor and ceiling voxels (a
	// storey is about 240 high, a voxel 64), low enough for the floor, high enough for the tops of walls
	static final float[] HEIGHTS = {48, 176};
	static final int SLOTS = FLOORS * HEIGHTS.length + 1;
	static final int SURFACE = SLOTS - 1;
	static final float TILE = 128;
	// rays per probe: an even spread over the sky's half of the sphere
	static final int RAYS = 32;
	static final int ROTATIONS = GiProbes.ROTATIONS;
	// rays that go this many voxels without a hit see the sky: 16 tiles
	static final float MAX_DISTANCE = 32;
	// tiles around a changed voxel whose rays may have met it
	static final int REACH = (int) Math.ceil(MAX_DISTANCE * GiGrid.VOXEL / TILE) + 1;
	// tiles per side of the blocks tracing goes by
	static final int BLOCK = 4;
	static final int HEIGHT_SLICE = 0, LINK_SLICE = 1, PROBE_SLICE = 5;
	static final int TEX_W = 2 * SIZE, TEX_H = SIZE, TEX_D = PROBE_SLICE + SLOTS;
	// the floor height of a storey a tile has no floor on: a point is never above it
	static final float NO_FLOOR = -60000;

	private static final short HALF_ONE = GlobalIllumination.toHalf(1);
	private static final short HALF_UP = GlobalIllumination.toHalf(-0.5f);
	// where a probe's point may move when its voxel is taken: across the tile and up and down (world units, y down)
	private static final float[] SHIFT = {0, -32, 32};
	private static final float[] NUDGE = {0, -24, 24, -48, 48};
	// the least height above the ground a point may be moved down to
	private static final float MIN_HEIGHT = 8;
	// how far above the highest voxel of a tile the SURFACE probe is
	private static final float SURFACE_GAP = 8;
	// probes within this distance of an occupied voxel are traced, the others see the open sky
	private static final float NEAR_GEOMETRY = 96;
	// voxels: a blocker up to NEAR_BLOCKER away hides its share of the sky in full; from there to FAR_BLOCKER it hides
	// less and less, down to letting FAR_OPEN of it through
	private static final float NEAR_BLOCKER = 3;
	private static final float FAR_BLOCKER = 24;
	private static final float FAR_OPEN = 0.6f;

	// [rotation][ray * 3 + axis]: the rays' directions, and the same turned so that they add up to the mean direction
	// of an even spread, (0, -0.5, 0) (up is -y), for the harmonics
	private static final float[][] DIRECTIONS = new float[ROTATIONS][];
	private static final float[][] BALANCED = new float[ROTATIONS][];

	static
	{
		// the upper half of an even spread over the sphere (y down)
		float[] sphere = GiProbes.fibonacci(2 * RAYS);
		float[] base = java.util.Arrays.copyOfRange(sphere, RAYS * 3, RAYS * 6);
		for (int r = 0; r < ROTATIONS; ++r)
		{
			double a = 2 * Math.PI * r / ROTATIONS;
			float c = (float) Math.cos(a), s = (float) Math.sin(a);
			float[] d = new float[RAYS * 3];
			float[] mean = new float[3];
			for (int i = 0; i < RAYS; ++i)
			{
				d[i * 3] = c * base[i * 3] - s * base[i * 3 + 2];
				d[i * 3 + 1] = base[i * 3 + 1];
				d[i * 3 + 2] = s * base[i * 3] + c * base[i * 3 + 2];
				for (int k = 0; k < 3; ++k) mean[k] += d[i * 3 + k] / RAYS;
			}
			float[] b = new float[RAYS * 3];
			for (int i = 0; i < RAYS * 3; ++i) b[i] = d[i] - mean[i % 3] + (i % 3 == 1 ? -0.5f : 0);
			DIRECTIONS[r] = d;
			BALANCED[r] = b;
		}
	}

	SkyGrid()
	{
		texels = ByteBuffer.allocateDirect(TEX_W * TEX_H * TEX_D * 4 * Short.BYTES)
			.order(ByteOrder.nativeOrder()).asShortBuffer();
	}

	final ShortBuffer texels;

	private GiGrid grid;
	SkyTerrain terrain;
	private int baseX, baseY;
	private boolean instance;
	private WallMap walls;
	// the floor heights of the storeys at each tile's centre, [storey][z * SIZE + x], world y
	private final float[][] ground = new float[FLOORS][SIZE * SIZE];
	// actual scene floors per tile (z * SIZE + x), one bit per plane; the ground floor always
	private final byte[] floors = new byte[SIZE * SIZE];
	// where the SURFACE probe of each tile is, world y
	private final float[] surface = new float[SIZE * SIZE];
	// units to trace, [(slot * SIZE + z) * SIZE + x]
	final boolean[] active = new boolean[SLOTS * SIZE * SIZE];
	final boolean[] dirty = new boolean[active.length];
	private int dirtyCount;
	// rings of tiles around the last trace's center with nothing left to trace
	private int clearRings;
	// texels written since takeChanges: tiles x0..x1 by z0..z1 (none while x1 < x0), slices s0..s1, or everything
	private int changeX0, changeZ0, changeX1 = -1, changeZ1, changeS0, changeS1;
	private boolean changedAll;

	// scratch for a probe: its start point in voxel space
	private float startX, startY, startZ;

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

	/** Rings of tiles around where the last trace() started that are all traced. */
	int clearRings()
	{
		return clearRings;
	}

	/** How many units are near geometry, and so traced. */
	int activeCount()
	{
		int n = 0;
		for (boolean a : active) if (a) n++;
		return n;
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
		int dx = bx - baseX, dz = by - baseY;
		boolean fresh = old == null || inst != instance || inst && (dx != 0 || dz != 0)
			|| Math.abs(dx) >= SIZE || Math.abs(dz) >= SIZE;
		if (fresh)
		{
			fillOpen(0, SIZE, 0, SIZE);
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
		selectNearby();
		if (fresh || dx != 0 || dz != 0)
		{
			markDirty(0, 0, SIZE, SIZE);
			changedAll = true;
		}
		else
		{
			markChanged(old, g);
		}
		return fresh;
	}

	/** The walls changed (a door opened): the links around the changed tiles are written again, their probes traced again. */
	void setWalls(WallMap w)
	{
		WallMap old = walls;
		walls = w;
		if (grid == null || old == w)
		{
			return;
		}
		int x0 = 0, z0 = 0, x1 = SIZE - 1, z1 = SIZE - 1;
		if (old != null && w != null && old.size == w.size && old.offset == w.offset)
		{
			int[] d = w.diff(old);
			if (d == null)
			{
				return;
			}
			x0 = Math.max(0, d[0] - w.offset);
			z0 = Math.max(0, d[1] - w.offset);
			x1 = Math.min(SIZE - 1, d[2] - w.offset);
			z1 = Math.min(SIZE - 1, d[3] - w.offset);
			if (x1 < x0 || z1 < z0)
			{
				return;
			}
		}
		// a tile's links read its neighbours' walls
		for (int tz = Math.max(0, z0 - 1); tz <= Math.min(SIZE - 1, z1 + 1); ++tz)
		{
			for (int tx = Math.max(0, x0 - 1); tx <= Math.min(SIZE - 1, x1 + 1); ++tx)
			{
				writeLinks(tx, tz);
			}
		}
		markDirty(x0, z0, x1 + 1, z1 + 1);
	}

	/**
	 * Trace dirty units, those nearest the given world tile (absolute, as the scene's base) first, until about budget
	 * rays are spent. Returns the rays traced. Units go by blocks of BLOCK x BLOCK tiles in rings around the player's,
	 * so what a call writes lies together and uploads as a small box.
	 */
	int trace(int budget, float worldTileX, float worldTileZ)
	{
		if (grid == null || dirtyCount == 0)
		{
			clearRings = SIZE;
			return 0;
		}
		int bx = Math.max(0, Math.min(SIZE - 1, Math.round(worldTileX - baseX))) / BLOCK;
		int bz = Math.max(0, Math.min(SIZE - 1, Math.round(worldTileZ - baseY))) / BLOCK;
		int blocks = (SIZE + BLOCK - 1) / BLOCK;
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
					for (int tz = Math.max(0, z0); tz < Math.min(SIZE, z0 + BLOCK); ++tz)
					{
						for (int tx = Math.max(0, x0); tx < Math.min(SIZE, x0 + BLOCK); ++tx)
						{
							for (int slot = 0; slot < SLOTS; ++slot)
							{
								int u = (slot * SIZE + tz) * SIZE + tx;
								if (!dirty[u])
								{
									continue;
								}
								if (rays >= budget)
								{
									// every tile of the rings of blocks before this one is traced
									clearRings = Math.max(0, (r - 1) * BLOCK);
									return rays;
								}
								dirty[u] = false;
								dirtyCount--;
								rays += traceUnit(tx, tz, slot);
							}
						}
					}
				}
			}
		}
		clearRings = SIZE;
		return rays;
	}

	/**
	 * The texels written since the last call, as {x0, z0, x1, z1, s0, s1, all} in tiles and slices, null when none;
	 * after a new place or a move all of them, with all 1.
	 */
	int[] takeChanges()
	{
		int[] c = changedAll ? new int[]{0, 0, SIZE - 1, SIZE - 1, 0, TEX_D - 1, 1}
			: changeX1 < changeX0 ? null : new int[]{changeX0, changeZ0, changeX1, changeZ1, changeS0, changeS1, 0};
		changedAll = false;
		changeX1 = -1;
		return c;
	}

	/** Where the traced probes within a few tiles of a point lie, {x, y, z} in the base scene's local world units. */
	List<float[]> tracedProbes(float x, float z, int tiles)
	{
		List<float[]> result = new ArrayList<>();
		if (grid == null)
		{
			return result;
		}
		int cx = (int) (x / TILE), cz = (int) (z / TILE);
		for (int tz = Math.max(0, cz - tiles); tz <= Math.min(SIZE - 1, cz + tiles); ++tz)
		{
			for (int tx = Math.max(0, cx - tiles); tx <= Math.min(SIZE - 1, cx + tiles); ++tx)
			{
				for (int slot = 0; slot < SLOTS; ++slot)
				{
					int u = (slot * SIZE + tz) * SIZE + tx;
					int bits = slot == SURFACE ? 0 : wallBits(slot / HEIGHTS.length, tx, tz);
					for (int h = 0; active[u] && !dirty[u] && h < (WallMap.split(bits) ? 2 : 1); ++h)
					{
						startPoint(tx, tz, slot, bits, h);
						result.add(new float[]{startX * GiGrid.VOXEL, (startY + grid.layer0) * GiGrid.VOXEL, startZ * GiGrid.VOXEL});
					}
				}
			}
		}
		return result;
	}

	// ---------------------------------------------------------------------------------------------------------

	private void layout(int[][][] heights, int offset, byte[] sceneFloors)
	{
		int sceneSize = walls != null ? walls.size : SIZE + 2 * offset;
		for (int tz = 0; tz < SIZE; ++tz)
		{
			for (int tx = 0; tx < SIZE; ++tx)
			{
				int t = tz * SIZE + tx;
				for (int f = 0; f < FLOORS; ++f)
				{
					// as GiProbes: a floor's ground is at least FLOOR_GAP over the one below
					float h = heights != null && f < heights.length ? height(heights[f], tx + 0.5f + offset, tz + 0.5f + offset)
						: f > 0 ? ground[f - 1][t] - GiProbes.FLOOR_GAP : 0;
					if (f == 0 && terrain != null)
					{
						h = terrain.height((tx + 0.5f) * TILE, (tz + 0.5f) * TILE, h);
					}
					ground[f][t] = f == 0 ? h : Math.min(h, ground[f - 1][t] - GiProbes.FLOOR_GAP);
				}
				int i = (tx + offset) * sceneSize + tz + offset;
				floors[t] = (byte) (1 | (sceneFloors != null && i < sceneFloors.length ? sceneFloors[i] : 0));
				surface[t] = surfaceY(tx, tz);
			}
		}
		for (int tz = 0; tz < SIZE; ++tz)
		{
			for (int tx = 0; tx < SIZE; ++tx)
			{
				int t = tz * SIZE + tx;
				boolean changed = write(texel(2 * tx, tz, HEIGHT_SLICE), GlobalIllumination.toHalf(ground[0][t]),
					GlobalIllumination.toHalf(hasFloor(1, t) ? ground[1][t] : NO_FLOOR),
					GlobalIllumination.toHalf(hasFloor(2, t) ? ground[2][t] : NO_FLOOR), GlobalIllumination.toHalf(surface[t]));
				if (changed)
				{
					change(tx, tz, tx, tz, HEIGHT_SLICE, HEIGHT_SLICE);
				}
				writeLinks(tx, tz);
			}
		}
	}

	private boolean hasFloor(int f, int t)
	{
		return (floors[t] >> f & 1) != 0;
	}

	private static float height(int[][] h, float x, float z)
	{
		x = Math.max(0, Math.min(h.length - 1, x));
		z = Math.max(0, Math.min(h[(int) x].length - 1, z));
		int x0 = (int) x, z0 = (int) z, x1 = Math.min(x0 + 1, h.length - 1), z1 = Math.min(z0 + 1, h[x0].length - 1);
		float fx = x - x0, fz = z - z0;
		return (h[x0][z0] * (1 - fx) + h[x1][z0] * fx) * (1 - fz) + (h[x0][z1] * (1 - fx) + h[x1][z1] * fx) * fz;
	}

	/** The wall bits of a tile on a floor, with its diagonal; 0 outside the scene or without walls. */
	private int wallBits(int f, int tx, int tz)
	{
		return walls == null ? 0 : walls.get(f, tx + walls.offset, tz + walls.offset);
	}

	/** A tile's diagonal wall: 0 none, 1 "/", 2 "\". */
	private static int diagonal(int bits)
	{
		return (bits & WallMap.DIAG_SW_NE) != 0 ? 1 : (bits & WallMap.DIAG_NW_SE) != 0 ? 2 : 0;
	}

	private static int opposite(int side)
	{
		return (side << 2 | side >> 2) & 15;
	}

	private static boolean inside(int tx, int tz)
	{
		return tx >= 0 && tz >= 0 && tx < SIZE && tz < SIZE;
	}

	/** Whether a half of a tile (bits, as WallMap) reaches the tile across a side with no wall in between. */
	private boolean crosses(int f, int tx, int tz, int bits, int half, int side, int dx, int dz)
	{
		return inside(tx + dx, tz + dz) && WallMap.half(bits, side) == half
			&& (walls == null || !walls.edge(f, tx + walls.offset, tz + walls.offset, side)
				&& !walls.solid(f, tx + dx + walls.offset, tz + dz + walls.offset));
	}

	/**
	 * What a point in one quadrant of a half of a tile on a floor may blend with, beside the tile's own probe, the sx, sz
	 * (+1 east or north, -1 west or south) neighbours: bit 0 the one along x, bit 1 along z, bit 2 the diagonal one,
	 * each when no wall is in the way (the diagonal one needs an open path round either corner), and bits 3 to 5 which
	 * half of each of them it blends with, the one that the way in comes to.
	 */
	private int link(int f, int tx, int tz, int half, int sx, int sz)
	{
		int sideX = sx > 0 ? WallMap.EAST : WallMap.WEST, sideZ = sz > 0 ? WallMap.NORTH : WallMap.SOUTH;
		int own = wallBits(f, tx, tz), bitsX = wallBits(f, tx + sx, tz), bitsZ = wallBits(f, tx, tz + sz);
		int bitsD = wallBits(f, tx + sx, tz + sz);
		boolean x = crosses(f, tx, tz, own, half, sideX, sx, 0), z = crosses(f, tx, tz, own, half, sideZ, 0, sz);
		int halfX = WallMap.half(bitsX, opposite(sideX)), halfZ = WallMap.half(bitsZ, opposite(sideZ));
		boolean viaX = x && crosses(f, tx + sx, tz, bitsX, halfX, sideZ, 0, sz);
		boolean viaZ = z && crosses(f, tx, tz + sz, bitsZ, halfZ, sideX, sx, 0);
		int halfD = WallMap.half(bitsD, opposite(viaX ? sideZ : sideX));
		return (x ? 1 : 0) | (z ? 2 : 0) | (viaX || viaZ ? 4 : 0) | halfX << 3 | halfZ << 4 | halfD << 5;
	}

	private void writeLinks(int tx, int tz)
	{
		int code = 0, split = 0;
		for (int f = FLOORS - 1; f >= 0; --f)
		{
			int kind = diagonal(wallBits(f, tx, tz));
			code = code * 3 + kind;
			split |= kind;
		}
		for (int q = 0; q < 4; ++q)
		{
			for (int half = 0; half <= (split != 0 ? 1 : 0); ++half)
			{
				int sx = (q & 1) * 2 - 1, sz = (q >> 1) * 2 - 1;
				if (write(texel(2 * tx + half, tz, LINK_SLICE + q), GlobalIllumination.toHalf(link(0, tx, tz, half, sx, sz)),
					GlobalIllumination.toHalf(link(1, tx, tz, half, sx, sz)), GlobalIllumination.toHalf(link(2, tx, tz, half, sx, sz)),
					GlobalIllumination.toHalf(code)))
				{
					change(tx, tz, tx, tz, LINK_SLICE + q, LINK_SLICE + q);
				}
			}
		}
	}

	private int texel(int x, int y, int slice)
	{
		return ((slice * TEX_H + y) * TEX_W + x) * 4;
	}

	/** Set a texel, returning whether it differs from what it held. */
	private boolean write(int at, short r, short g, short b, short a)
	{
		if (texels.get(at) == r && texels.get(at + 1) == g && texels.get(at + 2) == b && texels.get(at + 3) == a)
		{
			return false;
		}
		texels.put(at, r);
		texels.put(at + 1, g);
		texels.put(at + 2, b);
		texels.put(at + 3, a);
		return true;
	}

	/** Open sky in both halves of every probe slice's tiles x0..x1 - 1 by z0..z1 - 1. */
	private void fillOpen(int x0, int x1, int z0, int z1)
	{
		for (int s = PROBE_SLICE; s < TEX_D; ++s)
		{
			for (int y = z0; y < z1; ++y)
			{
				for (int x = 2 * x0; x < 2 * x1; ++x)
				{
					write(texel(x, y, s), HALF_ONE, (short) 0, HALF_UP, (short) 0);
				}
			}
		}
	}

	/** Move the probes to a base dx, dz tiles away: what lay at tile t + d is now at t; what is new is open sky. */
	private void shift(int dx, int dz)
	{
		short[] slice = new short[TEX_W * TEX_H * 4];
		short[] moved = new short[slice.length];
		int row = TEX_W * 4;
		for (int s = PROBE_SLICE; s < TEX_D; ++s)
		{
			texels.position(texel(0, 0, s));
			texels.get(slice);
			java.util.Arrays.fill(moved, (short) 0);
			int xs = Math.max(0, -dx), xe = Math.min(SIZE, SIZE - dx);
			for (int y = Math.max(0, -dz); y < Math.min(SIZE, SIZE - dz); ++y)
			{
				System.arraycopy(slice, (y + dz) * row + 2 * (xs + dx) * 4, moved, y * row + 2 * xs * 4, 2 * (xe - xs) * 4);
			}
			texels.position(texel(0, 0, s));
			texels.put(moved);
			texels.position(0);
		}
		// the tiles that came in
		fillOpen(0, SIZE, 0, Math.max(0, -dz));
		fillOpen(0, SIZE, Math.min(SIZE, SIZE - dz), SIZE);
		fillOpen(0, Math.max(0, -dx), 0, SIZE);
		fillOpen(Math.min(SIZE, SIZE - dx), SIZE, 0, SIZE);
	}

	/** World y of a slot's probe at a tile, before it moves out of solid voxels. */
	private float slotY(int slot, int t)
	{
		return slot == SURFACE ? surface[t] : ground[slot / HEIGHTS.length][t] - HEIGHTS[slot % HEIGHTS.length];
	}

	/**
	 * Decide which units are traced: those whose storey has a floor on the tile and that are near geometry. The others
	 * are open sky.
	 */
	private void selectNearby()
	{
		for (int slot = 0; slot < SLOTS; ++slot)
		{
			for (int tz = 0; tz < SIZE; ++tz)
			{
				for (int tx = 0; tx < SIZE; ++tx)
				{
					int t = tz * SIZE + tx, u = slot * SIZE * SIZE + t;
					boolean keep = (slot == SURFACE || hasFloor(slot / HEIGHTS.length, t))
						&& nearGeometry((tx + 0.5f) * TILE, slotY(slot, t), (tz + 0.5f) * TILE);
					if (!keep && dirty[u])
					{
						dirty[u] = false;
						dirtyCount--;
					}
					if (keep && !active[u] && !dirty[u])
					{
						dirty[u] = true;
						dirtyCount++;
					}
					if (!keep && active[u])
					{
						writeOpen(tx, tz, slot);
					}
					active[u] = keep;
				}
			}
		}
	}

	private void writeOpen(int tx, int tz, int slot)
	{
		for (int half = 0; half < 2; ++half)
		{
			if (write(texel(2 * tx + half, tz, PROBE_SLICE + slot), HALF_ONE, (short) 0, HALF_UP, (short) 0))
			{
				change(tx, tz, tx, tz, PROBE_SLICE + slot, PROBE_SLICE + slot);
			}
		}
	}

	private boolean nearGeometry(float px, float py, float pz)
	{
		int vx = (int) Math.floor(px / GiGrid.VOXEL), vy = (int) Math.floor(py / GiGrid.VOXEL) - grid.layer0;
		int vz = (int) Math.floor(pz / GiGrid.VOXEL);
		int r = (int) Math.ceil(NEAR_GEOMETRY / GiGrid.VOXEL);
		for (int z = Math.max(0, vz - r); z <= Math.min(grid.nz - 1, vz + r); z++)
		for (int x = Math.max(0, vx - r); x <= Math.min(grid.nx - 1, vx + r); x++)
		for (int y = Math.max(0, vy - r); y <= Math.min(grid.ny - 1, vy + r); y++)
		{
			if (!grid.occupied(x, y, z))
			{
				continue;
			}
			float dx = Math.max(Math.max(x * GiGrid.VOXEL - px, px - (x + 1) * GiGrid.VOXEL), 0);
			float dy = Math.max(Math.max((y + grid.layer0) * GiGrid.VOXEL - py, py - (y + grid.layer0 + 1) * GiGrid.VOXEL), 0);
			float dz = Math.max(Math.max(z * GiGrid.VOXEL - pz, pz - (z + 1) * GiGrid.VOXEL), 0);
			if (dx * dx + dy * dy + dz * dz <= NEAR_GEOMETRY * NEAR_GEOMETRY)
			{
				return true;
			}
		}
		return false;
	}

	/** Mark the units of every slot at tiles x0..x1 - 1 by z0..z1 - 1 (clamped) for tracing. */
	private void markDirty(int x0, int z0, int x1, int z1)
	{
		x0 = Math.max(0, x0);
		z0 = Math.max(0, z0);
		x1 = Math.min(SIZE, x1);
		z1 = Math.min(SIZE, z1);
		for (int slot = 0; slot < SLOTS; ++slot)
		{
			for (int tz = z0; tz < z1; ++tz)
			{
				for (int tx = x0; tx < x1; ++tx)
				{
					int u = (slot * SIZE + tz) * SIZE + tx;
					if (active[u] && !dirty[u])
					{
						dirty[u] = true;
						dirtyCount++;
					}
				}
			}
		}
	}

	/** Mark the tiles within reach of every tile whose voxels differ between two grids of the same place. */
	private void markChanged(GiGrid a, GiGrid b)
	{
		if (a.nx != b.nx || a.ny != b.ny || a.nz != b.nz || a.layer0 != b.layer0)
		{
			markDirty(0, 0, SIZE, SIZE);
			return;
		}
		// changed tiles, then spread reach along x and then along z
		int perTile = (int) (TILE / GiGrid.VOXEL);
		boolean[] changed = new boolean[SIZE * SIZE];
		int stride = a.nx * a.nz;
		for (int vz = 0; vz < a.nz; ++vz)
		{
			for (int vx = 0; vx < a.nx; ++vx)
			{
				int t = Math.min(vz / perTile, SIZE - 1) * SIZE + Math.min(vx / perTile, SIZE - 1);
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
		boolean[] wide = new boolean[SIZE * SIZE];
		for (int tz = 0; tz < SIZE; ++tz)
		{
			for (int tx = 0; tx < SIZE; ++tx)
			{
				if (changed[tz * SIZE + tx])
				{
					for (int x = Math.max(0, tx - REACH); x <= Math.min(SIZE - 1, tx + REACH); ++x)
					{
						wide[tz * SIZE + x] = true;
					}
				}
			}
		}
		for (int tx = 0; tx < SIZE; ++tx)
		{
			for (int tz = 0; tz < SIZE; ++tz)
			{
				if (wide[tz * SIZE + tx])
				{
					markDirty(tx, tz - REACH, tx + 1, tz + REACH + 1);
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

	/** Trace the probes of a unit: one per half of a diagonal tile, else one. Returns the rays traced. */
	private int traceUnit(int tx, int tz, int slot)
	{
		int bits = slot == SURFACE ? 0 : wallBits(slot / HEIGHTS.length, tx, tz);
		int rot = GiProbes.rotation((slot * SIZE + tz) * SIZE + tx);
		int rays = 0;
		for (int half = 0; half < (WallMap.split(bits) ? 2 : 1); ++half)
		{
			startPoint(tx, tz, slot, bits, half);
			float[] dirs = DIRECTIONS[rot], balanced = BALANCED[rot];
			// the harmonics: the share of rays out, and their directions' sum over the rays
			float a = 0, bx = 0, by = 0, bz = 0;
			for (int r = 0; r < RAYS; ++r)
			{
				int hit = grid.trace(startX, startY, startZ, dirs[r * 3], dirs[r * 3 + 1], dirs[r * 3 + 2], MAX_DISTANCE, true,
					(((slot * SIZE + tz) * SIZE + tx) * 2 + half) * RAYS + r & 0x7fffffff);
				// a blocker far off hides less of the sky than one close by: light comes round it and off what is
				// around it. A miss counts whole, a hit by how far away it is.
				float open = 1;
				if (hit >= 0)
				{
					float d = (float) Math.sqrt(sq(grid.cellX(hit) + 0.5f - startX) + sq(grid.cellY(hit) + 0.5f - startY)
						+ sq(grid.cellZ(hit) + 0.5f - startZ));
					open = smoothstep(NEAR_BLOCKER, FAR_BLOCKER, d) * FAR_OPEN;
				}
				a += open;
				bx += open * balanced[r * 3];
				by += open * balanced[r * 3 + 1];
				bz += open * balanced[r * 3 + 2];
			}
			rays += RAYS;
			if (write(texel(2 * tx + half, tz, PROBE_SLICE + slot), GlobalIllumination.toHalf(a / RAYS),
				GlobalIllumination.toHalf(bx / RAYS), GlobalIllumination.toHalf(by / RAYS), GlobalIllumination.toHalf(bz / RAYS)))
			{
				change(tx, tz, tx, tz, PROBE_SLICE + slot, PROBE_SLICE + slot);
			}
		}
		return rays;
	}

	/**
	 * Where a half of a tile's probe starts (startX, startY, startZ, voxel space): at the tile's centre, or the half's
	 * centroid, at the slot's height; out of a solid voxel (a wall's, which stands on the tile's edge) when it is in one,
	 * by moving across the tile and up or down. Never across a diagonal wall.
	 */
	private void startPoint(int tx, int tz, int slot, int bits, int half)
	{
		int t = tz * SIZE + tx;
		float mx = 0.5f, mz = 0.5f;
		if (WallMap.split(bits))
		{
			boolean slash = (bits & WallMap.DIAG_SW_NE) != 0, north = half == 0;
			mx = slash == north ? 1 / 3f : 2 / 3f;
			mz = north ? 2 / 3f : 1 / 3f;
		}
		float px = (tx + mx) * TILE, pz = (tz + mz) * TILE, y = slotY(slot, t);
		if (slot != SURFACE)
		{
			float height = HEIGHTS[slot % HEIGHTS.length];
			for (int k = 0; k < (WallMap.split(bits) ? 1 : 5); ++k)
			{
				// the centre, then a step to either side along x and along z
				float x = px + (k == 1 || k == 2 ? SHIFT[k] : 0), z = pz + (k == 3 || k == 4 ? SHIFT[k - 2] : 0);
				for (float dy : NUDGE)
				{
					if (height - dy >= MIN_HEIGHT && (k == 0 || Math.abs(dy) <= 24) && !solid(x, y + dy, z))
					{
						setStart(x, y + dy, z);
						return;
					}
				}
			}
		}
		setStart(px, y, pz);
	}

	// Highest occupied voxel over the tile, foliage included: the SURFACE probe belongs above a tree's crown, not in it.
	private float surfaceY(int tx, int tz)
	{
		int perTile = (int) (TILE / GiGrid.VOXEL);
		for (int y = 0; y < grid.ny; ++y)
			for (int z = tz * perTile; z < Math.min(grid.nz, (tz + 1) * perTile); ++z)
				for (int x = tx * perTile; x < Math.min(grid.nx, (tx + 1) * perTile); ++x)
					if (grid.cells[grid.index(x, y, z)] != 0)
						return (y + grid.layer0) * GiGrid.VOXEL - SURFACE_GAP;
		return ground[0][tz * SIZE + tx] - HEIGHTS[0];
	}

	private void setStart(float x, float y, float z)
	{
		startX = x / GiGrid.VOXEL;
		startY = y / GiGrid.VOXEL - grid.layer0;
		startZ = z / GiGrid.VOXEL;
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
