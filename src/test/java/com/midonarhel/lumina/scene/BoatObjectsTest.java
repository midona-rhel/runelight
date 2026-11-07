package com.midonarhel.lumina.scene;

import java.util.ArrayList;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public class BoatObjectsTest
{
	@Before public void reset() { BoatObjects.clear(); }

	@Test public void onlyBoatNamesGetTheExtrusionException()
	{
		for (String name : new String[]{"Boat", " BOAT ", "Fishing boat", "Rowboat", "Longboats", "Sailboat"})
			assertTrue(name, BoatObjects.namedBoat(name));
		for (String name : new String[]{null, "", "null", "Boathouse", "Boat house", "Boat rack", "Dock", "Rock", "Tree", "Ship", "Canoe"})
			assertFalse(name, BoatObjects.namedBoat(name));
	}

	@Test public void namesResolveBeforeUploadAndWorkerReadsNeedNoClientCalls() throws Exception
	{
		BoatObjects.want(1);
		BoatObjects.want(2);
		BoatObjects.want(1);
		List<Integer> resolved = new ArrayList<>();
		Thread resolver = Thread.currentThread();
		BoatObjects.resolve(id ->
		{
			assertSame(resolver, Thread.currentThread());
			resolved.add(id);
			return id == 1 ? "Rowboat" : "Dock";
		});
		assertEquals(2, resolved.size());
		var result = new java.util.concurrent.FutureTask<Boolean>(() -> BoatObjects.is(1, null) && !BoatObjects.is(2, null));
		Thread worker = new Thread(result);
		worker.start();
		assertTrue(result.get());
		BoatObjects.want(1);
		BoatObjects.resolve(id -> { fail("Already cached"); return null; });
		assertFalse(BoatObjects.is(999, null));
	}
}
