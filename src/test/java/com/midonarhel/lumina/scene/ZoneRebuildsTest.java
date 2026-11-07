package com.midonarhel.lumina.scene;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;
import static org.junit.Assert.*;

public class ZoneRebuildsTest
{
	@Test public void pendingInvalidationCannotBeReusedByNextSceneLoad()
	{
		int size = com.midonarhel.lumina.LuminaPlugin.NUM_ZONES;
		Zone[][] zones = new Zone[size][size];
		for (int x = 0; x < size; ++x)
			for (int z = 0; z < size; ++z)
			{
				zones[x][z] = new Zone();
				zones[x][z].initialized = true;
				zones[x][z].sizeO = 1;
			}
		assertTrue(SceneLoader.canReuse(zones, 2, 2));
		zones[2][2].invalidate = true;
		assertFalse(SceneLoader.canReuse(zones, 2, 2));
		assertFalse(SceneLoader.canReuse(zones, 3, 2)); // its neighbor's shared geometry must be refreshed too
	}

	private static Zone[][] invalid(int count)
	{
		Zone[][] zones = new Zone[1][count];
		for (int i = 0; i < count; ++i)
		{
			zones[0][i] = new Zone();
			zones[0][i].invalidate = true;
			zones[0][i].initialized = true;
		}
		return zones;
	}

	@Test public void finishesAtomicZonesThenDefersRemainingInvalidations()
	{
		ZoneRebuilds pacing = new ZoneRebuilds();
		Zone[][] zones = invalid(3);
		AtomicLong clock = new AtomicLong();
		ZoneRebuilds.Upload upload = (view, x, z) -> { zones[x][z].invalidate = false; clock.addAndGet(3); };
		ZoneRebuilds.Result first = pacing.run(Collections.singletonList(zones), 5, clock::get, upload);
		assertEquals(2, first.completed);
		assertEquals(1, first.backlog);
		assertEquals(3, first.maxZone);
		assertEquals(1, first.overrun);
		assertTrue(zones[0][2].initialized); // deferred zone remains drawable
		assertTrue(zones[0][2].invalidate);
		ZoneRebuilds.Result second = pacing.run(Collections.singletonList(zones), 5, clock::get, upload);
		assertEquals(1, second.completed);
		assertEquals(0, second.backlog);
		assertEquals(0, second.overrun);
	}

	@Test public void expensiveZonesCannotStarveOtherViewsOrLaterZones()
	{
		ZoneRebuilds pacing = new ZoneRebuilds();
		List<Zone[][]> views = Arrays.asList(invalid(3), invalid(2));
		AtomicLong clock = new AtomicLong();
		List<String> order = new ArrayList<>();
		for (int tick = 0; tick < 5; ++tick)
		{
			ZoneRebuilds.Result result = pacing.run(views, 1, clock::get, (view, x, z) ->
			{
				order.add(view + ":" + z);
				views.get(view)[x][z].invalidate = false;
				clock.addAndGet(4); // indivisible upload overruns the whole tick budget
			});
			assertEquals(1, result.completed);
			assertEquals(4, result.maxZone);
			assertEquals(3, result.overrun);
			views.get(0)[0][0].invalidate = true; // a frequently changing early zone must not monopolize the cursor
		}
		assertEquals(Arrays.asList("0:0", "1:0", "0:1", "1:1", "0:2"), order);
	}

	@Test public void replacementSceneDropsOldPendingWorkAndStartsItsOwnCursor()
	{
		ZoneRebuilds pacing = new ZoneRebuilds();
		Zone[][] old = invalid(3), replacement = invalid(2);
		AtomicLong clock = new AtomicLong();
		pacing.run(Collections.singletonList(old), 1, clock::get, (view, x, z) ->
		{
			old[x][z].invalidate = false;
			clock.incrementAndGet();
		});
		List<Integer> rebuilt = new ArrayList<>();
		ZoneRebuilds.Result result = pacing.run(Collections.singletonList(replacement), 10, clock::get, (view, x, z) ->
		{
			rebuilt.add(z);
			replacement[x][z].invalidate = false;
			clock.incrementAndGet();
		});
		assertEquals(Arrays.asList(0, 1), rebuilt);
		assertEquals(0, result.backlog);
		assertTrue(old[0][1].invalidate); // no queued old-scene callback was executed
		assertTrue(old[0][2].invalidate);
		assertEquals(0, pacing.run(Collections.emptyList(), 1, clock::get,
			(view, x, z) -> fail("Despawned views must not be visited")).completed);
	}
}
