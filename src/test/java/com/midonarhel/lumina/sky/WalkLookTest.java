package com.midonarhel.lumina.sky;

import static org.junit.Assert.assertEquals;
import com.google.gson.Gson;
import com.midonarhel.lumina.weather.WeatherData;
import java.lang.reflect.Proxy;
import net.runelite.api.Scene;
import org.junit.Test;

/**
 * The walk of the client's log of 2026-11-06 (every place LookMonitor reported a jump, in order, at a run of 2 tiles
 * per 0.6 s and 60 FPS, the scene base moving by 40 tiles as the client's does): no value of the area's look moves by
 * more than LookMonitor calls a jump in one frame. Prints how many frames did and the largest step per value.
 */
public class WalkLookTest
{
	private static final int[][] ROUTE = {{3239, 3336, 0}, {3214, 3373, 0}, {3211, 3414, 0}, {3245, 3430, 0}, {3285, 3461, 0},
		{3269, 3496, 0}, {3269, 3498, 0}, {3272, 3500, 0}, {3273, 3502, 0}, {3273, 3504, 0}, {3273, 3508, 0}, {3273, 3510, 0},
		{3280, 3509, 0}, {3280, 3507, 0}, {3280, 3503, 0}, {3280, 3501, 0}, {3280, 3497, 0}, {3280, 3495, 0}, {3280, 3496, 0},
		{3280, 3498, 0}, {3282, 3502, 0}, {3285, 3501, 0}, {3285, 3497, 0}, {3285, 3492, 1}, {3282, 3496, 1}, {3281, 3495, 1},
		{3285, 3496, 0}, {3285, 3498, 0}, {3285, 3502, 0}, {3282, 3504, 0}, {3281, 3508, 0}, {3281, 3510, 0}, {3287, 3509, 0},
		{3289, 3507, 0}, {3290, 3503, 0}, {3290, 3501, 0}, {3290, 3497, 0}, {3289, 3495, 0}, {3281, 3475, 0}, {3275, 3433, 0},
		{3241, 3430, 0}, {3212, 3393, 0}, {3208, 3385, 0}};
	private static final String[] NAMES = {"ambientR", "ambientG", "ambientB", "sunR", "sunG", "sunB", "skyTintR", "skyTintG",
		"skyTintB", "fogColorR", "fogColorG", "fogColorB", "fogColorWeight", "fogDepth", "haze"};
	private static final float JUMP = 0.03f, FOG_DEPTH_JUMP = 3;

	private static Scene scene(int baseX, int baseY)
	{
		return (Scene) Proxy.newProxyInstance(Scene.class.getClassLoader(), new Class<?>[]{Scene.class}, (p, m, a) ->
		{
			switch (m.getName())
			{
				case "getBaseX":
					return baseX;
				case "getBaseY":
					return baseY;
				case "isInstance":
					return false;
				case "getExtendedTileSettings":
					return null;
				case "hashCode":
					return System.identityHashCode(p);
				case "equals":
					return p == a[0];
				default:
					throw new AssertionError(m.getName());
			}
		});
	}

	private static float[] values(EnvironmentManager.Ambience a, float[] v)
	{
		System.arraycopy(a.ambient, 0, v, 0, 3);
		System.arraycopy(a.sun, 0, v, 3, 3);
		System.arraycopy(a.skyTint, 0, v, 6, 3);
		// as it reaches the picture, like LookMonitor: at weight 0 it is not used
		for (int k = 0; k < 3; ++k)
		{
			v[9 + k] = a.fogColor[k] * a.fogColorWeight;
		}
		v[12] = a.fogColorWeight;
		v[13] = a.fogDepth;
		v[14] = a.haze;
		return v;
	}

	@Test
	public void noValueJumps()
	{
		WeatherData.load(new Gson());
		EnvironmentManager env = new EnvironmentManager();
		env.load(new Gson());
		int baseX = (ROUTE[0][0] - 52) & ~7, baseY = (ROUTE[0][1] - 52) & ~7;
		Scene scene = scene(baseX, baseY);
		float tiles = 2 / (0.6f * 60);
		float[] now = new float[NAMES.length], before = new float[NAMES.length], most = new float[NAMES.length];
		int[] jumps = new int[NAMES.length];
		float time = 0;
		boolean primed = false;
		double x = ROUTE[0][0] + 0.5, y = ROUTE[0][1] + 0.5;
		int swaps = 0, frames = 0;
		long nanos = 0;
		for (int[] to : ROUTE)
		{
			double dx = to[0] + 0.5 - x, dy = to[1] + 0.5 - y, d = Math.hypot(dx, dy);
			int steps = Math.max((int) Math.ceil(d / tiles), 1);
			for (int s = 0; s < steps; ++s)
			{
				x += dx / steps;
				y += dy / steps;
				// the client reloads with the player within 16 tiles of the scene's edge, 40 tiles over
				if (x - baseX < 30 || x - baseX > 74 || y - baseY < 30 || y - baseY > 74)
				{
					baseX = ((int) x - 52) & ~7;
					baseY = ((int) y - 52) & ~7;
					scene = scene(baseX, baseY);
					swaps++;
				}
				time += 1 / 60f;
				long t0 = System.nanoTime();
				env.update(scene, (int) ((x - baseX) * 128), (int) ((y - baseY) * 128), to[2], 0x808080, time);
				nanos += System.nanoTime() - t0;
				values(env.getAmbience(), now);
				frames++;
				for (int k = 0; primed && k < NAMES.length; ++k)
				{
					float step = Math.abs(now[k] - before[k]);
					most[k] = Math.max(most[k], step);
					jumps[k] += step > (k == 13 ? FOG_DEPTH_JUMP : JUMP) ? 1 : 0;
				}
				primed = true;
				System.arraycopy(now, 0, before, 0, now.length);
			}
		}
		int total = 0;
		StringBuilder t = new StringBuilder(String.format("walk: %d frames, %d scene moves, the update %.1f us a frame%n", frames, swaps, nanos / 1e3 / frames));
		for (int k = 0; k < NAMES.length; ++k)
		{
			total += jumps[k];
			t.append(String.format("%s: %d jumps, largest step %.4f%n", NAMES[k], jumps[k], most[k]));
		}
		System.out.println(t + "WALK total " + total);
		assertEquals(t.toString(), 0, total);
	}
}
