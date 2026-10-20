package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class AreaHidingTest
{
	private static final int OFF = RuneLightPlugin.SCENE_OFFSET;
	private static final int EXT = Constants.EXTENDED_SCENE_SIZE;

	private static AreaHiding.Area area(String name, int[][] area, int[][] unhide)
	{
		return new AreaHiding.Area(name, area, unhide);
	}

	private static final int[][] NONE = {};

	private static class FakeScene
	{
		final int baseX, baseY;
		final int[][][] templates;
		final Tile[][][] tiles = new Tile[Constants.MAX_Z][EXT][EXT];
		final List<Tile> removed = new ArrayList<>();
		final Scene scene;

		FakeScene(int baseX, int baseY, int[][][] templates)
		{
			this.baseX = baseX;
			this.baseY = baseY;
			this.templates = templates;
			scene = (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(), new Class<?>[]{Scene.class}, (p, m, a) ->
			{
				switch (m.getName())
				{
					case "isInstance":
						return templates != null;
					case "getBaseX":
						return baseX;
					case "getBaseY":
						return baseY;
					case "getExtendedTiles":
						return tiles;
					case "getInstanceTemplateChunks":
						return templates;
					case "removeTile":
						removed.add((Tile) a[0]);
						return null;
					default:
						return identity(p, m, a);
				}
			});
		}

		// what lets the proxies sit in lists
		private static Object identity(Object p, java.lang.reflect.Method m, Object[] a)
		{
			switch (m.getName())
			{
				case "equals":
					return p == a[0];
				case "hashCode":
					return System.identityHashCode(p);
				default:
					return null;
			}
		}

		/** A tile at scene tile (x, y) of a plane, as the scene keeps it. */
		Tile put(int plane, int x, int y)
		{
			Tile t = (Tile) Proxy.newProxyInstance(Tile.class.getClassLoader(), new Class<?>[]{Tile.class}, (p, m, a) -> identity(p, m, a));
			tiles[plane][x + OFF][y + OFF] = t;
			return t;
		}
	}

	@Test
	public void containsTakesPlanesAndUnhiddenBoxesIntoAccount()
	{
		AreaHiding.Area a = area("A", new int[][]{{10, 10, 1, 19, 19, 2}}, new int[][]{{30, 30, 0, 31, 31, 0}});
		assertTrue(a.contains(false, 10, 19, 1));
		assertTrue(a.contains(false, 19, 10, 2));
		assertFalse(a.contains(false, 20, 10, 1));
		assertFalse(a.contains(false, 15, 15, 0));
		assertFalse(a.contains(false, 15, 15, 3));
		// the boxes it shows are not part of it, but stay in the scene
		assertFalse(a.contains(false, 30, 30, 0));
		assertTrue(a.contains(true, 30, 30, 0));
		assertFalse(a.contains(true, 30, 30, 1));
	}

	@Test
	public void theFirstAreaContainingThePointIsTheOne()
	{
		AreaHiding.Area lower = area("LOWER", new int[][]{{0, 0, 0, 99, 99, 1}}, NONE);
		AreaHiding.Area upper = area("UPPER", new int[][]{{40, 40, 1, 60, 60, 1}}, NONE);
		AreaHiding hiding = new AreaHiding(lower, upper);
		// the lower floor includes a bit of the floor above and wins there, as in 117 HD
		assertSame(lower, hiding.locate(50, 50, 1));
		assertSame(lower, hiding.locate(5, 5, 0));
		assertNull(hiding.locate(50, 50, 2));
		assertNull(hiding.locate(100, 5, 0));
		assertSame(upper, new AreaHiding(upper, lower).locate(50, 50, 1));
	}

	@Test
	public void importedAreas()
	{
		AreaHiding hiding = new AreaHiding();
		hiding.load(new Gson());
		// Tears of Guthix is a cave of its own
		assertEquals("TEARS_OF_GUTHIX", hiding.locate(3230, 9500, 0).name);
		// the surface of the mainland hides what is loaded around it
		assertEquals("MAINLAND", hiding.locate(3222, 3218, 0).name);
		assertNull(hiding.locate(200, 200, 0));
		// the second Karuulm floor shows a bit of the first below it
		AreaHiding.Area floor = hiding.locate(1250, 10150, 2);
		assertTrue(floor.name.endsWith("PLANE_2"));
		assertTrue(floor.contains(true, 1300, 10200, 0));
		assertFalse(floor.contains(false, 1300, 10200, 0));
	}

	@Test
	public void tilesOutsideTheAreaAreRemoved()
	{
		// a scene whose base is world (3200, 9472): the area covers its tiles 10..19 on the ground floor
		FakeScene f = new FakeScene(3200, 9472, null);
		Tile in = f.put(0, 12, 15), edge = f.put(0, 19, 10), out = f.put(0, 20, 15), upstairs = f.put(1, 12, 15);
		// past the loaded map, the extended tiles map as well
		Tile beyond = f.put(0, -5, 15);
		AreaHiding hiding = new AreaHiding(area("CAVE", new int[][]{{3210, 9482, 0, 3219, 9491, 0}}, NONE));
		hiding.prepare(f.scene, true, 0, new int[]{12, 15});
		assertEquals(3, f.removed.size());
		assertTrue(f.removed.contains(out) && f.removed.contains(upstairs) && f.removed.contains(beyond));
		assertFalse(f.removed.contains(in) || f.removed.contains(edge));
	}

	@Test
	public void nothingIsRemovedOutsideEveryAreaOrWhenOff()
	{
		FakeScene f = new FakeScene(3200, 9472, null);
		f.put(0, 12, 15);
		AreaHiding hiding = new AreaHiding(area("CAVE", new int[][]{{3210, 9482, 0, 3219, 9491, 0}}, NONE));
		hiding.prepare(f.scene, true, 0, new int[]{50, 50});
		hiding.prepare(f.scene, false, 0, new int[]{12, 15});
		assertTrue(f.removed.isEmpty());
	}

	@Test
	public void aNewMapIsCutToTheAreaOfItsMiddleChunkIfItIsAllInOne()
	{
		// the middle chunk is scene tiles 48..55
		FakeScene f = new FakeScene(3200, 9472, null);
		Tile far = f.put(0, 90, 90), middle = f.put(0, 50, 50);
		AreaHiding whole = new AreaHiding(area("CAVE", new int[][]{{3248, 9520, 0, 3255, 9527, 0}}, NONE));
		whole.prepare(f.scene, true, 0, null);
		assertEquals(1, f.removed.size());
		assertSame(far, f.removed.get(0));
		assertFalse(f.removed.contains(middle));

		// part of the chunk in the area: the player's tile is not known yet, so nothing is hidden for now
		f.removed.clear();
		AreaHiding part = new AreaHiding(area("CAVE", new int[][]{{3248, 9520, 0, 3251, 9527, 0}}, NONE));
		part.prepare(f.scene, true, 0, null);
		assertTrue(f.removed.isEmpty());
		assertTrue(part.sameAsShown());
	}

	@Test
	public void instancesAreMatchedByTheChunksTheyAreBuiltFrom()
	{
		// every chunk of the instance but one is empty; that one is the real chunk (400, 1200), turned once
		int[][][] templates = new int[Constants.MAX_Z][Constants.SCENE_SIZE / 8][Constants.SCENE_SIZE / 8];
		for (int[][] plane : templates)
		{
			for (int[] column : plane)
			{
				java.util.Arrays.fill(column, -1);
			}
		}
		templates[0][2][3] = 400 << 14 | 1200 << 3 | 1 << 1;
		FakeScene f = new FakeScene(0, 0, templates);
		// scene tile (17, 25) is in chunk (2, 3); turned once its x runs along the real chunk's -y
		f.put(0, 17, 25);
		AreaHiding hiding = new AreaHiding(area("COPY", new int[][]{{3200, 9600, 0, 3207, 9607, 0}}, NONE));
		int[] world = new int[3];
		assertTrue(EnvironmentManager.toWorld(f.scene, 17, 25, 0, world));
		assertTrue(world[0] >= 3200 && world[0] <= 3207 && world[1] >= 9600 && world[1] <= 9607);
		assertSame(hiding.locate(world[0], world[1], world[2]), hiding.locate(f.scene, 17, 25, 0));
		hiding.prepare(f.scene, true, 0, new int[]{17, 25});
		assertTrue(f.removed.isEmpty());
		assertNull(hiding.locate(f.scene, 40, 40, 0));
		assertNull(hiding.locate(f.scene, 17, 25, 1));
	}

	@Test
	public void aSceneIsOnlyLoadedAgainOnceForAnAreaChange()
	{
		AreaHiding.Area cave = area("CAVE", new int[][]{{3210, 9482, 0, 3219, 9491, 0}}, NONE);
		AreaHiding hiding = new AreaHiding(cave);
		FakeScene f = new FakeScene(3200, 9472, null);
		// on the surface: nothing to change
		assertFalse(hiding.changed(f.scene, true, 50, 50, 0));
		// walked into the cave
		assertTrue(hiding.changed(f.scene, true, 12, 12, 0));
		assertFalse(hiding.changed(f.scene, true, 12, 12, 0));
		hiding.prepare(f.scene, true, 0, new int[]{12, 12});
		assertFalse(hiding.sameAsShown());
		hiding.swapped();
		assertFalse(hiding.changed(f.scene, true, 13, 12, 0));
		// the option turned off
		assertTrue(hiding.changed(f.scene, false, 13, 12, 0));
		hiding.prepare(f.scene, false, 0, new int[]{13, 12});
		hiding.swapped();
		assertFalse(hiding.changed(f.scene, false, 13, 12, 0));
		// and on again, then out of the cave
		assertTrue(hiding.changed(f.scene, true, 13, 12, 0));
		hiding.prepare(f.scene, true, 0, new int[]{13, 12});
		hiding.swapped();
		assertTrue(hiding.changed(f.scene, true, 50, 50, 0));
		hiding.prepare(f.scene, true, 0, new int[]{50, 50});
		hiding.swapped();
		assertTrue(hiding.sameAsShown());
		assertFalse(hiding.changed(f.scene, true, 50, 50, 0));
	}
}
