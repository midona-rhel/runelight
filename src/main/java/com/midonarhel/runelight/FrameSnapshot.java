package com.midonarhel.runelight;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import net.runelite.client.RuneLite;

/**
 * Reference-build measurement, the same as Lumina's ::lumina snapshot: records every frame's wall time for a number
 * of seconds and writes a CSV plus a summary (mean, median, p95, max, frames over 1.5x the median = hitches) to
 * ~/.runelite/lumina/snapshots/perfwip-<time>.csv. Call {@link #frame} once per swapped frame on the client thread.
 */
final class FrameSnapshot
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FrameSnapshot.class);
	private static final Path DIR = RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("snapshots");

	private List<String> header;
	private long endNanos;
	private long lastNanos;
	private final List<Double> frames = new ArrayList<>();
	private Runnable done;

	boolean running()
	{
		return endNanos != 0;
	}

	void start(List<String> header, int seconds, Runnable done)
	{
		this.header = header;
		this.done = done;
		frames.clear();
		lastNanos = 0;
		endNanos = System.nanoTime() + seconds * 1_000_000_000L;
		log.info("Snapshot started: {} s", seconds);
	}

	void frame()
	{
		if (endNanos == 0)
		{
			return;
		}
		long now = System.nanoTime();
		if (lastNanos != 0)
		{
			frames.add((now - lastNanos) / 1e6);
		}
		lastNanos = now;
		if (now >= endNanos)
		{
			endNanos = 0;
			write();
			if (done != null)
			{
				Runnable d = done;
				done = null;
				d.run();
			}
		}
	}

	private void write()
	{
		double[] f = frames.stream().mapToDouble(Double::doubleValue).toArray();
		double[] sorted = f.clone();
		Arrays.sort(sorted);
		double mean = Arrays.stream(f).average().orElse(0);
		double median = sorted.length == 0 ? 0 : sorted[sorted.length / 2];
		double p95 = sorted.length == 0 ? 0 : sorted[(int) (sorted.length * 0.95)];
		double max = sorted.length == 0 ? 0 : sorted[sorted.length - 1];
		long hitches = Arrays.stream(f).filter(x -> x > 1.5 * median).count();
		String summary = String.format("frames=%d mean=%.2f median=%.2f p95=%.2f max=%.2f hitches(>1.5x median)=%d fps=%.0f",
			f.length, mean, median, p95, max, hitches, mean > 0 ? 1000 / mean : 0);
		StringBuilder sb = new StringBuilder();
		for (String h : header)
		{
			sb.append("# ").append(h).append('\n');
		}
		sb.append("# ").append(summary).append("\nframe_ms\n");
		for (double x : f)
		{
			sb.append(String.format("%.3f%n", x));
		}
		String name = "perfwip-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".csv";
		try
		{
			Files.createDirectories(DIR);
			Files.write(DIR.resolve(name), sb.toString().getBytes(StandardCharsets.UTF_8));
			log.info("Snapshot written: {} {}", DIR.resolve(name), summary);
		}
		catch (IOException ex)
		{
			log.warn("Couldn't write snapshot", ex);
		}
	}

	static Consumer<String> noop()
	{
		return s -> { };
	}
}
