package com.midonarhel.lumina.scene;

import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Tick-local rebuild pacing. The zones' invalidation flags remain the only pending-work queue. */
final class ZoneRebuilds
{
	@FunctionalInterface interface Upload { void zone(int view, int x, int z); }

	static final class Result
	{
		int completed, backlog;
		long elapsed, maxZone, overrun;
	}

	private final Map<Zone[][], Integer> cursors = new IdentityHashMap<>();
	private int nextView;

	Result run(List<Zone[][]> views, long budget, LongSupplier clock, Upload upload)
	{
		long start = clock.getAsLong();
		Result result = new Result();
		// A scene replacement/despawn drops its cursor. No old scene/zone work is retained for later execution.
		cursors.keySet().removeIf(zones -> !views.contains(zones));
		if (!views.isEmpty())
		{
			nextView %= views.size();
			int emptyViews = 0;
			while (emptyViews < views.size() && (result.completed == 0 || clock.getAsLong() - start < budget))
			{
				int view = nextView;
				nextView = (nextView + 1) % views.size();
				Zone[][] zones = views.get(view);
				int width = zones.length, height = width == 0 ? 0 : zones[0].length, size = width * height;
				int cursor = cursors.getOrDefault(zones, 0);
				boolean found = false;
				for (int checked = 0; checked < size; ++checked)
				{
					int index = (cursor + checked) % size, x = index / height, z = index % height;
					if (!zones[x][z].invalidate) continue;
					// Advance before the atomic upload, including when that same zone is invalidated again next tick.
					cursors.put(zones, (index + 1) % size);
					long zoneStart = clock.getAsLong();
					upload.zone(view, x, z);
					result.maxZone = Math.max(result.maxZone, clock.getAsLong() - zoneStart);
					result.completed++;
					found = true;
					break;
				}
				emptyViews = found ? 0 : emptyViews + 1;
			}
		}
		for (Zone[][] zones : views)
			for (Zone[] column : zones)
				for (Zone zone : column)
					if (zone.invalidate) result.backlog++;
		result.elapsed = clock.getAsLong() - start;
		result.overrun = Math.max(0, result.elapsed - budget);
		return result;
	}

	void clear() { cursors.clear(); nextView = 0; }
}
