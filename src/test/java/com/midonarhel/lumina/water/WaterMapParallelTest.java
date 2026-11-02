package com.midonarhel.lumina.water;

import com.midonarhel.lumina.LuminaPlugin;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.runelite.api.GroundObject;
import net.runelite.api.Model;
import net.runelite.api.Point;
import net.runelite.api.Scene;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Tile;
import org.junit.Test;
import static org.junit.Assert.assertTrue;

/**
 * WaterMap.build sharing its work with other threads (the loader's upload threads) makes the same map as on one thread.
 */
public class WaterMapParallelTest
{
	private static final int N = WaterMap.SIZE;
	private static final int OFF = LuminaPlugin.SCENE_OFFSET;

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> c, java.util.function.Function<String, Object> answer)
	{
		return (T) Proxy.newProxyInstance(c.getClassLoader(), new Class<?>[]{c}, (p, m, a) -> answer.apply(m.getName()));
	}

	/**
	 * A ground object on tile (tx, tz) whose model is the quads given, four corners each {x, y, z} in scene extended units
	 * (y down), each two faces; with `texture` on them (1, fresh water: fake water) or none (-1).
	 */
	private static GroundObject object(int tx, int tz, float[][] quads, short texture)
	{
		int ox = tx * 128 + 64, oz = tz * 128 + 64;
		int n = quads.length;
		float[] vx = new float[4 * n], vy = new float[4 * n], vz = new float[4 * n];
		int[] f1 = new int[2 * n], f2 = new int[2 * n], f3 = new int[2 * n], colors = new int[2 * n];
		short[] textures = new short[2 * n];
		for (int q = 0; q < n; ++q)
		{
			for (int k = 0; k < 4; ++k)
			{
				vx[q * 4 + k] = quads[q][k * 3] - ox;
				vy[q * 4 + k] = quads[q][k * 3 + 1];
				vz[q * 4 + k] = quads[q][k * 3 + 2] - oz;
			}
			f1[q * 2] = q * 4;
			f2[q * 2] = q * 4 + 1;
			f3[q * 2] = q * 4 + 2;
			f1[q * 2 + 1] = q * 4 + 1;
			f2[q * 2 + 1] = q * 4 + 3;
			f3[q * 2 + 1] = q * 4 + 2;
			textures[q * 2] = textures[q * 2 + 1] = texture;
		}
		Model model = proxy(Model.class, m ->
		{
			switch (m)
			{
				case "getVerticesX":
					return vx;
				case "getVerticesY":
					return vy;
				case "getVerticesZ":
					return vz;
				case "getFaceIndices1":
					return f1;
				case "getFaceIndices2":
					return f2;
				case "getFaceIndices3":
					return f3;
				case "getFaceColors1":
				case "getFaceColors3":
					return colors;
				case "getFaceTextures":
					return texture >= 0 ? textures : null;
				case "getVerticesCount":
					return 4 * n;
				case "getFaceCount":
					return 2 * n;
				default:
					return null;
			}
		});
		return proxy(GroundObject.class, m ->
		{
			switch (m)
			{
				case "getRenderable":
					return model;
				case "getX":
					return ox - (OFF << 7);
				case "getY":
					return oz - (OFF << 7);
				case "getId":
				case "getZ":
					return 0;
				default:
					return null;
			}
		});
	}

	private static Tile tile(int x, int y, int texture, GroundObject ground)
	{
		SceneTilePaint paint = proxy(SceneTilePaint.class, m -> "getTexture".equals(m) ? (Object) texture
			: "getNeColor".equals(m) ? (Object) 0 : null);
		Point loc = new Point(x - OFF, y - OFF);
		return proxy(Tile.class, m ->
		{
			switch (m)
			{
				case "getSceneTilePaint":
					return paint;
				case "getRenderLevel":
					return 0;
				case "getSceneLocation":
					return loc;
				case "getGroundObject":
					return ground;
				default:
					return null;
			}
		});
	}

	/**
	 * A lake (x 20-159, y 60-119) on rolling land, stepping down 256 at a terrain fall across most of it (row 89) and at
	 * x 150-152 over a land tile that a model lays a sheet of water on; posts standing in it every few tiles.
	 */
	private static Scene lake(long seed)
	{
		Random r = new Random(seed);
		Tile[][][] tiles = new Tile[4][N][N];
		int[][][] heights = new int[4][N + 1][N + 1];
		for (int x = 0; x <= N; ++x)
		{
			for (int y = 0; y <= N; ++y)
			{
				boolean lake = x >= 20 && x <= 160 && y >= 60 && y <= 120;
				heights[0][x][y] = lake ? (y >= 90 ? 256 : 0) : r.nextInt(40) - 20;
			}
		}
		for (int x = 0; x < N; ++x)
		{
			for (int y = 0; y < N; ++y)
			{
				boolean water = x >= 20 && x < 160 && y >= 60 && y < 120 && !(y == 89 && x >= 150 && x < 153);
				GroundObject g = null;
				if (y == 89 && x == 150)
				{
					// the sheet on the land slope, a unit over it, x 150-153
					g = object(x, y, new float[][]{{150 * 128, -1, 89 * 128, 153 * 128, -1, 89 * 128, 150 * 128, 255, 90 * 128,
						153 * 128, 255, 90 * 128}}, (short) 1);
				}
				else if (water && x % 3 == 0 && y % 4 == 1)
				{
					// a post standing in the water: four sides from below its surface to above it
					float x0 = x * 128 + 40 + r.nextInt(20), z0 = y * 128 + 40 + r.nextInt(20), x1 = x0 + 30, z1 = z0 + 30;
					float s = heights[0][x][y];
					float lo = s + 60, hi = s - 80;
					g = object(x, y, new float[][]{{x0, lo, z0, x1, lo, z0, x0, hi, z0, x1, hi, z0},
						{x1, lo, z0, x1, lo, z1, x1, hi, z0, x1, hi, z1},
						{x1, lo, z1, x0, lo, z1, x1, hi, z1, x0, hi, z1},
						{x0, lo, z1, x0, lo, z0, x0, hi, z1, x0, hi, z0}}, (short) -1);
				}
				tiles[0][x][y] = tile(x, y, water ? 1 : -1, g);
			}
		}
		return proxy(Scene.class, m ->
		{
			switch (m)
			{
				case "getExtendedTiles":
					return tiles;
				case "getTileHeights":
					return heights;
				case "getBaseX":
				case "getBaseY":
					return 3200;
				default:
					return null;
			}
		});
	}

	/** Every field of a and b the same, the bodies' too, arrays element by element; the build's own bookkeeping aside. */
	private static void assertSameMap(Object a, Object b, String path) throws Exception
	{
		for (Field f : a.getClass().getDeclaredFields())
		{
			if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("pool") || f.getName().equals("partMs"))
			{
				continue;
			}
			f.setAccessible(true);
			Object x = f.get(a), y = f.get(b);
			if (x instanceof WaterBodies)
			{
				assertSameMap(x, y, path + f.getName() + ".");
			}
			else
			{
				assertTrue(path + f.getName(), Objects.deepEquals(x, y));
			}
		}
	}

	@Test
	public void sharedWithOtherThreadsTheMapIsTheSame() throws Exception
	{
		ExecutorService pool = Executors.newFixedThreadPool(3);
		try
		{
			for (long seed = 1; seed <= 3; ++seed)
			{
				Scene scene = lake(seed);
				WaterMap one = WaterMap.build(scene, null, null, null);
				WaterMap shared = WaterMap.build(scene, null, null, pool);
				// the scene has what the bands split: falls (the terrain's and a model's on land) and crossings
				assertTrue(one.fall[89 * N + 40] && one.fall[89 * N + 151]);
				assertTrue(one.contact != null);
				assertSameMap(one, shared, "");
			}
		}
		finally
		{
			pool.shutdownNow();
		}
	}

	/** Not a test: what the parts of the build take on one thread and shared, medians in ms (run by hand). */
	public static void main(String[] args) throws Exception
	{
		ExecutorService pool = Executors.newFixedThreadPool(3);
		Scene scene = lake(1);
		List<double[]> one = new ArrayList<>(), shared = new ArrayList<>();
		for (int i = 0; i < 40; ++i)
		{
			long t = System.nanoTime();
			WaterMap a = WaterMap.build(scene, null, null, null);
			double ta = (System.nanoTime() - t) / 1e6;
			t = System.nanoTime();
			WaterMap b = WaterMap.build(scene, null, null, pool);
			double tb = (System.nanoTime() - t) / 1e6;
			if (i >= 10)
			{
				double[] pa = java.util.Arrays.copyOf(a.partMs, a.partMs.length + 1);
				double[] pb = java.util.Arrays.copyOf(b.partMs, b.partMs.length + 1);
				pa[pa.length - 1] = ta;
				pb[pb.length - 1] = tb;
				one.add(pa);
				shared.add(pb);
			}
		}
		for (int k = 0; k <= WaterMap.PARTS.length; ++k)
		{
			int kk = k;
			double ma = one.stream().mapToDouble(p -> p[kk]).sorted().toArray()[one.size() / 2];
			double mb = shared.stream().mapToDouble(p -> p[kk]).sorted().toArray()[shared.size() / 2];
			System.out.printf("%-11s one %7.2f ms  shared %7.2f ms%n", k < WaterMap.PARTS.length ? WaterMap.PARTS[k] : "total", ma, mb);
		}
		pool.shutdownNow();
	}
}
