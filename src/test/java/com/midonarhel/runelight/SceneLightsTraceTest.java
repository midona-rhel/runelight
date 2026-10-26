package com.midonarhel.runelight;

import com.google.gson.Gson;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.DynamicObject;
import net.runelite.api.GameObject;
import net.runelite.api.GameState;
import net.runelite.api.IndexedObjectSet;
import net.runelite.api.Model;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * A scene's lights from the objects through to the frame's lights, with the client faked: what Varrock should show
 * by day and by night.
 */
public class SceneLightsTraceTest
{
	// a lantern of 117 HD's, lit at night only (lights.json), and a torch it knows nothing of
	private static final int NIGHT_LANTERN = 4501, TORCH = 90001, FLAME = 6 << 10 | 7 << 7 | 100;

	@SuppressWarnings("unchecked")
	private static <T> T fake(Class<T> c, Map<String, Object> values)
	{
		return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{c}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "hashCode":
					return System.identityHashCode(p);
				case "equals":
					return p == a[0];
				case "toString":
					return c.getSimpleName();
			}
			Object v = values.get(m.getName());
			if (v != null || !m.getReturnType().isPrimitive())
			{
				return v;
			}
			Class<?> r = m.getReturnType();
			return r == boolean.class ? (Object) false : r == byte.class ? (Object) (byte) 0
				: r == short.class ? (Object) (short) 0 : r == long.class ? (Object) 0L
				: r == float.class ? (Object) 0f : r == double.class ? (Object) 0d : (Object) 0;
		});
	}

	private static Map<String, Object> map(Object... kv)
	{
		Map<String, Object> m = new HashMap<>();
		for (int i = 0; i < kv.length; i += 2)
		{
			m.put((String) kv[i], kv[i + 1]);
		}
		return m;
	}

	/** One flame-colored triangle, a meter up. */
	private static Model model()
	{
		return fake(Model.class, map("getVerticesCount", 3, "getVerticesX", new float[]{0, 10, 0},
			"getVerticesY", new float[]{-100, -100, -120}, "getVerticesZ", new float[]{0, 0, 10}, "getFaceCount", 1,
			"getFaceIndices1", new int[]{0}, "getFaceIndices2", new int[]{1}, "getFaceIndices3", new int[]{2},
			"getFaceColors1", new int[]{FLAME}, "getFaceColors2", new int[]{FLAME}, "getFaceColors3", new int[]{FLAME},
			"getFaceRenderPriorities", new byte[1], "getFaceBias", new byte[1], "getTextureFaces", new byte[1],
			"getTexIndices1", new int[256], "getTexIndices2", new int[256], "getTexIndices3", new int[256]));
	}

	private static GameObject object(int id, int tileX, int tileY, Renderable renderable)
	{
		LocalPoint lp = new LocalPoint(tileX * 128 + 64, tileY * 128 + 64);
		return fake(GameObject.class, map("getId", id, "getRenderable", renderable, "getLocalLocation", lp,
			"getX", lp.getX(), "getY", lp.getY(), "getSizeX", 1, "getSizeY", 1,
			"getSceneMinLocation", new net.runelite.api.Point(tileX, tileY)));
	}

	private static SceneLights scene(Renderable torch, Renderable lantern) throws Exception
	{
		Map<Integer, ObjectComposition> comps = new HashMap<>();
		comps.put(TORCH, fake(ObjectComposition.class, map("getName", "Wall torch", "getId", TORCH)));
		comps.put(NIGHT_LANTERN, fake(ObjectComposition.class, map("getName", "Lantern", "getId", NIGHT_LANTERN)));
		Tile tile = fake(Tile.class, map("getGameObjects", new GameObject[]{object(TORCH, 3, 3, torch),
			object(NIGHT_LANTERN, 5, 3, lantern)}));
		Scene scene = fake(Scene.class, map("getExtendedTiles", new Tile[][][]{{{tile}}}, "isInstance", false));
		net.runelite.api.Deque<?> noDeque = fake(net.runelite.api.Deque.class, map("iterator", java.util.Collections.emptyIterator()));
		IndexedObjectSet<?> none = fake(IndexedObjectSet.class, map("iterator", java.util.Collections.emptyIterator()));
		WorldView wv = fake(WorldView.class, map("getScene", scene, "players", none, "npcs", none, "getGraphicsObjects", noDeque));
		Client client = fake(Client.class, map("getGameState", GameState.LOGGED_IN, "getTopLevelWorldView", wv,
			"getProjectiles", noDeque, "getGraphicsObjects", noDeque));
		// the client answers object definitions by id
		Client answering = (Client) Proxy.newProxyInstance(Client.class.getClassLoader(), new Class<?>[]{Client.class},
			(p, m, a) -> m.getName().equals("getObjectDefinition") ? comps.get((Integer) a[0])
				: m.getName().equals("hashCode") ? System.identityHashCode(p) : m.getName().equals("equals") ? p == a[0]
				: m.getName().equals("toString") ? "client" : client.getClass().getMethod(m.getName(), m.getParameterTypes())
				.invoke(client, a));
		SceneLights lights = new SceneLights(answering);
		lights.load(new Gson());
		lights.rebuild();
		return lights;
	}

	private static List<PointLight> frame(SceneLights lights, float night)
	{
		List<PointLight> out = new ArrayList<>();
		lights.collect(out, 10, night, 45, 4 * 128, 3 * 128 + 64, 6000);
		return out;
	}

	@Test
	public void aTorchOfNoLightsJsonEntryIsLitFromItsModel()
	{
		SceneLights lights;
		try
		{
			lights = scene(model(), model());
		}
		catch (Exception e)
		{
			throw new AssertionError(e);
		}
		List<PointLight> day = frame(lights, 0);
		assertEquals("the automatic torch, and not the night lantern", 1, day.size());
		assertEquals(3 * 128 + 64, day.get(0).x, 40);
		assertTrue(day.get(0).intensity > 0);
		assertEquals("both at night", 2, frame(lights, 1).size());
		// the summary a few seconds on counts what the frames showed
		List<PointLight> out = new ArrayList<>();
		lights.collect(out, 30, 0, 45, 4 * 128, 3 * 128 + 64, 6000);
		assertEquals(1, out.size());
	}

	@Test
	public void anAnimatedObjectsModelIsFoundWherTheUploaderFindsIt()
	{
		Model m = model();
		DynamicObject animated = fake(DynamicObject.class, map("getModelZbuf", m));
		assertSame(m, ModelData.modelOf(animated));
		assertSame(m, ModelData.modelOf(m));
		assertNull(ModelData.modelOf(null));
		Model fallback = model();
		assertSame(fallback, ModelData.modelOf(fake(DynamicObject.class, map("getModel", fallback))));
	}

	@Test
	public void anAnimatedTorchGetsItsLight() throws Exception
	{
		DynamicObject fire = fake(DynamicObject.class, map("getModelZbuf", model()));
		List<PointLight> day = frame(scene(fire, model()), 0);
		assertEquals(1, day.size());
		assertNotNull(day.get(0));
	}
}
