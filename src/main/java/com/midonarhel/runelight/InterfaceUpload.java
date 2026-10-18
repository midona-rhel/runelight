package com.midonarhel.runelight;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import static org.lwjgl.opengl.GL33C.*;

/**
 * Uploads the client's interface canvas to its texture through a pixel buffer. Copying a whole canvas into the
 * mapped buffer takes the client thread over half a millisecond at 1440p, so the copy runs on worker threads,
 * split in bands, while the client thread submits the composite; the texture is filled from the buffer after.
 * The canvas array is safe to read meanwhile: the copy is waited for before the client's draw call returns, and the
 * client thread draws nothing into the canvas during it.
 */
class InterfaceUpload
{
	private static final Logger log = LoggerFactory.getLogger(InterfaceUpload.class);

	// below this many pixels the copy is quicker than handing it to the workers
	private static final int THREADED_MIN = 512 * 512;
	private static final int THREADS = 2;

	private ExecutorService pool;
	private final Future<?>[] pending = new Future<?>[THREADS];
	private int pbo;
	private int width, height;
	private boolean mapped;
	private boolean copyFailed;

	/**
	 * Map the buffer and start copying the canvas into it. The buffer is left unbound, so other uploads made
	 * before {@link #finish} don't read from it.
	 */
	void start(int pbo, int[] pixels, int width, int height)
	{
		this.pbo = pbo;
		this.width = width;
		this.height = height;
		copyFailed = false;
		int count = width * height;
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
		// orphaned every frame: the driver hands out fresh memory instead of waiting for last frame's upload
		glBufferData(GL_PIXEL_UNPACK_BUFFER, (long) count * Integer.BYTES, GL_STREAM_DRAW);
		ByteBuffer buf = glMapBuffer(GL_PIXEL_UNPACK_BUFFER, GL_WRITE_ONLY);
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		mapped = buf != null;
		if (!mapped)
		{
			return;
		}
		IntBuffer ints = buf.asIntBuffer();
		if (count < THREADED_MIN || !startPool())
		{
			ints.put(pixels, 0, count);
			return;
		}
		for (int t = 0; t < THREADS; ++t)
		{
			int from = bandStart(t, THREADS, height) * width;
			int to = bandStart(t + 1, THREADS, height) * width;
			IntBuffer dst = ints.duplicate();
			dst.position(from);
			pending[t] = pool.submit(() -> dst.put(pixels, from, to - from));
		}
	}

	/**
	 * Wait for the copy. Call before {@link #finish}; split from it so the wait can be timed apart.
	 */
	void await()
	{
		for (int t = 0; t < THREADS; ++t)
		{
			Future<?> f = pending[t];
			if (f == null)
			{
				continue;
			}
			pending[t] = null;
			try
			{
				f.get();
			}
			catch (Exception ex)
			{
				copyFailed = true;
				log.warn("Interface copy failed", ex);
			}
		}
	}

	/**
	 * Unmap the buffer and fill the texture from it. Returns whether the texture has this frame's canvas.
	 */
	boolean finish(int texture)
	{
		await();
		if (!mapped)
		{
			return false;
		}
		mapped = false;
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, pbo);
		// false when the buffer's contents were lost while mapped (rare); last frame's canvas stays up then
		boolean ok = glUnmapBuffer(GL_PIXEL_UNPACK_BUFFER) && !copyFailed;
		if (ok)
		{
			glBindTexture(GL_TEXTURE_2D, texture);
			glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, width, height, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, 0);
			glBindTexture(GL_TEXTURE_2D, 0);
		}
		glBindBuffer(GL_PIXEL_UNPACK_BUFFER, 0);
		return ok;
	}

	/** First row of band t of n over the given rows; band n starts past the last row. */
	static int bandStart(int t, int n, int rows)
	{
		int band = (rows + n - 1) / n;
		return Math.min(t * band, rows);
	}

	private boolean startPool()
	{
		if (pool == null)
		{
			try
			{
				pool = Executors.newFixedThreadPool(THREADS, r ->
				{
					Thread t = new Thread(r, "RuneLight UI upload");
					t.setDaemon(true);
					return t;
				});
			}
			catch (RuntimeException ex)
			{
				log.warn("No threads for the interface upload", ex);
				return false;
			}
		}
		return true;
	}

	/** Drop the worker threads; the buffer belongs to the caller. Call after {@link #finish}. */
	void free()
	{
		await();
		if (pool != null)
		{
			pool.shutdownNow();
			pool = null;
		}
	}
}
