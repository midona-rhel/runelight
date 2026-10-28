package com.midonarhel.lumina;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import net.runelite.client.RuneLite;

/**
 * Records every frame's CPU and GPU times for a few seconds and writes them to a CSV file, with the conditions they
 * were measured under as comment lines at the top. GPU results arrive a few frames late (GpuTimer); they are
 * joined to their frame when the file is written.
 */
@Slf4j
class Snapshot
{
	static final Path DIR = RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("snapshots");

	private final List<String> header;
	private final long endNanos;
	private final List<double[]> rows = new ArrayList<>();
	private final Map<Long, double[]> gpu = new HashMap<>();

	Snapshot(List<String> header, int seconds)
	{
		this.header = header;
		this.endNanos = System.nanoTime() + seconds * 1_000_000_000L;
	}

	boolean isDone()
	{
		return System.nanoTime() >= endNanos;
	}

	/**
	 * @param frame    the frame's number (GpuTimer's)
	 * @param frameMs  time since the previous frame ended
	 * @param cpu      the frame's CPU step times (CpuTimer)
	 * @param players  players in the scene
	 */
	void addFrame(long frame, double frameMs, CpuTimer cpu, int players)
	{
		double[] row = new double[3 + CpuTimer.STAGES];
		row[0] = frame;
		row[1] = frameMs;
		row[2] = players;
		for (int s = 0; s < CpuTimer.STAGES; ++s)
		{
			row[3 + s] = cpu.getLastMs(s);
		}
		rows.add(row);
	}

	void addGpu(long frame, GpuTimer timer)
	{
		double[] ms = new double[GpuTimer.STAGES];
		for (int s = 0; s < GpuTimer.STAGES; ++s)
		{
			ms[s] = timer.getReadMs(s);
		}
		gpu.put(frame, ms);
	}

	/** Write the file on a background thread. */
	void write()
	{
		StringBuilder sb = new StringBuilder();
		for (String line : header)
		{
			sb.append("# ").append(line).append('\n');
		}
		sb.append("frame,frame_ms,players");
		for (String name : CpuTimer.NAMES)
		{
			sb.append(",cpu_").append(name);
		}
		for (String name : GpuTimer.NAMES)
		{
			sb.append(",gpu_").append(name);
		}
		sb.append('\n');
		for (double[] row : rows)
		{
			sb.append((long) row[0]).append(',').append(fmt(row[1])).append(',').append((int) row[2]);
			for (int s = 0; s < CpuTimer.STAGES; ++s)
			{
				sb.append(',').append(fmt(row[3 + s]));
			}
			// frames whose GPU results were dropped (not ready in time) get empty cells
			double[] g = gpu.get((long) row[0]);
			for (int s = 0; s < GpuTimer.STAGES; ++s)
			{
				sb.append(',');
				if (g != null)
				{
					sb.append(fmt(g[s]));
				}
			}
			sb.append('\n');
		}

		String name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")) + ".csv";
		Path file = DIR.resolve(name);
		String text = sb.toString();
		new Thread(() ->
		{
			try
			{
				Files.createDirectories(DIR);
				Files.write(file, text.getBytes(StandardCharsets.UTF_8));
				log.info("Snapshot written: {} ({} frames)", file, rows.size());
			}
			catch (IOException ex)
			{
				log.warn("Couldn't write snapshot {}", file, ex);
			}
		}, "Lumina snapshot").start();
	}

	private static String fmt(double v)
	{
		return Double.isFinite(v) ? String.format(java.util.Locale.ROOT, "%.3f", v) : "";
	}
}
