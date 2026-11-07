package com.midonarhel.lumina.weather;

import org.junit.Test;
import static org.junit.Assert.assertEquals;

public class RainImpactTest
{
	@Test
	public void capturedRainStillSubmitsImpactsWhenGroundHasNotBecomeWet()
	{
		// rain-splashes-20261107: rainEffective=.214787185, wetness and splash-box wet peak both 0.
		// This is the minimum rainShown at that location; the old wetness multiplication submitted zero.
		assertEquals(945, Weather.rainImpactCount(0.214787185f));
	}

	@Test
	public void dryWeatherDoesNotSubmitRainImpacts()
	{
		assertEquals(0, Weather.rainImpactCount(0));
	}
}
