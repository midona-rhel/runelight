package com.midonarhel.lumina.scene;

import com.midonarhel.lumina.LuminaPlugin;
import com.midonarhel.lumina.dev.PlainData;
import com.midonarhel.lumina.dev.SceneTiles;
import com.midonarhel.lumina.water.WaterMap;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import net.runelite.api.WorldView;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The bench draws a scene's water beds again from what a scene dump keeps of its tiles (SceneTiles through PlainData, and
 * its "scene." getters) exactly as SceneUploader draws them from the scene itself.
 */
public class BedRedrawTest
{
	private static final int N = WaterMap.SIZE;
	private static final int OFF = LuminaPlugin.SCENE_OFFSET;

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> c, java.util.function.Function<String, Object> answer)
	{
		return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{c}, (p, m, a) ->
		{
			Object v = answer.apply(m.getName());
			Class<?> r = m.getReturnType();
			return v != null || !r.isPrimitive() ? v : r == boolean.class ? (Object) false : (Object) 0;
		});
	}

	private static int hsl(int h, int s, int l)
	{
		return h << 10 | s << 7 | l;
	}

	/**
	 * A lake (extended x 80-87, y 80-87) of painted water in a ring of shore tiles that are shaped models, half water and
	 * half land (the bank's own colours, which the bed takes where it meets them), on land painted in colours that change
	 * from tile to tile.
	 */
	private static Map<String, Object> scene(Tile[][][] tiles)
	{
		Random r = new Random(7);
		int[][][] heights = new int[4][N + 1][N + 1];
		short[][][] overlays = new short[4][N][N], underlays = new short[4][N][N];
		byte[][][] settings = new byte[4][N][N];
		int[][][] roofs = new int[4][N][N];
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				boolean lake = x >= 80 && x < 88 && y >= 80 && y < 88;
				boolean shore = lake && (x == 80 || x == 87 || y == 80 || y == 87);
				int sx = x - OFF, sy = y - OFF;
				Point loc = new Point(sx, sy);
				SceneTilePaint paint = null;
				SceneTileModel model = null;
				if (shore)
				{
					// two triangles across the tile: the one toward the lake water (texture 1), the other land
					int x0 = sx * 128, x1 = x0 + 128, z0 = sy * 128, z1 = z0 + 128;
					int[] vx = {x0, x1, x1, x0}, vy = {0, 0, 0, 0}, vz = {z0, z0, z1, z1};
					int[] fa = {0, 0}, fb = {1, 2}, fc = {2, 3};
					int land = hsl(8 + r.nextInt(8), 2 + r.nextInt(3), 30 + r.nextInt(40));
					int[] ca = {hsl(37, 2, 44), land}, cb = {hsl(37, 2, 44), land}, cc = {hsl(37, 2, 44), land};
					int[] tex = {1, -1};
					model = proxy(SceneTileModel.class, m ->
					{
						switch (m)
						{
							case "getVertexX":
								return vx;
							case "getVertexY":
								return vy;
							case "getVertexZ":
								return vz;
							case "getFaceX":
								return fa;
							case "getFaceY":
								return fb;
							case "getFaceZ":
								return fc;
							case "getTriangleColorA":
								return ca;
							case "getTriangleColorB":
								return cb;
							case "getTriangleColorC":
								return cc;
							case "getTriangleTextureId":
								return tex;
							case "getShape":
								return 1;
							default:
								return null;
						}
					});
				}
				else
				{
					int texture = lake ? 1 : -1;
					int c0 = hsl(r.nextInt(64), r.nextInt(8), 20 + r.nextInt(90)), c1 = hsl(r.nextInt(64), r.nextInt(8), 20 + r.nextInt(90));
					paint = proxy(SceneTilePaint.class, m ->
					{
						switch (m)
						{
							case "getTexture":
								return texture;
							case "getSwColor":
							case "getNeColor":
								return c0;
							case "getSeColor":
							case "getNwColor":
								return c1;
							default:
								return null;
						}
					});
				}
				SceneTilePaint p = paint;
				SceneTileModel md = model;
				tiles[0][x][y] = proxy(Tile.class, m ->
				{
					switch (m)
					{
						case "getSceneTilePaint":
							return p;
						case "getSceneTileModel":
							return md;
						case "getRenderLevel":
							return 0;
						case "getSceneLocation":
							return loc;
						default:
							return null;
					}
				});
			}
		}
		Map<String, Object> d = new HashMap<>();
		d.put("scene.getTileHeights", heights);
		d.put("scene.getOverlayIds", overlays);
		d.put("scene.getUnderlayIds", underlays);
		d.put("scene.getExtendedTileSettings", settings);
		d.put("scene.getRoofs", roofs);
		d.put("scene.getWorldViewId", WorldView.TOPLEVEL);
		d.put("scene.getBaseX", 3200);
		d.put("scene.getBaseY", 3200);
		return d;
	}

	private static Scene of(Tile[][][] tiles, Map<String, Object> d)
	{
		return proxy(Scene.class, m -> m.equals("getExtendedTiles") ? tiles : d.get("scene." + m));
	}

	@Test
	public void theBedsDrawnFromWhatTheDumpKeepsAreTheScenes() throws Exception
	{
		Tile[][][] tiles = new Tile[4][N][N];
		Map<String, Object> d = scene(tiles);
		Scene scene = of(tiles, d);
		WaterMap water = WaterMap.build(scene, null, null);
		// what the dump keeps of the tiles, through the dump's own writing and reading
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		new PlainData.Writer(new DataOutputStream(bytes)).write(SceneTiles.record(scene, water));
		SceneTiles kept = (SceneTiles) new PlainData.Reader(new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))).read();
		Scene again = BedRedraw.scene(d, kept);
		int beds = 0, shore = 0;
		for (int mzx = 9; mzx <= 10; ++mzx)
		{
			for (int mzz = 9; mzz <= 10; ++mzz)
			{
				Map<String, int[]> first = BedRedraw.beds(scene, water, mzx, mzz), second = BedRedraw.beds(again, water, mzx, mzz);
				assertEquals(first.keySet(), second.keySet());
				for (Map.Entry<String, int[]> e : first.entrySet())
				{
					assertTrue(e.getKey(), java.util.Arrays.equals(e.getValue(), second.get(e.getKey())));
					// a shore tile's bed takes its bank's colours where it meets them: colours other than the water's
					shore += e.getValue()[0] != (37 << 10 | 2 << 7 | 44) ? 1 : 0;
				}
				beds += first.size();
			}
		}
		assertTrue("beds drawn: " + beds, beds > 100);
		assertTrue("shore colours: " + shore, shore > 0);
	}
}
