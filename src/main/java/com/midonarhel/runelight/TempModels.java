package com.midonarhel.runelight;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Uploads the models drawTemp is given (actors, projectiles, animated objects) on worker threads. Computing their
 * normals and sorting and packing their faces took the client thread most of a millisecond a frame in busy areas.
 * The client rebuilds animated models in buffers it reuses once the callback returns, so the client thread only
 * copies the model; a worker does the rest into the job's own array. {@link #flush} copies the packed faces into the
 * frame's buffers in call order, so the buffers, their draw ranges and the zones' temp alpha models end up exactly as
 * if each model had been uploaded in its callback. Flush before anything reads those (the opaque pass, the alpha
 * pass, zone alpha draws) and before the client draws another scene, whose projection could replace the one the
 * workers project with.
 *
 * Everything but the packing runs on the client thread. A job's inputs are written while it is idle and published by
 * setting it queued; its outputs are written while it runs and published by setting it idle again. Whichever thread
 * claims a queued job packs it, so the client thread packs the jobs no worker has reached instead of waiting.
 */
class TempModels
{
	private static final Logger log = LoggerFactory.getLogger(TempModels.class);

	// jobs in flight: bounds the memory the copies hold, and is plenty of lookahead for the workers
	static final int MAX_JOBS = 64;

	// a worker that runs out of jobs waits this long for more before parking: waking a parked thread costs the client
	// thread more than copying a model, and drawTemp calls come a few tens of microseconds apart
	private static final long LINGER_NANOS = 200_000;

	private static final int IDLE = 0, QUEUED = 1, RUNNING = 2;
	private static final int FACE_SIZE = (VAO.VERT_SIZE >> 2) * 3;

	private final ThreadLocal<ModelUploader> uploaders = ThreadLocal.withInitial(ModelUploader::new);
	private final Job[] jobs = new Job[MAX_JOBS];
	private int head, count;
	private int threads;
	private Workers workers;

	final class Job
	{
		final ModelData model = new ModelData();
		private final AtomicInteger state = new AtomicInteger(IDLE);

		// read by the packing thread
		private boolean sorted, noDepth;
		private Projection projection;
		private int orient, x, y, z, cameraX, cameraY, cameraZ, material;

		// written by the packing thread: opaque faces from 0, alpha faces from alphaStart
		int[] out = new int[0];
		private IntBuffer opaque = IntBuffer.wrap(out), alpha = IntBuffer.wrap(out);
		int alphaStart, opaqueLen, alphaLen;

		// client thread only
		private RuneLightPlugin.RenderThread rt;
		private RuneLightPlugin.SceneContext ctx;
		private Scene scene;
		private int size, zx, zz, plane, alphaY;

		boolean tryPack(ModelUploader uploader)
		{
			if (state.get() != QUEUED || !state.compareAndSet(QUEUED, RUNNING))
			{
				return false;
			}
			try
			{
				pack(uploader);
			}
			finally
			{
				state.set(IDLE);
			}
			return true;
		}

		void pack(ModelUploader uploader)
		{
			opaqueLen = alphaLen = 0;
			try
			{
				if (sorted)
				{
					int faces = Math.min(model.faceCount, ModelUploader.MAX_FACE_COUNT);
					int end = faces * FACE_SIZE;
					alphaStart = end - ModelUploader.maxAlphaFaces(model) * FACE_SIZE;
					buffers(end);
					opaque.limit(alphaStart);
					alpha.limit(end);
					alpha.position(alphaStart);
					uploader.uploadSortedModel(projection, model, orient, x, y, z,
						cameraX, cameraY, cameraZ, opaque, alpha, noDepth, material);
					opaqueLen = opaque.position();
					alphaLen = alpha.position() - alphaStart;
				}
				else
				{
					int end = model.faceCount * FACE_SIZE;
					buffers(end);
					opaque.limit(end);
					uploader.uploadTempModel(model, orient, x, y, z, cameraX, cameraY, cameraZ, opaque, material);
					opaqueLen = opaque.position();
				}
			}
			catch (Exception ex)
			{
				opaqueLen = alphaLen = 0;
				log.debug("error drawing entity", ex);
			}
		}

		private void buffers(int size)
		{
			if (out.length < size)
			{
				out = new int[size];
				opaque = IntBuffer.wrap(out);
				alpha = IntBuffer.wrap(out);
			}
			opaque.clear();
			alpha.clear();
		}
	}

	/** Use n worker threads; 0 uploads each model in its callback. */
	void setThreads(int n)
	{
		if (n != threads)
		{
			free();
			threads = n;
		}
	}

	void uploadTemp(RuneLightPlugin.RenderThread rt, RuneLightPlugin.SceneContext ctx, Scene scene, Model m,
		int orient, int x, int y, int z, int material)
	{
		Job job = next(rt, ctx, scene, m, orient, x, y, z, material);
		job.sorted = false;
		start(job);
	}

	/**
	 * @param zx zone of the object, for its alpha faces
	 * @param alphaY height its alpha faces are sorted at among the zone's alpha models
	 */
	void uploadSorted(RuneLightPlugin.RenderThread rt, RuneLightPlugin.SceneContext ctx, Scene scene, Projection projection,
		Model m, int orient, int x, int y, int z, int material, boolean noDepth, int zx, int zz, int plane, int alphaY)
	{
		Job job = next(rt, ctx, scene, m, orient, x, y, z, material);
		job.sorted = true;
		job.projection = projection;
		job.noDepth = noDepth;
		job.zx = zx;
		job.zz = zz;
		job.plane = plane;
		job.alphaY = alphaY;
		start(job);
	}

	private Job next(RuneLightPlugin.RenderThread rt, RuneLightPlugin.SceneContext ctx, Scene scene, Model m,
		int orient, int x, int y, int z, int material)
	{
		if (count == MAX_JOBS)
		{
			finishOldest();
		}
		int i = (head + count) % MAX_JOBS;
		Job job = jobs[i];
		if (job == null)
		{
			job = jobs[i] = new Job();
		}
		if (startPool())
		{
			job.model.copy(m);
		}
		else
		{
			job.model.wrap(m);
		}
		job.rt = rt;
		job.ctx = ctx;
		job.scene = scene;
		job.size = m.getFaceCount() * 3 * VAO.VERT_SIZE;
		job.orient = orient;
		job.x = x;
		job.y = y;
		job.z = z;
		job.cameraX = ctx.cameraX;
		job.cameraY = ctx.cameraY;
		job.cameraZ = ctx.cameraZ;
		job.material = material;
		return job;
	}

	private void start(Job job)
	{
		job.state.set(QUEUED);
		++count;
		if (workers == null)
		{
			// no workers: upload now, as the callback always did
			flush();
			return;
		}
		workers.add(job);
	}

	/** Copy every model handed over so far into the frame's buffers, in the order they were drawn. */
	void flush()
	{
		while (count > 0)
		{
			finishOldest();
		}
	}

	private void finishOldest()
	{
		Job job = jobs[head];
		complete(job);
		head = (head + 1) % MAX_JOBS;
		--count;
		emit(job);
	}

	/** Wait for the job to be packed, packing it here if no worker has started it. */
	void complete(Job job)
	{
		ModelUploader uploader = uploaders.get();
		if (job.tryPack(uploader))
		{
			return;
		}
		// a worker has it: meanwhile pack the newest jobs, which the workers reach last
		int spins = 0;
		while (job.state.get() != IDLE)
		{
			if (packNewest(uploader))
			{
				continue;
			}
			if (++spins < 100)
			{
				Thread.onSpinWait();
			}
			else
			{
				Thread.yield();
			}
		}
	}

	private boolean packNewest(ModelUploader uploader)
	{
		for (int i = count - 1; i > 0; --i)
		{
			if (jobs[(head + i) % MAX_JOBS].tryPack(uploader))
			{
				return true;
			}
		}
		return false;
	}

	/** Copy a packed job into the frame's buffers. Overridden by tests, which have no GL. */
	void emit(Job job)
	{
		RuneLightPlugin.RenderThread rt = job.rt;
		RuneLightPlugin.SceneContext ctx = job.ctx;
		if (job.sorted)
		{
			VAO o = rt.vaoO.get(job.size);
			VAO a = rt.vaoA.get(job.size);
			int start = a.vbo.vb.position();
			o.vbo.vb.put(job.out, 0, job.opaqueLen);
			a.vbo.vb.put(job.out, job.alphaStart, job.alphaLen);
			int end = a.vbo.vb.position();

			o.addRange(ctx.projection, job.scene, job.noDepth ? Renderable.RENDERMODE_SORTED_NO_DEPTH : 0);

			if (end > start)
			{
				Zone zone = ctx.zones[job.zx][job.zz];
				zone.addTempAlphaModel(a.vao, start, end, job.plane, job.x & 1023, job.alphaY, job.z & 1023);
			}
		}
		else
		{
			VAO o = rt.vaoO.get(job.size);
			o.vbo.vb.put(job.out, 0, job.opaqueLen);
			o.addRange(ctx.projection, job.scene, 0);
		}
		job.rt = null;
		job.ctx = null;
		job.scene = null;
		job.projection = null;
	}

	private boolean startPool()
	{
		if (workers == null && threads > 0)
		{
			try
			{
				workers = new Workers(threads);
			}
			catch (RuntimeException ex)
			{
				log.warn("No threads for the temp models", ex);
				threads = 0;
			}
		}
		return workers != null;
	}

	/**
	 * Worker threads taking queued jobs. A job the client thread packs itself stays in the queue, and is skipped
	 * when a worker takes it. Replaced as a whole, so threads of an old pool still lingering never touch a new one.
	 */
	private final class Workers
	{
		private final ExecutorService pool;
		private final ConcurrentLinkedQueue<Job> queue = new ConcurrentLinkedQueue<>();
		private final AtomicInteger running = new AtomicInteger();
		// workers lingering for a job, which will take the next one without being woken
		private final AtomicInteger waiting = new AtomicInteger();
		private final int max;

		Workers(int max)
		{
			this.max = max;
			pool = Executors.newFixedThreadPool(max, r ->
			{
				Thread t = new Thread(r, "RuneLight temp models");
				t.setDaemon(true);
				return t;
			});
		}

		void add(Job job)
		{
			queue.offer(job);
			if (waiting.get() == 0 && join())
			{
				try
				{
					pool.execute(this::drain);
				}
				catch (RejectedExecutionException ex)
				{
					// still queued: flush packs it
					running.decrementAndGet();
				}
			}
		}

		/** Count one more worker running, unless all are. */
		private boolean join()
		{
			for (int n; (n = running.get()) < max; )
			{
				if (running.compareAndSet(n, n + 1))
				{
					return true;
				}
			}
			return false;
		}

		private void drain()
		{
			ModelUploader uploader = uploaders.get();
			do
			{
				Job job;
				while ((job = poll()) != null)
				{
					job.tryPack(uploader);
				}
				running.decrementAndGet();
				// a job added since the last poll may have found no worker waiting and every worker running
			}
			while (!queue.isEmpty() && join());
		}

		private Job poll()
		{
			Job job = queue.poll();
			if (job != null)
			{
				return job;
			}
			waiting.incrementAndGet();
			long start = System.nanoTime();
			do
			{
				Thread.onSpinWait();
				job = queue.poll();
			}
			while (job == null && System.nanoTime() - start < LINGER_NANOS);
			waiting.decrementAndGet();
			return job;
		}

		/** Threads still draining finish within the linger: nothing is added to this queue any more. */
		void shutdown()
		{
			pool.shutdown();
		}
	}

	/** Drop the models in flight and the worker threads. Call on the client thread. */
	void free()
	{
		for (; count > 0; --count)
		{
			Job job = jobs[head];
			head = (head + 1) % MAX_JOBS;
			while (!job.state.compareAndSet(QUEUED, IDLE) && job.state.get() != IDLE)
			{
				Thread.yield();
			}
		}
		head = 0;
		Arrays.fill(jobs, null);
		uploaders.remove();
		if (workers != null)
		{
			workers.shutdown();
			workers = null;
		}
	}
}
