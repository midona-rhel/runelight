package com.midonarhel.runelight;

import java.util.Arrays;

/**
 * Connected bodies of water in a square tile grid, what kind each is, and how its water flows. Pure tile logic,
 * filled in by {@link WaterMap} from the scene and tested on synthetic grids.
 *
 * Flow follows the map rather than being simulated. Where the world flow map ({@link WaterFlow}) knows a tile, its
 * flow is used as is; elsewhere it is guessed from the scene alone: a body that runs off the scene edge or opens into
 * wide water flows toward that outlet (rivers into the sea or a lake, a river crossing the scene downhill), and small
 * enclosed pools and moats stay still. Either way the sea pushes gently toward its shores.
 *
 * The map and the guess give the direction and how much water a channel carries; how fast it runs at each tile comes
 * from the scene's own shape ({@link #shapeFlow}): fastest in the middle of a channel and slow at its banks, faster
 * where the channel narrows (the same water through a smaller cross-section, the bed being shallower near the shore
 * too) and over drops, slower where it widens into a lake. A shaped world flow map ({@link #mapShaped}) already has
 * all of that but the drops, worked out from the map at a finer scale than the scene's tiles, so its tiles only get
 * those.
 */
final class WaterBodies
{
	static final int NONE = 0;
	// enclosed, small or narrow: still water
	static final int POOL = 1;
	// enclosed with open water away from the shore
	static final int LAKE = 2;
	// narrow and running off the scene edge
	static final int RIVER = 3;
	// large, or open water running off the scene edge, or tiles the game marks as sea
	static final int OCEAN = 4;

	// tiles from shore where water counts as open: rivers flow into it
	static final int OPEN_LAKE = 3;
	static final int OPEN_OCEAN = 4;
	// a body running off the scene edge this big, or this far from shore, is sea
	static final int OCEAN_SIZE = 400;
	static final int OCEAN_SHORE = 6;
	private static final int MAX_DISTANCE = 64;

	// tiles per second
	static final float RIVER_SPEED = 0.7f;
	static final float ONSHORE_SPEED = 0.18f;
	// surface height difference (world units) per tile at which a river runs twice as fast (rapids, falls)
	private static final float STEEP = 24f;
	// Speed across a channel: parabolic from PROFILE_BANK of the middle's at the bank to the middle, scaled so the
	// channel's mean is unchanged
	private static final float PROFILE_BANK = 0.3f;
	// Speed along a channel: the cross-section of a channel REFERENCE_RIDGE tiles from bank to middle runs at the
	// given speed, others by (that cross-section / theirs) ^ CONTINUITY, within CONTINUITY_MIN to CONTINUITY_MAX (a
	// lake would bring the water to a stop, a ditch would shoot it out)
	private static final float REFERENCE_RIDGE = 2.5f;
	private static final float CONTINUITY = 0.6f;
	private static final float CONTINUITY_MIN = 0.3f, CONTINUITY_MAX = 1.8f;
	// fastest any water runs, tiles per second
	private static final float MAX_SPEED = 1.6f;
	// speed of the middle of a typical channel in a shaped world flow map: faster is a narrows, which runs rough
	private static final float SHAPED_MIDDLE = 0.75f;
	// tiles per second below which water counts as still for the shaping and the turbulence
	private static final float STILL = 0.02f;
	// heights within this of the lowest exit count as equally low (world units)
	private static final int EXIT_TOLERANCE = 16;
	private static final int SMOOTH_PASSES = 2;

	final int size;
	final boolean[] water;
	// surface height of each tile, y down (larger is lower)
	final float[] surface;
	// tiles the game's own textures mark as open sea
	final boolean[] sea;
	// tiles the scene doesn't have (not loaded): neither land nor water, the water may go on there
	final boolean[] unknown;
	// flow from the world flow map in tiles per second, NaN where it has none
	final float[] mapFlowX, mapFlowZ;
	// whether the map's speeds already follow the channel's shape (WaterFlow.shaped): only drops are added to them
	boolean mapShaped;

	// per tile: body label (-1 none), distance to the nearest land in tiles (1 next to land), flow in tiles per
	// second, wave energy 0-1
	final int[] body;
	final int[] shore;
	final float[] flowX, flowZ;
	final float[] energy;
	// how rough the water runs 0-1: where it speeds up through a narrows or over a drop
	final float[] turbulence;
	// distance to shore of the middle of the channel each tile is in, tiles
	final float[] ridge;
	// how much faster than the map's flow each tile with map flow runs after shaping (1 where still)
	final float[] gain;
	// the sea's push toward its shores, added to the flow after smoothing
	private final float[] pushX, pushZ;
	// per body
	int bodies;
	int[] bodyClass = new int[0];
	int[] bodySize = new int[0];

	private final int[] queue;
	private final int[] dist;

	WaterBodies(int size)
	{
		this.size = size;
		int n = size * size;
		water = new boolean[n];
		surface = new float[n];
		sea = new boolean[n];
		unknown = new boolean[n];
		mapFlowX = new float[n];
		mapFlowZ = new float[n];
		Arrays.fill(mapFlowX, Float.NaN);
		Arrays.fill(mapFlowZ, Float.NaN);
		pushX = new float[n];
		pushZ = new float[n];
		body = new int[n];
		shore = new int[n];
		flowX = new float[n];
		flowZ = new float[n];
		energy = new float[n];
		turbulence = new float[n];
		ridge = new float[n];
		gain = new float[n];
		queue = new int[n];
		dist = new int[n];
	}

	void solve()
	{
		label();
		shoreDistance();
		classify();
		Arrays.fill(flowX, 0);
		Arrays.fill(flowZ, 0);
		Arrays.fill(pushX, 0);
		Arrays.fill(pushZ, 0);
		for (int b = 0; b < bodies; ++b)
		{
			flow(b);
		}
		for (int i = 0; i < water.length; ++i)
		{
			if (water[i] && hasMapFlow(i))
			{
				flowX[i] = mapFlowX[i];
				flowZ[i] = mapFlowZ[i];
			}
		}
		smoothFlow();
		shapeFlow();
		for (int i = 0; i < water.length; ++i)
		{
			flowX[i] += pushX[i];
			flowZ[i] += pushZ[i];
		}
		energies();
	}

	// whether the water runs on past the scene: the grid edge, or tiles that aren't loaded
	private boolean edge(int i)
	{
		int x = i % size, y = i / size;
		return x == 0 || y == 0 || x == size - 1 || y == size - 1
			|| unknown[i - 1] || unknown[i + 1] || unknown[i - size] || unknown[i + size];
	}

	private void label()
	{
		Arrays.fill(body, -1);
		int count = 0;
		int[] sizes = new int[16];
		for (int start = 0; start < water.length; ++start)
		{
			if (!water[start] || body[start] != -1)
			{
				continue;
			}
			if (count == sizes.length)
			{
				sizes = Arrays.copyOf(sizes, count * 2);
			}
			int head = 0, tail = 0;
			queue[tail++] = start;
			body[start] = count;
			while (head < tail)
			{
				int i = queue[head++];
				int x = i % size, y = i / size;
				tail = visit(x - 1, y, count, tail);
				tail = visit(x + 1, y, count, tail);
				tail = visit(x, y - 1, count, tail);
				tail = visit(x, y + 1, count, tail);
			}
			sizes[count++] = tail;
		}
		bodies = count;
		bodySize = Arrays.copyOf(sizes, count);
	}

	private int visit(int x, int y, int label, int tail)
	{
		if (x < 0 || y < 0 || x >= size || y >= size)
		{
			return tail;
		}
		int j = y * size + x;
		if (water[j] && body[j] == -1)
		{
			body[j] = label;
			queue[tail++] = j;
		}
		return tail;
	}

	/**
	 * Breadth-first distance from water next to land. Off the grid is not land: the scene edge is not a shore.
	 */
	private void shoreDistance()
	{
		Arrays.fill(shore, 0);
		int tail = 0;
		for (int i = 0; i < water.length; ++i)
		{
			if (water[i] && touchesLand(i))
			{
				shore[i] = 1;
				queue[tail++] = i;
			}
		}
		int head = 0;
		while (head < tail)
		{
			int i = queue[head++];
			int x = i % size, y = i / size;
			int d = shore[i] + 1;
			if (d > MAX_DISTANCE)
			{
				continue;
			}
			tail = spread(shore, x - 1, y, d, tail);
			tail = spread(shore, x + 1, y, d, tail);
			tail = spread(shore, x, y - 1, d, tail);
			tail = spread(shore, x, y + 1, d, tail);
		}
		// bodies with no land in sight
		for (int i = 0; i < water.length; ++i)
		{
			if (water[i] && shore[i] == 0)
			{
				shore[i] = MAX_DISTANCE;
			}
		}
	}

	private int spread(int[] field, int x, int y, int d, int tail)
	{
		if (x < 0 || y < 0 || x >= size || y >= size)
		{
			return tail;
		}
		int j = y * size + x;
		if (water[j] && field[j] == 0)
		{
			field[j] = d;
			queue[tail++] = j;
		}
		return tail;
	}

	private boolean touchesLand(int i)
	{
		int x = i % size, y = i / size;
		return land(x - 1, y) || land(x + 1, y) || land(x, y - 1) || land(x, y + 1);
	}

	private boolean land(int x, int y)
	{
		return x >= 0 && y >= 0 && x < size && y < size && !water[y * size + x] && !unknown[y * size + x];
	}

	private void classify()
	{
		bodyClass = new int[bodies];
		boolean[] edge = new boolean[bodies];
		boolean[] hasSea = new boolean[bodies];
		int[] maxShore = new int[bodies];
		for (int i = 0; i < water.length; ++i)
		{
			int b = body[i];
			if (b < 0)
			{
				continue;
			}
			edge[b] |= edge(i);
			hasSea[b] |= sea[i];
			maxShore[b] = Math.max(maxShore[b], shore[i]);
		}
		for (int b = 0; b < bodies; ++b)
		{
			if (hasSea[b] || edge[b] && (bodySize[b] >= OCEAN_SIZE || maxShore[b] >= OCEAN_SHORE))
			{
				bodyClass[b] = OCEAN;
			}
			else if (maxShore[b] >= OPEN_LAKE)
			{
				bodyClass[b] = LAKE;
			}
			else if (edge[b])
			{
				bodyClass[b] = RIVER;
			}
			else
			{
				// enclosed and narrow: ponds, fountains, moats
				bodyClass[b] = POOL;
			}
		}
	}

	private void flow(int b)
	{
		int cls = bodyClass[b];
		if (cls == POOL)
		{
			return;
		}

		Arrays.fill(dist, -1);
		int tail = 0;
		int open = cls == OCEAN ? OPEN_OCEAN : OPEN_LAKE;
		for (int i = 0; i < water.length; ++i)
		{
			if (body[i] == b && shore[i] >= open)
			{
				dist[i] = 0;
				queue[tail++] = i;
			}
		}
		boolean channel = tail == 0;
		if (channel)
		{
			tail = riverExit(b);
			if (tail == 0)
			{
				return;
			}
		}

		int head = 0;
		while (head < tail)
		{
			int i = queue[head++];
			int x = i % size, y = i / size;
			int d = dist[i] + 1;
			tail = step(b, x - 1, y, d, tail);
			tail = step(b, x + 1, y, d, tail);
			tail = step(b, x, y - 1, d, tail);
			tail = step(b, x, y + 1, d, tail);
		}

		for (int i = 0; i < water.length; ++i)
		{
			if (body[i] != b || dist[i] <= 0)
			{
				continue;
			}
			int x = i % size, y = i / size;
			int here = dist[i];
			float gx = dist(b, x + 1, y, here) - dist(b, x - 1, y, here);
			float gz = dist(b, x, y + 1, here) - dist(b, x, y - 1, here);
			float len = (float) Math.sqrt(gx * gx + gz * gz);
			if (len < 1e-4f)
			{
				continue;
			}
			// Rivers run the whole way. In a lake or the sea only channels do: along a plain shore the distance to
			// open water plus the distance to land is about the width of the shallows, and it grows up a channel.
			float speed = channel ? RIVER_SPEED : RIVER_SPEED * clamp((here + shore[i] - open) / 3f, 0, 1);
			if (speed <= 0)
			{
				continue;
			}
			flowX[i] = -gx / len * speed;
			flowZ[i] = -gz / len * speed;
		}

		if (cls == OCEAN)
		{
			// the sea pushes toward its shores, most strongly in the shallows
			for (int i = 0; i < water.length; ++i)
			{
				if (body[i] != b || shore[i] > OPEN_OCEAN)
				{
					continue;
				}
				int x = i % size, y = i / size;
				int here = shore[i];
				float gx = shoreAt(b, x + 1, y, here) - shoreAt(b, x - 1, y, here);
				float gz = shoreAt(b, x, y + 1, here) - shoreAt(b, x, y - 1, here);
				float len = (float) Math.sqrt(gx * gx + gz * gz);
				if (len < 1e-4f)
				{
					continue;
				}
				float s = ONSHORE_SPEED * (1 - (here - 1) / (float) OPEN_OCEAN);
				pushX[i] = -gx / len * s;
				pushZ[i] = -gz / len * s;
			}
		}
	}

	boolean hasMapFlow(int i)
	{
		return !Float.isNaN(mapFlowX[i]);
	}

	private int step(int b, int x, int y, int d, int tail)
	{
		if (x < 0 || y < 0 || x >= size || y >= size)
		{
			return tail;
		}
		int j = y * size + x;
		if (body[j] == b && dist[j] == -1)
		{
			dist[j] = d;
			queue[tail++] = j;
		}
		return tail;
	}

	private int dist(int b, int x, int y, int fallback)
	{
		if (x < 0 || y < 0 || x >= size || y >= size)
		{
			return fallback;
		}
		int j = y * size + x;
		return body[j] == b && dist[j] >= 0 ? dist[j] : fallback;
	}

	private int shoreAt(int b, int x, int y, int fallback)
	{
		if (x < 0 || y < 0 || x >= size || y >= size)
		{
			return fallback;
		}
		int j = y * size + x;
		return body[j] == b ? shore[j] : 0;
	}

	private float surfaceAt(int x, int y, int fallback)
	{
		if (x < 0 || y < 0 || x >= size || y >= size || !water[y * size + x] || body[y * size + x] != body[fallback])
		{
			return surface[fallback];
		}
		return surface[y * size + x];
	}

	/**
	 * Seed the queue with the edge tiles a river leaves the scene through: the lowest ones, and of those (a level
	 * river) the widest connected run. Returns the queue length.
	 */
	private int riverExit(int b)
	{
		float lowest = -Float.MAX_VALUE;
		for (int i = 0; i < water.length; ++i)
		{
			if (body[i] == b && edge(i))
			{
				lowest = Math.max(lowest, surface[i]);
			}
		}
		if (lowest == -Float.MAX_VALUE)
		{
			return 0;
		}

		// group the candidate edge tiles into runs along the edge, keep the largest
		int[] run = new int[water.length];
		Arrays.fill(run, -1);
		int best = -1, bestCount = 0, runs = 0;
		for (int i = 0; i < water.length; ++i)
		{
			if (!candidate(b, i, lowest) || run[i] != -1)
			{
				continue;
			}
			int head = 0, tail = 0;
			queue[tail++] = i;
			run[i] = runs;
			while (head < tail)
			{
				int j = queue[head++];
				int x = j % size, y = j / size;
				for (int dy = -1; dy <= 1; ++dy)
				{
					for (int dx = -1; dx <= 1; ++dx)
					{
						int nx = x + dx, ny = y + dy;
						if (nx < 0 || ny < 0 || nx >= size || ny >= size)
						{
							continue;
						}
						int k = ny * size + nx;
						if (run[k] == -1 && candidate(b, k, lowest))
						{
							run[k] = runs;
							queue[tail++] = k;
						}
					}
				}
			}
			if (tail > bestCount)
			{
				bestCount = tail;
				best = runs;
			}
			++runs;
		}

		int tail = 0;
		for (int i = 0; i < water.length; ++i)
		{
			if (run[i] == best)
			{
				dist[i] = 0;
				queue[tail++] = i;
			}
		}
		return tail;
	}

	private boolean candidate(int b, int i, float lowest)
	{
		return body[i] == b && edge(i) && surface[i] >= lowest - EXIT_TOLERANCE;
	}

	private void smoothFlow()
	{
		float[] sx = new float[flowX.length], sz = new float[flowZ.length];
		for (int pass = 0; pass < SMOOTH_PASSES; ++pass)
		{
			for (int i = 0; i < water.length; ++i)
			{
				int b = body[i];
				if (b >= 0 && hasMapFlow(i))
				{
					// already smooth, and right: it only steers the guessed tiles next to it
					sx[i] = flowX[i];
					sz[i] = flowZ[i];
					continue;
				}
				if (b < 0 || bodyClass[b] == POOL)
				{
					sx[i] = sz[i] = 0;
					continue;
				}
				int x = i % size, y = i / size;
				float ax = flowX[i] * 2, az = flowZ[i] * 2, w = 2;
				for (int k = 0; k < 4; ++k)
				{
					int nx = x + (k == 0 ? -1 : k == 1 ? 1 : 0), ny = y + (k == 2 ? -1 : k == 3 ? 1 : 0);
					if (nx < 0 || ny < 0 || nx >= size || ny >= size)
					{
						continue;
					}
					int j = ny * size + nx;
					if (body[j] == b)
					{
						ax += flowX[j];
						az += flowZ[j];
						w += 1;
					}
				}
				sx[i] = ax / w;
				sz[i] = az / w;
			}
			System.arraycopy(sx, 0, flowX, 0, sx.length);
			System.arraycopy(sz, 0, flowZ, 0, sz.length);
		}
	}

	/**
	 * Speed up or slow down each flowing tile by the shape of its channel: across it a parabolic profile by the
	 * distance to the bank relative to the channel's half width, along it the cross-section (continuity: the same
	 * water through a narrower or shallower channel runs faster), and over drops in the surface. Also how turbulent
	 * the water runs there.
	 */
	private void shapeFlow()
	{
		ridges();
		float refArea = crossSection(REFERENCE_RIDGE);
		for (int i = 0; i < water.length; ++i)
		{
			turbulence[i] = 0;
			gain[i] = 1;
			float fx = flowX[i], fz = flowZ[i];
			float speed = (float) Math.sqrt(fx * fx + fz * fz);
			if (body[i] < 0 || speed < STILL)
			{
				continue;
			}
			if (mapShaped && hasMapFlow(i))
			{
				// the map knows the channel at a finer scale than the scene's tiles: only the drops are the scene's
				float steep = Math.min(drop(i, fx / speed, fz / speed) / STEEP, 2);
				float shaped = Math.min(speed * (1 + steep), MAX_SPEED);
				gain[i] = shaped / speed;
				flowX[i] = fx * gain[i];
				flowZ[i] = fz * gain[i];
				turbulence[i] = clamp(0.6f * (speed / SHAPED_MIDDLE - 1) + 0.8f * steep, 0, 1) * smoothstep(STILL, 0.2f, speed);
				continue;
			}
			float r = ridge[i];
			// 0 at the bank, 1 in the middle
			float s = clamp((shore[i] - 0.5f) / Math.max(r - 0.5f, 0.5f), 0, 1);
			float profile = (PROFILE_BANK + (1 - PROFILE_BANK) * s * (2 - s)) / (PROFILE_BANK + (1 - PROFILE_BANK) * 2 / 3f);
			float continuity = clamp((float) Math.pow(refArea / crossSection(r), CONTINUITY), CONTINUITY_MIN, CONTINUITY_MAX);
			float steep = Math.min(drop(i, fx / speed, fz / speed) / STEEP, 2);
			float shaped = Math.min(speed * profile * continuity * (1 + steep), MAX_SPEED);
			gain[i] = shaped / speed;
			flowX[i] = fx / speed * shaped;
			flowZ[i] = fz / speed * shaped;
			// rough where the water is squeezed or falls
			turbulence[i] = clamp(0.6f * (continuity - 1) + 0.8f * steep, 0, 1) * smoothstep(STILL, 0.2f, speed);
		}
	}

	/**
	 * The distance to shore of the middle of the channel each water tile is in: carried down from the farthest tiles
	 * toward the banks, averaging where channels meet.
	 */
	private void ridges()
	{
		// tiles by distance to shore, farthest first (counting sort)
		int[] count = new int[MAX_DISTANCE + 2];
		for (int i = 0; i < water.length; ++i)
		{
			if (body[i] >= 0)
			{
				++count[shore[i]];
			}
		}
		int[] start = new int[MAX_DISTANCE + 2];
		for (int d = MAX_DISTANCE - 1; d >= 0; --d)
		{
			start[d] = start[d + 1] + count[d + 1];
		}
		int tiles = start[0] + count[0];
		for (int i = 0; i < water.length; ++i)
		{
			if (body[i] >= 0)
			{
				queue[start[shore[i]]++] = i;
			}
		}
		for (int k = 0; k < tiles; ++k)
		{
			int i = queue[k];
			int x = i % size, y = i / size;
			float sum = 0;
			int n = 0;
			for (int d = 0; d < 4; ++d)
			{
				int nx = x + (d == 0 ? -1 : d == 1 ? 1 : 0), ny = y + (d == 2 ? -1 : d == 3 ? 1 : 0);
				if (nx < 0 || ny < 0 || nx >= size || ny >= size)
				{
					continue;
				}
				int j = ny * size + nx;
				if (body[j] == body[i] && shore[j] > shore[i])
				{
					sum += ridge[j];
					++n;
				}
			}
			ridge[i] = n > 0 ? Math.max(shore[i], sum / n) : shore[i];
		}
	}

	/**
	 * Cross-section of a channel whose middle is {@code ridge} tiles from the bank, in tiles times world units of
	 * depth: the bed deepens away from the shore like {@link WaterMap}'s.
	 */
	static float crossSection(float ridge)
	{
		float area = 0;
		int whole = (int) ridge;
		for (int d = 1; d <= whole; ++d)
		{
			area += 2 * bedDepth(d);
		}
		return area + 2 * (ridge - whole) * bedDepth(whole + 1);
	}

	// the bed's depth across a tile d tiles from the shore: between its corners' depth levels d - 1 and d
	private static float bedDepth(int d)
	{
		return Math.max((WaterMap.depthForLevel(d - 1) + WaterMap.depthForLevel(Math.min(d, WaterMap.MAX_LEVEL))) * 0.5f,
			WaterMap.MIN_DEPTH);
	}

	/**
	 * How far the surface falls (world units) from the tile upstream of i to the one downstream, over the larger of
	 * the two steps: water speeds up toward a lip and below it.
	 */
	private float drop(int i, float dx, float dz)
	{
		int x = i % size, y = i / size;
		// the neighbours the flow points at, diagonals included past 22.5 degrees
		int sx = Math.abs(dx) > 0.38f ? (int) Math.signum(dx) : 0, sz = Math.abs(dz) > 0.38f ? (int) Math.signum(dz) : 0;
		if (sx == 0 && sz == 0)
		{
			return 0;
		}
		// y down: the surface downstream is larger where it falls
		float down = surfaceAt(x + sx, y + sz, i) - surface[i];
		float up = surface[i] - surfaceAt(x - sx, y - sz, i);
		return Math.max(0, Math.max(down, up));
	}

	private void energies()
	{
		for (int i = 0; i < water.length; ++i)
		{
			int b = body[i];
			if (b < 0)
			{
				energy[i] = 0;
				continue;
			}
			float s = shore[i];
			switch (bodyClass[b])
			{
				case OCEAN:
					energy[i] = 0.5f + 0.5f * smoothstep(1, 6, s);
					break;
				case LAKE:
					energy[i] = 0.25f + 0.2f * smoothstep(1, 5, s);
					break;
				case RIVER:
					energy[i] = 0.25f;
					break;
				default:
					energy[i] = 0.08f;
			}
		}
	}

	private static float clamp(float v, float lo, float hi)
	{
		return Math.max(lo, Math.min(hi, v));
	}

	private static float smoothstep(float a, float b, float v)
	{
		float t = clamp((v - a) / (b - a), 0, 1);
		return t * t * (3 - 2 * t);
	}
}
