package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The bake of the static lights (LightBake): shadows from walls and roofs, no shadow from the voxel a light sits in,
 * which faces get its light, what a flickering light leaves in a cell, and how long it takes.
 */
public class LightBakeTest
{
	// the box of cells every test bakes: the scene's 1000 units on x and z, and from 512 up to 64 down
	private static final int X0 = 0, Y0 = -16, Z0 = 0, SX = 32, SY = 18, SZ = 32;

	private static final float[] STEADY = new float[LightBake.SLOTS];

	static
	{
		java.util.Arrays.fill(STEADY, 1f);
	}

	private static final class Baked
	{
		final short[] a = new short[SX * SY * SZ * 4], b = new short[SX * SY * SZ * 4];

		/** The brightness a surface facing n gets at a world position. */
		float at(float x, float y, float z, float nx, float ny, float nz, float[] mods)
		{
			int i = ((LightBake.cell(z) - Z0) * SY + (LightBake.cell(y) - Y0)) * SX + (LightBake.cell(x) - X0);
			float[] out = new float[3];
			LightBake.irradiance(a, b, i, nx, ny, nz, mods, out);
			return (out[0] + out[1] + out[2]) / 3;
		}

		float at(float x, float y, float z)
		{
			// all around: the mean of the six faces
			float sum = 0;
			float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
			for (float[] f : n)
			{
				sum += at(x, y, z, f[0], f[1], f[2], STEADY);
			}
			return sum / 6;
		}
	}

	private static LightBake.Light light(float x, float y, float z, float radius, float strength, int slot)
	{
		return new LightBake.Light(x, y, z, radius, strength, strength, strength, slot);
	}

	private static Baked bake(VoxelGrid grid, LightBake.Light... lights)
	{
		Baked out = new Baked();
		LightBake.bake(grid, lights, lights.length, X0, Y0, Z0, SX, SY, SZ, out.a, out.b);
		return out;
	}

	@Test
	public void openGroundFallsOffWithDistance()
	{
		Baked baked = bake(LightScenes.open(), light(300, -100, 300, 640, 1, 0));
		float near = baked.at(300 + 100, -100, 300), far = baked.at(300 + 400, -100, 300);
		assertTrue(near > far);
		assertTrue(far > 0);
		// 117 HD's falloff: (1 - d / r)^2 of the strength
		float expected = (1 - 100 / 640f) * (1 - 100 / 640f);
		float faceToward = baked.at(300 + 100, -100, 300, -1, 0, 0, STEADY);
		assertEquals(expected, faceToward, 0.15f * expected);
		assertEquals(0, baked.at(512 + 480, -100, 512 - 500), 0);
	}

	@Test
	public void wallsShadowTheOutsideOfARoom()
	{
		LightBake.Light lamp = light(512, -100, 512, 640, 1, 0);
		Baked open = bake(LightScenes.open(), lamp), room = bake(LightScenes.room(), lamp);
		// inside, a few tiles from the lamp: the same as in the open
		float inside = room.at(512 - 130, -100, 512 + 90);
		assertTrue(inside > 0.5f * open.at(512 - 130, -100, 512 + 90));
		// outside, past the wall, as near to the lamp as the inside one: in the open it is lit, behind the wall it is not
		for (float outsideX : new float[]{840, 880})
		{
			assertTrue(open.at(outsideX, -100, 512) > 0.02f);
			assertEquals(0, room.at(outsideX, -100, 512), 0.2f * open.at(outsideX, -100, 512));
		}
	}

	@Test
	public void aRoofShadowsWhatIsUnderIt()
	{
		// a lamp above the roof lights nothing in the room under it
		LightBake.Light above = light(512, -420, 512, 640, 1, 0);
		Baked open = bake(LightScenes.open(), above), room = bake(LightScenes.room(), above);
		assertTrue(open.at(512, -100, 512) > 0.03f);
		assertEquals(0, room.at(512, -100, 512), 0.15f * open.at(512, -100, 512));
		// and what is on the roof is lit
		assertTrue(room.at(512, -330, 512) > 0.5f * open.at(512, -330, 512));
		// a lamp in the room does not light the roof's far side
		LightBake.Light inside = light(512, -100, 512, 640, 1, 0);
		Baked in = bake(LightScenes.room(), inside), free = bake(LightScenes.room(false, false), inside);
		assertEquals(0, in.at(512, -420, 512), 0.2f * free.at(512, -420, 512));
		assertTrue(free.at(512, -420, 512) > 0.02f);
	}

	@Test
	public void aDoorwayLetsLightOutAsABeam()
	{
		LightBake.Light lamp = light(608, -100, 400, 760, 1, 0);
		Baked shut = bake(LightScenes.room(), lamp), door = bake(LightScenes.room(true, true), lamp);
		// the doorway is in the wall at z = 768, x 512 to 640 (a voxel of it open, 576 to 640): out through it, in line
		// with the lamp, is lit; off to the side behind the wall, not
		float lit = door.at(608, -100, 840), unlit = door.at(400, -100, 840);
		assertTrue(lit > 0.02f);
		assertTrue(lit > 4 * unlit);
		assertEquals(0, shut.at(608, -100, 840), 0.2f * lit);
	}

	@Test
	public void aWallInsideTheRoomShadowsItsFarSide()
	{
		LightBake.Light lamp = light(400, -100, 512, 640, 1, 0);
		Baked divided = bake(LightScenes.roomDivided(), lamp), room = bake(LightScenes.room(), lamp);
		assertTrue(room.at(640, -100, 512) > 0.02f);
		assertEquals(0, divided.at(640, -100, 512), 0.2f * room.at(640, -100, 512));
		// the near side is the same either way
		assertEquals(room.at(300, -100, 512), divided.at(300, -100, 512), 0.15f * room.at(300, -100, 512));
	}

	@Test
	public void aLightInsideAVoxelDoesNotShadowItself()
	{
		// a lamp in a brazier: the voxel it is in is the brazier's, which would shade everything round it if the rays
		// ended in the light itself. It is lit from just over the brazier's top: what is up there is lit, a ground
		// level cell beside the brazier is in its real shadow
		LightBake.Light flame = light(540, -30, 540, 640, 1, 0);
		Baked open = bake(LightScenes.open(), flame), brazier = bake(LightScenes.brazier(), flame);
		for (float[] side : new float[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}})
		{
			float x = 540 + side[0] * 220, z = 540 + side[1] * 220;
			assertTrue("side " + side[0] + ", " + side[1], brazier.at(x, -100, z) > 0.7f * open.at(x, -100, z));
		}
		assertTrue(brazier.at(540, -100, 540 + 100) > 0.7f * open.at(540, -100, 540 + 100));
		// and in a closed box the light is shut in with it
		Baked shut = bake(LightScenes.box(), light(512, -60, 512, 640, 1, 0));
		assertEquals(0, shut.at(512 + 250, -60, 512), 0);
		assertTrue(shut.at(512 + 40, -60, 512) > 0.1f);
	}

	@Test
	public void aWallTorchLightsTheRoomAndNotTheOutside()
	{
		// a torch in the wall's own voxel, on the room's side of it (the wall is x = 256, voxels 192 to 320)
		LightBake.Light torch = light(272, -120, 512, 640, 1, 0);
		Baked baked = bake(LightScenes.room(), torch), open = bake(LightScenes.open(), torch);
		assertTrue(baked.at(400, -120, 512) > 0.5f * open.at(400, -120, 512));
		assertTrue(open.at(100, -120, 512) > 0.02f);
		assertEquals(0, baked.at(100, -120, 512), 0.2f * open.at(100, -120, 512));
		// the wall's own cells next to it are lit on the room's side
		assertTrue(baked.at(340, -120, 512, -1, 0, 0, STEADY) > 0);
	}

	@Test
	public void thePlayerSideOfAWallIsTheOneALightOnItHangsOn()
	{
		// the same torch put on the other side of the wall lights the outside and not the room
		LightBake.Light torch = light(240, -120, 512, 640, 1, 0);
		Baked baked = bake(LightScenes.room(), torch), open = bake(LightScenes.open(), torch);
		assertTrue(baked.at(100, -120, 512) > 0.5f * open.at(100, -120, 512));
		assertEquals(0, baked.at(400, -120, 512), 0.2f * open.at(400, -120, 512));
	}

	@Test
	public void eachFaceTakesTheLightFromItsOwnSide()
	{
		Baked baked = bake(LightScenes.open(), light(512, -200, 512, 640, 1, 0));
		// a cell east of the lamp: the light comes from the west, a surface facing west gets it and one facing east not
		float west = baked.at(512 + 200, -200, 512, -1, 0, 0, STEADY), east = baked.at(512 + 200, -200, 512, 1, 0, 0, STEADY);
		assertTrue(west > 0.1f);
		assertEquals(0, east, 0.02f * west);
		// below the lamp (y down: larger y), the light comes from above: the side that faces up (-y) gets it
		float up = baked.at(512, -200 + 160, 512 + 40, 0, -1, 0, STEADY), down = baked.at(512, -200 + 160, 512 + 40, 0, 1, 0, STEADY);
		assertTrue(up > 0.1f);
		assertTrue(up > 10 * down);
		// a slanted surface gets a share of each face in proportion to the square of its normal
		float s = (float) Math.sqrt(0.5);
		float slant = baked.at(512 + 200, -200, 512, -s, 0, -s, STEADY);
		float front = baked.at(512 + 200, -200, 512, 0, 0, -1, STEADY);
		assertEquals(0.5f * west + 0.5f * front, slant, 0.02f);
	}

	@Test
	public void aCellRightBesideALightIsLitOnEveryFace()
	{
		// the direction to a light is too coarse within a cell of it: all faces get some, so a surface beside a lamp
		// is not dark because its centre is on the wrong side
		Baked baked = bake(LightScenes.open(), light(512, -200, 512, 640, 1, 0));
		float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		for (float[] f : n)
		{
			assertTrue(baked.at(512 + 8, -200 + 8, 512 + 8, f[0], f[1], f[2], STEADY) > 0.2f);
		}
	}

	@Test
	public void aFlickeringLightLeavesItsShareToSwing()
	{
		LightBake.Light flame = light(512, -150, 512, 640, 1, 5);
		Baked baked = bake(LightScenes.open(), flame);
		float[] mods = STEADY.clone();
		float full = baked.at(512 + 150, -150, 512, -1, 0, 0, mods);
		assertTrue(full > 0.1f);
		mods[5] = 0.5f;
		assertEquals(0.5f * full, baked.at(512 + 150, -150, 512, -1, 0, 0, mods), 0.1f * full);
		mods[5] = 0;
		assertEquals(0, baked.at(512 + 150, -150, 512, -1, 0, 0, mods), 0.1f * full);
		// with a steady light as bright as it, beside it, half of the cell's light swings
		Baked both = bake(LightScenes.open(), flame, light(512 + 300, -150, 512, 640, 1, 0));
		mods[5] = 0;
		float steadyLeft = both.at(512 + 150, -150, 512, -1, 0, 0, mods);
		float both1 = both.at(512 + 150, -150, 512, -1, 0, 0, STEADY);
		assertTrue(steadyLeft > 0.2f * both1);
		assertTrue(steadyLeft < 0.8f * both1);
	}

	@Test
	public void theStrongestFlickeringLightIsTheOneARecordedSlot()
	{
		Baked baked = bake(LightScenes.open(), light(300, -150, 512, 500, 1, 3), light(700, -150, 512, 500, 1, 9));
		for (float[] spot : new float[][]{{360, 512}, {640, 512}})
		{
			int i = ((LightBake.cell(512) - Z0) * SY + (LightBake.cell(-150) - Y0)) * SX + LightBake.cell(spot[0]) - X0;
			int code = Math.round(Half.fromHalf(baked.b[i * 4 + 3]));
			assertEquals(spot[0] < 500 ? 3 : 9, code >> 4);
			assertTrue((code & 15) > 8);
		}
	}

	@Test
	public void colourSurvivesAsATint()
	{
		float[] out = new float[3];
		LightBake.untint(LightBake.tint(1, 0.5f, 0.2f), out);
		assertEquals(1, (out[0] + out[1] + out[2]) / 3, 1e-3f);
		assertTrue(out[0] > out[1] && out[1] > out[2]);
		// (1, 0.5, 0.2) has the hue 0.375 of the way from red to yellow and the saturation 0.8
		assertEquals((1 - 0.2f) / (0.5f - 0.2f), (out[0] - out[2]) / (out[1] - out[2]), 0.4f);
		LightBake.untint(LightBake.tint(0.7f, 0.7f, 0.7f), out);
		assertEquals(1, out[0], 1e-4f);
		assertEquals(1, out[2], 1e-4f);
		// the code is a whole number half floats hold
		assertTrue(LightBake.tint(0, 0, 1) < 2048);
		assertEquals(LightBake.tint(0.3f, 0.6f, 0.9f), Half.fromHalf(Half.toHalf(LightBake.tint(0.3f, 0.6f, 0.9f))), 0);
		float flicker = LightBake.flicker(LightBake.SLOTS - 1, 1);
		assertEquals(flicker, Half.fromHalf(Half.toHalf(flicker)), 0);
	}

	@Test
	public void aBakedCellKeepsTheLightsColour()
	{
		Baked baked = new Baked();
		LightBake.bake(LightScenes.open(), new LightBake.Light[]{new LightBake.Light(512, -150, 512, 640, 1.0f, 0.5f, 0.1f, 0)},
			1, X0, Y0, Z0, SX, SY, SZ, baked.a, baked.b);
		int i = ((LightBake.cell(512) - Z0) * SY + (LightBake.cell(-150) - Y0)) * SX + LightBake.cell(700) - X0;
		float[] out = new float[3];
		LightBake.irradiance(baked.a, baked.b, i, -1, 0, 0, STEADY, out);
		assertTrue(out[0] > out[1] && out[1] > out[2]);
		assertEquals(2f, out[0] / out[1], 0.5f);
	}

	@Test
	public void twoLightsAdd()
	{
		LightBake.Light one = light(400, -150, 512, 500, 0.6f, 0), two = light(700, -150, 512, 500, 0.6f, 0);
		float a = bake(LightScenes.open(), one).at(550, -150, 512), b = bake(LightScenes.open(), two).at(550, -150, 512);
		assertEquals(a + b, bake(LightScenes.open(), one, two).at(550, -150, 512), 0.02f * (a + b));
	}

	@Test
	public void twoHundredLightsBakeInTime()
	{
		// the bake of a busy scene: lights spread over the box, each reaching 5 tiles
		java.util.Random random = new java.util.Random(1);
		LightBake.Light[] lights = new LightBake.Light[200];
		for (int i = 0; i < lights.length; ++i)
		{
			lights[i] = light(random.nextInt(1000), -random.nextInt(300), random.nextInt(1000), 640, 1, i % 4 == 0 ? 1 + i % 100 : 0);
		}
		VoxelGrid grid = LightScenes.room();
		// warm up the JIT as the first scenes of a session would not have
		Baked out = new Baked();
		LightBake.bake(grid, lights, 20, X0, Y0, Z0, SX, SY, SZ, out.a, out.b);
		long start = System.nanoTime();
		LightBake.bake(grid, lights, lights.length, X0, Y0, Z0, SX, SY, SZ, out.a, out.b);
		double ms = (System.nanoTime() - start) / 1e6;
		long cells = 0;
		for (LightBake.Light l : lights)
		{
			cells += l.cells();
		}
		System.out.printf("Bake of 200 lights over %d cells: %.0f ms (%.2f ms a light, %d traced cells)%n", SX * SY * SZ, ms,
			ms / lights.length, cells);
		assertTrue("took " + ms + " ms", ms < 15000);
	}
}
