package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * A closed, roofed room built from triangles as the scene's zones give them (Voxelizer, VoxelGrid, SkyGrid): nothing
 * leaks under the roof, and the roof's height is known to well under a voxel, which is what tells a roof top from a
 * ladder that reaches up to it (skylight_ao.glsl).
 */
public class RoofTest
{
	private static void face(VoxelCapture cap, int[] a, int[] b, int[] c)
	{
		cap.add(a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2], false);
	}

	private static int[] p(int x, int y, int z)
	{
		return new int[]{x, y, z};
	}

	private static void quad(VoxelCapture cap, int[] a, int[] b, int[] c, int[] d)
	{
		face(cap, a, b, c);
		face(cap, a, c, d);
	}

	private static VoxelGrid lastGrid;

	static SkyGrid room(boolean pitched, int wall, int roofY, int ridgeY)
	{
		VoxelCapture cap = new VoxelCapture();
		quad(cap, p(0, 0, 0), p(1000, 0, 0), p(1000, 0, 1000), p(0, 0, 1000));
		int lo = 256, hi = 768;
		// four walls, single sheets on the tile edges (a tile is 128), as small quads of one tile each
		for (int t = lo; t < hi; t += 128)
		{
			quad(cap, p(lo, 0, t), p(lo, 0, t + 128), p(lo, -wall, t + 128), p(lo, -wall, t));
			quad(cap, p(hi, 0, t), p(hi, 0, t + 128), p(hi, -wall, t + 128), p(hi, -wall, t));
			quad(cap, p(t, 0, lo), p(t + 128, 0, lo), p(t + 128, -wall, lo), p(t, -wall, lo));
			quad(cap, p(t, 0, hi), p(t + 128, 0, hi), p(t + 128, -wall, hi), p(t, -wall, hi));
		}
		// the roof over it, with an overhang of a tile, in tile sized quads
		for (int x = lo - 128; x < hi + 128; x += 128)
		{
			for (int z = lo - 128; z < hi + 128; z += 128)
			{
				int y0 = roofY, y1 = roofY;
				if (pitched)
				{
					// ridge along x at z = 512
					y0 = roofY + (ridgeY - roofY) * Math.max(0, 256 - Math.abs(z + 0 - 512)) / 256;
					y1 = roofY + (ridgeY - roofY) * Math.max(0, 256 - Math.abs(z + 128 - 512)) / 256;
					if (z >= 512) { y0 = roofY + (ridgeY - roofY) * Math.max(0, 384 - Math.abs(z - 512)) / 384; y1 = roofY + (ridgeY - roofY) * Math.max(0, 384 - Math.abs(z + 128 - 512)) / 384; }
				}
				quad(cap, p(x, y0, z), p(x + 128, y0, z), p(x + 128, y1, z + 128), p(x, y1, z + 128));
			}
		}
		Voxelizer.Zone zone = Voxelizer.voxelize(cap);
		VoxelGrid g = VoxelGrid.assemble(new Voxelizer.Zone[]{zone}, new int[]{5}, new int[]{5}, 5, null);
		int[][][] f = new int[1][SkyGrid.SIZE][SkyGrid.SIZE];
		for (int k = 2; k <= 5; ++k)
		{
			f[0][2][k] |= WallMap.LOS_WEST;
			f[0][5][k] |= WallMap.LOS_EAST;
			f[0][k][5] |= WallMap.LOS_NORTH;
			f[0][k][2] |= WallMap.LOS_SOUTH;
		}
		lastGrid = g;
		SkyGrid s = new SkyGrid();
		s.setWalls(WallMap.build(f, null, SkyGrid.SIZE, 0, 1));
		s.setGrid(g, new int[1][SkyGrid.CORNERS][SkyGrid.CORNERS], 0, 0, 0, false, null);
		while (s.dirtyCount() > 0)
		{
			s.trace(1 << 20, 4, 4);
		}
		return s;
	}

	static float val(SkyGrid s, int tx, int tz, int i, int j, int level, int lobe)
	{
		int x = (lobe == 4 ? 2 * SkyGrid.SIZE : 0) + 2 * tx + i, y = 2 * tz + j;
		int t = (((1 + level) * SkyGrid.TEX_H + y) * SkyGrid.TEX_W + x) * 4 + (lobe == 4 ? 0 : lobe);
		return Half.fromHalf(s.texels.get(t));
	}

	@Test
	public void aRoofedRoomLetsNoSkyIn()
	{
		// walls 240 high, a flat roof sheet on them; and a pitched one on their tops, which leaves the gables open
		// sideways, so only the sky overhead is checked for it
		for (boolean pitched : new boolean[]{false, true})
		{
			SkyGrid s = room(pitched, 240, -240, -400);
			for (int level = 0; level < 3; ++level)
			{
				for (int lobe = 0; lobe < (pitched ? 1 : 5); ++lobe)
				{
					float most = 0;
					for (int tx = 2; tx < 6; ++tx) for (int tz = 2; tz < 6; ++tz) for (int i = 0; i < 2; ++i) for (int j = 0; j < 2; ++j)
						most = Math.max(most, val(s, tx, tz, i, j, level, lobe));
					assertTrue("pitched " + pitched + " level " + level + " lobe " + lobe + ": " + most, most < 0.1f);
				}
			}
		}
	}

	@Test
	public void theRoofsHeightIsExact()
	{
		// a flat sheet at 250, over wall tops at 240: neither is a voxel's edge
		SkyGrid flat = room(false, 240, -250, 0);
		VoxelGrid g = lastGrid;
		// over the room's middle, and on the wall's column in its corner: the sheet; away from the building, the ground
		assertEquals(-250, g.top[8 * g.nx + 8], 1e-3f);
		assertEquals(-250, g.top[4 * g.nx + 8], 1e-3f);
		assertEquals(0, g.top[1 * g.nx + 1], 1e-3f);
		// at a corner in the middle of the roof, the mean of its four columns; the texel the shader reads says the same
		assertEquals(-250, flat.topAt(4, 4), 1e-3f);
		int t = flat.texel(2 * 4 + 2 * flat.size, 2 * 4, 0) + 1;
		assertEquals(-250, Half.fromHalf(flat.texels.get(t)), 0.5f);
		// a pitched roof is a plane: its height at a column's centre, the ridge at 400 falling to the eave at 250
		room(true, 240, -250, -400);
		VoxelGrid p = lastGrid;
		float ridge = p.top[8 * p.nx + 8];
		assertTrue("ridge " + ridge, ridge < -380 && ridge >= -400);
		assertTrue(p.top[5 * p.nx + 8] > ridge + 50);
	}

	@Test
	public void theWeatherSoundsHearTheRoom()
	{
		SkyGrid s = room(false, 240, -250, 0);
		// a player's head, 120 above the floor: under the roof it sees no sky and the sounds are fully muffled; in the
		// open it sees all of it and they are not
		float inside = s.upVisibility(4.5f * 128, -120, 4.5f * 128);
		VoxelCapture cap = new VoxelCapture();
		quad(cap, p(0, 0, 0), p(1000, 0, 0), p(1000, 0, 1000), p(0, 0, 1000));
		SkyGrid open = new SkyGrid();
		open.setGrid(VoxelGrid.assemble(new Voxelizer.Zone[]{Voxelizer.voxelize(cap)}, new int[]{5}, new int[]{5}, 5, null),
			new int[1][SkyGrid.CORNERS][SkyGrid.CORNERS], 0, 0, 0, false, null);
		while (open.dirtyCount() > 0)
		{
			open.trace(1 << 20, 4, 4);
		}
		float outside = open.upVisibility(4.5f * 128, -120, 4.5f * 128);
		assertTrue("inside " + inside, inside >= 0 && inside < WeatherAudio.FULLY_INDOORS);
		assertTrue("outside " + outside, outside > WeatherAudio.FULLY_OUTDOORS);
		assertEquals(1, WeatherAudio.indoorMix(inside, null, 0, 0, 0), 0);
		assertEquals(0, WeatherAudio.indoorMix(outside, null, 0, 0, 0), 0);
	}
}
