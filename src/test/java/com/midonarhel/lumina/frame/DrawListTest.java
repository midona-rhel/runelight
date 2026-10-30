package com.midonarhel.lumina.frame;

import com.midonarhel.lumina.scene.SceneContext;
import com.midonarhel.lumina.scene.Zone;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The recorder replays a frame's commands in recording order with the state each was recorded with, copies the
 * projection it is handed, and starts over empty. GL-free: the executor is a stub, the zones are null references.
 */
public class DrawListTest
{
	/** Writes one line per command, with every argument, so the order and the captured state are checked at once. */
	private static class Log implements DrawList.Executor
	{
		final List<String> lines = new ArrayList<>();

		@Override
		public void clear()
		{
			lines.add("clear");
		}

		@Override
		public void skyHd117()
		{
			lines.add("skyHd117");
		}

		@Override
		public void skyboxVanilla()
		{
			lines.add("skybox");
		}

		@Override
		public void skyLumina()
		{
			lines.add("skyLumina");
		}

		@Override
		public void entityProj(float[] projection)
		{
			lines.add("proj " + Arrays.toString(projection));
		}

		@Override
		public void entityTint(int h, int s, int l, int a)
		{
			lines.add("tint " + h + " " + s + " " + l + " " + a);
		}

		@Override
		public void zoneOpaque(Zone zone, int zx, int zz, int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds, boolean gapFillers)
		{
			lines.add("opaque " + zx + "," + zz + " " + minLevel + "/" + level + "/" + maxLevel + " roofs=" + hideRoofIds + " gap=" + gapFillers);
		}

		@Override
		public void dynamicOpaque()
		{
			lines.add("dynamic");
		}

		@Override
		public void zoneAlpha(Zone zone, int zx, int zz, int cameraYaw, int cameraPitch, int minLevel, int level, int maxLevel,
			int alphaLevel, Set<Integer> hideRoofIds, boolean unsorted, boolean depthPass)
		{
			lines.add("alpha " + zx + "," + zz + " cam=" + cameraYaw + "/" + cameraPitch + " " + minLevel + "/" + level + "/" + maxLevel
				+ " L" + alphaLevel + " roofs=" + hideRoofIds + " unsorted=" + unsorted + " depth=" + depthPass);
		}

		@Override
		public void removeTemp(SceneContext ctx)
		{
			lines.add("removeTemp");
		}
	}

	@Test
	public void replaysAFrameInRecordingOrderWithItsState()
	{
		DrawList list = new DrawList();
		Set<Integer> roofs = Collections.singleton(7);
		float[] boat = Mat4.translate(1, 2, 3);

		// the top level's frame: clear, sky, its zones, the dynamic models, Lumina's sky, the alpha pass with a boat
		// nested in it, then the boat's own pre and post, the top-level alpha zones and its temp removal
		list.clear();
		list.skyHd117();
		list.zoneOpaque(null, 3, 4, 0, 1, 3, roofs, true);
		list.zoneOpaque(null, 5, 4, 1, 1, 3, roofs, false);
		list.entityProj(boat);
		list.entityTint(1, 2, 3, 4);
		list.zoneOpaque(null, 0, 0, 0, 0, 3, roofs, true);
		list.entityProj(SceneProgram.IDENTITY);
		list.entityProj(boat);
		list.entityTint(1, 2, 3, 4);
		list.zoneAlpha(null, 0, 0, 100, 200, 0, 0, 3, 0, roofs, true, false);
		list.removeTemp(null);
		list.entityTint(0, 0, 0, 0);
		list.entityProj(SceneProgram.IDENTITY);
		list.dynamicOpaque();
		list.skyLumina();
		list.entityProj(SceneProgram.IDENTITY);
		list.entityTint(0, 0, 0, 0);
		list.zoneAlpha(null, 3, 4, 100, 200, 0, 1, 3, 0, roofs, false, true);
		list.zoneAlpha(null, 3, 4, 100, 200, 0, 1, 3, 1, roofs, false, true);
		list.removeTemp(null);
		assertEquals(21, list.size());

		Log log = new Log();
		list.replay(log);
		assertEquals(Arrays.asList(
			"clear",
			"skyHd117",
			"opaque 3,4 0/1/3 roofs=[7] gap=true",
			"opaque 5,4 1/1/3 roofs=[7] gap=false",
			"proj " + Arrays.toString(boat),
			"tint 1 2 3 4",
			"opaque 0,0 0/0/3 roofs=[7] gap=true",
			"proj " + Arrays.toString(SceneProgram.IDENTITY),
			"proj " + Arrays.toString(boat),
			"tint 1 2 3 4",
			"alpha 0,0 cam=100/200 0/0/3 L0 roofs=[7] unsorted=true depth=false",
			"removeTemp",
			"tint 0 0 0 0",
			"proj " + Arrays.toString(SceneProgram.IDENTITY),
			"dynamic",
			"skyLumina",
			"proj " + Arrays.toString(SceneProgram.IDENTITY),
			"tint 0 0 0 0",
			"alpha 3,4 cam=100/200 0/1/3 L0 roofs=[7] unsorted=false depth=true",
			"alpha 3,4 cam=100/200 0/1/3 L1 roofs=[7] unsorted=false depth=true",
			"removeTemp"
		), log.lines);

		// the replay leaves the list as it is; reset empties it
		assertEquals(21, list.size());
		list.reset();
		assertTrue(list.isEmpty());
		list.replay(new Log());
	}

	@Test
	public void copiesTheProjectionWhenRecorded()
	{
		DrawList list = new DrawList();
		float[] projection = Mat4.translate(1, 2, 3);
		list.entityProj(projection);
		// the caller's array changes after recording: the replay still has the recorded matrix
		float[] recorded = projection.clone();
		projection[12] = 99;

		float[][] seen = new float[1][];
		list.replay(new Log()
		{
			@Override
			public void entityProj(float[] p)
			{
				seen[0] = p;
			}
		});
		assertNotSame(projection, seen[0]);
		assertArrayEquals(recorded, seen[0], 0f);

		// the next frame reuses the copy's storage, no new array
		list.reset();
		list.entityProj(SceneProgram.IDENTITY);
		float[][] again = new float[1][];
		list.replay(new Log()
		{
			@Override
			public void entityProj(float[] p)
			{
				again[0] = p;
			}
		});
		assertSame(seen[0], again[0]);
		assertArrayEquals(SceneProgram.IDENTITY, again[0], 0f);
	}

	@Test
	public void growsPastItsInitialCapacity()
	{
		DrawList list = new DrawList();
		Set<Integer> roofs = Collections.emptySet();
		for (int i = 0; i < 3000; ++i)
		{
			list.zoneAlpha(null, i, -i, 1, 2, 0, 1, 3, i & 3, roofs, (i & 1) != 0, true);
			list.entityProj(SceneProgram.IDENTITY);
		}
		int[] n = new int[1];
		list.replay(new Log()
		{
			@Override
			public void zoneAlpha(Zone zone, int zx, int zz, int cameraYaw, int cameraPitch, int minLevel, int level, int maxLevel,
				int alphaLevel, Set<Integer> hideRoofIds, boolean unsorted, boolean depthPass)
			{
				assertEquals(n[0], zx);
				assertEquals(-n[0], zz);
				assertEquals(n[0] & 3, alphaLevel);
				assertEquals((n[0] & 1) != 0, unsorted);
				n[0]++;
			}
		});
		assertEquals(3000, n[0]);
	}

	@Test
	public void tracesOneFrameWithRunsCollapsed()
	{
		DrawList list = new DrawList();
		// nothing armed: nothing traced
		list.trace('P', -1, -1);
		assertNull(list.traceLine());

		list.traceNextFrame();
		list.trace('P', -1, -1);
		list.trace('o', -1, -1);
		list.trace('o', -1, -1);
		list.trace('t', -1, -1);
		list.trace('P', -1, 5);
		list.trace('o', -1, 5);
		list.trace('D', -1, 5);
		list.trace('a', 0, 5);
		list.trace('a', 0, 5);
		list.trace('a', 1, 5);
		list.trace('Q', -1, 5);
		list.trace('D', -1, -1);
		list.trace('Q', -1, -1);
		assertEquals("P@-1 o@-1x2 t@-1 P@5 o@5 D@5 a0@5x2 a1@5 Q@5 D@-1 Q@-1", list.traceLine());
		// the trace is over after one frame
		list.trace('P', -1, -1);
		assertNull(list.traceLine());
	}
}
