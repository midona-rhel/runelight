package com.midonarhel.runelight.scene;

import com.midonarhel.runelight.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one queue for the GL work a scene load leaves on the client thread: zone buffers mapped and unmapped, the static
 * shadow map drawn, freed buffers deleted, the occlusion and sky redrawn. Every such job runs here, once a frame right
 * after the frame is presented, in order of priority (lower first, then oldest first), within {@link #BUDGET_NANOS} of
 * client-thread time a frame all told; a job is handed the deadline and does as much of its work as fits, so no frame
 * pays for a whole load. The scene swap itself does no GL work: what it needs was done here during the load.
 *
 * Jobs are added from any thread (the map loader adds its uploads); they run on the client thread only.
 */
public final class SceneJobs
{
	private static final Logger log = LoggerFactory.getLogger(SceneJobs.class);

	/** Client-thread time a frame for all jobs together. */
	static final long BUDGET_NANOS = 1_000_000L;
	/**
	 * While the client shows its loading box (GameState.LOADING), the load's own jobs (priority LOAD) may take this
	 * much a frame: the next swap waits on them, and at 1 ms the 529 zone buffers of a teleport took 13 frames
	 * (≈ 215 ms of the teleport, 2026-10-30) for about 5 ms of work. 4 ms leaves a 60 FPS frame far from 25 ms.
	 */
	static final long LOADING_BUDGET_NANOS = 4_000_000L;

	// priorities: what the next swap waits for first, then what the frame after it shows, then upkeep
	public static final int LOAD = 0;
	public static final int SHOWN = 1;
	static final int UPKEEP = 2;

	/** A share of work run on the client thread with the GL context. */
	public interface Job
	{
		/**
		 * Do work until the deadline (System.nanoTime) or until done; at least one unit of work per call.
		 *
		 * @return true when the job is done and leaves the queue
		 */
		boolean step(long deadlineNanos);
	}

	private static final class Entry
	{
		final String name;
		final int priority;
		final Job job;
		final long seq;
		double ms;
		int frames;

		Entry(String name, int priority, Job job, long seq)
		{
			this.name = name;
			this.priority = priority;
			this.job = job;
			this.seq = seq;
		}
	}

	private final ConcurrentLinkedQueue<Entry> incoming = new ConcurrentLinkedQueue<>();
	private final List<Entry> queue = new ArrayList<>();
	private long seq;
	private volatile long lastRunNanos;
	// this frame's job names, for the hitch monitor
	private final StringBuilder ran = new StringBuilder();

	/** Queue a job; any thread. */
	public void add(String name, int priority, Job job)
	{
		synchronized (incoming)
		{
			incoming.add(new Entry(name, priority, job, seq++));
		}
	}

	/** Whether no job is queued; client thread. */
	boolean idle()
	{
		return queue.isEmpty() && incoming.isEmpty();
	}

	/** When the queue last ran (System.nanoTime), 0 never; any thread. */
	long lastRunNanos()
	{
		return lastRunNanos;
	}

	/**
	 * Run jobs for this frame's budget; client thread, after the frame is presented.
	 *
	 * @param loading whether the client is loading a scene (its loading box is up): the load's jobs get more time
	 * @return the client-thread time spent, ms
	 */
	double run(boolean loading)
	{
		long start = System.nanoTime();
		lastRunNanos = start;
		ran.setLength(0);
		for (Entry e; (e = incoming.poll()) != null; )
		{
			int i = queue.size();
			while (i > 0 && (queue.get(i - 1).priority > e.priority
				|| queue.get(i - 1).priority == e.priority && queue.get(i - 1).seq > e.seq))
			{
				--i;
			}
			queue.add(i, e);
		}
		long deadline = start + BUDGET_NANOS;
		long loadDeadline = start + (loading ? LOADING_BUDGET_NANOS : BUDGET_NANOS);
		// one step per job per frame, in order; a job that stops before the deadline (done, or waiting for something)
		// leaves the rest of the budget to the next
		int i = 0;
		while (i < queue.size())
		{
			long now = System.nanoTime();
			Entry e = queue.get(i);
			long end = e.priority == LOAD ? loadDeadline : deadline;
			if (now >= end)
			{
				if (e.priority == LOAD)
				{
					++i;
					continue;
				}
				break;
			}
			boolean done;
			try
			{
				done = e.job.step(end);
			}
			catch (RuntimeException ex)
			{
				log.error("Scene job {} failed", e.name, ex);
				done = true;
			}
			e.ms += (System.nanoTime() - now) / 1e6;
			e.frames++;
			if (ran.length() > 0)
			{
				ran.append(", ");
			}
			ran.append(e.name);
			if (done)
			{
				queue.remove(i);
				log.debug("Scene job {} done: {} ms over {} frames", e.name, String.format("%.2f", e.ms), e.frames);
			}
			else
			{
				++i;
			}
		}
		return (System.nanoTime() - start) / 1e6;
	}

	/** The jobs that ran in the last run, comma separated, empty for none. */
	String ranText()
	{
		return ran.toString();
	}

	/** Drop every job without running it (shutdown). */
	void clear()
	{
		incoming.clear();
		queue.clear();
	}
}
