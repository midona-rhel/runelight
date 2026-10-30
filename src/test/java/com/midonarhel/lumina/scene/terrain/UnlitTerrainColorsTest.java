package com.midonarhel.lumina.scene.terrain;

import static com.midonarhel.lumina.scene.SceneData.HIDDEN_HSL;
import static com.midonarhel.lumina.scene.terrain.UnlitTerrainColors.NONE;
import static com.midonarhel.lumina.scene.terrain.UnlitTerrainColors.TEXTURE_HSL;
import static com.midonarhel.lumina.scene.terrain.UnlitTerrainColors.packHsl;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import org.junit.Test;

public class UnlitTerrainColorsTest
{
	/**
	 * HealthBarUpdate.method2533 by hand: the saturation halves per lightness threshold, then sat/32<<7 | hue/4<<10 | light/2.
	 */
	@Test
	public void packHslMatchesTheClient()
	{
		assertEquals(0, packHsl(0, 0, 0));
		// no halving: 200/32 = 6 << 7, 100/4 = 25 << 10, 100/2
		assertEquals(6 << 7 | 25 << 10 | 50, packHsl(100, 200, 100));
		// 180 > 179: one halving -> 100/32 = 3
		assertEquals(3 << 7 | 25 << 10 | 90, packHsl(100, 200, 180));
		// 193 > 192: two -> 50/32 = 1
		assertEquals(1 << 7 | 25 << 10 | 96, packHsl(100, 200, 193));
		// 218 > 217: three -> 25/32 = 0
		assertEquals(25 << 10 | 109, packHsl(100, 200, 218));
		// 255 > 243: four -> 15/32 = 0; hue 63 << 10, light 127
		assertEquals(63 << 10 | 127, packHsl(255, 255, 255));
	}

	@Test
	public void floorDefinitionColorsMatchTheClient()
	{
		// grey: no saturation, lightness 128, hue multiplier floored to 1
		UnlitTerrainColors.Underlay grey = new UnlitTerrainColors.Underlay(0x808080);
		assertEquals(0, grey.hue);
		assertEquals(0, grey.saturation);
		assertEquals(128, grey.lightness);
		assertEquals(1, grey.hueMultiplier);

		// pure red: hue 0, full saturation, lightness (0 + 255/256) / 2 -> 127, multiplier 512 * l * s = 255
		UnlitTerrainColors.Underlay red = new UnlitTerrainColors.Underlay(0xFF0000);
		assertEquals(0, red.hue);
		assertEquals(255, red.saturation);
		assertEquals(127, red.lightness);
		assertEquals(255, red.hueMultiplier);

		// overlay red: hue 0, sat 255 -> 7 << 7, light 127 -> 63
		assertEquals(7 << 7 | 63, UnlitTerrainColors.overlayColor(0xFF0000, -1));
		assertEquals(TEXTURE_HSL, UnlitTerrainColors.overlayColor(0xFF0000, 3));
		assertEquals(HIDDEN_HSL, UnlitTerrainColors.overlayColor(0xFF00FF, -1));
	}

	@Test
	public void decodeFollowsTheOpcodes()
	{
		// underlay: opcode 1 rgb, end
		assertEquals(0x123456, UnlitTerrainColors.decodeUnderlayRgb(new byte[]{1, 0x12, 0x34, 0x56, 0}));
		assertEquals(0, UnlitTerrainColors.decodeUnderlayRgb(null));
		// overlay: opcode 7 secondary (skipped), 1 primary, 5 hideUnderlay, end
		assertEquals(UnlitTerrainColors.overlayColor(0x405060, -1),
			UnlitTerrainColors.decodeOverlay(new byte[]{7, 1, 2, 3, 1, 0x40, 0x50, 0x60, 5, 0}));
		// opcode 2 texture makes it textured
		assertEquals(TEXTURE_HSL, UnlitTerrainColors.decodeOverlay(new byte[]{1, 0x40, 0x50, 0x60, 2, 9, 0}));
	}

	@Test
	public void heightLightAndLitCorner()
	{
		int[][] flat = new int[5][5];
		assertEquals(84, UnlitTerrainColors.heightLight(flat, 2, 2));
		// textured: the light itself, clamped
		assertEquals(84, UnlitTerrainColors.lit(TEXTURE_HSL, 84));
		assertEquals(126, UnlitTerrainColors.lit(TEXTURE_HSL, 200));
		// coloured: hue and saturation kept, lightness * I / 128
		int base = 25 << 10 | 3 << 7 | 100;
		assertEquals(25 << 10 | 3 << 7 | 100 * 84 / 128, UnlitTerrainColors.lit(base, 84));
		assertEquals(25 << 10 | 3 << 7 | 2, UnlitTerrainColors.lit(base, 0));
	}

	private static final int N = 20;

	// black and white: one B among up to 100 A raises the average lightness by at least 2, which survives the
	// client's light / 2 packing (a subtler colour would vanish in it)
	private static final UnlitTerrainColors.Underlay A = new UnlitTerrainColors.Underlay(0x000000);
	private static final UnlitTerrainColors.Underlay B = new UnlitTerrainColors.Underlay(0xFFFFFF);
	private static final int OVERLAY_COLORED = UnlitTerrainColors.overlayColor(0x806040, -1);

	private static UnlitTerrainColors.Underlay underlay(int id)
	{
		return id == 0 ? A : B;
	}

	private static int overlay(int id)
	{
		switch (id)
		{
			case 2:
				return TEXTURE_HSL;
			case 3:
				return HIDDEN_HSL;
			default:
				return OVERLAY_COLORED;
		}
	}

	/**
	 * A 20x20 plane of underlay A (id 1) with one tile of B (id 2) at (6, 6); overlays textured at (2, 2), hidden at
	 * (3, 3) and coloured at (4, 4), which has no underlay.
	 */
	private static UnlitTerrainColors grid()
	{
		short[][][] u = new short[1][N][N];
		short[][][] o = new short[1][N][N];
		for (int x = 0; x < N; x++)
		{
			for (int y = 0; y < N; y++)
			{
				u[0][x][y] = 1;
			}
		}
		u[0][6][6] = 2;
		o[0][2][2] = 3;
		o[0][3][3] = 4;
		o[0][4][4] = 5;
		u[0][4][4] = 0;
		return UnlitTerrainColors.compute(u, o, UnlitTerrainColorsTest::underlay, UnlitTerrainColorsTest::overlay);
	}

	/**
	 * The client's window by brute force: columns x-4..x+5 and rows y-4..y+5 inside the plane.
	 */
	private static int expectedUnderlay(int x, int y, short[][] ids)
	{
		int hue = 0, sat = 0, light = 0, mul = 0, n = 0;
		for (int xx = Math.max(0, x - 4); xx <= Math.min(N - 1, x + 5); xx++)
		{
			for (int yy = Math.max(0, y - 4); yy <= Math.min(N - 1, y + 5); yy++)
			{
				if (ids[xx][yy] == 0)
				{
					continue;
				}
				UnlitTerrainColors.Underlay d = underlay(ids[xx][yy] - 1);
				hue += d.hue;
				sat += d.saturation;
				light += d.lightness;
				mul += d.hueMultiplier;
				n++;
			}
		}
		return packHsl(hue * 256 / mul, sat / n, light / n) & 0xFFFF;
	}

	@Test
	public void underlayBlendIsTheClientsWindow()
	{
		UnlitTerrainColors colors = grid();
		short[][] ids = new short[N][N];
		for (short[] column : ids)
		{
			java.util.Arrays.fill(column, (short) 1);
		}
		ids[6][6] = 2;
		ids[4][4] = 0;

		int pureA = packHsl(A.hue * 256 / A.hueMultiplier, A.saturation, A.lightness) & 0xFFFF;
		// far from B and the gap: pure A
		assertEquals(pureA, colors.underlay(0, 15, 15));
		// the window reaches 5 tiles east and north but only 4 west and south
		assertNotEquals(pureA, colors.underlay(0, 1, 6)); // B is 5 east of (1, 6)
		assertEquals(pureA, colors.underlay(0, 11, 6)); // B is 5 west of (11, 6)
		assertNotEquals(pureA, colors.underlay(0, 10, 6)); // 4 west
		assertNotEquals(pureA, colors.underlay(0, 6, 1)); // 5 north
		assertEquals(pureA, colors.underlay(0, 6, 11)); // 5 south
		// every tile matches the brute-force window, including the clipped edges
		for (int x = 1; x < N - 1; x++)
		{
			for (int y = 1; y < N - 1; y++)
			{
				if (x == 4 && y == 4)
				{
					continue;
				}
				assertEquals("tile " + x + "," + y, expectedUnderlay(x, y, ids), colors.underlay(0, x, y));
			}
		}
		// the client builds no tiles on the plane's edge
		assertEquals(NONE, colors.underlay(0, 0, 5));
		assertEquals(NONE, colors.underlay(0, 5, N - 1));
	}

	@Test
	public void overlaysAndPaintColor()
	{
		UnlitTerrainColors colors = grid();
		assertEquals(TEXTURE_HSL, colors.overlay(0, 2, 2));
		assertEquals(HIDDEN_HSL, colors.overlay(0, 3, 3));
		assertEquals(OVERLAY_COLORED, colors.overlay(0, 4, 4));
		assertEquals(NONE, colors.overlay(0, 5, 5));
		// a tile with an overlay but no underlay: hidden underlay faces, the paint takes the overlay's colour
		assertEquals(HIDDEN_HSL, colors.underlay(0, 4, 4));
		assertEquals(OVERLAY_COLORED, colors.paint(0, 4, 4));
		// a tile without an overlay paints with its underlay
		assertEquals(colors.underlay(0, 5, 5), colors.paint(0, 5, 5));
		assertEquals(colors.underlay(0, 5, 5), colors.layer(0, 5, 5, false));
	}
}
