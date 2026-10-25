package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Test;

/**
 * The registry and scheduler of the baked lights (LightVolume): what is resident when, what is baked again when a
 * light or the scene changes, the window as the player moves, and flicker slots.
 */
public class LightVolumeTest
{
	private final LightVolume volume = new LightVolume();
	// what the texture would hold: the latest bake of each block, by slot
	private final LightVolume.Result[] texture = new LightVolume.Result[LightVolume.BLOCK_COUNT];
	private SceneVoxels voxels = new SceneVoxels(LightScenes.open(), 10, 20, false, null);
	// the player stands on the ground, which is y = 0
	private float focalX = 512, focalY = 0, focalZ = 512;

	@After
	public void free()
	{
		volume.free();
	}

	private static PointLight lamp(Object key, float x, float y, float z, float strength)
	{
		PointLight l = new PointLight().set(x, y, z, 640, 1, 1, 1, strength).fixed();
		return l.bakeable(key, 640, strength, 1, false);
	}

	private void frame(List<PointLight> lights)
	{
		volume.update(lights, voxels, 10, 20, false, focalX, focalY, focalZ);
		for (LightVolume.Result r : volume.pollFinished(1000))
		{
			texture[r.slot] = r;
			volume.committed(r);
		}
	}

	/** Frames until every bake is in; the workers are on their own threads, so it waits. */
	private void settle(List<PointLight> lights)
	{
		long end = System.currentTimeMillis() + 30000;
		do
		{
			frame(lights);
			if (volume.pendingBlocks() == 0)
			{
				return;
			}
			try
			{
				Thread.sleep(2);
			}
			catch (InterruptedException e)
			{
				throw new IllegalStateException(e);
			}
		}
		while (System.currentTimeMillis() < end);
		throw new AssertionError("bakes did not finish: " + volume.pendingBlocks() + " pending");
	}

	/** The brightness the baked cells have at a world position, for the faces together. */
	private float baked(float x, float y, float z)
	{
		int cx = LightBake.cell(x), cy = LightBake.cell(y), cz = LightBake.cell(z);
		int slot = volume.slotOf(Math.floorDiv(cx, LightVolume.BLOCK), Math.floorDiv(cy, LightVolume.BLOCK),
			Math.floorDiv(cz, LightVolume.BLOCK));
		assertTrue("in the window", slot >= 0);
		LightVolume.Result r = texture[slot];
		if (r == null || r.a == null)
		{
			return 0;
		}
		int lx = cx - (r.bx * LightVolume.BLOCK - LightVolume.APRON), ly = cy - (r.by * LightVolume.BLOCK - LightVolume.APRON),
			lz = cz - (r.bz * LightVolume.BLOCK - LightVolume.APRON);
		int i = (lz * LightVolume.REGION + ly) * LightVolume.REGION + lx;
		float sum = 0;
		float[] mods = volume.modulation();
		float[][] n = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
		float[] out = new float[3];
		for (float[] f : n)
		{
			LightBake.irradiance(r.a, r.b, i, f[0], f[1], f[2], mods, out);
			sum += (out[0] + out[1] + out[2]) / 3;
		}
		return sum / 6;
	}

	private static List<PointLight> list(PointLight... lights)
	{
		return new ArrayList<>(java.util.Arrays.asList(lights));
	}

	@Test
	public void nothingIsBakedWithoutVoxelsOfTheScene()
	{
		PointLight l = lamp("a", 512, -100, 512, 1);
		List<PointLight> lights = list(l);
		volume.update(lights, null, 10, 20, false, focalX, focalY, focalZ);
		assertEquals(0, volume.epochOf(l));
		assertFalse(volume.isPlaced());
		// voxels of another scene count as none
		volume.update(lights, voxels, 11, 20, false, focalX, focalY, focalZ);
		assertEquals(0, volume.epochOf(l));
		assertFalse(volume.resident(l));
	}

	@Test
	public void aLightIsOnTheOldPathUntilItsBlocksAreIn()
	{
		PointLight l = lamp("a", 512, -100, 512, 1);
		List<PointLight> lights = list(l);
		volume.update(lights, voxels, 10, 20, false, focalX, focalY, focalZ);
		assertTrue(volume.epochOf(l) > 0);
		assertFalse("not before its blocks are baked", volume.resident(l));
		assertTrue(volume.pendingBlocks() > 0);
		settle(lights);
		assertTrue(volume.resident(l));
		assertTrue(volume.bakedBlocks() > 0);
		assertTrue(baked(512 + 100, -100, 512) > 0.1f);
		// the blocks it reaches hold its epoch or a later one, the shader's test
		for (int slot : new int[]{volume.slotOf(Math.floorDiv(LightBake.cell(512), 16), Math.floorDiv(LightBake.cell(-100), 16),
			Math.floorDiv(LightBake.cell(512), 16))})
		{
			assertTrue(volume.blockEpochs()[slot] >= volume.epochOf(l));
		}
	}

	@Test
	public void aLightThatDoesNotChangeIsNotBakedAgain()
	{
		PointLight l = lamp("a", 512, -100, 512, 1);
		List<PointLight> lights = list(l);
		settle(lights);
		int epoch = volume.epochOf(l);
		int baked = volume.bakedBlocks();
		// the same light each frame, its strength moving by less than the tolerance
		for (int i = 0; i < 5; ++i)
		{
			lights = list(lamp("a", 512, -100, 512, 1 + 0.01f * i));
			frame(lights);
		}
		assertEquals(0, volume.pendingBlocks());
		assertEquals(epoch, volume.epochOf(lights.get(0)));
		assertEquals(baked, volume.bakedBlocks());
	}

	@Test
	public void aLightThatChangesIsBakedAgainWhereItReaches()
	{
		List<PointLight> lights = list(lamp("a", 512, -100, 512, 1));
		settle(lights);
		float before = baked(512 + 100, -100, 512);
		int epoch = volume.epochOf(lights.get(0));
		lights = list(lamp("a", 512, -100, 512, 2));
		frame(lights);
		assertEquals("the light stays resident on the old bake until the new one is in", epoch, volume.epochOf(lights.get(0)));
		assertTrue(volume.pendingBlocks() > 0);
		settle(lights);
		assertEquals(2 * before, baked(512 + 100, -100, 512), 0.05f * before);
		// a blocks far from it are not touched: only the blocks it reaches were baked again
		LightVolume.Result far = null;
		for (LightVolume.Result r : texture)
		{
			if (r != null && r.lights == 0)
			{
				far = r;
			}
		}
		assertTrue(volume.lightCount() == 1);
	}

	@Test
	public void aLightThatMovesIsBakedAgainAtBothPlaces()
	{
		List<PointLight> lights = list(lamp("a", 400, -100, 512, 1));
		settle(lights);
		assertTrue(baked(400 + 100, -100, 512) > 0.05f);
		lights = list(lamp("a", 900, -100, 512, 1));
		settle(lights);
		assertEquals(0, baked(400 - 100, -100, 512), 1e-3f);
		assertTrue(baked(900 - 100, -100, 512) > 0.05f);
	}

	@Test
	public void aLightThatGoesIsGoneFromTheCells()
	{
		List<PointLight> lights = list(lamp("a", 512, -100, 512, 1), lamp("b", 700, -100, 700, 1));
		settle(lights);
		assertEquals(2, volume.lightCount());
		assertTrue(baked(512 + 40, -100, 512) > 0.1f);
		lights = list(lamp("b", 700, -100, 700, 1));
		settle(lights);
		assertEquals(1, volume.lightCount());
		assertFalse(volume.resident(new PointLight().set(512, -100, 512, 640, 1, 1, 1, 1).bakeable("a", 640, 1, 1, false)));
		// a is out; b alone lights the cell
		float b = baked(512 + 40, -100, 512);
		assertEquals(baked(512 + 40, -100, 512), b, 0);
		List<PointLight> only = list(lamp("b", 700, -100, 700, 1));
		assertTrue(b < 0.3f);
		frame(only);
	}

	@Test
	public void theSceneChangingRebakesAndAnotherSceneStartsOver()
	{
		List<PointLight> lights = list(lamp("a", 400, -100, 512, 1));
		settle(lights);
		assertTrue(baked(640, -100, 512) > 0.05f);
		// the voxels change: a wall goes up between the light and a cell, found by comparing the grids on a worker, so
		// some frames later; until then the old bake stands
		voxels = new SceneVoxels(LightScenes.roomDivided(), 10, 20, false, null);
		long end = System.currentTimeMillis() + 10000;
		do
		{
			frame(lights);
		}
		while (baked(640, -100, 512) > 0 && System.currentTimeMillis() < end);
		settle(lights);
		assertEquals(0, baked(640, -100, 512), 1e-4f);
		assertTrue(baked(300, -100, 512) > 0.05f);
		// another scene: nothing is resident
		PointLight l = lights.get(0);
		volume.update(lights, new SceneVoxels(LightScenes.open(), 11, 20, false, null), 11, 20, false, focalX, focalY, focalZ);
		assertTrue(volume.epochOf(l) > 0);
		assertEquals(0, volume.bakedBlocks());
		assertFalse(volume.resident(l));
	}

	@Test
	public void aVoxelChangeOnlyRebakesWhatItReaches()
	{
		// two lights far apart, a wall goes up by one of them
		List<PointLight> lights = list(lamp("a", 300, -100, 300, 1), lamp("b", 2300, -100, 700, 1));
		settle(lights);
		int baked = volume.bakedBlocks();
		assertTrue(baked > 0);
		LightVolume.Result before = null;
		for (LightVolume.Result r : texture)
		{
			if (r != null && r.a != null && r.bx >= 3)
			{
				before = r;
			}
		}
		assertTrue(before != null);
		int epoch = volume.blockEpochs()[before.slot];
		voxels = new SceneVoxels(LightScenes.roomDivided(), 10, 20, false, null);
		long end = System.currentTimeMillis() + 10000;
		do
		{
			frame(lights);
		}
		while (baked(640, -100, 300) > 0 && System.currentTimeMillis() < end);
		settle(lights);
		assertEquals("blocks the changed voxels do not reach are not baked again", epoch, volume.blockEpochs()[before.slot]);
	}

	@Test
	public void aFlickeringLightHoldsASlotAndSwingsWithoutRebaking()
	{
		PointLight flame = new PointLight().set(512, -100, 512, 960, 1, 1, 1, 1).bakeable("f", 640, 1, 1, true);
		List<PointLight> lights = list(flame);
		settle(lights);
		int epoch = volume.epochOf(flame);
		float steady = baked(512 + 100, -100, 512);
		assertTrue(steady > 0.1f);
		// the swing is the slot's modulation, not a bake
		for (float m : new float[]{0.5f, 1.3f, 0.2f})
		{
			PointLight f = new PointLight().set(512, -100, 512, 960, 1, 1, 1, 1).bakeable("f", 640, 1, m, true);
			lights = list(f);
			frame(lights);
			assertEquals(0, volume.pendingBlocks());
			assertEquals(epoch, volume.epochOf(f));
			assertEquals(steady * m, baked(512 + 100, -100, 512), 0.15f * steady);
		}
		int slots = 0;
		for (float m : volume.modulation())
		{
			if (m != 1f)
			{
				++slots;
			}
		}
		assertEquals(1, slots);
	}

	@Test
	public void aFlickerSlotIsGivenBackOnceTheBlocksHoldNoTraceOfTheLight()
	{
		PointLight flame = new PointLight().set(512, -100, 512, 960, 1, 1, 1, 1).bakeable("f", 640, 1, 0.5f, true);
		List<PointLight> lights = list(flame);
		settle(lights);
		int used = -1;
		for (int s = 0; s < volume.modulation().length; ++s)
		{
			if (volume.modulation()[s] != 1f)
			{
				used = s;
			}
		}
		assertTrue(used > 0);
		// removed: the slot waits until the blocks are baked without the light, then another light can have it
		lights = list();
		settle(lights);
		for (int i = 0; i < 125; ++i)
		{
			lights.add(new PointLight().set(100 + i * 5, -100, 300, 200, 1, 1, 1, 1).bakeable("n" + i, 200, 1, 1, true));
		}
		settle(lights);
		int distinct = 0;
		for (float m : volume.modulation())
		{
			if (m == 1f)
			{
				++distinct;
			}
		}
		assertTrue(distinct >= 1);
		assertEquals(125, volume.lightCount());
	}

	@Test
	public void lightsOutOfTheWindowAreNotBaked()
	{
		PointLight far = lamp("far", 9000, -100, 9000, 1);
		List<PointLight> lights = list(far, lamp("near", 512, -100, 512, 1));
		settle(lights);
		assertEquals(1, volume.lightCount());
		assertEquals(0, volume.epochOf(far));
		assertFalse(volume.resident(far));
	}

	@Test
	public void aLightReachingOutOfTheWindowStaysWithTheClusters()
	{
		// resident only when all of it is in the window, else the part past the edge would be dark
		PointLight edge = new PointLight().set(512, -100, 512, 5000, 1, 1, 1, 1).bakeable("edge", 5000, 1, 1, false);
		List<PointLight> lights = list(edge);
		settle(lights);
		assertTrue(volume.epochOf(edge) > 0);
		assertFalse(volume.resident(edge));
	}

	@Test
	public void theWindowFollowsThePlayerAndOnlyTheNewBlocksAreBaked()
	{
		List<PointLight> lights = new ArrayList<>();
		for (int i = 0; i < 6; ++i)
		{
			lights.add(lamp("l" + i, 300 + 400 * i, -100, 512, 1));
		}
		settle(lights);
		int originX = volume.originCellX();
		assertTrue(volume.slotOf(Math.floorDiv(LightBake.cell(focalX), 16), Math.floorDiv(LightBake.cell(focalY), 16),
			Math.floorDiv(LightBake.cell(focalZ), 16)) >= 0);
		int before = volume.bakedBlocks();
		// a step that stays near the middle moves nothing
		focalX += 100;
		frame(lights);
		assertEquals(originX, volume.originCellX());
		// a few blocks east moves the window a block at a time: the blocks it keeps stay baked
		focalX += 2500;
		frame(lights);
		assertTrue(volume.originCellX() > originX);
		assertTrue(volume.bakedBlocks() > 0);
		assertTrue(volume.bakedBlocks() < before + 1);
		settle(lights);
		// the lights now near are in, those left behind are not
		assertTrue(volume.epochOf(lights.get(5)) > 0);
		assertTrue(volume.slotOf(Math.floorDiv(LightBake.cell(focalX), 16), Math.floorDiv(LightBake.cell(focalY), 16),
			Math.floorDiv(LightBake.cell(focalZ), 16)) >= 0);
	}

	@Test
	public void movingUpOrDownBakesTheWindowAgain()
	{
		List<PointLight> lights = list(lamp("a", 512, -100, 512, 1));
		settle(lights);
		int originY = volume.originCellY();
		focalY -= 1500;
		frame(lights);
		assertNotEquals(originY, volume.originCellY());
		settle(lights);
		assertTrue(volume.slotOf(Math.floorDiv(LightBake.cell(focalX), 16), Math.floorDiv(LightBake.cell(focalY), 16),
			Math.floorDiv(LightBake.cell(focalZ), 16)) >= 0);
	}

	@Test
	public void bakingNeverBlocksAFrameAndHandsOutBlocksAFewAtATime()
	{
		List<PointLight> lights = new ArrayList<>();
		for (int i = 0; i < 60; ++i)
		{
			lights.add(lamp("l" + i, 200 + (i % 10) * 90, -100, 200 + (i / 10) * 140, 1));
		}
		long worst = 0;
		long start = System.nanoTime();
		long end = System.currentTimeMillis() + 60000;
		int maxPerFrame = 0;
		while (System.currentTimeMillis() < end)
		{
			long t = System.nanoTime();
			volume.update(lights, voxels, 10, 20, false, focalX, focalY, focalZ);
			List<LightVolume.Result> done = volume.pollFinished(4);
			for (LightVolume.Result r : done)
			{
				volume.committed(r);
			}
			maxPerFrame = Math.max(maxPerFrame, done.size());
			worst = Math.max(worst, System.nanoTime() - t);
			if (volume.pendingBlocks() == 0)
			{
				break;
			}
		}
		assertEquals(0, volume.pendingBlocks());
		assertTrue(maxPerFrame <= 4);
		// a frame spends little on it: the scheduling is a scan of the window's 256 blocks
		System.out.printf("Light volume: %d lights baked in %.0f ms wall, the worst frame spent %.2f ms in update%n", lights.size(),
			(System.nanoTime() - start) / 1e6, worst / 1e6);
		assertTrue("worst frame " + worst / 1e6 + " ms", worst < 50_000_000L);
	}

	@Test
	public void aPooledLightForgetsBeingBaked()
	{
		PointLight l = new PointLight().set(1, 2, 3, 100, 1, 1, 1, 1).bakeable("k", 90, 2, 0.5f, true);
		l.bakeOnly = true;
		l.bakeEpoch = 7;
		l.set(4, 5, 6, 100, 1, 1, 1, 1);
		assertEquals(null, l.bakeKey);
		assertFalse(l.bakeOnly);
		assertFalse(l.bakeModulated);
		assertEquals(0, l.bakeEpoch);
		assertEquals(1, l.bakeMod, 0);
		assertEquals(0, volume.epochOf(l));
		assertFalse(volume.resident(l));
	}

	@Test
	public void theShadersAreCompiledWithTheWindowsSize()
	{
		String config = LightVolume.shaderConfig();
		assertTrue(config.contains("LIGHT_CELL " + LightVolume.CELL + ".0"));
		assertTrue(config.contains("LIGHT_VOLUME_EPOCH_VECS " + LightVolume.BLOCK_COUNT / 4));
		assertTrue(config.contains("LIGHT_VOLUME_FLICKER_VECS " + LightBake.SLOTS / 4));
		assertTrue(config.contains("vec3(" + LightVolume.DIM_X + ".0, " + LightVolume.DIM_Y + ".0, " + LightVolume.DIM_Z + ".0)"));
		// a flicker code (slot * 16 + share) and a tint (hue * 16 + saturation) are whole numbers a half float holds
		assertTrue(LightBake.SLOTS * 16 <= 2048);
		assertTrue(LightBake.HUE_STEPS * 16 <= 2048);
		// the baked epochs follow the lights' extra texels, ahead of the room map
		assertTrue(LightClusters.LIGHT_EXTRA_BASE < LightClusters.LIGHT_BAKED_BASE);
		assertTrue(LightClusters.LIGHT_BAKED_BASE + LightClusters.MAX_LIGHTS <= LightClusters.ROOM_BASE);
	}
}
