package com.midonarhel.lumina.harness;

import com.midonarhel.lumina.light.PointLight;
import com.midonarhel.lumina.visibility.WallMap;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/** The checks on images made in code: each finds what it is for and passes what is fine. */
public class ChecksTest
{
	static final int W = 64, H = 64;

	static BufferedImage fill(int rgb)
	{
		BufferedImage img = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < H; ++y)
		{
			for (int x = 0; x < W; ++x)
			{
				img.setRGB(x, y, rgb);
			}
		}
		return img;
	}

	// a flat ground seen from straight above: pixel (x, y) is world (x * 16, 0, y * 16), half a tile per 8 pixels
	static final Checks.Positions GROUND = (x, y) -> new float[]{x * 16f, 0, y * 16f};
	// the NORMALS view's encoding of straight up (y down): (0, -1, 0) * 0.5 + 0.5
	static final int UP = 0x800080;

	@Test
	public void diffCountsWhatDiffers()
	{
		BufferedImage a = fill(0x102030), b = fill(0x102030);
		assertEquals(0, Checks.diff(a, b).max);
		for (int y = 0; y < 8; ++y)
		{
			for (int x = 0; x < 8; ++x)
			{
				b.setRGB(x, y, 0x10203A);
			}
		}
		Checks.Diff d = Checks.diff(a, b);
		assertEquals(10, d.max);
		assertEquals(64.0 / (W * H), d.over8, 1e-9);
		assertTrue(d.within(1, 0.02));
		assertTrue(!d.within(1, 0.01));
	}

	@Test
	public void flatnessFindsBumps()
	{
		BufferedImage n = fill(UP);
		Checks.Flatness flat = Checks.flatness(n, new Rectangle(0, 0, W, H));
		assertTrue(flat.toString(), flat.maxDegrees < 1);
		assertEquals(-1, flat.meanNormal[1], 1e-3);
		// a tilted patch, 30° toward x
		float s = (float) Math.sin(Math.toRadians(30)), c = (float) Math.cos(Math.toRadians(30));
		int tilted = Math.round((s * 0.5f + 0.5f) * 255) << 16 | Math.round((-c * 0.5f + 0.5f) * 255) << 8 | 0x80;
		for (int y = 20; y < 30; ++y)
		{
			for (int x = 20; x < 30; ++x)
			{
				n.setRGB(x, y, tilted);
			}
		}
		Checks.Flatness bumpy = Checks.flatness(n, new Rectangle(0, 0, W, H));
		assertTrue(bumpy.toString(), bumpy.maxDegrees > 25);
	}

	@Test
	public void skylightStepsAreFoundOnTileEdges()
	{
		BufferedImage normals = fill(UP);
		BufferedImage smooth = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		BufferedImage tiled = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
		for (int y = 0; y < H; ++y)
		{
			for (int x = 0; x < W; ++x)
			{
				int v = 100 + x;
				smooth.setRGB(x, y, v << 16 | v << 8 | v);
				// one value per tile (8 pixels), stepping 40 at each tile's edge
				int t = 60 + (x / 8) * 20;
				tiled.setRGB(x, y, t << 16 | t << 8 | t);
			}
		}
		Checks.SkylightSteps none = Checks.skylightSteps(smooth, normals, GROUND, 0.05f);
		assertEquals(none.toString(), 0, none.steps);
		assertTrue(none.pairs > 7000);
		Checks.SkylightSteps steps = Checks.skylightSteps(tiled, normals, GROUND, 0.05f);
		assertEquals(steps.toString(), 7 * H, steps.steps);
		assertEquals(steps.toString(), steps.steps, steps.onTileEdges);
		// the unworked out red is not open ground
		Checks.SkylightSteps red = Checks.skylightSteps(fill(0x800000), normals, GROUND, 0.05f);
		assertEquals(0, red.pairs);
	}

	@Test
	public void lampLightBehindAWallIsFound()
	{
		// a wall along x = 4 tiles (between local tiles 3 and 4), z 0 to 8, in a scene of 16 tiles (offset 0); the ground
		// covers 8 x 8 tiles
		int[][][] flags = new int[4][16][16];
		for (int z = 0; z < 8; ++z)
		{
			flags[0][4][z] |= WallMap.LOS_WEST;
			flags[0][3][z] |= WallMap.LOS_EAST;
		}
		WallMap walls = WallMap.build(flags, null, 16, 0, 1);
		PointLight lamp = new PointLight().set(2 * 128, -100, 4 * 128, 6 * 128, 1, 0.8f, 0.6f, 4).onPlane(0);
		BufferedImage off = fill(0x101010);
		BufferedImage both = fill(0x101010);
		BufferedImage near = fill(0x101010);
		for (int y = 0; y < H; ++y)
		{
			for (int x = 0; x < W; ++x)
			{
				both.setRGB(x, y, 0x606060);
				// the lamp's side of the wall only: x < 4 tiles = 32 pixels
				near.setRGB(x, y, x < 32 ? 0x606060 : 0x101010);
			}
		}
		Checks.LampLeak leak = Checks.lampLeak(both, off, GROUND, List.of(lamp), walls, 0, 4);
		assertTrue(leak.toString(), leak.behind > 500);
		assertEquals(leak.toString(), leak.behind, leak.lit);
		Checks.LampLeak clean = Checks.lampLeak(near, off, GROUND, List.of(lamp), walls, 0, 4);
		assertEquals(clean.toString(), 0, clean.lit);
		assertEquals(leak.behind, clean.behind);
	}
}
