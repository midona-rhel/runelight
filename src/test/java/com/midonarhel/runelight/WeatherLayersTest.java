package com.midonarhel.runelight;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class WeatherLayersTest
{
	@Test
	public void layersReachWhatTheCameraSees()
	{
		for (float out = 100; out < 40000; out *= 1.3f)
		{
			int last = Weather.lastLayer(out);
			// the last layer is at full strength out to the farthest particle
			assertTrue(last == Weather.MAX_LAYERS - 1 || Weather.layerReach(last) * (1 - Weather.LAYER_CROSSFADE) >= out);
			// and not one more than needed
			assertTrue(last == 0 || Weather.layerReach(last - 1) * (1 - Weather.LAYER_CROSSFADE) < out);
		}
		assertEquals(Weather.MAX_LAYERS - 1, Weather.lastLayer(1e9f));
	}

	@Test
	public void bandsDouble()
	{
		for (int k = 1; k < Weather.MAX_LAYERS; ++k)
		{
			assertEquals(Weather.layerReach(k - 1) * 2, Weather.layerReach(k), 0);
			assertTrue(Weather.layerHeight(2560, k) >= Weather.layerHeight(2560, k - 1));
		}
		assertEquals(2560, Weather.layerHeight(2560, 0), 0);
	}
}
