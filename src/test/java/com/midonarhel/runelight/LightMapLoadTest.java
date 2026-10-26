package com.midonarhel.runelight;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * A scene load without a GL context: the walls are known at once, the models arrive later (the voxel build), and a
 * frame loop sends the blocks as the plugin does. It measures the time to the first light, to every light, to the final
 * blocks, and the CPU the threads used; and checks that the final blocks are what one bake with every blocker makes.
 */
public class LightMapLoadTest
{
	private static final int SIZE = 184, OFFSET = 40, LOCAL = 104;
	private static final byte[] BARREL = {6, 6, 26, 26, 0x7e, 0x7e, 6, 6, 26, 26, 0x7e, 0x7e, 6, 6, 26, 26, 0x7e, 0x7e, 6, 6, 26, 26, 0x7e, 0x7e};

	/** The scene in its two states: what the walls alone say, and with the models. */
	static final class Town
	{
		LightBlockers walls, full;
		List<LightMap.Source> lights = new ArrayList<>();
	}

	static Town town(long seed, int lightCount)
	{
		Random rnd = new Random(seed);
		int[][][] flags = new int[4][LOCAL][LOCAL];
		float[] floors = new float[4 * SIZE * SIZE];
		byte[] filled = new byte[SIZE * SIZE];
		for (int p = 0; p < 4; ++p)
		{
			Arrays.fill(floors, p * SIZE * SIZE, (p + 1) * SIZE * SIZE, -240f * p);
		}
		Arrays.fill(filled, (byte) 1);
		ModelBlockers models = new ModelBlockers(SIZE * 4);
		for (int b = 0; b < 40; ++b)
		{
			int w = 6 + rnd.nextInt(8), h = 6 + rnd.nextInt(6);
			int x0 = 4 + rnd.nextInt(LOCAL - w - 8), y0 = 4 + rnd.nextInt(LOCAL - h - 8);
			for (int x = x0; x <= x0 + w; ++x)
			{
				for (int y = y0; y <= y0 + h; ++y)
				{
					boolean edge = x == x0 || x == x0 + w || y == y0 || y == y0 + h;
					if (edge && (x + y) % 7 != 0)
					{
						if (x == x0)
						{
							flags[0][x][y] |= WallMap.LOS_WEST;
							if (x > 0) flags[0][x - 1][y] |= WallMap.LOS_EAST;
						}
						else if (x == x0 + w)
						{
							flags[0][x][y] |= WallMap.LOS_EAST;
							if (x + 1 < LOCAL) flags[0][x + 1][y] |= WallMap.LOS_WEST;
						}
						if (y == y0)
						{
							flags[0][x][y] |= WallMap.LOS_SOUTH;
							if (y > 0) flags[0][x][y - 1] |= WallMap.LOS_NORTH;
						}
						else if (y == y0 + h)
						{
							flags[0][x][y] |= WallMap.LOS_NORTH;
							if (y + 1 < LOCAL) flags[0][x][y + 1] |= WallMap.LOS_SOUTH;
						}
					}
				}
			}
			int px = x0 + w / 2;
			for (int y = y0; y <= y0 + h; ++y)
			{
				if (y != y0 + 2)
				{
					flags[0][px][y] |= WallMap.LOS_WEST;
					flags[0][px - 1][y] |= WallMap.LOS_EAST;
				}
			}
			flags[0][x0 + 2][y0 + h - 2] |= WallMap.LOS_FULL;
			flags[0][x0 + 3][y0 + h - 2] |= WallMap.LOS_FULL;
			for (int k = 0; k < 14; ++k)
			{
				int gx = (OFFSET + x0 + rnd.nextInt(w)) * 4 + rnd.nextInt(4), gz = (OFFSET + y0 + rnd.nextInt(h)) * 4 + rnd.nextInt(4);
				models.set(0, gx, gz, 0, (short) 0x3f, BARREL, 0);
			}
		}
		WallMap wallMap = WallMap.build(flags, null, SIZE, OFFSET, 1);
		Town t = new Town();
		t.walls = new LightBlockers(wallMap, null, floors, filled);
		t.full = new LightBlockers(wallMap, models, floors, filled);
		for (int i = 0; i < lightCount; ++i)
		{
			int plane = i % 10 < 2 ? 1 : 0;
			float tx = OFFSET + 8 + rnd.nextFloat() * 88, ty = OFFSET + 8 + rnd.nextFloat() * 88;
			float radius = (2.5f + rnd.nextFloat() * 5) * 128;
			float height = rnd.nextFloat() < 0.3f ? 150 : 40;
			t.lights.add(new LightMap.Source(plane, (tx - OFFSET) * 128, -plane * 240f - height, (ty - OFFSET) * 128, radius,
				1f, 0.7f, 0.4f, 0.8f, false, 1f, 0));
		}
		return t;
	}

	/** Records what is sent. */
	static final class Recorder implements LightMap.Sink
	{
		final Map<Integer, long[]> blocks = new HashMap<>();
		final ByteBuffer staging = ByteBuffer.allocateDirect(32 * 32 * 4 * (LightMapBake.HEIGHT_LAYERS + LightMap.INDEX_SLICES));
		int sent;
		long last, first = -1;
		long start;

		@Override
		public void block(int plane, int bx, int bz, byte[] vis, byte[] index)
		{
			// what the texture upload does on this side of GL: copy both to a staging buffer
			staging.clear();
			if (vis != null) staging.put(vis);
			staging.clear();
			if (index != null) staging.put(index);
			int key = (plane * 64 + bz) * 64 + bx;
			if (vis == null)
			{
				blocks.remove(key);
			}
			else
			{
				blocks.put(key, signature(vis, index));
			}
			sent++;
			last = System.nanoTime() - start;
			if (first < 0)
			{
				first = last;
			}
		}

		@Override
		public void floor(int plane, int bx, int bz, float[] floor)
		{
		}
	}

	/**
	 * A block as what it says of each cell, whichever slots the lights are in: per cell the sum, over its lights, of a hash of
	 * the light's id and its visibility at every layer.
	 */
	static long[] signature(byte[] vis, byte[] index)
	{
		int n = LightMap.BLOCK * LightMap.BLOCK;
		long[] out = new long[n];
		for (int i = 0; i < n; ++i)
		{
			for (int slot = 0; slot < LightMap.SLOTS; ++slot)
			{
				int at = ((slot >> 1) * n + i) * 4 + (slot & 1) * 2;
				int id = (index[at] & 255) + 256 * (index[at + 1] & 255);
				if (id != 0)
				{
					long h = id;
					for (int layer = 0; layer < LightMapBake.HEIGHT_LAYERS; ++layer)
					{
						h = h * 1_000_003L + (vis[(layer * n + i) * 4 + slot] & 255);
					}
					out[i] += h * 0x9E3779B97F4A7C15L;
				}
			}
		}
		return out;
	}

	// ---- the calls that differ between the two versions of the map (see runBaseline in the notes)

	static LightMap make(int threads, int burst)
	{
		return new LightMap(threads, burst);
	}

	static void bind(LightMap m, LightBlockers b, boolean awaited)
	{
		m.setBlockers(b, awaited);
	}

	static int pump(LightMap m, LightMap.Sink s, boolean budget)
	{
		return budget ? m.update(24, 1_000_000, 96, s) : m.update(24, s);
	}

	static long bakes(LightMap m)
	{
		return m.stats()[0];
	}

	static long[] cpu()
	{
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		long total = 0, n = 0;
		for (ThreadInfo ti : mx.getThreadInfo(mx.getAllThreadIds()))
		{
			if (ti != null && ti.getThreadName().startsWith("RuneLight light map"))
			{
				total += Math.max(0, mx.getThreadCpuTime(ti.getThreadId()));
				n++;
			}
		}
		return new long[] {total, n};
	}

	/** The results of one scene load. */
	static final class Load
	{
		double firstBlock, firstLive, allLive, finalBlock, cpuMs;
		int blocks, sent;
		long bakes;
		Recorder rec;
	}

	/**
	 * @param awaitModels the plugin tells the map the models are coming (the new behaviour)
	 * @param modelsAtMs when the models arrive
	 */
	static Load load(Town t, int threads, int burst, boolean awaitModels, boolean budget, long modelsAtMs) throws Exception
	{
		// no thread of an earlier map is left to count
		for (int i = 0; i < 200 && cpu()[1] > 0; ++i)
		{
			Thread.sleep(10);
		}
		LightMap map = make(threads, burst);
		map.setFocus(52 * 128, 52 * 128);
		Recorder rec = new Recorder();
		long cpu0 = 0;
		long start = System.nanoTime();
		rec.start = start;
		List<LightMap.Source> lights = new ArrayList<>();
		for (LightMap.Source s : t.lights)
		{
			lights.add(new LightMap.Source(s.plane, s.x, s.y, s.z, s.radius, s.red, s.green, s.blue, s.strength, false, 1f, 0));
		}
		Load out = new Load();
		out.rec = rec;
		boolean added = false;
		long lastSent = start;
		int lastCount = 0;
		while (true)
		{
			long now = System.nanoTime();
			long ms = (now - start) / 1_000_000;
			boolean modelsIn = ms >= modelsAtMs;
			bind(map, modelsIn ? t.full : t.walls, awaitModels && !modelsIn);
			if (!added)
			{
				for (LightMap.Source s : lights)
				{
					map.add(s);
				}
				added = true;
			}
			pump(map, rec, budget);
			double at = (System.nanoTime() - start) / 1e6;
			if (rec.sent > 0 && out.firstBlock == 0)
			{
				out.firstBlock = rec.first / 1e6;
			}
			int live = 0;
			for (LightMap.Source s : lights)
			{
				live += LightMap.isLive(s) ? 1 : 0;
			}
			if (live > 0 && out.firstLive == 0)
			{
				out.firstLive = at;
			}
			if (live == lights.size() && out.allLive == 0)
			{
				out.allLive = at;
			}
			if (rec.sent != lastCount)
			{
				lastCount = rec.sent;
				lastSent = System.nanoTime();
			}
			// done: the models are in, every light live, and nothing sent for a while
			if (ms > modelsAtMs + 400 && live == lights.size() && System.nanoTime() - lastSent > 300_000_000L)
			{
				break;
			}
			if (at > 20_000)
			{
				throw new AssertionError("never settled");
			}
			long sleep = 16 - (System.nanoTime() - now) / 1_000_000;
			if (sleep > 0)
			{
				Thread.sleep(sleep);
			}
		}
		out.finalBlock = rec.last / 1e6;
		out.cpuMs = (cpu()[0] - cpu0) / 1e6;
		out.blocks = rec.blocks.size();
		out.sent = rec.sent;
		out.bakes = bakes(map);
		map.free();
		return out;
	}

	/** The map after one bake with every blocker, all blocks sent. */
	static Map<Integer, long[]> reference(Town t)
	{
		LightMap map = new LightMap(0);
		map.setFocus(52 * 128, 52 * 128);
		map.setBlockers(t.full);
		for (LightMap.Source s : t.lights)
		{
			map.add(new LightMap.Source(s.plane, s.x, s.y, s.z, s.radius, s.red, s.green, s.blue, s.strength, false, 1f, 0));
		}
		map.runTasks();
		Recorder rec = new Recorder();
		map.update(1_000_000, rec);
		return rec.blocks;
	}

	@Test
	public void finalBlocksAreWhatOneBakeWithEveryBlockerMakes() throws Exception
	{
		Town t = town(5, 14);
		Map<Integer, long[]> expected = reference(t);
		assertTrue(expected.size() > 20);
		// the models arrive after the wait ended, before it, and never being waited for
		long[] arrivals = {50, 400, 1600};
		for (long at : arrivals)
		{
			for (boolean await : new boolean[] {false, true})
			{
				Load l = load(t, 2, 4, await, true, at);
				assertEquals("blocks, models at " + at + " awaited " + await, expected.keySet(), l.rec.blocks.keySet());
				int bad = 0;
				for (Map.Entry<Integer, long[]> e : expected.entrySet())
				{
					long[] got = l.rec.blocks.get(e.getKey());
					bad += Arrays.equals(e.getValue(), got) ? 0 : 1;
				}
				System.out.println("DIFF models at " + at + " awaited " + await + ": " + bad + " of " + expected.size() + " blocks differ");
				for (Map.Entry<Integer, long[]> e : expected.entrySet())
				{
					long[] got = l.rec.blocks.get(e.getKey());
					assertTrue("block " + e.getKey() + " models at " + at + " awaited " + await, Arrays.equals(e.getValue(), got));
				}
			}
		}
	}

	@Test
	public void sceneLoad() throws Exception
	{
		int count = Integer.getInteger("lights", 150);
		int threads = Integer.getInteger("threads", 3), burst = Integer.getInteger("burst", 3);
		boolean await = Boolean.parseBoolean(System.getProperty("await", "true"));
		boolean budget = Boolean.parseBoolean(System.getProperty("budget", "true"));
		long modelsAt = Long.getLong("modelsAt", 3000);
		int runs = Integer.getInteger("runs", 3);
		Town t = town(11, count);
		// warm up the JIT, as a running client is
		load(town(3, 40), threads, burst, await, budget, 100);
		System.out.printf("LOAD %d lights, threads %d/%d, awaited %b, budget %b, models at %d ms%n", count, threads, burst, await, budget, modelsAt);
		for (int i = 0; i < runs; ++i)
		{
			Load l = load(t, threads, burst, await, budget, modelsAt);
			System.out.printf("LOAD run %d: first block %.0f ms, first live %.0f, all live %.0f, final block %.0f ms, cpu %.0f ms, %d sends, %d blocks, %d bakes%n",
				i, l.firstBlock, l.firstLive, l.allLive, l.finalBlock, l.cpuMs, l.sent, l.blocks, l.bakes);
		}
	}
}
