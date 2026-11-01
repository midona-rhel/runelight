package com.midonarhel.lumina.harness;

import static org.junit.Assert.assertEquals;
import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneObjects;
import com.midonarhel.lumina.visibility.RoofMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Constants;
import net.runelite.api.Scene;
import org.junit.Test;

/**
 * A roof's lowest point counts no lower than the level it stands on: Ardougne's hip and corner pieces (types 14 and 16)
 * carry the top of the storey's wall under them, 64 below their level, where the shop's lanterns hang (stall5); counted
 * as roof, it cut the lanterns' light to that wall in wedges.
 */
public class RoofMapBuildTest
{
	private static final int SIZE = Constants.EXTENDED_SCENE_SIZE;

	@Test
	public void aRoofStartsAtItsLevel()
	{
		SceneObjects s = new SceneObjects();
		s.size = SIZE;
		int n = 4 * SIZE * SIZE;
		s.paint = new int[n];
		s.tileModel = new boolean[n];
		s.tile = new boolean[n];
		java.util.Arrays.fill(s.paint, SceneObjects.NO_PAINT);
		// a corner piece on level 2 at z -800 hanging to -736, and a straight piece from -800 up
		roof(s, 100, 100, 16, new float[]{64, -192, 0});
		roof(s, 101, 100, 12, new float[]{0, -136, -40});
		SceneObjectFakes f = new SceneObjectFakes(s, LuminaPlugin.SCENE_OFFSET);
		Map<String, Object> sc = new HashMap<>();
		sc.put("getExtendedTiles", f.tiles);
		sc.put("getRoofs", new int[4][SIZE][SIZE]);
		RoofMap m = RoofMap.build(Fakes.of(Scene.class, sc));
		float[] out = new float[SIZE * SIZE];
		m.bottoms(Collections.emptySet(), 0, out);
		assertEquals(-800, out[100 * SIZE + 100] - RoofMap.BIAS, 0);
		assertEquals(-800, out[100 * SIZE + 101] - RoofMap.BIAS, 0);
	}

	private static void roof(SceneObjects s, int x, int y, int type, float[] ys)
	{
		s.tile[(2 * SIZE + x) * SIZE + y] = true;
		SceneObjects.Obj o = new SceneObjects.Obj();
		o.kind = SceneObjects.KIND_GAME;
		o.plane = 2;
		o.x = x;
		o.y = y;
		o.config = type;
		o.z = -800;
		o.minX = x - LuminaPlugin.SCENE_OFFSET;
		o.minY = y - LuminaPlugin.SCENE_OFFSET;
		SceneObjects.Mesh m = new SceneObjects.Mesh();
		m.plain = true;
		m.count = ys.length;
		m.x = new float[ys.length];
		m.y = ys;
		m.z = new float[ys.length];
		o.a = m;
		s.objects.add(o);
	}
}
