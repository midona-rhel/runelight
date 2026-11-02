package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.dev.SceneDump;
import com.midonarhel.lumina.scene.ZoneIndexer;
import com.midonarhel.lumina.scene.ZoneSnapshot;
import java.nio.IntBuffer;
import java.util.Arrays;

/**
 * The zones' opaque streams indexed as the upload indexes them (ZoneIndexer), per dump: how many vertices are
 * distinct, the bytes the buffers hold before and after, and the indexing's time for the whole scene on one thread
 * (the median of 15 runs after 3). ./gradlew indexStats -Pargs="nightref tree1"
 */
public final class IndexStats
{
	public static void main(String[] args) throws Exception
	{
		for (String name : args)
		{
			SceneDump.Loaded dump = RenderDump.load(name);
			long stream = 0, distinct = 0, before = 0, after = 0;
			int zones = 0, indexed = 0, shorts = 0;
			ZoneIndexer ix = new ZoneIndexer();
			for (java.util.List<ZoneSnapshot> view : dump.zones)
			{
				for (ZoneSnapshot s : view)
				{
					if (s.vertsO == null)
					{
						continue;
					}
					IntBuffer out = IntBuffer.allocate(Math.max(s.sizeO * 3 * 6, s.vertsO.length));
					ZoneIndexer.Result r = ix.write(s.vertsO, s.vertsO.length, out);
					if (!Arrays.equals(ZoneIndexer.expand(Arrays.copyOf(out.array(), out.position()), r), s.vertsO))
					{
						throw new IllegalStateException("zone " + s.x + "," + s.z + ": the indexed buffer is not the stream");
					}
					zones++;
					stream += r.streamVertices;
					before += s.vertsO.length * 4L;
					after += r.ints * 4L;
					if (r.indexType != 0)
					{
						indexed++;
						distinct += r.vertices;
						shorts += r.indexSize() == 2 ? 1 : 0;
					}
					else
					{
						distinct += r.streamVertices;
					}
				}
			}
			double[] ms = new double[15];
			IntBuffer direct = null;
			for (int run = -3; run < ms.length; ++run)
			{
				long t0 = System.nanoTime();
				for (java.util.List<ZoneSnapshot> view : dump.zones)
				{
					for (ZoneSnapshot s : view)
					{
						if (s.vertsO != null)
						{
							int cap = Math.max(s.sizeO * 3 * 6, s.vertsO.length);
							if (direct == null || direct.capacity() < cap)
							{
								// as the upload writes it: into a mapping, native order, direct
								direct = java.nio.ByteBuffer.allocateDirect(cap * 4).order(java.nio.ByteOrder.nativeOrder()).asIntBuffer();
							}
							IntBuffer out = direct.duplicate();
							out.limit(cap);
							ix.write(s.vertsO, s.vertsO.length, out.slice());
						}
					}
				}
				if (run >= 0)
				{
					ms[run] = (System.nanoTime() - t0) / 1e6;
				}
			}
			Arrays.sort(ms);
			System.out.printf("INDEX %s: %d zones, %d indexed (%d with 16-bit indices); %,d stream vertices -> %,d distinct (%.1f %%); "
					+ "buffers %.1f MB -> %.1f MB (%.1f %%); indexing the scene %.1f ms on one thread (min %.1f)%n",
				name, zones, indexed, shorts, stream, distinct, 100.0 * distinct / stream, before / 1e6, after / 1e6,
				100.0 * after / before, ms[ms.length / 2], ms[0]);
		}
		System.exit(0);
	}

	private IndexStats()
	{
	}
}
