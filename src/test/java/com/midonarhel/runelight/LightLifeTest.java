package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class LightLifeTest
{
	private static final float EPS = 1e-4f;

	private static LightDefinition def()
	{
		LightDefinition d = new LightDefinition();
		d.fadeInDuration = 0;
		d.fadeOutDuration = 0;
		return d;
	}

	// run the life for this long in 10 ms steps
	private static void run(LightLife life, boolean parentExists, float seconds)
	{
		for (int i = 0; i < Math.round(seconds * 100); ++i)
		{
			life.update(parentExists, 0.01f);
		}
	}

	@Test
	public void plainLightIsThereAtOnceAndStays()
	{
		LightLife life = new LightLife(def(), true);
		life.update(true, 0);
		assertTrue(life.visible());
		assertEquals(1, life.brightness(), EPS);
		run(life, true, 100);
		assertTrue(life.visible());
		assertFalse(life.over(false));
	}

	@Test
	public void spawnDelayHoldsTheLightBackAndFadeInEasesIt()
	{
		LightDefinition d = def();
		d.spawnDelay = 500;
		d.fadeInDuration = 200;
		LightLife life = new LightLife(d, true);
		run(life, true, 0.3f);
		assertFalse(life.visible());
		run(life, true, 0.3f);
		assertTrue(life.visible());
		// 0.1 s into a 0.2 s fade, from the delay at 0.5 s
		assertEquals(0.5f, life.brightness(), 0.06f);
		run(life, true, 0.3f);
		assertEquals(1, life.brightness(), EPS);
	}

	@Test
	public void aLightOfTheSceneDoesNotFadeIn()
	{
		LightDefinition d = def();
		d.fadeInDuration = 500;
		LightLife life = new LightLife(d, false);
		life.update(true, 0);
		assertEquals(1, life.brightness(), EPS);
	}

	@Test
	public void lightWaitsOutTheDespawnDelayAndFadesAfterItsParentGoes()
	{
		LightDefinition d = def();
		d.despawnDelay = 400;
		d.fadeOutDuration = 200;
		LightLife life = new LightLife(d, false);
		run(life, true, 1);
		run(life, false, 0.2f);
		assertTrue(life.visible());
		// the delay is 0.4 s, and the fade takes the last 0.2 s of it
		assertEquals(1, life.brightness(), 0.06f);
		run(life, false, 0.15f);
		assertEquals(0.25f, life.brightness(), 0.1f);
		run(life, false, 0.2f);
		assertFalse(life.visible());
		assertTrue(life.over(true));
	}

	@Test
	public void despawnWithParentGoesAtOnce()
	{
		LightDefinition d = def();
		d.despawnDelay = 5000;
		d.despawnWithParent = true;
		LightLife life = new LightLife(d, false);
		run(life, true, 1);
		life.update(false, 0.01f);
		assertFalse(life.visible());
	}

	@Test
	public void fixedDespawnTimeLastsWhateverTheParentDoes()
	{
		LightDefinition d = def();
		d.spawnDelay = 100;
		d.despawnDelay = 400;
		d.fixedDespawnTime = true;
		LightLife life = new LightLife(d, false);
		run(life, true, 0.3f);
		assertTrue(life.visible());
		// the parent is still there at 0.5 s, and the light is gone
		run(life, true, 0.3f);
		assertFalse(life.visible());
		assertTrue(life.over(false));
	}

	@Test
	public void lightOfAnAnimationShowsWhileItPlaysAndStartsOverEachTime()
	{
		LightDefinition d = def();
		d.animationIds = new int[]{7};
		d.despawnDelay = 100;
		LightLife life = new LightLife(d, false);
		run(life, false, 1);
		assertFalse(life.visible());
		assertFalse(life.over(false));
		run(life, true, 0.5f);
		assertTrue(life.visible());
		run(life, false, 0.3f);
		assertFalse(life.visible());
		// never over: it comes back
		assertFalse(life.over(false));
		life.update(true, 0.01f);
		assertTrue(life.visible());
	}

	@Test
	public void hidingFadesTheLightOutAndBackIn()
	{
		LightLife life = new LightLife(def(), false);
		run(life, true, 1);
		life.hide(true, false);
		assertEquals(1, life.brightness(), EPS);
		assertTrue(life.visible());
		run(life, true, LightLife.HIDING_FADE / 2);
		assertEquals(0.5f, life.brightness(), 0.1f);
		run(life, true, LightLife.HIDING_FADE);
		assertFalse(life.visible());
		assertEquals(0, life.brightness(), EPS);
		life.hide(false, false);
		run(life, true, LightLife.HIDING_FADE / 2);
		assertTrue(life.visible());
		assertEquals(0.5f, life.brightness(), 0.1f);
	}

	@Test
	public void changingPlanesHidesWithoutAFade()
	{
		LightLife life = new LightLife(def(), false);
		run(life, true, 1);
		life.hide(true, true);
		assertFalse(life.visible());
		assertEquals(0, life.brightness(), EPS);
	}
}
