package com.midonarhel.runelight;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.nio.IntBuffer;
import org.junit.Test;

/** The volume of tint: its faces, the colour they give, and what a pixel reads of it (GiEmitters, GiRelight, gi.glsl). */
public class GiTintTest
{
	private static final int BASE_ZONE = 5;
	// zone offsets from BASE_ZONE of the 3 x 3 zones of a scene: 1024 units each, the middle one's corner at 2048
	private static final int FIRST = 1, ZONES = 3;
	// the game's packed hsl of a mid green and of a red
	private static final int GREEN = 21 << 10 | 5 << 7 | 60;
	private static final int RED = 6 << 7 | 50;
	private static final int YELLOW = 11 << 10 | 5 << 7 | 60;
	private static final int GRAY = 63;
	private static final float MIDDLE = (FIRST + 1) * 1024;

	/** A square of ground 1024 across at height y, colour color, its front up (y down). */
	private static void ground(GiCapture cap, int color, int x0, int z0, int x1, int z1, int y)
	{
		GlobalIlluminationTest.quad(cap, color, new int[]{x0, y, z0}, new int[]{x1, y, z0}, new int[]{x1, y, z1},
			new int[]{x0, y, z1}, 0, -1, 0);
	}

	/** The scene's 3 x 3 zones: a zone's capture for each (x, z), null for an empty one. */
	private interface Zones
	{
		GiCapture at(int x, int z);
	}

	private static GiGrid grid(Zones zones, GiWater water)
	{
		GiVoxels.Zone[] voxels = new GiVoxels.Zone[ZONES * ZONES];
		int[] zx = new int[voxels.length], zz = new int[voxels.length];
		for (int i = 0; i < voxels.length; ++i)
		{
			GiCapture cap = zones.at(i / ZONES, i % ZONES);
			voxels[i] = cap == null ? null : GiVoxels.voxelize(cap);
			zx[i] = BASE_ZONE + FIRST + i / ZONES;
			zz[i] = BASE_ZONE + FIRST + i % ZONES;
		}
		return GiGrid.assemble(voxels, zx, zz, BASE_ZONE, water);
	}

	private static GiGrid grid(Zones zones)
	{
		return grid(zones, null);
	}

	/** Green ground at height y everywhere, and in the middle zone a gray wall 64 thick, 400 high, along z at x 448..512. */
	private static GiGrid greenYard(int y)
	{
		return grid((x, z) ->
		{
			GiCapture cap = new GiCapture();
			ground(cap, GREEN, 0, 0, 1024, 1024, y);
			if (x == 1 && z == 1)
			{
				GlobalIlluminationTest.box(cap, GRAY, 448, y - 400, 0, 512, y, 1024);
			}
			return cap;
		});
	}

	private static GiRelight.Light sunlit(boolean bounce)
	{
		float[] t = new float[GiRelight.TABLE_FLOATS];
		int row = GiRelight.SKY_W * GiRelight.SKY_H * 4;
		t[row] = 2.9f;
		t[row + 1] = 2.6f;
		t[row + 2] = 2.2f;
		t[row + 12] = 1;
		float l = (float) Math.sqrt(0.25 + 0.49 + 0.25);
		return GiRelight.Light.fromTable(t, new float[]{0.5f / l, -0.7f / l, 0.5f / l}, bounce);
	}

	private static byte[] relight(GiGrid grid, GiRelight.Light light)
	{
		return GiRelight.relight(GiEmitters.build(grid, GiEmitters.ParallelFor.SEQUENTIAL), light, GiEmitters.ParallelFor.SEQUENTIAL);
	}

	/** What the composite does with a lookup: the tint at luminance 1 and how much of it applies (light_occlusion.glsl giTint). */
	static float[] tint(float[] gi)
	{
		float l = GiRelight.luminance(gi[0], gi[1], gi[2]);
		if (l < 1e-4f)
		{
			return new float[]{1, 1, 1, 0};
		}
		return new float[]{gi[0] / l, gi[1] / l, gi[2] / l, (float) Math.sqrt(Math.min(1, l)) * gi[3]};
	}

	// ---- the faces

	@Test
	public void groundGivesColourUpAndNothingDown()
	{
		GiGrid g = greenYard(32);
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		assertTrue(e.faceCount > 0);
		for (int f = 0; f < e.faceCount; ++f)
		{
			// every face looks into an empty voxel, from a filled one
			assertEquals(0, g.cells[e.faceCell[f]]);
			assertNotEquals(0, g.cells[e.faceSolid[f]]);
			// and faces the way it is given to
			float dot = 0;
			int[] d = {0, 0, 0};
			d[e.faceDirection[f] / 2] = e.faceDirection[f] % 2 == 0 ? 1 : -1;
			for (int c = 0; c < 3; ++c)
			{
				dot += d[c] * e.faceNormal[f * 3 + c];
			}
			assertTrue(dot >= GiEmitters.FACING_COS - 1e-4f);
		}
		// the ground at the middle of the yard is given up, nothing under it
		int x = (int) (MIDDLE + 200) / GiGrid.VOXEL, z = (int) (MIDDLE + 200) / GiGrid.VOXEL;
		int ground = -1;
		for (int y = 0; y < g.ny; ++y)
		{
			if (g.cells[g.index(x, y, z)] != 0)
			{
				ground = g.index(x, y, z);
			}
		}
		assertTrue(ground >= 0);
		int faces = 0;
		for (int f = 0; f < e.faceCount; ++f)
		{
			if (e.faceSolid[f] == ground)
			{
				faces++;
				assertEquals(-1, e.faceNormal[f * 3 + 1], 0.05);
			}
		}
		assertEquals(1, faces);
	}

	@Test
	public void aThinWallGivesColourOnBothSides()
	{
		GiGrid g = grid((x, z) ->
		{
			GiCapture cap = new GiCapture();
			if (x == 1 && z == 1)
			{
				GlobalIlluminationTest.quad(cap, GRAY, new int[]{100, 0, 0}, new int[]{100, -60, 0}, new int[]{100, -60, 60},
					new int[]{100, 0, 60}, -1, 0, 0);
				GlobalIlluminationTest.quad(cap, GRAY, new int[]{108, 0, 0}, new int[]{108, -60, 0}, new int[]{108, -60, 60},
					new int[]{108, 0, 60}, 1, 0, 0);
			}
			return cap;
		});
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		int west = 0, east = 0;
		for (int f = 0; f < e.faceCount; ++f)
		{
			if (e.faceDirection[f] == 0)
			{
				east++;
			}
			else if (e.faceDirection[f] == 1)
			{
				west++;
			}
		}
		assertTrue(west > 0 && east > 0);
	}

	@Test
	public void theProfileGoesFromOneToNothingAtTheReach()
	{
		assertEquals(1, GiEmitters.profile(0), 0);
		assertEquals(0, GiEmitters.profile(GiEmitters.REACH), 0);
		assertEquals(0, GiEmitters.profile(GiEmitters.REACH * 2), 0);
		float last = 1;
		for (float d = 1; d <= GiEmitters.REACH; d += 1)
		{
			float v = GiEmitters.profile(d);
			assertTrue(v <= last);
			last = v;
		}
	}

	@Test
	public void aFaceGivesItsColourToTheFieldsOfItsSlope()
	{
		GiGrid g = greenYard(32);
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		boolean ground = false, wall = false;
		for (int f = 0; f < e.faceCount; ++f)
		{
			assertEquals(1, e.share(f, 0) + e.share(f, 1) + e.share(f, 2), 1e-3);
			// the ground gives to the level field, the wall to the upright one
			if (e.faceNormal[f * 3 + 1] < -0.99f)
			{
				assertEquals(1, e.share(f, 2), 0.02);
				ground = true;
			}
			if (Math.abs(e.faceNormal[f * 3 + 1]) < 0.02f)
			{
				assertEquals(1, e.share(f, 0), 0.02);
				wall = true;
			}
		}
		assertTrue(ground && wall);
		// within the snap of a slope it lies at it exactly, and in between, to the two it lies between, as the angle goes
		float[] out = new float[3];
		for (float degrees : new float[]{0, 5, 15})
		{
			GiEmitters.bands((float) -Math.sin(Math.toRadians(degrees)), out, 0);
			assertArrayEquals(new float[]{1, 0, 0}, out, 1e-5f);
		}
		for (float degrees : new float[]{30, 45, 60})
		{
			GiEmitters.bands((float) -Math.sin(Math.toRadians(degrees)), out, 0);
			assertArrayEquals(new float[]{0, 1, 0}, out, 1e-5f);
		}
		for (float degrees : new float[]{75, 90})
		{
			GiEmitters.bands((float) Math.sin(Math.toRadians(degrees)), out, 0);
			assertArrayEquals(new float[]{0, 0, 1}, out, 1e-5f);
		}
		GiEmitters.bands((float) -Math.sin(Math.toRadians(22.5)), out, 0);
		assertArrayEquals(new float[]{0.5f, 0.5f, 0}, out, 1e-5f);
		GiEmitters.bands((float) Math.sin(Math.toRadians(67.5)), out, 0);
		assertArrayEquals(new float[]{0, 0.5f, 0.5f}, out, 1e-5f);
	}

	@Test
	public void theVoxelsKnowWhereTheirSurfaceIs()
	{
		// ground 20 into a voxel [0, 64): its center, 32 down, is 12 behind the surface that faces up
		GiGrid g = grid((x, z) ->
		{
			GiCapture cap = new GiCapture();
			ground(cap, GRAY, 0, 0, 1024, 1024, 20);
			return cap;
		});
		int idx = g.index(40, -g.layer0, 40);
		assertNotEquals(0, g.cells[idx]);
		assertEquals(-12, g.planeDistance(idx), 1);
	}

	// ---- the volume

	@Test
	public void groundGreensTheFootOfAWallAndFadesUp()
	{
		GiGrid g = greenYard(32);
		byte[] volume = relight(g, sunlit(true));
		// the wall's east face, at x = MIDDLE + 512, looks at the sunlit grass (the sun is to the east, so the grass on the
		// west side is in the wall's shadow); pixels up it
		float[][] at = new float[7][];
		int k = 0;
		for (float up : new float[]{5, 64, 128, 192, 256, 96, 160})
		{
			float[] gi = GiLookup.irradiance(g, volume, new float[]{MIDDLE + 513, 32 - up, MIDDLE + 500}, new float[]{1, 0, 0});
			at[k] = tint(gi);
			System.err.println("wall at " + up + " up: gi " + java.util.Arrays.toString(gi) + " tint " + java.util.Arrays.toString(at[k]));
			k++;
		}
		// green at the foot, and a good deal of it
		assertTrue("foot " + java.util.Arrays.toString(at[0]), at[0][1] > at[0][0] * 1.5f && at[0][1] > at[0][2] * 1.5f);
		assertTrue("amount at the foot " + at[0][3], at[0][3] > 0.6f);
		// and less of it higher up, still green, and none by the reach
		assertTrue("64 up " + at[1][3] + " foot " + at[0][3], at[1][3] < at[0][3] && at[1][3] > 0.2f);
		assertTrue("128 up " + at[2][3] + " 64 up " + at[1][3], at[2][3] < at[1][3] * 0.7f && at[2][3] > 0);
		assertTrue(at[1][1] > at[1][0] && at[1][1] > at[1][2]);
		assertTrue("192 up " + at[3][3], at[3][3] < 0.1f);
		assertEquals(0, at[4][3], 0.02);
	}

	@Test
	public void theColourAtAWallsFootDoesNotDependOnWhereTheGridCutsTheGround()
	{
		// the ground at any height in a voxel: the foot and 100 units up read the same, give or take a good tenth
		float[] foot = new float[8], up = new float[8];
		int k = 0;
		for (int y : new int[]{0, 8, 16, 24, 32, 40, 48, 56})
		{
			GiGrid g = greenYard(y);
			byte[] volume = relight(g, sunlit(true));
			foot[k] = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + 513, y - 5, MIDDLE + 500}, new float[]{1, 0, 0}))[3];
			up[k] = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + 513, y - 100, MIDDLE + 500}, new float[]{1, 0, 0}))[3];
			k++;
		}
		System.err.println("foot " + java.util.Arrays.toString(foot) + " 100 up " + java.util.Arrays.toString(up));
		for (int i = 0; i < foot.length; ++i)
		{
			assertEquals(foot[0], foot[i], 0.12);
			assertEquals(up[0], up[i], 0.12);
		}
	}

	@Test
	public void aFloorTakesTheColourOfTheWallBesideItAndLosesItWithDistance()
	{
		// a red wall on gray ground
		GiGrid g = grid((x, z) ->
		{
			GiCapture cap = new GiCapture();
			ground(cap, GRAY, 0, 0, 1024, 1024, 32);
			if (x == 1 && z == 1)
			{
				GlobalIlluminationTest.box(cap, RED, 448, -368, 0, 512, 32, 1024);
			}
			return cap;
		});
		byte[] volume = relight(g, sunlit(true));
		// the sun is to the east, so the west side of the wall is in its shadow: the sky alone lights it. Look east, where
		// the sun is on the wall, at the ground beside it
		float[][] at = new float[5][];
		int k = 0;
		for (float away : new float[]{10, 64, 128, 192, 256})
		{
			float[] gi = GiLookup.irradiance(g, volume, new float[]{MIDDLE + 512 + away, 32, MIDDLE + 500}, new float[]{0, -1, 0});
			at[k] = tint(gi);
			System.err.println("floor " + away + " from the wall: gi " + java.util.Arrays.toString(gi) + " tint " + java.util.Arrays.toString(at[k]));
			k++;
		}
		assertTrue("beside " + java.util.Arrays.toString(at[0]), at[0][0] > at[0][1] * 1.3f && at[0][0] > at[0][2] * 1.3f);
		assertTrue(at[0][3] > 0.2f);
		assertTrue(at[1][3] < at[0][3] && at[2][3] < at[1][3]);
		assertEquals(0, at[4][3], 0.02);
		// the ground west of the wall, in its shadow, gets less of it
		float shaded = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + 448 - 10, 32, MIDDLE + 500}, new float[]{0, -1, 0}))[3];
		assertTrue("shaded " + shaded + " lit " + at[0][3], shaded < at[0][3]);
	}

	/**
	 * Gray ground at height y with a flight of eight steps on it, rising toward +x from x = 192 (zone-local): treads 32
	 * deep and risers 32 high, yellow treads and red risers, 128 wide in z from 384. The sun is east, so the treads are
	 * lit and the risers, which face west, are not.
	 */
	private static GiGrid stairs(int y)
	{
		return grid((x, z) ->
		{
			GiCapture cap = new GiCapture();
			ground(cap, GRAY, 0, 0, 1024, 1024, y);
			if (x == 1 && z == 1)
			{
				for (int i = 0; i < 8; ++i)
				{
					int top = y - 32 * (i + 1), x0 = 192 + 32 * i;
					GlobalIlluminationTest.quad(cap, YELLOW, new int[]{x0, top, 384}, new int[]{x0 + 32, top, 384},
						new int[]{x0 + 32, top, 512}, new int[]{x0, top, 512}, 0, -1, 0);
					GlobalIlluminationTest.quad(cap, RED, new int[]{x0, top, 384}, new int[]{x0, top + 32, 384},
						new int[]{x0, top + 32, 512}, new int[]{x0, top, 512}, -1, 0, 0);
				}
			}
			return cap;
		});
	}

	@Test
	public void aFlightOfStairsIsTintedTheSameOnTreadsAndRisers()
	{
		for (int y : new int[]{0, 16, 32, 48})
		{
			GiGrid g = stairs(y);
			byte[] volume = relight(g, sunlit(true));
			float[][] tread = new float[8][], riser = new float[8][];
			StringBuilder out = new StringBuilder("stairs on ground " + y + ", tint amount tread / riser:");
			for (int i = 0; i < 8; ++i)
			{
				int top = y - 32 * (i + 1), x0 = 192 + 32 * i;
				tread[i] = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + x0 + 16, top, MIDDLE + 448}, new float[]{0, -1, 0}));
				riser[i] = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + x0, top + 16, MIDDLE + 448}, new float[]{-1, 0, 0}));
				out.append(String.format(" %.2f/%.2f", tread[i][3], riser[i][3]));
			}
			System.err.println(out);
			// up the flight (the lowest risers see the gray ground, and the top steps have fewer above them) a tread and the
			// riser beside it are tinted alike, in strength and in hue, and one step is tinted as the next: no bands
			for (int i = 3; i < 6; ++i)
			{
				String at = "step " + i + " on ground " + y + " tread " + java.util.Arrays.toString(tread[i]) + " riser " + java.util.Arrays.toString(riser[i]);
				assertEquals(at, tread[i][3], riser[i][3], 0.03);
				assertEquals(at, tread[i][0] / tread[i][1], riser[i][0] / riser[i][1], 0.05);
				if (i < 5)
				{
					assertEquals(at, tread[i][3], tread[i + 1][3], 0.03);
					assertEquals(at, riser[i][3], riser[i + 1][3], 0.03);
				}
			}
		}
	}

	@Test
	public void theTintIsContinuousInThePixelsNormal()
	{
		// a pixel by the foot of the green wall, turned from facing the wall's side through to facing up
		GiGrid g = greenYard(20);
		byte[] volume = relight(g, sunlit(true));
		float[] last = null;
		for (int degrees = 0; degrees <= 90; ++degrees)
		{
			double a = Math.toRadians(degrees);
			float[] gi = GiLookup.irradiance(g, volume, new float[]{MIDDLE + 540, 20 - 6, MIDDLE + 500},
				new float[]{(float) Math.cos(a), (float) -Math.sin(a), 0});
			float[] t = tint(gi);
			if (last != null)
			{
				assertEquals("at " + degrees + " degrees", last[3], t[3], 0.04);
				assertEquals("at " + degrees + " degrees", last[1], t[1], 0.06);
			}
			last = t;
		}
	}

	@Test
	public void theRiseUpAWallIsSmoothWhereverTheGridCutsTheGround()
	{
		for (int y : new int[]{0, 12, 24, 36, 48, 60})
		{
			GiGrid g = greenYard(y);
			byte[] volume = relight(g, sunlit(true));
			float[] amount = new float[61];
			for (int i = 0; i < amount.length; ++i)
			{
				amount[i] = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + 513, y - 4 * i, MIDDLE + 500}, new float[]{1, 0, 0}))[3];
			}
			StringBuilder out = new StringBuilder("rise up a wall, ground " + y + ":");
			for (int h : new int[]{0, 32, 64, 96, 128, 160, 192})
			{
				out.append(String.format(" %d: %.2f", h, amount[h / 4]));
			}
			System.err.println(out);
			// strong at the foot, about half of it by a hundred units up (half a wall), none by the reach, and nowhere a step:
			// it falls without rising and the bend in it, over steps of 4 units, is a few hundredths at most
			assertTrue(out.toString(), amount[0] > 0.8f && amount[0] < 1);
			assertEquals(out.toString(), 0.45f, amount[96 / 4], 0.12);
			assertEquals(out.toString(), 0, amount[192 / 4], 0.05);
			assertEquals(out.toString(), 0, amount[224 / 4], 0.01);
			for (int i = 1; i < amount.length - 1; ++i)
			{
				assertTrue(out.toString(), amount[i] <= amount[i - 1] + 0.01f);
				assertEquals(out.toString() + " at " + i * 4, 0, amount[i + 1] - 2 * amount[i] + amount[i - 1], 0.03);
			}
		}
	}

	@Test
	public void theColourDoesNotGoThroughAWall()
	{
		// green ground west of a wall, red east of it
		GiGrid g = grid((x, z) ->
		{
			GiCapture cap = new GiCapture();
			if (x == 1 && z == 1)
			{
				ground(cap, GREEN, 0, 0, 448, 1024, 32);
				ground(cap, RED, 512, 0, 1024, 1024, 32);
				GlobalIlluminationTest.box(cap, GRAY, 448, -368, 0, 512, 32, 1024);
			}
			return cap;
		});
		byte[] volume = relight(g, sunlit(true));
		float[] west = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + 447, 32 - 20, MIDDLE + 500}, new float[]{-1, 0, 0}));
		float[] east = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + 513, 32 - 20, MIDDLE + 500}, new float[]{1, 0, 0}));
		assertTrue("west " + java.util.Arrays.toString(west), west[1] > west[0] * 1.5f && west[1] > west[2] * 1.5f);
		assertTrue("east " + java.util.Arrays.toString(east), east[0] > east[1] * 1.5f && east[0] > east[2] * 1.5f);
	}

	@Test
	public void aSurfaceDoesNotTakeItsOwnKindsColour()
	{
		// the middle of a yard of green ground is lit and green, and its own colour tints nothing
		GiGrid g = greenYard(32);
		byte[] volume = relight(g, sunlit(true));
		float[] gi = GiLookup.irradiance(g, volume, new float[]{MIDDLE + 800, 32, MIDDLE + 500}, new float[]{0, -1, 0});
		assertEquals(0, tint(gi)[3], 0.02);
		// a pixel by the gray wall takes its gray, which is no tint of green
		float[] byWall = tint(GiLookup.irradiance(g, volume, new float[]{MIDDLE + 540, 32, MIDDLE + 500}, new float[]{0, -1, 0}));
		assertTrue(byWall[1] < byWall[0] * 1.1f && byWall[1] < byWall[2] * 1.1f);
		// nor does a green wall its own, over gray ground
		GiGrid wall = grid((x, z) ->
		{
			GiCapture cap = new GiCapture();
			ground(cap, GRAY, 0, 0, 1024, 1024, 32);
			if (x == 1 && z == 1)
			{
				GlobalIlluminationTest.box(cap, GREEN, 448, -368, 0, 512, 32, 1024);
			}
			return cap;
		});
		byte[] volume2 = relight(wall, sunlit(true));
		float[] face = tint(GiLookup.irradiance(wall, volume2, new float[]{MIDDLE + 513, 32 - 150, MIDDLE + 500}, new float[]{1, 0, 0}));
		assertTrue("wall " + java.util.Arrays.toString(face), face[1] < face[0] * 1.1f && face[1] < face[2] * 1.1f);
	}

	@Test
	public void theSunFadesOutOverAVoxelAtTheEdgeOfAShadow()
	{
		GiGrid g = greenYard(32);
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		float[] share = GiRelight.sunShare(e, sunlit(true), GiEmitters.ParallelFor.SEQUENTIAL);
		// the ground along x in the wall's shadow, which falls west of it
		float[] visible = new float[g.nx];
		java.util.Arrays.fill(visible, -1);
		int z = (int) (MIDDLE + 500) / GiGrid.VOXEL;
		for (int f = 0; f < e.faceCount; ++f)
		{
			if (e.faceDirection[f] == 3 && g.cellZ(e.faceSolid[f]) == z)
			{
				visible[g.cellX(e.faceSolid[f])] = share[f];
			}
		}
		int partly = 0;
		StringBuilder out = new StringBuilder("sun along x:");
		for (int x = (int) MIDDLE / GiGrid.VOXEL; x < (int) (MIDDLE + 448) / GiGrid.VOXEL; ++x)
		{
			out.append(String.format(" %.2f", visible[x]));
			partly += visible[x] > 0.01f && visible[x] < 0.99f ? 1 : 0;
			if (x > (int) MIDDLE / GiGrid.VOXEL)
			{
				// no voxel goes from lit to dark in one step
				assertEquals(out.toString(), visible[x - 1], visible[x], 0.5f);
			}
		}
		System.err.println(out);
		assertEquals(out.toString(), 1, visible[(int) MIDDLE / GiGrid.VOXEL], 0);
		assertEquals(out.toString(), 0, visible[(int) (MIDDLE + 447) / GiGrid.VOXEL], 0);
		assertTrue(out.toString(), partly >= 1);
	}

	@Test
	public void shadeAndNoSunGiveLess()
	{
		GiGrid g = greenYard(32);
		float[] p = {MIDDLE + 513, 32 - 20, MIDDLE + 500};
		float[] n = {1, 0, 0};
		float sun = tint(GiLookup.irradiance(g, relight(g, sunlit(true)), p, n))[3];
		// Sun bounce off: only the sky share lights the grass
		float sky = tint(GiLookup.irradiance(g, relight(g, sunlit(false)), p, n))[3];
		assertTrue("sun " + sun + " sky " + sky, sky > 0.05f && sky < sun * 0.7f);
		// and the grass in the wall's shadow gives as little as with no sun
		float[] west = {MIDDLE + 447, 32 - 20, MIDDLE + 500};
		float shaded = tint(GiLookup.irradiance(g, relight(g, sunlit(true)), west, new float[]{-1, 0, 0}))[3];
		assertEquals(tint(GiLookup.irradiance(g, relight(g, sunlit(false)), west, new float[]{-1, 0, 0}))[3], shaded, shaded * 0.02f);
	}

	@Test
	public void aNewVolumeBlendsInFromTheOld()
	{
		GiGrid g = greenYard(32);
		byte[] lit = relight(g, sunlit(true));
		byte[] dark = new byte[lit.length];
		float[] p = {MIDDLE + 513, 32 - 20, MIDDLE + 500};
		float[] n = {1, 0, 0};
		float full = GiLookup.irradiance(g, dark, lit, 1, p, n)[1];
		assertEquals(GiLookup.irradiance(g, lit, p, n)[1], full, 1e-6);
		assertEquals(0, GiLookup.irradiance(g, dark, lit, 0, p, n)[1], 0);
		assertEquals(full / 2, GiLookup.irradiance(g, dark, lit, 0.5f, p, n)[1], full * 0.01);
	}

	@Test
	public void theLookupIsContinuous()
	{
		GiGrid g = greenYard(20);
		byte[] volume = relight(g, sunlit(true));
		// up the wall, away from it (over the ground), along it, and on the ground toward it: the sum of colour moves by a
		// few hundredths of a unit a step
		smooth("up the wall", g, volume, new float[]{MIDDLE + 513, 20, MIDDLE + 500}, new float[]{MIDDLE + 513, -300, MIDDLE + 500},
			new float[]{1, 0, 0});
		smooth("along the wall", g, volume, new float[]{MIDDLE + 513, 20 - 60, MIDDLE + 100}, new float[]{MIDDLE + 513, 20 - 60, MIDDLE + 900},
			new float[]{1, 0, 0});
		smooth("ground toward the wall", g, volume, new float[]{MIDDLE + 900, 20, MIDDLE + 500},
			new float[]{MIDDLE + 513, 20, MIDDLE + 500}, new float[]{0, -1, 0});
		smooth("ground along the wall", g, volume, new float[]{MIDDLE + 540, 20, MIDDLE + 100},
			new float[]{MIDDLE + 540, 20, MIDDLE + 900}, new float[]{0, -1, 0});
		// away from the wall on the shaded side, where the sun's shadow of it ends
		smooth("through the wall's shadow", g, volume, new float[]{MIDDLE + 447, 20 - 60, MIDDLE + 500},
			new float[]{MIDDLE + 100, 20 - 60, MIDDLE + 500}, new float[]{-1, 0, 0});
		// a slope between the two kinds
		float s = (float) Math.sqrt(0.5);
		smooth("a slope", g, volume, new float[]{MIDDLE + 513, 20 - 200, MIDDLE + 500}, new float[]{MIDDLE + 513, 20, MIDDLE + 500},
			new float[]{s, -s, 0});
	}

	private static void smooth(String what, GiGrid g, byte[] volume, float[] a, float[] b, float[] n)
	{
		float length = (float) Math.sqrt((b[0] - a[0]) * (b[0] - a[0]) + (b[1] - a[1]) * (b[1] - a[1])
			+ (b[2] - a[2]) * (b[2] - a[2]));
		int steps = (int) Math.ceil(length / 2);
		float[] last = null;
		for (int s = 0; s <= steps; ++s)
		{
			float t = (float) s / steps;
			float[] at = {a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
			float[] gi = GiLookup.irradiance(g, volume, at, n);
			// the composite takes the colour by its weight (the share of the filter given any colour falls off over a voxel at the
			// colour's reach), so where the weight is nothing the colour does not matter
			for (int c = 0; c < 3; ++c)
			{
				gi[c] *= gi[3];
			}
			if (last != null)
			{
				for (int c = 0; c < 4; ++c)
				{
					assertTrue(what + " at " + java.util.Arrays.toString(at) + " channel " + c + " " + last[c] + " -> " + gi[c],
						Math.abs(gi[c] - last[c]) <= (c == 3 ? 0.05f : 0.03f));
				}
			}
			last = gi;
		}
	}

	@Test
	public void volumeTexelsAreWhereTheShaderLooksForThem()
	{
		GiGrid g = greenYard(32);
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		byte[] volume = GiRelight.relight(e, sunlit(true), GiEmitters.ParallelFor.SEQUENTIAL);
		// three fields of the grid's cells, four bytes each
		assertEquals(g.cells.length * 3 * 4, volume.length);
		int cells = g.cells.length;
		java.util.BitSet touched = new java.util.BitSet();
		for (int i = 0; i < e.cellCount; ++i)
		{
			touched.set(e.cellIndex[i]);
		}
		for (int idx = 0; idx < cells; ++idx)
		{
			for (int field = 0; field < 3; ++field)
			{
				int alpha = volume[(idx + field * cells) * 4 + 3] & 0xff;
				// a field is given whole: everywhere in the air, and in a filled voxel only where a face gives to it
				assertTrue(alpha == 0 || alpha == 255);
				if (g.cells[idx] == 0)
				{
					assertEquals(255, alpha);
				}
				else if (!touched.get(idx))
				{
					assertEquals(0, alpha);
				}
			}
		}
		// the ground gives to the level field alone: a filled voxel with a face looking up is given in it and not in the others
		int ground = -1;
		for (int y = 0; y < g.ny; ++y)
		{
			if (g.cells[g.index((int) (MIDDLE + 200) / GiGrid.VOXEL, y, (int) (MIDDLE + 200) / GiGrid.VOXEL)] != 0)
			{
				ground = g.index((int) (MIDDLE + 200) / GiGrid.VOXEL, y, (int) (MIDDLE + 200) / GiGrid.VOXEL);
			}
		}
		assertTrue(ground >= 0);
		assertEquals(0, volume[ground * 4 + 3]);
		assertEquals(0, volume[(ground + cells) * 4 + 3]);
		assertEquals(255, volume[(ground + 2 * cells) * 4 + 3] & 0xff);
		// the red of the grass' tint is a good deal less than its green
		float[] sum = new float[3];
		for (int i = 0; i < e.cellCount; ++i)
		{
			for (int c = 0; c < 3; ++c)
			{
				sum[c] += GiRelight.decode(volume[(e.cellIndex[i] + 2 * cells) * 4 + c]);
			}
		}
		assertTrue(sum[1] > sum[0] * 1.5f && sum[1] > sum[2] * 1.5f);
	}

	@Test
	public void whatAVoxelIsGivenIsScaledToOneAtMost()
	{
		GiGrid g = greenYard(32);
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		for (int i = 0; i < e.cellCount; ++i)
		{
			float[] sum = new float[3];
			for (int k = e.inStart[i]; k < e.inStart[i + 1]; ++k)
			{
				for (int a = 0; a < 3; ++a)
				{
					sum[a] += e.inWeight[k * 3 + a];
					assertTrue(e.inWeight[k * 3 + a] >= 0);
				}
			}
			for (int a = 0; a < 3; ++a)
			{
				assertTrue(sum[a] <= 1 + 1e-4f);
			}
		}
	}

	// ---- colour

	@Test
	public void chromaIsTheColourAtUnitLuminance()
	{
		float[] out = new float[3];
		GiRelight.chroma(0.05f, 0.2f, 0.04f, out, 0);
		assertEquals(1, GiRelight.luminance(out[0], out[1], out[2]), 1e-4);
		assertTrue(out[1] > out[0] * 2 && out[1] > out[2] * 2);
		// as strong for a dark color as for a bright one of the same hue
		float[] bright = new float[3];
		GiRelight.chroma(0.5f, 2f, 0.4f, bright, 0);
		assertArrayEquals(out, bright, 1e-4f);
		// gray gives white, which tints nothing, and black does too
		GiRelight.chroma(0.4f, 0.4f, 0.4f, out, 0);
		assertArrayEquals(new float[]{1, 1, 1}, out, 1e-4f);
		GiRelight.chroma(0.001f, 0.002f, 0, out, 0);
		assertArrayEquals(new float[]{1, 1, 1}, out, 0);
		// a pure blue is held to MAX_CHROMA in a channel, at the same luminance
		GiRelight.chroma(0, 0, 0.5f, out, 0);
		assertEquals(GiRelight.MAX_CHROMA, out[2], 1e-4);
		assertEquals(1, GiRelight.luminance(out[0], out[1], out[2]), 1e-4);
		assertTrue(out[0] > 0 && out[1] > 0);
	}

	@Test
	public void aFaceGivesItsAlbedosOwnChroma()
	{
		GiGrid g = greenYard(32);
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		float[] want = new float[3];
		boolean checked = false;
		for (int f = 0; f < e.faceCount; ++f)
		{
			int cell = g.cells[e.faceSolid[f]];
			GiRelight.chroma(GiGrid.albedo(cell, 0), GiGrid.albedo(cell, 1), GiGrid.albedo(cell, 2), want, 0);
			assertArrayEquals(want, java.util.Arrays.copyOfRange(e.faceChroma, f * 3, f * 3 + 3), 1e-5f);
			checked = true;
		}
		assertTrue(checked);
	}

	@Test
	public void anUntexturedTriangleHoldsTheMeanOfItsVerticesColours()
	{
		int blue = 42 << 10 | 6 << 7 | 50;
		float[] out = new float[3], vertex = new float[3], mean = new float[3];
		for (int c : new int[]{GREEN, RED, blue})
		{
			GiVoxels.hslToRgb(c, vertex);
			for (int i = 0; i < 3; ++i)
			{
				mean[i] += GiVoxels.srgbToLinear(vertex[i]) / 3;
			}
		}
		GiVoxels.albedo(GREEN, RED | blue << 16, out);
		assertArrayEquals(mean, out, 1e-5f);
		// a triangle of one color is that color
		GiVoxels.hslToRgb(RED, vertex);
		GiVoxels.albedo(RED, RED | RED << 16, out);
		for (int i = 0; i < 3; ++i)
		{
			assertEquals(GiVoxels.srgbToLinear(vertex[i]), out[i], 1e-5);
		}
	}

	@Test
	public void aTexturedTriangleHoldsItsTexturesAverageColour()
	{
		float[] before = GiVoxels.textureAlbedo;
		try
		{
			float[] textures = GiVoxels.newTextureAlbedo(4);
			// texture 2 is a bluish green on average (the second ARGB pixel is a hole), whatever the lightness the face is lit with
			GiVoxels.textureAverage(2, new int[]{0xff208060, 0, 0xff40a0c0}, textures);
			GiVoxels.textureAlbedo = textures;
			float[] out = new float[3];
			GiVoxels.albedo(3 << 16 | 90, 90 | 90 << 16, out);
			assertEquals((GiVoxels.srgbToLinear(0x20 / 255f) + GiVoxels.srgbToLinear(0x40 / 255f)) / 2, out[0], 1e-5);
			assertEquals((GiVoxels.srgbToLinear(0x80 / 255f) + GiVoxels.srgbToLinear(0xa0 / 255f)) / 2, out[1], 1e-5);
			assertEquals((GiVoxels.srgbToLinear(0x60 / 255f) + GiVoxels.srgbToLinear(0xc0 / 255f)) / 2, out[2], 1e-5);
			// and a texture whose pixels are not known yet is a neutral gray, which tints nothing
			GiVoxels.albedo(1 << 16, 0, out);
			assertEquals(out[0], out[1], 0);
			assertEquals(out[1], out[2], 0);
		}
		finally
		{
			GiVoxels.textureAlbedo = before;
		}
	}

	@Test
	public void texelsHoldTheSquareRootOfTheSum()
	{
		byte[] t = new byte[4];
		for (int v : new int[]{0, 1, 17, 128, 255})
		{
			t[0] = (byte) v;
			float u = v / 255f;
			assertEquals(u * u * GiRelight.TEXEL_RANGE, GiRelight.decode(t[0]), 1e-6);
		}
	}

	@Test
	public void relightTriggers()
	{
		assertEquals(0, GlobalIllumination.angle(new float[]{0, -1, 0}, new float[]{0, -2, 0}), 1e-3);
		float c = (float) Math.cos(Math.toRadians(3)), s = (float) Math.sin(Math.toRadians(3));
		assertEquals(3, GlobalIllumination.angle(new float[]{0, -1, 0}, new float[]{s, -c, 0}), 1e-2);
	}

	@Test
	public void shaderMatchesTheLayout()
	{
		assertEquals(GiGrid.SCENE_TILES, GiLookup.define("GI_SIZE"), 0);
		assertEquals(GiGrid.TILE, GiLookup.define("GI_TILE"), 0);
		assertEquals(GiGrid.VOXEL, GiLookup.define("GI_VOXEL"), 0);
		assertEquals(GiRelight.TEXEL_RANGE, GiLookup.define("GI_RANGE"), 0);
		// the snap of the shader's weights is the one the faces' shares are given
		assertEquals(Math.pow(Math.sin(Math.toRadians(GiEmitters.SLOPE_SNAP)), 2), GiLookup.define("GI_SLOPE_SLACK"), 0.001);
		// a pixel reads past the half voxel a surface can be off the center of its own and short of the voxel, so the point it
		// reads is in front of its surface and between the first voxels of air
		assertTrue(GiLookup.define("GI_LOOKUP") > 0.5f * GiGrid.VOXEL && GiLookup.define("GI_LOOKUP") < GiGrid.VOXEL);
		// the reach is within the tiles the grid fades over
		assertTrue(GiEmitters.REACH < GiLookup.define("GI_EDGE_FADE") * GiGrid.TILE);
	}

	// ---- water and capture

	@Test
	public void waterSurfaceIsALayerOfBlueVoxels()
	{
		GiWater water = new GiWater();
		// two tiles of water at world y 40: the voxel layer 0
		for (int x = 10; x < 12; ++x)
		{
			water.type[10 * GiGrid.SCENE_TILES + x] = WaterMap.FRESH;
			water.surface[10 * GiGrid.SCENE_TILES + x] = 40;
		}
		// one deeper down (y down: larger)
		water.type[20 * GiGrid.SCENE_TILES + 20] = WaterMap.OCEAN;
		water.surface[20 * GiGrid.SCENE_TILES + 20] = 3 * 64 + 10;
		// and one over the ground, which keeps its voxels
		water.type[3 * GiGrid.SCENE_TILES + 3] = WaterMap.FRESH;
		water.surface[3 * GiGrid.SCENE_TILES + 3] = 0;
		GiGrid g = pond(water);
		int cell = g.cells[g.index(20, -g.layer0, 20)];
		assertNotEquals(0, cell);
		assertTrue(GiGrid.albedo(cell, 2) > GiGrid.albedo(cell, 0) * 1.5f);
		assertEquals(-1, GiGrid.normal(cell)[1], 0.02);
		// the voxel's center, y 32, is 8 above the surface at 40: in front of the face that looks up
		assertEquals(40 - 32, g.planeDistance(g.index(20, -g.layer0, 20)), 1);
		assertNotEquals(0, g.cells[g.index(23, -g.layer0, 21)]);
		assertEquals(0, g.cells[g.index(24, -g.layer0, 20)]);
		assertNotEquals(0, g.cells[g.index(41, 3 - g.layer0, 41)]);
		assertEquals(0, g.cells[g.index(41, 2 - g.layer0, 41)]);
		GiGrid dry = pond(null);
		assertEquals(dry.cells[dry.index(6, -dry.layer0, 6)], g.cells[g.index(6, -g.layer0, 6)]);
	}

	// 4 x 4 zones of ground where the middle four are missing (the water's bed is left out of the capture)
	private static GiGrid pond(GiWater water)
	{
		GiVoxels.Zone[] zones = new GiVoxels.Zone[16];
		int[] zx = new int[16], zz = new int[16];
		GiCapture cap = new GiCapture();
		ground(cap, GREEN, 0, 0, 1024, 1024, 0);
		GiVoxels.Zone ground = GiVoxels.voxelize(cap);
		for (int i = 0; i < 16; ++i)
		{
			int x = i / 4, z = i % 4;
			zones[i] = x >= 1 && x <= 2 && z >= 1 && z <= 2 ? null : ground;
			zx[i] = BASE_ZONE + x;
			zz[i] = BASE_ZONE + z;
		}
		return GiGrid.assemble(zones, zx, zz, BASE_ZONE, water);
	}

	@Test
	public void waterGivesBlueAndGrassGreen()
	{
		GiWater water = new GiWater();
		for (int x = 8; x < 24; ++x)
		{
			for (int z = 8; z < 24; ++z)
			{
				water.type[z * GiGrid.SCENE_TILES + x] = WaterMap.FRESH;
				water.surface[z * GiGrid.SCENE_TILES + x] = 40;
			}
		}
		GiGrid g = pond(water);
		GiEmitters e = GiEmitters.build(g, GiEmitters.ParallelFor.SEQUENTIAL);
		float[] water3 = null, grass3 = null;
		for (int f = 0; f < e.faceCount; ++f)
		{
			// the face that looks up in the column of voxels at (30, 30) and at (4, 4)
			if (e.faceDirection[f] != 3)
			{
				continue;
			}
			int idx = e.faceSolid[f];
			if (g.cellX(idx) == 30 && g.cellZ(idx) == 30)
			{
				water3 = java.util.Arrays.copyOfRange(e.faceChroma, f * 3, f * 3 + 3);
			}
			else if (g.cellX(idx) == 4 && g.cellZ(idx) == 4)
			{
				grass3 = java.util.Arrays.copyOfRange(e.faceChroma, f * 3, f * 3 + 3);
			}
		}
		assertTrue(water3 != null && grass3 != null);
		assertTrue("water " + java.util.Arrays.toString(water3), water3[2] > water3[0] * 2f && water3[2] > water3[1] * 0.9f);
		assertTrue("grass " + java.util.Arrays.toString(grass3), grass3[1] > grass3[2] * 2f);
	}

	@Test
	public void noWaterNoSurface()
	{
		assertNull(GiWater.of(null, null, 0));
	}

	@Test
	public void captureLeavesOutTheSunkBed()
	{
		GiCapture.Buffer buffer = new GiCapture.Buffer(IntBuffer.allocate(64));
		for (int k = 0; k < 3; ++k)
		{
			buffer.put22224(k * 100, 0, k % 2 * 100, 0, GREEN);
			buffer.put2222(WaterMap.BED_FLAG, 0, 0, 0);
		}
		assertEquals(0, buffer.capture.count);
		for (int k = 0; k < 3; ++k)
		{
			buffer.put22224(k * 100, 0, k % 2 * 100, 0, GREEN);
			buffer.put2222(0, 0, 0, 0);
		}
		assertEquals(1, buffer.capture.count);
	}
}
