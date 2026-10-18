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

	// per tile: body label (-1 none), distance to the nearest land in tiles (1 next to land), flow in tiles per
	// second, wave energy 0-1
	final int[] body;
	final int[] shore;
	final float[] flowX, flowZ;
	final float[] energy;
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
			// narrow runs faster
			speed *= clamp(1.3f - 0.15f * shore[i], 0.7f, 1.3f);
			// and so does water running downhill
			float drop = Math.abs(surfaceAt(x + (gx < 0 ? 1 : -1), y, i) - surface[i])
				+ Math.abs(surfaceAt(x, y + (gz < 0 ? 1 : -1), i) - surface[i]);
			speed *= 1 + Math.min(drop / STEEP, 2);
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
		if (x < 0 || y < 0 || x >= size || y >= size || !water[y * size + x])
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
