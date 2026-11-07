package com.midonarhel.lumina.dev;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.runelite.client.RuneLite;

/** Opt-in CPU state trace; no GL readback or lighting changes. Retains only the last 600 frames. */
public final class PlayerLightTrace
{
	private final String[] rows = new String[600];
	private boolean enabled;
	private long frame;
	private int next, count;

	public boolean enabled() { return enabled; }

	public void start()
	{
		Arrays.fill(rows, null);
		next = count = 0;
		frame = 0;
		enabled = true;
	}

	public void stop() { enabled = false; }

	public void record(String values)
	{
		if (!enabled) return;
		rows[next] = frame++ + "," + System.nanoTime() + "," + values;
		next = (next + 1) % rows.length;
		count = Math.min(count + 1, rows.length);
	}

	/** Freeze the ring before writing so the crossing cannot be overwritten while inspecting it. */
	public Path dump() throws IOException
	{
		stop();
		List<String> out = new ArrayList<>(count + 1);
		out.add("frame,nanos,pointsEnabled,testEnabled,wallVersion,listCount,listIndex,retainedIndex,result,x,y,z,originX,originZ,intensity,radius,plane,clusterMemberships,totalClusterOverflows");
		for (int i = 0; i < count; ++i) out.add(rows[(next - count + i + rows.length) % rows.length]);
		Path dir = RuneLite.RUNELITE_DIR.toPath().resolve("lumina").resolve("traces");
		Files.createDirectories(dir);
		Path file = Files.createTempFile(dir, "player-light-", ".csv");
		Files.write(file, out, StandardCharsets.UTF_8);
		return file;
	}
}
