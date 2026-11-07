package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.light.SceneLights;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Collections;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Exercises light scheduling through the real queue, including its removal of failed jobs. */
public class SceneLightsJobRecoveryTest
{
	private static <T> T fake(Class<T> type, InvocationHandler handler)
	{
		return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
	}

	private static final class Fixture
	{
		final SceneJobs jobs = new SceneJobs();
		final SceneLights lights;
		boolean failScan;
		int scans;

		Fixture()
		{
			Scene scene = fake(Scene.class, (proxy, method, args) ->
			{
				if (method.getName().equals("getExtendedTiles"))
				{
					++scans;
					if (failScan)
					{
						throw new IllegalStateException("test scene scan failure");
					}
					return new Tile[0][][];
				}
				throw new AssertionError(method);
			});
			IndexedObjectSet<?> actors = fake(IndexedObjectSet.class,
				(proxy, method, args) -> Collections.emptyIterator());
			WorldView view = fake(WorldView.class, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getScene": return scene;
					case "players":
					case "npcs": return actors;
					default: throw new AssertionError(method);
				}
			});
			Client client = fake(Client.class, (proxy, method, args) ->
			{
				switch (method.getName())
				{
					case "getGameState": return GameState.LOGGED_IN;
					case "getTopLevelWorldView": return view;
					default: throw new AssertionError(method);
				}
			});
			lights = new SceneLights(client);
			lights.setJobs(jobs);
		}

		void drain()
		{
			for (int frame = 0; frame < 100 && !jobs.idle(); ++frame)
			{
				jobs.run(false);
			}
			assertTrue("scene jobs should finish", jobs.idle());
		}
	}

	@Test
	public void rebuildCanScheduleAfterSceneScanThrows()
	{
		Fixture f = new Fixture();
		f.failScan = true;
		f.lights.rebuild();
		f.drain();
		assertEquals(1, f.scans);

		f.failScan = false;
		f.lights.rebuild();
		assertFalse("rebuild must replace the failed light job", f.jobs.idle());
		f.drain();
		assertEquals(2, f.scans);
		assertFalse("completed scan must release the busy flag", f.lights.placing());
	}

	@Test
	public void rebuildWhileQueuedCompletesCurrentScene()
	{
		Fixture f = new Fixture();
		f.lights.rebuild();
		f.lights.rebuild();
		f.drain();
		assertEquals("only the current scene should be scanned", 1, f.scans);
		assertFalse(f.lights.placing());
	}
}
