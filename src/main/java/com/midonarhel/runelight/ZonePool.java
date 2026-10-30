package com.midonarhel.runelight;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.TreeMap;
import static org.lwjgl.opengl.GL33C.*;

/**
 * The zones' vertex buffers, each with the vertex array that reads it, kept for reuse instead of deleted and made again
 * on every load (a teleport made and deleted 529 of each, 2026-10-30; GL object churn is slow on Windows drivers). A
 * freed zone's buffers come here without a GL call; a new zone takes the smallest kept buffer that holds it and is not
 * much larger, else a kept one given new storage under the same name (glBufferData), else a new one. Mapping a
 * buffer invalidates its old contents (VBO.map), so the driver can orphan storage the GPU may still read. What is
 * kept beyond the scene in use is deleted a few at a time by the scheduler (SceneJobs).
 *
 * Client thread only.
 */
final class ZonePool
{
	// a kept buffer is used for a zone that needs at least this share of it
	private static final double FIT = 1.5;
	private static final int FIT_SLACK = 16 << 10;
	// storage is given in these steps, so a buffer fits the zones of a size near its own
	private static final int GRANULE = 4 << 10;
	// kept beyond the zones in use: as much as the zones in use, at least MIN_KEPT and at most MAX_KEPT (a scene's zones
	// were 112–376 MB, 2026-10-30: a whole spare scene kept would double the zones' share of the GPU's memory)
	private static final long MIN_KEPT = 64L << 20;
	private static final long MAX_KEPT = 256L << 20;

	private final TreeMap<Integer, ArrayDeque<VBO>> kept = new TreeMap<>();
	private long keptBytes;
	private long liveBytes;
	private int keptCount;
	private boolean trimQueued;

	/** A buffer of at least bytes for a zone, with its vertex array, not mapped. */
	VBO acquire(int bytes)
	{
		Map.Entry<Integer, ArrayDeque<VBO>> e = kept.ceilingEntry(bytes);
		VBO vbo;
		if (e != null && e.getKey() <= bytes * FIT + FIT_SLACK)
		{
			vbo = take(e);
			vbo.size = bytes;
		}
		else if (!kept.isEmpty())
		{
			// none fits: the largest kept one takes new storage under its name, its vertex array still reads it
			vbo = take(kept.lastEntry());
			vbo.respecify(bytes, roundUp(bytes));
		}
		else
		{
			vbo = new VBO(bytes);
			vbo.init(GL_STATIC_DRAW, roundUp(bytes));
			vbo.vao = glGenVertexArrays();
			SceneLoadReport.arraysCreated++;
			Zone.setupVao(vbo.vao, vbo.bufId);
		}
		liveBytes += vbo.capacity;
		return vbo;
	}

	/** A zone is done with this buffer: keep it (no GL call unless it is still mapped). */
	void release(VBO vbo, SceneJobs jobs)
	{
		if (vbo.mapped)
		{
			vbo.unmap();
		}
		liveBytes -= vbo.capacity;
		kept.computeIfAbsent(vbo.capacity, k -> new ArrayDeque<>()).add(vbo);
		keptBytes += vbo.capacity;
		keptCount++;
		if (!trimQueued && keptBytes > keptLimit())
		{
			trimQueued = true;
			jobs.add("zone buffer trim", SceneJobs.UPKEEP, this::trim);
		}
	}

	/** Delete kept buffers, largest first, until no more is kept than keptLimit. */
	private boolean trim(long deadline)
	{
		do
		{
			if (keptBytes <= keptLimit() || kept.isEmpty())
			{
				trimQueued = false;
				return true;
			}
			delete(take(kept.lastEntry()));
		}
		while (System.nanoTime() < deadline);
		return false;
	}

	private long keptLimit()
	{
		return Math.max(MIN_KEPT, Math.min(liveBytes, MAX_KEPT));
	}

	private VBO take(Map.Entry<Integer, ArrayDeque<VBO>> e)
	{
		VBO vbo = e.getValue().poll();
		if (e.getValue().isEmpty())
		{
			kept.remove(e.getKey());
		}
		keptBytes -= vbo.capacity;
		keptCount--;
		return vbo;
	}

	private static void delete(VBO vbo)
	{
		vbo.destroy();
		glDeleteVertexArrays(vbo.vao);
		SceneLoadReport.arraysDeleted++;
		vbo.vao = 0;
	}

	private static int roundUp(int bytes)
	{
		return (bytes + GRANULE - 1) / GRANULE * GRANULE;
	}

	int keptCount()
	{
		return keptCount;
	}

	long keptBytes()
	{
		return keptBytes;
	}

	/** Delete everything kept (shutdown); the zones in use free theirs through release first. */
	void free()
	{
		while (!kept.isEmpty())
		{
			delete(take(kept.lastEntry()));
		}
		liveBytes = 0;
		trimQueued = false;
	}
}
