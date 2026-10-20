package com.midonarhel.runelight;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LightOcclusionTest
{
	private static final int SIZE = 24, OFFSET = 4, LOCAL = SIZE - 2 * OFFSET;
	private static final float TILE = 128;

	// local world position of the center of extended tile t
	private static float world(int t)
	{
		return (t - OFFSET + 0.5f) * TILE;
	}

	private static int[][][] noFlags()
	{
		return new int[4][LOCAL][LOCAL];
	}

	// a line-of-sight wall on the west edge of extended tile (x, y), flagged on both sides as the game does
	private static void westWall(int[][][] f, int x, int y)
	{
		f[0][x - OFFSET][y - OFFSET] |= WallMap.LOS_WEST | RoomMap.BLOCK_WEST;
		f[0][x - 1 - OFFSET][y - OFFSET] |= WallMap.LOS_EAST | RoomMap.BLOCK_EAST;
	}

	private static void southWall(int[][][] f, int x, int y)
	{
		f[0][x - OFFSET][y - OFFSET] |= WallMap.LOS_SOUTH | RoomMap.BLOCK_SOUTH;
		f[0][x - OFFSET][y - 1 - OFFSET] |= WallMap.LOS_NORTH | RoomMap.BLOCK_NORTH;
	}

	@Test
	public void edgesFromLineOfSightFlags()
	{
		int[][][] f = noFlags();
		westWall(f, 10, 10);
		// a fence: blocks walking, not sight
		f[0][12 - OFFSET][10 - OFFSET] |= RoomMap.BLOCK_WEST;
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		assertTrue(w.edge(0, 10, 10, WallMap.WEST));
		assertTrue(w.edge(0, 9, 10, WallMap.EAST));
		assertFalse(w.edge(0, 12, 10, WallMap.WEST));
		assertFalse(w.edge(0, 10, 10, WallMap.NORTH));
	}

	@Test
	public void straightWallBlocks()
	{
		int[][][] f = noFlags();
		for (int y = 6; y < 14; ++y)
		{
			westWall(f, 10, y);
		}
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		assertTrue(w.blocked(0, world(8), world(10), world(12), world(9), 0));
		assertFalse(w.blocked(0, world(11), world(10), world(12), world(13), 0));
		// around the end of the wall
		assertFalse(w.blocked(0, world(8), world(15), world(12), world(15), 0));
		// other planes have no walls
		assertFalse(w.blocked(1, world(8), world(10), world(12), world(9), 0));
	}

	@Test
	public void lightOnTheWallLightsBothSides()
	{
		int[][][] f = noFlags();
		westWall(f, 10, 10);
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		// right on the tile edge, 2 units west of it
		float edgeX = (10 - OFFSET) * TILE;
		assertTrue(w.blocked(0, edgeX - 2, world(10), world(11), world(10), 0));
		assertFalse(w.blocked(0, edgeX - 2, world(10), world(11), world(10), LightOcclusion.WALL_IGNORE));
	}

	@Test
	public void cornerNeedsBothWays()
	{
		// an L of walls meeting at the south-west corner of tile (10, 10): west edge of (10, 10), south edge of (10, 10)
		int[][][] f = noFlags();
		westWall(f, 10, 10);
		southWall(f, 10, 10);
		WallMap w = WallMap.build(f, null, SIZE, OFFSET, 1);
		// exactly through the corner, from (9, 9) to (10, 10): both ways round it cross a wall
		assertTrue(w.blocked(0, world(9), world(9), world(10), world(10), 0));
		// a single wall end at a corner lets a ray graze past it
		int[][][] g = noFlags();
		westWall(g, 10, 10);
		WallMap w2 = WallMap.build(g, null, SIZE, OFFSET, 1);
		assertFalse(w2.blocked(0, world(9), world(9), world(10), world(8), 0));
	}

	@Test
	public void diagonalWalls()
	{
		List<WallMap.Diagonal> d = new ArrayList<>();
		d.add(new WallMap.Diagonal(0, 10, 10, WallMap.DIAG_SW_NE));
		int[][][] f = noFlags();
		f[0][10 - OFFSET][10 - OFFSET] |= WallMap.LOS_FULL;
		WallMap w = WallMap.build(f, d, SIZE, OFFSET, 1);
		// "/" splits the tile into north-west and south-east halves: from the west tile to the south tile crosses it
		float x0 = (10 - OFFSET) * TILE, y0 = (10 - OFFSET) * TILE;
		assertTrue(w.blocked(0, x0 + 32, y0 + 96, x0 + 96, y0 + 32, 0));
		// along it, on one side
		assertFalse(w.blocked(0, x0 + 16, y0 + 48, x0 + 80, y0 + 112, 0));
		// across it from far away
		assertTrue(w.blocked(0, world(7), world(13), world(13), world(7), 0));
		// "\" the other way
		List<WallMap.Diagonal> d2 = Collections.singletonList(new WallMap.Diagonal(0, 10, 10, WallMap.DIAG_NW_SE));
		WallMap w2 = WallMap.build(f, d2, SIZE, OFFSET, 1);
		assertFalse(w2.blocked(0, x0 + 32, y0 + 96, x0 + 96, y0 + 32, 0));
		assertTrue(w2.blocked(0, x0 + 32, y0 + 32, x0 + 96, y0 + 96, 0));
		// a diagonal fence (sight not blocked) is no wall
		WallMap w3 = WallMap.build(noFlags(), d, SIZE, OFFSET, 1);
		assertFalse(w3.blocked(0, x0 + 32, y0 + 96, x0 + 96, y0 + 32, 0));
	}

	@Test
	public void diagonalFromModel()
	{
		// a strip along x = z
		float[] x = {-64, -60, 60, 64}, z = {-64, -56, 56, 64};
		assertEquals(WallMap.DIAG_SW_NE, WallMap.diagonalOf(x, z, 4, 0, 1));
		assertEquals(WallMap.DIAG_NW_SE, WallMap.diagonalOf(x, new float[]{64, 56, -56, -64}, 4, 0, 0));
		// a straight west wall turned 45 degrees by its model orientation
		float[] wx = {-64, -64, -64, -64}, wz = {-64, -20, 20, 64};
		int a = WallMap.diagonalOf(wx, wz, 4, 256, 0);
		int b = WallMap.diagonalOf(wx, wz, 4, 2048 - 256, 0);
		assertTrue(a != b);
		// nothing to go by: the rotation's parity
		assertEquals(WallMap.DIAG_NW_SE, WallMap.diagonalOf(null, null, 0, 0, 3));
	}

	/** A 5x5 room (tiles 8..12) with a doorway in its east wall at y = 10, unless closed. */
	private static WallMap room(boolean closed)
	{
		int[][][] f = noFlags();
		for (int i = 8; i <= 12; ++i)
		{
			westWall(f, 8, i);
			if (closed || i != 10)
			{
				westWall(f, 13, i);
			}
			southWall(f, i, 8);
			southWall(f, i, 13);
		}
		return WallMap.build(f, null, SIZE, OFFSET, 1);
	}

	@Test
	public void castStopsAtWalls()
	{
		WallMap w = room(false);
		LightOcclusion.Entry e = new LightOcclusion.Entry(0, 0, 0, 0, world(10), world(10), 900);
		LightOcclusion.castEntry(e, w);
		assertFalse(e.open);
		assertNotNull(e.cells);
		LightOcclusion.Atlas a = LightOcclusion.pack(Collections.singletonList(e), 0);
		// inside: lit; outside the west wall: dark; out through the east doorway: lit; beside it, behind the wall: dark
		assertEquals(1, visAt(a, e, world(10), world(10)), 1e-6);
		assertEquals(1, visAt(a, e, world(8), world(12)), 1e-6);
		assertEquals(0, visAt(a, e, world(6), world(10)), 1e-6);
		assertEquals(1, visAt(a, e, world(14), world(10)), 1e-6);
		assertEquals(0, visAt(a, e, world(14), world(12) + 40), 1e-6);
		// the doorway closed: dark outside it too
		WallMap closed = room(true);
		LightOcclusion.castEntry(e, closed);
		a = LightOcclusion.pack(Collections.singletonList(e), 0);
		assertEquals(0, visAt(a, e, world(14), world(10)), 1e-6);
	}

	@Test
	public void castBehindDiagonalWall()
	{
		// a diagonal wall running across tiles (8, 8) .. (12, 12), light north-west of it
		List<WallMap.Diagonal> d = new ArrayList<>();
		int[][][] f = noFlags();
		for (int i = 6; i <= 14; ++i)
		{
			d.add(new WallMap.Diagonal(0, i, i, WallMap.DIAG_SW_NE));
			f[0][i - OFFSET][i - OFFSET] |= WallMap.LOS_FULL;
		}
		WallMap w = WallMap.build(f, d, SIZE, OFFSET, 1);
		LightOcclusion.Entry e = new LightOcclusion.Entry(0, 0, 0, 0, world(8), world(12), 700);
		LightOcclusion.castEntry(e, w);
		LightOcclusion.Atlas a = LightOcclusion.pack(Collections.singletonList(e), 0);
		assertEquals(1, visAt(a, e, world(9), world(11)), 1e-6);
		assertEquals(0, visAt(a, e, world(11), world(9)), 1e-6);
		assertEquals(0, visAt(a, e, world(12), world(10)), 1e-6);
		// a cell on the wall itself is half lit
		float onWall = visAt(a, e, world(10) - 48, world(10) - 48);
		assertTrue(onWall > 0.2f && onWall < 0.8f);
	}

	@Test
	public void openWithoutWalls()
	{
		WallMap w = WallMap.build(noFlags(), null, SIZE, OFFSET, 1);
		LightOcclusion.Entry e = new LightOcclusion.Entry(0, 0, 0, 0, world(10), world(10), 600);
		LightOcclusion.castEntry(e, w);
		assertTrue(e.open);
		assertNull(e.cells);
		FloatBuffer out = FloatBuffer.allocate(4);
		LightOcclusion.texel(e, LightOcclusion.pack(Collections.singletonList(e), 0), 100, true, 0, 600, false, out);
		assertEquals(-1, out.get(0), 0);
		// covered, and the walls (there are none near) decide on plane 0
		assertEquals(2 + 4, out.get(3), 0);
	}

	@Test
	public void gridGeometryAndTexel()
	{
		LightOcclusion.Entry fine = new LightOcclusion.Entry(0, 0, 0, 0, 1000, 2000, 500);
		assertEquals(512, fine.radius, 0);
		assertEquals(LightOcclusion.CELL_FINE, fine.cell, 0);
		assertTrue(fine.originX <= 1000 - 512 && fine.originX + fine.n * fine.cell >= 1000 + 512);
		LightOcclusion.Entry big = new LightOcclusion.Entry(1, 0, 0, 0, 1000, 2000, 5000);
		assertEquals(LightOcclusion.MAX_RADIUS, big.radius, 0);
		assertEquals(LightOcclusion.CELL_COARSE, big.cell, 0);

		WallMap w = room(false);
		LightOcclusion.Entry e = new LightOcclusion.Entry(0, 0, 0, 0, world(10), world(10), 900);
		LightOcclusion.castEntry(e, w);
		LightOcclusion.Atlas a = LightOcclusion.pack(Collections.singletonList(e), 0);
		assertEquals((e.n * e.n + 15) / 16, a.texelCount);
		FloatBuffer out = FloatBuffer.allocate(4);
		LightOcclusion.texel(e, a, 5000, false, 2, 900, false, out);
		assertEquals(5000, out.get(0), 0);
		assertEquals(e.originX, out.get(1), 0);
		// cells per side, plane 2, handled by the grid, fine cells
		assertEquals(e.n * 32 + 2 * 8 + 4, out.get(3), 0);
		// a light beyond the grids' reach, or whose grid isn't cast yet, is left to the rooms
		FloatBuffer far = FloatBuffer.allocate(4);
		LightOcclusion.texel(e, a, 5000, false, 0, LightOcclusion.MAX_RADIUS + 1, false, far);
		assertEquals(0, (int) far.get(3) & 4);
		LightOcclusion.Entry pending = new LightOcclusion.Entry(1, 0, 1, 0, world(10), world(10), 900);
		FloatBuffer wait = FloatBuffer.allocate(4);
		LightOcclusion.texel(pending, a, 5000, false, 0, 900, false, wait);
		assertEquals(-1, wait.get(0), 0);
		assertEquals(0, (int) wait.get(3) & 4);
		// the soft lights of building rooms stay in their rooms
		FloatBuffer room = FloatBuffer.allocate(4);
		LightOcclusion.texel(e, a, 5000, false, 0, 900, true, room);
		assertEquals(0, (int) room.get(3) & 4);
		// every packed float is a whole number the shader can take apart exactly
		for (int i = 0; i < a.texelCount * 4; ++i)
		{
			assertEquals(Math.rint(a.texels[i]), a.texels[i], 0);
			assertTrue(a.texels[i] < 65536);
		}
	}

	@Test
	public void assignKeepsGridsForFixedLightsOnly()
	{
		LightOcclusion o = new LightOcclusion();
		try
		{
			o.update(room(false));
			List<PointLight> lights = new ArrayList<>();
			lights.add(new PointLight().set(world(10), -200, world(10), 600, 1, 1, 1, 1).onPlane(0).fixed());
			lights.add(new PointLight().set(world(11), -200, world(10), 600, 1, 1, 1, 1).onPlane(0));
			o.assign(lights, true);
			assertNotNull(lights.get(0).occlusion);
			assertNull(lights.get(1).occlusion);
			LightOcclusion.Entry first = lights.get(0).occlusion;
			// same light next frame, a little brighter flicker: same grid
			lights.get(0).set(world(10), -200, world(10), 610, 1, 1, 1, 1).onPlane(0).fixed();
			o.assign(lights, true);
			assertEquals(first, lights.get(0).occlusion);
			// off: none
			o.assign(lights, false);
			assertNull(lights.get(0).occlusion);
			// wait for the worker
			long until = System.currentTimeMillis() + 5000;
			while (o.getAtlas() == null && System.currentTimeMillis() < until)
			{
				Thread.sleep(5);
			}
			assertNotNull(o.getAtlas());
			assertTrue(o.getAtlas().base(first) >= 0);
		}
		catch (InterruptedException ex)
		{
			throw new RuntimeException(ex);
		}
		finally
		{
			o.shutdown();
		}
	}

	// the visibility the shader's bilinear lookup gives at local world (x, z)
	private static float visAt(LightOcclusion.Atlas a, LightOcclusion.Entry e, float x, float z)
	{
		float cx = (x - e.originX) / e.cell - 0.5f, cz = (z - e.originZ) / e.cell - 0.5f;
		int bx = (int) Math.floor(cx), bz = (int) Math.floor(cz);
		float fx = cx - bx, fz = cz - bz;
		float v00 = LightOcclusion.lookup(a, e, bx, bz), v10 = LightOcclusion.lookup(a, e, bx + 1, bz);
		float v01 = LightOcclusion.lookup(a, e, bx, bz + 1), v11 = LightOcclusion.lookup(a, e, bx + 1, bz + 1);
		return (v00 * (1 - fx) + v10 * fx) * (1 - fz) + (v01 * (1 - fx) + v11 * fx) * fz;
	}
}
