package com.midonarhel.lumina.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import com.midonarhel.lumina.dev.PlainData;
import org.junit.Test;

/**
 * The field across scene moves, as Weather.updateField drives it: the window laid again 40 tiles on, in local units of
 * a base that moves with it, over a snowy region west of a wet one and a dry one. Each frame the bytes the textures are
 * made from are read through the rect the shaders get, at fixed world points, against the eased cells there: a move
 * must change nothing. Prints the largest step of the ground and of the rain a shader reads.
 */
public class WeatherFieldMoveTest
{
	private static final int N = 33, TILES = WeatherField.CELL_TILES;
	private static final int BORDER_SNOW = 3200, BORDER_WET = 3350;

	private static String biome(int wx)
	{
		return wx < BORDER_SNOW ? "SNOWY" : wx < BORDER_WET ? "DRY" : "RAINY";
	}

	/** One channel of the ground (1 snow cover, 0 wet) from the texture's bytes, bilinear, through the rect. */
	private static float read(WeatherField f, int channel, float localX, float localZ)
	{
		byte[] b = (byte[]) PlainData.get(f, "groundBytes");
		float u = (localX - f.getOriginX()) / WeatherField.CELL - 0.5f, v = (localZ - f.getOriginZ()) / WeatherField.CELL - 0.5f;
		u = Math.max(0, Math.min(N - 1, u));
		v = Math.max(0, Math.min(N - 1, v));
		int x0 = Math.min((int) u, N - 2), z0 = Math.min((int) v, N - 2);
		float fx = u - x0, fz = v - z0;
		float a = (b[(z0 * N + x0) * 2 + channel] & 255) * (1 - fx) + (b[(z0 * N + x0 + 1) * 2 + channel] & 255) * fx;
		float c = (b[((z0 + 1) * N + x0) * 2 + channel] & 255) * (1 - fx) + (b[((z0 + 1) * N + x0 + 1) * 2 + channel] & 255) * fx;
		return (a * (1 - fz) + c * fz) / 255;
	}

	private static void lay(WeatherField f, int worldX, int worldY, int baseX, int baseY)
	{
		int cx0 = Math.floorDiv(worldX, TILES) - N / 2, cz0 = Math.floorDiv(worldY, TILES) - N / 2;
		long[] keys = new long[N * N];
		String[] biomes = new String[N * N];
		for (int z = 0; z < N; ++z)
		{
			for (int x = 0; x < N; ++x)
			{
				int wx = (cx0 + x) * TILES + TILES / 2, wy = (cz0 + z) * TILES + TILES / 2;
				keys[z * N + x] = WeatherField.chunkKey(wx, wy);
				biomes[z * N + x] = biome(wx);
			}
		}
		f.layout(N, N, (cx0 * TILES - baseX) * 128f, (cz0 * TILES - baseY) * 128f, keys, biomes);
	}

	@Test
	public void movesChangeNothing()
	{
		WeatherTestData.load();
		WeatherType snow = WeatherTestData.type("SNOW"), rain = WeatherTestData.type("RAIN"), clear = WeatherTestData.type("CLEAR");
		java.util.function.Function<String, WeatherType> types = b -> "SNOWY".equals(b) ? snow : "RAINY".equals(b) ? rain : clear;
		java.util.function.Function<String, float[]> ground = b -> new float[]{"RAINY".equals(b) ? 1 : 0, "SNOWY".equals(b) ? 1 : 0};
		WeatherField f = new WeatherField();
		int y = 3400, baseX = 3100, baseY = 3350;
		float px = 3180;
		lay(f, (int) px, y, baseX, baseY);
		f.update(types, ground, 0.6f, true, 12, 0);
		// fixed world points around the walk: each side of the snow's and the water's edges
		int[] watch = {3150, 3185, 3200, 3215, 3250, 3300, 3335, 3350, 3365, 3400};
		float[][] last = new float[watch.length][2];
		for (int k = 0; k < watch.length; ++k)
		{
			for (int c = 0; c < 2; ++c)
			{
				last[k][c] = read(f, c, (watch[k] - baseX) * 128f, (y - baseY) * 128f);
			}
		}
		float most = 0, stale = 0;
		int moves = 0;
		for (int frame = 0; frame < 6000; ++frame)
		{
			// a run of 2 tiles per 0.6 s there and back, at 60 FPS
			px += frame < 3000 ? 2f / 36 : -2f / 36;
			boolean moved = Math.abs(px - baseX - 52) > 12;
			if (moved)
			{
				// what the shaders would read if the rect moved a frame before the texture: the new base on the old layout
				byte[] old = ((byte[]) PlainData.get(f, "groundBytes")).clone();
				baseX = ((int) px - 52) & ~7;
				lay(f, (int) px, y, baseX, baseY);
				moves++;
				f.update(types, ground, 0.6f, true, 12, 0.1f);
				byte[] now = (byte[]) PlainData.get(f, "groundBytes");
				int row = (int) ((y - baseY) * 128f - f.getOriginZ()) / WeatherField.CELL;
				for (int x = 0; x < N; ++x)
				{
					stale = Math.max(stale, Math.abs((old[(row * N + x) * 2 + 1] & 255) - (now[(row * N + x) * 2 + 1] & 255)) / 255f);
				}
			}
			if (moved || frame % 6 == 0)
			{
				f.update(types, ground, 0.6f, true, 12, 0.1f);
			}
			for (int k = 0; k < watch.length; ++k)
			{
				// only what is near the player: a point at the window's edge is clamped by design
				if (Math.abs(watch[k] - px) > 104)
				{
					continue;
				}
				for (int c = 0; c < 2; ++c)
				{
					float v = read(f, c, (watch[k] - baseX) * 128f, (y - baseY) * 128f);
					if (last[k][c] >= 0)
					{
						most = Math.max(most, Math.abs(v - last[k][c]));
					}
					last[k][c] = v;
				}
			}
		}
		System.out.printf("FIELD MOVES: %d moves over 6000 frames, the most a fixed point's ground stepped in a frame %.4f; with the rect a frame off the texture it would be up to %.2f%n", moves, most, stale);
		assertTrue("moves", moves > 10);
		assertEquals("ground step", 0, most, 0.02f);
	}
}
