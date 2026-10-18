package com.midonarhel.runelight;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the shader source directory for changes. Polling is used instead of WatchService because the JDK
 * WatchService on macOS is itself a slow poller (seconds of latency); checking mtimes of a few dozen files
 * every 200ms is cheaper and reacts immediately on every platform.
 */
class ShaderWatcher
{
	private static final Logger log = LoggerFactory.getLogger(ShaderWatcher.class);
	private static final long POLL_MS = 200;

	private final Path dir;
	private final AtomicBoolean changed = new AtomicBoolean();
	private Thread thread;
	private volatile boolean running;

	private ShaderWatcher(Path dir)
	{
		this.dir = dir;
	}

	/**
	 * Find the shader source directory. Uses -Drunelight.shaderDir if set, otherwise the resources folder of the
	 * project when launched from the project root (gradle run / IntelliJ).
	 *
	 * @return a watcher, or null when no source directory is available (e.g. a Plugin Hub install)
	 */
	static ShaderWatcher create()
	{
		String prop = System.getProperty("runelight.shaderDir");
		Path dir = prop != null
			? Paths.get(prop)
			: Paths.get(System.getProperty("user.dir"), "src", "main", "resources", "com", "midonarhel", "runelight");
		dir = dir.toAbsolutePath().normalize();
		if (!Files.isDirectory(dir) || !Files.isRegularFile(dir.resolve("frag.glsl")))
		{
			log.debug("No shader source directory at {}, hot reload disabled", dir);
			return null;
		}
		return new ShaderWatcher(dir);
	}

	Path getDirectory()
	{
		return dir;
	}

	void start()
	{
		running = true;
		thread = new Thread(this::run, "RuneLight shader watcher");
		thread.setDaemon(true);
		thread.start();
		log.info("Shader hot reload watching {}", dir);
	}

	void stop()
	{
		running = false;
		if (thread != null)
		{
			thread.interrupt();
			thread = null;
		}
	}

	/**
	 * @return true once after any shader file was added, removed or modified
	 */
	boolean poll()
	{
		return changed.getAndSet(false);
	}

	private void run()
	{
		Map<Path, Long> last = snapshot();
		while (running)
		{
			try
			{
				Thread.sleep(POLL_MS);
			}
			catch (InterruptedException e)
			{
				return;
			}

			Map<Path, Long> now = snapshot();
			if (!now.equals(last))
			{
				last = now;
				changed.set(true);
			}
		}
	}

	private Map<Path, Long> snapshot()
	{
		Map<Path, Long> m = new HashMap<>();
		try (Stream<Path> files = Files.walk(dir, 4))
		{
			files.filter(p -> p.toString().endsWith(".glsl")).forEach(p ->
			{
				try
				{
					m.put(p, Files.getLastModifiedTime(p).toMillis() ^ Files.size(p) << 40);
				}
				catch (IOException ignored)
				{
					// file vanished mid-walk, e.g. editor atomic save; next poll picks it up
				}
			});
		}
		catch (IOException | RuntimeException ex)
		{
			log.debug("shader dir walk failed", ex);
		}
		return m;
	}
}
