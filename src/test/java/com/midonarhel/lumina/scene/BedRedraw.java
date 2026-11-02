package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.SceneTiles;
import com.midonarhel.lumina.water.WaterMap;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;

/**
 * The bench's water beds drawn again by this tree's SceneUploader from the tiles a scene dump keeps (SceneTiles, and the
 * scene's heights, overlay and underlay ids and settings from its "scene." getters), the dump's own drawn beds then
 * recoloured with them: how a change to the beds' colours shows before it reaches the client. Each bed triangle is matched
 * by its three corners (a zone's triangles come in another order than here); its geometry is the dump's. Bench only.
 */
public final class BedRedraw
{
	// a vertex in a zone's buffer: 6 ints (Zone.VERT_SIZE), the colour in the third, the material bits in the fourth
	private static final int STRIDE = 6;

	/** What a redraw did: the dump's bed triangles found again, and how many of them changed colour. */
	public static final class Result
	{
		public int triangles, matched, recoloured;

		@Override
		public String toString()
		{
			return "BEDREDRAW " + matched + " of " + triangles + " bed triangles drawn again, " + recoloured + " recoloured";
		}
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> c, java.util.function.Function<Method, Object> answer)
	{
		return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{c}, (p, m, a) -> answer.apply(m));
	}

	private static Object zero(Class<?> type)
	{
		return type == int.class ? (Object) 0 : type == boolean.class ? (Object) false : type == long.class ? (Object) 0L
			: type == short.class ? (Object) (short) 0 : type == byte.class ? (Object) (byte) 0 : type == float.class
			? (Object) 0f : type == double.class ? (Object) 0.0 : null;
	}

	/** The scene as a dump keeps it: its "scene." getters' values, the tiles by the water (others null). */
	public static Scene scene(Map<String, Object> derived, SceneTiles kept)
	{
		Tile[][][] tiles = new Tile[kept.planes][kept.size][kept.size];
		for (int z = 0; z < kept.planes; ++z)
		{
			for (int x = 0; x < kept.size; ++x)
			{
				for (int y = 0; y < kept.size; ++y)
				{
					SceneTiles.T t = kept.tiles.get(kept.key(z, x, y));
					tiles[z][x][y] = t != null ? tile(t) : null;
				}
			}
		}
		return proxy(Scene.class, m ->
		{
			if (m.getName().equals("getExtendedTiles"))
			{
				return tiles;
			}
			Object v = derived.get("scene." + m.getName());
			return v != null ? v : zero(m.getReturnType());
		});
	}

	private static Tile tile(SceneTiles.T t)
	{
		SceneTilePaint paint = t.paint == null ? null : proxy(SceneTilePaint.class, m ->
		{
			switch (m.getName())
			{
				case "getTexture":
					return t.paint.texture;
				case "getSwColor":
					return t.paint.sw;
				case "getSeColor":
					return t.paint.se;
				case "getNeColor":
					return t.paint.ne;
				case "getNwColor":
					return t.paint.nw;
				default:
					return zero(m.getReturnType());
			}
		});
		SceneTileModel model = t.model == null ? null : proxy(SceneTileModel.class, m ->
		{
			switch (m.getName())
			{
				case "getVertexX":
					return t.model.vertexX;
				case "getVertexY":
					return t.model.vertexY;
				case "getVertexZ":
					return t.model.vertexZ;
				case "getFaceX":
					return t.model.faceX;
				case "getFaceY":
					return t.model.faceY;
				case "getFaceZ":
					return t.model.faceZ;
				case "getTriangleColorA":
					return t.model.colorA;
				case "getTriangleColorB":
					return t.model.colorB;
				case "getTriangleColorC":
					return t.model.colorC;
				case "getTriangleTextureId":
					return t.model.textures;
				case "getShape":
					return t.model.shape;
				default:
					return zero(m.getReturnType());
			}
		});
		Tile bridge = t.bridge != null ? tile(t.bridge) : null;
		Point loc = new Point(t.x, t.y);
		return proxy(Tile.class, m ->
		{
			switch (m.getName())
			{
				case "getSceneTilePaint":
					return paint;
				case "getSceneTileModel":
					return model;
				case "getRenderLevel":
					return t.renderLevel;
				case "getSceneLocation":
					return loc;
				case "getBridge":
					return bridge;
				default:
					return zero(m.getReturnType());
			}
		});
	}

	private static Object get(Object o, String name) throws ReflectiveOperationException
	{
		Field f = SceneUploader.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	private static void set(Object o, String name, Object v) throws ReflectiveOperationException
	{
		Field f = SceneUploader.class.getDeclaredField(name);
		f.setAccessible(true);
		f.set(o, v);
	}

	/**
	 * The bed triangles SceneUploader draws for the tiles of extended zone (mzx, mzz), keyed by their corners (bedKey):
	 * their three colours. The zone's ground blended first, as uploadZone does.
	 */
	public static Map<String, int[]> beds(Scene scene, WaterMap water, int mzx, int mzz) throws ReflectiveOperationException
	{
		SceneUploader up = new SceneUploader(null);
		up.water = water;
		int offset = LuminaPlugin.SCENE_OFFSET >> 3;
		set(up, "scene", scene);
		set(up, "sinkWater", true);
		set(up, "basex", (mzx - offset) << 10);
		set(up, "basez", (mzz - offset) << 10);
		((GroundBlender) get(up, "groundBlender")).build(scene, mzx, mzz);
		Method paintUpload = SceneUploader.class.getDeclaredMethod("upload", Scene.class, SceneTilePaint.class, int.class,
			int.class, int.class, GpuIntBuffer.class, int.class, int.class);
		Method modelUpload = SceneUploader.class.getDeclaredMethod("upload", Scene.class, SceneTileModel.class, int.class,
			int.class, int.class, GpuIntBuffer.class);
		paintUpload.setAccessible(true);
		modelUpload.setAccessible(true);
		int basex = (mzx - offset) << 10, basez = (mzz - offset) << 10;
		IntBuffer ib = IntBuffer.allocate(1 << 20);
		GpuIntBuffer vb = new GpuIntBuffer(ib);
		Tile[][][] tiles = scene.getExtendedTiles();
		for (int z = 0; z < tiles.length; ++z)
		{
			for (int xoff = 0; xoff < 8; ++xoff)
			{
				for (int zoff = 0; zoff < 8; ++zoff)
				{
					int x = (mzx << 3) + xoff, y = (mzz << 3) + zoff;
					if (x >= tiles[z].length || y >= tiles[z][x].length)
					{
						continue;
					}
					for (Tile t = tiles[z][x][y]; t != null; t = t.getBridge())
					{
						set(up, "tile", t);
						Point p = t.getSceneLocation();
						if (t.getSceneTilePaint() != null)
						{
							paintUpload.invoke(up, scene, t.getSceneTilePaint(), t.getRenderLevel(), p.getX(), p.getY(), vb,
								p.getX() * 128 - basex, p.getY() * 128 - basez);
						}
						if (t.getSceneTileModel() != null)
						{
							modelUpload.invoke(up, scene, t.getSceneTileModel(), t.getRenderLevel(), p.getX(), p.getY(), vb);
						}
					}
				}
			}
		}
		int[] a = new int[ib.position()];
		ib.flip();
		ib.get(a);
		return beds(a, 0, 0);
	}

	/** The bed triangles of vertices a (zone local, the zone at (ox, oz) added), by their corners: their colours. */
	static Map<String, int[]> beds(int[] a, int ox, int oz)
	{
		Map<String, int[]> out = new HashMap<>();
		int fake = WaterMap.BED_FLAG | WaterMap.LAND_FLAG;
		for (int i = 0; a != null && i + 3 * STRIDE <= a.length; i += 3 * STRIDE)
		{
			boolean bed = true;
			for (int k = 0; k < 3 && bed; ++k)
			{
				bed = ((a[i + k * STRIDE + 3] & 0xffff) & fake) == WaterMap.BED_FLAG;
			}
			if (bed)
			{
				out.put(bedKey(a, i, ox, oz), new int[]{a[i + 2] & 0xffff, a[i + STRIDE + 2] & 0xffff, a[i + 2 * STRIDE + 2] & 0xffff});
			}
		}
		return out;
	}

	/** A triangle's three corners (x, y, z each), in their order, as a key. */
	private static String bedKey(int[] a, int i, int ox, int oz)
	{
		StringBuilder sb = new StringBuilder();
		for (int k = 0; k < 3; ++k)
		{
			int o = i + k * STRIDE;
			sb.append((short) (a[o] & 0xffff) + ox).append(',').append((short) (a[o] >> 16)).append(',')
				.append((short) (a[o + 1] & 0xffff) + oz).append(';');
		}
		return sb.toString();
	}

	/**
	 * The dump's drawn beds (the top level's zones) recoloured as this tree's SceneUploader draws them from the dump's
	 * tiles: each bed triangle found again by its corners takes the colours drawn now.
	 */
	public static Result apply(List<ZoneSnapshot> zones, Map<String, Object> derived, SceneTiles kept, WaterMap water)
		throws ReflectiveOperationException
	{
		Result r = new Result();
		Scene scene = scene(derived, kept);
		for (ZoneSnapshot s : zones)
		{
			int[] a = s.vertsO;
			Map<String, int[]> drawn = null;
			int fake = WaterMap.BED_FLAG | WaterMap.LAND_FLAG;
			for (int i = 0; a != null && i + 3 * STRIDE <= a.length; i += 3 * STRIDE)
			{
				boolean bed = true;
				for (int k = 0; k < 3 && bed; ++k)
				{
					bed = ((a[i + k * STRIDE + 3] & 0xffff) & fake) == WaterMap.BED_FLAG;
				}
				if (!bed)
				{
					continue;
				}
				++r.triangles;
				if (drawn == null)
				{
					drawn = beds(scene, water, s.x, s.z);
				}
				int[] c = drawn.get(bedKey(a, i, 0, 0));
				if (c == null)
				{
					continue;
				}
				++r.matched;
				boolean changed = false;
				for (int k = 0; k < 3; ++k)
				{
					int o = i + k * STRIDE + 2;
					changed |= (a[o] & 0xffff) != c[k];
					a[o] = (a[o] & 0xffff0000) | c[k];
				}
				r.recoloured += changed ? 1 : 0;
			}
		}
		return r;
	}
}
