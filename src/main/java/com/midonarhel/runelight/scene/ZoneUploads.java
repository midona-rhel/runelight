package com.midonarhel.runelight.scene;

import com.midonarhel.runelight.*;

import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import net.runelite.client.callback.ClientThread;

/**
 * The GL half of a load's zone uploads, as one scheduler job (SceneJobs) running alongside the map loader: buffers are
 * taken from the pool and mapped ahead of the loader, and unmapped behind it as it finishes each zone, so the loader
 * writes vertices while the client thread spends at most its frame budget on the GL calls, and every zone is ready to
 * draw before the scene swap. The loader calls {@link #mapped} before writing zone i and {@link #uploaded} after; then
 * {@link #finish} waits for the last unmap.
 *
 * On the client thread itself (the plugin's start-up load) the job is run inline, without a budget.
 */
final class ZoneUploads implements SceneJobs.Job
{
	// the loader waits this long for the job to run before it asks the client thread for a step itself
	private static final long STALL_NANOS = 100_000_000L;

	private final Zone[] zones;
	private final ZonePool pool;
	private final SceneJobs jobs;
	private final ClientThread clientThread;
	private final Consumer<double[]> report;
	// client thread work before the first map (Interactables.resolve)
	private Runnable first;
	private volatile int mappedCount;
	private volatile int unmappedCount;
	private final ConcurrentLinkedQueue<Zone> toUnmap = new ConcurrentLinkedQueue<>();
	private double clientMs;
	private int steps;
	private boolean reported;

	/**
	 * @param zones the zones to upload, in upload order, sized (sizeO, sizeA)
	 * @param first run on the client thread before the first buffer is mapped
	 * @param report given {client-thread ms, steps} when the last zone is unmapped
	 */
	ZoneUploads(Zone[] zones, ZonePool pool, SceneJobs jobs, ClientThread clientThread, Runnable first,
		Consumer<double[]> report)
	{
		this.zones = zones;
		this.pool = pool;
		this.jobs = jobs;
		this.clientThread = clientThread;
		this.first = first;
		this.report = report;
	}

	/** Queue the job (or nothing, on the client thread: the waits run it). */
	void start(boolean onClientThread)
	{
		if (!onClientThread)
		{
			jobs.add("zone buffers", SceneJobs.LOAD, this);
		}
	}

	@Override
	public boolean step(long deadline)
	{
		long t0 = System.nanoTime();
		if (first != null)
		{
			first.run();
			first = null;
		}
		// unmap what the loader finished first (the swap waits on it), then map ahead
		for (Zone z; System.nanoTime() < deadline && (z = toUnmap.poll()) != null; )
		{
			z.unmap();
			unmappedCount++;
		}
		while (mappedCount < zones.length && System.nanoTime() < deadline)
		{
			Zone zone = zones[mappedCount];
			zone.init(map(zone.sizeO), map(zone.sizeA));
			mappedCount++;
		}
		clientMs += (System.nanoTime() - t0) / 1e6;
		steps++;
		boolean done = unmappedCount == zones.length;
		if (done && !reported)
		{
			reported = true;
			report.accept(new double[]{clientMs, steps});
		}
		synchronized (this)
		{
			notifyAll();
		}
		return done;
	}

	private VBO map(int faces)
	{
		int bytes = faces * Zone.VERT_SIZE * 3;
		if (bytes <= 0)
		{
			return null;
		}
		VBO vbo = pool.acquire(bytes);
		vbo.map();
		return vbo;
	}

	/** Loader: wait until zone i's buffers are mapped. */
	void mapped(int i, boolean onClientThread)
	{
		await(() -> mappedCount > i, onClientThread);
	}

	/** Loader: zone i is written. */
	void uploaded(int i)
	{
		toUnmap.add(zones[i]);
	}

	/** Loader: wait until every zone is unmapped, ready to draw. */
	void finish(boolean onClientThread)
	{
		await(() -> unmappedCount == zones.length, onClientThread);
	}

	private void await(java.util.function.BooleanSupplier ready, boolean onClientThread)
	{
		if (onClientThread)
		{
			while (!ready.getAsBoolean())
			{
				step(Long.MAX_VALUE);
			}
			return;
		}
		long waitStart = System.nanoTime();
		synchronized (this)
		{
			while (!ready.getAsBoolean())
			{
				try
				{
					wait(10);
				}
				catch (InterruptedException ex)
				{
					Thread.currentThread().interrupt();
					throw new RuntimeException(ex);
				}
				long now = System.nanoTime();
				if (now - Math.max(waitStart, jobs.lastRunNanos()) > STALL_NANOS)
				{
					// no frame has run the queue for a while (the client is not drawing): ask for a step directly
					waitStart = now;
					clientThread.invoke(() -> step(System.nanoTime() + SceneJobs.BUDGET_NANOS));
				}
			}
		}
	}
}
